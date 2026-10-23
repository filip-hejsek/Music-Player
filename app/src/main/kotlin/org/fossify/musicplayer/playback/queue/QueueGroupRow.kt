package org.fossify.musicplayer.playback.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_groups")
data class QueueGroupRow(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "source") val source: QueueGroupSource,
    @ColumnInfo(name = "source_id") val sourceId: Long? = null,
)
