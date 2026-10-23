package org.fossify.musicplayer.playback.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fossify.musicplayer.models.Track

enum class InsertPosition { End, PlayNext }

/**
 * Describes the group created by one "add" operation. When several tracks are added without a spec,
 * an unlabeled [QueueGroupSource.MANUAL] group is created.
 */
data class QueueGroupSpec(
    val source: QueueGroupSource,
    val sourceId: Long? = null,
    val label: String = "",
)

data class AddResult(
    val entryIds: List<Long>,
    /** Nothing was active or waiting before the add. Callers decide whether to start playback. */
    val wasQueueEmpty: Boolean,
)

/**
 * Result of moving the cursor.
 *
 * @property head the entry the cursor is now at, or null if the queue is exhausted
 * @property isActive whether playback should (continue to) run on [head]
 * @property stoppedAtPauseMarker a pause marker was reached and consumed: playback must stop
 * @property repeated the same entry plays another repetition
 */
data class NavigationResult(
    val head: QueueEntry.TrackEntry?,
    val isActive: Boolean,
    val stoppedAtPauseMarker: Boolean = false,
    val repeated: Boolean = false,
)

/**
 * Owns the logical queue: loads it from Room, applies operations to it and publishes [QueueState].
 *
 * Every operation works out a [QueueMutation] from an in-memory snapshot, commits it atomically through
 * [QueueDao.commit] and then reloads the snapshot, so Room is always the source of truth.
 *
 * Operations are serialized with a mutex. Pass a [scope] bound to the player thread once the timeline
 * materializer is wired in, since materialization touches the `ExoPlayer` instance.
 *
 * This class only deals with the logical queue: it knows nothing about ExoPlayer. Whoever drives playback
 * calls [next], [previous], [onTrackFinished] and friends and reacts to the returned [NavigationResult].
 *
 * Positions are fractional, so inserting or moving an entry writes a single row. When the gap between two
 * neighbours gets too small, all positions are renumbered.
 */
