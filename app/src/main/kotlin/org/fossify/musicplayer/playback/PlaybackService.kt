package org.fossify.musicplayer.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.os.postDelayed
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.showErrorToast
import org.fossify.musicplayer.extensions.audioHelper
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.extensions.isReallyPlaying
import org.fossify.musicplayer.extensions.nextMediaItem
import org.fossify.musicplayer.extensions.queueEntriesDAO
import org.fossify.musicplayer.extensions.runOnPlayerThread
import org.fossify.musicplayer.helpers.NotificationHelper
import org.fossify.musicplayer.helpers.getPermissionToRequest
import org.fossify.musicplayer.playback.library.MediaItemProvider
import org.fossify.musicplayer.playback.player.SimpleMusicPlayer
import org.fossify.musicplayer.playback.player.initializeSessionAndPlayer
import org.fossify.musicplayer.playback.queue.LooperDispatcher
import org.fossify.musicplayer.playback.queue.QueueController
import org.fossify.musicplayer.playback.queue.QueueManager
import org.fossify.musicplayer.playback.queue.QueueState

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), MediaSessionService.Listener {
    internal lateinit var player: SimpleMusicPlayer
    internal lateinit var playerListener: Player.Listener
    internal lateinit var mediaSession: MediaLibrarySession
    internal lateinit var mediaItemProvider: MediaItemProvider

    internal var currentRoot = ""

    // Queue operations touch the ExoPlayer instance (see TimelineMaterializer), so they run on the player thread.
    private lateinit var queueScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        setListener(this)
        initializeSessionAndPlayer(handleAudioFocus = true, handleAudioBecomingNoisy = true)
        initializeLibrary()
        initializeQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSession()
        clearListener()
        stopSleepTimer()
        SimpleEqualizer.release()
        player.navigator = null
        queueController?.release()
        queueController = null
        _queueState.value = null
        queueScope.cancel()
    }

    fun stopService() {
        withPlayer {
            pause()
            stop()
        }

        stopSelf()
    }

    private fun initializeLibrary() {
        mediaItemProvider = MediaItemProvider(this)
        if (hasPermission(getPermissionToRequest())) {
            mediaItemProvider.reload()
        } else {
            showNoPermissionNotification()
        }
    }

    private fun initializeQueue() {
        queueScope = CoroutineScope(SupervisorJob() + LooperDispatcher(player.applicationLooper))
        val manager = QueueManager(
            dao = queueEntriesDAO,
            // blocking Room queries, so keep them off the manager's coroutine thread
            loadTracks = { withContext(Dispatchers.IO) { audioHelper.getAllTracks().associateBy { it.mediaStoreId } } },
            stateFlow = _queueState,
            historyCap = { config.historyCap },
            scope = queueScope,
        )

        val controller = QueueController(player, manager, _queueState, queueScope)
        player.navigator = controller
        queueController = controller
        controller.start()
    }

    private fun releaseMediaSession() {
        mediaSession.release()
        withPlayer {
            removeListener(playerListener)
            release()
        }
    }

    internal fun withPlayer(callback: SimpleMusicPlayer.() -> Unit) {
        player.runOnPlayerThread { callback(this) }
    }

    private fun showNoPermissionNotification() {
        Handler(Looper.getMainLooper()).postDelayed(delayInMillis = 100L) {
            try {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.createInstance(this).createNoPermissionNotification()
                )
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * This method is only required to be implemented on Android 12 or above when an attempt is made
     * by a media controller to resume playback when the {@link MediaSessionService} is in the
     * background.
     */
    override fun onForegroundServiceStartNotAllowedException() {
        showErrorToast(getString(org.fossify.commons.R.string.unknown_error_occurred))
        // todo: show a notification instead.
    }

    companion object {
        // Initializing a media controller might take a noticeable amount of time thus we expose current playback info here to keep things as quick as possible.
        var isPlaying: Boolean = false
            private set
        var currentMediaItem: MediaItem? = null
            private set
        var nextMediaItem: MediaItem? = null
            private set

        private val _queueState = MutableStateFlow<QueueState?>(null)

        /**
         * The logical queue; `null` until it has been loaded from Room, which is different from an empty queue.
         * The service doesn't declare a separate process, so UI and service share this.
         */
        val queueState: StateFlow<QueueState?> = _queueState.asStateFlow()

        /** Set while the service is alive; UI dispatches queue intents through it. */
        var queueController: QueueController? = null
            private set

        fun updatePlaybackInfo(player: Player) {
            currentMediaItem = player.currentMediaItem
            nextMediaItem = player.nextMediaItem
            isPlaying = player.isReallyPlaying
        }
    }
}

