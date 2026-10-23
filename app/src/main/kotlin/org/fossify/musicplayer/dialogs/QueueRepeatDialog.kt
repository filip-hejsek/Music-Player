package org.fossify.musicplayer.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.*
import org.fossify.musicplayer.R
import org.fossify.musicplayer.databinding.DialogQueueRepeatBinding
import org.fossify.musicplayer.playback.queue.QueueEntry

/**
 * Asks for the total number of times a queue entry should play.
 * The callback gets `null` for "repeat forever".
 */
class QueueRepeatDialog(
    val activity: Activity,
    entry: QueueEntry.TrackEntry,
    val callback: (total: Int?) -> Unit,
) {
    private val binding by activity.viewBinding(DialogQueueRepeatBinding::inflate)

    init {
        binding.queueRepeatCount.setText(entry.totalRepetitions?.toString() ?: "")
        binding.queueRepeatForever.isChecked = entry.isInfinite
        binding.queueRepeatCountHint.beGoneIf(entry.isInfinite)
        binding.queueRepeatForever.setOnCheckedChangeListener { _, isChecked ->
            binding.queueRepeatCountHint.beGoneIf(isChecked)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.queue_set_repeat_count) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (binding.queueRepeatForever.isChecked) {
                            callback(null)
                            alertDialog.dismiss()
                            return@setOnClickListener
                        }

                        val count = binding.queueRepeatCount.value.toIntOrNull()?.takeIf { it >= 1 }
                        if (count == null) {
                            activity.toast(R.string.queue_invalid_repeat_count)
                        } else {
                            callback(count)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
