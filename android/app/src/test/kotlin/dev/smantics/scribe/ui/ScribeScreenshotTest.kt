package dev.smantics.scribe.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ime.PanelActions
import dev.smantics.scribe.ime.ScribePanel
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.ScribeTheme
import dev.smantics.scribe.ui.theme.ScribeTokens
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every state of the keyboard to a PNG so it can actually be looked at.
 *
 * Run with `./gradlew :app:screenshotTest`; output lands in `app/build/screenshots/`.
 *
 * **What these images are.** Real Compose output, laid out at the Galaxy Z Fold 7's
 * approximate reported metrics for its cover and inner displays, rendered through
 * Robolectric's native graphics on an emulated x86-64 JVM (this build host is aarch64, and
 * that runtime ships x86-64 only).
 *
 * **What they are not.** They are not a photograph of the phone. The dp figures below are
 * computed from the Fold 7's published pixel dimensions and a assumed density, not read off
 * a device; system fonts, One UI's own insets and the real keyboard height are absent. They
 * are good enough to review hierarchy, contrast, spacing, truncation and reach — and not
 * good enough to sign off appearance, which stays OWNER-VERIFY.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33])
class ScribeScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val outputDir: File by lazy {
        File(System.getProperty("scribe.screenshots") ?: "build/screenshots").apply { mkdirs() }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outputDir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun panel(
        state: EngineState,
        handedness: Handedness = Handedness.RIGHT,
    ): @Composable () -> Unit = {
        ScribeTheme(handedness = handedness) {
            Box(Modifier.fillMaxWidth().background(ScribeTokens.bg).padding(vertical = 8.dp)) {
                ScribePanel(
                    state = state,
                    actions = NoActions,
                    keysShown = true,
                    onToggleKeys = {},
                )
            }
        }
    }

    private object NoActions : PanelActions {
        override fun toggleDictation() = Unit
        override fun cancelDictation() = Unit
        override fun toggleMode() = Unit
        override fun setBoost(held: Boolean) = Unit
        override fun type(text: String) = Unit
        override fun acceptSuggestion(word: String) = Unit
        override fun backspace() = Unit
        override fun deleteWord() = Unit
        override fun space() = Unit
        override fun enter() = Unit
        override fun switchKeyboard() = Unit
        override fun openApp() = Unit
    }

    // ------------------------------------------------------------- cover screen
    // 1080 × 2520 px at ~2.625× → about 411 × 960 dp.

    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard ready on the cover screen`() {
        shoot(
            "cover-01-ready",
            panel(EngineState(status = DictationStatus.READY, statusDetail = "Ready", modelName = "Base (English)")),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard listening on the cover screen`() {
        shoot(
            "cover-02-listening",
            panel(
                EngineState(
                    status = DictationStatus.RECORDING,
                    statusDetail = "Listening…",
                    level = 0.62f,
                    modelName = "Base (English)",
                ),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard transcribing on the cover screen`() {
        shoot(
            "cover-03-transcribing",
            panel(EngineState(status = DictationStatus.TRANSCRIBING, statusDetail = "Transcribing…")),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard in raw mode with high accuracy armed`() {
        shoot(
            "cover-04-raw-boost",
            panel(
                EngineState(
                    status = DictationStatus.RECORDING,
                    statusDetail = "Listening…",
                    level = 0.4f,
                    mode = Mode.RAW,
                    boostActive = true,
                ),
            ),
        )
    }

    /** The state that decides whether someone abandons setup. */
    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard without microphone permission`() {
        shoot(
            "cover-05-needs-permission",
            panel(
                EngineState(
                    status = DictationStatus.NEEDS_PERMISSION,
                    statusDetail = "Open Scribe to allow the microphone",
                ),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard showing an error`() {
        shoot(
            "cover-06-error",
            panel(
                EngineState(
                    status = DictationStatus.ERROR,
                    statusDetail = "Microphone busy or disconnected — check Settings → Audio",
                    error = "Microphone busy or disconnected — check Settings → Audio",
                ),
            ),
        )
    }

    // ------------------------------------------------------------ inner screen
    // 2184 × 1968 px at ~2.625× → about 832 × 750 dp. Much wider than tall, which is the
    // case a keyboard laid out for a phone is most likely to get wrong.

    @Test
    @Config(qualifiers = "w832dp-h750dp-xxhdpi")
    fun `keyboard listening on the unfolded screen`() {
        shoot(
            "inner-01-listening",
            panel(
                EngineState(
                    status = DictationStatus.RECORDING,
                    statusDetail = "Listening…",
                    level = 0.55f,
                    modelName = "Small (English)",
                ),
            ),
        )
    }

    /** Left-handed layout moves the mode toggle to the reachable side of an 8-inch screen. */
    @Test
    @Config(qualifiers = "w832dp-h750dp-xxhdpi")
    fun `keyboard unfolded for a left-handed grip`() {
        shoot(
            "inner-02-left-handed",
            panel(
                EngineState(status = DictationStatus.READY, statusDetail = "Ready"),
                handedness = Handedness.LEFT,
            ),
        )
    }

    @Test
    @Config(qualifiers = "w832dp-h750dp-xxhdpi")
    fun `keyboard unfolded without microphone permission`() {
        shoot(
            "inner-03-needs-permission",
            panel(
                EngineState(
                    status = DictationStatus.NEEDS_PERMISSION,
                    statusDetail = "Open Scribe to allow the microphone",
                ),
            ),
        )
    }

    // -------------------------------------------------------- long-content case

    /**
     * A status line far longer than the design assumes. Real error strings are long, and a
     * panel that only looks right with "Ready" in it has been tested with demo data.
     */
    @Test
    @Config(qualifiers = "w411dp-h960dp-xxhdpi")
    fun `keyboard with an unusually long status line`() {
        shoot(
            "cover-07-long-status",
            panel(
                EngineState(
                    status = DictationStatus.ERROR,
                    statusDetail = "The high-accuracy model could not be loaded because " +
                        "there is not enough free memory on this phone right now",
                    error = "long",
                ),
            ),
        )
    }
}
