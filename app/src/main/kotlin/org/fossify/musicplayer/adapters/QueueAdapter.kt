package org.fossify.musicplayer.adapters

import android.annotation.SuppressLint
import android.graphics.Paint
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.extensions.*
import org.fossify.commons.views.MyRecyclerView
import org.fossify.musicplayer.R
import org.fossify.musicplayer.activities.SimpleActivity
import org.fossify.musicplayer.databinding.ItemQueueGroupHeaderBinding
import org.fossify.musicplayer.databinding.ItemQueuePauseMarkerBinding
import org.fossify.musicplayer.databinding.ItemTrackQueueBinding
import org.fossify.musicplayer.extensions.*
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.queue.QueueEntry
import java.util.Collections

/**
 * Renders the logical queue. It owns no queue state: [submit] takes the rows derived from the latest
 * `QueueState`, and every user action is handed to the [Listener], which forwards it to the `QueueManager`.
 *
 * The one exception is dragging, which moves rows locally for feedback and reports the final spot on drop.
 */
class QueueAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    private val listener: Listener,
    itemClick: (Any) -> Unit,
) : BaseMusicAdapter<QueueRow>(ArrayList(), activity, recyclerView, itemClick), RecyclerViewFastScroller.OnPopupTextUpdate {

    interface Listener {
        fun onRemoveEntries(entryIds: List<Long>)
        fun onRemoveGroup(groupId: Long)
        fun onShuffleGroup(groupId: Long)

        /** [toUpcomingIndex] is relative to the upcoming entries without the moved one. */
        fun onMoveEntry(entryId: Long, toUpcomingIndex: Int)
        fun onPauseAfter(entryId: Long)
        fun onSetRepeatCount(entry: QueueEntry.TrackEntry)
    }

    private val touchHelper = ItemTouchHelper(DragCallback())

    private var isDragging = false
    private var rowsDuringDrag: Pair<List<QueueRow>, String>? = null
    private var dragStartIndex = -1

    /** Reordering makes no sense on a filtered list. */
    private var isFiltered = false

    init {
        setupDragListener(true)
        touchHelper.attachToRecyclerView(recyclerView)
    }

    override fun getActionMenuId() = R.menu.cab_queue

    override fun getItemViewType(position: Int) = when (items[position]) {
        is QueueRow.TrackRow -> VIEW_TYPE_TRACK
        is QueueRow.PauseRow -> VIEW_TYPE_PAUSE
        is QueueRow.GroupHeaderRow -> VIEW_TYPE_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val root = when (viewType) {
            VIEW_TYPE_PAUSE -> ItemQueuePauseMarkerBinding.inflate(layoutInflater, parent, false).root
            VIEW_TYPE_HEADER -> ItemQueueGroupHeaderBinding.inflate(layoutInflater, parent, false).root
            else -> ItemTrackQueueBinding.inflate(layoutInflater, parent, false).root
        }
        return createViewHolder(root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = items.getOrNull(position) ?: return
        val allowClicks = row !is QueueRow.GroupHeaderRow
        holder.bindView(row, allowSingleClick = allowClicks, allowLongClick = allowClicks) { itemView, _ ->
            when (row) {
                is QueueRow.TrackRow -> setupTrack(itemView, row, holder)
                is QueueRow.PauseRow -> setupPauseMarker(itemView, row)
                is QueueRow.GroupHeaderRow -> setupHeader(itemView, row)
            }
        }
        bindViewHolder(holder)
    }

    // region selection

    // Selection keys must be unique per entry: the same track can be queued more than once, so the
    // default of using the item's hash code would select all copies at once.
    override fun getItemSelectionKey(position: Int) = items.getOrNull(position)?.key?.hashCode()

    override fun getItemKeyPosition(key: Int) = items.indexOfFirst { it.key.hashCode() == key }

    override fun getIsItemSelectable(position: Int) = items.getOrNull(position) !is QueueRow.GroupHeaderRow

    override fun getSelectableItemCount() = items.count { it !is QueueRow.GroupHeaderRow }

    override fun getSelectedItems(): List<QueueRow> = items.filter { selectedKeys.contains(it.key.hashCode()) }

    override fun getSelectedTracks(): List<Track> {
        return getSelectedItems().filterIsInstance<QueueRow.TrackRow>().map { it.entry.track }.distinctBy { it.mediaStoreId }
    }

    private fun getSelectedEntryIds(): List<Long> = getSelectedItems().mapNotNull {
        when (it) {
            is QueueRow.TrackRow -> it.entry.id
            is QueueRow.PauseRow -> it.entry.id
            is QueueRow.GroupHeaderRow -> null
        }
    }

    /** The single selected row, if it hasn't been played yet (or is playing). */
    private fun getSingleSelectedPlayableRow(): QueueRow.TrackRow? {
        if (!isOneItemSelected()) {
            return null
        }
        return (getSelectedItems().firstOrNull() as? QueueRow.TrackRow)?.takeIf { it.section != QueueRow.Section.HISTORY }
    }

    override fun prepareActionMode(menu: Menu) {
        val playable = getSingleSelectedPlayableRow() != null
        menu.apply {
            findItem(R.id.cab_pause_after).isVisible = playable
            findItem(R.id.cab_set_repeat).isVisible = playable
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_remove_from_queue -> {
                listener.onRemoveEntries(getSelectedEntryIds())
                finishActMode()
            }

            R.id.cab_share -> shareFiles()
            R.id.cab_add_to_playlist -> addToPlaylist()
            R.id.cab_select_all -> selectAll()
            R.id.cab_pause_after -> getSingleSelectedPlayableRow()?.let {
                listener.onPauseAfter(it.entry.id)
                finishActMode()
            }

            R.id.cab_set_repeat -> getSingleSelectedPlayableRow()?.let {
                listener.onSetRepeatCount(it.entry)
                finishActMode()
            }
        }
    }

    override fun onActionModeCreated() = notifyDataChanged()

    override fun onActionModeDestroyed() = notifyDataChanged()

    // endregion

    // region updating

    fun submit(rows: List<QueueRow>, highlightText: String = "") {
        if (isDragging) {
            // applied when the drag ends, see onDragFinished()
            rowsDuringDrag = rows to highlightText
            return
        }

        val oldItems = items
        val hadSelection = selectedKeys.isNotEmpty()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = rows.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldItems[oldPos].key == rows[newPos].key

            // rows are data classes, so structural equality covers every rendered property
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = oldItems[oldPos] == rows[newPos]
        })

        items = ArrayList(rows)
        isFiltered = highlightText.isNotBlank()
        val highlightChanged = textToHighlight != highlightText
        textToHighlight = highlightText

        // rows may have disappeared (e.g. removed from elsewhere): forget selections of rows that are gone
        val keys = items.mapTo(HashSet()) { it.key.hashCode() }
        selectedKeys.retainAll(keys)

        diff.dispatchUpdatesTo(this)
        if (highlightChanged) {
            notifyDataChanged()
        }

        if (hadSelection && selectedKeys.isEmpty()) {
            finishActMode()
        }
    }

    fun indexOfRowWithKey(key: Long) = items.indexOfFirst { it.key == key }

    // endregion

    // region binding

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTrack(view: View, row: QueueRow.TrackRow, holder: ViewHolder) {
        val entry = row.entry
        val track = entry.track

        ItemTrackQueueBinding.bind(view).apply {
            root.setupViewBackground(context)
            trackQueueFrame.isSelected = selectedKeys.contains(row.key.hashCode())
            trackQueueFrame.alpha = if (row.section == QueueRow.Section.HISTORY) HISTORY_ALPHA else 1f

            trackQueueTitle.text = if (textToHighlight.isEmpty()) {
                track.title
            } else {
                track.title.highlightTextPart(textToHighlight, properPrimaryColor)
            }

            // skipped history entries are crossed out; an entry with at least one completed repetition counts as played
            val skipped = row.section == QueueRow.Section.HISTORY && entry.wasSkipped
            trackQueueTitle.paintFlags = if (skipped) {
                trackQueueTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                trackQueueTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            val color = if (row.section == QueueRow.Section.CURRENT) activity.getProperPrimaryColor() else textColor
            arrayOf(trackQueueTitle, trackQueueDuration, trackQueueRepeat).forEach { it.setTextColor(color) }

            val repeatLabel = getRepeatLabel(row)
            trackQueueRepeat.text = repeatLabel
            trackQueueRepeat.beVisibleIf(repeatLabel.isNotEmpty())

            trackQueueDuration.text = track.duration.getFormattedDuration()

            // only entries that haven't started can be reordered
            val canDrag = row.section == QueueRow.Section.UPCOMING && !isFiltered
            trackQueueDragHandle.beVisibleIf(selectedKeys.isNotEmpty() && canDrag)
            trackQueueDragHandle.applyColorFilter(textColor)
            trackQueueDragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                false
            }

            context.getTrackCoverArt(track) { coverArt ->
                loadImage(trackQueueImage, coverArt, placeholderBig)
            }
        }
    }

    private fun setupPauseMarker(view: View, row: QueueRow.PauseRow) {
        ItemQueuePauseMarkerBinding.bind(view).apply {
            root.setupViewBackground(context)
            queuePauseFrame.isSelected = selectedKeys.contains(row.key.hashCode())
            queuePauseTitle.setTextColor(textColor)
            queuePauseIcon.applyColorFilter(textColor)
        }
    }

    private fun setupHeader(view: View, row: QueueRow.GroupHeaderRow) {
        ItemQueueGroupHeaderBinding.bind(view).apply {
            val label = row.group?.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.queue_added_together)
            queueGroupTitle.text = "$label · ${row.trackCount}"
            queueGroupTitle.setTextColor(textColor)

            arrayOf(queueGroupShuffle, queueGroupRemove).forEach {
                it.applyColorFilter(textColor)
                it.beVisibleIf(row.hasUpcoming && !isFiltered)
            }

            queueGroupShuffle.setOnClickListener { listener.onShuffleGroup(row.groupId) }
            queueGroupRemove.setOnClickListener { listener.onRemoveGroup(row.groupId) }
        }
    }

    /**
     * - playing: `2/4`, or `2/∞`
     * - waiting: `×3` (what is left to play), or `∞`
     * - played: `×3` if it was played more than once
     */
    private fun getRepeatLabel(row: QueueRow.TrackRow): String {
        val entry = row.entry
        return when (row.section) {
            QueueRow.Section.CURRENT -> when {
                entry.isInfinite -> "${entry.completedRepetitions + 1}/∞"
                (entry.totalRepetitions ?: 1) > 1 -> "${entry.completedRepetitions + 1}/${entry.totalRepetitions}"
                else -> ""
            }

            QueueRow.Section.UPCOMING -> when {
                entry.isInfinite -> "∞"
                (entry.remainingRepetitions ?: 1) > 1 -> "×${entry.remainingRepetitions}"
                else -> ""
            }

            QueueRow.Section.HISTORY -> if (entry.completedRepetitions > 1) "×${entry.completedRepetitions}" else ""
        }
    }

    override fun onChange(position: Int): String {
        val row = items.getOrNull(position) as? QueueRow.TrackRow ?: return ""
        return row.entry.track.getBubbleText(context.config.trackSorting)
    }

    // endregion

    // region dragging

    private fun isUpcomingEntryRow(row: QueueRow?) = (row is QueueRow.TrackRow && row.section == QueueRow.Section.UPCOMING)

    /**
     * Rows that can be dragged over: everything after the last history/current track row. That includes group
     * headers in that stretch, so an entry can be dragged across a group boundary.
     */
    private fun isInUpcomingRegion(position: Int): Boolean {
        val lastPlayed = items.indexOfLast {
            it is QueueRow.TrackRow && it.section != QueueRow.Section.UPCOMING
        }
        return position > lastPlayed
    }

    /** Number of upcoming entries (tracks and pause markers) in front of the row at [position]. */
    private fun upcomingIndexOf(position: Int) = items.subList(0, position).count {
        isUpcomingEntryRow(it) || it is QueueRow.PauseRow
    }

    private fun onDragFinished(viewHolder: RecyclerView.ViewHolder) {
        val position = viewHolder.bindingAdapterPosition
        val row = items.getOrNull(position)
        if (row is QueueRow.TrackRow && dragStartIndex >= 0) {
            val newIndex = upcomingIndexOf(position)
            if (newIndex != dragStartIndex) {
                listener.onMoveEntry(row.entry.id, newIndex)
            }
        }

        isDragging = false
        dragStartIndex = -1
        rowsDuringDrag?.let { (rows, highlight) -> submit(rows, highlight) }
        rowsDuringDrag = null
    }

    private inner class DragCallback : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false

        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val row = items.getOrNull(viewHolder.bindingAdapterPosition)
            val flags = if (isUpcomingEntryRow(row) && !isFiltered) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
            return makeMovementFlags(flags, 0)
        }

        override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            return isInUpcomingRegion(target.bindingAdapterPosition)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                isDragging = true
                dragStartIndex = upcomingIndexOf(viewHolder.bindingAdapterPosition)
            }
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false
            }

            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            onDragFinished(viewHolder)
        }
    }

    // endregion

    private companion object {
        const val VIEW_TYPE_TRACK = 0
        const val VIEW_TYPE_PAUSE = 1
        const val VIEW_TYPE_HEADER = 2
        const val HISTORY_ALPHA = 0.5f
    }
}
