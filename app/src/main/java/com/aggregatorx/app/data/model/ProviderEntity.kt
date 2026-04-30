package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a content provider (source) with its specific navigation 
 * and pagination state.
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val name: String,
    val isEnabled: Boolean = true,
    val requiresConsent: Boolean = true,
    val hasConsent: Boolean = false,
    
    // Pagination state
    var currentPage: Int = 1,
    var nextPageUrl: String? = null,
    val paginationType: PaginationType = PaginationType.PAGE_NUMBER,
    
    // Base configuration
    val baseUrl: String,
    val searchPath: String
)

enum class PaginationType {
    PAGE_NUMBER, // Uses ?page=2
    OFFSET,      // Uses ?offset=20
    URL_TOKEN    // Uses a specific nextPageUrl provided by the site
}
