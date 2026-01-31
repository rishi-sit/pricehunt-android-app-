package com.pricehunt.data.scrapers.http

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.scrapers.BaseScraper
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scraper for Flipkart Minutes (grocery/quick commerce)
 * Uses same HTTP-first approach as FlipkartScraper
 */
@Singleton
class FlipkartMinutesScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.FLIPKART_MINUTES
    override val platformColor = Platforms.FLIPKART_MINUTES_COLOR
    override val deliveryTime = "10-15 mins"
    override val baseUrl = "https://m.flipkart.com" // Use mobile site for robustness
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            val httpProducts = withTimeoutOrNull(3_500L) {
                tryHttpScraping(query, pincode)
            }
            if (!httpProducts.isNullOrEmpty()) {
                return@withContext httpProducts
            }

            // Flipkart is an SPA - use WebView directly
            println("$platformName: Using WebView (SPA site)...")
            tryWebViewScraping(query, pincode)
        }
    
    private suspend fun tryHttpScraping(query: String, pincode: String): List<Product> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Try grocery-specific URLs
            val urls = listOf(
                "$baseUrl/search?q=$encodedQuery&marketplace=GROCERY",
                "$baseUrl/grocery-supermart-store?q=$encodedQuery"
            )
            
            for (searchUrl in urls) {
                println("$platformName: HTTP request to $searchUrl")
                
                val request = okhttp3.Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                    .header("Accept", "text/html")
                    .header("Cookie", "pincode=$pincode")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    println("$platformName: HTTP ${response.code}")
                    continue
                }
                
                val html = response.body?.string() ?: continue
                println("$platformName: Got ${html.length} chars via HTTP")
                
                // Parse with Jsoup
                val doc = org.jsoup.Jsoup.parse(html)
                
                // Check for homepage redirect
                val title = doc.title()
                if (title.contains("Online Shopping India") && !html.contains(query, ignoreCase = true)) {
                    println("$platformName: Homepage redirect, trying next URL...")
                    continue
                }
                
                val productCards = doc.select("div[data-id], div._1AtVbE, div._2kHMtA, a._1fQZEK").take(10)
                
                if (productCards.isEmpty()) {
                    println("$platformName: No product cards found")
                    continue
                }
                
                val products = mutableListOf<Product>()
                
                for (card in productCards) {
                    try {
                        val name = card.selectFirst("div._4rR01T, a.s1Q9rs, div.IRpwTa")?.text()
                            ?: card.selectFirst("a[title]")?.attr("title")
                        
                        if (name.isNullOrBlank() || name.length < 3) continue
                        
                        val priceText = card.selectFirst("div._30jeq3, div._1_WHN1")?.text()
                        if (priceText.isNullOrBlank()) continue
                        
                        val price = parsePrice(priceText)
                        if (price <= 0) continue
                        
                        val originalPriceText = card.selectFirst("div._3I9_wc")?.text()
                        val originalPrice = originalPriceText?.let { parsePrice(it) }?.takeIf { it > price }
                        
                        val imageUrl = card.selectFirst("img")?.let { 
                            it.attr("src").ifBlank { it.attr("data-src") }
                        } ?: ""
                        
                        var productUrl = card.selectFirst("a[href*='/p/']")?.attr("href")
                            ?: card.selectFirst("a[href]")?.attr("href")
                            ?: ""
                        
                        if (productUrl.isNotBlank() && !productUrl.startsWith("http")) {
                            productUrl = "$baseUrl$productUrl"
                        }
                        
                        if (productUrl.isBlank()) {
                            productUrl = "$baseUrl/search?q=${URLEncoder.encode(name, "UTF-8")}&marketplace=GROCERY&pincode=$pincode"
                        }
                        
                        products.add(Product(
                            name = name.trim(),
                            price = price,
                            originalPrice = originalPrice,
                            imageUrl = imageUrl,
                            platform = platformName,
                            platformColor = platformColor,
                            deliveryTime = deliveryTime,
                            url = productUrl,
                            rating = null,
                            discount = originalPrice?.let { ((it - price) / it * 100).toInt().toString() + "% off" },
                            available = true
                        ))
                    } catch (e: Exception) {
                        continue
                    }
                }
                
                if (products.isNotEmpty()) {
                    println("$platformName: ✓ Found ${products.size} products via HTTP")
                    return products
                }
            }
            
            return emptyList()
            
        } catch (e: Exception) {
            println("$platformName: HTTP error - ${e.message}")
            return emptyList()
        }
    }
    
    private suspend fun tryWebViewScraping(query: String, pincode: String): List<Product> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // FLIPKART MINUTES: Focus on grocery/quick commerce URLs
        // Mobile site is most reliable and faster to load
        val urlsToTry = listOf(
            // Primary: Mobile search with grocery filter (fastest & most reliable)
            "https://m.flipkart.com/search?q=$encodedQuery&marketplace=GROCERY"
        )
        
        webViewHelper.setLocation(pincode)
        
        // OPTIMIZED: Shorter timeout - dynamic content detection means we don't need long waits
        val perUrlTimeout = 9_000L
        
        for ((index, searchUrl) in urlsToTry.withIndex()) {
            try {
                println("$platformName: Trying URL ${index + 1}/${urlsToTry.size}: $searchUrl")
                
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    timeoutMs = perUrlTimeout,
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: URL ${index + 1} returned empty, trying next...")
                    continue
                }
                
                // Check if we got a valid search results page (not homepage redirect)
                if (html.length < 5000 || !html.contains("search", ignoreCase = true)) {
                    println("$platformName: URL ${index + 1} seems like redirect/empty, trying next...")
                    continue
                }
                
                println("$platformName: Got ${html.length} chars from URL ${index + 1}")
                
                val products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                if (products.isNotEmpty()) {
                    println("$platformName: ✓ Found ${products.size} products via URL ${index + 1}")
                    return fixProductUrls(products, pincode)
                }
                
                println("$platformName: URL ${index + 1} - no products extracted, trying next...")
                
            } catch (e: Exception) {
                println("$platformName: URL ${index + 1} error: ${e.message}")
                // Don't continue if timeout - likely all URLs will timeout too
                if (e.message?.contains("Timed out") == true) {
                    println("$platformName: Timeout reached, stopping URL attempts")
                    break
                }
            }
        }
        
        println("$platformName: ✗ All URLs failed")
        return emptyList()
    }
    
    private fun fixProductUrls(products: List<Product>, pincode: String): List<Product> {
        return products.map { product ->
            val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                "$baseUrl/search?q=${URLEncoder.encode(product.name, "UTF-8")}&marketplace=GROCERY&pincode=$pincode"
            } else {
                "${product.url}${if (product.url.contains("?")) "&" else "?"}pincode=$pincode"
            }
            product.copy(url = finalUrl)
        }
    }
}
