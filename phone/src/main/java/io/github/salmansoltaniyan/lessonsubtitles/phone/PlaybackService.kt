package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import org.json.JSONObject
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the queue in the background (also with the screen off), loads the JSON timeline of the
 * current lesson, keeps the watch up to date and runs the watch's button commands.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var watch: WatchSender
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private var sentIndex = Int.MIN_VALUE
    private var sentPlaying = false
    private var sentLesson: Lesson? = null
    private var lastAutoOpenMs = 0L
    private var lastSaveMs = 0L
    private var holdText = true

    /** Opens the watch app again when it was covered while playing (see Prefs.keepWatchOpen). */
    private val reopenWatchApp = Runnable {
        if (player.isPlaying && Prefs.keepWatchOpen(this) && !LessonStore.watchVisible.value) {
            scope.launch { WatchLink.openAppOnWatch(this@PlaybackService) }
        }
    }

    /** The lesson that was playing before the last media item transition. */
    private var previousPath: String? = null

    /** Lesson and position at the last tick while playing; saved when the user switches lesson. */
    private var tickPath: String? = null
    private var tickPositionMs = 0L

    /** Parsed timelines by lesson path; cleared when a new queue is loaded. */
    private val timelines = HashMap<String, Lesson>()
    private var timelineJob: Job? = null

    private val tick = object : Runnable {
        override fun run() {
            tickPath = player.currentMediaItem?.mediaId
            tickPositionMs = player.currentPosition
            update()
            if (player.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }

    // Wearable listeners are called on the main thread.
    // Commands from the watch arrive through WatchCommandService, also when this service is not running.
    private val watchListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != WatchProtocol.PATH_WATCH_STATE) return@OnMessageReceivedListener
        val visible = try {
            JSONObject(String(event.data, Charsets.UTF_8)).optBoolean("visible", false)
        } catch (_: Exception) {
            false
        }
        handler.removeCallbacks(reopenWatchApp)
        if (visible) {
            sendCurrent() // the watch app just opened: send it the current text
        } else if (player.isPlaying && Prefs.keepWatchOpen(this)) {
            handler.postDelayed(reopenWatchApp, REOPEN_WATCH_DELAY_MS)
        }
    }

    // Kept in a field: SharedPreferences only holds a weak reference to listeners.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (Prefs.isEndModeKey(key)) applyEndMode()
        // The subtitle is found from the media position, so it stays in sync at any speed.
        if (Prefs.isSpeedKey(key)) player.setPlaybackSpeed(Prefs.speed(this))
        if (Prefs.isHoldTextKey(key)) {
            holdText = Prefs.holdTextDuringPersian(this)
            update()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        holdText = Prefs.holdTextDuringPersian(this)

        // Index seeking makes "previous/next segment" land on the exact time, also in VBR MP3s.
        val extractors = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

        player = ExoPlayer.Builder(this, DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when the earphones disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep the CPU awake while playing (screen off)
            .build()

        applyEndMode()
        player.setPlaybackSpeed(Prefs.speed(this))
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(tick)
                handler.post(tick)
                if (isPlaying) {
                    maybeOpenWatchApp()
                } else {
                    handler.removeCallbacks(reopenWatchApp)
                    savePosition()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = update()

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    previousPath = player.currentMediaItem?.mediaId
                    timelines.clear() // a new queue: JSON files may have changed
                    loadTimeline()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The previous lesson was played to its end: next time it starts from the beginning.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    previousPath?.let { Prefs.setLessonPosition(this@PlaybackService, it, 0) }
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                    previousPath != null && previousPath == tickPath
                ) {
                    // The user switched lesson while playing: remember where they were.
                    Prefs.setLessonPosition(this@PlaybackService, previousPath!!, tickPositionMs)
                }
                // Lesson buttons or the next lesson in the queue: continue where the user stopped in it.
                val path = mediaItem?.mediaId
                if (path != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                    reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                ) {
                    val resumeMs = Prefs.lessonPosition(this@PlaybackService, path)
                    if (resumeMs > 0) player.seekTo(resumeMs)
                }
                previousPath = path
                loadTimeline()
                savePosition()
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()

        watch = WatchSender(this, scope)
        Wearable.getMessageClient(this).addListener(watchListener)
    }

    private fun applyEndMode() {
        when (Prefs.endMode(this)) {
            EndMode.OFF -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.pauseAtEndOfMediaItems = true
            }
            EndMode.REPEAT_LESSON -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.pauseAtEndOfMediaItems = false
            }
            EndMode.NEXT -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.pauseAtEndOfMediaItems = false
            }
            EndMode.LOOP -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.pauseAtEndOfMediaItems = false
            }
        }
    }

    /** Publishes the current lesson and loads its JSON timeline. */
    private fun loadTimeline() {
        val path = player.currentMediaItem?.mediaId
        val file = LessonStore.queue.value?.items?.firstOrNull { it.relPath == path }
        LessonStore.currentFile.value = file
        timelineJob?.cancel()

        val cached = path?.let { timelines[it] }
        LessonStore.lesson.value = cached
        if (cached != null || file == null) {
            update()
            return
        }
        timelineJob = scope.launch {
            val lesson = withContext(Dispatchers.IO) { readTimeline(file) }
            if (lesson != null) timelines[file.relPath] = lesson
            if (player.currentMediaItem?.mediaId == file.relPath) {
                LessonStore.lesson.value = lesson
                update()
            }
        }
    }

    /**
     * The timeline of [file]: first the tag inside the MP3, then the JSON file next to it.
     * Null if neither exists or both are broken (the lesson then plays without subtitles).
     */
    private fun readTimeline(file: LessonFile): Lesson? {
        Id3Timeline.read(this, file.audioUri)?.let { json ->
            try {
                return Lesson.parse(file.audioUri, file.title, json)
            } catch (_: Exception) {
                // broken tag: try the JSON file
            }
        }
        val jsonUri = file.jsonUri ?: return null
        return try {
            val text = contentResolver.openInputStream(jsonUri)!!.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            Lesson.parse(file.audioUri, file.title, text)
        } catch (_: Exception) {
            null
        }
    }

    /** Finds the current segment and tells the screen and the watch when something changed. */
    private fun update() {
        val lesson = LessonStore.lesson.value
        val positionMs = player.currentPosition
        LessonStore.positionMs.value = positionMs
        LessonStore.durationMs.value = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

        val spoken = lesson?.indexAt(positionMs / 1000.0) ?: -1
        val index = lesson?.shownIndex(spoken, holdText) ?: -1
        val playing = player.isPlaying
        if (index != sentIndex || playing != sentPlaying || lesson !== sentLesson) {
            sentIndex = index
            sentPlaying = playing
            sentLesson = lesson
            LessonStore.currentIndex.value = index
            watch.send(lesson?.segments?.getOrNull(index), playing)
        }

        val now = SystemClock.elapsedRealtime()
        if (playing && now - lastSaveMs > SAVE_EVERY_MS) savePosition()
    }

    /** Remembers the queue, lesson and position so the app can continue there next time. */
    private fun savePosition() {
        lastSaveMs = SystemClock.elapsedRealtime()
        val queue = LessonStore.queue.value ?: return
        val path = player.currentMediaItem?.mediaId ?: return
        var positionMs = player.currentPosition
        val durationMs = player.duration
        // Stopped at the very end: next time start from the beginning.
        if (durationMs != C.TIME_UNSET && positionMs >= durationMs - FINISHED_MARGIN_MS) positionMs = 0
        Prefs.setLastPlayed(this, Prefs.LastPlayed(queue.id, path, positionMs))
        Prefs.setLessonPosition(this, path, positionMs)
    }

    private fun sendCurrent() {
        val lesson = LessonStore.lesson.value
        watch.send(lesson?.segments?.getOrNull(LessonStore.currentIndex.value), player.isPlaying)
    }

    val hasMedia: Boolean get() = player.mediaItemCount > 0

    /** Buttons on the watch. Moving to a segment also starts playback. */
    fun handleCommand(command: String) {
        if (player.mediaItemCount == 0) return
        if (command == "toggle") {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            return
        }
        val lesson = LessonStore.lesson.value ?: return
        val index = lesson.indexAt(player.currentPosition / 1000.0)
        when (command) {
            "repeat" -> playSegment(lesson, index.coerceAtLeast(0))
            "prev" -> playSegment(lesson, (index - 1).coerceAtLeast(0))
            "next" -> if (index + 1 < lesson.segments.size) playSegment(lesson, index + 1)
        }
    }

    private fun playSegment(lesson: Lesson, index: Int) {
        player.seekTo((lesson.segments[index].start * 1000).toLong())
        player.play()
    }

    /** Opens the app on the watch when playback starts, unless it is already open. */
    private fun maybeOpenWatchApp() {
        if (!Prefs.autoOpenWatch(this) || LessonStore.watchVisible.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoOpenMs < AUTO_OPEN_COOLDOWN_MS) return
        lastAutoOpenMs = now
        scope.launch { WatchLink.openAppOnWatch(this@PlaybackService) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PLAY_FROM_WATCH) return super.onStartCommand(intent, flags, startId)
        // Started with startForegroundService: become a foreground service right away. The media
        // notification uses the same id, so it replaces this one when playback starts.
        try {
            startForegroundNow()
        } catch (_: Exception) {
            // Android did not allow a foreground service now (background start limits).
            if (player.mediaItemCount == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (!playLastQueue()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Plays the current lesson, or loads the last played queue and continues it. */
    private fun playLastQueue(): Boolean {
        if (player.mediaItemCount > 0) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
            return true
        }
        val queue = Prefs.lastQueue(this) ?: return false
        val last = Prefs.lastPlayed(this)
        val start = queue.items.indexOfFirst { it.relPath == last?.lessonPath }.coerceAtLeast(0)
        val positionMs = Prefs.lessonPosition(this, queue.items[start].relPath)
        LessonStore.queue.value = queue // before the media items: loadTimeline() reads it
        player.setMediaItems(queue.items.map { it.toMediaItem() }, start, positionMs)
        player.prepare()
        player.play()
        return true
    }

    private fun startForegroundNow() {
        val channelId = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_nav_player)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.now_playing))
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this,
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away; stop the service if nothing is playing.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(reopenWatchApp)
        savePosition()
        handler.removeCallbacks(tick)
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Wearable.getMessageClient(this).removeListener(watchListener)
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    companion object {
        /** Start action used by WatchCommandService: continue the last lesson. */
        const val ACTION_PLAY_FROM_WATCH = "io.github.salmansoltaniyan.lessonsubtitles.PLAY_FROM_WATCH"

        /** The running service, if any. Only used on the main thread. */
        var instance: PlaybackService? = null
            private set

        private const val REOPEN_WATCH_DELAY_MS = 3_000L
        private const val TICK_MS = 100L
        private const val SAVE_EVERY_MS = 5_000L
        private const val AUTO_OPEN_COOLDOWN_MS = 30_000L
        private const val FINISHED_MARGIN_MS = 2_000L
    }
}
