package com.pricehunt.data.scrapers

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.remote.AIExtractRequest
import com.pricehunt.data.remote.PriceHuntApi
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.search.SearchIntelligence
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages scraping with multiple fallback strategies.
 * 
 * For each platform, tries strategies in order:
 * 1. Primary scraper (HTTP or WebView)
 * 2. Alternative URL patterns
 * 3. Different extraction methods
 * 4. Emergency fallback (broader selectors)
 * 
 * Updates UI incrementally as results come in.
 */
@Singleton
class FallbackScraperManager @Inject constructor(
    private val webViewHelper: WebViewScraperHelper,
    private val api: PriceHuntApi
) {
    
    companion object {
        // List of all platforms we expect results from
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
     * Search with fallback strategies.
     * Emits results incrementally as they come in.
     */
    fun searchWithFallbacks(
        query: String,
        pincode: String
    ): Flow<FallbackSearchResult> = flow {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableMapOf<String, List<Product>>()
        val failedPlatforms = mutableSetOf<String>()
        
        // Phase 1: Run all primary scrapers in parallel
        emit(FallbackSearchResult(phase = "primary", message = "Searching all platforms..."))
        
        val primaryResults = coroutineScope {
            ALL_PLATFORMS.map { platform ->
                async(Dispatchers.IO) {
                    platform to tryPrimaryStrategy(platform, query, encodedQuery, pincode)
                }
            }.awaitAll()
        }
        
        // Process primary results
        for ((platform, products) in primaryResults) {
            if (products.isNotEmpty()) {
                results[platform] = products
                emit(FallbackSearchResult(
                    phase = "primary",
                    platform = platform,
                    products = products,
                    message = "✓ $platform: ${products.size} products"
                ))
            } else {
                failedPlatforms.add(platform)
            }
        }
        
        println("FallbackManager: Primary complete - ${results.size} platforms succeeded, ${failedPlatforms.size} failed")
        
        // Phase 2: Run fallbacks in batches of 4 to avoid resource contention
        if (failedPlatforms.isNotEmpty()) {
            emit(FallbackSearchResult(
                phase = "fallback",
                message = "Running fallbacks for ${failedPlatforms.size} platforms..."
            ))
            
            // Process in batches of 4 platforms at a time
            val platformList = failedPlatforms.toList()
            val batchSize = 4
            
            for (batch in platformList.chunked(batchSize)) {
                println("FallbackManager: Processing batch of ${batch.size} platforms: ${batch.joinToString()}")
                
                val batchJobs = coroutineScope {
                    batch.map { platform ->
                        async(Dispatchers.IO) {
                            println("FallbackManager: Trying fallbacks for $platform")
                            val fallbackProducts = tryFallbackStrategies(platform, query, encodedQuery, pincode)
                            platform to fallbackProducts
                        }
                    }
                }
                
                // Collect results from this batch
                for (job in batchJobs) {
                    val (platform, fallbackProducts) = job.await()
                    
                    if (fallbackProducts.isNotEmpty()) {
                        results[platform] = fallbackProducts
                        failedPlatforms.remove(platform)
                        emit(FallbackSearchResult(
                            phase = "fallback",
                            platform = platform,
                            products = fallbackProducts,
                            message = "✓ $platform (fallback): ${fallbackProducts.size} products"
                        ))
                    } else {
                        emit(FallbackSearchResult(
                            phase = "fallback",
                            platform = platform,
                            products = emptyList(),
                            message = "✗ $platform: All strategies failed"
                        ))
                    }
                }
                
                // Small delay between batches to let resources recover
                if (batch != platformList.chunked(batchSize).last()) {
                    delay(500)
                }
            }
        }
        
        // Phase 3: Final summary
        emit(FallbackSearchResult(
            phase = "complete",
            allResults = results,
            message = "Complete: ${results.size}/${ALL_PLATFORMS.size} platforms"
        ))
        
    }.flowOn(Dispatchers.IO)
    
    /**
     * Try primary scraping strategy for a platform
     */
    private suspend fun tryPrimaryStrategy(
        platform: String,
        query: String,
        encodedQuery: String,
        pincode: String
    ): List<Product> {
        return when (platform) {
            Platforms.ZEPTO -> scrapeZepto(encodedQuery, pincode)
            Platforms.BLINKIT -> scrapeBlinkit(encodedQuery, pincode)
            Platforms.BIGBASKET -> scrapeBigBasket(encodedQuery, pincode)
            Platforms.INSTAMART -> scrapeInstamart(encodedQuery, pincode)
            Platforms.AMAZON_FRESH -> scrapeAmazonFresh(encodedQuery, pincode)
            Platforms.AMAZON -> scrapeAmazon(encodedQuery, pincode)
            Platforms.FLIPKART -> scrapeFlipkart(encodedQuery, pincode)
            Platforms.FLIPKART_MINUTES -> scrapeFlipkartMinutes(encodedQuery, pincode)
            Platforms.JIOMART -> scrapeJioMart(encodedQuery, pincode)
            Platforms.JIOMART_QUICK -> scrapeJioMartQuick(encodedQuery, pincode)
            else -> emptyList()
        }
    }
    
    /**
     * Public method to try fallbacks for a specific platform.
     * Called by ProductRepository after primary scrapers fail.
     */
    suspend fun tryFallbacksForPlatform(
        platform: String,
        query: String,
        pincode: String
    ): List<Product> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryFallbackStrategies(platform, query, encodedQuery, pincode)
    }
    
    /**
     * Try multiple fallback strategies for a failed platform
     * Runs strategies sequentially for stability (parallel caused resource issues)
     */
    private suspend fun tryFallbackStrategies(
        platform: String,
        query: String,
        encodedQuery: String,
        pincode: String
    ): List<Product> {
        val strategies = getFallbackStrategies(platform)
        
        if (strategies.isEmpty()) return emptyList()
        
        println("FallbackManager: $platform - trying ${strategies.size} strategies")
        
        for ((index, strategy) in strategies.withIndex()) {
            try {
                println("FallbackManager: $platform - strategy ${index + 1}/${strategies.size}: '${strategy.name}'")
                val products = strategy.execute(query, encodedQuery, pincode)
                if (products.isNotEmpty()) {
                    println("FallbackManager: $platform - ✓ '${strategy.name}' succeeded with ${products.size} products")
                    return products
                }
            } catch (e: Exception) {
                println("FallbackManager: $platform - ✗ '${strategy.name}' failed: ${e.message}")
            }
        }
        
        println("FallbackManager: $platform - all ${strategies.size} strategies failed")
        return emptyList()
    }
    
    /**
     * Get fallback strategies for a platform
     */
    private fun getFallbackStrategies(platform: String): List<ScraperStrategy> {
        val strategies = when (platform) {
            Platforms.FLIPKART, Platforms.FLIPKART_MINUTES -> listOf(
                ScraperStrategy("HTTP alternative URL") { _, eq, pc -> scrapeFlipkartAlt(eq, pc) },
                ScraperStrategy("WebView with scroll") { _, eq, pc -> scrapeFlipkartWebView(eq, pc) },
                ScraperStrategy("Price container extraction") { _, eq, pc -> scrapeFlipkartPriceContainers(eq, pc) }
            )
            Platforms.BIGBASKET -> listOf(
                ScraperStrategy("WebView longer wait") { _, eq, pc -> scrapeBigBasketLongWait(eq, pc) },
                ScraperStrategy("NEXT_DATA extraction") { _, eq, pc -> scrapeBigBasketNextData(eq, pc) },
                ScraperStrategy("Price+image containers") { _, eq, pc -> scrapeBigBasketContainers(eq, pc) }
            )
            Platforms.INSTAMART -> listOf(
                ScraperStrategy("WebView extraction") { _, eq, pc -> scrapeInstamartWebView(eq, pc) },
                ScraperStrategy("JSON script extraction") { _, eq, pc -> scrapeInstamartScripts(eq, pc) }
            )
            Platforms.JIOMART, Platforms.JIOMART_QUICK -> listOf(
                ScraperStrategy("Alternative URL") { _, eq, pc -> scrapeJioMartAlt(eq, pc) },
                ScraperStrategy("WebView extraction") { _, eq, pc -> scrapeJioMartWebView(eq, pc) }
            )
            Platforms.ZEPTO -> listOf(
                ScraperStrategy("HTTP with cookies") { _, eq, pc -> scrapeZeptoWithCookies(eq, pc) },
                ScraperStrategy("WebView extraction") { _, eq, pc -> scrapeZeptoWebView(eq, pc) }
            )
            Platforms.BLINKIT -> listOf(
                ScraperStrategy("WebView longer wait") { _, eq, pc -> scrapeBlinkitWebView(eq, pc) },
                ScraperStrategy("Price pattern extraction") { _, eq, pc -> scrapeBlinkitPatterns(eq, pc) }
            )
            else -> emptyList()
        }
        return strategies + ScraperStrategy("AI fallback extraction") { q, eq, pc ->
            aiExtractFallback(platform, q, eq, pc)
        }
    }
    
    // ==================== PRIMARY SCRAPERS ====================
    
    private suspend fun scrapeZepto(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.zeptonow.com/search?query=$encodedQuery",
            platform = Platforms.ZEPTO,
            color = Platforms.ZEPTO_COLOR,
            delivery = "10 mins",
            baseUrl = "https://www.zeptonow.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeBlinkit(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://blinkit.com/s/?q=$encodedQuery",
            platform = Platforms.BLINKIT,
            color = Platforms.BLINKIT_COLOR,
            delivery = "10 mins",
            baseUrl = "https://blinkit.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeBigBasket(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.bigbasket.com/ps/?q=$encodedQuery",
            platform = Platforms.BIGBASKET,
            color = Platforms.BIGBASKET_COLOR,
            delivery = "1-2 hours",
            baseUrl = "https://www.bigbasket.com",
            pincode = pincode,
            waitForSelector = "img[src*='bbassets'], img[src*='bigbasket'], [data-qa*='product'], [data-testid*='product']"
        )
    }
    
    private suspend fun scrapeInstamart(encodedQuery: String, pincode: String): List<Product> {
        return httpScrape(
            url = "https://www.swiggy.com/instamart/search?custom_back=true&query=$encodedQuery",
            platform = Platforms.INSTAMART,
            color = Platforms.INSTAMART_COLOR,
            delivery = "15-30 mins",
            baseUrl = "https://www.swiggy.com/instamart",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeAmazonFresh(encodedQuery: String, pincode: String): List<Product> {
        return httpScrapeAmazon(
            url = "https://www.amazon.in/s?k=$encodedQuery&i=nowstore",
            platform = Platforms.AMAZON_FRESH,
            color = Platforms.AMAZON_FRESH_COLOR,
            delivery = "2-4 hours",
            baseUrl = "https://www.amazon.in",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeAmazon(encodedQuery: String, pincode: String): List<Product> {
        return httpScrapeAmazon(
            url = "https://www.amazon.in/s?k=$encodedQuery",
            platform = Platforms.AMAZON,
            color = Platforms.AMAZON_COLOR,
            delivery = "1-3 days",
            baseUrl = "https://www.amazon.in",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeFlipkart(encodedQuery: String, pincode: String): List<Product> {
        return httpScrapeFlipkart(
            url = "https://www.flipkart.com/search?q=$encodedQuery",
            platform = Platforms.FLIPKART,
            color = Platforms.FLIPKART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.flipkart.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeFlipkartMinutes(encodedQuery: String, pincode: String): List<Product> {
        return httpScrapeFlipkart(
            url = "https://www.flipkart.com/search?q=$encodedQuery&marketplace=GROCERY",
            platform = Platforms.FLIPKART_MINUTES,
            color = Platforms.FLIPKART_MINUTES_COLOR,
            delivery = "10-15 mins",
            baseUrl = "https://www.flipkart.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeJioMart(encodedQuery: String, pincode: String): List<Product> {
        return httpScrape(
            url = "https://www.jiomart.com/search/$encodedQuery",
            platform = Platforms.JIOMART,
            color = Platforms.JIOMART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.jiomart.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeJioMartQuick(encodedQuery: String, pincode: String): List<Product> {
        return httpScrape(
            url = "https://www.jiomart.com/search/$encodedQuery?deliveryType=express",
            platform = Platforms.JIOMART_QUICK,
            color = Platforms.JIOMART_COLOR,
            delivery = "15-30 mins",
            baseUrl = "https://www.jiomart.com",
            pincode = pincode
        )
    }
    
    // ==================== FALLBACK SCRAPERS ====================
    
    private suspend fun scrapeFlipkartAlt(encodedQuery: String, pincode: String): List<Product> {
        return httpScrapeFlipkart(
            url = "https://www.flipkart.com/grocery-supermart-store?q=$encodedQuery",
            platform = Platforms.FLIPKART,
            color = Platforms.FLIPKART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.flipkart.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeFlipkartWebView(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.flipkart.com/search?q=$encodedQuery",
            platform = Platforms.FLIPKART,
            color = Platforms.FLIPKART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.flipkart.com",
            pincode = pincode,
            timeoutMs = 12_000L  // Reduced for faster fallback
        )
    }
    
    private suspend fun scrapeFlipkartPriceContainers(encodedQuery: String, pincode: String): List<Product> {
        // Use WebView and extract using price containers
        val html = webViewHelper.loadAndGetHtml(
            url = "https://www.flipkart.com/search?q=$encodedQuery",
            timeoutMs = 15_000L,
            pincode = pincode
        ) ?: return emptyList()
        
        return extractByPriceContainers(
            html = html,
            platform = Platforms.FLIPKART,
            color = Platforms.FLIPKART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.flipkart.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeBigBasketLongWait(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.bigbasket.com/ps/?q=$encodedQuery",
            platform = Platforms.BIGBASKET,
            color = Platforms.BIGBASKET_COLOR,
            delivery = "1-2 hours",
            baseUrl = "https://www.bigbasket.com",
            pincode = pincode,
            timeoutMs = 12_000L  // Reduced for faster fallback
        )
    }
    
    private suspend fun scrapeBigBasketNextData(encodedQuery: String, pincode: String): List<Product> {
        val html = webViewHelper.loadAndGetHtml(
            url = "https://www.bigbasket.com/ps/?q=$encodedQuery",
            timeoutMs = 15_000L,
            pincode = pincode
        ) ?: return emptyList()
        
        return extractFromNextData(
            html = html,
            platform = Platforms.BIGBASKET,
            color = Platforms.BIGBASKET_COLOR,
            delivery = "1-2 hours",
            baseUrl = "https://www.bigbasket.com"
        )
    }
    
    private suspend fun scrapeBigBasketContainers(encodedQuery: String, pincode: String): List<Product> {
        val html = webViewHelper.loadAndGetHtml(
            url = "https://www.bigbasket.com/ps/?q=$encodedQuery",
            timeoutMs = 15_000L,
            pincode = pincode
        ) ?: return emptyList()
        
        return extractByPriceContainers(
            html = html,
            platform = Platforms.BIGBASKET,
            color = Platforms.BIGBASKET_COLOR,
            delivery = "1-2 hours",
            baseUrl = "https://www.bigbasket.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeInstamartWebView(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.swiggy.com/instamart/search?custom_back=true&query=$encodedQuery",
            platform = Platforms.INSTAMART,
            color = Platforms.INSTAMART_COLOR,
            delivery = "15-30 mins",
            baseUrl = "https://www.swiggy.com/instamart",
            pincode = pincode,
            timeoutMs = 12_000L,  // Reduced for faster fallback
            waitForSelector = "img[src*='cloudinary'], img[src*='swiggy']"
        )
    }
    
    private suspend fun scrapeInstamartScripts(encodedQuery: String, pincode: String): List<Product> {
        val html = webViewHelper.loadAndGetHtml(
            url = "https://www.swiggy.com/instamart/search?custom_back=true&query=$encodedQuery",
            timeoutMs = 15_000L,
            pincode = pincode
        ) ?: return emptyList()
        
        return extractFromScriptTags(
            html = html,
            platform = Platforms.INSTAMART,
            color = Platforms.INSTAMART_COLOR,
            delivery = "15-30 mins",
            baseUrl = "https://www.swiggy.com/instamart"
        )
    }
    
    private suspend fun scrapeJioMartAlt(encodedQuery: String, pincode: String): List<Product> {
        return httpScrape(
            url = "https://www.jiomart.com/catalogsearch/result/?q=$encodedQuery",
            platform = Platforms.JIOMART,
            color = Platforms.JIOMART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.jiomart.com",
            pincode = pincode
        )
    }
    
    private suspend fun scrapeJioMartWebView(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.jiomart.com/search/$encodedQuery",
            platform = Platforms.JIOMART,
            color = Platforms.JIOMART_COLOR,
            delivery = "1-2 days",
            baseUrl = "https://www.jiomart.com",
            pincode = pincode,
            timeoutMs = 12_000L,  // Reduced for faster fallback
            waitForSelector = "a[href*='/p/'], img[alt]"
        )
    }
    
    private suspend fun scrapeZeptoWithCookies(encodedQuery: String, pincode: String): List<Product> {
        // Try HTTP with specific cookies
        val request = okhttp3.Request.Builder()
            .url("https://www.zeptonow.com/search?query=$encodedQuery")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
            .header("Cookie", "lat=12.9716; lng=77.5946; pincode=$pincode; storeId=default")
            .build()
        
        return try {
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return emptyList()
            
            ResilientExtractor.extractProducts(
                html = html,
                platformName = Platforms.ZEPTO,
                platformColor = Platforms.ZEPTO_COLOR,
                deliveryTime = "10 mins",
                baseUrl = "https://www.zeptonow.com"
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun scrapeZeptoWebView(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://www.zeptonow.com/search?query=$encodedQuery",
            platform = Platforms.ZEPTO,
            color = Platforms.ZEPTO_COLOR,
            delivery = "10 mins",
            baseUrl = "https://www.zeptonow.com",
            pincode = pincode,
            timeoutMs = 12_000L,  // Reduced for faster fallback
            waitForSelector = "a[href*='/pn/'], img[alt]"
        )
    }
    
    private suspend fun scrapeBlinkitWebView(encodedQuery: String, pincode: String): List<Product> {
        return webViewScrape(
            url = "https://blinkit.com/s/?q=$encodedQuery",
            platform = Platforms.BLINKIT,
            color = Platforms.BLINKIT_COLOR,
            delivery = "10 mins",
            baseUrl = "https://blinkit.com",
            pincode = pincode,
            timeoutMs = 12_000L,  // Reduced for faster fallback
            waitForSelector = "img[alt]"
        )
    }
    
    private suspend fun scrapeBlinkitPatterns(encodedQuery: String, pincode: String): List<Product> {
        val html = webViewHelper.loadAndGetHtml(
            url = "https://blinkit.com/s/?q=$encodedQuery",
            timeoutMs = 15_000L,
            pincode = pincode
        ) ?: return emptyList()
        
        return extractByPriceContainers(
            html = html,
            platform = Platforms.BLINKIT,
            color = Platforms.BLINKIT_COLOR,
            delivery = "10 mins",
            baseUrl = "https://blinkit.com",
            pincode = pincode
        )
    }
    
    // ==================== EXTRACTION HELPERS ====================
    
    private suspend fun webViewScrape(
        url: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String,
        pincode: String,
        timeoutMs: Long = 15_000L,
        waitForSelector: String? = null
    ): List<Product> {
        webViewHelper.setLocation(pincode)
        
        val html = webViewHelper.loadAndGetHtml(
            url = url,
            timeoutMs = timeoutMs,
            waitForSelector = waitForSelector,
            pincode = pincode
        )
        
        if (html.isNullOrBlank()) return emptyList()
        
        return ResilientExtractor.extractProducts(
            html = html,
            platformName = platform,
            platformColor = color,
            deliveryTime = delivery,
            baseUrl = baseUrl
        ).map { product ->
            val finalUrl = if (product.url.isBlank() || product.url == baseUrl) {
                "$baseUrl/search?q=${URLEncoder.encode(product.name, "UTF-8")}&pincode=$pincode"
            } else {
                product.url
            }
            product.copy(url = finalUrl)
        }
    }

    private suspend fun aiExtractFallback(
        platform: String,
        query: String,
        encodedQuery: String,
        pincode: String
    ): List<Product> {
        val config = getAIFallbackConfig(platform, encodedQuery) ?: return emptyList()
        return try {
            webViewHelper.setLocation(pincode)
            val html = webViewHelper.loadAndGetHtml(
                url = config.searchUrl,
                timeoutMs = 15_000L,
                waitForSelector = config.waitForSelector,
                pincode = pincode
            )?.take(100_000)

            if (html.isNullOrBlank()) {
                return emptyList()
            }

            val response = api.aiExtract(
                AIExtractRequest(
                    html = html,
                    platform = platform,
                    searchQuery = query,
                    baseUrl = config.baseUrl
                )
            )

            if (!response.aiPowered || response.productsFound <= 0) {
                return emptyList()
            }

            response.products.map { aiProduct ->
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class AIFallbackConfig(
        val searchUrl: String,
        val baseUrl: String,
        val color: Long,
        val deliveryTime: String,
        val waitForSelector: String?
    )

    private fun getAIFallbackConfig(platform: String, encodedQuery: String): AIFallbackConfig? {
        return when (platform) {
            Platforms.BIGBASKET -> AIFallbackConfig(
                searchUrl = "https://www.bigbasket.com/ps/?q=$encodedQuery",
                baseUrl = "https://www.bigbasket.com",
                color = Platforms.BIGBASKET_COLOR,
                deliveryTime = "1-2 hours",
                waitForSelector = "img[src*='bbassets'], img[src*='bigbasket'], [data-qa*='product'], [data-testid*='product']"
            )
            Platforms.INSTAMART -> AIFallbackConfig(
                searchUrl = "https://www.swiggy.com/instamart/search?custom_back=true&query=$encodedQuery",
                baseUrl = "https://www.swiggy.com/instamart",
                color = Platforms.INSTAMART_COLOR,
                deliveryTime = "15-30 mins",
                waitForSelector = "img[src*='cloudinary'], img[src*='swiggy']"
            )
            Platforms.BLINKIT -> AIFallbackConfig(
                searchUrl = "https://blinkit.com/s/?q=$encodedQuery",
                baseUrl = "https://blinkit.com",
                color = Platforms.BLINKIT_COLOR,
                deliveryTime = "10 mins",
                waitForSelector = "img[alt]"
            )
            Platforms.ZEPTO -> AIFallbackConfig(
                searchUrl = "https://www.zeptonow.com/search?query=$encodedQuery",
                baseUrl = "https://www.zeptonow.com",
                color = Platforms.ZEPTO_COLOR,
                deliveryTime = "10 mins",
                waitForSelector = "a[href*='/pn/'], img[alt]"
            )
            Platforms.FLIPKART_MINUTES -> AIFallbackConfig(
                searchUrl = "https://www.flipkart.com/search?q=$encodedQuery&marketplace=GROCERY",
                baseUrl = "https://www.flipkart.com",
                color = Platforms.FLIPKART_MINUTES_COLOR,
                deliveryTime = "10-15 mins",
                waitForSelector = "a[href*='/p/'], img[alt]"
            )
            Platforms.JIOMART_QUICK -> AIFallbackConfig(
                searchUrl = "https://www.jiomart.com/search/$encodedQuery?deliveryType=express",
                baseUrl = "https://www.jiomart.com",
                color = Platforms.JIOMART_COLOR,
                deliveryTime = "15-30 mins",
                waitForSelector = "a[href*='/p/'], img[alt]"
            )
            Platforms.JIOMART -> AIFallbackConfig(
                searchUrl = "https://www.jiomart.com/search/$encodedQuery",
                baseUrl = "https://www.jiomart.com",
                color = Platforms.JIOMART_COLOR,
                deliveryTime = "1-2 days",
                waitForSelector = "a[href*='/p/'], img[alt]"
            )
            Platforms.FLIPKART -> AIFallbackConfig(
                searchUrl = "https://www.flipkart.com/search?q=$encodedQuery",
                baseUrl = "https://www.flipkart.com",
                color = Platforms.FLIPKART_COLOR,
                deliveryTime = "1-2 days",
                waitForSelector = "a[href*='/p/'], img[alt]"
            )
            else -> null
        }
    }
    
    private suspend fun httpScrape(
        url: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String,
        pincode: String
    ): List<Product> {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                .header("Accept", "text/html")
                .header("Cookie", "pincode=$pincode")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            
            val html = response.body?.string() ?: return emptyList()
            
            ResilientExtractor.extractProducts(
                html = html,
                platformName = platform,
                platformColor = color,
                deliveryTime = delivery,
                baseUrl = baseUrl
            ).map { product ->
                val finalUrl = if (product.url.isBlank() || product.url == baseUrl) {
                    "$baseUrl/search?q=${URLEncoder.encode(product.name, "UTF-8")}&pincode=$pincode"
                } else {
                    product.url
                }
                product.copy(url = finalUrl)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun httpScrapeAmazon(
        url: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String,
        pincode: String
    ): List<Product> {
        return try {
            val deliveryLocationCookie = """{"locationType":"LOCATION_INPUT","zipCode":"$pincode","city":"","countryCode":"IN"}"""
            val encodedCookie = URLEncoder.encode(deliveryLocationCookie, "UTF-8")
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                .header("Accept", "text/html")
                .header("Cookie", "i18n-prefs=INR; lc-acbin=en_IN; delivery_location=$encodedCookie")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            
            val html = response.body?.string() ?: return emptyList()
            val doc = Jsoup.parse(html)
            
            val products = mutableListOf<Product>()
            val productCards = doc.select("div[data-component-type=s-search-result]").take(10)
            
            for (card in productCards) {
                try {
                    val asin = card.attr("data-asin")
                    val name = card.selectFirst("h2 a span, h2 span")?.text()
                    if (name.isNullOrBlank() || name.length < 3) continue
                    
                    val priceText = card.selectFirst("span.a-price span.a-offscreen, span.a-price-whole")?.text()
                    if (priceText.isNullOrBlank()) continue
                    
                    val price = priceText.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
                    if (price == null || price <= 0) continue
                    
                    val originalPriceText = card.selectFirst("span.a-price.a-text-price span.a-offscreen")?.text()
                    val originalPrice = originalPriceText?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()?.takeIf { it > price }
                    
                    val imageUrl = card.selectFirst("img.s-image")?.attr("src") ?: ""
                    
                    val productUrl = if (asin.isNotBlank()) {
                        "$baseUrl/dp/$asin?pincode=$pincode"
                    } else {
                        "$baseUrl/s?k=${URLEncoder.encode(name, "UTF-8")}&pincode=$pincode"
                    }
                    
                    val finalName = appendQuantityToNameIfMissing(name.trim(), card.text())
                    products.add(Product(
                        name = finalName,
                        price = price,
                        originalPrice = originalPrice,
                        imageUrl = imageUrl,
                        platform = platform,
                        platformColor = color,
                        deliveryTime = delivery,
                        url = productUrl,
                        rating = null,
                        discount = originalPrice?.let { ((it - price) / it * 100).toInt().toString() + "% off" },
                        available = true
                    ))
                } catch (e: Exception) {
                    continue
                }
            }
            
            products
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun httpScrapeFlipkart(
        url: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String,
        pincode: String
    ): List<Product> {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                .header("Accept", "text/html")
                .header("Cookie", "pincode=$pincode")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            
            val html = response.body?.string() ?: return emptyList()
            if (html.length < 10000) return emptyList()
            
            val doc = Jsoup.parse(html)
            
            // Try multiple Flipkart selectors
            var productCards = doc.select("div[data-id], div._1AtVbE, a._1fQZEK").take(10)
            
            // Fallback: find product links
            if (productCards.isEmpty()) {
                productCards = doc.select("a[href*='/p/']").take(10)
            }
            
            if (productCards.isEmpty()) return emptyList()
            
            val products = mutableListOf<Product>()
            
            for (card in productCards) {
                try {
                    val name = card.selectFirst("div._4rR01T, a.s1Q9rs, div.IRpwTa")?.text()
                        ?: card.attr("title")
                    if (name.isNullOrBlank() || name.length < 3) continue
                    
                    val priceText = card.selectFirst("div._30jeq3")?.text()
                        ?: Regex("₹([\\d,]+)").find(card.text())?.groupValues?.get(0)
                    if (priceText.isNullOrBlank()) continue
                    
                    val price = priceText.replace(Regex("[^\\d]"), "").toDoubleOrNull()
                    if (price == null || price <= 0) continue
                    
                    val originalPriceText = card.selectFirst("div._3I9_wc")?.text()
                    val originalPrice = originalPriceText?.replace(Regex("[^\\d]"), "")?.toDoubleOrNull()?.takeIf { it > price }
                    
                    val imageUrl = card.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } } ?: ""
                    
                    var productUrl = card.selectFirst("a[href*='/p/']")?.attr("href")
                        ?: card.attr("href")
                    if (productUrl.isNotBlank() && !productUrl.startsWith("http")) {
                        productUrl = "$baseUrl$productUrl"
                    }
                    if (productUrl.isBlank()) {
                        productUrl = "$baseUrl/search?q=${URLEncoder.encode(name, "UTF-8")}&pincode=$pincode"
                    }
                    
                    val finalName = appendQuantityToNameIfMissing(name.trim(), card.text())
                    products.add(Product(
                        name = finalName,
                        price = price,
                        originalPrice = originalPrice,
                        imageUrl = imageUrl,
                        platform = platform,
                        platformColor = color,
                        deliveryTime = delivery,
                        url = productUrl,
                        rating = null,
                        discount = originalPrice?.let { ((it - price) / it * 100).toInt().toString() + "% off" },
                        available = true
                    ))
                } catch (e: Exception) {
                    continue
                }
            }
            
            products
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun extractByPriceContainers(
        html: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String,
        pincode: String
    ): List<Product> {
        val doc = Jsoup.parse(html)
        val products = mutableListOf<Product>()
        val seen = mutableSetOf<Int>()
        
        // Find all elements containing prices
        val priceElements = doc.select("*:contains(₹)").toList().filter { el ->
            el.text().matches(Regex(".*₹\\s*\\d+.*")) && el.text().length < 200
        }
        
        for (priceEl in priceElements.take(30)) {
            // Find container with image
            val container = priceEl.parents().firstOrNull { parent ->
                parent.selectFirst("img") != null && 
                parent.text().length > 10 && 
                parent.text().length < 500 &&
                !seen.contains(parent.hashCode())
            } ?: continue
            
            seen.add(container.hashCode())
            
            // Extract name
            val name = container.selectFirst("h3, h4, [class*='name'], [class*='title']")?.text()
                ?: container.selectFirst("a")?.text()
                ?: container.selectFirst("img")?.attr("alt")
            if (name.isNullOrBlank() || name.length < 3) continue
            
            // Extract price
            val priceText = Regex("₹\\s*(\\d+)").find(container.text())?.groupValues?.get(1)
            val price = priceText?.toDoubleOrNull()?.takeIf { it in 10.0..5000.0 } ?: continue
            
            // Get image
            val imageUrl = container.selectFirst("img")?.let { 
                it.attr("src").ifBlank { it.attr("data-src") }
            } ?: ""
            
            // Get URL
            var productUrl = container.selectFirst("a[href]")?.attr("href") ?: ""
            if (productUrl.isNotBlank() && !productUrl.startsWith("http")) {
                productUrl = "$baseUrl$productUrl"
            }
            
            val finalName = appendQuantityToNameIfMissing(name.trim(), container.text())
            products.add(Product(
                name = finalName,
                price = price,
                originalPrice = null,
                imageUrl = imageUrl,
                platform = platform,
                platformColor = color,
                deliveryTime = delivery,
                url = productUrl.ifBlank { "$baseUrl/search?q=${URLEncoder.encode(name, "UTF-8")}&pincode=$pincode" },
                rating = null,
                discount = null,
                available = true
            ))
            
            if (products.size >= 10) break
        }
        
        return products
    }
    
    private fun extractFromNextData(
        html: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String
    ): List<Product> {
        val doc = Jsoup.parse(html)
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.html() ?: return emptyList()
        
        val products = mutableListOf<Product>()
        
        // Extract products from JSON patterns
        val productPattern = Regex(""""name"\s*:\s*"([^"]+)"[^}]*"(?:sp|price)"\s*:\s*(\d+(?:\.\d+)?)""")
        productPattern.findAll(nextDataScript).take(10).forEach { match ->
            val name = match.groupValues[1]
            val price = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            
            if (name.length > 3 && price in 10.0..5000.0) {
                val finalName = appendQuantityToNameIfMissing(name.trim(), null)
                products.add(Product(
                    name = finalName,
                    price = price,
                    originalPrice = null,
                    imageUrl = "",
                    platform = platform,
                    platformColor = color,
                    deliveryTime = delivery,
                    url = "$baseUrl/search?q=${URLEncoder.encode(name, "UTF-8")}",
                    rating = null,
                    discount = null,
                    available = true
                ))
            }
        }
        
        return products
    }
    
    private fun extractFromScriptTags(
        html: String,
        platform: String,
        color: Long,
        delivery: String,
        baseUrl: String
    ): List<Product> {
        val doc = Jsoup.parse(html)
        val products = mutableListOf<Product>()
        
        // Find scripts containing product data
        for (script in doc.select("script")) {
            val scriptContent = script.html()
            if (scriptContent.contains("\"products\"") || scriptContent.contains("\"items\"")) {
                val productPattern = Regex(""""name"\s*:\s*"([^"]+)"[^}]*"price"\s*:\s*(\d+)""")
                productPattern.findAll(scriptContent).take(10).forEach { match ->
                    val name = match.groupValues[1]
                    val price = match.groupValues[2].toDoubleOrNull() ?: return@forEach
                    
                    if (name.length > 3 && price in 10.0..5000.0) {
                        val finalName = appendQuantityToNameIfMissing(name.trim(), null)
                        products.add(Product(
                            name = finalName,
                            price = price,
                            originalPrice = null,
                            imageUrl = "",
                            platform = platform,
                            platformColor = color,
                            deliveryTime = delivery,
                            url = "$baseUrl/search?q=${URLEncoder.encode(name, "UTF-8")}",
                            rating = null,
                            discount = null,
                            available = true
                        ))
                    }
                }
                
                if (products.isNotEmpty()) break
            }
        }
        
        return products
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
}

/**
 * Result from fallback search
 */
data class FallbackSearchResult(
    val phase: String,
    val platform: String? = null,
    val products: List<Product> = emptyList(),
    val allResults: Map<String, List<Product>>? = null,
    val message: String
)

/**
 * A scraper strategy with a name
 */
data class ScraperStrategy(
    val name: String,
    val execute: suspend (query: String, encodedQuery: String, pincode: String) -> List<Product>
)
