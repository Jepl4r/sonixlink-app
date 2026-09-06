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
    /**
     * What goes on the left when there is no artwork: the section's icon, or
     * nothing. Artists, album artists and playlists have no picture, and one
     * icon repeated down the whole list is just noise.
     */
    var leading: Leading = Leading.ICON,
    // The position comes with the row: in the queue the same track can appear
    // twice, and looking it up by content would land on the first.
    private val onClick: (Int, Row) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_HEADER = 1
    }

    enum class Leading { ICON, NONE }

    private val items = ArrayList<Row>()

    /**
     * The strip's letters and where each begins, worked out once when the list
     * arrives. Deriving them row by row while a finger drags meant walking six
     * thousand titles on every movement.
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
     * Repaints rows already bound. For when a colour changes and the data does
     * not: the playing track's title carries the accent, and a bound row keeps
     * the old one until it comes back through here.
     */
    @Suppress("NotifyDataSetChanged")
    fun repaint() {
        notifyDataSetChanged()
    }

    fun submit(rows: List<Row>) {
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
        views.chevron.visibility = if (row.isTrack) View.GONE else View.VISIBLE

        // The thumbnails are ready and small: reading one costs a primary-key
        // query and no decoding.
        val covers = Session.covers
        val cover = when {
            covers == null -> null
            // A track has its own thumbnail only if the player has actually
            // drawn that row at least once. Failing that, its album's does
            // just as well: it is the same picture.
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
                    // A list that mixes kinds -- the search results -- says
                    // row by row which icon it wants.
                    row.iconRes != 0 -> row.iconRes
                    row.isTrack -> R.drawable.ic_track
                    else -> categoryIcon
                }
            )
            // With neither artwork nor icon the square goes too: the title
            // starts at the margin, as in a list of names.
            views.artwork.visibility = if (leading == Leading.ICON) View.VISIBLE else View.GONE
        }

        val subtitle = when {
            row.subtitle.isNotEmpty() -> row.subtitle
            !row.isTrack && row.count > 0 -> context.getString(R.string.tracks_count, row.count)
            else -> ""
        }
        views.subtitle.text = subtitle
        views.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

        val playing = highlightPath.isNotEmpty() && row.path == highlightPath
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
    }
}
