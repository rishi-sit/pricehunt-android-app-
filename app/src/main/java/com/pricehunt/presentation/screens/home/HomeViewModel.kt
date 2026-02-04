package com.pricehunt.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.model.SearchEvent
import com.pricehunt.data.repository.CacheStats
import com.pricehunt.data.repository.ProductRepository
import com.pricehunt.data.repository.SmartSearchRepository
import com.pricehunt.data.repository.SmartSearchResult
import com.pricehunt.data.repository.FilterResult
import com.pricehunt.data.repository.LocalProductGroup
import com.pricehunt.data.search.SearchIntelligence  // Suggestions + per-unit helpers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class HomeUiState(
    val query: String = "",
    val pincode: String = "560001",
    val isSearching: Boolean = false,
    val isSendingToAI: Boolean = false,  // Indicates AI processing phase
    val results: Map<String, List<Product>> = emptyMap(),
    val platformStatus: Map<String, PlatformStatus> = emptyMap(),
    val bestDeal: Product? = null,
    val error: String? = null,
    val cacheStats: CacheStats? = null,
    val platformsCompleted: Int = 0,
    val totalPlatforms: Int = 10,
    // Search suggestions (local fallback available)
    val suggestions: List<String> = emptyList(),
    val showSuggestions: Boolean = false,
    // Grouped products for comparison
    val groupedProducts: Map<String, List<Product>> = emptyMap(),
    val productGroups: List<LocalProductGroup> = emptyList(),  // AI-matched groups
    // View mode toggle - regular view vs comparison view
    val isComparisonView: Boolean = false,
    // Filtering stats
    val aiPowered: Boolean = false,  // true = backend AI, false = local fallback
    val totalScraped: Int = 0,
    val totalFiltered: Int = 0,
    // Status message for user
    val statusMessage: String? = null,
    // NEW: Hybrid strategy indicators
    val showingCachedResults: Boolean = false,  // Shows "Showing cached results, refreshing..."
    val isRefreshingInBackground: Boolean = false  // Background refresh in progress
)

enum class PlatformStatus {
    PENDING, LOADING, COMPLETED, CACHED, FAILED
}

