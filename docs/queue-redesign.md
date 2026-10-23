Design specification: Queue & History Rework
============================================

Goals
-----

Replace the existing queue system (a thin wrapper around ExoPlayer's `Player` timeline,
persisted via the `queue_items` Room table keyed by track ID) with a custom logical queue
model.

This should make it possible to implement these features:
- Per-track repeat counts, including infinite repeat
- "Pause here" markers that truncate playback until the user resumes
- Grouping of consecutive entries (e.g. "this whole album was added at once") that can be
  removed as a unit
- A unified queue/history model, where history is mostly-immutable and separately clearable

It is not a goal to change anything unrelated to queue/history management.


Data Model
----------

### Queue Entries

Two entry kinds: a track, a pause marker.

**Requirement.** Every entry has a uniquely generated identifier.
Track ID / `mediaStoreId` is insufficient since there can be multiple entries for a single track.

**Track Entries:**
- the track
- the number of completed repetitions
- the number of remaining repetitions (may be infinite)

**Pause Markers:** no properties

Every track entry carries both counters, whether it is in the queue or in history, so that
an entry can move between the two (see **previous**) without forgetting anything.
The total repeat count is not stored; it is `completedRepetitions + remainingRepetitions`.
The remaining count includes the repetition currently being played.

**Suggestion.** Possible implementation:
```kotlin
sealed class QueueEntry {
    abstract val id: Long
    abstract val groupId: Long?    // (see the next section)

    data class TrackEntry(
        override val id: Long,
        override val groupId: Long? = null,
        val track: Track,
        val completedRepetitions: Int = 0,
        val remainingRepetitions: Int? = 1,    // null = infinite
    ) : QueueEntry()

    data class PauseMarker(
        override val id: Long,
        override val groupId: Long? = null,
    ) : QueueEntry()
}
```

### Groups

Each entry may optionally be part of a group.
The player will group items that are added to the queue together in one operation.

Groups should ideally consist of consecutive queue entries. TBD if this is enforced.

**Suggestion.** Group membership could be a plain tag, no positional invariant enforced.

**Suggestion.** Possible implementation:
```kotlin
enum class QueueGroupSource { ALBUM, PLAYLIST, MANUAL }

data class QueueGroup(
    val id: Long,
    val label: String,    // denormalized (e.g. album title) to avoid a join on read and for manual labels
    val source: QueueGroupSource,
    val sourceId: Long?,
)
```

### Queue and History

There is a single list containing current, future and past queue entries.
A **cursor** splits it into a queue and a history.

History has a configurable cap; oldest entries are pruned once the cursor advances past it.

The cursor must be well-defined even when no entry is active (after a pause marker, or when
the queue has been played to the end), so it is a *boundary* in the list, not a pointer to
an entry:
- Entries before the boundary are history.
- The first entry at or after the boundary is the **head**: the entry that is playing, or that
  will play next.
- A flag says whether the head is **active** (see "Active entry" below).

**Suggestion.** Possible implementation:
```kotlin
data class QueueState(
    val history: List<QueueEntry>,
    val current: QueueEntry?,       // the head, if it is active
    val upcoming: List<QueueEntry>, // everything after `current`; includes the head if it is not active
) {
    companion object {
        val Empty = QueueState(emptyList(), null, emptyList())
    }
}

data class Cursor(
    val headPosition: Double,   // entries with a smaller position are history
    val isActive: Boolean,
    val positionMs: Long,       // in-track position of the head
)
```


Semantics
---------

### History is immutable

**Requirement.** Normal operations never modify the history.

Triggering automatic pruning of old entries doesn't count as modification. History *can*
be modified by a few dedicated operations (e.g. clearing, single item removal). One operation
is special: **previous**. It moves the cursor backward, popping the latest history item
back into the queue.

### Playback

Should be mostly obvious. User can start and stop playback. When a track ends, it either
repeats, or the next track starts playing. This is done by advancing the cursor, which
automatically puts the finished track in history.

Entries with infinite repeat count still track the current number of repeats
(`completedRepetitions` keeps counting up, `remainingRepetitions` stays infinite).

When a pause marker is reached, playback is stopped and the pause marker is **removed**.
The player advances to the next item in the queue, but it doesn't start playing until
the user hits the play button.

