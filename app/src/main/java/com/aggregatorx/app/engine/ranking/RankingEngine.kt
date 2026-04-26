package com.aggregatorx.app.engine.ranking

import com.aggregatorx.app.data.model.AggregatedSearchResults
import com.aggregatorx.app.data.model.ProviderSearchResults
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.nlp.ProcessedQuery
import com.aggregatorx.app.engine.util.EngineUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Intelligent Result Ranking Engine v3
 * 
 * IMPORTANT DESIGN PRINCIPLE:
 * - This engine's ranking/scoring is used ONLY for the "Top Results" list.
 * - Provider-specific result sections keep their NATIVE order exactly as
 *   returned by each site's own search logic. We never re-sort them.
 * - Top Results are pinned/sorted using text relevance, user preference
 *   learning (from likes), engagement signals, and quality indicators.
 * 
 * Features:
 * - Text relevance (TF-IDF inspired with fuzzy matching)
 * - Levenshtein edit distance for typo tolerance
 * - N-gram substring matching for partial word detection
 * - User preference learning from liked results (keyword/provider/quality boost)
 * - Provider reliability score
 * - Content freshness, engagement signals, quality indicators
 * - Error providers automatically sorted to bottom
 * - Deduplicates by normalized title in Top Results
 * - Never returns empty if ANY content exists
 */
