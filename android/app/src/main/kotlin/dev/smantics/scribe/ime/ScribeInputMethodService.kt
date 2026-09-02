package dev.smantics.scribe.ime

import android.content.Intent
import android.util.Log
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.ui.MainActivity
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.ScribeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Scribe as a system keyboard.
 *
 * This is the architectural decision the whole Android port turns on. Wispr Flow injects
 * text from a floating bubble, and its Android users report that the bubble "breaks every
 * five to ten minutes" because Android's battery management stops the process behind it;
 * getting it working at all involves developer options. An input method has none of those
 * problems by construction: the system binds it, keeps it alive for exactly as long as a
 * text field has focus, and asks for no overlay permission, no accessibility service and
 * no battery exemption. Text goes in through `InputConnection`, which every app accepts
 * and which never touches the clipboard.
 *
 * The service is deliberately thin. It owns the view and the input connection; the model,
 * the recording and the last transcript live in [ScribeEngine] at process scope, because
 * this view is destroyed and rebuilt every time the Fold is opened or closed.
 */
class ScribeInputMethodService : InputMethodService() {

    private companion object { const val TAG = "ScribeIME" }

    private lateinit var engine: ScribeEngine
    private val host = ImeComposeHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handedness = MutableStateFlow(Handedness.RIGHT)

    private val sink = InputConnectionSink { currentInputConnection }

    override fun onCreate() {
        super.onCreate()
        host.onCreate()
        engine = ScribeEngine.get(this)
        engine.warmUp()
        scope.launch {
            engine.settings.config.collect { handedness.value = it.handedness }
        }
    }

    /**
     * Build the panel.
     *
     * The owners are applied to the window's decor as well as to the ComposeView, and the
     * lifecycle is moved to RESUMED here rather than waiting for `onStartInputView` —
     * Compose's frame clock starts paused and only runs from `ON_START`, so a host still
     * at CREATED composes and then draws nothing at all.
     *
     * The whole thing is wrapped. If composition cannot start, the alternative to a
     * fallback view is an input method that is listed, can be enabled, and silently never
     * appears — which is precisely how this failed the first time it was put on a phone.
     * A keyboard that says why it is broken is worth far more than one that vanishes.
     */
    override fun onCreateInputView(): View {
        host.attachToWindowRoot(window?.window?.decorView)
        host.onResume()

        return runCatching {
            ComposeView(this).apply {
                // Torn down with its window rather than with the composition, so a
                // fold/unfold rebuilds cleanly instead of leaking the previous one.
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val state by engine.state.collectAsState()
                    val hand by handedness.collectAsState()
                    ScribeTheme(handedness = hand) {
                        ScribePanel(state = state, actions = panelActions)
                    }
                }
                host.attachTo(this)
            }
        }.getOrElse { failure ->
            Log.e(TAG, "the Compose panel could not be created", failure)
            fallbackView(failure)
        }
    }

    /**
     * A plain-View panel for when Compose will not start.
     *
     * Deliberately built from nothing but framework classes, so it cannot fail for the
     * same reason the real panel did. It reports the failure and offers the one action
     * that always works — going back to the previous keyboard.
     */
    private fun fallbackView(failure: Throwable): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF0B0F14.toInt())
        setPadding(48, 48, 48, 48)
        addView(
            TextView(context).apply {
                text = "Scribe's keyboard could not start on this device."
                setTextColor(0xFFE7EEF4.toInt())
                textSize = 15f
            },
        )
        addView(
            TextView(context).apply {
                text = "${failure::class.java.simpleName}: ${failure.message}"
                setTextColor(0xFF93A1B0.toInt())
                textSize = 12f
                setPadding(0, 16, 0, 24)
            },
        )
        addView(
            Button(context).apply {
                text = "Switch keyboard"
                setOnClickListener { panelActions.switchKeyboard() }
            },
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // The decor exists for certain by now, even if it did not when the view was built.
        host.attachToWindowRoot(window?.window?.decorView)
        host.onResume()
        // The package being typed into is the only context Scribe ever reads, and it is
        // used for one thing: choosing a tone profile. Nothing about the field's contents
        // is inspected, and nothing leaves the device.
        engine.attach(sink, info?.packageName)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        host.onPause()
        // Abandon any decode in flight. The user has moved on; making them wait for a
        // transcript they will never see would only cost battery.
        engine.detach()
    }

    override fun onDestroy() {
        host.onDestroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------- the actions

    private val panelActions = object : PanelActions {
        override fun startRecording() = engine.startRecording()
        override fun stopRecording() = engine.stopRecording()
        override fun toggleMode() = engine.toggleMode()
        override fun setBoost(held: Boolean) = engine.setBoost(held)

        override fun backspace() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        override fun space() {
            currentInputConnection?.commitText(" ", 1)
        }

        /**
         * Enter means "send" in a chat app and "new line" in a note. The editor tells us
         * which through its IME action, and honouring that is the difference between a
         * keyboard that works in WhatsApp and one that inserts a stray line break.
         */
        override fun enter() {
            val ic = currentInputConnection ?: return
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            when (action) {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE,
                -> ic.performEditorAction(action)
                else -> {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }

        override fun moveCursor(delta: Int) {
            val ic = currentInputConnection ?: return
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }

        /**
         * One tap back to the previous keyboard.
         *
         * Choosing Scribe must never feel like a commitment. `switchToPreviousInputMethod`
         * returns to whatever the user was using before; if the system has nothing to go
         * back to, the picker is the honest fallback rather than doing nothing.
         */
        override fun switchKeyboard() {
            val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                false
            }
            if (!switched) {
                runCatching {
                    @Suppress("DEPRECATION")
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showInputMethodPicker()
                }.onFailure { Log.w(TAG, "could not show the keyboard picker", it) }
            }
        }

        /**
         * Open Scribe so the user can grant the microphone.
         *
         * Wrapped, because starting an Activity from an input method runs into
         * background-activity-start rules that vary by Android version and by OEM. If the
         * system refuses, the exception would otherwise propagate out of a click handler
         * and take the whole keyboard down — turning "I could not open the app" into "my
         * keyboard crashed", which is a far worse failure than the one it came from.
         */
        override fun openApp() {
            val intent = Intent(this@ScribeInputMethodService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
            }
            runCatching { startActivity(intent) }.onFailure {
                Log.w(TAG, "could not open Scribe from the keyboard", it)
            }
        }
    }
}
