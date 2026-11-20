package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.net.Uri
import org.json.JSONObject

/** One spoken line of the lesson. Times are in seconds from the start of the MP3. */
data class Segment(
    val start: Double,
    val speechEnd: Double,
    val end: Double,
    val row: Int,
    val tag: String,
    val lang: String,
    val text: String,
)

/** An MP3 file plus its JSON timeline. */
data class Lesson(
    val audioUri: Uri,
    val title: String,
    /** The "audio" field of the JSON: the MP3 file name the timeline was made for. */
    val audioNameInJson: String,
    val durationSec: Double,
    val segments: List<Segment>,
) {
    /**
     * Index of the segment to show at [seconds]: the last segment whose start is <= [seconds].
     * A segment stays on screen until the next one starts (also during the pause after it).
     * Returns -1 before the first segment.
     */
    fun indexAt(seconds: Double): Int {
        var lo = 0
        var hi = segments.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].start <= seconds) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        fun parse(audioUri: Uri, title: String, json: String): Lesson {
            val root = JSONObject(json)
            val array = root.getJSONArray("segments")
            val segments = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val end = o.optDouble("end", 0.0)
                Segment(
                    start = o.getDouble("start"),
                    speechEnd = o.optDouble("speech_end", end),
                    end = end,
                    row = o.optInt("row", 0),
                    tag = o.optString("tag", ""),
                    lang = o.optString("lang", "dutch"),
                    text = o.optString("text", ""),
                )
            }.sortedBy { it.start }
            require(segments.isNotEmpty()) { "The JSON file has no segments." }
            return Lesson(
                audioUri = audioUri,
                title = title,
                audioNameInJson = root.optString("audio", ""),
                durationSec = root.optDouble("duration", segments.last().end),
                segments = segments,
            )
        }
    }
}
