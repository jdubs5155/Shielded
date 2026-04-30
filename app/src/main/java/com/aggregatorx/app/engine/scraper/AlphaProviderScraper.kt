package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.ResultItem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AlphaProviderScraper : BaseProviderScraper("Provider_Alpha") {

    override fun getResultElements(doc: Document): List<Element> {
        // Look for the container that holds the search results
        return doc.select(".search-item-container")
    }

    override fun mapToResult(element: Element): ResultItem {
        val titleElement = element.selectFirst(".item-title")
        val linkElement = element.selectFirst("a")
        
        return ResultItem(
            id = linkElement?.attr("abs:href").hashCode().toString(),
            providerName = providerName,
            title = titleElement?.text() ?: "No Title",
            url = linkElement?.attr("abs:href") ?: "",
            thumbnailUrl = element.selectFirst("img")?.attr("src")
        )
    }
}
