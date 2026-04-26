package com.aggregatorx.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.data.model.ProviderSearchResults
import com.aggregatorx.app.ui.viewmodel.SearchViewModel

/**
 * Composable function that displays a scrolling video feed with pagination support.
 * Supports full-screen video playback with Media3 ExoPlayer.
 */
@Composable
fun ResultsScreen(
    viewModel: SearchViewModel = hiltViewModel()
) {
    var currentPlayingUrl by remember { mutableStateOf<String?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    val context = LocalContext.current

    // Handle play full-screen action
    val playFullScreen: (String) -> Unit = { url ->
        playFullScreen(url, context) { newPlayer ->
            exoPlayer = newPlayer
            currentPlayingUrl = url
        }
    }

    // Back handler for full-screen mode
    BackHandler(enabled = currentPlayingUrl != null) {
        exoPlayer?.release()
        exoPlayer = null
        currentPlayingUrl = null
    }

    // If currently playing, show full-screen player
    if (currentPlayingUrl != null && exoPlayer != null) {
        FullScreenVideoPlayer(
            exoPlayer = exoPlayer!!,
            onClose = {
                exoPlayer?.release()
                exoPlayer = null
                currentPlayingUrl = null
            }
        )
    } else {
        // Show video feed
        VideoFeed(
            onPlayClick = { videoUrl ->
                playFullScreen(videoUrl)
            }
        )
    }
}

/**
 * Displays a scrolling list of videos with thumbnails, titles, and play buttons.
 */
@Composable
fun VideoFeed(
    onPlayClick: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // Get provider results from ViewModel
    val providerResults by viewModel.providerResults.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(providerResults, key = { "${it.provider.id}_provider" }) { result ->
            // Display provider name header
            Text(
                text = result.provider.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        items(providerResults.flatMapIndexed { _, result ->
            result.results.map { it to result.provider.name }
        }, key = { "${it.first.url}_result" }) { (searchResult, _) ->
            VideoThumbnailItem(
                searchResult = searchResult,
                onPlayClick = {
                    onPlayClick(searchResult.url)
                }
            )
        }
    }
}

/**
 * Individual video item with thumbnail, title, and play button.
 */
@Composable
fun VideoThumbnailItem(
    searchResult: SearchResult,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
        ) {
            // Overlay with title and play button
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Title at top
                Text(
                    text = searchResult.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 3,
                    modifier = Modifier.weight(1f)
                )

                // Play button at bottom-right
                Box(
                    modifier = Modifier.align(Alignment.End)
                ) {
                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.8f), shape = RoundedCornerShape(24.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play video",
                            tint = Color.Black
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen video player using ExoPlayer.
 */
@Composable
fun FullScreenVideoPlayer(
    exoPlayer: ExoPlayer,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ExoPlayer UI - using AndroidView
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Close button overlay
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(24.dp))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close player",
                tint = Color.White
            )
        }
    }
}

/**
 * Handles creating and configuring an ExoPlayer instance with the given video URL.
 * Creates a new player, sets media item, prepares, and plays.
 * Includes error handling with user feedback.
 */
fun playFullScreen(
    url: String,
    context: Context,
    onPlayerCreated: (ExoPlayer) -> Unit
) {
    try {
        val player = ExoPlayer.Builder(context).build()
        
        // Set media item and prepare
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        
        onPlayerCreated(player)
    } catch (e: Exception) {
        // Handle errors gracefully
        Toast.makeText(
            context,
            "Play failed: ${e.message ?: "Unknown error"}",
            Toast.LENGTH_SHORT
        ).show()
    }
}
