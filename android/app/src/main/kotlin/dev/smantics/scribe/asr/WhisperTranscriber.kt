package dev.smantics.scribe.asr

import android.util.Log
import dev.smantics.scribe.NativeLibs
import dev.smantics.scribe.core.dictation.Transcriber
import dev.smantics.scribe.core.dictation.TranscriberProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin side of libscribewhisper.so.
 *
 * Contract lives in `android/docs/jni-contract.md`; both sides must match it exactly.
 * `transcribe` blocks for the length of the decode and is only ever called from the
 * dictation worker.
 */
object NativeWhisper {
    external fun create(modelPath: String, nThreads: Int): Long
    external fun transcribe(
        handle: Long,
        pcm16k: FloatArray,
        language: String,
        beamSize: Int,
        initialPrompt: String?,
    ): String
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun systemInfo(): String
}

/**
 * One loaded ggml model, owned for the life of this object.
 *
 * The native handle is an opaque pointer with a single owner. `close` nulls it before
 * freeing so a racing `cancel` cannot reach a freed context.
 */
class WhisperTranscriber private constructor(
    private val handle: AtomicLong,
    private val modelName: String,
    /** The file this was loaded from, so a changed setting can be noticed. */
    val source: File,
    threads: Int,
) : Transcriber, AutoCloseable {

    override val backendLabel: String = "$modelName · CPU ($threads threads)"

    override fun transcribe(
        pcm16k: FloatArray,
        language: String,
        beamSize: Int,
        initialPrompt: String?,
    ): String {
        val h = handle.get()
        check(h != 0L) { "transcriber has been closed" }
        return NativeWhisper.transcribe(h, pcm16k, language, beamSize, initialPrompt)
    }

    /** Abort an in-flight decode. Safe to call from another thread; safe after close. */
    fun cancel() {
        val h = handle.get()
        if (h != 0L) runCatching { NativeWhisper.cancel(h) }
    }

    override fun close() {
        val h = handle.getAndSet(0L)
        if (h != 0L) runCatching { NativeWhisper.destroy(h) }
    }

    companion object {
        private const val TAG = "WhisperTranscriber"

        /**
         * Threads for the decode.
         *
         * Capped at 6 rather than handed every core. On a big.LITTLE phone the little
         * cores slow the batch down more than they add, and a keyboard that pins every
         * core for two seconds makes the rest of the phone stutter while the user is
         * still looking at it.
         */
        fun threadCount(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        /** Returns null rather than throwing when the model or the library is missing. */
        fun open(modelFile: File, modelName: String): WhisperTranscriber? {
            if (!NativeLibs.whisperAvailable) {
                Log.w(TAG, "libscribewhisper.so unavailable")
                return null
            }
            if (!modelFile.isFile) {
                Log.w(TAG, "model file missing: ${modelFile.path}")
                return null
            }
            val threads = threadCount()
            val handle = runCatching { NativeWhisper.create(modelFile.absolutePath, threads) }
                .getOrElse {
                    Log.e(TAG, "NativeWhisper.create threw", it)
                    0L
                }
            if (handle == 0L) {
                Log.e(TAG, "failed to load ${modelFile.name}")
                return null
            }
            return WhisperTranscriber(AtomicLong(handle), modelName, modelFile, threads)
        }
    }
}

/**
 * Supplies the everyday and high-accuracy models, loading each on first use and keeping it.
 *
 * The heavy model is several hundred megabytes and most dictation never needs it, so it is
 * never loaded until the boost key asks for it — the same lazy path as the desktop.
 */
class WhisperProvider(
    private val everydayModel: () -> Pair<File, String>,
    private val heavyModel: () -> Pair<File, String>,
) : TranscriberProvider {

    @Volatile private var everyday: WhisperTranscriber? = null
    @Volatile private var heavy: WhisperTranscriber? = null

    /**
     * The loaded everyday model, **checked against the one that is configured**.
     *
     * This used to return the cached transcriber the moment it existed, without asking
     * which model was wanted — so choosing a different model in Settings changed the
     * stored setting, the "IN USE" tag and nothing else. The first model loaded after
     * install went on doing every transcription for the life of the process, and the
     * panel's own label went on naming it, which is exactly what it looked like from
     * the outside: *"it always says it's using base even when I change the model"*.
     */
    @Synchronized
    override fun everyday(): Transcriber = loaded(everyday, everydayModel(), "everyday")
        .also { everyday = it }

    @Synchronized
    override fun heavy(): Transcriber = loaded(heavy, heavyModel(), "high-accuracy")
        .also { heavy = it }

    /**
     * Reuse [current] if it came from the file that is wanted now; otherwise swap it.
     *
     * The old one is closed before the new one is opened. Both are hundreds of megabytes
     * and holding two at once on a phone is how a process gets killed mid-sentence.
     */
    private fun loaded(
        current: WhisperTranscriber?,
        wanted: Pair<File, String>,
        role: String,
    ): WhisperTranscriber {
        val (file, name) = wanted
        current?.let {
            if (it.source.absolutePath == file.absolutePath) return it
            Log.i(TAG, "$role model changed to $name; reloading")
            it.close()
        }
        return WhisperTranscriber.open(file, name)
            ?: error("could not load the $role model $name")
    }

    override fun cancelAll() {
        // Deliberately not synchronized: this is called from the main thread to abandon a
        // partial, and the lock can be held by the worker for the seconds a model load
        // takes. `cancel` is safe on a closed transcriber, which is what the fields being
        // @Volatile is for.
        everyday?.cancel()
        heavy?.cancel()
    }

    private companion object { const val TAG = "WhisperProvider" }

    /** Frees both models. Called when the keyboard is torn down for good. */
    @Synchronized
    fun release() {
        everyday?.close()
        heavy?.close()
        everyday = null
        heavy = null
    }
}
