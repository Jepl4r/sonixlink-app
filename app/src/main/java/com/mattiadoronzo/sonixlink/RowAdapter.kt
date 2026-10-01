package com.mattiadoronzo.sonixlink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mattiadoronzo.sonixlink.databinding.RowHeaderBinding
import com.mattiadoronzo.sonixlink.databinding.RowItemBinding

/**
 * One row for everything: a track and a category differ in artwork, icon,
 * subtitle and chevron, not in structure.
 */
class RowAdapter(
    private val categoryIcon: Int,
    /** What goes on the left when there is no artwork: the section's icon, or nothing. */
    var leading: Leading = Leading.ICON,
    // Receives the position too: in the queue the same track can appear twice,
    // so a lookup by content would land on the first.
    private val onClick: (Int, Row) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_HEADER = 1
    }

    enum class Leading { ICON, NONE }

    private val items = ArrayList<Row>()

    /** The row at [position], or null when out of range. */
    fun rowAt(position: Int): Row? = items.getOrNull(position)

    val rowCount: Int get() = items.size

    // -----------------------------------------------------------------------
    // Selection mode
    // -----------------------------------------------------------------------

    // As on the player: a long press starts it, a tap toggles a row, and a
    // chosen row shows a tick in place of its chevron. Selections are list
    // positions; the list's owner decides what each one stands for.

    /** Called on a long press, with the row's position. Null: no selection here. */
    var onLongPress: ((Int, Row) -> Unit)? = null

    var selecting = false
        private set
    private val chosen = LinkedHashSet<Int>()

    val selected: List<Int> get() = chosen.sorted()

    @Suppress("NotifyDataSetChanged")
    fun startSelection(first: Int) {
        selecting = true
        chosen.clear()
        if (first in items.indices) chosen.add(first)
        notifyDataSetChanged()
    }

    @Suppress("NotifyDataSetChanged")
    fun stopSelection() {
        selecting = false
        chosen.clear()
        notifyDataSetChanged()
    }

    /** Chooses the row or lets it go. Returns how many are chosen now. */
    fun toggle(position: Int): Int {
        if (!chosen.remove(position)) chosen.add(position)
        notifyItemChanged(position)
        return chosen.size
    }

    /** Takes rows out of the list (a removal the player has confirmed). */
    @Suppress("NotifyDataSetChanged")
    fun removeAt(positions: Collection<Int>) {
        positions.sortedDescending().forEach { if (it in items.indices) items.removeAt(it) }
        chosen.clear()
        notifyDataSetChanged()
    }

    /**
     * The strip's letters and the position where each begins, computed once in
     * [submit] so a drag along the strip never walks the list.
     */
    private val letterAt = LinkedHashMap<Char, Int>()

    /** The playing row, highlighted. Empty when no row is. */
    var highlightPath: String = ""
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /**
     * The playing track, for the playmark. A track row is marked when its path
     * is this one's; a category row when [marksCategory] says it holds it.
     * Left empty in the queue, which marks by [highlightIndex] and
     * [highlightPath] instead.
     */
    var playing: PlayerState = PlayerState.EMPTY
        set(value) {
            val old = field
            field = value
            if (old.path != value.path || old.album != value.album || old.artist != value.artist ||
                old.albumArtist != value.albumArtist
            ) {
                notifyItemRangeChanged(0, items.size)
            }
        }

    /** Whether a category row holds the playing track: an album by name, an artist by artist. */
    var marksCategory: ((Row, PlayerState) -> Boolean)? = null

    /**
     * Rebinds every row, for when the accent changes and the data does not:
     * the playing track's title and the playmark are drawn in the accent.
     */
    @Suppress("NotifyDataSetChanged")
    fun repaint() {
        notifyDataSetChanged()
    }

    /**
     * The playing row by its place in the list, for a list where the same
     * track can appear twice (the queue). -1 leaves it to [highlightPath].
     */
    var highlightIndex: Int = -1
        set(value) {
            if (field != value) {
                val old = field
                field = value
                if (old in items.indices) notifyItemChanged(old)
                if (value in items.indices) notifyItemChanged(value)
            }
        }

    /**
     * Swaps in the rows from `at` onwards, leaving the rest and the scroll
     * where they are: the queue fills its placeholders page by page this way.
     */
    fun replace(at: Int, rows: List<Row>) {
        if (at < 0 || at >= items.size) return
        val end = minOf(items.size, at + rows.size)
        for (i in at until end) {
            items[i] = rows[i - at]
        }
        notifyItemRangeChanged(at, end - at)
    }

    fun submit(rows: List<Row>) {
        // Positions chosen in the old rows mean nothing in the new ones.
        selecting = false
        chosen.clear()
        items.clear()
        items.addAll(rows)
        letterAt.clear()
        for ((at, row) in items.withIndex()) {
            if (row.kind != Row.Kind.ROW || row.sortKey.isEmpty()) continue
            val letter = Letters.ofSortKey(row.sortKey)
            if (letter !in letterAt) {
                letterAt[letter] = at
            }
        }
        notifyDataSetChanged()
    }

    /** Rebinds all rows when thumbnails arrive from the player. */
    private val coversArrived: () -> Unit = { notifyItemRangeChanged(0, items.size) }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        Covers.watch(coversArrived)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        Covers.unwatch(coversArrived)
    }

    class Holder(val views: RowItemBinding) : RecyclerView.ViewHolder(views.root)

    class HeaderHolder(val views: RowHeaderBinding) : RecyclerView.ViewHolder(views.root)

    override fun getItemViewType(position: Int): Int =
        if (items[position].kind == Row.Kind.HEADER) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(RowHeaderBinding.inflate(inflater, parent, false))
        } else {
            Holder(RowItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    /** The first row under that letter, -1 when there is none. */
    fun firstStartingWith(letter: Char): Int = letterAt[letter] ?: -1

    /** The letters present, in the order the list meets them. */
    fun letters(): List<Char> = letterAt.keys.toList()

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = items[position]
        if (holder is HeaderHolder) {
            holder.views.headerLabel.text = row.title
            return
        }
        if (holder !is Holder) return
        val views = holder.views
        val context = views.root.context

        views.title.text = row.title
        val isChosen = selecting && position in chosen
        views.chevron.visibility =
            if (selecting || row.isTrack || row.kind == Row.Kind.PENDING) View.GONE else View.VISIBLE
        views.check.visibility = if (isChosen) View.VISIBLE else View.GONE
        if (isChosen) {
            views.check.imageTintList = android.content.res.ColorStateList.valueOf(Session.accent)
            views.root.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.surface)
            )
        } else {
            // Back to the ripple the layout gives it.
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            views.root.setBackgroundResource(ripple.resourceId)
        }

        // Read on the main thread: a thumbnail is raw pixels behind a
        // primary-key query, with no decoding.
        val covers = Session.covers
        val cover = when {
            covers == null -> null
            // A track has its own thumbnail only once the player has drawn
            // that row; until then the album's cover stands in.
            row.isTrack -> covers.forTrack(row.artPath, row.artMtime, row.artSize)
                ?: covers.forAlbum(row.album)
            row.album.isNotEmpty() -> covers.forAlbum(row.album)
            else -> null
        }
        if (cover != null) {
            views.cover.setImageBitmap(cover)
            views.cover.visibility = View.VISIBLE
            views.icon.visibility = View.GONE
            views.artwork.visibility = View.VISIBLE
        } else {
            views.cover.setImageDrawable(null)
            views.cover.visibility = View.GONE
            views.icon.visibility = if (leading == Leading.ICON) View.VISIBLE else View.GONE
            views.icon.setImageResource(
                when {
                    // Lists that mix kinds, like search results, set the icon per row.
                    row.iconRes != 0 -> row.iconRes
                    row.isTrack -> R.drawable.ic_track
                    else -> categoryIcon
                }
            )
            // With neither artwork nor icon the square goes too, and the title
            // starts at the margin.
            views.artwork.visibility = if (leading == Leading.ICON) View.VISIBLE else View.GONE
        }

        val subtitle = when {
            row.subtitle.isNotEmpty() -> row.subtitle
            !row.isTrack && row.count > 0 -> context.getString(R.string.tracks_count, row.count)
            else -> ""
        }
        views.subtitle.text = subtitle
        views.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

        val playing = if (highlightIndex >= 0) {
            position == highlightIndex
        } else {
            highlightPath.isNotEmpty() && row.path == highlightPath
        }
        val marked = playing || when {
            row.kind != Row.Kind.ROW -> false
            row.isTrack -> this.playing.path.isNotEmpty() && row.path == this.playing.path
            else -> this.playing.hasTrack && marksCategory?.invoke(row, this.playing) == true
        }
        if (marked) {
            views.playmark.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * context.resources.displayMetrics.density
                setColor(Session.accent)
            }
            views.playmark.visibility = View.VISIBLE
        } else {
            views.playmark.visibility = View.GONE
        }
        views.title.setTextColor(
            if (playing) {
                Session.accent
            } else {
                androidx.core.content.ContextCompat.getColor(context, R.color.text_primary)
            }
        )

        views.root.setOnClickListener {
            val at = holder.bindingAdapterPosition
            if (at != RecyclerView.NO_POSITION) {
                onClick(at, items[at])
            }
        }
        val longPress = onLongPress
        if (longPress == null) {
            views.root.setOnLongClickListener(null)
            views.root.isLongClickable = false
        } else {
            views.root.setOnLongClickListener {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) {
                    longPress(at, items[at])
                }
                true
            }
        }
    }
}
