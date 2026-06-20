package website.ahdesign.vocalis

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import website.ahdesign.vocalis.companion.history.Session
import website.ahdesign.vocalis.databinding.ItemHistoryBinding
import website.ahdesign.vocalis.databinding.ItemSessionHeaderBinding

/** Renders the history screen: a session header followed by its turns, for each session. */
class HistoryAdapter(
    private val rows: List<HistoryRow>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    class HeaderViewHolder(
        val binding: ItemSessionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    class TurnViewHolder(
        val binding: ItemHistoryBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is HistoryRow.Header -> TYPE_HEADER
            is HistoryRow.TurnItem -> TYPE_TURN
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemSessionHeaderBinding.inflate(inflater, parent, false))
        } else {
            TurnViewHolder(ItemHistoryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is HistoryRow.Header -> bindHeader(holder as HeaderViewHolder, row.session)
            is HistoryRow.TurnItem -> bindTurn(holder as TurnViewHolder, row.turn)
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun bindHeader(
        holder: HeaderViewHolder,
        session: Session,
    ) {
        holder.binding.sessionTag.text =
            session.tag.ifBlank {
                holder.binding.root.context
                    .getString(R.string.history_untagged)
            }
        val time = DateUtils.getRelativeTimeSpanString(session.startTs)
        val count = session.turns.size
        val unit = if (count == 1) "turn" else "turns"
        holder.binding.sessionMeta.text = "$time  ·  $count $unit"
    }

    private fun bindTurn(
        holder: TurnViewHolder,
        turn: Turn,
    ) {
        holder.binding.replyText.text = turn.reply.text
        holder.binding.meaningText.text = turn.meaning
        holder.binding.heardText.text = turn.heard
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TURN = 1
    }
}
