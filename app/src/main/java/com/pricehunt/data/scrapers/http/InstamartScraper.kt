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
 * Scraper for Swiggy Instamart using WebView with resilient extraction
 * Instamart loads products dynamically - need to wait for them
 */
@Singleton
class InstamartScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.INSTAMART
    override val platformColor = Platforms.INSTAMART_COLOR
    override val deliveryTime = "15-30 mins"
    override val baseUrl = "https://www.swiggy.com/instamart"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                // Use the full search URL with custom_back parameter as seen in browser
                val searchUrl = "$baseUrl/search?custom_back=true&query=$encodedQuery"
                
                println("$platformName: Loading $searchUrl")
                
                webViewHelper.setLocation(pincode)
                
                // Wait longer and look for product images to load
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    timeoutMs = 20_000L,  // 20 seconds for Instamart
                    waitForSelector = "img[src*='swiggy'], img[src*='res.cloudinary'], [class*='product'], [class*='Product']",
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: ✗ WebView returned empty HTML")
                    return@withContext emptyList()
                }
                
                println("$platformName: Got ${html.length} chars HTML")
                
                // Debug: check what we got
                val hasSwiggyImages = html.contains("swiggy") || html.contains("cloudinary")
                val hasProductClasses = html.contains("product", ignoreCase = true)
                val hasPrices = html.contains("₹") || html.contains("Rs")
                println("$platformName: DEBUG - swiggy images: $hasSwiggyImages, product classes: $hasProductClasses, prices: $hasPrices")
                
                val products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                if (products.isEmpty()) {
                    println("$platformName: ✗ No products extracted")
                    // Show more of HTML to debug
                    val snippet = html.take(800).replace("\n", " ").replace("\\s+".toRegex(), " ")
                    println("$platformName: HTML snippet: $snippet")
                }
                
                // Fix URLs
                val fixedProducts = products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank() || product.url == "https://www.swiggy.com") {
                        val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                        "$baseUrl/search?custom_back=true&query=$productSearchQuery"
                    } else {
                        product.url
                    }
                    product.copy(url = finalUrl)
                }
                
                println("$platformName: ✓ Found ${fixedProducts.size} products")
                fixedProducts
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                emptyList()
            }
        }
}
