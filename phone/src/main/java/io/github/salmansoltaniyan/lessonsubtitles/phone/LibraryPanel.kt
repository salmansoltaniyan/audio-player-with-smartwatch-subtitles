package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

/**
 * Library tab: all lessons of the lessons folder, grouped by sub folder.
 * Tap: play that sub folder from this lesson. Long press: select lessons to add to a playlist.
 */
class LibraryPanel(private val activity: MainActivity, root: View) {

    private val folderText: TextView = root.findViewById(R.id.libraryFolder)
    private val hint: TextView = root.findViewById(R.id.libraryHint)
    private val refresh: SwipeRefreshLayout = root.findViewById(R.id.libraryRefresh)
    private val selectionBar: View = root.findViewById(R.id.selectionBar)
    private val selectionCount: TextView = root.findViewById(R.id.selectionCount)
    private val adapter = LibraryAdapter(::onClick, ::onLongClick)
    private val selected = mutableSetOf<String>()

    init {
        root.findViewById<Button>(R.id.chooseFolderButton).setOnClickListener { activity.chooseFolder() }
        root.findViewById<RecyclerView>(R.id.libraryList).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@LibraryPanel.adapter
        }
        refresh.setOnRefreshListener {
            if (activity.lessonsFolder == null) refresh.isRefreshing = false else activity.reloadLibrary()
        }
        root.findViewById<Button>(R.id.addToPlaylistButton).setOnClickListener {
            // Keep the library order.
            val paths = LessonStore.library.value.map { it.relPath }.filter { it in selected }
            activity.playlistsPanel.chooseAndAdd(paths) { endSelection() }
        }
        root.findViewById<Button>(R.id.cancelSelectionButton).setOnClickListener { endSelection() }

        activity.lifecycleScope.launch {
            LessonStore.library.collect { files ->
                selected.retainAll(files.map { it.relPath }.toSet())
                adapter.submit(files)
                refreshHint()
            }
        }
    }

    fun showFolder(uri: Uri?) {
        folderText.text = uri?.let(activity::folderName) ?: activity.getString(R.string.no_folder)
        refreshHint()
    }

    fun setRefreshing(refreshing: Boolean) {
        refresh.isRefreshing = refreshing
    }

    fun setPlaying(path: String?) {
        if (adapter.playingPath == path) return
        adapter.playingPath = path
        adapter.refresh()
    }

    /** Returns true if the back press was used (to leave selection mode). */
    fun onBack(): Boolean {
        if (!adapter.selecting) return false
        endSelection()
        return true
    }

    private fun refreshHint() {
        val count = LessonStore.library.value.size
        hint.text = when {
            activity.lessonsFolder == null -> activity.getString(R.string.library_hint_no_folder)
            count == 0 -> activity.getString(R.string.library_hint_empty)
            else -> activity.resources.getQuantityString(R.plurals.library_hint, count, count)
        }
    }

    private fun onClick(file: LessonFile) {
        if (adapter.selecting) {
            toggle(file)
        } else {
            activity.playQueue(activity.folderQueue(file.folder), file.relPath)
        }
    }

    private fun onLongClick(file: LessonFile) {
        adapter.selecting = true
        toggle(file)
    }

    private fun toggle(file: LessonFile) {
        if (!selected.remove(file.relPath)) selected += file.relPath
        if (selected.isEmpty()) {
            endSelection()
            return
        }
        selectionBar.visibility = View.VISIBLE
        selectionCount.text = activity.getString(R.string.selection_count, selected.size)
        adapter.selected = selected.toSet()
        adapter.refresh()
    }

    private fun endSelection() {
        selected.clear()
        adapter.selecting = false
        adapter.selected = emptySet()
        selectionBar.visibility = View.GONE
        adapter.refresh()
    }
}
