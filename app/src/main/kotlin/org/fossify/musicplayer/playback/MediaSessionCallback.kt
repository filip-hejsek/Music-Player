package org.fossify.musicplayer.playback

import android.os.Bundle
import android.os.ConditionVariable
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import org.fossify.musicplayer.extensions.toTracks
import org.fossify.musicplayer.playback.PlaybackService.Companion.queueController
import java.util.concurrent.Executors

@UnstableApi
internal fun PlaybackService.getMediaSessionCallback() = object : MediaLibrarySession.Callback {
    private val browsers = mutableMapOf<MediaSession.ControllerInfo, String>()
    private val executorService by lazy {
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4))
    }

    private fun <T> callWhenSourceReady(action: () -> T): ListenableFuture<T> {
        val conditionVariable = ConditionVariable()
        return if (mediaItemProvider.whenReady { conditionVariable.open() }) {
            executorService.submit<T> {
                action()
            }
        } else {
            executorService.submit<T> {
                conditionVariable.block()
                action()
            }
        }
    }

    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val resultBuilder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        if (controller.isTrusted) {
            val availableSessionCommands = DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            for (command in customCommands) {
                availableSessionCommands.add(command)
            }
            resultBuilder.setAvailableSessionCommands(availableSessionCommands.build())
        }

        return Futures.immediateFuture(resultBuilder.build())
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        val customLayout = getCustomLayout()
        if (customLayout.isNotEmpty() && controller.controllerVersion != 0) {
            mediaSession.setCustomLayout(controller, customLayout)
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val command = CustomCommands.fromSessionCommand(customCommand)
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))

        when (command) {
            CustomCommands.CLOSE_PLAYER -> stopService()
            CustomCommands.RELOAD_CONTENT -> reloadContent()
            CustomCommands.TOGGLE_SLEEP_TIMER -> toggleSleepTimer()
        }

        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (params != null && params.isRecent) {
            // The service currently does not support recent playback. Tell System UI by returning
            // an error of type 'RESULT_ERROR_NOT_SUPPORTED' for a `params.isRecent` request. See
            // https://github.com/androidx/media/issues/355
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        }

        return Futures.immediateFuture(
            LibraryResult.ofItem(
                mediaItemProvider.getRootItem(),
                params
            )
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ) = callWhenSourceReady {
        currentRoot = parentId
        val children = mediaItemProvider.getChildren(parentId)
            ?: return@callWhenSourceReady LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

        LibraryResult.ofItemList(children, params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ) = callWhenSourceReady {
        val item = mediaItemProvider[mediaId]
            ?: return@callWhenSourceReady LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

        LibraryResult.ofItem(item, null)
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?
    ) = callWhenSourceReady {
        val children = mediaItemProvider.getChildren(parentId)
            ?: return@callWhenSourceReady LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

        browsers[browser] = parentId
        session.notifyChildrenChanged(browser, parentId, children.size, params)
        LibraryResult.ofVoid()
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val queue = queueController
        if (queue == null) {
            settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0))
        } else {
            queue.launchOnQueue {
                val (items, startPositionMs) = resumptionTimeline()
                settableFuture.set(MediaSession.MediaItemsWithStartPosition(items, 0, startPositionMs))
            }
        }

        return settableFuture
    }

    /**
     * Controllers that bring their own queue (Android Auto, assistants) don't get to replace the timeline:
     * their items are queued right after the current entry and the first of them starts playing, and the
     * session is handed the timeline derived from the logical queue.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val queue = queueController
            ?: return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0))

        val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        callWhenSourceReady {
            // this is to avoid single items in the queue: https://github.com/androidx/media/issues/156
            val resolved = mediaItems.map { mediaItemProvider[it.mediaId] ?: it }
            val startItemId = resolved.getOrNull(startIndex)?.mediaId ?: resolved.firstOrNull()?.mediaId
            val currentItems = mediaItemProvider.getChildren(currentRoot).orEmpty()
            val queueItems = when {
                resolved.size > 1 -> resolved
                currentItems.any { it.mediaId == startItemId } -> currentItems
                else -> mediaItemProvider.getDefaultQueue() ?: resolved
            }

            val tracks = queueItems.toTracks()
            val startTrackIndex = queueItems.indexOfFirst { it.mediaId == startItemId }.coerceAtLeast(0)
            queue.launchOnQueue {
                val items = playTracksNow(tracks, startTrackIndex)
                settableFuture.set(MediaSession.MediaItemsWithStartPosition(items, 0, startPositionMs.coerceAtLeast(0)))
            }
        }

        return settableFuture
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return executorService.submit<List<MediaItem>> {
            val items = mediaItems.map { mediaItem ->
                if (mediaItem.requestMetadata.searchQuery != null) {
                    getMediaItemFromSearchQuery(mediaItem.requestMetadata.searchQuery!!)
                } else {
                    mediaItemProvider[mediaItem.mediaId] ?: mediaItem
                }
            }
            items
        }
    }

    private fun getMediaItemFromSearchQuery(query: String): MediaItem {
        return mediaItemProvider.getItemFromSearch(query.lowercase()) ?: mediaItemProvider.getRandomItem()
    }

    private fun reloadContent() {
        mediaItemProvider.reload()
        mediaItemProvider.whenReady {
            val rootItem = mediaItemProvider.getRootItem()
            val rootItemCount = mediaItemProvider.getChildren(rootItem.mediaId)?.size ?: 0

            executorService.execute {
                browsers.forEach { (browser, parentId) ->
                    val itemCount = mediaItemProvider.getChildren(parentId)?.size ?: 0
                    mediaSession.notifyChildrenChanged(browser, parentId, itemCount, null)
                    mediaSession.notifyChildrenChanged(browser, rootItem.mediaId, rootItemCount, null)
                }
            }
        }
    }
}
