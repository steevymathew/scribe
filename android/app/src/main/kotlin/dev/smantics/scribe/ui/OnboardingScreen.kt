package dev.smantics.scribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.model.ModelRegistry
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.model.ModelState
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.GhostButton
import dev.smantics.scribe.ui.components.PrimaryButton
import dev.smantics.scribe.ui.components.ScribeMark
import dev.smantics.scribe.ui.components.SecondaryButton
import dev.smantics.scribe.ui.components.Waveform
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * First run.
 *
 * Ported from the desktop's five-step wizard (`Wizard.qml`), including its copy, because
 * the copy is the product's argument: *everything stays on this device*. What changes is
 * step 3 — a phone has no push-to-talk key, so instead the user enables Scribe as a
 * keyboard, which is the one genuinely unfamiliar step in the whole setup.
 *
 * The desktop wizard's step 4 downloads a 500 MB model. This one does not: the everyday
 * model ships inside the APK, so setup finishes without touching the network at all. That
 * is a straightforwardly better first run, and it is only possible because the phone build
 * bundles its model.
 */
@Composable
fun OnboardingScreen(
    engine: ScribeEngine,
    system: SystemSetupState,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val state by engine.state.collectAsState()
    val keyboardEnabled = system.keyboardEnabled

    // The microphone step shows a live level so the user can see Scribe hearing them.
    // A meter that cannot move would be the worst possible first impression for a
    // dictation app: it is indistinguishable from a broken microphone.
    DisposableEffect(step, system.micGranted) {
        if (step == MIC_STEP && system.micGranted) engine.startLevelPreview()
        onDispose { engine.stopLevelPreview() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ScribeTokens.bg)
            .verticalScroll(rememberScrollState())
            .padding(ScribeTokens.pageInset),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge),
    ) {
        Header(step)

        when (step) {
            0 -> WelcomeStep()
            MIC_STEP -> MicrophoneStep(
                granted = system.micGranted,
                permanentlyDenied = system.micPermanentlyDenied,
                level = state.level,
                onRequest = onRequestMic,
            )
            2 -> KeyboardStep(enabled = keyboardEnabled, onOpen = onOpenKeyboardSettings)
            3 -> ModesStep()
            else -> DoneStep(
                state = state,
                heavyModelInstalled = remember {
                    engine.models.state(ModelRegistry.SHARP) is ModelState.Installed
                },
                onRequestNotifications = onRequestNotifications,
            )
        }

        Spacer(Modifier.height(ScribeTokens.gapLarge))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                GhostButton("Back", "onboarding-back") { step-- }
            }
            Spacer(Modifier.weight(1f))
            val lastStep = step >= 4
            PrimaryButton(
                label = if (lastStep) "Start dictating" else "Next",
                enabled = canAdvance(step, system),
                testTag = "onboarding-next",
            ) {
                if (lastStep) onFinish() else step++
            }
        }

        // Never a blocked button with no explanation: if Next is disabled, the reason is
        // written next to it.
        BlockedReason(step, system)
    }
}

/** The microphone step's index, named because three places depend on it. */
private const val MIC_STEP = 1

/**
 * The microphone step gates the wizard — but only while Android is still willing to ask.
 *
 * Once the permission has been refused twice the system stops showing the dialog and
 * silently answers "denied", at which point holding the wizard shut would trap the user in
 * two screens with no exit and an inert button. So the gate lifts, the button changes to
 * one that opens Scribe's settings page, and the outstanding item moves to the home
 * screen's checklist where it can be dealt with at any time.
 *
 * The keyboard step never gates: some people prefer to enable it in their own time, and a
 * wizard that refuses to end is worse than one that trusts them.
 */
private fun canAdvance(step: Int, system: SystemSetupState): Boolean =
    when (step) {
        MIC_STEP -> system.micGranted || system.micPermanentlyDenied
        else -> true
    }

@Composable
private fun BlockedReason(step: Int, system: SystemSetupState) {
    val blocked = !canAdvance(step, system)
    val reason = when {
        step == MIC_STEP && blocked ->
            "Scribe needs the microphone before it can transcribe anything."
        step == MIC_STEP && system.micPermanentlyDenied ->
            "You can carry on without it, but Scribe cannot transcribe until the " +
                "microphone is allowed."
        step == 2 && !system.keyboardEnabled ->
            "Not now? You can turn the keyboard on later from the Scribe home screen."
        else -> null
    } ?: return
    Text(
        reason,
        color = if (blocked) ScribeTokens.warn else ScribeTokens.muted,
        fontSize = 13.sp,
    )
}

@Composable
private fun Header(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gap)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScribeMark(size = 30.dp)
            Text("Set up Scribe", color = ScribeTokens.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier
                        .height(6.dp)
                        .widthIn(min = if (i == step) 22.dp else 6.dp)
                        .clip(RoundedCornerShape(ScribeTokens.radiusPill))
                        .background(if (i <= step) ScribeTokens.accent else ScribeTokens.s2),
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepBody(
        title = "Talk instead of type",
        body = "Hold the button, speak, let go — Scribe types your words wherever your " +
            "cursor is, in any app.",
    ) {
        Text(
            "Everything stays on this device. Your voice is transcribed here and never " +
                "leaves your phone — no account, no cloud, no telemetry.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall)) {
            Chip("On-device", ScribeTokens.good)
            Chip("No account", ScribeTokens.good)
            Chip("Works offline", ScribeTokens.good)
        }
    }
}

