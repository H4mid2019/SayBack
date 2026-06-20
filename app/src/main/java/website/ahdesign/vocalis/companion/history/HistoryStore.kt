package website.ahdesign.vocalis.companion.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import website.ahdesign.vocalis.Engine
import website.ahdesign.vocalis.Suggestion
import website.ahdesign.vocalis.Turn
import java.io.File

/**
 * History lives on the PHONE as an append-only JSON-lines file (`history.jsonl` in app storage):
 * one turn per line, cheap to append and crash-safe. Read back newest-first by [recent].
 *
 * Optional [HistorySync] POSTs new turns to a user endpoint for cross-device history (off by default).
 */
class HistoryStore(
    context: Context,
) {
    private val sync = HistorySync(context)
    private val file = File(context.filesDir, FILE_NAME)

    /** Append one turn as a JSON line. */
    suspend fun save(turn: Turn): Unit =
        withContext(Dispatchers.IO) {
            val line =
                JSONObject()
                    .put("heard", turn.heard)
                    .put("meaning", turn.meaning)
                    .put("reply", turn.reply.text)
                    .put("phonetic", turn.reply.phonetic ?: "")
                    .put("engine", turn.engine.name)
                    .put("ts", turn.tsEpochMs)
                    .toString()
            runCatching { file.appendText("$line\n") }
                .onSuccess { Log.i(TAG, "saved turn (${file.length()} bytes total)") }
                .onFailure { Log.e(TAG, "history write failed", it) }
            runCatching { sync.maybeUpload(turn) }
                .onFailure { Log.w(TAG, "history sync deferred", it) }
        }

    /** The most recent [limit] turns, newest first. */
    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<Turn> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                return@withContext emptyList()
            }
            runCatching {
                file
                    .readLines()
                    .takeLast(limit)
                    .mapNotNull(::parseLine)
                    .asReversed()
            }.getOrElse {
                Log.e(TAG, "history read failed", it)
                emptyList()
            }
        }

    /** Delete all saved history. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching { file.delete() }
                .onFailure { Log.e(TAG, "history clear failed", it) }
        }
    }

    private fun parseLine(line: String): Turn? =
        runCatching {
            val o = JSONObject(line)
            Turn(
                heard = o.getString("heard"),
                meaning = o.getString("meaning"),
                reply = Suggestion(o.getString("reply"), o.optString("phonetic").ifBlank { null }),
                engine = runCatching { Engine.valueOf(o.optString("engine")) }.getOrDefault(Engine.ONLINE),
                tsEpochMs = o.optLong("ts"),
            )
        }.getOrNull()

    companion object {
        private const val TAG = "HistoryStore"
        private const val FILE_NAME = "history.jsonl"
        private const val DEFAULT_LIMIT = 100
    }
}

/** Optional cross-device sync. No-op unless the user enables it (endpoint + flag in prefs). */
class HistorySync(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)
    private val endpoint: String? get() = prefs.getString(KEY_ENDPOINT, null)

    suspend fun maybeUpload(turn: Turn) {
        if (!enabled || endpoint.isNullOrBlank()) return
        // Later: POST JSON {heard, meaning, reply, engine, ts} to `endpoint` with OkHttp.
        Log.i(TAG, "would upload turn (heard len=${turn.heard.length}) to $endpoint")
    }

    companion object {
        private const val TAG = "HistorySync"
        private const val PREFS = "history_sync"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
    }
}
