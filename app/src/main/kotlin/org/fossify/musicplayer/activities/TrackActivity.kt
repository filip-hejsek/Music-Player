package org.fossify.musicplayer.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.os.postDelayed
import androidx.core.view.GestureDetectorCompat
import androidx.media3.common.MediaItem
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.musicplayer.R
import org.fossify.musicplayer.databinding.ActivityTrackBinding
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.extensions.getCoverArtHeight
import org.fossify.musicplayer.extensions.getTrackCoverArt
import org.fossify.musicplayer.extensions.getTrackFromUri
import org.fossify.musicplayer.extensions.isReallyPlaying
import org.fossify.musicplayer.extensions.loadGlideResource
import org.fossify.musicplayer.extensions.nextMediaItem
import org.fossify.musicplayer.extensions.sendCommand
import org.fossify.musicplayer.extensions.toTrack
import org.fossify.musicplayer.extensions.updatePlayPauseIcon
import org.fossify.musicplayer.fragments.PlaybackSpeedFragment
import org.fossify.musicplayer.helpers.PlaybackSetting
import org.fossify.musicplayer.helpers.SEEK_INTERVAL_S
import org.fossify.musicplayer.interfaces.PlaybackSpeedListener
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.CustomCommands
import org.fossify.musicplayer.playback.PlaybackService
import org.fossify.musicplayer.playback.queue.QueueEntry
import org.fossify.musicplayer.playback.queue.QueueState
import java.text.DecimalFormat
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

class TrackActivity : SimpleControllerActivity(), PlaybackSpeedListener {
    companion object {
        private const val SWIPE_DOWN_THRESHOLD = 100
        private const val UPDATE_INTERVAL_MS = 150L
    }

    private var isThirdPartyIntent = false
    private lateinit var nextTrackPlaceholder: Drawable

    private val handler = Handler(Looper.getMainLooper())

