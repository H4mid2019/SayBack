# Task: group history by session + per-session LLM topic tag + search

Status: **clear-history button is DONE.** Remaining: session grouping, the LLM tag, and search.

## Goal

Group the phone history into **sessions** (one session = one listen→stop cycle on the watch). When a
session ends, ask the LLM for a short **topic tag** (e.g. "grocery store", "doctor visit"). The
history screen groups turns under their session + tag, and the user can **search** sessions by tag
(or content) — e.g. find all "grocery" conversations.

## Current state (what exists)

- History is a flat append-only `history.jsonl` in the phone's filesDir — one turn per line:
  `{heard, meaning, reply, phonetic, engine, ts}`. Written by `HistoryStore.save(turn)` (called from
  `Pipeline.processOnline`'s `onTurn`).
- Viewer: `HistoryActivity` + `HistoryAdapter` (flat RecyclerView list). `HistoryStore.recent()`
  reads newest-first. `clear()` deletes the file (Clear-history menu action already wired).

## Design

**A session = exactly one `Pipeline.processOnline` invocation** (channel open → stream close). So:

1. **Stamp a sessionId** at the top of `processOnline`: `val sessionId = now()` (start ms).
2. **Tag each turn with sessionId.** Easiest without changing the `:core` `Turn`/watch contract:
   `HistoryStore.save(turn, sessionId)` and add a `sessionId` field to the JSONL line. Also keep a
   running `mutableListOf<String>()` of the session's `heard` transcripts.
3. **At session end** (after `pump.join()`, if ≥1 turn was produced):
   - Build a tag prompt (add `buildTagPrompt(lang, transcripts)` to `:core/PromptBuilder.kt`):
     "Given this <lang> conversation, output a 1–3 word topic tag (e.g. 'grocery store', 'doctor
     visit'). Only the tag, no punctuation." Call the existing `callOpenRouter(client, key, model, prompt)`.
   - If offline / call fails → tag = "" (untagged); don't block.
   - `HistoryStore.saveSession(sessionId, startTs, endTs, tag, turnCount)` → append to a new
     `sessions.jsonl` (`{sessionId, startTs, endTs, tag, turnCount}`).

## HistoryStore changes

- `save(turn, sessionId)` — include sessionId in the turn line.
- `saveSession(...)` — append to `sessions.jsonl`.
- `sessions(): List<Session>` — read `sessions.jsonl`, attach each session's turns (grouped by
  sessionId from `history.jsonl`), newest first. `data class Session(id, startTs, endTs, tag, turns)`.
- `clear()` — delete BOTH `history.jsonl` and `sessions.jsonl`.

## Viewer changes (HistoryActivity / HistoryAdapter)

- Sectioned list: a `sealed interface Row { data class Header(session) ; data class TurnRow(turn) }`,
  flatten sessions → rows, adapter with 2 view types (header card = tag + relative time + turn count;
  turn card = existing item_history layout).
- **Search**: add a `SearchView` to `history_menu.xml`; filter to sessions whose `tag` or any turn's
  `heard/meaning/reply` contains the query (case-insensitive), rebuild the row list.

## Files to touch

`Pipeline.kt` (sessionId, collect transcripts, end-of-session tag call + saveSession), `HistoryStore.kt`
(sessionId on turns, sessions index, grouped read, clear both), `core/PromptBuilder.kt` (tag prompt),
`HistoryActivity.kt` + `HistoryAdapter.kt` (grouped + search), `res/menu/history_menu.xml` (SearchView),
`res/layout/item_session_header.xml` (new).

## Acceptance

- [ ] Each listen→stop becomes one grouped session with a topic tag (or "untagged" when offline).
- [ ] Searching "grocery" surfaces grocery sessions; clearing wipes everything.
- [ ] `./gradlew check` stays green (detekt forbids `TODO` comments — use `Later:`).
