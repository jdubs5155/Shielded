package com.aggregatorx.app.engine.ai

import android.util.Base64
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One JWT we managed to pull off a page or out of a response header.
 *
 * `payloadJson` is the decoded body if the token parsed cleanly. The other
 * fields are surfaced separately because they're the ones a scraping
 * pipeline tends to act on (decide whether to re-use a token, when it
 * expires, what API surface it grants).
 */
data class JwtInfo(
    val raw: String,
    val header: JSONObject?,
    val payload: JSONObject?,
    val issuer: String?,
    val subject: String?,
    val audience: String?,
    val expiresAtSec: Long?,
    val issuedAtSec: Long?,
    val scope: String?
) {
    val isExpired: Boolean
        get() {
            val exp = expiresAtSec ?: return false
            return exp < (System.currentTimeMillis() / 1_000)
        }
}

/**
 * Extracts and analyzes JWTs / Bearer tokens / cookie-style auth blobs from
 * scraped pages and HTTP headers. Used by the scraping pipeline to decide
 * whether a captured session can be re-used by subsequent requests.
 */
@Singleton
class TokenAnalyzer @Inject constructor() {

    /**
     * Pull every JWT-shaped string out of a free-form blob (HTML, JSON, etc.).
     * Each match is decoded so the caller can immediately make an
     * expiry/usefulness decision without a second pass.
     */
    fun extractJwts(blob: String): List<JwtInfo> {
        if (blob.isBlank()) return emptyList()
        return JWT_REGEX.findAll(blob)
            .map { it.value }
            .distinct()
            .mapNotNull { decodeJwt(it) }
            .toList()
    }

    /**
     * Pull the JWT from a `Bearer <token>` style HTTP `Authorization` header.
     * Returns null if the header is missing, malformed, or non-bearer.
     */
    fun jwtFromAuthHeader(header: String?): JwtInfo? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return decodeJwt(parts[1])
    }

    /**
     * Decode a single JWT. Returns null if it doesn't have three base64url
     * segments or the payload doesn't parse as JSON.
     */
    fun decodeJwt(token: String): JwtInfo? {
        val parts = token.split(".")
        if (parts.size != 3) return null

        val header  = decodeSegment(parts[0])
        val payload = decodeSegment(parts[1]) ?: return null

        return JwtInfo(
            raw = token,
            header = header,
            payload = payload,
            issuer       = payload.optString("iss").ifBlankNull(),
            subject      = payload.optString("sub").ifBlankNull(),
            audience     = payload.optString("aud").ifBlankNull(),
            expiresAtSec = payload.optLongOrNull("exp"),
            issuedAtSec  = payload.optLongOrNull("iat"),
            scope        = payload.optString("scope").ifBlankNull()
                ?: payload.optString("scp").ifBlankNull()
        )
    }

    /**
     * Score how "useful" a captured token looks. Used to decide whether to
     * promote a sniffed token onto subsequent requests against the same
     * provider.
     */
    fun usefulnessScore(jwt: JwtInfo): Float {
        if (jwt.isExpired) return 0f
        var score = 0.5f
        if (!jwt.scope.isNullOrBlank()) score += 0.2f
        if (!jwt.audience.isNullOrBlank()) score += 0.1f
        if (!jwt.subject.isNullOrBlank()) score += 0.1f
        if (jwt.expiresAtSec != null) score += 0.1f
        return score.coerceAtMost(1f)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun decodeSegment(segment: String): JSONObject? = try {
        // JWTs use base64url (no padding). Re-pad before decoding.
        val padded = segment.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val bytes = Base64.decode(padded, Base64.DEFAULT)
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (_: Throwable) {
        null
    }

    private fun String.ifBlankNull(): String? = takeIf { it.isNotBlank() }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    }

    companion object {
        private val JWT_REGEX =
            Regex("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+")
    }
}
