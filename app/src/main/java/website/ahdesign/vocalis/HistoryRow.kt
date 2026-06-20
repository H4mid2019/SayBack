package website.ahdesign.vocalis

import website.ahdesign.vocalis.companion.history.Session

/** A flattened history row: a session header followed by its turns. */
sealed interface HistoryRow {
    data class Header(
        val session: Session,
    ) : HistoryRow

    data class TurnItem(
        val turn: Turn,
    ) : HistoryRow
}
