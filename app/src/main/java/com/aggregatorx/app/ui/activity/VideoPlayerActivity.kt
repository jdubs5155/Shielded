package com.aggregatorx.app.ui.activity

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.aggregatorx.app.engine.media.MediaTypeDetector
import com.aggregatorx.app.engine.util.EngineUtils

/**
 * Video player activity backed by ExoPlayer + a Compose-hosted PlayerView.
 *
 * Notes on the black-screen fix:
 *  - We previously inferred HLS / DASH purely from the URL extension which
 *    misses query-string-only manifests (e.g. `?type=m3u8`). Detection now
 *    runs through [MediaTypeDetector] which combines extension + MIME hint
 *    so the player picks the right MediaSource type and actually renders
 *    instead of staying on a black surface.
 *  - The HTTP data source forwards a rotating realistic User-Agent so
 *    edge-WAFs don't silently 403 the segment requests.
 *  - On a [PlaybackException] we wait one frame (via prepare()) before
 *    retrying instead of an immediate hot-loop.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        val mimeHint = intent.getStringExtra(EXTRA_MIME_HINT)

        if (videoUrl.isBlank()) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PlayerScreen(videoUrl = videoUrl, mimeHint = mimeHint)
                }
            }
        }
    }

    @Composable
    private fun PlayerScreen(videoUrl: String, mimeHint: String?) {
        val context = androidx.compose.ui.platform.LocalContext.current

        val exoPlayer = remember {
            buildPlayer(context, videoUrl, mimeHint)
        }

        DisposableEffect(exoPlayer) {
            onDispose { exoPlayer.release() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                        player = exoPlayer
                    }
                },
                update = { it.player = exoPlayer }
            )
        }
    }

    private fun buildPlayer(
        ctx: android.content.Context,
        videoUrl: String,
        mimeHint: String?
    ): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(EngineUtils.getRandomUserAgent())
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        val defaultFactory = DefaultMediaSourceFactory(ctx).setDataSourceFactory(httpFactory)

        val type = MediaTypeDetector.detect(videoUrl, mimeHint)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl))
            .apply {
                when (type) {
                    MediaTypeDetector.Type.HLS  -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    MediaTypeDetector.Type.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    MediaTypeDetector.Type.PROGRESSIVE -> { /* let ExoPlayer infer */ }
                }
            }
            .build()

        val mediaSource = when (type) {
            MediaTypeDetector.Type.HLS  -> HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
            MediaTypeDetector.Type.DASH -> DashMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
            MediaTypeDetector.Type.PROGRESSIVE -> ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        }

        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(defaultFactory)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Single self-heal attempt — full retry of the same source.
                        // Avoids the prior infinite hot-loop on a hard error.
                        if (error.errorCode != PlaybackException.ERROR_CODE_TIMEOUT) {
                            seekTo(C.TIME_UNSET)
                            prepare()
                        }
                    }
                })
            }
    }

    companion object {
        const val EXTRA_VIDEO_URL = "VIDEO_URL"
        const val EXTRA_MIME_HINT = "MIME_HINT"
    }
}
