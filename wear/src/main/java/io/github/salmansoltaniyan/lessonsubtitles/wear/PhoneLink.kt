package io.github.salmansoltaniyan.lessonsubtitles.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/** Message paths shared with the phone module (keep them in sync with WatchProtocol there). */
object Protocol {
    /** Phone -> watch. UTF-8 JSON {"seq", "text", "lang", "playing"}. */
    const val PATH_SUBTITLE = "/lesson/subtitle"

    /** Watch -> phone. UTF-8 JSON {"visible", "version"}. */
    const val PATH_WATCH_STATE = "/lesson/watch_state"

    /** Watch -> phone. UTF-8 command: "prev", "repeat", "next" or "toggle". */
    const val PATH_COMMAND = "/lesson/command"

    /** Phone -> watch channel: 8-byte length, then the watch APK. */
    const val PATH_APK = "/lesson/apk"

    /** Watch -> phone. UTF-8 text about the update, shown on the phone as a toast. */
    const val PATH_UPDATE_STATUS = "/lesson/update_status"
}

/** Fire-and-forget messages to the phone. */
object PhoneLink {
    private const val TAG = "PhoneLink"

    fun send(context: Context, path: String, data: ByteArray) {
        val app = context.applicationContext
        Wearable.getNodeClient(app).connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) {
                    Wearable.getMessageClient(app).sendMessage(node.id, path, data)
                        .addOnFailureListener { Log.w(TAG, "Could not send $path", it) }
                }
            }
            .addOnFailureListener { Log.w(TAG, "No phone reachable", it) }
    }

    fun command(context: Context, command: String) =
        send(context, Protocol.PATH_COMMAND, command.toByteArray(Charsets.UTF_8))

    /** Tells the phone whether this app is on screen, and which version it is. */
    fun state(context: Context, visible: Boolean) = send(
        context,
        Protocol.PATH_WATCH_STATE,
        JSONObject()
            .put("visible", visible)
            .put("version", BuildConfig.VERSION_CODE)
            .toString()
            .toByteArray(Charsets.UTF_8),
    )

    fun updateStatus(context: Context, text: String) =
        send(context, Protocol.PATH_UPDATE_STATUS, text.toByteArray(Charsets.UTF_8))
}
