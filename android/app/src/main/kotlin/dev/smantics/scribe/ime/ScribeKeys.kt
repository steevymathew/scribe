package dev.smantics.scribe.ime

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.neuKey
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The size of the keyboard, in one place, because every page has to agree on it.
 *
 * **The height must not change when you swipe sideways.** A keyboard that grows or shrinks
 * between its own pages re-lays out the app behind it every time, which is both ugly and
 * disorienting — the thing you were writing jumps while you are looking at the keys. So
 * all three pages are built to the same grid: four rows of content above one bottom row,
 * whatever is in them. The symbols page shares the letters' number row for exactly this
 * reason, and the emoji grid is given the height of the four rows it replaces.
 *
 * Height changes for two reasons only: the keys being laid down, and the transcript
 * appearing while dictating.
 */
object KeyMetrics {
    val rowHeight = 46.dp
    val rowGap = 6.dp
    const val CONTENT_ROWS = 4

    /** The keys, all rows, without the card's own padding. */
    val keysHeight: Dp = rowHeight * (CONTENT_ROWS + 1) + rowGap * CONTENT_ROWS

    /** The four rows above the bottom row — what the emoji grid has to fill. */
    val contentHeight: Dp = rowHeight * CONTENT_ROWS + rowGap * (CONTENT_ROWS - 1)

    val cardPadding = 6.dp
    val keyGap = 5.dp

    /** Deep enough to read as moulded rather than as a rounded rectangle. */
    val keyRadius = 13.dp

    /** The card seen edge-on, when it is lying face down. */
    val edgeHeight = 22.dp
}

/**
 * Tracks whether the gesture in progress turned into a swipe.
 *
 * Keys commit their character on **touch-down**, not on release, because that is what
 * makes typing feel accurate rather than approximate: a thumb that lands on `h` and drifts
 * a millimetre before lifting has typed `h`, where waiting for the release means it typed
 * nothing and the user finds out several words later. Every keyboard on the platform does
 * it this way, and doing it the other way is most of why this one felt imprecise.
 *
 * The cost is that a swipe beginning on a key types one character on its way past. So the
 * card raises this counter the moment a drag becomes a swipe, and the key that fired takes
 * its character back — the gesture *became* something else, so the tentative letter is
 * retracted rather than left behind.
 */
private class SwipeArbiter {
    var swipes: Int = 0
}

/**
 * The keys, as a stack of pages you swipe between, inside a card you can lay face down.
 *
 * **Why the gestures replaced the buttons.** The panel used to carry a utility bar: a key
 * to show the letters, a key to hide them, a key to cycle the width, a key to split, and a
 * key to leave for another keyboard. Five permanent controls, on a surface whose whole
 * argument is that it sits on top of what the user is actually writing. Four of them are
 * gone. Showing and hiding is a swipe down and up, the pages are a swipe left and right,
 * width and split moved to Settings where a decision made once belongs, and nothing here
 * offers to switch away from the keyboard the user just chose.
 *
 * A gesture nobody can see is worse than a button, so the card is drawn as a card: a raised
 * slab with an edge, and small chevrons on the sides that have somewhere to go. Pages slide
 * in from the direction they were pulled from, which is what makes "there is another one
 * over there" true rather than merely claimed.
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
    val arbiter = remember { SwipeArbiter() }

    Box(
        modifier
            .testTag("keys-card")
            .fillMaxWidth()
            .height(KeyMetrics.keysHeight + KeyMetrics.cardPadding * 2)
            .neuKey(
                shape = RoundedCornerShape(ScribeTokens.radius),
                pressed = false,
                surface = ScribeTokens.s0,
            )
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radius))
            .padding(KeyMetrics.cardPadding)
            .pageGestures(
                page = page,
                arbiter = arbiter,
                onPage = { next -> haptics(); onPage(next) },
                onHide = { haptics(); onHide() },
            ),
    ) {
        // The page slides in from the side it was pulled from. Anything else and a swipe
        // is a cut, which reads as the keyboard being replaced rather than moved.
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val entering: (Int) -> Int = { if (forward) it else -it }
                val leaving: (Int) -> Int = { if (forward) -it else it }
                (slideInHorizontally(tween(PAGE_SLIDE_MS), entering) + fadeIn(tween(PAGE_SLIDE_MS)))
                    .togetherWith(
                        slideOutHorizontally(tween(PAGE_SLIDE_MS), leaving) +
                            fadeOut(tween(PAGE_SLIDE_MS)),
                    )
                    .using(SizeTransform(clip = false))
            },
            label = "keyboard-page",
        ) { shown ->
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(KeyMetrics.rowGap),
            ) {
                if (shown == KeyboardPage.EMOJI) {
                    EmojiPage(onEmoji = { haptics(); actions.type(it); onTypedCharacter() })
                } else {
                    Keys(
                        page = shown,
                        shift = shift,
                        split = split,
                        actions = actions,
                        arbiter = arbiter,
                        onPage = onPage,
                        onShift = onShift,
                        onTypedCharacter = onTypedCharacter,
                    )
                }
                // The bottom row is on every page, emoji included: it is the way back, and
                // a page you can only leave by knowing a gesture is a page you get stuck on.
                KeyRow(
                    keys = KeyboardLayout.bottomRow,
                    page = shown,
                    shift = shift,
                    split = false,
                    actions = actions,
                    arbiter = arbiter,
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
 * Decided the moment the travel passes the threshold rather than at the end of the drag,
 * so the key that fired on touch-down can take its character back while the finger is
 * still moving. The axis with the larger travel wins, so a diagonal does one thing.
 */
