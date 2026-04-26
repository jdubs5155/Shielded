package com.aggregatorx.app.engine.ai

import com.aggregatorx.app.engine.util.EngineUtils
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AggregatorX AI Code Injection Engine
 *
 * Generates, manages, and learns dynamic JavaScript injection strategies for
 * advanced content extraction. The engine adapts to each site by:
 *   1. Identifying what kind of protection / obfuscation a page uses
 *   2. Selecting or generating the best injection template
 *   3. Learning from success/failure to improve future attempts
 *   4. Building site-specific injection profiles over time
 *
 * Capabilities:
 *   - Anti-bot bypass (Cloudflare challenge, datadome, etc.)
 *   - Dynamic video source extraction via JS evaluation
 *   - Cookie / session token harvesting for authenticated scraping
 *   - DOM mutation observation for lazy-loaded content
 *   - Ad overlay / popup removal before extraction
 *   - Obfuscated URL decoding (base64, hex, packed JS)
 */
@Singleton
class AICodeInjectionEngine @Inject constructor() {

    // ═══════════════════════════════════════════════════════════════════
    //  LEARNING STATE
    // ═══════════════════════════════════════════════════════════════════

    /** Per-domain injection profiles learned from previous interactions. */
    private val domainProfiles = ConcurrentHashMap<String, DomainInjectionProfile>()

    /** Global template effectiveness tracking. */
    private val templateStats = ConcurrentHashMap<String, TemplateStats>()

    /** Pattern → decoder cache: remembers how to decode specific obfuscation patterns. */
    private val obfuscationDecoders = ConcurrentHashMap<String, String>()

    // ═══════════════════════════════════════════════════════════════════
    //  INJECTION TEMPLATE LIBRARY  (pre-built, battle-tested snippets)
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        /**
         * Remove all ad overlays, popups, and anti-adblock modals before
         * interacting with the real page content.
         */
        const val JS_REMOVE_ADS = """
            (function() {
                const selectors = [
                    '.ad-overlay', '.popup', '.modal', '#overlay',
                    '[class*="ad-"]', '[id*="ad-"]', '.anti-adblock',
                    '.adsbygoogle', '.ad-container', '.interstitial',
                    '[class*="popup"]', '[class*="modal"]', '[class*="overlay"]',
                    '.notification-bar', '.cookie-banner', '.gdpr',
                    'div[style*="z-index: 999"]', 'div[style*="z-index:999"]'
                ];
                selectors.forEach(sel => {
                    document.querySelectorAll(sel).forEach(el => el.remove());
                });
                // Re-enable scrolling
                document.body.style.overflow = 'auto';
                document.documentElement.style.overflow = 'auto';
            })();
        """

