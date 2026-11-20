package io.github.salmansoltaniyan.lessonsubtitles.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Gets the result of the PackageInstaller session started by ApkUpdate. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Show Android's "Update this app?" screen on the watch.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    PhoneLink.updateStatus(context, "confirm the update on the watch.")
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            // On success Android restarts this app, so this message may not arrive.
            PackageInstaller.STATUS_SUCCESS -> PhoneLink.updateStatus(context, "update installed.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> PhoneLink.updateStatus(context, "update cancelled.")
            else -> PhoneLink.updateStatus(
                context,
                "update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"),
            )
        }
    }
}
