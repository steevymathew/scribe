package dev.smantics.scribe.ime

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text-injection contract.
 *
 * [InputConnectionSink.replace] is the risky one: it edits text that is already sitting in
 * someone's message. Every test here is about the cases where it must refuse.
 *
 * The fake is a dynamic proxy rather than a hand-written stub because `InputConnection` has
 * about thirty methods and only four of them matter to this class; stubbing the rest by
 * hand would bury the four that do.
 */
class InputConnectionSinkTest {

    /** A tiny editable buffer with a cursor at the end, driven through InputConnection. */
    private class FakeEditor {
        val text = StringBuilder()
        var batchDepth = 0
        var rejectDelete = false

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "beginBatchEdit" -> { batchDepth++; true }
                "endBatchEdit" -> { batchDepth--; true }
                "commitText" -> {
                    text.append(args[0] as CharSequence)
                    true
                }
                "getTextBeforeCursor" -> {
                    val n = args[0] as Int
                    text.substring(maxOf(0, text.length - n))
                }
                "deleteSurroundingText" -> {
                    if (rejectDelete) {
                        false
                    } else {
                        val before = args[0] as Int
                        text.delete(maxOf(0, text.length - before), text.length)
                        true
                    }
                }
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as InputConnection
    }

    private fun sink(editor: FakeEditor?) = InputConnectionSink { editor?.connection }

    // ------------------------------------------------------------------ commit

    @Test fun `commit inserts at the cursor`() {
        val editor = FakeEditor()
        sink(editor).commit("hello world")
        assertEquals("hello world", editor.text.toString())
    }

    @Test fun `commit is wrapped in a batch edit`() {
        val editor = FakeEditor()
        sink(editor).commit("hello")
        // Balanced: an unbalanced batch leaves the target app's editor in a broken state.
        assertEquals(0, editor.batchDepth)
    }

    @Test fun `commit without a field throws so the engine can report it`() {
        val thrown = runCatching { sink(null).commit("hello") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    // ----------------------------------------------------------------- replace

    @Test fun `replace swaps the text it previously inserted`() {
        val editor = FakeEditor()
        val s = sink(editor)
        s.commit("um, hello there")
        assertTrue(s.replace("um, hello there", "Hello there"))
        assertEquals("Hello there", editor.text.toString())
    }

    @Test fun `replace works when the insertion is not the whole field`() {
        val editor = FakeEditor()
        val s = sink(editor)
        editor.text.append("Note: ")
        s.commit("um, hello")
        assertTrue(s.replace("um, hello", "Hello"))
        assertEquals("Note: Hello", editor.text.toString())
    }

    /**
     * The important refusal. If the user has typed since Scribe inserted, the text before
     * the cursor is no longer what Scribe wrote, and deleting that many characters would
     * eat the user's own words.
     */
    @Test fun `replace refuses when the user has typed since`() {
        val editor = FakeEditor()
        val s = sink(editor)
        s.commit("um, hello there")
        editor.text.append(" and goodbye")

        assertFalse(s.replace("um, hello there", "Hello there"))
        assertEquals("um, hello there and goodbye", editor.text.toString())
    }

    @Test fun `replace refuses when the field has been cleared`() {
        val editor = FakeEditor()
        val s = sink(editor)
        s.commit("um, hello there")
        editor.text.clear()

        assertFalse(s.replace("um, hello there", "Hello there"))
        assertEquals("", editor.text.toString())
    }

    @Test fun `replace refuses when there is no field at all`() {
        assertFalse(sink(null).replace("a", "b"))
    }

    @Test fun `replace of nothing is refused rather than deleting the whole field`() {
        val editor = FakeEditor()
        editor.text.append("existing text")
        assertFalse(sink(editor).replace("", "something"))
        assertEquals("existing text", editor.text.toString())
    }

    @Test fun `replace leaves the batch balanced even when the delete fails`() {
        val editor = FakeEditor()
        val s = sink(editor)
        s.commit("hello")
        editor.rejectDelete = true
        s.replace("hello", "goodbye")
        assertEquals(0, editor.batchDepth)
    }
}
