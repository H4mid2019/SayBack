package com.example.callsummarizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CallService : Service() {
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var overlayManager: OverlayManager
    private lateinit var settings: AppSettings
    private var webSocket: WebSocket? = null
    private val attachedEffects = mutableListOf<AudioEffect>()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    private val client by lazy {
        OkHttpClient
            .Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // keep WS open
            .pingInterval(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        settings = AppSettings(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Android 14+ requires RECORD_AUDIO to be granted BEFORE startForeground with
        // FOREGROUND_SERVICE_TYPE_MICROPHONE — otherwise SecurityException. Bail cleanly.
        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO missing; aborting service start")
            stopSelf()
            return START_NOT_STICKY
        }

        // BYOK: nothing to do without keys. Don't even go foreground.
        if (!settings.hasKeys() && intent?.action == ACTION_START) {
            Log.w(TAG, "API keys missing; aborting session start")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true

        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> {
                stopRecording()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

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

    @android.annotation.SuppressLint("MissingPermission") // RECORD_AUDIO is verified in onStartCommand
    private fun startRecording() {
        if (!isRecording.compareAndSet(false, true)) return

        overlayManager.showOverlay()
        overlayManager.showStatus(getString(R.string.overlay_waiting))

        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            failStart("audio buffer size error ($minBuffer)")
            return
        }
        val bufferSize = minBuffer.coerceAtLeast(4096) * 2

        val source = pickAudioSource()
        val record =
            try {
                AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
            } catch (e: Exception) {
                // SecurityException on Android 14+ if perms changed; IllegalArgumentException on bad params.
                Log.e(TAG, "AudioRecord init threw", e)
                failStart("mic init failed")
                return
            }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord uninitialized (mic busy / OEM restriction?)")
            runCatching { record.release() }
            failStart("mic unavailable — busy?")
            return
        }

        attachAudioEffects(record.audioSessionId)

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            // Common during an active phone call on devices that reserve the mic exclusively.
            Log.e(TAG, "startRecording threw", e)
            releaseAudioEffects()
            runCatching { record.release() }
            failStart("mic locked by call")
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "Recording didn't start (state=${record.recordingState})")
            releaseAudioEffects()
            runCatching { record.release() }
            failStart("mic locked by call")
            return
        }

        audioRecord = record
        registerSilencingWatcher(record.audioSessionId)
        connectDeepgram()

        serviceScope.launch {
            val buffer = ByteArray(bufferSize)
            var readFrames = 0
            var sentFrames = 0
            var maxRms = 0.0
            var lastLog = android.os.SystemClock.elapsedRealtime()
            try {
                while (isRecording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            readFrames++
                            val rmsValue = rms(buffer, read)
                            if (rmsValue > maxRms) maxRms = rmsValue
                            if (rmsValue >= SILENCE_RMS_FLOOR) {
                                webSocket?.send(buffer.toByteString(0, read))
                                sentFrames++
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastLog >= 2000) {
                                Log.i(
                                    TAG,
                                    "Recording: read=$readFrames sent=$sentFrames " +
                                        "maxRms=${"%.0f".format(maxRms)} (floor=$SILENCE_RMS_FLOOR)",
                                )
                                readFrames = 0
                                sentFrames = 0
                                maxRms = 0.0
                                lastLog = now
                            }
                        }
                        read == AudioRecord.ERROR_DEAD_OBJECT -> {
                            Log.e(TAG, "AudioRecord dead object; ending session")
                            break
                        }
                        read < 0 -> Log.w(TAG, "AudioRecord.read error=$read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop crashed", e)
            }
            Log.i(TAG, "Recording loop exited")
        }
    }

    // One UI 8 / Android 15+ silences third-party AudioRecord sessions during cellular calls
    // (XDA-documented HAL behaviour). Detect the transition so the overlay can tell the user
    // why nothing's coming through, instead of failing silently.
    private fun registerSilencingWatcher(mySessionId: Int) {
        val am = audioManager ?: return
        val cb =
            object : AudioManager.AudioRecordingCallback() {
                private var lastSilenced = false

                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == mySessionId } ?: return
                    val silenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mine.isClientSilenced
                    if (silenced == lastSilenced) return
                    lastSilenced = silenced
                    if (silenced) {
                        Log.w(TAG, "AudioRecord silenced by OS (privacy-sensitive use case took mic)")
                        overlayManager.showStatus(getString(R.string.overlay_muted_by_os))
                    } else {
                        Log.i(TAG, "AudioRecord unsilenced")
                        overlayManager.showStatus(getString(R.string.overlay_waiting))
                    }
                }
            }
        recordingCallback = cb
        runCatching { am.registerAudioRecordingCallback(cb, null) }
            .onFailure { Log.w(TAG, "Failed to register recording callback", it) }
    }

    private fun unregisterSilencingWatcher() {
        val cb = recordingCallback ?: return
        runCatching { audioManager?.unregisterAudioRecordingCallback(cb) }
        recordingCallback = null
    }

    private fun pickAudioSource(): Int {
        val am = getSystemService(AudioManager::class.java)
        val unprocessedOk = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessedOk) {
            Log.i(TAG, "Audio source: UNPROCESSED (device-reported support)")
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            Log.i(TAG, "Audio source: VOICE_RECOGNITION (UNPROCESSED unsupported)")
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId == AudioRecord.ERROR || sessionId == AudioRecord.ERROR_BAD_VALUE) {
            Log.w(TAG, "Skipping audio effects: invalid session id ($sessionId)")
            return
        }
        tryAttach("NoiseSuppressor", NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)
        }
        tryAttach("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)
        }
        tryAttach("AutomaticGainControl", AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)
        }
    }

    private inline fun tryAttach(
        name: String,
        available: Boolean,
        factory: () -> AudioEffect?,
    ) {
        if (!available) {
            Log.i(TAG, "$name unavailable on this device")
            return
        }
        runCatching {
            factory()?.also {
                it.enabled = true
                attachedEffects += it
            }
        }.onSuccess { Log.i(TAG, "$name enabled") }
            .onFailure { Log.w(TAG, "$name failed to attach", it) }
    }

    private fun releaseAudioEffects() {
        attachedEffects.forEach { runCatching { it.release() } }
        attachedEffects.clear()
    }

    private fun failStart(reason: String) {
        isRecording.set(false)
        overlayManager.showStatus("Error: $reason")
        // Keep the overlay up briefly so the user can read it, then tear down.
        serviceScope.launch {
            kotlinx.coroutines.delay(2500)
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun connectDeepgram() {
        val lang = settings.sourceLanguage
        val model = settings.deepgramModel
        val keyLen = settings.deepgramKey.length
        val url =
            "wss://api.deepgram.com/v1/listen" +
                "?language=$lang&model=$model&smart_format=true&interim_results=false" +
                "&encoding=linear16&sample_rate=16000&channels=1"
        Log.i(TAG, "Connecting Deepgram: lang=$lang model=$model keyLen=$keyLen")
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", "Token ${settings.deepgramKey}")
                .build()

        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        Log.i(TAG, "Deepgram WS opened (HTTP ${response.code})")
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        Log.v(TAG, "Deepgram msg: ${text.take(160)}")
                        val json = JSONObject(text)
                        val transcript =
                            json
                                .optJSONObject("channel")
                                ?.optJSONArray("alternatives")
                                ?.optJSONObject(0)
                                ?.optString("transcript")
                                .orEmpty()
                        val isFinal = json.optBoolean("is_final")
                        if (transcript.isNotBlank()) {
                            Log.i(TAG, "Transcript (final=$isFinal): '$transcript'")
                            if (isFinal) processTranscript(transcript)
                        }
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(TAG, "Deepgram WS closed code=$code reason=$reason")
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(TAG, "Deepgram WS closing code=$code reason=$reason")
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.e(
                            TAG,
                            "Deepgram WS failure (HTTP ${response?.code} body=${runCatching { response?.body?.string() }.getOrNull()?.take(
                                200,
                            )})",
                            t,
                        )
                    }
                },
            )
    }

    private fun processTranscript(transcript: String) {
        serviceScope.launch {
            val userContext =
                getSharedPreferences("prefs", MODE_PRIVATE)
                    .getString("call_context", "")
                    .orEmpty()

            val prompt = buildPrompt(settings.sourceLanguage, settings.replyScript, userContext, transcript)

            val (translation, reply) =
                runCatching { callOpenRouter(prompt) }
                    .getOrElse { e ->
                        Log.e(TAG, "OpenRouter call failed", e)
                        return@launch
                    }

            withContext(Dispatchers.Main) {
                overlayManager.showAnswer(transcript, translation, reply)
            }
        }
    }

    private fun callOpenRouter(prompt: String): LlmAnswer {
        Log.i(TAG, "Calling OpenRouter model=${settings.openrouterModel} keyLen=${settings.openrouterKey.length}")
        val json =
            JSONObject().apply {
                put("model", settings.openrouterModel)
                put("response_format", JSONObject().put("type", "json_object"))
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        },
                    ),
                )
            }

        val request =
            Request
                .Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.openrouterKey}")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

        val content =
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.i(TAG, "OpenRouter HTTP ${response.code} bodyLen=${body.length}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "OpenRouter error body: ${body.take(400)}")
                    throw IllegalStateException("OpenRouter HTTP ${response.code}")
                }
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }

        return parseLlmAnswer(content)
    }

    private fun stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return
        unregisterSilencingWatcher()
        runCatching { audioRecord?.stop() }
        releaseAudioEffects()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "Call ended")
        webSocket = null
        overlayManager.hideOverlay()
    }

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

        val stopIntent = Intent(this, CallService::class.java).setAction(ACTION_STOP)
        val stopPi =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop_action), stopPi)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        serviceScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "START_RECORDING"
        const val ACTION_STOP = "STOP_RECORDING"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CallServiceChannel"
        private const val TAG = "CallService"

        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set
    }
}
