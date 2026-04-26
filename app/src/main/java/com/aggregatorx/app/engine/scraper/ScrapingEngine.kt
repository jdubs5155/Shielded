package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.database.ProviderDao
import com.aggregatorx.app.data.database.ScrapingConfigDao
import com.aggregatorx.app.data.database.SiteAnalysisDao
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.SmartContentClassifier
import com.aggregatorx.app.engine.analyzer.PageType
import com.aggregatorx.app.engine.analyzer.ContainerType
import com.aggregatorx.app.engine.analyzer.EndpointDiscoveryEngine
import com.aggregatorx.app.engine.ai.AIDecisionEngine
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.nlp.ProcessedQuery
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.aggregatorx.app.engine.util.EngineUtils
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Advanced Multi-Provider Scraping Engine
 * 
 * Features:
 * - Concurrent scraping across all enabled providers
 * - Smart navigation to bypass category pages
 * - Intelligent fallback mechanisms
 * - Result scoring and ranking
 * - Rate limiting and retry logic
 * - Pattern-based content extraction
 * - Error resilience with provider isolation
 * - Thumbnail extraction for video previews
 * - INTELLIGENT RESULT VALIDATION - detects category pages vs real results
 * - NEVER BREAKS LOOP - continues to all providers even on failures
 * - AI LEARNING INTEGRATION - learns from every scraping attempt
 * - NLP QUERY UNDERSTANDING - translates natural language descriptions into effective search terms
 * - SEMANTIC RESULT MATCHING - scores results by concept similarity, not just keywords
 */
