package com.aggregatorx.app.engine.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight wrapper around [androidx.media3.exoplayer.offline.DownloadManager]
 * that lets the rest of the app enqueue a download with a single call.
 *
 * Notes:
 *  - Storage lives at `<app cacheDir>/media-downloads`. Anything we write
 *    here is regular ExoPlayer cache, so the player can resume offline
 *    without a re-download.
 *  - We expose a snapshot of progress state via [downloads] so the UI can
 *    observe pending / finished downloads without coupling to Media3 types.
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val downloadDir: File = File(context.cacheDir, DOWNLOAD_SUBDIR).apply { mkdirs() }
    private val databaseProvider = StandaloneDatabaseProvider(context)

    private val cache = SimpleCache(downloadDir, NoOpCacheEvictor(), databaseProvider)

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(EngineUtils.getRandomUserAgent())
        .setAllowCrossProtocolRedirects(true)

    private val downloadExecutor = Executors.newFixedThreadPool(2)

    private val downloadManager: DownloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(databaseProvider),
        DefaultDownloaderFactory(
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory),
            downloadExecutor
        )
    ).apply {
        maxParallelDownloads = 2
        requirements = Requirements(Requirements.NETWORK)
    }

    private val _downloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    val downloads = _downloads.asStateFlow()

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                publishSnapshot()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                publishSnapshot()
            }
        })
        publishSnapshot()
    }

    /**
     * Enqueue a download. Returns the [DownloadRequest.id] which can be
     * used later to cancel/remove the download.
     */
    suspend fun enqueue(videoUrl: String, mimeHint: String? = null): String = withContext(Dispatchers.IO) {
        val type = MediaTypeDetector.detect(videoUrl, mimeHint)
        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .apply {
                when (type) {
                    MediaTypeDetector.Type.HLS  -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    MediaTypeDetector.Type.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    MediaTypeDetector.Type.PROGRESSIVE -> { /* let ExoPlayer infer */ }
                }
            }
            .build()

        val request = DownloadRequest.Builder(videoUrl, mediaItem.localConfiguration!!.uri)
            .setMimeType(
                when (type) {
                    MediaTypeDetector.Type.HLS  -> MimeTypes.APPLICATION_M3U8
                    MediaTypeDetector.Type.DASH -> MimeTypes.APPLICATION_MPD
                    MediaTypeDetector.Type.PROGRESSIVE -> MimeTypes.VIDEO_MP4
                }
            )
            .build()

        downloadManager.addDownload(request)
        request.id
    }

    fun pause(downloadId: String) {
        downloadManager.setStopReason(downloadId, STOP_REASON_USER_PAUSED)
    }

    fun resume(downloadId: String) {
        downloadManager.setStopReason(downloadId, Download.STOP_REASON_NONE)
    }

    fun remove(downloadId: String) {
        downloadManager.removeDownload(downloadId)
    }

    /**
     * Render a fresh snapshot of every download we currently know about.
     */
    private fun publishSnapshot() {
        val snapshots = downloadManager.currentDownloads.map { d ->
            DownloadSnapshot(
                id = d.request.id,
                url = d.request.uri.toString(),
                state = d.state,
                bytesDownloaded = d.bytesDownloaded,
                percentDownloaded = d.percentDownloaded
            )
        }
        _downloads.value = snapshots
    }

    /**
     * Releases the underlying [DownloadManager] resources.
     * Call from `Application.onTerminate` or from a Hilt-aware singleton
     * teardown if you decide to expose lifecycle hooks.
     */
    fun release() {
        downloadManager.release()
        cache.release()
        databaseProvider.close()
        downloadExecutor.shutdown()
    }

    /** Plain-data snapshot the UI can observe without depending on Media3. */
    data class DownloadSnapshot(
        val id: String,
        val url: String,
        val state: Int,
        val bytesDownloaded: Long,
        val percentDownloaded: Float
    )

    companion object {
        private const val DOWNLOAD_SUBDIR = "media-downloads"
        private const val STOP_REASON_USER_PAUSED = 1
    }
}
