package com.aggregatorx.app.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
object DownloadHelper {
    private var downloadManager: DownloadManager? = null
    private var cache: SimpleCache? = null

    fun getDownloadManager(context: Context): DownloadManager {
        if (downloadManager == null) {
            val databaseProvider = StandaloneDatabaseProvider(context)
            val downloadDir = File(context.getExternalFilesDir(null), "downloads")
            
            cache = SimpleCache(downloadDir, NoOpCacheEvictor(), databaseProvider)
            
            downloadManager = DownloadManager(
                context,
                databaseProvider,
                cache!!,
                DefaultHttpDataSource.Factory().setUserAgent("AggregatorX/1.0"),
                Executors.newFixedThreadPool(3)
            ).apply {
                maxParallelDownloads = 2
            }
        }
        return downloadManager!!
    }
}
