// scribe_bench — host-side accuracy and latency harness for the ASR path.
//
//   scribe_bench --model ggml-base.en-q5_1.bin corpus/*.wav
//
// For each 16 kHz mono WAV it runs the real whisper.cpp decode, times it, and — when a
// sibling .txt reference exists — reports word error rate against it. Prints a per-clip
// table and a summary line.
//
// This builds for the machine it runs on. On the aarch64 build host the WER numbers are
// meaningful (the model and the decode are identical to the phone's) but the timings are
// NOT: a workstation CPU is not a Snapdragon. Latency on device is OWNER-VERIFY.

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <numeric>
#include <sstream>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct Wav {
  std::vector<float> samples;  // mono, normalised to [-1, 1]
  int sample_rate = 0;
};

uint32_t read_u32(const unsigned char* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint16_t read_u16(const unsigned char* p) {
  return static_cast<uint16_t>(p[0]) | static_cast<uint16_t>(p[1] << 8);
}

/** Minimal RIFF/WAVE reader: PCM16 only, mono or stereo (stereo is downmixed). */
bool load_wav(const std::string& path, Wav* out, std::string* err) {
  std::ifstream f(path, std::ios::binary);
  if (!f) {
    *err = "cannot open";
    return false;
  }
  std::vector<unsigned char> buf((std::istreambuf_iterator<char>(f)),
                                 std::istreambuf_iterator<char>());
  if (buf.size() < 44 || std::memcmp(buf.data(), "RIFF", 4) != 0 ||
      std::memcmp(buf.data() + 8, "WAVE", 4) != 0) {
    *err = "not a RIFF/WAVE file";
    return false;
  }

  size_t pos = 12;
  uint16_t channels = 0, bits = 0, format = 0;
  const unsigned char* data = nullptr;
  uint32_t data_len = 0;
  while (pos + 8 <= buf.size()) {
    const char* id = reinterpret_cast<const char*>(buf.data() + pos);
    const uint32_t len = read_u32(buf.data() + pos + 4);
    const size_t body = pos + 8;
    if (body + len > buf.size()) break;
    if (std::memcmp(id, "fmt ", 4) == 0 && len >= 16) {
      format = read_u16(buf.data() + body);
      channels = read_u16(buf.data() + body + 2);
      out->sample_rate = static_cast<int>(read_u32(buf.data() + body + 4));
      bits = read_u16(buf.data() + body + 14);
    } else if (std::memcmp(id, "data", 4) == 0) {
      data = buf.data() + body;
      data_len = len;
    }
    pos = body + len + (len & 1);  // chunks are word-aligned
  }

  if (format != 1 || bits != 16 || channels == 0 || data == nullptr) {
    *err = "expected uncompressed PCM16";
    return false;
  }

  const size_t frames = data_len / (2u * channels);
  out->samples.resize(frames);
  for (size_t i = 0; i < frames; ++i) {
    int32_t acc = 0;
    for (uint16_t c = 0; c < channels; ++c) {
      const size_t k = (i * channels + c) * 2;
      acc += static_cast<int16_t>(read_u16(data + k));
    }
    out->samples[i] = static_cast<float>(acc) / (channels * 32768.0f);
  }
  return true;
}

/** Lowercase, strip punctuation, split on whitespace — the usual WER normalisation. */
std::vector<std::string> normalise(const std::string& text) {
  std::string flat;
  flat.reserve(text.size());
  for (unsigned char c : text) {
    if (std::isalnum(c) || c == '\'' || std::isspace(c)) {
      flat += static_cast<char>(std::tolower(c));
    } else {
      flat += ' ';
    }
  }
  std::vector<std::string> words;
  std::istringstream in(flat);
  std::string w;
  while (in >> w) words.push_back(w);
  return words;
}

/** Levenshtein distance over words. */
size_t edit_distance(const std::vector<std::string>& a, const std::vector<std::string>& b) {
  std::vector<size_t> prev(b.size() + 1), cur(b.size() + 1);
  std::iota(prev.begin(), prev.end(), 0u);
  for (size_t i = 1; i <= a.size(); ++i) {
    cur[0] = i;
    for (size_t j = 1; j <= b.size(); ++j) {
      const size_t cost = (a[i - 1] == b[j - 1]) ? 0u : 1u;
      cur[j] = std::min({prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost});
    }
    prev = cur;
  }
  return prev[b.size()];
}

std::string read_file(const std::string& path) {
  std::ifstream f(path);
  if (!f) return "";
  std::stringstream ss;
  ss << f.rdbuf();
  return ss.str();
}

std::string reference_path(const std::string& wav) {
  const size_t dot = wav.find_last_of('.');
  return (dot == std::string::npos ? wav : wav.substr(0, dot)) + ".txt";
}

}  // namespace

