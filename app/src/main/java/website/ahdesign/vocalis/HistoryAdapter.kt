package website.ahdesign.vocalis

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import website.ahdesign.vocalis.databinding.ItemHistoryBinding

/** Renders saved [Turn]s in the phone history screen (newest first). */
class HistoryAdapter(
    private val items: List<Turn>,
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    class ViewHolder(
        val binding: ItemHistoryBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val turn = items[position]
        holder.binding.replyText.text = turn.reply.text
        holder.binding.meaningText.text = turn.meaning
        val time = DateUtils.getRelativeTimeSpanString(turn.tsEpochMs)
        holder.binding.heardText.text = "${turn.heard}  ·  $time"
    }

    override fun getItemCount(): Int = items.size
}
