package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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
    GENERAL, STREAMING, TORRENT, NEWS, SOCIAL, MEDIA, API_BASED, CUSTOM
}

/**
 * Site Analysis Result - Complete analysis of a website's structure
 */
@Entity(
    tableName = "site_analysis",
    foreignKeys = [
        ForeignKey(
            entity = Provider::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["providerId"])]
)
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
    val detectedPatterns: String = "[]", // JSON array
    val navigationStructure: String = "{}", // JSON object
    val contentAreas: String = "[]", // JSON array
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
    val apiEndpoints: String = "[]", 
    val apiType: String? = null, 
    
    // Performance Metrics
    val loadTime: Long = 0L,
    val resourceCount: Int = 0,
    val totalSize: Long = 0L,
    
    // Scraping Config
    val scrapingStrategy: ScrapingStrategy = ScrapingStrategy.HTML_PARSING,
    val requiresJavaScript: Boolean = false,
    val requiresAuth: Boolean = false,
    val rateLimit: Int = 10,
    val retryCount: Int = 3,
    
    // Raw data
    val rawHtml: String? = null,
    val headers: String = "{}",
    val cookies: String = "[]"
)

@Serializable
enum class ScrapingStrategy {
    HTML_PARSING, DYNAMIC_CONTENT, API_BASED, HYBRID, HEADLESS_BROWSER, TAB_CRAWL
}

/**
 * Search Result - Individual result (Not an Entity, used for UI/Transfer)
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
    SEARCH_FORM, RESULT_LIST, RESULT_ITEM, PAGINATION, VIDEO_PLAYER, VIDEO_LIST,
    NAVIGATION, SIDEBAR, FOOTER, HEADER, CONTENT_AREA, THUMBNAIL_GRID,
    CARD_LAYOUT, TABLE_LAYOUT, INFINITE_SCROLL, LOAD_MORE_BUTTON, FILTER_PANEL,
    SORT_OPTIONS, CATEGORY_LIST, TAG_CLOUD, RATING_SYSTEM, COMMENT_SECTION,
    RELATED_CONTENT, ADVERTISEMENT, LOGIN_FORM, API_ENDPOINT
}

/**
 * Scraping Configuration for a specific provider
 */
@Entity(
    tableName = "scraping_configs",
    foreignKeys = [
        ForeignKey(
            entity = Provider::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["providerId"])]
)
@Serializable
data class ScrapingConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val searchUrlTemplate: String,
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
    val headers: String = "{}",
    val cookies: String = "{}",
    val postData: String? = null,
    val encoding: String = "UTF-8",
    val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36", 
    val timeout: Int = 30000,
    val retryCount: Int = 3,
    val retryDelay: Long = 1000,
    val rateLimitMs: Long = 500
)

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

@Entity(tableName = "user_preferences")
@Serializable
data class UserPreferences(
    @PrimaryKey
    val id: Int = 1,
    val clickedCategories: String = "[]",
    val watchedGenres: String = "[]",
    val preferredQualities: String = "[\"1080p\", \"720p\"]",
    val recentClicks: String = "[]",
    val favoriteProviders: String = "[]",
    val lastUpdated: Long = System.currentTimeMillis()
)

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
    val titleKeywords: String = "[]"
)

@Entity(tableName = "learned_profile")
@Serializable
data class LearnedUserProfile(
    @PrimaryKey
    val id: Int = 1,
    val preferredKeywords: String = "",
    val preferredProviders: String = "",
    val preferredCategories: String = "",
    val preferredQualities: String = "",
    val totalLikes: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    private fun parseWeightMap(raw: String): Map<String, Float> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val weight = parts[1].toFloatOrNull() ?: 0f
                if (key.isNotEmpty()) key to weight else null
            } else null
        }.toMap()
    }

    fun preferredKeywordsMap(): Map<String, Float> = parseWeightMap(preferredKeywords)
    fun preferredProvidersMap(): Map<String, Float> = parseWeightMap(preferredProviders)
    fun preferredCategoriesMap(): Map<String, Float> = parseWeightMap(preferredCategories)
    fun preferredQualitiesMap(): Map<String, Float> = parseWeightMap(preferredQualities)
}
