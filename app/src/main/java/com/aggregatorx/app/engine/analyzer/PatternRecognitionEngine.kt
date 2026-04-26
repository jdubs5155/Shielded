package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced Pattern Recognition Engine
 * 
 * Uses machine learning-inspired heuristics to detect:
 * - Content patterns (grids, lists, cards)
 * - Navigation structures
 * - Media containers
 * - Interactive elements
 * - Pagination systems
 */
@Singleton
class PatternRecognitionEngine @Inject constructor() {
    
    /**
     * Analyze document for content patterns
     */
    fun analyzeContentPatterns(document: Document): List<ContentPattern> {
        val patterns = mutableListOf<ContentPattern>()
        
        // Grid detection
        detectGridPatterns(document)?.let { patterns.add(it) }
        
        // Card layouts
        detectCardPatterns(document)?.let { patterns.add(it) }
        
        // List structures
        detectListPatterns(document)?.let { patterns.add(it) }
        
        // Table layouts
        detectTablePatterns(document)?.let { patterns.add(it) }
        
        // Infinite scroll
        if (detectInfiniteScroll(document)) {
            patterns.add(ContentPattern(
                type = ContentPatternType.INFINITE_SCROLL,
                selector = "[data-infinite-scroll], .infinite-scroll",
                confidence = 0.8f
            ))
        }
        
        // Lazy loading
        if (detectLazyLoading(document)) {
            patterns.add(ContentPattern(
                type = ContentPatternType.LAZY_LOADING,
                selector = "[data-src], [loading='lazy']",
                confidence = 0.9f
            ))
        }
        
        return patterns.sortedByDescending { it.confidence }
    }
    
