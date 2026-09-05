package dev.smantics.scribe.ime

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.neuKey
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The keys, as a stack of pages you swipe between, inside a card you can swipe away.
 *
 * **Why the gestures replaced the buttons.** The panel used to carry a utility bar: a key
 * to show the letters, a key to hide them, a key to cycle the width, a key to split, and a
 * key to leave for another keyboard. Five permanent controls, on a surface whose whole
 * argument is that it sits on top of what the user is actually writing. Four of them are
 * gone. Showing and hiding is a swipe down and up, the pages are a swipe left and right,
 * width and split moved to Settings where a decision made once belongs, and nothing here
 * offers to switch away from the keyboard the user just chose.
 *
 * A gesture nobody can see is worse than a button, so the card is drawn as a card: a faint
 * outline that says "this is one object", and small chevrons on the edges that have
 * somewhere to go. When the keys are away, a grabber stays behind — the whole reason the
 * outline is there is so that the grabber reads as the edge of something rather than as a
 * stray line.
 */
@Composable
fun KeysCard(
    page: KeyboardPage,
    shift: ShiftState,
    split: Boolean,
    actions: PanelActions,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onHide: () -> Unit,
    onTypedCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()

    Box(
        modifier
            .testTag("keys-card")
            .fillMaxWidth()
            .border(
                1.dp,
                ScribeTokens.stroke,
                RoundedCornerShape(ScribeTokens.radius),
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .pageGestures(
                page = page,
                onPage = { next -> haptics(); onPage(next) },
                onHide = { haptics(); onHide() },
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (page) {
                KeyboardPage.EMOJI -> EmojiPage(
                    onEmoji = { haptics(); actions.type(it); onTypedCharacter() },
                )
                else -> Keys(
                    page = page,
                    shift = shift,
                    split = split,
                    actions = actions,
                    haptics = haptics,
                    onPage = onPage,
                    onShift = onShift,
                    onTypedCharacter = onTypedCharacter,
                )
            }
        }
        PageHint(page, Modifier.align(Alignment.CenterStart), toLeft = true)
        PageHint(page, Modifier.align(Alignment.CenterEnd), toLeft = false)
    }
}

/**
 * The swipes that move between pages and put the keys away.
 *
 * Decided at the end of the drag rather than as it moves, because the alternative is a
 * page that starts sliding every time a thumb travels a few pixels off a key on its way
 * up. The axis with the larger travel wins, so a diagonal does one thing rather than both.
 *
 * Keys still receive their taps: a child claims the tap, this claims the drag once it has
 * passed the touch slop, and a key that was pressed and then dragged off un-presses
 * without typing — which is the behaviour that makes swiping from anywhere safe.
 */
private fun Modifier.pageGestures(
    page: KeyboardPage,
    onPage: (KeyboardPage) -> Unit,
    onHide: () -> Unit,
): Modifier = this.pointerInput(page) {
    var travel = Offset.Zero
    detectDragGestures(
        onDragStart = { travel = Offset.Zero },
        onDragEnd = {
            val (dx, dy) = travel
            when {
                kotlin.math.abs(dy) > kotlin.math.abs(dx) && dy > SWIPE_THRESHOLD_PX -> onHide()
                dx > SWIPE_THRESHOLD_PX -> page.right()?.let(onPage)
                dx < -SWIPE_THRESHOLD_PX -> page.left()?.let(onPage)
            }
        },
        onDragCancel = { travel = Offset.Zero },
    ) { change, amount ->
        travel += amount
        change.consume()
    }
}

/**
 * A chevron on the edge of the card, when there is a page that way.
 *
 * Deliberately at the very bottom of the visible range. It has to be findable by someone
 * looking for a way out and ignorable by everyone else; a hint loud enough to notice while
 * typing is a hint that has become decoration.
 */
@Composable
private fun BoxScope.PageHint(page: KeyboardPage, modifier: Modifier, toLeft: Boolean) {
    val target = if (toLeft) page.left() else page.right()
    if (target == null) return
    Box(
        modifier
            .testTag(if (toLeft) "hint-left" else "hint-right")
            .size(width = 10.dp, height = 34.dp)
            .semantics {
                contentDescription = when (target) {
                    KeyboardPage.EMOJI -> "Swipe for emoji"
                    KeyboardPage.SYMBOLS -> "Swipe for symbols"
                    KeyboardPage.LETTERS -> "Swipe for letters"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            if (toLeft) GlyphName.CHEVRON_LEFT else GlyphName.CHEVRON_RIGHT,
            ScribeTokens.faint.copy(alpha = 0.5f),
            size = 10.dp,
            thickness = 1.4.dp,
        )
    }
}

/**
 * What is left when the keys are swiped away.
 *
 * A short bar and a chevron, on the same outline the card had, so the edge the keys went
 * behind is still visible. Swipe it up or tap it: the gesture is the one being taught, the
 * tap is there because a hint that only answers to the gesture you have not learned yet is
 * not a hint.
 */
@Composable
fun KeysGrabber(onShow: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    val show = { haptics(); onShow() }
    Box(
        modifier
            .testTag("keys-grabber")
            .fillMaxWidth()
            .height(26.dp)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radiusSm))
            .semantics { contentDescription = "Swipe up for the keys" }
            .pointerInput(Unit) {
                var travel = 0f
                detectDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = { if (travel < -SWIPE_THRESHOLD_PX) show() },
                ) { change, amount ->
                    travel += amount.y
                    change.consume()
                }
            }
            .pointerInput(Unit) { detectTapGestures { show() } },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(
                GlyphName.CHEVRON_UP,
                ScribeTokens.faint.copy(alpha = 0.6f),
                size = 10.dp,
                thickness = 1.4.dp,
            )
            Box(
                Modifier
                    .size(width = 34.dp, height = 3.dp)
                    .background(
                        ScribeTokens.faint.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

// ------------------------------------------------------------------------ the keys

@Composable
private fun Keys(
    page: KeyboardPage,
    shift: ShiftState,
    split: Boolean,
    actions: PanelActions,
    haptics: () -> Unit,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
) {
    val rows = KeyboardLayout.rowsFor(page, shift)

    // The number row comes first and is a little shorter than the letters. It is a row of
    // real keys, not a hint strip: digits are typed often enough to deserve a target.
    if (page == KeyboardPage.LETTERS) {
        KeyRow(
            keys = KeyboardLayout.numberRow,
            page = page,
            shift = shift,
            split = split,
            height = 34.dp,
            actions = actions,
            haptics = haptics,
            onPage = onPage,
            onShift = onShift,
            onTypedCharacter = onTypedCharacter,
        )
    }

    rows.forEachIndexed { index, row ->
        // Shift and backspace bracket the last row. They are attached here rather than
        // baked into the layout so the split can divide the letters evenly and hang the
        // two wide keys off the outside edges, where a thumb finds them.
        val isLast = index == rows.lastIndex
        KeyRow(
            keys = row,
            page = page,
            shift = shift,
            split = split,
            leading = if (isLast) Key.Shift else null,
            trailing = if (isLast) Key.Backspace else null,
            actions = actions,
            haptics = haptics,
            onPage = onPage,
            onShift = onShift,
            onTypedCharacter = onTypedCharacter,
        )
    }

    KeyRow(
        keys = KeyboardLayout.bottomRow,
        page = page,
        shift = shift,
        split = false,
        actions = actions,
        haptics = haptics,
        onPage = onPage,
        onShift = onShift,
        onTypedCharacter = onTypedCharacter,
    )
}

@Composable
private fun KeyRow(
    keys: List<Key>,
    page: KeyboardPage,
    shift: ShiftState,
    split: Boolean,
    actions: PanelActions,
    haptics: () -> Unit,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
    leading: Key? = null,
    trailing: Key? = null,
    height: androidx.compose.ui.unit.Dp = 46.dp,
) {
    Row(
        Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val button: @Composable RowScope.(Key) -> Unit = { key ->
            KeyButton(
                key = key,
                page = page,
                shift = shift,
                height = height,
                actions = actions,
                haptics = haptics,
                onPage = onPage,
                onShift = onShift,
                onTypedCharacter = onTypedCharacter,
            )
        }

        leading?.let { button(it) }
        if (split) {
            // A gap down the middle, so each half sits under a thumb. On an eight-inch
            // display a full-width row puts Q and P a hand apart.
            val (left, right) = KeyboardLayout.split(keys)
            left.forEach { button(it) }
            Spacer(Modifier.weight(1.6f))
            right.forEach { button(it) }
        } else {
            keys.forEach { button(it) }
        }
        trailing?.let { button(it) }
    }
}

/**
 * One key: what it says, what it does, and the fact that it moved when you touched it.
 *
 * The press state lives here rather than in an `InteractionSource` because the same
 * gesture has to survive being turned into a page swipe. `tryAwaitRelease` reports whether
 * the finger lifted on the key or was taken by the card's drag detector, so a swipe that
 * begins on `h` un-presses `h` and types nothing — which is what makes it safe to start a
 * swipe anywhere on the keyboard.
 */
@Composable
private fun RowScope.KeyButton(
    key: Key,
    page: KeyboardPage,
    shift: ShiftState,
    height: androidx.compose.ui.unit.Dp,
    actions: PanelActions,
    haptics: () -> Unit,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    val label: String? = when (key) {
        is Key.Char -> KeyboardLayout.textFor(key, page, shift)
        is Key.Layer -> if (page == KeyboardPage.SYMBOLS) "ABC" else "?123"
        else -> null
    }
    val glyph: GlyphName? = when (key) {
        is Key.Backspace -> GlyphName.BACKSPACE
        is Key.Enter -> GlyphName.RETURN
        is Key.Space -> GlyphName.SPACE
        is Key.Emoji -> GlyphName.EMOJI
        is Key.Mic -> GlyphName.MIC
        is Key.Shift -> GlyphName.BOLT
        else -> null
    }
    val description = when (key) {
        is Key.Char -> label.orEmpty()
        is Key.Shift -> "Shift"
        is Key.Backspace -> "Backspace"
        is Key.Space -> "Space"
        is Key.Enter -> "Enter"
        is Key.Emoji -> "Emoji"
        is Key.Layer -> if (page == KeyboardPage.SYMBOLS) "Letters" else "Symbols"
        is Key.Mic -> "Dictate"
    }

    val emphasised = key is Key.Shift && shift != ShiftState.OFF
    // Letters and space stand proud; the function keys sit slightly back, which is the
    // same hierarchy every hardware keyboard has and needs no explaining.
    val resting = if (key is Key.Char || key is Key.Space) ScribeTokens.s2 else ScribeTokens.s1

    val primary: () -> Unit = {
        when (key) {
            is Key.Char -> {
                actions.type(KeyboardLayout.textFor(key, page, shift))
                onTypedCharacter()
            }
            is Key.Shift -> onShift()
            is Key.Layer -> onPage(
                if (page == KeyboardPage.SYMBOLS) KeyboardPage.LETTERS else KeyboardPage.SYMBOLS,
            )
            is Key.Emoji -> onPage(KeyboardPage.EMOJI)
            is Key.Space -> {
                actions.space()
                onTypedCharacter()
            }
            is Key.Enter -> actions.enter()
            is Key.Mic -> actions.toggleDictation()
            is Key.Backspace -> Unit // handled by the hold gesture
        }
    }

    val secondaryAction: (String) -> Unit = { secondary ->
        // The gear on the comma is Android's convention for "the keyboard's own settings",
        // so it opens Scribe rather than typing a cog.
        if (secondary == "⚙") actions.openApp() else actions.type(secondary)
        onTypedCharacter()
    }
    val currentPrimary by rememberUpdatedState(primary)
    val currentSecondary by rememberUpdatedState(secondaryAction)

    Box(
        Modifier
            .testTag("key-$description")
            .weight(KeyboardLayout.weightOf(key))
            .height(height)
            .neuKey(
                shape = RoundedCornerShape(ScribeTokens.radiusChip),
                pressed = pressed,
                surface = resting,
                tint = if (emphasised) ScribeTokens.accent else null,
            )
            .semantics { contentDescription = description }
            .keyGestures(
                key = key,
                haptics = haptics,
                onPressedChange = { pressed = it },
                // Read through `rememberUpdatedState`, not captured directly. `pointerInput`
                // is keyed on the key, and the function keys are `data object`s — the same
                // instance on every page — so a directly captured lambda is the one built
                // during the *first* composition. That is a real bug and the tests caught
                // it: `?123` switched to symbols, and then the same key on the symbols page
                // switched to symbols again, because it was still running the letters
                // page's lambda. Nothing here may close over a value that changes.
                onPrimary = { currentPrimary() },
                onSecondary = { currentSecondary(it) },
                actions = actions,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val secondary = (key as? Key.Char)?.secondary
        if (secondary != null && page == KeyboardPage.LETTERS) {
            Text(
                secondary,
                color = ScribeTokens.faint,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp),
            )
        }
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
            glyph != null -> Glyph(glyph, ScribeTokens.muted, size = 19.dp, thickness = 1.7.dp)
        }
    }
}

/**
 * Press, hold to repeat, hold to reach the second character.
 *
 * Backspace repeats **by word** once it is held, which is the difference between clearing
 * a misheard sentence in two seconds and holding the key down for twenty. Dictation
 * produces whole phrases, so the word is the right unit for this keyboard in a way it is
 * not for a purely typing one.
 *
 * Everything else holds for its shoulder symbol. A long press suppresses the tap, so `a`
 * held gives `@` and never `a@`.
 */
private fun Modifier.keyGestures(
    key: Key,
    haptics: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    onPrimary: () -> Unit,
    onSecondary: (String) -> Unit,
    actions: PanelActions,
): Modifier = this.pointerInput(key, actions) {
    detectTapGestures(
        onPress = {
            onPressedChange(true)
            haptics()
            if (key is Key.Backspace) {
                actions.backspace()
                // Still held after the threshold? Switch to whole words until it lifts.
                val liftedEarly = withTimeoutOrNull(REPEAT_DELAY_MS) { tryAwaitRelease() }
                if (liftedEarly == null) {
                    var holding = true
                    while (holding) {
                        actions.deleteWord()
                        val lifted = withTimeoutOrNull(REPEAT_INTERVAL_MS) { tryAwaitRelease() }
                        if (lifted != null) holding = false
                    }
                }
            } else {
                tryAwaitRelease()
            }
            onPressedChange(false)
        },
        onLongPress = {
            val secondary = (key as? Key.Char)?.secondary ?: return@detectTapGestures
            haptics()
            onSecondary(secondary)
        },
        onTap = { if (key !is Key.Backspace) onPrimary() },
    )
}

/**
 * A short tick of haptic feedback for every press.
 *
 * A touch keyboard without it feels broken in a way that is hard to name — there is no
 * travel, no click, and no confirmation that the tap registered at all until the character
 * appears. `KEYBOARD_TAP` is the system's own keypress effect, so Scribe feels like the
 * keyboard the user already has rather than inventing its own vocabulary. It fires on
 * *press*, on the same frame the key is drawn sinking into the surface: the tick and the
 * movement are one event or they are two bugs.
 */
@Composable
fun rememberKeyHaptics(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
            Unit
        }
    }
}

