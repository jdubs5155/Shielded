package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single search result captured from a provider.
 * Stores metadata for the UI and tracking for the AI refinement engine.
 */
@Entity(tableName = "results")
data class ResultItem(
    @PrimaryKey val id: String, // Use a hash of the URL or a unique ID from the site
    val providerName: String,
    val title: String,
    val description: String? = null,
    val url: String,
    val videoUrl: String? = null, // Path for the Media3 player to use
    val thumbnailUrl: String? = null,
    val isLiked: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
