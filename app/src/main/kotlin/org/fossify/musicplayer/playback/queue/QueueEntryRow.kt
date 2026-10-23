package org.fossify.musicplayer.playback.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class QueueEntryType { TRACK, PAUSE_MARKER }

@Entity(
    tableName = "queue_entries",
    foreignKeys = [
        ForeignKey(
            entity = QueueGroupRow::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("position"), Index("group_id")],
)
data class QueueEntryRow(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** Fractional so that an entry can be inserted or moved by writing a single row. */
    @ColumnInfo(name = "position") val position: Double,
    @ColumnInfo(name = "type") val type: QueueEntryType,
    /** [org.fossify.musicplayer.models.Track.mediaStoreId]; TRACK only. */
    @ColumnInfo(name = "track_id") val trackId: Long? = null,
    @ColumnInfo(name = "completed_repetitions") val completedRepetitions: Int = 0,
    /** `null` = infinite; TRACK only. */
    @ColumnInfo(name = "remaining_repetitions") val remainingRepetitions: Int? = 1,
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
)
