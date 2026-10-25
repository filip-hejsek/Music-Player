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

Groups should ideally consist of consecutive queue entries, but this is **not enforced**.
Group membership is a plain tag with no positional invariant. Consequences, as implemented:
- Moving an entry out of a group's range leaves it in the group.
- A pause marker inserted between two entries of the same group joins that group, so the group
  stays visually contiguous; otherwise a marker has no group.
- Removing a group removes only its not-yet-played entries (history and the active entry stay).
- A group is deleted once its last entry is gone (done in the same transaction).
- Several tracks added in one operation get an unlabeled `MANUAL` group (blank label) unless the
  caller supplies a `QueueGroupSpec`. Adding a single track creates no group.

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

History has a configurable cap (`Config.historyCap`, default 100, no UI yet); the oldest entries are
pruned in the same transaction as any cursor move (next, previous, jump, track finished), never at
other times. A history that is over the cap for another reason (e.g. migrated from the old queue)
therefore stays over it until the next move.

**TODO:** consider if we want to fix that? the migration is going to be removed anyway, so maybe we don't need to.

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
    val history: List<QueueEntry.TrackEntry>,   // never contains pause markers
    val current: QueueEntry.TrackEntry?,        // the head, if it is active
    val upcoming: List<QueueEntry>,             // everything after `current`; includes the head if it is not active
    val groups: Map<Long, QueueGroup> = emptyMap(),
    val canUndoReorder: Boolean = false,        // the last shuffle/sort can still be reverted
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

**TODO:** review the design of `canUndoReorder`


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

When a repetition finishes, `completedRepetitions` goes up and a finite `remainingRepetitions`
goes down. If nothing remains, the entry leaves the head with `remainingRepetitions == 0`, which
is the one case where a history entry has zero remaining repetitions (see **previous** below).

When a pause marker is reached, playback is stopped and the pause marker is **removed**.
The player advances to the next item in the queue, but it doesn't start playing until
the user hits the play button.

**Requirement.** The history never contains pause marker entries.

**Decision.** Consecutive pause markers are disallowed at insertion: a marker can't be placed next
to another one (the operation reports failure). Moving entries can still make markers adjacent,
and removals can too, so playback also handles it defensively: reaching a run of markers consumes
all of them at once, with a single stop. A marker that ends up at the head while nothing is active
(e.g. dragged to the front of a waiting queue) has nothing left to pause and is dropped.
A marker can only be inserted after the active entry or after an upcoming entry, not into history.

**TODO:** Coalesce pause markers.

### Navigation

- **Next** advances the cursor and **marks the history entry as skipped**.
- **Previous** moves the cursor back
- Both always **skip all remaining repeats** of the current entry
- **Next** honors pause markers: the marker is removed, and playback stops on the entry after it
  (that entry becomes the head but is not active)
- Tapping an entry (jump) moves the head there in either direction. Entries jumped over go to
  history (forwards) or return to the queue (backwards). Pause markers jumped over are removed,
  since history never contains them.
- **TODO:** Reconsider this design. Tapping a history item should just append another copy of it to
  the queue. And tapping a queue item shouldn't skip ahead either.
- **Previous** with an empty history restarts the current track.
- **TODO:** better behavior for Previous restarting the current track
  (most likely: it always first restarts the track, then moves back, like in normal players)

#### What happens to the repetition counters when navigating?
E.g. user is on repeat 2 of 4, presses next, then previous.
In this situation, the number of remaining repeats must be **preserved**.

Navigation never touches the counters. It only moves the cursor, so the entry
leaves the head with its remaining repeats intact, and gets them back when previous pops it
out of history. Only finishing a repetition changes the counters (`completedRepetitions`
goes up by one, a finite `remainingRepetitions` goes down by one).

The one exception is an entry that was played to the end: it has `remainingRepetitions == 0`
in history. When previous (or a backwards jump) returns it to the queue, its remaining count is
reset to 1, i.e. it plays once more, so that the invariant "an entry in the queue has at least one
remaining repetition" holds. Entries that were only skipped keep their remaining repeats untouched.

With this model "skipped" doesn't need its own field: a history entry was skipped if and only if
it has no completions (`completedRepetitions == 0`). An entry that is left after at least one
completed repetition counts as played, even if its remaining repetitions were skipped. An entry in
the queue or active always has at least one remaining repetition.

**Current implementation.** Editing the repeat count sets the *total* number of plays (or infinite);
completed repetitions count towards it, and the result is clamped so at least one repetition
remains. It works on the active entry and on upcoming entries, not on history.

**TODO:** Reconsider if this is the right approach. A likely better alternative is to instead edit the *remaining* count.

### Shuffle

