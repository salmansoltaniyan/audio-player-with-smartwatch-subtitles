package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AlertDialog
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        updateButton = findViewById(R.id.updateButton)

        findViewById<Button>(R.id.openButton).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.prevButton).setOnClickListener { previousSegment() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { nextSegment() }
        playButton.setOnClickListener { togglePlay() }
        updateButton.setOnClickListener { checkForUpdate() }
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version, BuildConfig.VERSION_NAME)

        findViewById<MaterialSwitch>(R.id.repeatSwitch).apply {
            isChecked = Prefs.repeatLesson(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setRepeatLesson(this@MainActivity, checked)
                controller?.repeatMode = repeatModeFor(checked)
            }
        }
        findViewById<MaterialSwitch>(R.id.autoOpenSwitch).apply {
            isChecked = Prefs.autoOpenWatch(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoOpenWatch(this@MainActivity, checked) }
        }

        // Android 13+: the playback notification needs this permission to be visible.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
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
            c.repeatMode = repeatModeFor(Prefs.repeatLesson(this))
            // A lesson was picked before the controller was ready.
            val lesson = LessonStore.lesson.value
            if (lesson != null && c.mediaItemCount == 0) setMedia(c, lesson)
            refreshPlayButton()
        }, ContextCompat.getMainExecutor(this))

        lifecycleScope.launch { WatchLink.connectedNodes(this@MainActivity) }
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun repeatModeFor(repeat: Boolean) =
        if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

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
                toast(getString(R.string.json_error, e.message ?: e.javaClass.simpleName))
                return@launch
            }

            if (lesson.audioNameInJson.isNotEmpty() && lesson.audioNameInJson != lesson.title) {
                toast(getString(R.string.name_mismatch, lesson.audioNameInJson))
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

    // ---- Player buttons ----

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

    // ---- Updates from GitHub releases ----

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        toast(getString(R.string.checking_update))
        lifecycleScope.launch {
            try {
                val release = Updater.latestRelease()
                if (release == null) {
                    toast(getString(R.string.up_to_date))
                    return@launch
                }
                val watchConnected = WatchLink.connectedNodes(this@MainActivity).isNotEmpty()
                val watchVersion = LessonStore.watchVersion.value
                val phoneNeeds = release.phoneApkUrl != null && release.versionCode > BuildConfig.VERSION_CODE
                // An unknown watch version (watch app closed, or too old to report it) may need an update.
                val watchMayNeed = watchConnected && release.watchApkUrl != null &&
                    (watchVersion == null || watchVersion < release.versionCode)

                if (!phoneNeeds && !watchMayNeed) {
                    toast(getString(R.string.up_to_date))
                } else {
                    askToUpdate(release, phoneNeeds, watchMayNeed, watchVersion)
                }
            } catch (e: Exception) {
                toast(getString(R.string.update_error, e.message ?: e.javaClass.simpleName))
            } finally {
                updateButton.isEnabled = true
            }
        }
    }

    private fun askToUpdate(release: Release, phoneNeeds: Boolean, watchMayNeed: Boolean, watchVersion: Int?) {
        val phoneLine = getString(
            if (phoneNeeds) R.string.update_line_phone_new else R.string.update_line_phone_ok,
            BuildConfig.VERSION_NAME,
        )
        val watchLine = when {
            !watchMayNeed -> getString(R.string.update_line_watch_ok)
            watchVersion == null -> getString(R.string.update_line_watch_unknown)
            else -> getString(R.string.update_line_watch_new, "0.1.$watchVersion")
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_title, release.title))
            .setMessage("$phoneLine\n$watchLine")
            .setNegativeButton(R.string.later, null)
        // Update the watch first: installing the phone app closes this app.
        if (watchMayNeed) builder.setPositiveButton(R.string.update_watch) { _, _ -> updateWatch(release) }
        if (phoneNeeds) {
            if (watchMayNeed) {
                builder.setNeutralButton(R.string.update_phone) { _, _ -> updatePhone(release) }
            } else {
                builder.setPositiveButton(R.string.update_phone) { _, _ -> updatePhone(release) }
            }
        }
        builder.show()
    }

    private fun updatePhone(release: Release) {
        val url = release.phoneApkUrl ?: return
        // Android asks once per app: "Allow installing apps from this source?"
        if (!packageManager.canRequestPackageInstalls()) {
            toast(getString(R.string.allow_installs))
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val progress = progressDialog(getString(R.string.downloading_unknown))
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@MainActivity, url, "phone.apk") { percent ->
                    progress.setMessage(downloadMessage(percent))
                }
                progress.dismiss()
                Updater.install(this@MainActivity, apk)
            } catch (e: Exception) {
                progress.dismiss()
                toast(getString(R.string.update_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun updateWatch(release: Release) {
        val url = release.watchApkUrl ?: return
        val progress = progressDialog(getString(R.string.opening_watch_app))
        lifecycleScope.launch {
            try {
                // The watch app must be open to receive the file.
                WatchLink.openAppOnWatch(this@MainActivity)
                val opened = withTimeoutOrNull(WATCH_OPEN_TIMEOUT_MS) {
                    LessonStore.watchVisible.first { it }
                }
                if (opened == null) {
                    progress.dismiss()
                    toast(getString(R.string.watch_app_not_open))
                    return@launch
                }
                val watchVersion = LessonStore.watchVersion.value
                if (watchVersion != null && watchVersion >= release.versionCode) {
                    progress.dismiss()
                    toast(getString(R.string.watch_up_to_date))
                    return@launch
                }

                val apk = Updater.download(this@MainActivity, url, "watch.apk") { percent ->
                    progress.setMessage(downloadMessage(percent))
                }
                WatchLink.sendApk(this@MainActivity, apk) { percent ->
                    progress.setMessage(getString(R.string.sending_to_watch, percent))
                }
                progress.dismiss()
                toast(getString(R.string.confirm_on_watch))
            } catch (e: Exception) {
                progress.dismiss()
                toast(getString(R.string.update_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun progressDialog(message: String): AlertDialog =
        MaterialAlertDialogBuilder(this).setMessage(message).setCancelable(false).show()

    private fun downloadMessage(percent: Int) =
        if (percent >= 0) getString(R.string.downloading, percent) else getString(R.string.downloading_unknown)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val RESTART_WINDOW_SEC = 1.5
        const val WATCH_OPEN_TIMEOUT_MS = 20_000L
    }
}
