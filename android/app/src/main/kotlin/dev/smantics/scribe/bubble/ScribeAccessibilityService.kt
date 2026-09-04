package dev.smantics.scribe.bubble

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.smantics.scribe.R
import dev.smantics.scribe.core.dictation.TextSink
import dev.smantics.scribe.core.dictation.TextSplice
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.ime.ImeComposeHost
import dev.smantics.scribe.ui.MainActivity
import dev.smantics.scribe.ui.components.BubbleAssembly
import dev.smantics.scribe.ui.components.DismissTarget
import dev.smantics.scribe.ui.components.modelLabelFor
import dev.smantics.scribe.ui.theme.ScribeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The floating bubble, and the thing that knows when to show it.
 *
 * **Why this is an accessibility service.** Deciding whether a text field has focus
 * anywhere on screen is not something an ordinary app can do — only an accessibility
 * service can see other apps' view trees. That is the real reason Wispr Flow needs one
 * too, and the observation in the brief that "there is something for the user to click on
 * if there is a text field on the screen" is exactly right: the bubble is not decoration,
 * it is the affordance.
 *
 * Two consequences worth stating, because this is the most invasive permission Scribe
 * asks for:
 *
 *  - It draws with `TYPE_ACCESSIBILITY_OVERLAY`, which needs **no** draw-over-other-apps
 *    permission. Turning the bubble on therefore costs one permission, not two.
 *  - It reads exactly two things from the focused node: whether it is editable, and — at
 *    the moment text is inserted — the existing contents, so the transcript can be placed
 *    at the cursor without destroying what is already there. Nothing is stored, logged or
 *    sent anywhere. `canRetrieveWindowContent` is required for `ACTION_SET_TEXT`; there is
 *    no way to type into another app's field without it.
 *
 * Insertion is `ACTION_SET_TEXT`, not the clipboard — the same promise the keyboard makes.
 */
class ScribeAccessibilityService : AccessibilityService() {

    private lateinit var engine: ScribeEngine
    private val host = ImeComposeHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var overlay: ComposeView? = null

    /** Expanded = the panel with the waveform; collapsed = the small bubble. */
    private var expanded by mutableStateOf(false)

    /** The package of the app whose field has focus, for per-app tone. */
    @Volatile private var focusedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        host.onCreate()
        engine = ScribeEngine.get(this)
        engine.warmUp()
        instance = this
        ContextCompat.registerReceiver(
            this,
            summonReceiver,
            IntentFilter(ACTION_SUMMON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // An accessibility overlay keeps drawing when the screen turns off, which puts the
        // bubble on the always-on display alongside the clock. Nothing about a dictation
        // button belongs there, so it comes down with the screen and back up with it.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Up straight away, rather than waiting to detect a text field that may never be
        // reported. The user turned this on; showing it is the whole agreement.
        showOverlay()
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> updateForFocus()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(summonReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(SUMMON_NOTIFICATION)
        }
        hideOverlay()
        host.onDestroy()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ field tracking

    /**
     * Show the bubble when an editable field has focus, hide it when one does not.
     *
     * While dictating the bubble stays put regardless: losing it mid-sentence because the
     * focus flickered would be worse than a moment of it lingering.
     */
    private fun updateForFocus() {
        // Never take the bubble away mid-utterance: focus flickers as apps redraw, and
        // losing the control you are speaking into is worse than it lingering a moment.
        if (engine.isDictating || expanded) return

        // Noted whatever mode the bubble is in. This used to sit below the persistent-mode
        // branch, which returns — so once the bubble became always-on nothing recorded the
        // focused app at all, and every dictation through the bubble was cleaned with the
        // neutral tone profile regardless of what it was being typed into.
        noteFocus()

        // Kept up regardless of what has focus.
        //
        // Focus detection turned out to be the wrong thing to hang this on: apps report
        // editable focus inconsistently, some never do, and a button that appears most of
        // the time is worse than one that is simply always there. Android's own Bubbles
        // API — the one behind Messenger's chat heads — is reserved for conversations
        // (a MessagingStyle notification with a long-lived shortcut), so Scribe cannot use
        // it and has to keep its own window alive. This is that: shown until dismissed.
        if (alwaysOn) {
            if (!dismissedByUser) showOverlay()
            return
        }

        // Nodes are not recycled anywhere in this file. `recycle()` has been a documented
        // no-op since API 33, which is Scribe's minimum, and calling it bought nothing but
        // a chance of touching a node the framework had already taken back.
        if (dismissedUntilNextField) {
            val stillEditable = runCatching {
                findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable
            }.getOrNull() ?: false
            if (!stillEditable) dismissedUntilNextField = false
            return
        }

        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editable = node != null && node.isEditable && node.isVisibleToUser
        focusedPackage = node?.packageName?.toString()

        if (editable) {
            // Showing is immediate; hiding is not. Apps churn their view trees constantly,
            // and a bubble that vanishes and reappears on every redraw reads as broken
            // even when it is technically correct at every instant.
            hideJob?.cancel()
            hideJob = null
            showOverlay()
        } else if (hideJob == null) {
            hideJob = scope.launch {
                delay(HIDE_DEBOUNCE_MS)
                hideJob = null
                if (!engine.isDictating && !expanded) hideOverlay()
            }
        }
    }

    private var hideJob: Job? = null

    /**
     * Remember which app owns the field that currently has focus.
     *
     * Used for one thing — choosing the tone profile the Clean pipeline runs with. Nothing
     * about the field's *contents* is read here.
     *
     * Rate-limited because the event stream it hangs off fires on every content change in
     * every app on screen, and each call is a round trip into the focused app's process.
     */
    private fun noteFocus() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusNote < FOCUS_NOTE_INTERVAL_MS) return
        lastFocusNote = now
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (node != null && node.isEditable) focusedPackage = node.packageName?.toString()
    }