There is no "shuffle" playback mode. Instead, **shuffle** is an action that can be performed
on the queue. The following operations are supported:
- shuffle all
- shuffle a group
- undo a shuffle
- sort by album and track number

These only permute the upcoming *track* entries among the positions those entries
already occupy. Pause markers and (for "shuffle a group") entries of other groups stay exactly where
they are. The active entry and history are never touched.

As implemented, sorting compares album title (case-insensitive), disc number, track number and title.

Undo is a single level: it remembers the positions before the last shuffle/sort and is discarded by
any other change to the queue (including playback advancing). It restores only entries that still exist.

**Question.** WHat does "It restores only entries that still exist" mean?

**TODO:** Don't discard undo positions when playback advances. Instead just restore only positions of items that remain in queue (not active or history).

In the UI, the shuffle button in `TrackActivity` performs "shuffle all" (long press undoes), and
the repeat button cycles: off, repeat forever, pause marker after the current entry.

### Adding items to the queue

Clicking on a track adds it to the **end** of the queue. If the queue was empty, playback starts immediately.

A whole album can be added at once. If the queue was empty, playback will not start
automatically, so the user can adjust the queue before playing (e.g. shuffle).

**TODO:**  Currently all multi-track adds produce an unlabeled `MANUAL` group; adding an album with an
`ALBUM` group (and a label) is not wired up yet.

**Current implementation.** Tracks from outside the app are different, since they ask to be played:
files opened from other apps and controllers that bring their own queue (Android Auto, assistants)
are inserted right after the current entry (`PlayNext`) and the first requested one is jumped to and
played immediately. Whatever was queued before stays in the queue. A new session timeline is derived
from the logical queue and handed back to the session. Still open: `onAddMediaItems` from external
controllers lets Media3 append raw items to the timeline, bypassing the queue.

**TODO:** review this design (low priority)

### Active entry

The entry that is currently playing is considered *active*. The active entry is, in some respects,
a part of history rather than the queue. It cannot be reordered. If at least one repeat has completed,
it already shows up in history. But unlike true history entries, the active item is not immutable.

An entry is considerd active only if some playback actually occured. When the player is paused,
there may be no active entry, even when the queue is not empty. This means that no entry will be active
after encountering a pause marker.

**Current implementation.** The head becomes active when playback of it actually starts
(`markActive`, triggered by the play request), and stays active across repetitions. Moving the
cursor sets the new active flag from whether playback should continue (`play`), except that stopping
at a pause marker, or running out of tracks, leaves nothing active.

### Persistence

All playback state should be persisted when the app is closed. After reopening,
playback will be paused, but all other state will be restored.

On start, the queue is loaded from Room and the player is prepared (paused) on the saved head at
the saved position. If the process was killed and a controller asks to resume, the timeline is
derived from the stored queue the same way.

**Current implementation.** Entries whose track is gone (the media scanner recreates `tracks` rows)
are skipped during playback and hidden from the state, but their rows stay in the database, since
the track may come back. Tracks are loaded once and cached; the `tracks` table is re-read only when
a queued track id is not in the cache, and not again for ids already known to be missing.


Persistent Data Storage
-----------------------

**Requirement.** All persistent queue data is stored in a Room database.
Thhis includes the entries, groups and the cursor.

It's desirable to be able to commit all state atomically. SharedPreferences should
ideally store only actual preferences, and no state.

During playback, in-track position should only be written on a periodic ticker (~5–10s)
to avoid excessive writes. As implemented: every 5 s while playing, plus on pause (and on any cursor
move, which resets it to 0). **This is a change from the existing code.** Everything other
than playback time can (and should) still be written eagerly.

Queue items can be reordered by the user, so choose a suitable ordering representation.

Ordering uses a fractional (`Double`) position. Inserting or moving an entry writes
one row. When the gap between neighbours drops below `1e-6`, all positions are renumbered to
1.0, 2.0, ... in one transaction, moving the cursor boundary with them.


The ExoPlayer Timeline
----------------------

**Requirement.** The ExoPlayer timeline is *derived* from the logical queue. It's a lossy
representation of the queue, and is built incrementally ("materialized") a couple of windows
ahead of playback.

**Requirement.** Each `MediaItem` carries a back-reference to its source logical entry ID and
repetition number (via metadata extras).

How to materialize items:
- There must be an upper bound on how many `MediaItem`s are in the timeline at any given time.
  Only materialize a fixed window (`TIMELINE_WINDOW_SIZE`, currently 5 physical items). Prune old entries before materializing new ones. Window size
  is counted in physical items, not logical entries, so that a large repeat count doesn't
  result in a large number of `MediaItem`s.
- Media3's native `shuffleModeEnabled`/`ShuffleOrder` should never be used. The existing
  `SET_SHUFFLE_ORDER`/`setShuffleIndices` hack should be removed.