**Requirement.** The history never contains pause marker entries.

TBD: What to do with multiple consecutive pause marker entries. Options:
- Disallow them
- Coalesce them
- Handle them during playback

### Navigation

- **Next** advances the cursor and **marks the history entry as skipped**.
- **Previous** moves the cursor back
- Both always **skip all remaining repeats** of the current entry
- **Next** honors pause markers

#### What happens to the repetition counters when navigating?
E.g. user is on repeat 2 of 4, presses next, then previous.
In this situation, the number of remaining repeats must be **preserved**.

**Suggestion.** Navigation never touches the counters. It only moves the cursor, so the entry
leaves the head with its remaining repeats intact, and gets them back when previous pops it
out of history. Only finishing a repetition changes the counters (`completedRepetitions`
goes up by one, a finite `remainingRepetitions` goes down by one).

With this model "skipped" doesn't need its own field: a history entry was skipped if and only if
it has no completions (`completedRepetitions == 0`). An entry that is left after at least one
completed repetition counts as played, even if its remaining repetitions were skipped. An entry in
the queue or active always has at least one remaining repetition; editing the repeat count of the
active entry clamps to that.

### Shuffle

There is no "shuffle" playback mode. Instead, **shuffle** is an action that can be performed
on the queue. The following operations are supported:
- shuffle all
- shuffle a group
- undo a shuffle
- sort by album and track number

### Adding items to the queue

Clicking on a track adds it to the queue. If the queue was empty, playback starts immediately.

A while album can be added at once. If the queue was empty, playback will not start
automatically, so the user can adjust the queue before playing (e.g. shuffle).

### Active entry

The entry that is currently playing is considered *active*. The active entry is, in some respects,
a part of history rather than the queue. It cannot be reordered. If at least one repeat has completed,
it already shows up in history. But unlike true history entries, the active item is not immutable.

An entry is considerd active only if some playback actually occured. When the player is paused,
there may be no active entry, even when the queue is not empty. This means that no entry will be active
after encountering a pause marker.

### Persistence

All playback state should be persisted when the app is closed. After reopening,
playback will be paused, but all other state will be restored.


Persistent Data Storage
-----------------------

**Requirement.** All persistent queue data is stored in a Room database.
Thhis includes the entries, groups and the cursor.

It's desirable to be able to commit all state atomically. SharedPreferences should
ideally store only actual preferences, and no state.

During playback, in-track position should only be written on a periodic ticker (~5–10s)
to avoid excessive writes. **This is a change from the existing code.** Everything other
than playback time can (and should) still be written eagerly.

Queue items can be reordered by the user, so choose a suitable ordering representation.

**Suggestion.** You can represent ordering with a fractional (`Double`) position.


The ExoPlayer Timeline
----------------------

**Requirement.** The ExoPlayer timeline is *derived* from the logical queue. It's a lossy
representation of the queue, and is built incrementally ("materialized") a couple of windows
ahead of playback.

**Requirement.** Each `MediaItem` carries a back-reference to its source logical entry ID and
repetition number (via metadata extras).

How to materialize items:
- There must be an upper bound on how many `MediaItem`s are in the timeline at any given time.
  Only materialize a fixed window. Prune old entries before materializing new ones. Window size
  is counted in physical items, not logical entries, so that a large repeat count doesn't
  result in a large number of `MediaItem`s.
- Media3's native `shuffleModeEnabled`/`ShuffleOrder` should never be used. The existing
  `SET_SHUFFLE_ORDER`/`setShuffleIndices` hack should be removed.
- Repetitions should be handled by generating duplicate `MediaItem`s. As an optimization,
  `repeatMode` can be used when an item with infinite repeats is playing.


Architecture
------------

**Data flow, one-way:**

```
Room (queue_entries, queue_groups, playback_cursor)
        ↕ (load/save)
   QueueManager  ──owns──>  QueueState (StateFlow)
        │                         ↑ collected by
        │                    QueueActivity / QueueAdapter (render-only)
        │                         │ dispatches actions (move/remove/clear/...)
        ▼                         ▼
TimelineMaterializer  <───  QueueManager (action handlers)
        │
        ▼
   ExoPlayer timeline (via SimpleMusicPlayer / MediaController)
```

