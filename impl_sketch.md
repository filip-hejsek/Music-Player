

# QueueManager

Central class owning the in-memory `QueueState`, the cursor, and all navigation/mutation
operations. Lives on `PlaybackService`.

```kotlin
class QueueManager(
    private val queueDao: QueueDao,
    private val trackRepository: TrackRepository,
    private val scope: CoroutineScope
) {
    private val _queueState = MutableStateFlow(QueueState.Empty)
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    // -- lifecycle --
    suspend fun loadFromDatabase()

    // -- mutation --
    suspend fun addTracks(
        tracks: List<Track>,
        position: InsertPosition = InsertPosition.End,
        group: QueueGroupSource? = null,
        groupLabel: String? = null
    )
    suspend fun removeEntry(entryId: Long)
    suspend fun removeGroup(groupId: Long)
    suspend fun moveEntry(entryId: Long, newIndex: Int)
    suspend fun setRepeatCount(entryId: Long, count: Int?)
    suspend fun insertPauseMarker(afterEntryId: Long)
    suspend fun clearQueue()   // deletes upcoming only
    suspend fun clearHistory() // deletes history only

    // -- navigation --
    suspend fun next(): QueueEntry?
    suspend fun previous(): QueueEntry?

    /** Called when playback reaches the end of the current physical window with no user input. */
    suspend fun onPhysicalAdvance(finishedEntryId: Long?): NextStep
}

enum class InsertPosition { End, PlayNext }

sealed class NextStep {
    data class RepeatSameEntry(val entry: QueueEntry.TrackEntry) : NextStep()
    data class AdvanceTo(val entry: QueueEntry) : NextStep()
    object QueueExhausted : NextStep()
}
```

**`next()` / `previous()` sketch** — always whole-entry jumps, matching the agreed semantics:

```kotlin
suspend fun next(): QueueEntry? {
    val cursor = queueDao.getCursor() ?: return null
    val currentPosition = cursor.currentEntryId?.let { queueDao.getEntryById(it)?.position } ?: return null
    val nextRow = queueDao.getEntryAfter(currentPosition) ?: return null // see §13 re: pause markers
    queueDao.updateCursor(nextRow.id, positionMs = 0)
    refreshState()
    return nextRow.toQueueEntry()
}

suspend fun previous(): QueueEntry? {
    val cursor = queueDao.getCursor() ?: return null
    val currentPosition = cursor.currentEntryId?.let { queueDao.getEntryById(it)?.position } ?: return null
    val prevRow = queueDao.getEntryBefore(currentPosition) ?: return null
    queueDao.updateCursor(prevRow.id, positionMs = 0)
    refreshState()
    return prevRow.toQueueEntry()
}
```

**`onPhysicalAdvance()` sketch** — this is where finite-vs-infinite repeat and
auto-vs-user-driven advancement are actually decided:

```kotlin
suspend fun onPhysicalAdvance(finishedEntryId: Long?): NextStep {
    val row = finishedEntryId?.let { queueDao.getEntryById(it) } ?: return NextStep.QueueExhausted
    if (row.type == QueueEntryType.TRACK) {
        val exhausted = row.repeatCount != null && row.repeatProgress + 1 >= row.repeatCount
        if (!exhausted) {
            val newProgress = row.repeatProgress + 1
            queueDao.updateRepeatProgress(row.id, newProgress)
            return NextStep.RepeatSameEntry(row.copy(repeatProgress = newProgress).toQueueEntry() as QueueEntry.TrackEntry)
        }
    }
    val nextRow = queueDao.getEntryAfter(row.position) ?: return NextStep.QueueExhausted
    queueDao.updateCursor(nextRow.id, positionMs = 0)
    pruneHistoryIfNeeded()
    refreshState()
    return NextStep.AdvanceTo(nextRow.toQueueEntry())
}

private suspend fun pruneHistoryIfNeeded() {
    val cursor = queueDao.getCursor() ?: return
    val position = cursor.currentEntryId?.let { queueDao.getEntryById(it)?.position } ?: return
    val cap = /* Config.historyCap */ 0
    val count = queueDao.getHistoryCount(position)
    if (count > cap) queueDao.pruneOldestHistory(position, count - cap)
}
```

`refreshState()` re-queries history/current/upcoming (split around the cursor position, per
§2.3) and pushes into `_queueState`.

# Physical Timeline Materialization

Since infinite repeats and pause markers rule out precomputing the whole physical list,
materialization is a top-up operation, not a one-shot transform.

