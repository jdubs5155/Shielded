package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.engine.media.DownloadManager
import com.aggregatorx.app.engine.media.DownloadState
import com.aggregatorx.app.engine.media.RecoveryStrategy
import com.aggregatorx.app.engine.media.VideoExtractorEngine
import com.aggregatorx.app.engine.media.VideoExtractionResult
import com.aggregatorx.app.engine.media.VideoStreamResolver
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight result returned by video extraction so the UI can forward
 * both the playable URL **and** the HTTP headers that the CDN expects.
 */
data class VideoPreviewResult(
    val videoUrl: String,
    val headers: Map<String, String> = emptyMap()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AggregatorRepository,
    private val videoExtractor: VideoExtractorEngine,
    private val videoStreamResolver: VideoStreamResolver,
    private val downloadManager: DownloadManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    
    private val _providerResults = MutableStateFlow<List<ProviderSearchResults>>(emptyList())
    val providerResults: StateFlow<List<ProviderSearchResults>> = _providerResults.asStateFlow()

    private val _providerPageIndex = MutableStateFlow<Map<String, Int>>(emptyMap())
    val providerPageIndex: StateFlow<Map<String, Int>> = _providerPageIndex.asStateFlow()

    private val _providerActionLoading = MutableStateFlow<Set<String>>(emptySet())
    val providerActionLoading: StateFlow<Set<String>> = _providerActionLoading.asStateFlow()

    private val _videoExtractionState = MutableStateFlow<VideoExtractionState>(VideoExtractionState.Idle)
    val videoExtractionState: StateFlow<VideoExtractionState> = _videoExtractionState.asStateFlow()
    
    val downloads: StateFlow<Map<String, DownloadState>> = downloadManager.downloads

    // ── Liked-result state ──────────────────────────────────────────────
    private val _likedUrls = MutableStateFlow<Set<String>>(emptySet())
    val likedUrls: StateFlow<Set<String>> = _likedUrls.asStateFlow()
    
    // Video URL cache for faster repeated preview loads (caches full result with headers)
    private val videoPreviewCache = java.util.concurrent.ConcurrentHashMap<String, VideoPreviewResult>()
    
    // Current search job - cancel when new search starts
    private var currentSearchJob: kotlinx.coroutines.Job? = null
    
    init {
        // Load recent searches
        viewModelScope.launch {
            repository.getRecentSearches().collect { searches ->
                _uiState.update { it.copy(recentSearches = searches) }
            }
        }
        // Pre-load liked URLs so the UI can render heart icons immediately
        viewModelScope.launch {
            _likedUrls.value = repository.getAllLikedUrls()
        }
        // Load available providers
        viewModelScope.launch {
            repository.getEnabledProviders().collect { providers ->
                _uiState.update { it.copy(availableProviders = providers) }
            }
        }
    }

    /** Toggle like on a search result. Updates local set immediately for snappy UI. */
    fun toggleLike(result: SearchResult) {
        viewModelScope.launch {
            val nowLiked = repository.toggleLike(result)
            _likedUrls.update { urls ->
                if (nowLiked) urls + result.url else urls - result.url
            }
        }
    }
    
    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }
    
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        
        // Cancel any previous search to prevent stale results
        currentSearchJob?.cancel()
        
        currentSearchJob = viewModelScope.launch {
            // ALWAYS clear cache for each search to ensure fresh results
            // This prevents stale results from being shown for different queries
            repository.clearSearchCache()
            videoPreviewCache.clear()
            
            // Reset UI state immediately for responsive feedback
            _uiState.update { 
                it.copy(
                    isSearching = true, 
                    searchCompleted = false,
                    currentSearchQuery = query,
                    // Reset totals and aggregated results for new search
                    totalResults = 0,
                    successfulProviders = 0,
                    failedProviders = 0,
                    aggregatedResults = null,
                    error = null
                ) 
            }
            // Clear provider results and reset page indices for new search
            _providerResults.value = emptyList()
            _providerPageIndex.value = emptyMap()
            
            val results = mutableListOf<ProviderSearchResults>()
            
            repository.searchAllProviders(query, uiState.value.selectedProviders)
                .catch { e ->
                    // Only set error if we have NO results at all — otherwise partial results are fine
                    if (results.isEmpty()) {
                        _uiState.update { 
                            it.copy(error = e.message ?: "Search failed") 
                        }
                    }
                }
                .collect { providerResult ->
                    results.add(providerResult)
                _providerPageIndex.update { currentMap ->
                    if (currentMap.containsKey(providerResult.provider.id)) currentMap
                    else currentMap + (providerResult.provider.id to 1)
                }
                _providerResults.value = results.toList()
                
                // Update aggregated results — wrap in try-catch so aggregation
                // failure never kills the collection loop
                    try {
                        val aggregated = repository.aggregateSearchResults(query, results)
                        _uiState.update { 
                            it.copy(
                                aggregatedResults = aggregated,
                                totalResults = aggregated.totalResults,
                                successfulProviders = aggregated.successfulProviders,
                                failedProviders = aggregated.failedProviders,
                                error = null // clear any previous error since we got results
                            ) 
                        }
                    } catch (e: Exception) {
                        // Aggregation failed — still show raw provider results
                        _uiState.update {
                            it.copy(
                                totalResults = results.sumOf { r -> r.results.size },
                                successfulProviders = results.count { r -> r.success },
                                failedProviders = results.count { r -> !r.success }
                            )
                        }
                    }
                }
            
            _uiState.update { it.copy(isSearching = false, searchCompleted = true) }
        }
    }
    
    fun clearSearch() {
        _uiState.update { 
            it.copy(
                query = "",
                aggregatedResults = null,
                searchCompleted = false,
                error = null
            ) 
        }
        _providerResults.value = emptyList()
        // Clear video URL cache when search is cleared
        videoPreviewCache.clear()
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }
    
    fun toggleProviderSelection(providerId: String) {
        _uiState.update { state ->
            val newSelected = if (state.selectedProviders.contains(providerId)) {
                state.selectedProviders - providerId
            } else {
                state.selectedProviders + providerId
            }
            state.copy(selectedProviders = newSelected)
        }
    }
    
    fun toggleAdvancedOptions() {
        _uiState.update { it.copy(showAdvancedOptions = !it.showAdvancedOptions) }
    }
    
    fun searchFromHistory(query: String) {
        _uiState.update { it.copy(query = query) }
        search()
    }
    
    /**
     * Extract video URL from a page URL using the FULL resolution chain:
     *   1) Cache hit
     *   2) VideoExtractorEngine fast (JSoup, no browser)
     *   3) VideoExtractorEngine full (known host extractors + iframe + headless)
     *   4) VideoStreamResolver (direct → proxy → headless → site-specific extractors)
     *   5) Direct URL probe: if pageUrl itself looks playable, try it directly
     *   6) Fallback: return the page URL itself for embeddable sites
     *   7) Last resort: pass the raw URL to the player (never returns null)
     *
     * Returns [VideoPreviewResult] with a playable URL and HTTP headers,
     * or **null** if no playable stream could be found.  Null is explicitly
     * preferable to returning an HTML page URL — the caller should show
     * an error dialog instead of feeding HTML to ExoPlayer.
     */
    suspend fun extractVideoForPreview(pageUrl: String): VideoPreviewResult? {
        // 1. Cache hit — only return if the cached URL still looks valid
        videoPreviewCache[pageUrl]?.let { cached ->
            if (isLikelyMediaUrl(cached.videoUrl)) return cached
            else videoPreviewCache.remove(pageUrl)  // purge stale/bad cache entries
        }

        return try {
            // 2. Fast extraction (JSoup only – no headless browser)
            val fastUrl = videoExtractor.extractVideoUrlForPreview(pageUrl)
            if (!fastUrl.isNullOrEmpty() && isLikelyMediaUrl(fastUrl)) {
                val result = VideoPreviewResult(
                    videoUrl = fastUrl,
                    headers = buildPlaybackHeaders(pageUrl)
                )
                videoPreviewCache[pageUrl] = result
                return result
            }

            // 3. Full extraction (known host extractors + standard HTML + headless)
            val fullExtraction = videoExtractor.extractVideoUrl(pageUrl)
            if (fullExtraction.success && !fullExtraction.videoUrl.isNullOrEmpty()
                && isLikelyMediaUrl(fullExtraction.videoUrl)
            ) {
                val result = VideoPreviewResult(
                    videoUrl = fullExtraction.videoUrl,
                    headers = buildPlaybackHeaders(pageUrl)
                )
                videoPreviewCache[pageUrl] = result
                return result
            }

            // 4. Full resolution chain (proxy → headless → site-specific)
            val resolved = videoStreamResolver.resolveVideoStream(pageUrl)
            if (resolved.success && !resolved.streamUrl.isNullOrEmpty()
                && isLikelyMediaUrl(resolved.streamUrl)
            ) {
                val result = VideoPreviewResult(
                    videoUrl = resolved.streamUrl,
                    headers = resolved.headers ?: buildPlaybackHeaders(pageUrl)
                )
                videoPreviewCache[pageUrl] = result
                return result
            }

            // 5. Direct URL probe – if the page URL itself is a direct media link
            if (isLikelyMediaUrl(pageUrl)) {
                val result = VideoPreviewResult(
                    videoUrl = pageUrl,
                    headers = buildPlaybackHeaders(pageUrl)
                )
                videoPreviewCache[pageUrl] = result
                return result
            }

            // 6. (removed) — Embeddable sites (YouTube, Vimeo, etc.) cannot be
            //    played directly in ExoPlayer.  They would need yt-dlp or similar
            //    which we don't bundle.  Don't pretend these are playable.

            // 7. Last resort — only return the raw URL if it actually looks like
            //    a media stream (e.g. direct .mp4 link from a CDN).
            //    If the URL is just an HTML page, return null so the caller
            //    can show a proper error instead of feeding HTML to ExoPlayer.
            if (isLikelyMediaUrl(pageUrl)) {
                val fallback = VideoPreviewResult(
                    videoUrl = pageUrl,
                    headers = buildPlaybackHeaders(pageUrl)
                )
                videoPreviewCache[pageUrl] = fallback
                return fallback
            }

            // Extraction failed — no playable stream found
            return null
        } catch (e: Exception) {
            // On exception, only return raw URL if it looks like actual media
            return if (isLikelyMediaUrl(pageUrl)) {
                VideoPreviewResult(
                    videoUrl = pageUrl,
                    headers = buildPlaybackHeaders(pageUrl)
                )
            } else {
                null
            }
        }
    }

    /**
     * Checks whether a URL plausibly points to a media stream rather than an HTML page.
     * Used as a safety gate to avoid handing HTML pages to ExoPlayer.
     */
    private fun isLikelyMediaUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val positiveIndicators = listOf(
            ".mp4", ".m3u8", ".mpd", ".webm", ".mkv", ".avi", ".mov",
            ".flv", ".wmv", ".ts", ".m4v", ".3gp", ".f4v", ".ogv",
            "/hls/", "/dash/", "/video/", "/stream/", "videoplayback",
            "/get_video", "/dl/", "/media/", "/embed/",
            "googlevideo.com", "akamaized.net", "cdn.streamtape",
            "dood.", "filemoon.", "streamwish.", "mixdrop.", "voe.sx",
            "blob:", "data:video", "data:audio",
            "token=", "sig=", "signature=", "expires=", "sessionid=", "mt="
        )
        val negativeIndicators = listOf(
            ".html", ".htm", "text/html", "/search?", "/category/",
            "/login", "/register", "?page=", "?sort="
        )
        if (negativeIndicators.any { lowerUrl.contains(it) }) return false
        if (positiveIndicators.any { lowerUrl.contains(it) }) return true

        val pathPart = lowerUrl.split("?")[0].split("/").lastOrNull() ?: ""
        if (pathPart.length > 20 && !lowerUrl.contains(".html") && !lowerUrl.contains("text/")) {
            return true
        }

        return lowerUrl.contains("?") && !negativeIndicators.any { lowerUrl.contains(it) }
    }

    suspend fun resolveVideoForPlayback(pageUrl: String, recoveryStrategy: RecoveryStrategy? = null): VideoPreviewResult? {
        videoPreviewCache[pageUrl]?.let { cached ->
            if (isLikelyMediaUrl(cached.videoUrl)) return cached
            else videoPreviewCache.remove(pageUrl)
        }

        return try {
            val resolved = videoStreamResolver.resolveVideoStream(pageUrl, recoveryStrategy = recoveryStrategy)
            if (resolved.success && !resolved.streamUrl.isNullOrBlank() && isLikelyMediaUrl(resolved.streamUrl)) {
                val result = VideoPreviewResult(
                    videoUrl = resolved.streamUrl,
                    headers = resolved.headers ?: buildPlaybackHeaders(pageUrl)
                )
                videoPreviewCache[pageUrl] = result
                return result
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Convenience wrapper that keeps the old String?-returning signature for callers that don't need headers. */
    suspend fun extractVideoUrlForPreview(pageUrl: String): String? {
        return extractVideoForPreview(pageUrl)?.videoUrl
    }

    /** Build standard playback headers from the originating page URL. */
    private fun buildPlaybackHeaders(pageUrl: String): Map<String, String> {
        val origin = try {
            val uri = android.net.Uri.parse(pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { pageUrl }
        return mapOf(
            "User-Agent" to EngineUtils.DEFAULT_USER_AGENT,
            "Referer" to "$origin/",
            "Origin" to origin,
            "Accept" to "*/*",
            "Accept-Language" to "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive"
        )
    }
    
    /**
     * Extract video URL from a search result page
     */
    fun extractVideoUrl(result: SearchResult) {
        viewModelScope.launch {
            _videoExtractionState.value = VideoExtractionState.Extracting(result.title)
            
            try {
                val extractionResult = videoExtractor.extractVideoUrl(result.url)
                
                if (extractionResult.success && extractionResult.videoUrl != null) {
                    _videoExtractionState.value = VideoExtractionState.Success(
                        videoUrl = extractionResult.videoUrl,
                        title = result.title,
                        quality = extractionResult.quality,
                        isStream = extractionResult.format in listOf("m3u8", "mpd", "hls"),
                        headers = emptyMap()
                    )
                } else {
                    _videoExtractionState.value = VideoExtractionState.Error(
                        extractionResult.error ?: "Could not extract video URL"
                    )
                }
            } catch (e: Exception) {
                _videoExtractionState.value = VideoExtractionState.Error(
                    e.message ?: "Video extraction failed"
                )
            }
        }
    }
    
    /**
     * Reset video extraction state
     */
    fun resetVideoState() {
        _videoExtractionState.value = VideoExtractionState.Idle
    }
    
    /**
     * Start download for a search result
     */
    fun downloadResult(result: SearchResult) {
        viewModelScope.launch {
            try {
                downloadManager.downloadFromPage(result.url, result.title)
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(error = "Download failed: ${e.message}") 
                }
            }
        }
    }
    
    /**
     * Download from extracted video URL directly
     */
    fun downloadVideoUrl(videoUrl: String, title: String) {
        viewModelScope.launch {
            try {
                downloadManager.downloadDirect(videoUrl, title)
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(error = "Download failed: ${e.message}") 
                }
            }
        }
    }
    
    /**
     * Cancel a download
     */
    fun cancelDownload(downloadId: String) {
        downloadManager.cancelDownload(downloadId)
    }

    /**
     * Load next page for a provider
     */
    fun loadProviderNextPage(providerId: String) {
        val currentQuery = _uiState.value.currentSearchQuery
        if (currentQuery.isEmpty()) return

        viewModelScope.launch {
            try {
                _providerActionLoading.update { it + providerId }
                
                val currentPage = _providerPageIndex.value[providerId] ?: 1
                val nextPageNum = currentPage + 1
                
                val nextPageResults = repository.searchProviderPage(providerId, currentQuery, nextPageNum)

                if (nextPageResults.success && nextPageResults.results.isNotEmpty()) {
                    // Update page index FIRST before modifying results
                    _providerPageIndex.update { it + (providerId to nextPageNum) }

                    val updatedResults = _providerResults.value.toMutableList()
                    val providerIndex = updatedResults.indexOfFirst { it.provider.id == providerId }

                    if (providerIndex >= 0) {
                        // Preserve total result count across pages
                        val oldProvider = updatedResults[providerIndex]
                        val updatedProvider = nextPageResults.copy(
                            totalResults = nextPageResults.results.size,
                            hasMore = nextPageResults.hasMore
                        )
                        updatedResults[providerIndex] = updatedProvider
                        _providerResults.value = updatedResults
                    }

                    _uiState.update { state ->
                        state.copy(
                            totalResults = updatedResults.sumOf { it.results.size },
                            error = null
                        )
                    }
                } else {
                    _uiState.update { it.copy(error = "No additional results available on page $nextPageNum") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load page: ${e.message}") }
            } finally {
                _providerActionLoading.update { it - providerId }
            }
        }
    }

    /**
     * Load previous page for a provider
     */
    fun loadProviderPreviousPage(providerId: String) {
        val currentQuery = _uiState.value.currentSearchQuery
        if (currentQuery.isEmpty()) return

        viewModelScope.launch {
            try {
                val currentPage = _providerPageIndex.value[providerId] ?: 1
                if (currentPage <= 1) {
                    _uiState.update { it.copy(error = "Already on first page") }
                    return@launch
                }

                _providerActionLoading.update { it + providerId }
                
                val previousPageNum = currentPage - 1
                val previousPageResults = repository.searchProviderPage(providerId, currentQuery, previousPageNum)

                if (previousPageResults.success && previousPageResults.results.isNotEmpty()) {
                    // Update page index FIRST before modifying results
                    _providerPageIndex.update { it + (providerId to previousPageNum) }

                    val updatedResults = _providerResults.value.toMutableList()
                    val providerIndex = updatedResults.indexOfFirst { it.provider.id == providerId }

                    if (providerIndex >= 0) {
                        // Preserve pagination state
                        val updatedProvider = previousPageResults.copy(
                            totalResults = previousPageResults.results.size,
                            hasMore = previousPageResults.hasMore
                        )
                        updatedResults[providerIndex] = updatedProvider
                        _providerResults.value = updatedResults
                    }

                    _uiState.update { state ->
                        state.copy(
                            totalResults = updatedResults.sumOf { it.results.size },
                            error = null
                        )
                    }
                } else {
                    _uiState.update { it.copy(error = "Failed to load page $previousPageNum") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Previous page error: ${e.message}") }
            } finally {
                _providerActionLoading.update { it - providerId }
            }
        }
    }

    /**
     * Refresh current page for a provider
     */
    fun refreshProviderResults(providerId: String) {
        val currentQuery = _uiState.value.currentSearchQuery
        if (currentQuery.isEmpty()) return

        viewModelScope.launch {
            try {
                _providerActionLoading.update { it + providerId }

                val currentPage = _providerPageIndex.value[providerId] ?: 1
                val refreshedResults = repository.searchProviderPage(providerId, currentQuery, currentPage)

                if (refreshedResults.success) {
                    // Ensure page index is correct
                    _providerPageIndex.update { it + (providerId to currentPage) }

                    val updatedResults = _providerResults.value.toMutableList()
                    val providerIndex = updatedResults.indexOfFirst { it.provider.id == providerId }

                    if (providerIndex >= 0) {
                        // Preserve the current page number
                        val updatedProvider = refreshedResults.copy(
                            totalResults = refreshedResults.results.size,
                            hasMore = refreshedResults.hasMore
                        )
                        updatedResults[providerIndex] = updatedProvider
                        _providerResults.value = updatedResults
                    }

                    _uiState.update { state ->
                        state.copy(
                            totalResults = updatedResults.sumOf { it.results.size },
                            error = null
                        )
                    }
                } else {
                    _uiState.update { it.copy(error = "Refresh failed: ${refreshedResults.errorMessage ?: "unknown error"}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Refresh error: ${e.message}") }
            } finally {
                _providerActionLoading.update { it - providerId }
            }
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val currentSearchQuery: String = "",
    val isSearching: Boolean = false,
    val searchCompleted: Boolean = false,
    val aggregatedResults: AggregatedSearchResults? = null,
    val totalResults: Int = 0,
    val successfulProviders: Int = 0,
    val failedProviders: Int = 0,
    val recentSearches: List<SearchHistoryEntry> = emptyList(),
    val error: String? = null,
    val selectedProviders: Set<String> = emptySet(),
    val availableProviders: List<Provider> = emptyList(),
    val showAdvancedOptions: Boolean = false
)

/**
 * State for video extraction process
 */
sealed class VideoExtractionState {
    object Idle : VideoExtractionState()
    data class Extracting(val title: String) : VideoExtractionState()
    data class Success(
        val videoUrl: String,
        val title: String,
        val quality: String?,
        val isStream: Boolean,
        val headers: Map<String, String> = emptyMap()
    ) : VideoExtractionState()
    data class Error(val message: String) : VideoExtractionState()
}
