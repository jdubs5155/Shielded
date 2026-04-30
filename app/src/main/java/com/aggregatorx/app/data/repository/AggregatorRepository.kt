package com.aggregatorx.app.data.repository

import com.aggregatorx.app.data.database.AggregatorDao
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.engine.ai.NLPQueryEngine
import com.aggregatorx.app.engine.scraper.ScrapingEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direction passed to [AggregatorRepository.loadMore]:
 *  - REFRESH : re-scrape the same page the provider is currently on.
 *  - FORWARD : advance one page (or follow a discovered URL_TOKEN link).
 *  - BACK    : go back one page, clamped at 1.
 */
enum class PageDirection { REFRESH, FORWARD, BACK }

@Singleton
class AggregatorRepository @Inject constructor(
    private val dao: AggregatorDao,
    private val scrapingEngine: ScrapingEngine,
    private val nlpEngine: NLPQueryEngine
) {

    /** The most recently submitted query. Persisted via SavedStateHandle in the VM, but
     *  also kept here so per-provider pagination can re-use it without UI plumbing. */
    private val _lastQuery = MutableStateFlow("")
    val lastQuery = _lastQuery.asStateFlow()

    /** Per-provider lock — guarantees we never run two scrapes for the same provider
     *  concurrently while still letting different providers fan out in parallel. */
    private val providerLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(name: String): Mutex = synchronized(providerLocks) {
        providerLocks.getOrPut(name) { Mutex() }
    }

    /**
     * Executes a fresh search across all enabled providers. Resets each
     * provider to `currentPage = 1` so the user is on the first page after
     * a brand new query.
     */
    suspend fun performSearch(userQuery: String) {
        val optimizedQuery = nlpEngine.rewriteQuery(userQuery)
        _lastQuery.value = optimizedQuery

        val providers = dao.getEnabledProviders().first()
        providers.forEach { provider ->
            // Reset the provider to page 1 for a fresh search.
            dao.updateProviderPagination(
                name = provider.name,
                currentPage = 1,
                nextPageUrl = null
            )
            scrapeAndStore(
                provider = provider.copy(currentPage = 1, nextPageUrl = null),
                query = optimizedQuery,
                page = 1,
                replaceSlice = true
            )
        }
    }

    /**
     * Per-provider pagination. Updates ONLY the specified provider's slice
     * of the result map and persists the new page on the provider entity.
     */
    suspend fun loadMore(providerName: String, direction: PageDirection) {
        val provider = dao.getProviderByName(providerName) ?: return
        val targetPage = when (direction) {
            PageDirection.REFRESH -> provider.currentPage.coerceAtLeast(1)
            PageDirection.FORWARD -> provider.currentPage + 1
            PageDirection.BACK    -> (provider.currentPage - 1).coerceAtLeast(1)
        }

        scrapeAndStore(
            provider = provider,
            query = _lastQuery.value,
            page = targetPage,
            replaceSlice = true
        )

        // Persist the new pagination state. nextPageUrl is updated by scrapeAndStore
        // (we re-read the latest provider row to avoid clobbering it here).
        val refreshed = dao.getProviderByName(providerName) ?: return
        dao.updateProviderPagination(
            name = providerName,
            currentPage = targetPage,
            nextPageUrl = refreshed.nextPageUrl
        )
    }

    /**
     * Toggles the 'Liked' state and triggers AI refinement.
     */
    suspend fun toggleLike(item: ResultItem) {
        val updatedItem = item.copy(isLiked = !item.isLiked)
        dao.updateResult(updatedItem)

        if (updatedItem.isLiked) {
            val likedItems = dao.getResultsByProvider(item.providerName)
                .first()
                .filter { it.isLiked }
            nlpEngine.refineResults(likedItems)
        }
    }

    fun getResultsForProvider(providerName: String): Flow<List<ResultItem>> =
        dao.getResultsByProvider(providerName)

    fun getProviders(): Flow<List<ProviderEntity>> = dao.getEnabledProviders()

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Run a single scrape for `provider` and write the results into the DB.
     * If `replaceSlice` is true, the provider's existing rows are deleted
     * first — that's how refresh/back/forward avoid mixing pages together
     * inside the same provider card.
     *
     * The discovered next-page URL (when present) is persisted so that
     * URL_TOKEN-style providers can be paginated forward on the next click.
     */
    private suspend fun scrapeAndStore(
        provider: ProviderEntity,
        query: String,
        page: Int,
        replaceSlice: Boolean
    ) {
        lockFor(provider.name).withLock {
            val result = scrapingEngine.scrape(provider, query, page)

            if (replaceSlice) {
                dao.clearResultsByProvider(provider.name)
            }
            if (result.items.isNotEmpty()) {
                dao.insertResults(result.items)
            }

            if (result.discoveredNextPageUrl != null) {
                dao.updateProviderPagination(
                    name = provider.name,
                    currentPage = page,
                    nextPageUrl = result.discoveredNextPageUrl
                )
            }
        }
    }
}
