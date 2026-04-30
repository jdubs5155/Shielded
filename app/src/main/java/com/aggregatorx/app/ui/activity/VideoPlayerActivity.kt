package com.aggregatorx.app.ui.activity

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.aggregatorx.app.databinding.ActivityVideoPlayerBinding

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUrl = intent.getStringExtra("VIDEO_URL") ?: return
        initializePlayer(videoUrl)
    }

    private fun initializePlayer(videoUrl: String) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AggregatorX/1.0 (Linux; Android 13; SM-A326U)")

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                
                val uri = Uri.parse(videoUrl)
                val mediaItem = MediaItem.fromUri(uri)
                
                // Determine source type (HLS, DASH, or Progressive)
                val mediaSource = when {
                    videoUrl.contains(".m3u8") -> {
                        HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    }
                    videoUrl.contains(".mpd") -> {
                        DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    }
                    else -> mediaSourceFactory.createMediaSource(mediaItem)
                }

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Handle black screen/error by attempting a retry or logging
                        exoPlayer.prepare() 
                    }
                })
            }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
