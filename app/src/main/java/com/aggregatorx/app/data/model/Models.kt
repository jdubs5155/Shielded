package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Provider - Represents a configured content provider/website
 */
@Entity(tableName = "providers")
@Serializable
data class Provider(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val baseUrl: String,
    val isEnabled: Boolean = true,
    val iconUrl: String? = null,
    val description: String? = null,
    val category: ProviderCategory = ProviderCategory.GENERAL,
    val lastAnalyzed: Long = System.currentTimeMillis(),
    val analysisVersion: Int = 1,
    val healthScore: Float = 1.0f,
    val avgResponseTime: Long = 0L,
    val successRate: Float = 1.0f,
    val totalSearches: Int = 0,
    val failedSearches: Int = 0
)

@Serializable
enum class ProviderCategory {
    GENERAL,
    STREAMING,
    TORRENT,
    NEWS,
    SOCIAL,
    MEDIA,
    API_BASED,
    CUSTOM
}

/**
 * Site Analysis Result - Complete analysis of a website's structure
 */
@Entity(tableName = "site_analysis")
@Serializable
data class SiteAnalysis(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val url: String,
    val analyzedAt: Long = System.currentTimeMillis(),
    
    // Security Analysis
    val securityScore: Float = 0f,
    val hasSSL: Boolean = false,
    val sslVersion: String? = null,
    val hasCSP: Boolean = false,
    val hasXFrameOptions: Boolean = false,
    val hasHSTS: Boolean = false,
    val cookieFlags: String? = null,
    
    // Structure Analysis
    val domDepth: Int = 0,
    val totalElements: Int = 0,
    val uniqueTags: Int = 0,
    val formCount: Int = 0,
    val linkCount: Int = 0,
    val scriptCount: Int = 0,
    val iframeCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    
    // Pattern Detection
    val detectedPatterns: String = "[]", // JSON array of patterns
    val navigationStructure: String = "{}", // JSON object
    val contentAreas: String = "[]", // JSON array of content selectors
    val searchFormSelector: String? = null,
    val searchInputSelector: String? = null,
    val resultContainerSelector: String? = null,
    val resultItemSelector: String? = null,
    val paginationSelector: String? = null,
    
    // Media Detection
    val videoPlayerType: String? = null,
    val videoSourcePattern: String? = null,
    val thumbnailSelector: String? = null,
    val titleSelector: String? = null,
    val descriptionSelector: String? = null,
    val dateSelector: String? = null,
    val ratingSelector: String? = null,
    
    // API Detection
    val hasAPI: Boolean = false,
    val apiEndpoints: String = "[]", // JSON array
    val apiType: String? = null, // REST, GraphQL, etc.
    
    // Performance Metrics
    val loadTime: Long = 0L,
    val resourceCount: Int = 0,
    val totalSize: Long = 0L,
    
    // Scraping Config
    val scrapingStrategy: ScrapingStrategy = ScrapingStrategy.HTML_PARSING,
    val requiresJavaScript: Boolean = false,
    val requiresAuth: Boolean = false,
    val rateLimit: Int = 10, // requests per minute
    val retryCount: Int = 3,
    
    // Raw data
    val rawHtml: String? = null,
    val headers: String = "{}", // JSON object
    val cookies: String = "[]" // JSON array
)

@Serializable
enum class ScrapingStrategy {
    HTML_PARSING,      // Simple Jsoup parsing
    DYNAMIC_CONTENT,   // Requires JavaScript execution
    API_BASED,         // Direct API calls
    HYBRID,            // Combination of methods
    HEADLESS_BROWSER,  // Full browser simulation
    TAB_CRAWL          // Navigate/click category tabs when no search is available
}

/**
 * Search Result - Individual result from a provider
 */