    @Volatile private var lastFocusNote = 0L

    /** Set when the user closes the bubble; cleared once they touch a different field. */
    @Volatile private var dismissedUntilNextField = false

    /** Persistent mode: the bubble stays up until it is explicitly dismissed. */
    @Volatile private var alwaysOn = true

    /** Only cleared by the notification action, so a dismissal really means dismissed. */
    @Volatile private var dismissedByUser = false

    // ---------------------------------------------------------------- the overlay

    private fun showOverlay() {
        if (overlay != null || !screenOn) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Needs no SYSTEM_ALERT_WINDOW grant; an accessibility service may draw this.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never focusable. The bubble must not take focus from the field being typed
            // into, or the text it produces would have nowhere to go.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = 16
            // Sits clear of the on-screen keyboard, which is what is usually up when a
            // text field has focus.
            y = 320
        }

        overlayParams = params
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by engine.state.collectAsState()
                ScribeTheme {
                    // Collapse once the whole sequence, insertion included, is over —
                    // unless it did not work. A panel that closes on failure exactly as it
                    // closes on success is how the insertion bug stayed invisible: the
                    // words were shown being assembled and then the bubble simply shrank.
                    LaunchedEffect(state.stage, insertFailure) {
                        if (state.stage == DictationStage.IDLE &&
                            !engine.isDictating &&
                            insertFailure == null
                        ) {
                            expanded = false
                        }
                    }

                    // One object, dragged as one thing: see BubbleAssembly for why the
                    // circle sits below the panel and why that keeps it still.
                    BubbleAssembly(
                        state = state,
                        expanded = expanded,
                        modelLabel = modelLabelFor(state),
                        onToggleExpanded = {
                            // The circle opens and closes the panel. Nothing else,
                            // deliberately: a control that sometimes toggles a panel and
                            // sometimes starts recording is one you have to watch to use.
                            if (expanded && engine.isDictating) engine.cancelToggle()
                            insertFailure = null
                            expanded = !expanded
                        },
                        onClose = { dismiss() },
                        onToggleDictation = { toggleDictation() },
                        onToggleMode = { engine.toggleMode() },
                        onCancel = {
                            engine.cancelToggle()
                            insertFailure = null
                        },
                        onOpenApp = { openApp() },
                        failure = insertFailure,
                        onRetryInsert = { retryInsert() },
                        onDismissFailure = { insertFailure = null },
                        // The drag lives on the whole assembly, not just the circle, so an
                        // open panel can be moved off whatever it is covering. Taps still
                        // reach the buttons inside: a child claims the tap, this claims the
                        // drag once it passes the touch slop.
                        //
                        // The one place a vertical drag does *not* move the window is over
                        // the transcript, which scrolls — a child scroller takes vertical
                        // drags before this sees them. That is the right way round: long
                        // transcripts have to be readable, and the circle underneath is
                        // always a handle.
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { if (overTarget) dismiss() else settle() },
                                onDragCancel = { settle() },
                            ) { change, dragAmount ->
                                change.consume()
                                moveBy(dragAmount.x, dragAmount.y)
                            }
                        },
                    )
                }
            }
        }
        host.attachTo(view)
        host.onResume()

        runCatching { wm.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure { Log.w(TAG, "could not add the bubble", it) }
    }

    /** Remembered so a drag can adjust it without rebuilding the window. */
    private var overlayParams: WindowManager.LayoutParams? = null

    /** True when the bubble is over the dismiss target and letting go would close it. */
    private var overTarget by mutableStateOf(false)

    /** The window holding the dismiss target, added only while a drag is in progress. */
    private var dismissTarget: ComposeView? = null

    /**
     * Move the bubble. Gravity is END|BOTTOM, so x grows leftward and y grows upward.
     */
    private fun moveBy(dx: Float, dy: Float) {
        val params = overlayParams ?: return
        val view = overlay ?: return
        // Bounded at both ends, not just at zero. The window is WRAP_CONTENT with
        // FLAG_LAYOUT_NO_LIMITS, so it is free to sit off the edge of the display — which
        // was survivable when the thing being dragged was a 58 dp circle and is not now
        // that an open panel goes with it. `view.width/height` is the assembly's real
        // measured size, so the limit follows the panel opening and closing.
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
        params.x = (params.x - dx).toInt().coerceIn(0, maxX)
        params.y = (params.y - dy).toInt().coerceIn(0, maxY)
        runCatching { windowManager?.updateViewLayout(view, params) }

        showDismissTarget()
        // Gravity is BOTTOM, so a small y means near the bottom of the screen, which is
        // where the target sits.
        overTarget = params.y < DISMISS_REACH_PX
    }

    /** Snap back from the dismiss zone when the finger lifts without dropping. */
    private fun settle() {
        hideDismissTarget()
        val params = overlayParams ?: return
        val view = overlay ?: return
        if (params.y < DISMISS_REACH_PX) {
            params.y = DISMISS_REACH_PX
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
        overTarget = false
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val wm = windowManager ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = 96
        }
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ScribeTheme { DismissTarget(active = overTarget) } }
        }
        host.attachTo(view)
        runCatching { wm.addView(view, params) }.onSuccess { dismissTarget = view }
    }

    private fun hideDismissTarget() {
        val view = dismissTarget ?: return
        dismissTarget = null
        runCatching { windowManager?.removeView(view) }
    }

    /** Put the bubble away until the user taps a different text field. */
    private fun dismiss() {
        dismissedUntilNextField = true
        dismissedByUser = true
        hideDismissTarget()
        hideOverlay()
        showSummonNotification()
    }

    /**
     * The way back, once the bubble has been closed.
     *
     * A persistent, silent notification with one action. This is the "hard-coded button"
     * the bubble needed: it does not depend on detecting anything, it is in the same place
     * every time, and it works from any screen.
     */
    private fun showSummonNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(SUMMON_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SUMMON_CHANNEL,
                    getString(R.string.summon_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { setShowBadge(false) },
            )
        }
        val summon = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_SUMMON).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, SUMMON_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.summon_title))
            .setContentText(getString(R.string.summon_body))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(summon)
            .addAction(0, getString(R.string.summon_action), summon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        runCatching { manager.notify(SUMMON_NOTIFICATION, notification) }
    }

    /** Bring the bubble back and take the notification away. */
    private fun summon() {
        dismissedByUser = false
        dismissedUntilNextField = false
        showOverlay()
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(SUMMON_NOTIFICATION)
        }
    }

    private val summonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SUMMON) summon()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    if (engine.isDictating) engine.cancelToggle()
                    hideOverlay()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (alwaysOn && !dismissedByUser) showOverlay()
                }
            }
        }
    }

    @Volatile private var screenOn = true

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        overlayParams = null
        expanded = false
        insertFailure = null
        runCatching { windowManager?.removeView(view) }
    }

    // -------------------------------------------------------------- dictation

    /**
     * One tap starts, one tap finishes — the same toggle the keyboard uses.
     *
     * Driven from the panel's microphone button rather than the circle: the circle opens
     * and closes the panel now, and one control that does two different things depending
     * on state is a control you have to watch to use. The panel stays open through the
     * reveal and closes once the text is in, so it is never sitting open over a finished
     * job — but only when the insertion actually worked.
     */
    private fun toggleDictation() {
        if (engine.isDictating) {
            engine.stopToggle()
            return
        }
        // A new utterance clears the last one's failure: whatever went wrong, the user has
        // moved on and the stale message would only be in the way.
        insertFailure = null
        val started = engine.toggleDictation(sink = nodeSink, packageName = focusedPackage)
        if (!started && !engine.isDictating) {
            // Nothing silent: if the microphone could not be opened the panel says so
            // rather than the bubble appearing to do nothing at all, which is exactly how
            // this failed the first time it went on a phone.
            Log.w(TAG, "dictation did not start: ${engine.state.value.statusDetail}")
        }
    }

    private fun openApp() {
        expanded = false
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    // ------------------------------------------------------------ text insertion

    /**
     * Types into whatever field has focus, in whatever app owns it.
     *
     * **This is the part that was broken, and it failed silently.** There was one way to
     * find the field — `findFocus(FOCUS_INPUT)` — and one way to write to it, and when
     * either came back empty-handed the exception was swallowed upstream, the panel
     * collapsed on its way back to IDLE, and the user watched their sentence be assembled
     * and then disappear. Three things changed:
     *
     *  - **Finding the field is a cascade, not a single call** — see [resolveTarget].
     *    Between the app, its input method and Scribe's own overlay there are several
     *    windows on screen, and which of them the framework treats as focused is exactly
     *    the thing that varies by OEM.
     *  - **It runs on the service's own thread.** Accessibility node calls are round trips
     *    into another process; issuing them from the reveal's timer thread was doing the
     *    one thing every sample in the platform documentation avoids.
     *  - **A failure is now visible.** [insertFailure] holds the panel open with the words
     *    still in it and a button to try again, instead of closing as if it had worked.
     *
     * `ACTION_SET_TEXT` replaces a node's whole contents, so the existing text is read,
     * the transcript spliced in at the cursor, and the result written back — otherwise
     * dictating into a half-written message would delete the half already there. The
     * clipboard is never touched, which is the same promise the keyboard makes.
     */
    private val nodeSink = object : TextSink {
        override fun commit(text: String) {
            if (text.isEmpty()) return
            insertFromAnyThread(text)?.let { error(it) }
        }

        /**
         * Re-rendering after the fact is a keyboard feature. Through the accessibility
         * path there is no reliable way to know the field still holds exactly what Scribe
         * wrote, and rewriting on a guess would edit someone's message.
         */
        override fun replace(previous: String, next: String): Boolean = false
    }

    /** Posts to the service thread and waits, because [TextSink.commit] must report back. */
    private fun insertFromAnyThread(text: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return insert(text)
        val problem = AtomicReference<String?>(null)
        val finished = CountDownLatch(1)
        main.post {
            problem.set(insert(text))
            finished.countDown()
        }
        if (!finished.await(INSERT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return note("the app did not answer in time")
        }
        return problem.get()
    }

    /** Put [text] in the field. Returns null on success, or what went wrong. */
    private fun insert(text: String): String? {
        val node = resolveTarget() ?: return note("no text field has focus")
        val problem = runCatching { typeInto(node, text) }
            .getOrElse { it.message ?: it::class.java.simpleName }
        return if (problem == null) {
            insertFailure = null
            Log.i(TAG, "inserted ${text.length} characters into ${node.packageName}")
            null
        } else {
            note(problem)
        }
    }

    /** Record a failure where both the user and `adb logcat -s ScribeA11y` can see it. */
    private fun note(reason: String): String {
        Log.w(TAG, "could not insert: $reason")
        insertFailure = reason
        return reason
    }

    /**
     * Find something to type into.
     *
     * Ordered by how much the answer can be trusted, and each step is tried only because
     * the one before it is known to come back empty on some device or in some app:
     *
     *  1. `findFocus` on the service, which resolves through whichever window the system
     *     believes has input focus.
     *  2. The same question asked of the active window's tree directly. The two disagree
     *     when something else is between the app and the top of the window stack — which,
     *     with a floating bubble on screen, is every time the bubble is used.
     *  3. Every window Scribe is allowed to see, its own overlays excluded.
     *  4. A walk of the active window looking for a field that says it is focused. Apps
     *     that draw their own text handling — WebViews especially — often never report
     *     input focus at all.
     *  5. The only editable field on screen, if there is exactly one. With one candidate
     *     there is nothing to get wrong; with two, guessing could type into the wrong one,
     *     so it gives up instead.
     */
    private fun resolveTarget(): AccessibilityNodeInfo? {
        editable(runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())
            ?.let { return it }

        val root = runCatching { rootInActiveWindow }.getOrNull()
        editable(runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())
            ?.let { return it }

        runCatching { windows }.getOrNull().orEmpty().forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            val windowRoot = runCatching { window.root }.getOrNull() ?: return@forEach
            editable(
                runCatching { windowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull(),
            )?.let { return it }
        }

        return root?.let { searchForField(it) }
    }

    /** A node worth typing into: editable, and not one of Scribe's own views. */
    private fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (!node.isEditable) return null
        if (node.packageName?.toString() == packageName) return null
        return node
    }

    /**
     * Walk the tree for a field, preferring one that claims focus.
     *
     * Breadth-first and capped: this runs on the service thread while the user is waiting,
     * and a pathological view hierarchy must not be able to stall it.
     */
    private fun searchForField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_NODES_SCANNED) {
            val node = queue.removeFirst()
            scanned++
            if (editable(node) != null && node.isVisibleToUser) {
                if (node.isFocused) return node
                candidates += node
            }
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.addLast(it) }
            }
        }
        // Two fields and no focus is a guess, and guessing types into the wrong one.
        return candidates.singleOrNull()
    }

    /** Splice the transcript in at the cursor and write the field back. */
    private fun typeInto(node: AccessibilityNodeInfo, text: String): String? {
        // An empty field often reports its *placeholder* as `text`. Splicing into that
        // produced "what you saidType a message…" — the transcript landing in front of
        // hint text that was never really there. `isShowingHintText` is the only reliable
        // way to tell the two apart, and when it is set the field is empty however much
        // text it claims to have.
        val showingHint = node.isShowingHintText ||
            (node.hintText != null && node.hintText?.toString() == node.text?.toString())
        val existing = if (showingHint) "" else node.text?.toString().orEmpty()
        // Where the words go is decided in `core`, which has no Android in it and is
        // therefore the one part of this path that is covered by tests on a machine with
        // no phone attached. See TextSpliceTest.
        val spliced = TextSplice.into(
            existing = existing,
            start = node.textSelectionStart,
            end = node.textSelectionEnd,
            insertion = text,
        )
        val next = spliced.text

        // Ask for focus first when the node does not have it. The bubble is used while
        // *another* keyboard owns the field, so by the time the reveal finishes the app
        // may have moved focus on — and several toolkits refuse ACTION_SET_TEXT outright
        // on a node they do not consider focused.
        if (!node.isFocused) runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        var typed = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)

        if (!typed) {
            // Second attempt with a caret placed first. A field that has never been
            // touched can report itself editable and still refuse to be written to until
            // it has a selection to write at.
            runCatching {
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    caretAt(existing.length),
                )
            }
            typed = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        }

        if (!typed) return "this app would not accept typed text"

        // Put the cursor after what was just inserted rather than at the end of the field,
        // so the user can carry on where they left off. Best effort: a field that will
        // take text but not a selection is still a success.
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caretAt(spliced.caret))
        }

        // Read back, for the log only. Some fields reformat what they are given and some
        // report stale text for a moment, so this is a diagnostic and never a verdict —
        // treating a mismatch as failure would offer a retry that typed everything twice.
        runCatching {
            if (node.refresh() && node.text?.toString() == existing && next != existing) {
                Log.w(TAG, "the field still reads as it did before the write")
            }
        }
        return null
    }

    private fun caretAt(position: Int): Bundle = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
    }

    /**
     * Try the last transcript again, after the user has tapped back into a field.
     *
     * The words are still in `state.lastText`, so a failed insertion costs a tap rather
     * than the whole utterance.
     */
    private fun retryInsert() {
        val text = engine.state.value.lastText
        if (text.isEmpty()) {
            insertFailure = null
            return
        }
        insertFailure = null
        insert(text)
    }

    /** The service's own thread, which is where accessibility calls belong. */
    private val main = Handler(Looper.getMainLooper())

    /**
     * Why the last insertion failed, or null. Holding this keeps the panel open with the
     * transcript still in it — the alternative, which is what shipped, is a panel that
     * closes exactly as it does on success and a field that never receives anything.
     */
    private var insertFailure by mutableStateOf<String?>(null)

    companion object {
        private const val TAG = "ScribeA11y"

        /** Long enough to ride out an app redrawing its view tree. */
        private const val HIDE_DEBOUNCE_MS = 700L

        /** How close to the bottom counts as being over the dismiss target. */
        private const val DISMISS_REACH_PX = 200

        /** How long the reveal's thread will wait for the field to accept the text. */
        private const val INSERT_TIMEOUT_MS = 2_500L

        /** A cap on the tree walk, so a pathological view hierarchy cannot stall it. */
        private const val MAX_NODES_SCANNED = 600

        /** Focus is noted at most this often; the event stream is far busier than this. */
        private const val FOCUS_NOTE_INTERVAL_MS = 250L

        private const val SUMMON_CHANNEL = "scribe-summon"
        private const val SUMMON_NOTIFICATION = 3
        const val ACTION_SUMMON = "dev.smantics.scribe.SUMMON_BUBBLE"

        @Volatile private var instance: ScribeAccessibilityService? = null

        /** Whether the service is actually running, for the settings screen to report. */
        val isRunning: Boolean get() = instance != null
    }
}
