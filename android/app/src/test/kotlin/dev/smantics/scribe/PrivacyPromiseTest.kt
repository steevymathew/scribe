package dev.smantics.scribe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy claims, as tests.
 *
 * The desktop build pins "no Qt in the engine" with a test rather than a convention, on the
 * grounds that a promise nobody checks is a promise that quietly stops being true. The
 * claims Scribe makes on a phone are much stronger, so they get the same treatment: an
 * analytics SDK added by a well-meaning future change should fail the build, not ship.
 *
 * These read the build script and the manifests rather than mocking anything, because what
 * matters is what ends up in the APK.
 */
class PrivacyPromiseTest {

    private val projectDir = File(".").absoluteFile
    private val buildScript = File(projectDir, "build.gradle.kts")

    /**
     * SDKs that phone home. None of these has any business in a dictation app that says
     * your voice never leaves the device.
     */
    private val forbiddenDependencies = listOf(
        "firebase", "crashlytics", "com.google.android.gms", "play-services",
        "analytics", "appcenter", "sentry", "bugsnag", "mixpanel", "amplitude",
        "segment", "adjust", "appsflyer", "facebook", "onesignal", "braze",
    )

    @Test
    fun `no analytics or crash-reporting SDK is a dependency`() {
        assertTrue("cannot find ${buildScript.absolutePath}", buildScript.isFile)
        val offenders = buildScript.readLines().withIndex().filter { (_, line) ->
            val code = line.substringBefore("//").lowercase()
            code.contains("implementation") && forbiddenDependencies.any { code.contains(it) }
        }.map { (i, line) -> "build.gradle.kts:${i + 1}  ${line.trim()}" }

        assertTrue(
            "Scribe claims your audio never leaves the phone. These would make that " +
                "false:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * There is exactly one place in Scribe that can open a network connection.
     *
     * That is what makes the ledger in Settings complete rather than best-effort: a
     * request that could happen somewhere else would be a request the user is never shown.
     * If a second call site ever appears, either it records to the ledger too or this
     * claim stops being true — and this test forces that decision to be made deliberately.
     */
    @Test
    fun `only the model downloader can reach the network`() {
        val sourceRoots = listOf(
            File(projectDir, "src/main/kotlin"),
            File(projectDir, "../core/src/main/kotlin"),
        )
        val networkApis = Regex(
            """\b(URL\(|HttpURLConnection|OkHttpClient|Socket\(|DatagramSocket|""" +
                """InetAddress|WebView|Retrofit)""",
        )
        val callSites = sourceRoots
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { file -> networkApis.containsMatchIn(codeOnly(file.readText())) }
            .map { it.name }
            .toSet()

        assertEquals(
            "network access must stay confined to the model downloader, which records " +
                "every request in the ledger the user can read",
            setOf("ModelStore.kt"),
            callSites,
        )
    }

    /**
     * The source with its comments and imports taken out.
     *
     * Prose has to be able to name these APIs — explaining *why* a piece of code does not
     * use a WebView is exactly the kind of comment worth having — and a mention in a
     * doc comment is not a call site. Stripping comments before the scan makes the check
     * strictly more precise rather than less: nothing that can open a connection lives
     * inside `/* */`.
     */
    private fun codeOnly(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("import ") }
        .joinToString("\n")

    /**
     * The airgap flavour's whole argument is that it *cannot* reach a network, which is a
     * property of its manifest rather than a policy. If `INTERNET` ever appears in the
     * shared manifest it would be inherited by both flavours and the argument would
     * silently collapse.
     */
    @Test
    fun `the shared manifest does not request internet access`() {
        val manifest = File(projectDir, "src/main/AndroidManifest.xml")
        assertTrue("cannot find ${manifest.absolutePath}", manifest.isFile)
        val text = manifest.readText()
        assertTrue(
            "INTERNET belongs only in the standard flavour's manifest — in the shared one " +
                "it would be inherited by the airgap build too.",
            !text.contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun `the airgap flavour adds no permissions at all`() {
        val manifest = File(projectDir, "src/airgap/AndroidManifest.xml")
        assertTrue("cannot find ${manifest.absolutePath}", manifest.isFile)
        assertEquals(
            "the airgap flavour must add nothing",
            emptySet<String>(),
            requestedPermissions(manifest),
        )
    }

    @Test
    fun `only the standard flavour asks for the network, and only for downloads`() {
        val manifest = File(projectDir, "src/standard/AndroidManifest.xml")
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
            requestedPermissions(manifest),
        )
    }

    /** Permissions the app *requests*, ignoring ones it merely requires callers to hold. */
    private fun requestedPermissions(manifest: File): Set<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()

    /**
     * A dictation app has no reason to read contacts, location, storage or the camera, and
     * asking for any of them would undercut everything else it says about itself.
     *
     * SYSTEM_ALERT_WINDOW is deliberately absent: the floating bubble draws with
     * `TYPE_ACCESSIBILITY_OVERLAY` from the accessibility service that has to exist anyway
     * to know a text field is focused, so turning the bubble on costs one permission
     * rather than two.
     */
    @Test
    fun `no permission beyond what dictation needs`() {
        val allowed = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
        )
        // BIND_INPUT_METHOD appears in the manifest but is not requested: it is the
        // permission the *system* must hold to bind Scribe's keyboard service, which is
        // what stops any other app from impersonating the system and binding it.
        val requested = requestedPermissions(File(projectDir, "src/main/AndroidManifest.xml"))
        val extra = requested - allowed
        assertTrue("unexpected permissions requested: $extra", extra.isEmpty())
    }
}
