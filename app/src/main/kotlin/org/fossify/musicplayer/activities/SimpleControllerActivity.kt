package org.fossify.musicplayer.activities

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import org.fossify.commons.extensions.toast
import org.fossify.musicplayer.R
import org.fossify.musicplayer.extensions.*
import org.fossify.musicplayer.helpers.SimpleMediaController
import org.fossify.musicplayer.models.Events
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.PlaybackService
import org.fossify.musicplayer.playback.queue.InsertPosition
import org.greenrobot.eventbus.EventBus

/**
 * Base class for activities that want to control the [Player].
 */
abstract class SimpleControllerActivity : SimpleActivity(), Player.Listener {
    private lateinit var controller: SimpleMediaController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SimpleMediaController.getInstance(this)
        maybePreparePlayer()
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(this)
    }

    override fun onStop() {
        super.onStop()
        controller.removeListener(this)
    }

    override fun onResume() {
        super.onResume()
        maybePreparePlayer()
    }

    open fun onPlayerPrepared(success: Boolean) {}

    fun withPlayer(callback: MediaController.() -> Unit) = controller.withController(callback)

    /**
     * Clicking a track adds it to the queue, and playback starts if the queue was empty. With [playNow] the track
     * is queued right after the current one and played immediately (e.g. a file opened from another app).
     */
    fun prepareAndPlay(
        tracks: List<Track>,
        startIndex: Int = 0,
        startPositionMs: Long = 0,
        startActivity: Boolean = true,
        playNow: Boolean = false,
    ) {
        val track = tracks.getOrNull(startIndex) ?: return
        withPlayer {
            val queue = PlaybackService.queueController ?: return@withPlayer
            val wasEmpty = PlaybackService.queueState.value?.isQueueEmpty != false
            if (startActivity && (wasEmpty || playNow)) {
                startActivity(Intent(this@SimpleControllerActivity, TrackActivity::class.java))
            }

            if (playNow) {
                queue.playNow(listOf(track))
            } else {
                queue.addTracks(listOf(track), startIfEmpty = true)
                if (!wasEmpty) {
                    toast(R.string.added_to_queue)
                }
            }
        }
    }

    fun maybePreparePlayer() {
        withPlayer {
            maybePreparePlayer(context = this@SimpleControllerActivity, callback = ::onPlayerPrepared)
        }
    }

    fun togglePlayback() = withPlayer { togglePlayback() }

    fun addTracksToQueue(tracks: List<Track>, callback: () -> Unit) {
        withPlayer {
            PlaybackService.queueController?.addTracks(tracks)
            callback()
        }
    }

    fun playNextInQueue(track: Track, callback: () -> Unit) {
        withPlayer {
            PlaybackService.queueController?.addTracks(listOf(track), where = InsertPosition.PlayNext)
            callback()
        }
    }

    fun refreshQueueAndTracks(@Suppress("UNUSED_PARAMETER") trackToUpdate: Track? = null) {
        PlaybackService.queueController?.refreshTracks()

        EventBus.getDefault().post(Events.RefreshTracks())
    }
}
