package website.ahdesign.vocalis.companion

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import website.ahdesign.vocalis.Paths
import java.io.InputStream

/**
 * PHONE side. The Data Layer wakes this service when the watch opens the audio channel.
 *
 * We process the stream with [runBlocking] — NOT a fire-and-forget coroutine. A WearableListenerService
 * is torn down within seconds of the callback returning, which would cancel any background coroutine
 * (observed: the pipeline died ~2s in). Blocking the callback for the whole session keeps the service
 * alive until the watch closes the channel. The callback runs on a Data Layer background thread, so
 * blocking it is safe (no ANR).
 */
class WearAudioListenerService : WearableListenerService() {
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val pipeline by lazy { Pipeline(applicationContext) }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Paths.AUDIO_CHANNEL) return
        val sourceNodeId = channel.nodeId
        WatchSession.listening.value = true
        try {
            runBlocking {
                runCatching {
                    val input: InputStream = channelClient.getInputStream(channel).await()
                    pipeline.process(input) { turn ->
                        ResultSender.send(this@WearAudioListenerService, sourceNodeId, turn)
                    }
                }.onFailure { Log.e(TAG, "channel processing failed", it) }
                runCatching { channelClient.close(channel).await() }
            }
        } finally {
            WatchSession.listening.value = false
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Paths.LISTEN_START -> {
                Log.i(TAG, "watch session start")
                WatchSession.listening.value = true
            }
            Paths.LISTEN_STOP -> {
                Log.i(TAG, "watch session stop")
                WatchSession.listening.value = false
            }
        }
    }

    companion object {
        private const val TAG = "WearAudioListener"
    }
}
