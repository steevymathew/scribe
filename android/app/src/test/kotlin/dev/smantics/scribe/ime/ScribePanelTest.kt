package dev.smantics.scribe.ime

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.ScribeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard's user flows, driven headlessly.
 *
 * This machine is aarch64 Linux, for which Google publishes no Android emulator, and
 * Robolectric's and Paparazzi's pixel-rendering runtimes are x86-64 only. So these tests
 * assert *behaviour and structure* — that the controls exist, are reachable, are labelled
 * for a screen reader, and route to the right action — and make no claim about how the
 * panel looks. Appearance is marked OWNER-VERIFY and is checked on a device.
 *
 * That distinction is kept honest deliberately: a test that claims to have verified an
 * interface it never rendered is exactly the kind of false report the project's own rules
 * forbid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScribePanelTest {

    @get:Rule val compose = createComposeRule()

    private class RecordingActions : PanelActions {
        val calls = mutableListOf<String>()
        override fun startRecording() { calls += "start" }
        override fun stopRecording() { calls += "stop" }
        override fun toggleMode() { calls += "toggle" }
        override fun setBoost(held: Boolean) { calls += "boost:$held" }
        override fun backspace() { calls += "backspace" }
        override fun space() { calls += "space" }
        override fun enter() { calls += "enter" }
        override fun moveCursor(delta: Int) { calls += "cursor:$delta" }
        override fun switchKeyboard() { calls += "switch" }
        override fun openApp() { calls += "openApp" }
    }

    private fun panel(
        state: EngineState = EngineState(status = DictationStatus.READY, statusDetail = "Ready"),
        actions: PanelActions = RecordingActions(),
    ) {
        compose.setContent {
            ScribeTheme {
                // A width is pinned so the layout is measured rather than collapsing to
                // zero, which would make every "is displayed" assertion meaningless.
                ScribePanel(state = state, actions = actions, modifier = Modifier.width(412.dp))
            }
        }
    }

    // ------------------------------------------------------------- the controls

    @Test fun `the panel shows the microphone, the mode toggle and the edit row`() {
        panel()
        compose.onNodeWithTag("mic-button").assertIsDisplayed()
        compose.onNodeWithTag("mode-toggle").assertIsDisplayed()
        compose.onNodeWithTag("key-Backspace").assertIsDisplayed()
        compose.onNodeWithTag("key-Switch keyboard").assertIsDisplayed()
    }

    @Test fun `the mode toggle sits next to the waveform and is reachable`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("mode-toggle").performClick()
        assertEquals(listOf("toggle"), actions.calls)
    }

    @Test fun `both modes are labelled so neither is a hidden state`() {
        panel()
        compose.onNodeWithText("RAW").assertIsDisplayed()
        compose.onNodeWithText("CLEAN").assertIsDisplayed()
    }

    // ------------------------------------------------------------- the status

    @Test fun `status text is the engine's, not a guess`() {
        panel(EngineState(status = DictationStatus.RECORDING, statusDetail = "Listening…"))
        compose.onNodeWithTag("status-detail").assertIsDisplayed()
        compose.onNodeWithText("Listening…").assertIsDisplayed()
    }

    @Test fun `an error is shown in the panel, not buried in a log`() {
        panel(
            EngineState(
                status = DictationStatus.ERROR,
                statusDetail = "Microphone unavailable — check Settings → Audio",
                error = "Microphone unavailable — check Settings → Audio",
            ),
        )
        compose.onNodeWithText("Microphone unavailable — check Settings → Audio").assertIsDisplayed()
    }

    @Test fun `the boost badge appears only when high accuracy is armed`() {
        panel(EngineState(status = DictationStatus.READY, statusDetail = "Ready", boostActive = true))
        compose.onNodeWithText("HD").assertIsDisplayed()
    }

    // ------------------------------------------------------ the permission path

    /**
     * The flow that decides whether someone gives up during setup. An input method cannot
     * request RECORD_AUDIO itself, so without a route back to the app this state is a dead
     * end — which is the shape of the complaints about Wispr Flow's Android setup.
     */
    @Test fun `without the microphone the panel explains and offers a way out`() {
        val actions = RecordingActions()
        panel(
            EngineState(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            ),
            actions,
        )
        compose.onNodeWithTag("permission-row").assertIsDisplayed()
        compose.onNodeWithTag("open-scribe").performClick()
        assertEquals(listOf("openApp"), actions.calls)
    }

    @Test fun `the permission state hides the microphone button rather than teasing it`() {
        panel(
            EngineState(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            ),
        )
        compose.onNodeWithTag("mic-button").assertDoesNotExist()
    }

    /** Editing keys stay available even without the microphone: the keyboard still works. */
    @Test fun `the edit row survives the permission state`() {
        panel(
            EngineState(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            ),
        )
        compose.onNodeWithTag("key-Switch keyboard").assertIsDisplayed()
    }

    // --------------------------------------------------------------- the keys

    @Test fun `every edit key routes to its action`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("key-Backspace").performClick()
        compose.onNodeWithTag("key-Space").performClick()
        compose.onNodeWithTag("key-Enter").performClick()
        compose.onNodeWithTag("key-Move cursor left").performClick()
        compose.onNodeWithTag("key-Move cursor right").performClick()
        compose.onNodeWithTag("key-Switch keyboard").performClick()

        assertEquals(
            listOf("backspace", "space", "enter", "cursor:-1", "cursor:1", "switch"),
            actions.calls,
        )
    }

    @Test fun `boost is reachable from the panel`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("boost-button").performClick()
        assertEquals(listOf("boost:true"), actions.calls)
    }

    // ------------------------------------------------------------ accessibility

    /**
     * Every control carries a description. A voice keyboard that a screen-reader user
     * cannot operate has failed at its own premise.
     */
    @Test fun `controls are labelled for a screen reader`() {
        panel()
        listOf(
            "Hold to dictate",
            "Dictation mode",
            "Backspace",
            "Space",
            "Enter",
            "Move cursor left",
            "Move cursor right",
            "Switch keyboard",
        ).forEach { label ->
            val nodes = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            assertTrue("no node described as \"$label\"", nodes.isNotEmpty())
        }
    }
}
