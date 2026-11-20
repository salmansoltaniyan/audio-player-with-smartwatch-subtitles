package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** What happens when a lesson ends. */
enum class EndMode(val labelRes: Int) {
    OFF(R.string.end_mode_off),
    REPEAT_LESSON(R.string.end_mode_repeat),
    NEXT(R.string.end_mode_next),
    LOOP(R.string.end_mode_loop),
}

/** User settings that survive app restarts. */
object Prefs {
    private const val END_MODE = "end_mode"
    private const val OLD_REPEAT_LESSON = "repeat_lesson" // version 0.1.2
    private const val AUTO_OPEN_WATCH = "auto_open_watch"
    private const val LESSONS_FOLDER = "lessons_folder"
    private const val LAST_QUEUE = "last_queue"
    private const val LAST_LESSON = "last_lesson"
    private const val LAST_POSITION = "last_position"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun endMode(context: Context): EndMode {
        val p = prefs(context)
        val saved = p.getString(END_MODE, null)
        if (saved != null) return EndMode.entries.firstOrNull { it.name == saved } ?: EndMode.NEXT
        return if (p.getBoolean(OLD_REPEAT_LESSON, false)) EndMode.REPEAT_LESSON else EndMode.NEXT
    }

    fun setEndMode(context: Context, mode: EndMode) =
        prefs(context).edit().putString(END_MODE, mode.name).apply()

    fun isEndModeKey(key: String?) = key == END_MODE

    /** Open the app on the watch when playback starts. */
    fun autoOpenWatch(context: Context) = prefs(context).getBoolean(AUTO_OPEN_WATCH, true)
    fun setAutoOpenWatch(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(AUTO_OPEN_WATCH, value).apply()

    /** The lessons folder picked by the user (a document tree URI). */
    fun lessonsFolder(context: Context): Uri? =
        prefs(context).getString(LESSONS_FOLDER, null)?.let(Uri::parse)
    fun setLessonsFolder(context: Context, uri: Uri) =
        prefs(context).edit().putString(LESSONS_FOLDER, uri.toString()).apply()

    /** Where the user was: queue id (see PlayQueue.id), lesson path and position. */
    data class LastPlayed(val queueId: String, val lessonPath: String, val positionMs: Long)

    fun lastPlayed(context: Context): LastPlayed? {
        val p = prefs(context)
        val queue = p.getString(LAST_QUEUE, null) ?: return null
        val lesson = p.getString(LAST_LESSON, null) ?: return null
        return LastPlayed(queue, lesson, p.getLong(LAST_POSITION, 0))
    }

    fun setLastPlayed(context: Context, last: LastPlayed) = prefs(context).edit()
        .putString(LAST_QUEUE, last.queueId)
        .putString(LAST_LESSON, last.lessonPath)
        .putLong(LAST_POSITION, last.positionMs)
        .apply()
}
