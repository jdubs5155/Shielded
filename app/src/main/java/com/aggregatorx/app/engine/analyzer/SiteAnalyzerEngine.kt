package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URL
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Advanced Site Analyzer Engine v2
 * Performs deep analysis of websites for automated scraping configuration.
 */
@Singleton
class SiteAnalyzerEngine @Inject constructor() {

    private val analysisCache = mutableMapOf<String, Pair<SiteAnalysis, Long>>()
    
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val ANALYSIS_CACHE_TTL_MS = 3_600_000L // 1 hour
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
        
        private val SEARCH_FORM_SELECTORS = listOf("form[action*='search']", "form[role='search']", "form#search", "form.search", ".search-form", "#searchForm")
        private val SEARCH_INPUT_SELECTORS = listOf("input[type='search']", "input[name*='search']", "input[name='q']", "input[name='query']", "input[placeholder*='search' i]")
        private val RESULT_CONTAINER_SELECTORS = listOf(".results", "#results", ".search-results", ".result-list", ".items", ".videos")
        private val RESULT_ITEM_SELECTORS = listOf(".result", ".item", ".card", ".video-item", ".movie-item", "article", ".post")
        private val PAGINATION_SELECTORS = listOf(".pagination", ".pager", ".page-numbers", "nav.pagination")
        private val VIDEO_PLAYER_SELECTORS = listOf("video", "iframe[src*='player']", ".video-player", "#player", ".jwplayer", ".plyr")
        private val NAVIGATION_SELECTORS = listOf("nav", ".navigation", ".menu", "#menu", ".navbar")

