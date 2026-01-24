package com.pricehunt.data.scrapers.http

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.scrapers.BaseScraper
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Robust Blinkit Scraper with multiple extraction strategies
 * 
 * Strategy Priority (most stable first):
 * 1. Image alt text (very stable - accessibility requirement)
 * 2. data-* attributes (stable - used for tracking/analytics)
 * 3. Link patterns (/prn/, /prid/)
 * 4. Price proximity to images
 * 5. Generic resilient extraction (fallback)
 * 
 * Robustness features:
 * - Multiple URL attempts
 * - Retry with increased wait times
 * - HTML structure monitoring for debugging
 * - Invalid name filtering (delivery times, UI text)
 */
@Singleton
class BlinkitScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.BLINKIT
    override val platformColor = Platforms.BLINKIT_COLOR
    override val deliveryTime = "10-15 mins"
    override val baseUrl = "https://blinkit.com"
    
    companion object {
        // Selectors to wait for (product cards)
        private val WAIT_SELECTORS = listOf(
            "img[alt]",                    // Product images with alt text
            "[data-testid]",               // Test IDs
            "a[href*='/prn/']",            // Product links
            "a[href*='/prid/']"            // Product ID links
        )
        
        // Invalid product name patterns
        private val INVALID_PATTERNS = listOf(
            Regex("^\\d+(-\\d+)?\\s*(mins?|minutes?|hours?|hr)$", RegexOption.IGNORE_CASE),
            Regex("^(add|buy|view|cart|login|search)\\s", RegexOption.IGNORE_CASE),
            Regex("^(free delivery|express|same day)", RegexOption.IGNORE_CASE)
        )
    }
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$baseUrl/s/?q=$encodedQuery"
            
            webViewHelper.setLocation(pincode)
            
            // RETRY LOGIC: Try up to 3 times with increasing wait
            for (attempt in 1..3) {
                val waitTime = when (attempt) {
                    1 -> 20_000L   // First attempt: 20s
                    2 -> 30_000L   // Second attempt: 30s (more time for React)
                    else -> 40_000L // Third attempt: 40s (slow network)
                }
                
                println("$platformName: Attempt $attempt/3 with ${waitTime/1000}s timeout")
                
                try {
                    val html = webViewHelper.loadAndGetHtml(
                        url = searchUrl,
                        timeoutMs = waitTime,
                        waitForSelector = "img[alt]", // Wait for product images
                        pincode = pincode
                    )
                    
                    if (html.isNullOrBlank()) {
                        println("$platformName: Attempt $attempt - empty HTML")
                        continue
                    }
                    
                    println("$platformName: Got ${html.length} chars")
                    
                    // Log HTML structure for monitoring
                    logHtmlStructure(html)
                    
                    // Try Blinkit-specific extraction first
                    var products = extractBlinkitProducts(html, pincode)
                    
                    // If specific extraction fails, use generic resilient extractor
                    if (products.isEmpty()) {
                        println("$platformName: Blinkit-specific extraction failed, trying generic...")
                        products = ResilientExtractor.extractProducts(
                            html = html,
                            platformName = platformName,
                            platformColor = platformColor,
                            deliveryTime = deliveryTime,
                            baseUrl = baseUrl
                        )
                    }
                    
                    // Filter invalid products
                    val validProducts = products.filter { isValidProduct(it) }
                    
                    if (validProducts.isNotEmpty()) {
                        println("$platformName: ✓ Found ${validProducts.size} products on attempt $attempt")
                        return@withContext fixProductUrls(validProducts, pincode)
                    }
                    
                    println("$platformName: Attempt $attempt - no valid products")
                    
                    // Wait before retry
                    if (attempt < 3) {
                        delay(2000)
                    }
                    
                } catch (e: Exception) {
                    println("$platformName: Attempt $attempt error: ${e.message}")
                }
            }
            
            println("$platformName: ✗ All 3 attempts failed")
            emptyList()
        }
    
    /**
     * Blinkit-specific extraction using stable patterns
     * Priority: img[alt] > data-* attributes > link patterns
     */
    private fun extractBlinkitProducts(html: String, pincode: String): List<Product> {
        val products = mutableListOf<Product>()
        val doc = Jsoup.parse(html)
        val seenNames = mutableSetOf<String>()
        
        println("$platformName: Starting Blinkit-specific extraction...")
        
        // Strategy 1: Extract from product images with alt text
        val productImages = doc.select("img[alt][src*='cdn'], img[alt][src*='blinkit'], img[alt][data-src]")
        println("$platformName: Found ${productImages.size} product images")
        
        for (img in productImages) {
            val alt = img.attr("alt").trim()
            
            // Skip if not a valid product name
            if (alt.length < 5 || alt.length > 120) continue
            if (!isValidProductName(alt)) continue
            if (seenNames.contains(alt.lowercase())) continue
            
            // Find price near this image
            val container = img.parents().firstOrNull { parent ->
                parent.text().contains("₹") && 
                parent.text().length < 500 // Not too large
            }
            
            val price = container?.let { extractPrice(it.text()) } ?: continue
            if (price <= 0 || price > 10000) continue
            
            // Find original price (MRP)
            val originalPrice = container?.let { extractOriginalPrice(it.text(), price) }
            
            // Find product URL
            val productUrl = findProductUrl(img, container)
            
            // Get image URL
            val imageUrl = img.attr("src").ifBlank { img.attr("data-src") }
            
            seenNames.add(alt.lowercase())
            
            products.add(Product(
                name = alt,
                price = price,
                originalPrice = originalPrice,
                imageUrl = imageUrl,
                platform = platformName,
                platformColor = platformColor,
                deliveryTime = deliveryTime,
                url = productUrl ?: "$baseUrl/s/?q=${URLEncoder.encode(alt, "UTF-8")}",
                rating = null,
                discount = originalPrice?.let { 
                    if (it > price) "${((it - price) / it * 100).toInt()}% off" else null 
                },
                available = true
            ))
            
            if (products.size >= 15) break
        }
        
        // Strategy 2: Extract from data-* attributes
        if (products.size < 5) {
            val dataElements = doc.select("[data-product-id], [data-sku], [data-item-id], [data-prid]")
            println("$platformName: Found ${dataElements.size} data-* elements")
            
            for (element in dataElements) {
                val productId = element.attr("data-product-id")
                    .ifBlank { element.attr("data-sku") }
                    .ifBlank { element.attr("data-item-id") }
                    .ifBlank { element.attr("data-prid") }
                
                if (productId.isBlank()) continue
                
                // Find name from img alt or text content
                val name = element.selectFirst("img[alt]")?.attr("alt")
                    ?: element.selectFirst("h3, h4, [class*='name'], [class*='title']")?.text()
                    ?: continue
                
                if (name.length < 5 || !isValidProductName(name)) continue
                if (seenNames.contains(name.lowercase())) continue
                
                val price = extractPrice(element.text()) ?: continue
                if (price <= 0 || price > 10000) continue
                
                val originalPrice = extractOriginalPrice(element.text(), price)
                val imageUrl = element.selectFirst("img")?.let { 
                    it.attr("src").ifBlank { it.attr("data-src") }
                } ?: ""
                
                seenNames.add(name.lowercase())
                
                products.add(Product(
                    name = name.trim(),
                    price = price,
                    originalPrice = originalPrice,
                    imageUrl = imageUrl,
                    platform = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    url = "$baseUrl/prn/$productId",
                    rating = null,
                    discount = originalPrice?.let { 
                        if (it > price) "${((it - price) / it * 100).toInt()}% off" else null 
                    },
                    available = true
                ))
                
                if (products.size >= 15) break
            }
        }
        
        // Strategy 3: Extract from product links
        if (products.size < 5) {
            val productLinks = doc.select("a[href*='/prn/'], a[href*='/prid/'], a[href*='/product/']")
            println("$platformName: Found ${productLinks.size} product links")
            
            for (link in productLinks) {
                val href = link.attr("href")
                val name = link.selectFirst("img[alt]")?.attr("alt")
                    ?: link.text().takeIf { it.length in 5..100 }
                    ?: continue
                
                if (!isValidProductName(name)) continue
                if (seenNames.contains(name.lowercase())) continue
                
                // Find price in parent
                val parent = link.parents().firstOrNull { it.text().contains("₹") }
                val price = parent?.let { extractPrice(it.text()) } ?: continue
                if (price <= 0 || price > 10000) continue
                
                val originalPrice = parent?.let { extractOriginalPrice(it.text(), price) }
                val imageUrl = link.selectFirst("img")?.attr("src") ?: ""
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                
                seenNames.add(name.lowercase())
                
                products.add(Product(
                    name = name.trim(),
                    price = price,
                    originalPrice = originalPrice,
                    imageUrl = imageUrl,
                    platform = platformName,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    url = fullUrl,
                    rating = null,
                    discount = originalPrice?.let { 
                        if (it > price) "${((it - price) / it * 100).toInt()}% off" else null 
                    },
                    available = true
                ))
                
                if (products.size >= 15) break
            }
        }
        
        println("$platformName: Blinkit extraction found ${products.size} products")
        return products
    }
    
    /**
     * Extract price from text, prioritizing actual prices over discounts
     */
    private fun extractPrice(text: String): Double? {
        // Find all prices
        val pricePattern = Regex("""₹\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)""")
        val prices = pricePattern.findAll(text)
            .map { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .filterNotNull()
            .filter { it in 1.0..10000.0 }
            .toList()
        
        if (prices.isEmpty()) return null
        
        // If text contains "save" or "off", the lowest might be a discount
        val lowerText = text.lowercase()
        if (lowerText.contains("save") || lowerText.contains("off")) {
            // Return the second price (actual selling price) if available
            return prices.getOrNull(1) ?: prices.firstOrNull()
        }
        
        // Return lowest valid price
        return prices.minOrNull()
    }
    
    /**
     * Extract original/MRP price (strikethrough price)
     */
    private fun extractOriginalPrice(text: String, sellingPrice: Double): Double? {
        val pricePattern = Regex("""₹\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)""")
        val prices = pricePattern.findAll(text)
            .map { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .filterNotNull()
            .filter { it > sellingPrice && it < sellingPrice * 3 } // MRP should be higher but reasonable
            .toList()
        
        return prices.maxOrNull()
    }
    
    /**
     * Find product URL from element or its parents
     */
    private fun findProductUrl(img: org.jsoup.nodes.Element, container: org.jsoup.nodes.Element?): String? {
        // Check img's parent link
        val parentLink = img.parents().firstOrNull { it.tagName() == "a" }
        if (parentLink != null) {
            val href = parentLink.attr("href")
            if (href.contains("/prn/") || href.contains("/prid/") || href.contains("/product/")) {
                return if (href.startsWith("http")) href else "$baseUrl$href"
            }
        }
        
        // Check container for links
        container?.selectFirst("a[href*='/prn/'], a[href*='/prid/'], a[href*='/product/']")?.let {
            val href = it.attr("href")
            return if (href.startsWith("http")) href else "$baseUrl$href"
        }
        
        return null
    }
    
    /**
     * Validate product name (filter out UI text, delivery times, etc.)
     */
    private fun isValidProductName(name: String): Boolean {
        val trimmed = name.trim()
        
        // Basic length check
        if (trimmed.length < 5 || trimmed.length > 120) return false
        
        // Check against invalid patterns
        if (INVALID_PATTERNS.any { it.matches(trimmed) }) return false
        
        // Must have some letters
        if (!trimmed.any { it.isLetter() }) return false
        
        // Shouldn't be just common UI words
        val lower = trimmed.lowercase()
        val invalidWords = setOf(
            "search", "results", "loading", "add to cart", "buy now", 
            "view", "see more", "show more", "filter", "sort", "menu",
            "mins", "minutes", "hours", "delivery", "free delivery"
        )
        if (invalidWords.any { lower == it }) return false
        
        return true
    }
    
    /**
     * Validate full product (name + price)
     */
    private fun isValidProduct(product: Product): Boolean {
        if (!isValidProductName(product.name)) return false
        if (product.price <= 0 || product.price > 10000) return false
        return true
    }
    
    /**
     * Log HTML structure for monitoring/debugging
     */
    private fun logHtmlStructure(html: String) {
        val doc = Jsoup.parse(html)
        
        // Count key elements for monitoring
        val imgCount = doc.select("img[alt]").size
        val dataProductCount = doc.select("[data-product-id], [data-sku], [data-prid]").size
        val productLinkCount = doc.select("a[href*='/prn/'], a[href*='/prid/']").size
        val priceCount = Regex("₹\\s*\\d+").findAll(html).count()
        
        println("$platformName: HTML Structure Monitor:")
        println("  - Images with alt: $imgCount")
        println("  - data-product elements: $dataProductCount")
        println("  - Product links: $productLinkCount")
        println("  - Price occurrences: $priceCount")
        
        // Alert if structure seems wrong
        if (imgCount == 0 && dataProductCount == 0 && productLinkCount == 0) {
            println("$platformName: ⚠️ WARNING: No product elements found! HTML structure may have changed.")
            // Log first 1000 chars for debugging
            println("$platformName: HTML preview: ${html.take(1000)}")
        }
    }
    
    private fun fixProductUrls(products: List<Product>, pincode: String): List<Product> {
        return products.map { product ->
            val finalUrl = if (product.url == baseUrl || product.url.isBlank()) {
                val productSearchQuery = URLEncoder.encode(product.name, "UTF-8")
                "$baseUrl/s/?q=$productSearchQuery&pincode=$pincode"
            } else {
                val separator = if (product.url.contains("?")) "&" else "?"
                "${product.url}${separator}pincode=$pincode"
            }
            product.copy(url = finalUrl)
        }
    }
}
