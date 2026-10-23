package org.fossify.musicplayer.activities

import android.app.SearchManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.musicplayer.R
import org.fossify.musicplayer.adapters.QueueAdapter
import org.fossify.musicplayer.adapters.QueueRow
import org.fossify.musicplayer.adapters.toRows
import org.fossify.musicplayer.databinding.ActivityQueueBinding
import org.fossify.musicplayer.dialogs.NewPlaylistDialog
import org.fossify.musicplayer.dialogs.QueueRepeatDialog
import org.fossify.musicplayer.extensions.*
import org.fossify.musicplayer.helpers.RoomHelper
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.PlaybackService
import org.fossify.musicplayer.playback.queue.QueueEntry
import org.fossify.musicplayer.playback.queue.QueueState

/**
 * Shows the logical queue (history, the active entry, upcoming) and sends the user's intents to the queue controller.
 * It never looks at the player timeline and never edits its own list: every change goes through the `QueueManager`
 * and comes back as a new [QueueState].
 */
class QueueActivity : SimpleControllerActivity(), QueueAdapter.Listener {
    private var searchMenuItem: MenuItem? = null
    private var isSearchOpen = false
    private var searchQuery = ""

    private var latestState: QueueState? = null
    private var scrolledToCurrent = false

    private val binding by viewBinding(ActivityQueueBinding::inflate)

    private val queue get() = PlaybackService.queueController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.queueList))
        setupMaterialScrollListener(binding.queueList, binding.queueAppbar)

        setupAdapter()
        binding.queueFastscroller.updateColors(getProperPrimaryColor())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackService.queueState.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.queueAppbar, NavigationIcon.Arrow, searchMenuItem = searchMenuItem)
    }

    override fun onBackPressedCompat(): Boolean {
        return if (isSearchOpen && searchMenuItem != null) {
            searchMenuItem!!.collapseActionView()
            true
        } else {
            false
        }
    }

    private fun setupAdapter() {
        binding.queueList.adapter = QueueAdapter(
            activity = this,
            recyclerView = binding.queueList,
            listener = this,
        ) { row ->
            onRowClicked(row as QueueRow)
        }

        if (areSystemAnimationsEnabled) {
            binding.queueList.scheduleLayoutAnimation()
        }
    }

    private fun getAdapter(): QueueAdapter? = binding.queueList.adapter as? QueueAdapter

    // region rendering

    private fun render(state: QueueState?) {
        latestState = state
        val adapter = getAdapter() ?: return
        if (state == null) {
            // not loaded yet, which is different from an empty queue
            return
        }

        val rows = state.toRows(searchQuery)
        adapter.submit(rows, searchQuery)
        binding.queuePlaceholder.beGoneIf(rows.isNotEmpty())
        binding.queueToolbar.menu.findItem(R.id.undo_queue_reorder)?.isEnabled = state.canUndoReorder

        if (!scrolledToCurrent && searchQuery.isEmpty()) {
            scrolledToCurrent = true
            val head = state.head
            val position = head?.let { adapter.indexOfRowWithKey(it.id) } ?: -1
            if (position > 0) {
                binding.queueList.lazySmoothScroll(position)
            }
        }
    }

    // endregion

    // region options menu

    private fun setupOptionsMenu() {
        setupSearch(binding.queueToolbar.menu)
        binding.queueToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.create_playlist_from_queue -> createPlaylistFromQueue()
                R.id.shuffle_queue -> queue?.post { shuffleUpcoming() }
                R.id.sort_queue_by_album -> queue?.post { sortUpcomingByAlbum() }
                R.id.undo_queue_reorder -> queue?.post { undoReorder() }
                R.id.clear_queue -> confirm(R.string.clear_queue_confirmation) { queue?.post { clearQueue() } }
                R.id.clear_queue_history -> confirm(R.string.clear_queue_history_confirmation) { queue?.post { clearHistory() } }
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun confirm(messageId: Int, callback: () -> Unit) {
        ConfirmationDialog(this, "", messageId, org.fossify.commons.R.string.yes, org.fossify.commons.R.string.no) {
            callback()
        }
    }

    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(R.id.search)
        (searchMenuItem!!.actionView as SearchView).apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        searchQuery = newText
                        render(latestState)
                    }
                    return true
                }
            })
        }

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                isSearchOpen = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                isSearchOpen = false
                searchQuery = ""
                render(latestState)
                return true
            }
        })
    }

    private fun createPlaylistFromQueue() {
        val tracks = latestState?.allEntries.orEmpty()
            .filterIsInstance<QueueEntry.TrackEntry>()
            .map { it.track }
            .distinctBy { it.mediaStoreId }

        NewPlaylistDialog(this) { newPlaylistId ->
            val playlistTracks = ArrayList<Track>()
            tracks.forEach {
                it.playListId = newPlaylistId
                playlistTracks.add(it)
            }

            ensureBackgroundThread {
                RoomHelper(this).insertTracksWithPlaylist(playlistTracks)
            }
        }
    }

    // endregion

    // region intents

    private fun onRowClicked(row: QueueRow) {
        val entry = (row as? QueueRow.TrackRow)?.entry ?: return
        when (row.section) {
            QueueRow.Section.CURRENT -> withPlayer { if (!isReallyPlaying) play() }
            else -> queue?.jumpTo(entry.id)
        }
    }

    override fun onRemoveEntries(entryIds: List<Long>) {
        queue?.post { removeEntries(entryIds) }
    }

    override fun onRemoveGroup(groupId: Long) {
        queue?.post { removeGroup(groupId) }
    }

    override fun onShuffleGroup(groupId: Long) {
        queue?.post { shuffleUpcoming(groupId) }
    }

    override fun onMoveEntry(entryId: Long, toUpcomingIndex: Int) {
        queue?.post { moveEntry(entryId, toUpcomingIndex) }
    }

    override fun onPauseAfter(entryId: Long) {
        queue?.post {
            if (!insertPauseMarker(entryId)) {
                toast(R.string.queue_pause_not_possible)
            }
        }
    }

    override fun onSetRepeatCount(entry: QueueEntry.TrackEntry) {
        QueueRepeatDialog(this, entry) { total ->
            queue?.post { setRepeatCount(entry.id, total) }
        }
    }

    // endregion
}
