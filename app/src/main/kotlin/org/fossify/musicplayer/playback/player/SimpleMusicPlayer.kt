package org.fossify.musicplayer.playback.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.fossify.musicplayer.helpers.RESTART_ON_PREVIOUS_THRESHOLD
import org.fossify.musicplayer.playback.queue.QueueNavigator

@UnstableApi
class SimpleMusicPlayer(private val exoPlayer: ExoPlayer) : ForwardingPlayer(exoPlayer) {

    /**
     * Decides what plays next. The ExoPlayer timeline only holds the few items around the current one,
     * so navigation can't be left to it: the notification, headset buttons and other controllers all end up
     * here, and are routed to the logical queue.
     */
    var navigator: QueueNavigator? = null

    /**
     * The default implementation only advertises the seek to next and previous item in the case
     * that it's not the first or last track. We manually advertise that these
     * are available to ensure next/previous buttons are always visible.
     */
    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands()
            .buildUpon()
            .addAll(
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
            .build()
    }

    override fun play() {
        navigator?.onPlayRequested()
        super.play()
    }

    override fun seekToNext() {
        val navigator = navigator
        if (navigator != null) {
            navigator.onNextRequested()
        } else {
            super.seekToNext()
        }
    }

    override fun seekToPrevious() {
        if (currentPosition > RESTART_ON_PREVIOUS_THRESHOLD) {
            seekTo(0)
            play()
            return
        }

        val navigator = navigator
        if (navigator != null) {
            navigator.onPreviousRequested()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNextMediaItem() = seekToNext()

    override fun seekToPreviousMediaItem() = seekToPrevious()

    override fun getAudioSessionId() = exoPlayer.audioSessionId

    fun setSkipSilence(skipSilence: Boolean) {
        exoPlayer.skipSilenceEnabled = skipSilence
    }
}
