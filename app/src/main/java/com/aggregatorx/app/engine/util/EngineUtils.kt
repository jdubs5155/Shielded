package com.aggregatorx.app.engine.util

/**
 * Shared utility functions used across multiple engine components.
 * Consolidates duplicated code (URL normalization, domain extraction,
 * Levenshtein distance, user agent management) into a single source of truth.
 */
object EngineUtils {

    // ═══════════════════════════════════════════════════════════════════
    //  USER AGENTS (single source of truth for the entire app)
    // ═══════════════════════════════════════════════════════════════════

    /** Default user-agent string — Chrome 132 on Windows (Feb 2026). */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    /** Pool of realistic user-agents for rotation (Chrome 132, Firefox 135, Safari 17.6, Edge 132). */
    val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.0",
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.83 Mobile Safari/537.36"
    )

    /** Returns a random user-agent from the pool. */
    fun getRandomUserAgent(): String = USER_AGENTS.random()

    // ═══════════════════════════════════════════════════════════════════
    //  URL UTILITIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Normalize a relative or absolute URL against a base URL.
     * Handles protocol-relative, root-relative, and relative paths.
     */
    fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "${baseUrl.trimEnd('/')}$url"
            url.isEmpty() -> baseUrl
            else -> "${baseUrl.trimEnd('/')}/$url"
        }
    }

    /**
     * Normalize a full URL (add scheme if missing, strip trailing slash).
     */
    fun normalizeFullUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }

    /**
     * Extract the domain (host without "www." prefix) from a URL.
     */
    fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host.removePrefix("www.")
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Extract the base URL (scheme + host) from a full URL.
     */
    fun extractBaseUrl(url: String): String {
        return try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) {
            url
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STRING DISTANCE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Compute the Levenshtein edit distance between two strings.
     * Uses an optimized single-row DP approach — O(min(m,n)) space.
     */
    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Ensure s1 is the shorter string for space optimization
        val s1 = if (a.length <= b.length) a else b
        val s2 = if (a.length <= b.length) b else a

        var prevRow = IntArray(s1.length + 1) { it }
        var currRow = IntArray(s1.length + 1)

        for (j in 1..s2.length) {
            currRow[0] = j
            for (i in 1..s1.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[i] = minOf(
                    currRow[i - 1] + 1,      // insertion
                    prevRow[i] + 1,           // deletion
                    prevRow[i - 1] + cost     // substitution
                )
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }
        return prevRow[s1.length]
    }

    /**
     * Maximum edit distance allowed for fuzzy matching based on word length.
     * Short words require exact matches; longer words allow more edits.
     */
    fun maxEditDistance(word: String): Int {
        return when {
            word.length <= 3 -> 0
            word.length <= 5 -> 1
            else -> 2  // capped at 2 to prevent false positives
        }
    }
}
