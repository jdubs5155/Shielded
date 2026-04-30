package com.aggregatorx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import com.aggregatorx.app.engine.media.MediaDownloadManager
import com.aggregatorx.app.ui.viewmodel.DownloadsViewModel

/**
 * Downloads screen — observes [MediaDownloadManager.downloads] and lets the
 * user pause / resume / remove queued downloads. The list updates live as
 * Media3's [DownloadManager.Listener] fires inside [MediaDownloadManager].
 */
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val downloads by viewModel.downloads.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        if (downloads.isEmpty()) {
            Text(
                text = "No downloads yet. Tap the download icon on a result to queue one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(downloads, key = { it.id }) { snap ->
                DownloadRow(
                    snap = snap,
                    onPause  = { viewModel.pause(snap.id) },
                    onResume = { viewModel.resume(snap.id) },
                    onRemove = { viewModel.remove(snap.id) }
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    snap: MediaDownloadManager.DownloadSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = snap.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${stateLabel(snap.state)} · ${"%.0f".format(snap.percentDownloaded)}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Row {
                    when (snap.state) {
                        Download.STATE_DOWNLOADING -> IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                        }
                        Download.STATE_STOPPED, Download.STATE_QUEUED -> IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (snap.percentDownloaded / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Divider()
        }
    }
}

private fun stateLabel(state: Int): String = when (state) {
    Download.STATE_QUEUED       -> "Queued"
    Download.STATE_DOWNLOADING  -> "Downloading"
    Download.STATE_COMPLETED    -> "Completed"
    Download.STATE_STOPPED      -> "Paused"
    Download.STATE_FAILED       -> "Failed"
    Download.STATE_REMOVING     -> "Removing"
    Download.STATE_RESTARTING   -> "Restarting"
    else                        -> "Unknown"
}
