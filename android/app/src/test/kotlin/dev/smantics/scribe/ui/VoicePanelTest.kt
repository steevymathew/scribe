package dev.smantics.scribe.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ui.components.BubbleAssembly
import dev.smantics.scribe.ui.components.VoicePanel
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.ScribeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The bubble's panel, and specifically what it does when the words cannot be typed.
 *
 * The bug these pin: insertion through the accessibility path failed, the exception was
 * swallowed, the panel collapsed on its way back to IDLE, and the whole thing was
 * indistinguishable from success — the user watched a sentence be assembled and then
 * watched the bubble shrink with the field still empty. Finding the field on a real phone
 * cannot be tested here; *what the panel does when it is not found* can be, and this is
 * the half that made the failure invisible.
 */
@RunWith(RobolectricTestRunner::class)
// A screen big enough to hold the whole panel. Robolectric's default is 320x470 dp, which
// is narrower and shorter than any phone this runs on and shorter than the keyboard itself.
@Config(sdk = [33], qualifiers = "+w420dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VoicePanelTest {

    @get:Rule val compose = createComposeRule()

    private val transcript = "See you on Tuesday."

    private fun panel(
        failure: String? = null,
        state: EngineState = EngineState(
            status = DictationStatus.READY,
            statusDetail = "Ready",
            stage = DictationStage.IDLE,
            lastText = transcript,
        ),
        onRetry: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onCancel: () -> Unit = {},
        onToggleDictation: () -> Unit = {},
    ) {
        compose.setContent {
            ScribeTheme {
                VoicePanel(
                    state = state,
                    modelLabel = "Base (English)",
                    onToggleMode = {},
                    onToggleDictation = onToggleDictation,
                    onCancel = onCancel,
                    onOpenApp = {},
                    // Pinned so the layout is measured rather than collapsing to zero,
                    // which would make every "is displayed" assertion meaningless.
                    modifier = Modifier.width(412.dp),
                    failure = failure,
                    onRetryInsert = onRetry,
                    onDismissFailure = onDismiss,
                )
            }
        }
    }

    @Test fun `nothing is said about insertion when it worked`() {
        panel()
        compose.onNodeWithTag("voice-insert-failed").assertDoesNotExist()
        compose.onNodeWithTag("voice-panel-status").assertIsDisplayed()
    }

    @Test fun `a failed insertion says so instead of closing quietly`() {
        panel(failure = "no text field has focus")
        compose.onNodeWithTag("voice-insert-failed").assertIsDisplayed()
    }

    /** The words are not lost with the field: they are still on screen to be recovered. */
    @Test fun `the transcript survives a failed insertion`() {
        panel(failure = "no text field has focus")
        compose.onNodeWithText(transcript).assertIsDisplayed()
    }

    @Test fun `there is a way to try again`() {
        val retries = mutableListOf<Unit>()
        panel(failure = "no text field has focus", onRetry = { retries += Unit })
        compose.onNodeWithTag("voice-retry-insert").performClick()
        assertEquals(1, retries.size)
    }

    @Test fun `and a way to give up on it`() {
        val dismissals = mutableListOf<Unit>()
        panel(failure = "no text field has focus", onDismiss = { dismissals += Unit })
        compose.onNodeWithTag("voice-dismiss-failure").performClick()
        assertEquals(1, dismissals.size)
    }

    // ------------------------------------------------------------- the controls

    /**
     * The panel has to be able to *start* a dictation, not only finish one. The circle
     * opens and closes the panel now, so it is no longer the thing that begins recording.
     */
    @Test fun `the microphone starts a dictation from the panel`() {
        val toggles = mutableListOf<Unit>()
        panel(onToggleDictation = { toggles += Unit })
        compose.onNodeWithTag("voice-insert").performClick()
        assertEquals(1, toggles.size)
    }

    @Test fun `the same control finishes it`() {
        val toggles = mutableListOf<Unit>()
        panel(
            state = listening(),
            onToggleDictation = { toggles += Unit },
        )
        compose.onNodeWithContentDescription("Insert what I said").performClick()
        assertEquals(1, toggles.size)
    }

    /** Nothing to cancel when nothing is running; a cancel that is always there is noise. */
    @Test fun `there is no cancel until there is something to cancel`() {
        panel()
        compose.onNodeWithTag("voice-cancel").assertDoesNotExist()
    }

    @Test fun `cancel appears while dictating and is reachable`() {
        val cancels = mutableListOf<Unit>()
        panel(state = listening(), onCancel = { cancels += Unit })
        compose.onNodeWithTag("voice-cancel").assertIsDisplayed()
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals(1, cancels.size)
    }

    /** It used to be a line in the hamburger menu. A destructive action is not a menu item. */
    @Test fun `discard is not buried in the menu any more`() {
        panel(state = listening())
        compose.onNodeWithTag("voice-discard").assertDoesNotExist()
    }

    @Test fun `the panel no longer carries its own collapse control`() {
        panel()
        compose.onNodeWithTag("voice-collapse").assertDoesNotExist()
    }

    // -------------------------------------------------------- the assembly

    private fun assembly(
        expanded: Boolean,
        state: EngineState = idle(),
        onToggleExpanded: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        compose.setContent {
            ScribeTheme {
                BubbleAssembly(
                    state = state,
                    expanded = expanded,
                    modelLabel = "Base (English)",
                    onToggleExpanded = onToggleExpanded,
                    onClose = onClose,
                    onToggleDictation = {},
                    onToggleMode = {},
                    onCancel = {},
                    onOpenApp = {},
                    modifier = Modifier.width(412.dp),
                )
            }
        }
    }

    /** The circle used to disappear the moment the panel it opened appeared. */
    @Test fun `the circle is there with the panel closed`() {
        assembly(expanded = false)
        compose.onNodeWithTag("voice-bubble").assertIsDisplayed()
        compose.onNodeWithTag("voice-panel").assertDoesNotExist()
    }

    @Test fun `and still there with the panel open`() {
        assembly(expanded = true)
        compose.onNodeWithTag("voice-bubble").assertIsDisplayed()
        compose.onNodeWithTag("voice-panel").assertIsDisplayed()
    }

    @Test fun `the circle opens and closes the panel`() {
        val taps = mutableListOf<Unit>()
        assembly(expanded = false, onToggleExpanded = { taps += Unit })
        compose.onNodeWithTag("voice-bubble").performClick()
        assertEquals(1, taps.size)
    }

    /** Which way the next tap goes has to be readable without pressing it. */
    @Test fun `the circle says it will open when the panel is closed`() {
        assembly(expanded = false)
        compose.onNodeWithContentDescription("Open Scribe").assertIsDisplayed()
    }

    @Test fun `and that it will close when the panel is open`() {
        assembly(expanded = true)
        compose.onNodeWithContentDescription("Close the Scribe panel").assertIsDisplayed()
    }

    /** Closing the bubble entirely is a different control from collapsing the panel. */
    @Test fun `the close badge is not the expand control`() {
        val closes = mutableListOf<Unit>()
        val taps = mutableListOf<Unit>()
        assembly(expanded = true, onToggleExpanded = { taps += Unit }, onClose = { closes += Unit })
        compose.onNodeWithTag("bubble-close").performClick()
        assertEquals(1, closes.size)
        assertEquals(0, taps.size)
    }

    private fun idle() = EngineState(
        status = DictationStatus.READY,
        statusDetail = "Ready",
        stage = DictationStage.IDLE,
        lastText = transcript,
    )

    private fun listening() = EngineState(
        status = DictationStatus.RECORDING,
        statusDetail = "Listening…",
        stage = DictationStage.LISTENING,
        lastText = transcript,
    )
}
