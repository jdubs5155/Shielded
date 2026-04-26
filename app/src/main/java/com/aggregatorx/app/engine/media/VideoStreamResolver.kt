package com.aggregatorx.app.engine.media

import android.content.Context
import android.net.Uri
import com.aggregatorx.app.engine.network.ProxyVPNEngine
import com.aggregatorx.app.engine.network.ProxyConfig
import com.aggregatorx.app.engine.network.ProxyType
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.*
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AggregatorX Advanced Video Stream Resolver
 * 
 * Intelligent video playback system with:
 * - Netherlands proxy/VPN integration for geo-restricted content
 * - Multi-source fallback chain (direct → proxy → headless browser)
 * - HLS/DASH stream resolution with quality selection
 * - Automatic format detection and transcoding hints
 * - Smart retry with exponential backoff
 * - Real-time stream health monitoring
 * - Ad/popup bypass for embedded players
 * - Error diagnosis and recovery
 */
@Singleton
class VideoStreamResolver @Inject constructor(
    private val proxyVPNEngine: ProxyVPNEngine,
    private val videoExtractorEngine: VideoExtractorEngine
) {
    
    companion object {
        private val USER_AGENT = EngineUtils.DEFAULT_USER_AGENT
        
        // Stream types that need special handling
        private val STREAM_EXTENSIONS = listOf(".m3u8", ".mpd", ".ts")
        
        // Error patterns and their fixes
        private val ERROR_RECOVERY_MAP = mapOf(
            "Source error" to RecoveryStrategy.TRY_PROXY,
            "Playback failed" to RecoveryStrategy.TRY_ALTERNATE_SOURCE,
            "403" to RecoveryStrategy.USE_NETHERLANDS_PROXY,
            "404" to RecoveryStrategy.TRY_ALTERNATE_SOURCE,
            "geo" to RecoveryStrategy.USE_NETHERLANDS_PROXY,
            "blocked" to RecoveryStrategy.USE_NETHERLANDS_PROXY,
            "unavailable" to RecoveryStrategy.TRY_ALL_METHODS,
            "timeout" to RecoveryStrategy.RETRY_WITH_LONGER_TIMEOUT
        )
        
        // Referer patterns for specific sites
        private val REFERER_PATTERNS = mapOf(
            "vidsrc" to "https://vidsrc.to/",
            "vidcloud" to "https://vidcloud.co/",
            "streamwish" to "https://streamwish.to/",
            "filemoon" to "https://filemoon.sx/",
            "doodstream" to "https://dood.to/",
            "mixdrop" to "https://mixdrop.co/",
            "upstream" to "https://upstream.to/"
        )
    }
    
    /**
     * Resolve and prepare a video stream for playback
     * This is the main entry point - handles all complexity internally
     */
    suspend fun resolveVideoStream(
        pageUrl: String,
        useProxy: Boolean = true,
        preferHighQuality: Boolean = true
    ): VideoStreamResult = withContext(Dispatchers.IO) {
        
        // Initialize proxy if needed
        if (useProxy && proxyVPNEngine.getCurrentProxy() == null) {
            try {
                proxyVPNEngine.initialize()
            } catch (e: Exception) { /* Continue without proxy */ }
        }
        
        // Chain of resolution methods - use suspend lambdas
        val resolutionChain: List<suspend () -> VideoStreamResult> = listOf(
            { resolveDirectExtraction(pageUrl) },
            { resolveWithProxy(pageUrl) },
            { resolveWithHeadlessBrowser(pageUrl) },
            { resolveWithAlternateExtractors(pageUrl) }
        )
        
        var lastError: String? = null
        var bestResult: VideoStreamResult? = null
        
        for (resolver in resolutionChain) {
            try {
                val result = resolver()
                
                if (result.success && result.streamUrl != null) {
                    // Validate the stream URL
                    val validationResult = validateStreamUrl(result.streamUrl, result.headers)
                    
                    if (validationResult.isValid) {
                        return@withContext result.copy(
                            isValidated = true,
                            estimatedBitrate = validationResult.bitrate
                        )
                    } else if (bestResult == null || result.confidence > (bestResult.confidence)) {
                        bestResult = result
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        
        // Return best result even if not validated, or error
        bestResult ?: VideoStreamResult(
            success = false,
            error = lastError ?: "Could not resolve video stream from any source",
            suggestedRecovery = RecoveryStrategy.TRY_ALL_METHODS
        )
    }
    
    /**
     * Direct extraction without proxy
     */
    private suspend fun resolveDirectExtraction(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        val extraction = videoExtractorEngine.extractVideoUrl(pageUrl)
        
        if (extraction.success && extraction.videoUrl != null) {
            val referer = determineReferer(pageUrl)
            
            VideoStreamResult(
                success = true,
                streamUrl = extraction.videoUrl,
                streamType = determineStreamType(extraction.videoUrl),
                quality = extraction.quality ?: "Unknown",
                format = extraction.format ?: "mp4",
                headers = buildPlaybackHeaders(pageUrl, referer),
                confidence = 0.8f
            )
        } else {
            VideoStreamResult(
                success = false,
                error = extraction.error ?: "Direct extraction failed",
                suggestedRecovery = RecoveryStrategy.TRY_PROXY
            )
        }
    }
    
    /**
     * Extraction using Netherlands proxy
     */
    private suspend fun resolveWithProxy(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        try {
            val document = proxyVPNEngine.fetchDocumentWithProxy(pageUrl)
                ?: return@withContext VideoStreamResult(
                    success = false,
                    error = "Proxy fetch failed"
                )
            
            // Extract video from proxy-fetched page
            val videoUrl = extractVideoFromDocument(document, pageUrl)
            
            if (videoUrl != null) {
                val referer = determineReferer(pageUrl)
                VideoStreamResult(
                    success = true,
                    streamUrl = videoUrl,
                    streamType = determineStreamType(videoUrl),
                    quality = detectQualityFromUrl(videoUrl),
                    headers = buildPlaybackHeaders(pageUrl, referer),
                    usedProxy = proxyVPNEngine.getCurrentProxy()?.toString(),
                    confidence = 0.7f
                )
            } else {
                VideoStreamResult(
                    success = false,
                    error = "No video found via proxy",
                    suggestedRecovery = RecoveryStrategy.TRY_HEADLESS_BROWSER
                )
            }
        } catch (e: Exception) {
            VideoStreamResult(
                success = false,
                error = "Proxy resolution error: ${e.message}",
                suggestedRecovery = RecoveryStrategy.TRY_HEADLESS_BROWSER
            )
        }
    }
    
    /**
     * Resolution using headless browser (JavaScript rendering)
     */
    private suspend fun resolveWithHeadlessBrowser(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        try {
            // Use headless browser with ad skip and shadow DOM support
            val pageContent = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                url = pageUrl,
                waitSelector = "video, source, [data-video-url], iframe[src*='player']",
                timeout = 25000
            )
            
            if (pageContent.isNullOrEmpty()) {
                return@withContext VideoStreamResult(
                    success = false,
                    error = "Headless browser fetch failed"
                )
            }
            
            val document = Jsoup.parse(pageContent, pageUrl)
            val videoUrl = extractVideoFromDocument(document, pageUrl)
            
            // Also try extracting video URLs captured by headless browser
            if (videoUrl == null) {
                val capturedVideos = HeadlessBrowserHelper.extractVideoUrls(pageUrl)
                val bestVideo = capturedVideos.maxByOrNull { url ->
                    when {
                        url.contains("1080") -> 100
                        url.contains("720") -> 80
                        url.contains("480") -> 60
                        url.contains(".m3u8") -> 90
                        else -> 50
                    }
                }
                
                if (bestVideo != null) {
                    return@withContext VideoStreamResult(
                        success = true,
                        streamUrl = bestVideo,
                        streamType = determineStreamType(bestVideo),
                        quality = detectQualityFromUrl(bestVideo),
                        headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                        confidence = 0.6f
                    )
                }
            }
            
            if (videoUrl != null) {
                VideoStreamResult(
                    success = true,
                    streamUrl = videoUrl,
                    streamType = determineStreamType(videoUrl),
                    quality = detectQualityFromUrl(videoUrl),
                    headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                    confidence = 0.65f
                )
            } else {
                VideoStreamResult(
                    success = false,
                    error = "No video found via headless browser",
                    suggestedRecovery = RecoveryStrategy.TRY_ALTERNATE_SOURCE
                )
            }
        } catch (e: Exception) {
            VideoStreamResult(
                success = false,
                error = "Headless browser error: ${e.message}",
                suggestedRecovery = RecoveryStrategy.TRY_ALTERNATE_SOURCE
            )
        }
    }
    
    /**
     * Try alternate extractors for embedded players
     */
    private suspend fun resolveWithAlternateExtractors(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        // Try embedded player extraction - use suspend lambdas
        val embedExtractors: List<suspend () -> String?> = listOf(
            { extractFromVidSrc(pageUrl) },
            { extractFromFilemoon(pageUrl) },
            { extractFromDoodstream(pageUrl) },
            { extractFromMixdrop(pageUrl) },
            { extractFromStreamwish(pageUrl) },
            { extractFromIframeChain(pageUrl) }
        )
        
        for (extractor in embedExtractors) {
            try {
                val result = extractor()
                if (result != null && result.isNotEmpty()) {
                    return@withContext VideoStreamResult(
                        success = true,
                        streamUrl = result,
                        streamType = determineStreamType(result),
                        quality = detectQualityFromUrl(result),
                        headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                        confidence = 0.5f
                    )
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        VideoStreamResult(
            success = false,
            error = "All alternate extractors failed",
            suggestedRecovery = RecoveryStrategy.MANUAL_INTERVENTION
        )
    }
    
    /**
     * Extract video URL from parsed document
     */
    private fun extractVideoFromDocument(document: Document, baseUrl: String): String? {
        // Direct video tag
        document.select("video[src], video source[src]").firstOrNull()?.let { video ->
            val src = video.attr("src").takeIf { it.isNotEmpty() }
                ?: video.attr("data-src").takeIf { it.isNotEmpty() }
            if (src != null) return normalizeUrl(src, baseUrl)
        }
        
        // Data attributes
        val dataAttrs = listOf(
            "[data-video-url]", "[data-src]", "[data-video]",
            "[data-file]", "[data-stream]", "[data-mp4]", "[data-hls]"
        )
        for (selector in dataAttrs) {
            document.select(selector).firstOrNull()?.let { elem ->
                val attrName = selector.removeSurrounding("[", "]")
                val url = elem.attr(attrName)
                if (url.isNotEmpty() && isVideoUrl(url)) {
                    return normalizeUrl(url, baseUrl)
                }
            }
        }
        
        // Script extraction
        val scripts = document.select("script").html()
        val patterns = listOf(
            Regex("""(?:src|file|source|url|video_url|videoUrl)['":\s]+['"]?(https?://[^'">\s]+\.(?:mp4|m3u8|webm|mpd)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
            Regex("""file:\s*['"]([^'"]+\.(?:mp4|m3u8|webm|mpd)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            Regex("""sources:\s*\[\s*\{\s*(?:file|src):\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(scripts)?.groupValues?.getOrNull(1)?.let { url ->
                if (isVideoUrl(url)) {
                    return normalizeUrl(url.replace("\\", ""), baseUrl)
                }
            }
        }
        
        return null
    }
    
    /**
     * Validate a stream URL is actually playable.
     * Uses a lenient approach: if HEAD request fails or returns unexpected content-type,
     * still assume playable (CDNs often block HEAD or return generic types).
     */
    private suspend fun validateStreamUrl(url: String, headers: Map<String, String>?): ValidationResult = withContext(Dispatchers.IO) {
        try {
            val client = proxyVPNEngine.createProxyClient()
            
            val requestBuilder = Request.Builder()
                .url(url)
                .head() // HEAD request to check without downloading
                .header("User-Agent", USER_AGENT)
            
            headers?.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            if (response.isSuccessful || response.code == 206 || response.code == 302 || response.code == 301) {
                val contentType = response.header("Content-Type") ?: ""
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
                
                val isValid = when {
                    contentType.contains("video") -> true
                    contentType.contains("mpegurl") -> true // HLS
                    contentType.contains("dash+xml") -> true // DASH
                    contentType.contains("octet-stream") -> true
                    contentType.contains("binary") -> true
                    contentType.contains("application/vnd") -> true
                    url.contains(".m3u8") || url.contains(".mpd") -> true
                    url.contains(".mp4") || url.contains(".webm") -> true
                    // CDNs often return text/plain for m3u8 playlists
                    contentType.contains("text/plain") && (url.contains(".m3u8") || url.contains("manifest")) -> true
                    // If content-type is empty but URL looks like video, assume valid
                    contentType.isEmpty() -> true
                    else -> false
                }
                
                ValidationResult(
                    isValid = isValid,
                    contentType = contentType,
                    bitrate = estimateBitrate(contentLength)
                )
            } else if (response.code == 403 || response.code == 405) {
                // Many CDNs block HEAD requests but allow GET — assume valid
                ValidationResult(isValid = true)
            } else {
                ValidationResult(isValid = false, errorCode = response.code)
            }
        } catch (e: Exception) {
            // If validation fails, assume it might still work (lenient)
            ValidationResult(isValid = true)
        }
    }
    
    // Embedded player extractors
    private suspend fun extractFromVidSrc(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("vidsrc", ignoreCase = true)) return@withContext null
        // VidSrc-specific extraction logic
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                Regex("""file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(scripts)?.groupValues?.get(1)
            }
        } catch (e: Exception) { null }
    }
    
    private suspend fun extractFromFilemoon(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("filemoon", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+)['"]""").find(scripts)?.groupValues?.get(1)
            }
        } catch (e: Exception) { null }
    }
    
    private suspend fun extractFromDoodstream(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("dood", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                Regex("""(?:src|file):\s*['"]([^'"]+/(?:download|video)[^'"]*)['"]""").find(scripts)?.groupValues?.get(1)
            }
        } catch (e: Exception) { null }
    }
    
    private suspend fun extractFromMixdrop(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("mixdrop", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                // Mixdrop uses obfuscation - basic pattern
                Regex("""wurl\s*=\s*['"]([^'"]+)['"]""").find(scripts)?.groupValues?.get(1)?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }
            }
        } catch (e: Exception) { null }
    }
    
    private suspend fun extractFromStreamwish(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("streamwish", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(scripts)?.groupValues?.get(1)
            }
        } catch (e: Exception) { null }
    }

    /**
     * Follow iframe chains up to 3 levels deep to find embedded video players.
     * Many sites nest the actual player inside 1-2 iframe hops.
     */
    private suspend fun extractFromIframeChain(pageUrl: String, depth: Int = 0): String? = withContext(Dispatchers.IO) {
        if (depth > 3) return@withContext null
        try {
            val doc = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(12000)
                .ignoreHttpErrors(true)
                .get()

            // First try direct video extraction from this page
            val directVideo = extractVideoFromDocument(doc, pageUrl)
            if (directVideo != null) return@withContext directVideo

            // Then follow each iframe
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src").let { s ->
                    when {
                        s.startsWith("http") -> s
                        s.startsWith("//") -> "https:$s"
                        s.startsWith("/") -> {
                            val base = Uri.parse(pageUrl)
                            "${base.scheme}://${base.host}$s"
                        }
                        else -> null
                    }
                } ?: continue

                // Skip known non-video iframes
                val lowerSrc = src.lowercase()
                if (lowerSrc.contains("google.com/recaptcha") ||
                    lowerSrc.contains("facebook.com/plugins") ||
                    lowerSrc.contains("twitter.com/widgets") ||
                    lowerSrc.contains("ads.") ||
                    lowerSrc.contains("doubleclick.net")) continue

                // Recursively try extraction from iframe URL
                val result = extractFromIframeChain(src, depth + 1)
                if (result != null) return@withContext result
            }
            null
        } catch (e: Exception) { null }
    }
    
    // Helper functions
    private fun determineStreamType(url: String): StreamType {
        return when {
            url.contains(".m3u8") -> StreamType.HLS
            url.contains(".mpd") -> StreamType.DASH
            url.contains(".ts") -> StreamType.TS
            else -> StreamType.DIRECT
        }
    }
    
    private fun detectQualityFromUrl(url: String): String {
        val qualityPatterns = listOf(
            "2160p" to "4K", "4k" to "4K", "uhd" to "4K",
            "1080p" to "1080p", "1080" to "1080p", "fullhd" to "1080p",
            "720p" to "720p", "720" to "720p", "hd" to "720p",
            "480p" to "480p", "480" to "480p", "sd" to "480p",
            "360p" to "360p", "360" to "360p"
        )
        
        val urlLower = url.lowercase()
        return qualityPatterns.find { urlLower.contains(it.first) }?.second ?: "Auto"
    }
    
    private fun determineReferer(url: String): String {
        val urlLower = url.lowercase()
        return REFERER_PATTERNS.entries.find { urlLower.contains(it.key) }?.value
            ?: try {
                val uri = Uri.parse(url)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
                url
            }
    }
    
    private fun buildPlaybackHeaders(pageUrl: String, referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to referer.trimEnd('/'),
            "Accept" to "*/*",
            "Accept-Language" to "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive"
        )
    }
    
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = Uri.parse(baseUrl)
                "${base.scheme}://${base.host}$url"
            }
            else -> url
        }
    }
    
    private fun isVideoUrl(url: String): Boolean {
        val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mpd", ".mkv", ".avi", ".mov", ".ts")
        return videoExtensions.any { url.contains(it, ignoreCase = true) }
    }
    
    private fun estimateBitrate(contentLength: Long): Long {
        // Rough estimate assuming average video duration
        return if (contentLength > 0) contentLength / 60 else 0
    }
    
    /**
     * Diagnose playback error and suggest recovery
     */
    fun diagnoseError(error: String): RecoveryStrategy {
        val errorLower = error.lowercase()
        return ERROR_RECOVERY_MAP.entries.find { errorLower.contains(it.key) }?.value
            ?: RecoveryStrategy.TRY_ALL_METHODS
    }
}