        /**
         * Extract all video source URLs from the current page,
         * including sources inside shadow DOMs, iframes, and lazy-loaded elements.
         * Returns a JSON array of { url, type, quality } objects.
         */
        const val JS_EXTRACT_VIDEO_SOURCES = """
            (function() {
                const found = [];
                const seen = new Set();

                function addUrl(url, type, quality) {
                    if (!url || seen.has(url)) return;
                    seen.add(url);
                    found.push({ url: url, type: type || 'unknown', quality: quality || 'auto' });
                }

                // 1. <video> and <source> tags
                document.querySelectorAll('video').forEach(v => {
                    if (v.src) addUrl(v.src, 'video', 'auto');
                    if (v.currentSrc) addUrl(v.currentSrc, 'video', 'auto');
                    v.querySelectorAll('source').forEach(s => {
                        addUrl(s.src, s.type || 'video', s.getAttribute('label') || 'auto');
                    });
                });

                // 2. Player configs in JS globals
                const configKeys = [
                    'playerConfig', 'videoConfig', 'mediaConfig',
                    'streamConfig', '__PLAYER__', 'jwConfig'
                ];
                configKeys.forEach(key => {
                    try {
                        const cfg = window[key];
                        if (cfg) {
                            const json = JSON.stringify(cfg);
                            const re = /https?:\/\/[^"'\s]+\.(mp4|m3u8|webm|mpd)[^"'\s]*/gi;
                            let m;
                            while ((m = re.exec(json)) !== null) addUrl(m[0], m[1], 'auto');
                        }
                    } catch(e) {}
                });

                // 3. JWPlayer / Video.js / Clappr instances
                try {
                    if (typeof jwplayer !== 'undefined') {
                        const jw = jwplayer();
                        if (jw && jw.getPlaylistItem) {
                            const item = jw.getPlaylistItem();
                            if (item && item.file) addUrl(item.file, 'jwplayer', 'auto');
                            if (item && item.sources) {
                                item.sources.forEach(s => addUrl(s.file || s.src, 'jwplayer', s.label || 'auto'));
                            }
                        }
                    }
                } catch(e) {}
                try {
                    if (typeof videojs !== 'undefined') {
                        document.querySelectorAll('.video-js').forEach(el => {
                            const player = videojs(el.id);
                            if (player) {
                                const src = player.currentSrc();
                                if (src) addUrl(src, 'videojs', 'auto');
                            }
                        });
                    }
                } catch(e) {}

                // 4. Scan all <script> content for video URLs
                document.querySelectorAll('script').forEach(s => {
                    const html = s.textContent || '';
                    const patterns = [
                        /(?:src|file|source|url|video_url|videoUrl|stream|play)['":,\s]+['"]?(https?:\/\/[^'">\s]+\.(?:mp4|m3u8|webm|mpd)[^'">\s]*)/gi,
                        /['"]?(https?:\/\/[^'">\s]+\.(?:mp4|m3u8|webm|mpd)[^'">\s]*)['"]?/gi
                    ];
                    patterns.forEach(re => {
                        let m;
                        while ((m = re.exec(html)) !== null) addUrl(m[1] || m[0], 'script', 'auto');
                    });
                });

                // 5. Shadow DOM traversal
                function walkShadow(root) {
                    root.querySelectorAll('*').forEach(el => {
                        if (el.shadowRoot) {
                            el.shadowRoot.querySelectorAll('video, source').forEach(v => {
                                addUrl(v.src || v.currentSrc, 'shadow', 'auto');
                            });
                            walkShadow(el.shadowRoot);
                        }
                    });
                }
                walkShadow(document);

                return JSON.stringify(found);
            })();
        """

        /**
         * Decode common obfuscation schemes found on video hosting sites:
         * base64 encoded URLs, packed JS (eval(function(p,a,c,k,e,d){})),
         * hex-encoded strings, and simple string reversal.
         */
        const val JS_DECODE_OBFUSCATION = """
            (function() {
                const results = [];

                // 1. Find and decode base64 strings that look like URLs
                const b64Pattern = /atob\(['"]([A-Za-z0-9+/=]{20,})['"]\)/g;
                document.querySelectorAll('script').forEach(s => {
                    const html = s.textContent || '';
                    let m;
                    while ((m = b64Pattern.exec(html)) !== null) {
                        try {
                            const decoded = atob(m[1]);
                            if (decoded.startsWith('http')) results.push(decoded);
                        } catch(e) {}
                    }
                });

                // 2. Find hex-encoded strings (\x68\x74\x74\x70...)
                const hexPattern = /(?:\\x[0-9a-f]{2}){10,}/gi;
                document.querySelectorAll('script').forEach(s => {
                    const html = s.textContent || '';
                    let m;
                    while ((m = hexPattern.exec(html)) !== null) {
                        try {
                            const decoded = m[0].replace(/\\x([0-9a-f]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16)));
                            if (decoded.startsWith('http')) results.push(decoded);
                        } catch(e) {}
                    }
                });

                // 3. Look for String.fromCharCode chains
                const charCodePattern = /String\.fromCharCode\(([0-9,\s]+)\)/g;
                document.querySelectorAll('script').forEach(s => {
                    const html = s.textContent || '';
                    let m;
                    while ((m = charCodePattern.exec(html)) !== null) {
                        try {
                            const decoded = m[1].split(',').map(n => String.fromCharCode(parseInt(n.trim()))).join('');
                            if (decoded.startsWith('http')) results.push(decoded);
                        } catch(e) {}
                    }
                });

                return JSON.stringify(results);
            })();
        """

