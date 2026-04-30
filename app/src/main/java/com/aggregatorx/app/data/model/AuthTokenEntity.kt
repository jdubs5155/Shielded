package com.aggregatorx.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A captured authentication token (JWT, bearer token, csrf, etc.) tied to
 * a specific host. The lifecycle is:
 *
 *   UNTESTED → ACTIVE      (on first 2xx response that used it)
 *           → FAILED       (on 401/403 response that used it)
 *           → EXPIRED      (when JWT `exp` is past)
 *
 * Tokens that go FAILED or EXPIRED are removed from the in-memory cache by
 * [com.aggregatorx.app.engine.auth.TokenStore]; we keep the row in the DB
 * for audit but never re-inject it.
 */
@Entity(tableName = "auth_tokens")
data class AuthTokenEntity(
    /** Stable id derived from `${host}::${shortHash(token)}`. */
    @PrimaryKey val id: String,
    val host: String,
    /** The full token value (e.g. the raw JWT). Used as `Authorization: Bearer <value>`. */
    val value: String,
    /** Header name to inject under. Defaults to `Authorization` for bearer tokens. */
    val headerName: String = "Authorization",
    /** If true, the value is wrapped in `Bearer <value>` before being sent. */
    val isBearer: Boolean = true,
    val status: String = STATUS_UNTESTED,
    /** Unix seconds for JWT exp, if known. */
    val expiresAtSec: Long? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0
) {
    companion object {
        const val STATUS_UNTESTED = "UNTESTED"
        const val STATUS_ACTIVE   = "ACTIVE"
        const val STATUS_FAILED   = "FAILED"
        const val STATUS_EXPIRED  = "EXPIRED"
    }
}
