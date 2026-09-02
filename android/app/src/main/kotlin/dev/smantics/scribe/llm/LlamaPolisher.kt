package dev.smantics.scribe.llm

import android.util.Log
import dev.smantics.scribe.NativeLibs
import dev.smantics.scribe.core.clean.Polisher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Kotlin side of libscribellm.so. Contract: `android/docs/jni-contract.md`. */
object NativeLlm {
    external fun create(modelPath: String, nThreads: Int, contextTokens: Int): Long
    external fun generate(
        handle: Long,
        systemPrompt: String,
        userText: String,
        maxTokens: Int,
        temperature: Float,
    ): String
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

/**
 * The optional Polish stage, backed by a small local model through llama.cpp.
 *
 * Two things are enforced here rather than left to hope:
 *
 *  - **A hard wall-clock deadline.** Generation runs on its own thread and the caller
 *    waits at most `timeoutMs`; on expiry the native decode is aborted and null is
 *    returned. A keyboard that stalls for four seconds after you stop speaking is worse
 *    than one that never polished at all, so the deadline is not advisory.
 *  - **Never throwing.** Every failure — no library, no model, a native fault, a timeout —
 *    is a null, and `GuardedPolisher` turns that into the rules-only text.
 *
 * Whether the output is *acceptable* is not decided here. That is `PolishGuard`, in core,
 * where it can be tested without a model.
 */
class LlamaPolisher private constructor(
    private val handle: AtomicLong,
    val modelName: String,
) : Polisher, AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scribe-polish").apply { isDaemon = true }
    }

    override val isAvailable: Boolean get() = handle.get() != 0L

    override fun polish(text: String, instruction: String, timeoutMs: Long): String? {
        val h = handle.get()
        if (h == 0L) return null

        // The budget is generous relative to the input because a cleanup is never longer
        // than what was said, and a runaway generation is stopped by the deadline anyway.
        val maxTokens = (text.length / 2).coerceIn(32, 512)

        val future = executor.submit<String> {
            NativeLlm.generate(h, instruction, text, maxTokens, TEMPERATURE)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS).takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            // A timeout leaves the decode running until the abort callback sees the flag,
            // so cancel it explicitly rather than leaving a thread burning battery.
            runCatching { NativeLlm.cancel(h) }
            future.cancel(true)
            Log.w(TAG, "polish abandoned: ${t.javaClass.simpleName}")
            null
        }
    }

    override fun close() {
        val h = handle.getAndSet(0L)
        if (h != 0L) {
            runCatching { NativeLlm.cancel(h) }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            runCatching { NativeLlm.destroy(h) }
        } else {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "LlamaPolisher"

        /** Greedy. Text cleanup has a right answer; sampling only varies the wrong ones. */
        private const val TEMPERATURE = 0.0f

        /** Enough for a long dictated paragraph plus the instruction, and no more. */
        private const val CONTEXT_TOKENS = 2048

        /** Polish gets fewer threads than ASR — it runs while the user is reading. */
        fun threadCount(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        fun open(modelFile: File, modelName: String): LlamaPolisher? {
            if (!NativeLibs.llmAvailable) return null
            if (!modelFile.isFile) return null
            val handle = runCatching {
                NativeLlm.create(modelFile.absolutePath, threadCount(), CONTEXT_TOKENS)
            }.getOrElse {
                Log.e(TAG, "NativeLlm.create threw", it)
                0L
            }
            if (handle == 0L) {
                Log.e(TAG, "failed to load polish model ${modelFile.name}")
                return null
            }
            return LlamaPolisher(AtomicLong(handle), modelName)
        }
    }
}

/** Stands in when no polish model is installed, which is the default state. */
object NoPolisher : Polisher {
    override val isAvailable = false
    override fun polish(text: String, instruction: String, timeoutMs: Long): String? = null
}