- Repetitions should be handled by generating duplicate `MediaItem`s. As an optimization,
  `repeatMode` can be used when an item with infinite repeats is playing.

As implemented:
- The materialized window starts at the head and is cut off at a pause marker, so the last item
  before the marker ends playback; the logical queue then consumes the marker. An entry with infinite
  repeats is materialized once and nothing after it; while it is the current item, `REPEAT_MODE_ONE`
  is on, otherwise the repeat mode is always off.
- Physical `mediaId` is `"<entryId>:<repetition>"` (repetition is 0-based and continues from
  `completedRepetitions`), so every physical item has a distinct id. The entry id and repetition
  are also in the metadata extras (`EXTRA_QUEUE_ENTRY_ID`, `EXTRA_QUEUE_REPETITION`).
  `MediaItem.isSameMedia()` compares the track's `mediaStoreId` from the extras instead of `mediaId`.
- After queue edits or when the player advanced by itself, the timeline is refreshed in place:
  already-played items are dropped, and the items after the current one are only replaced from the
  first one that differs, so the item prepared for gapless playback survives. Only if the player
  isn't on the head (the head changed) is the whole timeline rebuilt, which restarts playback.


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
QueueController  (glue; player events and UI intents)
        │ ↑ NavigationResult        ↑ intents
        ▼ │
TimelineMaterializer  <───  QueueManager (action handlers)
        │
        ▼
   ExoPlayer timeline (via SimpleMusicPlayer / MediaController)
```

`QueueManager` only deals with the logical queue and knows nothing about ExoPlayer. Every operation
computes a `QueueMutation` (new entries/group, deletions, new positions, new counters, new cursor)
from an in-memory snapshot, commits it through one `QueueDao.commit()` transaction, then reloads the
snapshot from Room and publishes the new `QueueState`. Room is always the source of truth.
Navigation operations return a `NavigationResult` (new head, whether it is active, whether a pause
marker stopped playback, whether the same entry repeats).

`QueueController` is the new glue class that the spec didn't have. It reacts to player events
(play requested, next/previous requested, a repetition finished), calls the manager, and brings the
timeline in line through `TimelineMaterializer`. The player never decides what plays next: when it
finishes an item it reports that, and the manager works out what follows. The UI sends its intents
through the controller too (`post`, `jumpTo`, `addTracks`, ...), so each is followed by a timeline sync.

The UI never talks to the `Player` timeline directly and never mutates its own local list —
it renders the queue state and sends intents back. This is a change from the existing code:
`QueueActivity` currently builds its list from the controller's timeline
(`currentMediaItemsShuffled`), and `QueueAdapter` reorders through the `SET_SHUFFLE_ORDER`
custom command.

### Exposing the state

`QueueManager` and `QueueController` live on `PlaybackService`
(`PlaybackService.queueState` and `PlaybackService.queueController`; `queueState` is a
`StateFlow<QueueState?>`, `null` = not loaded). The service doesn't declare a separate process in the
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

All queue mutations run on a coroutine scope bound to the player's looper (`LooperDispatcher`),
like `PlaybackService.withPlayer { }` does today, because materialization touches the `ExoPlayer`
instance. In addition, `QueueManager` serializes operations with a mutex. Room access goes through
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

All of the above has been removed (including `PlaybackSetting.REPEAT_PLAYLIST`, `Config.playbackSetting`
and `FLAG_IS_CURRENT`). The following now route through the queue instead of the timeline:
- `SimpleMusicPlayer.seekToNext()`/`seekToPrevious()`, since the notification, headset buttons
  and other controllers reach the player this way
- `MediaSessionCallback.onSetMediaItems()`/`onAddMediaItems()`, for external controllers

`MediaItem.mediaId` is currently the track's `mediaStoreId`, and several places assume it
identifies a window: `MediaItem.isSameMedia()`, `SimpleMusicPlayer.setNextMediaItem()`,
`SimpleControllerActivity` (filtering the tracks it adds by `mediaId`),
`MediaSessionCallback.onSetMediaItems()`. Materialized items from the same track share that id,
so these had to change. Resolved by giving every physical item a distinct `mediaId` (see above).


Room DB Schema
--------------

The implementation follows the schema below, with the deviations listed in each subsection. Entities
live in `QueueEntryRow.kt`, `QueueGroupRow.kt` and `PlaybackCursorRow.kt`, and the singleton cursor
row is created on first load (`ensureCursor`).

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

`historyCap` is a plain integer setting (like the existing `playbackSpeed`), not something that needs transactional consistency — keep it in
`Config`, as a member next to the other settings, with the key and default in `Constants.kt`:

```kotlin
var historyCap: Int
    get() = prefs.getInt(HISTORY_CAP, DEFAULT_HISTORY_CAP)
    set(value) = prefs.edit().putInt(HISTORY_CAP, value).apply()