```kotlin
private const val TOP_UP_WINDOW_COUNT = 2 // keep this many windows queued past current

class TimelineMaterializer(
    private val player: Player,
    private val queueManager: QueueManager
) {
    /** Call after every transition/discontinuity and after any queue mutation. */
    fun topUpIfNeeded() {
        val windowsAhead = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (windowsAhead >= TOP_UP_WINDOW_COUNT) return
        repeat(TOP_UP_WINDOW_COUNT - windowsAhead) {
            val item = nextPhysicalItem() ?: return // hits a pause marker or end of queue — stop
            player.addMediaItem(item)
        }
    }

    /** Rebuild the physical timeline from scratch, e.g. on restore or after a queue jump. */
    fun rebuildFrom(entry: QueueEntry.TrackEntry, startPositionMs: Long) {
        player.setMediaItems(listOf(entry.toMediaItem(entry.repeatProgress)), 0, startPositionMs)
        player.prepare()
        topUpIfNeeded()
    }

    private fun nextPhysicalItem(): MediaItem? { /* consult QueueManager's logical state
        for what comes after the last materialized entry; returns null at a PauseMarker
        or end of queue */ }
}
```

**Back-reference for identity:**

```kotlin
private const val EXTRA_QUEUE_ENTRY_ID = "queue_entry_id"

fun QueueEntry.TrackEntry.toMediaItem(repeatIndex: Int): MediaItem {
    val base = track.toMediaItem() // existing extension
    val metadata = base.mediaMetadata.buildUpon()
        .setExtras(bundleOf(EXTRA_QUEUE_ENTRY_ID to id))
        .build()
    return base.buildUpon().setMediaMetadata(metadata).build()
}

val MediaItem.queueEntryId: Long?
    get() = mediaMetadata.extras?.getLong(EXTRA_QUEUE_ENTRY_ID)?.takeIf { it != 0L }
```

# Player/Session Integration

**`PlayerListener` replacement** — the existing `STOP_AFTER_CURRENT_TRACK`/
`REPEAT_MODE_ONE` hack is deleted outright; its job is now done structurally by pause
markers plus this:

```kotlin
override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> scope.launch {
            queueManager.onPhysicalAdvance(mediaItem?.queueEntryId)
            timelineMaterializer.topUpIfNeeded()
        }
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
            // user tapped a queue row directly — sync cursor to the tapped entry
        }
        else -> Unit
    }
}
```

**`SimpleMusicPlayer`** — `seekToNext()`/`seekToPrevious()` now delegate to
`QueueManager.next()`/`previous()` and rebuild the physical timeline from the returned
entry, instead of calling `super.seekToNext()`/manipulating `ShuffleOrder`. The
`setShuffleIndices`/`setNextMediaItem`/`ensureItemPlaysNext` methods and their upstream
Media3-limitation workarounds are removed entirely — ordering is decided when materializing,
so there's nothing left for them to work around.

**`MediaSessionCallback`** — `SET_SHUFFLE_ORDER` and `SET_NEXT_ITEM` custom commands and
their handlers are removed (superseded). `onPlaybackResumption` is rewritten — see §9.
`onSetMediaItems`'s "avoid single-item queues" workaround for external controllers (Android
Auto, Assistant) should be re-routed through `QueueManager.addTracks()` rather than raw
player calls, so external-controller-initiated playback still produces a proper logical
queue.

# Persistence Strategy

```kotlin
class QueuePersistence(
    private val queueDao: QueueDao,
    private val serviceScope: CoroutineScope
) {
    /** Eager — call after every structural mutation (add/remove/reorder/cursor move). */
    suspend fun saveCursorEager(entryId: Long?, positionMs: Long) =
        queueDao.updateCursor(entryId, positionMs)

    /** Throttled — started once, runs for the service's lifetime. */
    fun startPositionTicker(player: Player, currentEntryId: () -> Long?) {
        serviceScope.launch {
            while (isActive) {
                delay(5.seconds)
                if (player.isReallyPlaying) {
                    currentEntryId()?.let { saveCursorEager(it, player.currentPosition) }
                }
            }
        }
    }
}
```

Structural writes (`addTracks`, `removeEntry`, `removeGroup`, `moveEntry`, cursor moves from
`next()`/`previous()`) go straight through `QueueDao`'s `@Transaction`-annotated functions
immediately — they're infrequent, so no batching/throttling needed there.

# Restore on Process Death / `onPlaybackResumption`

Reuses the existing two-phase "fast then full" pattern from
`AudioHelper.getQueuedTracksLazily`/`MediaItemProvider.getRecentItemsLazily` (return the
current item immediately so playback/notification can start fast, then load the rest):

```kotlin
override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo
): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
    scope.launch {
        val cursor = queueDao.getCursor()
        val currentRow = cursor?.currentEntryId?.let { queueDao.getEntryById(it) }
        val currentEntry = currentRow?.toQueueEntry() as? QueueEntry.TrackEntry

        if (currentEntry != null) {
            val firstItem = currentEntry.toMediaItem(currentEntry.repeatProgress)
            future.set(MediaSession.MediaItemsWithStartPosition(listOf(firstItem), 0, cursor.positionMs))
        } else {
            future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0))
        }

        queueManager.loadFromDatabase() // populates full QueueState; UI + materializer top-up follow
    }
    return future
}
```

