package website.ahdesign.vocalis

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import website.ahdesign.vocalis.companion.history.HistoryStore
import website.ahdesign.vocalis.databinding.ActivityHistoryBinding

/** Browse saved conversation turns (read from HistoryStore's JSONL file), newest first. */
class HistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.historyList.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            val turns = HistoryStore(this@HistoryActivity).recent()
            binding.historyList.adapter = HistoryAdapter(turns)
            binding.emptyView.isVisible = turns.isEmpty()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
