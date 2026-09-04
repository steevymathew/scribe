package dev.smantics.scribe.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.smantics.scribe.audio.MicRecorder
import dev.smantics.scribe.asr.WhisperProvider
import dev.smantics.scribe.core.clean.GuardedPolisher
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.core.clean.RawPipeline
import dev.smantics.scribe.core.clean.PolishVerdict
import dev.smantics.scribe.core.clean.TextDiff
import dev.smantics.scribe.core.dictation.DictationEvent
import dev.smantics.scribe.core.dictation.DictationMachine
import dev.smantics.scribe.core.dictation.MachineSettings
import dev.smantics.scribe.core.dictation.SilenceEndpointer
import dev.smantics.scribe.core.dictation.TextSink
import dev.smantics.scribe.core.model.ModelRegistry
import dev.smantics.scribe.history.HistoryStore
import dev.smantics.scribe.llm.LlamaPolisher
import dev.smantics.scribe.llm.NoPolisher
import dev.smantics.scribe.model.ModelStore
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.settings.SettingsRepository
import dev.smantics.scribe.ui.theme.DictationStatus
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the keyboard panel and the app screens need to render. */
data class EngineState(
    val status: DictationStatus = DictationStatus.LOADING,
    val statusDetail: String = "Starting…",
    val level: Float = 0f,
    val mode: Mode = Mode.CLEAN,
    val boostActive: Boolean = false,
    val modelName: String = "",
    val lastText: String = "",
    val error: String? = null,
    val polishAvailable: Boolean = false,
    /** Whether the high-accuracy model is on disk. False on a fresh install. */
    val heavyModelAvailable: Boolean = false,

    /** Which part of the dictate-and-reveal sequence is on screen. */
    val stage: DictationStage = DictationStage.IDLE,

    /** The live, unpolished transcript, updated while the user is still speaking. */
    val partialText: String = "",

    /** What Clean mode changed, so the reveal can show its working. */
    val diff: List<TextDiff.Segment> = emptyList(),

    /** The finished text, once the pipeline has run. */
    val finalText: String = "",
)

/**
 * The stages of a toggled dictation, in order.
 *
 * The user speaks, sees what was heard, then watches the cleanup happen before it lands.
 * Showing the edit rather than silently applying it is what makes Clean mode trustworthy:
 * you can see a filler dropped and a correction applied instead of wondering whether the
 * transcription was wrong.
 */
enum class DictationStage {
    IDLE,

    /** Recording. The raw transcript updates underneath the waveform as you talk. */
    LISTENING,

    /**
     * The microphone is closed and the final decode is running.
     *
     * This stage exists because without it the panel went on saying "listening" for the
     * several seconds after stop was pressed — the one moment the user most needs to be
     * told that something changed.
     */
    TRANSCRIBING,

    /** Removals struck through in the raw text. */
    CLEANING,

    /** Additions — punctuation, capitals — marked in the cleaned text. */
    PUNCTUATING,

    /** The finished line, just before it is inserted. */
    FINAL,
}

/**
 * Process-scoped owner of the dictation engine.
 *
 * **This exists because of the Fold.** An input method's view is destroyed and rebuilt when
 * the device is folded or unfolded, which is a configuration change like any other. If the
 * recording, the loaded model or the last transcript lived in that view, opening the phone
 * mid-sentence would lose them. They live here instead, and the view becomes a pure
 * rendering of [state].
 *
 * It is also the single place that owns the loaded Whisper model, which costs hundreds of
 * megabytes and must not be loaded twice because two surfaces (the keyboard and the app)
 * both wanted it.
 */
