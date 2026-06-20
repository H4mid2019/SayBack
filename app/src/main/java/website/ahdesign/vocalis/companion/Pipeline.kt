package website.ahdesign.vocalis.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import website.ahdesign.vocalis.AppSettings
import website.ahdesign.vocalis.DeepgramConfig
import website.ahdesign.vocalis.Engine
import website.ahdesign.vocalis.Paths
import website.ahdesign.vocalis.Suggestion
import website.ahdesign.vocalis.Turn
import website.ahdesign.vocalis.buildPrompt
import website.ahdesign.vocalis.buildTagPrompt
import website.ahdesign.vocalis.callOpenRouter
import website.ahdesign.vocalis.companion.history.HistoryStore
import website.ahdesign.vocalis.companion.offline.OnDeviceEngine
import website.ahdesign.vocalis.openDeepgramSocket
import website.ahdesign.vocalis.vocalisHttpClient
import java.io.IOException
import java.io.InputStream

/**
 * The brain. Reads PCM from the watch and produces [Turn]s, choosing the engine by connectivity.
 * The output contract is identical online and offline, so the watch UI is engine-agnostic.
 *
 * ONLINE  → the SAME two-service flow CallService uses for phone calls, via the shared :core
 *           helpers (Deepgram WS -> buildPrompt -> OpenRouter).
 * OFFLINE → delegate to [OnDeviceEngine] (gemma-4-e4b-it audio-in, or ML Kit translate fallback).
 */
class Pipeline(
    private val context: Context,
) {
    private val onDevice by lazy { OnDeviceEngine(context) }
    private val history = HistoryStore(context)

    /**
     * Consume the watch's PCM stream. Each utterance is saved (under one session id) + forwarded via
     * [onTurn]; when the session (one listen→stop cycle) ends, an LLM produces a short topic tag.
     */
    suspend fun process(
        pcm: InputStream,
        onTurn: suspend (Turn) -> Unit,
    ) {
        val settings = AppSettings(context)
        val client = vocalisHttpClient()
        val sessionId = now()
        val heard = mutableListOf<String>()
        val record: suspend (Turn) -> Unit = { turn ->
            heard += turn.heard
            history.save(turn, sessionId)
            onTurn(turn)
        }

        val engine = chooseEngine()
        when (engine) {
            Engine.ONLINE -> processOnline(pcm, settings, client, record)
            else -> onDevice.process(pcm, engine, record)
        }

        if (heard.isNotEmpty()) {
            val tag = if (engine == Engine.ONLINE) generateTag(settings, client, heard) else ""
            history.saveSession(sessionId, sessionId, now(), tag, heard.size)
        }
    }

    /** One LLM call to label a finished session (e.g. "grocery store"). Empty on failure/offline. */
    private suspend fun generateTag(
        settings: AppSettings,
        client: OkHttpClient,
        heard: List<String>,
    ): String =
        withContext(Dispatchers.IO) {
            runCatching {
                callOpenRouter(
                    client,
                    settings.openrouterKey,
                    settings.openrouterModel,
                    buildTagPrompt(settings.sourceLanguage, heard),
                ).reply.trim()
            }.getOrElse {
                Log.e(TAG, "tag generation failed", it)
                ""
            }
        }

    /**
     * Stream the watch's PCM to Deepgram and, for each final transcript, ask OpenRouter for a short
     * reply. A producer pumps audio into the socket while a consumer turns transcripts into [Turn]s.
     */
    private suspend fun processOnline(
        pcm: InputStream,
        settings: AppSettings,
        client: OkHttpClient,
        onTurn: suspend (Turn) -> Unit,
    ) = coroutineScope {
        val transcripts = Channel<String>(Channel.UNLIMITED)

        val socket =
            openDeepgramSocket(
                client,
                DeepgramConfig(settings.deepgramKey, settings.deepgramModel, settings.sourceLanguage),
            ) { text, isFinal ->
                if (isFinal) transcripts.trySend(text)
            }

        // Pump the watch's PCM into Deepgram on a background coroutine. The blocking reads must NOT
        // run in the main coroutine, or they starve the transcript consumer below.
        val pump =
            launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_BYTES)
                var readBytes = 0
                var sentBytes = 0
                try {
                    while (isActive) {
                        val n = pcm.read(buffer)
                        if (n < 0) break
                        if (n > 0) {
                            readBytes += n
                            // The watch already drops silence; forward everything to Deepgram.
                            if (socket.send(buffer.toByteString(0, n))) sentBytes += n
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "audio pump ended: ${e.message}") // channel closed (e.g. user hit Stop)
                } finally {
                    Log.i(TAG, "pump done: read=$readBytes sent=$sentBytes bytes to Deepgram")
                    socket.close(WS_NORMAL_CLOSURE, "stream ended")
                    transcripts.close()
                }
            }

        // Consume transcripts in the main coroutine — it only suspends on receive, never blocks.
        for (text in transcripts) {
            Log.i(TAG, "transcript -> LLM: '$text'")
            val prompt = buildPrompt(settings.sourceLanguage, settings.replyScript, IN_PERSON_CONTEXT, text)
            val answer =
                runCatching {
                    callOpenRouter(client, settings.openrouterKey, settings.openrouterModel, prompt)
                }.getOrElse {
                    Log.e(TAG, "OpenRouter failed for '$text'", it)
                    continue
                }
            Log.i(TAG, "reply='${answer.reply}' meaning='${answer.translation}'")
            onTurn(Turn(text, answer.translation, Suggestion(answer.reply), Engine.ONLINE, now()))
        }
        pump.join()
    }

    /** ONLINE iff the active network is validated; else pick whichever offline engine is ready. */
    private fun chooseEngine(): Engine {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return when {
            online -> Engine.ONLINE
            onDevice.gemmaReady() -> Engine.OFFLINE_GEMMA
            else -> Engine.OFFLINE_MLKIT
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "Pipeline"
        private const val BUFFER_BYTES = 4096
        private const val WS_NORMAL_CLOSURE = 1000
        private const val IN_PERSON_CONTEXT = "In-person conversation, not a phone call. Keep replies short."
    }
}

/** Serialize a [Turn] back to the watch over [Paths.RESULT] (MessageClient). */
object ResultSender {
    private const val TAG = "ResultSender"

    suspend fun send(
        context: Context,
        nodeId: String,
        turn: Turn,
    ) {
        val json =
            JSONObject()
                .put("heard", turn.heard)
                .put("meaning", turn.meaning)
                .put("replyText", turn.reply.text)
                .put("replyPhonetic", turn.reply.phonetic ?: "")
                .put("engine", turn.engine.name)
                .put("tsEpochMs", turn.tsEpochMs)
                .toString()
        runCatching {
            Wearable
                .getMessageClient(context)
                .sendMessage(nodeId, Paths.RESULT, json.toByteArray())
                .await()
        }.onFailure { Log.e(TAG, "send failed", it) }
    }
}
