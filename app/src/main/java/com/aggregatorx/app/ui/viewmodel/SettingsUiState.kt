package com.aggregatorx.app.ui.viewmodel

import com.aggregatorx.app.data.model.DownloadSettings
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.SiteAnalysis

data class SettingsUiState(
    val customUrl: String = "",
    val isAnalyzing: Boolean = false,
    val lastAnalysis: SiteAnalysis? = null,
    val lastProvider: Provider? = null,
    val isRefreshingAll: Boolean = false,
    val refreshProgress: Float = 0f,
    val message: String? = null,
    val error: String? = null,
    val showAnalysisDetails: Boolean = false,
    val downloadSettings: DownloadSettings = DownloadSettings()
)
