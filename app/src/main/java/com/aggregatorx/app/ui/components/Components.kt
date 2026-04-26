package com.aggregatorx.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.media.RecoveryStrategy
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.VideoPreviewResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Futuristic Search Bar with glow effect
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FuturisticSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search across all providers...",
    isLoading: Boolean = false,
    suggestions: List<String> = emptyList(),
    onSuggestionClick: (String) -> Unit = {},
    selectedProviders: Set<String> = emptySet(),
    availableProviders: List<Provider> = emptyList(),
    onProviderToggle: (String) -> Unit = {},
    showAdvanced: Boolean = false,
    onToggleAdvanced: () -> Unit = {}
) {
    var showSuggestions by remember { mutableStateOf(false) }
    var showProviderFilter by remember { mutableStateOf(false) }

    val glowAlpha by animateFloatAsState(
        targetValue = if (query.isNotEmpty() || showSuggestions || showProviderFilter) 0.6f else 0.3f,
        animationSpec = tween(300)
    )

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(CyberCyan, CyberBlue, CyberPurple)
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                        alpha = glowAlpha
                    )
                }
                .clip(RoundedCornerShape(16.dp))
                .background(DarkCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                BasicTextField(
                    value = query,
                    onValueChange = {
                        onQueryChange(it)
                        showSuggestions = it.isNotEmpty() && suggestions.isNotEmpty()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 16.sp
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = TextTertiary,
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = CyberCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    // Provider filter button
                    IconButton(onClick = { showProviderFilter = !showProviderFilter }) {
                        Icon(
                            imageVector = if (selectedProviders.isNotEmpty()) Icons.Default.FilterList else Icons.Outlined.FilterList,
                            contentDescription = "Filter providers",
                            tint = if (selectedProviders.isNotEmpty()) CyberCyan else TextTertiary
                        )
                    }

                    // Advanced options button
                    IconButton(onClick = onToggleAdvanced) {
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Advanced options",
                            tint = TextTertiary
                        )
                    }

                    // Search button
                    IconButton(onClick = onSearch, enabled = query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Search",
                            tint = if (query.isNotEmpty()) CyberCyan else TextTertiary
                        )
                    }
                }
            }
        }

        // Provider filter dropdown
        AnimatedVisibility(
            visible = showProviderFilter,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Search in providers:",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableProviders.size) { index ->
                            val provider = availableProviders[index]
                            FilterChip(
                                selected = selectedProviders.contains(provider.id),
                                onClick = { onProviderToggle(provider.id) },
                                label = {
                                    Text(
                                        text = provider.name,
                                        color = if (selectedProviders.contains(provider.id)) TextPrimary else TextSecondary
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = CyberCyan
                                )
                            )
                        }
                    }
                }
            }
        }

        // Search suggestions
        AnimatedVisibility(
            visible = showSuggestions && suggestions.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(suggestions.size) { index ->
                        val suggestion = suggestions[index]
                        TextButton(
                            onClick = {
                                onSuggestionClick(suggestion)
                                showSuggestions = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = suggestion,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            }
        }

        // Advanced search options
        AnimatedVisibility(
            visible = showAdvanced,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Advanced Search Options",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Use natural language queries like \"funny cat videos\" or \"scary movies with jumpscares\"\n" +
                              "• Filter by specific providers using the filter button\n" +
                              "• Search history appears as you type\n" +
                              "• Results are intelligently ranked by relevance",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

/**
 * Glowing Button Component
 */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = CyberCyan,
    content: @Composable RowScope.() -> Unit
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(200)
    )
    
    Box(
        modifier = modifier
            .drawBehind {
                if (enabled) {
                    drawCircle(
                        color = color,
                        radius = size.minDimension / 2 + 4.dp.toPx(),
                        alpha = 0.3f
                    )
                }
            }
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = color.copy(alpha = animatedAlpha),
                contentColor = DarkBackground,
                disabledContainerColor = color.copy(alpha = 0.3f),
                disabledContentColor = DarkBackground.copy(alpha = 0.5f)
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.size(44.dp)
        ) {
            content()
        }
    }
}

/**
 * Provider Card Component
 */
