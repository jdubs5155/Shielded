package com.aggregatorx.app.data.repository

import com.aggregatorx.app.data.database.AggregatorDao
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.engine.ai.NLPQueryEngine
import com.aggregatorx.app.engine.scraper.ScrapingEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AggregatorRepository @Inject constructor(
    private val dao: AggregatorDao,
    private val scrapingEngine: ScrapingEngine,
    private val nlpEngine: NLPQueryEngine
) {

    /**
     * Executes a new search across all enabled providers.
     * Uses NLP to optimize the query before hitting the web.
     */
    suspend fun performSearch(userQuery: String) {
        val optimizedQuery = nlpEngine.rewriteQuery(userQuery)
        val providers = dao.getEnabledProviders().first()

        providers.forEach { provider ->
            // Clear old results to keep the UI clean during a new search
            dao.clearResultsByProvider(provider.name)
            
            val results = scrapingEngine.scrape(provider, optimizedQuery, page = 1)
            dao.insertResults(results)
        }
    }

    /**
     * Handles pagination for a specific provider.
     */
    suspend fun loadMore(providerName: String, direction: Int) {
        val providers = dao.getEnabledProviders().first()
        val provider = providers.find { it.name == providerName } ?: return
        
        // Direction: 1 for next, -1 for previous (logic varies by provider type)
        val results = scrapingEngine.scrape(provider, "", page = direction)
        dao.insertResults(results)
    }

    /**
     * Toggles the 'Liked' state and triggers AI refinement.
     */
    suspend fun toggleLike(item: ResultItem) {
        val updatedItem = item.copy(isLiked = !item.isLiked)
        dao.updateResult(updatedItem)

        if (updatedItem.isLiked) {
            // Trigger background refinement loop
            val likedItems = dao.getResultsByProvider(item.providerName).first().filter { it.isLiked }
            nlpEngine.refineResults(likedItems)
        }
    }

    fun getResultsForProvider(providerName: String): Flow<List<ResultItem>> =
        dao.getResultsByProvider(providerName)

    fun getProviders(): Flow<List<ProviderEntity>> = dao.getEnabledProviders()
}