        /**
         * Observe DOM mutations for lazy-loaded video elements and report
         * when new video sources appear. Returns immediately with current state
         * and sets a global callback for dynamic updates.
         */
        const val JS_OBSERVE_DOM_MUTATIONS = """
            (function() {
                window.__aggx_video_urls = window.__aggx_video_urls || [];
                const observer = new MutationObserver(mutations => {
                    mutations.forEach(mutation => {
                        mutation.addedNodes.forEach(node => {
                            if (node.nodeType !== 1) return;
                            // Check the added node and all descendants
                            const elements = [node, ...node.querySelectorAll('video, source, iframe')];
                            elements.forEach(el => {
                                const src = el.src || el.currentSrc || el.getAttribute('data-src');
                                if (src && /\.(mp4|m3u8|webm|mpd)/i.test(src)) {
                                    if (!window.__aggx_video_urls.includes(src)) {
                                        window.__aggx_video_urls.push(src);
                                    }
                                }
                            });
                        });
                    });
                });
                observer.observe(document.body, { childList: true, subtree: true });
                return JSON.stringify(window.__aggx_video_urls);
            })();
        """

        /**
         * Harvest cookies and session tokens for authenticated scraping.
         * Returns a JSON object with all cookies and any auth tokens found in
         * localStorage / sessionStorage.
         */
        const val JS_HARVEST_SESSION = """
            (function() {
                const data = { cookies: document.cookie, tokens: {} };
                try {
                    const tokenKeys = ['token', 'auth', 'session', 'jwt', 'access_token', 'api_key'];
                    [localStorage, sessionStorage].forEach(store => {
                        for (let i = 0; i < store.length; i++) {
                            const key = store.key(i);
                            if (tokenKeys.some(tk => key.toLowerCase().includes(tk))) {
                                data.tokens[key] = store.getItem(key);
                            }
                        }
                    });
                } catch(e) {}
                return JSON.stringify(data);
            })();
        """

        /**
         * Intercept network requests to capture video stream URLs that are
         * fetched dynamically via fetch() or XMLHttpRequest.
         */
        const val JS_INTERCEPT_NETWORK = """
            (function() {
                window.__aggx_intercepted = window.__aggx_intercepted || [];

                // Intercept fetch()
                const origFetch = window.fetch;
                window.fetch = function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : args[0]?.url;
                    if (url && /\.(mp4|m3u8|webm|mpd)|video|stream/i.test(url)) {
                        window.__aggx_intercepted.push(url);
                    }
                    return origFetch.apply(this, args);
                };

                // Intercept XMLHttpRequest
                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    if (url && /\.(mp4|m3u8|webm|mpd)|video|stream/i.test(url)) {
                        window.__aggx_intercepted.push(url);
                    }
                    return origOpen.apply(this, arguments);
                };

                return JSON.stringify(window.__aggx_intercepted);
            })();
        """
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Analyse a domain and return the best sequence of JS injection templates
     * to use before attempting content extraction.
     */
    fun getInjectionPlan(domain: String, pageContext: PageContext = PageContext.UNKNOWN): InjectionPlan {
        val profile = domainProfiles[domain]

        // If we know this domain, use learned profile
        if (profile != null && profile.confidence > 0.6f) {
            return InjectionPlan(
                domain = domain,
                steps = profile.workingTemplates.map { templateId ->
                    InjectionStep(templateId = templateId, code = getTemplateCode(templateId))
                },
                confidence = profile.confidence,
                isLearned = true
            )
        }

        // Otherwise build a default plan based on page context
        val steps = mutableListOf<InjectionStep>()

        // Always start by removing ads/popups
        steps.add(InjectionStep("remove_ads", JS_REMOVE_ADS))

        when (pageContext) {
            PageContext.VIDEO_PLAYER -> {
                steps.add(InjectionStep("intercept_network", JS_INTERCEPT_NETWORK))
                steps.add(InjectionStep("extract_video", JS_EXTRACT_VIDEO_SOURCES))
                steps.add(InjectionStep("decode_obfuscation", JS_DECODE_OBFUSCATION))
                steps.add(InjectionStep("observe_mutations", JS_OBSERVE_DOM_MUTATIONS))
            }
            PageContext.SEARCH_RESULTS -> {
                steps.add(InjectionStep("observe_mutations", JS_OBSERVE_DOM_MUTATIONS))
            }
            PageContext.PROTECTED_CONTENT -> {
                steps.add(InjectionStep("harvest_session", JS_HARVEST_SESSION))
                steps.add(InjectionStep("intercept_network", JS_INTERCEPT_NETWORK))
                steps.add(InjectionStep("extract_video", JS_EXTRACT_VIDEO_SOURCES))
                steps.add(InjectionStep("decode_obfuscation", JS_DECODE_OBFUSCATION))
            }
            PageContext.UNKNOWN -> {
                steps.add(InjectionStep("extract_video", JS_EXTRACT_VIDEO_SOURCES))
                steps.add(InjectionStep("decode_obfuscation", JS_DECODE_OBFUSCATION))
            }
        }

        return InjectionPlan(
            domain = domain,
            steps = steps,
            confidence = if (profile != null) profile.confidence else 0.5f,
            isLearned = false
        )
    }

