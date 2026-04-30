package com.shielded.aggregatorx.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val isEnabled: Boolean = true,
    
    // Pagination State
    val currentPage: Int = 1,
    val nextPageUrl: String? = null,
    val paginationType: String = "OFFSET", // OFFSET, PAGE, or TOKEN
    
    // Consent & Compliance
    val hasUserConsent: Boolean = false,
    val lastActionTimestamp: Long = System.currentTimeMillis()
)