private fun Modifier.pageGestures(
    page: KeyboardPage,
    arbiter: SwipeArbiter,
    onPage: (KeyboardPage) -> Unit,
    onHide: () -> Unit,
): Modifier = this.pointerInput(page) {
    var travel = Offset.Zero
    var fired = false
    detectDragGestures(
        onDragStart = { travel = Offset.Zero; fired = false },
        onDragCancel = { travel = Offset.Zero; fired = false },
        onDragEnd = { travel = Offset.Zero; fired = false },
    ) { change, amount ->
        travel += amount
        change.consume()
        if (!fired) {
            val dx = travel.x
            val dy = travel.y
            if (abs(dy) > abs(dx) && dy > SWIPE_THRESHOLD_PX) {
                fired = true
                arbiter.swipes++
                onHide()
            } else if (abs(dx) > abs(dy) && abs(dx) > SWIPE_THRESHOLD_PX) {
                // A swipe with nowhere to go still counts: the letter typed on the way
                // past comes back whether or not the page changes.
                fired = true
                arbiter.swipes++
                (if (dx > 0) page.right() else page.left())?.let(onPage)
            }
        }
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
    val target = (if (toLeft) page.left() else page.right()) ?: return
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
 * The card seen edge-on, which is all that is left when the keys are laid face down.
 *
 * The same slab, the same width, just its thickness. That is the whole idea: the keys did
 * not vanish, they went flat, and this is the edge you get a fingernail under. Swipe it up
 * or tap it — the gesture is the one being taught, the tap is there because a hint that
 * only answers to the gesture you have not learned yet is not a hint.
 */
@Composable
fun KeysGrabber(onShow: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    val show = { haptics(); onShow() }
    Box(
        modifier
            .testTag("keys-grabber")
            .fillMaxWidth()
            .height(KeyMetrics.edgeHeight)
            .neuKey(
                shape = RoundedCornerShape(ScribeTokens.radiusSm),
                pressed = false,
                surface = ScribeTokens.s1,
            )
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
        Box(
            Modifier
                .size(width = 42.dp, height = 3.dp)
                .background(ScribeTokens.faint.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
        )
    }
}

// ------------------------------------------------------------------------ the keys

@Composable
private fun ColumnScope.Keys(
    page: KeyboardPage,
    shift: ShiftState,
    split: Boolean,
    actions: PanelActions,
    arbiter: SwipeArbiter,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
) {
    // The number row is on the letters *and* the symbols page. Digits are typed often
    // enough to deserve a permanent target, and sharing the row is what keeps the two
    // pages the same height. See KeyMetrics.
    KeyRow(
        keys = KeyboardLayout.numberRow,
        page = page,
        shift = shift,
        split = split,
        actions = actions,
        arbiter = arbiter,
        onPage = onPage,
        onShift = onShift,
        onTypedCharacter = onTypedCharacter,
    )

    val rows = KeyboardLayout.rowsFor(page, shift)
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
            arbiter = arbiter,
            onPage = onPage,
            onShift = onShift,
            onTypedCharacter = onTypedCharacter,
        )
    }
}

@Composable
private fun KeyRow(
    keys: List<Key>,
    page: KeyboardPage,
    shift: ShiftState,
    split: Boolean,
    actions: PanelActions,
    arbiter: SwipeArbiter,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
    leading: Key? = null,
    trailing: Key? = null,
) {
    // Padded to a fixed ten units, so a nine-key row does not come out fatter than a
    // ten-key one. This is the stagger a physical keyboard has, and without it `asdfghjkl`
    // was visibly wider than `qwertyuiop` and a thumb aiming between rows missed.
    val indent = KeyboardLayout.indentFor(listOfNotNull(leading) + keys + listOfNotNull(trailing))

    Row(
        Modifier.fillMaxWidth().height(KeyMetrics.rowHeight),
        horizontalArrangement = Arrangement.spacedBy(KeyMetrics.keyGap),
    ) {
        val button: @Composable RowScope.(Key) -> Unit = { key ->
            KeyButton(
                key = key,
                page = page,
                shift = shift,
                actions = actions,
                arbiter = arbiter,
                onPage = onPage,
                onShift = onShift,
                onTypedCharacter = onTypedCharacter,
            )
        }

        if (indent > 0f) Spacer(Modifier.weight(indent))
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
        if (indent > 0f) Spacer(Modifier.weight(indent))
    }
}

/**
 * One key: what it says, what it does, and the fact that it moved when you touched it.
 *
 * **Characters commit on touch-down.** That is the single change that makes typing feel
 * accurate rather than approximate — see [SwipeArbiter] for why, and for what happens to
 * the character when the gesture turns out to be a swipe. The function keys still act on
 * release, because they are pressed one at a time and deliberately, and acting on their
 * release keeps a swipe starting on `?123` from changing the page twice.
 */
@Composable
private fun RowScope.KeyButton(
    key: Key,
    page: KeyboardPage,
    shift: ShiftState,
    actions: PanelActions,
    arbiter: SwipeArbiter,
    onPage: (KeyboardPage) -> Unit,
    onShift: () -> Unit,
    onTypedCharacter: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = rememberKeyHaptics()
    val heavy = rememberDeleteHaptics()

    val label: String? = when (key) {
        is Key.Char -> KeyboardLayout.textFor(key, page, shift)
        is Key.Layer -> if (page == KeyboardPage.SYMBOLS) "ABC" else "?123"
        else -> null
    }
    val glyph: GlyphName? = when (key) {
        is Key.Backspace -> GlyphName.BACKSPACE
        is Key.Space -> GlyphName.SPACE
        is Key.Emoji -> GlyphName.EMOJI
        is Key.Mic -> GlyphName.MIC
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

    /** What a character key does the instant it is touched. */
    val typeNow: () -> Unit = {
        when (key) {
            is Key.Char -> {
                actions.type(KeyboardLayout.textFor(key, page, shift))
                onTypedCharacter()
            }
            is Key.Space -> {
                actions.space()
                onTypedCharacter()
            }
            else -> Unit
        }
    }

    /** What the function keys do on release. */
    val actOnRelease: () -> Unit = {
        when (key) {
            is Key.Shift -> onShift()
            is Key.Layer -> onPage(
                if (page == KeyboardPage.SYMBOLS) KeyboardPage.LETTERS else KeyboardPage.SYMBOLS,
            )
            is Key.Emoji -> onPage(KeyboardPage.EMOJI)
            is Key.Enter -> actions.enter()
            is Key.Mic -> actions.toggleDictation()
            else -> Unit
        }
    }

    val holdForSecondary: (String) -> Unit = { secondary ->
        // The character already went in on touch-down, so holding *replaces* it — which is
        // what the popup on any other keyboard does, and what makes `a` held give `@`
        // rather than `a@`.
        if (key is Key.Char) actions.backspace()
        // The gear on the comma is Android's convention for "the keyboard's own settings",
        // so it opens Scribe rather than typing a cog.
        if (secondary == "⚙") actions.openApp() else actions.type(secondary)
        onTypedCharacter()
    }

    val currentType by rememberUpdatedState(typeNow)
    val currentRelease by rememberUpdatedState(actOnRelease)
    val currentSecondary by rememberUpdatedState(holdForSecondary)

    Box(
        Modifier
            .testTag("key-$description")
            .weight(KeyboardLayout.weightOf(key))
            .fillMaxHeight()
            .neuKey(
                shape = RoundedCornerShape(KeyMetrics.keyRadius),
                pressed = pressed,
                surface = resting,
                tint = if (emphasised) ScribeTokens.accent else null,
            )
            .semantics { contentDescription = description }
            .pointerInput(key, actions) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics()
                        val typesOnDown = key is Key.Char || key is Key.Space
                        val swipesBefore = arbiter.swipes
                        if (typesOnDown) currentType()

                        if (key is Key.Backspace) {
                            actions.backspace()
                            // Still held after the threshold? Switch to whole words until
                            // the finger lifts, with a heavier tick so the change of unit
                            // is felt rather than discovered several words later.
                            val early = withTimeoutOrNull(REPEAT_DELAY_MS) { tryAwaitRelease() }
                            if (early == null) {
                                var holding = true
                                while (holding) {
                                    heavy()
                                    actions.deleteWord()
                                    val lifted =
                                        withTimeoutOrNull(REPEAT_INTERVAL_MS) { tryAwaitRelease() }
                                    if (lifted != null) holding = false
                                }
                            }
                        } else {
                            tryAwaitRelease()
                        }

                        // The card claimed the gesture as a swipe, so the character typed
                        // on the way past is taken back. See SwipeArbiter.
                        if (typesOnDown && arbiter.swipes != swipesBefore) actions.backspace()
                        pressed = false
                    },
                    onLongPress = {
                        val secondary = (key as? Key.Char)?.secondary ?: return@detectTapGestures
                        heavy()
                        currentSecondary(secondary)
                    },
                    onTap = {
                        // Characters have already gone in; this is only the function keys.
                        if (key !is Key.Char && key !is Key.Space && key !is Key.Backspace) {
                            currentRelease()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val secondary = (key as? Key.Char)?.secondary
        if (secondary != null && page != KeyboardPage.SYMBOLS) {
            Text(
                secondary,
                color = ScribeTokens.faint.copy(alpha = 0.75f),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 5.dp, top = 3.dp),
            )
        }
        when {
            label != null -> Text(
                label,
                color = ScribeTokens.text,
                fontSize = if (label.length > 2) 13.sp else 18.sp,
            )
            key is Key.Shift -> Glyph(
                GlyphName.SHIFT,
                when (shift) {
                    ShiftState.LOCKED -> ScribeTokens.accent
                    ShiftState.ONCE -> ScribeTokens.text
                    ShiftState.OFF -> ScribeTokens.muted
                },
                size = 19.dp,
                thickness = 1.7.dp,
            )
            // Enter carries the accent: it is the one key on the board that sends what you
            // wrote, and the only place the teal appears while typing.
            key is Key.Enter -> Glyph(
                GlyphName.RETURN,
                ScribeTokens.accent,
                size = 20.dp,
                thickness = 1.8.dp,
            )
            glyph != null -> Glyph(glyph, ScribeTokens.muted, size = 20.dp, thickness = 1.7.dp)
        }
    }
}

/**
 * A short tick of haptic feedback for every press.
 *
 * A touch keyboard without it feels broken in a way that is hard to name — there is no
 * travel, no click, and no confirmation that the tap registered at all until the character
 * appears. `KEYBOARD_TAP` is the system's own keypress effect, so Scribe feels like the
 * keyboard the user already has rather than inventing its own vocabulary. It fires on
 * *press*, on the same frame the key sinks into the surface and the same frame the
 * character is committed: the tick, the movement and the letter are one event.
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

/**
 * A heavier thump, for deleting a whole word and for a long press landing.
 *
 * Holding backspace switches from characters to words, and the two felt identical through
 * a keypress tick — so the moment a held key started eating whole words passed unnoticed
 * until several of them were gone. `LONG_PRESS` is the system's own heavier effect, which
 * makes the change of unit something the hand registers rather than something the eye has
 * to catch up with.
 */
@Composable
fun rememberDeleteHaptics(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
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
 * A plain grid, scrolled vertically, given exactly the height of the four rows it stands in
 * for so the keyboard does not change size. Categories are headings rather than a tab strip
 * — a strip would want horizontal gestures, and horizontal is how you leave this page.
 * Swiping right goes back to the letters from anywhere here, including mid-scroll, because
 * a vertical scroller does not consume horizontal drags.
 *
 * No recents and no search. Both mean keeping a record of what the user typed, and this
 * keyboard's one distinguishing promise is that it does not.
 */
@Composable
private fun ColumnScope.EmojiPage(onEmoji: (String) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .testTag("emoji-page")
            .fillMaxWidth()
            .height(KeyMetrics.contentHeight)
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
            .neuKey(RoundedCornerShape(KeyMetrics.keyRadius), pressed, ScribeTokens.s1)
            .semantics { contentDescription = glyph }
            .pointerInput(glyph) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics()
                        onClick()
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 21.sp)
    }
}

private const val EMOJI_PER_ROW = 8

/**
 * How far a finger travels before it counts as a swipe rather than a wobble.
 *
 * Generous on purpose. Every pixel below this is a chance to change the page while somebody
 * is typing, and the cost of missing a deliberate swipe is that they do it again.
 */
private const val SWIPE_THRESHOLD_PX = 110f

/** Long enough to read as movement, short enough not to be in the way of the next key. */
private const val PAGE_SLIDE_MS = 180

private const val REPEAT_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 110L
