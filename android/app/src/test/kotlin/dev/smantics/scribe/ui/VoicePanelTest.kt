package dev.smantics.scribe.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.EngineState
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
@Config(sdk = [33])
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
    ) {
        compose.setContent {
            ScribeTheme {
                VoicePanel(
                    state = state,
                    modelLabel = "base.en",
                    onToggleMode = {},
                    onStop = {},
                    onCancel = {},
                    onCollapse = {},
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
}
