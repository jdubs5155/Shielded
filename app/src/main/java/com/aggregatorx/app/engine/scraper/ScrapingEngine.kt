package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.PaginationType
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.util.EngineUtils
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of scraping a single provider page.
 *
 * `discoveredNextPageUrl` lets the repository persist the next-page link
 * for `URL_TOKEN`-style providers (forums, infinite-scroll APIs) without
 * re-scraping just to find it.
 */
data class ScrapeResult(
    val items: List<ResultItem>,
    val discoveredNextPageUrl: String? = null
)

@Singleton
class ScrapingEngine @Inject constructor(
    private val bypassEngine: CloudflareBypassEngine
) {

    /**
     * Scrape a single page for `provider`. Pagination behavior is dispatched
     * from the provider's `paginationType` so the same call site works for
     * page-number, offset, and url-token providers.
     */
    suspend fun scrape(
        provider: ProviderEntity,
        query: String,
        page: Int
    ): ScrapeResult {
        val url = buildPageUrl(provider, query, page)
        val html = bypassEngine.resolve(url)
        val doc = Jsoup.parse(html, provider.baseUrl)

        val items = doc.select(".search-result, .search-item-container, article, li.result")
            .mapNotNull { element ->
                val link = element.selectFirst("a")?.absUrl("href").orEmpty()
                if (link.isBlank()) return@mapNotNull null

                val title = element.selectFirst(".title, h2, h3, a")?.text()?.trim().orEmpty()
                val description = element.selectFirst(".description, p")?.text()?.trim()
                val thumb = element.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
                val videoUrl = element.attr("data-video-url").takeIf { it.isNotBlank() }
                    ?: element.selectFirst("video, source")?.absUrl("src")?.takeIf { it.isNotBlank() }

                ResultItem(
                    id = "${provider.name}::${link.hashCode()}",
                    providerName = provider.name,
                    title = title.ifBlank { link },
                    description = description,
                    url = link,
                    videoUrl = videoUrl,
                    thumbnailUrl = thumb
                )
            }

        // For URL_TOKEN providers, surface the discovered "next" link so the
        // repository can persist it on the entity for the next forward-page click.
        val nextLink = if (provider.paginationType == PaginationType.URL_TOKEN) {
            doc.selectFirst("a[rel=next], a.next, a.pagination-next")?.absUrl("href")
        } else {
            null
        }

        return ScrapeResult(items = items, discoveredNextPageUrl = nextLink)
    }

    /**
     * Compose the URL for a given page based on the provider's pagination type.
     *
     *  - PAGE_NUMBER : substitutes `{page}` with the literal page number.
     *  - OFFSET      : substitutes `{page}` with `(page - 1) * pageSize` so a
     *                  searchPath like `/find/{query}?offset={page}` works as expected.
     *  - URL_TOKEN   : if `nextPageUrl` is populated for forward navigation
     *                  (page > 1) we use it directly, otherwise fall back to
     *                  the templated path.
     */
    private fun buildPageUrl(provider: ProviderEntity, query: String, page: Int): String {
        if (provider.paginationType == PaginationType.URL_TOKEN
            && page > 1
            && !provider.nextPageUrl.isNullOrBlank()
        ) {
            return provider.nextPageUrl
        }

        val pageToken = when (provider.paginationType) {
            PaginationType.PAGE_NUMBER -> page.toString()
            PaginationType.OFFSET -> ((page - 1).coerceAtLeast(0) * provider.pageSize).toString()
            PaginationType.URL_TOKEN -> page.toString()
        }

        val raw = "${provider.baseUrl}${provider.searchPath}"
            .replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", pageToken)

        return EngineUtils.normalizeFullUrl(raw)
    }
}
