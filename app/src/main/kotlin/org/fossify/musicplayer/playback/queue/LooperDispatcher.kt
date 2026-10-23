package org.fossify.musicplayer.playback.queue

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Runs coroutines on the given looper (here: the player's), and runs them inline if already on it.
 * Avoids depending on `kotlinx-coroutines-android` just for `Dispatchers.Main`.
 */
class LooperDispatcher(private val looper: Looper) : CoroutineDispatcher() {
    private val handler = Handler(looper)

    override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() != looper

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}
