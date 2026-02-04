package com.pricehunt.data.repository

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.model.SearchEvent
import com.pricehunt.data.remote.PriceHuntApi
import com.pricehunt.data.scrapers.FallbackScraperManager
import com.pricehunt.data.scrapers.SelfHealingScraper
import com.pricehunt.data.scrapers.api.ApiScrapeResult
import com.pricehunt.data.scrapers.api.DirectApiScraper
import com.pricehunt.data.scrapers.health.PlatformHealthMonitor
import com.pricehunt.data.scrapers.http.*
import com.pricehunt.data.search.PerUnitPrice
import com.pricehunt.data.search.SearchIntelligence
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that coordinates all platform scrapers and provides streaming search results.
 * 
 * UPDATED: Now uses SELF-HEALING SCRAPER for maximum reliability.
 * 
 * Self-Healing Features:
 * 1. Platform health monitoring with circuit breaker pattern
 * 2. Adaptive selector engine that learns and adapts
 * 3. Multi-strategy fallbacks for each platform
 * 4. Smart caching with staleness indicators
 * 5. Graceful degradation when platforms fail
 * 
 * Strategy:
 * 1. Return cached results INSTANTLY
 * 2. Scrape platforms in parallel with health checks
 * 3. Automatically skip failing platforms (with exponential backoff)
 * 4. Return raw results to backend for AI processing
 */