@Singleton
class ScrapingEngine @Inject constructor(
    private val providerDao: ProviderDao,
    private val scrapingConfigDao: ScrapingConfigDao,
    private val siteAnalysisDao: SiteAnalysisDao,
    private val smartNavigationEngine: SmartNavigationEngine,
    private val smartContentClassifier: SmartContentClassifier,
    private val aiDecisionEngine: AIDecisionEngine,
    private val cloudflareBypassEngine: CloudflareBypassEngine,
    private val endpointDiscoveryEngine: EndpointDiscoveryEngine,
    private val nlpProcessor: NaturalLanguageQueryProcessor
) {
    // Current NLP-processed query for semantic scoring throughout the pipeline
    @Volatile
    private var currentProcessedQuery: ProcessedQuery? = null
    fun clearCache() {
        synchronized(resultCache) {
            resultCache.clear()
        }
    }
    
    // Provider health tracking
    private val providerHealthMap = ConcurrentHashMap<String, ProviderHealth>()
    
    // Rate limiting
    private val lastRequestTime = ConcurrentHashMap<String, Long>()
    
    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val DEFAULT_RETRY_COUNT = 3
        private const val DEFAULT_RETRY_DELAY = 800L
        private const val DEFAULT_RATE_LIMIT_MS = 50L
        private const val MAX_CONCURRENT_PROVIDERS = 20
        private val DEFAULT_USER_AGENT = EngineUtils.DEFAULT_USER_AGENT
        
        // Delegate to shared user agent pool
        private val USER_AGENTS = EngineUtils.USER_AGENTS
        
        // Patterns to identify category/navigation URLs
        private val CATEGORY_URL_PATTERNS = listOf(
            "/genre/", "/category/", "/browse/", "/filter/", "/tags/",
            "/type/", "/sort/", "/order/", "?genre=", "?category=",
            "?type=", "/all-", "/list/genre", "/movies/genre"
        )
        
        // Generic category names to filter out
        private val GENERIC_CATEGORY_NAMES = setOf(
            "action", "comedy", "drama", "horror", "thriller", "romance",
            "sci-fi", "documentary", "animation", "anime", "sports", "news",
            "music", "kids", "family", "adventure", "fantasy", "crime",
            "mystery", "western", "war", "history", "biography", "all movies",
            "all videos", "trending", "popular", "latest", "new releases",
            "top rated", "most viewed", "recommended"
        )
        
        // Patterns that indicate actual content URLs
        private val CONTENT_URL_PATTERNS = listOf(
            "/watch", "/video", "/movie/", "/episode/", "/play",
            "/stream", "/view", "/v/", "/e/", "-watch", "-online",
            "-full", "-hd", "-720p", "-1080p", "-episode-"
        )

        // Cache TTL: 10 minutes
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    /**
     * Search across all enabled providers
     * Returns a Flow that emits results as they come in from each provider
     *
     * RESILIENT DESIGN:
     * - GUARANTEES all enabled providers are scraped (never skips any)
     * - Never breaks the loop even if individual providers fail
     * - Sorts providers by success rate (best performers first)
     * - Emits results progressively as they complete
     * - Includes comprehensive error handling per-provider
     * - Auto-retries with fallback strategies including headless browser
     */
    // Cache entry wraps results with a timestamp for TTL-based eviction
    private data class CacheEntry(
        val results: List<ProviderSearchResults>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val resultCache = object : LinkedHashMap<String, CacheEntry>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 100
        }
    }

    var cacheResults: Boolean = true

    fun searchAllProviders(query: String, cache: Boolean = cacheResults): Flow<ProviderSearchResults> = flow {
        // ── NLP QUERY PROCESSING ────────────────────────────────────
        // Transform the raw query into optimised search terms using
        // natural language understanding. This enables descriptive queries
        // like "scared my cat and it jumped so high" to produce effective
        // searches like "cat jump scare", "scared cat reaction", etc.
        val processedQuery = nlpProcessor.processQuery(query)
        currentProcessedQuery = processedQuery

        // Check cache first (respects TTL)
        if (cache) {
            val cachedEntry = synchronized(resultCache) {
                resultCache[query]
            }
            if (cachedEntry != null && System.currentTimeMillis() - cachedEntry.timestamp < CACHE_TTL_MS) {
                cachedEntry.results.forEach { emit(it) }
                return@flow
            }
        }

        var enabledProviders = providerDao.getEnabledProvidersSync()
        if (enabledProviders.isEmpty()) {
            return@flow
        }

        // Sort providers by success rate and avg response time (most successful first)
        // But GUARANTEE all providers will be searched
        enabledProviders = enabledProviders.sortedWith(
            compareByDescending<Provider> { it.successRate }
                .thenBy { it.avgResponseTime }
                .thenByDescending { it.totalSearches }
        )
        
        // Track which providers we've processed to ensure none are missed
        val processedProviders = mutableSetOf<String>()

        // Create a semaphore for rate limiting concurrent requests
        val semaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)
        val results = mutableListOf<ProviderSearchResults>()

        // Search ALL providers concurrently - NEVER BREAK THE LOOP, NEVER SKIP ANY
        // Per-provider timeout prevents a single hung provider from blocking the entire search
        val perProviderTimeoutMs = 90_000L

        coroutineScope {
            val deferredResults = enabledProviders.map { provider ->
                async {
                    semaphore.withPermit {
                        processedProviders.add(provider.id)
                        // Wrap in comprehensive try-catch + timeout to ensure no single provider breaks the loop
                        try {
                            withTimeoutOrNull(perProviderTimeoutMs) {
                                safeSearchProvider(provider, query)
                            } ?: ProviderSearchResults(
                                provider = provider,
                                results = emptyList(),
                                searchTime = perProviderTimeoutMs,
                                success = false,
                                errorMessage = "Provider ${provider.name} timed out after ${perProviderTimeoutMs / 1000}s"
                            )
                        } catch (e: CancellationException) {
                            // Coroutine was cancelled (e.g. user navigated away) — still produce a result
                            // but re-throw so the parent scope knows
                            throw e
                        } catch (e: Exception) {
                            ProviderSearchResults(
                                provider = provider,
                                results = emptyList(),
                                searchTime = 0L,
                                success = false,
                                errorMessage = "Provider ${provider.name} error: ${e.message?.take(120)}"
                            )
                        }
                    }
                }
            }

            // Emit results as they complete - use individual try-catch for each
            // This ensures we get results from ALL providers
            deferredResults.forEachIndexed { index, deferred ->
                val provider = enabledProviders.getOrNull(index)
                try {
                    val result = deferred.await()
                    results.add(result)
                    emit(result)
                } catch (e: CancellationException) {
                    // Search was cancelled by user — emit what we have and stop cleanly
                    if (provider != null) {
                        val cancelledResult = ProviderSearchResults(
                            provider = provider,
                            results = emptyList(),
                            searchTime = 0L,
                            success = false,
                            errorMessage = "Search cancelled"
                        )
                        results.add(cancelledResult)
                        emit(cancelledResult)
                    }
                } catch (e: Exception) {
                    // Even if await fails somehow, don't break - create failed result for this provider
                    // This guarantees the UI knows about every provider
                    val failedResult = ProviderSearchResults(
                        provider = provider ?: enabledProviders.firstOrNull() ?: return@forEachIndexed,
                        results = emptyList(),
                        searchTime = 0L,
                        success = false,
                        errorMessage = "Unexpected error processing ${provider?.name}: ${e.message}"
                    )
                    results.add(failedResult)
                    emit(failedResult)
                }
            }
        }

        // Safety net: check if any enabled provider was missed (should never happen)
        val missedProviders = enabledProviders.filter { it.id !in processedProviders }
        for (missed in missedProviders) {
            val failedResult = ProviderSearchResults(
                provider = missed,
                results = emptyList(),
                searchTime = 0L,
                success = false,
                errorMessage = "Provider ${missed.name} was not processed (safety net)"
            )
            results.add(failedResult)
            emit(failedResult)
        }

        // Cache successful results
        if (cache && results.any { it.success }) {
            synchronized(resultCache) {
                resultCache[query] = CacheEntry(results)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Safe provider search that NEVER throws - always returns a result
     * ENHANCED WITH INTELLIGENT RESULT VALIDATION:
     * - Detects category pages vs real content results
     * - Filters out unusable results (just links to genres/categories)
     * - Continues to ALL providers even if some fail
     * - LEARNS FROM EVERY ATTEMPT for AI improvement
     */
    private suspend fun safeSearchProvider(provider: Provider, query: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        val domain = extractDomain(provider.baseUrl)
        
        // Even providers in "cooldown" still get searched via fallback methods
        // so NO provider is ever silently skipped
        val inCooldown = provider.failedSearches > 5 && 
            provider.failedSearches.toFloat() / maxOf(provider.totalSearches, 1).toFloat() > 0.7f
        
        return try {
            if (inCooldown) {
                // Skip the primary smart search but STILL try fallback methods
                // This gives struggling providers a lightweight retry path
                try {
                    val fallbackResult = tryFallbackScraping(
                        provider, query, startTime,
                        Exception("Provider in cooldown — trying fallback only")
                    )
                    if (fallbackResult.success && fallbackResult.results.isNotEmpty()) {
                        val validatedResults = validateAndFilterResults(fallbackResult.results, query)
                        if (validatedResults.isNotEmpty()) {
                            // Provider recovered via fallback — reset cooldown learning
                            aiDecisionEngine.learnRecovery(domain, "COOLDOWN", "FALLBACK_RECOVERY")
                            return fallbackResult.copy(results = validatedResults)
                        }
                    }
                    // Cooldown fallback also returned nothing — try NLP queries as last resort
                    val nlpCooldownResult = retryWithNlpQueries(provider, query, startTime)
                    nlpCooldownResult ?: fallbackResult
                } catch (e: Exception) {
                    ProviderSearchResults(
                        provider = provider,
                        results = emptyList(),
                        searchTime = System.currentTimeMillis() - startTime,
                        success = false,
                        errorMessage = "Provider in cooldown, fallback also failed: ${e.message?.take(80)}"
                    )
                }
            } else {
                // Get AI recommended strategy
                val recommendedStrategy = aiDecisionEngine.getAdaptiveStrategy(domain)
                
                // Try smart search first
                val result = searchProviderSmart(provider, query)
                
                // Validate the results are actual content, not category pages
                if (result.success && result.results.isNotEmpty()) {
                    val validatedResults = validateAndFilterResults(result.results, query)
                    if (validatedResults.isEmpty() && result.results.isNotEmpty()) {
                        // Results were all invalid (likely category pages)
                        // LEARN: category page detection failure
                        aiDecisionEngine.learnFromFailure(
                            domain = domain,
                            errorType = "CATEGORY_PAGE_RESULTS",
                            errorMessage = "Results were category/navigation pages",
                            strategy = ScrapingStrategy.HTML_PARSING,
                            selector = null,
                            url = provider.baseUrl
                        )
                        
                        // ── NLP RETRY: try NLP-generated queries before giving up ────
                        val nlpRetryResult = retryWithNlpQueries(provider, query, startTime)
                        if (nlpRetryResult != null) {
                            nlpRetryResult
                        } else {
                            result.copy(
                                results = emptyList(),
                                success = false,
                                errorMessage = "Results were category/navigation pages, not actual content"
                            )
                        }
                    } else {
                        // LEARN: successful scraping
                        aiDecisionEngine.learnFromSuccess(
                            domain = domain,
                            strategy = ScrapingStrategy.HTML_PARSING,
                            resultSelector = null,
                            titleSelector = null,
                            thumbnailSelector = null,
                            resultCount = validatedResults.size,
                            responseTime = System.currentTimeMillis() - startTime
                        )
                        
                        result.copy(results = validatedResults)
                    }
                } else {
                    // ── NLP RETRY: primary search returned nothing — try NLP queries ──
                    val nlpRetryResult = retryWithNlpQueries(provider, query, startTime)
                    if (nlpRetryResult != null) {
                        nlpRetryResult
                    } else {
                        // LEARN: no results failure
                        if (!result.success) {
                            aiDecisionEngine.learnFromFailure(
                                domain = domain,
                                errorType = "NO_RESULTS",
                                errorMessage = result.errorMessage,
                                strategy = ScrapingStrategy.HTML_PARSING,
                                selector = null,
                                url = provider.baseUrl
                            )
                        }
                        result
                    }
                }
            }
        } catch (e: CancellationException) {
            // Respect coroutine cancellation — re-throw so parent handles it
            throw e
        } catch (e: Exception) {
            // LEARN: exception failure
            try {
                aiDecisionEngine.learnFromFailure(
                    domain = domain,
                    errorType = "EXCEPTION",
                    errorMessage = e.message,
                    strategy = ScrapingStrategy.HTML_PARSING,
                    selector = null,
                    url = provider.baseUrl
                )
            } catch (_: Exception) { /* learning should never break the loop */ }
            
            // Primary search failed - try fallback (NEVER BREAK THE LOOP)
            try {
                val fallbackResult = tryFallbackScraping(provider, query, startTime, e)
                
                // Also validate fallback results
                if (fallbackResult.success && fallbackResult.results.isNotEmpty()) {
                    val validatedResults = validateAndFilterResults(fallbackResult.results, query)
                    if (validatedResults.isEmpty() && fallbackResult.results.isNotEmpty()) {
                        // Fallback returned category pages — try NLP queries before giving up
                        val nlpExceptionResult = retryWithNlpQueries(provider, query, startTime)
                        nlpExceptionResult ?: fallbackResult.copy(
                            results = emptyList(),
                            success = false,
                            errorMessage = "Fallback results were category/navigation pages"
                        )
                    } else {
                        // LEARN: successful fallback recovery
                        try {
                            aiDecisionEngine.learnRecovery(domain, "EXCEPTION", "FALLBACK_SUCCESS")
                            aiDecisionEngine.learnFromSuccess(
                                domain = domain,
                                strategy = ScrapingStrategy.HTML_PARSING,
                                resultSelector = null,
                                titleSelector = null,
                                thumbnailSelector = null,
                                resultCount = validatedResults.size,
                                responseTime = System.currentTimeMillis() - startTime
                            )
                        } catch (_: Exception) { /* learning should never break the loop */ }
                        
                        fallbackResult.copy(results = validatedResults)
                    }
                } else {
                    // Fallback search also returned nothing — NLP retry as last resort
                    val nlpFallbackResult = retryWithNlpQueries(provider, query, startTime)
                    nlpFallbackResult ?: fallbackResult
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (fallbackEx: Exception) {
                // All methods failed — final NLP attempt before giving up
                val nlpLastResort = try {
                    retryWithNlpQueries(provider, query, startTime)
                } catch (_: Exception) { null }

                nlpLastResort ?: ProviderSearchResults(
                    provider = provider,
                    results = emptyList(),
                    searchTime = System.currentTimeMillis() - startTime,
                    success = false,
                    errorMessage = "All search methods failed: ${e.message?.take(100)}"
                )
            }
        }
    }

    /**
     * Retry a provider search using NLP-generated query variants.
     *
     * When the user's raw query (e.g. "scared my cat and it jumped so high")
     * returned zero results, we re-search the same provider with the
     * semantically rewritten queries produced by [NaturalLanguageQueryProcessor]
     * (e.g. "cat jump scare", "scared cat reaction", "cat startled jumping").
     *
     * Returns a successful [ProviderSearchResults] if any NLP query variant
     * produced validated results, or null to let the caller fall through to
     * its existing fallback logic.
     */
    private suspend fun retryWithNlpQueries(
        provider: Provider,
        originalQuery: String,
        startTime: Long
    ): ProviderSearchResults? {
        val processed = currentProcessedQuery ?: return null
        if (processed.searchQueries.isEmpty()) return null

        // Only retry with NLP queries that differ from the original
        val nlpQueries = processed.searchQueries
            .filter { it.lowercase() != originalQuery.lowercase() }
            .take(4) // Limit retries to keep latency reasonable

        if (nlpQueries.isEmpty()) return null

        val allResults = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        for (nlpQuery in nlpQueries) {
            try {
                val result = searchProviderSmart(provider, nlpQuery)
                if (result.success && result.results.isNotEmpty()) {
                    val validated = validateAndFilterResults(result.results, originalQuery)
                    for (r in validated) {
                        if (r.url !in seenUrls) {
                            seenUrls.add(r.url)
                            allResults.add(r.copy(
                                relevanceScore = calculateRelevanceScore(
                                    r.title, originalQuery, r.description, r.url
                                )
                            ))
                        }
                    }
                    // If we already have a decent batch, stop early
                    if (allResults.size >= 15) break
                }
            } catch (_: CancellationException) {
                throw CancellationException("Cancelled during NLP retry")
            } catch (_: Exception) {
                continue
            }
        }

        return if (allResults.isNotEmpty()) {
            val domain = extractDomain(provider.baseUrl)
            try {
                aiDecisionEngine.learnRecovery(domain, "NO_RESULTS", "NLP_QUERY_RETRY")
                aiDecisionEngine.learnFromSuccess(
                    domain = domain,
                    strategy = ScrapingStrategy.HTML_PARSING,
                    resultSelector = null,
                    titleSelector = null,
                    thumbnailSelector = null,
                    resultCount = allResults.size,
                    responseTime = System.currentTimeMillis() - startTime
                )
            } catch (_: Exception) {}

            ProviderSearchResults(
                provider = provider,
                results = allResults.sortedByDescending { it.relevanceScore },
                searchTime = System.currentTimeMillis() - startTime,
                success = true
            )
        } else null
    }

    /** Delegate to shared implementation. */
    private fun extractDomain(url: String): String =
        EngineUtils.extractDomain(url)
    
    /**
     * Validate and filter results to ensure they are actual content, not category pages
     * ENHANCED: Also matches against descriptions and collects related content
     */
    private fun validateAndFilterResults(results: List<SearchResult>, query: String): List<SearchResult> {
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        val processed = currentProcessedQuery

        return results.filter { result ->
            val titleLower = result.title.lowercase()
            val urlLower = result.url.lowercase()

            // Must have a meaningful title
            if (result.title.trim().length < 3) return@filter false

            // Must have a meaningful URL (not just the base URL itself)
            val path = try { java.net.URL(result.url).path } catch (_: Exception) { result.url }
            if (path.length <= 1) return@filter false  // root / or empty

            // Filter out category/navigation links
            val isCategoryLink = CATEGORY_URL_PATTERNS.any { urlLower.contains(it) }
            if (isCategoryLink) return@filter false

            // Filter out generic category names only if they have no description AND no thumbnail
            val isTooGeneric = titleLower.trim() in GENERIC_CATEGORY_NAMES &&
                              result.description.isNullOrEmpty() &&
                              result.thumbnailUrl.isNullOrEmpty()
            if (isTooGeneric) return@filter false

            // Exclude common non-content file types
            val isExcludedExtension = urlLower.matches(Regex(".*\\.(css|js|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot)(\\?.*)?$"))
            if (isExcludedExtension) return@filter false

            // ── RELEVANCE GATE (NLP-ENHANCED) ───────────────────────────────
            // Accept results that match EITHER:
            //  1. At least one raw query keyword, OR
            //  2. Any concept term from NLP processing (subjects, actions, synonyms)
            //  3. A semantic relevance score ≥ 15 from the NLP processor
            if (queryWords.isNotEmpty() || processed != null) {
                val descLower = result.description?.lowercase() ?: ""
                val urlPath = try {
                    java.net.URL(result.url).path.lowercase().replace("-", " ").replace("_", " ")
                } catch (_: Exception) { urlLower }
                val combined = "$titleLower $descLower $urlPath"

                // Check 1: raw keyword match (original behaviour)
                val hasAnyKeyword = queryWords.any { combined.contains(it) }
                if (hasAnyKeyword) return@filter true

                // Check 2: NLP concept term match
                if (processed != null) {
                    val hasConceptMatch = processed.conceptTerms.any { term ->
                        combined.contains(term)
                    }
                    if (hasConceptMatch) return@filter true

                    // Check 3: semantic relevance scoring
                    val semanticScore = nlpProcessor.calculateSemanticRelevance(
                        result.title,
                        result.description,
                        processed.concepts
                    )
                    if (semanticScore >= 15f) return@filter true
                }

                // None of the checks passed — reject this result
                return@filter false
            }

            true
        }
    }
    
    /**
     * Enhanced matching that searches in titles, descriptions, and URLs.
     * Now also checks NLP concept terms and semantic synonyms.
     * Returns true if any part of the content matches the query.
     */
    private fun matchesQueryEnhanced(result: SearchResult, query: String): Boolean {
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        
        // Check title
        val titleLower = result.title.lowercase()
        if (queryWords.any { titleLower.contains(it) }) return true
        
        // Check description
        val descLower = result.description?.lowercase() ?: ""
        if (descLower.isNotEmpty() && queryWords.any { descLower.contains(it) }) return true
        
        // Check URL path (often contains keywords)
        val urlPath = try {
            java.net.URL(result.url).path.lowercase()
        } catch (e: Exception) {
            result.url.lowercase()
        }
        if (queryWords.any { urlPath.contains(it.replace(" ", "-")) || urlPath.contains(it.replace(" ", "_")) }) return true
        
        // Fuzzy matching for similar terms
        val titleWords = titleLower.split(Regex("\\W+")).filter { it.length > 2 }
        for (queryWord in queryWords) {
            for (titleWord in titleWords) {
                if (areSimilarWords(queryWord, titleWord)) return true
            }
        }

        // ── NLP concept matching ─────────────────────────────────────────
        val processed = currentProcessedQuery
        if (processed != null) {
            val combined = "$titleLower $descLower $urlPath"
            // Check NLP concept terms (subjects, actions, descriptors + their synonyms)
            if (processed.conceptTerms.any { combined.contains(it) }) return true
            // Semantic relevance threshold
            val semanticScore = nlpProcessor.calculateSemanticRelevance(
                result.title, result.description, processed.concepts
            )
            if (semanticScore >= 15f) return true
        }
        
        return false
    }
    
    /**
     * Check if two words are similar using Levenshtein edit distance
     */
    private fun areSimilarWords(word1: String, word2: String): Boolean {
        if (word1 == word2) return true
        if (word1.length < 3 || word2.length < 3) return false
        
        // One contains the other (e.g. "spider" / "spiderman")
        // Guard: the shorter word must be ≥ 60% the length of the longer to prevent
        // false positives like "art" matching "started"
        val shorter = if (word1.length <= word2.length) word1 else word2
        val longer  = if (word1.length <= word2.length) word2 else word1
        if (shorter.length >= 4 && shorter.length.toFloat() / longer.length >= 0.6f &&
            longer.contains(shorter)) return true
        
        // Same start (stem matching) — must share first 3 letters AND ≥ 4-char prefix
        val minLen = minOf(word1.length, word2.length)
        if (minLen >= 5 && word1.take(3) == word2.take(3) && word1.take(minLen - 1) == word2.take(minLen - 1)) return true
        
        // True Levenshtein edit distance — conservative thresholds
        val maxDist = when {
            minLen <= 4 -> 0   // short words must match exactly
            minLen <= 6 -> 1
            minLen <= 9 -> 2
            else -> 2          // capped at 2 to avoid false positives
        }
        val editDist = levenshteinDistance(word1, word2)
        return editDist in 1..maxDist
    }
    
    /** Delegate to shared implementation. */
    private fun levenshteinDistance(a: String, b: String): Int =
        EngineUtils.levenshteinDistance(a, b)
    
    /**
     * Generate related/similar results even when main query doesn't match exactly
     * This helps provide content when exact matches are few
     */
    private fun findRelatedContent(
        allResults: List<SearchResult>,
        query: String,
        existingMatches: List<SearchResult>
    ): List<SearchResult> {
        val existingUrls = existingMatches.map { it.url }.toSet()
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        
        // Find results that partially match — require at least one exact keyword
        return allResults
            .filter { it.url !in existingUrls }
            .filter { result ->
                // Must contain at least ONE exact query keyword in title or description
                val combined = "${result.title.lowercase()} ${result.description?.lowercase() ?: ""}"
                queryWords.any { combined.contains(it) }
            }
            .map { result ->
                val relevance = calculatePartialRelevance(result, queryWords)
                Pair(result, relevance)
            }
            .filter { it.second > 0.4f }
            .sortedByDescending { it.second }
            .take(15)
            .map { it.first.copy(relevanceScore = it.second * 50f) }
    }
    
    /**
     * Calculate partial relevance for related content matching
     */
    private fun calculatePartialRelevance(result: SearchResult, queryWords: List<String>): Float {
        var score = 0f
        val titleLower = result.title.lowercase()
        val descLower = result.description?.lowercase() ?: ""
        val combined = "$titleLower $descLower"
        
        for (word in queryWords) {
            // Exact word match
            if (combined.contains(word)) {
                score += 0.3f
            }
            // Partial/fuzzy match
            else if (combined.split(Regex("\\W+")).any { areSimilarWords(it, word) }) {
                score += 0.15f
            }
        }
        
        // Bonus for having thumbnail (likely real content)
        if (!result.thumbnailUrl.isNullOrEmpty()) score += 0.1f
        
        // Bonus for having description
        if (!result.description.isNullOrEmpty()) score += 0.05f
        
        return score.coerceIn(0f, 1f)
    }
    
    /**
     * Smart search that uses SmartNavigationEngine to bypass category pages
     */
    suspend fun searchProviderSmart(provider: Provider, query: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()

        // ── NLP QUERY SELECTION ─────────────────────────────────────────
        // When the raw query is conversational natural language, use the
        // first (best) NLP-generated search query for the actual provider
        // search. This converts e.g. "scared my cat and it jumped so high"
        // into "cat jump scare" which matches real content titles.
        val processed = currentProcessedQuery
        val effectiveQuery = if (processed != null &&
            processed.isNaturalLanguage &&
            processed.searchQueries.isNotEmpty() &&
            query == processed.originalQuery
        ) {
            processed.searchQueries.first()
        } else {
            query
        }

        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)

            // Step 1: Try to find a search URL (works for most providers)
            val smartSearchUrl = smartNavigationEngine.findSearchUrl(provider.baseUrl, effectiveQuery)

            if (smartSearchUrl != null) {
                val results = scrapeWithSmartNavigation(provider, query, smartSearchUrl)
                if (results.isNotEmpty()) {
                    updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                    return ProviderSearchResults(
                        provider = provider,
                        results = results,
                        searchTime = System.currentTimeMillis() - startTime,
                        success = true
                    )
                }
            }

            // Step 2: No search found – try crawling tabs/categories as a smart alternative
            // This handles sites that organise content by genre/category tabs without search
            val tabResults = scrapeWithTabCrawl(provider, effectiveQuery)
            if (tabResults.isNotEmpty()) {
                updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                return ProviderSearchResults(
                    provider = provider,
                    results = tabResults,
                    searchTime = System.currentTimeMillis() - startTime,
                    success = true
                )
            }

            // Step 2.5: Try advanced endpoint discovery (hidden APIs, CMS endpoints)
            // Uses learned endpoints first, then probes new ones + headless interception
            try {
                val domain = extractDomain(provider.baseUrl)
                // Prefer any previously-learned endpoints for this domain
                val learnedEndpoints = endpointDiscoveryEngine.getRankedEndpoints(domain)
                var foundViaEndpoint = false

                for (endpoint in learnedEndpoints.take(3)) {
                    val url = endpoint
                        .replace("{query}", java.net.URLEncoder.encode(effectiveQuery, "UTF-8"))
                        .let { if (it.startsWith("http")) it else "${provider.baseUrl.trimEnd('/')}$it" }
                    try {
                        val apiDoc = fetchDocument(url)
                        val apiResults = extractResultsWithThumbnails(apiDoc, provider, query)
                        if (apiResults.isNotEmpty()) {
                            endpointDiscoveryEngine.learnWorkingEndpoint(domain, endpoint, apiResults.size)
                            aiDecisionEngine.learnEndpoint(domain, endpoint, ScrapingStrategy.API_BASED, apiResults.size)
                            updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                            return ProviderSearchResults(
                                provider = provider,
                                results = apiResults,
                                searchTime = System.currentTimeMillis() - startTime,
                                success = true
                            )
                        } else {
                            endpointDiscoveryEngine.learnFailedEndpoint(domain, endpoint)
                        }
                    } catch (_: Exception) {
                        endpointDiscoveryEngine.learnFailedEndpoint(domain, endpoint)
                    }
                }

                // If no learned endpoint worked, try standard discovery
                val apiSearchUrl = endpointDiscoveryEngine.getBestSearchEndpoint(provider.baseUrl, effectiveQuery)
                if (apiSearchUrl != null) {
                    val apiDoc = fetchDocument(apiSearchUrl)
                    val apiResults = extractResultsWithThumbnails(apiDoc, provider, query)
                    if (apiResults.isNotEmpty()) {
                        updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                        aiDecisionEngine.learnFromSuccess(
                            domain = domain,
                            strategy = ScrapingStrategy.API_BASED,
                            resultSelector = null,
                            titleSelector = null,
                            thumbnailSelector = null,
                            resultCount = apiResults.size,
                            responseTime = System.currentTimeMillis() - startTime
                        )
                        return ProviderSearchResults(
                            provider = provider,
                            results = apiResults,
                            searchTime = System.currentTimeMillis() - startTime,
                            success = true
                        )
                    }
                }
            } catch (_: Exception) {}

            // Step 3: Fall back to normal search with all its own fallbacks
            searchProvider(provider, effectiveQuery)
        } catch (e: Exception) {
            searchProvider(provider, effectiveQuery)
        }
    }
    
    /**
     * Scrape using smart navigation with pagination support
     * Also attempts to collect page 2 and 3 if initial results are thin
     */
    private suspend fun scrapeWithSmartNavigation(
        provider: Provider,
        query: String,
        searchUrl: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val document = fetchDocument(searchUrl)

        // If we landed on a category page, navigate past it first
        val (activeUrl, activeDoc) = if (smartNavigationEngine.isCategoryPage(searchUrl, document)) {
            val nav = smartNavigationEngine.navigatePastCategory(provider.baseUrl, document, query)
            nav ?: (searchUrl to document)
        } else {
            searchUrl to document
        }

        val pageOneResults = extractResultsWithThumbnails(activeDoc, provider, query)

        // If results are thin, collect additional pages
        if (pageOneResults.size < 15) {
            val paginationUrls = smartNavigationEngine.getPaginationLinks(activeDoc, provider.baseUrl, maxPages = 3)
            val allResults = pageOneResults.toMutableList()
            val seenUrls = pageOneResults.map { it.url }.toMutableSet()

            for (pageUrl in paginationUrls) {
                try {
                    val pageDoc = fetchDocument(pageUrl)
                    val pageResults = extractResultsWithThumbnails(pageDoc, provider, query)
                    pageResults.forEach { r ->
                        if (r.url !in seenUrls) {
                            seenUrls.add(r.url)
                            allResults.add(r)
                        }
                    }
                    if (allResults.size >= 50) break
                } catch (_: Exception) {}
            }

            return@withContext allResults.sortedByDescending { it.relevanceScore }
        }

        pageOneResults
    }
    
    /**
     * Extract results with thumbnail URLs
     * v3: Fixes ContentLink destructuring bug (was using title as thumbnailUrl),
     * uses all ContentLink fields directly, falls back gracefully to full content dump.
     */
    private fun extractResultsWithThumbnails(
        document: Document,
        provider: Provider,
        query: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val allExtracted = mutableListOf<SearchResult>()

        // Use SmartNavigationEngine to extract content links
        val contentLinks = smartNavigationEngine.extractContentLinks(document, provider.baseUrl)

        for (contentLink in contentLinks) {
            val url = contentLink.url
            // Prefer title from ContentLink (correct field), fall back to URL/doc extraction
            val title = contentLink.title.takeIf { it.length > 2 }
                ?: extractTitleFromUrl(url)
                ?: findTitleInDocument(document, url)
                ?: continue

            val description = findDescriptionInDocument(document, url)
            val thumbnailUrl = contentLink.thumbnail  // correct field (was taking .title before)

            val combinedRelevance = calculateRelevanceScore(title, query, description, url)

            val result = SearchResult(
                title = title,
                url = url,
                thumbnailUrl = thumbnailUrl,
                description = description,
                providerId = provider.id,
                providerName = provider.name,
                relevanceScore = combinedRelevance
            )

            allExtracted.add(result)

            if (matchesQueryEnhanced(result, query)) {
                results.add(result)
            }
        }

        // Also try generic extraction if not enough results
        if (results.size < 5) {
            val genericResults = extractResultsGeneric(document, provider, query)
            results.addAll(genericResults)
        }

        val uniqueResults = results.distinctBy { it.url }
        if (uniqueResults.size < 10) {
            val related = findRelatedContent(allExtracted, query, uniqueResults)
            val combined = (uniqueResults + related).distinctBy { it.url }

            if (combined.size < 5 && allExtracted.size > combined.size) {
                val existingUrls = combined.map { it.url }.toSet()
                // ONLY backfill with results that pass the relevance gate
                val remaining = validateAndFilterResults(
                    allExtracted.filter { it.url !in existingUrls }, query
                ).sortedByDescending { it.relevanceScore }.take(15)
                return (combined + remaining).distinctBy { it.url }
            }

            return combined
        }

        return uniqueResults
    }
    
    /**
     * Find description for a content URL in document
     */
    private fun findDescriptionInDocument(document: Document, url: String): String? {
        // Try to find the container that has this link
        val linkElement = document.select("a[href='$url'], a[href*='${url.substringAfterLast("/")}']").firstOrNull()
            ?: return null
        
        // Look for description in parent or sibling elements
        val parent = linkElement.parent() ?: return null
        val grandparent = parent.parent()
        
        val descSelectors = listOf(
            ".description", ".desc", ".synopsis", ".summary", ".text",
            "p", ".info", "[class*='desc']", "[class*='info']"
        )
        
        // Search in parent and grandparent
        for (container in listOfNotNull(parent, grandparent)) {
            for (selector in descSelectors) {
                val desc = container.select(selector).firstOrNull()?.text()?.trim()
                if (!desc.isNullOrEmpty() && desc.length > 10 && desc.length < 500) {
                    return desc
                }
            }
        }
        
        return null
    }
    
    private fun extractTitleFromUrl(url: String): String? {
        return try {
            val path = java.net.URL(url).path
            val segments = path.split("/").filter { it.isNotBlank() }
            segments.lastOrNull()
                ?.replace("-", " ")
                ?.replace("_", " ")
                ?.replace(Regex("\\.[a-z]{3,4}$"), "")
                ?.replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun findTitleInDocument(document: Document, url: String): String? {
        return document.select("a[href='$url'], a[href*='${url.substringAfterLast("/")}']")
            .firstOrNull()?.text()?.takeIf { it.isNotBlank() }
    }
    

    
    /**
     * Search a single provider with full error handling and fallback
     */
    suspend fun searchProvider(provider: Provider, query: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Rate limiting
            enforceRateLimit(provider.id)
            
            // Update search count
            providerDao.incrementSearchCount(provider.id)
            
            // Get scraping config or use analysis
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            
            // Try primary scraping method
            val results = when {
                config != null -> scrapeWithConfig(provider, query, config)
                analysis != null -> scrapeWithAnalysis(provider, query, analysis)
                else -> scrapeGeneric(provider, query)
            }
            
            // Update provider health on success
            updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
            
            ProviderSearchResults(
                provider = provider,
                results = results,
                searchTime = System.currentTimeMillis() - startTime,
                success = true
            )
        } catch (e: Exception) {
            // Try fallback methods
            tryFallbackScraping(provider, query, startTime, e)
        }
    }
    
    /**
     * Scrape using stored configuration
     */
    private suspend fun scrapeWithConfig(
        provider: Provider, 
        query: String, 
        config: ScrapingConfig
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, config.encoding)
        val searchUrl = config.searchUrlTemplate
            .replace("{baseUrl}", provider.baseUrl)
            .replace("{query}", encodedQuery)
            .replace("{page}", "1")
        
        val document = fetchDocument(searchUrl, config)
        extractResultsWithConfig(document, provider, query, config)
    }
    
    /**
     * Scrape using site analysis data
     */
    private suspend fun scrapeWithAnalysis(
        provider: Provider, 
        query: String, 
        analysis: SiteAnalysis
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        // Build search URL from analysis
        val searchUrl = buildSearchUrl(provider, query, analysis)
        
        val document = fetchDocument(searchUrl)
        extractResultsWithAnalysis(document, provider, query, analysis)
    }
    
    /**
     * Generic scraping for sites without configuration.
     * Uses the full expanded URL-pattern list (mirrors SmartNavigationEngine patterns)
     * so that even the initial generic scrape has high coverage.
     */
    private suspend fun scrapeGeneric(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(query, "UTF-8")
        val slug = query.trim().lowercase().replace(Regex("\\s+"), "-")
        val plus = query.trim().replace(Regex("\\s+"), "+")
        val base = provider.baseUrl.trimEnd('/')

        val searchPatterns = listOf(
            "$base/search?q=$enc",
            "$base/?s=$enc",
            "$base/search?query=$enc",
            "$base/?q=$enc",
            "$base/search?s=$enc",
            "$base/search?keyword=$enc",
            "$base/search?keywords=$enc",
            "$base/search?term=$enc",
            "$base/search?text=$enc",
            "$base/?search_query=$enc",
            "$base/results?q=$enc",
            "$base/videos?search=$enc",
            "$base/movies?search=$enc",
            "$base/search.php?q=$enc",
            "$base/search.html?q=$enc",
            "$base/find?q=$enc",
            "$base/index.php?s=$enc",
            "$base/index.php?q=$enc",
            "$base/?search=$enc",
            "$base/search/$enc",
            "$base/search/$slug",
            "$base/api/search?q=$enc"
        )

        for (pattern in searchPatterns) {
            try {
                val document = fetchDocument(pattern)
                val results = extractResultsGeneric(document, provider, query)
                if (results.isNotEmpty()) {
                    return@withContext results
                }
            } catch (e: Exception) {
                continue
            }
        }

        emptyList()
    }
    
    /**
     * Fallback scraping when primary method fails
     * ENHANCED: Uses headless browser more aggressively and tries multiple strategies
     */
    private suspend fun tryFallbackScraping(
        provider: Provider,
        query: String,
        startTime: Long,
        originalException: Exception
    ): ProviderSearchResults {
        // Mark initial failure
        providerDao.incrementFailedCount(provider.id)

        // Try alternative methods in order of resource usage
        // Tab-crawl is included for sites that have no search functionality
        val fallbackMethods: List<suspend () -> List<SearchResult>> = listOf(
            { scrapeGeneric(provider, query) },
            { scrapeWithAlternateUserAgent(provider, query) },
            { searchWithDecomposedQuery(provider, query) },
            { scrapeWithDelay(provider, query) },
            { scrapeMobileVersion(provider, query) },
            { scrapeWithAlternateSearchPatterns(provider, query) },
            { scrapeWithTabCrawl(provider, query) },       // NEW: crawls tabs/categories
            { scrapeProviderHomepage(provider, query) }
        )

        for (method in fallbackMethods) {
            try {
                val results = method()
                if (results.isNotEmpty()) {
                    updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                    return ProviderSearchResults(
                        provider = provider,
                        results = results,
                        searchTime = System.currentTimeMillis() - startTime,
                        success = true
                    )
                }
            } catch (e: Exception) {
                continue
            }
        }

        // Headless browser fallback (Playwright) - tries multiple strategies
        try {
            // Strategy A: Try common search URL patterns via headless
            val searchUrls = buildSearchUrlsForHeadless(provider, query)
            for (searchUrl in searchUrls) {
                try {
                    val html = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                        searchUrl,
                        waitSelector = ".item, .result, .video-item, article, .card",
                        timeout = 20000
                    )
                    if (!html.isNullOrEmpty()) {
                        val doc = org.jsoup.Jsoup.parse(html, provider.baseUrl)
                        val results = extractResultsWithThumbnails(doc, provider, query)
                        if (results.isNotEmpty()) {
                            updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                            return ProviderSearchResults(provider = provider, results = results,
                                searchTime = System.currentTimeMillis() - startTime, success = true)
                        }
                    }
                } catch (_: Exception) { continue }
            }

            // Strategy B: Headless search form submission (JS-rendered search)
            try {
                val formHtml = HeadlessBrowserHelper.searchViaHeadlessForm(provider.baseUrl, query, timeout = 25000)
                if (!formHtml.isNullOrEmpty()) {
                    val doc = org.jsoup.Jsoup.parse(formHtml, provider.baseUrl)
                    val results = extractResultsWithThumbnails(doc, provider, query)
                    if (results.isNotEmpty()) {
                        updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                        return ProviderSearchResults(provider = provider, results = results,
                            searchTime = System.currentTimeMillis() - startTime, success = true)
                    }
                }
            } catch (_: Exception) {}

            // Strategy C: Headless tab/category clicking for no-search sites
            try {
                val tabHtml = HeadlessBrowserHelper.fetchContentByClickingTabs(provider.baseUrl, query, timeout = 25000)
                if (!tabHtml.isNullOrEmpty()) {
                    val doc = org.jsoup.Jsoup.parse(tabHtml, provider.baseUrl)
                    val results = extractResultsWithThumbnails(doc, provider, query)
                    if (results.isNotEmpty()) {
                        updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                        return ProviderSearchResults(provider = provider, results = results,
                            searchTime = System.currentTimeMillis() - startTime, success = true)
                    }
                }
            } catch (_: Exception) {}

            // Strategy D: Headless base URL scrape
            try {
                val html = HeadlessBrowserHelper.fetchPageContent(provider.url)
                if (!html.isNullOrEmpty()) {
                    val doc = org.jsoup.Jsoup.parse(html, provider.url)
                    val results = extractResultsWithThumbnails(doc, provider, query)
                    if (results.isNotEmpty()) {
                        updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                        return ProviderSearchResults(provider = provider, results = results,
                            searchTime = System.currentTimeMillis() - startTime, success = true)
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        // All fallbacks failed
        updateProviderHealth(provider.id, false, System.currentTimeMillis() - startTime)
        return ProviderSearchResults(
            provider = provider,
            results = emptyList(),
            searchTime = System.currentTimeMillis() - startTime,
            success = false,
            errorMessage = "All scraping methods failed (including headless): ${originalException.message}"
        )
    }
    
    /**
     * Build multiple search URLs to try with headless browser
     */
    private fun buildSearchUrlsForHeadless(provider: Provider, query: String): List<String> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val baseUrl = provider.baseUrl.trimEnd('/')
        
        return listOf(
            "$baseUrl/search?q=$encodedQuery",
            "$baseUrl/search?query=$encodedQuery",
            "$baseUrl/search/$encodedQuery",
            "$baseUrl/?s=$encodedQuery",
            "$baseUrl/videos?search=$encodedQuery",
            "$baseUrl/movies?search=$encodedQuery",
            "$baseUrl/search.php?q=$encodedQuery",
            "$baseUrl/find?q=$encodedQuery",
            "$baseUrl/?q=$encodedQuery",
            "$baseUrl/results?search_query=$encodedQuery"
        )
    }
    
    /**
     * Try alternate search URL patterns not covered by scrapeGeneric.
     * Uses extractResultsWithThumbnails instead of extractResultsGeneric for
     * richer result extraction (thumbnails, descriptions, SmartNavigation).
     */
    private suspend fun scrapeWithAlternateSearchPatterns(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val slug = query.trim().lowercase().replace(Regex("\\s+"), "-")
        val baseUrl = provider.baseUrl.trimEnd('/')

        // Only patterns NOT already in scrapeGeneric
        val extraPatterns = listOf(
            "$baseUrl/search/$slug",
            "$baseUrl/videos?q=$encodedQuery",
            "$baseUrl/movies?q=$encodedQuery",
            "$baseUrl/results?search_query=$encodedQuery",
            "$baseUrl/search/${query.replace(" ", "+")}"
        )

        for (pattern in extraPatterns) {
            try {
                val document = fetchDocument(pattern)
                val results = extractResultsWithThumbnails(document, provider, query)
                if (results.isNotEmpty()) {
                    return@withContext results
                }
            } catch (e: Exception) {
                continue
            }
        }

        emptyList()
    }

    /**
     * Scrape a provider by crawling its tabs/categories when search is unavailable.
     * Intelligently scores each tab against the query and visits the most relevant ones.
     * Falls back to visiting all generic content tabs if nothing matches.
     */
    private suspend fun scrapeWithTabCrawl(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val contentLinks = smartNavigationEngine.crawlCategoryTabsForQuery(
            baseUrl = provider.baseUrl,
            query = query,
            maxTabs = 8
        )
        if (contentLinks.isEmpty()) return@withContext emptyList()

        val seenUrls = mutableSetOf<String>()
        val results = mutableListOf<SearchResult>()

        for (link in contentLinks) {
            if (link.url in seenUrls) continue
            seenUrls.add(link.url)

            val title = link.title.takeIf { it.length > 2 }
                ?: extractTitleFromUrl(link.url)
                ?: continue

            val relevanceScore = calculateRelevanceScore(title, query, url = link.url)
            results.add(
                SearchResult(
                    title = title,
                    url = link.url,
                    thumbnailUrl = link.thumbnail,
                    providerId = provider.id,
                    providerName = provider.name,
                    relevanceScore = relevanceScore
                )
            )
        }

        // Filter through relevance gate before returning
        validateAndFilterResults(results, query)
            .sortedByDescending { it.relevanceScore }
            .take(50)
    }

    /**
     * Fetch document with retries + Cloudflare bypass fallback
     */
    private suspend fun fetchDocument(
        url: String,
        config: ScrapingConfig? = null
    ): Document = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        val retryCount = config?.retryCount ?: DEFAULT_RETRY_COUNT
        val retryDelay = config?.retryDelay ?: DEFAULT_RETRY_DELAY
        val timeout = config?.timeout ?: DEFAULT_TIMEOUT

        // ── Layer 1: Standard Jsoup fetch with retries ──
        repeat(retryCount) { attempt ->
            try {
                val connection = Jsoup.connect(url)
                    .userAgent(config?.userAgent ?: getRandomUserAgent())
                    .timeout(timeout)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)   // handle 403/503 ourselves
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("sec-ch-ua", "\"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\", \"Not-A.Brand\";v=\"99\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")

                // Add custom headers
                config?.headers?.let { headersJson ->
                    try {
                        val headers = parseHeaders(headersJson)
                        headers.forEach { (key, value) -> connection.header(key, value) }
                    } catch (e: Exception) { /* ignore */ }
                }

                // Add cookies (including any cached CF bypass cookies)
                val cfCookies = cloudflareBypassEngine.getCookiesForDomain(url)
                cfCookies.forEach { (key, value) -> connection.cookie(key, value) }
                config?.cookies?.let { cookiesJson ->
                    try {
                        val cookies = parseCookies(cookiesJson)
                        cookies.forEach { (key, value) -> connection.cookie(key, value) }
                    } catch (e: Exception) { /* ignore */ }
                }

                val response = connection.execute()
                val statusCode = response.statusCode()

                // If we got a successful response with real content, parse and return
                if (statusCode in 200..399) {
                    val doc = response.parse()
                    val html = doc.html()
                    // Check if it's a Cloudflare challenge page
                    if (!html.contains("Checking your browser") && !html.contains("cf_chl_opt") &&
                        !html.contains("Just a moment") && html.length > 500) {
                        return@withContext doc
                    }
                    // It's a challenge page — break to bypass layer
                }
                // 403/503/429 — break to bypass layer
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryCount - 1) {
                    delay(retryDelay * (attempt + 1))
                }
            }
        }

        // ── Layer 2: Cloudflare bypass engine ──
        try {
            val bypassDoc = cloudflareBypassEngine.fetchJsoupDocument(url, timeout)
            if (bypassDoc != null) return@withContext bypassDoc
        } catch (_: Exception) {}

        // ── Layer 3: Headless browser fetch ──
        try {
            val headlessHtml = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(url, null, timeout)
            if (!headlessHtml.isNullOrEmpty() && headlessHtml.length > 500) {
                return@withContext Jsoup.parse(headlessHtml, url)
            }
        } catch (_: Exception) {}

        throw lastException ?: Exception("Failed to fetch document after all strategies")
    }
    
    /**
     * Extract results using scraping configuration
     */
    private fun extractResultsWithConfig(
        document: Document,
        provider: Provider,
        query: String,
        config: ScrapingConfig
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val items = document.select(config.resultSelector)
        
        items.forEach { item ->
            try {
                val title = extractText(item, config.titleSelector)
                val url = extractUrl(item, config.urlSelector, provider.baseUrl)
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    results.add(SearchResult(
                        providerId = provider.id,
                        providerName = provider.name,
                        title = title,
                        url = url,
                        description = config.descriptionSelector?.let { extractText(item, it) },
                        thumbnailUrl = config.thumbnailSelector?.let { extractImageUrl(item, it, provider.baseUrl) },
                        date = config.dateSelector?.let { extractText(item, it) },
                        size = config.sizeSelector?.let { extractText(item, it) },
                        seeders = config.seedersSelector?.let { extractNumber(item, it) },
                        leechers = config.leechersSelector?.let { extractNumber(item, it) },
                        rating = config.ratingSelector?.let { extractRating(item, it) },
                        category = config.categorySelector?.let { extractText(item, it) },
                        relevanceScore = calculateRelevanceScore(title, query)
                    ))
                }
            } catch (e: Exception) {
                // Skip malformed items
            }
        }
        
        return results.sortedByDescending { it.relevanceScore }
    }
    
    /**
     * Extract results using site analysis data
     */
    private fun extractResultsWithAnalysis(
        document: Document,
        provider: Provider,
        query: String,
        analysis: SiteAnalysis
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        // Try to find result items
        val itemSelector = analysis.resultItemSelector 
            ?: detectResultItemSelector(document)
            ?: return emptyList()
        
        val items = document.select(itemSelector)
        
        items.forEach { item ->
            try {
                val title = extractTitleFromItem(item, analysis)
                val url = extractUrlFromItem(item, provider.baseUrl)
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    results.add(SearchResult(
                        providerId = provider.id,
                        providerName = provider.name,
                        title = title,
                        url = url,
                        description = analysis.descriptionSelector?.let { extractText(item, it) },
                        thumbnailUrl = analysis.thumbnailSelector?.let { extractImageUrl(item, it, provider.baseUrl) },
                        date = analysis.dateSelector?.let { extractText(item, it) },
                        rating = analysis.ratingSelector?.let { extractRating(item, it) },
                        relevanceScore = calculateRelevanceScore(title, query)
                    ))
                }
            } catch (e: Exception) {
                // Skip malformed items
            }
        }
        
        return results.sortedByDescending { it.relevanceScore }
    }
    
    /**
     * Generic result extraction without configuration
     * ENHANCED: Searches in descriptions, extracts more metadata, finds related content
     */
    private fun extractResultsGeneric(
        document: Document,
        provider: Provider,
        query: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val allResults = mutableListOf<SearchResult>()
        
        // Try to detect result structure - expanded selectors
        val itemSelectors = listOf(
            ".result", ".item", ".card", ".entry", ".post",
            ".video-item", ".movie-item", ".torrent-item",
            "article", ".row", "[data-item]", "[data-id]",
            ".search-result", ".listing", ".media",
            ".thumb", ".preview", ".content-item",
            "[class*='video']", "[class*='movie']", "[class*='result']",
            ".grid-item", ".list-item", "li.item"
        )
        
        // Find selector with most matches (but at least 2)
        val itemSelector = itemSelectors
            .map { it to document.select(it).size }
            .filter { it.second >= 2 }
            .maxByOrNull { it.second }
            ?.first ?: return emptyList()
            
        val items = document.select(itemSelector)
        
        items.forEach { item ->
            try {
                val title = extractBestTitle(item)
                val url = extractUrlFromItem(item, provider.baseUrl)
                val description = extractBestDescription(item)
                val thumbnail = extractBestThumbnail(item, provider.baseUrl)
                
                if (title.isNotEmpty() && url.isNotEmpty() && 
                    !url.contains("javascript:") && !url.startsWith("#")) {
                    
                    val result = SearchResult(
                        providerId = provider.id,
                        providerName = provider.name,
                        title = title,
                        url = url,
                        description = description,
                        thumbnailUrl = thumbnail,
                        relevanceScore = calculateRelevanceScore(title, query, description, url)
                    )
                    
                    allResults.add(result)
                    
                    // Check if matches query (title, description, or URL)
                    if (matchesQueryEnhanced(result, query)) {
                        results.add(result)
                    }
                }
            } catch (e: Exception) {
                // Skip malformed items
            }
        }
        
        // If few direct matches, add related content
        val uniqueResults = results.distinctBy { it.url }
        if (uniqueResults.size < 10) {
            val related = findRelatedContent(allResults, query, uniqueResults)
            return (uniqueResults + related)
                .distinctBy { it.url }
                .sortedByDescending { it.relevanceScore }
                .take(50)
        }
        
        return uniqueResults
            .sortedByDescending { it.relevanceScore }
            .take(50)
    }
    
    /**
     * Detect result item selector dynamically
     */
    private fun detectResultItemSelector(document: Document): String? {
        val candidates = listOf(
            ".result", ".item", ".card", ".entry", ".post",
            ".video-item", ".movie-item", ".torrent-item",
            "article", ".row", "[data-item]", "[data-id]",
            ".search-result", ".listing", ".media"
        )
        
        // Find selector with most matches (but at least 2)
        return candidates
            .map { it to document.select(it).size }
            .filter { it.second >= 2 }
            .maxByOrNull { it.second }
            ?.first
    }
    
    /**
     * Build search URL from analysis
     */
    private fun buildSearchUrl(provider: Provider, query: String, analysis: SiteAnalysis): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // Try to find search form action
        analysis.searchFormSelector?.let { selector ->
            // Use detected search pattern
        }
        
        // Default patterns
        return "${provider.baseUrl}/search?q=$encodedQuery"
    }
    
    // Extraction helper methods
    private fun extractText(element: Element, selector: String): String {
        return element.select(selector).firstOrNull()?.text()?.trim() ?: ""
    }
    
    private fun extractUrl(element: Element, selector: String, baseUrl: String): String {
        val href = element.select(selector).firstOrNull()?.attr("href") ?: ""
        return normalizeUrl(href, baseUrl)
    }
    
    private fun extractImageUrl(element: Element, selector: String, baseUrl: String): String? {
        val img = element.select(selector).firstOrNull()
        val src = img?.attr("src") 
            ?: img?.attr("data-src") 
            ?: img?.attr("data-lazy-src")
            ?: return null
        return normalizeUrl(src, baseUrl)
    }
    
    private fun extractNumber(element: Element, selector: String): Int? {
        val text = extractText(element, selector)
        return text.replace(Regex("[^0-9]"), "").toIntOrNull()
    }
    
    private fun extractRating(element: Element, selector: String): Float? {
        val text = extractText(element, selector)
        return text.replace(Regex("[^0-9.]"), "").toFloatOrNull()
    }
    
    private fun extractTitleFromItem(item: Element, analysis: SiteAnalysis): String {
        // Try analysis selector first
        analysis.titleSelector?.let { selector ->
            val text = item.select(selector).text().trim()
            if (text.isNotEmpty()) return text
        }
        return extractBestTitle(item)
    }
    
    private fun extractBestTitle(item: Element): String {
        // Try common title patterns
        val selectors = listOf(
            "h1", "h2", "h3", "h4", ".title", ".name", 
            "[class*='title']", "[class*='name']", "a[title]"
        )
        
        for (selector in selectors) {
            val text = item.select(selector).firstOrNull()?.text()?.trim()
            if (!text.isNullOrEmpty() && text.length > 2 && text.length < 500) {
                return text
            }
        }
        
        // Fallback to first link text
        return item.select("a").firstOrNull()?.text()?.trim() ?: ""
    }
    
    private fun extractUrlFromItem(item: Element, baseUrl: String): String {
        // Look for the main link
        val link = item.select("a[href]").firstOrNull()
            ?: item.select("a").firstOrNull()
        val href = link?.attr("href") ?: ""
        return normalizeUrl(href, baseUrl)
    }
    
    private fun extractBestDescription(item: Element): String? {
        val selectors = listOf(
            ".description", ".desc", ".synopsis", ".summary",
            "[class*='description']", "p", ".info"
        )
        
        for (selector in selectors) {
            val text = item.select(selector).firstOrNull()?.text()?.trim()
            if (!text.isNullOrEmpty() && text.length > 10) {
                return text.take(500)
            }
        }
        return null
    }
    
    private fun extractBestThumbnail(item: Element, baseUrl: String): String? {
        val img = item.select("img").firstOrNull()
        val src = img?.attr("src") 
            ?: img?.attr("data-src") 
            ?: img?.attr("data-lazy-src")
            ?: return null
        
        if (src.isNotEmpty() && !src.contains("placeholder") && !src.contains("blank")) {
            return normalizeUrl(src, baseUrl)
        }
        return null
    }
    
    /**
     * Calculate relevance score for ranking results.
     * Considers title, optional description, and URL path keywords.
     */
    private fun calculateRelevanceScore(
        title: String,
        query: String,
        description: String? = null,
        url: String? = null
    ): Float {
        val titleLower = title.lowercase()
        val queryLower = query.lowercase()
        val queryTerms = queryLower.split(Regex("\\s+")).filter { it.length > 1 }

        if (queryTerms.isEmpty()) return 5f

        var score = 0f

        // Exact phrase match bonus
        if (titleLower.contains(queryLower)) {
            score += 50f
        }

        // Individual term matching in title
        var matchedTerms = 0
        queryTerms.forEach { term ->
            if (titleLower.contains(term)) {
                matchedTerms++
                score += 10f

                // Bonus for term at start
                if (titleLower.startsWith(term)) score += 15f

                // Position-based bonus: earlier is better
                val pos = titleLower.indexOf(term)
                score += maxOf(0f, 5f - pos / 20f)
            }
        }

        // Ratio of matched terms
        val matchRatio = matchedTerms.toFloat() / queryTerms.size
        score += matchRatio * 25f

        // Description matching (weighted at 40% of title)
        if (!description.isNullOrBlank()) {
            val descLower = description.lowercase()
            if (descLower.contains(queryLower)) score += 20f
            var descMatched = 0
            queryTerms.forEach { term ->
                if (descLower.contains(term)) descMatched++
            }
            score += (descMatched.toFloat() / queryTerms.size) * 10f
        }

        // URL path matching (slug keywords)
        if (!url.isNullOrBlank()) {
            try {
                val path = java.net.URL(url).path
                    .lowercase()
                    .replace("-", " ")
                    .replace("_", " ")
                    .replace("/", " ")
                var urlMatched = 0
                queryTerms.forEach { term ->
                    if (path.contains(term)) urlMatched++
                }
                score += (urlMatched.toFloat() / queryTerms.size) * 8f
            } catch (_: Exception) {}
        }

        // Penalize very long titles (likely spam or navigation text)
        if (title.length > 120) score -= 8f

        // Word order bonus
        if (queryTerms.size > 1) {
            val pattern = queryTerms.joinToString(".*")
            if (titleLower.matches(Regex(".*$pattern.*"))) score += 10f
        }

        // Fuzzy / partial stem match if no direct match
        if (matchedTerms == 0) {
            var fuzzyHits = 0
            queryTerms.forEach { term ->
                if (term.length >= 4) {
                    val stem = term.take(term.length - 1)
                    if (titleLower.contains(stem)) fuzzyHits++
                }
            }
            if (fuzzyHits > 0) score += fuzzyHits * 3f
        }

        // ── NLP SEMANTIC SCORING BOOST ──────────────────────────────────
        // If the NLP processor has analysed the query, add a semantic
        // similarity component that understands concept relationships.
        // This is especially important for natural language queries where
        // the raw keywords may not appear in the result at all but the
        // semantic concepts do (e.g. "scared my cat" → title "cat jump scare").
        val processed = currentProcessedQuery
        if (processed != null) {
            val semanticScore = nlpProcessor.calculateSemanticRelevance(
                title, description, processed.concepts
            )
            // Blend: if keyword matching found nothing, semantic can carry; otherwise it boosts
            if (matchedTerms == 0) {
                // Keyword matching failed — lean heavily on semantic understanding
                score += semanticScore * 0.8f
            } else {
                // Keywords matched too — add a modest semantic boost
                score += semanticScore * 0.3f
            }
        }

        return score.coerceIn(0f, 100f)
    }
    
    // Alternative scraping methods
    
    /**
     * Scrape provider's homepage/landing page as ultimate fallback
     * When search returns nothing, the homepage often has trending/popular/latest content
     * Returns all extractable content from the homepage, letting RankingEngine sort by relevance
     */
    private suspend fun scrapeProviderHomepage(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val homepageUrls = listOf(
            provider.baseUrl,
            provider.baseUrl.trimEnd('/') + "/",
            provider.baseUrl.trimEnd('/') + "/home",
            provider.baseUrl.trimEnd('/') + "/latest",
            provider.baseUrl.trimEnd('/') + "/new",
            provider.baseUrl.trimEnd('/') + "/trending",
            provider.baseUrl.trimEnd('/') + "/popular"
        )
        
        for (homepageUrl in homepageUrls) {
            try {
                val document = fetchDocument(homepageUrl)
                
                // Extract content from homepage, then keep only results that
                // actually match the user's query — never return random trending/latest
                val allResults = extractAllContentFromPage(document, provider)
                if (allResults.isNotEmpty()) {
                    val relevant = allResults
                        .filter { matchesQueryEnhanced(it, query) }
                        .map { result ->
                            result.copy(relevanceScore = calculateRelevanceScore(result.title, query))
                        }
                        .sortedByDescending { it.relevanceScore }
                    if (relevant.isNotEmpty()) {
                        return@withContext relevant
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        emptyList()
    }
    
    /**
     * Extract ALL content links from a page without query filtering
     * Used for homepage scraping to get whatever content the provider has
     */
    private fun extractAllContentFromPage(document: Document, provider: Provider): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        // Use SmartNavigationEngine to find content links (fixed destructuring)
        val contentLinks = smartNavigationEngine.extractContentLinks(document, provider.baseUrl)
        for (contentLink in contentLinks) {
            val url = contentLink.url
            if (url in seenUrls) continue
            seenUrls.add(url)

            val title = contentLink.title.takeIf { it.length > 2 }
                ?: extractTitleFromUrl(url)
                ?: findTitleInDocument(document, url)
                ?: continue
            if (title.length < 3) continue

            val description = findDescriptionInDocument(document, url)

            results.add(SearchResult(
                title = title,
                url = url,
                thumbnailUrl = contentLink.thumbnail,  // correct field
                description = description,
                providerId = provider.id,
                providerName = provider.name,
                relevanceScore = 0f
            ))
        }
        
        // Also try generic extraction for any missed items
        val itemSelectors = listOf(
            "article a[href]", ".card a[href]", ".item a[href]", 
            ".video-item a[href]", ".movie-item a[href]",
            ".post a[href]", ".entry a[href]", ".thumb a[href]",
            "[class*='video'] a[href]", "[class*='movie'] a[href]",
            ".grid-item a[href]", ".list-item a[href]"
        )
        
        for (selector in itemSelectors) {
            try {
                val elements = document.select(selector)
                for (element in elements) {
                    val href = element.attr("href")
                    val url = normalizeUrl(href, provider.baseUrl)
                    if (url in seenUrls || url.contains("javascript:") || url.startsWith("#")) continue
                    
                    // Skip category/navigation URLs
                    if (CATEGORY_URL_PATTERNS.any { url.lowercase().contains(it) }) continue
                    
                    // Prefer content URLs
                    val isContentUrl = CONTENT_URL_PATTERNS.any { url.lowercase().contains(it) }
                    
                    val title = element.text().trim().takeIf { it.length > 2 }
                        ?: extractTitleFromUrl(url)
                        ?: continue
                    
                    if (title.lowercase().trim() in GENERIC_CATEGORY_NAMES) continue
                    
                    seenUrls.add(url)
                    
                    // Find thumbnail nearby
                    val thumbnail = element.select("img").firstOrNull()?.let { img ->
                        val src = img.attr("src").takeIf { it.isNotEmpty() }
                            ?: img.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                        src?.let { normalizeUrl(it, provider.baseUrl) }
                    }
                    
                    results.add(SearchResult(
                        title = title,
                        url = url,
                        thumbnailUrl = thumbnail,
                        description = null,
                        providerId = provider.id,
                        providerName = provider.name,
                        relevanceScore = if (isContentUrl) 10f else 5f
                    ))
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        return results.distinctBy { it.url }.take(100)
    }
    
    /**
     * Try searching with decomposed query - individual keywords when full query fails
     * Useful when sites have limited search that doesn't handle multi-word queries well
     */
    private suspend fun searchWithDecomposedQuery(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        // ── NLP-enhanced decomposition ──────────────────────────────────
        // Instead of naively splitting the raw query into individual keywords,
        // use the NLP-generated search queries which are semantically meaningful
        // and formatted to match how content is actually titled on the web.
        val processed = currentProcessedQuery
        val searchTerms: List<String> = if (processed != null && processed.searchQueries.isNotEmpty()) {
            // Use up to 5 of the best NLP-generated queries
            processed.searchQueries.take(5)
        } else {
            // Fallback: split raw query into individual keywords
            val keywords = query.lowercase()
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .distinct()
            if (keywords.size <= 1) return@withContext emptyList()
            keywords.take(3)
        }

        val allResults = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        for (searchTerm in searchTerms) {
            try {
                val searchPatterns = listOf(
                    "${provider.baseUrl}/search?q=${URLEncoder.encode(searchTerm, "UTF-8")}",
                    "${provider.baseUrl}/search?query=${URLEncoder.encode(searchTerm, "UTF-8")}",
                    "${provider.baseUrl}/search/${URLEncoder.encode(searchTerm, "UTF-8")}",
                    "${provider.baseUrl}/?s=${URLEncoder.encode(searchTerm, "UTF-8")}"
                )

                for (pattern in searchPatterns) {
                    try {
                        val document = fetchDocument(pattern)
                        val results = extractResultsGeneric(document, provider, query)
                        for (result in results) {
                            if (result.url !in seenUrls) {
                                seenUrls.add(result.url)
                                // Re-score against the FULL original query
                                allResults.add(result.copy(
                                    relevanceScore = calculateRelevanceScore(result.title, query)
                                ))
                            }
                        }
                        if (results.isNotEmpty()) break // Got results for this search term
                    } catch (e: Exception) {
                        continue
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        allResults.sortedByDescending { it.relevanceScore }
    }
    
    private suspend fun scrapeWithAlternateUserAgent(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "${provider.baseUrl}/search?q=${URLEncoder.encode(query, "UTF-8")}"
        USER_AGENTS.shuffled().forEach { userAgent ->
            try {
                val document = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(DEFAULT_TIMEOUT)
                    .get()
                val results = extractResultsGeneric(document, provider, query)
                if (results.isNotEmpty()) {
                    return@withContext results
                }
            } catch (e: Exception) {
                // Try next user agent
            }
        }
        emptyList()
    }
    
    private suspend fun scrapeWithDelay(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        delay(2000) // Wait before retry
        scrapeGeneric(provider, query)
    }
    
    private suspend fun scrapeMobileVersion(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val mobileUrl = provider.baseUrl
            .replace("www.", "m.")
            .replace("://", "://m.")
        val searchUrl = "$mobileUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        
        try {
            val document = Jsoup.connect(searchUrl)
                .userAgent(EngineUtils.getRandomUserAgent())
                .timeout(DEFAULT_TIMEOUT)
                .get()
            extractResultsGeneric(document, provider, query)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Utility methods
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }
    }
    
    private fun enforceRateLimit(providerId: String) {
        val lastTime = lastRequestTime[providerId] ?: 0L
        val elapsed = System.currentTimeMillis() - lastTime
        if (elapsed < DEFAULT_RATE_LIMIT_MS) {
            Thread.sleep(DEFAULT_RATE_LIMIT_MS - elapsed)
        }
        lastRequestTime[providerId] = System.currentTimeMillis()
    }
    
    private fun updateProviderHealth(providerId: String, success: Boolean, responseTime: Long) {
        val current = providerHealthMap.getOrPut(providerId) { ProviderHealth() }
        val newHealth = if (success) {
            current.copy(
                successCount = current.successCount + 1,
                avgResponseTime = (current.avgResponseTime + responseTime) / 2,
                lastSuccess = System.currentTimeMillis()
            )
        } else {
            current.copy(
                failureCount = current.failureCount + 1,
                lastFailure = System.currentTimeMillis()
            )
        }
        providerHealthMap[providerId] = newHealth
    }
    
    private fun getRandomUserAgent(): String = EngineUtils.getRandomUserAgent()
    
    private fun parseHeaders(json: String): Map<String, String> {
        // Simple JSON parsing for headers
        return try {
            val cleaned = json.trim().removeSurrounding("{", "}")
            cleaned.split(",")
                .map { it.split(":").map { p -> p.trim().removeSurrounding("\"") } }
                .filter { it.size == 2 }
                .associate { it[0] to it[1] }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun parseCookies(json: String): Map<String, String> = parseHeaders(json)
    
    data class ProviderHealth(
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val avgResponseTime: Long = 0,
        val lastSuccess: Long = 0,
        val lastFailure: Long = 0
    )
}
