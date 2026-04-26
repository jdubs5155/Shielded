package com.aggregatorx.app.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aggregatorx.app.engine.util.EngineUtils
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.engine.media.RecoveryStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AggregatorX Enhanced Video Player Dialog
 * 
 * Features:
 * - Intelligent stream format detection (HLS, DASH, Progressive)
 * - Custom HTTP headers support for restricted content
 * - Automatic retry with different sources
 * - Netherlands proxy awareness
 * - Quality selection
 * - Smart error recovery
 * - Beautiful animated controls
 */

/**
 * Helper function to detect if URL is likely a playable video stream
 */
private fun isLikelyVideoUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val lowerUrl = url.lowercase()
    
    // Strong positive indicators - return true immediately
    val positiveIndicators = listOf(
        ".mp4", ".m3u8", ".mpd", ".webm", ".mkv", ".avi", ".mov",
        ".flv", ".wmv", ".ts", ".m4v", ".3gp", ".f4v", ".ogv",
        "/hls/", "/dash/", "/video/", "/stream/", "/manifest",
        "videoplayback", "youtube", "vimeo", "dailymotion",
        "blob:", "data:video", "akamai", "cloudflare", "cloudfront"
    )
    if (positiveIndicators.any { lowerUrl.contains(it) }) return true
    
    // Definite negative indicators - return false immediately
    val negativeIndicators = listOf(
        ".html", ".htm", "text/html", "/search?", "/category/",
        "/login", "/register", "?page=", "?sort="
    )
    if (negativeIndicators.any { lowerUrl.contains(it) }) return false
    
    // Default to true - trust ExoPlayer's auto-detection
    // It can handle more formats than we can validate here
    return true
}

/**
 * Detect the media type from URL for appropriate source handling.
 * Checks URL paths, query params, and common CDN patterns.
 */
private fun detectMediaType(url: String): MediaType {
    val lowerUrl = url.lowercase()
    return when {
        // HLS (HTTP Live Streaming) - m3u8 playlists
        lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/") ||
        lowerUrl.contains("index.m3u8") || lowerUrl.contains("master.m3u8") ||
        lowerUrl.contains("variant.m3u8") -> MediaType.HLS
        
        // DASH (Dynamic Adaptive Streaming over HTTP) - mpd manifests
        lowerUrl.contains(".mpd") || lowerUrl.contains("/dash/") ||
        lowerUrl.contains("manifest.mpd") -> MediaType.DASH
        
        // Progressive/Simple HTTP video files
        lowerUrl.contains(".mp4") || lowerUrl.contains(".webm") ||
        lowerUrl.contains(".mkv") || lowerUrl.contains(".avi") ||
        lowerUrl.contains(".mov") || lowerUrl.contains(".m4v") ||
        lowerUrl.contains(".flv") || lowerUrl.contains(".wmv") ||
        lowerUrl.contains(".3gp") || lowerUrl.contains(".ogv") ||
        lowerUrl.contains(".f4v") -> MediaType.PROGRESSIVE
        
        // MPEG-2 Transport Stream (often used in live TV)
        lowerUrl.contains(".ts") || lowerUrl.contains("segment") -> MediaType.PROGRESSIVE
        
        // CDN path patterns that typically serve HTTP-progressive video
        lowerUrl.contains("/video/") || lowerUrl.contains("videoplayback") ||
        lowerUrl.contains("/get_video") || lowerUrl.contains("/dl/") ||
        lowerUrl.contains("/media/") || lowerUrl.contains("/cdn-cgi/") ||
        lowerUrl.contains("/asset/") || lowerUrl.contains("/file/") ||
        lowerUrl.contains("/stream-") || lowerUrl.contains("/vod/") ||
        lowerUrl.contains("/play") || lowerUrl.contains("/content/") -> MediaType.PROGRESSIVE
        
        // Known streaming service CDNs (usually progressive)
        lowerUrl.contains("googlevideo") || lowerUrl.contains("dailymotion") ||
        lowerUrl.contains("vimeo") || lowerUrl.contains("cloudfront") ||
        lowerUrl.contains("akamai") || lowerUrl.contains("fastly") -> MediaType.PROGRESSIVE
        
        // For anything else, let ExoPlayer auto-detect
        else -> MediaType.UNKNOWN
    }
}

private enum class MediaType {
    HLS, DASH, PROGRESSIVE, UNKNOWN
}

