package com.aggregatorx.app.data.repository

import com.aggregatorx.app.data.database.AggregatorDao
import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.engine.scraper.ScrapingEngine
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository handling the coordination between persistent storage, 
 * scraping engines, and audit logging.
 */
@Singleton
class AggregatorRepository @Inject constructor(
    private val aggregatorDao: AggregatorDao,
    private val auditLogDao: AuditLogDao,
    private val scrapingEngine: ScrapingEngine
) {

    fun getEnabledProviders(): Flow<List<ProviderEntity>> = aggregatorDao.getEnabledProviders()

    suspend fun getProviderByName(name: String): ProviderEntity? = aggregatorDao.getProviderByName(name)

    /**
     * Scrapes a specific provider, updates pagination state, and logs the action.
     */
    suspend fun scrapeProvider(query: String, providerName: String, page: Int): List<ResultItem> {
        val provider = aggregatorDao.getProviderByName(providerName) 
            ?: throw IllegalArgumentException("Provider not found: $providerName")

        return try {
            // Log the start of the scraping action
            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = "SCRAPE_START",
                    providerName = providerName,
                    details = "Query: $query, Page: $page"
                )
            )

            // Perform the actual scrape via the engine
            val results = scrapingEngine.scrape(
                query = query,
                provider = provider,
                page = page
            )

            // Update provider's current page in the database for persistence
            provider.currentPage = page
            aggregatorDao.updateProvider(provider)

            // Save results to cache
            aggregatorDao.insertResults(results)

            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = "SCRAPE_SUCCESS",
                    providerName = providerName,
                    details = "Found ${results.size} items"
                )
            )

            results
        } catch (e: Exception) {
            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = "SCRAPE_FAILURE",
                    providerName = providerName,
                    details = "Error: ${e.message}",
                    isSuccess = false
                )
            )
            emptyList()
        }
    }

    suspend fun updateItemLikeStatus(id: String, isLiked: Boolean) {
        aggregatorDao.updateItemLikeStatus(id, isLiked)
        auditLogDao.insertLog(
            AuditLogEntity(
                actionType = "PREFERENCE_UPDATE",
                providerName = null,
                details = "Item $id liked: $isLiked"
            )
        )
    }
}
