package dev.smantics.scribe.ime

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
// A screen big enough to hold the whole panel. Robolectric's default is 320x470 dp, which
// is narrower and shorter than any phone this runs on and shorter than the keyboard itself.
@Config(sdk = [33], qualifiers = "+w420dp-h1000dp")
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
        override fun acceptSuggestion(word: String) { calls += "accept:$word" }
        override fun backspace() { calls += "backspace" }
        override fun deleteWord() { calls += "deleteWord" }
        override fun space() { calls += "space" }
        override fun enter() { calls += "enter" }
        override fun switchKeyboard() { calls += "switch" }
        override fun openApp() { calls += "openApp" }
    }

    /**
     * Opens the letters.
     *
     * There is no "show the letters" key any more — the grabber left behind when they are
     * swiped away answers a tap as well as a swipe, because a hint that only responds to
     * the gesture you have not learned yet is not a hint.
     */
    private fun showKeys() {
        compose.onNodeWithTag("keys-grabber").performClick()
    }

    private fun panel(
        state: EngineState = EngineState(status = DictationStatus.READY, statusDetail = "Ready"),
        actions: PanelActions = RecordingActions(),
        keysShown: Boolean = false,
        keyPreview: Boolean = false,
        suggestions: List<String> = emptyList(),
    ) {
        compose.setContent {
            var keys by remember { mutableStateOf(keysShown) }
            ScribeTheme {
                // A width is pinned so the layout is measured rather than collapsing to
                // zero, which would make every "is displayed" assertion meaningless.
                ScribePanel(
                    state = state,
                    actions = actions,
                    keysShown = keys,
                    onToggleKeys = { keys = !keys },
                    modifier = Modifier.width(412.dp),
                    keyPreview = keyPreview,
                    suggestions = suggestions,
                )
            }
        }
    }

    // ------------------------------------------------------------- the controls

    /** Voice is the primary mode: the letters are away until asked for. */
    @Test fun `the panel opens as a voice panel, not a wall of keys`() {
        panel()
        compose.onNodeWithTag("mic-button").assertIsDisplayed()
        compose.onNodeWithTag("mode-toggle").assertIsDisplayed()
        compose.onNodeWithTag("key-q").assertDoesNotExist()
        compose.onNodeWithTag("keys-grabber").assertIsDisplayed()
    }

    @Test fun `the letters can be summoned and put away`() {
        panel()
        showKeys()
        compose.onNodeWithTag("key-q").assertIsDisplayed()
        // The edge stays in the layout underneath the card the whole time — it is the same
        // slab seen side-on, and it simply stops being covered when the card lies down. So
        // the assertion is that the keys arrived, not that the edge left.
        compose.onNodeWithTag("keys-card").assertIsDisplayed()
    }

    /** The number row is part of the letters page, not a trip through `?123`. */
    @Test fun `the digits are on the letters page`() {
        panel()
        showKeys()
        compose.onNodeWithTag("key-1").assertIsDisplayed()
        compose.onNodeWithTag("key-0").assertIsDisplayed()
    }

    /**
     * The grips are the only sign the drag band exists, and since the card can now *only*
     * be moved from that band, they are the only sign the gesture exists at all.
     */
    @Test fun `the edges say which way the card goes`() {
        panel()
        showKeys()
        compose.onNodeWithContentDescription("Drag for emoji").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drag for symbols").assertIsDisplayed()
    }

    /** The emoji page is reachable without knowing the gesture. */
    @Test fun `the emoji key opens the emoji page`() {
        panel()
        showKeys()
        compose.onNodeWithTag("key-Emoji").performClick()
        compose.onNodeWithTag("emoji-page").assertIsDisplayed()
        compose.onNodeWithTag("key-q").assertDoesNotExist()
    }

    /**
     * Nothing on the primary keyboard offers to leave it. Android's own picker does that,
     * and a key for it here is an invitation to tap away from the keyboard just chosen.
     */
    @Test fun `there is no switch-keyboard key`() {
        panel()
        showKeys()
        compose.onNodeWithTag("util-Switch to another keyboard").assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch keyboard").assertDoesNotExist()
    }

    /** Width and split moved to Settings; neither has a permanent key any more. */
    @Test fun `there are no layout keys on the keyboard`() {
        panel()
        showKeys()
        compose.onNodeWithTag("util-Full width").assertDoesNotExist()
        compose.onNodeWithTag("util-Split the keyboard").assertDoesNotExist()
    }

    /**
     * The correction the first release needed. Shipping voice-only meant fixing one
     * misheard word sent the user to another keyboard, so Scribe must be able to type.
     */
    @Test fun `letters can be typed without leaving Scribe`() {
        val actions = RecordingActions()
        panel(actions = actions)
        showKeys()
        compose.onNodeWithTag("key-q").performClick()
        compose.onNodeWithTag("key-a").performClick()
        compose.onNodeWithTag("key-Space").performClick()
        assertEquals(listOf("type:q", "type:a", "space"), actions.calls)
    }

    @Test fun `shift capitalises exactly one letter`() {
        val actions = RecordingActions()
        panel(actions = actions)
        showKeys()
        compose.onNodeWithTag("key-Shift").performClick()
        compose.onNodeWithTag("key-Q").performClick()
        compose.onNodeWithTag("key-a").performClick()   // shift released after one letter
        assertEquals(listOf("type:Q", "type:a"), actions.calls)
    }

    @Test fun `the symbols layer is reachable and comes back`() {
        val actions = RecordingActions()
        panel(actions = actions)
        showKeys()
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
     * The high-accuracy control is gone from the panel.
     *
     * It made transcripts worse in use and there was no way to tell from the keyboard
     * whether it had done anything at all. Which model runs is now a decision made once in
     * Settings, not a button that silently changes the answer.
     */
    @Test fun `there is no high accuracy button on the keyboard`() {
        panel()
        compose.onNodeWithTag("boost-button").assertDoesNotExist()
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
        compose.onNodeWithTag("open-scribe-permission").performClick()
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
        showKeys()
        compose.onNodeWithTag("key-q").assertIsDisplayed()
        compose.onNodeWithTag("key-Backspace").assertIsDisplayed()
    }

    // --------------------------------------------------------------- the keys

    /** The utility bar is always there, whether or not the letters are showing. */
    /** Backspace and enter live on the keys, where every keyboard puts them. */
    @Test fun `backspace and enter are on the key rows`() {
        val actions = RecordingActions()
        panel(actions = actions)
        showKeys()
        compose.onNodeWithTag("key-Backspace").performClick()
        compose.onNodeWithTag("key-Enter").performClick()
        assertEquals(listOf("backspace", "enter"), actions.calls)
    }

    /** The one way into the app that is always on screen, keys up or down. */
    @Test fun `the panel always offers a way into Scribe`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("open-scribe").performClick()
        assertEquals(listOf("openApp"), actions.calls)
    }

    /** Dictation is a toggle now: one tap on, one tap off, no holding. */
    @Test fun `the microphone is a toggle`() {
        val actions = RecordingActions()
        panel(actions = actions)
        compose.onNodeWithTag("mic-button").performClick()
        assertEquals(listOf("toggleDictation"), actions.calls)
    }

    /**
     * The control that starts the recording is the control that finishes it — the finger
     * is already there, and hunting for a separate "done" is what made this confusing.
     */
    @Test fun `the microphone becomes the insert button while listening`() {
        val actions = RecordingActions()
        panel(
            EngineState(
                status = DictationStatus.RECORDING,
                statusDetail = "Listening…",
                stage = DictationStage.LISTENING,
            ),
            actions,
        )
        assertTrue(
            compose.onAllNodesWithContentDescription("Insert what I said")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("mic-button").performClick()
        assertEquals(listOf("toggleDictation"), actions.calls)
    }

    /**
     * The letters stay exactly as the user left them. An earlier version hid them the
     * moment dictation started, so the panel jumped on every press of the microphone and
     * the choice never stuck.
     */
    @Test fun `the letters stay up while dictating if they were up`() {
        panel(
            EngineState(
                status = DictationStatus.RECORDING,
                statusDetail = "Listening…",
                stage = DictationStage.LISTENING,
            ),
            keysShown = true,
        )
        compose.onNodeWithTag("key-q").assertIsDisplayed()
        compose.onNodeWithTag("transcript-reveal").assertIsDisplayed()
    }

    /** Enter belongs at the end of the bottom row, where every keyboard puts it. */
    @Test fun `enter sits on the bottom row of the letters`() {
        val actions = RecordingActions()
        panel(actions = actions, keysShown = true)
        compose.onNodeWithTag("key-Enter").performClick()
        assertEquals(listOf("enter"), actions.calls)
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
        compose.onNodeWithText("LISTENING", substring = true).assertIsDisplayed()
    }

    /**
     * There is a limit on how long one utterance can be, and it used to be invisible: the
     * panel showed a live transcript of everything and then typed in only the first part.
     */
    @Test fun `listening shows how long you have been talking`() {
        panel(
            EngineState(
                status = DictationStatus.RECORDING,
                stage = DictationStage.LISTENING,
                partialText = "still going",
                recordedSeconds = 97f,
            ),
        )
        compose.onNodeWithText("LISTENING · 1:37").assertIsDisplayed()
    }

    /** The length of what you said is the number that predicts the wait. */
    @Test fun `transcribing says how much audio it is working through`() {
        panel(
            EngineState(
                status = DictationStatus.TRANSCRIBING,
                stage = DictationStage.TRANSCRIBING,
                partialText = "hey Sydney",
                decodingSeconds = 62f,
            ),
        )
        compose.onNodeWithText("TRANSCRIBING 1:02").assertIsDisplayed()
    }

    /** Stopping must be visibly different from listening, immediately. */
    @Test fun `stopping says transcribing rather than still listening`() {
        panel(
            EngineState(
                status = DictationStatus.TRANSCRIBING,
                statusDetail = "Transcribing",
                stage = DictationStage.TRANSCRIBING,
                partialText = "hey Sydney",
            ),
        )
        compose.onNodeWithText("TRANSCRIBING", substring = true).assertIsDisplayed()
        compose.onNodeWithText("LISTENING", substring = true).assertDoesNotExist()
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

    // -------------------------------------------------------------- suggestions

    /**
     * The strip and the status share one line because they never happen at once — nobody
     * types and dictates simultaneously — and a permanent suggestion row would cost height
     * on every screen for something that is empty most of the time.
     */
    @Test fun `suggestions take the line the status usually has`() {
        panel(suggestions = listOf("hello", "help", "held"))
        compose.onNodeWithTag("suggestions").assertIsDisplayed()
        compose.onNodeWithTag("status-detail").assertDoesNotExist()
    }

    @Test fun `the status comes back when there is nothing to suggest`() {
        panel()
        compose.onNodeWithTag("status-detail").assertIsDisplayed()
        compose.onNodeWithTag("suggestions").assertDoesNotExist()
    }

    @Test fun `picking a suggestion routes to the action`() {
        val actions = RecordingActions()
        panel(actions = actions, suggestions = listOf("hello"))
        compose.onNodeWithTag("suggestion-hello").performClick()
        assertEquals(listOf("accept:hello"), actions.calls)
    }

    /** While dictating the line belongs to the transcript, whatever was being suggested. */
    @Test fun `suggestions give way to the transcript`() {
        panel(
            state = EngineState(
                status = DictationStatus.RECORDING,
                stage = DictationStage.LISTENING,
                partialText = "talking now",
            ),
            suggestions = listOf("hello"),
        )
        compose.onNodeWithTag("suggestions").assertDoesNotExist()
        compose.onNodeWithTag("transcript-reveal").assertIsDisplayed()
    }

    // ------------------------------------------------------------- key preview

    @Test fun `there is no key preview unless it is switched on`() {
        panel(keysShown = true, keyPreview = false)
        compose.onNodeWithTag("key-preview").assertDoesNotExist()
    }

    // ------------------------------------------------------------ accessibility

    /**
     * Every control carries a description. A voice keyboard that a screen-reader user
     * cannot operate has failed at its own premise.
     */
    @Test fun `controls are labelled for a screen reader`() {
        panel(keysShown = true)
        listOf(
            "Start dictating",
            "Dictation mode",
            "Backspace",
            "Space",
            "Enter",
            "Emoji",
            "Symbols",
            "Shift",
            "Open Scribe",
            // The card can only be dragged from its edge band, so the grips that mark it
            // are the only sign the gesture exists and have to be readable too.
            "Drag for emoji",
            "Drag for symbols",
        ).forEach { label ->
            val nodes = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            assertTrue("no node described as \"$label\"", nodes.isNotEmpty())
        }
    }
}
