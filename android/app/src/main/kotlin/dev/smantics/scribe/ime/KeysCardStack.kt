package dev.smantics.scribe.ime

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.ui.components.neuKey
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The keyboard as a physical card: dragged, not swiped.
 *
 * **The difference is the whole point, and the first version got it wrong.** That one
 * watched for a swipe and, once the travel passed a threshold, played a fixed animation.
 * Nothing moved under the finger while the finger was moving — so the card felt clunky next
 * to its own keys, which react on touch-down. A card you are pushing has to move exactly as
 * far as you have pushed it, arrive when you let go, and carry the speed you gave it.
 *
 * Everything here is tracked one to one:
 *
 *  - **Sideways**, the pages sit edge to edge and the strip slides with the finger. They
 *    never overlap: the outgoing page leaves exactly as fast as the incoming one arrives,
 *    because they are one strip being moved, not two things being cross-faded.
 *  - **Downward**, the card tips away around its bottom edge, degree by degree with the
 *    drag, until it is face down and only its thickness is showing.
 *  - **Letting go** hands the offset to a spring carrying the finger's velocity. There is no
 *    threshold to guess at: the question is where the card was going, not how far it got.
 *  - **Pulling toward a page that is not there** meets resistance rather than blankness.
 *
 * The axis locks on the first movement past the touch slop, so a diagonal does one thing.
 */
