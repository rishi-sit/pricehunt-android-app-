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
 * Scraper for BigBasket using WebView with resilient extraction
 */
@Singleton
class BigBasketScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.BIGBASKET
    override val platformColor = Platforms.BIGBASKET_COLOR
    override val deliveryTime = "1-2 hours"
    override val baseUrl = "https://www.bigbasket.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$baseUrl/ps/?q=$encodedQuery"
                
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
                
                // Fix URLs - add pincode to all URLs
                val fixedProducts = products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                        val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                        "$baseUrl/ps/?q=$productSearchQuery&pincode=$pincode"
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
