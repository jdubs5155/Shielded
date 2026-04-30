package com.shielded.aggregatorx.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: String, // SCRAPE, TOKEN_EXTRACTION, PROXY_CHANGE, CONSENT_GRANT
    val providerId: String?,
    val details: String,
    val isSuccess: Boolean,
    val metadata: String? = null // For storing JSON strings of headers or AI reasoning
)
