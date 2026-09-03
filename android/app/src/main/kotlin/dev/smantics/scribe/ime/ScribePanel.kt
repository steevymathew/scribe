package dev.smantics.scribe.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.ModeToggle
import dev.smantics.scribe.ui.components.TranscriptReveal
import dev.smantics.scribe.ui.components.Waveform
import dev.smantics.scribe.ui.components.neuActive
import dev.smantics.scribe.ui.components.neuInset
import dev.smantics.scribe.ui.components.neuRaised
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.LocalHandedness
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens
import dev.smantics.scribe.ui.theme.statusColor
import kotlinx.coroutines.withTimeoutOrNull

/** What the panel can ask the service to do. */
interface PanelActions {
    /** Start or stop dictating. One tap each way. */
    fun toggleDictation()

    /** Throw away the utterance in progress. */
    fun cancelDictation()

    fun toggleMode()
    fun setBoost(held: Boolean)
    fun type(text: String)
    fun backspace()
    fun deleteWord()
    fun space()
    fun enter()
    fun switchKeyboard()
    fun openApp()
}

/**
 * The keyboard.
 *
 * It is a keyboard first and a dictation panel second, which is the correction the first
 * version needed: shipping voice-only meant that fixing a single misheard word sent the
 * user off to another keyboard, and a dictation keyboard you have to leave in order to
 * type is not a keyboard, it is a dialog.
 *
 * Dictating is a toggle rather than a hold. The letters stay put while it runs and the
 * transcript builds underneath the waveform, so stopping and fixing a word is one tap
 * rather than a change of mode.
 */
@Composable
fun ScribePanel(
    state: EngineState,
    actions: PanelActions,
    modifier: Modifier = Modifier,
) {
    var layer by remember { mutableStateOf(KeyboardLayer.LETTERS) }
    var shift by remember { mutableStateOf(ShiftState.OFF) }

    Column(
        modifier
            .fillMaxWidth()
            .background(ScribeTokens.bg)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.status == DictationStatus.NEEDS_PERMISSION) {
            PermissionRow(onOpenApp = actions::openApp)
        } else {
            VoiceStrip(state, actions)
            AnimatedVisibility(
                visible = state.stage != DictationStage.IDLE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                TranscriptReveal(
                    stage = state.stage,
                    partialText = state.partialText,
                    diff = state.diff,
                    finalText = state.finalText,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }

        Keys(
            layer = layer,
            shift = shift,
            state = state,
            actions = actions,
            onLayer = {
                layer = if (layer == KeyboardLayer.LETTERS) {
                    KeyboardLayer.SYMBOLS
                } else {
                    KeyboardLayer.LETTERS
                }
                shift = ShiftState.OFF
            },
            onShift = {
                shift = when (shift) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> ShiftState.LOCKED
                    ShiftState.LOCKED -> ShiftState.OFF
                }
            },
            onTypedCharacter = {
                // One-shot shift releases after a single letter, as every other keyboard
                // behaves. Caps lock does not.
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
            },
        )
    }
}

/**
 * The dictation controls: waveform, mode, microphone.
 *
 * While an utterance is being finished the strip grows a discard control, so the reveal
 * can be thrown away rather than only waited out.
 */
@Composable
private fun VoiceStrip(state: EngineState, actions: PanelActions) {
    val toggleFirst = LocalHandedness.current == Handedness.LEFT
    val listening = state.stage == DictationStage.LISTENING
    val finishing = state.stage != DictationStage.IDLE && !listening

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(visible = finishing) {
                RoundControl(
                    glyph = GlyphName.BACKSPACE,
                    description = "Discard this dictation",
                    testTag = "voice-cancel",
                    tint = ScribeTokens.rec,
                    onClick = actions::cancelDictation,
                )
            }

            if (toggleFirst) {
                ModeToggle(state.mode, enabled = true, onToggle = actions::toggleMode)
                Waveform(
                    state.level, state.status, state.boostActive,
                    Modifier.weight(1f), height = 32.dp, barCount = 20,
                )
            } else {
                Waveform(
                    state.level, state.status, state.boostActive,
                    Modifier.weight(1f), height = 32.dp, barCount = 20,
                )
                ModeToggle(state.mode, enabled = true, onToggle = actions::toggleMode)
            }

            MicToggle(state, actions::toggleDictation)
            BoostButton(state.boostActive, state.heavyModelAvailable, actions::setBoost)
        }

        // Status goes under the strip, not beside it: the strip is already several
        // controls wide on a cover screen, and an error must never be what gets truncated.
        if (state.stage == DictationStage.IDLE) {
            Text(
                text = state.statusDetail,
                color = if (state.error != null) ScribeTokens.rec else ScribeTokens.faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp).testTag("status-detail"),
            )
        }
    }
}