@Composable
fun KeysCardStack(
    page: KeyboardPage,
    open: Boolean,
    onPage: (KeyboardPage) -> Unit,
    onOpenChange: (Boolean) -> Unit,
    /** Called once the drag has been claimed, so a key can take back what it typed. */
    onDragClaimed: () -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable BoxScope.(KeyboardPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val metrics = LocalDensity.current
    val haptics = rememberKeyHaptics()
    val grabHaptics = rememberGrabHaptics()

    val fullHeight = KeyMetrics.keysHeight + KeyMetrics.cardPadding * 2

    // **Plain floats, written directly.** These were `Animatable`s, and every pointer event
    // launched a coroutine to `snapTo` them — which puts a mutex and a scheduling hop
    // between the finger and the card, on the one surface whose whole job is to feel
    // attached to the finger. A drag writes these; only the settle animates them.
    /** Horizontal travel in pixels; zero is the current page centred. */
    var slide by remember { mutableFloatStateOf(0f) }

    /** One is upright and readable, zero is lying on its face. */
    var upright by remember { mutableFloatStateOf(if (open) 1f else 0f) }

    /** The settle in flight, cancelled the instant a finger lands again. */
    var settling by remember { mutableStateOf<Job?>(null) }

    /** The page being pulled in, drawn beside the current one and never on top of it. */
    var incoming by remember { mutableStateOf<KeyboardPage?>(null) }

    /** True while the whole card has been taken hold of by an edge. */
    var grabbed by remember { mutableStateOf(false) }

    /** The card's own width, which is how far a page has to travel to be gone. */
    var widthPx by remember { mutableFloatStateOf(0f) }

    val currentPage by rememberUpdatedState(page)
    val currentOnPage by rememberUpdatedState(onPage)
    val currentOnOpen by rememberUpdatedState(onOpenChange)
    val currentOnClaimed by rememberUpdatedState(onDragClaimed)

    // Settings and the edge's own tap change `open` from outside a drag; the card follows.
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        if (upright != target) {
            animate(upright, target, animationSpec = settle()) { value, _ -> upright = value }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(KeyMetrics.edgeHeight + (fullHeight - KeyMetrics.edgeHeight) * upright),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Only there once the card is on its way down. It used to sit underneath the whole
        // time, which meant a stray line across the bottom of a keyboard that was already
        // up — a control for a state you were not in. It fades in exactly as the card tips
        // away, so what you are looking at is the edge the card went behind.
        if (upright < 0.995f) {
            KeysGrabber(
                onShow = { currentOnOpen(true) },
                modifier = Modifier.graphicsLayer { alpha = 1f - upright },
            )
        }

        if (upright > 0.005f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(fullHeight)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .graphicsLayer {
                        // Tipped away from the viewer around the bottom edge, which is what
                        // reads as a hinge rather than a spin.
                        rotationX = -CARD_LAY_DEGREES * (1f - upright)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        // Without a camera distance the projection is severe enough to look
                        // like the card is being crushed rather than tilted.
                        cameraDistance = 16f * density
                        alpha = (upright * 2.4f).coerceAtMost(1f)
                        // A card that has been taken hold of comes towards you a little.
                        val lift = if (grabbed) GRAB_LIFT else 1f
                        scaleX = lift
                        scaleY = lift
                    }
                    .cardGestures(
                        slide = { slide },
                        setSlide = { slide = it },
                        upright = { upright },
                        setUpright = { upright = it },
                        settling = { settling },
                        setSettling = { settling = it },
                        width = { widthPx },
                        setGrabbed = { grabbed = it },
                        page = { currentPage },
                        setIncoming = { incoming = it },
                        onPage = { currentOnPage(it) },
                        onOpenChange = { currentOnOpen(it) },
                        onDragClaimed = { currentOnClaimed() },
                        haptics = haptics,
                        grabHaptics = grabHaptics,
                        scope = scope,
                    ),
            ) {
                CardFace(
                    grabbed = grabbed,
                    modifier = Modifier
                        .testTag("keys-card")
                        .graphicsLayer { translationX = slide },
                ) {
                    pageContent(currentPage)
                    // The grips are the only sign the band exists, so they sit exactly
                    // where it does and light up when it is being used.
                    EdgeGrip(
                        page = currentPage,
                        toLeft = true,
                        active = grabbed && slide > 0f,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    EdgeGrip(
                        page = currentPage,
                        toLeft = false,
                        active = grabbed && slide < 0f,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    BottomGrip(
                        active = grabbed && upright < 1f,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                incoming?.let { next ->
                    // Placed on whichever side the current page is being pushed away from,
                    // one card-width along **plus a gap**. Without the gap the two faces
                    // touch and read as one long strip sliding past; with it they are
                    // plainly two cards, one arriving and one leaving.
                    val direction = if (slide < 0f) 1f else -1f
                    val gap = with(metrics) { CARD_GAP.toPx() }
                    CardFace(
                        grabbed = false,
                        modifier = Modifier
                            .testTag("keys-card-incoming")
                            .graphicsLayer { translationX = slide + direction * (widthPx + gap) },
                    ) { pageContent(next) }
                }
            }
        }
    }
}

/** The card's own surface: the slab everything else is drawn on. */
@Composable
private fun CardFace(
    grabbed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(KeyMetrics.keysHeight + KeyMetrics.cardPadding * 2)
            .neuKey(
                shape = RoundedCornerShape(ScribeTokens.radius),
                pressed = false,
                surface = ScribeTokens.s0,
            )
            .border(
                // The edge lights up when the whole card has been taken hold of. That is the
                // difference between having a key and having the card, and it has to be
                // visible from under a thumb.
                if (grabbed) 1.5.dp else 1.dp,
                if (grabbed) ScribeTokens.accent.copy(alpha = 0.55f) else ScribeTokens.stroke,
                RoundedCornerShape(ScribeTokens.radius),
            ),
    ) {
        content()
    }
}

private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

/** How the wait on an edge ended. */
private enum class Hold { GRABBED, MOVED, RELEASED }

/**
 * Everything the finger does to the card.
 *
 * Written against the raw pointer stream rather than `detectDragGestures`, because three
 * things have to happen that the ready-made detectors do not offer together: the offsets
 * have to track the finger exactly, the release has to carry velocity into a spring, and a
 * press that *stays still* on an edge has to become a grab rather than a drag.
 */
private fun Modifier.cardGestures(
    slide: () -> Float,
    setSlide: (Float) -> Unit,
    upright: () -> Float,
    setUpright: (Float) -> Unit,
    settling: () -> Job?,
    setSettling: (Job?) -> Unit,
    width: () -> Float,
    setGrabbed: (Boolean) -> Unit,
    page: () -> KeyboardPage,
    setIncoming: (KeyboardPage?) -> Unit,
    onPage: (KeyboardPage) -> Unit,
    onOpenChange: (Boolean) -> Unit,
    onDragClaimed: () -> Unit,
    haptics: () -> Unit,
    grabHaptics: () -> Unit,
    scope: CoroutineScope,
): Modifier = this.pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val edgeBand = EDGE_BAND_DP.dp.toPx()
    val layDistance = LAY_DISTANCE_DP.dp.toPx()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val onLeftOrRight = start.x < edgeBand || start.x > size.width - edgeBand
        val onBottom = start.y > size.height - edgeBand

        // **The card only moves from its edges, and this is the whole fix for it being
        // eager.** A drag used to be recognised anywhere on the card, so a thumb that
        // travelled a few millimetres on its way between keys started pushing the keyboard
        // off screen — while typing, which is the worst possible moment. Confining the
        // gesture to the border means it cannot fire from a key at all, because there is no
        // key there: the band is the padding between the frame and the first row. The
        // divots drawn in it say where it is.
        if (!onLeftOrRight && !onBottom) return@awaitEachGesture

        // A finger on the card takes it back from whatever spring was carrying it. Catching
        // a card mid-flight is most of what makes it feel like an object rather than a
        // sequence of animations.
        settling()?.cancel()
        setSettling(null)

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, start)

        // Touching the band lights it: the card is already yours, and Material's own
        // language for "this responds" is that it acknowledges the touch before it moves.
        setGrabbed(true)
        grabHaptics()

        // The axis comes from the edge that was touched, not from which way the finger
        // goes. Taking hold of the side means sideways; taking hold of the bottom means
        // down. There is nothing to guess and nothing to lock.
        val held = true

        var axis = Axis.UNDECIDED
        var travel = Offset.Zero
        var claimed = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                release(
                    axis = axis,
                    travel = travel,
                    velocity = tracker.calculateVelocity(),
                    slide = slide,
                    setSlide = setSlide,
                    upright = upright,
                    setUpright = setUpright,
                    setSettling = setSettling,
                    width = width(),
                    layDistance = layDistance,
                    page = page,
                    setIncoming = setIncoming,
                    onPage = onPage,
                    onOpenChange = onOpenChange,
                    scope = scope,
                )
                setGrabbed(false)
                break
            }

            travel += change.positionChange()
            tracker.addPosition(change.uptimeMillis, change.position)

            if (axis == Axis.UNDECIDED && travel.getDistance() > slop) {
                axis = if (onBottom) Axis.VERTICAL else Axis.HORIZONTAL
                if (!claimed) {
                    claimed = true
                    // Kept as insurance rather than necessity: no key can have fired,
                    // because the band the gesture started in has no keys in it.
                    onDragClaimed()
                }
            }

            when (axis) {
                Axis.HORIZONTAL -> {
                    change.consume()
                    val next = if (travel.x > 0) page().right() else page().left()
                    setIncoming(next)
                    // Resisted at the ends of the run, so a pull toward nothing says so
                    // rather than sliding into blankness. Written straight to the state:
                    // no mutex, no coroutine, no frame of lag between finger and card.
                    val resistance = if (next == null) RUBBER_BAND else 1f
                    setSlide(travel.x * resistance)
                }
                Axis.VERTICAL -> {
                    change.consume()
                    val progress = (travel.y / layDistance).coerceIn(-1f, 1f)
                    setUpright((1f - progress).coerceIn(0f, 1f))
                }
                Axis.UNDECIDED -> Unit
            }
        }
    }
}

