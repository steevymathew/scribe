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
     */
    @Test
    fun `no permission beyond what dictation needs`() {
        val allowed = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.SYSTEM_ALERT_WINDOW",
        )
        // BIND_INPUT_METHOD appears in the manifest but is not requested: it is the
        // permission the *system* must hold to bind Scribe's keyboard service, which is
        // what stops any other app from impersonating the system and binding it.
        val requested = requestedPermissions(File(projectDir, "src/main/AndroidManifest.xml"))
        val extra = requested - allowed
        assertTrue("unexpected permissions requested: $extra", extra.isEmpty())
    }
}
