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

        private val CMS_PATTERNS = mapOf(
            "WordPress" to listOf("wp-content", "wp-includes", "wp-json"),
            "Ghost" to listOf("ghost.io", "content/ghost"),
            "Shopify" to listOf("shopify.com", "cdn.shopify.com")
        )

        private val MODERN_REQUEST_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Upgrade-Insecure-Requests" to "1"
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
            val strategy = determineScrapingStrategy(document, patterns)
            
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
                scrapingStrategy = strategy,
                requiresJavaScript = detectJavaScriptRequirement(document),
                requiresAuth = detectAuthRequirement(document),
                rawHtml = document.html().take(50000),
                headers = json.encodeToString(response.headers()),
                cookies = json.encodeToString(response.cookies())
            )
            analysisCache[normalizedUrl] = result to System.currentTimeMillis()
            result
        } catch (e: Exception) {
            SiteAnalysis(providerId = providerId, url = url, securityScore = 0f)
        }
    }
    
    private fun analyzeSecurityHeaders(url: String, headers: Map<String, String>): SecurityAnalysisResult {
        var score = 0f
        val hasCSP = headers.keys.any { it.equals("Content-Security-Policy", true) }
        val hasXFrame = headers.keys.any { it.equals("X-Frame-Options", true) }
        val hasHSTS = headers.keys.any { it.equals("Strict-Transport-Security", true) }
        
        if (url.startsWith("https")) score += 30f
        if (hasCSP) score += 30f
        if (hasXFrame) score += 20f
        if (hasHSTS) score += 20f
        
        return SecurityAnalysisResult(
            score = score,
            sslVersion = if (url.startsWith("https")) "TLSv1.3" else null,
            hasCSP = hasCSP,
            hasXFrameOptions = hasXFrame,
            hasHSTS = hasHSTS,
            cookieFlags = headers["Set-Cookie"] ?: "None"
        )
    }
    
    private fun analyzeDOMStructure(document: Document): DOMAnalysisResult {
        val allElements = document.allElements
        var maxDepth = 0
        fun calcDepth(el: Element, depth: Int) {
            maxDepth = maxOf(maxDepth, depth)
            el.children().forEach { calcDepth(it, depth + 1) }
        }
        document.body()?.let { calcDepth(it, 0) }
        
        return DOMAnalysisResult(
            totalElements = allElements.size,
            uniqueTags = allElements.map { it.tagName() }.distinct().size,
            maxDepth = maxDepth,
            formCount = document.select("form").size,
            linkCount = document.select("a").size,
            scriptCount = document.select("script").size,
            iframeCount = document.select("iframe").size,
            imageCount = document.select("img").size,
            videoCount = document.select("video").size,
            contentAreas = findContentAreas(document)
        )
    }

    private fun findContentAreas(document: Document): List<ContentArea> {
        return listOf("main", "#content", ".content", "article").mapNotNull { selector ->
            document.select(selector).firstOrNull()?.let {
                ContentArea(selector, it.tagName(), it.children().size, it.text().length, 0.8f)
            }
        }
    }
    
    private fun detectPatterns(document: Document): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        
        fun addPattern(list: List<String>, type: PatternType) {
            list.forEach { sel ->
                val els = document.select(sel)
                if (els.isNotEmpty()) {
                    patterns.add(DetectedPattern(type, sel, 0.9f, els.first()?.outerHtml()?.take(100), els.size))
                }
            }
        }

        addPattern(SEARCH_FORM_SELECTORS, PatternType.SEARCH_FORM)
        addPattern(RESULT_CONTAINER_SELECTORS, PatternType.RESULT_LIST)
        addPattern(PAGINATION_SELECTORS, PatternType.PAGINATION)
        addPattern(VIDEO_PLAYER_SELECTORS, PatternType.VIDEO_PLAYER)
        
        // Manual result item detection
        val items = RESULT_ITEM_SELECTORS.firstOrNull { document.select(it).size >= 3 }
        if (items != null) {
            patterns.add(DetectedPattern(PatternType.RESULT_ITEM, items, 0.8f, null, document.select(items).size))
        }

        return patterns
    }
    
    private fun analyzeMediaContent(document: Document): MediaAnalysisResult {
        val player = when {
            document.select(".jwplayer").isNotEmpty() -> "JWPlayer"
            document.select("video").isNotEmpty() -> "HTML5"
            else -> null
        }
        val source = document.select("video source").attr("src").takeIf { it.isNotEmpty() }
        return MediaAnalysisResult(player, source, document.select("img[class*='thumb']").firstOrNull()?.let { getSimpleSelector(it) })
    }

    private fun detectAPIEndpoints(document: Document, html: String): APIAnalysisResult {