/**
 * Start and stop dictating.
 *
 * A toggle, not press-and-hold. Holding is precise, but it occupies the hand holding the
 * phone and it makes a live transcript pointless — there is no reading what is being heard
 * while a thumb is pinned to a button.
 */
@Composable
private fun MicToggle(state: EngineState, onToggle: () -> Unit) {
    val listening = state.stage == DictationStage.LISTENING
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ScribeMotion.PULSE_RING),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-pulse",
    )

    Box(
        Modifier
            .testTag("mic-button")
            .size(52.dp)
            .scale(if (listening) pulse else 1f)
            .then(
                if (listening) {
                    Modifier.neuActive(CircleShape, ScribeTokens.rec)
                } else {
                    Modifier.neuRaised(CircleShape)
                },
            )
            .semantics {
                contentDescription = if (listening) "Stop dictating" else "Start dictating"
            }
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            GlyphName.MIC,
            if (listening) ScribeTokens.rec else statusColor(state.status),
            size = 24.dp,
            thickness = 2.dp,
        )
    }
}

@Composable
private fun BoostButton(active: Boolean, available: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .testTag("boost-button")
            .size(38.dp)
            .then(
                if (active && available) {
                    Modifier.neuActive(CircleShape, ScribeTokens.warn)
                } else {
                    Modifier.neuInset(CircleShape, depth = 0.7f)
                },
            )
            .semantics {
                contentDescription = when {
                    !available -> "High accuracy needs the Small model — get it in Scribe"
                    active -> "High accuracy on"
                    else -> "Turn on high accuracy"
                }
            }
            .clickable(enabled = available) { onChange(!active) },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            GlyphName.BOLT,
            when {
                !available -> ScribeTokens.faint.copy(alpha = 0.4f)
                active -> ScribeTokens.warn
                else -> ScribeTokens.faint
            },
            size = 16.dp,
            thickness = 1.6.dp,
        )
    }
}

@Composable
private fun RoundControl(
    glyph: GlyphName,
    description: String,
    testTag: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .testTag(testTag)
            .size(38.dp)
            .neuInset(CircleShape, depth = 0.7f)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, tint, size = 17.dp, thickness = 1.7.dp)
    }
}

// ------------------------------------------------------------------------ the keys

