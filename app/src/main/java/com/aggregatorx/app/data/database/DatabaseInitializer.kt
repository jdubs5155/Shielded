package com.aggregatorx.app.data.database

import com.aggregatorx.app.data.model.PaginationType
import com.aggregatorx.app.data.model.ProviderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the database with default search providers upon first installation.
 */
@Singleton
class DatabaseInitializer @Inject constructor(
    private val aggregatorDao: AggregatorDao
) {
    fun initialize() {
        CoroutineScope(Dispatchers.IO).launch {
            val defaultProviders = listOf(
                ProviderEntity(
                    name = "Provider_Alpha",
                    baseUrl = "https://example-alpha.com",
                    searchPath = "/search?q={query}&p={page}",
                    paginationType = PaginationType.PAGE_NUMBER
                ),
                ProviderEntity(
                    name = "Provider_Beta",
                    baseUrl = "https://example-beta.org",
                    searchPath = "/find/{query}/{page}",
                    paginationType = PaginationType.OFFSET
                )
            )

            defaultProviders.forEach { provider ->
                aggregatorDao.insertProvider(provider)
            }
        }
    }
}
