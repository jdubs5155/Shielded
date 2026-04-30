package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core engine responsible for selecting the appropriate scraping strategy
 * and executing it via the headless WebView or fallback mechanisms.
 */
@Singleton
class ScrapingEngine @Inject constructor(
    private val headlessBrowser: HeadlessBrowserHelper,
    private val cloudflareBypass: CloudflareBypassEngine
) {

    /**
     * Executes a scrape for a specific provider and page.
     * Integrates with the headless WebView for dynamic content resolution.
     */
    suspend fun scrape(
        query: String,
        provider: ProviderEntity,
        page: Int
    ): List<ResultItem> {
        
        // Construct the paginated URL based on the Provider's configuration
        val requestUrl = buildUrl(provider, query, page)

        // Resolve the page content through the headless WebView to handle JS
        val htmlContent = headlessBrowser.getHtml(requestUrl)

        // If Cloudflare or bot detection is detected, attempt to bypass
        if (isDetectionTriggered(htmlContent)) {
            val bypassedHtml = cloudflareBypass.resolve(requestUrl)
            return parseResults(bypassedHtml, provider.name)
        }

        return parseResults(htmlContent, provider.name)
    }

    private fun buildUrl(provider: ProviderEntity, query: String, page: Int): String {
        val pageParam = when (provider.paginationType) {
            com.aggregatorx.app.data.model.PaginationType.PAGE_NUMBER -> "page=$page"
            com.aggregatorx.app.data.model.PaginationType.OFFSET -> "offset=${(page - 1) * 20}"
            com.aggregatorx.app.data.model.PaginationType.URL_TOKEN -> provider.nextPageUrl ?: ""
        }
        
        return "${provider.baseUrl}${provider.searchPath}?q=$query&$pageParam"
    }

    private fun isDetectionTriggered(html: String): Boolean {
        return html.contains("cloudflare", ignoreCase = true) || 
               html.contains("captcha", ignoreCase = true)
    }

    private fun parseResults(html: String, providerName: String): List<ResultItem> {
        // This is where the UniversalFormatParser or SiteAnalyzerEngine is called
        // to map raw HTML/JSON into the ResultItem list.
        // For the baseline, we return a structured list mapped to the ProviderName.
        return emptyList() // Placeholder for the parser integration
    }
}
