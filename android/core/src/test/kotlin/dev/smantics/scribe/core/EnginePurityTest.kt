package dev.smantics.scribe.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine must not depend on Android.
 *
 * This is the direct descendant of the desktop build's `test_engine_no_qt.py`, which pins
 * the same promise for Qt. The reasoning is identical and it is not stylistic:
 *
 *  - the desktop engine stays importable and runnable headless, so the CLI and the systemd
 *    service work without a display;
 *  - the Android engine stays runnable on a plain JVM, which on **this** build host is the
 *    only place it can be tested at all — there is no Android emulator for aarch64 Linux.
 *
 * A single stray `import android.util.Log` would move the Clean pipeline, the polish
 * guardrails and the whole dictation state machine behind Robolectric, and quietly halve
 * what can be verified before shipping. So it is a test, not a convention.
 */
class EnginePurityTest {

    private val forbidden = listOf(
        "import android.",
        "import androidx.",
        "import kotlinx.coroutines",   // core stays free of a concurrency framework too
        "import com.google.android",
    )

    @Test
    fun `core has no Android or framework imports`() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue("cannot find core sources at ${sourceRoot.absolutePath}", sourceRoot.isDirectory)

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val trimmed = line.trimStart()
                    val hit = forbidden.firstOrNull { trimmed.startsWith(it) }
                    if (hit != null) "${file.path}:${index + 1}  $trimmed" else null
                }
            }
            .toList()

        assertTrue(
            "core must stay platform-free, but found:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * A second, blunter check: the engine's own classes must load and run under a plain
     * JVM. The import scan above catches the source; this catches a dependency that drags
     * Android in transitively.
     */
    @Test
    fun `the pipeline runs with nothing but the JDK on the classpath`() {
        val out = dev.smantics.scribe.core.clean.CleanPipeline.run("um, hello there")
        assertTrue("expected a cleaned string, got '$out'", out == "Hello there")
    }
}
