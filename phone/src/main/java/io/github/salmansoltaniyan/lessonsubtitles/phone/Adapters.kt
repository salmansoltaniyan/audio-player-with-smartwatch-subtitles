package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

private fun ViewGroup.inflate(layout: Int): View =
    LayoutInflater.from(context).inflate(layout, this, false)

/** Shows "▶ name" in bold for the lesson that is playing. */
private fun TextView.showLessonName(name: String, playing: Boolean) {
    text = if (playing) "▶ $name" else name
    setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
}

// ---------------------------------------------------------------------------------------------
// Library: lessons grouped by sub folder

class LibraryAdapter(
    private val onClick: (LessonFile) -> Unit,
    private val onLongClick: (LessonFile) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val folder: String) : Row()
        data class Item(val file: LessonFile) : Row()
    }

    private var rows: List<Row> = emptyList()
    var selecting = false
    var selected: Set<String> = emptySet()
    var playingPath: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(files: List<LessonFile>) {
        val list = mutableListOf<Row>()
        var folder: String? = null
        for (file in files) {
            if (file.folder != folder) {
                folder = file.folder
                list += Row.Header(folder)
            }
            list += Row.Item(file)
        }
        rows = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(
            parent.inflate(if (viewType == 0) R.layout.item_header else R.layout.item_lesson)
        ) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = rows[position]) {
            is Row.Header -> v.findViewById<TextView>(R.id.headerText).text =
                row.folder.ifEmpty { v.context.getString(R.string.root_folder) }

            is Row.Item -> {
                val file = row.file
                v.findViewById<TextView>(R.id.lessonName).showLessonName(file.title, file.relPath == playingPath)
                v.findViewById<TextView>(R.id.lessonNote).apply {
                    visibility = if (file.jsonUri == null) View.VISIBLE else View.GONE
                    setText(R.string.no_subtitles)
                }
                v.findViewById<CheckBox>(R.id.lessonCheck).apply {
                    visibility = if (selecting) View.VISIBLE else View.GONE
                    isChecked = file.relPath in selected
                }
                v.setOnClickListener { onClick(file) }
                v.setOnLongClickListener { onLongClick(file); true }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The list of playlists

class PlaylistsAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Playlist> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(playlists: List<Playlist>) {
        items = playlists
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(parent.inflate(R.layout.item_playlist)) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val playlist = items[position]
        val v = holder.itemView
        v.findViewById<TextView>(R.id.playlistName).text = playlist.name
        v.findViewById<TextView>(R.id.playlistCount).text =
            v.resources.getQuantityString(R.plurals.lesson_count, playlist.lessons.size, playlist.lessons.size)
        v.setOnClickListener { onClick(playlist) }
        v.setOnLongClickListener { onLongClick(playlist); true }
    }
}

// ---------------------------------------------------------------------------------------------
// The lessons of one playlist, with drag to reorder

class PlaylistLessonsAdapter(
    private val resolve: (String) -> LessonFile?,
    private val onClick: (index: Int) -> Unit,
    private val onRemove: (index: Int) -> Unit,
    /** Called with the new order when a drag ends. */
    private val onReordered: (List<String>) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val paths = mutableListOf<String>()
    var playingPath: String? = null
    private var moved = false

    val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            from: RecyclerView.ViewHolder,
            to: RecyclerView.ViewHolder,
        ): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            paths.add(b, paths.removeAt(a))
            notifyItemMoved(a, b)
            moved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        // Drag only starts from the handle; long press is used for other things in this list's panel.
        override fun isLongPressDragEnabled() = false

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (moved) {
                moved = false
                onReordered(paths.toList())
            }
        }
    })

    @SuppressLint("NotifyDataSetChanged")
    fun submit(lessons: List<String>) {
        paths.clear()
        paths.addAll(lessons)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    override fun getItemCount() = paths.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(parent.inflate(R.layout.item_playlist_lesson)) {}

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val path = paths[position]
        val file = resolve(path)
        val v = holder.itemView
        val note = v.findViewById<TextView>(R.id.lessonNote)
        v.findViewById<TextView>(R.id.lessonName)
            .showLessonName(file?.title ?: path.substringAfterLast('/'), path == playingPath)
        when {
            file == null -> {
                note.visibility = View.VISIBLE
                note.text = v.context.getString(R.string.file_not_found, path)
            }
            file.folder.isNotEmpty() || file.jsonUri == null -> {
                note.visibility = View.VISIBLE
                note.text = listOfNotNull(
                    file.folder.ifEmpty { null },
                    if (file.jsonUri == null) v.context.getString(R.string.no_subtitles) else null,
                ).joinToString(" · ")
            }
            else -> note.visibility = View.GONE
        }
        v.alpha = if (file == null) 0.5f else 1f
        v.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        v.findViewById<View>(R.id.removeButton).setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onRemove)
        }
        v.findViewById<View>(R.id.dragHandle).setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
            false
        }
    }
}
