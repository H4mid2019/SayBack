package website.ahdesign.vocalis

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Shared online pipeline: Deepgram live transcription (WebSocket) + OpenRouter completion. Reused by
 * the phone call path (CallService) and the watch in-person path (companion Pipeline), so both speak
 * to the two services identically.
 */

private const val TAG = "VocalisOnline"
private const val DEFAULT_SAMPLE_RATE = 16_000
private const val WS_READ_TIMEOUT_MS = 0L // keep the WS open indefinitely
private const val WS_PING_SECONDS = 20L
private const val HTTP_CALL_TIMEOUT_SECONDS = 30L
private const val ERROR_BODY_PREVIEW = 400

/** OkHttp client tuned for a long-lived Deepgram WS plus short OpenRouter calls. */
fun vocalisHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .readTimeout(WS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

/** Deepgram live-transcription connection parameters. */
class DeepgramConfig(
    val apiKey: String,
    val model: String,
    val language: String,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
)

/**
 * Open a Deepgram live-transcription WebSocket. [onTranscript] fires for each transcript with an
 * isFinal flag. The caller sends 16 kHz mono PCM16 via the returned [WebSocket] and closes it.
 */
fun openDeepgramSocket(
    client: OkHttpClient,
    config: DeepgramConfig,
    onTranscript: (text: String, isFinal: Boolean) -> Unit,
): WebSocket {
    val url =
        "wss://api.deepgram.com/v1/listen" +
            "?language=${config.language}&model=${config.model}&smart_format=true&interim_results=false" +
            "&encoding=linear16&sample_rate=${config.sampleRate}&channels=1"
    val request =
        Request
            .Builder()
            .url(url)
            .addHeader("Authorization", "Token ${config.apiKey}")
            .build()
    return client.newWebSocket(
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
                val json = JSONObject(text)
                val transcript =
                    json
                        .optJSONObject("channel")
                        ?.optJSONArray("alternatives")
                        ?.optJSONObject(0)
                        ?.optString("transcript")
                        .orEmpty()
                if (transcript.isNotBlank()) onTranscript(transcript, json.optBoolean("is_final"))
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                Log.i(TAG, "Deepgram WS closing code=$code reason=$reason")
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                Log.i(TAG, "Deepgram WS closed code=$code reason=$reason")
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                Log.e(TAG, "Deepgram WS failure (HTTP ${response?.code})", t)
            }
        },
    )
}

/**
 * One OpenRouter chat completion. Blocking — call from an IO dispatcher. Returns the parsed
 * {translation, reply}; throws [IllegalStateException] on a non-2xx response.
 */
fun callOpenRouter(
    client: OkHttpClient,
    apiKey: String,
    model: String,
    prompt: String,
): LlmAnswer {
    val json =
        JSONObject().apply {
            put("model", model)
            put("response_format", JSONObject().put("type", "json_object"))
            // Prefer the lowest-latency provider for this model (no load balancing).
            put("provider", JSONObject().put("sort", "latency"))
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
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
    val content =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "OpenRouter HTTP ${response.code}: ${body.take(ERROR_BODY_PREVIEW)}" }
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    return parseLlmAnswer(content)
}