@Serializable
data class SearchResult(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val providerName: String,
    val title: String,
    val url: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val category: String? = null,
    val date: String? = null,
    val size: String? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val rating: Float? = null,
    val views: Long? = null,
    val duration: String? = null,
    val quality: String? = null,
    val relevanceScore: Float = 0f,
    val matchedTerms: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Provider Search Results - Grouped results from a single provider
 */
@Serializable
data class ProviderSearchResults(
    val provider: Provider,
    val results: List<SearchResult>,
    val searchTime: Long,
    val success: Boolean,
    val errorMessage: String? = null,
    val totalResults: Int = results.size,
    val hasMore: Boolean = false,
    val nextPageUrl: String? = null
)

/**
 * Aggregated Search Results - All results from all providers
 */
@Serializable
data class AggregatedSearchResults(
    val query: String,
    val providerResults: List<ProviderSearchResults>,
    val totalResults: Int,
    val searchTime: Long,
    val successfulProviders: Int,
    val failedProviders: Int,
    val topResults: List<SearchResult> = emptyList(),
    val relatedResults: List<SearchResult> = emptyList()
)

/**
 * DOM Element Analysis
 */
@Serializable
data class DOMElement(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val textContent: String? = null,
    val childCount: Int = 0,
    val depth: Int = 0,
    val selector: String = "",
    val isInteractive: Boolean = false,
    val isContentContainer: Boolean = false
)

/**
 * Pattern Detection Result
 */
@Serializable
data class DetectedPattern(
    val type: PatternType,
    val selector: String,
    val confidence: Float,
    val sampleContent: String? = null,
    val occurrences: Int = 0
)

@Serializable
enum class PatternType {
    SEARCH_FORM,
    RESULT_LIST,
    RESULT_ITEM,
    PAGINATION,
    VIDEO_PLAYER,
    VIDEO_LIST,
    NAVIGATION,
    SIDEBAR,
    FOOTER,
    HEADER,
    CONTENT_AREA,
    THUMBNAIL_GRID,
    CARD_LAYOUT,
    TABLE_LAYOUT,
    INFINITE_SCROLL,
    LOAD_MORE_BUTTON,
    FILTER_PANEL,
    SORT_OPTIONS,
    CATEGORY_LIST,
    TAG_CLOUD,
    RATING_SYSTEM,
    COMMENT_SECTION,
    RELATED_CONTENT,
    ADVERTISEMENT,
    LOGIN_FORM,
    API_ENDPOINT
}

/**
 * Scraping Configuration for a specific provider
 */
@Entity(tableName = "scraping_configs")
@Serializable
data class ScrapingConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val searchUrlTemplate: String, // e.g., "{baseUrl}/search?q={query}&page={page}"
    val resultSelector: String,
    val titleSelector: String,
    val urlSelector: String,
    val descriptionSelector: String? = null,
    val thumbnailSelector: String? = null,
    val dateSelector: String? = null,
    val sizeSelector: String? = null,
    val seedersSelector: String? = null,
    val leechersSelector: String? = null,
    val ratingSelector: String? = null,
    val categorySelector: String? = null,
    val nextPageSelector: String? = null,
    val headers: String = "{}", // JSON object of custom headers
    val cookies: String = "{}", // JSON object of cookies
    val postData: String? = null, // For POST requests
    val encoding: String = "UTF-8",
    val userAgent: String = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT,
    val timeout: Int = 30000,
    val retryCount: Int = 3,
    val retryDelay: Long = 1000,
    val rateLimitMs: Long = 500
)

/**
 * Search History Entry
 */
@Entity(tableName = "search_history")
@Serializable
data class SearchHistoryEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val timestamp: Long = System.currentTimeMillis(),
    val resultCount: Int = 0,
    val providersSearched: Int = 0,
    val successfulProviders: Int = 0
)

/**
 * User Preferences - Tracks user behavior for intelligent suggestions
 * When no results found for query, uses these preferences to suggest content
 */
@Entity(tableName = "user_preferences")
@Serializable
data class UserPreferences(
    @PrimaryKey
    val id: Int = 1, // Single row for user preferences
    val clickedCategories: String = "[]", // JSON array of clicked categories
    val watchedGenres: String = "[]", // JSON array of watched genres
    val preferredQualities: String = "[\"1080p\", \"720p\"]", // JSON array of preferred qualities
    val recentClicks: String = "[]", // JSON array of recently clicked items
    val favoriteProviders: String = "[]", // JSON array of favorite provider IDs
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Liked Result - Tracks individual results the user has liked/thumbs-up'd.
 * The learning system analyses these to discover user preferences over time
 * and boost similar content in the Top Results list.
 */
@Entity(tableName = "liked_results")
@Serializable
data class LikedResult(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val providerId: String,
    val providerName: String,
    val category: String? = null,
    val quality: String? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val seeders: Int? = null,
    val rating: Float? = null,
    val likedAt: Long = System.currentTimeMillis(),
    // Extracted keywords from title for preference learning
    val titleKeywords: String = "[]" // JSON array of lowercase keywords
)

/**
 * Learned User Profile - Aggregated preference model built from liked results.
 * Updated periodically as the user likes more content.
 */
@Entity(tableName = "learned_profile")
@Serializable
data class LearnedUserProfile(
    @PrimaryKey
    val id: Int = 1, // Single row
    val preferredKeywords: String = "", // Serialised: "key:0.5;key2:1.0"
    val preferredProviders: String = "",
    val preferredCategories: String = "",
    val preferredQualities: String = "",
    val totalLikes: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /** Parse "key:weight;key2:weight" into a Map<String, Float> */
    private fun parseWeightMap(raw: String): Map<String, Float> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) parts[0] to (parts[1].toFloatOrNull() ?: 0f) else null
        }.toMap()
    }

    fun preferredKeywordsMap(): Map<String, Float> = parseWeightMap(preferredKeywords)
    fun preferredProvidersMap(): Map<String, Float> = parseWeightMap(preferredProviders)
    fun preferredCategoriesMap(): Map<String, Float> = parseWeightMap(preferredCategories)
    fun preferredQualitiesMap(): Map<String, Float> = parseWeightMap(preferredQualities)
}

