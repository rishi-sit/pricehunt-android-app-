package com.pricehunt.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.model.SearchEvent
import com.pricehunt.data.repository.CacheStats
import com.pricehunt.data.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val query: String = "",
    val pincode: String = "560001",
    val isSearching: Boolean = false,
    val results: Map<String, List<Product>> = emptyMap(),
    val platformStatus: Map<String, PlatformStatus> = emptyMap(),
    val bestDeal: Product? = null,
    val error: String? = null,
    val cacheStats: CacheStats? = null,
    val platformsCompleted: Int = 0,
    val totalPlatforms: Int = 10
)

enum class PlatformStatus {
    PENDING, LOADING, COMPLETED, CACHED
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProductRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }
    
    fun updatePincode(pincode: String) {
        _uiState.update { it.copy(pincode = pincode) }
    }
    
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return
        
        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isSearching = true,
                    results = emptyMap(),
                    bestDeal = null,
                    error = null,
                    platformStatus = Platforms.ALL.associate { p -> p.name to PlatformStatus.PENDING },
                    platformsCompleted = 0,
                    totalPlatforms = Platforms.ALL.size
                )
            }
            
            val allResults = mutableMapOf<String, List<Product>>()
            
            repository.searchStream(query, _uiState.value.pincode)
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isSearching = false) }
                }
                .collect { event ->
                    when (event) {
                        is SearchEvent.Started -> {
                            _uiState.update {
                                it.copy(
                                    platformStatus = event.platforms.associateWith { PlatformStatus.LOADING }
                                )
                            }
                        }
                        is SearchEvent.PlatformResult -> {
                            allResults[event.platform] = event.products
                            
                            _uiState.update { state ->
                                val newStatus = state.platformStatus.toMutableMap()
                                newStatus[event.platform] = if (event.cached) {
                                    PlatformStatus.CACHED
                                } else {
                                    PlatformStatus.COMPLETED
                                }
                                
                                // Count completed platforms
                                val completedCount = newStatus.values.count { 
                                    it == PlatformStatus.COMPLETED || it == PlatformStatus.CACHED 
                                }
                                
                                // Sort results: Quick Commerce first, then E-Commerce
                                val sortedResults = sortResultsByPlatformType(allResults)
                                
                                state.copy(
                                    results = sortedResults,
                                    platformStatus = newStatus,
                                    bestDeal = repository.findBestDeal(allResults),
                                    platformsCompleted = completedCount
                                )
                            }
                        }
                        is SearchEvent.Completed -> {
                            _uiState.update { it.copy(isSearching = false) }
                            loadCacheStats()
                        }
                        is SearchEvent.Error -> {
                            _uiState.update { it.copy(error = event.message, isSearching = false) }
                        }
                    }
                }
        }
    }
    
    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            loadCacheStats()
        }
    }
    
    private fun loadCacheStats() {
        viewModelScope.launch {
            val stats = repository.getCacheStats()
            _uiState.update { it.copy(cacheStats = stats) }
        }
    }
    
    fun openProductUrl(url: String) {
        // Intent will be handled in the UI layer
    }
    
    /**
     * Sort results with Quick Commerce platforms first, then E-Commerce.
     * Within each category, maintain the order defined in Platforms.ALL
     */
    private fun sortResultsByPlatformType(results: Map<String, List<Product>>): Map<String, List<Product>> {
        // Define the preferred order: Quick Commerce first, then E-Commerce
        val platformOrder = listOf(
            // Quick Commerce (fast delivery)
            Platforms.ZEPTO,           // 10-15 mins
            Platforms.BLINKIT,         // 8-12 mins
            Platforms.FLIPKART_MINUTES, // 10-45 mins
            Platforms.JIOMART_QUICK,   // 10-30 mins
            Platforms.INSTAMART,       // 15-30 mins
            Platforms.AMAZON_FRESH,    // 2-4 hours
            Platforms.BIGBASKET,       // 2-4 hours
            // E-Commerce (slower delivery)
            Platforms.AMAZON,          // 1-3 days
            Platforms.FLIPKART,        // 2-4 days
            Platforms.JIOMART          // 1-3 days
        )
        
        return results.toList()
            .sortedBy { (platform, _) ->
                val index = platformOrder.indexOf(platform)
                if (index >= 0) index else platformOrder.size
            }
            .toMap()
    }
}

