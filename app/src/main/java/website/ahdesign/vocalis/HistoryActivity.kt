package website.ahdesign.vocalis

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import website.ahdesign.vocalis.companion.history.HistoryStore
import website.ahdesign.vocalis.databinding.ActivityHistoryBinding

/** Browse saved conversation turns (read from HistoryStore's JSONL file), newest first. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        loadHistory()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_clear -> {
                confirmClear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val turns = HistoryStore(this@HistoryActivity).recent()
            binding.historyList.adapter = HistoryAdapter(turns)
            binding.emptyView.isVisible = turns.isEmpty()
        }
    }

    private fun confirmClear() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                lifecycleScope.launch {
                    HistoryStore(this@HistoryActivity).clear()
                    loadHistory()
                    Toast.makeText(this@HistoryActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                }
            }.show()
    }
}
