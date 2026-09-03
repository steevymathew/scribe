package dev.smantics.scribe.bubble

import android.accessibilityservice.AccessibilityService
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import kotlinx.coroutines.SupervisorJob
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

        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editable = node != null && node.isEditable && node.isVisibleToUser
        focusedPackage = node?.packageName?.toString()
        node?.recycle()

        if (editable) showOverlay() else hideOverlay()
    }

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
                        VoiceBubble(state = state, onClick = { startDictation() })
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

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
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
                val existing = node.text?.toString().orEmpty()
                val start = node.textSelectionStart.coerceIn(0, existing.length)
                val end = node.textSelectionEnd.coerceIn(0, existing.length)
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

        @Volatile private var instance: ScribeAccessibilityService? = null

        /** Whether the service is actually running, for the settings screen to report. */
        val isRunning: Boolean get() = instance != null
    }
}