/**
 * Where the card was going when it was let go.
 *
 * Velocity decides; distance only breaks the tie. A flick that travelled a centimetre was on
 * its way, and a slow push that travelled three and stopped was not — judging by distance
 * alone is what makes a gesture feel like it is arguing with you.
 */
private fun release(
    axis: Axis,
    travel: Offset,
    velocity: androidx.compose.ui.unit.Velocity,
    slide: () -> Float,
    setSlide: (Float) -> Unit,
    upright: () -> Float,
    setUpright: (Float) -> Unit,
    setSettling: (Job?) -> Unit,
    width: Float,
    layDistance: Float,
    page: () -> KeyboardPage,
    setIncoming: (KeyboardPage?) -> Unit,
    onPage: (KeyboardPage) -> Unit,
    onOpenChange: (Boolean) -> Unit,
    scope: CoroutineScope,
) {
    when (axis) {
        Axis.HORIZONTAL -> {
            val next = if (travel.x > 0) page().right() else page().left()
            val committed = next != null && (
                abs(velocity.x) > FLICK_PX_PER_SECOND ||
                    abs(travel.x) > width * COMMIT_FRACTION
                )
            setSettling(
                scope.launch {
                    // Carried the rest of the way rather than cut, so the page arrives
                    // instead of appearing.
                    val target = if (!committed) 0f else if (travel.x > 0) width else -width
                    animate(slide(), target, velocity.x, settle()) { value, _ -> setSlide(value) }
                    if (committed) onPage(next!!)
                    setSlide(0f)
                    setIncoming(null)
                },
            )
        }
        Axis.VERTICAL -> {
            val down = velocity.y > FLICK_PX_PER_SECOND ||
                (velocity.y > -FLICK_PX_PER_SECOND && travel.y > layDistance * COMMIT_FRACTION)
            setSettling(
                scope.launch {
                    val target = if (down) 0f else 1f
                    animate(upright(), target, -velocity.y, settle()) { v, _ -> setUpright(v) }
                    onOpenChange(!down)
                },
            )
        }
        Axis.UNDECIDED -> {
            setSettling(
                scope.launch {
                    animate(slide(), 0f, 0f, settle()) { value, _ -> setSlide(value) }
                    setIncoming(null)
                },
            )
        }
    }
}

