package dev.smantics.scribe.bubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.smantics.scribe.R
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.ime.ImeComposeHost
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.theme.ScribeTheme
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * The optional floating button.
 *
 * This is Wispr Flow's shape, offered because some people want it, and shipped with the
 * honest caveat that it is the worse mechanism. Wispr's own Android users report the bubble
 * dying every five to ten minutes to Android's battery management, and that is not a bug in
 * their implementation — an overlay-plus-background-service is simply not something the OS
 * guarantees to keep alive, whereas an input method is. The keyboard remains the default
 * and the recommendation.
 *
 * It is off unless the user turns it on, and turning it on requires the overlay permission,
 * which is asked for in Settings rather than sprung on anyone.
 */
class BubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: ComposeView? = null
    private val host = ImeComposeHost()
    private lateinit var engine: ScribeEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        host.onCreate()
        engine = ScribeEngine.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        showBubble()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bubble_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.bubble_channel_body) },
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.bubble_title))
            .setContentText(getString(R.string.bubble_body))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the bubble must never steal focus from the field the user is
            // typing into, or the text has nowhere to go.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = 24
            y = 240
        }

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ScribeTheme {
                    Box(
                        Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(ScribeTokens.s1)
                            .border(1.dp, ScribeTokens.stroke2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(GlyphName.MIC, ScribeTokens.accent, size = 26.dp)
                    }
                }
            }
        }
        host.attachTo(view)
        host.onResume()
        runCatching { wm.addView(view, params) }.onSuccess { bubble = view }
    }

    override fun onDestroy() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        host.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "scribe-bubble"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.smantics.scribe.STOP_BUBBLE"

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!canDrawOverlays(context)) return
            runCatching {
                context.startForegroundService(Intent(context, BubbleService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, BubbleService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
