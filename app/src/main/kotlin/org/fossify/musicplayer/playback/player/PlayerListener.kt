package org.fossify.musicplayer.playback.player

import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.extensions.toast
import org.fossify.musicplayer.playback.PlaybackService
import org.fossify.musicplayer.playback.PlaybackService.Companion.queueController

@UnstableApi
internal fun PlaybackService.getPlayerListener() = object : Player.Listener {

    override fun onPlayerError(error: PlaybackException) = toast(org.fossify.commons.R.string.unknown_error_occurred, Toast.LENGTH_LONG)

    override fun onEvents(player: Player, events: Player.Events) {
        if (
            events.containsAny(
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TRACKS_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYLIST_METADATA_CHANGED
            )
        ) {
            updatePlaybackState()
        }
    }

    /**
     * The player moved on to the next physical item by itself (or repeated the current one): the logical queue
     * has to catch up. Transitions we caused ourselves (rebuilding the timeline, seeking) are not "finished tracks".
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            queueController?.onTrackFinished()
        }
    }

    /**
     * The timeline ends at a pause marker and at the end of the queue, where the player has no next item.
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            queueController?.onTrackFinished()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            queueController?.savePositionNow()
        }
    }
}
