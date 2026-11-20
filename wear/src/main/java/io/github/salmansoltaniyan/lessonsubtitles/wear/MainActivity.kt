package io.github.salmansoltaniyan.lessonsubtitles.wear

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shows the text of the segment the phone is playing.
 * Buttons: previous / repeat / next segment. Tap the text: play / pause. Bezel: previous / next.
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var root: View
    private lateinit var subtitle: TextView
    private lateinit var status: TextView

    private var lastSeq = Long.MIN_VALUE
    private var playing = false
    private var hasText = false
    private var updating = false
    private var lastRotaryMs = 0L

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path == Protocol.PATH_APK) receiveUpdate(channel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        subtitle = findViewById(R.id.subtitle)
        status = findViewById(R.id.status)
        status.setText(R.string.waiting)

        findViewById<View>(R.id.prevButton).setOnClickListener { command(it, "prev") }
        findViewById<View>(R.id.repeatButton).setOnClickListener { command(it, "repeat") }
        findViewById<View>(R.id.nextButton).setOnClickListener { command(it, "next") }
        subtitle.setOnClickListener { command(it, "toggle") }

        // Galaxy Watch touch bezel / rotating crown arrive as rotary scroll events.
        root.setOnGenericMotionListener { _, event -> onRotary(event) }

        // Registered for the whole life of the activity, so text keeps updating
        // even while the activity is in the background.
        Wearable.getMessageClient(applicationContext).addListener(this)
        Wearable.getChannelClient(applicationContext).registerChannelCallback(channelCallback)
    }

    override fun onResume() {
        super.onResume()
        root.requestFocus() // rotary events go to the focused view
        // Tell the phone we are on screen; it answers with the current text.
        PhoneLink.state(this, visible = true)
        // An update may be waiting for the "install unknown apps" permission.
        lifecycleScope.launch { installPendingUpdate() }
    }

    override fun onPause() {
        PhoneLink.state(this, visible = false)
        super.onPause()
    }

    override fun onDestroy() {
        Wearable.getMessageClient(applicationContext).removeListener(this)
        Wearable.getChannelClient(applicationContext).unregisterChannelCallback(channelCallback)
        super.onDestroy()
    }

    // ---- Subtitles from the phone ----

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.PATH_SUBTITLE) return
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
        val isPlaying = message.optBoolean("playing", false)
        runOnUiThread { show(text, lang, isPlaying) }
    }

    private fun show(text: String, lang: String, isPlaying: Boolean) {
        playing = isPlaying
        hasText = text.isNotEmpty()
        subtitle.text = text
        subtitle.textDirection =
            if (lang.equals("persian", ignoreCase = true)) View.TEXT_DIRECTION_RTL
            else View.TEXT_DIRECTION_LTR
        refreshStatus()
        refreshKeepScreenOn()
    }

    private fun refreshStatus() {
        status.text = when {
            updating -> status.text // set by the update code
            playing -> ""
            !hasText -> getString(R.string.waiting)
            else -> getString(R.string.paused)
        }
    }

    /** Keep the screen on while the lesson plays or an update is being received. */
    private fun refreshKeepScreenOn() {
        if (playing || updating) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- Controls ----

    private fun command(view: View, command: String) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        PhoneLink.command(this, command)
    }

    private fun onRotary(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) return false
        // Positive = clockwise.
        val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        val now = SystemClock.elapsedRealtime()
        if (delta != 0f && now - lastRotaryMs > ROTARY_DEBOUNCE_MS) {
            lastRotaryMs = now
            command(root, if (delta > 0) "next" else "prev")
        }
        return true
    }

    // ---- Update sent by the phone ----

    private fun receiveUpdate(channel: ChannelClient.Channel) {
        updating = true
        status.setText(R.string.receiving_update)
        refreshKeepScreenOn()
        PhoneLink.updateStatus(this, "receiving the update…")
        lifecycleScope.launch {
            try {
                ApkUpdate.receive(applicationContext, channel)
                status.setText(R.string.installing_update)
                installPendingUpdate()
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                PhoneLink.updateStatus(this@MainActivity, "update failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                updating = false
                refreshStatus()
                refreshKeepScreenOn()
            }
        }
    }

    private suspend fun installPendingUpdate() {
        try {
            ApkUpdate.installPending(this)
        } catch (e: Exception) {
            Log.w(TAG, "Install failed", e)
            PhoneLink.updateStatus(this, "install failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "WearSubtitles"
        const val ROTARY_DEBOUNCE_MS = 400L
    }
}
