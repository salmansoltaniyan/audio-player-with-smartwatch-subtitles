package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context

/** User settings that survive app restarts. */
object Prefs {
    private const val REPEAT_LESSON = "repeat_lesson"
    private const val AUTO_OPEN_WATCH = "auto_open_watch"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Start the lesson again from the beginning when it ends. */
    fun repeatLesson(context: Context) = prefs(context).getBoolean(REPEAT_LESSON, false)
    fun setRepeatLesson(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(REPEAT_LESSON, value).apply()

    /** Open the app on the watch when playback starts. */
    fun autoOpenWatch(context: Context) = prefs(context).getBoolean(AUTO_OPEN_WATCH, true)
    fun setAutoOpenWatch(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(AUTO_OPEN_WATCH, value).apply()
}
