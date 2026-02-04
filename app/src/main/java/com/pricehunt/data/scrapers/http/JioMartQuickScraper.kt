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
 * Scraper for JioMart Quick (express delivery)
 * Uses same logic as JioMart but with express filter
 */
@Singleton
class JioMartQuickScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.JIOMART_QUICK
    override val platformColor = Platforms.JIOMART_COLOR
    override val deliveryTime = "15-30 mins"
    override val baseUrl = "https://www.jiomart.com"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            val httpProducts = withTimeoutOrNull(4_000L) {
                tryHttpScraping(query, pincode)
            }
            if (!httpProducts.isNullOrEmpty()) {
                return@withContext httpProducts
            }

            // JioMart Quick is an SPA - use WebView directly
            println("$platformName: Using WebView (SPA site)...")
            tryWebViewScraping(query, pincode)
        }
    
    private suspend fun tryHttpScraping(query: String, pincode: String): List<Product> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$baseUrl/search/$encodedQuery?deliveryType=express"
            
            println("$platformName: HTTP request to $searchUrl")
            
            val urls = listOf(
                "$baseUrl/search/$encodedQuery?deliveryType=express",
                "$baseUrl/catalogsearch/result/?q=$encodedQuery&deliveryType=express"
            )

            for (url in urls) {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                    .header("Accept", "text/html")
                    .header("Cookie", "pincode=$pincode")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    println("$platformName: HTTP ${response.code}")
                    continue
                }

                val html = response.body?.string() ?: continue
                println("$platformName: Got ${html.length} chars via HTTP")

                if (html.length < 5000) {
                    println("$platformName: Too short, likely error page")
                    continue
                }

                val doc = org.jsoup.Jsoup.parse(html)
                val productCards = doc.select("div.plp-card-container, li[data-sku], a[href*='/p/']").take(10)
                if (productCards.isEmpty()) {
                    println("$platformName: No product cards found")
                    continue
                }

                val products = mutableListOf<Product>()
                for (card in productCards) {
                    try {
                        val name = card.selectFirst("[class*='name'], [class*='title'], h3, h4")?.text()
                            ?: card.text().take(60)
                        if (name.isBlank() || name.length < 3) continue

                        val priceText = card.selectFirst("[class*='price'], span:contains(₹)")?.text()
                            ?: Regex("""₹\s*(\d+)""").find(card.text())?.value
                        if (priceText.isNullOrBlank()) continue

                        val price = parsePrice(priceText)
                        if (price <= 0) continue

                        var productUrl = card.selectFirst("a[href]")?.attr("href") ?: ""
                        if (productUrl.isNotBlank() && !productUrl.startsWith("http")) {
                            productUrl = "$baseUrl$productUrl"
                        }
                        if (productUrl.isBlank()) {
                            productUrl = "$baseUrl/search/${URLEncoder.encode(name, "UTF-8")}?deliveryType=express&pincode=$pincode"
                        }

                        val finalName = appendQuantityToNameIfMissing(name.trim(), card.text())
                        products.add(Product(
                            name = finalName,
                            price = price,
                            originalPrice = null,
                            imageUrl = card.selectFirst("img")?.attr("src") ?: "",
                            platform = platformName,
                            platformColor = platformColor,
                            deliveryTime = deliveryTime,
                            url = productUrl,
                            rating = null,
                            discount = null,
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
            val searchUrl = "$baseUrl/search/$encodedQuery?deliveryType=express"
            
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
                    "$baseUrl/search/${URLEncoder.encode(product.name, "UTF-8")}?deliveryType=express&pincode=$pincode"
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
