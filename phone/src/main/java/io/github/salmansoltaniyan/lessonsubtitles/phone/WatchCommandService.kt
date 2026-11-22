package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the watch's button commands. Google Play services starts this service when a command
 * arrives, also when the phone app is not running, so the watch can start playback by itself.
 */
class WatchCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.PATH_COMMAND) return
        val command = String(event.data, Charsets.UTF_8)
        val app = applicationContext
        Handler(Looper.getMainLooper()).post {
            val player = PlaybackService.instance
            if (player != null && player.hasMedia) {
                player.handleCommand(command)
            } else {
                startPlayback(app)
            }
        }
    }

    private fun startPlayback(context: android.content.Context) {
        val intent = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY_FROM_WATCH)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android may not allow starting playback from the background. Ask the user on the phone.
            Log.w(TAG, "Could not start playback from the watch", e)
            notifyTapToPlay(context, intent)
        }
    }

    private fun notifyTapToPlay(context: android.content.Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.watch_request_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        // Tapping a notification is a user action, so the service may start in the foreground then.
        val tap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_player)
            .setContentTitle(context.getString(R.string.watch_request_title))
            .setContentText(context.getString(R.string.watch_request_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are not allowed.
        }
    }

    private companion object {
        const val TAG = "WatchCommandService"
        const val CHANNEL_ID = "watch_requests"
        const val NOTIFICATION_ID = 2001
    }
}
