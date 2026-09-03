// JNI wrapper for llama.cpp — dev.smantics.scribe.llm.NativeLlm.
// Contract: android/docs/jni-contract.md. Change that file first, then both sides.
//
//   create(modelPath, nThreads, contextTokens)                  -> handle (0 on failure)
//   generate(handle, systemPrompt, userText, maxTokens, temp)   -> completion ("" on failure)
//   cancel(handle)                                              -> abort the running decode
//   destroy(handle)
//
// This exists for exactly one job: Clean mode's optional Polish stage, where a small local
// model tidies text the rule pipeline has already processed. It is deliberately a plain
// single-turn completion with no history, no streaming and no tool use — a dictation
// keyboard has no use for a conversation, and every feature not built here is one that
// cannot go wrong inside somebody's message.
//
// Nothing here decides whether the model's output is acceptable. That judgement — the
// diff cap, the length bounds, the timeout, the fallback to the rules-only text — lives in
// Kotlin, in the core module, where it is testable without a device or a model.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "jni_util.h"
#include "llama.h"

namespace {

constexpr const char* kTag = "scribellm";

struct LlmHandle {
  llama_model* model = nullptr;
  llama_context* ctx = nullptr;
  const llama_vocab* vocab = nullptr;
  int n_threads = 4;
  std::atomic<bool> abort{false};
};

bool abort_requested(void* user_data) {
  auto* h = static_cast<LlmHandle*>(user_data);
  return h != nullptr && h->abort.load(std::memory_order_relaxed);
}

std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text,
                                  bool add_special) {
  // A negative return is the required buffer size; ask once, then fill.
  const int32_t needed = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                         nullptr, 0, add_special, true);
  if (needed <= 0) return {};
  std::vector<llama_token> tokens(static_cast<size_t>(needed));
  const int32_t n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                   tokens.data(), needed, add_special, true);
  if (n < 0) return {};
  tokens.resize(static_cast<size_t>(n));
  return tokens;
}

std::string piece(const llama_vocab* vocab, llama_token token) {
  char buf[256];
  const int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
  if (n <= 0) return "";
  return std::string(buf, static_cast<size_t>(n));
}

/**
 * Render the model's own chat template around the two messages.
 *
 * If the GGUF carries no template, fall back to a plain concatenation. A 270M model that
 * has lost its turn markers produces noise, but noise is what the Kotlin guardrails are
 * there to reject — it must not be a crash.
 */
