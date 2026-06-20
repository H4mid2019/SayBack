package website.ahdesign.vocalis.wear.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import website.ahdesign.vocalis.AudioSpec
import website.ahdesign.vocalis.Paths
import website.ahdesign.vocalis.SILENCE_RMS_FLOOR
import website.ahdesign.vocalis.rms
import website.ahdesign.vocalis.wear.R
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground (microphone-type) service on the WATCH. Captures mic PCM and streams it to the phone
 * over a Data Layer [ChannelClient]. The phone does ASR/translate/LLM and sends results back.
 *
 * Remaining work (TODOs) is about robustness, not compilation:
 *  - Pick the phone node via CapabilityClient instead of "first connected node".
 *  - Reconnect if the channel drops; auto-stop after a max duration to save battery.
 */
class WearMicService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val streaming = AtomicBoolean(false)
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private var channel: ChannelClient.Channel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked by ListenActivity before starting
    private fun startStreaming() {
        if (!streaming.compareAndSet(false, true)) return

        scope.launch {
            val phoneNodeId =
                nearestPhoneNodeId() ?: run {
                    Log.w(TAG, "No connected phone node; cannot stream")
                    stopStreaming()
                    return@launch
                }

            Log.i(TAG, "streaming to phone node=$phoneNodeId")
            Wearable
                .getMessageClient(this@WearMicService)
                .sendMessage(phoneNodeId, Paths.LISTEN_START, ByteArray(0))

            val ch = channelClient.openChannel(phoneNodeId, Paths.AUDIO_CHANNEL).await()
            channel = ch
            val out: OutputStream = channelClient.getOutputStream(ch).await()
            Log.i(TAG, "audio channel open; capturing mic")

            val minBuf =
                AudioRecord.getMinBufferSize(
                    AudioSpec.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val bufSize = minBuf.coerceAtLeast(MIN_BUFFER_BYTES) * 2

            @SuppressLint("MissingPermission")
            val record =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioSpec.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )

            try {
                record.startRecording()
                val buffer = ByteArray(bufSize)
                var readFrames = 0
                var sentFrames = 0
                var maxRms = 0.0
                var lastLog = SystemClock.elapsedRealtime()
                while (streaming.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            readFrames++
                            val level = rms(buffer, read)
                            if (level > maxRms) maxRms = level
                            if (level >= SILENCE_RMS_FLOOR) {
                                out.write(buffer, 0, read)
                                sentFrames++
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastLog >= LOG_INTERVAL_MS) {
                                Log.v(
                                    TAG,
                                    "mic: read=$readFrames sent=$sentFrames " +
                                        "maxRms=${"%.0f".format(maxRms)} (floor=$SILENCE_RMS_FLOOR)",
                                )
                                readFrames = 0
                                sentFrames = 0
                                maxRms = 0.0
                                lastLog = now
                            }
                        }
                        read < 0 -> {
                            Log.w(TAG, "AudioRecord.read error=$read")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Includes ChannelIOException when the phone closes the channel (e.g. on Stop).
                Log.w(TAG, "capture loop ended", e)
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { out.flush(); out.close() }
            }
        }
    }

    private fun stopStreaming() {
        if (!streaming.compareAndSet(true, false)) return
        scope.launch {
            nearestPhoneNodeId()?.let {
                Wearable
                    .getMessageClient(this@WearMicService)
                    .sendMessage(it, Paths.LISTEN_STOP, ByteArray(0))
            }
            channel?.let { ch -> runCatching { channelClient.close(ch).await() } }
            channel = null
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** TODO: use CapabilityClient to pick the node advertising the companion capability. */
    private suspend fun nearestPhoneNodeId(): String? =
        Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()?.id

    private fun createNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        streaming.set(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "WEAR_LISTEN_START"
        const val ACTION_STOP = "WEAR_LISTEN_STOP"
        private const val NOTIFICATION_ID = 42
        private const val MIN_BUFFER_BYTES = 4096
        private const val LOG_INTERVAL_MS = 2000L
        private const val CHANNEL_ID = "WearListenChannel"
        private const val TAG = "WearMicService"
    }
}
