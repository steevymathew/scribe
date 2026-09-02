package dev.smantics.scribe.core.dictation

import dev.smantics.scribe.core.clean.CleanContext
import dev.smantics.scribe.core.clean.Mode
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table, including every error path, exercised with no Android and no
 * microphone.
 *
 * The error paths matter more than the happy one. Each of the guarantees checked here was
 * arrived at on the desktop by something going wrong first, and the whole reason for
 * porting the state machine rather than rewriting it is to not rediscover them.
 */
class DictationMachineTest {

    // ------------------------------------------------------------------ fakes

    private class FakeRecorder(
        var samples: FloatArray = FloatArray(DictationMachine.SAMPLE_RATE),
        var failOnStart: Throwable? = null,
    ) : AudioRecorder {
        var started = 0
        var stopped = 0
        override fun start() {
            failOnStart?.let { throw it }
            started++
        }
        override fun stop(): FloatArray {
            stopped++
            return samples
        }
    }

    private class FakeTranscriber(
        override val backendLabel: String = "base.en · CPU",
        var result: String = "hello world",
        var boom: Throwable? = null,
    ) : Transcriber {
        var calls = 0
        var lastPrompt: String? = null
        override fun transcribe(
            pcm16k: FloatArray,
            language: String,
            beamSize: Int,
            initialPrompt: String?,
        ): String {
            calls++
            lastPrompt = initialPrompt
            boom?.let { throw it }
            return result
        }
    }

    private class FakeProvider(
        val light: FakeTranscriber = FakeTranscriber(),
        val big: FakeTranscriber = FakeTranscriber("small.en · CPU", "hello world"),
        var lightBoom: Throwable? = null,
    ) : TranscriberProvider {
        var cancels = 0
        override fun everyday(): Transcriber {
            lightBoom?.let { throw it }
            return light
        }
        override fun heavy(): Transcriber = big
        override fun cancelAll() { cancels++ }
    }

    private class FakeSink(var boom: Throwable? = null) : TextSink {
        val committed = mutableListOf<String>()
        val replacements = mutableListOf<Pair<String, String>>()
        var replaceSucceeds = true
        override fun commit(text: String) {
            boom?.let { throw it }
            committed += text
        }
        override fun replace(previous: String, next: String): Boolean {
            replacements += previous to next
            return replaceSucceeds
        }
    }

    /** Runs the worker inline so every assertion sees a settled machine. */
    private val inline = Executor { it.run() }

    private fun machine(
        recorder: AudioRecorder = FakeRecorder(),
        provider: TranscriberProvider = FakeProvider(),
        sink: TextSink = FakeSink(),
        settings: MachineSettings = MachineSettings(mode = Mode.RAW),
        events: MutableList<DictationEvent> = mutableListOf(),
    ) = DictationMachine(recorder, provider, sink, inline, { settings }) { events += it } to events

    private fun seconds(n: Double) = FloatArray((DictationMachine.SAMPLE_RATE * n).toInt())

    // -------------------------------------------------------------- start-up

    @Test fun `warm up announces the model and becomes ready`() {
        val (m, events) = machine()
        m.warmUp()
        assertEquals(DictationEvent.ModelLoading("base.en"), events[0])
        assertEquals(DictationEvent.ModelLoaded("base.en", "base.en · CPU"), events[1])
        assertEquals(DictationMachine.Status.READY, m.status)
    }

    @Test fun `a model that will not load reports an error rather than pretending`() {
        val provider = FakeProvider(lightBoom = IllegalStateException("corrupt file"))
        val (m, events) = machine(provider = provider)
        m.warmUp()
        assertEquals(DictationMachine.Status.ERROR, m.status)
        assertTrue(events.last() is DictationEvent.Error)
    }

    // --------------------------------------------------------- the happy path

    @Test fun `press speak release types the text`() {
        val sink = FakeSink()
        val (m, events) = machine(sink = sink)
        m.warmUp()
        m.startRecording()
        m.stopRecording()

        assertEquals(listOf("hello world"), sink.committed)
        val names = events.map { it::class.simpleName }
        assertTrue(names.containsAll(listOf("RecordingStarted", "RecordingStopped", "Transcribing", "Injected")))
        assertEquals(DictationMachine.Status.READY, m.status)
    }

