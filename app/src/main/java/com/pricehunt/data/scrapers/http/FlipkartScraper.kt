package com.pricehunt.data.scrapers.http

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.scrapers.BaseScraper
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scraper for Flipkart using direct HTTP (faster, more reliable)
 * Falls back to WebView if HTTP fails
 */
@Singleton
class FlipkartScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.FLIPKART
    override val platformColor = Platforms.FLIPKART_COLOR
    override val deliveryTime = "1-2 days"
    override val baseUrl = "https://www.flipkart.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            // Flipkart is an SPA - HTTP only returns JS shell, no products
            // Use WebView directly (longer timeout for JS to load)
            println("$platformName: Using WebView (SPA site)...")
            tryWebViewScraping(query, pincode)
        }
    
    private suspend fun tryHttpScraping(query: String, pincode: String): List<Product> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$baseUrl/search?q=$encodedQuery&marketplace=GROCERY"
            
            println("$platformName: HTTP request to $searchUrl")
            
            val request = okhttp3.Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Cookie", "pincode=$pincode")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                println("$platformName: HTTP ${response.code}")
                return emptyList()
            }
            
            val html = response.body?.string() ?: return emptyList()
            println("$platformName: Got ${html.length} chars via HTTP")
            
            // Use ResilientExtractor which handles dynamic class names
            val products = ResilientExtractor.extractProducts(
                html = html,
                platformName = platformName,
                platformColor = platformColor,
                deliveryTime = deliveryTime,
                baseUrl = baseUrl
            )
            
            if (products.isNotEmpty()) {
                println("$platformName: ✓ Found ${products.size} products via HTTP")
                return fixProductUrls(products, pincode)
            }
            
            println("$platformName: No products found in HTTP response")
            return emptyList()
            
        } catch (e: Exception) {
            println("$platformName: HTTP error - ${e.message}")
            return emptyList()
        }
    }
    
    private suspend fun tryWebViewScraping(query: String, pincode: String): List<Product> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // ROBUST: Try multiple URLs in order of reliability
        val urlsToTry = listOf(
            "https://m.flipkart.com/search?q=$encodedQuery",                    // Mobile site (most reliable)
            "https://www.flipkart.com/search?q=$encodedQuery",                  // Desktop fallback
            "https://m.flipkart.com/search?q=$encodedQuery&otracker=search"     // With tracker
        )
        
        webViewHelper.setLocation(pincode)
        
        for ((index, searchUrl) in urlsToTry.withIndex()) {
            try {
                println("$platformName: Trying URL ${index + 1}/${urlsToTry.size}: $searchUrl")
                
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    timeoutMs = 25_000L,
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: URL ${index + 1} returned empty, trying next...")
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
            }
        }
        
        println("$platformName: ✗ All ${urlsToTry.size} URLs failed")
        return emptyList()
    }
    
    private fun fixProductUrls(products: List<Product>, pincode: String): List<Product> {
        return products.map { product ->
            val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                "$baseUrl/search?q=${URLEncoder.encode(product.name, "UTF-8")}&pincode=$pincode"
            } else {
                "${product.url}${if (product.url.contains("?")) "&" else "?"}pincode=$pincode"
            }
            product.copy(url = finalUrl)
        }
    }
}
