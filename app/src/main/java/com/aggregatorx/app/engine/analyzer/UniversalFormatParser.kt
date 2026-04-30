package com.aggregatorx.app.engine.analyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal Format Parser - Multi-Format Website Data Extractor
 */
@Singleton
class UniversalFormatParser @Inject constructor() {
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
    
    companion object {
        private val NEXTJS_PATTERN = Regex("__NEXT_DATA__|_next/static|/_next/")
        private val NUXTJS_PATTERN = Regex("__NUXT__|_nuxt/|window\\.__NUXT__")
        private val REACT_PATTERN = Regex("data-reactroot|__REACT_DEVTOOLS|reactProps")
        private val VUE_PATTERN = Regex("data-v-|__VUE__|v-app")
        private val ANGULAR_PATTERN = Regex("ng-app|ng-controller|\\[ng-")
        
        private val MEDIA_SCHEMA_TYPES = listOf(
            "Movie", "TVSeries", "TVEpisode", "VideoObject", "MediaObject",
            "Article", "SearchResultsPage", "ItemList", "WebPage"
        )
    }
    
    suspend fun parseContent(
        document: Document, 
        url: String
    ): UniversalParseResult = withContext(Dispatchers.IO) {
        
        val format = detectFormat(document, url)
        val results = mutableListOf<ExtractedContent>()
        val metadata = mutableMapOf<String, String>()
        
        // 1. Framework-specific extraction
        when (format) {
            DataFormat.NEXTJS -> extractNextJsData(document)?.let { results.addAll(it) }
            DataFormat.NUXTJS -> extractNuxtJsData(document)?.let { results.addAll(it) }
            DataFormat.SPA_REACT, DataFormat.SPA_VUE, DataFormat.SPA_ANGULAR -> 
                extractSPAData(document)?.let { results.addAll(it) }
            else -> {}
        }
        
        // 2. Structured Data
        extractJsonLdData(document)?.let { results.addAll(it) }
        extractMetaTags(document, metadata)
        extractEmbeddedJson(document)?.let { results.addAll(it) }
        extractDataAttributes(document)?.let { results.addAll(it) }
        
        // 3. Fallbacks
        extractTraditionalHtml(document)?.let { results.addAll(it) }
        if (format == DataFormat.RSS || format == DataFormat.XML) {
            extractXmlData(document)?.let { results.addAll(it) }
        }
        
        val uniqueResults = deduplicateResults(results)
        
        UniversalParseResult(
            format = format,
            items = uniqueResults,
            metadata = metadata,
            confidence = calculateOverallConfidence(uniqueResults),
            extractionMethods = determineUsedMethods(results),
            rawDataSnapshots = captureRawSnapshots(document)
        )
    }
    
    private fun detectFormat(document: Document, url: String): DataFormat {
        val html = document.html()
        return when {
            document.select("rss, feed, channel").isNotEmpty() -> DataFormat.RSS
            url.endsWith(".xml") || url.contains("/feed") -> DataFormat.XML
            NEXTJS_PATTERN.containsMatchIn(html) -> DataFormat.NEXTJS
            NUXTJS_PATTERN.containsMatchIn(html) -> DataFormat.NUXTJS
            REACT_PATTERN.containsMatchIn(html) -> DataFormat.SPA_REACT
            VUE_PATTERN.containsMatchIn(html) -> DataFormat.SPA_VUE
            ANGULAR_PATTERN.containsMatchIn(html) -> DataFormat.SPA_ANGULAR
            document.select("script[type='application/ld+json']").isNotEmpty() -> DataFormat.JSON_LD
            html.contains("application/json") -> DataFormat.JSON_EMBEDDED
            html.trim().startsWith("{") || html.trim().startsWith("[") -> DataFormat.JSON_API
            else -> DataFormat.HTML_STANDARD
        }
    }
    
    private fun extractNextJsData(document: Document): List<ExtractedContent>? {
        val script = document.select("script#__NEXT_DATA__").firstOrNull() ?: return null
        return runCatching {
            val jsonElement = json.parseToJsonElement(script.html())
            val pageProps = jsonElement.jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
            extractContentFromJsonObject(pageProps, "NextJS")
        }.getOrNull()
    }
    
