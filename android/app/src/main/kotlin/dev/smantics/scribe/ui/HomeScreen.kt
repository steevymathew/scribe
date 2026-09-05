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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import dev.smantics.scribe.settings.KeyboardWidth
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.settings.SplitMode
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.ModeToggle
import dev.smantics.scribe.ui.components.NavRow
import dev.smantics.scribe.ui.components.ScribeMark
import dev.smantics.scribe.ui.components.ScribeCard
import dev.smantics.scribe.ui.components.SecondaryButton
import dev.smantics.scribe.ui.components.SectionLabel
import dev.smantics.scribe.ui.components.neuActive
import dev.smantics.scribe.ui.components.neuInset
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
    system: SystemSetupState,
    onRequestMic: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onOpenKeyboardPicker: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BREAKPOINT_DP

    // Keyed on the setup state, which is itself re-read on resume, so returning from the
    // models screen or from system settings refreshes the count rather than showing the
    // number that happened to be true when this screen was first composed.
    val installedCount = remember(system) { engine.models.installed().size }
    val modelSummary = "Using ${config.model} · $installedCount installed"
    val vocabularySummary =
        "${config.dictionary.size} words · ${config.snippets.size} shortcuts"
    val historySummary =
        if (config.historyEnabled) "On — saved on this phone" else "Off — nothing is saved"


    val left: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge)) {
            StatusCard(state, config)
            if (!system.micGranted) {
                SetupCard(system = system, onRequestMic = onRequestMic)
            }
            // Permanently available, not only while setup is unfinished: these are the
            // routes into the product, and one of them being off is a thing the user needs
            // to be able to find and fix at any time.
            WaysToUseCard(
                system = system,
                onOpenKeyboardPicker = onOpenKeyboardPicker,
                onOpenKeyboardSettings = onOpenKeyboardSettings,
                onOpenAccessibility = onOpenAccessibility,
            )
            ModeCard(config) { engine.toggleMode() }
            KeyboardCard(config) { update -> scope.launch { engine.settings.update(update) } }
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
            TryItCard()
            AboutCard()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ScribeTokens.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
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
        ScribeMark(size = 34.dp)
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

/** The one thing nothing works without. */
@Composable
private fun SetupCard(
    system: SystemSetupState,
    onRequestMic: () -> Unit,
) {
    ScribeCard(testTag = "setup-card") {
        SectionLabel("FINISH SETTING UP")
        SetupRow(
            done = system.micGranted,
            title = "Allow the microphone",
            body = if (system.micPermanentlyDenied) {
                "Android has stopped asking. Switch it on in Scribe's settings."
            } else {
                "Recorded only while you're dictating."
            },
            action = if (system.micPermanentlyDenied) "Open settings" else "Allow",
            onAction = onRequestMic,
        )
    }
}

/**
 * The three ways to reach Scribe, and which of them are switched on.
 *
 * This is the most important card in the app. Scribe's first version shipped with only its
 * own keyboard, and on a real phone that turned out to be both the hardest route to set up
 * and the least likely one to be wanted: someone who already has a keyboard they like does
 * not want to swap it out to dictate a sentence. Any one of these is enough.
 */
@Composable
private fun WaysToUseCard(
    system: SystemSetupState,
    onOpenKeyboardPicker: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    ScribeCard(testTag = "ways-card") {
        SectionLabel("WAYS TO USE SCRIBE")
        Text("Either one works.", color = ScribeTokens.muted, fontSize = 13.sp)

        WayRow(
            on = system.bubbleEnabled,
            title = "A floating button",
            body = "Sits on screen and works in any app. Drag it anywhere, drag it to the " +
                "bottom to close.\n" +
                "Needs accessibility access — the only way an app may type into another app.",
            action = if (system.bubbleEnabled) "Accessibility settings" else "Turn on",
            testTag = "way-bubble",
            onAction = onOpenAccessibility,
            recommended = true,
            // Written from what actually happened on a Fold 7, not from what the intent
            // was expected to do. Android blocks accessibility access for anything
            // sideloaded, and the block is a dead end unless you know the way round it:
            // the toggle greys out, the dialog names "restricted settings", and the
            // control that lifts it is on a different screen in a different app with no
            // link from here to there.
            steps = listOf(
                "Tap Turn on. Find Scribe in the accessibility list and try to enable it.",
                "Android says restricted settings are blocking it. Close that dialog.",
                "Open Android Settings → Apps → Scribe.",
                "Tap the three dots, top right → Allow restricted settings.",
                "Go back to Accessibility → Scribe and turn it on. It stays on.",
            ),
            stepsTitle = "If Android refuses, it is not broken — do this",
        )

        WayRow(
            on = system.keyboardEnabled,
            title = "Scribe's own keyboard",
            body = if (system.keyboardEnabled && !system.keyboardSelected) {
                "On. Pick it from the keyboard switcher to dictate."
            } else {
                "Dictates and types. One tap back to your usual keyboard."
            },
            action = if (system.keyboardEnabled) "Choose keyboard" else "Turn on",
            testTag = "way-keyboard",
            onAction = if (system.keyboardEnabled) onOpenKeyboardPicker else onOpenKeyboardSettings,
        )
    }
}

@Composable
private fun WayRow(
    on: Boolean,
    title: String,
    body: String,
    action: String,
    testTag: String,
    onAction: () -> Unit,
    recommended: Boolean = false,
    /** The exact taps, when the obvious route does not work. Hidden until asked for. */
    steps: List<String> = emptyList(),
    stepsTitle: String = "How",
) {
    var stepsOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (on) {
                Glyph(GlyphName.CHECK, ScribeTokens.good, size = 18.dp)
            } else {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, ScribeTokens.faint, CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = ScribeTokens.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (on) Tag("ON", ScribeTokens.good)
                else if (recommended) Tag("EASIEST", ScribeTokens.accent)
            }
            Text(body, color = ScribeTokens.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall)) {
                SecondaryButton(action, testTag, onClick = onAction)
                if (steps.isNotEmpty()) {
                    SecondaryButton(
                        if (stepsOpen) "Hide steps" else "Steps",
                        "$testTag-steps",
                        onClick = { stepsOpen = !stepsOpen },
                    )
                }
            }
            if (stepsOpen && steps.isNotEmpty()) {
                Column(
                    Modifier
                        .testTag("$testTag-step-list")
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stepsTitle,
                        color = ScribeTokens.warn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    steps.forEachIndexed { index, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${index + 1}.",
                                color = ScribeTokens.faint,
                                fontSize = 12.sp,
                            )
                            Text(step, color = ScribeTokens.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
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
                        "Word for word."
                    } else {
                        "Punctuated, tidied, structured."
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
 * The shape of the keyboard, decided here rather than on the keyboard.
 *
 * Both of these used to be keys — a control that cycled the width blindly through three
 * values, and a toggle for the split — sitting permanently on a surface whose whole
 * argument is that it covers what the user is writing. They are decisions taken once for
 * a device, so they belong on a screen with room to say what they do, and the keyboard
 * gets the space back.
 */
@Composable
private fun KeyboardCard(config: ScribeConfig, onUpdate: ((ScribeConfig) -> ScribeConfig) -> Unit) {
    ScribeCard(testTag = "keyboard-card") {
        SectionLabel("KEYBOARD")
        ChoiceRow(
            title = "Split",
            body = "Two halves, one under each thumb. Automatic splits on a screen wide " +
                "enough to need it — an unfolded Fold, a tablet — and not otherwise.",
            options = SplitMode.entries.map { it to it.label },
            selected = config.keyboardSplit,
            testTag = "split-mode",
        ) { choice -> onUpdate { it.copy(keyboardSplit = choice) } }
        ChoiceRow(
            title = "Width",
            body = "A narrower keyboard sits under one thumb and leaves more of the app " +
                "visible above it.",
            options = KeyboardWidth.entries.map { it to it.label },
            selected = config.keyboardWidth,
            testTag = "keyboard-width",
        ) { choice -> onUpdate { it.copy(keyboardWidth = choice) } }
    }
}

/** A titled row of mutually exclusive chips. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    body: String,
    options: List<Pair<T, String>>,
    selected: T,
    testTag: String,
    onChoose: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = ScribeTokens.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(body, color = ScribeTokens.muted, fontSize = 12.sp)
        Row(
            Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
        ) {
            options.forEach { (value, label) ->
                val chosen = value == selected
                Box(
                    Modifier
                        .testTag("$testTag-$label")
                        .then(
                            if (chosen) {
                                Modifier.neuActive(
                                    RoundedCornerShape(ScribeTokens.radiusChip),
                                    ScribeTokens.accent,
                                )
                            } else {
                                Modifier.neuInset(
                                    RoundedCornerShape(ScribeTokens.radiusChip),
                                    depth = 0.7f,
                                )
                            },
                        )
                        .clickable { onChoose(value) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        label,
                        color = if (chosen) ScribeTokens.accent else ScribeTokens.muted,
                        fontSize = 12.sp,
                    )
                }
            }
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
 * Somewhere to actually try it.
 *
 * Every other control on this screen changes what happens the *next* time the user
 * dictates, somewhere else, in another app — so the effect of a toggle is invisible at the
 * moment it is flipped. This is a real text field: switch to the Scribe keyboard, dictate
 * into it, and see the result of the settings that are on screen.
 */
@Composable
private fun TryItCard() {
    var text by remember { mutableStateOf("") }
    ScribeCard(testTag = "try-it-card") {
        SectionLabel("TRY IT")
        Text(
            "Dictate in here to test your settings.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        Box(
            Modifier
                .testTag("demo-field")
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .clip(RoundedCornerShape(ScribeTokens.radiusSm))
                .background(ScribeTokens.s2)
                .padding(12.dp),
        ) {
            if (text.isEmpty()) {
                Text("Tap here, then dictate", color = ScribeTokens.faint, fontSize = 14.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = ScribeTokens.text, fontSize = 14.sp),
                cursorBrush = SolidColor(ScribeTokens.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotEmpty()) {
            SecondaryButton("Clear", "demo-clear") { text = "" }
        }
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
        ToggleRow("Remove fillers", "um, uh, erm", config.removeFillers) { v ->
            onUpdate { it.copy(removeFillers = v) }
        }
        ToggleRow("Spoken punctuation", "\"comma\" → ,", config.spokenPunctuation) { v ->
            onUpdate { it.copy(spokenPunctuation = v) }
        }
        ToggleRow(
            "Spoken corrections",
            "\"4pm, actually 3pm\" → 3pm",
            config.selfCorrection,
        ) { v -> onUpdate { it.copy(selfCorrection = v) } }
        ToggleRow(
            "Paragraphs and lists",
            "Breaks at pauses, numbers become list items",
            config.paragraphs,
        ) { v -> onUpdate { it.copy(paragraphs = v, splitSentences = v) } }
        ToggleRow(
            "Format numbers and lists",
            "times, percentages, bullets",
            config.formatNumbers,
        ) { v -> onUpdate { it.copy(formatNumbers = v, detectLists = v) } }
        ToggleRow(
            "Remove \"you know\", \"I mean\"",
            "Off — these are also real phrases",
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
            "Runs on this phone. Your audio never touches a network socket.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
    }
}
