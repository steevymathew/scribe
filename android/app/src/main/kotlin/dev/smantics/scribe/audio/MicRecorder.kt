package dev.smantics.scribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.smantics.scribe.core.dictation.AudioRecorder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microphone capture for dictation: 16 kHz mono float, which is exactly what Whisper wants
 * and therefore involves no resampling anywhere in the path.
 *
 * The capture pattern is VisEar's, which is proven on this hardware: a dedicated
 * high-priority thread doing blocking reads, never touching the UI thread. What differs is
 * the source. VisEar rejected `VOICE_RECOGNITION` because it delivers processed mono and
 * that destroyed the inter-channel delay it needed for direction finding. For single-
 * channel speech recognition, processed mono — noise-suppressed, AGC'd, tuned by the OEM
 * for exactly this job — is the best signal available, so Scribe asks for it by name.
 *
 * Everything is accumulated and handed over whole on [stop]. Utterances are short by
 * construction (the machine caps them at 120 s ≈ 7.7 MB of float) and Whisper is not a
 * streaming decoder, so there is nothing to gain from a ring buffer and a correctness
 * risk in dropping audio the user actually spoke.
 */
class MicRecorder(
    private val onLevel: (Float) -> Unit = {},
) : AudioRecorder {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile private var buffer = FloatArray(INITIAL_CAPACITY)
    @Volatile private var length = 0
    private var stopped: CountDownLatch? = null

    /**
     * @throws SecurityException when RECORD_AUDIO has not been granted — the caller turns
     *   this into the "open Scribe to grant microphone access" state, because an input
     *   method cannot request the permission itself.
     * @throws IllegalStateException when the microphone exists but will not open.
     */
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        length = 0

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(minBuffer > 0) { "microphone busy or unavailable (min buffer $minBuffer)" }
        // A second of headroom, so a scheduling hiccup does not cost the user a syllable.
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * BYTES_PER_SAMPLE)

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            error("microphone busy or unavailable")
        }

        record = recorder
        stopped = CountDownLatch(1)
        recorder.startRecording()

        thread = Thread({ readLoop(recorder) }, "scribe-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stop(): FloatArray {
        if (!running.compareAndSet(true, false)) return FloatArray(0)
        stopped?.await()
        thread = null

        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null

        val out = buffer.copyOf(length)
        length = 0
        return out
    }

    private fun readLoop(recorder: AudioRecord) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            while (running.get()) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION or ERROR_DEAD_OBJECT: the mic went away
                    // mid-utterance. Keep what we have rather than losing the whole clip.
                    if (read < 0) Log.w(TAG, "AudioRecord.read returned $read; ending capture")
                    if (read < 0) break else continue
                }
                append(chunk, read)
                onLevel(rms(chunk, read))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture thread died", t)
        } finally {
            stopped?.countDown()
        }
    }

    private fun append(chunk: ShortArray, count: Int) {
        ensureCapacity(length + count)
        val target = buffer
        for (i in 0 until count) {
            target[length + i] = chunk[i] / 32768f
        }
        length += count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var size = buffer.size
        while (size < required) size *= 2
        buffer = buffer.copyOf(min(size, MAX_CAPACITY))
    }

    /** Root-mean-square, scaled so a normal speaking voice fills most of the meter. */
    private fun rms(chunk: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val v = chunk[i] / 32768.0
            sum += v * v
        }
        val raw = sqrt(sum / count).toFloat()
        return (raw * METER_GAIN).coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "MicRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        /** 20 ms per read: fast enough for a waveform that tracks the voice. */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 50

        const val INITIAL_CAPACITY = SAMPLE_RATE * 8      // 8 s before the first grow
        const val MAX_CAPACITY = SAMPLE_RATE * 130        // past the machine's 120 s cap

        /**
         * Speech RMS sits around 0.05–0.15, so a bar drawn straight from it barely moves.
         * The meter exists to tell the user Scribe can hear them; this makes it do that.
         */
        const val METER_GAIN = 6f
    }
}
