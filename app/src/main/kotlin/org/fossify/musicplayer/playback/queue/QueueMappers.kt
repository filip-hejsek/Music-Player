package org.fossify.musicplayer.playback.queue

import org.fossify.musicplayer.models.Track

/**
 * Returns null when the track no longer exists. Rows in `tracks` are deleted and recreated by the media
 * scanner, so a queue entry can outlive its track; callers drop such entries (but leave the row alone,
 * since the track may come back).
 */
fun QueueEntryRow.toQueueEntry(tracks: Map<Long, Track>): QueueEntry? = when (type) {
    QueueEntryType.TRACK -> trackId?.let { tracks[it] }?.let { track ->
        QueueEntry.TrackEntry(
            id = id,
            groupId = groupId,
            track = track,
            completedRepetitions = completedRepetitions,
            remainingRepetitions = remainingRepetitions
        )
    }

    QueueEntryType.PAUSE_MARKER -> QueueEntry.PauseMarker(id, groupId)
}

fun QueueEntry.toRow(position: Double): QueueEntryRow = when (this) {
    is QueueEntry.TrackEntry -> QueueEntryRow(
        id = id,
        position = position,
        type = QueueEntryType.TRACK,
        trackId = track.mediaStoreId,
        completedRepetitions = completedRepetitions,
        remainingRepetitions = remainingRepetitions,
        groupId = groupId
    )

    is QueueEntry.PauseMarker -> QueueEntryRow(
        id = id,
        position = position,
        type = QueueEntryType.PAUSE_MARKER,
        groupId = groupId
    )
}

fun QueueGroupRow.toQueueGroup() = QueueGroup(id = id, label = label, source = source, sourceId = sourceId)
