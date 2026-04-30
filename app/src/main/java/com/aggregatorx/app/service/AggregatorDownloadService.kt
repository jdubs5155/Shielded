package com.aggregatorx.app.service

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.aggregatorx.app.R
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
class AggregatorDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0
) {

    override fun getDownloadManager(): DownloadManager {
        // This is a simplified singleton access; in full implementation, 
        // this is provided via Hilt from a MediaModule.
        return DownloadHelper.getDownloadManager(this)
    }

    override fun getScheduler(): Scheduler? {
        return PlatformScheduler(this, JOB_ID)
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val notificationHelper = DownloadNotificationHelper(this, CHANNEL_ID)
        return notificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_download,
            null,
            null,
            downloads,
            notMetRequirements
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"
        private const val JOB_ID = 1
    }
}