/**
 * HomeViewModel with AI-powered smart search + local fallback.
 * 
 * Primary Flow (Backend AI):
 * 1. Scrape ALL products from all platforms (no local filtering)
 * 2. Send raw scraped data to backend
 * 3. Backend uses AI (Mistral) to filter + match products
 * 4. Display AI-filtered results
 * 
 * Fallback Flow:
 * If backend is unavailable, show raw results without local filtering.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProductRepository,
    private val smartSearchRepository: SmartSearchRepository  // AI-powered search
) : ViewModel() {

    private companion object {
        const val FINAL_AI_MAX_WAIT_MS = 120_000L
        const val FINAL_AI_MIN_WAIT_MS = 3_000L
        const val FINAL_AI_SOFT_STATUS_MS = 15_000L
        const val FINAL_AI_RETRY_DELAY_MS = 5_000L
        const val FINAL_AI_RETRY_MAX_WAIT_MS = 60_000L
    }
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    // Store raw scraped products (before filtering)
    private var rawScrapedProducts = mutableListOf<Product>()

    private val activeAiCalls = AtomicInteger(0)
    
    
    fun updateQuery(query: String) {
        // Generate local suggestions as backup
        val suggestions = if (query.length >= 2) {
            SearchIntelligence.getSuggestions(query)
        } else {
            emptyList()
        }
        
        _uiState.update { 
            it.copy(
                query = query,
                suggestions = suggestions,
                showSuggestions = suggestions.isNotEmpty() && query.isNotBlank()
            ) 
        }
    }
    
    fun selectSuggestion(suggestion: String) {
        _uiState.update { 
            it.copy(
                query = suggestion,
                showSuggestions = false,
                suggestions = emptyList()
            )
        }
        search()
    }
    
    fun hideSuggestions() {
        _uiState.update { it.copy(showSuggestions = false) }
    }
    
    fun updatePincode(pincode: String) {
        _uiState.update { it.copy(pincode = pincode) }
    }
    
    /**
     * HYBRID CACHE + LIVE STRATEGY
     * 
     * This provides instant results while ensuring freshness:
     * 
     * Phase 1 (INSTANT): Show cached results immediately (even if slightly stale)
     * Phase 2 (BACKGROUND): Scrape fresh data from all platforms  
     * Phase 3 (UPDATE): Replace/merge fresh results as they arrive
     * Phase 4 (AI): Apply AI filtering to fresh results
     * 
     * User sees results in <1 second, then sees updates as fresh data arrives.
     */
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return
        
        // Reset raw products
        rawScrapedProducts.clear()
        activeAiCalls.set(0)
        
        println("📝 HYBRID SEARCH: Starting for '$query'")
        
        viewModelScope.launch {
            val allResults = mutableMapOf<String, List<Product>>()
            val aiResultsByPlatform = mutableMapOf<String, List<Product>>()
            val platformRequestVersion = mutableMapOf<String, Int>()
            val platformFilteredCounts = mutableMapOf<String, Int>()
            val aiPoweredPlatforms = mutableSetOf<String>()
            val aiSemaphore = Semaphore(4)

            fun productKey(product: Product): String {
                val normalizedName = product.name.trim().lowercase()
                val normalizedUrl = product.url.trim().lowercase()
                val normalizedPlatform = product.platform.trim().lowercase()
                return if (normalizedUrl.isNotBlank()) {
                    "$normalizedPlatform|$normalizedUrl"
                } else {
                    "$normalizedPlatform|$normalizedName|${product.price}"
                }
            }

            fun applyAiFilterToExisting(
                existing: Map<String, List<Product>>,
                aiProducts: List<Product>
            ): Map<String, List<Product>> {
                val relevantKeys = aiProducts.map { productKey(it) }.toSet()
                val filtered = existing.mapValues { (_, products) ->
                    products.filter { relevantKeys.contains(productKey(it)) }
                }
                return sortResultsByPlatformType(filtered)
            }

            fun mergedResults(): Map<String, List<Product>> {
                val merged = allResults.toMutableMap()
                aiResultsByPlatform.forEach { (platform, products) ->
                    merged[platform] = products
                }
                return sortResultsByPlatformType(merged)
            }

            fun updateMergedResults() {
                _uiState.update { state ->
                    val merged = mergedResults()
                    val fallbackBestDeal = repository.findBestDealWithRelevance(merged, query)
                    val bestDeal = if (state.aiPowered && state.bestDeal != null) {
                        state.bestDeal
                    } else {
                        fallbackBestDeal
                    }
                    state.copy(
                        results = merged,
                        bestDeal = bestDeal,
                        aiPowered = aiPoweredPlatforms.isNotEmpty(),
                        totalFiltered = platformFilteredCounts.values.sum()
                    )
                }
            }

            fun isRetryableAiError(message: String?): Boolean {
                if (message.isNullOrBlank()) return true
                val normalized = message.lowercase()
                return listOf(
                    "timeout",
                    "timed out",
                    "failed to connect",
                    "connection",
                    "unable to resolve host",
                    "host is unreachable",
                    "http 5",
                    "502",
                    "503",
                    "504"
                ).any { normalized.contains(it) }
            }

            suspend fun runFinalAi(timeoutMs: Long): SmartSearchResult? {
                val aiStart = System.currentTimeMillis()
                println("🤖 Final AI request started (timeout=${timeoutMs}ms, products=${rawScrapedProducts.size})")
                val result = withTimeoutOrNull(timeoutMs) {
                    smartSearchRepository.smartSearch(
                        query = query,
                        scrapedProducts = rawScrapedProducts.toList(),
                        pincode = _uiState.value.pincode,
                        strictMode = true,
                        platformResults = allResults.toMap()
                    )
                }
                val aiElapsed = System.currentTimeMillis() - aiStart
                when (result) {
                    is SmartSearchResult.Success -> {
                        println(
                            "✅ Final AI success in ${aiElapsed}ms " +
                                "(aiPowered=${result.aiPowered}, products=${result.relevantProducts.size})"
                        )
                    }
                    is SmartSearchResult.Error -> {
                        println("❌ Final AI error in ${aiElapsed}ms: ${result.message}")
                    }
                    null -> {
                        println("⏱️ Final AI timeout after ${aiElapsed}ms")
                    }
                }
                return result
            }

            fun enqueuePlatformAi(
                platform: String,
                products: List<Product>,
                timeoutMs: Long,
                reason: String
            ) {
                if (products.isEmpty()) {
                    println("🔍 Per-platform AI: $platform - skipped (0 products)")
                    aiResultsByPlatform.remove(platform)
                    platformFilteredCounts.remove(platform)
                    updateMergedResults()
                    return
                }

                val requestId = (platformRequestVersion[platform] ?: 0) + 1
                platformRequestVersion[platform] = requestId
                println("🔍 Per-platform AI: $platform - queued (${products.size} products, timeout=${timeoutMs}ms, reason=$reason)")

                launch {
                    aiSemaphore.acquire()
                    println("🔍 Per-platform AI: $platform - started")
                    startAiCall()
                    try {
                        val aiStart = System.currentTimeMillis()
                        val result = withTimeoutOrNull(timeoutMs) {
                            smartSearchRepository.filterProducts(
                                query = query,
                                products = products,
                                pincode = _uiState.value.pincode,
                                strictMode = true,
                                platformResults = mapOf(platform to products)
                            )
                        }
                        val aiElapsed = System.currentTimeMillis() - aiStart

                        if (platformRequestVersion[platform] != requestId) {
                            println("🔍 Per-platform AI: $platform - cancelled (stale request)")
                            return@launch
                        }

                        when (result) {
                            is FilterResult.Success -> {
                                println(
                                    "✅ Per-platform AI: $platform - success in ${aiElapsed}ms " +
                                        "(aiPowered=${result.aiPowered}, ${result.relevantProducts.size}/${products.size} kept, ${result.filteredOut.size} filtered)"
                                )
                                if (result.relevantProducts.isEmpty() && products.isNotEmpty()) {
                                    println("⚠️ AI returned 0 items for $platform; keeping raw results")
                                    aiResultsByPlatform[platform] = products
                                    platformFilteredCounts[platform] = 0
                                } else {
                                    aiResultsByPlatform[platform] = result.relevantProducts
                                    platformFilteredCounts[platform] = result.filteredOut.size
                                }
                                if (result.aiPowered) aiPoweredPlatforms.add(platform)
                            }
                            is FilterResult.Error -> {
                                println("❌ Per-platform AI: $platform - error in ${aiElapsed}ms: ${result.message}")
                                aiResultsByPlatform[platform] = products
                                platformFilteredCounts[platform] = 0
                            }
                            null -> {
                                println("⏱️ Per-platform AI: $platform - timeout after ${aiElapsed}ms")
                                aiResultsByPlatform[platform] = products
                                platformFilteredCounts[platform] = 0
                            }
                        }

                        updateMergedResults()
                    } finally {
                        finishAiCall()
                        aiSemaphore.release()
                    }
                }
            }

            // PHASE 1: INSTANT - Show cached results immediately
            val cachedResults = repository.getCachedResults(query, _uiState.value.pincode)
            val hasCachedResults = cachedResults.values.flatten().isNotEmpty()
            
            if (hasCachedResults) {
                println("⚡ INSTANT: Found ${cachedResults.values.flatten().size} cached products")
                
                // Show cached results as-is (AI will handle relevance)
                val sortedCached = sortResultsByPlatformType(cachedResults)
                val cachedBestDeal = repository.findBestDealWithRelevance(sortedCached, query)
                allResults.putAll(cachedResults)
                
                _uiState.update { 
                    it.copy(
                        isSearching = false,  // Not "searching" - we have results!
                        isRefreshingInBackground = true,  // But refreshing in background
                        showingCachedResults = true,
                        results = sortedCached,
                        bestDeal = cachedBestDeal,
                        platformStatus = cachedResults.keys.associateWith { PlatformStatus.CACHED },
                        platformsCompleted = cachedResults.size,
                        totalPlatforms = Platforms.ALL.size,
                        showSuggestions = false,
                        totalScraped = cachedResults.values.flatten().size,
                        statusMessage = "Showing current results • Refreshing..."
                    )
                }

                cachedResults.forEach { (platform, products) ->
                    enqueuePlatformAi(platform, products, timeoutMs = 4_000L, reason = "cache")
                }
            } else {
                // No cache - show loading state
                _uiState.update { 
                    it.copy(
                        isSearching = true,
                        isSendingToAI = false,
                        results = emptyMap(),
                        bestDeal = null,
                        error = null,
                        platformStatus = Platforms.ALL.associate { p -> p.name to PlatformStatus.PENDING },
                        platformsCompleted = 0,
                        totalPlatforms = Platforms.ALL.size,
                        showSuggestions = false,
                        groupedProducts = emptyMap(),
                        productGroups = emptyList(),
                        aiPowered = false,
                        totalScraped = 0,
                        totalFiltered = 0,
                        showingCachedResults = false,
                        isRefreshingInBackground = false,
                        statusMessage = "Searching across platforms..."
                    )
                }
            }
            
            repository.searchStream(query, _uiState.value.pincode)
                .catch { e ->
                    if (!hasCachedResults) {
                        _uiState.update { it.copy(error = e.message, isSearching = false) }
                    }
                }
                .collect { event ->
                    when (event) {
                        is SearchEvent.Started -> {
                            if (!hasCachedResults) {
                                _uiState.update {
                                    it.copy(
                                        platformStatus = event.platforms.associateWith { PlatformStatus.LOADING }
                                    )
                                }
                            }
                        }
                        is SearchEvent.Message -> {
                            println("Search progress: ${event.text}")
                        }
                        is SearchEvent.PlatformResult -> {
                            val products = event.products
                            rawScrapedProducts.addAll(products)
                            allResults[event.platform] = products
                            enqueuePlatformAi(
                                platform = event.platform,
                                products = products,
                                timeoutMs = 4_000L,
                                reason = "fresh"
                            )
                            
                            _uiState.update { state ->
                                val newStatus = state.platformStatus.toMutableMap()
                                newStatus[event.platform] = when {
                                    event.cached -> PlatformStatus.CACHED
                                    products.isNotEmpty() -> PlatformStatus.COMPLETED
                                    else -> PlatformStatus.FAILED
                                }
                                
                                val completedCount = newStatus.values.count { 
                                    it == PlatformStatus.COMPLETED || it == PlatformStatus.CACHED 
                                }
                                
                                // Progressive update: merge fresh results with display
                                val sortedResults = mergedResults()
                                val bestDeal = repository.findBestDealWithRelevance(sortedResults, query)
                                
                                println("📊 Scraping: ${completedCount}/${Platforms.ALL.size} platforms, Total: ${rawScrapedProducts.size}")
                                
                                state.copy(
                                    results = sortedResults,
                                    bestDeal = bestDeal,
                                    platformStatus = newStatus,
                                    platformsCompleted = completedCount,
                                    totalScraped = rawScrapedProducts.size,
                                    statusMessage = if (state.showingCachedResults) {
                                        "Showing current results • Refreshing... ${completedCount}/${Platforms.ALL.size} platforms • AI refining soon"
                                    } else {
                                        "Showing current results • ${completedCount}/${Platforms.ALL.size} platforms • AI refining soon"
                                    }
                                )
                            }
                        }
                        is SearchEvent.Completed -> {
                            // PHASE 4: Final backend grouping + smart search
                            println("✅ Fresh scraping complete. Total: ${rawScrapedProducts.size} products")
                            println("🤖 Applying AI smart search...")

                            launch {
                                _uiState.update { state ->
                                    state.copy(
                                        statusMessage = "Showing current results • Sent to AI for relevance"
                                    )
                                }
                                startAiCall()
                                try {
                                    val softStatusJob = launch {
                                        delay(FINAL_AI_SOFT_STATUS_MS)
                                        _uiState.update { state ->
                                            if (state.isSendingToAI) {
                                                state.copy(
                                                    statusMessage = "AI is taking longer • Showing current results"
                                                )
                                            } else {
                                                state
                                            }
                                        }
                                    }

                                    var finalResult = runFinalAi(
                                        FINAL_AI_MAX_WAIT_MS.coerceAtLeast(FINAL_AI_MIN_WAIT_MS)
                                    )
                                    softStatusJob.cancel()

                                    val shouldRetry = when (finalResult) {
                                        null -> true
                                        is SmartSearchResult.Error ->
                                            isRetryableAiError(finalResult.message)
                                        else -> false
                                    }

                                    if (shouldRetry) {
                                        _uiState.update { state ->
                                            state.copy(
                                                statusMessage = "AI is taking longer • Retrying..."
                                            )
                                        }
                                        delay(FINAL_AI_RETRY_DELAY_MS)
                                        finalResult = runFinalAi(FINAL_AI_RETRY_MAX_WAIT_MS)
                                    }

                                    when (finalResult) {
                                        is SmartSearchResult.Success -> {
                                            if (finalResult.relevantProducts.isEmpty() && rawScrapedProducts.isNotEmpty()) {
                                                println("⚠️ Final AI returned 0 items; keeping current results")
                                                _uiState.update { state ->
                                                    state.copy(
                                                        isSearching = false,
                                                        showingCachedResults = false,
                                                        isRefreshingInBackground = false,
                                                        statusMessage = "AI returned no relevant items • Showing current results",
                                                        error = null
                                                    )
                                                }
                                                loadCacheStats()
                                            } else {
                                                val groupedProducts = finalResult.productGroups
                                                    .filter { it.products.size >= 2 }
                                                    .associate { group -> group.canonicalName to group.products }

                                                _uiState.update { state ->
                                                    val filteredResults = applyAiFilterToExisting(
                                                        state.results,
                                                        finalResult.relevantProducts
                                                    )
                                                    state.copy(
                                                        isSearching = false,
                                                        results = filteredResults,
                                                        bestDeal = finalResult.bestDeal ?: state.bestDeal,
                                                        groupedProducts = groupedProducts,
                                                        productGroups = finalResult.productGroups,
                                                        aiPowered = finalResult.aiPowered || aiPoweredPlatforms.isNotEmpty(),
                                                        totalFiltered = finalResult.filteredOut.size,
                                                        showingCachedResults = false,
                                                        isRefreshingInBackground = false,
                                                        statusMessage = if (finalResult.aiPowered) {
                                                            "Smart AI powered result"
                                                        } else {
                                                            "Showing current results"
                                                        },
                                                        error = null
                                                    )
                                                }
                                                loadCacheStats()
                                            }
                                        }
                                        is SmartSearchResult.Error, null -> {
                                            val errorMessage = (finalResult as? SmartSearchResult.Error)?.message
                                            val isTimeout = finalResult == null ||
                                                (errorMessage?.contains("timeout", ignoreCase = true) == true) ||
                                                (errorMessage?.contains("timed out", ignoreCase = true) == true)
                                            val hasPartialAi = aiPoweredPlatforms.isNotEmpty()
                                            val status = when {
                                                hasPartialAi -> "Smart AI powered result"
                                                isTimeout -> "AI is taking longer • Showing current results"
                                                else -> "AI unavailable • Showing current results"
                                            }
                                            println("❌ Final AI grouping failed: ${errorMessage ?: "timeout"}")
                                            _uiState.update { state ->
                                                state.copy(
                                                    isSearching = false,
                                                    showingCachedResults = false,
                                                    isRefreshingInBackground = false,
                                                    aiPowered = state.aiPowered || hasPartialAi,
                                                    statusMessage = status
                                                )
                                            }
                                            loadCacheStats()
                                        }
                                    }
                                } finally {
                                    finishAiCall()
                                }
                            }
                        }
                        is SearchEvent.Error -> {
                            if (!hasCachedResults) {
                                _uiState.update { it.copy(error = event.message, isSearching = false) }
                            } else {
                                // Keep showing cached results on error
                                _uiState.update { it.copy(
                                    isRefreshingInBackground = false,
                                    statusMessage = "Showing cached results (refresh failed)"
                                ) }
                            }
                        }
                    }
                }
        }
    }

    private fun startAiCall() {
        val count = activeAiCalls.incrementAndGet()
        if (count == 1) {
            _uiState.update { it.copy(isSendingToAI = true) }
        }
    }

    private fun finishAiCall() {
        val count = activeAiCalls.decrementAndGet()
        if (count <= 0) {
            activeAiCalls.set(0)
            _uiState.update { it.copy(isSendingToAI = false) }
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
     * Toggle between regular view (by platform) and comparison view (grouped similar products)
     */
    fun setComparisonView(isComparison: Boolean) {
        _uiState.update { it.copy(isComparisonView = isComparison) }
    }
    
    /**
     * Check if AI backend is available.
     */
    fun checkAIAvailability() {
        viewModelScope.launch {
            val isAvailable = smartSearchRepository.isAiAvailable()
            println("🤖 AI Backend available: $isAvailable")
        }
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

