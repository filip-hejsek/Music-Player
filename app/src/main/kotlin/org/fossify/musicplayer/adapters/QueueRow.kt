package org.fossify.musicplayer.adapters

import org.fossify.commons.extensions.normalizeString
import org.fossify.musicplayer.playback.queue.QueueEntry
import org.fossify.musicplayer.playback.queue.QueueGroup
import org.fossify.musicplayer.playback.queue.QueueState

/**
 * What the queue screen shows, derived from a [QueueState]: one row per entry, plus a header row in front of each
 * run of consecutive entries of the same group. The screen renders these and never mutates them.
 */
sealed class QueueRow {
    /** Stable across updates, used for diffing and selection. Entry ids are positive, header keys negative. */
    abstract val key: Long

    enum class Section { HISTORY, CURRENT, UPCOMING }

    data class TrackRow(val entry: QueueEntry.TrackEntry, val section: Section) : QueueRow() {
        override val key get() = entry.id
    }

    data class PauseRow(val entry: QueueEntry.PauseMarker) : QueueRow() {
        override val key get() = entry.id
    }

    data class GroupHeaderRow(
        val groupId: Long,
        val group: QueueGroup?,
        val firstEntryId: Long,
        val trackCount: Int,
        /** Removing the group only affects entries that haven't been played yet. */
        val hasUpcoming: Boolean,
    ) : QueueRow() {
        override val key get() = -(firstEntryId + 1)
    }
}

/**
 * Searching shows a flat list of the matching tracks without headers, since groups and pause markers
 * make no sense on a filtered list.
 */
fun QueueState.toRows(query: String = ""): List<QueueRow> {
    val tagged = history.map { it as QueueEntry to QueueRow.Section.HISTORY } +
        listOfNotNull(current).map { it as QueueEntry to QueueRow.Section.CURRENT } +
        upcoming.map { it to QueueRow.Section.UPCOMING }

    if (query.isNotBlank()) {
        val normalizedQuery = query.normalizeString()
        return tagged.mapNotNull { (entry, section) ->
            (entry as? QueueEntry.TrackEntry)
                ?.takeIf { it.track.title.normalizeString().contains(normalizedQuery, true) }
                ?.let { QueueRow.TrackRow(it, section) }
        }
    }

    val rows = ArrayList<QueueRow>(tagged.size + 4)
    tagged.forEachIndexed { i, (entry, section) ->
        val groupId = entry.groupId
        if (groupId != null && (i == 0 || tagged[i - 1].first.groupId != groupId)) {
            val runEnd = (i until tagged.size).firstOrNull { tagged[it].first.groupId != groupId } ?: tagged.size
            val run = tagged.subList(i, runEnd)
            rows += QueueRow.GroupHeaderRow(
                groupId = groupId,
                group = groups[groupId],
                firstEntryId = entry.id,
                trackCount = run.count { it.first is QueueEntry.TrackEntry },
                hasUpcoming = run.any { it.second == QueueRow.Section.UPCOMING },
            )
        }

        rows += when (entry) {
            is QueueEntry.TrackEntry -> QueueRow.TrackRow(entry, section)
            is QueueEntry.PauseMarker -> QueueRow.PauseRow(entry)
        }
    }

    return rows
}