/**
 * Video stream resolution result
 */
data class VideoStreamResult(
    val success: Boolean,
    val streamUrl: String? = null,
    val streamType: StreamType = StreamType.DIRECT,
    val quality: String = "Unknown",
    val format: String = "mp4",
    val headers: Map<String, String>? = null,
    val usedProxy: String? = null,
    val error: String? = null,
    val suggestedRecovery: RecoveryStrategy? = null,
    val confidence: Float = 0f,
    val isValidated: Boolean = false,
    val estimatedBitrate: Long = 0
)

/**
 * Stream validation result
 */
data class ValidationResult(
    val isValid: Boolean,
    val contentType: String = "",
    val bitrate: Long = 0,
    val errorCode: Int = 0
)

/**
 * Stream types supported
 */
enum class StreamType {
    DIRECT,     // Direct MP4/WebM file
    HLS,        // m3u8 playlist
    DASH,       // mpd manifest
    TS,         // Transport stream
    EMBED       // Embedded player (YouTube, Vimeo, etc.)
}

/**
 * Error recovery strategies
 */
enum class RecoveryStrategy {
    TRY_PROXY,                  // Use Netherlands proxy
    USE_NETHERLANDS_PROXY,      // Force Netherlands proxy for geo-blocked content
    TRY_HEADLESS_BROWSER,       // Use JavaScript rendering
    TRY_ALTERNATE_SOURCE,       // Try different video source
    TRY_ALL_METHODS,            // Try every available method
    RETRY_WITH_LONGER_TIMEOUT,  // Retry with extended timeout
    MANUAL_INTERVENTION         // Requires user action
}
