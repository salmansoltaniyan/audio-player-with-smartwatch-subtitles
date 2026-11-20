package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.app.Application
import android.widget.Toast
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/** Listens to the watch for as long as the app's process lives. */
class LessonApp : Application() {

    private val watchListener = MessageClient.OnMessageReceivedListener { event ->
        when (event.path) {
            WatchProtocol.PATH_WATCH_STATE -> {
                val state = try {
                    JSONObject(String(event.data, Charsets.UTF_8))
                } catch (_: Exception) {
                    return@OnMessageReceivedListener
                }
                LessonStore.watchConnected.value = true
                LessonStore.watchVersion.value = state.optInt("version", 0).takeIf { it > 0 }
                LessonStore.watchVisible.value = state.optBoolean("visible", false)
            }

            WatchProtocol.PATH_UPDATE_STATUS -> Toast.makeText(
                this,
                getString(R.string.watch_says, String(event.data, Charsets.UTF_8)),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Wearable.getMessageClient(this).addListener(watchListener)
    }
}
