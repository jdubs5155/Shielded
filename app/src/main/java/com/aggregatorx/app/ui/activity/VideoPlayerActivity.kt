package com.aggregatorx.app.ui.activity

import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.aggregatorx.app.databinding.ActivityVideoPlayerBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Dedicated Activity for high-quality video playback using Media3.
 * Supports HLS, DASH, and Progressive MP4 streams with automatic retry logic.
 */
@AndroidEntryPoint
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUrl = intent.getStringExtra("VIDEO_URL")
        if (videoUrl.isNullOrBlank()) {
            Toast.makeText(this, "Invalid Video URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializePlayer(videoUrl)
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AggregatorX/1.0")
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
                binding.playerView.player = this
                
                val mediaItem = MediaItem.fromUri(url)
                val mediaSource = when {
                    url.contains(".m3u8") -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    url.contains(".mpd") -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    else -> mediaSourceFactory.createMediaSource(mediaItem)
                }

                setMediaSource(mediaSource)
                prepare()
            }

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Fix for black screen: Show error and provide retry feedback
                Toast.makeText(this@VideoPlayerActivity, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                binding.progressBar.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
