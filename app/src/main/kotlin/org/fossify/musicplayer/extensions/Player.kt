package org.fossify.musicplayer.extensions

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.fossify.musicplayer.helpers.RESTART_ON_PREVIOUS_THRESHOLD
import org.fossify.musicplayer.playback.PlaybackService

val Player.isReallyPlaying: Boolean
    get() = when (playbackState) {
        Player.STATE_ENDED, Player.STATE_IDLE -> false
        else -> isPlaying || playWhenReady
    }

/**
 * The materialized timeline: the current item and the few that follow. This is not the queue, see
 * [org.fossify.musicplayer.playback.queue.QueueState] for that.
 */
val Player.currentMediaItems: List<MediaItem>
    get() = (0 until (mediaItemCount)).map { getMediaItemAt(it) }

/** The item that will play after the current one, if it is already in the timeline. */
val Player.nextMediaItem: MediaItem?
    get() = if (hasNextMediaItem()) getMediaItemAt(nextMediaItemIndex) else null

inline fun <T : Player> T.runOnPlayerThread(crossinline callback: T.() -> Unit) =
    applicationLooper.post {
        callback()
    }

fun Player.togglePlayback() {
    if (isReallyPlaying) {
        pause()
    } else {
        play()
    }
}

fun Player.maybeRestartOnPrevious(): Boolean {
    if (currentPosition > RESTART_ON_PREVIOUS_THRESHOLD) {
        seekTo(0)
        return true
    } else {
        return false
    }
}

/**
 * Makes sure the player is prepared on the saved queue head, if there's one. The queue itself lives in Room and is
 * loaded by the playback service, so this only has to ask the service to prepare the player: first with the
 * current entry only, then the few items that follow (see `TimelineMaterializer`).
 */
var prepareInProgress = false
inline fun Player.maybePreparePlayer(@Suppress("UNUSED_PARAMETER") context: Context, crossinline callback: (success: Boolean) -> Unit) {
    val queue = PlaybackService.queueController
    if (!prepareInProgress && currentMediaItem == null && queue != null) {
        prepareInProgress = true
        queue.restore { success ->
            prepareInProgress = false
            callback(success)
        }
    } else {
        callback(false)
    }
}