class QueueManager(
    private val dao: QueueDao,
    private val loadTracks: suspend () -> Map<Long, Track>,
    private val stateFlow: MutableStateFlow<QueueState?>,
    private val historyCap: () -> Int,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private var trackCache: Map<Long, Track> = emptyMap()
    private var missingTrackIds: Set<Long> = emptySet()
    private var snapshot: Snapshot? = null

    /** entry id -> position before the last shuffle/sort. Only valid until the next change. */
    private var reorderUndo: Map<Long, Double>? = null

    /** Runs [block] in the manager's scope; the UI uses this to dispatch intents. */
    fun post(block: suspend QueueManager.() -> Unit): Job = scope.launch { block(this@QueueManager) }

    // region lifecycle

    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    /** Re-reads the tracks (e.g. after a setting that changes track titles), keeping queue, history and cursor. */
    suspend fun reloadTracks() {
        mutex.withLock {
            trackCache = emptyMap()
            missingTrackIds = emptySet()
            refresh()
        }
    }

    private suspend fun loadLocked(): Snapshot {
        dao.ensureCursor(PlaybackCursorRow())
        return refresh()
    }

    private suspend fun snap(): Snapshot = snapshot ?: loadLocked()

    private suspend fun refresh(): Snapshot {
        val rows = dao.getAll()

        // The media scanner recreates track rows, so only hit the tracks table again when a queued
        // track is unknown - and not again for tracks that are known to be gone.
        val missing = rows.mapNotNull { row ->
            row.trackId?.takeIf { row.type == QueueEntryType.TRACK && it !in trackCache }
        }.toSet()
        if (missing.any { it !in missingTrackIds }) {
            trackCache = loadTracks()
            missingTrackIds = missing.filterTo(HashSet()) { it !in trackCache }
        }

        val cursor = dao.getCursor() ?: PlaybackCursorRow().also { dao.setCursor(it) }
        val groups = dao.getAllGroups().associate { it.id to it.toQueueGroup() }
        val items = rows.map { Item(it, it.toQueueEntry(trackCache)) }

        return Snapshot(items, cursor, groups).also {
            snapshot = it
            publish(it)
        }
    }

    private fun publish(snap: Snapshot) {
        stateFlow.value = snap.toState(canUndoReorder = reorderUndo != null)
    }

    private suspend fun commit(mutation: QueueMutation): List<Long> {
        reorderUndo = null
        val ids = dao.commit(mutation)
        refresh()
        return ids
    }

    // endregion

    // region adding

    /**
     * Adds [tracks] as new entries, one entry per track (duplicates are fine).
     *
     * If [group] is null, a group is still created when more than one track is added.
     */
    suspend fun addTracks(
        tracks: List<Track>,
        where: InsertPosition = InsertPosition.End,
        group: QueueGroupSpec? = null,
    ): AddResult = mutex.withLock {
        val wasEmpty = snap().toState().isQueueEmpty
        if (tracks.isEmpty()) {
            return@withLock AddResult(emptyList(), wasEmpty)
        }

        val index = if (where == InsertPosition.PlayNext) 0 else Int.MAX_VALUE
        val plan = planInsertOrRenumber(index, tracks.size)
        val spec = group ?: QueueGroupSpec(QueueGroupSource.MANUAL).takeIf { tracks.size > 1 }

        val rows = tracks.mapIndexed { i, track ->
            QueueEntryRow(
                position = plan.positions[i],
                type = QueueEntryType.TRACK,
                trackId = track.mediaStoreId,
            )
        }

        val cursor = snap().cursor
        val ids = commit(
            QueueMutation(
                newGroup = spec?.let { QueueGroupRow(label = it.label, source = it.source, sourceId = it.sourceId) },
                newEntries = rows,
                cursor = plan.newHeadPosition?.let { cursor.copy(headPosition = it) },
            )
        )
        AddResult(ids, wasEmpty)
    }

    /**
     * Inserts a "pause here" marker right after the entry [afterEntryId], which must be the active entry or
     * an upcoming one. Returns false if that isn't possible or the marker would be adjacent to another one.
     */
    suspend fun insertPauseMarker(afterEntryId: Long): Boolean = mutex.withLock {
        val s = snap()
        val upcoming = s.upcoming
        val index = when (afterEntryId) {
            s.activeItem?.id -> 0
            else -> upcoming.indexOfFirst { it.id == afterEntryId }.takeIf { it >= 0 }?.plus(1)
        } ?: return@withLock false

        val before = if (index > 0) upcoming[index - 1] else s.activeItem
        val after = upcoming.getOrNull(index)
        if (before?.entry is QueueEntry.PauseMarker || after?.entry is QueueEntry.PauseMarker) {
            return@withLock false
        }

        // keep a group visually contiguous if the marker lands inside of it
        val groupId = before?.entry?.groupId?.takeIf { it == after?.entry?.groupId }

        val plan = planInsertOrRenumber(index, 1)
        commit(
            QueueMutation(
                newEntries = listOf(
                    QueueEntryRow(position = plan.positions[0], type = QueueEntryType.PAUSE_MARKER, groupId = groupId)
                ),
                cursor = plan.newHeadPosition?.let { snap().cursor.copy(headPosition = it) },
            )
        )
        true
    }

    // endregion

    // region editing

    /**
     * Removes entries from the queue or from history. The active entry can't be removed this way.
     * Returns the number of removed entries.
     */
    suspend fun removeEntries(entryIds: Collection<Long>): Int = mutex.withLock {
        val s = snap()
        val known = s.items.mapTo(HashSet()) { it.id }
        val ids = entryIds.filter { it in known && it != s.activeItem?.id }
        if (ids.isNotEmpty()) {
            commit(QueueMutation(deleteIds = ids))
        }
        ids.size
    }

    /** Removes the not-yet-played entries of a group (as a unit). History and the active entry stay. */
    suspend fun removeGroup(groupId: Long): Int = mutex.withLock {
        val ids = snap().upcoming.filter { it.entry?.groupId == groupId }.map { it.id }
        if (ids.isNotEmpty()) {
            commit(QueueMutation(deleteIds = ids))
        }
        ids.size
    }

    /** Deletes everything not yet played. The active entry keeps playing. */
    suspend fun clearQueue() = mutex.withLock {
        val s = snap()
        val ids = s.items.subList(s.upcomingStart, s.items.size).map { it.id }
        if (ids.isNotEmpty()) {
            commit(QueueMutation(deleteIds = ids))
        }
    }

    suspend fun clearHistory() = mutex.withLock {
        val s = snap()
        val ids = s.items.subList(0, s.headIndex).map { it.id }
        if (ids.isNotEmpty()) {
            commit(QueueMutation(deleteIds = ids))
        }
    }

    /**
     * Moves an upcoming entry so that it ends up at [toUpcomingIndex] of the upcoming list.
     * The index refers to the list *without* the moved entry, so `0` always means "first".
     * Neither history nor the active entry can be reordered.
     */
    suspend fun moveEntry(entryId: Long, toUpcomingIndex: Int): Boolean = mutex.withLock {
        val s = snap()
        val from = s.upcoming.indexOfFirst { it.id == entryId }
        if (from < 0) {
            return@withLock false
        }
        if (from == toUpcomingIndex.coerceIn(0, s.upcoming.size - 1)) {
            return@withLock true
        }

        val plan = planInsertOrRenumber(toUpcomingIndex, 1, excluding = setOf(entryId))
        commit(
            QueueMutation(
                positions = mapOf(entryId to plan.positions[0]),
                cursor = plan.newHeadPosition?.let { snap().cursor.copy(headPosition = it) },
            )
        )
        true
    }

    /**
     * Sets the total number of times an active or upcoming entry plays; `null` = infinitely.
     * Completed repetitions count, and at least the current/next one always remains.
     */
    suspend fun setRepeatCount(entryId: Long, total: Int?): Boolean = mutex.withLock {
        val s = snap()
        val index = s.items.indexOfFirst { it.id == entryId }
        val entry = s.items.getOrNull(index)?.entry as? QueueEntry.TrackEntry
        if (entry == null || index < s.headIndex) {
            return@withLock false
        }

        val remaining = total?.let { maxOf(1, it - entry.completedRepetitions) }
        commit(QueueMutation(repetitions = mapOf(entryId to Repetitions(entry.completedRepetitions, remaining))))
        true
    }

    // endregion

    // region reordering

    /** Shuffles the upcoming tracks, or only those of [groupId] (within the slots they occupy). */
    suspend fun shuffleUpcoming(groupId: Long? = null): Boolean = mutex.withLock {
        reorderUpcomingTracks(groupId) { it.shuffled() }
    }

    /** Sorts the upcoming tracks by album, disc and track number. */
    suspend fun sortUpcomingByAlbum(): Boolean = mutex.withLock {
        reorderUpcomingTracks(null) { items ->
            items.sortedWith(
                compareBy<Item>(
                    { (it.entry as QueueEntry.TrackEntry).track.album.lowercase() },
                    { (it.entry as QueueEntry.TrackEntry).track.discNumber ?: 0 },
                    { (it.entry as QueueEntry.TrackEntry).track.trackId ?: Int.MAX_VALUE },
                    { (it.entry as QueueEntry.TrackEntry).track.title.lowercase() },
                )
            )
        }
    }

    /** Reverts the last shuffle/sort, as long as nothing else changed the queue since. */
    suspend fun undoReorder(): Boolean = mutex.withLock {
        val undo = reorderUndo ?: return@withLock false
        val known = snap().items.mapTo(HashSet()) { it.id }
        commit(QueueMutation(positions = undo.filterKeys { it in known }))
        true
    }

    /**
     * Permutes the matching upcoming track entries among the positions they already occupy,
     * so pause markers and entries of other groups stay exactly where they are.
     */
    private suspend fun reorderUpcomingTracks(groupId: Long?, order: (List<Item>) -> List<Item>): Boolean {
        val candidates = snap().upcoming.filter {
            it.entry is QueueEntry.TrackEntry && (groupId == null || it.entry.groupId == groupId)
        }
        if (candidates.size < 2) {
            return false
        }

        val slots = candidates.map { it.position }
        val reordered = order(candidates)
        val undo = candidates.associate { it.id to it.position }
        commit(QueueMutation(positions = reordered.mapIndexed { i, item -> item.id to slots[i] }.toMap()))

        reorderUndo = undo
        snapshot?.let { publish(it) }
        return true
    }

    // endregion

    // region navigation

    /**
     * Makes [entryId] the head, e.g. when the user taps it. Entries skipped over go to history (marked as
     * skipped, since they have no completions); entries popped back out of history return to the queue.
     * Pause markers that are jumped over are removed, since history never contains markers.
     */
    suspend fun jumpTo(entryId: Long, play: Boolean = true): NavigationResult? = mutex.withLock {
        val s = snap()
        val target = s.items.indexOfFirst { it.id == entryId }
        if (s.items.getOrNull(target)?.entry !is QueueEntry.TrackEntry) {
            return@withLock null
        }
        moveHead(s, target, play)
    }

    /**
     * Skips the rest of the current entry (including all of its remaining repetitions, which are preserved
     * in case the user comes back) and moves on. Honors pause markers: if one is next, it is removed and
     * playback stops on the entry after it.
     */
    suspend fun next(play: Boolean = true): NavigationResult? = mutex.withLock {
        advance(snap(), play, emptyMap())
    }

    /** Pops the latest history entry back into the queue and makes it the head. Null if history is empty. */
    suspend fun previous(play: Boolean = true): NavigationResult? = mutex.withLock {
        val s = snap()
        val target = s.items.subList(0, s.headIndex).indexOfLast { it.entry is QueueEntry.TrackEntry }
        if (target < 0) {
            return@withLock null
        }
        moveHead(s, target, play)
    }

    /**
     * The active entry finished one repetition: either it repeats, or the queue advances
     * (which puts the finished entry into history, as played).
     */
    suspend fun onTrackFinished(): NavigationResult? = mutex.withLock {
        val s = snap()
        val head = s.activeItem?.entry as? QueueEntry.TrackEntry ?: return@withLock null

        val completed = head.completedRepetitions + 1
        val remaining = head.remainingRepetitions?.minus(1)

        if (remaining == null || remaining >= 1) {
            commit(
                QueueMutation(
                    repetitions = mapOf(head.id to Repetitions(completed, remaining)),
                    cursor = s.cursor.copy(positionMs = 0),
                )
            )
            val updated = head.copy(completedRepetitions = completed, remainingRepetitions = remaining)
            NavigationResult(head = updated, isActive = true, repeated = true)
        } else {
            advance(s, play = true, extraRepetitions = mapOf(head.id to Repetitions(completed, 0)))
        }
    }

    /** Playback of the head actually started. */
    suspend fun markActive() = mutex.withLock {
        val s = snap()
        if (!s.isActive && s.items.getOrNull(s.headIndex)?.entry is QueueEntry.TrackEntry) {
            dao.updateIsActive(true)
            refresh()
        }
    }

    /** Called from the periodic ticker. Deliberately doesn't reload anything. */
    suspend fun savePosition(positionMs: Long) = dao.updatePositionMs(positionMs)

    /** The saved in-track position of the head, for restoring playback. */
    suspend fun savedPositionMs(): Long = mutex.withLock { snap().cursor.positionMs }

    /**
     * Pause markers can end up at the head when the user drags one to the front of a waiting queue.
     * Such a marker has nothing left to pause, so it is simply dropped. Returns true if anything was removed.
     */
    suspend fun dropLeadingPauseMarkers(): Boolean = mutex.withLock {
        val s = snap()
        if (s.isActive) {
            return@withLock false
        }
        val ids = s.items.drop(s.headIndex).takeWhile { it.entry == null || it.entry is QueueEntry.PauseMarker }
            .filter { it.entry is QueueEntry.PauseMarker }
            .map { it.id }
        if (ids.isNotEmpty()) {
            commit(QueueMutation(deleteIds = ids))
        }
        ids.isNotEmpty()
    }

    private suspend fun advance(s: Snapshot, play: Boolean, extraRepetitions: Map<Long, Repetitions>): NavigationResult? {
        val items = s.items
        val leaving = items.getOrNull(s.headIndex) ?: return null

        val delete = HashSet<Long>()
        if (leaving.entry is QueueEntry.PauseMarker) {
            delete += leaving.id
        }

        // Reached pause markers are consumed (consecutive ones all at once). Entries whose track is gone
        // are stepped over too.
        var stopped = false
        var k = s.headIndex + 1
        while (k < items.size && (items[k].entry == null || items[k].entry is QueueEntry.PauseMarker)) {
            if (items[k].entry is QueueEntry.PauseMarker) {
                delete += items[k].id
                stopped = stopped || leaving.entry is QueueEntry.TrackEntry
            }
            k++
        }

        val newHead = items.getOrNull(k)?.entry as? QueueEntry.TrackEntry
        val headPosition = items.getOrNull(k)?.position
            ?: items.lastOrNull { it.id !in delete }?.position?.plus(1.0)
            ?: s.cursor.headPosition
        val active = play && !stopped && newHead != null

        commit(
            QueueMutation(
                deleteIds = delete + historyToPrune(items, k, delete),
                repetitions = extraRepetitions,
                cursor = s.cursor.copy(headPosition = headPosition, isActive = active, positionMs = 0),
            )
        )
        return NavigationResult(head = newHead, isActive = active, stoppedAtPauseMarker = stopped)
    }

    /** Moves the cursor to the entry at index [target] of the snapshot, forwards or backwards. */
    private suspend fun moveHead(s: Snapshot, target: Int, play: Boolean): NavigationResult {
        val items = s.items
        val delete = HashSet<Long>()
        val repetitions = HashMap<Long, Repetitions>()

        if (target > s.headIndex) {
            items.subList(s.headIndex, target)
                .filter { it.entry is QueueEntry.PauseMarker }
                .mapTo(delete) { it.id }
        } else {
            // Entries that were played to the end have nothing remaining. They go back to the queue to be
            // played once more.
            items.subList(target, s.headIndex).forEach {
                val entry = it.entry
                if (entry is QueueEntry.TrackEntry && entry.remainingRepetitions == 0) {
                    repetitions[entry.id] = Repetitions(entry.completedRepetitions, 1)
                }
            }
        }

        val head = (items[target].entry as QueueEntry.TrackEntry).let { entry ->
            repetitions[entry.id]?.let { entry.copy(remainingRepetitions = it.remaining) } ?: entry
        }
        commit(
            QueueMutation(
                deleteIds = delete + historyToPrune(items, target, delete),
                repetitions = repetitions,
                cursor = s.cursor.copy(headPosition = items[target].position, isActive = play, positionMs = 0),
            )
        )
        return NavigationResult(head = head, isActive = play)
    }

    /** The oldest history entries beyond the configured cap, if the head moves to [newHeadIndex]. */
    private fun historyToPrune(items: List<Item>, newHeadIndex: Int, alreadyDeleted: Set<Long>): List<Long> {
        val history = items.subList(0, newHeadIndex).filter { it.id !in alreadyDeleted }
        val excess = history.size - historyCap().coerceAtLeast(0)
        return if (excess > 0) history.take(excess).map { it.id } else emptyList()
    }

    // endregion

    // region positions

    private class InsertPlan(val positions: List<Double>, val newHeadPosition: Double?)

    private suspend fun planInsertOrRenumber(upcomingIndex: Int, count: Int, excluding: Set<Long> = emptySet()): InsertPlan {
        snap().planInsert(upcomingIndex, count, excluding)?.let { return it }
        renumber()
        return checkNotNull(snap().planInsert(upcomingIndex, count, excluding)) { "no room in a freshly renumbered queue" }
    }

    /** Respaces all positions evenly, keeping the order and the cursor boundary. */
    private suspend fun renumber() {
        val s = snap()
        val rows = s.items.map { it.row }
        val newPositions = rows.mapIndexed { i, row -> row.id to (i + 1).toDouble() }.toMap()
        val newHead = s.items.indexOfFirst { it.position >= s.cursor.headPosition }
            .let { if (it < 0) rows.size + 1.0 else it + 1.0 }

        commit(QueueMutation(positions = newPositions, cursor = s.cursor.copy(headPosition = newHead)))
    }

    // endregion

    private class Item(val row: QueueEntryRow, val entry: QueueEntry?) {
        val id: Long get() = row.id
        val position: Double get() = row.position
    }

    /**
     * All rows in position order (including ones whose track is currently unknown, which have no [Item.entry])
     * and the cursor, plus the derived split into history / head / upcoming.
     */
    private class Snapshot(val items: List<Item>, val cursor: PlaybackCursorRow, val groups: Map<Long, QueueGroup>) {
        /** Index of the first row at or after the boundary; `items.size` if the queue is exhausted. */
        val headIndex: Int = items.indexOfFirst { it.position >= cursor.headPosition }.let { if (it < 0) items.size else it }

        val isActive: Boolean = cursor.isActive && items.getOrNull(headIndex)?.entry is QueueEntry.TrackEntry

        val activeItem: Item? = if (isActive) items[headIndex] else null

        /** The active entry is not part of the upcoming list. */
        val upcomingStart: Int = if (isActive) headIndex + 1 else headIndex

        /** Entries that can still be reordered or removed as part of the queue. */
        val upcoming: List<Item> by lazy { items.subList(upcomingStart, items.size).filter { it.entry != null } }

        private val lastHistory: Item? get() = items.subList(0, headIndex).lastOrNull { it.entry != null }

        fun toState(canUndoReorder: Boolean = false) = QueueState(
            history = items.subList(0, headIndex).mapNotNull { it.entry as? QueueEntry.TrackEntry },
            current = activeItem?.entry as? QueueEntry.TrackEntry,
            upcoming = upcoming.mapNotNull { it.entry },
            groups = groups,
            canUndoReorder = canUndoReorder,
        )

        /**
         * Finds [count] positions that place new entries at [upcomingIndex] of the upcoming list (ignoring
         * [excluding]), i.e. between the entry before and the entry after that spot. Null if the gap is too
         * small, in which case the caller has to [renumber] first.
         *
         * If the head isn't active and the entries go first, they become the new head, so the boundary has to
         * move to them ([InsertPlan.newHeadPosition]).
         */
        fun planInsert(upcomingIndex: Int, count: Int, excluding: Set<Long>): InsertPlan? {
            val list = upcoming.filter { it.id !in excluding }
            val index = upcomingIndex.coerceIn(0, list.size)

            val before = if (index > 0) list[index - 1].position else (activeItem ?: lastHistory)?.position
            val after = list.getOrNull(index)?.position

            val low = before ?: ((after ?: (cursor.headPosition + 1.0)) - 1.0)
            val high = after ?: (low + count + 1.0)
            val step = (high - low) / (count + 1)
            if (step < MIN_POSITION_STEP) {
                return null
            }

            val positions = (1..count).map { low + step * it }
            val newHead = if (!isActive && index == 0) positions.first() else null
            return InsertPlan(positions, newHead)
        }
    }

    private companion object {
        const val MIN_POSITION_STEP = 1e-6
    }
}