    @Test fun `the injected event carries both renderings`() {
        val (m, events) = machine(settings = MachineSettings(mode = Mode.RAW))
        m.warmUp()
        m.startRecording()
        m.stopRecording()
        val injected = events.filterIsInstance<DictationEvent.Injected>().single()
        assertEquals("hello world", injected.text)
        assertEquals("hello world", injected.raw)
        assertFalse(injected.heavy)
    }

    // -------------------------------------------------------- microphone path

    @Test fun `a microphone that will not open never leaves the machine recording`() {
        val recorder = FakeRecorder(failOnStart = SecurityException("permission denied"))
        val sink = FakeSink()
        val (m, events) = machine(recorder = recorder, sink = sink)
        m.warmUp()
        m.startRecording()

        assertTrue(events.last() is DictationEvent.Error)
        assertTrue((events.last() as DictationEvent.Error).message.contains("microphone", true))
        // The decisive part: stopping now must be a no-op, not a phantom transcription.
        m.stopRecording()
        assertTrue(sink.committed.isEmpty())
    }

    @Test fun `a mis-tap shorter than the minimum is discarded silently`() {
        val recorder = FakeRecorder(samples = seconds(0.1))
        val sink = FakeSink()
        val (m, events) = machine(recorder = recorder, sink = sink)
        m.warmUp()
        m.startRecording()
        m.stopRecording()

        assertTrue(sink.committed.isEmpty())
        assertTrue(events.none { it is DictationEvent.Transcribing })
        assertTrue(events.any { it is DictationEvent.RecordingStopped })
        assertEquals(DictationMachine.Status.READY, m.status)
    }

    @Test fun `an over-long clip is truncated rather than refused`() {
        val provider = FakeProvider()
        val recorder = FakeRecorder(samples = seconds(DictationMachine.MAX_AUDIO_SEC + 30))
        val (m, _) = machine(recorder = recorder, provider = provider)
        m.warmUp()
        m.startRecording()
        m.stopRecording()
        assertEquals(1, provider.light.calls)
    }

    // ------------------------------------------------------------ error paths

    /** Desktop contract: a failed decode emits Error and NO Injected. */
    @Test fun `a failed transcription emits no injected event`() {
        val provider = FakeProvider(light = FakeTranscriber(boom = RuntimeException("decode")))
        val (m, events) = machine(provider = provider)
        m.warmUp()
        m.startRecording()
        m.stopRecording()

        assertTrue(events.any { it is DictationEvent.Error })
        assertTrue(events.none { it is DictationEvent.Injected })
    }

    /**
     * Desktop contract, and the subtler half: a failed *injection* still emits Injected.
     * The transcript happened; the user must still find it in history even though the
     * field would not take it.
     */
    @Test fun `a failed injection still emits injected`() {
        val sink = FakeSink(boom = IllegalStateException("no input connection"))
        val (m, events) = machine(sink = sink)
        m.warmUp()
        m.startRecording()
        m.stopRecording()

        assertTrue(events.any { it is DictationEvent.Error })
        assertEquals(1, events.count { it is DictationEvent.Injected })
    }

    @Test fun `silence produces an empty injected event and types nothing`() {
        val provider = FakeProvider(light = FakeTranscriber(result = "[BLANK_AUDIO]"))
        val sink = FakeSink()
        val (m, events) = machine(provider = provider, sink = sink)
        m.warmUp()
        m.startRecording()
        m.stopRecording()

        assertTrue(sink.committed.isEmpty())
        assertEquals("", events.filterIsInstance<DictationEvent.Injected>().single().text)
        assertEquals(DictationMachine.Status.READY, m.status)
    }

    // ----------------------------------------------------------------- boost

    @Test fun `boost debounces key repeat`() {
        val (m, events) = machine()
        m.warmUp()
        repeat(5) { m.setBoost(true) }
        assertEquals(1, events.count { it is DictationEvent.Boost })
    }

    @Test fun `boost pressed mid recording upgrades the clip in flight`() {
        val provider = FakeProvider()
        val (m, _) = machine(provider = provider)
        m.warmUp()
        m.startRecording()
        m.setBoost(true)
        m.setBoost(false)   // released before the key — must still count
        m.stopRecording()
        assertEquals(1, provider.big.calls)
        assertEquals(0, provider.light.calls)
    }