@Composable
private fun MicrophoneStep(
    granted: Boolean,
    permanentlyDenied: Boolean,
    level: Float,
    onRequest: () -> Unit,
) {
    StepBody(
        title = "Microphone",
        body = when {
            granted -> "Say something — the bars should move."
            permanentlyDenied -> "Android has stopped asking, so this has to be switched " +
                "on in Scribe's settings."
            else -> "Scribe records only while you hold the button, and the audio never " +
                "leaves this phone."
        },
    ) {
        if (granted) {
            // Live, not decorative: the level is coming from a real capture opened for
            // this step only. If these bars do not move, the microphone is the problem
            // and the user finds out here rather than mid-sentence.
            Waveform(
                level = level,
                stage = DictationStage.LISTENING,
                boostActive = false,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(GlyphName.CHECK, ScribeTokens.good, size = 18.dp)
                Text("Microphone allowed", color = ScribeTokens.good, fontSize = 13.sp)
            }
        } else {
            PrimaryButton(
                label = if (permanentlyDenied) "Open Scribe's settings" else "Allow the microphone",
                enabled = true,
                testTag = "allow-mic",
                onClick = onRequest,
            )
        }
    }
}

@Composable
private fun KeyboardStep(enabled: Boolean, onOpen: () -> Unit) {
    StepBody(
        title = "How you'll use it",
        body = "Scribe can work three ways. You only need one, and you can change your " +
            "mind later from the home screen.",
    ) {
        RouteExplainer(
            "In the keyboard you already have",
            "Set Scribe as the phone's voice input, then press the microphone button on " +
                "whatever keyboard you use. Nothing to switch to. This is the easiest one.",
        )
        RouteExplainer(
            "A button on text fields",
            "A small Scribe button appears whenever you tap a text box.",
        )
        RouteExplainer(
            "Scribe's own keyboard",
            "A full dictation panel you switch to, and back from, in one tap.",
        )
        if (enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(GlyphName.CHECK, ScribeTokens.good, size = 18.dp)
                Text("Scribe keyboard is on", color = ScribeTokens.good, fontSize = 13.sp)
            }
        }
        Text(
            "Finish setting up, and the home screen will walk you through whichever you pick.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun RouteExplainer(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radiusSm))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = ScribeTokens.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(body, color = ScribeTokens.muted, fontSize = 12.sp)
    }
}

@Composable
private fun ModesStep() {
    StepBody(
        title = "Raw and Clean",
        body = "Next to the waveform there is a switch with two settings. It decides what " +
            "Scribe types.",
    ) {
        ModeExplainer(
            "RAW",
            "Exactly what you said, word for word — including the ums.",
            "um so i think we should ship it friday",
        )
        ModeExplainer(
            "CLEAN",
            "Punctuated and tidied. Fillers go, spoken punctuation becomes punctuation, " +
                "and corrections you say out loud are applied.",
            "So I think we should ship it Friday.",
        )
        Text(
            "You can flip the switch after Scribe has typed and it will rewrite what it " +
                "just inserted. Nothing is re-recorded.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ModeExplainer(label: String, description: String, example: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radiusSm))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = ScribeTokens.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
        Text(description, color = ScribeTokens.muted, fontSize = 13.sp)
        Text("“$example”", color = ScribeTokens.text, fontSize = 13.sp)
    }
}

/**
 * The last step reports the engine's real state rather than asserting readiness.
 *
 * Staging the bundled model and loading it happen on a background thread while the wizard
 * runs. If either failed, a screen headed "Ready" would be the interface stating something
 * it has not checked — and the user would be sent to dictate with no engine and no idea
 * why nothing happens.
 */
@Composable
private fun DoneStep(
    state: EngineState,
    heavyModelInstalled: Boolean,
    onRequestNotifications: () -> Unit,
) {
    val ready = state.status == DictationStatus.READY
    StepBody(
        title = when (state.status) {
            DictationStatus.ERROR -> "Something went wrong"
            DictationStatus.READY -> "Ready"
            else -> "Nearly there"
        },
        body = when (state.status) {
            DictationStatus.ERROR -> state.statusDetail
            DictationStatus.READY ->
                "Tap a text box anywhere, switch to the Scribe keyboard, and hold the " +
                    "microphone."
            else -> "Getting the speech model ready…"
        },
    ) {
        if (state.status == DictationStatus.ERROR) {
            Text(
                "You can still finish setup — Settings → Models has the recovery options.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
        }
        if (ready && heavyModelInstalled) {
            Text(
                "The bolt beside the microphone turns on the high-accuracy model for names " +
                    "and technical words.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
        } else if (ready) {
            // Do not promise the high-accuracy model on a fresh install: it is a 190 MB
            // download that is not there yet, and finding that out mid-sentence — on a
            // plane, which is exactly why someone chose an offline dictation app — is a
            // promise made in setup and broken on the first try.
            Text(
                "A more accurate model is available in Settings → Models when you want it.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
        }

        // Asked for explicitly rather than sprung as a dialog on a screen that says
        // "Ready". Scribe's whole pitch is that it asks for as little as possible.
        Text(
            "Scribe shows a notice while the microphone is open, so you always know when " +
                "it is listening.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        SecondaryButton("Allow that notice", "allow-notifications", onClick = onRequestNotifications)
    }
}

@Composable
private fun StepBody(title: String, body: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gap)) {
        Text(title, color = ScribeTokens.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = ScribeTokens.muted, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun Chip(label: String, dot: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusPill))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radiusPill))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label, color = ScribeTokens.muted, fontSize = 12.sp)
    }
}
