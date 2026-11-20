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
    private const val SPEED = "speed"
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

    /** Playback speeds offered to the user. */
    val SPEEDS = floatArrayOf(0.5f, 0.6f, 0.7f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Playback speed; 1 is normal. The pitch of the voice does not change. */
    fun speed(context: Context) = prefs(context).getFloat(SPEED, 1f)
    fun setSpeed(context: Context, speed: Float) = prefs(context).edit().putFloat(SPEED, speed).apply()
    fun isSpeedKey(key: String?) = key == SPEED

    /** Shows a speed as "0.8×", "1×", "1.25×". */
    fun speedLabel(speed: Float): String =
        java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "×"

    /** Open the app on the watch when playback starts. */
    fun autoOpenWatch(context: Context) = prefs(context).getBoolean(AUTO_OPEN_WATCH, true)
    fun setAutoOpenWatch(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(AUTO_OPEN_WATCH, value).apply()

    /** The lessons folder picked by the user (a document tree URI). */
    fun lessonsFolder(context: Context): Uri? =
        prefs(context).getString(LESSONS_FOLDER, null)?.let(Uri::parse)
    fun setLessonsFolder(context: Context, uri: Uri) =
        prefs(context).edit().putString(LESSONS_FOLDER, uri.toString()).apply()

    // ---- Position of each lesson, to continue where the user stopped ----

    private fun positions(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    /** Where the user stopped in the lesson at [path] (0 if never played or finished). */
    fun lessonPosition(context: Context, path: String): Long = positions(context).getLong(path, 0)

    fun setLessonPosition(context: Context, path: String, positionMs: Long) {
        val edit = positions(context).edit()
        if (positionMs > 0) edit.putLong(path, positionMs) else edit.remove(path)
        edit.apply()
    }

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
