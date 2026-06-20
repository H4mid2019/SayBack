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

/** One conversation session (a listen→stop cycle) with its turns and an LLM topic tag. */
data class Session(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val tag: String,
    val turns: List<Turn>,
)

/**
 * History lives on the PHONE as two append-only JSON-lines files in app storage:
 *  - `history.jsonl`  — one turn per line, each stamped with its `sessionId`.
 *  - `sessions.jsonl` — one record per finished session: {id, startTs, endTs, tag, turnCount}.
 * [sessions] joins them (newest session first). [HistorySync] optionally uploads turns.
 */
class HistoryStore(
    context: Context,
) {
    private val sync = HistorySync(context)
    private val turnsFile = File(context.filesDir, TURNS_FILE)
    private val sessionsFile = File(context.filesDir, SESSIONS_FILE)

    /** Append one turn (tagged with its session). */
    suspend fun save(
        turn: Turn,
        sessionId: Long,
    ) {
        withContext(Dispatchers.IO) {
            val line =
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("heard", turn.heard)
                    .put("meaning", turn.meaning)
                    .put("reply", turn.reply.text)
                    .put("phonetic", turn.reply.phonetic ?: "")
                    .put("engine", turn.engine.name)
                    .put("ts", turn.tsEpochMs)
                    .toString()
            runCatching { turnsFile.appendText("$line\n") }
                .onFailure { Log.e(TAG, "turn write failed", it) }
            runCatching { sync.maybeUpload(turn) }
                .onFailure { Log.w(TAG, "history sync deferred", it) }
        }
    }

    /** Append the session record once the session ends. */
    suspend fun saveSession(
        id: Long,
        startTs: Long,
        endTs: Long,
        tag: String,
        turnCount: Int,
    ) {
        withContext(Dispatchers.IO) {
            val line =
                JSONObject()
                    .put("id", id)
                    .put("startTs", startTs)
                    .put("endTs", endTs)
                    .put("tag", tag)
                    .put("turnCount", turnCount)
                    .toString()
            runCatching { sessionsFile.appendText("$line\n") }
                .onSuccess { Log.i(TAG, "saved session '$tag' ($turnCount turns)") }
                .onFailure { Log.e(TAG, "session write failed", it) }
        }
    }

    /** All sessions, newest first, each with its turns (chronological within the session). */
    suspend fun sessions(): List<Session> =
        withContext(Dispatchers.IO) {
            if (!sessionsFile.exists()) {
                return@withContext emptyList()
            }
            val turnsBySession =
                runCatching {
                    if (turnsFile.exists()) {
                        turnsFile.readLines().mapNotNull(::parseTurnLine).groupBy({ it.first }, { it.second })
                    } else {
                        emptyMap()
                    }
                }.getOrDefault(emptyMap())
            runCatching {
                sessionsFile
                    .readLines()
                    .mapNotNull { line -> parseSessionLine(line, turnsBySession) }
                    .asReversed()
            }.getOrElse {
                Log.e(TAG, "sessions read failed", it)
                emptyList()
            }
        }

    /** Delete all saved history (turns + sessions). */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                turnsFile.delete()
                sessionsFile.delete()
            }.onFailure { Log.e(TAG, "history clear failed", it) }
        }
    }

    private fun parseTurnLine(line: String): Pair<Long, Turn>? =
        runCatching {
            val o = JSONObject(line)
            o.optLong("sessionId") to
                Turn(
                    heard = o.getString("heard"),
                    meaning = o.getString("meaning"),
                    reply = Suggestion(o.getString("reply"), o.optString("phonetic").ifBlank { null }),
                    engine = runCatching { Engine.valueOf(o.optString("engine")) }.getOrDefault(Engine.ONLINE),
                    tsEpochMs = o.optLong("ts"),
                )
        }.getOrNull()

    private fun parseSessionLine(
        line: String,
        turnsBySession: Map<Long, List<Turn>>,
    ): Session? =
        runCatching {
            val o = JSONObject(line)
            val id = o.getLong("id")
            Session(
                id = id,
                startTs = o.optLong("startTs"),
                endTs = o.optLong("endTs"),
                tag = o.optString("tag"),
                turns = turnsBySession[id].orEmpty(),
            )
        }.getOrNull()

    companion object {
        private const val TAG = "HistoryStore"
        private const val TURNS_FILE = "history.jsonl"
        private const val SESSIONS_FILE = "sessions.jsonl"
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
