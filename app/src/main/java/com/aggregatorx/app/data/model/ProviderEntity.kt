package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Defines the types of pagination a provider might use.
 */
enum class PaginationType {
    PAGE_NUMBER, // e.g., ?page=2  (substituted into searchPath via `{page}`)
    OFFSET,      // e.g., ?offset=20 (substituted into searchPath via `{page}`, but multiplied)
    URL_TOKEN    // e.g., uses a "next" link harvested from the previous response (`nextPageUrl`)
}

/**
 * Represents a search provider (website) and its scraping configuration.
 *
 * Pagination state lives on the entity so that:
 *  - the user-facing refresh / back / forward controls operate on a single
 *    provider's slice of the result map, not the whole search,
 *  - the next-page URL discovered by URL_TOKEN providers is persisted across
 *    process death,
 *  - `currentPage` survives across cold starts so the in-app browser /
 *    video player can return without losing position.
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val name: String,
    val baseUrl: String,
    val searchPath: String,
    val paginationType: PaginationType,
    val isEnabled: Boolean = true,
    /** 1-based page index; for OFFSET providers the offset = (currentPage - 1) * pageSize. */
    val currentPage: Int = 1,
    /** Page size used for OFFSET pagination. */
    val pageSize: Int = 20,
    /** Used for URL_TOKEN pagination — populated from the previous response's "next" link. */
    val nextPageUrl: String? = null
)
