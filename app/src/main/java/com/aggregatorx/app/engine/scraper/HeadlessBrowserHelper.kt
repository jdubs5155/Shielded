package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.engine.util.EngineUtils
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * AggravatedX Enhanced Headless Browser Helper
 * 
 * Features:
 * - Auto-click ad/popup close buttons
 * - Shadow DOM traversal
 * - Smart media extraction
 * - Cookie consent handling
 * - Anti-bot evasion
 * - Intelligent wait strategies
 * - Video URL extraction from dynamic pages
 */
object HeadlessBrowserHelper {

    private var browser: Browser? = null
    private var playwright: Playwright? = null
    
    // Comprehensive ad/popup close selectors
    private val AD_CLOSE_SELECTORS = listOf(
        // Common close buttons
        ".ad-close", ".skip-ad", ".ad_skip_btn", ".ad-overlay-close",
        ".popup-close", ".modal-close", ".close-button", ".close-btn",
        ".dismiss", ".btn-close", ".icon-close", ".close-icon",
        "[aria-label='Close']", "[aria-label='Dismiss']",
        "[data-dismiss='modal']", "[data-close]",
        
        // Ad-specific
        ".ad-skip", ".skip-button", ".skip-ad-button", ".skipButton",
        ".video-ad-close", ".preroll-close", ".ad-countdown-skip",
        "#skip-button", "#ad-close", "#close-ad",
        
        // Popup/modal closes
        ".popup-dismiss", ".overlay-close", ".lightbox-close",
        ".fancybox-close", ".magnificPopup-close",
        ".modal__close", ".dialog-close", ".notification-close",
        
        // Cookie consent
        ".cookie-accept", ".cookie-close", ".cookie-dismiss",
        "#cookie-accept", "#accept-cookies", ".accept-cookies",
        ".gdpr-accept", ".consent-accept", "[data-cookieconsent='accept']",
        ".cc-dismiss", ".cc-accept", "#onetrust-accept-btn-handler",
        
        // Generic patterns
        "[class*='ad'] .close", "[class*='popup'] .close",
        "[class*='modal'] .close", "[class*='overlay'] .close",
        "[class*='banner'] .close", "[id*='close']",
        "button[class*='close']", "a[class*='close']",
        ".btn.close", ".button.close"
    )
    
    // Video source patterns to extract
    private val VIDEO_SOURCE_PATTERNS = listOf(
        Regex("""(?:src|file|source|url|video_url|videoUrl|stream)['":\s]+['"]?(https?://[^'">\s]+\.(?:mp4|m3u8|webm|mpd)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
        Regex("""['"]?(https?://[^'">\s]*\.(?:mp4|m3u8|webm|mpd|flv|mkv)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
        Regex("""file:\s*['"](https?://[^'"]+)['"']""", RegexOption.IGNORE_CASE),
        Regex("""sources:\s*\[\s*\{\s*(?:file|src):\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""player\.src\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""hls\.loadSource\(['"]([^'"]+)['"]\)""", RegexOption.IGNORE_CASE),
        Regex("""dash\.initialize\([^,]+,\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
        Regex(""""embedUrl"\s*:\s*"([^"]+)"""")
    )

    /**
     * Get or create browser instance with anti-detection settings
     */
    fun getBrowser(): Browser {
        if (browser == null || !browser!!.isConnected) {
            playwright = Playwright.create()
            browser = playwright!!.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(listOf(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-web-security",
                        "--disable-features=IsolateOrigins,site-per-process",
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-accelerated-2d-canvas",
                        "--disable-gpu"
                    ))
            )
        }
        return browser!!
    }

    /**
     * Create a new page with anti-detection settings
     */
    private fun createPage(): Page {
        val page = getBrowser().newPage()
        
        // Set user agent + Chrome 132 full fingerprint headers
        page.setExtraHTTPHeaders(mapOf(
            "User-Agent" to EngineUtils.DEFAULT_USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "sec-ch-ua" to "\"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\", \"Not-A.Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "DNT" to "1"
        ))

        // Inject comprehensive Chrome 132 anti-detection fingerprint
        page.addInitScript("""
            // Remove webdriver flag (most bot checks look for this)
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});

