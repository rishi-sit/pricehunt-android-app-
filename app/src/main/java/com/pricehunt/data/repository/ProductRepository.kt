package com.pricehunt.data.repository

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.model.SearchEvent
import com.pricehunt.data.remote.PriceHuntApi
import com.pricehunt.data.scrapers.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that coordinates all platform scrapers and provides streaming search results.
 * 
 * Strategy: DIRECT API CALLS TO EACH PLATFORM
 * 1. Each scraper calls its platform's internal search API
 * 2. Results are collected and displayed progressively
 * 3. Cache results for future use
 */
@Singleton
class ProductRepository @Inject constructor(
    private val api: PriceHuntApi,  // Kept for potential future use
    private val cacheManager: CacheManager,
    private val amazonScraper: AmazonScraper,
    private val amazonFreshScraper: AmazonFreshScraper,
    private val flipkartScraper: FlipkartScraper,
    private val flipkartMinutesScraper: FlipkartMinutesScraper,
    private val jioMartScraper: JioMartScraper,
    private val jioMartQuickScraper: JioMartQuickScraper,
    private val bigBasketScraper: BigBasketScraper,
    private val zeptoScraper: ZeptoScraper,
    private val blinkitScraper: BlinkitScraper,
    private val instamartScraper: InstamartScraper
) {
    companion object {
        // Time to wait for each platform's API call
        private const val PLATFORM_TIMEOUT_MS = 30_000L  // 30 seconds per platform
        // Maximum time to wait for entire search
        private const val MAX_SEARCH_TIME_MS = 60_000L  // 60 seconds max
    }
    
    private val scrapers = listOf(
        zeptoScraper,
        blinkitScraper,
        bigBasketScraper,
        instamartScraper,
        amazonFreshScraper,
        flipkartMinutesScraper,
        jioMartQuickScraper,
        amazonScraper,
        flipkartScraper,
        jioMartScraper
    )
    
    /**
     * Search all platforms using their internal APIs.
     * Results are emitted progressively as each platform responds.
     */
    fun searchStream(query: String, pincode: String): Flow<SearchEvent> = flow {
        emit(SearchEvent.Started(scrapers.map { it.platformName }))
        
        val resultsChannel = Channel<SearchEvent.PlatformResult>(Channel.UNLIMITED)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        val emittedPlatforms = ConcurrentHashMap<String, Boolean>()
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalPlatforms = scrapers.size
        
        try {
            println("=== STARTING SEARCH: $query (pincode: $pincode) ===")
            
            // Launch all platform API calls in parallel
            scrapers.forEach { scraper ->
                scope.launch {
                    try {
                        // Check cache first
                        val (cached, isStale) = cacheManager.get(query, scraper.platformName, pincode)
                        
                        if (cached != null && !isStale && cached.isNotEmpty()) {
                            println("${scraper.platformName}: Using cached results (${cached.size} products)")
                            emittedPlatforms[scraper.platformName] = true
                            resultsChannel.send(SearchEvent.PlatformResult(
                                platform = scraper.platformName,
                                products = cached,
                                cached = true
                            ))
                            return@launch
                        }
                        
                        // Call platform's internal API with timeout
                        println("${scraper.platformName}: Calling API...")
                        val products = withTimeoutOrNull(PLATFORM_TIMEOUT_MS) {
                            scraper.search(query, pincode)
                        } ?: emptyList()
                        
                        if (products.isNotEmpty()) {
                            cacheManager.set(query, scraper.platformName, pincode, products)
                            println("${scraper.platformName}: ✓ Found ${products.size} products")
                        } else {
                            println("${scraper.platformName}: ✗ No products found")
                        }
                        
                        emittedPlatforms[scraper.platformName] = true
                        resultsChannel.send(SearchEvent.PlatformResult(
                            platform = scraper.platformName,
                            products = products,
                            cached = false
                        ))
                        
                    } catch (e: Exception) {
                        println("${scraper.platformName}: ✗ Error - ${e.message}")
                        emittedPlatforms[scraper.platformName] = true
                        resultsChannel.send(SearchEvent.PlatformResult(
                            platform = scraper.platformName,
                            products = emptyList(),
                            cached = false
                        ))
                    } finally {
                        completedCount.incrementAndGet()
                    }
                }
            }
            
            // Emit results as they arrive
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < MAX_SEARCH_TIME_MS) {
                // Try to receive a result
                val result = withTimeoutOrNull(500) { 
                    resultsChannel.receiveCatching().getOrNull() 
                }
                
                if (result != null) {
                    emit(result)
                }
                
                // Check if all platforms have completed
                if (completedCount.get() >= totalPlatforms) {
                    // Drain any remaining results
                    delay(200)
                    while (true) {
                        val remaining = resultsChannel.tryReceive().getOrNull() ?: break
                        emit(remaining)
                    }
                    break
                }
            }
            
            // Emit empty results for any platforms that didn't respond
            scrapers.forEach { scraper ->
                if (emittedPlatforms.putIfAbsent(scraper.platformName, true) == null) {
                    println("${scraper.platformName}: Timeout - no response")
                    emit(SearchEvent.PlatformResult(
                        platform = scraper.platformName,
                        products = emptyList(),
                        cached = false
                    ))
                }
            }
            
            println("=== SEARCH COMPLETED ===")
            emit(SearchEvent.Completed)
            
        } catch (e: Exception) {
            println("Search error: ${e.message}")
            emit(SearchEvent.Error(e.message ?: "Unknown error"))
        } finally {
            resultsChannel.close()
            scope.cancel()
        }
    }
    
    /**
     * Search all platforms and return aggregated results (non-streaming).
     */
    suspend fun search(query: String, pincode: String): Map<String, List<Product>> = 
        withContext(Dispatchers.IO) {
            val results = mutableMapOf<String, List<Product>>()
            
            val jobs = scrapers.map { scraper ->
                async {
                    try {
                        // Check cache
                        val (cached, isStale) = cacheManager.get(query, scraper.platformName, pincode)
                        
                        if (cached != null && !isStale) {
                            return@async scraper.platformName to cached
                        }
                        
                        val products = withTimeoutOrNull(PLATFORM_TIMEOUT_MS) {
                            scraper.search(query, pincode)
                        } ?: emptyList()
                        
                        if (products.isNotEmpty()) {
                            cacheManager.set(query, scraper.platformName, pincode, products)
                        }
                        
                        scraper.platformName to products
                    } catch (e: Exception) {
                        scraper.platformName to emptyList()
                    }
                }
            }
            
            jobs.forEach { job ->
                val (platform, products) = job.await()
                results[platform] = products
            }
            
            results
        }
    
    /**
     * Get the cheapest product across all results.
     */
    fun findBestDeal(results: Map<String, List<Product>>): Product? {
        return results.values
            .flatten()
            .filter { it.available && it.price > 0 }
            .minByOrNull { it.price }
    }
    
    /**
     * Get all platforms.
     */
    fun getPlatforms() = Platforms.ALL
    
    /**
     * Clear cache.
     */
    suspend fun clearCache() {
        cacheManager.clearAll()
    }
    
    /**
     * Get cache stats.
     */
    suspend fun getCacheStats() = cacheManager.getStats()
}
