package org.fossify.musicplayer.playback.queue

import org.fossify.musicplayer.models.Track

/**
 * Logical queue model. This is what the UI renders and what [QueueManager] manipulates;
 * the ExoPlayer timeline is derived from it.
 */
sealed class QueueEntry {
    /** Unique per entry (not per track: the same track can be queued many times). */
    abstract val id: Long
    abstract val groupId: Long?

    /**
     * Repetition counters are kept for entries both in the queue and in history, so an entry can
     * move between the two without forgetting anything.
     *
     * - total repeat count = [completedRepetitions] + [remainingRepetitions]
     * - [remainingRepetitions] includes the repetition currently being played
     * - `null` remaining = infinite
     * - an entry in the queue (or active) has at least one remaining repetition
     * - a history entry with `completedRepetitions == 0` was skipped
     */
    data class TrackEntry(
        override val id: Long,
        override val groupId: Long? = null,
        val track: Track,
        val completedRepetitions: Int = 0,
        val remainingRepetitions: Int? = 1,
    ) : QueueEntry() {
        val isInfinite: Boolean get() = remainingRepetitions == null

        /** `null` for infinite. */
        val totalRepetitions: Int? get() = remainingRepetitions?.plus(completedRepetitions)

        val wasSkipped: Boolean get() = completedRepetitions == 0
    }

    data class PauseMarker(
        override val id: Long,
        override val groupId: Long? = null,
    ) : QueueEntry()
}

enum class QueueGroupSource { ALBUM, PLAYLIST, MANUAL }

data class QueueGroup(
    val id: Long,
    /** Denormalized (e.g. album title) so reads need no join. Blank for unlabeled manual groups. */
    val label: String,
    val source: QueueGroupSource,
    val sourceId: Long?,
)

/**
 * Snapshot of the whole queue, split around the cursor.
 *
 * `history` never contains pause markers. [current] is the head, but only if it is *active*;
 * when the head is not active it is the first element of [upcoming] instead.
 */
data class QueueState(
    val history: List<QueueEntry.TrackEntry>,
    val current: QueueEntry.TrackEntry?,
    val upcoming: List<QueueEntry>,
    val groups: Map<Long, QueueGroup> = emptyMap(),
    /** The last shuffle/sort can still be reverted. */
    val canUndoReorder: Boolean = false,
) {
    /** Nothing is active and nothing is waiting to be played. */
    val isQueueEmpty: Boolean get() = current == null && upcoming.isEmpty()

    /** The entry that is playing, or that will play next. */
    val head: QueueEntry.TrackEntry? get() = current ?: upcoming.firstOrNull() as? QueueEntry.TrackEntry

    /** Everything that follows the head. */
    val afterHead: List<QueueEntry> get() = if (current != null) upcoming else upcoming.drop(1)

    /** History, then the active entry, then upcoming - in list order. */
    val allEntries: List<QueueEntry>
        get() = history + listOfNotNull(current) + upcoming

    companion object {
        val Empty = QueueState(emptyList(), null, emptyList())
    }
}