Note `currentEntry.repeatProgress` being fed into `toMediaItem` — this is what prevents a
process killed mid-repeat (say, copy 2 of 3) from resuming at copy 1.

# UI Layer

`QueueActivity` collects `PlaybackService.queueState` (via whatever same-process accessor
mirrors the existing `Companion` playback-state pattern) instead of reading
`currentMediaItemsShuffled` off the player. `QueueAdapter` becomes a pure renderer:

```kotlin
class QueueDiffCallback(
    private val old: List<QueueEntry>,
    private val new: List<QueueEntry>
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
    override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
}
```

`data class` structural equality (from §4.1) is what makes `areContentsTheSame` correct with
no extra work — this is one of the payoffs of modeling `QueueEntry` as a sealed class of data
classes rather than mutable objects.

Row actions (drag-reorder, remove, remove-group, clear history/queue) call into
`QueueManager` — either directly if UI and service are guaranteed same-process, or via new
`CustomCommands` entries if you want to keep the existing controller-command channel as the
UI's only path to the service:

```kotlin
enum class CustomCommands {
    // existing entries...
    REMOVE_QUEUE_ENTRY,
    REMOVE_QUEUE_GROUP,
    MOVE_QUEUE_ENTRY,
    CLEAR_HISTORY,
    CLEAR_QUEUE,
}
```

# Existing Code: What Changes, What Doesn't

#### Untouched
`SimpleMediaScanner`, `AudioHelper`'s library-scan methods (`getAllTracks`, album/artist
rebuild logic), `TagHelper`, notification handling, `SimpleEqualizer`, `MediaItemProvider`'s
browse-tree building (`buildAlbums`/`buildArtists`/etc.) — none of this is queue-related.

#### Modified
| File                             | Change                                                                                                                                                                                                                                                                   |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SongsDatabase`                  | New entities registered, schema version bumped, migration added (§12)                                                                                                                                                                                                    |
| `MediaSessionCallback`           | `onPlaybackResumption` rewritten (§9); `SET_SHUFFLE_ORDER`/`SET_NEXT_ITEM` removed; `onSetMediaItems` re-routed through `QueueManager`                                                                                                                                   |
| `SimpleMusicPlayer`              | `seekToNext`/`seekToPrevious` delegate to `QueueManager`; shuffle-order hack methods removed                                                                                                                                                                             |
| `PlayerListener`                 | `onMediaItemTransition` rewritten around `onPhysicalAdvance`; repeat-mode hack removed                                                                                                                                                                                   |
| `Player.kt` extensions           | `setRepeatMode`, `maybeForceNext/Previous`, `maybeRestartOnPrevious`, `addRemainingMediaItems`, `prepareUsingTracks`, `maybePreparePlayer` — superseded by `QueueManager`/`TimelineMaterializer`; widget (`MyWidgetProvider`) needs an equivalent cold-start entry point |
| `QueueActivity` / `QueueAdapter` | Rewritten as state-driven renderer (§10)                                                                                                                                                                                                                                 |
| `Config`                         | `playbackSetting` (repeat enum) likely removed — "stop after current" becomes "insert a pause marker after current", a `QueueManager.insertPauseMarker()` call, not a global player mode; `isShuffleEnabled`'s role shifts to "shuffle at queue-build time" (see §13)    |

#### Removed
`QueueItem.kt`, `QueueItemsDao.kt`, the `queue_items` table (after migration, §12), and the
`EXTRA_SHUFFLE_INDICES`/`EXTRA_NEXT_MEDIA_ID` custom-command extras.

Explicitly **not** addressed here: `Playlist`/`Track`'s denormalized one-row-per-playlist-
membership schema. Adding a playlist's tracks to the queue only needs to *read* via the
existing `PlaylistsDao` query — it doesn't require changing how playlists are stored. That's
a separate, larger piece of work if you want it later.

# Migration Path

On first launch after the update, a one-time migration reads the old `queue_items` table
(if present) and converts it into the new schema before dropping it:

```kotlin
// Room Migration, sketch:
val MIGRATION_OLD_QUEUE = object : Migration(OLD_VERSION, NEW_VERSION) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE queue_entries (...)")
        db.execSQL("CREATE TABLE queue_groups (...)")
        db.execSQL("CREATE TABLE playback_cursor (...)")
        // read old queue_items ordered by track_order, insert as QueueEntryRow(type=TRACK,
        // repeatCount=1, groupId=null, position = index.toDouble()); old is_current row
        // becomes the playback_cursor row.
        db.execSQL("DROP TABLE queue_items")
    }
}
```

# Open Decisions / Follow-ups

**Same-process assumption.** We assume `QueueState` can be exposed directly from
`PlaybackService` to the UI in-process (matching the existing `Companion` static-field
pattern). If external controllers (Android Auto, Assistant) ever need live queue/history
visibility beyond what `MediaLibrarySession`'s browse tree already exposes, that's an
additional design surface not covered here.

