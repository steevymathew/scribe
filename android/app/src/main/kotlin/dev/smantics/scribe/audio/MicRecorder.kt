package dev.smantics.scribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.smantics.scribe.core.dictation.AudioRecorder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Microphone capture for dictation: 16 kHz mono, which is exactly what Whisper wants and
 * therefore involves no resampling anywhere in the path.
 *
 * The capture pattern is VisEar's, which is proven on this hardware: a dedicated
 * high-priority thread doing blocking reads, never touching the UI thread. What differs is
 * the source. VisEar rejected `VOICE_RECOGNITION` because it delivers processed mono and
 * that destroyed the inter-channel delay it needed for direction finding. For single-
 * channel speech recognition, processed mono — noise-suppressed, AGC'd, tuned by the OEM
 * for exactly this job — is the best signal available, so Scribe asks for it by name.
 *
 * **Audio is held as a list of fixed blocks, and this is not an implementation detail.**
 * The first version grew one array by doubling it, which put three faults on the capture
 * thread at once:
 *
 *  - a `copyOf` of the whole recording every time it doubled — several megabytes, on the
 *    thread that has 20 ms to get back to `AudioRecord.read` before the driver's buffer
 *    overruns. Past about a minute that copy is long enough to drop audio, and dropped
 *    audio is silent: nothing reports it, the words simply are not in the transcript;
 *  - a hard ceiling, past which `ensureCapacity` clamped and `append` then wrote off the
 *    end of the array, killing the capture thread inside a `catch (Throwable)` that only
 *    logged. Everything after that point was lost with no sign at all;
 *  - float32 storage, doubling the memory for samples that arrive as 16-bit anyway.
 *
 * Blocks fix all three. Appending is a bounded array write, a new block is a 32 KB
 * allocation once a second, there is no ceiling, and the conversion to float happens on
 * whichever thread asked for the audio — never on the one holding the microphone open.
 */
class MicRecorder(
    private val onLevel: (Float) -> Unit = {},
) : AudioRecorder {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Completed blocks, oldest first. Written only by the capture thread. */
    private val blocks = CopyOnWriteArrayList<ShortArray>()

    /** The block being filled, and how much of it is real. */
    @Volatile private var current = ShortArray(BLOCK_SAMPLES)
    @Volatile private var currentLength = 0

    private var stopped: CountDownLatch? = null

    /** How long the recording is, in seconds. Safe to read from any thread. */
    val seconds: Float
        get() = (blocks.size.toLong() * BLOCK_SAMPLES + currentLength) / SAMPLE_RATE.toFloat()

    /**
     * @throws SecurityException when RECORD_AUDIO has not been granted — the caller turns
     *   this into the "open Scribe to grant microphone access" state, because an input
     *   method cannot request the permission itself.
     * @throws IllegalStateException when the microphone exists but will not open.
     */
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        blocks.clear()
        current = ShortArray(BLOCK_SAMPLES)
        currentLength = 0

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

    /**
     * A copy of the audio so far, taken while the microphone is still open.
     *
     * This is what makes the live transcript possible: the partial decoder takes a copy of
     * the audio to date, transcribes it, and the recording carries on underneath. Safe
     * without locking because the capture thread only ever appends — a block, once it is
     * in the list, never changes again, and the worst case is a snapshot a few
     * milliseconds behind, which for a partial is not a defect.
     */
    override fun snapshot(): FloatArray {
        if (!running.get()) return FloatArray(0)
        return collect()
    }

    override fun stop(): FloatArray {
        if (!running.compareAndSet(true, false)) return FloatArray(0)
        // Waited on, so the last blocking read completes and its samples are in. Without
        // this the tail of every utterance would be lost, which is the failure mode that
        // is hardest to notice because it only removes the last syllable.
        stopped?.await()
        thread = null

        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null

        val out = collect()
        blocks.clear()
        currentLength = 0
        return out
    }

    /** Flatten the blocks into the float array Whisper wants. Never on the capture thread. */
    private fun collect(): FloatArray {
        // Read once: the capture thread may add a block between these two lines, and the
        // consequence of missing it is a snapshot 20 ms short, not a corrupt one.
        val completed = blocks.toList()
        val tail = current
        val tailLength = currentLength.coerceIn(0, tail.size)

        val total = completed.size * BLOCK_SAMPLES + tailLength
        if (total <= 0) return FloatArray(0)

        val out = FloatArray(total)
        var at = 0
        for (block in completed) {
            for (i in 0 until BLOCK_SAMPLES) out[at + i] = block[i] / 32768f
            at += BLOCK_SAMPLES
        }
        for (i in 0 until tailLength) out[at + i] = tail[i] / 32768f
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
            // Kept, but it should now be unreachable: there is no allocation here that can
            // fail on a bounded write, and no ceiling to run off the end of.
            Log.e(TAG, "capture thread died", t)
        } finally {
            stopped?.countDown()
        }
    }

    /**
     * Append into the block being filled, starting a new one when it is full.
     *
     * The only allocation is one 32 KB block a second, and the only copy is of the chunk
     * just read. Both are bounded and neither depends on how long the recording has been
     * going, which is the property the doubling array did not have.
     */
    private fun append(chunk: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val room = BLOCK_SAMPLES - currentLength
            val take = minOf(room, count - offset)
            System.arraycopy(chunk, offset, current, currentLength, take)
            currentLength += take
            offset += take
            if (currentLength == BLOCK_SAMPLES) {
                // Published before the new block is installed, so a reader that sees the
                // shorter `current` still finds every completed sample in `blocks`.
                blocks.add(current)
                current = ShortArray(BLOCK_SAMPLES)
                currentLength = 0
            }
        }
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

        /** One second per block — 32 KB, allocated once a second and never copied. */
        const val BLOCK_SAMPLES = SAMPLE_RATE

        /**
         * Speech RMS sits around 0.05–0.15, so a bar drawn straight from it barely moves.
         * The meter exists to tell the user Scribe can hear them; this makes it do that.
         */
        const val METER_GAIN = 6f
    }
}