int main(int argc, char** argv) {
  std::string model;
  std::string language = "en";
  int threads = 4;
  std::vector<std::string> wavs;

  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--model" && i + 1 < argc) {
      model = argv[++i];
    } else if (arg == "--language" && i + 1 < argc) {
      language = argv[++i];
    } else if (arg == "--threads" && i + 1 < argc) {
      threads = std::atoi(argv[++i]);
    } else if (arg == "--help" || arg == "-h") {
      std::printf("usage: scribe_bench --model MODEL.bin [--language en] [--threads N] FILE.wav...\n");
      return 0;
    } else {
      wavs.push_back(arg);
    }
  }

  if (model.empty() || wavs.empty()) {
    std::fprintf(stderr, "scribe_bench: --model and at least one WAV are required\n");
    return 2;
  }

  whisper_context_params cparams = whisper_context_default_params();
  cparams.use_gpu = false;
  whisper_context* ctx = whisper_init_from_file_with_params(model.c_str(), cparams);
  if (ctx == nullptr) {
    std::fprintf(stderr, "scribe_bench: failed to load model %s\n", model.c_str());
    return 1;
  }

  std::printf("model    %s\n", model.c_str());
  std::printf("threads  %d\n", threads);
  std::printf("%-28s %8s %8s %7s %7s  %s\n", "clip", "audio_s", "decode_s", "rtf", "wer", "transcript");

  double total_audio = 0.0, total_decode = 0.0;
  size_t total_errors = 0, total_words = 0;
  int failures = 0;

  for (const std::string& path : wavs) {
    Wav wav;
    std::string err;
    if (!load_wav(path, &wav, &err)) {
      std::fprintf(stderr, "%-28s SKIPPED (%s)\n", path.c_str(), err.c_str());
      ++failures;
      continue;
    }
    if (wav.sample_rate != WHISPER_SAMPLE_RATE) {
      std::fprintf(stderr, "%-28s SKIPPED (need %d Hz, got %d)\n", path.c_str(),
                   WHISPER_SAMPLE_RATE, wav.sample_rate);
      ++failures;
      continue;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = language.c_str();
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    const auto t0 = std::chrono::steady_clock::now();
    const int rc = whisper_full(ctx, params, wav.samples.data(),
                                static_cast<int>(wav.samples.size()));
    const auto t1 = std::chrono::steady_clock::now();
    if (rc != 0) {
      std::fprintf(stderr, "%-28s FAILED (whisper_full rc=%d)\n", path.c_str(), rc);
      ++failures;
      continue;
    }

    std::string text;
    for (int s = 0; s < whisper_full_n_segments(ctx); ++s) {
      const char* seg = whisper_full_get_segment_text(ctx, s);
      if (seg != nullptr) text += seg;
    }

    const double audio_s = static_cast<double>(wav.samples.size()) / WHISPER_SAMPLE_RATE;
    const double decode_s = std::chrono::duration<double>(t1 - t0).count();
    total_audio += audio_s;
    total_decode += decode_s;

    const std::string ref = read_file(reference_path(path));
    char wer_cell[16] = "     -";
    if (!ref.empty()) {
      const auto ref_words = normalise(ref);
      const auto hyp_words = normalise(text);
      const size_t errors = edit_distance(ref_words, hyp_words);
      total_errors += errors;
      total_words += ref_words.size();
      if (!ref_words.empty()) {
        std::snprintf(wer_cell, sizeof(wer_cell), "%6.1f%%",
                      100.0 * static_cast<double>(errors) / ref_words.size());
      }
    }

    const size_t slash = path.find_last_of('/');
    const std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    std::printf("%-28s %8.2f %8.2f %7.3f %7s  %s\n", name.c_str(), audio_s, decode_s,
                audio_s > 0 ? decode_s / audio_s : 0.0, wer_cell, text.c_str());
  }

  whisper_free(ctx);

  std::printf("\ntotal audio %.2fs, total decode %.2fs, rtf %.3f\n", total_audio, total_decode,
              total_audio > 0 ? total_decode / total_audio : 0.0);
  if (total_words > 0) {
    std::printf("corpus WER  %.2f%% (%zu errors over %zu reference words)\n",
                100.0 * static_cast<double>(total_errors) / total_words, total_errors,
                total_words);
  } else {
    std::printf("corpus WER  not measured (no .txt references alongside the WAVs)\n");
  }
  return failures == 0 ? 0 : 1;
}
