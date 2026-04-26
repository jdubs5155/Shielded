package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.database.SearchQueryPatternDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Search Query Optimizer Engine
 *
 * Learns how to format search queries for each provider by:
 * 1. Analyzing search forms on provider sites
 * 2. Detecting query parameter names and formats
 * 3. Understanding pagination strategies
 * 4. Building provider-specific search URL templates
 * 5. Caching learned patterns for future use
 *
 * This ensures searches submitted to providers are formatted exactly
 * as the actual site expects, matching real user search results.
 */
@Singleton
class SearchQueryOptimizerEngine @Inject constructor(
    private val patternDao: SearchQueryPatternDao
) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Analyze a provider's HTML to learn its search form structure
     */
    suspend fun analyzeSearchForm(
        html: String,
        providerId: String,
        providerUrl: String
    ): SearchQueryPattern = withContext(Dispatchers.Default) {
        val document = Jsoup.parse(html, providerUrl)
        
        // Check for cached pattern first
        val cached = patternDao.getPatternForProvider(providerId)
        if (cached != null && cached.confidence > 0.7f) {
            return@withContext cached
        }
        
        // Find search form
        val searchForm = findSearchForm(document)
        
        val pattern = if (searchForm != null) {
            analyzeFormStructure(searchForm, providerId, html)
        } else {
            // If no form found, create pattern from common conventions
            createDefaultPattern(providerId, providerUrl)
        }

        // Persist the learned pattern so subsequent searches can use it immediately.
        patternDao.insertPattern(pattern)
        return@withContext pattern
    }
    
    /**
     * Find the search form in the document
     */
    private fun findSearchForm(document: Document): FormElement? {
        // Try common search form selectors
        val selectors = listOf(
            "form[action*='search']",
            "form[role='search']",
            "form#search",
            "form.search",
            ".search-form form",
            ".search form"
        )
        
        for (selector in selectors) {
            val forms = document.selectFirst(selector)
            if (forms is FormElement) {
                return forms
            }
        }
        
        // Fallback: look for any form that contains a search input
        for (form in document.select("form")) {
            for (input in form.select("input")) {
                val name = input.attr("name").lowercase()
                val placeholder = input.attr("placeholder").lowercase()
                if (name.contains("search") || name.contains("query") || name.contains("q") ||
                    placeholder.contains("search") || placeholder.contains("query")) {
                    return form as? FormElement
                }
            }
        }
        
        return null
    }
    
    /**
     * Analyze a found form's structure
     */
    private fun analyzeFormStructure(
        form: FormElement,
        providerId: String,
        rawHtml: String
    ): SearchQueryPattern {
        // Extract form properties
        val action = form.attr("action").ifEmpty { "/" }
        val method = form.attr("method").ifEmpty { "get" }.uppercase()
        
        // Find the main search input
        var searchInputName = "q"
        var searchInputSelector = ""
        var searchInputFound = false
        
        for (input in form.select("input")) {
            val name = input.attr("name").lowercase()
            val type = input.attr("type").lowercase()
            val placeholder = input.attr("placeholder").lowercase()
            
            if ((name.contains("search") || name.contains("query") || name == "q" ||
                 placeholder.contains("search") || placeholder.contains("query")) &&
                type != "hidden") {
                searchInputName = input.attr("name")
                searchInputSelector = generateSelector(input)
                searchInputFound = true
                break
            }
        }
        
        // Extract other form fields (categories, sort, filters, etc.)
        val requiredParams = mutableMapOf<String, String>()
        val optionalParams = mutableMapOf<String, String>()
        var categoryField: String? = null
        var sortField: String? = null
        val filterFields = mutableListOf<String>()
        
        for (input in form.select("input, select")) {
            val name = input.attr("name")
            if (name.isNotEmpty() && name != searchInputName) {
                val value = input.attr("value") ?: input.selectFirst("option[selected]")?.attr("value") ?: ""
                
                when {
                    name.lowercase().contains("category") -> {
                        categoryField = name
                        optionalParams[name] = value
                    }
                    name.lowercase().contains("sort") -> {
                        sortField = name
                        optionalParams[name] = value
                    }
                    input.attr("type") == "hidden" -> {
                        if (value.isNotEmpty()) {
                            requiredParams[name] = value
                        }
                    }
                    else -> {
                        filterFields.add(name)
                        if (value.isNotEmpty()) {
                            optionalParams[name] = value
                        }
                    }
                }
            }
        }
        
        // Build search URL template
        val searchUrl = buildSearchUrl(action, method, searchInputName)
        
        // Detect pagination strategy
        val paginationStrategy = detectPaginationStrategy(form)
        
        // Confidence score based on how completely we understood the form
        val confidence = when {
            searchInputFound && searchUrl.contains("{query}") -> 0.95f
            searchUrl.contains("{query}") -> 0.85f
            else -> 0.5f
        }
        
        return SearchQueryPattern(
            providerId = providerId,
            searchFormSelector = generateSelector(form),
            searchInputName = searchInputName,
            searchMethod = method,
            searchUrlTemplate = searchUrl,
            requiredParams = Json.encodeToString(requiredParams),
            optionalParams = Json.encodeToString(optionalParams),
            categoryField = categoryField,
            sortField = sortField,
            filterFields = Json.encodeToString(filterFields),
            paginationStrategy = paginationStrategy,
            confidence = confidence,
            learnedCount = 1,
            rawFormHtml = rawHtml.take(5000) // Store sample for debugging
        )
    }
    
    /**
     * Build a search URL template from form action
     */
    private fun buildSearchUrl(action: String, method: String, queryParam: String): String {
        return if (method == "POST") {
            "POST:$action"
        } else {
            val baseUrl = if (action.startsWith("/") || action.startsWith("http")) {
                action
            } else {
                "/$action"
            }
            if (baseUrl.contains("?")) {
                "$baseUrl&$queryParam={query}"
            } else {
                "$baseUrl?$queryParam={query}"
            }
        }
    }
    
    /**
     * Detect how the site handles pagination
     */
    private fun detectPaginationStrategy(form: FormElement): PaginationStrategy {
        val inputs = form.select("input, select")
        
        for (input in inputs) {
            val name = input.attr("name").lowercase()
            when {
                name.contains("page") -> return PaginationStrategy.PAGE_NUMBER
                name.contains("offset") -> return PaginationStrategy.OFFSET
                name.contains("cursor") -> return PaginationStrategy.CURSOR
                name.contains("limit") || name.contains("perpage") -> return PaginationStrategy.PAGE_NUMBER
            }
        }
        
        return PaginationStrategy.PAGE_NUMBER // Default
    }
    
    /**
     * Create a default pattern when no form is found
     */
    private fun createDefaultPattern(providerId: String, providerUrl: String): SearchQueryPattern {
        // Use common URL patterns
        val baseUrl = extractDomain(providerUrl)
        val searchUrl = "$baseUrl/search?q={query}"
        
        return SearchQueryPattern(
            providerId = providerId,
            searchInputName = "q",
            searchMethod = "GET",
            searchUrlTemplate = searchUrl,
            confidence = 0.3f, // Low confidence for default pattern
            learnedCount = 0
        )
    }
    
    /**
     * Generate a CSS selector for an element
     */
    private fun generateSelector(element: Element): String {
        val id = element.id()
        if (id.isNotEmpty()) return "#$id"
        
        val classes = element.classNames().joinToString(".")
        if (classes.isNotEmpty()) return ".${element.tagName()}.$classes".replace("..", ".")
        
        val name = element.attr("name")
        if (name.isNotEmpty()) return "${element.tagName()}[name='$name']"
        
        return element.tagName()
    }
    
    /**
     * Extract domain from URL
     */
    private fun extractDomain(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            url.substringBefore("/search").substringBefore("/browse")
        }
    }
    
    /**
     * Format a search query according to a provider's learned pattern
     * Returns the formatted URL/parameters to use for searching
     */
    suspend fun formatSearchQuery(
        query: String,
        providerId: String,
        pattern: SearchQueryPattern? = null,
        page: Int = 1
    ): String = withContext(Dispatchers.Default) {
        val actualPattern = pattern ?: patternDao.getPatternForProvider(providerId)
            ?: return@withContext "$query" // Fallback

        val template = actualPattern.searchUrlTemplate ?: return@withContext query

        // Replace placeholders with escaped query and page values
        val escapedQuery = escapeQueryForUrl(query, actualPattern.queryEncoding)
        return@withContext template
            .replace("{query}", escapedQuery)
            .replace("{page}", page.toString())
    }

    /**
     * Properly escape a search query for URL usage
     */
    private fun escapeQueryForUrl(query: String, encoding: String = "UTF-8"): String {
        return java.net.URLEncoder.encode(query, encoding)
    }
    
    /**
     * Learn from a successful search to improve the pattern
     */
    suspend fun learnFromSuccess(
        providerId: String,
        query: String,
        usedPattern: SearchQueryPattern,
        resultCount: Int
    ) = withContext(Dispatchers.IO) {
        // Increase confidence and learned count
        val updatedPattern = usedPattern.copy(
            confidence = minOf(usedPattern.confidence + 0.02f, 1.0f),
            learnedCount = usedPattern.learnedCount + 1,
            lastUpdated = System.currentTimeMillis()
        )
        
        patternDao.updatePattern(updatedPattern)
    }
    
    /**
     * Learn from a failed search to adjust the pattern
     */
    suspend fun learnFromFailure(
        providerId: String,
        query: String,
        usedPattern: SearchQueryPattern
    ) = withContext(Dispatchers.IO) {
        // Decrease confidence for this pattern
        val updatedPattern = usedPattern.copy(
            confidence = maxOf(usedPattern.confidence - 0.1f, 0.0f),
            lastUpdated = System.currentTimeMillis()
        )
        
        patternDao.updatePattern(updatedPattern)
    }
    
    /**
     * Get the best pattern for a provider, or analyze if missing
     */
    suspend fun getOptimalPattern(
        providerId: String,
        providerHtml: String? = null,
        providerUrl: String? = null
    ): SearchQueryPattern? = withContext(Dispatchers.IO) {
        val existing = patternDao.getPatternForProvider(providerId)
        
        return@withContext when {
            existing != null && existing.confidence > 0.7f -> existing
            existing != null && providerHtml != null && providerUrl != null -> {
                // Re-analyze to improve confidence
                val updated = analyzeSearchForm(providerHtml, providerId, providerUrl)
                patternDao.insertPattern(updated)
                updated
            }
            existing == null && providerHtml != null && providerUrl != null -> {
                // Learn a new pattern on demand from the provider HTML.
                val learned = analyzeSearchForm(providerHtml, providerId, providerUrl)
                patternDao.insertPattern(learned)
                learned
            }
            else -> existing
        }
    }
}
