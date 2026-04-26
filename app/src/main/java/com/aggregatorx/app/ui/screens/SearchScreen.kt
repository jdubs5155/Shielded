package com.aggregatorx.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.ProviderSearchResults
import com.aggregatorx.app.data.model.ResultViewerType
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.ui.components.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.SearchUiState
import com.aggregatorx.app.ui.viewmodel.SearchViewModel
import com.aggregatorx.app.engine.media.RecoveryStrategy
import com.aggregatorx.app.ui.viewmodel.VideoPreviewResult
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val providerResults by viewModel.providerResults.collectAsState()
    val likedUrls by viewModel.likedUrls.collectAsState()
    val providerPageIndex by viewModel.providerPageIndex.collectAsState()
    val providerActionLoading by viewModel.providerActionLoading.collectAsState()
    val context = LocalContext.current
    
    val listState = rememberLazyListState()
    var inAppViewerResult by remember { mutableStateOf<SearchResult?>(null) }
    
    // Scroll-aware UI collapse for more result viewing space
    val isScrollingDown = remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 50
    }}
    
    // Track if we have results to show (determines if header should be collapsible)
    val hasResults = providerResults.isNotEmpty()
    
    // Collapse header when scrolling down through results
    val collapseHeader = hasResults && isScrollingDown.value
    
    // Animated height for collapsible header section
    val headerHeight by animateDpAsState(
        targetValue = if (collapseHeader) 0.dp else 76.dp,
        animationSpec = tween(durationMillis = 200)
    )
    
    val headerAlpha by animateFloatAsState(
        targetValue = if (collapseHeader) 0f else 1f,
        animationSpec = tween(durationMillis = 150)
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(DarkBackground, DarkSurface)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Collapsible header section - only shows when NOT scrolling through results
            AnimatedVisibility(
                visible = !collapseHeader,
                enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(100)) + shrinkVertically(animationSpec = tween(150))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Search bar - always visible at top when not scrolling
                    FuturisticSearchBar(
                        query = uiState.query,
                        onQueryChange = viewModel::updateQuery,
                        onSearch = viewModel::search,
                        isLoading = uiState.isSearching,
                        suggestions = uiState.recentSearches.map { it.query }.take(5),
                        onSuggestionClick = viewModel::searchFromHistory,
                        selectedProviders = uiState.selectedProviders,
                        availableProviders = uiState.availableProviders,
                        onProviderToggle = viewModel::toggleProviderSelection,
                        showAdvanced = uiState.showAdvancedOptions,
                        onToggleAdvanced = viewModel::toggleAdvancedOptions
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            
            // Search stats - collapses when scrolling
            AnimatedVisibility(
                visible = (uiState.searchCompleted || uiState.isSearching) && !collapseHeader,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                SearchStatsBar(
                    totalResults = uiState.totalResults,
                    successfulProviders = uiState.successfulProviders,
                    failedProviders = uiState.failedProviders,
                    isSearching = uiState.isSearching
                )
            }
            
            // Small spacer always present
            Spacer(modifier = Modifier.height(if (collapseHeader) 4.dp else 8.dp))
            
            // Content
            when {
                uiState.isSearching && providerResults.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            FuturisticLoader(size = 64.dp)
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Searching across providers...",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                
                providerResults.isNotEmpty() -> {
                    ProviderResultsList(
                        providerResults = providerResults,
                        topResults = uiState.aggregatedResults?.topResults ?: emptyList(),
                        listState = listState,
                        onResultClick = { result ->
                            // Open in external browser
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                            context.startActivity(intent)
                        },
                        onDownload = { result ->
                            viewModel.downloadResult(result)
                            Toast.makeText(context, "Downloading: ${result.title}", Toast.LENGTH_SHORT).show()
                        },
                        onOpenExternal = { result ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                            context.startActivity(intent)
                        },
                        onViewInApp = { result ->
                            inAppViewerResult = result
                        },
                        onLike = { result -> viewModel.toggleLike(result) },
                        likedUrls = likedUrls,
                        onExtractVideoUrl = { url ->
                            viewModel.extractVideoUrlForPreview(url)
                        },
                        onExtractVideoForPreview = { url ->
                            viewModel.extractVideoForPreview(url)
                        },
                        onResolveVideoStream = { url, recovery ->
                            viewModel.resolveVideoForPlayback(url, recovery)
                        },
                        onRefreshProvider = { providerId ->
                            viewModel.refreshProviderResults(providerId)
                        },
                        onNextPage = { providerId ->
                            viewModel.loadProviderNextPage(providerId)
                        },
                        onPreviousPage = { providerId ->
                            viewModel.loadProviderPreviousPage(providerId)
                        },
                        providerPageIndex = providerPageIndex,
                        providerActionLoading = providerActionLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                uiState.recentSearches.isNotEmpty() && !uiState.searchCompleted -> {
                    RecentSearches(
                        searches = uiState.recentSearches,
                        onSearchClick = viewModel::searchFromHistory,
                        onClearAll = viewModel::clearSearchHistory,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                else -> {
                    EmptySearchState(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            }
        }
        
        // In-app result viewer overlay
        inAppViewerResult?.let { result ->
            EnhancedResultViewer(
                result = result,
                viewerType = ResultViewerType.IN_APP_WEBVIEW,
                onClose = { inAppViewerResult = null },
                onOpenExternal = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Error snackbar
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = AccentRed.copy(alpha = 0.9f),
                contentColor = TextPrimary,
                action = {
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = TextPrimary)
                    }
                }
            ) {
                Text(error)
            }
        }
    }
    
}

@Composable
fun SearchStatsBar(
    totalResults: Int,
    successfulProviders: Int,
    failedProviders: Int,
    isSearching: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkCard)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(
            icon = Icons.Default.Summarize,
            value = totalResults.toString(),
            label = "Results",
            color = CyberCyan
        )
        StatItem(
            icon = Icons.Default.CheckCircle,
            value = successfulProviders.toString(),
            label = "Success",
            color = AccentGreen
        )
        StatItem(
            icon = Icons.Default.Error,
            value = failedProviders.toString(),
            label = "Failed",
            color = if (failedProviders > 0) AccentRed else TextTertiary
        )
        if (isSearching) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = CyberCyan,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = label,
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun ProviderResultsList(
    providerResults: List<ProviderSearchResults>,
    topResults: List<SearchResult>,
    listState: LazyListState,
    onResultClick: (SearchResult) -> Unit,
    onDownload: (SearchResult) -> Unit = {},
    onOpenExternal: (SearchResult) -> Unit = {},
    onLike: (SearchResult) -> Unit = {},
    likedUrls: Set<String> = emptySet(),
    onExtractVideoUrl: (suspend (String) -> String?)? = null,
    onExtractVideoForPreview: (suspend (String) -> VideoPreviewResult?)? = null,
    onResolveVideoStream: (suspend (String, RecoveryStrategy?) -> VideoPreviewResult?)? = null,
    onRefreshProvider: (String) -> Unit = {},
    onNextPage: (String) -> Unit = {},
    onPreviousPage: (String) -> Unit = {},
    onViewInApp: (SearchResult) -> Unit = {},
    providerPageIndex: Map<String, Int> = emptyMap(),
    providerActionLoading: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    // Separate successful and failed providers (failed go to bottom)
    val successfulProviders: List<ProviderSearchResults> = providerResults.filter { it.success && it.results.isNotEmpty() }
    val emptyProviders: List<ProviderSearchResults> = providerResults.filter { it.success && it.results.isEmpty() }
    val failedProviders: List<ProviderSearchResults> = providerResults.filter { !it.success }
    
    // Provider quick-jump tabs
    var selectedProviderIndex by remember { mutableStateOf(-1) } // -1 = Top Results
    val coroutineScope = rememberCoroutineScope()
    
    Column(modifier = modifier) {
        // Provider Tab Bar for quick navigation
        if (successfulProviders.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                // Top Results tab
                item {
                    ProviderTabChip(
                        name = "🏆 Top",
                        count = topResults.size,
                        isSelected = selectedProviderIndex == -1,
                        isError = false,
                        onClick = {
                            selectedProviderIndex = -1
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                        }
                    )
                }
                
                // Provider tabs
                items(successfulProviders.size) { index ->
                    val provider = successfulProviders[index]
                    ProviderTabChip(
                        name = provider.provider.name,
                        count = provider.results.size,
                        isSelected = selectedProviderIndex == index,
                        isError = false,
                        onClick = {
                            selectedProviderIndex = index
                            // Calculate scroll position (top results + providers before this one)
                            val scrollTo = 1 + (if (topResults.isNotEmpty()) topResults.size + 2 else 0) +
                                successfulProviders.take(index).sumOf { it.results.size + 2 }
                            coroutineScope.launch {
                                listState.animateScrollToItem(scrollTo)
                            }
                        }
                    )
                }
                
                // Failed providers indicator
                if (failedProviders.isNotEmpty()) {
                    item {
                        ProviderTabChip(
                            name = "⚠️ Failed",
                            count = failedProviders.size,
                            isSelected = false,
                            isError = true,
                            onClick = {
                                // Scroll to bottom
                                coroutineScope.launch {
                                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                }
                            }
                        )
                    }
                }
            }
        }
        
        // Main results list with improved scrolling
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // Improve scroll performance
            userScrollEnabled = true
        ) {
            // Top Results Section
            if (topResults.isNotEmpty()) {
                item(key = "top_header") {
                    Text(
                        text = "🏆 Top Results",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentYellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(
                    items = topResults.take(10),
                    key = { "top_${it.url.hashCode()}" }
                ) { result ->
                    SearchResultCard(
                        result = result,
                        onClick = { onResultClick(result) },
                        onDownload = { onDownload(result) },
                        onOpenExternal = { onOpenExternal(result) },
                        onViewInApp = { onViewInApp(result) },
                        onLike = { onLike(result) },
                        isLiked = result.url in likedUrls,
                        showControls = true,
                        onExtractVideoUrl = onExtractVideoUrl,
                        onExtractVideoForPreview = onExtractVideoForPreview,
                        onResolveVideoStream = onResolveVideoStream
                    )
                }
                
                item(key = "top_divider") {
                    HorizontalDivider(
                        color = DarkSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
            
            // Provider sections - Successful providers with results first
            items(
                count = successfulProviders.size,
                key = { index -> "provider_${successfulProviders[index].provider.id}" },
                contentType = { "provider_section" }
            ) { providerIndex ->
                val providerResult = successfulProviders[providerIndex]
                
                Column {
                    // Header
                    ProviderResultsHeader(
                        providerName = providerResult.provider.name,
                        resultCount = providerResult.results.size,
                        searchTime = providerResult.searchTime,
                        success = true,
                        errorMessage = null,
                        onRefresh = { onRefreshProvider(providerResult.provider.id) },
                        onNextPage = { onNextPage(providerResult.provider.id) },
                        onPreviousPage = { onPreviousPage(providerResult.provider.id) },
                        isActionLoading = providerResult.provider.id in providerActionLoading,
                        currentPage = providerPageIndex[providerResult.provider.id] ?: 1,
                        hasNextPage = providerResult.hasMore
                    )
                    
                    // Results for this provider
                    providerResult.results.forEachIndexed { resultIndex, result ->
                        SearchResultCard(
                            result = result,
                            onClick = { onResultClick(result) },
                            onDownload = { onDownload(result) },
                            onOpenExternal = { onOpenExternal(result) },
                            onViewInApp = { onViewInApp(result) },
                            onLike = { onLike(result) },
                            isLiked = result.url in likedUrls,
                            showControls = true,
                            onExtractVideoUrl = onExtractVideoUrl,
                            onExtractVideoForPreview = onExtractVideoForPreview,
                            onResolveVideoStream = onResolveVideoStream,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    
                    // Spacer
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            
            // Empty providers (searched but no results)
            if (emptyProviders.isNotEmpty()) {
                item(key = "empty_header") {
                    Text(
                        text = "No Results From:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                item(key = "empty_list") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        emptyProviders.forEach { provider ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = DarkCard
                            ) {
                                Text(
                                    text = provider.provider.name,
                                    color = TextTertiary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            // Failed providers at the BOTTOM
            if (failedProviders.isNotEmpty()) {
                item(key = "failed_header") {
                    HorizontalDivider(
                        color = AccentRed.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Text(
                        text = "⚠️ Provider Errors",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentRed.copy(alpha = 0.8f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(
                    items = failedProviders,
                    key = { "failed_${it.provider.id}" }
                ) { providerResult ->
                    FailedProviderCard(
                        providerName = providerResult.provider.name,
                        errorMessage = providerResult.errorMessage ?: "Connection failed"
                    )
                }
            }
            
            // Bottom padding for better scrolling
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
fun ProviderTabChip(
    name: String,
    count: Int,
    isSelected: Boolean,
    isError: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isError -> AccentRed.copy(alpha = 0.2f)
        isSelected -> CyberCyan.copy(alpha = 0.3f)
        else -> DarkCard
    }
    val textColor = when {
        isError -> AccentRed
        isSelected -> CyberCyan
        else -> TextSecondary
    }
    
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = name,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            if (count > 0) {
                Surface(
                    shape = CircleShape,
                    color = textColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = count.toString(),
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FailedProviderCard(
    providerName: String,
    errorMessage: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentRed.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = AccentRed.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun RecentSearches(
    searches: List<com.aggregatorx.app.data.model.SearchHistoryEntry>,
    onSearchClick: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Searches",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            TextButton(onClick = onClearAll) {
                Text("Clear All", color = CyberCyan)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(searches) { search ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSearchClick(search.query) },
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = search.query,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${search.resultCount} results from ${search.providersSearched} providers",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySearchState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = TextTertiary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Start searching",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter a search term to find content\nacross all your configured providers",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Feature highlights
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FeatureChip(
                icon = Icons.Default.Speed,
                text = "Fast",
                color = AccentGreen
            )
            FeatureChip(
                icon = Icons.Default.Hub,
                text = "Multi-provider",
                color = CyberCyan
            )
            FeatureChip(
                icon = Icons.Default.AutoAwesome,
                text = "Smart Ranking",
                color = CyberPurple
            )
        }
    }
}

@Composable
fun FeatureChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
