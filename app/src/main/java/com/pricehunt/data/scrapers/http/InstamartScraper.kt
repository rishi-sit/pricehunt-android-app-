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
                val searchUrl = "$baseUrl/search?query=$encodedQuery"
                
                println("$platformName: Loading $searchUrl")
                
                webViewHelper.setLocation(pincode)
                
                val html = webViewHelper.loadAndGetHtml(
                    url = searchUrl,
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    println("$platformName: ✗ WebView returned empty HTML")
                    return@withContext emptyList()
                }
                
                println("$platformName: Got ${html.length} chars HTML")
                
                val products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                if (products.isEmpty()) {
                    println("$platformName: ✗ No products extracted")
                    println("$platformName: HTML preview: ${html.take(500)}")
                }
                
                // Fix URLs - add pincode/location to all URLs
                val fixedProducts = products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank() || product.url == "https://www.swiggy.com") {
                        val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                        "$baseUrl/search?query=$productSearchQuery&pincode=$pincode"
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
                emptyList()
            }
        }
}
