# Music Player (personal fork)

A stripped-down, opinionated personal fork of Fossify Music Player
(upstream: `FossifyOrg/Music-Player`). Features here are too personal or specific for
upstream; no effort goes into making it usable for a general audience.

## Background

- Stack: Android, Kotlin, Room, Media3/ExoPlayer, Gradle.
- Installed package id is `io.github.filiphejsek.musicplayer`. `applicationId` (`APP_ID`) is
  decoupled from `namespace` (`APP_NAMESPACE`), so the Kotlin source tree keeps the upstream
  package name. Don't rename the source packages.
- Reasons for the fork:
  - Custom queue and playlist behavior (see [docs/queue-redesign.md](docs/queue-redesign.md)).
  - No file-destructive features: file deletion and track/playlist renaming were removed, since
    files are organized externally with a file manager. Intentionally kept: playlist creation
    (`NewPlaylistDialog`), the non-destructive "remove playlist" action in `PlaylistsAdapter`, and
    the internal DB housekeeping in `AudioHelper.deleteTracks()` used by `SimpleMediaScanner`.
- Org-coupled infrastructure (Fossify CI workflows, fastlane publishing, `.fossify/`, CODEOWNERS,
  issue templates, ...) was removed.
- Gapless playback works well in practice; don't regress it.
- Unused imports left over from the feature-stripping pass are meant to be removed in a later,
  dedicated cleanup pass.

## Queue/history rework

- [docs/queue-redesign.md](docs/queue-redesign.md) is the design spec and the source of truth for
  the data model, Room storage, semantics and ExoPlayer timeline. Parts marked **Suggestion**
  may be deviated from; **Requirement**s may not.
- Motivation: two upstream bugs (the playlist highlight not updating, and a brief audio snippet
  playing before stopping in "stop after current track" mode) share a root cause: queue
  entries are identified by track ID rather than a stable per-entry ID.
- Still open in the spec: handling of consecutive pause markers, and whether groups must consist
  of consecutive entries.
- The first implementation (UI and database part) is on branch `claude/queue-db-ui`; see
  [docs/queue-implementation-notes.md](docs/queue-implementation-notes.md). The `tmp/impl_sketch`
  branch has a loose guide (`impl_sketch.md`) that should not be followed blindly.

## Git workflow

- Base new work on `dev` unless told otherwise.
- Push nontrivial changes so they aren't lost. You are encouraged to create new branches in the
  `claude/` namespace.
- Filip may edit the history on `dev`, but he'll do so carefully so as not to disrupt your ongoing
  work. Only Filip may force-push to `dev`. You should never force-push to any branch other than
  those you have created yourself in the same session within the `claude/` namespace.

## Building

Use `./gradlew assembleDebug` to build the app.

The Android SDK should already be set up in the environment; notify the user if it isn't.
In cloud sessions, some maven repositories may answer 429, in which case retrying the build
usually helps (downloads are cached).