@Singleton
class ProductRepository @Inject constructor(
    private val api: PriceHuntApi,
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
    private val instamartScraper: InstamartScraper,
    private val fallbackManager: FallbackScraperManager,
    private val selfHealingScraper: SelfHealingScraper,
    private val healthMonitor: PlatformHealthMonitor,
    private val directApiScraper: DirectApiScraper
) {
    companion object {
        // Time to wait for each platform's API call - balanced for speed vs reliability
        private const val PLATFORM_TIMEOUT_MS = 10_000L  // 10 seconds max per platform
        // Maximum time to wait for entire search
        private const val MAX_SEARCH_TIME_MS = 15_000L  // 15 seconds max for all platforms
        // Direct API timeout (faster than WebView)
        private const val DIRECT_API_TIMEOUT_MS = 4_000L
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
     * Search all platforms and return RAW results (no filtering).
     * 
     * IMPORTANT: This method does NOT filter or rank results.
     * All intelligence is handled by the backend AI (Mistral/Gemini).
     * 
     * Results are emitted progressively as each platform responds.
     */
    fun searchStream(query: String, pincode: String): Flow<SearchEvent> = flow {
        emit(SearchEvent.Started(scrapers.map { it.platformName }))
        
        val resultsChannel = Channel<SearchEvent.PlatformResult>(Channel.UNLIMITED)
        val scraperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        val emittedPlatforms = ConcurrentHashMap<String, Boolean>()
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalPlatforms = scrapers.size
        
        // Separate HTTP-based and WebView-based scrapers
        val httpScrapers = listOf(amazonScraper, amazonFreshScraper)
        val webViewScrapers = listOf(
            blinkitScraper,
            instamartScraper,
            zeptoScraper,
            flipkartMinutesScraper,
            jioMartQuickScraper,
            bigBasketScraper,
            flipkartScraper,
            jioMartScraper
        )

        val directApiPlatforms = setOf(
            Platforms.ZEPTO,
            Platforms.BIGBASKET,
            Platforms.INSTAMART,
            Platforms.JIOMART,
            Platforms.JIOMART_QUICK
        )
        
        try {
            println("=== STARTING RAW SCRAPE: $query (pincode: $pincode) ===")
            println("=== NO LOCAL FILTERING - All intelligence handled by backend AI ===")
            
            // Helper function to run a scraper and send result to channel
            suspend fun runScraper(scraper: com.pricehunt.data.scrapers.BaseScraper) {
                try {
                    // Check cache first
                    val (cached, isStale) = cacheManager.get(query, scraper.platformName, pincode)
                    val staleCached = if (cached != null && isStale && cached.isNotEmpty()) {
                        cached
                    } else null
                    var usedStaleCache = false
                    
                    if (cached != null && !isStale && cached.isNotEmpty()) {
                        println("${scraper.platformName}: Using cached (${cached.size} products)")
                        emittedPlatforms[scraper.platformName] = true
                        resultsChannel.send(SearchEvent.PlatformResult(
                            platform = scraper.platformName,
                            products = cached,
                            cached = true
                        ))
                        return
                    }
                    
                    // NO QUERY OPTIMIZATION - send raw query to platform
                    // Let the platform return whatever it thinks is relevant
                    println("${scraper.platformName}: Searching for '$query'...")
                    
                    var products: List<Product> = emptyList()

                    // Fast path: Direct API (bypasses WebView) for unstable platforms
                    if (directApiPlatforms.contains(scraper.platformName)) {
                        val apiResult = withTimeoutOrNull(DIRECT_API_TIMEOUT_MS) {
                            directApiScraper.scrape(scraper.platformName, query, pincode)
                        }
                        when (apiResult) {
                            is ApiScrapeResult.Success -> {
                                products = apiResult.products
                                println("${scraper.platformName}: ✓ Direct API ${products.size} products")
                            }
                            is ApiScrapeResult.Failure -> {
                                println("${scraper.platformName}: Direct API failed (${apiResult.reason})")
                            }
                            ApiScrapeResult.NoProducts -> {
                                println("${scraper.platformName}: Direct API returned no products")
                            }
                            is ApiScrapeResult.NotSupported, null -> {
                                // Fall back to WebView
                            }
                        }
                    }

                    // Fallback: WebView/HTTP scraper
                    if (products.isEmpty()) {
                        products = withTimeoutOrNull(PLATFORM_TIMEOUT_MS) {
                            val result = scraper.search(query, pincode)
                            println("${scraper.platformName}: [RAW] Got ${result.size} products")
                            result
                        } ?: run {
                            println("${scraper.platformName}: [TIMEOUT]")
                            emptyList()
                        }
                    }
                    
                    if (products.isEmpty() && staleCached != null) {
                        println("${scraper.platformName}: Using stale cache (${staleCached.size} products)")
                        products = staleCached
                        usedStaleCache = true
                    }

                    if (products.isNotEmpty()) {
                        cacheManager.set(query, scraper.platformName, pincode, products)
                        println("${scraper.platformName}: ✓ ${products.size} products (raw, unfiltered)")
                    } else {
                        println("${scraper.platformName}: ✗ No products")
                    }
                    
                    emittedPlatforms[scraper.platformName] = true
                    resultsChannel.send(SearchEvent.PlatformResult(
                        platform = scraper.platformName,
                        products = products,
                        cached = usedStaleCache
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
                    println("${scraper.platformName}: Completed (${completedCount.get()}/$totalPlatforms)")
                }
            }
            
            // Use semaphore-based concurrency for maximum parallelism
            val webViewSemaphore = Semaphore(4)  // Balance speed vs WebView stability
            
            val scraperJob = scraperScope.launch {
                println("=== Launching all scrapers ===")
                
                // HTTP scrapers run immediately
                httpScrapers.forEach { scraper ->
                    launch { 
                        println("${scraper.platformName}: Starting (HTTP)")
                        runScraper(scraper) 
                    }
                }
                
                // WebView scrapers acquire semaphore before running
                webViewScrapers.forEach { scraper ->
                    launch {
                        webViewSemaphore.acquire()
                        try {
                            println("${scraper.platformName}: Starting (WebView)")
                            runScraper(scraper)
                        } finally {
                            webViewSemaphore.release()
                        }
                    }
                }
            }
            
            // Emit results as they arrive
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < MAX_SEARCH_TIME_MS) {
                val result = withTimeoutOrNull(50) { 
                    resultsChannel.receiveCatching().getOrNull() 
                }
                
                if (result != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    println("[STREAM] ${result.platform}: ${result.products.size} products at ${elapsed}ms")
                    emit(result)
                }
                
                if (completedCount.get() >= totalPlatforms) {
                    // Drain remaining
                    while (true) {
                        val remaining = resultsChannel.tryReceive().getOrNull() ?: break
                        emit(remaining)
                    }
                    break
                }
            }
            
            // Emit empty results for timed-out platforms
            scrapers.forEach { scraper ->
                if (emittedPlatforms.putIfAbsent(scraper.platformName, true) == null) {
                    println("${scraper.platformName}: Timeout")
                    emit(SearchEvent.PlatformResult(
                        platform = scraper.platformName,
                        products = emptyList(),
                        cached = false
                    ))
                }
            }
            
            println("=== RAW SCRAPE COMPLETED ===")
            emit(SearchEvent.Completed)
            
        } catch (e: Exception) {
            println("Search error: ${e.message}")
            emit(SearchEvent.Error(e.message ?: "Unknown error"))
        } finally {
            resultsChannel.close()
            scraperScope.cancel()
        }
    }
    
    /**
     * Search with robust fallback mechanism.
     * Phase 1: Use specialized scrapers (they work better)
     * Phase 2: Run fallbacks ONLY for platforms that failed
     */
    fun searchWithFallbacks(query: String, pincode: String): Flow<SearchEvent> = flow {
        emit(SearchEvent.Started(scrapers.map { it.platformName }))
        
        val allResults = mutableMapOf<String, List<Product>>()
        val failedPlatforms = mutableListOf<String>()
        
        // Phase 1: Run specialized scrapers in parallel
        println("=== PHASE 1: Running specialized scrapers ===")
        
        val primaryJobs = coroutineScope {
            scrapers.map { scraper ->
                async(Dispatchers.IO) {
                    try {
                        // Check cache first
                        val (cached, isStale) = cacheManager.get(query, scraper.platformName, pincode)
                        if (cached != null && !isStale && cached.isNotEmpty()) {
                            return@async Triple(scraper.platformName, cached, true)
                        }
                        
                        val products = withTimeoutOrNull(PLATFORM_TIMEOUT_MS) {
                            scraper.search(query, pincode)
                        } ?: emptyList()
                        
                        Triple(scraper.platformName, products, false)
                    } catch (e: Exception) {
                        println("${scraper.platformName}: Error - ${e.message}")
                        Triple(scraper.platformName, emptyList<Product>(), false)
                    }
                }
            }
        }
        
        // Collect primary results
        for (job in primaryJobs) {
            val (platform, products, cached) = job.await()
            
            if (products.isNotEmpty()) {
                allResults[platform] = products
                if (!cached) {
                    cacheManager.set(query, platform, pincode, products)
                }
                emit(SearchEvent.PlatformResult(platform, products, cached))
                println("$platform: ✓ ${products.size} products ${if (cached) "(cached)" else ""}")
            } else {
                failedPlatforms.add(platform)
                println("$platform: ✗ No products (will try fallback)")
            }
        }
        
        // Phase 2: Run fallbacks for failed platforms
        if (failedPlatforms.isNotEmpty()) {
            println("=== PHASE 2: Running fallbacks for ${failedPlatforms.size} platforms ===")
            emit(SearchEvent.Message("Running fallbacks for ${failedPlatforms.size} platforms..."))
            
            // Run fallbacks in batches of 3
            for (batch in failedPlatforms.chunked(3)) {
                val fallbackJobs = coroutineScope {
                    batch.map { platform ->
                        async(Dispatchers.IO) {
                            println("Fallback: Trying $platform")
                            val products = fallbackManager.tryFallbacksForPlatform(platform, query, pincode)
                            platform to products
                        }
                    }
                }
                
                for (job in fallbackJobs) {
                    val (platform, products) = job.await()
                    
                    if (products.isNotEmpty()) {
                        allResults[platform] = products
                        cacheManager.set(query, platform, pincode, products)
                        emit(SearchEvent.PlatformResult(platform, products, false))
                        println("$platform: ✓ ${products.size} products (fallback)")
                    } else {
                        emit(SearchEvent.PlatformResult(platform, emptyList(), false))
                        println("$platform: ✗ All fallbacks failed")
                    }
                }
            }
        }
        
        val successCount = allResults.count { it.value.isNotEmpty() }
        println("=== SEARCH COMPLETE: $successCount/${scrapers.size} platforms ===")
        emit(SearchEvent.Completed)
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
        val meaningfulQueryWords = queryWords.filter { it.length >= 3 }
        
        println("🔍 Best Deal Search: '$searchQuery' → Primary keyword: '$primaryKeyword'")
        
        // Derivative/flavored product indicators
        val derivativeIndicators = listOf(
            "juice", "jam", "jelly", "sauce", "syrup", "flavour", "flavor", 
            "essence", "extract", "candy", "chocolate", "ice cream", "shake",
            "smoothie", "squash", "drink", "beverage", "powder", "mix", "bar",
            "cake", "pastry", "muffin", "cookie", "biscuit", "wafer", "toffee",
            "chips", "chipp", "crisps", "snack", "snacks", "salted", "roasted",
            "fry", "fried"
        )
        
        // Score each product for relevance
        val scoredProducts = allProducts.map { product ->
            val nameLower = product.name.lowercase()
            val nameWords = nameLower.split(" ", ",", "-", "(", ")").filter { it.isNotBlank() }
            
            var relevanceScore = 0
            var reason = ""

            val matchesQuery = if (meaningfulQueryWords.isNotEmpty()) {
                meaningfulQueryWords.any { nameLower.contains(it) }
            } else {
                nameLower.contains(primaryKeyword)
            }

            if (!matchesQuery) {
                relevanceScore = -100
                reason = "no query match"
                return@map Pair(product, relevanceScore)
            }
            
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
    
    /**
     * HYBRID STRATEGY: Get all cached results instantly.
     * Returns cached data for all platforms (even if slightly stale).
     * This enables instant display while fresh data loads in background.
     */
    suspend fun getCachedResults(query: String, pincode: String): Map<String, List<Product>> {
        val results = mutableMapOf<String, List<Product>>()
        
        for (scraper in scrapers) {
            val (cached, _) = cacheManager.get(query, scraper.platformName, pincode)
            if (cached != null && cached.isNotEmpty()) {
                results[scraper.platformName] = cached
            }
        }
        
        println("💾 CACHE: Found cached results for ${results.size} platforms (${results.values.flatten().size} products)")
        return results
    }
    
    // ==================== SELF-HEALING SCRAPER API ====================
    
    /**
     * Search using the self-healing scraper with all resilience features.
     * 
     * This is the RECOMMENDED method for production use.
     * It handles:
     * - Platform health monitoring
     * - Automatic circuit breaker (skips failing platforms)
     * - Adaptive extraction (learns and adapts to changes)
     * - Multi-strategy fallbacks
     * - Smart caching with staleness indicators
     */
    fun searchWithSelfHealing(query: String, pincode: String) = 
        selfHealingScraper.search(query, pincode)
    
    /**
     * Get platform health status for UI display
     */
    fun getPlatformHealth() = healthMonitor.platformStates
    
    /**
     * Get list of currently disabled platforms (circuit breaker open)
     */
    fun getDisabledPlatforms() = healthMonitor.getDisabledPlatforms()
    
    /**
     * Get list of healthy platforms
     */
    fun getHealthyPlatforms() = healthMonitor.getHealthyPlatforms()
    
    /**
     * Manually reset a platform's health (force retry)
     */
    fun resetPlatformHealth(platform: String) {
        healthMonitor.resetPlatform(platform)
    }
    
    /**
     * Reset all platform health data
     */
    fun resetAllPlatformHealth() {
        healthMonitor.resetAll()
    }
    
    /**
     * Get detailed health info for a platform
     */
    fun getPlatformHealthInfo(platform: String) = healthMonitor.getHealth(platform)
}
