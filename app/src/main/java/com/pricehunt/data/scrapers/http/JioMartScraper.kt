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
 * Scraper for JioMart using direct HTTP (faster)
 * Falls back to WebView if HTTP fails
 */
@Singleton
class JioMartScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.JIOMART
    override val platformColor = Platforms.JIOMART_COLOR
    override val deliveryTime = "1-2 days"
    override val baseUrl = "https://www.jiomart.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            val httpProducts = withTimeoutOrNull(4_000L) {
                tryHttpScraping(query, pincode)
            }
            if (!httpProducts.isNullOrEmpty()) {
                return@withContext httpProducts
            }

            // JioMart is an SPA - use WebView directly
            println("$platformName: Using WebView (SPA site)...")
            tryWebViewScraping(query, pincode)
        }
    
    private suspend fun tryHttpScraping(query: String, pincode: String): List<Product> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Try multiple URL patterns
            val urls = listOf(
                "$baseUrl/search/$encodedQuery",
                "$baseUrl/catalogsearch/result/?q=$encodedQuery"
            )
            
            for (searchUrl in urls) {
                println("$platformName: HTTP request to $searchUrl")
                
                val request = okhttp3.Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-IN,en;q=0.9")
                    .header("Cookie", "pincode=$pincode; city=Bangalore")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    println("$platformName: HTTP ${response.code}")
                    continue
                }
                
                val html = response.body?.string() ?: continue
                println("$platformName: Got ${html.length} chars via HTTP")
                
                if (html.length < 5000 || html.contains("Something went wrong")) {
                    println("$platformName: Error page, trying next URL")
                    continue
                }
                
                // Parse with Jsoup
                val doc = org.jsoup.Jsoup.parse(html)
                
                // JioMart product selectors
                val productCards = doc.select("div.plp-card-container, li[data-sku], div[data-qa='product']").take(10)
                
                if (productCards.isEmpty()) {
                    // Try alternative approach - find product links
                    val productLinks = doc.select("a[href*='/p/'], a[href*='/pd/']").take(10)
                    
                    if (productLinks.isEmpty()) {
                        println("$platformName: No product cards in HTTP response")
                        continue
                    }
                    
                    val products = mutableListOf<Product>()
                    for (link in productLinks) {
                        val name = link.text().takeIf { it.length > 3 } ?: link.attr("title") ?: continue
                        val href = link.attr("href")
                        val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                        
                        // Try to find price nearby
                        val parent = link.parent()
                        val priceText = parent?.selectFirst("[class*='price'], span:contains(₹)")?.text()
                        val price = priceText?.let { parsePrice(it) }?.takeIf { it > 0 } ?: continue
                        
                        products.add(Product(
                            name = name.trim(),
                            price = price,
                            originalPrice = null,
                            imageUrl = parent?.selectFirst("img")?.attr("src") ?: "",
                            platform = platformName,
                            platformColor = platformColor,
                            deliveryTime = deliveryTime,
                            url = "$fullUrl?pincode=$pincode",
                            rating = null,
                            discount = null,
                            available = true
                        ))
                    }
                    
                    if (products.isNotEmpty()) {
                        println("$platformName: ✓ Found ${products.size} products via HTTP (links)")
                        return products
                    }
                    continue
                }
                
                println("$platformName: Found ${productCards.size} cards via HTTP")
                
                val products = mutableListOf<Product>()
                
                for (card in productCards) {
                    try {
                        val name = card.selectFirst("[class*='name'], [class*='title'], h3, h4")?.text()
                            ?: card.selectFirst("a")?.text()
                        
                        if (name.isNullOrBlank() || name.length < 3) continue
                        
                        val priceText = card.selectFirst("[class*='price'], span:contains(₹)")?.text()
                        if (priceText.isNullOrBlank()) continue
                        
                        val price = parsePrice(priceText)
                        if (price <= 0) continue
                        
                        val originalPriceText = card.selectFirst("[class*='strike'], [class*='mrp'], del")?.text()
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
                            productUrl = "$baseUrl/search/${URLEncoder.encode(name, "UTF-8")}?pincode=$pincode"
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
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$baseUrl/search/$encodedQuery"
            
            println("$platformName: Loading $searchUrl")
            webViewHelper.setLocation(pincode)
            
            val html = webViewHelper.loadAndGetHtml(
                url = searchUrl,
                timeoutMs = 9_000L, // 9 seconds
                pincode = pincode
            )
            
            if (html.isNullOrBlank()) {
                println("$platformName: ✗ WebView returned empty")
                return emptyList()
            }
            
            val products = ResilientExtractor.extractProducts(
                html = html,
                platformName = platformName,
                platformColor = platformColor,
                deliveryTime = deliveryTime,
                baseUrl = baseUrl
            )
            
            if (products.isNotEmpty()) {
                println("$platformName: ✓ Found ${products.size} products via WebView")
            } else {
                println("$platformName: ✗ No products found")
            }
            
            products.map { product ->
                val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                    "$baseUrl/search/${URLEncoder.encode(product.name, "UTF-8")}?pincode=$pincode"
                } else {
                    "${product.url}${if (product.url.contains("?")) "&" else "?"}pincode=$pincode"
                }
                product.copy(url = finalUrl)
            }
        } catch (e: Exception) {
            println("$platformName: ✗ WebView error - ${e.message}")
            emptyList()
        }
    }
}
