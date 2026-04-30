package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.data.repository.AggregatorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Enhanced SearchViewModel to handle persistent search states, per-provider pagination,
 * and AI-driven result refinement.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AggregatorRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Persistent State keys for SavedStateHandle
    companion object {
        private const val KEY_QUERY = "current_query"
        private const val KEY_RESULTS = "results_by_provider"
    }

    // UI State: Map of Provider Name to its list of ResultItems
    private val _resultsByProvider = MutableStateFlow<Map<String, List<ResultItem>>>(
        savedStateHandle.get<Map<String, List<ResultItem>>>(KEY_RESULTS) ?: emptyMap()
    )
    val resultsByProvider: StateFlow<Map<String, List<ResultItem>>> = _resultsByProvider.asStateFlow()

    // Current query state
    private val _searchQuery = MutableStateFlow(savedStateHandle.get<String>(KEY_QUERY) ?: "")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Loading states per provider to ensure UI feedback for pagination
    private val _loadingProviders = MutableStateFlow<Set<String>>(emptySet())
    val loadingProviders: StateFlow<Set<String>> = _loadingProviders.asStateFlow()

    /**
     * Performs a fresh search across all enabled providers.
     * Clears previous results and resets pagination.
     */
    fun performNewSearch(query: String) {
        if (query.isBlank()) return
        
        _searchQuery.value = query
        savedStateHandle[KEY_QUERY] = query
        
        viewModelScope.launch {
            // NLP Query rewriting would happen here before triggering repository
            _resultsByProvider.value = emptyMap()
            
            repository.getEnabledProviders().collect { providers ->
                providers.forEach { provider ->
                    scrapeProvider(provider.name, page = 1)
                }
            }
        }
    }

    /**
     * Navigates pagination for a specific provider without affecting others.
     * @param providerName The unique identifier for the provider.
     * @param direction -1 for back, 1 for forward, 0 for refresh.
     */
    fun navigateProviderPage(providerName: String, direction: Int) {
        viewModelScope.launch {
            val provider = repository.getProviderByName(providerName) ?: return@launch
            val currentPage = provider.currentPage
            val nextPage = when (direction) {
                1 -> currentPage + 1
                -1 -> if (currentPage > 1) currentPage - 1 else 1
                else -> currentPage // Refresh current page
            }
            
            scrapeProvider(providerName, page = nextPage)
        }
    }

    private suspend fun scrapeProvider(providerName: String, page: Int) {
        _loadingProviders.update { it + providerName }
        
        try {
            // Trigger the repository/engine to scrape
            // The repository will update the ProviderEntity's currentPage in Room
            val newResults = repository.scrapeProvider(
                query = _searchQuery.value,
                providerName = providerName,
                page = page
            )

            _resultsByProvider.update { currentMap ->
                val updatedMap = currentMap.toMutableMap()
                updatedMap[providerName] = newResults
                savedStateHandle[KEY_RESULTS] = updatedMap
                updatedMap
            }
        } catch (e: Exception) {
            // Error handling logic for UI feedback
        } finally {
            _loadingProviders.update { it - providerName }
        }
    }

    /**
     * Updates the "Like" status of an item and triggers the AI preference loop.
     */
    fun toggleLike(item: ResultItem) {
        viewModelScope.launch {
            repository.updateItemLikeStatus(item.id, !item.isLiked)
            // Baseline results persist; AI loop will pick up this change in the background
        }
    }
}