@Composable
fun VideoPlayerDialog(
    videoUrl: String,
    title: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit = {},
    onOpenExternal: () -> Unit = {},
    headers: Map<String, String>? = null,
    onStreamError: ((String, RecoveryStrategy?) -> Unit)? = null
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isBuffering by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }
    var retryCount by remember { mutableStateOf(0) }
    var bufferingStartTime by remember { mutableStateOf(System.currentTimeMillis()) }
    val BUFFERING_TIMEOUT_MS = 15_000L  // Timeout after 15 seconds of buffering
    var currentQuality by remember { mutableStateOf("Auto") }
    var showProxyBadge by remember { mutableStateOf(false) }
    
    // Track which media types have been tried (for auto-format switching)
    var triedFormats by remember { mutableStateOf(setOf<MediaType>()) }
    var currentFormatOverride by remember { mutableStateOf<MediaType?>(null) }
    
    // Check if URL is likely a valid video URL
    val detectedMediaType = remember(videoUrl) { detectMediaType(videoUrl) }
    val activeMediaType = currentFormatOverride ?: detectedMediaType
    val isLikelyValid = remember(videoUrl) { isLikelyVideoUrl(videoUrl) }
    
    // For UNKNOWN types, always try Progressive first — ExoPlayer will determine the format.
    // Do NOT show an early error; many valid CDN URLs have no extension at all.
    
    // Create HTTP data source with optimized settings for faster loading
    val httpDataSourceFactory = remember(videoUrl, headers) {
        val ua = headers?.get("User-Agent")
            ?: EngineUtils.DEFAULT_USER_AGENT
        DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15000)   // Increased to 15s (was 10s) - give servers time to respond
            .setReadTimeoutMs(25000)      // Increased to 25s (was 20s) - some CDNs are slow to start streaming
            .setAllowCrossProtocolRedirects(true)
            .apply {
                headers?.let { hdrs ->
                    setDefaultRequestProperties(hdrs)
                }
            }
    }
    
    // Optimized load control for faster playback start (2026 network speeds)
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                600,    // Min buffer before playback starts (600ms — start ASAP)
                45000,  // Max buffer size (45s ahead)
                200,    // Buffer for resuming from stall (very responsive)
                600     // Buffer for rebuffering (back to 600ms quickly)
            )
            .setPrioritizeTimeOverSizeThresholds(true)  // Always prioritize faster start
            .setTargetBufferBytes(2 * 1024 * 1024)      // 2 MB target buffer (reduced for faster loading)
            .build()
    }
    
    // Create appropriate media source based on URL — always attempt playback
    val exoPlayer = remember(videoUrl, retryCount, activeMediaType, headers, hasError) {
        if (hasError) null
        else ExoPlayer.Builder(context)
            .setLoadControl(loadControl)  // Use optimized load control
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                prepare()
                playWhenReady = true
            }
    }
    
    // Player listener with enhanced error handling
    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            // Reset timeout when buffering starts
                            bufferingStartTime = System.currentTimeMillis()
                            isBuffering = true
                        }
                        Player.STATE_READY -> {
                            duration = exoPlayer.duration
                            isBuffering = false
                            hasError = false // Clear error on successful playback
                        }
                        Player.STATE_ENDED -> {
                            isBuffering = false
                            isPlaying = false
                        }
                        Player.STATE_IDLE -> {
                            // Player is idle - might indicate a problem
                            isBuffering = false
                        }
                    }
                }
                
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                
                override fun onPlayerError(error: PlaybackException) {
                    val errorCode = error.errorCode
                    val errorMsg = when (errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> 
                            "Network connection failed - Check your connection"
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> 
                            "Connection timeout - Server may be slow"
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> 
                            "Source unavailable (${error.message}) - Trying alternate source..."
                        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> 
                            "Invalid content type - Trying different format..."
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> 
                            "Stream manifest error - Trying direct playback..."
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> 
                            "Unsupported stream format - Trying alternate format..."
                        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> 
                            "Behind live window - Restarting stream..."
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                            "Decoder error - This content format is not supported"
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                            "Invalid video format - Trying alternate format..."
                        else -> error.message ?: "Playback error (code: $errorCode)"
                    }
                    
                    // Determine recovery strategy
                    val recovery = when (errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> RecoveryStrategy.USE_NETHERLANDS_PROXY
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> RecoveryStrategy.TRY_PROXY
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> RecoveryStrategy.TRY_ALTERNATE_SOURCE
                        else -> RecoveryStrategy.TRY_ALL_METHODS
                    }
                    
                    // Notify parent for stream recovery
                    onStreamError?.invoke(errorMsg, recovery)
                    
                    // AUTO FORMAT SWITCHING: Try different media types before showing error
                    // Track this format as tried
                    triedFormats = triedFormats + activeMediaType
                    
                    // Determine which format to try next
                    val formatOrder = listOf(MediaType.PROGRESSIVE, MediaType.HLS, MediaType.DASH)
                    val nextFormat = formatOrder.firstOrNull { it !in triedFormats }
                    
                    if (nextFormat != null) {
                        // Auto-switch to next format
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(500)
                            currentFormatOverride = nextFormat
                            hasError = false
                            retryCount++ // Trigger recomposition
                        }
                    } else if (retryCount < 3 && errorCode in listOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                    )) {
                        // All formats tried, but network errors can be retried
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(2000)
                            // Reset format attempts for network retries
                            triedFormats = emptySet()
                            currentFormatOverride = null
                            retryCount++
                        }
                    } else {
                        // All formats tried and all retries exhausted
                        hasError = true
                        errorMessage = "Unable to play this video stream. The source may be geo-restricted, expired, or incompatible."
                    }
                }
            }
            
            exoPlayer.addListener(listener)
            
            onDispose {
                exoPlayer.removeListener(listener)
                exoPlayer.release()
            }
        }
    }
    
    // Update position
    LaunchedEffect(isPlaying, exoPlayer) {
        while (isPlaying && exoPlayer != null) {
            currentPosition = exoPlayer.currentPosition
            delay(500)
        }
    }
    
    // Monitor buffering timeout - crash out if stuck for too long
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            bufferingStartTime = System.currentTimeMillis()
            delay(BUFFERING_TIMEOUT_MS)
            // If still buffering after timeout, show error
            if (isBuffering && !hasError) {
                hasError = true
                errorMessage = "Buffering took too long - Stream may be unavailable or very slow. Check your connection and try again."
                triedFormats = emptySet()
                currentFormatOverride = null
            }
        }
    }
    
    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                }
        ) {
            if (hasError) {
                // Enhanced Error state with recovery options
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    DarkBackground,
                                    DarkSurface,
                                    DarkBackground
                                )
                            )
                        )
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Error icon with glow effect
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                AccentRed.copy(alpha = 0.15f),
                                CircleShape
                            )
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = AccentRed,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "Playback Error",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    if (retryCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Retry attempt: $retryCount/3",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Recovery action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Retry button
                        Button(
                            onClick = { 
                                // Reset format tracking on manual retry
                                triedFormats = emptySet()
                                currentFormatOverride = null
                                hasError = false
                                retryCount++ 
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan,
                                contentColor = DarkBackground
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Retry",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        
                        // Open in browser button — always available as escape hatch
                        Button(
                            onClick = onOpenExternal,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AIAccent,
                                contentColor = DarkBackground
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Browser",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        
                        // Close button
                        OutlinedButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Close",
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Hint text
                    Text(
                        text = "💡 Tip: Try enabling Netherlands proxy in Settings for geo-restricted content",
                        style = MaterialTheme.typography.bodySmall,
                        color = AIAccent,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else if (exoPlayer != null) {
                // Video player - only show if we have a valid player
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Buffering indicator
                if (isBuffering) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = CyberCyan,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                
                // Controls overlay
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.7f),
                                        Color.Transparent,
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.7f)
                                    )
                                )
                            )
                    ) {
                        // Top bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            
                            IconButton(onClick = onDownload) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        
                        // Center controls: Skip Back | Play/Pause | Skip Forward
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Skip back 10s
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable {
                                        exoPlayer?.let { player ->
                                            player.seekTo(maxOf(0, player.currentPosition - 10000))
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = "Skip back 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            
                            // Play/Pause
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable {
                                        exoPlayer?.let { player ->
                                            if (isPlaying) {
                                                player.pause()
                                            } else {
                                                player.play()
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            
                            // Skip forward 10s
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable {
                                        exoPlayer?.let { player ->
                                            player.seekTo(minOf(player.duration, player.currentPosition + 10000))
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = "Skip forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        
                        // Bottom controls
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            // Progress bar
                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                                onValueChange = { value ->
                                    exoPlayer?.seekTo((value * duration).toLong())
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = CyberCyan,
                                    activeTrackColor = CyberCyan,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )
                            
                            // Time display
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatDuration(currentPosition),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                                Text(
                                    text = formatDuration(duration),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Format duration to mm:ss or hh:mm:ss
 */
private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
