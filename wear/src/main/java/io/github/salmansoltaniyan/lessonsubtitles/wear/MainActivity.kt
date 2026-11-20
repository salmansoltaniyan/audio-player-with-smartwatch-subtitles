package io.github.salmansoltaniyan.lessonsubtitles.wear

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Shows the text of the segment the phone is playing. */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private var lastSeq = Long.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        subtitle = findViewById(R.id.subtitle)
        status = findViewById(R.id.status)
        status.setText(R.string.waiting)

        // Registered for the whole life of the activity, so text keeps updating
        // even while the activity is in the background.
        Wearable.getMessageClient(applicationContext).addListener(this)
    }

    override fun onResume() {
        super.onResume()
        // Ask the phone for the current text (we may have missed it while closed).
        requestCurrent()
    }

    override fun onDestroy() {
        Wearable.getMessageClient(applicationContext).removeListener(this)
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_SUBTITLE) return
        val message = try {
            JSONObject(String(event.data, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Bad message", e)
            return
        }
        val seq = message.optLong("seq")
        if (seq < lastSeq) return // an older message that arrived late
        lastSeq = seq

        val text = message.optString("text", "")
        val lang = message.optString("lang", "dutch")
        val playing = message.optBoolean("playing", false)
        runOnUiThread { show(text, lang, playing) }
    }

    private fun show(text: String, lang: String, playing: Boolean) {
        subtitle.text = text
        subtitle.textDirection =
            if (lang.equals("persian", ignoreCase = true)) View.TEXT_DIRECTION_RTL
            else View.TEXT_DIRECTION_LTR

        status.setText(
            when {
                playing -> R.string.empty
                text.isEmpty() -> R.string.waiting
                else -> R.string.paused
            }
        )

        // Keep the screen on only while the lesson is playing.
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun requestCurrent() {
        lifecycleScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, PATH_REQUEST, ByteArray(0)).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not reach the phone", e)
            }
        }
    }

    private companion object {
        const val TAG = "WearSubtitles"

        // Must match WatchProtocol in the phone module.
        const val PATH_SUBTITLE = "/lesson/subtitle"
        const val PATH_REQUEST = "/lesson/request"
    }
}
