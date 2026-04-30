package com.aggregatorx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.ui.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenBrowser: (String) -> Unit,
    onWatchVideo: (String) -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val resultsByProvider by viewModel.resultsByProvider.collectAsState()
    val loadingProviders by viewModel.loadingProviders.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Persistent Search Bar
        TopAppBar(
            title = {
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.performNewSearch(it) },
                    placeholder = { Text("Search authorized sites...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
            }
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            resultsByProvider.forEach { (providerName, items) ->
                item(key = providerName) {
                    ProviderSection(
                        name = providerName,
                        items = items,
                        isLoading = loadingProviders.contains(providerName),
                        onPagination = { direction -> viewModel.navigateProviderPage(providerName, direction) },
                        onLike = { item -> viewModel.toggleLike(item) },
                        onOpenBrowser = onOpenBrowser,
                        onWatchVideo = onWatchVideo
                    )
                }
            }
        }
    }
}

@Composable
fun ProviderSection(
    name: String,
    items: List<ResultItem>,
    isLoading: Boolean,
    onPagination: (Int) -> Unit,
    onLike: (ResultItem) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onWatchVideo: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                
                // Per-Provider Pagination Buttons
                Row {
                    IconButton(onClick = { onPagination(-1) }) { 
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back Page") 
                    }
                    IconButton(onClick = { onPagination(0) }) { 
                        if (isLoading) CircularProgressIndicator(size = 20.dp) 
                        else Icon(Icons.Default.Refresh, contentDescription = "Refresh") 
                    }
                    IconButton(onClick = { onPagination(1) }) { 
                        Icon(Icons.Default.ArrowForward, contentDescription = "Forward Page") 
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    items.forEach { item ->
                        ResultRow(
                            item = item,
                            onLike = { onLike(item) },
                            onOpenBrowser = { onOpenBrowser(item.url) },
                            onWatchVideo = { onWatchVideo(item.videoUrl ?: "") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ResultRow(
    item: ResultItem,
    onLike: () -> Unit,
    onOpenBrowser: () -> Unit,
    onWatchVideo: () -> Unit
) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = { Text(item.description ?: "") },
        trailingContent = {
            Row {
                IconButton(onClick = onLike) {
                    Icon(
                        imageVector = if (item.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (item.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = onOpenBrowser) { Icon(Icons.Default.Public, contentDescription = "Browser") }
                if (item.videoUrl != null) {
                    IconButton(onClick = onWatchVideo) { Icon(Icons.Default.PlayArrow, contentDescription = "Watch") }
                }
            }
        }
    )
}