The UI never talks to the `Player` timeline directly and never mutates its own local list —
it renders the queue state and sends intents back. This is a change from the existing code:
`QueueActivity` currently builds its list from the controller's timeline
(`currentMediaItemsShuffled`), and `QueueAdapter` reorders through the `SET_SHUFFLE_ORDER`
custom command.

### Exposing the state

`QueueManager` lives on `PlaybackService`. The service doesn't declare a separate process in the
manifest, so UI and service share one, and `queueState` can be exposed the way the existing
playback state is: `PlaybackService.Companion` already mirrors `isPlaying`, `currentMediaItem`
and `nextMediaItem` into static fields (updated by `updatePlaybackInfo()`) for fast reads
without waiting for a `MediaController` connection. No new cross-process mechanism is needed.

Differences from the existing fields:
- `queueState` is a `StateFlow` rather than a plain `var`, because it is written from the
  player thread and collected on the main thread, and because the UI needs change notifications.
- The static field is empty whenever the process starts, until the manager has loaded Room.
  The UI must be able to tell "not loaded yet" from "queue is empty", so expose
  `StateFlow<QueueState?>` (`null` = not loaded) or add an `isLoaded` flag. `QueueState.Empty`
  alone is not enough.
- `kotlinx-coroutines` is only a transitive dependency today (through `room-ktx`);
  add it explicitly if `StateFlow` is used.

### Threading

All queue mutations are serialized on the player thread, like `PlaybackService.withPlayer { }`
does today, because materialization touches the `ExoPlayer` instance. Room access goes through
the suspend DAO, which runs on the database's query executor.

### Existing code to rewire

The new queue replaces these pieces:
- `QueueItem`, `QueueItemsDao`, `Context.queueDAO`, and the `queue_items` table in `SongsDatabase`
- `AudioHelper.getQueuedTracks()`, `getQueuedTracksLazily()`, `initQueue()`, `resetQueue()`
- `MediaItemProvider.getRecentItemsLazily()`, `getRecentItemsWithStartPosition()`,
  `saveRecentItemsWithStartPosition()`. The last one is called from `updatePlaybackState()` on
  every player event and rewrites the whole queue each time, which is what the position
  ticker replaces.
- `SET_NEXT_ITEM` and `SET_SHUFFLE_ORDER` in `CustomCommands`, with their handlers in
  `MediaSessionCallback` and the `SimpleMusicPlayer.setNextMediaItem()`/`setShuffleIndices()` hacks
- The shuffle-aware helpers in `extensions/Player.kt` (`shuffledMediaItemsIndices`,
  `currentMediaItemsShuffled`, ...) and the shuffle toggle in `TrackActivity`/`Config.isShuffleEnabled`

These must route through the `QueueManager` instead of the timeline:
- `SimpleMusicPlayer.seekToNext()`/`seekToPrevious()`, since the notification, headset buttons
  and other controllers reach the player this way
- `MediaSessionCallback.onSetMediaItems()`/`onAddMediaItems()`, for external controllers

`MediaItem.mediaId` is currently the track's `mediaStoreId`, and several places assume it
identifies a window: `MediaItem.isSameMedia()`, `SimpleMusicPlayer.setNextMediaItem()`,
`SimpleControllerActivity` (filtering the tracks it adds by `mediaId`),
`MediaSessionCallback.onSetMediaItems()`. Materialized items from the same track share that id,
so these must switch to the entry ID / repetition extras from the previous section, or the
materializer must give every physical item a distinct `mediaId`.


Room DB Schema
--------------

**This whole section is only a suggestion.** You can use any reasonable database schema you like.

### Entities

```kotlin
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
    @ColumnInfo(name = "position") val position: Double,
    @ColumnInfo(name = "type") val type: QueueEntryType,
    @ColumnInfo(name = "track_id") val trackId: Long? = null,       // TRACK only
    @ColumnInfo(name = "completed_repetitions") val completedRepetitions: Int = 0, // TRACK only
    @ColumnInfo(name = "remaining_repetitions") val remainingRepetitions: Int? = 1, // TRACK only, null = infinite
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
)

@Entity(tableName = "queue_groups")
data class QueueGroupRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val source: QueueGroupSource,
    @ColumnInfo(name = "source_id") val sourceId: Long? = null,
)

/**
 * Singleton row (id is always 0) holding the cursor.
 * Entries with position < head_position are history; the first entry at or after
 * head_position is the head.
 */
@Entity(tableName = "playback_cursor")
data class PlaybackCursorRow(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "head_position") val headPosition: Double = 0.0,
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    @ColumnInfo(name = "position_ms") val positionMs: Long = 0,
)
```

