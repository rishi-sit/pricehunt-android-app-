package com.pricehunt.data.scrapers.http

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.scrapers.BaseScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scraper for Amazon Fresh.
 * Uses HTML parsing as Amazon's API requires authentication.
 */
@Singleton
class AmazonFreshScraper @Inject constructor() : BaseScraper() {
    
    override val platformName = Platforms.AMAZON_FRESH
    override val platformColor = Platforms.AMAZON_COLOR
    override val deliveryTime = "2-4 hours"
    override val baseUrl = "https://www.amazon.in"
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            try {
                // Amazon Fresh search URL with pincode
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$baseUrl/s?k=$encodedQuery&i=nowstore"
                
                println("$platformName: Fetching $searchUrl (pincode: $pincode)")
                
                // Amazon uses cookies for delivery location - include pincode
                val deliveryLocationCookie = """{"locationType":"LOCATION_INPUT","zipCode":"$pincode","city":"","countryCode":"IN","deviceType":"web","districtId":""}"""
                val encodedCookie = java.net.URLEncoder.encode(deliveryLocationCookie, "UTF-8")
                
                val request = okhttp3.Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-IN,en;q=0.9")
                    .header("Cookie", "session-token=dummy; ubid-acbin=dummy; i18n-prefs=INR; lc-acbin=en_IN; delivery_location=$encodedCookie")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    println("$platformName: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                
                val html = response.body?.string() ?: return@withContext emptyList()
                println("$platformName: Received HTML (${html.length} bytes)")
                
                val doc = Jsoup.parse(html)
                val results = mutableListOf<Product>()
                
                // Amazon product cards
                val productCards = doc.select("div[data-component-type=s-search-result]")
                println("$platformName: Found ${productCards.size} product cards")
                
                for (card in productCards.take(15)) {
                    try {
                        // Get ASIN first - this is the unique product identifier
                        val asin = card.attr("data-asin")
                        
                        // Name - try multiple locations
                        var name = card.selectFirst("h2.a-size-mini span.a-size-medium")?.text()
                        if (name.isNullOrBlank()) {
                            name = card.selectFirst("h2 a span")?.text()
                        }
                        if (name.isNullOrBlank()) {
                            name = card.selectFirst("h2 span")?.text()
                        }
                        if (name.isNullOrBlank()) {
                            continue
                        }
                        
                        // Price - Amazon uses .a-price structure
                        var priceText = card.selectFirst("span.a-price span.a-offscreen")?.text()
                        if (priceText.isNullOrBlank()) {
                            priceText = card.selectFirst("span.a-price-whole")?.text()
                        }
                        
                        if (priceText.isNullOrBlank()) {
                            println("$platformName: No price for '${name.take(20)}'")
                            continue
                        }
                        
                        val price = parsePrice(priceText)
                        if (price <= 0) continue
                        
                        // Original price
                        val originalPriceText = card.selectFirst("span.a-price.a-text-price span.a-offscreen")?.text()
                        val originalPrice = originalPriceText?.let { parsePrice(it) }?.takeIf { it > price }
                        
                        val discount = originalPrice?.let { 
                            "${((it - price) / it * 100).toInt()}% off" 
                        }
                        
                        // Image
                        val img = card.selectFirst("img.s-image")
                        val imageUrl = img?.attr("src")
                        
                        // URL - Try multiple strategies to get product URL
                        val link = card.selectFirst("h2 a")
                        val href = link?.attr("href") ?: ""
                        
                        // Build product URL with pincode for delivery location
                        val baseProductUrl = when {
                            // Strategy 1: Use ASIN if available
                            asin.isNotBlank() -> {
                                println("$platformName: Using ASIN: $asin")
                                "$baseUrl/dp/$asin"
                            }
                            // Strategy 2: Extract ASIN from href
                            href.contains("/dp/") -> {
                                val dpMatch = Regex("/dp/([A-Z0-9]{10})").find(href)
                                val extractedAsin = dpMatch?.groupValues?.get(1)
                                if (extractedAsin != null) {
                                    println("$platformName: Extracted ASIN from href: $extractedAsin")
                                    "$baseUrl/dp/$extractedAsin"
                                } else {
                                    "$baseUrl${href.split("?").first().split("/ref=").first()}"
                                }
                            }
                            // Strategy 3: Use href directly if it's a product link
                            href.isNotBlank() && !href.contains("/s?") -> {
                                val cleanHref = href.split("?").first().split("/ref=").first()
                                if (cleanHref.startsWith("http")) cleanHref else "$baseUrl$cleanHref"
                            }
                            // Strategy 4: Last resort - try to construct from name (not ideal)
                            else -> {
                                println("$platformName: ⚠ No product URL found for '${name.take(30)}', using search URL")
                                searchUrl
                            }
                        }
                        // Add pincode parameter to help Amazon set delivery location
                        val productUrl = "$baseProductUrl?pincode=$pincode&i=nowstore"
                        
                        println("$platformName: Product URL for '${name.take(30)}' = $productUrl")
                        
                        // Rating
                        val ratingText = card.selectFirst("span.a-icon-alt")?.text()
                        val rating = ratingText?.split(" ")?.firstOrNull()?.toDoubleOrNull()
                        
                        results.add(createProduct(
                            name = name,
                            price = price,
                            originalPrice = originalPrice,
                            discount = discount,
                            url = productUrl,
                            imageUrl = imageUrl,
                            rating = rating
                        ))
                        
                        println("$platformName: ✓ Added ${name.take(30)} at ₹$price -> $productUrl")
                    } catch (e: Exception) {
                        println("$platformName: Error processing card: ${e.message}")
                        continue
                    }
                }
                
                println("$platformName: ✓ Found ${results.size} products")
                results.take(5)
                
            } catch (e: Exception) {
                println("$platformName: ✗ Error - ${e.message}")
                emptyList()
            }
        }
    
    private fun getCoordinates(pincode: String): Pair<String, String> {
        return when (pincode) {
            "560001" -> Pair("12.9716", "77.5946")
            "560081" -> Pair("12.9352", "77.6245")
            "560034" -> Pair("12.9279", "77.6271")
            else -> Pair("12.9716", "77.5946")
        }
    }
}