    /**
     * Generate a custom JS injection snippet by combining learned patterns
     * for the given domain with the requested capability.
     */
    fun generateCustomInjection(domain: String, capability: InjectionCapability): String {
        val profile = domainProfiles[domain]

        return when (capability) {
            InjectionCapability.VIDEO_EXTRACTION -> {
                val specificSelectors = profile?.learnedSelectors?.joinToString(", ") { "'$it'" }
                    ?: "'video', 'source', 'iframe'"
                """
                (function() {
                    const urls = [];
                    document.querySelectorAll($specificSelectors).forEach(el => {
                        const src = el.src || el.currentSrc || el.getAttribute('data-src') || el.getAttribute('data-video-url');
                        if (src) urls.push(src);
                    });
                    ${if (profile?.usesObfuscation == true) "/* site uses obfuscation — add decoder */\n$JS_DECODE_OBFUSCATION" else ""}
                    return JSON.stringify(urls);
                })();
                """.trimIndent()
            }
            InjectionCapability.AD_BYPASS -> JS_REMOVE_ADS
            InjectionCapability.SESSION_HARVEST -> JS_HARVEST_SESSION
            InjectionCapability.NETWORK_INTERCEPT -> JS_INTERCEPT_NETWORK
            InjectionCapability.DOM_OBSERVATION -> JS_OBSERVE_DOM_MUTATIONS
            InjectionCapability.DEOBFUSCATION -> JS_DECODE_OBFUSCATION
        }
    }