    private fun extractNuxtJsData(document: Document): List<ExtractedContent>? {
        document.select("script").forEach { script ->
            val content = script.html()
            val regex = Regex("""(?:window\.)?__NUXT__\s*=\s*(\{.*?\});?""")
            regex.find(content)?.let { match ->
                return runCatching {
                    val jsonElement = json.parseToJsonElement(match.groupValues[1])
                    extractContentFromJsonObject(jsonElement.jsonObject, "NuxtJS")
                }.getOrNull()
            }
        }
        return null
    }
    
    private fun extractSPAData(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        val statePatterns = listOf(
            Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*?\});"""),
            Regex("""window\.__PRELOADED_STATE__\s*=\s*(\{.*?\});""")
        )
        document.select("script").forEach { script ->
            val content = script.html()
            statePatterns.forEach { pattern ->
                pattern.find(content)?.let { match ->
                    runCatching {
                        val jsonElement = json.parseToJsonElement(match.groupValues[1])
                        extractContentFromJsonObject(jsonElement.jsonObject, "SPA")?.let { results.addAll(it) }
                    }
                }
            }
        }
        return results.takeIf { it.isNotEmpty() }
    }
    
    private fun extractJsonLdData(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        document.select("script[type='application/ld+json']").forEach { script ->
            runCatching {
                val element = json.parseToJsonElement(script.html())
                val items = if (element is JsonArray) element else listOf(element)
                items.filterIsInstance<JsonObject>().forEach { item ->
                    val type = item["@type"]?.jsonPrimitive?.content
                    val title = extractJsonString(item, "name", "headline", "title")
                    val url = extractJsonString(item, "url", "@id")
                    
                    if (title != null && url != null) {
                        results.add(ExtractedContent(
                            source = "JSON-LD:${type ?: "Unknown"}",
                            title = title,
                            url = url,
                            description = extractJsonString(item, "description"),
                            thumbnail = extractJsonString(item, "image", "thumbnailUrl"),
                            contentType = mapSchemaToContentType(type),
                            confidence = 0.9f
                        ))
                    }
                }
            }
        }
        return results.takeIf { it.isNotEmpty() }
    }
    
    private fun extractMetaTags(document: Document, metadata: MutableMap<String, String>) {
        document.select("meta[property^='og:'], meta[name^='twitter:'], meta[name='description']").forEach { meta ->
            val key = if (meta.hasAttr("property")) meta.attr("property") else meta.attr("name")
            val content = meta.attr("content")
            if (content.isNotEmpty()) metadata[key] = content
        }
    }
    
    private fun extractEmbeddedJson(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        val patterns = listOf(
            Regex("""(?:var|const)\s+(?:videos|data)\s*=\s*(\[\{.*?\}\]);""", RegexOption.DOT_MATCHES_ALL)
        )
        document.select("script").forEach { script ->
            patterns.forEach { pattern ->
                pattern.findAll(script.html()).forEach { match ->
                    runCatching {
                        val array = json.parseToJsonElement(match.groupValues[1]) as? JsonArray
                        array?.filterIsInstance<JsonObject>()?.forEach { item ->
                            extractContentFromSingleJson(item, "EmbeddedJSON")?.let { results.add(it) }
                        }
                    }
                }
            }
        }
        return results.takeIf { it.isNotEmpty() }
    }
    
    private fun extractDataAttributes(document: Document): List<ExtractedContent>? {
        return document.select("[data-title], [data-name]").mapNotNull { el ->
            val title = el.attr("data-title").ifEmpty { el.attr("data-name") }
            val url = el.attr("href").ifEmpty { el.attr("data-url") }
            if (title.isNotEmpty() && url.isNotEmpty()) {
                ExtractedContent(source = "DataAttributes", title = title, url = url, confidence = 0.7f)
            } else null
        }.takeIf { it.isNotEmpty() }
    }

    private fun extractTraditionalHtml(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        val selectors = listOf(".video-item", ".movie-card", "article", ".result")
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.size >= 2) {
                elements.forEach { el ->
                    val title = el.select("h1, h2, h3, .title, a").firstOrNull()?.text()
                    val url = el.select("a[href]").firstOrNull()?.attr("abs:href")
                    if (!title.isNullOrEmpty() && !url.isNullOrEmpty()) {
                        results.add(ExtractedContent("HTML", title, url, confidence = 0.6f))
                    }
                }
                break
            }
        }
        return results.takeIf { it.isNotEmpty() }
    }

    private fun extractXmlData(document: Document): List<ExtractedContent>? {
        return document.select("item, entry").mapNotNull { item ->
            val title = item.select("title").text()
            val link = item.select("link").text().ifEmpty { item.select("link").attr("href") }
            if (title.isNotEmpty() && link.isNotEmpty()) {
                ExtractedContent("XML", title, link, confidence = 0.8f)
            } else null
        }.takeIf { it.isNotEmpty() }
    }

    private fun extractContentFromJsonObject(obj: JsonObject?, source: String): List<ExtractedContent>? {
        if (obj == null) return null
        val results = mutableListOf<ExtractedContent>()
        val contentKeys = listOf("items", "results", "videos", "data")
        
        fun search(current: JsonObject) {
            for ((key, value) in current) {
                if (key in contentKeys && value is JsonArray) {
                    value.filterIsInstance<JsonObject>().forEach {
                        extractContentFromSingleJson(it, source)?.let { res -> results.add(res) }
                    }
                } else if (value is JsonObject) {
                    search(value)
                }
            }
        }
        search(obj)
        return results.takeIf { it.isNotEmpty() }
    }

    private fun extractContentFromSingleJson(obj: JsonObject, source: String): ExtractedContent? {
        val title = extractJsonString(obj, "title", "name", "headline") ?: return null
        val url = extractJsonString(obj, "url", "link", "href") ?: return null
        return ExtractedContent(
            source = source,
            title = title,
            url = url,
            description = extractJsonString(obj, "description"),
            thumbnail = extractJsonString(obj, "thumbnail", "poster"),
            confidence = 0.85f
        )
    }

    private fun extractJsonString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj[key]
            if (value is JsonPrimitive && value.isString) return value.content
        }
        return null
    }

    private fun mapSchemaToContentType(type: String?): ContentType = when (type) {
        "Movie" -> ContentType.MOVIE
        "TVSeries" -> ContentType.SERIES
        "TVEpisode" -> ContentType.EPISODE
        else -> ContentType.VIDEO
    }

    private fun deduplicateResults(results: List<ExtractedContent>): List<ExtractedContent> {
        return results.distinctBy { it.url + it.title.lowercase() }.sortedByDescending { it.confidence }
    }

    private fun calculateOverallConfidence(results: List<ExtractedContent>): Float = 
        if (results.isEmpty()) 0f else results.map { it.confidence }.average().toFloat()

    private fun determineUsedMethods(results: List<ExtractedContent>): List<String> = 
        results.map { it.source }.distinct()

    private fun captureRawSnapshots(document: Document): Map<String, String> = mapOf(
        "jsonLd" to document.select("script[type='application/ld+json']").map { it.html() }.joinToString("\n").take(2000)
    )
}

@Serializable
enum class DataFormat {
    HTML_STANDARD, JSON_LD, JSON_EMBEDDED, JSON_API, NEXTJS, NUXTJS, SPA_REACT, SPA_VUE, SPA_ANGULAR, RSS, XML
}

@Serializable
enum class ContentType {
    VIDEO, MOVIE, SERIES, EPISODE
}

@Serializable
data class UniversalParseResult(
    val format: DataFormat,
    val items: List<ExtractedContent>,
    val metadata: Map<String, String>,
    val confidence: Float,
    val extractionMethods: List<String>,
    val rawDataSnapshots: Map<String, String> = emptyMap()
)

@Serializable
data class ExtractedContent(
    val source: String,
    val title: String,
    val url: String,
    val description: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val quality: String? = null,
    val contentType: ContentType = ContentType.VIDEO,
    val confidence: Float = 0.5f
)
