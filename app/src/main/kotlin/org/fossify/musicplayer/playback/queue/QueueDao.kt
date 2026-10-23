package org.fossify.musicplayer.playback.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class Repetitions(val completed: Int, val remaining: Int?)

/**
 * Everything one logical queue operation changes, committed atomically by [QueueDao.commit].
 *
 * [QueueManager] works out *what* changes from its in-memory snapshot; the DAO only applies it.
 */
data class QueueMutation(
    /** Inserted first; its generated id is assigned to every row in [newEntries]. */
    val newGroup: QueueGroupRow? = null,
    val newEntries: List<QueueEntryRow> = emptyList(),
    val deleteIds: Collection<Long> = emptyList(),
    /** entry id -> new position */
    val positions: Map<Long, Double> = emptyMap(),
    /** entry id -> new counters */
    val repetitions: Map<Long, Repetitions> = emptyMap(),
    val cursor: PlaybackCursorRow? = null,
)

/**
 * This is an `abstract class` rather than an `interface` so that the `@Transaction` methods can have a body.
 */
@Dao
abstract class QueueDao {
    @Query("SELECT * FROM queue_entries ORDER BY position, id")
    abstract suspend fun getAll(): List<QueueEntryRow>

    @Query("SELECT * FROM queue_groups")
    abstract suspend fun getAllGroups(): List<QueueGroupRow>

    @Query("SELECT * FROM playback_cursor WHERE id = 0")
    abstract suspend fun getCursor(): PlaybackCursorRow?

    /** Creates the singleton row on a fresh install; a no-op if it exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun ensureCursor(cursor: PlaybackCursorRow): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setCursor(cursor: PlaybackCursorRow)

    /** For the periodic ticker; touches nothing but the in-track position. */
    @Query("UPDATE playback_cursor SET position_ms = :positionMs WHERE id = 0")
    abstract suspend fun updatePositionMs(positionMs: Long)

    @Query("UPDATE playback_cursor SET is_active = :isActive WHERE id = 0")
    abstract suspend fun updateIsActive(isActive: Boolean)

    @Insert
    abstract suspend fun insertGroup(group: QueueGroupRow): Long

    @Insert
    abstract suspend fun insertEntries(entries: List<QueueEntryRow>): List<Long>

    @Query("UPDATE queue_entries SET position = :position WHERE id = :id")
    abstract suspend fun updateEntryPosition(id: Long, position: Double)

    @Query("UPDATE queue_entries SET completed_repetitions = :completed, remaining_repetitions = :remaining WHERE id = :id")
    abstract suspend fun updateRepetitions(id: Long, completed: Int, remaining: Int?)

    @Query("DELETE FROM queue_entries WHERE id IN (:ids)")
    abstract suspend fun deleteEntries(ids: List<Long>)

    /** Groups don't cascade from their entries, so clean up the ones that ended up empty. */
    @Query("DELETE FROM queue_groups WHERE id NOT IN (SELECT DISTINCT group_id FROM queue_entries WHERE group_id IS NOT NULL)")
    abstract suspend fun deleteEmptyGroups()

    @Query("DELETE FROM queue_entries")
    abstract suspend fun deleteAllEntries()

    @Query("DELETE FROM queue_groups")
    abstract suspend fun deleteAllGroups()

    /**
     * Applies a whole [QueueMutation] in one transaction and returns the ids of the inserted entries
     * (in the order of [QueueMutation.newEntries]).
     */
    @Transaction
    open suspend fun commit(mutation: QueueMutation): List<Long> {
        val groupId = mutation.newGroup?.let { insertGroup(it) }

        // SQLite has a limit on the number of variables in one statement.
        mutation.deleteIds.toList().chunked(DELETE_CHUNK_SIZE).forEach { deleteEntries(it) }
        mutation.positions.forEach { (id, position) -> updateEntryPosition(id, position) }
        mutation.repetitions.forEach { (id, r) -> updateRepetitions(id, r.completed, r.remaining) }

        val newIds = if (mutation.newEntries.isEmpty()) {
            emptyList()
        } else {
            insertEntries(mutation.newEntries.map { if (groupId != null) it.copy(groupId = groupId) else it })
        }

        mutation.cursor?.let { setCursor(it) }
        deleteEmptyGroups()
        return newIds
    }

    /**
     * Structural replace-everything, used for full queue rebuilds (e.g. "play this album now").
     * Don't use `RoomDatabase.clearAllTables()` here: it would wipe `tracks`, `playlists` etc. as well.
     *
     * Entries are inserted with their [QueueEntryRow.groupId] as given, so the groups need explicit ids.
     */
    @Transaction
    open suspend fun replaceAll(
        entries: List<QueueEntryRow>,
        groups: List<QueueGroupRow>,
        cursor: PlaybackCursorRow,
    ) {
        deleteAllEntries()
        deleteAllGroups()
        groups.forEach { insertGroup(it) }
        insertEntries(entries)
        setCursor(cursor)
    }

    private companion object {
        const val DELETE_CHUNK_SIZE = 500
    }
}
