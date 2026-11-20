package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Message paths shared with the wear module (keep them in sync). */
object WatchProtocol {
    /** Phone -> watch. Payload: UTF-8 JSON {"seq", "text", "lang", "playing"}. */
    const val PATH_SUBTITLE = "/lesson/subtitle"

    /** Watch -> phone. Empty payload. Asks the phone to send the current subtitle again. */
    const val PATH_REQUEST = "/lesson/request"
}

/** Sends the current subtitle to every connected watch. */
class WatchSender(context: Context, private val scope: CoroutineScope) {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
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
            try {
                val nodes = nodeClient.connectedNodes.await()
                LessonStore.watchConnected.value = nodes.isNotEmpty()
                for (node in nodes) {
                    messageClient.sendMessage(node.id, WatchProtocol.PATH_SUBTITLE, payload).await()
                }
            } catch (e: Exception) {
                // No Wear OS companion on the phone, watch out of range, etc.
                LessonStore.watchConnected.value = false
                Log.w(TAG, "Could not send subtitle to watch", e)
            }
        }
    }

    private companion object {
        const val TAG = "WatchSender"
    }
}
