package org.fossify.musicplayer.playback.queue

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.fossify.musicplayer.extensions.queueEntryId
import org.fossify.musicplayer.extensions.toMediaItem
import org.fossify.musicplayer.helpers.EXTRA_QUEUE_ENTRY_ID
import org.fossify.musicplayer.helpers.EXTRA_QUEUE_REPETITION

/** Upper bound for the number of physical `MediaItem`s in the timeline. Counts items, not logical entries. */
const val TIMELINE_WINDOW_SIZE = 5

/**
 * One physical `MediaItem` to be played: repetition [repetition] (0-based) of [entry].
 */
data class PhysicalSlot(val entry: QueueEntry.TrackEntry, val repetition: Int)

/**
 * Derives the ExoPlayer timeline from the logical queue. The timeline is a lossy, short-lived view:
 * it only ever holds the current item and a few items ahead, and is rebuilt or topped up whenever the queue changes.
 *
 * - Repetitions are materialized as duplicate items, so the window is counted in physical items and a large
 *   repeat count can't blow up the timeline.
 * - A pause marker ends the timeline: the last item before it ends playback, and the marker is consumed by
 *   the queue manager when that happens.
 * - An entry that repeats forever is materialized once, and nothing after it: it never ends on its own.
 *   While it is the current item, `REPEAT_MODE_ONE` does the repeating.
 *
 * The player's own shuffle and repeat-all modes are never used.
 *
 * All methods must be called on the player's thread.
 */
class TimelineMaterializer(private val player: Player) {

    /**
     * The physical items for [entries], which must start at the queue head (the active or next entry).
     */
    fun plan(entries: List<QueueEntry>, limit: Int = TIMELINE_WINDOW_SIZE): List<PhysicalSlot> {
        val slots = ArrayList<PhysicalSlot>()
        for (entry in entries) {
            if (slots.size >= limit || entry !is QueueEntry.TrackEntry) {
                break
            }

            val remaining = entry.remainingRepetitions
            if (remaining == null) {
                slots += PhysicalSlot(entry, entry.completedRepetitions)
                break
            }

            for (i in 0 until remaining) {
                if (slots.size >= limit) {
                    return slots
                }
                slots += PhysicalSlot(entry, entry.completedRepetitions + i)
            }
        }
        return slots
    }

    fun toMediaItems(entries: List<QueueEntry>): List<MediaItem> = plan(entries).map { it.toMediaItem() }

    /** The logical entry the player is currently positioned on, if the timeline was built by us. */
    val currentEntryId: Long? get() = player.currentMediaItem?.queueEntryId

    /** Replaces the whole timeline, so that it starts with the head of [entries]. */
    fun rebuild(entries: List<QueueEntry>, startPositionMs: Long, play: Boolean) {
        val slots = plan(entries)
        if (slots.isEmpty()) {
            clear()
            return
        }

        player.setMediaItems(slots.map { it.toMediaItem() }, 0, startPositionMs)
        applyRepeatMode(slots)
        player.playWhenReady = play
        player.prepare()
    }

    /**
     * Keeps the current item playing without a glitch and replaces everything after it, e.g. after the queue
     * was edited, or after the player moved on by itself. Items that were already played are dropped.
     * Returns false if the player isn't on the head, in which case the caller has to [rebuild].
     */
    fun refreshUpcoming(entries: List<QueueEntry>): Boolean {
        val slots = plan(entries)
        val head = slots.firstOrNull() ?: return false
        if (currentEntryId != head.entry.id) {
            return false
        }

        val index = player.currentMediaItemIndex
        if (index > 0) {
            player.removeMediaItems(0, index)
        }

        // Touch as little as possible, so an item that is already prepared for gapless playback survives.
        val existing = (1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val wanted = slots.drop(1)
        val wantedIds = wanted.map { it.mediaId }
        val keep = if (wantedIds.take(existing.size) == existing) existing.size else 0
        if (keep < existing.size) {
            player.removeMediaItems(1 + keep, player.mediaItemCount)
        }
        player.addMediaItems(wanted.drop(keep).map { it.toMediaItem() })

        applyRepeatMode(slots)
        return true
    }

    fun clear() {
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.clearMediaItems()
    }

    private fun applyRepeatMode(slots: List<PhysicalSlot>) {
        player.repeatMode = if (slots.firstOrNull()?.entry?.isInfinite == true) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }
}

/** Unique per physical item, unlike the track's `mediaStoreId`. */
val PhysicalSlot.mediaId: String get() = "${entry.id}:$repetition"

/**
 * The back-reference to the logical entry and the repetition number live in the metadata extras.
 */
fun PhysicalSlot.toMediaItem(): MediaItem {
    val base = entry.track.toMediaItem()
    val extras = Bundle(base.mediaMetadata.extras ?: Bundle.EMPTY).apply {
        putLong(EXTRA_QUEUE_ENTRY_ID, entry.id)
        putInt(EXTRA_QUEUE_REPETITION, repetition)
    }

    return base.buildUpon()
        .setMediaId(mediaId)
        .setMediaMetadata(base.mediaMetadata.buildUpon().setExtras(extras).build())
        .build()
}
