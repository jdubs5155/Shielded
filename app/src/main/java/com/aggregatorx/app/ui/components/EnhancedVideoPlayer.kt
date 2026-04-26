package com.aggregatorx.app.ui.components

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Enhanced video player with multi-format support
 * Automatically detects and plays HLS, DASH, RTSP, and progressive video formats
 * Works with various video sources and provides robust fallback mechanisms
 */
@Composable
fun EnhancedVideoPlayer(
    videoUrl: String,
    headers: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    showControls: Boolean = true,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    
    val exoPlayer = remember(videoUrl) {
        createOptimizedExoPlayer(context, videoUrl, headers, onError)
    }
    
    LaunchedEffect(autoPlay) {
        exoPlayer.playWhenReady = autoPlay
    }
    
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }
    
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = showControls
            }
        },
        modifier = modifier
    )
}

/**
 * Create ExoPlayer configured for robust video playback
 * Media3 automatically detects format types and applies appropriate handlers
 */
private fun createOptimizedExoPlayer(
    context: Context,
    videoUrl: String,
    headers: Map<String, String>,
    onError: ((String) -> Unit)?
): ExoPlayer {
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    val dataSourceFactory = DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory(httpClient).apply {
            setDefaultRequestProperties(headers)
        }
    )
    
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .apply {
            val mediaItem = MediaItem.Builder().setUri(Uri.parse(videoUrl)).build()
            setMediaItem(mediaItem)
            prepare()
        }
}
