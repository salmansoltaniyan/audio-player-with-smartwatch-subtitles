package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Playlists tab. Shows the list of playlists, or the lessons of one open playlist
 * (drag to reorder, ✕ to remove, tap to play from there).
 */
class PlaylistsPanel(private val activity: MainActivity, root: View) {

    private val backButton: View = root.findViewById(R.id.playlistBack)
    private val title: TextView = root.findViewById(R.id.playlistsTitle)
    private val actionButton: Button = root.findViewById(R.id.playlistAction)
    private val hint: TextView = root.findViewById(R.id.playlistsHint)
    private val list: RecyclerView = root.findViewById(R.id.playlistsList)

    /** Name of the open playlist, or null when the list of playlists is shown. */
    private var openName: String? = null
    private var libraryByPath: Map<String, LessonFile> = emptyMap()

    private val listAdapter = PlaylistsAdapter(
        onClick = { open(it) },
        onLongClick = { showOptions(it) },
    )
    private val lessonsAdapter = PlaylistLessonsAdapter(
        resolve = { libraryByPath[it] },
        onClick = { playFrom(it) },
        onRemove = { removeAt(it) },
        onReordered = { paths -> current?.let { save(it, it.copy(lessons = paths)) } },
    )

    private val current: Playlist?
        get() = openName?.let { name -> LessonStore.playlists.value.firstOrNull { it.name == name } }

    init {
        list.layoutManager = LinearLayoutManager(activity)
        lessonsAdapter.touchHelper.attachToRecyclerView(list)
        backButton.setOnClickListener { close() }
        actionButton.setOnClickListener {
            val playlist = current
            if (playlist == null) createPlaylist(emptyList()) else play(playlist, null)
        }
        activity.lifecycleScope.launch {
            combine(LessonStore.playlists, LessonStore.library) { _, _ -> }.collect { render() }
        }
    }

    fun render() {
        libraryByPath = LessonStore.library.value.associateBy { it.relPath }
        val playlist = current
        if (playlist == null) {
            openName = null
            backButton.visibility = View.GONE
            title.setText(R.string.tab_playlists)
            actionButton.setText(R.string.new_playlist)
            hint.text = activity.getString(
                when {
                    activity.lessonsFolder == null -> R.string.playlists_hint_no_folder
                    LessonStore.playlists.value.isEmpty() -> R.string.playlists_hint_empty
                    else -> R.string.playlists_hint
                }
            )
            if (list.adapter !== listAdapter) list.adapter = listAdapter
            listAdapter.submit(LessonStore.playlists.value)
        } else {
            backButton.visibility = View.VISIBLE
            title.text = playlist.name
            actionButton.setText(R.string.play)
            hint.text = activity.getString(
                if (playlist.lessons.isEmpty()) R.string.playlist_hint_empty else R.string.playlist_hint
            )
            if (list.adapter !== lessonsAdapter) list.adapter = lessonsAdapter
            lessonsAdapter.submit(playlist.lessons)
        }
    }

    fun setPlaying(path: String?) {
        if (lessonsAdapter.playingPath == path) return
        lessonsAdapter.playingPath = path
        if (openName != null) lessonsAdapter.refresh()
    }

    /** Returns true if the back press was used (to close the open playlist). */
    fun onBack(): Boolean {
        if (openName == null) return false
        close()
        return true
    }

    private fun open(playlist: Playlist) {
        openName = playlist.name
        render()
    }

    private fun close() {
        openName = null
        render()
    }

    private fun play(playlist: Playlist, startPath: String?) =
        activity.playQueue(activity.playlistQueue(playlist), startPath)

    private fun playFrom(index: Int) {
        val playlist = current ?: return
        val path = playlist.lessons.getOrNull(index) ?: return
        if (path !in libraryByPath) {
            activity.toast(activity.getString(R.string.file_not_found, path))
            return
        }
        play(playlist, path)
    }

