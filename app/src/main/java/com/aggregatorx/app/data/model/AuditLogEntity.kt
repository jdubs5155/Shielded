package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores security and operational logs for the application.
 * Captures AI performance, network responses, and bypass attempts.
 */
@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: String,      // e.g., "AI_INFERENCE", "NETWORK_REQUEST", "BYPASS_SUCCESS"
    val providerName: String?,   // The site involved, if applicable
    val details: String,         // Specific error messages or metadata
    val isSuccess: Boolean = true
)
