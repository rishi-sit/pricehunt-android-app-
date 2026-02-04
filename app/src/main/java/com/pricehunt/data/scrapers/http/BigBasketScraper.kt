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
 * Scraper for BigBasket using WebView with custom extraction fallback
 * BigBasket is a Next.js app - products may be in __NEXT_DATA__ or dynamic content
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
                
                // BigBasket is an SPA - use WebView directly
                println("$platformName: Loading $searchUrl (WebView)...")
                webViewHelper.setLocation(pincode)
                val waitSelector = "img[src*='bbassets'], img[src*='bigbasket'], [data-qa*='product'], [data-testid*='product']"
                var html = webViewHelper.loadAndGetHtml(
                    url = searchUrl, 
                    timeoutMs = 12_000L, // 12 seconds
                    waitForSelector = waitSelector,
                    pincode = pincode
                )
                
                if (html.isNullOrBlank()) {
                    // Retry once with a longer wait if the first load was empty
                    println("$platformName: Empty HTML, retrying with longer wait...")
                    html = webViewHelper.loadAndGetHtml(
                        url = searchUrl,
                        timeoutMs = 16_000L,
                        waitForSelector = waitSelector,
                        pincode = pincode
                    )
                }

                if (html.isNullOrBlank()) {
                    println("$platformName: ✗ WebView returned empty")
                    return@withContext emptyList()
                }
                
                println("$platformName: Got ${html.length} chars HTML")
                
                // Try standard extraction first
                var products = ResilientExtractor.extractProducts(
                    html = html,
                    platformName = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    baseUrl = baseUrl
                )
                
                // If standard fails, try custom BigBasket extraction
                if (products.isEmpty()) {
                    println("$platformName: Standard extraction failed, trying custom...")
                    products = extractBigBasketCustom(html, pincode)
                }

                // If still empty, retry once with a longer wait and re-extract
                if (products.isEmpty()) {
                    println("$platformName: Retrying load for extraction...")
                    val retryHtml = webViewHelper.loadAndGetHtml(
                        url = searchUrl,
                        timeoutMs = 18_000L,
                        waitForSelector = waitSelector,
                        pincode = pincode
                    )
                    if (!retryHtml.isNullOrBlank()) {
                        products = ResilientExtractor.extractProducts(
                            html = retryHtml,
                            platformName = platformName,
                            platformColor = platformColor,
                            deliveryTime = deliveryTime,
                            baseUrl = baseUrl
                        )
                        if (products.isEmpty()) {
                            products = extractBigBasketCustom(retryHtml, pincode)
                        }
                    }
                }
                
                if (products.isEmpty()) {
                    println("$platformName: ✗ No products extracted")
                    // Debug: check if there are prices in the HTML
                    val priceCount = Regex("₹\\s*\\d+").findAll(html).count()
                    println("$platformName: Found $priceCount price patterns in HTML")
                    // Debug: Check for location/error indicators
                    val hasLocationPrompt = html.contains("delivery location", ignoreCase = true) ||
                                           html.contains("enter your", ignoreCase = true) ||
                                           html.contains("select location", ignoreCase = true)
                    val hasError = html.contains("something went wrong", ignoreCase = true) ||
                                  html.contains("no results", ignoreCase = true)
                    println("$platformName: Location prompt: $hasLocationPrompt, Error page: $hasError")
                    println("$platformName: HTML preview: ${html.take(500)}")
                    return@withContext emptyList()
                }
                
                println("$platformName: ✓ Found ${products.size} products")
                
                // Fix URLs
                products.map { product ->
                    val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                        "$baseUrl/ps/?q=${URLEncoder.encode(product.name, "UTF-8")}&pincode=$pincode"
                    } else {
                        "${product.url}${if (product.url.contains("?")) "&" else "?"}pincode=$pincode"
                    }
                    product.copy(url = finalUrl)
                }
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                emptyList()
            }
        }
    
    /**
     * Custom extraction for BigBasket
     * BigBasket uses a Next.js app with specific data attributes
     */
    private fun extractBigBasketCustom(html: String, pincode: String): List<Product> {
        val products = mutableListOf<Product>()
        val doc = Jsoup.parse(html)
        
        // Strategy 1: Find __NEXT_DATA__ script
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.html()
        if (nextDataScript != null) {
            println("$platformName: Found __NEXT_DATA__, parsing...")
            
            // Extract products from JSON
            val productPattern = Regex(""""name"\s*:\s*"([^"]+)"[^}]*"sp"\s*:\s*(\d+(?:\.\d+)?)""")
            productPattern.findAll(nextDataScript).take(10).forEach { match ->
                val name = match.groupValues[1]
                val price = match.groupValues[2].toDoubleOrNull() ?: return@forEach
                
                if (name.length > 3 && price in 10.0..5000.0) {
                    val finalName = appendQuantityToNameIfMissing(name.trim(), null)
                    products.add(Product(
                        name = finalName,
                        price = price,
                        originalPrice = null,
                        imageUrl = "",
                        platform = platformName,
                        platformColor = platformColor,
                        deliveryTime = deliveryTime,
                        url = "$baseUrl/ps/?q=${URLEncoder.encode(name, "UTF-8")}",
                        rating = null,
                        discount = null,
                        available = true
                    ))
                }
            }
            
            if (products.isNotEmpty()) {
                println("$platformName: Found ${products.size} from NEXT_DATA")
                return products
            }
        }
        
        // Strategy 2: Find product cards by looking for price+image combinations
        val priceElements = doc.select("*:contains(₹)").filter { el ->
            el.text().matches(Regex(".*₹\\s*\\d+.*")) && el.text().length < 100
        }
        
        println("$platformName: Found ${priceElements.size} price elements")
        
        val processedParents = mutableSetOf<Int>()
        
        for (priceEl in priceElements.take(20)) {
            // Find container with image
            val container = priceEl.parents().firstOrNull { parent ->
                parent.selectFirst("img[src*='bigbasket'], img[src*='bbassets']") != null &&
                parent.text().length > 10 && parent.text().length < 500 &&
                !processedParents.contains(parent.hashCode())
            } ?: continue
            
            processedParents.add(container.hashCode())
            
            // Extract name from container
            val name = container.selectFirst("h3, h4, [class*='name'], [class*='title']")?.text()
                ?: container.selectFirst("a")?.text()
                ?: container.selectFirst("img")?.attr("alt")
            
            if (name.isNullOrBlank() || name.length < 3) continue
            
            // Extract price
            val priceText = Regex("₹\\s*(\\d+)").find(container.text())?.groupValues?.get(1)
            val price = priceText?.toDoubleOrNull()?.takeIf { it in 10.0..5000.0 } ?: continue
            
            // Get image
            val imageUrl = container.selectFirst("img")?.let { 
                it.attr("src").ifBlank { it.attr("data-src") }
            } ?: ""
            
            // Get URL
            var productUrl = container.selectFirst("a[href*='/pd/']")?.attr("href")
                ?: container.selectFirst("a[href]")?.attr("href")
                ?: ""
            
            if (productUrl.isNotBlank() && !productUrl.startsWith("http")) {
                productUrl = "$baseUrl$productUrl"
            }
            
            val finalName = appendQuantityToNameIfMissing(name.trim(), container.text())
            products.add(Product(
                name = finalName,
                price = price,
                originalPrice = null,
                imageUrl = imageUrl,
                platform = platformName,
                platformColor = platformColor,
                deliveryTime = deliveryTime,
                url = productUrl.ifBlank { "$baseUrl/ps/?q=${URLEncoder.encode(name, "UTF-8")}" },
                rating = null,
                discount = null,
                available = true
            ))
            
            if (products.size >= 10) break
        }
        
        return products
    }
}