class ScribeEngine private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scribe-worker").apply { isDaemon = true }
    }

    val settings = SettingsRepository(appContext)
    val models = ModelStore(appContext)
    val history = HistoryStore(appContext)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var config: ScribeConfig = ScribeConfig()

    /** The field currently being typed into, so per-app tone can be chosen. */
    @Volatile private var targetPackage: String? = null

    /** Whatever surface is currently able to insert text; null when nothing has focus. */
    @Volatile private var sink: TextSink? = null

    private var polisher: LlamaPolisher? = null
    private var guardedPolisher = GuardedPolisher(NoPolisher)

    private val provider = WhisperProvider(
        everydayModel = {
            val spec = ModelRegistry.byId(config.model) ?: ModelRegistry.EVERYDAY
            models.fileFor(spec) to spec.displayName
        },
        heavyModel = {
            val spec = ModelRegistry.byId(config.heavyModel) ?: ModelRegistry.SHARP
            models.fileFor(spec) to spec.displayName
        },
    )

    private val recorder = MicRecorder(onLevel = { machine.onLevel(it) })

    /**
     * Routes text to whichever surface has focus.
     *
     * A null sink is not an error worth shouting about — it means the user dismissed the
     * keyboard while a decode was still running. The transcript still reaches history.
     */
    private val routingSink = object : TextSink {
        override fun commit(text: String) {
            val target = sink ?: error("no field has focus")
            target.commit(text)
        }
        override fun replace(previous: String, next: String): Boolean =
            sink?.replace(previous, next) ?: false
    }

    // The explicit type is required, not cosmetic: `recorder` reports levels back into
    // `machine`, so inferring one from the other is circular and the compiler says so.
    private val machine: DictationMachine = DictationMachine(
        recorder = recorder,
        transcribers = provider,
        sink = routingSink,
        worker = worker,
        settings = {
            val c = config
            MachineSettings(
                model = c.model,
                heavyModel = c.heavyModel,
                language = c.language,
                beamSize = c.beamSize,
                mode = c.mode,
                cleanContext = c.cleanContext(targetPackage),
                initialPrompt = c.initialPrompt(),
            )
        },
        listener = ::onEvent,
    )

    // -------------------------------------------------------------- lifecycle

    init {
        scope.launch {
            settings.config.collect { c ->
                val previous = config
                config = c
                _state.value = _state.value.copy(mode = c.mode)
                // Choosing a different model in Settings has to actually change what runs,
                // and has to change it *now* rather than at the next dictation — the panel
                // names the loaded model, so anything less leaves the interface asserting
                // something untrue. Re-running warmUp reloads the model and re-emits the
                // events the label is built from.
                if (warmedUp && c.model != previous.model) {
                    Log.i(TAG, "speech model changed: ${previous.model} -> ${c.model}")
                    worker.execute { machine.warmUp() }
                }
                if (c.heavyModel != previous.heavyModel) refreshHeavyModelAvailability()
            }
        }
    }

    /** Load the bundled model and the everyday transcriber. Safe to call repeatedly. */
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        worker.execute {
            runCatching { models.stageBundledModel() }
            machine.warmUp()
            refreshHeavyModelAvailability()
            loadPolisherIfEnabled()
        }
    }

    @Volatile private var warmedUp = false

    /**
     * Whether the boost model is actually installed.
     *
     * Checked so the keyboard can say so *before* the user commits an utterance to it. An
     * armed-looking bolt that turns into "high-accuracy model unavailable" after you have
     * already spoken is a promise made and broken in the same breath.
     */
    fun refreshHeavyModelAvailability() {
        val spec = ModelRegistry.byId(config.heavyModel) ?: ModelRegistry.SHARP
        val present = models.fileFor(spec).isFile
        if (present != _state.value.heavyModelAvailable) {
            _state.value = _state.value.copy(heavyModelAvailable = present)
        }
    }

    private fun loadPolisherIfEnabled() {
        val c = config
        if (!c.polishEnabled) return
        val spec = ModelRegistry.byId(c.polishModel) ?: return
        val file = models.fileFor(spec)
        if (!file.isFile) return
        polisher = LlamaPolisher.open(file, spec.displayName)?.also { loaded ->
            guardedPolisher = GuardedPolisher(loaded, onVerdict = ::onPolishVerdict)
            _state.value = _state.value.copy(polishAvailable = true)
        }
    }

    private fun onPolishVerdict(verdict: PolishVerdict) {
        // Rejections are logged, never surfaced mid-sentence: the user asked for text, and
        // "the polish model made something up so I ignored it" is not something to
        // interrupt them with. It is visible in Settings → Logs.
        if (verdict is PolishVerdict.Rejected) Log.i(TAG, "polish rejected: ${verdict.reason}")
    }

    // ------------------------------------------------------------ the surface

    /**
     * Bind to whichever surface now has focus.
     *
     * The permission check here is deliberately **symmetric**. An earlier version only set
     * NEEDS_PERMISSION when the microphone was missing and never cleared it, so a user who
     * did exactly what the keyboard asked — open Scribe, grant the microphone, come back —
     * found the same "Scribe needs the microphone" card waiting for them, with no way to
     * dismiss it short of the process dying. Doing as you are told and being ignored is a
     * worse experience than a plain refusal.
     */
    fun attach(sink: TextSink, packageName: String?) {
        this.sink = sink
        this.targetPackage = packageName
        refreshHeavyModelAvailability()
        if (hasMicPermission() && _state.value.status == DictationStatus.NEEDS_PERMISSION) {
            val loaded = machine.status == DictationMachine.Status.READY
            _state.value = _state.value.copy(
                status = if (loaded) DictationStatus.READY else DictationStatus.LOADING,
                statusDetail = if (loaded) "Ready" else "Starting…",
            )
        }
        if (packageName != null && packageName != appContext.packageName) {
            // Remembered only so the tone screen has something to offer. Android's package
            // list is a restricted permission and Scribe never asks for it, so this list
            // can only ever contain apps the user has actually dictated into.
            scope.launch {
                settings.update { c ->
                    if (c.recentApps.firstOrNull() == packageName) c
                    else c.copy(recentApps = (listOf(packageName) + c.recentApps).distinct())
                }
            }
        }
        if (!hasMicPermission()) {
            _state.value = _state.value.copy(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            )
        }
    }

    fun detach() {
        machine.cancel()
        DictationService.stop(appContext)
        sink = null
        targetPackage = null
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // --------------------------------------------------------- level preview

    /**
     * Open the microphone purely to show a level, for the setup screen's meter.
     *
     * Nothing is transcribed and nothing is kept — the samples are read and dropped. It
     * exists because a microphone check that cannot move is worse than no check at all:
     * the user speaks at their phone in the first minute of using a dictation app and
     * cannot tell a passive meter from a dead microphone.
     *
     * Deliberately separate from [startRecording]: this must never enter the state
     * machine, never queue a decode, and never start the foreground service, because the
     * user has not asked to dictate anything.
     */
    @Synchronized
    fun startLevelPreview() {
        if (previewRecorder != null || !hasMicPermission()) return
        val recorder = MicRecorder(onLevel = { rms ->
            _state.value = _state.value.copy(level = rms)
        })
        previewRecorder = runCatching { recorder.also { it.start() } }.getOrElse {
            _state.value = _state.value.copy(
                status = DictationStatus.ERROR,
                statusDetail = "Microphone unavailable — another app may be using it",
            )
            null
        }
    }

    @Synchronized
    fun stopLevelPreview() {
        val recorder = previewRecorder ?: return
        previewRecorder = null
        runCatching { recorder.stop() }
        _state.value = _state.value.copy(level = 0f)
    }

    private var previewRecorder: MicRecorder? = null

    // -------------------------------------------------------------- controls

    fun startRecording() {
        if (!hasMicPermission()) {
            _state.value = _state.value.copy(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            )
            return
        }
        // Started before capture opens, so the "Scribe is listening" notification is up
        // for the whole time the microphone is, and never for a moment longer.
        DictationService.start(appContext)
        machine.startRecording()
    }

    fun stopRecording() {
        machine.stopRecording()
        DictationService.stop(appContext)
    }

    /**
     * Ask for the high-accuracy model for this utterance.
     *
     * **No surface calls this today.** The keyboard's boost control was removed
     * deliberately — it made transcripts worse in use and gave no sign from the panel
     * whether it had done anything — and with it went the last way to reach the heavy
     * model. The path below it still works and is still tested; it is kept because which
     * model runs is a decision the engine should be able to take per utterance, and
     * because deleting a working capability to tidy up an unused entry point is how a
     * feature gets rebuilt from scratch later. Anything wired to this must show, in the
     * panel, that it is in effect.
     */
    fun setBoost(held: Boolean) = machine.setBoost(held)

    fun cancel() = machine.cancel()

    /**
     * Flip Raw ↔ Clean.
     *
     * When something has just been inserted, the already-typed text is replaced with the
     * other rendering of the same utterance. No re-dictation, no model call — the raw
     * string was kept and every pipeline stage is pure. Wispr Flow has no equivalent: its
     * cleanup is applied in the cloud on the way in and cannot be undone afterwards.
     */
    fun toggleMode() {
        val next = if (config.mode == Mode.CLEAN) Mode.RAW else Mode.CLEAN
        scope.launch { settings.update { it.copy(mode = next) } }
        _state.value = _state.value.copy(mode = next)
        worker.execute {
            val text = machine.rerenderLast(next)
            if (text != null) {
                _state.value = _state.value.copy(lastText = text)
            }
        }
    }

    // --------------------------------------------------------------- toggle

    /**
     * Start or stop dictating. One tap begins, one tap ends.
     *
     * Holding a button down is precise but it costs a hand, and on a phone you are usually
     * holding the phone with it. Toggling also makes the live transcript worth having:
     * there is time to look at what is being heard and stop when it has enough.
     *
     * Returns true if this call started a session.
     */
    fun toggleDictation(sink: TextSink?, packageName: String? = null): Boolean {
        if (toggleActive.get()) {
            stopToggle()
            return false
        }
        if (!hasMicPermission()) {
            _state.value = _state.value.copy(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            )
            return false
        }

        toggleSink = sink
        targetPackage = packageName
        // The reveal commits, not the state machine — so the machine is given a sink that
        // swallows, and the real one is put back afterwards.
        sinkBeforeToggle = this.sink
        this.sink = DiscardingSink
        toggleActive.set(true)
        _state.value = _state.value.copy(
            stage = DictationStage.LISTENING,
            partialText = "",
            diff = emptyList(),
            finalText = "",
            error = null,
        )
        DictationService.start(appContext)
        machine.startRecording()

        if (machine.status != DictationMachine.Status.RECORDING) {
            toggleActive.set(false)
            _state.value = _state.value.copy(stage = DictationStage.IDLE)
            DictationService.stop(appContext)
            return false
        }
        schedulePartials()
        return true
    }

    fun stopToggle() {
        if (!toggleActive.compareAndSet(true, false)) return
        partialSchedule?.cancel(false)
        partialSchedule = null
        // Immediately, before any decoding: the user pressed stop and must see that
        // registered now, not in three seconds when the transcript arrives.
        _state.value = _state.value.copy(stage = DictationStage.TRANSCRIBING, level = 0f)
        // Abandon a partial mid-decode so the final transcription starts immediately
        // rather than queueing behind work whose answer is about to be superseded.
        provider.cancelAll()
        machine.stopRecording()
        DictationService.stop(appContext)
    }

    /** Throw the whole utterance away — the user pressed the cross. */
    fun cancelToggle() {
        toggleActive.set(false)
        partialSchedule?.cancel(false)
        partialSchedule = null
        toggleSink = null
        sink = sinkBeforeToggle
        sinkBeforeToggle = null
        cancel()
        DictationService.stop(appContext)
        _state.value = _state.value.copy(
            stage = DictationStage.IDLE,
            partialText = "",
            diff = emptyList(),
            finalText = "",
        )
    }

    val isDictating: Boolean get() = toggleActive.get()

    private val toggleActive = AtomicBoolean(false)
    @Volatile private var toggleSink: TextSink? = null
    @Volatile private var sinkBeforeToggle: TextSink? = null
    @Volatile private var partialSchedule: ScheduledFuture<*>? = null
    private val partialRunning = AtomicBoolean(false)

    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "scribe-timers").apply { isDaemon = true }
        }

    /**
     * Re-transcribe what has been said so far, every couple of seconds.
     *
     * Whisper is not a streaming decoder, so a "live" transcript is really the whole clip
     * decoded again — cheap while an utterance is short, which is what utterances are.
     * Partials run on the **same worker** as the final transcription because they share
     * one native model handle, and one is skipped rather than queued if the previous is
     * still going, so a slow phone falls back to fewer updates instead of a backlog.
     */
    private fun schedulePartials() {
        partialSchedule = timers.scheduleWithFixedDelay(
            {
                if (!toggleActive.get()) return@scheduleWithFixedDelay
                if (!partialRunning.compareAndSet(false, true)) return@scheduleWithFixedDelay
                worker.execute {
                    try {
                        val audio = recorder.snapshot()
                        if (!toggleActive.get() || audio.size < MIN_PARTIAL_SAMPLES) return@execute
                        val c = config
                        val text = provider.everyday()
                            .transcribe(audio, c.language, 1, c.initialPrompt())
                        val shown = RawPipeline.run(text)
                        if (toggleActive.get() && shown.isNotEmpty()) {
                            _state.value = _state.value.copy(partialText = shown)
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "partial skipped: ${t.message}")
                    } finally {
                        partialRunning.set(false)
                    }
                }
            },
            PARTIAL_FIRST_DELAY_MS,
            PARTIAL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Walk through the reveal, then insert.
     *
     * The pauses are the point: this is the app showing what it did to the user's words
     * before committing them, which is the difference between a tool that cleans up and a
     * tool that quietly rewrites.
     */
    private fun revealAndCommit(raw: String, cleaned: String) {
        val segments = TextDiff.diff(raw, cleaned)
        val changed = segments.any { it.kind != TextDiff.Kind.UNCHANGED }

        fun publish(stage: DictationStage) {
            _state.value = _state.value.copy(
                stage = stage,
                diff = segments,
                finalText = cleaned,
                partialText = raw,
            )
        }

        fun commit() {
            val target = toggleSink
            toggleSink = null
            sink = sinkBeforeToggle
            sinkBeforeToggle = null
            if (cleaned.isNotEmpty() && target != null) {
                runCatching { target.commit(cleaned) }.onFailure {
                    // Named, not generic. "Could not type into this field" was true of
                    // every failure and told nobody which one had happened; the surface
                    // that asked for the text is the one that knows why it would not go
                    // in, so its reason is what the user is shown.
                    _state.value = _state.value.copy(
                        status = DictationStatus.ERROR,
                        statusDetail = it.message?.takeIf(String::isNotBlank)
                            ?.replaceFirstChar(Char::uppercase)
                            ?: "Could not type into this field",
                    )
                }
            } else {
                // Logged because the two ways nothing appears in the field look identical
                // from outside: the text was never delivered, or it was refused. Only one
                // of them leaves a line here. See docs/OWNER-VERIFY.md.
                Log.i(TAG, "nothing to insert (empty=${cleaned.isEmpty()}, sink=${target != null})")
            }
            _state.value = _state.value.copy(
                stage = DictationStage.IDLE,
                partialText = "",
                diff = emptyList(),
            )
        }

        if (cleaned.isEmpty()) {
            _state.value = _state.value.copy(stage = DictationStage.IDLE, partialText = "")
            toggleSink = null
            return
        }

        // Nothing was changed, so there is nothing to show. Skipping the animation here
        // matters: a reveal that reveals nothing is just a delay.
        if (!changed) {
            publish(DictationStage.FINAL)
            timers.schedule({ commit() }, FINAL_HOLD_MS, TimeUnit.MILLISECONDS)
            return
        }

        // Only show a stage that has something to say. A "cleaning up" caption over text
        // with nothing struck through is a delay pretending to be an explanation.
        val removes = segments.any { it.kind == TextDiff.Kind.REMOVED }
        val adds = segments.any { it.kind == TextDiff.Kind.ADDED }

        fun punctuateThenFinish() {
            if (adds) {
                publish(DictationStage.PUNCTUATING)
                timers.schedule({
                    publish(DictationStage.FINAL)
                    timers.schedule({ commit() }, FINAL_HOLD_MS, TimeUnit.MILLISECONDS)
                }, PUNCTUATING_MS, TimeUnit.MILLISECONDS)
            } else {
                publish(DictationStage.FINAL)
                timers.schedule({ commit() }, FINAL_HOLD_MS, TimeUnit.MILLISECONDS)
            }
        }

        if (removes) {
            publish(DictationStage.CLEANING)
            timers.schedule({ punctuateThenFinish() }, CLEANING_MS, TimeUnit.MILLISECONDS)
        } else {
            punctuateThenFinish()
        }
    }

    // ------------------------------------------------------------- hands-free

    /**
     * Dictate without anything to hold down.
     *
     * The keyboard has a button you press and release, which is the most reliable
     * end-of-speech signal there is. The other two surfaces do not: a keyboard's own
     * microphone button routed here through the system voice-input service just starts
     * listening, and the floating bubble is a single tap. Both need Scribe to notice the
     * silence itself, which is what [SilenceEndpointer] is for.
     *
     * [onFinished] receives the cleaned text, or "" when nothing was said.
     */
    fun startHandsFree(
        packageName: String? = null,
        /**
         * Where the text should go. Null when the caller wants the transcript handed back
         * instead of typed — the system voice-input service returns it to whichever
         * keyboard asked, and inserting it here as well would type everything twice.
         */
        sink: TextSink? = null,
        onFinished: (text: String, raw: String) -> Unit,
        onFailed: (reason: String) -> Unit = {},
    ) {
        if (!hasMicPermission()) {
            onFailed("Scribe needs microphone access — open Scribe to allow it")
            return
        }
        this.sink = sink ?: DiscardingSink
        targetPackage = packageName
        endpointer.reset()
        handsFree = true
        pendingTranscript = onFinished
        pendingFailure = onFailed
        DictationService.start(appContext)
        machine.startRecording()
        if (machine.status != DictationMachine.Status.RECORDING) {
            handsFree = false
            pendingTranscript = null
            onFailed(_state.value.statusDetail)
        }
    }

    /** End a hands-free session early — the user tapped stop, or dismissed the bubble. */
    fun stopHandsFree() {
        if (!handsFree) return
        handsFree = false
        stopRecording()
    }

    fun cancelHandsFree() {
        handsFree = false
        pendingTranscript = null
        pendingFailure = null
        cancel()
        DictationService.stop(appContext)
    }

    /**
     * Accepts and drops text, for callers that take the transcript through the callback.
     * Without it the state machine would report "could not type into this field" for a
     * session that never intended to type anywhere.
     */
    private object DiscardingSink : TextSink {
        override fun commit(text: String) = Unit
        override fun replace(previous: String, next: String) = false
    }

    private val endpointer = SilenceEndpointer()

    @Volatile private var handsFree = false
    @Volatile private var pendingTranscript: ((String, String) -> Unit)? = null
    @Volatile private var pendingFailure: ((String) -> Unit)? = null

    private fun onHandsFreeLevel(rms: Float) {
        if (!handsFree) return
        when (endpointer.onLevel(rms, System.currentTimeMillis())) {
            SilenceEndpointer.Decision.CONTINUE -> Unit
            SilenceEndpointer.Decision.ENDED,
            SilenceEndpointer.Decision.TOO_LONG,
            -> {
                handsFree = false
                stopRecording()
            }
            SilenceEndpointer.Decision.NOTHING_HEARD -> {
                // Transcribing a room produces a confident sentence out of nothing.
                handsFree = false
                val fail = pendingFailure
                pendingTranscript = null
                pendingFailure = null
                cancel()
                DictationService.stop(appContext)
                fail?.invoke("Didn't catch anything")
            }
        }
    }

    // ---------------------------------------------------------------- events

    /**
     * Fold an engine event into the visible state.
     *
     * The reveal is started *after* the assignment below, never inside it. It publishes
     * states of its own, and running it inside the `when` meant the assignment that
     * followed immediately overwrote the stage it had just set — so the panel went on
     * saying "listening" for the whole time the final decode was running, which is exactly
     * the window in which the user needs to be told something has changed.
     */
    private fun onEvent(event: DictationEvent) {
        val current = _state.value
        _state.value = when (event) {
            is DictationEvent.ModelLoading -> current.copy(
                status = DictationStatus.LOADING,
                statusDetail = "Loading ${event.model}…",
                error = null,
            )
            is DictationEvent.ModelLoaded -> current.copy(
                status = DictationStatus.READY,
                statusDetail = "Ready",
                modelName = event.model,
                error = null,
            )
            DictationEvent.RecordingStarted -> current.copy(
                status = DictationStatus.RECORDING,
                statusDetail = "Listening…",
                error = null,
            )
            is DictationEvent.RecordingStopped -> {
                lastAudioSeconds = event.durationSec
                current
            }
            DictationEvent.Transcribing -> current.copy(
                status = DictationStatus.TRANSCRIBING,
                statusDetail = "Transcribing…",
                level = 0f,
            )
            is DictationEvent.Injected -> {
                recordHistory(event)
                recordSpeed(event)
                // The reveal publishes states of its own and runs *after* this assignment
                // — see the note on onEvent.
                current.copy(
                    status = DictationStatus.READY,
                    statusDetail = if (event.text.isEmpty()) "Nothing heard" else "Inserted",
                    lastText = event.text,
                    level = 0f,
                )
            }
            is DictationEvent.Boost -> current.copy(boostActive = event.active)
            is DictationEvent.Level -> {
                onHandsFreeLevel(event.rms)
                current.copy(level = event.rms)
            }
            is DictationEvent.Error -> current.copy(
                status = DictationStatus.ERROR,
                statusDetail = event.message,
                error = event.message,
                level = 0f,
            )
        }

        if (event is DictationEvent.Injected) {
            if (!deliverToggle(event)) deliverHandsFree(event)
        }
    }

    /**
     * Keep a running measure of how long decoding takes relative to speech.
     *
     * Measured from real use rather than a synthetic benchmark at startup: the result is
     * then true for this phone, this model and this person's utterances, and nobody waits
     * for a measurement at first launch that ordinary use would have produced anyway.
     *
     * A heavily smoothed average, because one thermally throttled decode is not evidence
     * that the phone is too slow for the model.
     */
    private fun recordSpeed(event: DictationEvent.Injected) {
        if (event.heavy || event.elapsedSec <= 0.0 || lastAudioSeconds <= 0.0) return
        val sample = event.elapsedSec / lastAudioSeconds
        if (sample <= 0.0 || sample > 30.0) return  // an outlier, not a measurement
        scope.launch {
            settings.update { c ->
                val previous = c.measuredRealTimeFactor
                val next = if (previous == null) sample else previous * 0.8 + sample * 0.2
                c.copy(measuredRealTimeFactor = next)
            }
        }
    }

    /** Length of the clip most recently handed to the decoder, for the speed measure. */
    @Volatile private var lastAudioSeconds: Double = 0.0

    private fun deliverToggle(event: DictationEvent.Injected): Boolean {
        if (toggleSink == null && !toggleActive.get()) return false
        revealAndCommit(raw = RawPipeline.run(event.raw), cleaned = event.text)
        return true
    }

    private fun deliverHandsFree(event: DictationEvent.Injected) {
        val waiting = pendingTranscript ?: return
        pendingTranscript = null
        pendingFailure = null
        handsFree = false
        DictationService.stop(appContext)
        runCatching { waiting(event.text, event.raw) }
    }

    private fun recordHistory(event: DictationEvent.Injected) {
        if (!config.historyEnabled || event.text.isEmpty()) return
        history.prepend(event.text, event.raw, event.backend, event.elapsedSec)
    }

    // ------------------------------------------------------------- polishing

    /** Applies the guarded polish stage, if the user has one loaded and enabled. */
    fun polish(text: String): String = guardedPolisher.apply(text)

    /** Reload the polish model after the user enables it or changes which one. */
    fun reloadPolisher() {
        worker.execute {
            polisher?.close()
            polisher = null
            guardedPolisher = GuardedPolisher(NoPolisher)
            _state.value = _state.value.copy(polishAvailable = false)
            loadPolisherIfEnabled()
        }
    }

    suspend fun currentConfig(): ScribeConfig = settings.config.first()

    companion object {
        private const val TAG = "ScribeEngine"

        /** Below about a second there is not enough audio for a useful partial. */
        private const val MIN_PARTIAL_SAMPLES = DictationMachine.SAMPLE_RATE

        private const val PARTIAL_FIRST_DELAY_MS = 1_200L
        private const val PARTIAL_INTERVAL_MS = 1_800L

        // Long enough to register a struck-through phrase, short enough that the text is
        // not being withheld. The whole reveal is under a second when there is something
        // to show, and is skipped when there is not.
        private const val CLEANING_MS = 620L
        private const val PUNCTUATING_MS = 420L
        private const val FINAL_HOLD_MS = 260L

        @Volatile private var instance: ScribeEngine? = null

        fun get(context: Context): ScribeEngine =
            instance ?: synchronized(this) {
                instance ?: ScribeEngine(context.applicationContext).also { instance = it }
            }
    }
}
