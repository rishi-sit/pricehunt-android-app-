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
 * Scraper for JioMart using WebView with resilient extraction
 * JioMart requires specific URL patterns and location settings
 */
@Singleton
class JioMartScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.JIOMART
    override val platformColor = Platforms.JIOMART_COLOR
    override val deliveryTime = "1-2 days"
    override val baseUrl = "https://www.jiomart.com"
    
    // JioMart search URL patterns to try
    private val searchUrlPatterns = listOf(
        { query: String -> "$baseUrl/search/$query" },  // Direct search
        { query: String -> "$baseUrl/catalogsearch/result/?q=$query" },  // Alternative search
        { query: String -> "$baseUrl/search?term=$query" }  // Query param search
    )
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                
                webViewHelper.setLocation(pincode)
                
                // Try multiple URL patterns
                for (urlPattern in searchUrlPatterns) {
                    val searchUrl = urlPattern(encodedQuery)
                    println("$platformName: Trying $searchUrl")
                    
                    val html = webViewHelper.loadAndGetHtml(
                        url = searchUrl,
                        timeoutMs = 15_000L,
                        waitForSelector = ".plp-card-container, .product-card, [data-qa='product']",
                        pincode = pincode
                    )
                    
                    if (html.isNullOrBlank()) {
                        println("$platformName: ✗ Empty HTML from $searchUrl")
                        continue
                    }
                    
                    // Check if we got an error page
                    if (html.contains("Something went wrong") || html.length < 5000) {
                        println("$platformName: ⚠️ Error page from $searchUrl, trying next...")
                        continue
                    }
                    
                    println("$platformName: Got ${html.length} chars HTML")
                    
                    val products = ResilientExtractor.extractProducts(
                        html = html,
                        platformName = platformName,
                        platformColor = platformColor,
                        deliveryTime = deliveryTime,
                        baseUrl = baseUrl
                    )
                    
                    if (products.isNotEmpty()) {
                        println("$platformName: ✓ Found ${products.size} products")
                        return@withContext fixProductUrls(products, pincode)
                    }
                }
                
                println("$platformName: ✗ All URL patterns failed")
                emptyList()
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                emptyList()
            }
        }
    
    private fun fixProductUrls(products: List<Product>, pincode: String): List<Product> {
        return products.map { product ->
            val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                "$baseUrl/search/$productSearchQuery?pincode=$pincode"
            } else {
                val separator = if (product.url.contains("?")) "&" else "?"
                "${product.url}${separator}pincode=$pincode"
            }
            product.copy(url = finalUrl)
        }
    }
}
