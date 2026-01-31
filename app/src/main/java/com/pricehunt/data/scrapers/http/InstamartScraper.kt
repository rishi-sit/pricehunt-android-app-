package com.pricehunt.data.scrapers.http

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.scrapers.BaseScraper
import com.pricehunt.data.scrapers.webview.WebViewScraperHelper
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Robust Instamart (Swiggy) Scraper
 * 
 * Strategy Priority (most stable first):
 * 1. __NEXT_DATA__ JSON extraction (Next.js embedded data)
 * 2. Image alt text with cloudinary URLs
 * 3. data-* attributes
 * 4. Price proximity to product elements
 * 5. Generic resilient extraction (fallback)
 * 
 * Robustness features:
 * - Multiple extraction attempts on same HTML
 * - Retry with increased wait times
 * - HTML structure monitoring
 */
@Singleton
class InstamartScraper @Inject constructor(
    private val webViewHelper: WebViewScraperHelper
) : BaseScraper() {
    
    override val platformName = Platforms.INSTAMART
    override val platformColor = Platforms.INSTAMART_COLOR
    override val deliveryTime = "15-30 mins"
    override val baseUrl = "https://www.swiggy.com/instamart"
    
    companion object {
        private val INVALID_PATTERNS = listOf(
            Regex("^\\d+(-\\d+)?\\s*(mins?|minutes?|hours?|hr)$", RegexOption.IGNORE_CASE),
            Regex("^(add|buy|view|cart|login|search)\\s", RegexOption.IGNORE_CASE),
            Regex("^(free delivery|express|same day|swiggy)", RegexOption.IGNORE_CASE)
        )
    }
    
    override suspend fun search(query: String, pincode: String): List<Product> = 
        withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Bangalore coordinates for pincode mapping
            val (lat, lng) = getPincodeCoordinates(pincode)
            
            webViewHelper.setLocation(pincode, lat, lng)

            val httpProducts = withTimeoutOrNull(3_500L) {
                tryHttpScraping(encodedQuery, pincode, lat, lng)
            }
            if (!httpProducts.isNullOrEmpty()) {
                return@withContext fixProductUrls(httpProducts)
            }
            
            // OPTIMIZED: Skip homepage, go directly to search with location cookies
            // WebViewScraperHelper already sets comprehensive Swiggy cookies
            val searchUrl = "$baseUrl/search?custom_back=true&query=$encodedQuery"
            
            // WebView needs adequate time for SPA to load
            for (attempt in 1..1) {  // Single attempt for speed
                val waitTime = 9_000L // 9 seconds
                
                println("$platformName: Attempt $attempt/3 with ${waitTime/1000}s timeout")
                
                try {
                    // Load search page with location cookies already set
                    val html = webViewHelper.loadAndGetHtml(
                        url = searchUrl,
                        timeoutMs = waitTime,
                        waitForSelector = "img[src*='cloudinary'], img[src*='swiggy']",
                        pincode = pincode
                    )
                    
                    if (html.isNullOrBlank()) {
                        println("$platformName: Attempt $attempt - empty HTML")
                        continue
                    }
                    
                    println("$platformName: Got ${html.length} chars")
                    
                    // Check if we're on the right page (skip if location prompt)
                    if (html.contains("detect my location", ignoreCase = true) ||
                        html.contains("enter your delivery location", ignoreCase = true)) {
                        println("$platformName: Location prompt detected, trying next attempt...")
                        continue
                    }
                    
                    // Log HTML structure for monitoring
                    logHtmlStructure(html)
                    
                    // Try multiple extraction strategies on the same HTML
                    var products = tryAllExtractions(html, pincode)
                    
                    // Filter invalid products
                    val validProducts = products.filter { isValidProduct(it) }
                    
                    if (validProducts.isNotEmpty()) {
                        println("$platformName: ✓ Found ${validProducts.size} products on attempt $attempt")
                        return@withContext fixProductUrls(validProducts)
                    }
                    
                    println("$platformName: Attempt $attempt - no valid products")
                    
                } catch (e: Exception) {
                    println("$platformName: Attempt $attempt error: ${e.message}")
                }
            }
            
            println("$platformName: ✗ All attempts failed")
            emptyList()
        }

    private fun tryHttpScraping(
        encodedQuery: String,
        pincode: String,
        lat: String,
        lng: String
    ): List<Product> {
        val urls = listOf(
            "$baseUrl/search?custom_back=true&query=$encodedQuery",
            "$baseUrl/search?query=$encodedQuery"
        )

        for (searchUrl in urls) {
            try {
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", BaseScraper.getRandomUserAgent())
                    .header("Accept", "text/html")
                    .header("Referer", "https://www.swiggy.com/instamart")
                    .header("Cookie", "lat=$lat; lng=$lng; pincode=$pincode")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    println("$platformName: HTTP ${response.code}")
                    continue
                }

                val html = response.body?.string().orEmpty()
                if (html.length < 5000) continue

                if (html.contains("detect my location", ignoreCase = true) ||
                    html.contains("enter your delivery location", ignoreCase = true)) {
                    continue
                }

                val products = tryAllExtractions(html, pincode).filter { isValidProduct(it) }
                if (products.isNotEmpty()) {
                    println("$platformName: ✓ Found ${products.size} products via HTTP")
                    return products
                }
            } catch (e: Exception) {
                println("$platformName: HTTP error - ${e.message}")
            }
        }

        return emptyList()
    }
    
    /**
     * Get coordinates for a pincode (basic mapping for major cities)
     */
    private fun getPincodeCoordinates(pincode: String): Pair<String, String> {
        return when {
            pincode.startsWith("560") -> "12.9716" to "77.5946"   // Bangalore
            pincode.startsWith("110") -> "28.6139" to "77.2090"   // Delhi
            pincode.startsWith("400") -> "19.0760" to "72.8777"   // Mumbai
            pincode.startsWith("600") -> "13.0827" to "80.2707"   // Chennai
            pincode.startsWith("500") -> "17.3850" to "78.4867"   // Hyderabad
            pincode.startsWith("700") -> "22.5726" to "88.3639"   // Kolkata
            pincode.startsWith("411") -> "18.5204" to "73.8567"   // Pune
            else -> "12.9716" to "77.5946" // Default to Bangalore
        }
    }
    
    /**
     * JavaScript to inject location before page loads
     */
    private fun getLocationInjectionScript(lat: String, lng: String, pincode: String): String {
        return """
            (function() {
                // Override geolocation API
                if (navigator.geolocation) {
                    navigator.geolocation.getCurrentPosition = function(success, error, options) {
                        success({
                            coords: {
                                latitude: $lat,
                                longitude: $lng,
                                accuracy: 100,
                                altitude: null,
                                altitudeAccuracy: null,
                                heading: null,
                                speed: null
                            },
                            timestamp: Date.now()
                        });
                    };
                    navigator.geolocation.watchPosition = function(success, error, options) {
                        success({
                            coords: {
                                latitude: $lat,
                                longitude: $lng,
                                accuracy: 100
                            },
                            timestamp: Date.now()
                        });
                        return 1;
                    };
                }
                
                // Set localStorage values Swiggy uses
                try {
                    localStorage.setItem('userLocation', JSON.stringify({
                        lat: $lat,
                        lng: $lng,
                        address: "Bangalore",
                        area: "",
                        city: "Bangalore",
                        pincode: "$pincode"
                    }));
                    localStorage.setItem('swiggy_location_set', 'true');
                    localStorage.setItem('lat', '$lat');
                    localStorage.setItem('lng', '$lng');
                } catch(e) {}
                
                console.log('Swiggy location injected: $lat, $lng');
            })();
        """.trimIndent()
    }
    
    /**
     * Try all extraction strategies on the HTML
     */
    private fun tryAllExtractions(html: String, pincode: String): List<Product> {
        val products = mutableListOf<Product>()
        val seenNames = mutableSetOf<String>()
        
        // Strategy 1: Extract from __NEXT_DATA__ (Next.js apps)
        println("$platformName: Trying __NEXT_DATA__ extraction...")
        val nextDataProducts = extractFromNextData(html)
        nextDataProducts.forEach { 
            if (seenNames.add(it.name.lowercase())) products.add(it)
        }
        if (products.size >= 5) {
            println("$platformName: __NEXT_DATA__ found ${products.size} products")
            return products
        }
        
        // Strategy 2: Extract from Cloudinary images with alt text
        println("$platformName: Trying Cloudinary image extraction...")
        val cloudinaryProducts = extractFromCloudinaryImages(html)
        cloudinaryProducts.forEach { 
            if (seenNames.add(it.name.lowercase())) products.add(it)
        }
        if (products.size >= 5) {
            println("$platformName: Cloudinary extraction found ${products.size} total products")
            return products
        }
        
        // Strategy 3: Extract from any images with alt text
        println("$platformName: Trying general image extraction...")
        val imageProducts = extractFromImages(html)
        imageProducts.forEach { 
            if (seenNames.add(it.name.lowercase())) products.add(it)
        }
        if (products.size >= 5) {
            println("$platformName: Image extraction found ${products.size} total products")
            return products
        }
        
        // Strategy 4: Extract from data-* attributes
        println("$platformName: Trying data-* extraction...")
        val dataProducts = extractFromDataAttributes(html)
        dataProducts.forEach { 
            if (seenNames.add(it.name.lowercase())) products.add(it)
        }
        if (products.size >= 5) {
            println("$platformName: Data attribute extraction found ${products.size} total products")
            return products
        }
        
        // Strategy 5: Generic resilient extractor
        println("$platformName: Trying generic resilient extraction...")
        val resilientProducts = ResilientExtractor.extractProducts(
            html = html,
            platformName = platformName,
            platformColor = platformColor,
            deliveryTime = deliveryTime,
            baseUrl = "https://www.swiggy.com"
        )
        resilientProducts.forEach { 
            if (seenNames.add(it.name.lowercase())) products.add(it)
        }
        
        println("$platformName: Total extraction found ${products.size} products")
        return products
    }
    
    /**
     * Extract from __NEXT_DATA__ script tag (Next.js apps embed data here)
     */
    private fun extractFromNextData(html: String): List<Product> {
        val products = mutableListOf<Product>()
        
        try {
            val nextDataPattern = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val nextDataMatch = nextDataPattern.find(html) ?: return emptyList()
            
            val json = nextDataMatch.groupValues[1]
            println("$platformName: Found __NEXT_DATA__ (${json.length} chars)")
            
            // Extract product-like objects from JSON
            // Look for patterns like "display_name":"Product Name" or "name":"Product Name"
            val namePatterns = listOf(
                Regex(""""display_name"\s*:\s*"([^"]{5,80})""""),
                Regex(""""product_name"\s*:\s*"([^"]{5,80})""""),
                Regex(""""item_name"\s*:\s*"([^"]{5,80})""""),
                Regex(""""name"\s*:\s*"([^"]{5,80})"""")
            )
            
            val pricePatterns = listOf(
                Regex(""""price"\s*:\s*(\d+(?:\.\d+)?)"""),
                Regex(""""selling_price"\s*:\s*(\d+(?:\.\d+)?)"""),
                Regex(""""offer_price"\s*:\s*(\d+(?:\.\d+)?)"""),
                Regex(""""final_price"\s*:\s*(\d+(?:\.\d+)?)""")
            )
            
            val imagePattern = Regex(""""image(?:_url)?"\s*:\s*"([^"]+(?:cloudinary|swiggy)[^"]+)"""")
            
            // Collect all names, prices, images
            val names = mutableListOf<String>()
            val prices = mutableListOf<Double>()
            val images = mutableListOf<String>()
            
            for (pattern in namePatterns) {
                pattern.findAll(json).forEach { match ->
                    val name = match.groupValues[1]
                    if (isValidProductName(name) && !names.contains(name)) {
                        names.add(name)
                    }
                }
            }
            
            for (pattern in pricePatterns) {
                pattern.findAll(json).forEach { match ->
                    val price = match.groupValues[1].toDoubleOrNull() ?: return@forEach
                    if (price in 10.0..5000.0) {
                        prices.add(price)
                    }
                }
            }
            
            imagePattern.findAll(json).forEach { match ->
                images.add(match.groupValues[1])
            }
            
            println("$platformName: __NEXT_DATA__ found ${names.size} names, ${prices.size} prices, ${images.size} images")
            
            // Create products by matching names with prices
            names.take(15).forEachIndexed { index, name ->
                val price = prices.getOrNull(index) ?: return@forEachIndexed
                val imageUrl = images.getOrNull(index) ?: ""
                
                products.add(createProduct(name, price, null, imageUrl))
            }
            
        } catch (e: Exception) {
            println("$platformName: __NEXT_DATA__ error: ${e.message}")
        }
        
        return products
    }
    
    /**
     * Extract from Cloudinary images (Swiggy uses Cloudinary CDN)
     */
    private fun extractFromCloudinaryImages(html: String): List<Product> {
        val products = mutableListOf<Product>()
        val doc = Jsoup.parse(html)
        val seenNames = mutableSetOf<String>()
        
        // Swiggy uses cloudinary for images
        val cloudinaryImages = doc.select("img[src*='cloudinary'], img[src*='swiggy'], img[data-src*='cloudinary'], img[data-src*='swiggy']")
        println("$platformName: Found ${cloudinaryImages.size} Cloudinary/Swiggy images")
        
        for (img in cloudinaryImages) {
            val alt = img.attr("alt").trim()
            if (alt.length < 5 || !isValidProductName(alt)) continue
            if (seenNames.contains(alt.lowercase())) continue
            
            // Find price in parent elements - look for ANY price indicator
            val container = img.parents().firstOrNull { parent ->
                val text = parent.text()
                text.length < 800 && (
                    text.contains("₹") || 
                    text.contains("Rs") ||
                    Regex("""\d{2,4}""").containsMatchIn(text) // Any 2-4 digit number
                )
            }
            
            // If no container with price, try closest parent anyway
            val priceContainer = container ?: img.parents().firstOrNull { it.text().length in 10..500 }
            
            val price = priceContainer?.let { extractPrice(it.text()) }
            if (price == null || price < 5.0) continue
            
            val originalPrice = priceContainer?.let { extractOriginalPrice(it.text(), price) }
            val imageUrl = img.attr("src").ifBlank { img.attr("data-src") }
            
            seenNames.add(alt.lowercase())
            products.add(createProduct(alt, price, originalPrice, imageUrl))
            
            if (products.size >= 15) break
        }
        
        return products
    }
    
    /**
     * Extract from any images with alt text
     */
    private fun extractFromImages(html: String): List<Product> {
        val products = mutableListOf<Product>()
        val doc = Jsoup.parse(html)
        val seenNames = mutableSetOf<String>()
        
        val images = doc.select("img[alt]")
        println("$platformName: Found ${images.size} images with alt")
        
        for (img in images) {
            val alt = img.attr("alt").trim()
            if (alt.length < 5 || alt.length > 100) continue
            if (!isValidProductName(alt)) continue
            if (seenNames.contains(alt.lowercase())) continue
            
            // Find price nearby
            val container = img.parents().firstOrNull { parent ->
                parent.text().contains("₹") && parent.text().length < 600
            } ?: continue
            
            val price = extractPrice(container.text()) ?: continue
            val originalPrice = extractOriginalPrice(container.text(), price)
            val imageUrl = img.attr("src").ifBlank { img.attr("data-src") }
            
            seenNames.add(alt.lowercase())
            products.add(createProduct(alt, price, originalPrice, imageUrl))
            
            if (products.size >= 15) break
        }
        
        return products
    }
    
    /**
     * Extract from data-* attributes
     */
    private fun extractFromDataAttributes(html: String): List<Product> {
        val products = mutableListOf<Product>()
        val doc = Jsoup.parse(html)
        val seenNames = mutableSetOf<String>()
        
        val dataElements = doc.select("[data-testid], [data-item-id], [data-product-id], [data-id]")
        println("$platformName: Found ${dataElements.size} data-* elements")
        
        for (element in dataElements) {
            // Find product name
            val name = element.selectFirst("img[alt]")?.attr("alt")
                ?: element.selectFirst("[class*='name'], [class*='title'], h3, h4")?.text()
                ?: continue
            
            if (name.length < 5 || !isValidProductName(name)) continue
            if (seenNames.contains(name.lowercase())) continue
            
            // Find price
            val price = extractPrice(element.text()) ?: continue
            val originalPrice = extractOriginalPrice(element.text(), price)
            val imageUrl = element.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            } ?: ""
            
            seenNames.add(name.lowercase())
            products.add(createProduct(name, price, originalPrice, imageUrl))
            
            if (products.size >= 15) break
        }
        
        return products
    }
    
    /**
     * Create a product with standard fields
     */
    private fun createProduct(name: String, price: Double, originalPrice: Double?, imageUrl: String): Product {
        return Product(
            name = name.trim(),
            price = price,
            originalPrice = originalPrice,
            imageUrl = imageUrl,
            platform = platformName,
            platformColor = platformColor,
            deliveryTime = deliveryTime,
            url = "$baseUrl/search?query=${URLEncoder.encode(name, "UTF-8")}",
            rating = null,
            discount = originalPrice?.let {
                if (it > price) "${((it - price) / it * 100).toInt()}% off" else null
            },
            available = true
        )
    }
    
    /**
     * Extract price from text - handles multiple formats
     */
    private fun extractPrice(text: String): Double? {
        // Try multiple price patterns
        val pricePatterns = listOf(
            Regex("""₹\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)"""),      // ₹123 or ₹1,234
            Regex("""Rs\.?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)"""),  // Rs 123 or Rs. 123
            Regex("""(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*₹"""),      // 123₹
            Regex("""(?:price|mrp|cost)[^\d]*(\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE), // price: 123
            Regex("""(\d{2,4}(?:\.\d{1,2})?)\s*(?:only|off|\/-|\/-)""", RegexOption.IGNORE_CASE) // 123 only
        )
        
        val allPrices = mutableListOf<Double>()
        for (pattern in pricePatterns) {
            pattern.findAll(text).forEach { match ->
                val price = match.groupValues[1].replace(",", "").toDoubleOrNull()
                if (price != null && price in 5.0..10000.0) {
                    allPrices.add(price)
                }
            }
        }
        
        if (allPrices.isEmpty()) return null
        
        // If text contains "save" or "off", the lowest might be a discount
        val lowerText = text.lowercase()
        if (lowerText.contains("save") || lowerText.contains("off")) {
            return allPrices.getOrNull(1) ?: allPrices.firstOrNull()
        }
        
        return allPrices.minOrNull()
    }
    
    /**
     * Extract original price (MRP)
     */
    private fun extractOriginalPrice(text: String, sellingPrice: Double): Double? {
        val pricePattern = Regex("""₹\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)""")
        val prices = pricePattern.findAll(text)
            .map { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .filterNotNull()
            .filter { it > sellingPrice && it < sellingPrice * 3 }
            .toList()
        
        return prices.maxOrNull()
    }
    
    /**
     * Validate product name
     */
    private fun isValidProductName(name: String): Boolean {
        val trimmed = name.trim()
        
        if (trimmed.length < 5 || trimmed.length > 100) return false
        if (INVALID_PATTERNS.any { it.matches(trimmed) }) return false
        if (!trimmed.any { it.isLetter() }) return false
        
        val lower = trimmed.lowercase()
        val invalidWords = setOf(
            "search", "results", "loading", "add to cart", "buy now",
            "view", "see more", "show more", "filter", "sort", "menu",
            "mins", "minutes", "delivery", "free delivery", "swiggy",
            "instamart", "logo", "icon", "banner"
        )
        if (invalidWords.any { lower == it }) return false
        
        return true
    }
    
    /**
     * Validate full product
     */
    private fun isValidProduct(product: Product): Boolean {
        if (!isValidProductName(product.name)) return false
        if (product.price <= 0 || product.price > 5000) return false
        return true
    }
    
    /**
     * Log HTML structure for monitoring
     */
    private fun logHtmlStructure(html: String) {
        val doc = Jsoup.parse(html)
        
        val nextDataExists = html.contains("__NEXT_DATA__")
        val cloudinaryCount = doc.select("img[src*='cloudinary'], img[src*='swiggy']").size
        val imgAltCount = doc.select("img[alt]").size
        val dataElementCount = doc.select("[data-testid], [data-item-id], [data-product-id]").size
        val priceCount = Regex("₹\\s*\\d+").findAll(html).count()
        
        println("$platformName: HTML Structure Monitor:")
        println("  - __NEXT_DATA__: $nextDataExists")
        println("  - Cloudinary/Swiggy images: $cloudinaryCount")
        println("  - Images with alt: $imgAltCount")
        println("  - data-* elements: $dataElementCount")
        println("  - Price occurrences: $priceCount")
        
        if (cloudinaryCount == 0 && imgAltCount == 0 && priceCount == 0) {
            println("$platformName: ⚠️ WARNING: No product elements found!")
            println("$platformName: HTML preview: ${html.take(1500)}")
        }
    }
    
    private fun fixProductUrls(products: List<Product>): List<Product> {
        return products.map { product ->
            val finalUrl = if (product.url.isBlank() || product.url == "https://www.swiggy.com") {
                "$baseUrl/search?query=${URLEncoder.encode(product.name, "UTF-8")}"
            } else {
                product.url
            }
            product.copy(url = finalUrl)
        }
    }
}
