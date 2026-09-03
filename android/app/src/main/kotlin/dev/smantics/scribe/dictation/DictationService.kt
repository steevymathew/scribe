package dev.smantics.scribe.dictation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.smantics.scribe.R

/**
 * A foreground service that runs for exactly as long as Scribe is recording.
 *
 * Android 14 and later will not let an app start microphone capture from the background,
 * and an input method's claim on the foreground is not something to bet a core feature on.
 * Declaring `foregroundServiceType="microphone"` for the duration of the recording removes
 * the ambiguity, and it has a second, better justification: while Scribe is listening,
 * there is a notification saying so. A dictation tool that can hold the microphone open
 * without visibly saying it is doing so has the wrong shape for a privacy-first app.
 *
 * The service holds no state. It starts when recording starts and stops when it ends; the
 * engine and the audio buffer live in [ScribeEngine], which outlives it.
 */
class DictationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startInForeground()
        }
        // Deliberately not sticky: if the process dies mid-utterance there is nothing to
        // resume — the audio went with it — and silently reopening the microphone after a
        // crash is the last thing this app should do.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.listening_title))
            .setContentText(getString(R.string.listening_body))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.listening_channel),
                // MIN rather than LOW: the notice belongs in the shade, where it can be
                // checked, and not as a permanent icon in the status bar. Scribe is
                // holding the microphone, which is worth stating — it is not worth a
                // badge sitting next to the clock all day.
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.listening_channel_body)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "scribe-dictation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.smantics.scribe.STOP_DICTATION"

        /**
         * Starting and stopping are best-effort. If the system refuses — an unusual
         * background state, a device with an aggressive OEM policy — dictation still
         * works while the keyboard is on screen, so a failure here must not become an
         * error the user sees.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, DictationService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DictationService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