    @Test fun `a missing heavy model falls back instead of failing`() {
        val provider = object : TranscriberProvider {
            val light = FakeTranscriber()
            override fun everyday(): Transcriber = light
            override fun heavy(): Transcriber = throw IllegalStateException("not downloaded")
            override fun cancelAll() {}
        }
        val sink = FakeSink()
        val (m, events) = machine(provider = provider, sink = sink)
        m.warmUp()
        m.startRecording()
        m.setBoost(true)
        m.stopRecording()

        assertEquals(listOf("hello world"), sink.committed)
        assertTrue(events.any { it is DictationEvent.Error })
    }

    // ---------------------------------------------------------------- paused

    @Test fun `pausing stops the keyboard from listening at all`() {
        val recorder = FakeRecorder()
        val (m, events) = machine(recorder = recorder)
        m.warmUp()
        m.setPaused(true)
        m.startRecording()
        assertEquals(0, recorder.started)
        assertTrue(events.none { it is DictationEvent.RecordingStarted })
    }

    @Test fun `a hotkey held across a pause cannot wedge a stuck recording`() {
        val recorder = FakeRecorder()
        val (m, _) = machine(recorder = recorder)
        m.warmUp()
        m.startRecording()
        m.setPaused(true)          // pause while the key is still down
        m.startRecording()         // and the next press does nothing
        assertEquals(1, recorder.started)
        assertEquals(1, recorder.stopped)
    }

    // --------------------------------------------------------------- levels

    @Test fun `level events only flow while recording`() {
        val (m, events) = machine()
        m.warmUp()
        m.onLevel(0.4f)
        assertTrue(events.none { it is DictationEvent.Level })
        m.startRecording()
        m.onLevel(0.4f)
        assertEquals(1, events.count { it is DictationEvent.Level })
    }

    // ------------------------------------------------------- the mode toggle

    @Test fun `re-rendering the last utterance swaps what was inserted`() {
        val provider = FakeProvider(light = FakeTranscriber(result = "um, hello there"))
        val sink = FakeSink()
        val (m, _) = machine(
            provider = provider,
            sink = sink,
            settings = MachineSettings(mode = Mode.RAW, cleanContext = CleanContext()),
        )
        m.warmUp()
        m.startRecording()
        m.stopRecording()
        assertEquals(listOf("um, hello there"), sink.committed)

        val cleaned = m.rerenderLast(Mode.CLEAN)
        assertEquals("Hello there", cleaned)
        assertEquals(listOf("um, hello there" to "Hello there"), sink.replacements)
    }

    @Test fun `re-rendering reports failure rather than guessing`() {
        val sink = FakeSink().apply { replaceSucceeds = false }
        val provider = FakeProvider(light = FakeTranscriber(result = "um, hello there"))
        val (m, _) = machine(provider = provider, sink = sink, settings = MachineSettings(mode = Mode.RAW))
        m.warmUp()
        m.startRecording()
        m.stopRecording()
        assertNull(m.rerenderLast(Mode.CLEAN))
    }

    @Test fun `re-rendering with nothing dictated yet does nothing`() {
        val (m, _) = machine()
        assertNull(m.rerenderLast(Mode.CLEAN))
    }

    // --------------------------------------------------------------- cancel

    @Test fun `cancelling abandons the decode and returns to ready`() {
        val provider = FakeProvider()
        val (m, _) = machine(provider = provider)
        m.warmUp()
        m.startRecording()
        m.cancel()
        assertEquals(1, provider.cancels)
        assertEquals(DictationMachine.Status.READY, m.status)
    }

    // -------------------------------------------------------------- settings

    @Test fun `the dictionary reaches recognition as an initial prompt`() {
        val provider = FakeProvider()
        val (m, _) = machine(
            provider = provider,
            settings = MachineSettings(mode = Mode.RAW, initialPrompt = "Jira, PostgreSQL"),
        )
        m.warmUp()
        m.startRecording()
        m.stopRecording()
        assertEquals("Jira, PostgreSQL", provider.light.lastPrompt)
    }
}