Notes:
- There is no "skipped" column; see "Navigation".
- A boundary needs no special case for an exhausted queue: when everything has been played,
  `head_position` is past the last entry, and entries added later are appended at or after it.
  Removing the head entry also needs no pointer fix-up.
- There is deliberately no foreign key from `track_id` to `tracks`. Rows in `tracks` are deleted
  and recreated by the media scanner, so a queue entry can outlive its track. The mapper must
  handle a missing track (see below).
- `track_id` stores `Track.mediaStoreId`, as the old `queue_items` did. Tracks without a MediaStore
  id (`mediaStoreId == 0`, e.g. `FLAG_MANUAL_CACHE`) are not unique under that key; this is
  an existing limitation, but now it would affect entries that should be distinct.

`historyCap` is a plain integer setting (like the existing `playbackSpeed`/
`isShuffleEnabled`), not something that needs transactional consistency — keep it in
`Config`, as a member next to the other settings, with the key and default in `Constants.kt`:

```kotlin
var historyCap: Int
    get() = prefs.getInt(HISTORY_CAP, DEFAULT_HISTORY_CAP)
    set(value) = prefs.edit().putInt(HISTORY_CAP, value).apply()
```

### DAO

This is an `abstract class` rather than an `interface`, so that the `@Transaction` method can have a body.
The existing DAOs are blocking, but `room-ktx` is already a dependency, so `suspend` works.

```kotlin
@Dao
abstract class QueueDao {
    @Query("SELECT * FROM queue_entries ORDER BY position")
    abstract suspend fun getAll(): List<QueueEntryRow>

    @Query("SELECT * FROM queue_entries WHERE id = :id")
    abstract suspend fun getEntryById(id: Long): QueueEntryRow?

    @Query("SELECT * FROM queue_entries WHERE position > :fromPosition ORDER BY position LIMIT 1")
    abstract suspend fun getEntryAfter(fromPosition: Double): QueueEntryRow?

    @Query("SELECT * FROM queue_entries WHERE position < :fromPosition ORDER BY position DESC LIMIT 1")
    abstract suspend fun getEntryBefore(fromPosition: Double): QueueEntryRow?

    @Query("SELECT * FROM queue_groups WHERE id = :id")
    abstract suspend fun getGroup(id: Long): QueueGroupRow?

    @Insert
    abstract suspend fun insertEntries(entries: List<QueueEntryRow>): List<Long>

    @Insert
    abstract suspend fun insertGroup(group: QueueGroupRow): Long

    @Query("UPDATE queue_entries SET position = :position WHERE id = :id")
    abstract suspend fun updateEntryPosition(id: Long, position: Double)

    @Query(
        "UPDATE queue_entries SET completed_repetitions = :completed, remaining_repetitions = :remaining WHERE id = :id"
    )
    abstract suspend fun updateRepetitions(id: Long, completed: Int, remaining: Int?)

    @Query("DELETE FROM queue_entries WHERE id = :id")
    abstract suspend fun deleteEntry(id: Long)

    /** Queue = everything from the head on, except the active entry (which can't be removed this way). */
    @Query("DELETE FROM queue_entries WHERE position >= :headPosition AND id IS NOT :activeEntryId")
    abstract suspend fun deleteQueue(headPosition: Double, activeEntryId: Long?)

    @Query("DELETE FROM queue_entries WHERE position < :headPosition")
    abstract suspend fun deleteHistory(headPosition: Double)

    @Query(
        "DELETE FROM queue_entries WHERE group_id = :groupId AND position >= :headPosition AND id IS NOT :activeEntryId"
    )
    abstract suspend fun deleteGroupFromQueue(groupId: Long, headPosition: Double, activeEntryId: Long?)

    /** Groups don't cascade from their entries, so clean up the ones that ended up empty. */
    @Query("DELETE FROM queue_groups WHERE id NOT IN (SELECT DISTINCT group_id FROM queue_entries WHERE group_id IS NOT NULL)")
    abstract suspend fun deleteEmptyGroups()

    @Query("SELECT COUNT(*) FROM queue_entries WHERE position < :headPosition")
    abstract suspend fun getHistoryCount(headPosition: Double): Int

    @Query(
        """
        DELETE FROM queue_entries WHERE id IN (
            SELECT id FROM queue_entries
            WHERE position < :headPosition
            ORDER BY position ASC LIMIT :count
        )
        """
    )
    abstract suspend fun pruneOldestHistory(headPosition: Double, count: Int)

    @Query("SELECT * FROM playback_cursor WHERE id = 0")
    abstract suspend fun getCursor(): PlaybackCursorRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setCursor(cursor: PlaybackCursorRow)

    /** Structural cursor change (next/previous/pause marker): written together with the entry changes. */
    @Query(
        "UPDATE playback_cursor SET head_position = :headPosition, is_active = :isActive, position_ms = :positionMs WHERE id = 0"
    )
    abstract suspend fun updateCursor(headPosition: Double, isActive: Boolean, positionMs: Long)

    /** For the periodic ticker; touches nothing but the in-track position. */
    @Query("UPDATE playback_cursor SET position_ms = :positionMs WHERE id = 0")
    abstract suspend fun updatePositionMs(positionMs: Long)

    @Query("DELETE FROM queue_entries")
    abstract suspend fun deleteAllEntries()

    @Query("DELETE FROM queue_groups")
    abstract suspend fun deleteAllGroups()

    /**
     * Structural replace-everything, used for full queue rebuilds (e.g. "play this album now").
     * Don't use `RoomDatabase.clearAllTables()` here: it would wipe `tracks`, `playlists` etc. as well.
     */
    @Transaction
    open suspend fun replaceAll(
        entries: List<QueueEntryRow>,
        groups: List<QueueGroupRow>,
        cursor: PlaybackCursorRow
    ) {
        deleteAllEntries()
        deleteAllGroups()
        groups.forEach { insertGroup(it) }
        insertEntries(entries)
        setCursor(cursor)
    }
}
```