    private val binding by viewBinding(ActivityTrackBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.nextTrackHolder))
        nextTrackPlaceholder =
            resources.getColoredDrawableWithColor(R.drawable.ic_headset, getProperTextColor())
        setupButtons()
        setupFlingListener()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackService.queueState.collect { renderQueueState(it) }
            }
        }

        binding.apply {
            activityTrackToolbar.setNavigationOnClickListener { finish() }
            nextTrackHolder.background = getProperBackgroundColor().toDrawable()
            nextTrackHolder.setOnClickListener {
                startActivity(Intent(applicationContext, QueueActivity::class.java))
            }

            isThirdPartyIntent = intent.action == Intent.ACTION_VIEW
            if (isThirdPartyIntent && (savedInstanceState == null || PlaybackService.currentMediaItem == null)) {
                initThirdPartyIntent()
                return
            }

            setupTrackInfo(PlaybackService.currentMediaItem)
            setupNextTrackInfo(PlaybackService.nextMediaItem)
            activityTrackPlayPause.updatePlayPauseIcon(
                isPlaying = PlaybackService.isPlaying,
                color = getProperTextColor()
            )
            updatePlayerState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateTextColors(binding.activityTrackHolder)
        binding.activityTrackTitle.setTextColor(getProperTextColor())
        binding.activityTrackArtist.setTextColor(getProperTextColor())
        updatePlayerState()
        updateTrackInfo()
    }

    override fun onPause() {
        super.onPause()
        cancelProgressUpdate()
    }

    override fun onStop() {
        super.onStop()
        cancelProgressUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelProgressUpdate()
        if (isThirdPartyIntent && !isChangingConfigurations) {
            withPlayer {
                if (!isReallyPlaying) {
                    sendCommand(CustomCommands.CLOSE_PLAYER)
                }
            }
        }
    }

    private fun setupTrackInfo(item: MediaItem?) {
        val track = item?.toTrack() ?: return

        setupTopArt(track)
        binding.apply {
            activityTrackTitle.text = track.title
            activityTrackArtist.text = track.artist
            activityTrackTitle.setOnLongClickListener {
                copyToClipboard(activityTrackTitle.value)
                true
            }

            activityTrackArtist.setOnLongClickListener {
                copyToClipboard(activityTrackArtist.value)
                true
            }

            activityTrackProgressbar.max = track.duration
            activityTrackProgressMax.text = track.duration.getFormattedDuration()
        }
    }

    private fun initThirdPartyIntent() {
        getTrackFromUri(intent.data) { track ->
            runOnUiThread {
                if (track != null) {
                    prepareAndPlay(listOf(track), startActivity = false, playNow = true)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    finish()
                }
            }
        }
    }

    private fun setupButtons() = binding.apply {
        activityTrackToggleShuffle.setOnClickListener { shuffleQueue() }
        activityTrackToggleShuffle.setOnLongClickListener { undoShuffle() }
        activityTrackPrevious.setOnClickListener { seekToPrevious() }
        activityTrackPlayPause.setOnClickListener { togglePlayback() }
        activityTrackNext.setOnClickListener { seekToNext() }
        activityTrackProgressCurrent.setOnClickListener { seekBack() }
        activityTrackProgressMax.setOnClickListener { seekForward() }
        activityTrackPlaybackSetting.setOnClickListener { togglePlaybackSetting() }
        activityTrackSpeedClickArea.setOnClickListener { showPlaybackSpeedPicker() }
        setupSeekbar()

        arrayOf(activityTrackPrevious, activityTrackPlayPause, activityTrackNext).forEach {
            it.applyColorFilter(getProperTextColor())
        }
    }

    private fun setupNextTrackInfo(item: MediaItem?) {
        val track = item?.toTrack()
        if (track == null) {
            binding.nextTrackHolder.beGone()
            return
        }

        binding.nextTrackHolder.beVisible()
        val artist =
            if (track.artist.trim().isNotEmpty() && track.artist != MediaStore.UNKNOWN_STRING) {
                " • ${track.artist}"
            } else {
                ""
            }

        @SuppressLint("SetTextI18n")
        binding.nextTrackLabel.text = "${getString(R.string.next_track)} ${track.title}$artist"

        getTrackCoverArt(track) { coverArt ->
            val cornerRadius =
                resources.getDimension(org.fossify.commons.R.dimen.rounded_corner_radius_small)
                    .toInt()
            val wantedSize = resources.getDimension(R.dimen.song_image_size).toInt()

            // change cover image manually only once loaded successfully to avoid blinking at fails and placeholders
            loadGlideResource(
                model = coverArt,
                options = RequestOptions().transform(CenterCrop(), RoundedCorners(cornerRadius)),
                size = Size(wantedSize, wantedSize),
                onLoadFailed = {
                    runOnUiThread {
                        binding.nextTrackImage.setImageDrawable(nextTrackPlaceholder)
                    }
                },
                onResourceReady = {
                    runOnUiThread {
                        binding.nextTrackImage.setImageDrawable(it)
                    }
                }
            )
        }
    }

    private fun setupTopArt(track: Track) {
        getTrackCoverArt(track) { coverArt ->
            var wantedHeight = resources.getCoverArtHeight()
            wantedHeight = min(wantedHeight, realScreenSize.y / 2)
            val wantedWidth = realScreenSize.x

            // change cover image manually only once loaded successfully to avoid blinking at fails and placeholders
            loadGlideResource(
                model = coverArt,
                // show full "audiobook" covers
                options = RequestOptions().fitCenter(),
                size = Size(wantedWidth, wantedHeight),
                onLoadFailed = {
                    val drawable = resources.getDrawable(R.drawable.ic_headset)
                    val placeholder = getResizedDrawable(drawable, wantedHeight)
                    placeholder.applyColorFilter(getProperTextColor())

                    runOnUiThread {
                        binding.activityTrackImage.setImageDrawable(placeholder)
                    }
                },
                onResourceReady = {
                    val coverHeight = it.intrinsicHeight
                    if (coverHeight > 0 && binding.activityTrackImage.height != coverHeight) {
                        binding.activityTrackImage.layoutParams.height = coverHeight
                    }

                    runOnUiThread {
                        binding.activityTrackImage.setImageDrawable(it)
                    }
                }
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFlingListener() {
        val flingListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    if (velocityY > 0 && velocityY > velocityX && e2.y - e1.y > SWIPE_DOWN_THRESHOLD) {
                        finish()
                        binding.activityTrackTopShadow.animate().alpha(0f).start()
                        overridePendingTransition(0, org.fossify.commons.R.anim.slide_down)
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        }

        val gestureDetector = GestureDetectorCompat(this, flingListener)
        binding.activityTrackHolder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    /** Shuffling is an action on the queue, not a playback mode. */
    private fun shuffleQueue() {
        PlaybackService.queueController?.post { shuffleUpcoming() }
        toast(R.string.queue_shuffled)
    }

    private fun undoShuffle(): Boolean {
        val queue = PlaybackService.queueController ?: return false
        if (PlaybackService.queueState.value?.canUndoReorder != true) {
            return false
        }

        queue.post { undoReorder() }
        return true
    }

    private fun renderQueueState(state: QueueState?) {
        setupShuffleButton(state?.canUndoReorder == true)
        setupPlaybackSettingButton(state?.toPlaybackSetting() ?: PlaybackSetting.REPEAT_OFF)
    }

    private fun QueueState.toPlaybackSetting(): PlaybackSetting {
        val head = head ?: return PlaybackSetting.REPEAT_OFF
        return when {
            head.isInfinite -> PlaybackSetting.REPEAT_TRACK
            afterHead.firstOrNull() is QueueEntry.PauseMarker -> PlaybackSetting.STOP_AFTER_CURRENT_TRACK
            else -> PlaybackSetting.REPEAT_OFF
        }
    }

    /** Highlighted while the last shuffle can still be undone (long press). */
    private fun setupShuffleButton(canUndo: Boolean) {
        binding.activityTrackToggleShuffle.apply {
            applyColorFilter(if (canUndo) getProperPrimaryColor() else getProperTextColor())
            alpha = if (canUndo) 1f else MEDIUM_ALPHA
            contentDescription = getString(R.string.shuffle_queue)
        }
    }

    private fun seekBack() {
        binding.activityTrackProgressbar.progress += -SEEK_INTERVAL_S
        withPlayer { seekBack() }
    }

    private fun seekForward() {
        binding.activityTrackProgressbar.progress += SEEK_INTERVAL_S
        withPlayer { seekForward() }
    }

    /**
     * Repeat and "stop after this track" are properties of the queue: repeating forever is the repeat count of
     * the current entry, and stopping is a pause marker right behind it.
     */
    private fun togglePlaybackSetting() {
        val queue = PlaybackService.queueController ?: return
        val state = PlaybackService.queueState.value ?: return
        val head = state.head ?: return
        val marker = state.afterHead.firstOrNull() as? QueueEntry.PauseMarker
        val next = state.toPlaybackSetting().nextPlaybackOption
        toast(next.descriptionStringRes)

        queue.post {
            when (next) {
                PlaybackSetting.REPEAT_TRACK -> setRepeatCount(head.id, null)
                PlaybackSetting.STOP_AFTER_CURRENT_TRACK -> {
                    setRepeatCount(head.id, 1)
                    insertPauseMarker(head.id)
                }

                PlaybackSetting.REPEAT_OFF -> marker?.let { removeEntries(listOf(it.id)) }
            }
        }
    }

    private fun setupPlaybackSettingButton(playbackSetting: PlaybackSetting) {
        binding.activityTrackPlaybackSetting.apply {
            contentDescription = getString(playbackSetting.contentDescriptionStringRes)
            setImageResource(playbackSetting.iconRes)

            val isRepeatOff = playbackSetting == PlaybackSetting.REPEAT_OFF

            alpha = if (isRepeatOff) MEDIUM_ALPHA else 1f
            applyColorFilter(if (isRepeatOff) getProperTextColor() else getProperPrimaryColor())
        }
    }

    private fun setupSeekbar() {
        binding.activityTrackSpeedIcon.applyColorFilter(getProperTextColor())
        updatePlaybackSpeed(config.playbackSpeed)

        binding.activityTrackProgressbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val formattedProgress = progress.getFormattedDuration()
                    binding.activityTrackProgressCurrent.text = formattedProgress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) = withPlayer {
                    seekTo(seekBar.progress * 1000L)
                }
            })
    }

    private fun showPlaybackSpeedPicker() {
        val fragment = PlaybackSpeedFragment()
        fragment.show(supportFragmentManager, PlaybackSpeedFragment::class.java.simpleName)
        fragment.setListener(this)
    }

    override fun updatePlaybackSpeed(speed: Float) {
        val isSlow = speed < 1f
        if (isSlow != binding.activityTrackSpeed.tag as? Boolean) {
            binding.activityTrackSpeed.tag = isSlow

            val drawableId =
                if (isSlow) R.drawable.ic_playback_speed_slow_vector else R.drawable.ic_playback_speed_vector
            binding.activityTrackSpeedIcon.setImageDrawable(resources.getDrawable(drawableId))
        }

        @SuppressLint("SetTextI18n")
        binding.activityTrackSpeed.text = "${DecimalFormat("#.##").format(speed)}x"
        withPlayer {
            setPlaybackSpeed(speed)
        }
    }

    private fun getResizedDrawable(drawable: Drawable, wantedHeight: Int): Drawable {
        val bitmap = (drawable as BitmapDrawable).bitmap
        val bitmapResized = bitmap.scale(wantedHeight, wantedHeight, false)
        return bitmapResized.toDrawable(resources)
    }

    override fun onPlaybackStateChanged(playbackState: Int) = updatePlayerState()

    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayerState()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        if (mediaItem == null) {
            finish()
        } else {
            binding.activityTrackProgressbar.progress = 0
            updateTrackInfo()
        }
    }

    private fun updateTrackInfo() {
        withPlayer {
            setupTrackInfo(currentMediaItem)
            setupNextTrackInfo(nextMediaItem)
        }
    }

    private fun updatePlayerState() {
        withPlayer {
            val isPlaying = isReallyPlaying
            if (isPlaying) {
                scheduleProgressUpdate()
            } else {
                cancelProgressUpdate()
            }

            updateProgress(currentPosition)
            updatePlayPause(isPlaying)
        }
    }

    private fun scheduleProgressUpdate() {
        cancelProgressUpdate()
        withPlayer {
            val delayInMillis = (UPDATE_INTERVAL_MS / config.playbackSpeed).toLong()
            handler.postDelayed(delayInMillis = delayInMillis) {
                updateProgress(currentPosition)
                scheduleProgressUpdate()
            }
        }
    }

    private fun cancelProgressUpdate() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateProgress(currentPosition: Long) {
        binding.activityTrackProgressbar.progress =
            currentPosition.milliseconds.inWholeSeconds.toInt()
    }

    private fun updatePlayPause(isPlaying: Boolean) {
        binding.activityTrackPlayPause.updatePlayPauseIcon(isPlaying, getProperTextColor())
    }

    // Navigation is decided by the queue (see SimpleMusicPlayer), including restarting the track on "previous".
    private fun seekToNext() = withPlayer { seekToNext() }

    private fun seekToPrevious() = withPlayer { seekToPrevious() }
}
