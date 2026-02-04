package com.pricehunt.data.scrapers

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.remote.PriceHuntApi
import com.pricehunt.data.remote.AIExtractRequest
import com.pricehunt.data.remote.AIExtractMultiRequest
import com.pricehunt.data.remote.PlatformHtml
import com.pricehunt.data.repository.CacheManager
import com.pricehunt.data.scrapers.adaptive.AdaptiveSelectorEngine
import com.pricehunt.data.scrapers.adaptive.ExtractionResult
import com.pricehunt.data.scrapers.api.DirectApiScraper
import com.pricehunt.data.scrapers.api.ApiScrapeResult
import com.pricehunt.data.scrapers.health.PlatformHealthMonitor
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.search.SearchIntelligence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-Healing Scraper - The main orchestrator for resilient web scraping.
 * 
 * This scraper is designed to be "unbreakable" through multiple defense layers:
 * 
 * LAYER 1: PLATFORM HEALTH MONITORING
 * - Tracks success/failure rates per platform
 * - Automatically disables failing platforms (circuit breaker)
 * - Exponential backoff for retries
 * - Persistent health metrics across app restarts
 * 
 * LAYER 2: ADAPTIVE EXTRACTION
 * - Pattern-based extraction (not hardcoded selectors)
 * - Self-learning selectors that adapt to changes
 * - Confidence scoring for extracted products
 * - Structure change detection
 * 
 * LAYER 3: MULTI-STRATEGY FALLBACKS
 * - Primary scraping strategy
 * - Alternative URL patterns
 * - Different extraction methods
 * - JSON extraction (JSON-LD, __NEXT_DATA__)
 * 
 * LAYER 4: SMART CACHING
 * - Cache-first with background refresh
 * - Stale-while-revalidate pattern
 * - Staleness indicators for UI
 * 
 * LAYER 5: GRACEFUL DEGRADATION
 * - Always return something (cached or partial)
 * - Clear indicators when data might be outdated
 * - Automatic recovery when platforms come back
 */
