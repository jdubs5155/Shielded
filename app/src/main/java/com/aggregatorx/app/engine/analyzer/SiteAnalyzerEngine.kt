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
 *
 * Performs deep analysis of websites including:
 * - Security analysis (SSL, headers, CSP, etc.)
 * - DOM structure analysis
 * - Pattern detection (search forms, result lists, video players, etc.)
 * - API endpoint detection (static + runtime network intercept)
 * - Tab/category navigation discovery for no-search sites
 * - Content mapping for streaming and media sites
 * - Performance metrics
 * - Results cached per URL to avoid redundant network calls
 */
@Singleton
class SiteAnalyzerEngine @Inject constructor() {

    /** Cache: url → (SiteAnalysis, timestamp) */
    private val analysisCache = mutableMapOf<String, Pair<SiteAnalysis, Long>>()
    
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }
    
    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val ANALYSIS_CACHE_TTL_MS = 3_600_000L // 1 hour
        private val DEFAULT_USER_AGENT = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT
        
        // Common selectors for pattern detection
        private val SEARCH_FORM_SELECTORS = listOf(
            "form[action*='search']", "form[role='search']", "form#search", 
            "form.search", ".search-form", "#searchForm", "form[method='get']"
        )
        private val SEARCH_INPUT_SELECTORS = listOf(
            "input[type='search']", "input[name*='search']", "input[name='q']",
            "input[name='query']", "input[placeholder*='search' i]", "#search-input"
        )
        private val RESULT_CONTAINER_SELECTORS = listOf(
            ".results", "#results", ".search-results", "#search-results",
            ".result-list", ".content-list", ".items", ".videos", ".movies"
        )
        private val RESULT_ITEM_SELECTORS = listOf(
            ".result", ".item", ".card", ".video-item", ".movie-item",
            ".torrent", ".row", "article", ".entry", ".post"
        )
        private val PAGINATION_SELECTORS = listOf(
            ".pagination", ".pager", ".page-numbers", ".pages",
            "nav.pagination", ".paginate", "[class*='pagination']"
        )
        private val VIDEO_PLAYER_SELECTORS = listOf(
            "video", "iframe[src*='player']", ".video-player", "#player",
            "iframe[src*='youtube']", "iframe[src*='vimeo']", ".jwplayer", ".plyr"
        )
        private val NAVIGATION_SELECTORS = listOf(
            "nav", ".navigation", ".menu", "#menu", ".navbar", "header nav"
        )

        // CMS detection patterns
        private val CMS_PATTERNS = mapOf(
            "WordPress" to listOf("wp-content", "wp-includes", "wp-json", "/wp/"),
            "Ghost" to listOf("ghost.io", "content/ghost", "/assets/built/"),
            "Drupal" to listOf("drupal.js", "drupal.min.js", "sites/default/"),
            "Joomla" to listOf("/templates/", "joomla", "com_content"),
            "Wix" to listOf("wixstatic.com", "wix.com", "X-Wix-"),
            "Squarespace" to listOf("squarespace.com", "squarespace-cdn.com"),
            "Shopify" to listOf("shopify.com", "cdn.shopify.com", "Shopify.theme"),
            "Strapi" to listOf("/api/", "strapi", "_strapi"),
            "Contentful" to listOf("contentful.com", "ctfassets.net"),
            "Webflow" to listOf("webflow.com", "webflow.io"),
            "Magento" to listOf("mage", "magento", "Magento"),
            "PrestaShop" to listOf("prestashop", "prestashop.com")
        )

        // Modern Accept headers that modern browsers send (helps bypass blocks)
        private val MODERN_REQUEST_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "sec-ch-ua" to "\"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\", \"Not-A.Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache"
        )
    }
    
    /**
     * Perform comprehensive site analysis, returning a cached result if fresh.
     */
    suspend fun analyzeSite(url: String, providerId: String): SiteAnalysis = withContext(Dispatchers.IO) {
        val normalizedKey = normalizeUrl(url)
        // Return cached analysis if still fresh
        analysisCache[normalizedKey]?.let { (cached, ts) ->
            if (System.currentTimeMillis() - ts < ANALYSIS_CACHE_TTL_MS) return@withContext cached
        }

        val startTime = System.currentTimeMillis()

        try {
            // Normalize URL
            val normalizedUrl = normalizeUrl(url)
            val baseUrl = extractBaseUrl(normalizedUrl)
            
            // Fetch the page with modern browser headers
            val connection = Jsoup.connect(normalizedUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(DEFAULT_TIMEOUT)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(false)
                .headers(MODERN_REQUEST_HEADERS)
            
            val response = connection.execute()
            val document = response.parse()
            val loadTime = System.currentTimeMillis() - startTime
            
            // Perform all analyses
            val securityAnalysis = analyzeSecurityHeaders(normalizedUrl, response.headers())
            val domAnalysis = analyzeDOMStructure(document)
            val patterns = detectPatterns(document)
            val mediaAnalysis = analyzeMediaContent(document)
            val apiAnalysis = detectAPIEndpoints(document, response.body())
            val navigationStructure = analyzeNavigation(document)
            val scrapingStrategy = determineScrapingStrategy(document, patterns)
            
            val result = SiteAnalysis(
                providerId = providerId,
                url = normalizedUrl,
                analyzedAt = System.currentTimeMillis(),
                
                // Security
                securityScore = securityAnalysis.score,
                hasSSL = normalizedUrl.startsWith("https"),
                sslVersion = securityAnalysis.sslVersion,
                hasCSP = securityAnalysis.hasCSP,
                hasXFrameOptions = securityAnalysis.hasXFrameOptions,
                hasHSTS = securityAnalysis.hasHSTS,
                cookieFlags = securityAnalysis.cookieFlags,
                
                // DOM Structure
                domDepth = domAnalysis.maxDepth,
                totalElements = domAnalysis.totalElements,
                uniqueTags = domAnalysis.uniqueTags,
                formCount = domAnalysis.formCount,
                linkCount = domAnalysis.linkCount,
                scriptCount = domAnalysis.scriptCount,
                iframeCount = domAnalysis.iframeCount,
                imageCount = domAnalysis.imageCount,
                videoCount = domAnalysis.videoCount,
                
                // Patterns
                detectedPatterns = json.encodeToString(patterns),
                navigationStructure = json.encodeToString(navigationStructure),
                contentAreas = json.encodeToString(domAnalysis.contentAreas),
                searchFormSelector = patterns.find { it.type == PatternType.SEARCH_FORM }?.selector,
                searchInputSelector = findSearchInput(document),
                resultContainerSelector = patterns.find { it.type == PatternType.RESULT_LIST }?.selector,
                resultItemSelector = patterns.find { it.type == PatternType.RESULT_ITEM }?.selector,
                paginationSelector = patterns.find { it.type == PatternType.PAGINATION }?.selector,
                
                // Media
                videoPlayerType = mediaAnalysis.playerType,
                videoSourcePattern = mediaAnalysis.sourcePattern,
                thumbnailSelector = mediaAnalysis.thumbnailSelector,
                titleSelector = findTitleSelector(document, patterns),
                descriptionSelector = findDescriptionSelector(document),
                dateSelector = findDateSelector(document),
                ratingSelector = findRatingSelector(document),
                
                // API
                hasAPI = apiAnalysis.hasAPI,
                apiEndpoints = json.encodeToString(apiAnalysis.endpoints),
                apiType = apiAnalysis.type,
                
                // Performance
                loadTime = loadTime,
                resourceCount = document.select("script, link, img, video").size,
                totalSize = response.body().length.toLong(),
                
                // Scraping Config
                scrapingStrategy = scrapingStrategy,
                requiresJavaScript = detectJavaScriptRequirement(document),
                requiresAuth = detectAuthRequirement(document),
                
                // Raw data
                rawHtml = document.html().take(50000), // Limit storage
                headers = json.encodeToString(response.headers()),
                cookies = json.encodeToString(response.cookies())
            )
            analysisCache[normalizedKey] = result to System.currentTimeMillis()
            result
        } catch (e: Exception) {
            // Return minimal analysis on failure
            SiteAnalysis(
                providerId = providerId,
                url = url,
                securityScore = 0f
            )
        }
    }
    
    /**
     * Security Header Analysis
     */
    private fun analyzeSecurityHeaders(url: String, headers: Map<String, String>): SecurityAnalysisResult {
        var score = 0f
        var sslVersion: String? = null
        
        // Check SSL
        if (url.startsWith("https")) {
            score += 20f
            sslVersion = getSSLVersion(url)
        }
        
        // Check security headers
        val hasCSP = headers.keys.any { it.equals("Content-Security-Policy", ignoreCase = true) }
        if (hasCSP) score += 20f
        
        val hasXFrameOptions = headers.keys.any { it.equals("X-Frame-Options", ignoreCase = true) }
        if (hasXFrameOptions) score += 15f
        
        val hasHSTS = headers.keys.any { it.equals("Strict-Transport-Security", ignoreCase = true) }
        if (hasHSTS) score += 20f
        
        val hasXContentType = headers.keys.any { it.equals("X-Content-Type-Options", ignoreCase = true) }
        if (hasXContentType) score += 10f
        
        val hasXXSS = headers.keys.any { it.equals("X-XSS-Protection", ignoreCase = true) }
        if (hasXXSS) score += 10f
        
        val hasReferrerPolicy = headers.keys.any { it.equals("Referrer-Policy", ignoreCase = true) }
        if (hasReferrerPolicy) score += 5f
        
        // Check cookie flags
        val setCookie = headers.entries.find { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
        val cookieFlags = analyzeCookieFlags(setCookie)
        
        return SecurityAnalysisResult(
            score = score,
            sslVersion = sslVersion,
            hasCSP = hasCSP,
            hasXFrameOptions = hasXFrameOptions,
            hasHSTS = hasHSTS,
            cookieFlags = cookieFlags
        )
    }
    
    private fun getSSLVersion(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as? HttpsURLConnection
            connection?.connect()
            // Get cipher suite which indicates TLS version
            val cipherSuite = connection?.cipherSuite
            connection?.disconnect()
            when {
                cipherSuite?.contains("TLS13") == true -> "TLSv1.3"
                cipherSuite?.contains("TLS12") == true -> "TLSv1.2"
                cipherSuite?.contains("TLS11") == true -> "TLSv1.1"
                cipherSuite?.contains("TLS") == true -> "TLSv1.0"
                cipherSuite?.contains("SSL") == true -> "SSL"
                else -> "TLSv1.2" // Default assumption for modern servers
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun analyzeCookieFlags(cookie: String?): String {
        if (cookie == null) return "No cookies"
        val flags = mutableListOf<String>()
        if (cookie.contains("Secure", ignoreCase = true)) flags.add("Secure")
        if (cookie.contains("HttpOnly", ignoreCase = true)) flags.add("HttpOnly")
        if (cookie.contains("SameSite", ignoreCase = true)) flags.add("SameSite")
        return flags.joinToString(", ").ifEmpty { "No security flags" }
    }
    
    /**
     * DOM Structure Analysis
     */
    private fun analyzeDOMStructure(document: Document): DOMAnalysisResult {
        val allElements = document.allElements
        val uniqueTags = allElements.map { it.tagName() }.distinct().size
        
        // Calculate max depth
        var maxDepth = 0
        fun calculateDepth(element: Element, depth: Int) {
            if (depth > maxDepth) maxDepth = depth
            element.children().forEach { calculateDepth(it, depth + 1) }
        }
        document.body()?.let { calculateDepth(it, 0) }
        
        // Find content areas
        val contentAreas = findContentAreas(document)
        
        return DOMAnalysisResult(
            totalElements = allElements.size,
            uniqueTags = uniqueTags,
            maxDepth = maxDepth,
            formCount = document.select("form").size,
            linkCount = document.select("a").size,
            scriptCount = document.select("script").size,
            iframeCount = document.select("iframe").size,
            imageCount = document.select("img").size,
            videoCount = document.select("video").size,
            contentAreas = contentAreas
        )
    }
    
    private fun findContentAreas(document: Document): List<ContentArea> {
        val areas = mutableListOf<ContentArea>()
        
        // Look for main content areas
        val mainSelectors = listOf(
            "main", "#main", ".main", "#content", ".content",
            "article", ".articles", "#articles", ".container"
        )
        
        mainSelectors.forEach { selector ->
            document.select(selector).firstOrNull()?.let { element ->
                areas.add(ContentArea(
                    selector = selector,
                    tagName = element.tagName(),
                    childCount = element.children().size,
                    textLength = element.text().length,
                    confidence = calculateContentConfidence(element)
                ))
            }
        }
        
        return areas.sortedByDescending { it.confidence }
    }
    
    private fun calculateContentConfidence(element: Element): Float {
        var confidence = 0f
        
        // More text = higher confidence it's a content area
        val textLength = element.text().length
        if (textLength > 500) confidence += 0.3f
        if (textLength > 2000) confidence += 0.2f
        
        // Has links and images = likely content
        if (element.select("a").isNotEmpty()) confidence += 0.2f
        if (element.select("img").isNotEmpty()) confidence += 0.15f
        
        // Has articles or items
        if (element.select("article, .item, .card").isNotEmpty()) confidence += 0.15f
        
        return confidence.coerceAtMost(1f)
    }
    
    /**
     * Pattern Detection
     */
    private fun detectPatterns(document: Document): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        
        // Search Form
        SEARCH_FORM_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.SEARCH_FORM,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    sampleContent = elements.first()?.outerHtml()?.take(200),
                    occurrences = elements.size
                ))
            }
        }
        
        // Result Lists
        RESULT_CONTAINER_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.RESULT_LIST,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    sampleContent = elements.first()?.outerHtml()?.take(200),
                    occurrences = elements.size
                ))
            }
        }
        
        // Result Items
        detectResultItems(document)?.let { patterns.add(it) }
        
        // Pagination
        PAGINATION_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.PAGINATION,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    sampleContent = elements.first()?.outerHtml()?.take(200),
                    occurrences = elements.size
                ))
            }
        }
        
        // Video Players
        detectVideoPlayer(document)?.let { patterns.add(it) }
        
        // Navigation
        NAVIGATION_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.NAVIGATION,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    occurrences = elements.size
                ))
            }
        }
        
        // Additional patterns
        detectAdditionalPatterns(document, patterns)
        
        return patterns.sortedByDescending { it.confidence }
    }
    
    private fun detectResultItems(document: Document): DetectedPattern? {
        // Look for repeating structures
        val candidates = mutableMapOf<String, Int>()
        
        // Check common item selectors
        RESULT_ITEM_SELECTORS.forEach { selector ->
            val count = document.select(selector).size
            if (count >= 3) { // At least 3 items suggests a list
                candidates[selector] = count
            }
        }
        
        // Also look for data attributes
        document.select("[data-id], [data-item], [data-result]").let {
            if (it.isNotEmpty() && it.size >= 3) {
                val selector = it.first()?.let { el ->
                    when {
                        el.hasAttr("data-id") -> "[data-id]"
                        el.hasAttr("data-item") -> "[data-item]"
                        else -> "[data-result]"
                    }
                }
                selector?.let { candidates[it] = it.length }
            }
        }
        
        // Return the best candidate
        return candidates.maxByOrNull { it.value }?.let { (selector, count) ->
            DetectedPattern(
                type = PatternType.RESULT_ITEM,
                selector = selector,
                confidence = (count.toFloat() / 20).coerceAtMost(1f),
                occurrences = count
            )
        }
    }
    
    private fun detectVideoPlayer(document: Document): DetectedPattern? {
        VIDEO_PLAYER_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                return DetectedPattern(
                    type = PatternType.VIDEO_PLAYER,
                    selector = selector,
                    confidence = 0.9f,
                    sampleContent = elements.first()?.outerHtml()?.take(300),
                    occurrences = elements.size
                )
            }
        }
        return null
    }
    
    private fun detectAdditionalPatterns(document: Document, patterns: MutableList<DetectedPattern>) {
        // Infinite scroll detection
        if (document.select("[data-infinite-scroll], .infinite-scroll, [class*='infinite']").isNotEmpty() ||
            document.html().contains("IntersectionObserver") ||
            document.html().contains("infinite")) {
            patterns.add(DetectedPattern(
                type = PatternType.INFINITE_SCROLL,
                selector = "[data-infinite-scroll]",
                confidence = 0.7f
            ))
        }
        
        // Load more button
        document.select("button:contains(Load More), a:contains(Load More), .load-more, #load-more").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.LOAD_MORE_BUTTON,
                selector = it.cssSelector(),
                confidence = 0.9f
            ))
        }
        
        // Thumbnail grid
        document.select(".thumbnails, .thumb-grid, .video-grid, .image-grid").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.THUMBNAIL_GRID,
                selector = it.cssSelector(),
                confidence = 0.85f
            ))
        }
        
        // Card layout
        document.select(".cards, .card-container, .card-grid").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.CARD_LAYOUT,
                selector = it.cssSelector(),
                confidence = 0.85f
            ))
        }
        
        // Filter panel
        document.select(".filters, .filter-panel, #filters, [class*='filter']").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.FILTER_PANEL,
                selector = it.cssSelector(),
                confidence = 0.8f
            ))
        }
        
        // Category list
        document.select(".categories, .category-list, #categories").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.CATEGORY_LIST,
                selector = it.cssSelector(),
                confidence = 0.85f
            ))
        }
        
        // Rating system
        document.select(".rating, .stars, [class*='rating'], [data-rating]").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.RATING_SYSTEM,
                selector = it.cssSelector(),
                confidence = 0.8f
            ))
        }
        
        // Login form
        document.select("form[action*='login'], form#login, .login-form, form:has(input[type='password'])").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.LOGIN_FORM,
                selector = it.cssSelector(),
                confidence = 0.9f
            ))
        }
    }
    
    /**
     * Media Content Analysis
     */
    private fun analyzeMediaContent(document: Document): MediaAnalysisResult {
        // Dismiss overlays/popups/ads before extracting media
        val cleanedDoc = dismissOverlaysAndAds(document)

        var playerType: String? = null
        var sourcePattern: String? = null
        var thumbnailSelector: String? = null

        // Detect video player type
        when {
            cleanedDoc.select(".jwplayer, #jwplayer").isNotEmpty() -> playerType = "JWPlayer"
            cleanedDoc.select(".video-js, .vjs-tech").isNotEmpty() -> playerType = "VideoJS"
            cleanedDoc.select(".plyr").isNotEmpty() -> playerType = "Plyr"
            cleanedDoc.select("iframe[src*='youtube']").isNotEmpty() -> playerType = "YouTube"
            cleanedDoc.select("iframe[src*='vimeo']").isNotEmpty() -> playerType = "Vimeo"
            cleanedDoc.select("video").isNotEmpty() -> playerType = "HTML5"
        }

        // Detect video source patterns
        cleanedDoc.select("video source, video[src]").firstOrNull()?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty()) {
                sourcePattern = extractUrlPattern(src)
            }
        }

        // Also check for m3u8 or streaming patterns in scripts
        val scripts = cleanedDoc.select("script").html()
        when {
            scripts.contains(".m3u8") -> sourcePattern = "HLS (m3u8)"
            scripts.contains(".mpd") -> sourcePattern = "DASH (mpd)"
            scripts.contains("rtmp://") -> sourcePattern = "RTMP"
        }

        // Find thumbnail selectors
        thumbnailSelector = listOf(
            ".thumbnail img", ".thumb img", "img.thumbnail",
            ".poster", "img.poster", "[data-poster]"
        ).firstOrNull { cleanedDoc.select(it).isNotEmpty() }

        return MediaAnalysisResult(
            playerType = playerType,
            sourcePattern = sourcePattern,
            thumbnailSelector = thumbnailSelector
        )
    }

    /**
     * Remove overlays/popups/ads and auto-click close/dismiss buttons
     */
    private fun dismissOverlaysAndAds(document: Document): Document {
        val popupSelectors = listOf(
            ".popup, .modal, .overlay, .ad, .banner, .cookie, .notification, .interstitial",
            "[class*='popup']", "[class*='modal']", "[class*='overlay']", "[class*='ad']",
            "[id*='popup']", "[id*='modal']", "[id*='overlay']", "[id*='ad']"
        )
        val closeButtonSelectors = listOf(
            ".close, .dismiss, .exit, .btn-close, .close-btn, .close-button, .modal-close, .popup-close",
            "button[aria-label='Close']", "button[aria-label='Dismiss']", "[data-dismiss]", "[data-close]"
        )

        // Remove overlays/popups/ads
        popupSelectors.forEach { selector ->
            document.select(selector).forEach { it.remove() }
        }

        // Simulate auto-clicking close/dismiss buttons
        closeButtonSelectors.forEach { selector ->
            document.select(selector).forEach { it.remove() }
        }

        return document
    }
    
    /**
     * API Endpoint Detection
     */
    private fun detectAPIEndpoints(document: Document, html: String): APIAnalysisResult {
        val endpoints = mutableListOf<String>()
        var apiType: String? = null
        val detectedTypes = mutableSetOf<String>()

        // Collect all inline script content for analysis
        val scripts = document.select("script").html()
        val allText = html

        // --- REST API patterns (fetch, axios, jQuery ajax, XHR, Angular http) ---
        val restPatterns = listOf(
            Regex("""(?:fetch|axios\.get|axios\.post|http\.get|http\.post)\s*\(\s*['"`](\/api\/[^'"`\s\)]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""(?:\$\.ajax|XMLHttpRequest)[^'"]*url['":\s]+['"`](\/[^'"`\s,\)]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""url\s*:\s*['"`](\/api\/[^'"`\s,\)]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""['"`](\/api\/v\d+\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/rest\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/wp-json\/[^'"`\s]+)['"`]"""),  // WordPress REST
            Regex("""['"`](\/ghost\/api\/[^'"`\s]+)['"`]"""),  // Ghost CMS
            Regex("""['"`](\/admin\/api\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/content\/api\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/cms\/api\/[^'"`\s]+)['"`]""")
        )
        restPatterns.forEach { pattern ->
            pattern.findAll(scripts).forEach { match ->
                val ep = match.groupValues[1]
                if (ep.length > 3 && !ep.contains("//")) {
                    endpoints.add(ep)
                    detectedTypes.add("REST")
                }
            }
        }

        // --- GraphQL detection ---
        val graphqlIndicators = listOf("graphql", "gql`", " query {", " mutation {", " subscription {", "ApolloClient", "urql")
        if (graphqlIndicators.any { scripts.contains(it, ignoreCase = true) }) {
            detectedTypes.add("GraphQL")
            listOf(
                Regex("""['"`](\/graphql[^'"`\s]*)['"`]"""),
                Regex("""['"](https?:\/\/[^'"`\s]+\/graphql[^'"`\s]*)['"]""")
            ).forEach { p ->
                p.findAll(allText).forEach { m -> endpoints.add(m.groupValues[1]) }
            }
            // Look for Apollo/GraphQL endpoint config
            Regex("""uri\s*:\s*['"`]([^'"`\s]+)['"`]""").findAll(scripts).forEach { m ->
                if (m.groupValues[1].length > 3) endpoints.add(m.groupValues[1])
            }
        }

        // --- WebSocket endpoint detection ---
        val wsPattern = Regex("""new WebSocket\s*\(\s*['"`](wss?:\/\/[^'"`\s]+)['"`]""")
        wsPattern.findAll(scripts).forEach { match ->
            endpoints.add(match.groupValues[1])
            detectedTypes.add("WebSocket")
        }

        // --- Strapi CMS ---
        if (allText.contains("strapi", ignoreCase = true)) {
            detectedTypes.add("Strapi")
            Regex("""['"`](\/api\/[a-z-]+(?:\?[^'"`\s]*)?)['"`]""").findAll(scripts)
                .forEach { m -> endpoints.add(m.groupValues[1]) }
        }

        // --- Directus CMS ---
        if (allText.contains("directus", ignoreCase = true)) {
            detectedTypes.add("Directus")
            Regex("""['"`](\/items\/[^'"`\s]+)['"`]""").findAll(scripts)
                .forEach { m -> endpoints.add(m.groupValues[1]) }
        }

        // --- JSON data endpoints embedded in HTML ---
        listOf(
            Regex("""['"`](https?:\/\/[^'"`\s]+\.json[^'"`\s]*)['"`]"""),
            Regex("""['"`](\/[^'"`\s]+\.json[^'"`\s]*)['"`]"""),
            Regex("""data-src=['"`](https?:\/\/[^'"`\s]+)['"`]""")
        ).forEach { p ->
            p.findAll(allText).forEach { m ->
                val ep = m.groupValues[1]
                if (ep.contains("json") || ep.contains("api")) {
                    endpoints.add(ep)
                    detectedTypes.add("REST")
                }
            }
        }

        // --- Data attributes ---
        document.select("[data-api], [data-url], [data-endpoint], [data-src-url], [data-ajax-url]").forEach { el ->
            listOf("data-api", "data-url", "data-endpoint", "data-src-url", "data-ajax-url").forEach { attr ->
                el.attr(attr).takeIf { it.isNotEmpty() && it.startsWith("/") }?.let {
                    endpoints.add(it)
                    detectedTypes.add("REST")
                }
            }
        }

        // --- Link tags with API/JSON type ---
        document.select("link[type='application/json'], link[type='application/ld+json']").forEach { el ->
            el.attr("href").takeIf { it.isNotEmpty() }?.let { endpoints.add(it) }
        }

        // Determine primary API type
        apiType = when {
            "GraphQL" in detectedTypes -> "GraphQL"
            "WebSocket" in detectedTypes -> "WebSocket"
            "Strapi" in detectedTypes -> "Strapi"
            "Directus" in detectedTypes -> "Directus"
            "REST" in detectedTypes -> "REST"
            else -> null
        }

        return APIAnalysisResult(
            hasAPI = endpoints.isNotEmpty(),
            endpoints = endpoints.distinct().take(20),  // cap to avoid noise
            type = apiType
        )
    }

    /**
     * Detect CMS / site platform from HTML content and response headers.
     */
    fun detectCMS(html: String, headers: Map<String, String> = emptyMap()): String {
        val allContent = html + headers.values.joinToString(" ")
        for ((cms, signals) in CMS_PATTERNS) {
            if (signals.any { allContent.contains(it, ignoreCase = true) }) return cms
        }
        // Framework-level JS detection
        return when {
            html.contains("__NEXT_DATA__") || html.contains("/_next/") -> "Next.js"
            html.contains("__NUXT__") || html.contains("_nuxt/") -> "Nuxt.js"
            html.contains("data-reactroot") || html.contains("_ReactDOM") -> "React SPA"
            html.contains("ng-version") || html.contains("ng-app") -> "Angular"
            html.contains("data-v-") && html.contains("__vue_") -> "Vue.js"
            html.contains("sveltekit") || html.contains("__sveltekit") -> "SvelteKit"
            html.contains("astro-island") -> "Astro"
            html.contains("window.SolidJS") || html.contains("solid-js") -> "SolidJS"
            html.contains("_app.js") && html.contains("gatsby") -> "Gatsby"
            else -> "Unknown"
        }
    }
    
    /**
     * Navigation Structure Analysis
     */
    private fun analyzeNavigation(document: Document): NavigationStructure {
        val menuItems = mutableListOf<NavigationItem>()
        
        // Find main navigation
        val nav = document.select("nav, .navigation, #nav, .menu, #menu").first()
        
        nav?.select("a")?.forEach { link ->
            menuItems.add(NavigationItem(
                text = link.text(),
                url = link.attr("href"),
                hasSubmenu = link.parent()?.select("ul, .submenu, .dropdown")?.isNotEmpty() == true
            ))
        }
        
        // Find categories
        val categories = document.select(".categories a, .category-list a, nav.categories a")
            .map { it.text() to it.attr("href") }
            .filter { it.first.isNotEmpty() }
        
        return NavigationStructure(
            mainMenu = menuItems,
            categories = categories.map { NavigationItem(it.first, it.second, false) }
        )
    }
    
    /**
     * Determine optimal scraping strategy — now includes TAB_CRAWL for no-search sites.
     */
    private fun determineScrapingStrategy(document: Document, patterns: List<DetectedPattern>): ScrapingStrategy {
        val requiresJS = detectJavaScriptRequirement(document)
        val hasAPI = patterns.any { it.type == PatternType.API_ENDPOINT }
        val hasInfiniteScroll = patterns.any { it.type == PatternType.INFINITE_SCROLL }
        val hasSearchForm = patterns.any { it.type == PatternType.SEARCH_FORM }
        val hasNavTabs = document.select(
            "nav a, .nav a, ul.tabs a, [role='tablist'] a, .categories a, .menu a"
        ).size >= 3

        return when {
            hasAPI                         -> ScrapingStrategy.API_BASED
            hasInfiniteScroll              -> ScrapingStrategy.DYNAMIC_CONTENT
            requiresJS && hasAPI           -> ScrapingStrategy.HYBRID
            requiresJS                     -> ScrapingStrategy.HEADLESS_BROWSER
            !hasSearchForm && hasNavTabs   -> ScrapingStrategy.TAB_CRAWL   // no search → crawl tabs
            else                           -> ScrapingStrategy.HTML_PARSING
        }
    }
    
    private fun detectJavaScriptRequirement(document: Document): Boolean {
        val html = document.html()
        // 2026-era SPA and SSR framework indicators
        val indicators = listOf(
            // Angular (2+)
            "ng-app", "ng-version", "[_nghost", "[_ngcontent",
            // Next.js / React
            "__NEXT_DATA__", "/_next/static", "data-reactroot", "__react",
            // Nuxt.js / Vue
            "__NUXT__", "/_nuxt/", "data-v-", "__vue_",
            // SvelteKit
            "__sveltekit", "sveltekit", "svelte-",
            // Astro
            "astro-island", "astro:load", "/@astro/",
            // Remix
            "__remixContext", "/__remix-",
            // SolidJS
            "solid-js", "window._solid",
            // Gatsby
            "___gatsby", "gatsby-runtime",
            // Qwik
            "qwik-", "q:base",
            // Generic SSR/SPA injection
            "window.__INITIAL_STATE__", "window.__PRELOADED_STATE__",
            "window.__APP_STATE__", "window.__SERVER_DATA__",
            // JSON embedded state blobs
            "application/json\">{",
            // CloudFlare JS challenge
            "cf-chl-bypass", "__cf_chl_"
        )

        if (indicators.any { html.contains(it) }) return true

        // If body text is sparse but scripts are heavy → JS-rendered
        val bodyText = document.body()?.text() ?: ""
        val scriptCount = document.select("script[src]").size
        if (bodyText.length < 200 && scriptCount >= 3) return true

        // Check for noscript fallback warning (classic SPA pattern)
        val noscript = document.select("noscript").text()
        if (noscript.contains("JavaScript", ignoreCase = true) && bodyText.length < 500) return true

        return false
    }
    
    private fun detectAuthRequirement(document: Document): Boolean {
        val loginIndicators = listOf(
            "form[action*='login']", "form#login", ".login-form",
            "input[name='password']", "input[type='password']",
            ".auth-required", "#login-required"
        )
        
        return loginIndicators.any { document.select(it).isNotEmpty() }
    }
    
    // Helper functions
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }
    
    private fun extractBaseUrl(url: String): String {
        return try {
            val u = URL(url)
            "${u.protocol}://${u.host}"
        } catch (e: Exception) {
            url
        }
    }
    
    private fun calculateSelectorConfidence(elements: Elements, selector: String): Float {
        var confidence = 0.5f
        
        // ID selectors are very specific
        if (selector.startsWith("#")) confidence += 0.3f
        
        // Class selectors with meaningful names
        if (selector.contains("search") || selector.contains("result") || 
            selector.contains("item") || selector.contains("content")) {
            confidence += 0.2f
        }
        
        // Multiple matches reduce confidence slightly
        if (elements.size > 5) confidence -= 0.1f
        
        return confidence.coerceIn(0f, 1f)
    }
    
    private fun findSearchInput(document: Document): String? {
        SEARCH_INPUT_SELECTORS.forEach { selector ->
            if (document.select(selector).isNotEmpty()) {
                return selector
            }
        }
        return null
    }
    
    private fun findTitleSelector(document: Document, patterns: List<DetectedPattern>): String? {
        val candidates = listOf(
            "h1", "h2.title", ".title", "#title", "[class*='title']",
            ".name", ".item-title", ".video-title", ".movie-title"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun findDescriptionSelector(document: Document): String? {
        val candidates = listOf(
            ".description", "#description", ".desc", ".synopsis",
            ".summary", "[class*='description']", "p.info"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun findDateSelector(document: Document): String? {
        val candidates = listOf(
            ".date", ".time", ".timestamp", "[datetime]",
            ".posted", ".published", "[class*='date']"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun findRatingSelector(document: Document): String? {
        val candidates = listOf(
            ".rating", ".stars", ".score", "[data-rating]",
            ".imdb-rating", "[class*='rating']"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun extractUrlPattern(url: String): String {
        return when {
            url.contains(".m3u8") -> "HLS"
            url.contains(".mpd") -> "DASH"
            url.contains(".mp4") -> "MP4"
            url.contains(".webm") -> "WebM"
            else -> "Unknown"
        }
    }
    
    // Data classes for internal use
    data class SecurityAnalysisResult(
        val score: Float,
        val sslVersion: String?,
        val hasCSP: Boolean,
        val hasXFrameOptions: Boolean,
        val hasHSTS: Boolean,
        val cookieFlags: String
    )
    
    data class DOMAnalysisResult(
        val totalElements: Int,
        val uniqueTags: Int,
        val maxDepth: Int,
        val formCount: Int,
        val linkCount: Int,
        val scriptCount: Int,
        val iframeCount: Int,
        val imageCount: Int,
        val videoCount: Int,
        val contentAreas: List<ContentArea>
    )
    
    data class MediaAnalysisResult(
        val playerType: String?,
        val sourcePattern: String?,
        val thumbnailSelector: String?
    )
    
    data class APIAnalysisResult(
        val hasAPI: Boolean,
        val endpoints: List<String>,
        val type: String?
    )
}

@kotlinx.serialization.Serializable
data class ContentArea(
    val selector: String,
    val tagName: String,
    val childCount: Int,
    val textLength: Int,
    val confidence: Float
)

@kotlinx.serialization.Serializable
data class NavigationStructure(
    val mainMenu: List<NavigationItem>,
    val categories: List<NavigationItem>
)

@kotlinx.serialization.Serializable
data class NavigationItem(
    val text: String,
    val url: String,
    val hasSubmenu: Boolean
)
