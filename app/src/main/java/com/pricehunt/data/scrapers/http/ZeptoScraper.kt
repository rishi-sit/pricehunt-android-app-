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
 * Scraper for Zepto using WebView with resilient extraction
 */
@Singleton
class ZeptoScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.ZEPTO
    override val platformColor = Platforms.ZEPTO_COLOR
    override val deliveryTime = "10-15 mins"
    override val baseUrl = "https://www.zeptonow.com"
    
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
                
                // DEBUG: Log price patterns found in HTML
                val pricePattern = Regex("""(?:₹|Rs\.?)\s*(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?)""")
                val allPrices = pricePattern.findAll(html).map { it.value }.toList()
                println("$platformName: DEBUG - All prices in HTML: ${allPrices.take(20)}")
                
                // DEBUG: Check for per-unit prices
                val perUnitPattern = Regex("""(?:₹|Rs\.?)\s*\d+(?:\.\d+)?[/\s]*(?:100)?[gG]""")
                val perUnitPrices = perUnitPattern.findAll(html).map { it.value }.toList()
                println("$platformName: DEBUG - Per-unit prices found: $perUnitPrices")
                
                val products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                // DEBUG: Log extracted product prices
                products.forEach { p ->
                    println("$platformName: DEBUG - Extracted: '${p.name}' = ₹${p.price} (original: ${p.originalPrice})")
                }
                
                if (products.isEmpty()) {
                    println("$platformName: ✗ No products extracted")
                    println("$platformName: HTML preview: ${html.take(1000)}")
                }
                
                // Fix URLs for Zepto products - add pincode to URL
                val fixedProducts = products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                        // Create a search URL for this specific product with pincode
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
