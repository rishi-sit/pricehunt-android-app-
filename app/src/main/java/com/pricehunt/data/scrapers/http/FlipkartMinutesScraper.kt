package com.pricehunt.data.scrapers.http

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.scrapers.BaseScraper
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scraper for Flipkart Minutes (quick commerce) using WebView with resilient extraction
 * Uses grocery-specific URLs for quick delivery products
 */
@Singleton
class FlipkartMinutesScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.FLIPKART_MINUTES
    override val platformColor = Platforms.FLIPKART_MINUTES_COLOR
    override val deliveryTime = "10-15 mins"
    override val baseUrl = "https://www.flipkart.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                
                // Use grocery marketplace search URL
                val searchUrl = "$baseUrl/search?q=$encodedQuery&marketplace=GROCERY&otracker=search&as-show=on"
                
                println("$platformName: Loading $searchUrl")
                
                webViewHelper.setLocation(pincode)
                
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    timeoutMs = 20_000L,
                    waitForSelector = "[data-id], ._1AtVbE, ._4ddWXP, .product-card",
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: ✗ WebView returned empty HTML")
                    return@withContext emptyList()
                }
                
                println("$platformName: Got ${html.length} chars HTML")
                
                // Check if we got homepage instead of search results
                if (html.contains("<title>Online Shopping India") && html.length < 10000) {
                    println("$platformName: ⚠️ Detected homepage redirect")
                    return@withContext emptyList()
                }
                
                val products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                if (products.isEmpty()) {
                    println("$platformName: ✗ No products extracted")
                    println("$platformName: HTML preview: ${html.take(300)}")
                    return@withContext emptyList()
                }
                
                println("$platformName: ✓ Found ${products.size} products")
                
                // Fix URLs
                products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                        val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                        "$baseUrl/search?q=$productSearchQuery&marketplace=GROCERY&pincode=$pincode"
                    } else {
                        val separator = if (product.url.contains("?")) "&" else "?"
                        "${product.url}${separator}pincode=$pincode"
                    }
                    product.copy(url = finalUrl)
                }
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                emptyList()
            }
        }
}