When groups are replaced, the group ids in `entries` must be remapped to the ids returned by
`insertGroup`, or inserted with explicit ids.

### Mappers

```kotlin
/** Returns null when the track no longer exists (see the note above); callers drop such entries. */
fun QueueEntryRow.toQueueEntry(tracks: Map<Long, Track>): QueueEntry? = when (type) {
    QueueEntryType.TRACK -> tracks[trackId]?.let { track ->
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
        id = id, position = position, type = QueueEntryType.TRACK,
        trackId = track.mediaStoreId, completedRepetitions = completedRepetitions,
        remainingRepetitions = remainingRepetitions, groupId = groupId
    )
    is QueueEntry.PauseMarker -> QueueEntryRow(
        id = id, position = position, type = QueueEntryType.PAUSE_MARKER, groupId = groupId
    )
}
```

Build the `tracks` map once per load from `AudioHelper.getAllTracks()` (keyed by `mediaStoreId`,
as `AudioHelper.getQueuedTracks()` does today) instead of querying per row.

### Database version and migration

A migration has been implemented, but its usefulness is questionable as the package name was changed,
so it's not possible to upgrade from versions before fork, and there is no release yet for the fork.

The migration should be removed, along with all migrations from previous versions.

The `UPDATE` queries on `playback_cursor` do nothing if the singleton row doesn't exist, so
the row must be inserted on first load for fresh installs.


Code Organization
-----------------

**Requirement.** Most of the new code lives in a new package, `playback/queue/`.

**Suggestion.** Possible code organization:
```
playback/
  queue/
    QueueEntry.kt            // sealed class — logical model
    QueueEntryRow.kt         // Room entity
    QueueGroupRow.kt         // Room entity
    PlaybackCursorRow.kt     // Room entity
    QueueDao.kt              // Room DAO
    QueueMappers.kt          // Row <-> logical model
    QueueManager.kt          // owns queue state, cursor, navigation
    TimelineMaterializer.kt  // logical entries -> physical MediaItems
    QueuePersistence.kt      // write scheduling
```
