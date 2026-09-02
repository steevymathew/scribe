package dev.smantics.scribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.BuildConfig
import dev.smantics.scribe.NativeLibs
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.core.model.ModelRegistry
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.model.ModelState
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.ModeToggle
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.ScribeTokens
import dev.smantics.scribe.ui.theme.statusColor
import kotlinx.coroutines.launch

/**
 * The home screen.
 *
 * It is organised around the two questions this app exists to answer — *is Scribe ready to
 * use?* and *what will it type?* — and everything else defers to those. The setup
 * checklist is at the top and disappears once it is satisfied, because a permanent
 * checklist of things already done is just noise.
 *
 * On the Fold's inner display the content is laid out in two columns; on the cover screen
 * it is one. The breakpoint is width, not a device check, so it is right on a tablet and
 * in split-screen too.
 */
@Composable
fun HomeScreen(
    engine: ScribeEngine,
    config: ScribeConfig,
    state: EngineState,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onOpenKeyboardPicker: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BREAKPOINT_DP

    val keyboardEnabled = context.isScribeEnabled()
    val keyboardSelected = context.isScribeSelected()
    val setupComplete = micGranted && keyboardEnabled

    val left: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge)) {
            StatusCard(state, config)
            if (!setupComplete) {
                SetupCard(
                    micGranted = micGranted,
                    keyboardEnabled = keyboardEnabled,
                    keyboardSelected = keyboardSelected,
                    onRequestMic = onRequestMic,
                    onOpenKeyboardSettings = onOpenKeyboardSettings,
                    onOpenKeyboardPicker = onOpenKeyboardPicker,
                )
            }
            ModeCard(config) { engine.toggleMode() }
        }
    }

    val right: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge)) {
            ModelsCard(engine, config)
            CleanupCard(config) { update -> scope.launch { engine.settings.update(update) } }
            PrivacyCard(engine, config) { update -> scope.launch { engine.settings.update(update) } }
            AboutCard()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ScribeTokens.bg)
            .verticalScroll(rememberScrollState())
            .padding(ScribeTokens.pageInset),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge),
    ) {
        TitleRow()
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge)) {
                Box(Modifier.weight(1f)) { left() }
                Box(Modifier.weight(1f)) { right() }
            }
        } else {
            left()
            right()
        }
    }
}

private const val WIDE_BREAKPOINT_DP = 700

@Composable
private fun TitleRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(34.dp * ScribeTokens.BRAND_RADIUS_RATIO))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(ScribeTokens.accent, ScribeTokens.accent2),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(GlyphName.MIC, ScribeTokens.onAccent, size = 18.dp, thickness = 1.6.dp)
        }
        Text("Scribe", color = ScribeTokens.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Status.
 *
 * The rule the desktop's own UX review put first: an interface must not state things that
 * are not true. So this reads the engine's real status rather than assuming readiness, and
 * an error is shown here, in the place the user is already looking, not left in a log.
 */
@Composable
private fun StatusCard(state: EngineState, config: ScribeConfig) {
    Card(testTag = "status-card") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(state.status)))
            Text(
                state.statusDetail,
                color = if (state.error != null) ScribeTokens.rec else ScribeTokens.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (state.modelName.isNotEmpty()) {
            Text(
                "Listening with ${state.modelName}",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
        }
        if (!NativeLibs.whisperAvailable) {
            Text(
                "The speech engine did not load in this build. Dictation is unavailable.",
                color = ScribeTokens.rec,
                fontSize = 13.sp,
            )
        }
    }
}

/** The three things that must be true before the keyboard can do anything. */
@Composable
private fun SetupCard(
    micGranted: Boolean,
    keyboardEnabled: Boolean,
    keyboardSelected: Boolean,
    onRequestMic: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onOpenKeyboardPicker: () -> Unit,
) {
    Card(testTag = "setup-card") {
        SectionLabel("FINISH SETTING UP")
        SetupRow(
            done = micGranted,
            title = "Allow the microphone",
            body = "Scribe records only while you hold the button.",
            action = "Allow",
            onAction = onRequestMic,
        )
        SetupRow(
            done = keyboardEnabled,
            title = "Turn on the Scribe keyboard",
            body = "In Settings, switch on \"Scribe voice keyboard\".",
            action = "Open settings",
            onAction = onOpenKeyboardSettings,
        )
        SetupRow(
            done = keyboardSelected,
            title = "Switch to it when you want to dictate",
            body = "You can go back to your usual keyboard in one tap.",
            action = "Choose keyboard",
            onAction = onOpenKeyboardPicker,
        )
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) {
                Glyph(GlyphName.CHECK, ScribeTokens.good, size = 18.dp)
            } else {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, ScribeTokens.warn, CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = if (done) ScribeTokens.muted else ScribeTokens.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!done) Text(body, color = ScribeTokens.muted, fontSize = 13.sp)
        }
        if (!done) {
            SmallButton(action, onAction)
        }
    }
}

