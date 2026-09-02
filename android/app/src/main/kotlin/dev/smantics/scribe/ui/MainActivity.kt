package dev.smantics.scribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.ui.theme.ScribeTheme
import kotlinx.coroutines.launch

/**
 * The app around the keyboard.
 *
 * It exists for the things a keyboard cannot do for itself: grant the microphone
 * permission (an input method has no Activity to host the dialog), manage models, edit the
 * dictionary, and read the history and network ledger. Dictation itself happens in the
 * keyboard; this is the settings and setup surface.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = ScribeEngine.get(this)
        engine.warmUp()

        val requestMic = intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true

        setContent {
            val config by engine.settings.config.collectAsState(initial = ScribeConfig())
            ScribeTheme(handedness = config.handedness) {
                ScribeApp(engine = engine, config = config, requestMicImmediately = requestMic)
            }
        }
    }

    companion object {
        /**
         * Set when the keyboard sent the user here because it has no microphone access.
         * The permission dialog then appears without the user having to find it, because
         * the whole reason they are looking at this screen is that something is missing.
         */
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}

@Composable
private fun ScribeApp(
    engine: ScribeEngine,
    config: ScribeConfig,
    requestMicImmediately: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var micGranted by remember { mutableStateOf(engine.hasMicPermission()) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* A denied notification permission only hides the listening notice. */ }

    LaunchedEffect(requestMicImmediately) {
        if (requestMicImmediately && !micGranted) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Navigation is a single enum in state rather than a navigation library: there are
    // four destinations, no deep links and no back stack worth the name, and a dependency
    // that solves none of those is a dependency that only adds build weight.
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    if (!config.onboardingComplete) {
        OnboardingScreen(
            engine = engine,
            micGranted = micGranted,
            onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onRequestNotifications = {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onOpenKeyboardSettings = { context.openKeyboardSettings() },
            onFinish = {
                scope.launch { engine.settings.update { it.copy(onboardingComplete = true) } }
            },
        )
    } else {
        when (screen) {
            Screen.HOME -> {
                val state by engine.state.collectAsState()
                HomeScreen(
                    engine = engine,
                    config = config,
                    state = state,
                    micGranted = micGranted,
                    onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenKeyboardSettings = { context.openKeyboardSettings() },
                    onOpenKeyboardPicker = { context.showKeyboardPicker() },
                    onOpenModels = { screen = Screen.MODELS },
                    onOpenVocabulary = { screen = Screen.VOCABULARY },
                    onOpenHistory = { screen = Screen.HISTORY },
                )
            }
            Screen.MODELS -> ModelsScreen(engine, config) { screen = Screen.HOME }
            Screen.VOCABULARY -> VocabularyScreen(engine, config) { screen = Screen.HOME }
            Screen.HISTORY -> HistoryScreen(engine, config) { screen = Screen.HOME }
        }
    }
}

private enum class Screen { HOME, MODELS, VOCABULARY, HISTORY }

/** Whether Scribe is in the system's list of enabled input methods. */
fun Context.isScribeEnabled(): Boolean {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == packageName }
}

/** Whether Scribe is the keyboard currently in use. */
fun Context.isScribeSelected(): Boolean {
    val current = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    )
    return current?.startsWith(packageName) == true
}

private fun Context.openKeyboardSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun Context.showKeyboardPicker() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    runCatching { imm.showInputMethodPicker() }
}
