package website.ahdesign.vocalis.wear.presentation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import website.ahdesign.vocalis.Engine
import website.ahdesign.vocalis.Turn
import website.ahdesign.vocalis.wear.data.PhoneBridge

/**
 * Round-screen UI. Top: a button + the latest exchange (big, readable). Below: a short scroll of
 * recent turns. The reply ("Say this") is the largest element — it's the actionable bit.
 */
@Composable
fun ListenScreen(
    bridge: PhoneBridge,
    listening: Boolean,
    hasMicPermission: () -> Boolean,
    onToggle: () -> Unit,
) {
    val latest by bridge.latest.collectAsState()
    val recent by bridge.recent.collectAsState()

    // Ask for RECORD_AUDIO just-in-time; on grant, proceed to start listening.
    val micLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onToggle()
        }
    val onClick: () -> Unit = {
        if (listening || hasMicPermission()) {
            onToggle()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            if (listening) {
                // While listening, the button IS the indicator: "● Listening…" with a small "Stop".
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(0.85f).height(40.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● Listening…", style = MaterialTheme.typography.caption1)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop", style = MaterialTheme.typography.caption3)
                    }
                }
            } else {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Tap to listen")
                }
            }
        }

        latest?.let { turn ->
            item { LatestCard(turn) }
        }

        if (recent.size > 1) {
            item { Text("History", style = MaterialTheme.typography.caption1) }
            items(recent.drop(1)) { turn -> HistoryRow(turn) }
        }
    }
}

@Composable
private fun LatestCard(turn: Turn) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            Text(
                turn.reply.text,
                style = MaterialTheme.typography.title1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            turn.reply.phonetic?.let { Text("($it)", style = MaterialTheme.typography.caption2) }
            Text(turn.meaning, style = MaterialTheme.typography.body2)
            Text(turn.heard, style = MaterialTheme.typography.caption2)
            if (turn.engine != Engine.ONLINE) {
                Text("offline", style = MaterialTheme.typography.caption2)
            }
        }
    }
}

@Composable
private fun HistoryRow(turn: Turn) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            Text(turn.reply.text, style = MaterialTheme.typography.body2)
            Text(turn.meaning, style = MaterialTheme.typography.caption2)
        }
    }
}