// ------------------------------------------------------------------------ emoji

/**
 * The emoji page.
 *
 * A plain grid, scrolled vertically, with the categories as headings rather than as a
 * tab strip — a strip would want horizontal gestures, and horizontal is how you leave this
 * page. Swiping right goes back to the letters from anywhere here, including mid-scroll,
 * because a vertical scroller does not consume horizontal drags.
 *
 * No recents and no search. Both mean keeping a record of what the user typed, and this
 * keyboard's one distinguishing promise is that it does not.
 */
@Composable
private fun EmojiPage(onEmoji: (String) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .testTag("emoji-page")
            .fillMaxWidth()
            .height(EMOJI_PAGE_HEIGHT)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Emoji.categories.forEach { (name, glyphs) ->
            Text(
                name,
                color = ScribeTokens.faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
            )
            glyphs.chunked(EMOJI_PER_ROW).forEach { rowGlyphs ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    rowGlyphs.forEach { glyph ->
                        EmojiKey(glyph, Modifier.weight(1f)) { onEmoji(glyph) }
                    }
                    // Keeps the last row of a category aligned with the ones above it
                    // rather than stretching four emoji across the whole width.
                    repeat(EMOJI_PER_ROW - rowGlyphs.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EmojiKey(glyph: String, modifier: Modifier, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = rememberKeyHaptics()
    Box(
        modifier
            .height(40.dp)
            .neuKey(RoundedCornerShape(ScribeTokens.radiusChip), pressed, ScribeTokens.s1)
            .semantics { contentDescription = glyph }
            .pointerInput(glyph) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics()
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 21.sp)
    }
}

private const val EMOJI_PER_ROW = 8
private val EMOJI_PAGE_HEIGHT = 214.dp

/** How far a finger has to travel before it counts as a swipe rather than a wobble. */
private const val SWIPE_THRESHOLD_PX = 90f

private const val REPEAT_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 110L
