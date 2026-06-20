package website.ahdesign.vocalis

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import website.ahdesign.vocalis.companion.history.HistoryStore
import website.ahdesign.vocalis.companion.history.Session
import website.ahdesign.vocalis.databinding.ActivityHistoryBinding

/** Browse saved sessions (grouped, newest first) with a topic tag; search by topic or content. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private var allSessions: List<Session> = emptyList()

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
        val search = menu.findItem(R.id.action_search).actionView as SearchView
        search.queryHint = getString(R.string.history_search_hint)
        search.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    showSessions(filter(newText.orEmpty()))
                    return true
                }
            },
        )
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
            allSessions = HistoryStore(this@HistoryActivity).sessions()
            showSessions(allSessions)
        }
    }

    private fun showSessions(sessions: List<Session>) {
        val rows =
            buildList {
                sessions.forEach { session ->
                    add(HistoryRow.Header(session))
                    session.turns.forEach { add(HistoryRow.TurnItem(it)) }
                }
            }
        binding.historyList.adapter = HistoryAdapter(rows)
        binding.emptyView.isVisible = rows.isEmpty()
    }

    private fun filter(query: String): List<Session> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return allSessions
        return allSessions.filter { session ->
            session.tag.lowercase().contains(q) ||
                session.turns.any {
                    it.heard.lowercase().contains(q) ||
                        it.meaning.lowercase().contains(q) ||
                        it.reply.text
                            .lowercase()
                            .contains(q)
                }
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
