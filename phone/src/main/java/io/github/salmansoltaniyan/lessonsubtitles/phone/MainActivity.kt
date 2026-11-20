package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.wearable.Wearable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var lessonTitle: TextView
    private lateinit var segmentText: TextView
    private lateinit var segmentInfo: TextView
    private lateinit var timeText: TextView
    private lateinit var watchStatus: TextView
    private lateinit var playButton: Button
    private lateinit var updateButton: Button

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = try {
            controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()
        } catch (_: Exception) {
            null
        }

    /** The MP3 picked in step 1, waiting for its JSON in step 2. */
    private var pendingAudio: Uri? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        keepReadPermission(uri)
        pendingAudio = uri
        Toast.makeText(this, R.string.pick_json_hint, Toast.LENGTH_LONG).show()
        pickJson.launch(arrayOf("*/*"))
    }

    private val pickJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val audio = pendingAudio
        pendingAudio = null
        if (uri == null || audio == null) return@registerForActivityResult
        loadLesson(audio, uri)
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lessonTitle = findViewById(R.id.lessonTitle)
        segmentText = findViewById(R.id.segmentText)
        segmentInfo = findViewById(R.id.segmentInfo)
        timeText = findViewById(R.id.timeText)
        watchStatus = findViewById(R.id.watchStatus)
        playButton = findViewById(R.id.playButton)

        findViewById<Button>(R.id.openButton).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.prevButton).setOnClickListener { previousSegment() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { nextSegment() }
        playButton.setOnClickListener { togglePlay() }

        findViewById<TextView>(R.id.versionText).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        updateButton = findViewById(R.id.updateButton)
        updateButton.setOnClickListener { checkForUpdate() }

        // Android 13+: the playback notification needs this permission to be visible.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(LessonStore.lesson, LessonStore.currentIndex) { lesson, index ->
                        lesson to index
                    }.collect { (lesson, index) -> showSegment(lesson, index) }
                }
                launch {
                    LessonStore.positionMs.collect { ms -> showTime(ms) }
                }
                launch {
                    LessonStore.watchConnected.collect { connected ->
                        watchStatus.setText(
                            when (connected) {
                                true -> R.string.watch_connected
                                false -> R.string.watch_not_connected
                                null -> R.string.watch_unknown
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = controller ?: return@addListener
            c.addListener(playerListener)
            // A lesson was picked before the controller was ready.
            val lesson = LessonStore.lesson.value
            if (lesson != null && c.mediaItemCount == 0) setMedia(c, lesson)
            refreshPlayButton()
        }, ContextCompat.getMainExecutor(this))

        checkWatch()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    // ---- Loading a lesson ----

    private fun loadLesson(audio: Uri, json: Uri) {
        lifecycleScope.launch {
            val lesson = try {
                withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(json)!!.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    Lesson.parse(audio, displayName(audio), text)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.json_error, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            if (lesson.audioNameInJson.isNotEmpty() && lesson.audioNameInJson != lesson.title) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.name_mismatch, lesson.audioNameInJson),
                    Toast.LENGTH_LONG,
                ).show()
            }

            LessonStore.lesson.value = lesson
            controller?.let { setMedia(it, lesson) }
        }
    }

    private fun setMedia(c: MediaController, lesson: Lesson) {
        val item = MediaItem.Builder()
            .setUri(lesson.audioUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(lesson.title).build())
            .build()
        c.setMediaItem(item)
        c.prepare()
    }

    /** Lets the background player keep reading the MP3 later (e.g. after the app restarts). */
    private fun keepReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some file providers do not offer persistable permissions; reading still works now.
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "lesson"

    // ---- Buttons ----

    private fun togglePlay() {
        val c = controller ?: return
        if (LessonStore.lesson.value == null) {
            Toast.makeText(this, R.string.no_lesson, Toast.LENGTH_SHORT).show()
            return
        }
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    /** Goes to the start of the current segment, or to the previous one if we are already near its start. */
    private fun previousSegment() {
        val c = controller ?: return
        val lesson = LessonStore.lesson.value ?: return
        val index = lesson.indexAt(c.currentPosition / 1000.0)
        val current = lesson.segments.getOrNull(index)
        val target = if (current != null && c.currentPosition / 1000.0 - current.start > RESTART_WINDOW_SEC) {
            index
        } else {
            (index - 1).coerceAtLeast(0)
        }
        c.seekTo((lesson.segments[target].start * 1000).toLong())
    }

    private fun nextSegment() {
        val c = controller ?: return
        val lesson = LessonStore.lesson.value ?: return
        val index = lesson.indexAt(c.currentPosition / 1000.0)
        val next = lesson.segments.getOrNull(index + 1) ?: return
        c.seekTo((next.start * 1000).toLong())
    }

    // ---- Display ----

    private fun refreshPlayButton() {
        playButton.setText(if (controller?.isPlaying == true) R.string.pause else R.string.play)
    }

    private fun showSegment(lesson: Lesson?, index: Int) {
        lessonTitle.text = lesson?.title ?: getString(R.string.no_lesson)
        val segment = lesson?.segments?.getOrNull(index)
        segmentText.text = segment?.text ?: ""
        segmentText.textDirection =
            if (segment?.lang.equals("persian", ignoreCase = true)) View.TEXT_DIRECTION_RTL
            else View.TEXT_DIRECTION_LTR
        segmentInfo.text = if (lesson == null || segment == null) "" else
            getString(R.string.segment_info, segment.row, index + 1, lesson.segments.size, segment.lang)
        showTime(LessonStore.positionMs.value)
    }

    private fun showTime(positionMs: Long) {
        val lesson = LessonStore.lesson.value
        timeText.text = if (lesson == null) "" else
            "${formatTime(positionMs / 1000)} / ${formatTime(lesson.durationSec.toLong())}"
    }

    private fun formatTime(totalSec: Long): String = "%d:%02d".format(totalSec / 60, totalSec % 60)

    // ---- Update from GitHub releases ----

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val update = Updater.findUpdate()
                if (update == null) {
                    Toast.makeText(this@MainActivity, R.string.up_to_date, Toast.LENGTH_SHORT).show()
                } else {
                    askToInstall(update)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_error, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                updateButton.isEnabled = true
            }
        }
    }

    private fun askToInstall(update: UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, update.title, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.install) { _, _ -> downloadAndInstall(update) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun downloadAndInstall(update: UpdateInfo) {
        // Android asks once per app: "Allow installing apps from this source?"
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.allow_installs, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        val progress = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.downloading_unknown)
            .setCancelable(false)
            .show()
        updateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@MainActivity, update) { percent ->
                    progress.setMessage(
                        if (percent >= 0) getString(R.string.downloading, percent)
                        else getString(R.string.downloading_unknown)
                    )
                }
                progress.dismiss()
                Updater.install(this@MainActivity, apk)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_error, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                updateButton.isEnabled = true
            }
        }
    }

    private fun checkWatch() {
        lifecycleScope.launch {
            LessonStore.watchConnected.value = try {
                Wearable.getNodeClient(this@MainActivity).connectedNodes.await().isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }
    }

    private companion object {
        const val RESTART_WINDOW_SEC = 1.5
    }
}
