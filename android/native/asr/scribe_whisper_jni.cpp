// JNI wrapper for whisper.cpp — dev.smantics.scribe.asr.NativeWhisper.
// Contract: android/docs/jni-contract.md. Change that file first, then both sides.
//
//   create(modelPath, nThreads)                              -> handle (0 on failure)
//   transcribe(handle, pcm16k, language, beamSize, prompt)   -> transcript ("" if none)
//   cancel(handle)                                           -> abort the running decode
//   destroy(handle)
//   systemInfo()                                             -> ggml's CPU feature line
//
// `transcribe` blocks for as long as the decode takes and MUST be called off the main
// thread. One handle serves one decode at a time; the Kotlin side owns that discipline.
//
// Two differences from the equivalent wrapper in the VisEar project, both because this is
// a keyboard rather than a background listener:
//
//   * language, beam size and an initial prompt are parameters rather than constants —
//     Scribe exposes all three as settings, and the prompt is how the user's custom
//     dictionary biases recognition itself rather than only fixing spelling afterwards;
//   * decode is abortable, because a user who dismisses the keyboard should not be made
//     to wait for a transcript they are no longer going to receive.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "jni_util.h"
#include "whisper.h"

namespace {

constexpr const char* kTag = "scribewhisper";

/**
 * A silence long enough to be a paragraph break, in centiseconds — whisper's timestamp
 * unit. Around three quarters of a second is a breath; past one and a half the speaker has
 * finished a thought.
 */
constexpr int64_t kPauseCentiseconds = 150;

struct WhisperHandle {
  whisper_context* ctx = nullptr;
  int n_threads = 4;
  std::atomic<bool> abort{false};
};

bool abort_requested(void* user_data) {
  auto* h = static_cast<WhisperHandle*>(user_data);
  return h != nullptr && h->abort.load(std::memory_order_relaxed);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_smantics_scribe_asr_NativeWhisper_create(
    JNIEnv* env, jobject /*thiz*/, jstring model_path, jint n_threads) {
  const std::string path = scribe::to_string(env, model_path);
  if (path.empty()) {
    SCRIBE_LOGE(kTag, "create: empty model path");
    return 0;
  }

  whisper_context_params cparams = whisper_context_default_params();
  cparams.use_gpu = false;  // CPU only. See docs/jni-contract.md.
  whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
  if (ctx == nullptr) {
    SCRIBE_LOGE(kTag, "create: failed to load model at %s", path.c_str());
    return 0;
  }

  auto* h = new WhisperHandle();
  h->ctx = ctx;
  h->n_threads = std::max(1, static_cast<int>(n_threads));
  return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL Java_dev_smantics_scribe_asr_NativeWhisper_transcribe(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray pcm16k, jstring language,
    jint beam_size, jstring initial_prompt) {
  auto* h = reinterpret_cast<WhisperHandle*>(handle);
  if (h == nullptr || h->ctx == nullptr || pcm16k == nullptr) {
    return scribe::make_jstring(env, "");
  }
  const jsize n = env->GetArrayLength(pcm16k);
  if (n <= 0) return scribe::make_jstring(env, "");

  std::vector<float> samples(static_cast<size_t>(n));
  env->GetFloatArrayRegion(pcm16k, 0, n, samples.data());

  const std::string lang = scribe::to_string(env, language, "en");
  const std::string prompt = scribe::to_string(env, initial_prompt);

  h->abort.store(false, std::memory_order_relaxed);

  const int beam = std::max(1, static_cast<int>(beam_size));
  whisper_full_params params = whisper_full_default_params(
      beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
  if (beam > 1) params.beam_search.beam_size = beam;

  params.n_threads = h->n_threads;
  params.language = lang.c_str();
  params.translate = false;
  // Each press of the key is an independent utterance; carrying decoder context across
  // them lets one bad transcript poison the next.
  params.no_context = true;
  // Timestamps are needed to find the pauses. They are not shown to anyone — they decide
  // where a paragraph break goes, so a speaker who stops for a breath gets a new
  // paragraph instead of two minutes arriving as one block.
  params.no_timestamps = false;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_special = false;
  params.print_timestamps = false;
  params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
  params.abort_callback = abort_requested;
  params.abort_callback_user_data = h;

  if (whisper_full(h->ctx, params, samples.data(), n) != 0) {
    if (!h->abort.load(std::memory_order_relaxed)) {
      SCRIBE_LOGE(kTag, "transcribe: whisper_full failed");
    }
    return scribe::make_jstring(env, "");
  }
  if (h->abort.load(std::memory_order_relaxed)) {
    return scribe::make_jstring(env, "");
  }

  // Segments are joined with a marker wherever the speaker paused for longer than
  // kPauseCentiseconds. The marker is U+001F (unit separator) — a control character no
  // transcript can contain, so the Kotlin side can find the pauses unambiguously and
  // strip any it does not use.
  std::string text;
  const int n_segments = whisper_full_n_segments(h->ctx);
  int64_t previous_end = -1;
  for (int s = 0; s < n_segments; ++s) {
    const char* seg = whisper_full_get_segment_text(h->ctx, s);
    if (seg == nullptr) continue;
    const int64_t t0 = whisper_full_get_segment_t0(h->ctx, s);
    if (previous_end >= 0 && (t0 - previous_end) >= kPauseCentiseconds) {
      text += "\x1F";
    }
    text += seg;
    previous_end = whisper_full_get_segment_t1(h->ctx, s);
  }
  scribe::trim(text);
  return scribe::make_jstring(env, text);
}

JNIEXPORT void JNICALL Java_dev_smantics_scribe_asr_NativeWhisper_cancel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* h = reinterpret_cast<WhisperHandle*>(handle);
  if (h != nullptr) h->abort.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL Java_dev_smantics_scribe_asr_NativeWhisper_destroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* h = reinterpret_cast<WhisperHandle*>(handle);
  if (h == nullptr) return;
  if (h->ctx != nullptr) whisper_free(h->ctx);
  delete h;
}

JNIEXPORT jstring JNICALL Java_dev_smantics_scribe_asr_NativeWhisper_systemInfo(
    JNIEnv* env, jobject /*thiz*/) {
  const char* info = whisper_print_system_info();
  return scribe::make_jstring(env, info != nullptr ? info : "");
}

}  // extern "C"
