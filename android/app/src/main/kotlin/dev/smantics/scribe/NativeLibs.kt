package dev.smantics.scribe

import android.util.Log

/**
 * Whether each native library actually loaded.
 *
 * A `.so` can legitimately be missing — a build that skipped the cross-compile, an APK
 * assembled without the polish library, a device whose ABI we did not ship. Every caller
 * checks the flag before touching an `external fun`, so the app degrades to a clear
 * message instead of dying on `UnsatisfiedLinkError` somewhere in the middle of a
 * dictation. This is VisEar's `CapabilityFlags` pattern, and it earns its keep.
 */
object NativeLibs {
    private const val TAG = "ScribeNative"

    /** Speech recognition. Without it Scribe cannot dictate at all. */
    val whisperAvailable: Boolean = tryLoad("scribewhisper")

    /** Optional LLM polish. Without it Clean mode runs rules-only, which is complete. */
    val llmAvailable: Boolean = tryLoad("scribellm")

    private fun tryLoad(name: String): Boolean = try {
        System.loadLibrary(name)
        true
    } catch (t: Throwable) {
        // UnsatisfiedLinkError, SecurityException, or a 4 KB-aligned library on a 16 KB
        // page device — all of them mean the same thing to the rest of the app.
        Log.w(TAG, "native library lib$name.so did not load: ${t.message}")
        false
    }
}