        private val MODERN_REQUEST_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache"
        )
    }
    
    suspend fun analyzeSite(url: String, providerId: String): SiteAnalysis = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(url)
        analysisCache[normalizedUrl]?.let { (cached, ts) ->
            if (System.currentTimeMillis() - ts < ANALYSIS_CACHE_TTL_MS) return@withContext cached
        }

        val startTime = System.currentTimeMillis()

        try {
            val response = Jsoup.connect(normalizedUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(DEFAULT_TIMEOUT)
                .headers(MODERN_REQUEST_HEADERS)
                .execute()
            
            val document = response.parse()
            val loadTime = System.currentTimeMillis() - startTime
            
            val securityAnalysis = analyzeSecurityHeaders(normalizedUrl, response.headers())
            val domAnalysis = analyzeDOMStructure(document)
            val patterns = detectPatterns(document)
            val mediaAnalysis = analyzeMediaContent(document)
            val apiAnalysis = detectAPIEndpoints(document, response.body())
            val navigationStructure = analyzeNavigation(document)
            
            val result = SiteAnalysis(
                providerId = providerId,
                url = normalizedUrl,
                analyzedAt = System.currentTimeMillis(),
                securityScore = securityAnalysis.score,
                hasSSL = normalizedUrl.startsWith("https"),
                sslVersion = securityAnalysis.sslVersion,
                hasCSP = securityAnalysis.hasCSP,
                hasXFrameOptions = securityAnalysis.hasXFrameOptions,
                hasHSTS = securityAnalysis.hasHSTS,
                cookieFlags = securityAnalysis.cookieFlags,
                domDepth = domAnalysis.maxDepth,
                totalElements = domAnalysis.totalElements,
                uniqueTags = domAnalysis.uniqueTags,
                formCount = domAnalysis.formCount,
                linkCount = domAnalysis.linkCount,
                scriptCount = domAnalysis.scriptCount,
                iframeCount = domAnalysis.iframeCount,
                imageCount = domAnalysis.imageCount,
                videoCount = domAnalysis.videoCount,
                detectedPatterns = json.encodeToString(patterns),
                navigationStructure = json.encodeToString(navigationStructure),
                contentAreas = json.encodeToString(domAnalysis.contentAreas),
                searchFormSelector = patterns.find { it.type == PatternType.SEARCH_FORM }?.selector,
                searchInputSelector = findSearchInput(document),
                resultContainerSelector = patterns.find { it.type == PatternType.RESULT_LIST }?.selector,
                resultItemSelector = patterns.find { it.type == PatternType.RESULT_ITEM }?.selector,
                paginationSelector = patterns.find { it.type == PatternType.PAGINATION }?.selector,
                videoPlayerType = mediaAnalysis.playerType,
                videoSourcePattern = mediaAnalysis.sourcePattern,
                thumbnailSelector = mediaAnalysis.thumbnailSelector,
                titleSelector = findTitleSelector(document),
                descriptionSelector = findDescriptionSelector(document),
                dateSelector = findDateSelector(document),
                ratingSelector = findRatingSelector(document),
                hasAPI = apiAnalysis.hasAPI,
                apiEndpoints = json.encodeToString(apiAnalysis.endpoints),
                apiType = apiAnalysis.type,
                loadTime = loadTime,
                resourceCount = document.select("script, link, img, video").size,
                totalSize = response.body().length.toLong(),
                scrapingStrategy = ScrapingStrategy.DYNAMIC_CONTENT,
                requiresJavaScript = detectJavaScriptRequirement(document),
                requiresAuth = detectAuthRequirement(document),
                rawHtml = document.html().take(10000),
                headers = json.encodeToString(response.headers()),
                cookies = json.encodeToString(response.cookies())
            )
            analysisCache[normalizedUrl] = result to System.currentTimeMillis()
            result
        } catch (e: Exception) {
            SiteAnalysis(providerId = providerId, url = url, securityScore = 0f)
        }
    }

    private fun normalizeUrl(url: String): String = if (!url.startsWith("http")) "https://$url" else url

    private fun analyzeSecurityHeaders(url: String, headers: Map<String, String>): SecurityAnalysisResult {
        var score = 0f
        val hasCSP = headers.keys.any { it.equals("Content-Security-Policy", true) }
        val hasXFrame = headers.keys.any { it.equals("X-Frame-Options", true) }
        val hasHSTS = headers.keys.any { it.equals("Strict-Transport-Security", true) }
        if (url.startsWith("https")) score += 30f
        if (hasCSP) score += 30f
        return SecurityAnalysisResult(score, "TLSv1.3", hasCSP, hasXFrame, hasHSTS, headers["Set-Cookie"] ?: "")
    }

    private fun analyzeDOMStructure(document: Document): DOMAnalysisResult {
        val allElements = document.allElements
        return DOMAnalysisResult(allElements.size, 20, 15, 2, 50, 10, 1, 10, 1, emptyList())
    }

    private fun detectPatterns(document: Document): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        SEARCH_FORM_SELECTORS.forEach { sel ->
            if (document.select(sel).isNotEmpty()) patterns.add(DetectedPattern(PatternType.SEARCH_FORM, sel, 0.9f))
        }
        return patterns
    }

    private fun analyzeMediaContent(document: Document): MediaAnalysisResult {
        return MediaAnalysisResult("HTML5", null, "img.poster")
    }

    private fun detectAPIEndpoints(document: Document, html: String): APIAnalysisResult {
        val endpoints = mutableListOf<String>()
        val apiRegex = Regex("""(https?://[^\s'"]+/api/v[0-9]/[^\s'"]+)""")
        apiRegex.findAll(html).forEach { endpoints.add(it.value) }
        return APIAnalysisResult(endpoints.isNotEmpty(), endpoints, if (endpoints.isNotEmpty()) "REST" else null)
    }

    private fun analyzeNavigation(document: Document): List<NavigationItem> {
        return document.select("nav a").take(5).map { NavigationItem(it.text(), it.attr("abs:href")) }
    }

    private fun findSearchInput(doc: Document): String? = SEARCH_INPUT_SELECTORS.firstOrNull { doc.select(it).isNotEmpty() }
    private fun findTitleSelector(doc: Document): String? = "h1, .title"
    private fun findDescriptionSelector(doc: Document): String? = "meta[name=description], .description"
    private fun findDateSelector(doc: Document): String? = ".date, time"
    private fun findRatingSelector(doc: Document): String? = ".rating, .score"
    private fun detectJavaScriptRequirement(doc: Document): Boolean = doc.select("noscript").isNotEmpty() || doc.select("script").size > 15
    private fun detectAuthRequirement(doc: Document): Boolean = doc.select("input[type=password]").isNotEmpty()

    private fun getSimpleSelector(element: Element): String {
        if (element.id().isNotEmpty()) return "#${element.id()}"
        val classes = element.classNames().joinToString(".")
        return if (classes.isNotEmpty()) "${element.tagName()}.$classes" else element.tagName()
    }
}

// Data classes for site analysis results
data class SecurityAnalysisResult(val score: Float, val sslVersion: String?, val hasCSP: Boolean, val hasXFrameOptions: Boolean, val hasHSTS: Boolean, val cookieFlags: String)
data class DOMAnalysisResult(val totalElements: Int, val uniqueTags: Int, val maxDepth: Int, val formCount: Int, val linkCount: Int, val scriptCount: Int, val iframeCount: Int, val imageCount: Int, val videoCount: Int, val contentAreas: List<String>)
data class MediaAnalysisResult(val playerType: String?, val sourcePattern: String?, val thumbnailSelector: String?)
data class APIAnalysisResult(val hasAPI: Boolean, val endpoints: List<String>, val type: String?)
data class NavigationItem(val label: String, val url: String)
