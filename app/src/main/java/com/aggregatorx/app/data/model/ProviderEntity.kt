package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Defines the types of pagination a provider might use.
 */
enum class PaginationType {
    PAGE_NUMBER, // e.g., ?page=2
    OFFSET,      // e.g., ?offset=20
    URL_TOKEN    // e.g., uses a "next" link from the response
}

/**
 * Represents a search provider (website) and its scraping configuration.
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val name: String,
    val baseUrl: String,
    val searchPath: String,
    val paginationType: PaginationType,
    val isEnabled: Boolean = true,
    val nextPageUrl: String? = null // Used for URL_TOKEN pagination
)
