package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.engine.ai.NLPQueryEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AggregatorRepository,
    private val nlpEngine: NLPQueryEngine,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Persistent State Keys
    private companion object {
        const val KEY_QUERY = "current_query"
        const val KEY_RESULTS = "results_map"
        const val KEY_PAGES = "provider_pages"
    }

    // StateFlows for UI observation
    val currentQuery = savedStateHandle.getStateFlow(KEY_QUERY, "")
    
    private val _resultsByProvider = MutableStateFlow<Map<String, List<ResultItem>>>(
        savedStateHandle[KEY_RESULTS] ?: emptyMap()
    )
    val resultsByProvider = _resultsByProvider.asStateFlow()

    private val _providerPages = MutableStateFlow<Map<String, Int>>(
        savedStateHandle[KEY_PAGES] ?: emptyMap()
    )

    val isAiProcessing = nlpEngine.isProcessing

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
            
            // Observe the repository for result updates
            repository.getProviders().first().forEach { provider ->
                observeProvider(provider.name)
            }
        }
    }

    /**
     * Provider-specific pagination. Updates only the specific provider's 
     * slice of the result map.
     */
    fun navigateProviderPage(providerName: String, isForward: Boolean) {
        val currentPage = _providerPages.value[providerName] ?: 1
        val nextPage = if (isForward) currentPage + 1 else (currentPage - 1).coerceAtLeast(1)
        
        viewModelScope.launch {
            _providerPages.value = _providerPages.value + (providerName to nextPage)
            savedStateHandle[KEY_PAGES] = _providerPages.value
            
            repository.loadMore(providerName, if (isForward) 1 else -1)
        }
    }

    /**
     * Refreshes a single provider's results.
     */
    fun refreshProvider(providerName: String) {
        viewModelScope.launch {
            repository.loadMore(providerName, 0)
        }
    }

    private fun observeProvider(providerName: String) {
        viewModelScope.launch {
            repository.getResultsForProvider(providerName).collect { results ->
                val currentMap = _resultsByProvider.value.toMutableMap()
                currentMap[providerName] = results
                _resultsByProvider.value = currentMap
                savedStateHandle[KEY_RESULTS] = currentMap
            }
        }
    }

    fun toggleLike(item: ResultItem) {
        viewModelScope.launch {
            repository.toggleLike(item)
            
            // Trigger AI refinement loop if many items are liked
            val allLiked = _resultsByProvider.value.values.flatten().filter { it.isLiked }
            nlpEngine.startRefinementLoop(allLiked) { refinedQuery ->
                // Background loop adds content without clearing baseline
                viewModelScope.launch {
                    repository.performSearch(refinedQuery)
                }
            }
        }
    }
}