std::string build_prompt(llama_model* model, const std::string& system,
                         const std::string& user) {
  const char* tmpl = llama_model_chat_template(model, nullptr);
  if (tmpl == nullptr) return system + "\n\n" + user + "\n";

  const llama_chat_message messages[2] = {
      {"system", system.c_str()},
      {"user", user.c_str()},
  };
  const size_t n_msg = system.empty() ? 1 : 2;
  const llama_chat_message* chat = system.empty() ? &messages[1] : &messages[0];

  std::vector<char> buf(2 * (system.size() + user.size()) + 512);
  int32_t n = llama_chat_apply_template(tmpl, chat, n_msg, /*add_ass=*/true, buf.data(),
                                        static_cast<int32_t>(buf.size()));
  if (n > static_cast<int32_t>(buf.size())) {
    buf.resize(static_cast<size_t>(n));
    n = llama_chat_apply_template(tmpl, chat, n_msg, true, buf.data(),
                                  static_cast<int32_t>(buf.size()));
  }
  if (n <= 0) return system + "\n\n" + user + "\n";
  return std::string(buf.data(), static_cast<size_t>(n));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_smantics_scribe_llm_NativeLlm_create(
    JNIEnv* env, jobject /*thiz*/, jstring model_path, jint n_threads, jint context_tokens) {
  const std::string path = scribe::to_string(env, model_path);
  if (path.empty()) {
    SCRIBE_LOGE(kTag, "create: empty model path");
    return 0;
  }

  llama_backend_init();
  llama_log_set([](ggml_log_level level, const char* text, void* /*ud*/) {
    if (level >= GGML_LOG_LEVEL_ERROR && text != nullptr) SCRIBE_LOGE(kTag, "%s", text);
  }, nullptr);

  llama_model_params mparams = llama_model_default_params();
  mparams.n_gpu_layers = 0;  // CPU only, like the ASR path.
  llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
  if (model == nullptr) {
    SCRIBE_LOGE(kTag, "create: failed to load model at %s", path.c_str());
    return 0;
  }

  auto* h = new LlmHandle();
  h->model = model;
  h->n_threads = std::max(1, static_cast<int>(n_threads));

  const uint32_t n_ctx = static_cast<uint32_t>(std::max(256, static_cast<int>(context_tokens)));
  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = n_ctx;
  cparams.n_batch = n_ctx;  // the whole prompt is submitted in one batch
  cparams.n_threads = h->n_threads;
  cparams.n_threads_batch = h->n_threads;
  cparams.abort_callback = abort_requested;
  cparams.abort_callback_data = h;

  h->ctx = llama_init_from_model(model, cparams);
  if (h->ctx == nullptr) {
    SCRIBE_LOGE(kTag, "create: failed to create context");
    llama_model_free(model);
    delete h;
    return 0;
  }
  h->vocab = llama_model_get_vocab(model);
  return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL Java_dev_smantics_scribe_llm_NativeLlm_generate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring system_prompt, jstring user_text,
    jint max_tokens, jfloat temperature) {
  auto* h = reinterpret_cast<LlmHandle*>(handle);
  if (h == nullptr || h->ctx == nullptr) return scribe::make_jstring(env, "");

  const std::string system = scribe::to_string(env, system_prompt);
  const std::string user = scribe::to_string(env, user_text);
  if (user.empty()) return scribe::make_jstring(env, "");

  h->abort.store(false, std::memory_order_relaxed);
  // Every call is independent; a leftover KV cache would let one utterance bleed into
  // the next one's polish.
  llama_memory_clear(llama_get_memory(h->ctx), true);

  const std::string prompt = build_prompt(h->model, system, user);
  std::vector<llama_token> tokens = tokenize(h->vocab, prompt, /*add_special=*/true);
  if (tokens.empty()) {
    SCRIBE_LOGE(kTag, "generate: prompt tokenised to nothing");
    return scribe::make_jstring(env, "");
  }

  const int n_ctx = static_cast<int>(llama_n_ctx(h->ctx));
  const int budget = std::max(1, static_cast<int>(max_tokens));
  if (static_cast<int>(tokens.size()) + budget > n_ctx) {
    SCRIBE_LOGE(kTag, "generate: prompt of %zu tokens does not fit in %d", tokens.size(), n_ctx);
    return scribe::make_jstring(env, "");
  }

  llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
  if (llama_decode(h->ctx, batch) != 0) {
    SCRIBE_LOGE(kTag, "generate: prompt decode failed");
    return scribe::make_jstring(env, "");
  }

  llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
  sparams.no_perf = true;
  llama_sampler* smpl = llama_sampler_chain_init(sparams);
  if (temperature <= 0.0f) {
    // The default. Text cleanup has a right answer; sampling for variety is not a
    // feature here, it is a way to get a different wrong result each time.
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
  } else {
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
  }

  std::string out;
  llama_token token = 0;
  for (int i = 0; i < budget; ++i) {
    if (h->abort.load(std::memory_order_relaxed)) break;
    token = llama_sampler_sample(smpl, h->ctx, -1);
    if (llama_vocab_is_eog(h->vocab, token)) break;
    out += piece(h->vocab, token);
    llama_batch next = llama_batch_get_one(&token, 1);
    if (llama_decode(h->ctx, next) != 0) break;
  }

  llama_sampler_free(smpl);
  if (h->abort.load(std::memory_order_relaxed)) return scribe::make_jstring(env, "");
  scribe::trim(out);
  return scribe::make_jstring(env, out);
}

JNIEXPORT void JNICALL Java_dev_smantics_scribe_llm_NativeLlm_cancel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* h = reinterpret_cast<LlmHandle*>(handle);
  if (h != nullptr) h->abort.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL Java_dev_smantics_scribe_llm_NativeLlm_destroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* h = reinterpret_cast<LlmHandle*>(handle);
  if (h == nullptr) return;
  if (h->ctx != nullptr) llama_free(h->ctx);
  if (h->model != nullptr) llama_model_free(h->model);
  delete h;
}

}  // extern "C"
