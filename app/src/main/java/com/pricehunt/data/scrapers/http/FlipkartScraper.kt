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
 * Scraper for Flipkart using WebView with resilient extraction
 * Uses mobile site for better compatibility and less aggressive bot detection
 */
@Singleton
class FlipkartScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.FLIPKART
    override val platformColor = Platforms.FLIPKART_COLOR
    override val deliveryTime = "1-2 days"
    override val baseUrl = "https://www.flipkart.com"
    
    // Mobile URL is less aggressive with bot detection
    private val mobileBaseUrl = "https://www.flipkart.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                
                // Try grocery/supermart URL first for better results
                val searchUrl = "$mobileBaseUrl/search?q=$encodedQuery&otracker=search&otracker1=search&marketplace=GROCERY&as-show=on&as=off"
                
                println("$platformName: Loading $searchUrl")
                
                webViewHelper.setLocation(pincode)
                
                // Use longer timeout and wait for product cards to load
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    timeoutMs = 20_000L,  // 20 seconds for Flipkart
                    waitForSelector = "[data-id], ._1AtVbE, ._4ddWXP, ._2kHMtA",  // Flipkart product selectors
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: ✗ WebView returned empty HTML")
                    return@withContext emptyList()
                }
                
                println("$platformName: Got ${html.length} chars HTML")
                
                // Check if we got redirected to homepage
                if (html.contains("<title>Online Shopping India") && html.length < 10000) {
                    println("$platformName: ⚠️ Detected homepage redirect, retrying with standard URL...")
                    
                    // Retry with standard search URL
                    val retryUrl = "$baseUrl/search?q=$encodedQuery"
                    val retryHtml = webViewHelper.loadAndGetHtml(
                        url = retryUrl,
                        timeoutMs = 20_000L,
                        pincode = pincode
                    )
                    
                    if (!retryHtml.isNullOrBlank() && retryHtml.length > 10000) {
                        return@withContext extractAndFixProducts(retryHtml, pincode)
                    }
                    
                    println("$platformName: ✗ Retry also failed")
                    return@withContext emptyList()
                }
                
                extractAndFixProducts(html, pincode)
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                emptyList()
            }
        }
    
    private fun extractAndFixProducts(html: String, pincode: String): List<Product> {
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
            return emptyList()
        }
        
        println("$platformName: ✓ Found ${products.size} products")
        
        // Fix URLs - add pincode to all URLs
        return products.map { product ->
            val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                "$baseUrl/search?q=$productSearchQuery&pincode=$pincode"
            } else {
                val separator = if (product.url.contains("?")) "&" else "?"
                "${product.url}${separator}pincode=$pincode"
            }
            product.copy(url = finalUrl)
        }
    }
}