/**
 * The spring the card settles on.
 *
 * No bounce, and soft: a card has mass and does not ping. Critically damped so it arrives
 * decisively without wobbling, which is what separates a physical object from a piece of UI
 * easing into place.
 */
private fun settle(): AnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** How close to a side counts as taking hold of the card rather than pressing a key. */
private const val EDGE_BAND_DP = 16

/**
 * How long a still finger on an edge waits before it has the card.
 *
 * Longer than a key's long press, which is around half a second, so the two do not race —
 * and long enough that a thumb resting on the frame on its way to a key does not grab.
 */
private const val GRAB_HOLD_MS = 620L

/** How far the card has to be pushed to lie flat. Roughly its own height. */
private const val LAY_DISTANCE_DP = 180

/** Past this speed, a release is a flick and the card finishes what it was doing. */
private const val FLICK_PX_PER_SECOND = 420f

/** Or this much of the way across, which is a commitment even without speed. */
private const val COMMIT_FRACTION = 0.32f

/** How much a pull toward a page that does not exist actually moves. */
private const val RUBBER_BAND = 0.28f

/** The card comes towards you when it is taken hold of. */
private const val GRAB_LIFT = 1.015f

/**
 * The air between the card leaving and the card arriving.
 *
 * Without it the two faces touch and read as one long strip sliding past — which is what
 * they technically are, and exactly what they should not look like. The gap is what makes
 * it two objects.
 */
private val CARD_GAP = 14.dp

/** Tipped this far from the viewer is edge-on, and there is nothing left to show. */
private const val CARD_LAY_DEGREES = 82f
