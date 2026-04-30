package com.aggregatorx.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.ui.activity.VideoPlayerActivity
import com.aggregatorx.app.ui.viewmodel.SearchViewModel

@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val resultsByProvider by viewModel.resultsByProvider.collectAsState()
    val query by viewModel.currentQuery.collectAsState()
    val isAiProcessing by viewModel.isAiProcessing.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Search Bar
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.performSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search with AI NLP...") },
            trailingIcon = {
                if (isAiProcessing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            resultsByProvider.forEach { (providerName, results) ->
                item(key = providerName) {
                    ProviderResultCard(
                        name = providerName,
                        results = results,
                        onNavigate = { forward -> viewModel.navigateProviderPage(providerName, forward) },
                        onRefresh = { viewModel.refreshProvider(providerName) },
                        onLike = { viewModel.toggleLike(it) },
                        onWatch = { url ->
                            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                                putExtra("VIDEO_URL", url)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ProviderResultCard(
    name: String,
    results: List<ResultItem>,
    onNavigate: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onLike: (ResultItem) -> Unit,
    onWatch: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    results.take(5).forEach { item ->
                        ResultRow(item, onLike, onWatch)
                    }
                    
                    // Pagination Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = { onNavigate(false) }) { Icon(Icons.Default.ArrowBack, "Back") }
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = { onNavigate(true) }) { Icon(Icons.Default.ArrowForward, "Forward") }
                    }
                }
            }
        }
    }
}

@Composable
fun ResultRow(item: ResultItem, onLike: (ResultItem) -> Unit, onWatch: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = { Text(item.providerName, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Row {
                IconButton(onClick = { onLike(item) }) {
                    Icon(Icons.Default.Favorite, contentDescription = "Like",
                        tint = if (item.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { item.videoUrl?.let { onWatch(it) } }) {
                    Icon(Icons.Default.PlayArrow, "Watch")
                }
            }
        }
    )
}