            // Realistic plugin list matching Chrome 132 on Windows 11
            Object.defineProperty(navigator, 'plugins', {
                get: () => {
                    const p = Object.create(PluginArray.prototype);
                    const makePlugin = (name, desc, filename) => {
                        const pl = Object.create(Plugin.prototype);
                        Object.defineProperty(pl, 'name', { get: () => name });
                        Object.defineProperty(pl, 'description', { get: () => desc });
                        Object.defineProperty(pl, 'filename', { get: () => filename });
                        Object.defineProperty(pl, 'length', { get: () => 1 });
                        return pl;
                    };
                    const plugins = [
                        makePlugin('Chrome PDF Plugin', 'Portable Document Format', 'internal-pdf-viewer'),
                        makePlugin('Chrome PDF Viewer', '', 'mhjfbmdgcfjbbpaeojofohoefgiehjai'),
                        makePlugin('Native Client', '', 'internal-nacl-plugin')
                    ];
                    Object.defineProperty(p, 'length', { get: () => plugins.length });
                    plugins.forEach((pl, i) => { p[i] = pl; p[pl.name] = pl; });
                    return p;
                }
            });

            // Realistic languages
            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });

            // Hardware concurrency (8-core machine)
            Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });

            // Device memory (8 GB)
            Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });

            // Platform
            Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });

            // Vendor
            Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.' });

            // Spoof WebGL renderer/vendor to match real GPU
            const getParameter = WebGLRenderingContext.prototype.getParameter;
            WebGLRenderingContext.prototype.getParameter = function(parameter) {
                if (parameter === 37446) return 'Intel(R) Iris(R) Xe Graphics';
                if (parameter === 37445) return 'Google Inc. (Intel)';
                return getParameter.call(this, parameter);
            };

            // Remove Playwright / CDP automation artifacts
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
            delete window.__playwright;
            delete window.__pw_manual;
            delete window.__PW_inspect;

            // Resolve permissions query for 'notifications' realistically
            const originalQuery = window.navigator.permissions.query;
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications'
                    ? Promise.resolve({ state: Notification.permission })
                    : originalQuery(parameters)
            );
        """)
        
        return page
    }

    /**
     * Create a new page with full anti-detection settings.
     * Public wrapper so that other engines (e.g. CloudflareBypassEngine)
     * can obtain a stealth page without duplicating the fingerprint logic.
     */
    fun createAntiDetectionPage(): Page = createPage()

    /**
     * Fetch page content with comprehensive ad/popup handling
     */
    fun fetchPageContent(url: String, waitSelector: String? = null, timeout: Int = 15000): String? {
        val page = createPage()
        
        try {
            page.navigate(url, Page.NavigateOptions().setTimeout(timeout.toDouble()))
            page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(timeout.toDouble()))
            
            // Wait for main content if needed
            if (waitSelector != null) {
                try {
                    page.waitForSelector(waitSelector, Page.WaitForSelectorOptions().setTimeout((timeout / 2).toDouble()))
                } catch (_: Exception) {}
            }
            
            // Auto-click close buttons multiple times
            repeat(3) {
                autoClickCloseButtons(page)
                Thread.sleep(200)
            }
            
            return page.content()
        } catch (e: Exception) {
            return null
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    /**
     * Enhanced page fetch with shadow DOM support and aggressive ad removal
     * Also handles video pre-roll ads for proper content extraction
     */
    fun fetchPageContentWithShadowAndAdSkip(url: String, waitSelector: String? = null, timeout: Int = 15000): String? {
        val page = createPage()
        
        try {
            page.navigate(url, Page.NavigateOptions().setTimeout(timeout.toDouble()))
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(timeout.toDouble()))
            
            // Wait for content
            if (waitSelector != null) {
                try {
                    page.waitForSelector(waitSelector, Page.WaitForSelectorOptions().setTimeout((timeout / 2).toDouble()))
                } catch (_: Exception) {}
            }
            
            // Aggressive ad/popup removal - multiple passes
            repeat(5) { pass ->
                autoClickCloseButtons(page)
                handleShadowDOMCloses(page)
                removeAdElements(page)
                Thread.sleep(300)
            }
            
            // Handle video pre-roll ads (wait for skip and click)
            handleVideoAds(page, 10000) // Wait up to 10 seconds
            
            // Handle cookie consent
            handleCookieConsent(page)
            
            return page.content()
        } catch (e: Exception) {
            return null
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    /**
     * Auto-click all close buttons found on page
     */
    private fun autoClickCloseButtons(page: Page) {
        for (selector in AD_CLOSE_SELECTORS) {
            try {
                val elements = page.querySelectorAll(selector)
                for (element in elements) {
                    try {
                        if (element.isVisible) {
                            element.click(com.microsoft.playwright.ElementHandle.ClickOptions().setTimeout(500.0))
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Handle close buttons inside shadow DOM
     */
    private fun handleShadowDOMCloses(page: Page) {
        try {
            page.evaluate("""
                const shadowCloseSelectors = [
                    '.ad-close', '.skip-ad', '.close-button', '.popup-close', 
                    '.modal-close', '.dismiss', '.btn-close', '[aria-label="Close"]'
                ];
                
                function findAndClickInShadow(root) {
                    const elements = root.querySelectorAll('*');
                    elements.forEach(el => {
                        if (el.shadowRoot) {
                            shadowCloseSelectors.forEach(selector => {
                                const closeBtn = el.shadowRoot.querySelector(selector);
                                if (closeBtn) {
                                    try { closeBtn.click(); } catch(e) {}
                                }
                            });
                            findAndClickInShadow(el.shadowRoot);
                        }
                    });
                }
                
                findAndClickInShadow(document);
            """)
        } catch (_: Exception) {}
    }

    /**
     * Remove ad elements from DOM
     */
    private fun removeAdElements(page: Page) {
        try {
            page.evaluate("""
                const adSelectors = [
                    '[class*="ad-"]', '[class*="ads-"]', '[class*="advertisement"]',
                    '[id*="ad-"]', '[id*="ads-"]', '[id*="advertisement"]',
                    '.ad', '.ads', '.advert', '.advertisement',
                    '.popup', '.modal:not(.video-modal)', '.overlay:not(.video-overlay)',
                    'iframe[src*="doubleclick"]', 'iframe[src*="googlesyndication"]',
                    'iframe[src*="adservice"]', '[class*="popup"]',
                    '.cookie-banner', '.gdpr-banner', '.consent-banner'
                ];
                
                adSelectors.forEach(selector => {
                    document.querySelectorAll(selector).forEach(el => {
                        // Don't remove if it contains video
                        if (!el.querySelector('video') && !el.matches('video')) {
                            el.style.display = 'none';
                        }
                    });
                });
            """)
        } catch (_: Exception) {}
    }

    /**
     * Handle cookie consent dialogs
     */
    private fun handleCookieConsent(page: Page) {
        val cookieAcceptSelectors = listOf(
            "#onetrust-accept-btn-handler",
            ".cookie-accept", "#accept-cookies", ".accept-cookies",
            ".gdpr-accept", ".consent-accept", "[data-cookieconsent='accept']",
            ".cc-dismiss", ".cc-accept", "#cookie-accept",
            "button[aria-label*='accept']", "button[aria-label*='Accept']"
        )
        
        for (selector in cookieAcceptSelectors) {
            try {
                val element = page.querySelector(selector)
                if (element != null && element.isVisible) {
                    element.click()
                    break
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Extract video URLs from a page (handles dynamic content)
     * Returns list of video URL strings for use by VideoExtractorEngine
     * Enhanced to skip video ads and filter out ad-related URLs
     */
    fun extractVideoUrls(url: String, timeout: Int = 20000): List<String> {
        val page = createPage()
        val videoUrls = mutableListOf<String>()
        
        try {
            // Intercept network requests for video files
            page.onRequest { request ->
                val requestUrl = request.url()
                if (isVideoUrl(requestUrl)) {
                    videoUrls.add(requestUrl)
                }
            }
            
            page.navigate(url, Page.NavigateOptions().setTimeout(timeout.toDouble()))
            page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(timeout.toDouble()))
            
            // Handle popup ads first - multiple passes
            repeat(3) {
                autoClickCloseButtons(page)
                Thread.sleep(300)
            }
            
            // Look for play buttons and click them to start the player
            clickPlayButtons(page)
            Thread.sleep(1000)
            
            // CRITICAL: Handle video ads - wait for skip button and click it
            // This handles pre-roll ads that play before the main content
            handleVideoAds(page, 15000) // Wait up to 15 seconds for ad skip
            
            // After skipping ads, wait a moment for main content to load
            Thread.sleep(1500)
            
            // Remove ad elements from DOM
            removeAdElements(page)
            
            // Extract from HTML
            val htmlUrls = extractVideoUrlsFromHtml(page)
            videoUrls.addAll(htmlUrls)
            
            // Extract from scripts
            val scriptUrls = extractVideoUrlsFromScripts(page)
            videoUrls.addAll(scriptUrls)
            
            // Extract from video elements
            val elementUrls = extractFromVideoElements(page)
            videoUrls.addAll(elementUrls)
            
            // Filter out ad URLs and sort by quality + content likelihood
            return videoUrls.distinct()
                .filter { !isAdUrl(it) }
                .sortedWith(compareBy(
                    // Primary sort: prefer likely main content
                    { if (isLikelyMainContent(it)) 0 else 1 },
                    // Secondary sort: prefer higher quality
                    { -getQualityScore(detectQualityFromUrl(it)) }
                ))
        } catch (e: Exception) {
            return videoUrls.filter { !isAdUrl(it) }
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    /**
     * Extract video URLs as VideoUrlInfo objects (internal use)
     */
    fun extractVideoUrlsDetailed(url: String, timeout: Int = 20000): List<VideoUrlInfo> {
        return extractVideoUrls(url, timeout).map { videoUrl ->
            VideoUrlInfo(
                url = videoUrl,
                quality = detectQualityFromUrl(videoUrl),
                format = detectFormatFromUrl(videoUrl),
                source = "headless"
            )
        }
    }

    /**
     * Click play buttons to trigger video loading
     */
    private fun clickPlayButtons(page: Page) {
        val playSelectors = listOf(
            ".play-button", ".btn-play", ".video-play",
            "[class*='play']", "[aria-label*='play' i]",
            ".vjs-big-play-button", ".plyr__control--overlaid",
            ".jw-icon-display", "#player-play"
        )
        
        for (selector in playSelectors) {
            try {
                val element = page.querySelector(selector)
                if (element != null && element.isVisible) {
                    element.click()
                    Thread.sleep(500)
                }
            } catch (_: Exception) {}
        }
    }
    
    /**
     * Handle video ads with skip buttons - waits for skip to become available and clicks it
     * This is critical for sites that play pre-roll video ads before main content
     */
    private fun handleVideoAds(page: Page, maxWaitMs: Int = 15000) {
        val skipButtonSelectors = listOf(
            // YouTube-style skip
            ".ytp-ad-skip-button", ".ytp-ad-skip-button-modern", ".ytp-skip-ad-button",
            "[class*='skip-ad']", "[class*='skip-button']", "[class*='skipButton']",
            
            // Generic video ad skip buttons
            ".skip-ad", ".skip-ad-button", ".skipAd", ".skip-advertisement",
            ".ad-skip", ".ad-skip-button", ".adSkipButton", ".skip-btn",
            "#skip-button", "#skip-ad", "#skipAd", "#ad-skip-button",
            
            // Video.js style
            ".vjs-ad-skip", ".vjs-skip-button", ".ima-skip-button",
            
            // JW Player style  
            ".jw-skip", ".jw-skipAd", ".jwplayer .skip",
            
            // Common patterns
            "button[class*='skip']", "div[class*='skip']", "span[class*='skip']",
            "[aria-label*='skip' i]", "[aria-label*='Skip' i]",
            "[title*='skip' i]", "[title*='Skip' i]",
            
            // Countdown-based skip (click when visible)
            ".ad-countdown", ".skip-countdown", ".ad-skip-countdown",
            
            // Close ad overlay buttons
            ".ad-close-button", ".close-ad", ".ad-overlay-close",
            ".video-ad-close", ".preroll-skip", ".preroll-close"
        )
        
        val startTime = System.currentTimeMillis()
        var adSkipped = false
        
        // Keep trying to skip ads for up to maxWaitMs
        while (System.currentTimeMillis() - startTime < maxWaitMs && !adSkipped) {
            // Try each skip selector
            for (selector in skipButtonSelectors) {
                try {
                    val element = page.querySelector(selector)
                    if (element != null && element.isVisible) {
                        element.click(com.microsoft.playwright.ElementHandle.ClickOptions().setTimeout(500.0))
                        Thread.sleep(300)
                        adSkipped = true
                        break
                    }
                } catch (_: Exception) {}
            }
            
            // Also try JavaScript-based skip
            if (!adSkipped) {
                try {
                    page.evaluate("""
                        () => {
                            // Find and click any skip-like button
                            const skipPatterns = ['skip', 'Skip', 'SKIP', 'close', 'dismiss'];
                            const buttons = document.querySelectorAll('button, div[role="button"], a, span');
                            for (const btn of buttons) {
                                const text = btn.innerText || btn.textContent || '';
                                const className = btn.className || '';
                                for (const pattern of skipPatterns) {
                                    if ((text.includes(pattern) || className.toLowerCase().includes(pattern.toLowerCase())) 
                                        && btn.offsetParent !== null) {
                                        btn.click();
                                        return true;
                                    }
                                }
                            }
                            return false;
                        }
                    """)
                } catch (_: Exception) {}
            }
            
            // Check if ad is playing and wait a bit
            val adPlaying = isAdCurrentlyPlaying(page)
            if (adPlaying && !adSkipped) {
                Thread.sleep(1000) // Wait 1 second before trying again
            } else if (!adPlaying) {
                // No ad playing, we can exit
                break
            }
        }
    }
    
    /**
     * Check if a video ad is currently playing
     */
    private fun isAdCurrentlyPlaying(page: Page): Boolean {
        return try {
            val result = page.evaluate("""
                () => {
                    // Check for common ad indicators
                    const adIndicators = [
                        '.ad-playing', '.ad-showing', '.ytp-ad-player-overlay',
                        '[class*="ad-container"]', '[class*="ad-player"]',
                        '.ima-ad-container', '.video-ads', '.preroll',
                        '[data-ad-playing="true"]'
                    ];
                    
                    for (const selector of adIndicators) {
                        const el = document.querySelector(selector);
                        if (el && el.offsetParent !== null) return true;
                    }
                    
                    // Check video element for ad-related attributes
                    const videos = document.querySelectorAll('video');
                    for (const v of videos) {
                        const src = v.src || v.currentSrc || '';
                        if (src.includes('ad') || src.includes('preroll') || 
                            src.includes('doubleclick') || src.includes('googlesyndication')) {
                            if (!v.paused && !v.ended) return true;
                        }
                    }
                    
                    return false;
                }
            """)
            result == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extract video URLs from HTML content
     */
    private fun extractVideoUrlsFromHtml(page: Page): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            val html = page.content()
            
            for (pattern in VIDEO_SOURCE_PATTERNS) {
                pattern.findAll(html).forEach { match ->
                    val url = match.groupValues.getOrNull(1) ?: match.value
                    if (isVideoUrl(url)) {
                        urls.add(url.replace("\\", "").trim('"', '\''))
                    }
                }
            }
        } catch (_: Exception) {}
        
        return urls
    }

    /**
     * Extract video URLs from JavaScript
     */
    private fun extractVideoUrlsFromScripts(page: Page): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            val result = page.evaluate("""
                () => {
                    const urls = [];
                    
                    // Check global variables
                    if (typeof videoUrl !== 'undefined') urls.push(videoUrl);
                    if (typeof streamUrl !== 'undefined') urls.push(streamUrl);
                    if (typeof hlsUrl !== 'undefined') urls.push(hlsUrl);
                    if (typeof source !== 'undefined' && typeof source === 'string') urls.push(source);
                    
                    // Check player instances
                    if (typeof jwplayer !== 'undefined') {
                        try {
                            const playlist = jwplayer().getPlaylist();
                            if (playlist && playlist[0] && playlist[0].file) {
                                urls.push(playlist[0].file);
                            }
                        } catch(e) {}
                    }
                    
                    if (typeof Hls !== 'undefined') {
                        document.querySelectorAll('video').forEach(v => {
                            if (v.src) urls.push(v.src);
                        });
                    }
                    
                    // Check data attributes
                    document.querySelectorAll('[data-video-url], [data-src], [data-file]').forEach(el => {
                        ['data-video-url', 'data-src', 'data-file', 'data-hls', 'data-dash'].forEach(attr => {
                            const val = el.getAttribute(attr);
                            if (val && (val.includes('.mp4') || val.includes('.m3u8') || val.includes('.webm'))) {
                                urls.push(val);
                            }
                        });
                    });
                    
                    return urls.filter(u => u && typeof u === 'string');
                }
            """)
            
            if (result is List<*>) {
                result.filterIsInstance<String>().forEach { url ->
                    if (isVideoUrl(url)) {
                        urls.add(url)
                    }
                }
            }
        } catch (_: Exception) {}
        
        return urls
    }

    /**
     * Extract from video elements
     */
    private fun extractFromVideoElements(page: Page): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            val videos = page.querySelectorAll("video, video source")
            for (video in videos) {
                val src = video.getAttribute("src") ?: video.getAttribute("data-src")
                if (src != null && isVideoUrl(src)) {
                    urls.add(src)
                }
            }
        } catch (_: Exception) {}
        
        return urls
    }

    /**
     * Check if URL is a video URL (excluding ad networks)
     */
    private fun isVideoUrl(url: String): Boolean {
        val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mpd", ".flv", ".mkv", ".ts", ".mov")
        val hasVideoExtension = videoExtensions.any { url.lowercase().contains(it) }
        
        // Must have video extension AND not be from ad network
        return hasVideoExtension && !isAdUrl(url)
    }
    
    /**
     * Check if URL is from an ad network - filter these out from video results
     */
    private fun isAdUrl(url: String): Boolean {
        val adPatterns = listOf(
            // Google ad networks
            "doubleclick", "googlesyndication", "googleadservices", "googleads",
            "google.com/pagead", "googlevideo.com/videogoodput", "google.com/ads",
            
            // Major ad networks
            "adsense", "adservice", "adserver", "adtech", "adnxs", "adzerk",
            "advertising.com", "openx.net", "pubmatic", "rubiconproject",
            "taboola", "outbrain", "criteo", "moatads", "amazon-adsystem",
            
            // Video ad specific
            "imasdk", "ima3", "/ads/", "/ad/", "/adv/", "/advertisement/",
            "preroll", "midroll", "postroll", "/vast/", "/vpaid/", "/vmap/",
            "spotx", "teads", "unruly", "tremor", "freewheel", "jwpltx",
            
            // Tracking and analytics (often serve ads)
            "scorecardresearch", "quantserve", "mixpanel", "segment.io",
            
            // Common ad URL patterns
            "ad.php", "ads.php", "advert", "/banner/", "popup",
            "sponsored", "promo.mp4", "commercial", 
            
            // Short ad indicators
            "15sec", "30sec", "15s.", "30s.", "_ad_", "-ad-", "_ad.", "-ad.",
            
            // CDN ad paths
            "/adcontent/", "/adserving/", "/adcreative/"
        )
        
        val urlLower = url.lowercase()
        return adPatterns.any { urlLower.contains(it) }
    }
    
    /**
     * Check if URL looks like main content (higher priority)
     */
    private fun isLikelyMainContent(url: String): Boolean {
        val contentIndicators = listOf(
            "episode", "movie", "film", "video", "watch", "play", "stream",
            "media", "content", "source", "master", "index.m3u8", "playlist.m3u8",
            "1080", "720", "480", "hd", "sd", "quality", "hls", "dash"
        )
        
        val urlLower = url.lowercase()
        return contentIndicators.any { urlLower.contains(it) } && !isAdUrl(url)
    }

    /**
     * Detect quality from URL
     */
    private fun detectQualityFromUrl(url: String): String {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("2160") || urlLower.contains("4k") -> "4K"
            urlLower.contains("1080") -> "1080p"
            urlLower.contains("720") -> "720p"
            urlLower.contains("480") -> "480p"
            urlLower.contains("360") -> "360p"
            urlLower.contains("hd") -> "HD"
            else -> "Unknown"
        }
    }

    /**
     * Detect format from URL
     */
    private fun detectFormatFromUrl(url: String): String {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains(".m3u8") -> "HLS"
            urlLower.contains(".mpd") -> "DASH"
            urlLower.contains(".mp4") -> "MP4"
            urlLower.contains(".webm") -> "WebM"
            urlLower.contains(".mkv") -> "MKV"
            urlLower.contains(".flv") -> "FLV"
            else -> "Unknown"
        }
    }

    /**
     * Get quality score for sorting
     */
    private fun getQualityScore(quality: String): Int {
        return when (quality.lowercase()) {
            "4k", "2160p" -> 100
            "1080p", "full hd" -> 90
            "720p", "hd" -> 70
            "480p" -> 50
            "360p" -> 30
            else -> 40
        }
    }

    /**
     * Close browser and cleanup
     */
    fun close() {
        try {
            browser?.close()
            browser = null
            playwright?.close()
            playwright = null
        } catch (_: Exception) {}
    }

    /**
     * Fetch page content by clicking through tabs/categories using headless browser.
     * Used for sites that load content via JS on tab-click and have no accessible search.
     *
     * Algorithm:
     * 1. Navigate to base URL
     * 2. Find all tab/nav links matching query keywords
     * 3. Click each relevant tab and collect rendered HTML
     * 4. Return merged HTML of all matching tab pages
     */
    fun fetchContentByClickingTabs(
        baseUrl: String,
        query: String,
        maxTabs: Int = 5,
        timeout: Int = 20000
    ): String? {
        val page = createPage()
        val allHtml = StringBuilder()

        try {
            page.navigate(baseUrl, Page.NavigateOptions().setTimeout(timeout.toDouble()))
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(timeout.toDouble()))

            // Dismiss ads/popups first
            repeat(3) { autoClickCloseButtons(page); Thread.sleep(300) }
            handleCookieConsent(page)

            // Collect current page HTML
            allHtml.append(page.content())

            // Find tab/nav links
            val tabSelectors = listOf(
                ".tabs a", ".tab a", "nav a", ".menu a", ".navbar a",
                ".categories a", ".genres a", ".nav-tabs a", ".category-list a",
                "[role='tab']", "[class*='tab'] a", "ul.menu > li > a",
                ".navigation a", ".sidebar a"
            )

            val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            val tabsVisited = mutableSetOf<String>()

            for (selector in tabSelectors) {
                try {
                    val tabLinks = page.querySelectorAll(selector)
                    val relevantTabs = tabLinks.filter { tab ->
                        try {
                            val text = tab.textContent()?.lowercase() ?: ""
                            val href = tab.getAttribute("href")?.lowercase() ?: ""
                            queryWords.any { w -> text.contains(w) || href.contains(w) }
                        } catch (_: Exception) { false }
                    }.take(maxTabs)

                    for (tab in relevantTabs) {
                        try {
                            val href = tab.getAttribute("href") ?: continue
                            if (href in tabsVisited || href.startsWith("#") || href.contains("javascript:")) continue
                            tabsVisited.add(href)

                            if (tabsVisited.size > maxTabs) break

                            // Click tab and wait for content to update
                            tab.click(com.microsoft.playwright.ElementHandle.ClickOptions().setTimeout(3000.0))
                            Thread.sleep(1000)
                            page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(5000.0))
                            repeat(2) { autoClickCloseButtons(page); Thread.sleep(200) }
                            allHtml.append("\n<!-- TAB: $href -->\n")
                            allHtml.append(page.content())
                        } catch (_: Exception) {}
                    }

                    if (tabsVisited.size >= maxTabs) break
                } catch (_: Exception) {}
            }

            return allHtml.toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            return allHtml.toString().takeIf { it.isNotBlank() }
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    /**
     * Find and submit the search form on a page via headless browser.
     * Works on sites where the search form requires JS to function.
     * Returns the rendered HTML of the search results page.
     */
    fun searchViaHeadlessForm(
        baseUrl: String,
        query: String,
        timeout: Int = 20000
    ): String? {
        val page = createPage()

        try {
            page.navigate(baseUrl, Page.NavigateOptions().setTimeout(timeout.toDouble()))
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(timeout.toDouble()))

            repeat(3) { autoClickCloseButtons(page); Thread.sleep(200) }
            handleCookieConsent(page)

            // Try to find and fill search input
            val searchInputSelectors = listOf(
                "input[type='search']",
                "input[name*='search']", "input[name='q']", "input[name='query']",
                "input[name='s']", "input[name='keyword']", "input[autocomplete='off']",
                "input[placeholder*='search' i]", "input[placeholder*='Search' i]",
                "#search-input", ".search-input input", ".search-bar input",
                "form input[type='text']"
            )

            for (selector in searchInputSelectors) {
                try {
                    val input = page.querySelector(selector) ?: continue
                    if (!input.isVisible) continue

                    input.click()
                    input.fill(query)
                    Thread.sleep(300)

                    // Submit: try Enter key, then look for submit button
                    try {
                        input.press("Enter")
                    } catch (_: Exception) {
                        val submitSelectors = listOf(
                            "button[type='submit']", "input[type='submit']",
                            ".search-button", ".search-btn", ".btn-search",
                            "[class*='search'][class*='btn']", "[class*='search'][class*='button']"
                        )
                        for (btnSel in submitSelectors) {
                            try {
                                val btn = page.querySelector(btnSel)
                                if (btn != null && btn.isVisible) {
                                    btn.click()
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout((timeout / 2).toDouble()))
                    Thread.sleep(1000)
                    repeat(3) { autoClickCloseButtons(page); Thread.sleep(200) }

                    val html = page.content()
                    if (html.isNotBlank()) return html
                    break
                } catch (_: Exception) { continue }
            }

            return null
        } catch (e: Exception) {
            return null
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    /**
     * Intercept network requests to discover hidden search/data API endpoints.
     * Navigates to the page, performs a search if possible, and captures all
     * JSON/XHR requests made. Returns discovered API endpoint URLs.
     */
    fun discoverSearchAPIEndpoints(
        baseUrl: String,
        sampleQuery: String = "test",
        timeout: Int = 25000
    ): List<String> {
        val page = createPage()
        val discoveredUrls = mutableListOf<String>()

        try {
            // Intercept all network requests
            page.onRequest { request ->
                val url = request.url()
                val resourceType = request.resourceType()
                // Capture XHR/fetch calls that look like data/search APIs
                if (resourceType in listOf("xhr", "fetch") || url.contains("/api/") ||
                    url.contains("/ajax/") || url.contains("/json") ||
                    url.contains("search") || url.contains(".json")
                ) {
                    if (!isAdUrl(url)) discoveredUrls.add(url)
                }
            }

            page.navigate(baseUrl, Page.NavigateOptions().setTimeout(timeout.toDouble()))
            page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(timeout.toDouble()))

            repeat(2) { autoClickCloseButtons(page); Thread.sleep(300) }
            handleCookieConsent(page)

            // Try to trigger a search to capture search API calls
            val searchInputSelectors = listOf(
                "input[type='search']", "input[name='q']", "input[name='query']",
                "input[name='s']", "input[placeholder*='search' i]"
            )
            for (selector in searchInputSelectors) {
                try {
                    val input = page.querySelector(selector) ?: continue
                    if (!input.isVisible) continue
                    input.fill(sampleQuery)
                    input.press("Enter")
                    page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(5000.0))
                    break
                } catch (_: Exception) {}
            }

            Thread.sleep(2000)
        } catch (_: Exception) {
        } finally {
            try { page.close() } catch (_: Exception) {}
        }

        return discoveredUrls
            .distinct()
            .filter { it.contains(sampleQuery) || it.contains("search") || it.contains("/api/") }
            .take(10)
    }
}

/**
 * Video URL information
 */
data class VideoUrlInfo(
    val url: String,
    val quality: String,
    val format: String,
    val source: String = "unknown",
    val fileSize: Long? = null
)
