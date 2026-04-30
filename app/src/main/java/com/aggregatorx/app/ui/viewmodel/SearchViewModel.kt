package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.data.repository.PageDirection
import com.aggregatorx.app.engine.ai.NLPQueryEngine
import com.aggregatorx.app.engine.ai.SmartQueryEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AggregatorRepository,
    private val nlpEngine: NLPQueryEngine,
    private val smartQueryEngine: SmartQueryEngine,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // ── Persistent State Keys (SavedStateHandle survives process death and
    //    short backgrounding for the in-app browser / video player). ────
    private companion object {
        const val KEY_QUERY   = "current_query"
        const val KEY_RESULTS = "results_map"
        const val KEY_PAGES   = "provider_pages"
    }

    // currentQuery is fully owned by SavedStateHandle so it survives across
    // configuration changes and brief backgrounding.
    val currentQuery = savedStateHandle.getStateFlow(KEY_QUERY, "")

    // Per-provider results map. Initialized from SavedStateHandle so the
    // user returns from the video player / browser to the same view.
    private val _resultsByProvider = MutableStateFlow<Map<String, List<ResultItem>>>(
        savedStateHandle[KEY_RESULTS] ?: emptyMap()
    )
    val resultsByProvider = _resultsByProvider.asStateFlow()

    private val _providerPages = MutableStateFlow<Map<String, Int>>(
        savedStateHandle[KEY_PAGES] ?: emptyMap()
    )
    val providerPages = _providerPages.asStateFlow()

    val isAiProcessing = nlpEngine.isProcessing

    // Track the providers we've already started observing so we don't
    // accumulate redundant collectors across recompositions.
    private val observedProviders = mutableSetOf<String>()

    init {
        // Reattach observers for any providers we already have cached results for
        // (case: process-death restore — the map is back from SavedStateHandle but
        // the Flow collectors were torn down when the VM was destroyed).
        _resultsByProvider.value.keys.forEach { observeProvider(it) }
    }

    /**
     * Executes a fresh search. Rewrites the query via NLP before
     * broadcasting to all enabled providers.
     */
    fun performSearch(query: String) {
        if (query.isBlank()) return

        savedStateHandle[KEY_QUERY] = query
        viewModelScope.launch {
            val optimizedQuery = nlpEngine.rewriteQuery(query)
            repository.performSearch(optimizedQuery)

            // Reset paging map on a fresh search.
            _providerPages.value = emptyMap()
            savedStateHandle[KEY_PAGES] = _providerPages.value

            repository.getProviders().first().forEach { provider ->
                observeProvider(provider.name)
            }
        }
    }

    /**
     * Provider-specific pagination — updates only the specific provider's
     * slice of the result map (Repository handles per-provider DB locking).
     */
    fun navigateProviderPage(providerName: String, isForward: Boolean) {
        viewModelScope.launch {
            val direction = if (isForward) PageDirection.FORWARD else PageDirection.BACK
            repository.loadMore(providerName, direction)
            syncProviderPage(providerName)
        }
    }

    /**
     * Smart auto-search: lets the user kick off a scrape without typing
     * anything. Picks a query from [SmartQueryEngine] (liked items →
     * cached titles → seed list) and runs it like a normal search.
     *
     * Used by the "✨ Smart test" button on SearchScreen and by the
     * empty-state if the user opens the app cold with no history.
     */
    fun runSmartTestSearch() {
        viewModelScope.launch {
            val q = smartQueryEngine.nextQuery()
            if (q.isNotBlank()) {
                savedStateHandle[KEY_QUERY] = q
                repository.performSearch(q)

                _providerPages.value = emptyMap()
                savedStateHandle[KEY_PAGES] = _providerPages.value

                repository.getProviders().first().forEach { provider ->
                    observeProvider(provider.name)
                }
            }
        }
    }

    /**
     * Refreshes a single provider's results — same page, fresh scrape.
     */
    fun refreshProvider(providerName: String) {
        viewModelScope.launch {
            repository.loadMore(providerName, PageDirection.REFRESH)
            syncProviderPage(providerName)
        }
    }

    fun toggleLike(item: ResultItem) {
        viewModelScope.launch {
            repository.toggleLike(item)

            val allLiked = _resultsByProvider.value.values
                .flatten()
                .filter { it.isLiked }
            nlpEngine.startRefinementLoop(allLiked) { refinedQuery ->
                viewModelScope.launch {
                    repository.performSearch(refinedQuery)
                }
            }
        }
    }

    private fun observeProvider(providerName: String) {
        if (!observedProviders.add(providerName)) return
        viewModelScope.launch {
            repository.getResultsForProvider(providerName).collect { results ->
                val currentMap = _resultsByProvider.value.toMutableMap()
                currentMap[providerName] = results
                _resultsByProvider.value = currentMap
                savedStateHandle[KEY_RESULTS] = currentMap
            }
        }
    }

    /**
     * After a paginate / refresh call, mirror the provider's persisted
     * `currentPage` back into the in-memory map exposed to the UI.
     */
    private suspend fun syncProviderPage(providerName: String) {
        val providers = repository.getProviders().first()
        val provider = providers.find { it.name == providerName } ?: return
        _providerPages.value = _providerPages.value + (providerName to provider.currentPage)
        savedStateHandle[KEY_PAGES] = _providerPages.value
    }
}
