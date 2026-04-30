package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.ResultItem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A reusable blueprint for site-specific scrapers.
 */
abstract class BaseProviderScraper(val providerName: String) {

    /**
     * Define how to extract a list of result elements from the page.
     */
    abstract fun getResultElements(doc: Document): List<Element>

    /**
     * Define how to map an individual HTML element to a ResultItem.
     */
    abstract fun mapToResult(element: Element): ResultItem?

    /**
     * Standard execution flow for scraping any provider.
     */
    fun parse(doc: Document): List<ResultItem> {
        return getResultElements(doc).mapNotNull { element ->
            try {
                mapToResult(element)
            } catch (e: Exception) {
                null // Skip malformed items
            }
        }
    }
}