    private fun removeAt(index: Int) {
        val playlist = current ?: return
        if (index !in playlist.lessons.indices) return
        save(playlist, playlist.copy(lessons = playlist.lessons.toMutableList().apply { removeAt(index) }))
    }

    // ---- Creating, adding, renaming, deleting ----

    /** Library tab: add [paths] to a playlist the user picks (or a new one). */
    fun chooseAndAdd(paths: List<String>, onDone: () -> Unit) {
        if (activity.lessonsFolder == null) return
        val playlists = LessonStore.playlists.value
        val names = playlists.map { it.name } + activity.getString(R.string.new_playlist_item)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    createPlaylist(paths, onDone)
                } else {
                    val playlist = playlists[which]
                    val toAdd = paths.filter { it !in playlist.lessons }
                    save(playlist, playlist.copy(lessons = playlist.lessons + toAdd)) {
                        activity.toast(
                            activity.resources.getQuantityString(
                                R.plurals.added_to_playlist, toAdd.size, toAdd.size, playlist.name,
                            )
                        )
                        onDone()
                    }
                }
            }
            .show()
    }

    private fun createPlaylist(lessons: List<String>, onDone: () -> Unit = {}) {
        if (activity.lessonsFolder == null) {
            activity.toast(activity.getString(R.string.playlists_hint_no_folder))
            return
        }
        activity.promptText(R.string.new_playlist, "") { name ->
            if (nameTaken(name, except = null)) return@promptText
            save(null, Playlist(name, lessons)) {
                activity.toast(activity.getString(R.string.playlist_created, name))
                onDone()
            }
        }
    }

    private fun showOptions(playlist: Playlist) {
        val options = arrayOf(activity.getString(R.string.rename), activity.getString(R.string.delete))
        MaterialAlertDialogBuilder(activity)
            .setTitle(playlist.name)
            .setItems(options) { _, which -> if (which == 0) rename(playlist) else confirmDelete(playlist) }
            .show()
    }

    private fun rename(playlist: Playlist) {
        val folder = activity.lessonsFolder ?: return
        activity.promptText(R.string.rename, playlist.name) { name ->
            if (name == playlist.name || nameTaken(name, except = playlist)) return@promptText
            activity.lifecycleScope.launch {
                try {
                    val renamed = Playlists.rename(activity, folder, playlist, name)
                    replace(playlist, renamed)
                } catch (e: Exception) {
                    activity.toast(activity.getString(R.string.playlist_error, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun confirmDelete(playlist: Playlist) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(R.string.delete_confirm, playlist.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.lifecycleScope.launch {
                    try {
                        Playlists.delete(activity, playlist)
                        replace(playlist, null)
                    } catch (e: Exception) {
                        activity.toast(activity.getString(R.string.playlist_error, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun nameTaken(name: String, except: Playlist?): Boolean {
        val taken = LessonStore.playlists.value.any { it !== except && it.name.equals(name, ignoreCase = true) }
        if (taken) activity.toast(activity.getString(R.string.playlist_exists, name))
        return taken
    }

    /** Writes [updated] to its file and puts it in place of [old] (null for a new playlist). */
    private fun save(old: Playlist?, updated: Playlist, onSaved: () -> Unit = {}) {
        val folder = activity.lessonsFolder ?: return
        activity.lifecycleScope.launch {
            try {
                val saved = Playlists.save(activity, folder, updated)
                replace(old, saved)
                onSaved()
            } catch (e: Exception) {
                activity.toast(activity.getString(R.string.playlist_error, e.message ?: e.javaClass.simpleName))
                render() // undo a drag that could not be saved
            }
        }
    }

    private fun replace(old: Playlist?, new: Playlist?) {
        if (old != null && openName == old.name) openName = new?.name
        val others = LessonStore.playlists.value.filter { old == null || it.docUri != old.docUri }
        LessonStore.playlists.value = (others + listOfNotNull(new)).sortedWith(compareBy(NaturalOrder) { it.name })
    }
}
