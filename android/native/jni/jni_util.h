// Shared JNI helpers for Scribe's native libraries.
//
// The UTF-8 decoder here is not optional politeness. `NewStringUTF` expects Java's
// *modified* UTF-8, and aborts the process under CheckJNI when handed a real 4-byte
// sequence. Both whisper.cpp and llama.cpp emit byte-fallback tokens that can produce
// exactly that, so every string crossing into Java goes through `make_jstring`.
// This bug was already paid for once in the VisEar project; it is not re-litigated here.

#ifndef SCRIBE_JNI_UTIL_H
#define SCRIBE_JNI_UTIL_H

#include <jni.h>

#include <cctype>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define SCRIBE_LOGE(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#define SCRIBE_LOGI(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#else
#define SCRIBE_LOGE(tag, ...)          \
  do {                                 \
    std::fprintf(stderr, "[%s] ", tag); \
    std::fprintf(stderr, __VA_ARGS__); \
    std::fprintf(stderr, "\n");        \
  } while (0)
#define SCRIBE_LOGI(tag, ...) SCRIBE_LOGE(tag, __VA_ARGS__)
#endif

namespace scribe {

/** Decode UTF-8 to UTF-16, skipping malformed bytes rather than aborting on them. */
inline std::vector<jchar> utf8_to_utf16(const std::string& s) {
  std::vector<jchar> out;
  out.reserve(s.size());
  size_t i = 0;
  const auto byte = [&](size_t k) { return static_cast<uint8_t>(s[k]); };
  while (i < s.size()) {
    const uint8_t b0 = byte(i);
    uint32_t cp = 0;
    size_t len = 0;
    if (b0 < 0x80) {
      cp = b0;
      len = 1;
    } else if ((b0 & 0xE0) == 0xC0) {
      cp = b0 & 0x1F;
      len = 2;
    } else if ((b0 & 0xF0) == 0xE0) {
      cp = b0 & 0x0F;
      len = 3;
    } else if ((b0 & 0xF8) == 0xF0) {
      cp = b0 & 0x07;
      len = 4;
    } else {
      ++i;  // stray continuation or invalid lead byte
      continue;
    }
    if (i + len > s.size()) break;
    bool ok = true;
    for (size_t k = 1; k < len; ++k) {
      if ((byte(i + k) & 0xC0) != 0x80) {
        ok = false;
        break;
      }
      cp = (cp << 6) | (byte(i + k) & 0x3F);
    }
    if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      ++i;
      continue;
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
    } else {
      out.push_back(static_cast<jchar>(cp));
    }
    i += len;
  }
  return out;
}

inline jstring make_jstring(JNIEnv* env, const std::string& utf8) {
  const std::vector<jchar> u16 = utf8_to_utf16(utf8);
  return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

/** Copy a Java string into a std::string, or return the fallback when null. */
inline std::string to_string(JNIEnv* env, jstring s, const char* fallback = "") {
  if (s == nullptr) return fallback;
  const char* chars = env->GetStringUTFChars(s, nullptr);
  if (chars == nullptr) return fallback;
  std::string out(chars);
  env->ReleaseStringUTFChars(s, chars);
  return out;
}

inline void trim(std::string& s) {
  const auto is_space = [](unsigned char c) { return std::isspace(c) != 0; };
  while (!s.empty() && is_space(static_cast<unsigned char>(s.back()))) s.pop_back();
  size_t k = 0;
  while (k < s.size() && is_space(static_cast<unsigned char>(s[k]))) ++k;
  s.erase(0, k);
}

}  // namespace scribe

#endif  // SCRIBE_JNI_UTIL_H
