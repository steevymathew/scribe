package dev.smantics.scribe.ime

import dev.smantics.scribe.bubble.ScribeAccessibilityService
import dev.smantics.scribe.voice.ScribeRecognitionService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The services must be constructible.
 *
 * This exists because of a crash that shipped: a property initialiser called `assets`, which
 * is a `Context` method, and property initialisers run in the **constructor** — before
 * `attachBaseContext` has given the service its context. `ContextWrapper` dereferences a
 * null base and throws, so the process died the moment the system created the keyboard,
 * which is the moment a text field takes focus. The app and the keyboard live in one
 * process, so the app went down with it.
 *
 * Nothing here asserts behaviour. It asserts that `new` works, which is the one thing every
 * other test in this file tree takes for granted and which no amount of Compose testing
 * covers, because those tests build the panel rather than the service that hosts it.
 *
 * **The rule it protects: a Service or Activity subclass may not touch its own `Context` in
 * a property initialiser.** Anything needing one is `lateinit` and assigned in `onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceConstructionTest {

    @Test fun `the keyboard can be constructed`() {
        ScribeInputMethodService()
    }

    @Test fun `the bubble can be constructed`() {
        ScribeAccessibilityService()
    }

    @Test fun `the voice input service can be constructed`() {
        ScribeRecognitionService()
    }
}
