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
import dev.smantics.scribe.core.clean.TextDiff
import dev.smantics.scribe.dictation.DictationStage
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
        override fun toggleDictation() { calls += "toggleDictation" }
        override fun cancelDictation() { calls += "cancel" }
        override fun toggleMode() { calls += "toggle" }
        override fun setBoost(held: Boolean) { calls += "boost:$held" }
        override fun type(text: String) { calls += "type:$text" }
        override fun backspace() { calls += "backspace" }
        override fun deleteWord() { calls += "deleteWord" }
        override fun space() { calls += "space" }
        override fun enter() { calls += "enter" }
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

    @Test fun `the panel shows the microphone, the mode toggle and a usable keyboard`() {
        panel()
        compose.onNodeWithTag("mic-button").assertIsDisplayed()
        compose.onNodeWithTag("mode-toggle").assertIsDisplayed()
        compose.onNodeWithTag("key-Backspace").assertIsDisplayed()
        compose.onNodeWithTag("key-Switch keyboard").assertIsDisplayed()
    }

    /**
     * The correction the first release needed. Shipping voice-only meant fixing one
     * misheard word sent the user to another keyboard, so Scribe must be able to type.
     */
    @Test fun `letters can be typed without leaving Scribe`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("key-q").performClick()
        compose.onNodeWithTag("key-a").performClick()
        compose.onNodeWithTag("key-Space").performClick()
        assertEquals(listOf("type:q", "type:a", "space"), actions.calls)
    }

    @Test fun `shift capitalises exactly one letter`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("key-Shift").performClick()
        compose.onNodeWithTag("key-Q").performClick()
        compose.onNodeWithTag("key-a").performClick()   // shift released after one letter
        assertEquals(listOf("type:Q", "type:a"), actions.calls)
    }

    @Test fun `the symbols layer is reachable and comes back`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("key-Symbols").performClick()
        compose.onNodeWithTag("key-1").performClick()
        compose.onNodeWithTag("key-Letters").performClick()
        compose.onNodeWithTag("key-q").performClick()
        assertEquals(listOf("type:1", "type:q"), actions.calls)
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

    /**
     * The panel lost its status row in the redesign — the transcript took that space — so
     * high accuracy now reads from the state of its own control rather than a badge.
     */
    @Test fun `high accuracy reports its state through the control`() {
        panel(
            EngineState(
                status = DictationStatus.READY,
                statusDetail = "Ready",
                boostActive = true,
                heavyModelAvailable = true,
            ),
        )
        assertTrue(
            compose.onAllNodesWithContentDescription("High accuracy on")
                .fetchSemanticsNodes().isNotEmpty(),
        )
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
    /** Typing must keep working even when dictation cannot. */
    @Test fun `the keyboard still types in the permission state`() {
        panel(
            EngineState(
                status = DictationStatus.NEEDS_PERMISSION,
                statusDetail = "Open Scribe to allow the microphone",
            ),
        )
        compose.onNodeWithTag("key-q").assertIsDisplayed()
        compose.onNodeWithTag("key-Switch keyboard").assertIsDisplayed()
    }

    // --------------------------------------------------------------- the keys

    @Test fun `the editing keys route to their actions`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("key-Backspace").performClick()
        compose.onNodeWithTag("key-Enter").performClick()
        compose.onNodeWithTag("key-Switch keyboard").performClick()
        assertEquals(listOf("backspace", "enter", "switch"), actions.calls)
    }

    /** Dictation is a toggle now: one tap on, one tap off, no holding. */
    @Test fun `the microphone is a toggle`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("mic-button").performClick()
        assertEquals(listOf("toggleDictation"), actions.calls)
    }

    @Test fun `the microphone key on the bottom row toggles too`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("key-Dictate").performClick()
        assertEquals(listOf("toggleDictation"), actions.calls)
    }

    @Test fun `boost is reachable once the high-accuracy model is installed`() {
        val actions = RecordingActions()
        panel(
            EngineState(
                status = DictationStatus.READY,
                statusDetail = "Ready",
                heavyModelAvailable = true,
            ),
            actions,
        )
        compose.onNodeWithTag("boost-button").performClick()
        assertEquals(listOf("boost:true"), actions.calls)
    }

    /**
     * On a fresh install the high-accuracy model is a 190 MB download that has not
     * happened. Arming it would confirm a capability the app can see it does not have, and
     * the user would find out only after speaking.
     */
    @Test fun `boost does nothing until its model is on the device`() {
        val actions = RecordingActions()
        panel(actions = actions)   // heavyModelAvailable defaults to false
        compose.onNodeWithTag("boost-button").performClick()
        assertTrue("boost must not arm without its model", actions.calls.isEmpty())
        compose.onAllNodesWithContentDescription(
            "High accuracy needs the Small model — get it in Scribe",
        ).fetchSemanticsNodes().let {
            assertTrue("the reason must be readable by a screen reader", it.isNotEmpty())
        }
    }

    // ------------------------------------------------------------ the reveal

    @Test fun `nothing is revealed while idle`() {
        panel()
        compose.onNodeWithTag("transcript-reveal").assertDoesNotExist()
    }

    @Test fun `the live transcript appears while listening`() {
        panel(
            EngineState(
                status = DictationStatus.RECORDING,
                statusDetail = "Listening…",
                stage = DictationStage.LISTENING,
                partialText = "hey um Sydney just wanted to check",
            ),
        )
        compose.onNodeWithTag("transcript-reveal").assertIsDisplayed()
        compose.onNodeWithText("hey um Sydney just wanted to check").assertIsDisplayed()
        compose.onNodeWithText("LISTENING").assertIsDisplayed()
    }

    @Test fun `the cleanup names each stage as it happens`() {
        val diff = TextDiff.diff("hey um Sydney", "Hey Sydney")
        panel(
            EngineState(
                status = DictationStatus.TRANSCRIBING,
                statusDetail = "Cleaning up",
                stage = DictationStage.CLEANING,
                diff = diff,
                finalText = "Hey Sydney",
            ),
        )
        compose.onNodeWithText("CLEANING IT UP").assertIsDisplayed()
    }

    @Test fun `the finished line is shown before it is inserted`() {
        panel(
            EngineState(
                status = DictationStatus.READY,
                statusDetail = "Ready",
                stage = DictationStage.FINAL,
                finalText = "Hey Sydney, just wanted to check.",
            ),
        )
        compose.onNodeWithText("Hey Sydney, just wanted to check.").assertIsDisplayed()
        compose.onNodeWithText("READY TO INSERT").assertIsDisplayed()
    }

    /** An utterance in progress can be thrown away rather than only waited out. */
    @Test fun `the reveal can be discarded`() {
        val actions = RecordingActions()
        panel(
            EngineState(
                status = DictationStatus.TRANSCRIBING,
                statusDetail = "Cleaning up",
                stage = DictationStage.CLEANING,
                finalText = "something",
            ),
            actions,
        )
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals(listOf("cancel"), actions.calls)
    }

    // ------------------------------------------------------------ accessibility

    /**
     * Every control carries a description. A voice keyboard that a screen-reader user
     * cannot operate has failed at its own premise.
     */
    @Test fun `controls are labelled for a screen reader`() {
        panel()
        listOf(
            "Start dictating",
            "Dictation mode",
            "Backspace",
            "Space",
            "Enter",
            "Shift",
            "Switch keyboard",
        ).forEach { label ->
            val nodes = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            assertTrue("no node described as \"$label\"", nodes.isNotEmpty())
        }
    }
}
