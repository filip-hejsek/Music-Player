package org.fossify.musicplayer.playback.queue

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossify.musicplayer.extensions.isReallyPlaying
import org.fossify.musicplayer.models.Track

/** What the player asks of the queue when the user (or a notification button, headset, ...) navigates. */
interface QueueNavigator {
    fun onPlayRequested()
    fun onNextRequested()
    fun onPreviousRequested()
}

private const val POSITION_SAVE_INTERVAL_MS = 5_000L

/**
 * Connects the logical queue ([QueueManager]) to the ExoPlayer timeline ([TimelineMaterializer]).
 *
 * The data flows one way: the manager changes the queue and publishes the new state, and the controller then
 * brings the player timeline in line with it. The player never decides what plays next; when it finishes an
 * item it reports that, and the manager works out what follows.
 *
 * Must be used with a [scope] running on the player's thread, since the timeline is touched directly.
 */
class QueueController(
    private val player: Player,
    val manager: QueueManager,
    private val state: StateFlow<QueueState?>,
    private val scope: CoroutineScope,
) : QueueNavigator {
    private val materializer = TimelineMaterializer(player)
    private val ready = CompletableDeferred<Unit>()
    private var tickerJob: Job? = null

    // region lifecycle

    /** Loads the queue from Room and prepares the player (paused) on the saved head. */
    fun start() {
        scope.launch {
            manager.load()
            restoreTimeline()
            ready.complete(Unit)
        }
        startPositionTicker()
    }

    suspend fun awaitReady() = ready.await()

    /** For cold starts (widget, activity launch): makes sure the player is prepared if there's anything to play. */
    fun restore(callback: (success: Boolean) -> Unit) {
        scope.launch {
            awaitReady()
            val alreadyPrepared = player.currentMediaItem != null
            if (!alreadyPrepared) {
                restoreTimeline()
            }
            callback(!alreadyPrepared && player.currentMediaItem != null)
        }
    }

    private suspend fun restoreTimeline() {
        manager.dropLeadingPauseMarkers()
        val entries = entriesFromHead()
        if (entries.firstOrNull() is QueueEntry.TrackEntry && player.currentMediaItem == null) {
            materializer.rebuild(entries, manager.savedPositionMs(), play = false)
        }
    }

    fun release() {
        tickerJob?.cancel()
    }

    // endregion

    // region persistence of the in-track position

    /** Everything but the playback position is written eagerly; the position only on this ticker. */
    private fun startPositionTicker() {
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                if (player.isReallyPlaying) {
                    savePositionNow()
                }
            }
        }
    }

    fun savePositionNow() {
        if (state.value?.current == null) {
            return
        }
        scope.launch { manager.savePosition(player.currentPosition) }
    }

    // endregion

    // region player events

    override fun onPlayRequested() {
        scope.launch {
            awaitReady()
            manager.markActive()
        }
    }

    override fun onNextRequested() {
        scope.launch {
            awaitReady()
            applyNavigation(manager.next(play = true))
        }
    }

    override fun onPreviousRequested() {
        scope.launch {
            awaitReady()
            val result = manager.previous(play = true)
            if (result == null) {
                // nothing in history: restart the current track
                player.seekTo(0)
                player.play()
            } else {
                applyNavigation(result)
            }
        }
    }

    /**
     * The player finished a repetition: it moved on to the next item by itself, repeated, or ran out of items
     * (at a pause marker or at the end of the queue).
     */
    fun onTrackFinished() {
        scope.launch {
            awaitReady()
            val result = manager.onTrackFinished() ?: return@launch
            if (result.isActive && !result.stoppedAtPauseMarker) {
                // the player already is on the right item: just drop what's behind it and top up what's ahead
                syncTimeline()
            } else {
                applyNavigation(result)
            }
        }
    }

    // endregion

    // region intents from the UI

    /** Runs [block] on the queue's (player) thread. */
    fun launchOnQueue(block: suspend QueueController.() -> Unit) {
        scope.launch { block() }
    }

    /** Runs a queue operation and brings the timeline in line with the result. */
    fun post(block: suspend QueueManager.() -> Unit) {
        scope.launch {
            awaitReady()
            manager.block()
            syncTimeline()
        }
    }

    /** The user tapped an entry. */
    fun jumpTo(entryId: Long) {
        scope.launch {
            awaitReady()
            applyNavigation(manager.jumpTo(entryId, play = true))
        }
    }

    /**
     * Adds tracks to the queue. When [startIfEmpty] is set and the queue was empty, playback starts at once;
     * otherwise the user gets to adjust the queue (e.g. shuffle it) before playing.
     */
    fun addTracks(
        tracks: List<Track>,
        where: InsertPosition = InsertPosition.End,
        group: QueueGroupSpec? = null,
        startIfEmpty: Boolean = false,
    ) {
        scope.launch {
            awaitReady()
            val result = manager.addTracks(tracks, where, group)
            syncTimeline()
            if (result.wasQueueEmpty && startIfEmpty && result.entryIds.isNotEmpty()) {
                player.play()
            }
        }
    }

    /**
     * For controllers that bring their own queue (Android Auto, assistants): queues [tracks] right after the
     * current entry, jumps to the one at [startIndex] and returns the timeline the session should set.
     * Whatever was queued before stays in the queue.
     */
    suspend fun playTracksNow(tracks: List<Track>, startIndex: Int): List<MediaItem> {
        queueAndJump(tracks, startIndex) ?: return emptyList()
        return materializer.toMediaItems(entriesFromHead())
    }

    /** Same as [playTracksNow], for callers that can use the controller directly. */
    fun playNow(tracks: List<Track>, startIndex: Int = 0) {
        scope.launch { applyNavigation(queueAndJump(tracks, startIndex)) }
    }

    private suspend fun queueAndJump(tracks: List<Track>, startIndex: Int): NavigationResult? {
        awaitReady()
        val result = manager.addTracks(tracks, InsertPosition.PlayNext)
        val target = result.entryIds.getOrNull(startIndex.coerceAtLeast(0)) ?: return null
        return manager.jumpTo(target, play = true)
    }

    /** Track metadata changed: reload it and re-prepare the current item (this restarts it at the same position). */
    fun refreshTracks() {
        scope.launch {
            awaitReady()
            manager.reloadTracks()
            val entries = entriesFromHead()
            if (entries.firstOrNull() is QueueEntry.TrackEntry && player.currentMediaItem != null) {
                materializer.rebuild(entries, player.currentPosition, play = player.playWhenReady)
            }
        }
    }

    /** The timeline for resuming playback after the process was killed. */
    suspend fun resumptionTimeline(): Pair<List<MediaItem>, Long> {
        awaitReady()
        manager.dropLeadingPauseMarkers()
        return materializer.toMediaItems(entriesFromHead()) to manager.savedPositionMs()
    }

    // endregion

    // region timeline

    /** The logical entries from the head on: the active entry (if any), then everything upcoming. */
    private fun entriesFromHead(): List<QueueEntry> {
        val s = state.value ?: return emptyList()
        return listOfNotNull<QueueEntry>(s.current) + s.upcoming
    }

    /** After a queue edit or after the player advanced on its own. Keeps playback running where possible. */
    private suspend fun syncTimeline() {
        manager.dropLeadingPauseMarkers()
        val entries = entriesFromHead()
        if (entries.firstOrNull() !is QueueEntry.TrackEntry) {
            materializer.clear()
            return
        }

        if (!materializer.refreshUpcoming(entries)) {
            // The head changed (it can only do that while nothing is active), so start over on the new one.
            materializer.rebuild(entries, startPositionMs = 0, play = player.playWhenReady)
        }
    }

    /** After the cursor moved: the timeline is rebuilt around the new head. */
    private suspend fun applyNavigation(result: NavigationResult?) {
        if (result == null) {
            return
        }

        manager.dropLeadingPauseMarkers()
        val entries = entriesFromHead()
        if (result.head == null || entries.firstOrNull() !is QueueEntry.TrackEntry) {
            materializer.clear()
            return
        }

        materializer.rebuild(entries, startPositionMs = 0, play = result.isActive)
    }

    // endregion
}
