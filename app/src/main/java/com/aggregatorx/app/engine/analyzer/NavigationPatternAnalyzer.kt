package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.database.NavigationPatternDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Navigation Pattern Analyzer Engine
 *
 * Discovers and analyzes navigation structures on provider sites:
 * - Tab-based navigation (click tabs to see different categories)
 * - Dropdown menus for category selection
 * - Sidebar category menus
 * - Filter panels
 *
 * This helps when a site doesn't have a traditional search function
 * but instead uses category navigation.
 */
@Singleton
class NavigationPatternAnalyzer @Inject constructor(
    private val navigationDao: NavigationPatternDao
) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Analyze HTML to discover navigation patterns
     */
    suspend fun analyzeNavigation(
        html: String,
        providerId: String,
        providerUrl: String
    ): NavigationPattern? = withContext(Dispatchers.Default) {
        val document = Jsoup.parse(html, providerUrl)
        
        // Check for cached pattern first
        val cached = navigationDao.getPatternForProvider(providerId)
        if (cached != null && cached.confidence > 0.7f) {
            return@withContext cached
        }
        
        // Try to detect different navigation types
        val pattern = detectTabNavigation(document, providerId)
            ?: detectDropdownNavigation(document, providerId)
            ?: detectSidebarNavigation(document, providerId)
            ?: detectFilterPanelNavigation(document, providerId)

        if (pattern != null) {
            navigationDao.insertPattern(pattern)
        }

        return@withContext pattern
    }
    
    /**
     * Detect tab-based navigation (e.g., tabs that switch content)
     */
    private suspend fun detectTabNavigation(
        document: Document,
        providerId: String
    ): NavigationPattern? {
        val tabSelectors = listOf(
            ".nav-tabs",
            ".tabs",
            ".tab-navigation",
            "[role='tablist']",
            ".nav",
            ".navigation"
        )
        
        for (selector in tabSelectors) {
            val navContainer = document.selectFirst(selector) ?: continue
            val tabs = navContainer.select("a, button, [role='tab']")
            
            if (tabs.size >= 3) {
                // Found potential tab navigation
                val tabNames = mutableListOf<String>()
                var anyClickable = false
                
                for (tab in tabs) {
                    val text = tab.text().trim()
                    if (text.isNotEmpty()) {
                        tabNames.add(text)
                        if (tab.tagName() == "a" || tab.tagName() == "button" || 
                            tab.hasAttr("onclick") || tab.hasAttr("data-toggle")) {
                            anyClickable = true
                        }
                    }
                }
                
                if (anyClickable && tabNames.isNotEmpty()) {
                    return NavigationPattern(
                        providerId = providerId,
                        navigationSelector = selector,
                        tabSelector = "a, button, [role='tab']",
                        resultContainerSelector = "[role='tabpanel'], .tab-content, .tab-pane",
                        categories = Json.encodeToString(tabNames),
                        navigationType = NavigationType.TAB_CLICK,
                        confidence = 0.85f
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Detect dropdown menu navigation
     */
    private suspend fun detectDropdownNavigation(
        document: Document,
        providerId: String
    ): NavigationPattern? {
        val selectElements = document.select("select")
        
        for (select in selectElements) {
            val name = select.attr("name").lowercase()
            val options = select.select("option")
            
            if ((name.contains("category") || name.contains("genre") || 
                 name.contains("filter") || name.contains("type")) &&
                options.size >= 3) {
                
                val categories = options
                    .filter { it.attr("value").isNotEmpty() }
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                
                if (categories.isNotEmpty()) {
                    return NavigationPattern(
                        providerId = providerId,
                        navigationSelector = "select[name='${select.attr("name")}']",
                        tabSelector = "option",
                        resultContainerSelector = "main, .content, .results, .items",
                        categories = Json.encodeToString(categories),
                        navigationType = NavigationType.DROPDOWN,
                        confidence = 0.9f
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Detect sidebar category menu
     */
    private suspend fun detectSidebarNavigation(
        document: Document,
        providerId: String
    ): NavigationPattern? {
        val sidebarSelectors = listOf(
            ".sidebar",
            ".sidenav",
            "aside nav",
            ".categories",
            ".genre-list"
        )
        
        for (selector in sidebarSelectors) {
            val sidebar = document.selectFirst(selector) ?: continue
            val items = sidebar.select("a, li")
            
            if (items.size >= 3) {
                val categories = items
                    .filter { it.text().trim().isNotEmpty() }
                    .map { it.text().trim() }
                    .filter { it.length < 50 } // Filter out navigation clutter
                    .distinct()
                
                if (categories.size >= 3) {
                    return NavigationPattern(
                        providerId = providerId,
                        navigationSelector = selector,
                        tabSelector = "a",
                        resultContainerSelector = "main, .content, .results",
                        categories = Json.encodeToString(categories),
                        navigationType = NavigationType.SIDEBAR_MENU,
                        confidence = 0.8f
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Detect filter panel navigation
     */
    private suspend fun detectFilterPanelNavigation(
        document: Document,
        providerId: String
    ): NavigationPattern? {
        val filterSelectors = listOf(
            ".filter-panel",
            ".filters",
            ".refinements",
            ".facets"
        )
        
        for (selector in filterSelectors) {
            val panel = document.selectFirst(selector) ?: continue
            val checkboxes = panel.select("input[type='checkbox'], input[type='radio']")
            
            if (checkboxes.size >= 3) {
                val categories = checkboxes
                    .mapNotNull { input ->
                        val label = input.parent()?.selectFirst("label")?.text()?.trim()
                            ?: input.nextElementSibling()?.text()?.trim()
                        label
                    }
                    .filter { it.isNotEmpty() }
                    .distinct()
                
                if (categories.isNotEmpty()) {
                    return NavigationPattern(
                        providerId = providerId,
                        navigationSelector = selector,
                        tabSelector = "input[type='checkbox'], input[type='radio']",
                        resultContainerSelector = ".results, .content",
                        categories = Json.encodeToString(categories),
                        navigationType = NavigationType.FILTER_PANEL,
                        confidence = 0.75f
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Get categories from a navigation pattern
     */
    suspend fun getCategoriesForPattern(
        providerId: String,
        pattern: NavigationPattern? = null
    ): List<String> = withContext(Dispatchers.Default) {
        val actualPattern = pattern ?: navigationDao.getPatternForProvider(providerId)
        ?: return@withContext emptyList()
        
        return@withContext try {
            json.parseToJsonElement(actualPattern.categories).jsonArray
                .mapNotNull { element ->
                    val primitive = element.jsonPrimitive
                    if (primitive.isString) primitive.content else null
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Build a navigation click that would select a specific category
     */
    suspend fun buildNavigationUrl(
        providerId: String,
        category: String,
        baseUrl: String,
        pattern: NavigationPattern? = null
    ): String = withContext(Dispatchers.Default) {
        val actualPattern = pattern ?: navigationDao.getPatternForProvider(providerId)
        ?: return@withContext baseUrl
        
        return@withContext when (actualPattern.navigationType) {
            NavigationType.DROPDOWN -> {
                // For dropdowns, the category is selected via form submission
                "$baseUrl?${actualPattern.navigationSelector.split("'")[1]}=${category.replace(" ", "+")}"
            }
            NavigationType.SIDEBAR_MENU -> {
                // For sidebar, usually a simple page link
                "$baseUrl/${category.lowercase().replace(" ", "-")}"
            }
            NavigationType.FILTER_PANEL -> {
                // For filters, add as URL parameter
                "$baseUrl?filter=${category.replace(" ", "+")}"
            }
            else -> baseUrl
        }
    }
    
    /**
     * Determine if a provider uses navigation instead of search
     */
    suspend fun usesNavigationInsteadOfSearch(providerId: String): Boolean {
        return navigationDao.getPatternForProvider(providerId) != null
    }
}
