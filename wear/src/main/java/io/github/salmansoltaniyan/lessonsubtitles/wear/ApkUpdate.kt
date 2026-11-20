package io.github.salmansoltaniyan.lessonsubtitles.wear

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Receives a new watch APK from the phone and installs it.
 * Android shows a confirmation on the watch; InstallReceiver handles the result.
 */
object ApkUpdate {

    private fun pendingFile(context: Context) = File(context.filesDir, "update.apk")

    /** Reads the APK from the channel the phone opened. */
    suspend fun receive(context: Context, channel: ChannelClient.Channel): File {
        val channelClient = Wearable.getChannelClient(context.applicationContext)
        val file = pendingFile(context)
        try {
            val stream = channelClient.getInputStream(channel).await()
            withContext(Dispatchers.IO) {
                DataInputStream(BufferedInputStream(stream)).use { input ->
                    val size = input.readLong()
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var left = size
                        while (left > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("Transfer stopped early")
                            output.write(buffer, 0, n)
                            left -= n
                        }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            channelClient.close(channel)
        }
        return file
    }

    /** Installs a received APK, if there is one. Safe to call again (e.g. in onResume). */
    suspend fun installPending(activity: Activity) {
        val file = pendingFile(activity)
        if (!file.exists()) return

        // The user must allow this app to install apps once.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            PhoneLink.updateStatus(activity, "allow “Install unknown apps” for Lesson Subtitles on the watch.")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (_: ActivityNotFoundException) {
                PhoneLink.updateStatus(activity, "this watch does not let apps install updates.")
                file.delete()
            }
            return // onResume calls us again when the user comes back
        }

        PhoneLink.updateStatus(activity, "installing…")
        val installer = activity.packageManager.packageInstaller
        val sessionId = withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            id
        }
        file.delete()

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val statusIntent = PendingIntent.getBroadcast(
            activity, sessionId, Intent(activity, InstallReceiver::class.java), flags,
        )
        installer.openSession(sessionId).use { it.commit(statusIntent.intentSender) }
    }
}
