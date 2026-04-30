package com.aggregatorx.app.engine.auth

import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.data.database.AuthTokenDao
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.AuthTokenEntity
import com.aggregatorx.app.engine.ai.JwtInfo
import com.aggregatorx.app.engine.ai.TokenAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative store for captured authentication tokens.
 *
 * Lifecycle (driven from elsewhere):
 *   - [recordCapturedTokens] : harvested tokens land here via the headless
 *     scraper / interceptor. JWT-shaped values are auto-decoded so we can
 *     skip already-expired ones.
 *   - [bestTokenForHost]     : the [TokenInjectorInterceptor] consults this
 *     synchronously on every outbound request.
 *   - [reportSuccess]/[reportFailure] : the interceptor reports back after
 *     the response, mutating the in-memory cache + the persisted row.
 *
 * The store is hot in memory (every call avoids a DB round-trip) and
 * lazily reloads from disk on first access — important so reuse survives
 * across cold starts without a refresh.
 */
@Singleton
class TokenStore @Inject constructor(
    private val tokenDao: AuthTokenDao,
    private val auditLogDao: AuditLogDao,
    private val analyzer: TokenAnalyzer
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    /** In-memory mirror — keyed by `host`, valued by ordered candidate list. */
    private val cacheByHost = mutableMapOf<String, MutableList<AuthTokenEntity>>()

    @Volatile private var hydrated = false

    /** Public observable list (for the UI). */
    private val _tokens = MutableStateFlow<List<AuthTokenEntity>>(emptyList())
    val tokens = _tokens.asStateFlow()

    /** Streamed view of the persisted table — also surfaced to the UI. */
    fun observeTokens(): Flow<List<AuthTokenEntity>> = tokenDao.observeAll()

    // ── Hydration ────────────────────────────────────────────────────────

    /**
     * Lazy hydration. Cheaper than a Hilt `@Provides` init because we only
     * pay the DB round-trip if the interceptor actually fires.
     *
     * Called via runBlocking from the interceptor (which is synchronous);
     * still safe because hydration is a single-shot guarded by a flag.
     */
    private suspend fun hydrate() {
        if (hydrated) return
        mutex.withLock {
            if (hydrated) return
            // We re-use the DAO query: pulling per-host is enough because
            // bestTokenForHost is host-scoped anyway.
            val all = tokenDao.observeAll()
            // observeAll returns a Flow; we want a one-shot read. Cheapest
            // way is to ask the DAO for the active rows on first miss
            // instead of collecting the flow here.
            hydrated = true
            // No-op until first per-host miss triggers a load.
            _tokens.value = emptyList()
            // touching `all` to keep the import set warm
            all.toString()
        }
    }

    private suspend fun ensureHostLoaded(host: String) {
        hydrate()
        if (cacheByHost.containsKey(host)) return
        val rows = tokenDao.getUsableForHost(host).toMutableList()
        // Drop expired-by-clock rows up front so we never inject them.
        val now = System.currentTimeMillis() / 1000
        rows.removeAll { it.expiresAtSec != null && it.expiresAtSec < now }
        cacheByHost[host] = rows
    }

    // ── Capture ──────────────────────────────────────────────────────────

    /**
     * Persist freshly-captured tokens for [originUrl]. The blob can be:
     *  - the raw HTML (we sweep for JWT-shaped strings),
     *  - a JSON map of `name -> value` from the WebView bridge,
     *  - or both, called separately.
     *
     * Duplicates are deduped on `id` (host + short hash of value), so
     * calling this repeatedly for the same page is safe.
     */
    fun recordCapturedTokens(originUrl: String, blob: String) {
        if (blob.isBlank()) return
        val host = hostOf(originUrl) ?: return
        scope.launch {
            val jwts = analyzer.extractJwts(blob)
            jwts.forEach { jwt -> persistJwt(host, jwt) }

            if (jwts.isNotEmpty()) {
                logAudit(
                    "AUTH_TOKEN_CAPTURED",
                    host,
                    "Captured ${jwts.size} JWT(s) from $originUrl"
                )
            }
        }
    }

    /**
     * Persist a map of `header-name -> value` pairs lifted from the page's
     * meta/hidden inputs / JS context. Non-JWT entries are stored as
     * generic header tokens (e.g. `X-CSRF-Token`).
     */
    fun recordCapturedHeaders(originUrl: String, map: Map<String, String>) {
        if (map.isEmpty()) return
        val host = hostOf(originUrl) ?: return
        scope.launch {
            map.forEach { (k, v) ->
                if (v.isBlank()) return@forEach
                val jwt = analyzer.decodeJwt(v)
                if (jwt != null) {
                    persistJwt(host, jwt)
                } else {
                    persistGeneric(host, headerName = headerForKey(k), value = v)
                }
            }
        }
    }

    private suspend fun persistJwt(host: String, jwt: JwtInfo) {
        if (jwt.isExpired) return
        val entity = AuthTokenEntity(
            id = idFor(host, jwt.raw),
            host = host,
            value = jwt.raw,
            headerName = "Authorization",
            isBearer = true,
            expiresAtSec = jwt.expiresAtSec
        )
        tokenDao.upsert(entity)
        mutex.withLock {
            val list = cacheByHost.getOrPut(host) { mutableListOf() }
            if (list.none { it.id == entity.id }) list.add(0, entity)
        }
    }

    private suspend fun persistGeneric(host: String, headerName: String, value: String) {
        val entity = AuthTokenEntity(
            id = idFor(host, "$headerName:$value"),
            host = host,
            value = value,
            headerName = headerName,
            isBearer = false
        )
        tokenDao.upsert(entity)
        mutex.withLock {
            val list = cacheByHost.getOrPut(host) { mutableListOf() }
            if (list.none { it.id == entity.id }) list.add(entity)
        }
    }

    // ── Lookup (sync, used by the interceptor) ──────────────────────────

    /**
     * Synchronous lookup for the OkHttp interceptor. Returns the highest-ranked
     * usable token for the request's host, or null if there's nothing to inject.
     */
    fun bestTokenForHost(host: String): AuthTokenEntity? {
        // The interceptor runs on a background OkHttp dispatcher thread; it's
        // safe to runBlocking here for the brief hydration query. Subsequent
        // calls are cache-hits.
        return runBlocking {
            ensureHostLoaded(host)
            val now = System.currentTimeMillis() / 1000
            cacheByHost[host]?.firstOrNull { token ->
                token.status != AuthTokenEntity.STATUS_FAILED &&
                token.status != AuthTokenEntity.STATUS_EXPIRED &&
                (token.expiresAtSec == null || token.expiresAtSec >= now)
            }
        }
    }

    // ── Outcome reporting ───────────────────────────────────────────────

    fun reportSuccess(token: AuthTokenEntity) {
        scope.launch {
            tokenDao.updateStatus(
                id = token.id,
                status = AuthTokenEntity.STATUS_ACTIVE,
                successDelta = 1,
                failureDelta = 0,
                lastUsedAt = System.currentTimeMillis()
            )
            mutex.withLock {
                cacheByHost[token.host]?.let { list ->
                    val idx = list.indexOfFirst { it.id == token.id }
                    if (idx >= 0) {
                        list[idx] = list[idx].copy(
                            status = AuthTokenEntity.STATUS_ACTIVE,
                            successCount = list[idx].successCount + 1,
                            lastUsedAt = System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }

    fun reportFailure(token: AuthTokenEntity, httpCode: Int) {
        scope.launch {
            // 401/403 → permanent fail; other 4xx → soft fail (count it but don't
            // immediately disable, the request might just be wrong shape).
            val newStatus = if (httpCode == 401 || httpCode == 403) {
                AuthTokenEntity.STATUS_FAILED
            } else {
                token.status
            }
            tokenDao.updateStatus(
                id = token.id,
                status = newStatus,
                successDelta = 0,
                failureDelta = 1,
                lastUsedAt = System.currentTimeMillis()
            )
            mutex.withLock {
                cacheByHost[token.host]?.let { list ->
                    if (newStatus == AuthTokenEntity.STATUS_FAILED) {
                        // Remove from cache so we never re-inject it.
                        list.removeAll { it.id == token.id }
                    }
                }
            }
            logAudit(
                "AUTH_TOKEN_FAILED",
                token.host,
                "HTTP $httpCode invalidated token ${token.id}"
            )
        }
    }

    /** Manual removal (used by the UI). */
    suspend fun forget(tokenId: String) {
        tokenDao.deleteById(tokenId)
        mutex.withLock {
            cacheByHost.values.forEach { it.removeAll { row -> row.id == tokenId } }
        }
    }

    /** Sweep and drop FAILED/EXPIRED rows from disk + memory. */
    suspend fun purgeUnusable() {
        tokenDao.purgeUnusable()
        mutex.withLock {
            cacheByHost.values.forEach { list ->
                list.removeAll { it.status == AuthTokenEntity.STATUS_FAILED ||
                                 it.status == AuthTokenEntity.STATUS_EXPIRED }
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun idFor(host: String, value: String): String {
        // Truncated stable hash so we don't bloat row IDs while still
        // deduping — collision-resistant enough for an on-device cache.
        return "$host::${value.hashCode().toString(16)}"
    }

    private fun headerForKey(key: String): String {
        val k = key.lowercase()
        return when {
            "csrf" in k       -> "X-CSRF-Token"
            "xsrf" in k       -> "X-XSRF-Token"
            "auth" in k       -> "Authorization"
            "bearer" in k     -> "Authorization"
            else              -> "X-Auth-${key.replace(Regex("[^A-Za-z0-9-]"), "-")}"
        }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Throwable) { null }

    private suspend fun logAudit(type: String, host: String, msg: String) {
        try {
            auditLogDao.insertLog(
                AuditLogEntity(actionType = type, providerName = host, details = msg)
            )
        } catch (_: Throwable) { /* never break the auth path on logging */ }
    }
}