/**
 * Video Item for results feed
 */
@Serializable
data class VideoItem(
    val thumbnail: String,
    val title: String,
    val url: String
)

/**
 * Learned Search Query Pattern - Stores how a provider expects search queries
 * to be formatted. Built through analysis of search forms and successful searches.
 */
@Entity(tableName = "search_query_patterns")
@Serializable
data class SearchQueryPattern(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val searchFormSelector: String? = null,
    val searchInputName: String = "q", // Field name for query parameter
    val searchMethod: String = "GET", // GET or POST
    val searchUrlTemplate: String?, // e.g., "/search?q={query}"
    val queryEncoding: String = "UTF-8",
    val requiredParams: String = "{}", // JSON object of always-required params
    val optionalParams: String = "{}", // JSON object of optional params with defaults
    val categoryField: String? = null, // Field name for category selection
    val sortField: String? = null, // Field name for sorting
    val filterFields: String = "[]", // JSON array of available filter fields
    val parseSuccessIndicators: String = "[]", // JSON array of indicators that search was successful
    val paginationStrategy: PaginationStrategy = PaginationStrategy.PAGE_NUMBER, // How pagination works
    val pageParam: String = "page", // Parameter name for page number
    val perPageParam: String = "limit", // Parameter name for results per page
    val defaultPerPage: Int = 20,
    val confidence: Float = 0f, // 0.0-1.0 confidence score from analysis
    val learnedCount: Int = 0, // Number of successful searches used to build this pattern
    val lastUpdated: Long = System.currentTimeMillis(),
    val rawFormHtml: String? = null // For debugging
)

@Serializable
enum class PaginationStrategy {
    PAGE_NUMBER,      // page=1, page=2, etc.
    OFFSET,           // offset=0, offset=20, etc.
    CURSOR,           // cursor=xyz123
    LOAD_MORE,        // Click load more button
    INFINITE_SCROLL,  // Scroll to load more
    NEXT_URL          // Follow next page URL
}

/**
 * Navigation Pattern - Stores discovered navigation structure (tabs, categories, etc.)
 * for sites that use category/tab navigation instead of search
 */
@Entity(tableName = "navigation_patterns")
@Serializable
data class NavigationPattern(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val navigationSelector: String, // CSS selector for main navigation container
    val tabSelector: String, // CSS selector for individual tabs/categories
    val tabNameAttribute: String = "innerText", // What attribute contains the tab name
    val linkSelector: String? = null, // CSS selector for the link within tab
    val resultContainerSelector: String, // Where results appear after clicking tab
    val categories: String = "[]", // JSON array of discovered category names
    val selectedCategory: String? = null, // Currently selected category
    val navigationType: NavigationType = NavigationType.TAB_CLICK,
    val confidence: Float = 0f,
    val lastDiscovered: Long = System.currentTimeMillis()
)

@Serializable
enum class NavigationType {
    TAB_CLICK,        // Click tabs to switch categories
    DROPDOWN,         // Dropdown menu selection
    SIDEBAR_MENU,     // Sidebar category menu
    HORIZONTAL_MENU,  // Horizontal navigation buttons
    FILTER_PANEL      // Expandable filter panel
}

/**
 * In-App Result Viewer Configuration - Determines if/how a result can be viewed in-app
 */
@Entity(tableName = "result_view_configs")
@Serializable
data class ResultViewConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val viewerType: ResultViewerType = ResultViewerType.EXTERNAL_LINK,
    val webViewWhitelist: String = "[]", // JSON array of URL patterns that can be viewed in WebView
    val webViewBlacklist: String = "[]", // JSON array of URL patterns to block in WebView
    val enableInlineVideo: Boolean = true,
    val enableImageGallery: Boolean = false,
    val enableFullScreenPreview: Boolean = true,
    val customCSS: String? = null, // CSS to apply for in-app viewing
    val injectJavaScript: String? = null, // JS to inject for in-app enhancements
    val blockExternalLinks: Boolean = false,
    val allowDownloads: Boolean = true,
    val confidence: Float = 1f,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Serializable
enum class ResultViewerType {
    EXTERNAL_LINK,    // Open in external browser
    IN_APP_WEBVIEW,   // Open in embedded WebView
    IN_APP_CUSTOM,    // Custom in-app viewer
    VIDEO_PLAYER,     // Open in video player
    IMAGE_GALLERY,    // Open in image gallery
    MEDIA_PLAYER      // Native media player
}
