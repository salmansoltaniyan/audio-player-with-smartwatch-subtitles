package io.github.salmansoltaniyan.lessonsubtitles.phone

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * State shared between the screen (MainActivity) and the background player (PlaybackService).
 * Both run in the same process.
 */
object LessonStore {
    /** The loaded lesson, or null. Set by MainActivity, read by PlaybackService. */
    val lesson = MutableStateFlow<Lesson?>(null)

    /** Index of the segment being shown, -1 if none. Written by PlaybackService. */
    val currentIndex = MutableStateFlow(-1)

    /** Playback position in milliseconds. Written by PlaybackService. */
    val positionMs = MutableStateFlow(0L)

    /** Whether a watch was reachable the last time we looked; null = unknown. */
    val watchConnected = MutableStateFlow<Boolean?>(null)

    /** Whether the watch app is open on the watch screen (reported by the watch). */
    val watchVisible = MutableStateFlow(false)

    /** versionCode of the watch app, once the watch has reported it. */
    val watchVersion = MutableStateFlow<Int?>(null)
}
