package com.pricehunt.data.repository

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.model.SearchEvent
import com.pricehunt.data.search.PerUnitPrice
import com.pricehunt.data.search.SearchIntelligence
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
     * @deprecated Use findBestDealWithRelevance for smarter results
     */
    fun findBestDeal(results: Map<String, List<Product>>): Product? {
        return results.values
            .flatten()
            .filter { it.available && it.price > 0 }
            .minByOrNull { it.price }
    }
    
    /**
     * Find the best deal considering both price AND relevance.
     * 
     * Logic:
     * 1. Products where the search term is the PRIMARY item (e.g., "Strawberry 200g") 
     *    are prioritized over flavored products (e.g., "Strawberry Juice")
     * 2. Among equally relevant products, the cheapest wins
     * 3. Product name starting with search term gets highest priority
     */
    fun findBestDealWithRelevance(
        results: Map<String, List<Product>>, 
        searchQuery: String
    ): Product? {
        val allProducts = results.values.flatten()
            .filter { it.available && it.price > 0 }
        
        if (allProducts.isEmpty()) return null
        
        val queryLower = searchQuery.lowercase().trim()
        val queryWords = queryLower.split(" ").filter { it.isNotBlank() }
        val primaryKeyword = queryWords.firstOrNull() ?: queryLower
        
        println("🔍 Best Deal Search: '$searchQuery' → Primary keyword: '$primaryKeyword'")
        
        // Derivative/flavored product indicators
        val derivativeIndicators = listOf(
            "juice", "jam", "jelly", "sauce", "syrup", "flavour", "flavor", 
            "essence", "extract", "candy", "chocolate", "ice cream", "shake",
            "smoothie", "squash", "drink", "beverage", "powder", "mix", "bar",
            "cake", "pastry", "muffin", "cookie", "biscuit", "wafer", "toffee"
        )
        
        // Score each product for relevance
        val scoredProducts = allProducts.map { product ->
            val nameLower = product.name.lowercase()
            val nameWords = nameLower.split(" ", ",", "-", "(", ")").filter { it.isNotBlank() }
            
            var relevanceScore = 0
            var reason = ""
            
            // +50: Name starts with the search keyword (most relevant)
            if (nameWords.firstOrNull()?.contains(primaryKeyword) == true) {
                relevanceScore += 50
                reason = "starts with '$primaryKeyword'"
            }
            // +40: Search keyword is at the END (e.g., "Fortune Sunflower Oil" - oil is the product type)
            else if (nameWords.takeLast(2).any { it.contains(primaryKeyword) }) {
                relevanceScore += 40
                reason = "'$primaryKeyword' at end (product type)"
            }
            // +30: Search keyword in first 3 words
            else if (nameWords.take(3).any { it.contains(primaryKeyword) }) {
                relevanceScore += 30
                reason = "'$primaryKeyword' in first 3 words"
            }
            // +20: Contains the keyword somewhere (still relevant)
            else if (nameLower.contains(primaryKeyword)) {
                relevanceScore += 20
                reason = "contains '$primaryKeyword'"
            }
            
            // -40: It's a derivative/flavored product (not the primary item)
            val isDerivative = derivativeIndicators.any { nameLower.contains(it) }
            if (isDerivative) {
                relevanceScore -= 40
                reason += " [DERIVATIVE -40]"
            }
            
            // +20: Has quantity indicator (likely actual product, not sample/essence)
            val hasQuantity = Regex("""\d+\s*(g|gm|kg|ml|l|pc|pcs)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(nameLower)
            if (hasQuantity) {
                relevanceScore += 20
            }
            
            // +10: Has "fresh" or similar indicator
            if (nameLower.contains("fresh") || nameLower.contains("organic")) {
                relevanceScore += 10
            }
            
            Pair(product, relevanceScore)
        }
        
        // Group by relevance tier
        // HIGH: Score >= 20 (keyword is prominently placed OR has fresh/organic)
        // MEDIUM: Score >= 0 (contains keyword but not derivative)
        // LOW: Score < 0 (derivative products like juice, essence, etc.)
        val highlyRelevant = scoredProducts.filter { it.second >= 20 }
        val moderatelyRelevant = scoredProducts.filter { it.second in 0..19 }
        val lowRelevant = scoredProducts.filter { it.second < 0 }
        
        println("📊 Relevance: HIGH=${highlyRelevant.size}, MEDIUM=${moderatelyRelevant.size}, LOW=${lowRelevant.size}")
        
        // Log top candidates from each tier (sorted by price)
        highlyRelevant.sortedBy { it.first.price }.take(5).forEach { (p, score) ->
            println("  ✅ HIGH [$score]: ${p.name.take(40)} = ₹${p.price}")
        }
        moderatelyRelevant.sortedBy { it.first.price }.take(3).forEach { (p, score) ->
            println("  ⚠️ MED [$score]: ${p.name.take(40)} = ₹${p.price}")
        }
        lowRelevant.sortedBy { it.first.price }.take(2).forEach { (p, score) ->
            println("  ❌ LOW [$score]: ${p.name.take(40)} = ₹${p.price}")
        }
        
        // Find best deal using per-unit price when possible for fair comparison
        // e.g., 200ml @ ₹109 = ₹54.5/100ml vs 500ml @ ₹250 = ₹50/100ml → 500ml is better value
        val bestDeal = when {
            highlyRelevant.isNotEmpty() -> findBestValueInTier(highlyRelevant)
            moderatelyRelevant.isNotEmpty() -> findBestValueInTier(moderatelyRelevant)
            else -> findBestValueInTier(lowRelevant)
        }
        
        println("🏆 Best Deal: ${bestDeal?.name?.take(50)} = ₹${bestDeal?.price}")
        
        return bestDeal
    }
    
    /**
     * Find the best value product in a tier using per-unit price comparison.
     * If per-unit price can be calculated, use it. Otherwise, fall back to raw price.
     */
    private fun findBestValueInTier(products: List<Pair<Product, Int>>): Product? {
        if (products.isEmpty()) return null
        
        // Calculate per-unit price for each product
        data class ProductWithPerUnit(
            val product: Product, 
            val score: Int, 
            val perUnit: PerUnitPrice?
        )
        
        val productsWithPerUnit = products.map { pair ->
            val perUnitPrice = SearchIntelligence.calculatePerUnitPrice(pair.first)
            ProductWithPerUnit(pair.first, pair.second, perUnitPrice)
        }
        
        // Split into products with and without per-unit prices
        val withPerUnit = productsWithPerUnit.filter { it.perUnit != null }
        
        println("  📏 Per-unit comparison: ${withPerUnit.size} products have calculable unit price")
        
        // Log per-unit prices for debugging
        withPerUnit.sortedBy { it.perUnit!!.pricePerBaseUnit }.take(5).forEach { item ->
            println("    💰 ${item.product.name.take(35)} = ₹${item.product.price} (${item.perUnit!!.toDisplayString()})")
        }
        
        // If we have products with per-unit prices, find the best value (lowest per-unit price)
        val bestByPerUnit = withPerUnit.minByOrNull { it.perUnit!!.pricePerBaseUnit }
        
        // Also find cheapest by raw price (fallback)
        val cheapestRaw = products.minByOrNull { it.first.price }
        
        // Decision logic:
        // - If per-unit is available and makes sense, prefer it
        // - If the per-unit best is reasonably priced (not 2x the cheapest raw), use it
        return if (bestByPerUnit != null && cheapestRaw != null) {
            val perUnitBest = bestByPerUnit.product
            val rawCheapest = cheapestRaw.first
            
            // If per-unit best is within 50% price of raw cheapest, it's the better value
            // This prevents picking huge expensive packages just for better per-unit
            if (perUnitBest.price <= rawCheapest.price * 1.5) {
                println("  ✨ Per-unit best: ${perUnitBest.name.take(40)} (${bestByPerUnit.perUnit!!.toDisplayString()})")
                perUnitBest
            } else {
                println("  💵 Raw cheapest: ${rawCheapest.name.take(40)} (per-unit best too expensive)")
                rawCheapest
            }
        } else {
            cheapestRaw?.first
        }
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
