package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One audio file in the lessons folder. Its timeline is either inside the file (ID3 tag, see
 * [Id3Timeline]) or in a JSON file next to it.
 */
data class LessonFile(
    /** Path relative to the lessons folder, e.g. "gang11/gang11_output9.mp3". Used as the id. */
    val relPath: String,
    val fileName: String,
    /** Sub folder relative to the lessons folder, "" for the folder itself. */
    val folder: String,
    val audioUri: Uri,
    val jsonUri: Uri?,
    /** The file has a timeline tag inside it. Only checked for files without a JSON file. */
    val hasTag: Boolean = false,
) {
    val title: String get() = fileName.substringBeforeLast('.')
    val hasTimeline: Boolean get() = hasTag || jsonUri != null
}

/** The lessons being played, in order. */
data class PlayQueue(
    /** "playlist:<name>" or "folder:<sub folder>"; saved to restore the queue later. */
    val id: String,
    val title: String,
    val items: List<LessonFile>,
)

/** Sorts "gang9" before "gang11" (numbers are compared as numbers). */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** Finds the lessons in the lessons folder and all its sub folders. */
object Library {
    /** Sub folder of the lessons folder where playlists are stored; not scanned for lessons. */
    const val PLAYLIST_DIR = "playlists"

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")

    internal data class Entry(val id: String, val name: String, val isDir: Boolean)

    suspend fun scan(context: Context, treeUri: Uri): List<LessonFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<LessonFile>()
        walk(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", result)
        result.sortedWith(compareBy(NaturalOrder) { it.relPath })
    }

    internal fun children(context: Context, treeUri: Uri, docId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )
        val entries = mutableListOf<Entry>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                entries += Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR)
            }
        }
        return entries
    }

    internal fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    internal fun baseName(name: String) = name.substringBeforeLast('.')

    private fun walk(context: Context, treeUri: Uri, docId: String, relDir: String, out: MutableList<LessonFile>) {
        val entries = children(context, treeUri, docId)
        val files = entries.filter { !it.isDir }
        val audios = files.filter { extension(it.name) in AUDIO_EXTENSIONS }
        val jsons = files.filter { extension(it.name) == "json" }

        // Rule 1: same name, e.g. lesson.mp3 + lesson.json.
        val jsonByBase = jsons.associateBy { baseName(it.name).lowercase() }
        val pairs = HashMap<String, Entry>() // audio id -> json
        for (audio in audios) {
            jsonByBase[baseName(audio.name).lowercase()]?.let { pairs[audio.id] = it }
        }

        // Rule 2: a JSON whose "audio" field names the file (the JSON was renamed).
        val unpaired = audios.filter { it.id !in pairs }
        if (unpaired.isNotEmpty()) {
            val used = pairs.values.map { it.id }.toSet()
            for (json in jsons.filter { it.id !in used }) {
                val audioName = readAudioField(context, treeUri, json.id) ?: continue
                val audio = unpaired.firstOrNull { it.name.equals(audioName, ignoreCase = true) } ?: continue
                if (audio.id !in pairs) pairs[audio.id] = json
            }
        }

        for (audio in audios) {
            val audioUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, audio.id)
            val json = pairs[audio.id]
            out += LessonFile(
                relPath = relDir + audio.name,
                fileName = audio.name,
                folder = relDir.trimEnd('/'),
                audioUri = audioUri,
                jsonUri = json?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) },
                // Rule 0 (checked when playing, before the JSON): the timeline inside the MP3.
                hasTag = json == null && Id3Timeline.has(context, audioUri),
            )
        }

        for (dir in entries.filter { it.isDir }) {
            if (dir.name.startsWith(".")) continue
            if (relDir.isEmpty() && dir.name.equals(PLAYLIST_DIR, ignoreCase = true)) continue
            walk(context, treeUri, dir.id, relDir + dir.name + "/", out)
        }
    }

    private fun readAudioField(context: Context, treeUri: Uri, jsonId: String): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, jsonId)
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            JSONObject(reader.readText()).optString("audio", "").ifEmpty { null }
        }
    } catch (_: Exception) {
        null
    }
}
