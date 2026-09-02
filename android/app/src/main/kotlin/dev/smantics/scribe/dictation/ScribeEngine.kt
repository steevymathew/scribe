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
import dev.smantics.scribe.core.clean.PolishVerdict
import dev.smantics.scribe.core.dictation.DictationEvent
import dev.smantics.scribe.core.dictation.DictationMachine
import dev.smantics.scribe.core.dictation.MachineSettings
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
)

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
                config = c
                _state.value = _state.value.copy(mode = c.mode)
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
            loadPolisherIfEnabled()
        }
    }

    @Volatile private var warmedUp = false

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

    fun attach(sink: TextSink, packageName: String?) {
        this.sink = sink
        this.targetPackage = packageName
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

    // ---------------------------------------------------------------- events

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
                current.copy(
                    status = DictationStatus.READY,
                    statusDetail = if (event.text.isEmpty()) "Nothing heard" else "Inserted",
                    lastText = event.text,
                    level = 0f,
                )
            }
            is DictationEvent.Boost -> current.copy(boostActive = event.active)
            is DictationEvent.Level -> current.copy(level = event.rms)
            is DictationEvent.Error -> current.copy(
                status = DictationStatus.ERROR,
                statusDetail = event.message,
                error = event.message,
                level = 0f,
            )
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

        @Volatile private var instance: ScribeEngine? = null

        fun get(context: Context): ScribeEngine =
            instance ?: synchronized(this) {
                instance ?: ScribeEngine(context.applicationContext).also { instance = it }
            }
    }
}