@Singleton
class SelfHealingScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper,
    private val healthMonitor: PlatformHealthMonitor,
    private val adaptiveEngine: AdaptiveSelectorEngine,
    private val cacheManager: CacheManager,
    private val api: PriceHuntApi,
    private val directApiScraper: DirectApiScraper
) {
    companion object {
        private const val TAG = "SelfHealingScraper"
        
        // Timeouts (optimized for balance between speed and reliability)
        private const val PRIMARY_TIMEOUT_MS = 12_000L
        private const val FALLBACK_TIMEOUT_MS = 8_000L
        
        // Minimum products to consider success
        private const val MIN_PRODUCTS_FOR_SUCCESS = 1
        
        // All supported platforms
        val ALL_PLATFORMS = listOf(
            Platforms.ZEPTO,
            Platforms.BLINKIT,
            Platforms.BIGBASKET,
            Platforms.INSTAMART,
            Platforms.AMAZON_FRESH,
            Platforms.AMAZON,
            Platforms.FLIPKART,
            Platforms.FLIPKART_MINUTES,
            Platforms.JIOMART,
            Platforms.JIOMART_QUICK
        )
        
        private val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    
    /**
     * Search all platforms with self-healing capabilities.
     * Emits results progressively as they become available.
     */
    fun search(query: String, pincode: String): Flow<SearchEvent> = flow {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableMapOf<String, List<Product>>()
        val cachedResults = mutableMapOf<String, List<Product>>()
        val stalePlatforms = mutableSetOf<String>()
        val failedPlatformHtmls = mutableListOf<Triple<String, String, PlatformConfig>>() // For batch AI fallback
        
        // PHASE 1: Emit cached results immediately
        emit(SearchEvent.Started(query, ALL_PLATFORMS.size))
        
        for (platform in ALL_PLATFORMS) {
            val (cached, isStale) = cacheManager.get(query, platform, pincode)
            if (cached != null && cached.isNotEmpty()) {
                cachedResults[platform] = cached
                if (isStale) stalePlatforms.add(platform)
                
                emit(SearchEvent.CachedResult(
                    platform = platform,
                    products = cached,
                    isStale = isStale
                ))
            }
        }
        
        if (cachedResults.isNotEmpty()) {
            emit(SearchEvent.CachePhaseComplete(
                cachedResults = cachedResults,
                stalePlatforms = stalePlatforms.toList()
            ))
        }
        
        // PHASE 2: Scrape platforms in parallel (with health checks)
        emit(SearchEvent.ScrapingStarted)
        
        val platformsToScrape = ALL_PLATFORMS.filter { platform ->
            val shouldScrape = healthMonitor.shouldScrape(platform)
            if (!shouldScrape) {
                emit(SearchEvent.PlatformSkipped(platform, "Circuit breaker open"))
            }
            shouldScrape
        }
        
        log("Scraping ${platformsToScrape.size}/${ALL_PLATFORMS.size} platforms (${ALL_PLATFORMS.size - platformsToScrape.size} disabled)")
        
        // Process in batches to avoid resource contention
        val batchSize = 4
        for (batch in platformsToScrape.chunked(batchSize)) {
            val batchResults = coroutineScope {
                batch.map { platform ->
                    async(Dispatchers.IO) {
                        scrapePlatform(platform, query, encodedQuery, pincode)
                    }
                }.awaitAll()
            }
            
            // Process batch results
            for ((platform, scrapeResult) in batch.zip(batchResults)) {
                when (scrapeResult) {
                    is ScrapeResult.Success -> {
                        results[platform] = scrapeResult.products
                        
                        // Record success with health monitor
                        healthMonitor.recordResult(
                            platform = platform,
                            success = true,
                            productCount = scrapeResult.products.size,
                            htmlHash = scrapeResult.structureHash
                        )
                        
                        // Update cache
                        cacheManager.set(query, platform, pincode, scrapeResult.products)
                        
                        emit(SearchEvent.PlatformResult(
                            platform = platform,
                            products = scrapeResult.products,
                            confidence = scrapeResult.avgConfidence,
                            fromCache = false
                        ))
                    }
                    is ScrapeResult.Failure -> {
                        // Record failure with health monitor
                        healthMonitor.recordResult(platform, success = false)
                        
                        // Use cached data if available
                        val cached = cachedResults[platform]
                        if (cached != null) {
                            results[platform] = cached
                            emit(SearchEvent.PlatformResult(
                                platform = platform,
                                products = cached,
                                confidence = 0.5,  // Lower confidence for cached
                                fromCache = true
                            ))
                        } else {
                            // Store the HTML for batch AI fallback
                            scrapeResult.html?.let { html ->
                                failedPlatformHtmls.add(Triple(platform, html, getPlatformConfig(platform)))
                            }
                            
                            emit(SearchEvent.PlatformFailed(
                                platform = platform,
                                reason = scrapeResult.reason,
                                willRetry = healthMonitor.shouldScrape(platform)
                            ))
                        }
                    }
                }
            }
        }
        
        // PHASE 3: Batch AI Fallback for all failed platforms
        if (failedPlatformHtmls.isNotEmpty()) {
            emit(SearchEvent.AIFallbackStarted(failedPlatformHtmls.size))
            log("Trying batch AI fallback for ${failedPlatformHtmls.size} failed platforms")
            
            try {
                val aiResults = tryBatchAIFallback(failedPlatformHtmls, query, pincode)
                
                for ((platform, products) in aiResults) {
                    if (products.isNotEmpty()) {
                        results[platform] = products
                        
                        // Record AI success
                        healthMonitor.recordResult(platform, success = true, productCount = products.size)
                        cacheManager.set(query, platform, pincode, products)
                        
                        emit(SearchEvent.PlatformResult(
                            platform = platform,
                            products = products,
                            confidence = 0.75, // AI extraction confidence
                            fromCache = false,
                            aiExtracted = true
                        ))
                    }
                }
            } catch (e: Exception) {
                log("Batch AI fallback failed: ${e.message}")
            }
        }
        
        // PHASE 4: Emit final summary
        val successCount = results.count { it.value.isNotEmpty() }
        val totalProducts = results.values.sumOf { it.size }
        
        emit(SearchEvent.Completed(
            allResults = results,
            successfulPlatforms = successCount,
            totalPlatforms = ALL_PLATFORMS.size,
            totalProducts = totalProducts,
            disabledPlatforms = healthMonitor.getDisabledPlatforms()
        ))
        
    }.flowOn(Dispatchers.IO)
    
    /**
     * Scrape a single platform with all fallback strategies
     * 
     * Strategy order (optimized for reliability):
     * 1. DIRECT API - Fastest and most reliable (calls platform's internal APIs)
     * 2. HTTP scraping - Fast, works for server-rendered pages
     * 3. WebView with adaptive extraction - For JavaScript-heavy SPAs
     * 4. Alternative URLs - Try different URL patterns
     * 5. AI Fallback - Send raw HTML to backend for AI extraction
     */
    private suspend fun scrapePlatform(
        platform: String,
        query: String,
        encodedQuery: String,
        pincode: String
    ): ScrapeResult {
        log("$platform: Starting scrape (5-layer strategy)")
        
        // Get platform config
        val config = getPlatformConfig(platform)
        
        // ============================================================
        // STRATEGY 1: DIRECT API (Most reliable - calls internal APIs)
        // ============================================================
        try {
            log("$platform: Trying Direct API...")
            val apiResult = directApiScraper.scrape(platform, query, pincode)
            
            when (apiResult) {
                is ApiScrapeResult.Success -> {
                    log("$platform: ✓ Direct API succeeded (${apiResult.products.size} products)")
                    return ScrapeResult.Success(
                        products = apiResult.products,
                        avgConfidence = 0.95,  // High confidence for API data
                        structureHash = null
                    )
                }
                is ApiScrapeResult.NoProducts -> {
                    log("$platform: Direct API returned no products")
                }
                is ApiScrapeResult.NotSupported -> {
                    log("$platform: Direct API not supported, trying other strategies")
                }
                is ApiScrapeResult.Failure -> {
                    log("$platform: Direct API failed: ${apiResult.reason}")
                }
            }
        } catch (e: Exception) {
            log("$platform: Direct API exception: ${e.message}")
        }
        
        // ============================================================
        // STRATEGY 2: HTTP-only scraping (Fast, for server-rendered pages)
        // ============================================================
        try {
            log("$platform: Trying HTTP scraping...")
            val httpResult = scrapeViaHttp(
                url = config.searchUrl(encodedQuery),
                platform = platform,
                config = config,
                pincode = pincode
            )
            
            if (httpResult is ScrapeResult.Success) {
                log("$platform: ✓ HTTP strategy succeeded (${httpResult.products.size} products)")
                return httpResult
            }
        } catch (e: Exception) {
            log("$platform: HTTP strategy failed: ${e.message}")
        }
        
        // ============================================================
        // STRATEGY 3: WebView with adaptive extraction (For SPAs)
        // ============================================================
        try {
            log("$platform: Trying WebView scraping...")
            val primaryResult = scrapeWithAdaptiveExtraction(
                url = config.searchUrl(encodedQuery),
                platform = platform,
                config = config,
                pincode = pincode,
                timeout = PRIMARY_TIMEOUT_MS
            )
            
            if (primaryResult is ScrapeResult.Success) {
                log("$platform: ✓ WebView strategy succeeded (${primaryResult.products.size} products)")
                return primaryResult
            }
        } catch (e: Exception) {
            log("$platform: WebView strategy failed: ${e.message}")
        }
        
        // ============================================================
        // STRATEGY 4: Alternative URL patterns
        // ============================================================
        for (altUrl in config.alternativeUrls(encodedQuery)) {
            try {
                log("$platform: Trying alternative URL: $altUrl")
                val altResult = scrapeWithAdaptiveExtraction(
                    url = altUrl,
                    platform = platform,
                    config = config,
                    pincode = pincode,
                    timeout = FALLBACK_TIMEOUT_MS
                )
                
                if (altResult is ScrapeResult.Success) {
                    log("$platform: ✓ Alternative URL succeeded (${altResult.products.size} products)")
                    return altResult
                }
            } catch (e: Exception) {
                log("$platform: Alternative URL failed: ${e.message}")
            }
        }
        
        // Strategy 4: AI FALLBACK - Send raw HTML to backend for AI extraction
        log("$platform: Trying AI fallback extraction...")
        try {
            val aiResult = scrapeWithAIFallback(
                url = config.searchUrl(encodedQuery),
                platform = platform,
                config = config,
                pincode = pincode,
                query = query
            )
            
            if (aiResult is ScrapeResult.Success) {
                log("$platform: ✓ AI fallback succeeded (${aiResult.products.size} products)")
                return aiResult
            }
        } catch (e: Exception) {
            log("$platform: AI fallback failed: ${e.message}")
        }
        
        log("$platform: ✗ All strategies failed (including AI fallback)")
        return ScrapeResult.Failure("All extraction strategies failed")
    }
    
    /**
     * AI Fallback: Send raw HTML to backend for AI-powered extraction
     * This is the ULTIMATE FALLBACK when all client-side extraction fails
     */
    private suspend fun scrapeWithAIFallback(
        url: String,
        platform: String,
        config: PlatformConfig,
        pincode: String,
        query: String
    ): ScrapeResult {
        webViewHelper.setLocation(pincode)
        
        // Get raw HTML from WebView
        val html = webViewHelper.loadAndGetHtml(
            url = url,
            timeoutMs = PRIMARY_TIMEOUT_MS,
            pincode = pincode
        )
        
        if (html.isNullOrBlank() || html.length < 1000) {
            return ScrapeResult.Failure("No HTML to send to AI")
        }
        
        log("$platform: Sending ${html.length} chars to AI extraction API")
        
        try {
            // Call backend AI extraction API
            val response = api.aiExtract(
                AIExtractRequest(
                    html = html,
                    platform = platform,
                    searchQuery = query,
                    baseUrl = config.baseUrl
                )
            )
            
            if (response.aiPowered && response.productsFound > 0) {
                val products = response.products.map { aiProduct ->
                    Product(
                        name = aiProduct.name,
                        price = aiProduct.price,
                        originalPrice = aiProduct.originalPrice,
                        imageUrl = aiProduct.imageUrl ?: "",
                        platform = platform,
                        platformColor = config.color,
                        deliveryTime = config.deliveryTime,
                        url = aiProduct.productUrl ?: config.baseUrl,
                        rating = null,
                        discount = aiProduct.originalPrice?.let { orig ->
                            if (orig > aiProduct.price) "${((orig - aiProduct.price) / orig * 100).toInt()}% off" else null
                        },
                        available = aiProduct.inStock
                    )
                }
                
                return ScrapeResult.Success(
                    products = products,
                    avgConfidence = response.confidence,
                    structureHash = null
                )
            } else {
                return ScrapeResult.Failure(response.error ?: "AI extraction returned no products")
            }
        } catch (e: Exception) {
            log("$platform: AI API call failed: ${e.message}")
            return ScrapeResult.Failure("AI API error: ${e.message}")
        }
    }
    
    /**
     * Scrape using WebView with adaptive extraction
     */
    private suspend fun scrapeWithAdaptiveExtraction(
        url: String,
        platform: String,
        config: PlatformConfig,
        pincode: String,
        timeout: Long
    ): ScrapeResult {
        webViewHelper.setLocation(pincode)
        
        val html = webViewHelper.loadAndGetHtml(
            url = url,
            timeoutMs = timeout,
            pincode = pincode
        )
        
        if (html.isNullOrBlank()) {
            return ScrapeResult.Failure("Empty HTML response")
        }
        
        log("$platform: Got ${html.length} chars HTML")
        
        // Check for structure changes
        val doc = org.jsoup.Jsoup.parse(html)
        val structureHash = adaptiveEngine.calculateStructureHash(doc)
        if (healthMonitor.hasStructureChanged(platform, structureHash)) {
            log("$platform: ⚠️ Structure change detected - adapting extraction")
        }
        
        // Use adaptive extraction
        val extractionResults = adaptiveEngine.extractProducts(
            html = html,
            platformName = platform,
            platformColor = config.color,
            deliveryTime = config.deliveryTime,
            baseUrl = config.baseUrl
        )
        
        if (extractionResults.isEmpty()) {
            return ScrapeResult.Failure("No products extracted", html = html) // Include HTML for AI fallback
        }
        
        val products = extractionResults.map { it.product }
        val enrichedProducts = enrichProductsWithQuantity(doc, products)
        val avgConfidence = extractionResults.map { it.confidence }.average()
        
        // Fix URLs if needed
        val fixedProducts = enrichedProducts.map { product ->
            if (product.url.isBlank() || product.url == config.baseUrl) {
                product.copy(
                    url = "${config.baseUrl}/search?q=${URLEncoder.encode(product.name, "UTF-8")}&pincode=$pincode"
                )
            } else if (!product.url.contains("pincode")) {
                val separator = if (product.url.contains("?")) "&" else "?"
                product.copy(url = "${product.url}${separator}pincode=$pincode")
            } else {
                product
            }
        }
        
        return ScrapeResult.Success(
            products = fixedProducts,
            avgConfidence = avgConfidence,
            structureHash = structureHash
        )
    }
    
    /**
     * Scrape via HTTP (no WebView) - faster but less reliable for SPAs
     */
    private suspend fun scrapeViaHttp(
        url: String,
        platform: String,
        config: PlatformConfig,
        pincode: String
    ): ScrapeResult {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
            .header("Accept", "text/html")
            .header("Cookie", "pincode=$pincode; lat=12.9716; lng=77.5946")
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return ScrapeResult.Failure("HTTP ${response.code}")
        }
        
        val html = response.body?.string()
        if (html.isNullOrBlank() || html.length < 5000) {
            return ScrapeResult.Failure("Empty or too short response")
        }
        
        val extractionResults = adaptiveEngine.extractProducts(
            html = html,
            platformName = platform,
            platformColor = config.color,
            deliveryTime = config.deliveryTime,
            baseUrl = config.baseUrl
        )
        
        if (extractionResults.isEmpty()) {
            return ScrapeResult.Failure("No products in HTTP response", html = html) // Include HTML for AI fallback
        }
        
        val doc = org.jsoup.Jsoup.parse(html)
        val products = extractionResults.map { it.product }
        val enrichedProducts = enrichProductsWithQuantity(doc, products)
        val avgConfidence = extractionResults.map { it.confidence }.average()
        
        return ScrapeResult.Success(
            products = enrichedProducts,
            avgConfidence = avgConfidence,
            structureHash = null
        )
    }
    
    /**
     * Batch AI fallback: Send all failed platform HTMLs to backend at once
     * More efficient than individual calls
     */
    private suspend fun tryBatchAIFallback(
        failedPlatforms: List<Triple<String, String, PlatformConfig>>,
        query: String,
        pincode: String
    ): Map<String, List<Product>> {
        if (failedPlatforms.isEmpty()) return emptyMap()
        
        log("Batch AI fallback: Processing ${failedPlatforms.size} platforms")
        
        try {
            // Build request with all platform HTMLs
            val platformHtmls = failedPlatforms.map { (platform, html, config) ->
                PlatformHtml(
                    platform = platform,
                    html = html.take(100000), // Limit HTML size
                    baseUrl = config.baseUrl
                )
            }
            
            val response = api.aiExtractMulti(
                AIExtractMultiRequest(
                    platforms = platformHtmls,
                    searchQuery = query
                )
            )
            
            val results = mutableMapOf<String, List<Product>>()
            
            for ((platform, extractResponse) in response.results) {
                if (extractResponse.aiPowered && extractResponse.productsFound > 0) {
                    val config = failedPlatforms.find { it.first == platform }?.third ?: continue
                    
                    val products = extractResponse.products.map { aiProduct ->
                        Product(
                            name = aiProduct.name,
                            price = aiProduct.price,
                            originalPrice = aiProduct.originalPrice,
                            imageUrl = aiProduct.imageUrl ?: "",
                            platform = platform,
                            platformColor = config.color,
                            deliveryTime = config.deliveryTime,
                            url = aiProduct.productUrl ?: config.baseUrl,
                            rating = null,
                            discount = aiProduct.originalPrice?.let { orig ->
                                if (orig > aiProduct.price) "${((orig - aiProduct.price) / orig * 100).toInt()}% off" else null
                            },
                            available = aiProduct.inStock
                        )
                    }
                    
                    results[platform] = products
                    log("$platform: AI batch extracted ${products.size} products")
                }
            }
            
            log("Batch AI fallback: Extracted products from ${results.size}/${failedPlatforms.size} platforms")
            return results
            
        } catch (e: Exception) {
            log("Batch AI fallback failed: ${e.message}")
            return emptyMap()
        }
    }
    
    /**
     * Get health status for all platforms
     */
    fun getPlatformHealth() = healthMonitor.platformStates
    
    /**
     * Reset health for a specific platform
     */
    fun resetPlatformHealth(platform: String) {
        healthMonitor.resetPlatform(platform)
    }
    
    /**
     * Reset all platform health
     */
    fun resetAllHealth() {
        healthMonitor.resetAll()
    }
    
    // ==================== Platform Configurations ====================
    
    private fun getPlatformConfig(platform: String): PlatformConfig {
        return when (platform) {
            Platforms.ZEPTO -> PlatformConfig(
                baseUrl = "https://www.zeptonow.com",
                color = Platforms.ZEPTO_COLOR,
                deliveryTime = "10 mins",
                searchUrlPattern = "/search?query=%s",
                alternativePatterns = listOf("/s/%s")
            )
            Platforms.BLINKIT -> PlatformConfig(
                baseUrl = "https://blinkit.com",
                color = Platforms.BLINKIT_COLOR,
                deliveryTime = "10 mins",
                searchUrlPattern = "/s/?q=%s",
                alternativePatterns = listOf("/search/%s")
            )
            Platforms.BIGBASKET -> PlatformConfig(
                baseUrl = "https://www.bigbasket.com",
                color = Platforms.BIGBASKET_COLOR,
                deliveryTime = "1-2 hours",
                searchUrlPattern = "/ps/?q=%s",
                alternativePatterns = listOf("/search?q=%s")
            )
            Platforms.INSTAMART -> PlatformConfig(
                baseUrl = "https://www.swiggy.com/instamart",
                color = Platforms.INSTAMART_COLOR,
                deliveryTime = "15-30 mins",
                searchUrlPattern = "/search?custom_back=true&query=%s",
                alternativePatterns = emptyList()
            )
            Platforms.AMAZON_FRESH -> PlatformConfig(
                baseUrl = "https://www.amazon.in",
                color = Platforms.AMAZON_FRESH_COLOR,
                deliveryTime = "2-4 hours",
                searchUrlPattern = "/s?k=%s&i=nowstore",
                alternativePatterns = listOf("/s?k=%s&rh=n:2454178031")
            )
            Platforms.AMAZON -> PlatformConfig(
                baseUrl = "https://www.amazon.in",
                color = Platforms.AMAZON_COLOR,
                deliveryTime = "1-3 days",
                searchUrlPattern = "/s?k=%s",
                alternativePatterns = emptyList()
            )
            Platforms.FLIPKART -> PlatformConfig(
                baseUrl = "https://www.flipkart.com",
                color = Platforms.FLIPKART_COLOR,
                deliveryTime = "1-2 days",
                searchUrlPattern = "/search?q=%s",
                alternativePatterns = listOf("/grocery-supermart-store?q=%s")
            )
            Platforms.FLIPKART_MINUTES -> PlatformConfig(
                baseUrl = "https://www.flipkart.com",
                color = Platforms.FLIPKART_MINUTES_COLOR,
                deliveryTime = "10-15 mins",
                searchUrlPattern = "/search?q=%s&marketplace=GROCERY",
                alternativePatterns = emptyList()
            )
            Platforms.JIOMART -> PlatformConfig(
                baseUrl = "https://www.jiomart.com",
                color = Platforms.JIOMART_COLOR,
                deliveryTime = "1-2 days",
                searchUrlPattern = "/search/%s",
                alternativePatterns = listOf("/catalogsearch/result/?q=%s")
            )
            Platforms.JIOMART_QUICK -> PlatformConfig(
                baseUrl = "https://www.jiomart.com",
                color = Platforms.JIOMART_COLOR,
                deliveryTime = "15-30 mins",
                searchUrlPattern = "/search/%s?deliveryType=express",
                alternativePatterns = emptyList()
            )
            else -> throw IllegalArgumentException("Unknown platform: $platform")
        }
    }
    
    private fun log(message: String) {
        println("$TAG: $message")
        try {
            android.util.Log.d(TAG, message)
        } catch (e: Exception) {
            // Unit test environment
        }
    }

    private fun enrichProductsWithQuantity(doc: org.jsoup.nodes.Document, products: List<Product>): List<Product> {
        return products.map { product ->
            if (SearchIntelligence.parseQuantity(product.name) != null) {
                product
            } else {
                val contextText = findQuantityContext(doc, product.name)
                val enrichedName = appendQuantityToNameIfMissing(product.name, contextText)
                if (enrichedName != product.name) {
                    product.copy(name = enrichedName)
                } else {
                    product
                }
            }
        }
    }

    private fun appendQuantityToNameIfMissing(name: String, contextText: String?): String {
        val trimmed = name.trim()
        if (SearchIntelligence.parseQuantity(trimmed) != null) return trimmed
        val context = contextText?.takeIf { it.isNotBlank() } ?: return trimmed
        val parsed = SearchIntelligence.parseQuantity(context) ?: return trimmed
        val display = parsed.toDisplayString()
        if (display.isBlank()) return trimmed
        if (trimmed.contains(display, ignoreCase = true)) return trimmed
        return "$trimmed, $display"
    }

    private fun findQuantityContext(doc: org.jsoup.nodes.Document, productName: String): String? {
        val tokens = productName
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
        if (tokens.isEmpty()) return null

        val keys = listOf(
            tokens.take(4).joinToString(" "),
            tokens.take(2).joinToString(" "),
            tokens.first()
        ).distinct().filter { it.isNotBlank() }

        for (key in keys) {
            val regex = Pattern.compile(Pattern.quote(key), Pattern.CASE_INSENSITIVE)
            val elements = doc.getElementsMatchingText(regex)
            for (element in elements.take(10)) {
                val container = element.parents()
                    .firstOrNull { it.text().length in 20..600 }
                    ?: element
                val context = container.text()
                if (SearchIntelligence.parseQuantity(context) != null) {
                    return context
                }
            }
        }

        return null
    }
}