/** Raw / Clean, in the app as well as on the keyboard, so it is discoverable. */
@Composable
private fun ModeCard(config: ScribeConfig, onToggle: () -> Unit) {
    Card(testTag = "mode-card") {
        SectionLabel("WHAT SCRIBE TYPES")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (config.mode == Mode.RAW) "Raw" else "Clean",
                    color = ScribeTokens.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (config.mode == Mode.RAW) {
                        "Word for word, exactly as spoken."
                    } else {
                        "Punctuated, de-filled and formatted."
                    },
                    color = ScribeTokens.muted,
                    fontSize = 13.sp,
                )
            }
            ModeToggle(config.mode, enabled = true, onToggle = onToggle)
        }
    }
}

@Composable
private fun ModelsCard(engine: ScribeEngine, config: ScribeConfig) {
    Card(testTag = "models-card") {
        SectionLabel("SPEECH MODELS")
        ModelRegistry.speech.forEach { spec ->
            val installed = engine.models.state(spec) is ModelState.Installed
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            spec.displayName,
                            color = ScribeTokens.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (spec.id == config.model) {
                            Tag("IN USE", ScribeTokens.accent)
                        }
                    }
                    Text(
                        "${spec.tier.label} · ${spec.sizeMb} MB" +
                            if (spec.bundled) " · included" else "",
                        color = ScribeTokens.faint,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    if (installed) "Installed" else "Not installed",
                    color = if (installed) ScribeTokens.good else ScribeTokens.muted,
                    fontSize = 12.sp,
                )
            }
        }
        if (!BuildConfig.NETWORK_ALLOWED) {
            Text(
                "This is the offline build: it has no internet permission at all. Add other " +
                    "models by copying the file onto the phone.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun CleanupCard(config: ScribeConfig, onUpdate: ((ScribeConfig) -> ScribeConfig) -> Unit) {
    Card(testTag = "cleanup-card") {
        SectionLabel("CLEAN MODE")
        ToggleRow("Remove filler words", "um, uh, erm", config.removeFillers) { v ->
            onUpdate { it.copy(removeFillers = v) }
        }
        ToggleRow("Spoken punctuation", "\"comma\" becomes ,", config.spokenPunctuation) { v ->
            onUpdate { it.copy(spokenPunctuation = v) }
        }
        ToggleRow(
            "Apply spoken corrections",
            "\"4pm, actually 3pm\" becomes 3pm",
            config.selfCorrection,
        ) { v -> onUpdate { it.copy(selfCorrection = v) } }
        ToggleRow("Format numbers and lists", "times, percentages, bullets", config.formatNumbers) { v ->
            onUpdate { it.copy(formatNumbers = v, detectLists = v) }
        }
        ToggleRow(
            "Remove \"you know\" and \"I mean\"",
            "Off by default — they are also real phrases",
            config.removeVerbalTics,
        ) { v -> onUpdate { it.copy(removeVerbalTics = v) } }
    }
}

@Composable
private fun PrivacyCard(
    engine: ScribeEngine,
    config: ScribeConfig,
    onUpdate: ((ScribeConfig) -> ScribeConfig) -> Unit,
) {
    Card(testTag = "privacy-card") {
        SectionLabel("PRIVACY")
        ToggleRow(
            "Keep a history of what I dictate",
            "Saved on this phone only. Off by default.",
            config.historyEnabled,
        ) { v -> onUpdate { it.copy(historyEnabled = v) } }

        val requests = engine.models.ledger.entries()
        Text(
            if (!BuildConfig.NETWORK_ALLOWED) {
                "This build has no internet permission, so there is nothing to list."
            } else if (requests.isEmpty()) {
                "Scribe has never made a network request."
            } else {
                "Network requests Scribe has made: ${requests.size}. All of them are model " +
                    "downloads you asked for."
            },
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        requests.take(3).forEach { entry ->
            Text("${entry.whenText}  ${entry.reason}", color = ScribeTokens.faint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AboutCard() {
    Card(testTag = "about-card") {
        SectionLabel("ABOUT")
        Text(
            "Scribe ${BuildConfig.VERSION_NAME}",
            color = ScribeTokens.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Speech recognition runs on this phone with whisper.cpp. Your audio never " +
                "touches a network socket.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
    }
}

// ------------------------------------------------------------------ building blocks

/** The card surface from the desktop build: s1 fill, hairline stroke, 16dp radius. */
@Composable
private fun Card(testTag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radius))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = ScribeTokens.faint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
    )
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = ScribeTokens.text, fontSize = 14.sp)
            Text(body, color = ScribeTokens.faint, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ScribeTokens.onAccent,
                checkedTrackColor = ScribeTokens.accent,
                uncheckedThumbColor = ScribeTokens.muted,
                uncheckedTrackColor = ScribeTokens.s2,
                uncheckedBorderColor = ScribeTokens.stroke2,
            ),
        )
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(ScribeTokens.s2)
            .border(1.dp, ScribeTokens.stroke2, RoundedCornerShape(ScribeTokens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = ScribeTokens.text, fontSize = 13.sp)
    }
}

@Composable
private fun Tag(label: String, colour: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusPill))
            .background(colour.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(label, color = colour, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
    }
}
