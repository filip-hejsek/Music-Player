package org.fossify.musicplayer.playback.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row (id is always 0) holding the cursor.
 *
 * The cursor is a *boundary*, not a pointer: entries with `position < head_position` are history,
 * and the first entry at or after `head_position` is the head (playing, or next to play).
 * That stays well-defined with no active entry, and when the queue has been played to the end
 * (`head_position` is then past the last entry).
 */
@Entity(tableName = "playback_cursor")
data class PlaybackCursorRow(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "head_position") val headPosition: Double = 0.0,
    /** Whether the head is active, i.e. some playback of it actually occurred. */
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    /** In-track position of the head. Only written on a ticker during playback. */
    @ColumnInfo(name = "position_ms") val positionMs: Long = 0,
)
