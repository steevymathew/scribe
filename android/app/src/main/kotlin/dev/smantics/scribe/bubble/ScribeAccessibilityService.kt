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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.ime.ImeComposeHost
import dev.smantics.scribe.ui.MainActivity
import dev.smantics.scribe.ui.components.VoiceBubble
import dev.smantics.scribe.ui.components.VoicePanel
import dev.smantics.scribe.ui.components.modelLabelFor
import dev.smantics.scribe.ui.theme.ScribeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        if (dismissedUntilNextField) {
            val stillEditable = runCatching {
                findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
                    val editable = node.isEditable
                    node.recycle()
                    editable
                }
            }.getOrNull() ?: false
            if (!stillEditable) dismissedUntilNextField = false
            return
        }

        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editable = node != null && node.isEditable && node.isVisibleToUser
        focusedPackage = node?.packageName?.toString()
        node?.recycle()

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

    /** Set when the user closes the bubble; cleared once they touch a different field. */
    @Volatile private var dismissedUntilNextField = false

    /** Persistent mode: the bubble stays up until it is explicitly dismissed. */
    @Volatile private var alwaysOn = true

    /** Only cleared by the notification action, so a dismissal really means dismissed. */
    @Volatile private var dismissedByUser = false

    // ---------------------------------------------------------------- the overlay

    private fun showOverlay() {
        if (overlay != null) return
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
                    // Collapse once the whole sequence, insertion included, is over.
                    LaunchedEffect(state.stage) {
                        if (state.stage == DictationStage.IDLE && !engine.isDictating) {
                            expanded = false
                        }
                    }
                    if (expanded) {
                        VoicePanel(
                            state = state,
                            modelLabel = modelLabelFor(state),
                            onToggleMode = { engine.toggleMode() },
                            onStop = { stopDictation() },
                            onCancel = { engine.cancelToggle(); expanded = false },
                            onOpenApp = { openApp() },
                        )
                    } else {
                        VoiceBubble(
                            state = state,
                            onClick = { startDictation() },
                            onClose = { dismiss() },
                            // Dragged with the finger, like every other floating control on
                            // the platform. Position is remembered for the session, so it
                            // stays where it was put.
                            modifier = Modifier.pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    moveBy(dragAmount.x, dragAmount.y)
                                }
                            },
                        )
                    }
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

    /**
     * Move the bubble. Gravity is END|BOTTOM, so x grows leftward and y grows upward.
     */
    private fun moveBy(dx: Float, dy: Float) {
        val params = overlayParams ?: return
        val view = overlay ?: return
        params.x = (params.x - dx).toInt().coerceAtLeast(0)
        params.y = (params.y - dy).toInt().coerceAtLeast(0)
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    /** Put the bubble away until the user taps a different text field. */
    private fun dismiss() {
        dismissedUntilNextField = true
        dismissedByUser = true
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
                    NotificationManager.IMPORTANCE_LOW,
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        overlayParams = null
        expanded = false
        runCatching { windowManager?.removeView(view) }
    }

    // -------------------------------------------------------------- dictation

    /**
     * One tap starts, one tap stops — the same toggle the keyboard uses.
     *
     * The panel stays expanded through the reveal and closes once the text has been
     * inserted, so the bubble is never sitting open over a finished job.
     */
    private fun startDictation() {
        expanded = true
        val started = engine.toggleDictation(sink = nodeSink, packageName = focusedPackage)
        if (!started && !engine.isDictating) {
            // Nothing silent: if the microphone could not be opened the panel says so
            // rather than the bubble appearing to do nothing at all, which is exactly how
            // this failed the first time it went on a phone.
            Log.w(TAG, "dictation did not start: ${engine.state.value.statusDetail}")
        }
    }

    private fun stopDictation() {
        if (engine.isDictating) engine.stopToggle() else engine.cancelToggle()
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
     * `ACTION_SET_TEXT` replaces a node's whole contents, so the existing text is read,
     * the transcript spliced in at the cursor, and the result written back — otherwise
     * dictating into a half-written message would delete the half already there. The
     * clipboard is never touched, which is the same promise the keyboard makes.
     */
    private val nodeSink = object : TextSink {
        override fun commit(text: String) {
            if (text.isEmpty()) return
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: error("no text field has focus")
            try {
                // An empty field often reports its *placeholder* as `text`. Splicing into
                // that produced "what you saidType a message…" — the transcript landing in
                // front of hint text that was never really there. `isShowingHintText` is
                // the only reliable way to tell the two apart, and when it is set the field
                // is empty however much text it claims to have.
                val showingHint = node.isShowingHintText ||
                    (node.hintText != null && node.hintText?.toString() == node.text?.toString())
                val existing = if (showingHint) "" else node.text?.toString().orEmpty()
                val start = node.textSelectionStart.coerceIn(0, existing.length)
                val end = node.textSelectionEnd.coerceIn(0, existing.length)
                    .coerceAtLeast(start)
                val from = minOf(start, end)
                val to = maxOf(start, end)

                val next = buildString {
                    append(existing, 0, from)
                    // A space when joining onto existing words, so dictating twice in a
                    // row does not run the sentences together.
                    if (from > 0 && !existing[from - 1].isWhitespace() && !text[0].isWhitespace()) {
                        append(' ')
                    }
                    append(text)
                    append(existing, to, existing.length)
                }

                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        next,
                    )
                }
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    "the app refused the text"
                }

                // Put the cursor after what was just inserted rather than at the end of
                // the field, so the user can carry on where they left off.
                val caret = next.length - (existing.length - to)
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
                    },
                )
            } finally {
                node.recycle()
            }
        }

        /**
         * Re-rendering after the fact is a keyboard feature. Through the accessibility
         * path there is no reliable way to know the field still holds exactly what Scribe
         * wrote, and rewriting on a guess would edit someone's message.
         */
        override fun replace(previous: String, next: String): Boolean = false
    }

    companion object {
        private const val TAG = "ScribeA11y"

        /** Long enough to ride out an app redrawing its view tree. */
        private const val HIDE_DEBOUNCE_MS = 700L

        private const val SUMMON_CHANNEL = "scribe-summon"
        private const val SUMMON_NOTIFICATION = 3
        const val ACTION_SUMMON = "dev.smantics.scribe.SUMMON_BUBBLE"

        @Volatile private var instance: ScribeAccessibilityService? = null

        /** Whether the service is actually running, for the settings screen to report. */
        val isRunning: Boolean get() = instance != null
    }
}
