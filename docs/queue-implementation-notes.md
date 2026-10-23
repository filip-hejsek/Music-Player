# Queue rework: implementation notes

Branch: `claude/queue-db-ui` (based on `dev`). Untested and never compiled (no Android SDK in the sandbox), so expect small compile errors.

## What exists
- `playback/queue/`: Room entities, `QueueDao` (atomic `commit(QueueMutation)`), `QueueManager` (logical queue, cursor, navigation, shuffle/sort/undo, groups, pause markers, history cap), `TimelineMaterializer`, `QueueController` (glue between manager and player), `LooperDispatcher`.
- DB v16: `MIGRATION_15_16` converts `queue_items` to the new tables and drops it.
- `PlaybackService.queueState` (`StateFlow<QueueState?>`, null = not loaded) and `PlaybackService.queueController`.
- UI: `QueueActivity`/`QueueAdapter` render `QueueState` only and send intents through `queueController`. `TrackActivity` shuffle button = shuffle action (long press undoes); repeat button cycles off -> repeat forever -> pause marker after current.

## Decisions and deviations
- Timeline window is 5 physical items (`TIMELINE_WINDOW_SIZE`). A pause marker or an infinitely repeating entry ends the materialized window; `REPEAT_MODE_ONE` is used only while an infinite entry is current.
- Physical `mediaId` is `"<entryId>:<repetition>"`, with entry id/repetition in metadata extras. `isSameMedia` compares the track's `mediaStoreId` from the extras.
- Consecutive pause markers: `insertPauseMarker` refuses to place one next to another (the TBD option "disallow"). A marker that ends up at an inactive head is dropped.
- Position is saved every 5 s and on pause; everything else is written eagerly.
- Clicking a track adds it to the end of the queue and starts playback only if the queue was empty. Files opened from other apps and external controllers (Android Auto) are queued right after the current entry and played immediately.
- `PlaybackSetting.REPEAT_PLAYLIST`, `Config.isShuffleEnabled`, `Config.playbackSetting` and the `SET_NEXT_ITEM`/`SET_SHUFFLE_ORDER` commands are removed.

## Not done / open
- `onAddMediaItems` from external controllers still lets Media3 append raw items to the timeline, bypassing the queue.
- No UI for `historyCap` (default 100, `Config.historyCap`).
- Album-level "add to queue" with an `ALBUM` group is not wired; multi-track adds create an unlabeled `MANUAL` group.
- The migration turns an old "whole library" queue into history + queue; history is only pruned to the cap on the next advance.
