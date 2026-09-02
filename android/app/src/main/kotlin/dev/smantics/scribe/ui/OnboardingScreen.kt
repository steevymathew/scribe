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
import androidx.compose.runtime.LaunchedEffect
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
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.Waveform
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
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val state by engine.state.collectAsState()

    // Re-checked every time this screen recomposes after the user comes back from the
    // system settings, so the step cannot claim success it has not observed.
    val keyboardEnabled = context.isScribeEnabled()

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
            1 -> MicrophoneStep(
                granted = micGranted,
                level = state.level,
                onRequest = onRequestMic,
            )
            2 -> KeyboardStep(enabled = keyboardEnabled, onOpen = onOpenKeyboardSettings)
            3 -> ModesStep()
            else -> DoneStep(onRequestNotifications)
        }

        Spacer(Modifier.height(ScribeTokens.gapLarge))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                GhostButton("Back") { step-- }
            }
            Spacer(Modifier.weight(1f))
            val lastStep = step >= 4
            PrimaryButton(
                label = if (lastStep) "Start dictating" else "Next",
                enabled = canAdvance(step, micGranted, keyboardEnabled),
                testTag = "onboarding-next",
            ) {
                if (lastStep) onFinish() else step++
            }
        }

        // Never a blocked button with no explanation: if Next is disabled, the reason is
        // written next to it.
        BlockedReason(step, micGranted, keyboardEnabled)
    }
}

/**
 * The microphone step is the only hard gate. The keyboard step can be skipped and revisited
 * from the home screen, because some people prefer to enable it from the system settings in
 * their own time and a wizard that refuses to end is worse than one that trusts them.
 */
private fun canAdvance(step: Int, micGranted: Boolean, keyboardEnabled: Boolean): Boolean =
    when (step) {
        1 -> micGranted
        else -> true
    }

@Composable
private fun BlockedReason(step: Int, micGranted: Boolean, keyboardEnabled: Boolean) {
    val reason = when {
        step == 1 && !micGranted ->
            "Scribe needs the microphone before it can transcribe anything."
        step == 2 && !keyboardEnabled ->
            "You can turn the keyboard on later from the Scribe home screen."
        else -> null
    } ?: return
    Text(reason, color = ScribeTokens.muted, fontSize = 13.sp)
}

@Composable
private fun Header(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gap)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
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
private fun BrandMark(size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * ScribeTokens.BRAND_RADIUS_RATIO))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(ScribeTokens.accent, ScribeTokens.accent2),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(GlyphName.MIC, ScribeTokens.onAccent, size = size * 0.53f, thickness = 1.6.dp)
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
private fun MicrophoneStep(granted: Boolean, level: Float, onRequest: () -> Unit) {
    StepBody(
        title = "Microphone",
        body = if (granted) {
            "Say something — the bars should move."
        } else {
            "Scribe records only while you hold the button, and the audio never leaves " +
                "this phone."
        },
    ) {
        if (granted) {
            Waveform(
                level = level,
                status = if (level > 0f) DictationStatus.RECORDING else DictationStatus.READY,
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
            PrimaryButton("Allow the microphone", enabled = true, testTag = "allow-mic", onClick = onRequest)
        }
    }
}

@Composable
private fun KeyboardStep(enabled: Boolean, onOpen: () -> Unit) {
    StepBody(
        title = "Turn on the keyboard",
        body = "Scribe works as a keyboard. That is what lets it type into any app " +
            "reliably, without a floating button that Android keeps shutting down.",
    ) {
        if (enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(GlyphName.CHECK, ScribeTokens.good, size = 18.dp)
                Text("Scribe keyboard is on", color = ScribeTokens.good, fontSize = 13.sp)
            }
        } else {
            Text(
                "In the screen that opens, switch on \"Scribe voice keyboard\", then come " +
                    "back here.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            PrimaryButton("Open keyboard settings", enabled = true, testTag = "open-ime-settings", onClick = onOpen)
        }
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

@Composable
private fun DoneStep(onRequestNotifications: () -> Unit) {
    LaunchedEffect(Unit) { onRequestNotifications() }
    StepBody(
        title = "Ready",
        body = "Tap a text box anywhere, switch to the Scribe keyboard, and hold the " +
            "microphone.",
    ) {
        Text(
            "The bolt beside the microphone turns on the high-accuracy model for names and " +
                "technical words. Everything else lives in Settings.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
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

@Composable
fun PrimaryButton(
    label: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(if (enabled) ScribeTokens.accent else ScribeTokens.s2)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = if (enabled) ScribeTokens.onAccent else ScribeTokens.faint,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GhostButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = ScribeTokens.muted, fontSize = 14.sp)
    }
}
