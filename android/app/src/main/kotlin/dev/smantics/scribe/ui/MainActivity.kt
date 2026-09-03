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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.ui.theme.Handedness
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
            // Null until the first read off disk lands. Starting from ScribeConfig()
            // would mean starting from onboardingComplete = false, so every cold start of
            // an already-configured app flashed the setup wizard before the home screen:
            // "still loading" and "never set up" are different states and must not render
            // the same.
            val config by engine.settings.config.collectAsState(initial = null)
            ScribeTheme(handedness = config?.handedness ?: Handedness.RIGHT) {
                config?.let {
                    ScribeApp(engine = engine, config = it, requestMicImmediately = requestMic)
                }
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

    // Bumped after every permission answer so the system state is re-read rather than
    // remembered from composition.
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val system = rememberSystemSetupState(permissionEpoch)

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionEpoch++ }

    val requestMic: () -> Unit = {
        if (system.micPermanentlyDenied) {
            // Android has stopped showing the dialog. Launching the request again would
            // do nothing at all and say nothing about why, which is how a setup screen
            // becomes a room with no doors.
            context.openAppPermissionSettings()
        } else {
            context.recordMicRequested()
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* A denied notification permission only hides the listening notice. */ }

    LaunchedEffect(requestMicImmediately) {
        if (requestMicImmediately && !system.micGranted) requestMic()
    }

    // Navigation is a single enum in state rather than a navigation library: there are
    // four destinations, no deep links and no back stack worth the name, and a dependency
    // that solves none of those is a dependency that only adds build weight.
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    if (!config.onboardingComplete) {
        OnboardingScreen(
            engine = engine,
            system = system,
            onRequestMic = requestMic,
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
                    system = system,
                    onRequestMic = requestMic,
                    onOpenKeyboardSettings = { context.openKeyboardSettings() },
                    onOpenKeyboardPicker = { context.showKeyboardPicker() },
                    onOpenAccessibility = { context.openAccessibilitySettings() },
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
