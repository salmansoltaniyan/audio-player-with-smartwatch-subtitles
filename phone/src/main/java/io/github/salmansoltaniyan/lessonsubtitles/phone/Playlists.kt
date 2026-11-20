package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user playlist. Stored as "<lessons folder>/playlists/<name>.json":
 * {"name": "...", "lessons": ["gang11/lesson.mp3", ...]} with paths relative to the lessons folder.
 */
data class Playlist(
    val name: String,
    val lessons: List<String>,
    /** The playlist's file, null until it is first saved. */
    val docUri: Uri? = null,
)

object Playlists {

    suspend fun load(context: Context, treeUri: Uri): List<Playlist> = withContext(Dispatchers.IO) {
        val dirId = findDir(context, treeUri, create = false) ?: return@withContext emptyList()
        Library.children(context, treeUri, dirId)
            .filter { !it.isDir && Library.extension(it.name) == "json" }
            .mapNotNull { entry ->
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
                try {
                    val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    val o = JSONObject(json)
                    val array = o.optJSONArray("lessons") ?: JSONArray()
                    Playlist(
                        name = o.optString("name", "").ifEmpty { Library.baseName(entry.name) },
                        lessons = (0 until array.length()).map { array.getString(it) },
                        docUri = uri,
                    )
                } catch (_: Exception) {
                    null // not a playlist file
                }
            }
            .sortedWith(compareBy(NaturalOrder) { it.name })
    }

    /** Writes the playlist file (creating it if needed) and returns the playlist with its file. */
    suspend fun save(context: Context, treeUri: Uri, playlist: Playlist): Playlist = withContext(Dispatchers.IO) {
        val uri = playlist.docUri ?: run {
            val dirId = findDir(context, treeUri, create = true)!!
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
            DocumentsContract.createDocument(
                context.contentResolver, dirUri, "application/json", fileNameFor(playlist.name),
            ) ?: error("Could not create the playlist file")
        }
        val json = JSONObject()
            .put("name", playlist.name)
            .put("lessons", JSONArray(playlist.lessons))
            .toString(1)
        val stream = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            context.contentResolver.openOutputStream(uri, "w")
        }
        stream!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        playlist.copy(docUri = uri)
    }

    suspend fun rename(context: Context, treeUri: Uri, playlist: Playlist, newName: String): Playlist {
        val saved = save(context, treeUri, playlist.copy(name = newName))
        val newUri = withContext(Dispatchers.IO) {
            try {
                DocumentsContract.renameDocument(context.contentResolver, saved.docUri!!, fileNameFor(newName))
            } catch (_: Exception) {
                null // the name inside the file is what counts; the file name is only for people
            }
        }
        return saved.copy(docUri = newUri ?: saved.docUri)
    }

    suspend fun delete(context: Context, playlist: Playlist) = withContext(Dispatchers.IO) {
        playlist.docUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
    }

    private fun fileNameFor(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "playlist" } + ".json"

    private fun findDir(context: Context, treeUri: Uri, create: Boolean): String? {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        Library.children(context, treeUri, rootId)
            .firstOrNull { it.isDir && it.name.equals(Library.PLAYLIST_DIR, ignoreCase = true) }
            ?.let { return it.id }
        if (!create) return null
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val created = DocumentsContract.createDocument(
            context.contentResolver, rootUri, Document.MIME_TYPE_DIR, Library.PLAYLIST_DIR,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }
}
