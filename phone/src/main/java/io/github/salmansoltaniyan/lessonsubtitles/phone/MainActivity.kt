package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var queueInfo: TextView
    private lateinit var lessonTitle: TextView
    private lateinit var segmentText: TextView
    private lateinit var segmentInfo: TextView
    private lateinit var timeText: TextView
    private lateinit var seekBar: SeekBar
    private var seeking = false
    private lateinit var watchStatus: TextView
    private lateinit var playButton: Button
    private lateinit var endModeButton: Button
    private lateinit var speedButton: Button
    private lateinit var updateButton: Button
    private lateinit var settingsSheet: BottomSheetDialog
    private lateinit var miniBar: View
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: Button
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var panels: Map<Int, View>

    lateinit var libraryPanel: LibraryPanel
        private set
    lateinit var playlistsPanel: PlaylistsPanel
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = try {
            controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()
        } catch (_: Exception) {
            null
        }

    /** The lessons folder (document tree), or null if not chosen yet. */
    var lessonsFolder: Uri? = null
        private set
    private var restoreDone = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // Read: play lessons. Write: save playlists in the "playlists" sub folder.
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Reading still works for now; it may need to be chosen again after a restart.
        }
        Prefs.setLessonsFolder(this, uri)
        lessonsFolder = uri
        reloadLibrary()
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        queueInfo = findViewById(R.id.queueInfo)
        lessonTitle = findViewById(R.id.lessonTitle)
        segmentText = findViewById(R.id.segmentText)
        segmentInfo = findViewById(R.id.segmentInfo)
        timeText = findViewById(R.id.timeText)
        seekBar = findViewById(R.id.seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = timeLabel(progress.toLong(), LessonStore.durationMs.value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                controller?.seekTo(bar.progress.toLong())
            }
        })
        watchStatus = findViewById(R.id.watchStatus)
        playButton = findViewById(R.id.playButton)
        // Settings live in a bottom sheet; its views are created once and kept.
        val settings = layoutInflater.inflate(R.layout.sheet_settings, null)
        settingsSheet = BottomSheetDialog(this).apply { setContentView(settings) }
        findViewById<View>(R.id.settingsButton).setOnClickListener { settingsSheet.show() }
        endModeButton = settings.findViewById(R.id.endModeButton)
        speedButton = settings.findViewById(R.id.speedButton)
        updateButton = settings.findViewById(R.id.updateButton)
        miniBar = findViewById(R.id.miniBar)
        miniTitle = findViewById(R.id.miniTitle)
        miniPlay = findViewById(R.id.miniPlay)
        bottomNav = findViewById(R.id.bottomNav)

        panels = mapOf(
            R.id.nav_player to findViewById(R.id.playerPanel),
            R.id.nav_playlists to findViewById(R.id.playlistsPanel),
            R.id.nav_library to findViewById(R.id.libraryPanel),
        )
        bottomNav.setOnItemSelectedListener { item ->
            showPanel(item.itemId)
            true
        }

        // Player tab
        findViewById<Button>(R.id.prevButton).setOnClickListener { previousSegment() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { nextSegment() }
        findViewById<Button>(R.id.prevLessonButton).setOnClickListener { previousLesson() }
        findViewById<Button>(R.id.nextLessonButton).setOnClickListener { nextLesson() }
        playButton.setOnClickListener { togglePlay() }
        miniPlay.setOnClickListener { togglePlay() }
        miniTitle.setOnClickListener { bottomNav.selectedItemId = R.id.nav_player }
        endModeButton.setOnClickListener { chooseEndMode() }
        refreshEndModeButton()
        speedButton.setOnClickListener { chooseSpeed() }
        refreshSpeedButton()
        updateButton.setOnClickListener { checkForUpdate() }
        settings.findViewById<TextView>(R.id.versionText).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        settings.findViewById<MaterialSwitch>(R.id.autoOpenSwitch).apply {
            isChecked = Prefs.autoOpenWatch(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoOpenWatch(this@MainActivity, checked) }
        }
        settings.findViewById<MaterialSwitch>(R.id.holdTextSwitch).apply {
            isChecked = Prefs.holdTextDuringPersian(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setHoldTextDuringPersian(this@MainActivity, checked) }
        }
        settings.findViewById<MaterialSwitch>(R.id.keepWatchOpenSwitch).apply {
            isChecked = Prefs.keepWatchOpen(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setKeepWatchOpen(this@MainActivity, checked) }
        }

        libraryPanel = LibraryPanel(this, findViewById(R.id.libraryPanel))
        playlistsPanel = PlaylistsPanel(this, findViewById(R.id.playlistsPanel))

        onBackPressedDispatcher.addCallback(this) {
            if (playlistsPanel.onBack() || libraryPanel.onBack()) return@addCallback
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
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
                    combine(LessonStore.lesson, LessonStore.currentIndex, LessonStore.currentFile) { l, i, f ->
                        Triple(l, i, f)
                    }.collect { (lesson, index, file) -> showSegment(lesson, index, file) }
                }
                launch { LessonStore.positionMs.collect { showTime() } }
                launch { LessonStore.durationMs.collect { showTime() } }
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

        lessonsFolder = Prefs.lessonsFolder(this)
        if (lessonsFolder != null) {
            reloadLibrary()
        } else {
            libraryPanel.showFolder(null)
            playlistsPanel.render()
            bottomNav.selectedItemId = R.id.nav_library
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
            refreshPlayButtons()
            restoreLastPlayed()
        }, ContextCompat.getMainExecutor(this))

        lifecycleScope.launch { WatchLink.connectedNodes(this@MainActivity) }
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    // ---- Tabs ----

    private fun showPanel(id: Int) {
        for ((panelId, panel) in panels) panel.visibility = if (panelId == id) View.VISIBLE else View.GONE
        refreshMiniBar()
    }

    private fun refreshMiniBar() {
        val file = LessonStore.currentFile.value
        val onPlayer = bottomNav.selectedItemId == R.id.nav_player
        miniBar.visibility = if (!onPlayer && file != null) View.VISIBLE else View.GONE
        miniTitle.text = file?.title ?: ""
    }

    // ---- Lessons folder ----

    fun chooseFolder() = pickFolder.launch(lessonsFolder)

    /** Scans the lessons folder and loads the playlists. */
    fun reloadLibrary() {
        val folder = lessonsFolder ?: return
        libraryPanel.showFolder(folder)
        libraryPanel.setRefreshing(true)
        lifecycleScope.launch {
            try {
                LessonStore.library.value = Library.scan(this@MainActivity, folder)
                LessonStore.playlists.value = Playlists.load(this@MainActivity, folder)
            } catch (e: Exception) {
                toast(getString(R.string.folder_error, e.message ?: e.javaClass.simpleName))
            } finally {
                libraryPanel.setRefreshing(false)
            }
            restoreLastPlayed()
        }
    }

    fun folderName(uri: Uri): String =
        DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "/" }

    // ---- Queue ----

    fun folderQueue(folder: String): PlayQueue = PlayQueue(
        id = "folder:$folder",
        title = folder.ifEmpty { getString(R.string.root_folder) },
        items = LessonStore.library.value.filter { it.folder == folder },
    )

    fun playlistQueue(playlist: Playlist): PlayQueue {
        val byPath = LessonStore.library.value.associateBy { it.relPath }
        return PlayQueue(
            id = "playlist:${playlist.name}",
            title = playlist.name,
            items = playlist.lessons.mapNotNull { byPath[it] },
        )
    }

    /**
     * Loads [queue] into the player and starts at [startPath] (or the first lesson), at [positionMs]
     * or, when null, where the user stopped in that lesson last time.
     */
    fun playQueue(queue: PlayQueue, startPath: String?, positionMs: Long? = null, autoPlay: Boolean = true) {
        val c = controller
        if (c == null) {
            toast(getString(R.string.player_not_ready))
            return
        }
        if (queue.items.isEmpty()) {
            toast(getString(R.string.queue_empty))
            return
        }
        val start = queue.items.indexOfFirst { it.relPath == startPath }.coerceAtLeast(0)
        val startFile = queue.items[start]
        val sameLesson = LessonStore.queue.value?.id == queue.id &&
            c.currentMediaItem?.mediaId == startFile.relPath && c.mediaItemCount == queue.items.size
        if (sameLesson && positionMs == null) {
            // The lesson is already loaded (for example restored at start): just continue it.
            LessonStore.queue.value = queue
        } else {
            val resumeMs = positionMs ?: Prefs.lessonPosition(this, startFile.relPath)
            if (positionMs == null && resumeMs > 0) {
                toast(getString(R.string.continuing_at, formatTime(resumeMs / 1000)))
            }
            LessonStore.queue.value = queue // before the media items: the service reads it
            Prefs.setLastQueue(this, queue)
            c.setMediaItems(queue.items.map { it.toMediaItem() }, start, resumeMs)
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        if (autoPlay) {
            c.play()
            bottomNav.selectedItemId = R.id.nav_player
        }
    }

    /** When the app starts with an empty player, goes back to where the user was (paused). */
    private fun restoreLastPlayed(autoPlay: Boolean = false): Boolean {
        if (restoreDone) return false
        val c = controller ?: return false
        if (c.mediaItemCount > 0) {
            restoreDone = true // the player is already playing something
            return false
        }
        if (LessonStore.library.value.isEmpty()) return false // not scanned yet
        restoreDone = true
        val last = Prefs.lastPlayed(this) ?: return false
        val queue = when {
            last.queueId.startsWith("playlist:") -> LessonStore.playlists.value
                .firstOrNull { "playlist:${it.name}" == last.queueId }?.let(::playlistQueue)
            last.queueId.startsWith("folder:") -> folderQueue(last.queueId.removePrefix("folder:"))
            else -> null
        } ?: return false
        if (queue.items.none { it.relPath == last.lessonPath }) return false
        playQueue(queue, last.lessonPath, last.positionMs, autoPlay)
        return true
    }

    // ---- Player buttons ----

    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            // The player was stopped (for example the app was closed): load the last lesson again.
            restoreDone = false
            if (!restoreLastPlayed(autoPlay = true)) toast(getString(R.string.no_lesson_hint))
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

    private fun previousLesson() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0)
    }

    private fun nextLesson() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else toast(getString(R.string.last_lesson))
    }

    private fun chooseEndMode() {
        val modes = EndMode.entries
        val current = Prefs.endMode(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.end_mode_title)
            .setSingleChoiceItems(
                modes.map { getString(it.labelRes) }.toTypedArray(),
                modes.indexOf(current),
            ) { dialog, which ->
                Prefs.setEndMode(this, modes[which]) // the service applies it
                refreshEndModeButton()
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseSpeed() {
        val speeds = Prefs.SPEEDS
        val current = speeds.indexOfFirst { it == Prefs.speed(this) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speed_title)
            .setSingleChoiceItems(speeds.map { Prefs.speedLabel(it) }.toTypedArray(), current) { dialog, which ->
                Prefs.setSpeed(this, speeds[which]) // the service applies it
                refreshSpeedButton()
                dialog.dismiss()
            }
            .show()
    }

    private fun refreshSpeedButton() {
        speedButton.text = getString(R.string.speed_button, Prefs.speedLabel(Prefs.speed(this)))
    }

    private fun refreshEndModeButton() {
        endModeButton.text = getString(R.string.end_mode_button, getString(Prefs.endMode(this).labelRes))
    }

    // ---- Display ----

    private fun refreshPlayButtons() {
        val label = if (controller?.isPlaying == true) R.string.pause else R.string.play
        playButton.setText(label)
        miniPlay.setText(label)
    }

    private fun showSegment(lesson: Lesson?, index: Int, file: LessonFile?) {
        val queue = LessonStore.queue.value
        val position = queue?.items?.indexOfFirst { it.relPath == file?.relPath } ?: -1
        queueInfo.text = if (queue == null || position < 0) "" else
            getString(R.string.queue_info, queue.title, position + 1, queue.items.size)
        lessonTitle.text = file?.title ?: getString(R.string.no_lesson)

        val segment = lesson?.segments?.getOrNull(index)
        segmentText.text = when {
            segment != null -> segment.text
            file != null && !file.hasTimeline -> getString(R.string.no_subtitles)
            else -> ""
        }
        segmentText.textDirection =
            if (segment?.lang.equals("persian", ignoreCase = true)) View.TEXT_DIRECTION_RTL
            else View.TEXT_DIRECTION_LTR
        segmentInfo.text = if (lesson == null || segment == null) "" else
            getString(R.string.segment_info, segment.row, index + 1, lesson.segments.size, segment.lang)
        showTime()
        refreshMiniBar()
        libraryPanel.setPlaying(file?.relPath)
        playlistsPanel.setPlaying(file?.relPath)
    }

    private fun showTime() {
        val duration = LessonStore.durationMs.value
        val hasLesson = LessonStore.currentFile.value != null
        seekBar.isEnabled = hasLesson && duration > 0
        if (seeking) return
        timeText.text = if (!hasLesson) "" else timeLabel(LessonStore.positionMs.value, duration)
        seekBar.max = duration.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        seekBar.progress = LessonStore.positionMs.value.coerceIn(0, seekBar.max.toLong()).toInt()
    }

    private fun timeLabel(positionMs: Long, durationMs: Long) =
        "${formatTime(positionMs / 1000)} / ${formatTime(durationMs / 1000)}"

    private fun formatTime(totalSec: Long): String = "%d:%02d".format(totalSec / 60, totalSec % 60)

    // ---- Small helpers used by the panels ----

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Asks for a line of text (e.g. a playlist name). */
    fun promptText(title: Int, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initial)
            setSelection(initial.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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

    private companion object {
        const val RESTART_WINDOW_SEC = 1.5
        const val WATCH_OPEN_TIMEOUT_MS = 20_000L
    }
}
