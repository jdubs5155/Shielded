package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.engine.media.MediaDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: MediaDownloadManager
) : ViewModel() {

    /** Live snapshot of every download we currently know about. */
    val downloads = downloadManager.downloads

    /** Manually enqueue a download — used by the "+ enqueue test" affordance. */
    fun enqueue(url: String, mimeHint: String? = null) {
        viewModelScope.launch { downloadManager.enqueue(url, mimeHint) }
    }

    fun pause(id: String)  = downloadManager.pause(id)
    fun resume(id: String) = downloadManager.resume(id)
    fun remove(id: String) = downloadManager.remove(id)
}
