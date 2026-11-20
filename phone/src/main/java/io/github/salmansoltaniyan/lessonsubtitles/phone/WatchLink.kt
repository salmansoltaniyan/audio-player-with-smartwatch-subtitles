package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Message paths shared with the wear module (keep them in sync with Protocol.kt there). */
object WatchProtocol {
    /** Phone -> watch. UTF-8 JSON {"seq", "text", "lang", "playing"}. */
    const val PATH_SUBTITLE = "/lesson/subtitle"

    /** Watch -> phone. UTF-8 JSON {"visible", "version"}. Sent when the watch app opens or closes. */
    const val PATH_WATCH_STATE = "/lesson/watch_state"

    /** Watch -> phone. UTF-8 command: "prev", "repeat", "next" or "toggle". */
    const val PATH_COMMAND = "/lesson/command"

    /** Phone -> watch channel: 8-byte length, then the watch APK. */
    const val PATH_APK = "/lesson/apk"

    /** Watch -> phone. UTF-8 text about the watch update, shown as a toast. */
    const val PATH_UPDATE_STATUS = "/lesson/update_status"

    /** Opens the watch app (matches the VIEW intent filter in the wear manifest). */
    const val OPEN_URI = "lessonsubtitles://open"
}

/** Everything the phone does with the watch. */
object WatchLink {
    private const val TAG = "WatchLink"

    suspend fun connectedNodes(context: Context): List<Node> = try {
        Wearable.getNodeClient(context.applicationContext).connectedNodes.await().also {
            LessonStore.watchConnected.value = it.isNotEmpty()
        }
    } catch (e: Exception) {
        // No Wear OS companion app on the phone, Bluetooth off, etc.
        LessonStore.watchConnected.value = false
        emptyList()
    }

    /** Opens the app on the watch through Wear OS. Returns false if no watch was reached. */
    suspend fun openAppOnWatch(context: Context): Boolean {
        val nodes = connectedNodes(context)
        if (nodes.isEmpty()) return false
        val helper = RemoteActivityHelper(context.applicationContext, ContextCompat.getMainExecutor(context))
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse(WatchProtocol.OPEN_URI))
        var opened = false
        for (node in nodes) {
            try {
                helper.startRemoteActivity(intent, node.id).await()
                opened = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not open the app on ${node.displayName}", e)
            }
        }
        return opened
    }

    /** Sends the watch APK over a Data Layer channel. [onProgress] gets 0..100 on the main thread. */
    suspend fun sendApk(context: Context, apk: File, onProgress: (Int) -> Unit) {
        val node = connectedNodes(context).firstOrNull() ?: throw IOException("No watch connected")
        val channelClient = Wearable.getChannelClient(context.applicationContext)
        val channel = channelClient.openChannel(node.id, WatchProtocol.PATH_APK).await()
        try {
            val stream = channelClient.getOutputStream(channel).await()
            withContext(Dispatchers.IO) {
                DataOutputStream(BufferedOutputStream(stream)).use { out ->
                    val total = apk.length()
                    out.writeLong(total)
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var sent = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            sent += n
                            val percent = (sent * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
            }
            // The watch closes the channel after it has read everything.
        } catch (e: Exception) {
            channelClient.close(channel)
            throw e
        }
    }
}

/** Sends the current subtitle to every connected watch. */
class WatchSender(private val context: Context, private val scope: CoroutineScope) {
    private val messageClient = Wearable.getMessageClient(context.applicationContext)

    // Increasing number so the watch can drop messages that arrive out of order.
    // Based on the clock so it keeps increasing after the service restarts.
    private var lastSeq = 0L

    fun send(segment: Segment?, playing: Boolean) {
        lastSeq = maxOf(lastSeq + 1, System.currentTimeMillis())
        val payload = JSONObject()
            .put("seq", lastSeq)
            .put("text", segment?.text ?: "")
            .put("lang", segment?.lang ?: "dutch")
            .put("playing", playing)
            .toString()
            .toByteArray(Charsets.UTF_8)

        scope.launch {
            for (node in WatchLink.connectedNodes(context)) {
                try {
                    messageClient.sendMessage(node.id, WatchProtocol.PATH_SUBTITLE, payload).await()
                } catch (e: Exception) {
                    Log.w("WatchSender", "Could not send subtitle to watch", e)
                }
            }
        }
    }
}
