package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays the lesson in the background (also with the screen off), keeps the watch
 * up to date with the segment that is being played, and runs the watch's button commands.
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

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (player.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }

    // Wearable listeners are called on the main thread.
    private val watchListener = MessageClient.OnMessageReceivedListener { event ->
        when (event.path) {
            WatchProtocol.PATH_COMMAND -> handleCommand(String(event.data, Charsets.UTF_8))
            // The watch app just opened: send it the current text.
            WatchProtocol.PATH_WATCH_STATE -> sendCurrent()
        }
    }

    override fun onCreate() {
        super.onCreate()

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

        player.repeatMode = if (Prefs.repeatLesson(this)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(tick)
                handler.post(tick)
                if (isPlaying) maybeOpenWatchApp()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = update()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = update()
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

    /** Finds the current segment and tells the screen and the watch when something changed. */
    private fun update() {
        val lesson = LessonStore.lesson.value
        val positionMs = player.currentPosition
        LessonStore.positionMs.value = positionMs

        val index = lesson?.indexAt(positionMs / 1000.0) ?: -1
        val playing = player.isPlaying
        if (index != sentIndex || playing != sentPlaying || lesson !== sentLesson) {
            sentIndex = index
            sentPlaying = playing
            sentLesson = lesson
            LessonStore.currentIndex.value = index
            watch.send(lesson?.segments?.getOrNull(index), playing)
        }
    }

    private fun sendCurrent() {
        val lesson = LessonStore.lesson.value
        watch.send(lesson?.segments?.getOrNull(LessonStore.currentIndex.value), player.isPlaying)
    }

    /** Buttons on the watch. Moving to a segment also starts playback. */
    private fun handleCommand(command: String) {
        val lesson = LessonStore.lesson.value ?: return
        if (player.mediaItemCount == 0) return
        val index = lesson.indexAt(player.currentPosition / 1000.0)
        when (command) {
            "toggle" -> if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away; stop the service if nothing is playing.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        Wearable.getMessageClient(this).removeListener(watchListener)
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TICK_MS = 100L
        const val AUTO_OPEN_COOLDOWN_MS = 30_000L
    }
}