@Singleton
class RankingEngine @Inject constructor(
    private val nlpProcessor: NaturalLanguageQueryProcessor
) {

    // ── User preference data (set before each ranking call) ─────────────
    // These are populated from the LikedResult database by the repository
    // before calling rankAndAggregate, so the engine itself stays stateless.
    private var preferredKeywords: Map<String, Float> = emptyMap()
    private var preferredProviders: Map<String, Float> = emptyMap()
    private var preferredQualities: Map<String, Float> = emptyMap()
    private var likedUrls: Set<String> = emptySet()

    // NLP processed query set by the search pipeline before ranking
    @Volatile
    private var currentProcessedQuery: ProcessedQuery? = null

    /**
     * Set the NLP-processed query before ranking so concept-based
     * relevance scoring is available during result ranking.
     */
    fun setProcessedQuery(processed: ProcessedQuery?) {
        currentProcessedQuery = processed
    }

    /**
     * Feed learned user preferences into the engine before ranking.
     * Called by the repository each time it aggregates results.
     */
    fun setUserPreferences(
        keywords: Map<String, Float> = emptyMap(),
        providers: Map<String, Float> = emptyMap(),
        qualities: Map<String, Float> = emptyMap(),
        liked: Set<String> = emptySet()
    ) {
        preferredKeywords = keywords
        preferredProviders = providers
        preferredQualities = qualities
        likedUrls = liked
    }
    
    companion object {
        // Scoring weights — text relevance dominates; non-text signals are secondary
        private const val WEIGHT_TEXT_RELEVANCE = 0.60f
        private const val WEIGHT_PROVIDER_SCORE = 0.05f
        private const val WEIGHT_FRESHNESS = 0.08f
        private const val WEIGHT_ENGAGEMENT = 0.15f
        private const val WEIGHT_QUALITY = 0.12f
        
        // Text matching bonuses
        private const val EXACT_MATCH_BONUS = 40f
        private const val TITLE_START_BONUS = 30f
        private const val ALL_TERMS_BONUS = 25f
        private const val WORD_ORDER_BONUS = 18f
        private const val PARTIAL_MATCH_BONUS = 12f
        private const val FUZZY_MATCH_BONUS = 8f
        private const val DESCRIPTION_MATCH_BONUS = 10f
        private const val SYNONYM_MATCH_BONUS = 7f
        private const val URL_PATH_MATCH_BONUS = 6f
        private const val NGRAM_MATCH_BONUS = 5f
        
        // Minimum score thresholds — on the SAME 0-100 scale as calculateFinalScore
        private const val MIN_SCORE_FOR_TOP = 15.0f
        private const val MIN_SCORE_FOR_RELATED = 5.0f
        
        // Target result counts — modest so we never pad with junk
        private const val MIN_TOP_RESULTS = 8
        private const val MIN_RELATED_RESULTS = 12
        
        // Levenshtein distance threshold (max edits allowed relative to word length)
        private const val MAX_EDIT_DISTANCE_RATIO = 0.35f
        
        // Common synonyms and related terms - MASSIVELY EXPANDED
        private val SYNONYMS = mapOf(
            "movie" to listOf("film", "cinema", "feature", "flick", "motion picture"),
            "film" to listOf("movie", "cinema", "feature", "flick", "motion picture"),
            "video" to listOf("clip", "footage", "recording", "stream", "content"),
            "watch" to listOf("view", "stream", "play", "see", "look"),
            "download" to listOf("get", "save", "grab", "fetch", "dl"),
            "hd" to listOf("high definition", "720p", "1080p", "high quality", "hq"),
            "full" to listOf("complete", "entire", "whole", "uncut"),
            "episode" to listOf("ep", "part", "chapter", "installment"),
            "season" to listOf("series", "s0", "complete season"),
            "free" to listOf("gratis", "no cost", "freeware"),
            "online" to listOf("streaming", "web", "internet", "digital"),
            "latest" to listOf("new", "recent", "newest", "fresh", "updated"),
            "best" to listOf("top", "greatest", "finest", "premium", "ultimate"),
            "trailer" to listOf("preview", "teaser", "promo"),
            "subtitle" to listOf("sub", "subs", "subtitles", "cc", "captions"),
            "dubbed" to listOf("dub", "english dub", "dual audio"),
            "anime" to listOf("animation", "cartoon", "animated"),
            "series" to listOf("show", "tv show", "tv series", "television"),
            "song" to listOf("music", "track", "audio", "mp3"),
            "game" to listOf("gaming", "gameplay", "playthrough", "walkthrough"),
            "tutorial" to listOf("guide", "how to", "howto", "lesson"),
            "review" to listOf("opinion", "critique", "analysis"),
            "live" to listOf("livestream", "live stream", "broadcast"),
            "old" to listOf("classic", "vintage", "retro", "throwback"),
            "funny" to listOf("comedy", "humor", "hilarious", "lol"),
            "scary" to listOf("horror", "terrifying", "creepy", "spooky"),
            // Torrent / file terms
            "torrent" to listOf("magnet", "download", "p2p", "seedbox"),
            "stream" to listOf("streaming", "watch online", "live stream", "play"),
            "4k" to listOf("uhd", "ultra hd", "2160p", "ultra-hd"),
            "bluray" to listOf("blu-ray", "blu ray", "bd", "bdrip"),
            "remux" to listOf("bdremux", "bdmv", "full bluray"),
            // Time / era
            "new" to listOf("latest", "recent", "fresh", "updated", "newest"),
            "popular" to listOf("trending", "viral", "top", "best", "hot"),
            // Content type extras
            "clip" to listOf("short", "snippet", "cut", "highlight"),
            "show" to listOf("tv show", "television", "programme", "program"),
            "documentary" to listOf("doc", "docuseries", "documentary film"),
            "cartoon" to listOf("animated", "animation", "anime"),
            "indian" to listOf("bollywood", "hindi", "desi", "tollywood"),
            "kids" to listOf("children", "family", "cartoon", "toddler"),
            "sport" to listOf("sports", "match", "game", "league", "cup"),
            "music" to listOf("song", "audio", "album", "track", "mp3")
        )
        
        // Common stop words to ignore in matching
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to",
            "for", "of", "with", "by", "from", "is", "it", "this", "that",
            "are", "was", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "could", "should", "may",
            "might", "can", "shall", "not", "no", "so", "if", "then"
        )
    }
    
    /**
     * Rank and aggregate results from all providers
     * Error providers are automatically placed at the bottom
     * 
     * ADVANCED LOGIC v2:
     * - Two-pass scoring: first pass scores all, second pass fills gaps with fallback
     * - Keyword extraction splits query into meaningful terms
     * - If exact matches are few, automatically broadens to related/similar content
     * - NEVER returns empty results if any provider returned anything
     * - Deduplicates by normalized title to avoid showing same content from multiple providers
     */
    fun rankAndAggregate(
        query: String,
        providerResults: List<ProviderSearchResults>
    ): AggregatedSearchResults {
        val startTime = System.currentTimeMillis()
        
        // Separate successful and failed providers
        val successfulProviders = providerResults.filter { it.success }
        val failedProviders = providerResults.filter { !it.success }

        // Extract meaningful keywords from query (remove stop words)
        val queryKeywords = extractKeywords(query)

        // PASS 1: Calculate scores for all results from successful providers
        val scoredResults = successfulProviders.flatMap { pr ->
            pr.results.map { result ->
                ScoredResult(
                    result = result,
                    providerScore = calculateProviderScore(pr),
                    score = calculateFinalScore(result, query, pr)
                )
            }
        }

        // Cross-provider boost: results corroborated by 2+ providers score higher.
        // Group by normalised URL; each additional provider mention adds a 10% boost.
        val urlProviderCount = scoredResults
            .groupBy { normalizeUrl(it.result.url) }
            .mapValues { (_, v) -> v.map { it.result.providerId }.toSet().size }

        val boostedResults = scoredResults.map { sr ->
            val providerCount = urlProviderCount[normalizeUrl(sr.result.url)] ?: 1
            if (providerCount > 1) {
                val boost = 1f + (providerCount - 1) * 0.10f
                sr.copy(score = (sr.score * boost).coerceAtMost(100f))
            } else sr
        }

        // Adaptive score threshold: lower floor when total results are scarce
        val totalAvailable = boostedResults.size
        val adaptiveTopThreshold = when {
            totalAvailable < 10  -> MIN_SCORE_FOR_TOP * 0.60f
            totalAvailable < 30  -> MIN_SCORE_FOR_TOP * 0.75f
            totalAvailable < 60  -> MIN_SCORE_FOR_TOP * 0.90f
            else                 -> MIN_SCORE_FOR_TOP
        }
        val adaptiveRelatedThreshold = adaptiveTopThreshold * 0.40f

        // Get top results - best matches first
        var topResults = boostedResults
            .filter { it.score >= adaptiveTopThreshold }
            .distinctBy { normalizeTitle(it.result.title) }
            .sortedByDescending { it.score }
            .take(30)
            .map { it.result.copy(relevanceScore = it.score) }

        // Find related/similar results (partial matches, fuzzy matches, synonym matches)
        var relatedResults = boostedResults
            .filter { it.score >= adaptiveRelatedThreshold && it.score < adaptiveTopThreshold }
            .sortedByDescending { it.score }
            .distinctBy { normalizeTitle(it.result.title) }
            .take(50)
            .map { it.result.copy(relevanceScore = it.score) }

        // PASS 2: If few top results, progressively lower threshold and add more
        if (topResults.size < MIN_TOP_RESULTS) {
            // First try synonym-based matches
            val synonymResults = findSynonymMatches(query, boostedResults, topResults, relatedResults)
            topResults = (topResults + synonymResults).distinctBy { it.url }.take(30)
        }
        
        if (topResults.size < MIN_TOP_RESULTS) {
            // Pull from related into top
            val additionalTop = relatedResults
                .sortedByDescending { it.relevanceScore }
                .take(MIN_TOP_RESULTS - topResults.size)
            
            topResults = (topResults + additionalTop).distinctBy { it.url }
            
            // Update related to exclude what's now in top
            val topUrls = topResults.map { it.url }.toSet()
            relatedResults = relatedResults.filter { it.url !in topUrls }
        }
        
        // PASS 3: Keyword-based broadening - use individual keywords for wider net
        if (topResults.size < MIN_TOP_RESULTS && queryKeywords.size > 1) {
            val existingUrls = (topResults + relatedResults).map { it.url }.toSet()
            val keywordResults = boostedResults
                .filter { it.result.url !in existingUrls }
                .filter { scored ->
                    val titleLower = scored.result.title.lowercase()
                    val descLower = scored.result.description?.lowercase() ?: ""
                    val urlPath = extractUrlPath(scored.result.url)
                    val combined = "$titleLower $descLower $urlPath"
                    // Match if ANY single keyword matches
                    queryKeywords.any { keyword -> 
                        combined.contains(keyword) || 
                        combined.split(Regex("\\W+")).any { word -> 
                            levenshteinDistance(word, keyword) <= maxEditDistance(keyword) 
                        }
                    }
                }
                .sortedByDescending { it.score }
                .take(MIN_TOP_RESULTS)
                .map { it.result.copy(relevanceScore = maxOf(it.score, MIN_SCORE_FOR_RELATED)) }
            
            topResults = (topResults + keywordResults).distinctBy { it.url }.take(30)
        }
        
        // (PASS 4 removed — never pad with unrelated content)

        // ── Provider-specific sections: PRESERVE NATIVE ORDER ───────────
        // Each provider's results stay exactly as that site returned them.
        // We do NOT re-rank or re-sort them — the user wants to see each
        // site's own relevance order as if they searched there directly.
        // Only order the provider SECTIONS by the number of results (most
        // productive providers first) so the user sees the richest data up top.
        val orderedSuccessfulProviders = successfulProviders
            .sortedByDescending { it.results.size }

        // Failed providers go at the bottom - keep original error info
        val orderedProviderResults = orderedSuccessfulProviders + failedProviders

        return AggregatedSearchResults(
            query = query,
            providerResults = orderedProviderResults,
            totalResults = successfulProviders.sumOf { it.results.size },
            searchTime = System.currentTimeMillis() - startTime,
            successfulProviders = successfulProviders.size,
            failedProviders = failedProviders.size,
            topResults = topResults,
            relatedResults = relatedResults
        )
    }
    
    /**
     * Extract meaningful keywords from query (removes stop words, splits compound terms)
     */
    private fun extractKeywords(query: String): List<String> {
        return query.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
            .distinct()
    }
    
    /**
     * Extract URL path for keyword matching
     */
    private fun extractUrlPath(url: String): String {
        return try {
            java.net.URL(url).path.lowercase()
                .replace("-", " ")
                .replace("_", " ")
                .replace("/", " ")
        } catch (e: Exception) {
            url.lowercase()
        }
    }
    
    /**
     * Calculate maximum edit distance allowed based on word length
     */
    private fun maxEditDistance(word: String): Int {
        return when {
            word.length <= 3 -> 0
            word.length <= 5 -> 1
            else -> 2   // capped at 2 to prevent false positives
        }
    }
    
    /**
     * Find results that match synonyms of the query terms
     */
    private fun findSynonymMatches(
        query: String,
        allResults: List<ScoredResult>,
        existingTop: List<SearchResult>,
        existingRelated: List<SearchResult>
    ): List<SearchResult> {
        val existingUrls = (existingTop + existingRelated).map { it.url }.toSet()
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        
        // Get synonyms for query terms
        val synonymTerms = queryTerms.flatMap { term ->
            SYNONYMS[term] ?: emptyList()
        }.distinct()
        
        if (synonymTerms.isEmpty()) return emptyList()
        
        // Find results matching synonyms
        return allResults
            .filter { it.result.url !in existingUrls }
            .filter { scored ->
                val titleLower = scored.result.title.lowercase()
                val descLower = scored.result.description?.lowercase() ?: ""
                synonymTerms.any { synonym ->
                    titleLower.contains(synonym) || descLower.contains(synonym)
                }
            }
            .filter { it.score >= MIN_SCORE_FOR_RELATED } // must have some base relevance
            .sortedByDescending { it.score }
            .take(8)
            .map { it.result.copy(relevanceScore = it.score + SYNONYM_MATCH_BONUS) }
    }
    
    /**
     * Calculate final score combining all factors.
     * This score is used ONLY for the Top Results list.
     * Includes user preference boost from learned likes.
     */
    private fun calculateFinalScore(
        result: SearchResult,
        query: String,
        providerResults: ProviderSearchResults
    ): Float {
        val textScore = calculateTextRelevance(result.title, result.description, query)
        val providerScore = calculateProviderScore(providerResults)
        val freshnessScore = calculateFreshnessScore(result.date)
        val engagementScore = calculateEngagementScore(result)
        val qualityScore = calculateQualityScore(result)

        var baseScore = (textScore * WEIGHT_TEXT_RELEVANCE +
                providerScore * WEIGHT_PROVIDER_SCORE +
                freshnessScore * WEIGHT_FRESHNESS +
                engagementScore * WEIGHT_ENGAGEMENT +
                qualityScore * WEIGHT_QUALITY) * 100f

        // ── User Preference Boost (from liked results) ─────────────────
        // Boost results that match learned user preferences.
        // This only affects Top Results scoring — provider sections are untouched.
        var prefBoost = 0f

        // Already-liked results get the biggest push to the top
        if (result.url in likedUrls) {
            prefBoost += 15f
        }

        // Keyword preference boost: title words that appear in liked history
        if (preferredKeywords.isNotEmpty()) {
            val titleWords = result.title.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
            val keywordScore = titleWords.sumOf { word ->
                (preferredKeywords[word] ?: 0f).toDouble()
            }.toFloat()
            // Normalise: max ≈ 10 points
            prefBoost += (keywordScore * 10f).coerceAtMost(10f)
        }

        // Provider preference boost
        val providerPref = preferredProviders[result.providerId] ?: 0f
        prefBoost += providerPref * 5f  // max ≈ 5 points

        // Quality preference boost
        val qualityTag = result.quality?.lowercase() ?: ""
        if (qualityTag.isNotEmpty()) {
            val qualityPref = preferredQualities[qualityTag] ?: 0f
            prefBoost += qualityPref * 3f  // max ≈ 3 points
        }

        return (baseScore + prefBoost).coerceIn(0f, 100f)
    }
    
    /**
     * Calculate text relevance score using enhanced TF-IDF-like approach with fuzzy matching
     * 
     * ADVANCED v2: 
     * - True Levenshtein edit distance for typo tolerance
     * - N-gram substring matching for partial word detection
     * - URL path keyword extraction and matching
     * - Better description matching with higher weight
     * - Expanded synonym matching
     * - Stem-based matching for word variants
     */
    private fun calculateTextRelevance(title: String, description: String?, query: String): Float {
        val titleLower = title.lowercase()
        val descLower = description?.lowercase() ?: ""
        val queryLower = query.lowercase()
        val queryTerms = queryLower.split(Regex("\\s+")).filter { it.length > 1 && it !in STOP_WORDS }
        
        if (queryTerms.isEmpty()) return 0.1f  // Give small score even with empty query
        
        var score = 0f
        
        // Exact full query match in title - highest priority
        if (titleLower.contains(queryLower)) {
            score += EXACT_MATCH_BONUS
        }
        
        // Exact full query match in description
        if (descLower.contains(queryLower)) {
            score += DESCRIPTION_MATCH_BONUS * 2.5f
        }
        
        // Title starts with query
        if (titleLower.startsWith(queryLower)) {
            score += TITLE_START_BONUS
        }
        
        // Term frequency analysis with fuzzy matching
        var titleMatches = 0
        var descMatches = 0
        var fuzzyMatches = 0
        var synonymMatches = 0
        var ngramMatches = 0
        val matchedTerms = mutableListOf<String>()
        
        queryTerms.forEach { term ->
            // === TITLE MATCHING ===
            val titleOccurrences = countOccurrences(titleLower, term)
            if (titleOccurrences > 0) {
                titleMatches++
                matchedTerms.add(term)
                // TF-IDF inspired: diminishing returns for repeated terms
                score += (1 + ln(titleOccurrences.toDouble())).toFloat() * 7f
                
                // Position bonus - earlier matches are better
                val position = titleLower.indexOf(term)
                score += max(0f, 8f - (position / 12f))
            } else {
                // Try Levenshtein edit distance for typo tolerance
                val titleWords = titleLower.split(Regex("\\W+")).filter { it.length > 1 }
                var bestEditMatch = false
                for (titleWord in titleWords) {
                    val editDist = levenshteinDistance(term, titleWord)
                    val maxAllowed = maxEditDistance(term)
                    if (editDist in 1..maxAllowed) {
                        fuzzyMatches++
                        score += FUZZY_MATCH_BONUS * (1f - editDist.toFloat() / (maxAllowed + 1))
                        bestEditMatch = true
                        break
                    }
                }
                
                // Try N-gram substring matching if edit distance didn't match
                if (!bestEditMatch && term.length >= 4) {
                    val ngrams = generateNgrams(term, 3)
                    val titleNgrams = generateNgrams(titleLower, 3)
                    val overlap = ngrams.count { it in titleNgrams }.toFloat() / ngrams.size.coerceAtLeast(1)
                    if (overlap >= 0.5f) {
                        ngramMatches++
                        score += NGRAM_MATCH_BONUS * overlap
                    }
                }
                
                // Try synonym matching
                val synonyms = SYNONYMS[term] ?: emptyList()
                for (synonym in synonyms) {
                    if (titleLower.contains(synonym)) {
                        synonymMatches++
                        score += SYNONYM_MATCH_BONUS
                        break
                    }
                }
                
                // Partial/stem match - term starts with or ends with query word
                if (term.length >= 3) {
                    val stem = term.dropLast(1)
                    if (titleLower.contains(stem)) {
                        score += PARTIAL_MATCH_BONUS * 0.6f
                    }
                }
            }
            
            // === DESCRIPTION MATCHING ===
            val descOccurrences = countOccurrences(descLower, term)
            if (descOccurrences > 0) {
                descMatches++
                score += (1 + ln(descOccurrences.toDouble())).toFloat() * 5f
            } else if (descLower.isNotEmpty()) {
                // Levenshtein in description
                val descWords = descLower.split(Regex("\\W+")).filter { it.length > 1 }
                for (descWord in descWords) {
                    val editDist = levenshteinDistance(term, descWord)
                    if (editDist in 1..maxEditDistance(term)) {
                        score += FUZZY_MATCH_BONUS * 0.6f
                        break
                    }
                }
                
                // Synonym match in description
                val synonyms = SYNONYMS[term] ?: emptyList()
                for (synonym in synonyms) {
                    if (descLower.contains(synonym)) {
                        score += SYNONYM_MATCH_BONUS * 0.5f
                        break
                    }
                }
            }
        }
        
        // All terms matched bonus (including fuzzy and synonym matches)
        val totalMatches = titleMatches + fuzzyMatches * 0.6f + synonymMatches * 0.4f + ngramMatches * 0.3f
        if (titleMatches == queryTerms.size) {
            score += ALL_TERMS_BONUS
        } else if (totalMatches >= queryTerms.size * 0.6) {
            score += ALL_TERMS_BONUS * 0.6f
        } else if (titleMatches + descMatches >= queryTerms.size) {
            score += ALL_TERMS_BONUS * 0.5f
        } else if (totalMatches >= queryTerms.size * 0.3) {
            // Even partial coverage gets a smaller bonus
            score += ALL_TERMS_BONUS * 0.25f
        }
        
        // Word order preservation bonus
        if (queryTerms.size > 1 && preservesWordOrder(titleLower, queryTerms)) {
            score += WORD_ORDER_BONUS
        }
        
        // Coverage ratio - how much of query is matched (including all match types)
        val coverageRatio = (titleMatches + descMatches * 0.6f + fuzzyMatches * 0.5f + 
                            synonymMatches * 0.4f + ngramMatches * 0.3f) / queryTerms.size
        score *= (0.3f + coverageRatio * 0.7f)
        
        // Small floor only for genuine exact keyword matches (not fuzzy-only)
        if (titleMatches > 0 || descMatches > 0) {
            score = max(score, 2f)
        }
        
        // Length penalty for very long titles (likely spam)
        if (title.length > 150) {
            score *= 0.85f
        }

        // ── NLP SEMANTIC CONCEPT SCORING ────────────────────────────────
        // When NLP processing is available, add concept-based relevance.
        // This is critical for natural language queries where the raw
        // keywords may not appear in the result but semantic concepts do.
        val processed = currentProcessedQuery
        if (processed != null) {
            val semanticScore = nlpProcessor.calculateSemanticRelevance(
                title, description, processed.concepts
            )
            // Blend based on how well keyword matching worked
            val keywordCoverage = (titleMatches + descMatches).toFloat() / queryTerms.size.coerceAtLeast(1)
            if (keywordCoverage < 0.2f) {
                // Keywords barely matched — lean heavily on semantic understanding
                score += semanticScore * 0.7f
            } else if (keywordCoverage < 0.5f) {
                // Partial keyword match — add moderate semantic boost
                score += semanticScore * 0.4f
            } else {
                // Good keyword coverage — add a small semantic refinement
                score += semanticScore * 0.15f
            }
        }
        
        return score.coerceIn(0f, 100f) / 100f
    }
    
    /**
     * Find fuzzy match using true Levenshtein edit distance
     * Returns the best matching word from text if within edit distance threshold
     */
    private fun findFuzzyMatch(text: String, term: String): String? {
        if (term.length < 3) return null
        
        val words = text.split(Regex("\\W+")).filter { it.length > 1 }
        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE
        val maxAllowed = maxEditDistance(term)
        
        for (word in words) {
            // Skip words with extreme length difference
            if (abs(word.length - term.length) > maxAllowed + 1) continue
            
            val distance = levenshteinDistance(word, term)
            if (distance < bestDistance && distance <= maxAllowed) {
                bestDistance = distance
                bestMatch = word
            }
        }
        return bestMatch
    }
    
    /**
     * Calculate string similarity using Levenshtein edit distance (0-1)
     */
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1f
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        // Quick check - if one is contained in the other
        if (longer.startsWith(shorter) || longer.endsWith(shorter)) return 0.9f
        if (longer.contains(shorter)) return 0.85f
        
        // True Levenshtein-based similarity
        val editDist = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        return (1f - editDist.toFloat() / maxLen).coerceIn(0f, 1f)
    }
    
    /** Delegate to shared implementation. */
    private fun levenshteinDistance(a: String, b: String): Int =
        EngineUtils.levenshteinDistance(a, b)
    
    /**
     * Generate character n-grams from a string
     */
    private fun generateNgrams(text: String, n: Int): Set<String> {
        if (text.length < n) return setOf(text)
        return (0..text.length - n).map { text.substring(it, it + n) }.toSet()
    }
    
    /**
     * Normalize title for deduplication
     */
    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(50)
    }

    /**
     * Normalize URL for cross-provider deduplication.
     * Strips scheme, www prefix, trailing slash, and common tracking params.
     */
    private fun normalizeUrl(url: String): String {
        return try {
            val u = java.net.URL(url)
            val host = u.host.removePrefix("www.").lowercase()
            val path = u.path.trimEnd('/').lowercase()
            "$host$path"
        } catch (_: Exception) {
            url.lowercase().trimEnd('/')
        }
    }
    
    /**
     * Calculate provider reliability score
     */
    private fun calculateProviderScore(providerResults: ProviderSearchResults): Float {
        val provider = providerResults.provider
        
        var score = 0.5f // Base score
        
        // Success rate factor
        if (provider.totalSearches > 0) {
            val successRate = 1f - (provider.failedSearches.toFloat() / provider.totalSearches)
            score += successRate * 0.3f
        }
        
        // Response time factor
        if (providerResults.searchTime < 1000) {
            score += 0.1f
        } else if (providerResults.searchTime < 3000) {
            score += 0.05f
        }
        
        // Health score from provider
        score += provider.healthScore * 0.1f
        
        return score.coerceIn(0f, 1f)
    }
    
    /**
     * Calculate freshness score based on date
     */
    private fun calculateFreshnessScore(date: String?): Float {
        if (date.isNullOrEmpty()) return 0.3f // Default for unknown dates
        
        // Try to parse relative dates
        val dateLower = date.lowercase()
        
        return when {
            dateLower.contains("today") || dateLower.contains("hour") -> 1.0f
            dateLower.contains("yesterday") -> 0.9f
            dateLower.contains("day") && extractNumber(dateLower) ?: 99 <= 7 -> 0.8f
            dateLower.contains("week") && extractNumber(dateLower) ?: 99 <= 2 -> 0.7f
            dateLower.contains("month") && extractNumber(dateLower) ?: 99 <= 1 -> 0.6f
            dateLower.contains("month") && extractNumber(dateLower) ?: 99 <= 3 -> 0.5f
            dateLower.contains("year") && extractNumber(dateLower) ?: 99 <= 1 -> 0.3f
            else -> 0.2f
        }
    }
    
    /**
     * Calculate engagement score based on user signals
     */
    private fun calculateEngagementScore(result: SearchResult): Float {
        var score = 0f
        var factors = 0
        
        // Seeders (for torrents)
        result.seeders?.let { seeders ->
            factors++
            score += when {
                seeders >= 1000 -> 1.0f
                seeders >= 100 -> 0.8f
                seeders >= 10 -> 0.5f
                seeders > 0 -> 0.3f
                else -> 0.1f
            }
        }
        
        // Views
        result.views?.let { views ->
            factors++
            score += when {
                views >= 1000000 -> 1.0f
                views >= 100000 -> 0.8f
                views >= 10000 -> 0.6f
                views >= 1000 -> 0.4f
                else -> 0.2f
            }
        }
        
        // Rating
        result.rating?.let { rating ->
            factors++
            score += (rating / 10f).coerceIn(0f, 1f)
        }
        
        return if (factors > 0) score / factors else 0.0f
    }
    
    /**
     * Calculate quality score based on content indicators
     */
    private fun calculateQualityScore(result: SearchResult): Float {
        var score = 0.0f
        
        // Quality indicator in title
        val qualityIndicators = mapOf(
            "4k" to 1.0f, "2160p" to 1.0f,
            "1080p" to 0.9f, "full hd" to 0.9f,
            "720p" to 0.7f, "hd" to 0.7f,
            "bluray" to 0.85f, "blu-ray" to 0.85f,
            "remux" to 0.95f, "web-dl" to 0.8f,
            "webrip" to 0.75f, "hdtv" to 0.7f,
            "cam" to 0.2f, "ts" to 0.25f,
            "screener" to 0.3f
        )
        
        val titleLower = result.title.lowercase()
        qualityIndicators.forEach { (indicator, qualityScore) ->
            if (titleLower.contains(indicator)) {
                score = max(score, qualityScore)
            }
        }
        
        // Explicit quality field
        result.quality?.let { quality ->
            val qualityLower = quality.lowercase()
            qualityIndicators.forEach { (indicator, qualityScore) ->
                if (qualityLower.contains(indicator)) {
                    score = max(score, qualityScore)
                }
            }
        }
        
        // Has thumbnail (indicates better content)
        if (!result.thumbnailUrl.isNullOrEmpty()) {
            score += 0.05f
        }
        
        // Has description
        if (!result.description.isNullOrEmpty()) {
            score += 0.05f
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    // Helper methods
    private fun countOccurrences(text: String, term: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(term, index)
            if (index == -1) break
            count++
            index += term.length
        }
        return count
    }
    
    private fun preservesWordOrder(text: String, terms: List<String>): Boolean {
        var lastIndex = -1
        for (term in terms) {
            val index = text.indexOf(term, lastIndex + 1)
            if (index == -1) return false
            lastIndex = index
        }
        return true
    }
    
    private fun extractNumber(text: String): Int? {
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }
    
    data class ScoredResult(
        val result: SearchResult,
        val providerScore: Float,
        val score: Float
    )
}