@Composable
fun ProviderCard(
    provider: Provider,
    onToggle: (Boolean) -> Unit,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isAnalyzing: Boolean = false
) {
    val categoryColor = when (provider.category) {
        ProviderCategory.STREAMING -> CategoryStreaming
        ProviderCategory.TORRENT -> CategoryTorrent
        ProviderCategory.NEWS -> CategoryNews
        ProviderCategory.MEDIA -> CategoryMedia
        ProviderCategory.API_BASED -> CategoryAPI
        else -> CategoryGeneral
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        categoryColor.copy(alpha = 0.5f),
                        categoryColor.copy(alpha = 0.2f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Provider icon/avatar
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(categoryColor, categoryColor.copy(alpha = 0.5f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = provider.name.take(2).uppercase(),
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = provider.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Switch(
                    checked = provider.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkBackground,
                        checkedTrackColor = CyberCyan,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = DarkSurfaceVariant
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatChip(
                    label = "Searches",
                    value = provider.totalSearches.toString(),
                    color = CyberCyan
                )
                StatChip(
                    label = "Success",
                    value = "${((1f - provider.failedSearches.toFloat() / 
                        maxOf(provider.totalSearches, 1).toFloat()) * 100).toInt()}%",
                    color = AccentGreen
                )
                StatChip(
                    label = provider.category.name,
                    value = "",
                    color = categoryColor,
                    isCategory = true
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReanalyze,
                    enabled = !isAnalyzing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CyberCyan
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = !isAnalyzing).copy(
                        brush = Brush.horizontalGradient(
                            colors = listOf(CyberCyan.copy(alpha = 0.5f), CyberBlue.copy(alpha = 0.5f))
                        )
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = CyberCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyzing...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-analyze")
                    }
                }
                
                OutlinedButton(
                    onClick = onDelete,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Stat Chip Component
 */
@Composable
fun StatChip(
    label: String,
    value: String,
    color: Color,
    isCategory: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isCategory) {
                Text(
                    text = value,
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                color = if (isCategory) color else TextTertiary,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Result Thumbnail Component
 *
 * Gesture mapping (matches user expectation):
 *   TAP        → quick animated preview pulse (visual feedback only — lightweight)
 *   LONG PRESS → triggers fullscreen video extraction & playback of the FULL video
 *
 * This component is intentionally lightweight — NO inline ExoPlayer.
 * All heavy video extraction + playback happens in the fullscreen VideoPlayerDialog.
 */
private const val VIDEO_EXTRACTION_TIMEOUT_MS = 25_000L

@Composable
fun InlineThumbnailPreview(
    thumbnailUrl: String?,
    duration: String? = null,
    modifier: Modifier = Modifier,
    onHoldFullscreen: () -> Unit = {},
    isExtracting: Boolean = false
) {
    val context = LocalContext.current
    var imageLoadFailed by remember { mutableStateOf(false) }
    // Tap ripple animation
    var showTapPulse by remember { mutableStateOf(false) }
    val pulseAlpha by animateFloatAsState(
        targetValue = if (showTapPulse) 0.5f else 0f,
        animationSpec = tween(durationMillis = 300),
        finishedListener = { if (showTapPulse) showTapPulse = false }
    )

    // Reset pulse after brief flash
    LaunchedEffect(showTapPulse) {
        if (showTapPulse) {
            delay(350)
            showTapPulse = false
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // TAP → open fullscreen player immediately with visual feedback
                        showTapPulse = true
                        onHoldFullscreen()
                    },
                    onLongPress = {
                        // LONG PRESS → also opens fullscreen video player
                        onHoldFullscreen()
                    }
                )
            }
    ) {
        // ── Thumbnail Image ───────────────────────────────────────────────
        if (!thumbnailUrl.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { imageLoadFailed = true },
                onSuccess = { imageLoadFailed = false }
            )
        }

        // Placeholder when no thumbnail or load failure
        if (thumbnailUrl.isNullOrEmpty() || imageLoadFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(DarkSurfaceVariant, DarkBackground)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // ── Overlay: extracting spinner / play icon / tap pulse ───────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(
                        alpha = when {
                            isExtracting -> 0.55f
                            pulseAlpha > 0f -> 0.1f + pulseAlpha * 0.3f
                            else -> 0.22f
                        }
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                isExtracting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = CyberCyan,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Loading video…",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                        fontSize = 9.sp
                    )
                }

                else -> Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "Tap or hold to play",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Duration badge
        duration?.let { dur ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Text(
                    dur,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        // "Hold to watch" hint badge
        if (!isExtracting) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = CyberCyan.copy(alpha = 0.75f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = DarkBackground,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        "Hold",
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkBackground,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

/**
 * Checks whether a URL plausibly points to an actual media stream (not an
 * HTML page).  Used as a gate before handing URLs to ExoPlayer — this
 * prevents the "no source / trying alternative" errors that happen when
 * ExoPlayer tries to parse HTML as video.
 *
 * Returns true for common video extensions, stream keywords, CDN patterns,
 * and known video hosting domains.
 */
private fun isLikelyStreamUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val lowerUrl = url.lowercase()

    // === DEFINITE VIDEO INDICATORS (Return true immediately) ===
    
    // Obvious video file extensions (very reliable)
    val videoExtensions = listOf(
        ".mp4", ".m3u8", ".mpd", ".webm", ".mkv", ".avi", ".mov",
        ".flv", ".wmv", ".ts", ".m4v", ".3gp", ".f4v", ".ogv",
        ".opus", ".vtt", ".srt", ".json"  // Playlist/subtitle formats
    )
    if (videoExtensions.any { lowerUrl.contains(it) }) return true

    // Known streaming service CDNs (very high confidence)
    val knownStreamingCdns = listOf(
        // Major CDNs
        "akamai", "cloudflare", "cloudfront", "fastly", "bunny",
        "cdn77", "cdnjs", "stackpath",
        
        // Video hosting services
        "youtube", "youtu.be", "vimeo", "dailymotion", "twitch",
        "facebook.com/video", "instagram", "tiktok", "telegram",
        "udemy", "skillshare", "coursera",
        
        // Streaming providers
        "netflix", "amazon", "hulu", "disney", "hbo", "peacock",
        "paramount", "appletv",
        
        // Video CDN services
        "brightcove", "kaltura", "ooyala", "wistia", "mux",
        "jwplayer", "theoplayer", "bitmovin",
        
        // Adult/streaming sites (common requests)
        "pornhub", "xvideos", "xnxx", "redtube", "youporn",
        
        // Torrent/P2P streaming
        "webtorrent", "ipfs", "magnet:",
        
        // Generic but strong indicators
        "blob:", "data:video", "stream", "video", "media",
        "playback", "content-deliver", "dash.akamai"
    )
    if (knownStreamingCdns.any { lowerUrl.contains(it) }) return true

    // Stream path keywords (strong indicators)
    val streamKeywords = listOf(
        "/video/", "/stream/", "/hls/", "/dash/", "/manifest",
        "/m3u8", "/mpd", "videoplayback", "/get_video", "/dl/",
        "/embed/", "/media/", "/cdn-cgi/", "/file/", "/play",
        "/vodplay", "/live/", "/master.", "/index.m3u",
        "segment", "playlist", "track", "/source/", "/blob/",
        "/content/", "/asset/", "delivery", "progressive_download",
        "chunklist", "resolution="
    )
    if (streamKeywords.any { lowerUrl.contains(it) }) return true

    // Dynamic stream URLs with query parameters
    val videoQueryParams = setOf(
        "video_id", "videoid", "stream", "file", "source",
        "url", "src", "video", "content", "media", "m3u8",
        "mpd", "vod", "hls", "dash", "playback", "videofile",
        "videourl", "streamurl", "playlisturl", "token", "key",
        "sig", "signature", "auth"
    )
    val queryPairs = lowerUrl.split("?").getOrNull(1)?.split("&") ?: emptyList()
    if (queryPairs.any { param ->
        videoQueryParams.any { paramName ->
            param.contains(paramName + "=") || param.startsWith(paramName)
        }
    }) return true

    // === DEFINITE NOT-VIDEO INDICATORS (Return false immediately) ===
    
    val notVideoIndicators = listOf(
        "/search?", "/category/", "/tag/", "/login", "/register",
        "/user/", "/forum/", "/browse", "/home", "/homepage",
        "/page/", "/post/", "/product/", "/cart", "text/html",
        ".html", ".asp", ".php", ".jsp", "?page=", "?sort=",
        "?filter=", "/api/", "/graphql", ".xml", ".json"
    )
    if (notVideoIndicators.any { lowerUrl.contains(it) && !lowerUrl.contains("video") }) {
        return false
    }

    // === HEURISTICS (Less certain, but still good indicators) ===
    
    // If it has a blob: URL, it's almost always a media stream from JS extraction
    if (lowerUrl.startsWith("blob:")) return true
    
    // Base64-encoded video data
    if (lowerUrl.startsWith("data:") && (lowerUrl.contains("video") || 
        lowerUrl.contains("base64") || lowerUrl.contains("mp4"))) return true
    
    // URLs with token/signature parameters (common for protected streams)
    if ((lowerUrl.contains("?") || lowerUrl.contains("&")) &&
        (lowerUrl.contains("token") || lowerUrl.contains("sig") || 
         lowerUrl.contains("key") || lowerUrl.contains("auth") ||
         lowerUrl.contains("expires"))) return true
    
    // Long numeric or alphanumeric paths (common for CDN streamed content)
    val pathPart = lowerUrl.split("?")[0].split("/").last()
    if (pathPart.length > 20 && !lowerUrl.contains(".html") && 
        !lowerUrl.contains("text/")) {
        return true
    }
    
    // If URL structure looks like it could be a stream (has ? params, no obvious HTML indicators)
    if (lowerUrl.contains("?") && !notVideoIndicators.any { lowerUrl.contains(it) }) {
        return true
    }

    // DEFAULT: Trust extraction engines - they found this URL for a reason
    // ExoPlayer can auto-detect format, so don't be too strict
    return true
}

/**
 * Search Result Card Component - Enhanced with Inline Video Preview & Download
 */
@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit,
    onDownload: () -> Unit = {},
    onOpenExternal: () -> Unit = {},
    onLike: () -> Unit = {},
    isLiked: Boolean = false,
    showControls: Boolean = true,
    onExtractVideoUrl: (suspend (String) -> String?)? = null,
    onExtractVideoForPreview: (suspend (String) -> VideoPreviewResult?)? = null,
    onResolveVideoStream: (suspend (String, RecoveryStrategy?) -> VideoPreviewResult?)? = null,
    onViewInApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scoreColor = getScoreColor(result.relevanceScore)
    val scope = rememberCoroutineScope()
    val canViewInApp = result.url.startsWith("http://") || result.url.startsWith("https://")
    
    var showFullscreenPlayer by remember { mutableStateOf(false) }
    var fullscreenVideoUrl by remember { mutableStateOf<String?>(null) }
    var fullscreenVideoHeaders by remember { mutableStateOf<Map<String, String>?>(null) }
    var isExtractingForFullscreen by remember { mutableStateOf(false) }
    var showExtractionError by remember { mutableStateOf(false) }
    var extractionErrorMessage by remember { mutableStateOf<String?>(null) }

    /**
     * Launches full video extraction then opens the fullscreen player.
     *
     * KEY DESIGN RULE: we NEVER pass a raw HTML page URL to ExoPlayer.
     * Only URLs that look like actual media streams (contain common
     * video extensions, stream keywords, or known CDN patterns) are
     * sent to the player.  If extraction completely fails we show an
     * error snackbar and offer "Open in Browser" instead of letting
     * ExoPlayer choke on HTML.
     */
    val openFullscreenPlayer: () -> Unit = {
        if (!result.url.isNullOrEmpty() && !isExtractingForFullscreen) {
            isExtractingForFullscreen = true
            scope.launch {
                try {
                    val resolvedPair = withTimeoutOrNull(VIDEO_EXTRACTION_TIMEOUT_MS) {
                        var resolvedUrl: String? = null
                        var resolvedHeaders: Map<String, String>? = null

                        // Attempt 1: full extraction chain (7-step) with headers
                        // ENHANCED: Try to use ANY extracted URL, not just those matching strict patterns
                        if (resolvedUrl == null && onExtractVideoForPreview != null) {
                            try {
                                val previewResult = onExtractVideoForPreview(result.url)
                                if (previewResult != null && previewResult.videoUrl.isNotEmpty()) {
                                    // Trust extraction engine - if it found a URL, try to play it
                                    // Let ExoPlayer's format detection handle edge cases
                                    resolvedUrl = previewResult.videoUrl
                                    resolvedHeaders = previewResult.headers
                                }
                            } catch (e: Exception) {
                                // Extraction failed, continue to next method
                            }
                        }

                        // Attempt 2: resolver fallback for proxy/headless recovery
                        if (resolvedUrl == null && onResolveVideoStream != null) {
                            try {
                                val resolverResult = onResolveVideoStream(result.url, null)
                                if (resolverResult != null && resolverResult.videoUrl.isNotEmpty()) {
                                    resolvedUrl = resolverResult.videoUrl
                                    resolvedHeaders = resolverResult.headers
                                }
                            } catch (e: Exception) {
                                // Continue to next method
                            }
                        }

                        // Attempt 3: simple URL extraction
                        if (resolvedUrl == null && onExtractVideoUrl != null) {
                            try {
                                val extractedUrl = onExtractVideoUrl(result.url)
                                if (!extractedUrl.isNullOrEmpty()) {
                                    // Trust the simplistic extractor too
                                    resolvedUrl = extractedUrl
                                    resolvedHeaders = null
                                }
                            } catch (e: Exception) {
                                // Continue to next method
                            }
                        }

                        // Attempt 4: raw URL if it looks promising
                        if (resolvedUrl == null) {
                            if (!result.url.isNullOrEmpty() && isLikelyStreamUrl(result.url)) {
                                resolvedUrl = result.url
                                resolvedHeaders = null
                            }
                        }

                        // Attempt 4: FALLBACK - Try raw URL even if validation uncertain
                        // ExoPlayer can auto-detect, so don't reject valid extraction
                        if (resolvedUrl == null && !result.url.isNullOrEmpty()) {
                            // Only reject obvious HTML pages
                            val lowerUrl = result.url.lowercase()
                            val isDefinitelyHtml = lowerUrl.contains(".html") || 
                                                lowerUrl.contains("text/html") ||
                                                lowerUrl.contains("?page=") ||
                                                lowerUrl.contains("?sort=")
                            
                            if (!isDefinitelyHtml) {
                                resolvedUrl = result.url
                                resolvedHeaders = null
                            }
                        }

                        // Return the resolved URL and headers as a pair
                        if (resolvedUrl != null) {
                            Pair(resolvedUrl, resolvedHeaders)
                        } else {
                            null
                        }
                    }

                    if (resolvedPair != null) {
                        fullscreenVideoUrl = resolvedPair.first
                        fullscreenVideoHeaders = resolvedPair.second
                        showFullscreenPlayer = true
                        showExtractionError = false
                        extractionErrorMessage = null
                    } else {
                        // Extraction failed — do NOT hand garbage to ExoPlayer
                        showExtractionError = true
                        extractionErrorMessage = "Could not find a playable video stream. Try \"Browser\" to open the page directly."
                    }
                } catch (e: Exception) {
                    showExtractionError = true
                    extractionErrorMessage = "Video extraction failed: ${e.message?.take(80) ?: "unknown error"}"
                } finally {
                    isExtractingForFullscreen = false
                }
            }
        }
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenExternal)
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        scoreColor.copy(alpha = 0.3f),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Thumbnail with gesture: TAP = visual preview pulse, HOLD = fullscreen video
                InlineThumbnailPreview(
                    thumbnailUrl = result.thumbnailUrl,
                    duration = result.duration,
                    isExtracting = isExtractingForFullscreen,
                    modifier = Modifier.size(140.dp),
                    onHoldFullscreen = openFullscreenPlayer
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Title
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Description
                    result.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    // Metadata row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        // Score
                        ScoreBadge(score = result.relevanceScore)
                        
                        // Seeders
                        result.seeders?.let { seeders ->
                            MetadataBadge(
                                icon = Icons.Default.ArrowUpward,
                                value = seeders.toString(),
                                color = AccentGreen
                            )
                        }
                        
                        // Size
                        result.size?.let { size ->
                            MetadataBadge(
                                icon = Icons.Default.Storage,
                                value = size,
                                color = TextTertiary
                            )
                        }
                        // Quality
                        result.quality?.let { quality ->
                            QualityBadge(quality = quality)
                        }
                        
                        // Rating
                        result.rating?.let { rating ->
                            MetadataBadge(
                                icon = Icons.Default.Star,
                                value = String.format("%.1f", rating),
                                color = AccentYellow
                            )
                        }
                    }
                }
            }
            
            // Fullscreen player dialog — HOLD thumbnail or Watch button
            if (showFullscreenPlayer && !fullscreenVideoUrl.isNullOrEmpty()) {
                VideoPlayerDialog(
                    videoUrl = fullscreenVideoUrl!!,
                    title = result.title,
                    headers = fullscreenVideoHeaders,
                    onStreamError = { _, recovery ->
                        scope.launch {
                            if (onResolveVideoStream != null) {
                                try {
                                    val fallback = onResolveVideoStream(result.url, recovery)
                                    if (fallback != null && fallback.videoUrl.isNotEmpty()) {
                                        fullscreenVideoUrl = fallback.videoUrl
                                        fullscreenVideoHeaders = fallback.headers
                                    }
                                } catch (_: Exception) {
                                    // best-effort recovery only
                                }
                            }
                        }
                    },
                    onDismiss = {
                        showFullscreenPlayer = false
                        fullscreenVideoUrl = null
                        fullscreenVideoHeaders = null
                    },
                    onOpenExternal = {
                        showFullscreenPlayer = false
                        fullscreenVideoUrl = null
                        fullscreenVideoHeaders = null
                        onOpenExternal()
                    }
                )
            }

            // Extraction error dialog — shown when we couldn't find a playable stream
            if (showExtractionError) {
                AlertDialog(
                    onDismissRequest = {
                        showExtractionError = false
                        extractionErrorMessage = null
                    },
                    containerColor = DarkCard,
                    titleContentColor = TextPrimary,
                    textContentColor = TextSecondary,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Video Unavailable", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    text = {
                        Text(
                            extractionErrorMessage ?: "Could not extract a playable video stream.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExtractionError = false
                                extractionErrorMessage = null
                                onOpenExternal()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan,
                                contentColor = DarkBackground
                            )
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open in Browser")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showExtractionError = false
                            extractionErrorMessage = null
                        }) {
                            Text("Close", color = TextSecondary)
                        }
                    }
                )
            }

            // Action buttons row
            if (showControls) {
                HorizontalDivider(
                    color = DarkSurfaceVariant,
                    thickness = 1.dp
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Watch button - Opens fullscreen video player
                    Button(
                        onClick = openFullscreenPlayer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCyan,
                            contentColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Watch",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // Download button - Auto downloads highest quality
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen.copy(alpha = 0.9f),
                            contentColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // Open in browser button
                    OutlinedButton(
                        onClick = onOpenExternal,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = CyberCyan
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = Brush.horizontalGradient(
                                colors = listOf(CyberCyan.copy(alpha = 0.6f), CyberBlue.copy(alpha = 0.6f))
                            )
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Browser",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (canViewInApp) {
                        OutlinedButton(
                            onClick = onViewInApp,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = CyberCyan
                            ),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(CyberCyan.copy(alpha = 0.4f), CyberBlue.copy(alpha = 0.4f))
                                )
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "In App",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    // Like / thumbs-up button
                    IconButton(
                        onClick = onLike,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) Color(0xFFFF4081) else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Download Progress Card
 */
@Composable
fun DownloadProgressCard(
    title: String,
    progress: Int,
    status: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = AccentRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = CyberCyan,
                trackColor = DarkSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Score Badge
 */
@Composable
fun ScoreBadge(score: Float) {
    val color = getScoreColor(score)
    
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = "${score.toInt()}",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Metadata Badge
 */
@Composable
fun MetadataBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = value,
            color = color,
            fontSize = 10.sp
        )
    }
}

/**
 * Quality Badge
 */
@Composable
fun QualityBadge(quality: String) {
    val color = when {
        quality.contains("4k", ignoreCase = true) || quality.contains("2160") -> AccentGreen
        quality.contains("1080") || quality.contains("full hd", ignoreCase = true) -> CyberCyan
        quality.contains("720") -> CyberBlue
        else -> TextTertiary
    }
    
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = quality.uppercase(),
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/**
 * Provider Results Section Header - with refresh and pagination buttons
 */
@Composable
fun ProviderResultsHeader(
    providerName: String,
    resultCount: Int,
    searchTime: Long,
    success: Boolean,
    errorMessage: String? = null,
    onRefresh: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    onPreviousPage: (() -> Unit)? = null,
    isActionLoading: Boolean = false,
    currentPage: Int = 1,
    hasNextPage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val categoryColor = CyberCyan
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        if (success) categoryColor.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                        Color.Transparent
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (success) AccentGreen else AccentRed)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = providerName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Page $currentPage",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (!success && errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentRed
                        )
                    }
                }
            }

            if (success && (onRefresh != null || onPreviousPage != null || onNextPage != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    onRefresh?.let {
                        IconButton(
                            onClick = it,
                            enabled = !isActionLoading,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh provider results",
                                tint = if (!isActionLoading) CyberCyan else TextTertiary.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    onPreviousPage?.let {
                        IconButton(
                            onClick = it,
                            enabled = !isActionLoading && currentPage > 1,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous provider page",
                                tint = if (!isActionLoading && currentPage > 1) CyberCyan else TextTertiary.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    onNextPage?.let {
                        val nextEnabled = !isActionLoading
                        IconButton(
                            onClick = it,
                            enabled = nextEnabled,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next provider page",
                                tint = if (nextEnabled) CyberCyan else TextTertiary.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$resultCount results",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${searchTime}ms",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

/**
 * Animated Loading Indicator
 */
@Composable
fun FuturisticLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val infiniteTransition = rememberInfiniteTransition()
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(size)
                .drawBehind {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(CyberCyan, CyberBlue, CyberPurple, CyberCyan)
                        ),
                        radius = this.size.minDimension / 2,
                        style = Stroke(width = 3.dp.toPx()),
                        alpha = glowAlpha
                    )
                }
                .graphicsLayer { rotationZ = rotation }
        )
        
        // Inner circle
        Box(
            modifier = Modifier
                .size(size * 0.6f)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(CyberCyan.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
    }
}

/**
 * Empty State Component
 */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextTertiary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/**
 * Security Score Indicator
 */
@Composable
fun SecurityScoreIndicator(
    score: Float,
    modifier: Modifier = Modifier
) {
    val color = getSecurityColor(score)
    val animatedScore by animateFloatAsState(
        targetValue = score,
        animationSpec = tween(1000)
    )
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .drawBehind {
                    // Background circle
                    drawCircle(
                        color = DarkSurfaceVariant,
                        radius = size.minDimension / 2,
                        style = Stroke(width = 8.dp.toPx())
                    )
                    // Progress arc
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = (animatedScore / 100f) * 360f,
                        useCenter = false,
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${score.toInt()}",
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Security Score",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}

/**
 * Provider Quick Navigation Tabs
 * 
 * Shows enabled providers as clickable tabs at the top of search results.
 * Clicking a tab smoothly scrolls to that provider's results section.
 */
@Composable
fun ProviderQuickTabs(
    providers: List<Provider>,
    selectedProvider: Provider? = null,
    onProviderClick: (Provider) -> Unit,
    modifier: Modifier = Modifier
) {
    if (providers.isEmpty()) return
    
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    // Auto-scroll to selected provider's tab
    LaunchedEffect(selectedProvider) {
        selectedProvider?.let { provider ->
            val index = providers.indexOfFirst { it.id == provider.id }
            if (index >= 0) {
                scope.launch {
                    scrollState.animateScrollTo(index * 120)
                }
            }
        }
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(DarkSurface, DarkSurfaceVariant)
                )
            )
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        providers.forEach { provider ->
            val isSelected = provider.id == selectedProvider?.id
            
            Surface(
                modifier = Modifier
                    .width(110.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onProviderClick(provider) },
                color = if (isSelected) MaterialTheme.colorScheme.primary else DarkSurfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Provider icon if available
                    if (!provider.iconUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = provider.iconUrl,
                            contentDescription = provider.name,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            contentScale = ContentScale.Crop,
                            alpha = if (isSelected) 1f else 0.7f
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    
                    // Provider name
                    Text(
                        text = provider.name.take(12),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Enhanced Result Viewer for In-App Content Display
 * 
 * Allows results to be shown within the app in various formats:
 * - WebView for HTML content
 * - Video player for media files
 * - Image gallery for image results
 * - Expandable detail cards
 */
@Composable
fun EnhancedResultViewer(
    result: SearchResult,
    viewerType: ResultViewerType = ResultViewerType.EXTERNAL_LINK,
    onClose: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (viewerType) {
        ResultViewerType.EXTERNAL_LINK -> {
            // Default - show external link option
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (!result.description.isNullOrEmpty()) {
                    Text(
                        text = result.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                Button(
                    onClick = onOpenExternal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in Browser")
                }
            }
        }
        
        ResultViewerType.IN_APP_WEBVIEW -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(DarkBackground)
            ) {
                Surface(
                    color = DarkSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = result.title.take(30),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close"
                            )
                        }
                    }
                }

                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            loadUrl(result.url)
                        }
                    },
                    update = { webView ->
                        if (webView.url != result.url) {
                            webView.loadUrl(result.url)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                )
            }
        }
        
        ResultViewerType.IMAGE_GALLERY -> {
            // Image gallery view
            Column(
                modifier = modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!result.thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = result.thumbnailUrl,
                        contentDescription = result.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
        
        else -> {
            // Default fallback
            Box(
                modifier = modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Viewer type not supported",
                    color = TextTertiary
                )
            }
        }
    }
}