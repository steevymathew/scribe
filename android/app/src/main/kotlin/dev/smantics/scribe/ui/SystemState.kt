package dev.smantics.scribe.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The three pieces of system state Scribe's setup depends on, read from the system every
 * time the user comes back to the app.
 *
 * Reading them once during composition was wrong in a way that mattered: the user taps
 * "Open settings", switches the keyboard on, returns — and the checklist still says it is
 * off, because nothing recomposed and nothing re-read. Being told to do something,
 * doing it, and being told again is worse than not being asked.
 *
 * `LifecycleResumeEffect` is the hook, because every one of these can only change while
 * the user is somewhere else.
 */
data class SystemSetupState(
    val micGranted: Boolean,
    val micPermanentlyDenied: Boolean,
    val keyboardEnabled: Boolean,
    val keyboardSelected: Boolean,
) {
    val complete: Boolean get() = micGranted && keyboardEnabled
}

@Composable
fun rememberSystemSetupState(refreshToken: Any = Unit): SystemSetupState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(context.readSetupState()) }
    LifecycleResumeEffect(refreshToken) {
        state = context.readSetupState()
        onPauseOrDispose { }
    }
    return state
}

private fun Context.readSetupState(): SystemSetupState {
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    return SystemSetupState(
        micGranted = granted,
        micPermanentlyDenied = !granted && micPermanentlyDenied(),
        keyboardEnabled = isScribeEnabled(),
        keyboardSelected = isScribeSelected(),
    )
}

/**
 * Whether Android will still show the microphone dialog, or has stopped asking.
 *
 * After a second refusal the system silently returns "denied" without showing anything, so
 * a button that launches the request becomes inert — it does nothing, says nothing, and
 * leaves the user pressing it. That is a dead end, and this is how it is detected: once
 * the permission has been requested and the rationale flag is false, the only route left
 * is the app's own settings page.
 *
 * The flag is only meaningful after at least one request, which is why the first launch
 * records that it asked.
 */
private fun Context.micPermanentlyDenied(): Boolean {
    val activity = findActivity() ?: return false
    if (!hasAskedForMic()) return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.RECORD_AUDIO,
    )
}

private const val ASKED_PREFS = "scribe-permissions"
private const val ASKED_MIC = "asked_mic"

fun Context.recordMicRequested() {
    getSharedPreferences(ASKED_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(ASKED_MIC, true).apply()
}

private fun Context.hasAskedForMic(): Boolean =
    getSharedPreferences(ASKED_PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_MIC, false)

fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Scribe's own page in system settings — the only route left once Android stops asking. */
fun Context.openAppPermissionSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Whether Scribe is in the system's list of enabled input methods. */
fun Context.isScribeEnabled(): Boolean = runCatching {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.enabledInputMethodList.any { it.packageName == packageName }
}.getOrDefault(false)

/** Whether Scribe is the keyboard currently in use. */
fun Context.isScribeSelected(): Boolean = runCatching {
    Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith(packageName) == true
}.getOrDefault(false)

fun Context.openKeyboardSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

fun Context.showKeyboardPicker() {
    runCatching {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
}