    /**
     * Detect grid-based layouts
     */
    private fun detectGridPatterns(document: Document): ContentPattern? {
        val gridSelectors = listOf(
            ".grid", "[class*='grid']", ".row > .col",
            "display: grid", ".flex-wrap", ".masonry"
        )
        
        for (selector in gridSelectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty() && elements.first()?.children()?.size ?: 0 >= 4) {
                return ContentPattern(
                    type = ContentPatternType.GRID,
                    selector = selector,
                    confidence = calculateGridConfidence(elements.first()!!),
                    metadata = mapOf(
                        "columns" to estimateGridColumns(elements.first()!!).toString(),
                        "itemCount" to (elements.first()?.children()?.size ?: 0).toString()
                    )
                )
            }
        }
        return null
    }
    
    /**
     * Detect card-based layouts
     */
    private fun detectCardPatterns(document: Document): ContentPattern? {
        val cardSelectors = listOf(
            ".card", "[class*='card']", ".tile", ".box",
            ".item", ".entry", "article"
        )
        
        for (selector in cardSelectors) {
            val elements = document.select(selector)
            if (elements.size >= 3) {
                // Check if cards have similar structure
                val similarity = calculateStructureSimilarity(elements.take(5))
                if (similarity > 0.7f) {
                    return ContentPattern(
                        type = ContentPatternType.CARD_LAYOUT,
                        selector = selector,
                        confidence = similarity,
                        metadata = mapOf(
                            "cardCount" to elements.size.toString(),
                            "hasImage" to elements.any { it.select("img").isNotEmpty() }.toString(),
                            "hasTitle" to elements.any { it.select("h1,h2,h3,h4,.title").isNotEmpty() }.toString()
                        )
                    )
                }
            }
        }
        return null
    }
    
    /**
     * Detect list patterns
     */
    private fun detectListPatterns(document: Document): ContentPattern? {
        // Ordered/unordered lists
        val lists = document.select("ul, ol")
        for (list in lists) {
            if (list.children().size >= 5) {
                val similarity = calculateStructureSimilarity(list.children().take(5))
                if (similarity > 0.6f) {
                    return ContentPattern(
                        type = ContentPatternType.LIST,
                        selector = list.cssSelector(),
                        confidence = similarity,
                        metadata = mapOf(
                            "itemCount" to list.children().size.toString(),
                            "listType" to list.tagName()
                        )
                    )
                }
            }
        }
        
        // Custom lists (divs with repeated structure)
        val containers = document.select("div, section")
        for (container in containers) {
            val children = container.children()
            if (children.size >= 4 && children.all { it.tagName() == children.first()?.tagName() }) {
                val similarity = calculateStructureSimilarity(children.take(5))
                if (similarity > 0.7f) {
                    return ContentPattern(
                        type = ContentPatternType.LIST,
                        selector = container.cssSelector(),
                        confidence = similarity
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Detect table patterns
     */
    private fun detectTablePatterns(document: Document): ContentPattern? {
        val tables = document.select("table")
        for (table in tables) {
            val rows = table.select("tr")
            if (rows.size >= 3) {
                return ContentPattern(
                    type = ContentPatternType.TABLE,
                    selector = table.cssSelector(),
                    confidence = 0.9f,
                    metadata = mapOf(
                        "rows" to rows.size.toString(),
                        "columns" to (rows.first()?.select("td, th")?.size ?: 0).toString(),
                        "hasHeader" to table.select("thead, th").isNotEmpty().toString()
                    )
                )
            }
        }
        return null
    }
    
    /**
     * Detect infinite scroll implementation
     */
    private fun detectInfiniteScroll(document: Document): Boolean {
        val html = document.html().lowercase()
        
        // Check for common infinite scroll indicators
        val indicators = listOf(
            "infinite-scroll",
            "infinitescroll",
            "infinite_scroll",
            "data-infinite",
            "intersectionobserver",
            "scroll-trigger",
            "load-more-trigger",
            "waypoint"
        )
        
        return indicators.any { html.contains(it) }
    }
    
    /**
     * Detect lazy loading
     */
    private fun detectLazyLoading(document: Document): Boolean {
        val lazySelectors = listOf(
            "[data-src]",
            "[data-lazy-src]",
            "[loading='lazy']",
            ".lazy",
            "[class*='lazy']",
            "[data-original]"
        )
        
        return lazySelectors.any { document.select(it).isNotEmpty() }
    }
    
    /**
     * Calculate grid column count estimate
     */
    private fun estimateGridColumns(container: Element): Int {
        val children = container.children()
        if (children.isEmpty()) return 0
        
        // Check CSS classes for hints
        val classHints = mapOf(
            "col-3" to 4, "col-4" to 3, "col-6" to 2,
            "grid-cols-2" to 2, "grid-cols-3" to 3, "grid-cols-4" to 4,
            "columns-2" to 2, "columns-3" to 3, "columns-4" to 4
        )
        
        container.classNames().forEach { className ->
            classHints.entries.find { className.contains(it.key) }?.let {
                return it.value
            }
        }
        
        // Default guess based on child count
        return when {
            children.size >= 12 -> 4
            children.size >= 6 -> 3
            else -> 2
        }
    }
    
    /**
     * Calculate confidence score for grid detection
     */
    private fun calculateGridConfidence(container: Element): Float {
        var confidence = 0.5f
        
        val children = container.children()
        if (children.isEmpty()) return 0f
        
        // More children = higher confidence
        if (children.size >= 4) confidence += 0.1f
        if (children.size >= 8) confidence += 0.1f
        
        // Check structural similarity
        val similarity = calculateStructureSimilarity(children.take(5))
        confidence += similarity * 0.3f
        
        return confidence.coerceAtMost(1f)
    }
    
    /**
     * Calculate structural similarity between elements
     */
    private fun calculateStructureSimilarity(elements: List<Element>): Float {
        if (elements.size < 2) return 0f
        
        val signatures = elements.map { getElementSignature(it) }
        val reference = signatures.first()
        
        var totalSimilarity = 0f
        signatures.drop(1).forEach { sig ->
            totalSimilarity += compareSignatures(reference, sig)
        }
        
        return totalSimilarity / (signatures.size - 1)
    }
    
    /**
     * Generate structural signature for an element
     */
    private fun getElementSignature(element: Element): ElementSignature {
        return ElementSignature(
            tagName = element.tagName(),
            childTags = element.children().map { it.tagName() },
            hasImage = element.select("img").isNotEmpty(),
            hasLink = element.select("a").isNotEmpty(),
            hasHeading = element.select("h1,h2,h3,h4,h5,h6").isNotEmpty(),
            hasText = element.text().length > 10,
            classPatterns = extractClassPatterns(element.classNames())
        )
    }
    
    /**
     * Compare two element signatures
     */
    private fun compareSignatures(a: ElementSignature, b: ElementSignature): Float {
        var score = 0f
        var factors = 0
        
        // Same tag name
        factors++
        if (a.tagName == b.tagName) score += 1f
        
        // Similar child structure
        factors++
        if (a.childTags.toSet() == b.childTags.toSet()) score += 1f
        else if ((a.childTags.toSet() intersect b.childTags.toSet()).size > a.childTags.size * 0.5) score += 0.5f
        
        // Same content types
        factors++
        if (a.hasImage == b.hasImage) score += 1f
        
        factors++
        if (a.hasLink == b.hasLink) score += 1f
        
        factors++
        if (a.hasHeading == b.hasHeading) score += 1f
        
        return score / factors
    }
    
    /**
     * Extract meaningful class patterns
     */
    private fun extractClassPatterns(classes: Set<String>): List<String> {
        val patterns = mutableListOf<String>()
        
        classes.forEach { className ->
            // Remove numbers to get pattern
            val pattern = className.replace(Regex("\\d+"), "*")
            patterns.add(pattern)
        }
        
        return patterns.distinct()
    }
    
    /**
     * Detect video content patterns
     */
    fun detectVideoPatterns(document: Document): VideoPatternAnalysis {
        val players = mutableListOf<VideoPlayerInfo>()
        
        // HTML5 video
        document.select("video").forEach { video ->
            players.add(VideoPlayerInfo(
                type = VideoPlayerType.HTML5,
                selector = video.cssSelector(),
                sources = video.select("source").map { it.attr("src") } + 
                         listOfNotNull(video.attr("src").takeIf { it.isNotEmpty() })
            ))
        }
        
        // YouTube embeds
        document.select("iframe[src*='youtube'], iframe[src*='youtu.be']").forEach { iframe ->
            players.add(VideoPlayerInfo(
                type = VideoPlayerType.YOUTUBE,
                selector = iframe.cssSelector(),
                sources = listOf(iframe.attr("src"))
            ))
        }
        
        // Vimeo embeds
        document.select("iframe[src*='vimeo']").forEach { iframe ->
            players.add(VideoPlayerInfo(
                type = VideoPlayerType.VIMEO,
                selector = iframe.cssSelector(),
                sources = listOf(iframe.attr("src"))
            ))
        }
        
        // JW Player
        document.select(".jwplayer, #jwplayer, [class*='jwplayer']").forEach { elem ->
            players.add(VideoPlayerInfo(
                type = VideoPlayerType.JWPLAYER,
                selector = elem.cssSelector(),
                sources = emptyList()
            ))
        }
        
        // Video.js
        document.select(".video-js, .vjs-tech").forEach { elem ->
            players.add(VideoPlayerInfo(
                type = VideoPlayerType.VIDEOJS,
                selector = elem.cssSelector(),
                sources = emptyList()
            ))
        }
        
        // Detect streaming protocols from scripts
        val html = document.html()
        val protocols = mutableListOf<StreamingProtocol>()
        
        if (html.contains(".m3u8")) protocols.add(StreamingProtocol.HLS)
        if (html.contains(".mpd")) protocols.add(StreamingProtocol.DASH)
        if (html.contains("rtmp://")) protocols.add(StreamingProtocol.RTMP)
        if (html.contains("rtsp://")) protocols.add(StreamingProtocol.RTSP)
        
        return VideoPatternAnalysis(
            players = players,
            protocols = protocols,
            hasVideoContent = players.isNotEmpty()
        )
    }
}

data class ContentPattern(
    val type: ContentPatternType,
    val selector: String,
    val confidence: Float,
    val metadata: Map<String, String> = emptyMap()
)

enum class ContentPatternType {
    GRID,
    CARD_LAYOUT,
    LIST,
    TABLE,
    INFINITE_SCROLL,
    LAZY_LOADING,
    MASONRY,
    CAROUSEL
}

data class ElementSignature(
    val tagName: String,
    val childTags: List<String>,
    val hasImage: Boolean,
    val hasLink: Boolean,
    val hasHeading: Boolean,
    val hasText: Boolean,
    val classPatterns: List<String>
)

data class VideoPatternAnalysis(
    val players: List<VideoPlayerInfo>,
    val protocols: List<StreamingProtocol>,
    val hasVideoContent: Boolean
)

data class VideoPlayerInfo(
    val type: VideoPlayerType,
    val selector: String,
    val sources: List<String>
)

enum class VideoPlayerType {
    HTML5,
    YOUTUBE,
    VIMEO,
    JWPLAYER,
    VIDEOJS,
    PLYR,
    FLOWPLAYER,
    CUSTOM
}

enum class StreamingProtocol {
    HLS,      // HTTP Live Streaming (.m3u8)
    DASH,     // Dynamic Adaptive Streaming (.mpd)
    RTMP,     // Real-Time Messaging Protocol
    RTSP,     // Real-Time Streaming Protocol
    MSS,      // Microsoft Smooth Streaming
    PROGRESSIVE // Regular HTTP download
}