@Composable
private fun Keys(
    layer: KeyboardLayer,
    shift: ShiftState,
    state: EngineState,
    actions: PanelActions,
    onLayer: () -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (KeyboardLayout.rowsFor(layer, shift) + listOf(KeyboardLayout.bottomRow)).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row.forEach { key ->
                    KeyButton(
                        key, layer, shift, state, actions,
                        onLayer, onShift, onTypedCharacter,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    key: Key,
    layer: KeyboardLayer,
    shift: ShiftState,
    state: EngineState,
    actions: PanelActions,
    onLayer: () -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
) {
    val label: String? = when (key) {
        is Key.Char -> KeyboardLayout.textFor(key, layer, shift)
        is Key.Layer -> if (layer == KeyboardLayer.LETTERS) "?123" else "ABC"
        else -> null
    }
    val glyph: GlyphName? = when (key) {
        is Key.Backspace -> GlyphName.BACKSPACE
        is Key.Enter -> GlyphName.RETURN
        is Key.Space -> GlyphName.SPACE
        is Key.Mic -> GlyphName.MIC
        is Key.SwitchIme -> GlyphName.KEYBOARD
        is Key.Shift -> GlyphName.BOLT
        else -> null
    }
    val description = when (key) {
        is Key.Char -> label.orEmpty()
        is Key.Shift -> "Shift"
        is Key.Backspace -> "Backspace"
        is Key.Space -> "Space"
        is Key.Enter -> "Enter"
        is Key.Layer -> if (layer == KeyboardLayer.LETTERS) "Symbols" else "Letters"
        is Key.Mic -> "Dictate"
        is Key.SwitchIme -> "Switch keyboard"
    }

    val listening = state.stage == DictationStage.LISTENING
    val emphasised = (key is Key.Shift && shift != ShiftState.OFF) ||
        (key is Key.Mic && listening)

    Box(
        Modifier
            .testTag("key-$description")
            .weight(KeyboardLayout.weightOf(key))
            .height(46.dp)
            .then(
                when {
                    emphasised -> Modifier.neuActive(
                        RoundedCornerShape(ScribeTokens.radiusChip),
                        if (key is Key.Mic) ScribeTokens.rec else ScribeTokens.accent,
                    )
                    key is Key.Char || key is Key.Space ->
                        Modifier.neuRaised(RoundedCornerShape(ScribeTokens.radiusChip))
                    else -> Modifier.neuInset(
                        RoundedCornerShape(ScribeTokens.radiusChip),
                        depth = 0.7f,
                    )
                },
            )
            .semantics { contentDescription = description }
            .keyGestures(
                key = key,
                actions = actions,
                onLayer = onLayer,
                onShift = onShift,
                onTypedCharacter = onTypedCharacter,
                onCharacter = {
                    if (key is Key.Char) {
                        actions.type(KeyboardLayout.textFor(key, layer, shift))
                        onTypedCharacter()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            label != null -> Text(
                label,
                color = ScribeTokens.text,
                fontSize = if (label.length > 2) 13.sp else 17.sp,
            )
            key is Key.Shift -> Glyph(
                GlyphName.BOLT,
                when (shift) {
                    ShiftState.LOCKED -> ScribeTokens.accent
                    ShiftState.ONCE -> ScribeTokens.text
                    ShiftState.OFF -> ScribeTokens.muted
                },
                size = 18.dp,
                thickness = 1.7.dp,
            )
            glyph != null -> Glyph(
                glyph,
                if (key is Key.Mic && listening) ScribeTokens.rec else ScribeTokens.muted,
                size = 19.dp,
                thickness = 1.7.dp,
            )
        }
    }
}

/**
 * Press, and hold to repeat.
 *
 * Backspace repeats **by word** once it is held, which is the difference between clearing
 * a misheard sentence in two seconds and holding the key down for twenty. Dictation
 * produces whole phrases, so the word is the right unit for this keyboard in a way it is
 * not for a purely typing one.
 */
private fun Modifier.keyGestures(
    key: Key,
    actions: PanelActions,
    onLayer: () -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
    onCharacter: () -> Unit,
): Modifier = this.pointerInput(key, actions) {
    detectTapGestures(
        onPress = {
            if (key !is Key.Backspace) return@detectTapGestures
            actions.backspace()
            // Still held after the threshold? Switch to whole words until the finger lifts.
            val liftedEarly = withTimeoutOrNull(REPEAT_DELAY_MS) { tryAwaitRelease() }
            if (liftedEarly == null) {
                var holding = true
                while (holding) {
                    actions.deleteWord()
                    val lifted = withTimeoutOrNull(REPEAT_INTERVAL_MS) { tryAwaitRelease() }
                    if (lifted != null) holding = false
                }
            }
        },
        onTap = {
            when (key) {
                is Key.Char -> onCharacter()
                is Key.Shift -> onShift()
                is Key.Layer -> onLayer()
                is Key.Space -> {
                    actions.space()
                    onTypedCharacter()
                }
                is Key.Enter -> actions.enter()
                is Key.Mic -> actions.toggleDictation()
                is Key.SwitchIme -> actions.switchKeyboard()
                is Key.Backspace -> Unit   // handled by onPress
            }
        },
    )
}

private const val REPEAT_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 110L

/**
 * The dead end that must not be one.
 *
 * An input method cannot request a runtime permission — it has no Activity to host the
 * dialog — so a keyboard without microphone access can only explain and offer a way out.
 */
@Composable
private fun PermissionRow(onOpenApp: () -> Unit) {
    Column(
        Modifier
            .testTag("permission-row")
            .fillMaxWidth()
            .neuInset(RoundedCornerShape(ScribeTokens.radius), depth = 0.8f)
            .border(
                1.dp,
                ScribeTokens.warn.copy(alpha = 0.4f),
                RoundedCornerShape(ScribeTokens.radius),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Scribe needs the microphone",
            color = ScribeTokens.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "A keyboard can't ask for permissions itself. Open Scribe once to allow it — " +
                "your voice stays on this phone either way.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        Box(
            Modifier
                .testTag("open-scribe")
                .neuActive(RoundedCornerShape(ScribeTokens.radiusSm), ScribeTokens.accent)
                .clickable(onClick = onOpenApp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Open Scribe",
                color = ScribeTokens.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