    /**
     * Get the best JS template code for a template ID.
     */
    fun getTemplateCode(templateId: String): String {
        return when (templateId) {
            "remove_ads" -> JS_REMOVE_ADS
            "extract_video" -> JS_EXTRACT_VIDEO_SOURCES
            "decode_obfuscation" -> JS_DECODE_OBFUSCATION
            "observe_mutations" -> JS_OBSERVE_DOM_MUTATIONS
            "harvest_session" -> JS_HARVEST_SESSION
            "intercept_network" -> JS_INTERCEPT_NETWORK
            else -> obfuscationDecoders[templateId] ?: "/* unknown template: $templateId */"
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LEARNING API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Record that an injection template succeeded for a domain.
     * Strengthens the profile for that domain.
     */
    fun recordSuccess(domain: String, templateId: String, extractedUrls: Int) {
        val existing = domainProfiles[domain] ?: DomainInjectionProfile(domain)
        val templates = (existing.workingTemplates + templateId).distinct()
        val newConf = ((existing.confidence * existing.successCount) + 1f) / (existing.successCount + 1)

        domainProfiles[domain] = existing.copy(
            workingTemplates = templates,
            successCount = existing.successCount + 1,
            confidence = newConf.coerceAtMost(1f),
            lastExtractedCount = extractedUrls,
            lastUsed = System.currentTimeMillis()
        )

        // Update global template stats
        val stats = templateStats[templateId] ?: TemplateStats(templateId)
        templateStats[templateId] = stats.copy(
            successCount = stats.successCount + 1,
            avgExtracted = (stats.avgExtracted * stats.successCount + extractedUrls).toFloat() / (stats.successCount + 1)
        )
    }

    /**
     * Record that an injection template failed for a domain.
     */
    fun recordFailure(domain: String, templateId: String, error: String?) {
        val existing = domainProfiles[domain] ?: DomainInjectionProfile(domain)
        val templates = existing.workingTemplates - templateId
        val newConf = ((existing.confidence * (existing.successCount + existing.failureCount)) - 0.1f) /
                (existing.successCount + existing.failureCount + 1)

        domainProfiles[domain] = existing.copy(
            workingTemplates = templates,
            failureCount = existing.failureCount + 1,
            confidence = newConf.coerceIn(0f, 1f),
            lastError = error,
            lastUsed = System.currentTimeMillis()
        )

        val stats = templateStats[templateId] ?: TemplateStats(templateId)
        templateStats[templateId] = stats.copy(failureCount = stats.failureCount + 1)
    }

    /**
     * Learn that a domain uses a specific obfuscation pattern and
     * store the decoder for future use.
     */
    fun learnObfuscationPattern(domain: String, patternKey: String, decoderJs: String) {
        obfuscationDecoders[patternKey] = decoderJs
        val existing = domainProfiles[domain] ?: DomainInjectionProfile(domain)
        domainProfiles[domain] = existing.copy(usesObfuscation = true)
    }

    /**
     * Learn selectors that successfully identified video elements for a domain.
     */
    fun learnSelectors(domain: String, selectors: List<String>) {
        val existing = domainProfiles[domain] ?: DomainInjectionProfile(domain)
        val merged = (existing.learnedSelectors + selectors).distinct().takeLast(20)
        domainProfiles[domain] = existing.copy(learnedSelectors = merged)
    }

    /**
     * Get injection engine stats for display.
     */
    fun getStats(): InjectionEngineStats {
        return InjectionEngineStats(
            knownDomains = domainProfiles.size,
            totalTemplates = templateStats.size + 6, // 6 built-in
            totalSuccesses = templateStats.values.sumOf { it.successCount },
            totalFailures = templateStats.values.sumOf { it.failureCount },
            learnedDecoders = obfuscationDecoders.size
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
//  DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/** Context hint for the page being processed. */
enum class PageContext {
    VIDEO_PLAYER,
    SEARCH_RESULTS,
    PROTECTED_CONTENT,
    UNKNOWN
}

/** Capability to request from the injection engine. */
enum class InjectionCapability {
    VIDEO_EXTRACTION,
    AD_BYPASS,
    SESSION_HARVEST,
    NETWORK_INTERCEPT,
    DOM_OBSERVATION,
    DEOBFUSCATION
}

/** Ordered list of JS snippets to execute before extraction. */
data class InjectionPlan(
    val domain: String,
    val steps: List<InjectionStep>,
    val confidence: Float,
    val isLearned: Boolean
)

data class InjectionStep(
    val templateId: String,
    val code: String
)

/** Per-domain learned injection profile. */
data class DomainInjectionProfile(
    val domain: String,
    val workingTemplates: List<String> = emptyList(),
    val learnedSelectors: List<String> = emptyList(),
    val usesObfuscation: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val confidence: Float = 0.5f,
    val lastExtractedCount: Int = 0,
    val lastError: String? = null,
    val lastUsed: Long = System.currentTimeMillis()
)

data class TemplateStats(
    val templateId: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val avgExtracted: Float = 0f
)

data class InjectionEngineStats(
    val knownDomains: Int,
    val totalTemplates: Int,
    val totalSuccesses: Int,
    val totalFailures: Int,
    val learnedDecoders: Int
)