/**
 * Platform configuration
 */
data class PlatformConfig(
    val baseUrl: String,
    val color: Long,
    val deliveryTime: String,
    val searchUrlPattern: String,
    val alternativePatterns: List<String>
) {
    fun searchUrl(encodedQuery: String): String {
        return baseUrl + searchUrlPattern.replace("%s", encodedQuery)
    }
    
    fun alternativeUrls(encodedQuery: String): List<String> {
        return alternativePatterns.map { pattern ->
            baseUrl + pattern.replace("%s", encodedQuery)
        }
    }
}

/**
 * Result of scraping a platform
 */
sealed class ScrapeResult {
    data class Success(
        val products: List<Product>,
        val avgConfidence: Double,
        val structureHash: String?
    ) : ScrapeResult()
    
    data class Failure(
        val reason: String,
        val html: String? = null  // Store HTML for AI fallback
    ) : ScrapeResult()
}

/**
 * Events emitted during search
 */
sealed class SearchEvent {
    data class Started(val query: String, val totalPlatforms: Int) : SearchEvent()
    
    data class CachedResult(
        val platform: String,
        val products: List<Product>,
        val isStale: Boolean
    ) : SearchEvent()
    
    data class CachePhaseComplete(
        val cachedResults: Map<String, List<Product>>,
        val stalePlatforms: List<String>
    ) : SearchEvent()
    
    object ScrapingStarted : SearchEvent()
    
    data class PlatformSkipped(
        val platform: String,
        val reason: String
    ) : SearchEvent()
    
    data class PlatformResult(
        val platform: String,
        val products: List<Product>,
        val confidence: Double,
        val fromCache: Boolean,
        val aiExtracted: Boolean = false
    ) : SearchEvent()
    
    data class AIFallbackStarted(val platformCount: Int) : SearchEvent()
    
    data class PlatformFailed(
        val platform: String,
        val reason: String,
        val willRetry: Boolean
    ) : SearchEvent()
    
    data class Completed(
        val allResults: Map<String, List<Product>>,
        val successfulPlatforms: Int,
        val totalPlatforms: Int,
        val totalProducts: Int,
        val disabledPlatforms: List<String>
    ) : SearchEvent()
}
