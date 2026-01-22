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
 * Scraper for Blinkit using WebView with resilient extraction
 * 
 * Uses multiple extraction strategies that don't rely on CSS class names:
 * - JSON-LD Schema.org data
 * - Data attributes (data-testid, etc.)  
 * - ARIA/semantic elements
 * - Link pattern extraction
 * - Content-based detection
 */
@Singleton
class BlinkitScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.BLINKIT
    override val platformColor = Platforms.BLINKIT_COLOR
    override val deliveryTime = "10-15 mins"
    override val baseUrl = "https://blinkit.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$baseUrl/s/?q=$encodedQuery"
                
                println("$platformName: Loading $searchUrl")
                
                // Set location before loading
                webViewHelper.setLocation(pincode)
                
                // Use WebView to load page (handles JavaScript rendering)
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: ✗ WebView returned empty HTML")
                    return@withContext emptyList()
                }
                
                println("$platformName: Got ${html.length} chars HTML")
                
                // Use resilient extractor (tries multiple strategies)
                val products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                if (products.isEmpty()) {
                    println("$platformName: ✗ No products extracted")
                    // Log first 500 chars for debugging
                    println("$platformName: HTML preview: ${html.take(500)}")
                }
                
                // Fix URLs - add pincode to all URLs
                val fixedProducts = products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                        val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                        "$baseUrl/s/?q=$productSearchQuery&pincode=$pincode"
                    } else {
                        // Add pincode to existing URL
                        val separator = if (product.url.contains("?")) "&" else "?"
                        "${product.url}${separator}pincode=$pincode"
                    }
                    println("$platformName: URL for '${product.name}' -> $finalUrl")
                    product.copy(url = finalUrl)
                }
                
                fixedProducts
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                e.printStackTrace()
                emptyList()
            }
        }
}