```

### DAO

This is an `abstract class` rather than an `interface`, so that the `@Transaction` methods can have a body.
The existing DAOs are blocking, but `room-ktx` is already a dependency, so `suspend` works.

**Deviation from the original suggestion.** The DAO does not have one query per operation
(`deleteQueue`, `deleteGroupFromQueue`, `pruneOldestHistory`, `getEntryAfter`, ...). The manager works
out what changes from its in-memory snapshot and the DAO only applies it, so history pruning,
group deletion and so on need no dedicated SQL and always commit together with the cursor move.

```kotlin
data class QueueMutation(
    val newGroup: QueueGroupRow? = null,          // inserted first; its id is assigned to every new entry
    val newEntries: List<QueueEntryRow> = emptyList(),
    val deleteIds: Collection<Long> = emptyList(),
    val positions: Map<Long, Double> = emptyMap(),        // entry id -> new position
    val repetitions: Map<Long, Repetitions> = emptyMap(),  // entry id -> new counters
    val cursor: PlaybackCursorRow? = null,
)

@Dao
abstract class QueueDao {
    getAll(): List<QueueEntryRow>            // ORDER BY position, id
    getAllGroups(): List<QueueGroupRow>
    getCursor(): PlaybackCursorRow?
    ensureCursor(cursor): Long               // INSERT OR IGNORE, for fresh installs
    setCursor(cursor)                        // INSERT OR REPLACE
    updatePositionMs(positionMs)             // the ticker; touches nothing else
    updateIsActive(isActive)

    /** Applies a whole QueueMutation in one transaction; returns ids of inserted entries. */
    @Transaction open suspend fun commit(mutation: QueueMutation): List<Long>

    /** Replace-everything. Don't use clearAllTables(): it would wipe tracks, playlists etc. */
    @Transaction open suspend fun replaceAll(entries, groups, cursor)
    // plus the single-row primitives used by commit(): insertGroup, insertEntries,
    // updateEntryPosition, updateRepetitions, deleteEntries (chunked, SQLite variable limit),
    // deleteEmptyGroups, deleteAllEntries, deleteAllGroups
}
```

`commit()` order: insert group, delete, update positions, update counters, insert entries, set cursor,
delete empty groups. When groups are replaced via `replaceAll`, entries are inserted with their
`groupId` as given, so the groups need explicit ids.

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

The `tracks` map is built from `AudioHelper.getAllTracks()` (keyed by `mediaStoreId`, as
`AudioHelper.getQueuedTracks()` did) and cached; see **Persistence** for when it is refreshed.
A missing track yields a null entry; the row is kept.

### Database version and migration

The database is at version 16. `MIGRATION_15_16` creates the three tables and converts the old
`queue_items` rows: each becomes a track entry that plays once, the old current track becomes the head
(everything before it is history, which is why the history may start out over the cap), the cursor is
active if the old `last_position` was greater than 0, and `queue_items` is dropped. The old queue is
stored in unshuffled order, so a shuffled order is not preserved.

**Note.** The package name was changed in the fork, so upgrading from versions before the fork
is impossible, and there is no release of the fork yet. The migration's usefulness is therefore
questionable, and properly testing its correctness is not worth the effort.

**TODO:** The migration should be removed, along with all migrations from previous versions.

The `UPDATE` queries on `playback_cursor` do nothing if the singleton row doesn't exist, so
the row must be inserted on first load for fresh installs.


Code Organization
-----------------

Most of the new code lives in a new package, `playback/queue/`.

```
playback/
  queue/
    QueueEntry.kt            // sealed class, QueueGroup, QueueGroupSource, QueueState
    QueueEntryRow.kt         // Room entity
    QueueGroupRow.kt         // Room entity
    PlaybackCursorRow.kt     // Room entity
    QueueDao.kt              // Room DAO, plus QueueMutation and Repetitions
    QueueMappers.kt          // Row <-> logical model
    QueueManager.kt          // owns queue state, cursor, navigation; no ExoPlayer knowledge
    TimelineMaterializer.kt  // logical entries -> physical MediaItems
    QueueController.kt       // glue between manager and player; position ticker; UI intents
    LooperDispatcher.kt      // coroutine dispatcher on the player's looper
```

UI additions: `QueueActivity`/`QueueAdapter` render `QueueState` only (grouped rows, pause markers,
group headers, a repeat-count dialog) and send intents through `queueController`.


Known gaps
----------

- `historyCap` has no UI.
- Album-level "add to queue" with a labeled `ALBUM` group is not wired up.
- `onAddMediaItems` from external controllers bypasses the queue.
- The migration question above.
- and others
