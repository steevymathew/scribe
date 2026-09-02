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
import androidx.compose.runtime.remember
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
import dev.smantics.scribe.ui.components.NavRow
import dev.smantics.scribe.ui.components.ScribeCard
import dev.smantics.scribe.ui.components.SecondaryButton
import dev.smantics.scribe.ui.components.SectionLabel
import dev.smantics.scribe.ui.components.Tag
import dev.smantics.scribe.ui.components.ToggleRow
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
    onOpenModels: () -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BREAKPOINT_DP

    val installedCount = remember { engine.models.installed().size }
    val modelSummary = "Using ${config.model} · $installedCount installed"
    val vocabularySummary =
        "${config.dictionary.size} words · ${config.snippets.size} shortcuts"
    val historySummary =
        if (config.historyEnabled) "On — saved on this phone" else "Off — nothing is saved"

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
            SpeedAdviceCard(engine, config, onOpenModels)
        }
    }

    val right: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge)) {
            MoreCard(
                modelSummary = modelSummary,
                vocabularySummary = vocabularySummary,
                historySummary = historySummary,
                onModels = onOpenModels,
                onVocabulary = onOpenVocabulary,
                onHistory = onOpenHistory,
            )
            CleanupCard(config) { update -> scope.launch { engine.settings.update(update) } }
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
    ScribeCard(testTag = "status-card") {
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
    ScribeCard(testTag = "setup-card") {
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
            SecondaryButton(action, "setup-${action.lowercase().replace(' ', '-')}", onClick = onAction)
        }
    }
}

/**
 * Advice about the model, but only once there is evidence for it.
 *
 * Nothing is shown until Scribe has measured how fast this phone actually decodes, from
 * real dictations. Guessing from the chipset name would be an interface stating something
 * it does not know, and the recommendation would be wrong for exactly the phones that most
 * need it right.
 */
@Composable
private fun SpeedAdviceCard(
    engine: ScribeEngine,
    config: ScribeConfig,
    onOpenModels: () -> Unit,
) {
    val rtf = config.measuredRealTimeFactor ?: return
    val totalRamMb = remember { engine.models.totalRamMb() }
    val suggested = ModelRegistry.recommend(totalRamMb, rtf)
    if (suggested.id == config.model) return

    val slower = rtf > 1.0
    ScribeCard(testTag = "speed-advice-card") {
        SectionLabel(if (slower) "THIS PHONE IS WORKING HARD" else "THIS PHONE HAS ROOM")
        Text(
            if (slower) {
                "Transcribing takes about %.1f× as long as you spend speaking. ".format(rtf) +
                    "${suggested.displayName} would keep up better."
            } else {
                "Transcribing takes about %.1f× as long as you spend speaking. ".format(rtf) +
                    "${suggested.displayName} would make fewer mistakes on names without " +
                    "feeling slow."
            },
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        SecondaryButton("Open models", "advice-open-models", onClick = onOpenModels)
    }
}

/** Raw / Clean, in the app as well as on the keyboard, so it is discoverable. */
@Composable
private fun ModeCard(config: ScribeConfig, onToggle: () -> Unit) {
    ScribeCard(testTag = "mode-card") {
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

/**
 * The way in to everything else.
 *
 * Rows rather than more cards: these are destinations, and a screen that renders every
 * setting inline is the wall the desktop's UX review complains about.
 */
@Composable
private fun MoreCard(
    modelSummary: String,
    vocabularySummary: String,
    historySummary: String,
    onModels: () -> Unit,
    onVocabulary: () -> Unit,
    onHistory: () -> Unit,
) {
    ScribeCard(testTag = "more-card") {
        SectionLabel("SETTINGS")
        NavRow("Models", modelSummary, "nav-models", onModels)
        NavRow("Words and shortcuts", vocabularySummary, "nav-vocabulary", onVocabulary)
        NavRow("History and network", historySummary, "nav-history", onHistory)
    }
}

/**
 * The Clean pipeline's toggles.
 *
 * The desktop roadmap asks for the pipeline to be "ordered, each step toggleable"; the
 * desktop shipped one toggle. These are the stages people actually disagree about — the
 * rest are always on because nobody wants doubled spaces.
 */
@Composable
private fun CleanupCard(config: ScribeConfig, onUpdate: ((ScribeConfig) -> ScribeConfig) -> Unit) {
    ScribeCard(testTag = "cleanup-card") {
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
        ToggleRow(
            "Format numbers and lists",
            "times, percentages, bullets",
            config.formatNumbers,
        ) { v -> onUpdate { it.copy(formatNumbers = v, detectLists = v) } }
        ToggleRow(
            "Remove \"you know\" and \"I mean\"",
            "Off by default — they are also real phrases",
            config.removeVerbalTics,
        ) { v -> onUpdate { it.copy(removeVerbalTics = v) } }
    }
}

@Composable
private fun AboutCard() {
    ScribeCard(testTag = "about-card") {
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
