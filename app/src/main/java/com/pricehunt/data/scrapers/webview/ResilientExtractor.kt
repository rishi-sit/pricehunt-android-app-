package com.pricehunt.data.scrapers.webview

import com.pricehunt.data.model.Product
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Ultra-Resilient Product Extractor
 * 
 * Extracts products using 12+ strategies that DO NOT rely on CSS class names.
 * These strategies target stable HTML attributes that rarely change:
 * 
 * TIER 1 - Most Stable (SEO/Legal requirements):
 * 1. JSON-LD Schema.org - Embedded for SEO, standardized format
 * 2. Microdata (itemprop) - Used by Google for rich snippets
 * 3. Open Graph metadata - Used by social media previews
 * 
 * TIER 2 - Very Stable (Framework/Testing):
 * 4. Next.js __NEXT_DATA__ - React/Next.js page data
 * 5. Data attributes - Used by QA teams for testing
 * 
 * TIER 3 - Stable (Accessibility requirements):
 * 6. ARIA attributes - Required for accessibility compliance
 * 7. Image alt text - Screen reader requirement
 * 8. Semantic HTML - article, section tags
 * 
 * TIER 4 - Moderately Stable (URL/Structure):
 * 9. Link URL patterns - Product URLs are stable
 * 10. Price pattern detection - ₹/Rs patterns
 * 11. Visual proximity - Price near image
 * 12. DOM structure analysis - Repeated similar structures
 */
object ResilientExtractor {
    
    private const val TAG = "ResilientExtractor"
    private val gson = Gson()
    
    // Price pattern regex - handles Indian currency formats
    private val PRICE_REGEX = Regex(
        """(?:₹|Rs\.?|INR|MRP:?\s*₹?)\s*(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )
    
    // Blacklisted names that indicate extraction failed
    private val INVALID_NAMES = setOf(
        "search results", "results", "products", "items", "loading",
        "add to cart", "add", "buy now", "view", "see more", "show more",
        "search", "filter", "sort", "home", "menu", "cart", "login",
        "sign in", "register", "wishlist", "compare", "share", "notify",
        "out of stock", "sold out", "unavailable", "coming soon",
        "view all", "load more", "next", "previous", "back"
    )
    
    // Logging wrapper that works in both Android and JVM unit tests
    private fun logD(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (e: Exception) {
            println("D/$tag: $msg")
        }
    }
    
    private fun logI(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (e: Exception) {
            println("I/$tag: $msg")
        }
    }
    
    private fun logW(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (e: Exception) {
            println("W/$tag: $msg")
        }
    }
    
    private fun logE(tag: String, msg: String) {
        try {
            android.util.Log.e(tag, msg)
        } catch (e: Exception) {
            println("E/$tag: $msg")
        }
    }
    
    /**
     * Main extraction method - tries all strategies in order of reliability
     */
    fun extractProducts(
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        if (html.isBlank()) {
            logW(TAG, "[$platformName] Empty HTML received")
            return emptyList()
        }
        
        val doc = Jsoup.parse(html)
        
        logD(TAG, "[$platformName] Starting extraction from ${html.length} chars")
        logD(TAG, "[$platformName] Page title: ${doc.title()}")
        
        // Build strategies list - platform-specific first, then generic
        val strategies = mutableListOf<Strategy>()
        
        // TIER 0 - Platform-Specific Strategies (try first for known platforms)
        when {
            platformName.contains("Blinkit", ignoreCase = true) -> {
                strategies.add(Strategy("Blinkit-Specific") { 
                    extractBlinkitProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
            platformName.contains("Instamart", ignoreCase = true) -> {
                strategies.add(Strategy("Instamart-Specific") { 
                    extractInstamartProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
            platformName.contains("Zepto", ignoreCase = true) -> {
                strategies.add(Strategy("Zepto-Specific") { 
                    extractZeptoProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
            platformName.contains("BigBasket", ignoreCase = true) -> {
                strategies.add(Strategy("BigBasket-Specific") { 
                    extractBigBasketProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
            platformName.contains("Flipkart", ignoreCase = true) -> {
                strategies.add(Strategy("Flipkart-Specific") { 
                    extractFlipkartProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
            platformName.contains("JioMart", ignoreCase = true) -> {
                strategies.add(Strategy("JioMart-Specific") { 
                    extractJioMartProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
            platformName.contains("Amazon", ignoreCase = true) -> {
                strategies.add(Strategy("Amazon-Specific") { 
                    extractAmazonProducts(doc, html, platformName, platformColor, deliveryTime, baseUrl) 
                })
            }
        }
        
        // TIER 1 - Most Stable (SEO/Legal)
        strategies.addAll(listOf(
            Strategy("JSON-LD Schema") { extractFromJsonLd(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Microdata/itemprop") { extractFromMicrodata(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Open Graph") { extractFromOpenGraph(doc, platformName, platformColor, deliveryTime, baseUrl) },
            
            // TIER 2 - Very Stable (Framework/Testing)
            Strategy("Next.js Data") { extractFromNextData(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Data Attributes") { extractFromDataAttributes(doc, platformName, platformColor, deliveryTime, baseUrl) },
            
            // TIER 3 - Stable (Accessibility)
            Strategy("ARIA/Semantic") { extractFromAriaElements(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Image Alt Text") { extractFromImageAlt(doc, platformName, platformColor, deliveryTime, baseUrl) },
            
            // TIER 4 - Moderately Stable (URL/Structure)
            Strategy("Link Patterns") { extractFromLinks(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Price Proximity") { extractFromPriceProximity(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("DOM Structure") { extractFromDomStructure(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Sibling Analysis") { extractFromSiblingAnalysis(doc, platformName, platformColor, deliveryTime, baseUrl) },
            Strategy("Grid Detection") { extractFromGrid(doc, platformName, platformColor, deliveryTime, baseUrl) },
            
            // TIER 5 - Fallback (Text-based extraction)
            Strategy("Text Pattern") { extractFromTextPatterns(doc, platformName, platformColor, deliveryTime, baseUrl) }
        ))
        
        for (strategy in strategies) {
            try {
                logD(TAG, "[$platformName] Trying: ${strategy.name}")
                val products = strategy.extract()
                
                // Filter out invalid names
                val validProducts = products.filter { isValidProductName(it.name) }
                
                if (validProducts.isNotEmpty()) {
                    logI(TAG, "[$platformName] ✓ ${strategy.name} SUCCESS - ${validProducts.size} products")
                    validProducts.take(3).forEachIndexed { i, p ->
                        logD(TAG, "[$platformName]   ${i+1}. ${p.name.take(50)} - ₹${p.price}")
                    }
                    return validProducts.take(10)
                }
            } catch (e: Exception) {
                logE(TAG, "[$platformName] ${strategy.name} error: ${e.message}")
            }
        }
        
        logW(TAG, "[$platformName] ✗ All ${strategies.size} strategies failed")
        logDebugInfo(doc, platformName)
        return emptyList()
    }
    
    // ==================== TIER 1: SEO/Legal Strategies ====================
    
    /**
     * STRATEGY 1: JSON-LD Schema.org
     * Most reliable - sites embed this for SEO, Google requires it for rich snippets
     */
    private fun extractFromJsonLd(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        val scripts = doc.select("script[type='application/ld+json']")
        
        logD(TAG, "[$platformName] Found ${scripts.size} JSON-LD scripts")
        
        for (script in scripts) {
            try {
                val jsonText = script.html()
                if (jsonText.isBlank()) continue
                
                val json = gson.fromJson(jsonText, JsonObject::class.java)
                extractProductsFromJsonLd(json, results, platformName, platformColor, deliveryTime, baseUrl)
            } catch (e: Exception) {
                // Continue to next script
            }
        }
        
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    private fun extractProductsFromJsonLd(
        json: JsonObject,
        results: MutableList<Product>,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ) {
        // Handle ItemList (search results page)
        if (json.has("@type")) {
            val type = json.get("@type")?.asString
            
            when (type) {
                "ItemList" -> {
                    json.getAsJsonArray("itemListElement")?.forEach { item ->
                        val obj = item.asJsonObject
                        val product = obj.getAsJsonObject("item") ?: obj
                        extractSingleProductFromSchema(product)?.let {
                            results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                        }
                    }
                }
                "Product" -> {
                    extractSingleProductFromSchema(json)?.let {
                        results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                    }
                }
                "WebPage", "SearchResultsPage" -> {
                    // Look for mainEntity or hasPart
                    json.getAsJsonObject("mainEntity")?.let { entity ->
                        extractProductsFromJsonLd(entity, results, platformName, platformColor, deliveryTime, baseUrl)
                    }
                    json.getAsJsonArray("hasPart")?.forEach { part ->
                        if (part.isJsonObject) {
                            extractProductsFromJsonLd(part.asJsonObject, results, platformName, platformColor, deliveryTime, baseUrl)
                        }
                    }
                }
            }
        }
        
        // Handle @graph array
        if (json.has("@graph")) {
            json.getAsJsonArray("@graph")?.forEach { item ->
                if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    if (obj.get("@type")?.asString == "Product") {
                        extractSingleProductFromSchema(obj)?.let {
                            results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                        }
                    }
                }
            }
        }
    }
    
    private fun extractSingleProductFromSchema(json: JsonObject): ProductData? {
        val name = json.get("name")?.asString 
            ?: json.get("title")?.asString 
            ?: return null
        
        var price = 0.0
        var originalPrice: Double? = null
        var imageUrl: String? = null
        var productUrl: String? = null
        
        // Extract price from offers
        if (json.has("offers")) {
            val offers = json.get("offers")
            val offerObj = when {
                offers.isJsonArray -> offers.asJsonArray.firstOrNull()?.asJsonObject
                offers.isJsonObject -> offers.asJsonObject
                else -> null
            }
            offerObj?.let {
                price = it.get("price")?.asDouble 
                    ?: it.get("lowPrice")?.asDouble 
                    ?: 0.0
                originalPrice = it.get("highPrice")?.asDouble
                productUrl = it.get("url")?.asString
            }
        }
        
        // Extract image
        if (json.has("image")) {
            imageUrl = json.get("image")?.let { img ->
                when {
                    img.isJsonArray -> img.asJsonArray.firstOrNull()?.asString
                    img.isJsonPrimitive -> img.asString
                    img.isJsonObject -> img.asJsonObject.get("url")?.asString
                    else -> null
                }
            }
        }
        
        // Extract URL
        if (productUrl == null && json.has("url")) {
            productUrl = json.get("url")?.asString
        }
        
        return if (price > 0 && name.length >= 3) {
            ProductData(name, price, originalPrice, imageUrl, productUrl)
        } else null
    }
    
    /**
     * STRATEGY 2: Microdata (itemprop)
     * Used by Google for rich snippets - very stable
     */
    private fun extractFromMicrodata(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Find product containers with itemtype
        val productContainers = doc.select(
            "[itemtype*='schema.org/Product'], " +
            "[itemtype*='schema.org/IndividualProduct'], " +
            "[itemtype*='schema.org/ProductModel']"
        )
        
        logD(TAG, "[$platformName] Found ${productContainers.size} microdata product containers")
        
        for (container in productContainers.take(15)) {
            try {
                val name = container.selectFirst("[itemprop='name']")?.text()
                    ?: container.selectFirst("[itemprop='title']")?.text()
                
                val priceEl = container.selectFirst("[itemprop='price']")
                val price = priceEl?.attr("content")?.toDoubleOrNull()
                    ?: priceEl?.text()?.let { extractPrice(it) }
                    ?: container.selectFirst("[itemprop='lowPrice']")?.attr("content")?.toDoubleOrNull()
                
                val imageUrl = container.selectFirst("[itemprop='image']")?.let { img ->
                    img.attr("abs:src").takeIf { it.isNotBlank() }
                        ?: img.attr("abs:content").takeIf { it.isNotBlank() }
                        ?: img.attr("abs:href").takeIf { it.isNotBlank() }
                }
                
                val productUrl = container.selectFirst("[itemprop='url']")?.attr("abs:href")
                    ?: container.selectFirst("a[href]")?.attr("abs:href")
                
                val originalPrice = container.selectFirst("[itemprop='highPrice']")?.attr("content")?.toDoubleOrNull()
                
                if (!name.isNullOrBlank() && price != null && price > 0) {
                    results.add(createProduct(
                        ProductData(name, price, originalPrice?.takeIf { it > price }, imageUrl, productUrl),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        // Also try finding itemprop elements directly (not nested in itemtype container)
        if (results.isEmpty()) {
            val nameElements = doc.select("[itemprop='name']")
            val priceElements = doc.select("[itemprop='price'], [itemprop='lowPrice']")
            
            if (nameElements.size >= 3 && priceElements.size >= 3) {
                for (i in 0 until minOf(nameElements.size, priceElements.size, 10)) {
                    val name = nameElements[i].text()
                    val price = priceElements[i].attr("content")?.toDoubleOrNull()
                        ?: extractPrice(priceElements[i].text())
                    
                    if (!name.isNullOrBlank() && price != null && price > 0) {
                        results.add(createProduct(
                            ProductData(name, price, null, null, null),
                            platformName, platformColor, deliveryTime, baseUrl
                        ))
                    }
                }
            }
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * STRATEGY 3: Open Graph metadata
     * Used for social media sharing - stable for product pages
     */
    private fun extractFromOpenGraph(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Check if this is a product page
        val ogType = doc.selectFirst("meta[property='og:type']")?.attr("content")
        if (ogType != "product" && ogType != "og:product") {
            // Not a product page, but might have product:* tags
        }
        
        // Extract product info from OG tags
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("meta[property='product:title']")?.attr("content")
        
        val priceAmount = doc.selectFirst("meta[property='product:price:amount']")?.attr("content")
            ?: doc.selectFirst("meta[property='og:price:amount']")?.attr("content")
        
        val imageUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("meta[property='product:image']")?.attr("content")
        
        val productUrl = doc.selectFirst("meta[property='og:url']")?.attr("content")
        
        val price = priceAmount?.toDoubleOrNull()
        
        if (!title.isNullOrBlank() && price != null && price > 0) {
            results.add(createProduct(
                ProductData(title, price, null, imageUrl, productUrl),
                platformName, platformColor, deliveryTime, baseUrl
            ))
        }
        
        // Also check for multiple products in product:item tags
        val productItems = doc.select("meta[property^='product:item']")
        if (productItems.isNotEmpty()) {
            var currentName: String? = null
            var currentPrice: Double? = null
            var currentImage: String? = null
            
            for (item in productItems) {
                val prop = item.attr("property")
                val content = item.attr("content")
                
                when {
                    prop.contains("name") || prop.contains("title") -> currentName = content
                    prop.contains("price") -> currentPrice = content.toDoubleOrNull()
                    prop.contains("image") -> currentImage = content
                }
                
                if (!currentName.isNullOrBlank() && currentPrice != null && currentPrice > 0) {
                    results.add(createProduct(
                        ProductData(currentName, currentPrice, null, currentImage, null),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                    currentName = null
                    currentPrice = null
                    currentImage = null
                }
            }
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    // ==================== TIER 2: Framework/Testing Strategies ====================
    
    /**
     * STRATEGY 4: Next.js __NEXT_DATA__
     * Modern React sites embed page data in this script
     */
    private fun extractFromNextData(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__") ?: return emptyList()
        
        try {
            val json = gson.fromJson(nextDataScript.html(), JsonObject::class.java)
            val props = json.getAsJsonObject("props")?.getAsJsonObject("pageProps")
            
            if (props != null) {
                findProductsInJsonRecursive(props, results, platformName, platformColor, deliveryTime, baseUrl, 0)
            }
            
            logD(TAG, "[$platformName] Next.js extracted ${results.size} products")
        } catch (e: Exception) {
            logD(TAG, "[$platformName] Next.js parse error: ${e.message}")
        }
        
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    private fun findProductsInJsonRecursive(
        json: JsonObject,
        results: MutableList<Product>,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String,
        depth: Int
    ) {
        if (depth > 8 || results.size >= 15) return
        
        // Common keys that contain product data
        val productKeys = listOf(
            "products", "items", "results", "searchResults", "data", "listings",
            "productList", "widgets", "entities", "objects", "hits", "nodes",
            "edges", "list", "content", "inventory", "catalog"
        )
        
        for ((key, value) in json.entrySet()) {
            when {
                // Check if this key contains products array
                productKeys.any { key.contains(it, ignoreCase = true) } && value.isJsonArray -> {
                    value.asJsonArray.forEach { item ->
                        if (item.isJsonObject) {
                            extractProductFromJsonObject(item.asJsonObject)?.let { data ->
                                results.add(createProduct(data, platformName, platformColor, deliveryTime, baseUrl))
                            }
                        }
                    }
                }
                // Recurse into objects
                value.isJsonObject -> {
                    // Check if this object itself is a product
                    val obj = value.asJsonObject
                    if (looksLikeProduct(obj)) {
                        extractProductFromJsonObject(obj)?.let { data ->
                            results.add(createProduct(data, platformName, platformColor, deliveryTime, baseUrl))
                        }
                    }
                    // Continue recursion
                    findProductsInJsonRecursive(obj, results, platformName, platformColor, deliveryTime, baseUrl, depth + 1)
                }
                // Recurse into arrays
                value.isJsonArray -> {
                    value.asJsonArray.forEach { item ->
                        if (item.isJsonObject) {
                            val obj = item.asJsonObject
                            if (looksLikeProduct(obj)) {
                                extractProductFromJsonObject(obj)?.let { data ->
                                    results.add(createProduct(data, platformName, platformColor, deliveryTime, baseUrl))
                                }
                            }
                            findProductsInJsonRecursive(obj, results, platformName, platformColor, deliveryTime, baseUrl, depth + 1)
                        }
                    }
                }
            }
        }
    }
    
    private fun looksLikeProduct(json: JsonObject): Boolean {
        val hasName = json.has("name") || json.has("title") || json.has("productName") || json.has("display_name")
        val hasPrice = json.has("price") || json.has("sellingPrice") || json.has("mrp") || json.has("selling_price")
        return hasName && hasPrice
    }
    
    private fun extractProductFromJsonObject(json: JsonObject): ProductData? {
        val name = json.get("name")?.asString
            ?: json.get("title")?.asString
            ?: json.get("productName")?.asString
            ?: json.get("display_name")?.asString
            ?: json.get("product_name")?.asString
            ?: return null
        
        val price = json.get("price")?.asDouble
            ?: json.get("sellingPrice")?.asDouble
            ?: json.get("selling_price")?.asDouble
            ?: json.get("salePrice")?.asDouble
            ?: json.get("sale_price")?.asDouble
            ?: json.get("discountedPrice")?.asDouble
            ?: json.get("finalPrice")?.asDouble
            ?: json.get("mrp")?.asDouble
            ?: return null
        
        if (price <= 0) return null
        
        val originalPrice = json.get("mrp")?.asDouble
            ?: json.get("originalPrice")?.asDouble
            ?: json.get("original_price")?.asDouble
            ?: json.get("listPrice")?.asDouble
        
        val imageUrl = json.get("image")?.asString
            ?: json.get("imageUrl")?.asString
            ?: json.get("image_url")?.asString
            ?: json.get("thumbnail")?.asString
            ?: json.get("thumbnailUrl")?.asString
            ?: json.get("product_image")?.asString
            ?: json.getAsJsonArray("images")?.firstOrNull()?.asString
        
        val productUrl = json.get("url")?.asString
            ?: json.get("productUrl")?.asString
            ?: json.get("product_url")?.asString
            ?: json.get("link")?.asString
            ?: json.get("pdpLink")?.asString
        
        return ProductData(name, price, originalPrice?.takeIf { it > price }, imageUrl, productUrl)
    }
    
    /**
     * STRATEGY 5: Data Attributes
     * QA teams use these for testing - they're stable
     */
    private fun extractFromDataAttributes(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Common data attribute patterns used for testing
        val selectors = listOf(
            "[data-testid*='product' i]",
            "[data-testid*='item' i]",
            "[data-testid*='card' i]",
            "[data-test*='product' i]",
            "[data-qa*='product' i]",
            "[data-cy*='product' i]",
            "[data-product-id]",
            "[data-item-id]",
            "[data-sku]",
            "[data-product]",
            "[data-entity-type='PRODUCT']",
            "[data-component*='product' i]",
            "[data-widget-type*='product' i]",
            "[data-automation*='product' i]"
        )
        
        for (selector in selectors) {
            try {
                val elements = doc.select(selector)
                if (elements.size >= 2) {
                    logD(TAG, "[$platformName] Data attr '$selector' found ${elements.size} elements")
                    
                    for (element in elements.take(12)) {
                        extractProductFromElement(element)?.let {
                            results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                        }
                    }
                    
                    if (results.isNotEmpty()) break
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    // ==================== TIER 3: Accessibility Strategies ====================
    
    /**
     * STRATEGY 6: ARIA and Semantic Elements
     * Required for accessibility compliance - legally mandated in many regions
     */
    private fun extractFromAriaElements(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        val selectors = listOf(
            "[role='listitem']",
            "[role='article']",
            "[role='gridcell']",
            "[role='option']",
            "article",
            "section[aria-label*='product' i]",
            "div[aria-label*='product' i]",
            "[aria-labelledby]"
        )
        
        for (selector in selectors) {
            try {
                val elements = doc.select(selector)
                // Need at least 3 to be confident these are product cards
                if (elements.size >= 3) {
                    logD(TAG, "[$platformName] ARIA '$selector' found ${elements.size} elements")
                    
                    for (element in elements.take(12)) {
                        extractProductFromElement(element)?.let {
                            results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                        }
                    }
                    
                    if (results.isNotEmpty()) break
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * STRATEGY 7: Image Alt Text Extraction
     * Alt text is required for accessibility - usually contains product name
     */
    private fun extractFromImageAlt(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Find all images with meaningful alt text
        val images = doc.select("img[alt]").filter { img ->
            val alt = img.attr("alt")
            alt.length >= 5 && 
            alt.length <= 150 && 
            !alt.contains("logo", ignoreCase = true) &&
            !alt.contains("banner", ignoreCase = true) &&
            !alt.contains("icon", ignoreCase = true) &&
            isValidProductName(alt)
        }
        
        logD(TAG, "[$platformName] Found ${images.size} images with valid alt text")
        
        for (img in images.take(15)) {
            val alt = img.attr("alt").trim()
            
            // Find price near this image
            val container = findProductContainer(img)
            if (container != null) {
                val price = extractPriceFromElement(container)
                
                if (price != null && price > 0) {
                    val imageUrl = img.attr("abs:src").takeIf { it.isNotBlank() }
                        ?: img.attr("abs:data-src").takeIf { it.isNotBlank() }
                    
                    // Find product URL - prioritize product-specific link patterns
                    val productUrl = findBestProductUrl(container, platformName)
                    val originalPrice = extractOriginalPriceFromElement(container, price)
                    
                    results.add(createProduct(
                        ProductData(alt, price, originalPrice, imageUrl, productUrl),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * Find the best product URL from a container, prioritizing platform-specific patterns
     */
    private fun findBestProductUrl(container: Element, platformName: String): String? {
        // Platform-specific URL patterns (most reliable)
        val platformPatterns = when {
            platformName.contains("Zepto", ignoreCase = true) -> listOf("/pn/", "/product/")
            platformName.contains("Blinkit", ignoreCase = true) -> listOf("/prn/", "/product/")
            platformName.contains("BigBasket", ignoreCase = true) -> listOf("/pd/", "/product/")
            platformName.contains("Flipkart", ignoreCase = true) -> listOf("/p/", "pid=")
            platformName.contains("Amazon", ignoreCase = true) -> listOf("/dp/", "/gp/product/")
            platformName.contains("JioMart", ignoreCase = true) -> listOf("/buy/", "/p/")
            platformName.contains("Instamart", ignoreCase = true) -> listOf("/product/", "/item/")
            else -> listOf("/product/", "/p/", "/item/")
        }
        
        // First try to find links matching platform patterns
        for (pattern in platformPatterns) {
            val link = container.selectFirst("a[href*='$pattern']")
            if (link != null) {
                val href = link.attr("abs:href")
                if (href.isNotBlank()) {
                    return href
                }
            }
        }
        
        // Fallback: find any link that looks like a product URL
        val links = container.select("a[href]")
        for (link in links) {
            val href = link.attr("abs:href")
            // Skip non-product links
            if (href.contains("/search") || 
                href.contains("/category") || 
                href.contains("/cart") ||
                href.contains("/login") ||
                href.contains("#") ||
                href.endsWith("/")) continue
            
            // Prefer links with product-like paths
            if (href.contains("/p") || href.contains("product") || href.contains("item")) {
                return href
            }
        }
        
        // Last resort: return first link
        return container.selectFirst("a[href]")?.attr("abs:href")
    }
    
    // ==================== TIER 4: URL/Structure Strategies ====================
    
    /**
     * STRATEGY 8: Link URL Pattern Extraction
     * Product URLs have stable patterns that rarely change
     */
    private fun extractFromLinks(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Platform-specific and generic product URL patterns
        val linkPatterns = listOf(
            "a[href*='/prn/']",       // Blinkit
            "a[href*='/pn/']",        // Zepto  
            "a[href*='/pd/']",        // BigBasket
            "a[href*='/p/']",         // Generic
            "a[href*='/product/']",   // Generic
            "a[href*='/item/']",      // Generic
            "a[href*='/dp/']",        // Amazon
            "a[href*='pid=']",        // Flipkart
            "a[href*='/buy/']",       // JioMart
            "a[href*='/grocery/']",   // Grocery
            "a[href*='productId=']",  // Generic
            "a[href*='sku=']"         // Generic
        )
        
        val seenUrls = mutableSetOf<String>()
        
        for (pattern in linkPatterns) {
            val links = doc.select(pattern)
            if (links.isNotEmpty()) {
                logD(TAG, "[$platformName] Link pattern '$pattern' found ${links.size} links")
            }
            
            for (link in links) {
                val href = link.attr("abs:href")
                if (href.isBlank() || seenUrls.contains(href)) continue
                seenUrls.add(href)
                
                val container = findProductContainer(link)
                val element = container ?: link
                
                extractProductFromElement(element)?.let { data ->
                    results.add(createProduct(data.copy(url = href), platformName, platformColor, deliveryTime, baseUrl))
                }
                
                if (results.size >= 12) break
            }
            
            if (results.size >= 3) break
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * STRATEGY 9: Price Proximity Analysis
     * Find products by locating prices and then finding nearby product info
     */
    private fun extractFromPriceProximity(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Find all elements containing price patterns
        val body = doc.body() ?: return emptyList()
        
        // Find elements with price that also have images nearby
        val containers = body.select("div:has(img), li:has(img), article:has(img), section:has(img)")
            .filter { container ->
                val text = container.text()
                PRICE_REGEX.containsMatchIn(text) && 
                container.select("img").isNotEmpty() &&
                container.text().length < 800 // Not too large
            }
            .sortedBy { it.text().length } // Prefer smaller containers
        
        logD(TAG, "[$platformName] Price proximity found ${containers.size} candidates")
        
        for (container in containers.take(20)) {
            extractProductFromElement(container)?.let {
                results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
            }
            if (results.size >= 10) break
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * STRATEGY 10: DOM Structure Analysis
     * Find repeated similar structures that likely represent products
     */
    private fun extractFromDomStructure(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Find parent elements with multiple similar children
        val potentialGrids = doc.select("ul, ol, div, section")
            .filter { parent ->
                val children = parent.children()
                if (children.size < 3) return@filter false
                
                // Check if children have similar structure (same tag, has image and price)
                val firstChildTag = children.firstOrNull()?.tagName()
                val similarChildren = children.count { child ->
                    child.tagName() == firstChildTag &&
                    child.select("img").isNotEmpty() &&
                    PRICE_REGEX.containsMatchIn(child.text())
                }
                
                similarChildren >= 3
            }
        
        logD(TAG, "[$platformName] DOM structure found ${potentialGrids.size} grids")
        
        for (grid in potentialGrids.take(3)) {
            val children = grid.children()
            for (child in children.take(12)) {
                if (child.select("img").isNotEmpty() && PRICE_REGEX.containsMatchIn(child.text())) {
                    extractProductFromElement(child)?.let {
                        results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                    }
                }
                if (results.size >= 10) break
            }
            if (results.size >= 5) break
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * STRATEGY 11: Sibling Analysis
     * Analyze sibling relationships to find product cards
     */
    private fun extractFromSiblingAnalysis(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        // Find all prices and check their siblings/ancestors for product info
        val priceElements = doc.select("span, div, p").filter { el ->
            val text = el.ownText()
            PRICE_REGEX.containsMatchIn(text) && text.length < 30
        }
        
        logD(TAG, "[$platformName] Sibling analysis found ${priceElements.size} price elements")
        
        val processedContainers = mutableSetOf<Element>()
        
        for (priceEl in priceElements.take(30)) {
            // Go up to find a container
            var container = priceEl.parent()
            repeat(4) {
                if (container != null && 
                    container.select("img").isEmpty() &&
                    container.parent() != null) {
                    container = container.parent()
                }
            }
            
            if (container != null && 
                container !in processedContainers &&
                container.select("img").isNotEmpty()) {
                
                processedContainers.add(container)
                
                extractProductFromElement(container)?.let {
                    results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                }
            }
            
            if (results.size >= 10) break
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * STRATEGY 12: Grid Detection (Fallback)
     * Generic grid/list structure detection
     */
    private fun extractFromGrid(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        val gridSelectors = listOf(
            "ul:has(> li > a > img)",
            "ul:has(> li img)",
            "ol:has(> li img)",
            "div[class*='grid' i]:has(img)",
            "div[class*='list' i]:has(img)",
            "div[class*='row' i]:has(img)",
            "section:has(> div > a > img)"
        )
        
        for (selector in gridSelectors) {
            try {
                val grids = doc.select(selector)
                for (grid in grids) {
                    val items = grid.children()
                    if (items.size >= 3) {
                        logD(TAG, "[$platformName] Grid '$selector' with ${items.size} items")
                        
                        for (item in items.take(12)) {
                            extractProductFromElement(item)?.let {
                                results.add(createProduct(it, platformName, platformColor, deliveryTime, baseUrl))
                            }
                        }
                        if (results.isNotEmpty()) return results.distinctBy { it.name.lowercase() }
                    }
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return results.distinctBy { it.name.lowercase() }
    }
    
    // ==================== Platform-Specific Extraction ====================
    
    /**
     * BLINKIT: Uses custom React components with lazy-loaded images
     * Product URLs follow pattern: /prn/{product-slug}/prid/{product-id}
     */
    private fun extractBlinkitProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying Blinkit-specific extraction")
        
        // Blinkit URL patterns - multiple possible formats
        val blinkitUrlPatterns = listOf(
            "a[href*='/prn/']",          // /prn/product-name/prid/12345
            "a[href*='/prid/']",         // Direct product ID
            "a[href*='/product/']",      // Generic product path
            "a[href*='blinkit.com/p']"   // Any product path
        )
        
        val seenUrls = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        
        // Strategy 1: Find product links with Blinkit-specific patterns
        for (pattern in blinkitUrlPatterns) {
            val productLinks = doc.select(pattern)
            logD(TAG, "[$platformName] Pattern '$pattern' found ${productLinks.size} links")
            
            for (link in productLinks.take(15)) {
                var href = link.attr("href")
                if (href.isBlank()) continue
                
                // Convert to absolute URL
                if (!href.startsWith("http")) {
                    href = if (href.startsWith("/")) {
                        "https://blinkit.com$href"
                    } else {
                        "https://blinkit.com/$href"
                    }
                }
                
                // Skip search/category URLs - IMPORTANT
                if (href.contains("/s?") || href.contains("/search") || 
                    href.contains("/category") || href.contains("/c/")) {
                    logD(TAG, "[$platformName] Skipping search/category URL: $href")
                    continue
                }
                
                if (seenUrls.contains(href)) continue
                seenUrls.add(href)
                
                val container = findProductContainer(link) ?: link
                
                // Extract product info
                val img = container.selectFirst("img[alt], img[src], img[data-src]")
                var imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() && !it.contains("placeholder") }
                    ?: img?.attr("abs:data-src")
                
                // Try background-image if no img src
                if (imageUrl == null) {
                    val style = container.attr("style")
                    if (style.contains("background-image")) {
                        val urlMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)
                        imageUrl = urlMatch?.groupValues?.get(1)
                    }
                }
                
                val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                    ?: container.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                        ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                
                if (name == null || seenNames.contains(name.lowercase())) continue
                seenNames.add(name.lowercase())
                
                // Use smart price extraction to filter out savings amounts
                val price = extractSmartPrice(container, platformName)
                val originalPrice = if (price != null) extractSmartOriginalPrice(container, price, platformName) else null
                
                if (price != null && price > 0) {
                    logD(TAG, "[$platformName] ✓ Extracted: $name = ₹$price -> $href")
                    results.add(createProduct(
                        ProductData(name, price, originalPrice, imageUrl, href),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
            
            if (results.isNotEmpty()) break
        }
        
        // Strategy 2: Look for product containers with data attributes
        if (results.isEmpty()) {
            val productContainers = doc.select("div[data-sku], div[data-product], [data-testid*='product'], [data-product-id]")
            logD(TAG, "[$platformName] Found ${productContainers.size} Blinkit containers with data attributes")
            
            for (container in productContainers.take(15)) {
                val element = findProductContainer(container) ?: container
                
                // Try to find a valid product URL (not search)
                var productUrl: String? = null
                for (pattern in blinkitUrlPatterns) {
                    element.selectFirst(pattern)?.attr("href")?.let { href ->
                        val fullUrl = if (href.startsWith("http")) href else "https://blinkit.com$href"
                        if (!fullUrl.contains("/s?") && !fullUrl.contains("/search") && 
                            !fullUrl.contains("/category")) {
                            productUrl = fullUrl
                        }
                    }
                    if (productUrl != null) break
                }
                
                // Try product ID to construct URL
                if (productUrl == null) {
                    val productId = container.attr("data-sku").takeIf { it.isNotBlank() }
                        ?: container.attr("data-product-id").takeIf { it.isNotBlank() }
                        ?: container.attr("data-id").takeIf { it.isNotBlank() }
                    if (productId != null) {
                        productUrl = "https://blinkit.com/prn/product/prid/$productId"
                        logD(TAG, "[$platformName] Constructed URL from product ID: $productUrl")
                    }
                }
                
                // Use smart price extraction
                val img = element.selectFirst("img[alt], img[src]")
                val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                    ?: element.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                        ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                
                if (name != null && !seenNames.contains(name.lowercase())) {
                    val price = extractSmartPrice(element, platformName)
                    val originalPrice = if (price != null) extractSmartOriginalPrice(element, price, platformName) else null
                    val imageUrl = img?.attr("abs:src")
                    
                    if (price != null && price > 0) {
                        seenNames.add(name.lowercase())
                        results.add(createProduct(
                            ProductData(name, price, originalPrice, imageUrl, productUrl),
                            platformName, platformColor, deliveryTime, baseUrl
                        ))
                    }
                }
            }
        }
        
        logD(TAG, "[$platformName] Blinkit-specific found ${results.size} products")
        return results.take(10)
    }
    
    /**
     * INSTAMART (Swiggy): Heavy React SPA with lazy loading
     * Product URLs follow pattern: /instamart/item/{item-id}
     */
    private fun extractInstamartProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying Instamart-specific extraction")
        
        // Strategy 1: Find product links first
        val productLinks = doc.select("a[href*='/item/'], a[href*='/product/'], a[href*='instamart']")
        logD(TAG, "[$platformName] Found ${productLinks.size} Instamart product links")
        
        val seenUrls = mutableSetOf<String>()
        
        for (link in productLinks.take(15)) {
            val href = link.attr("abs:href")
            if (href.isBlank() || seenUrls.contains(href)) continue
            if (href.contains("/search") || href.contains("/category")) continue
            seenUrls.add(href)
            
            val container = findProductContainer(link) ?: link
            
            val img = container.selectFirst("img[alt], img[src]")
            val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                ?: container.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                    ?.takeIf { it.length in 5..120 && isValidProductName(it) }
            
            // Use smart price extraction to filter out savings amounts
            val price = extractSmartPrice(container, platformName)
            val originalPrice = if (price != null) extractSmartOriginalPrice(container, price, platformName) else null
            val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            
            if (name != null && price != null && price > 0) {
                results.add(createProduct(
                    ProductData(name, price, originalPrice, imageUrl, href),
                    platformName, platformColor, deliveryTime, baseUrl
                ))
                logD(TAG, "[$platformName] Extracted: $name = ₹$price -> $href")
            }
        }
        
        // Strategy 2: Look for data-testid elements (Swiggy uses these)
        if (results.isEmpty()) {
            val testIdElements = doc.select("[data-testid]")
            logD(TAG, "[$platformName] Found ${testIdElements.size} data-testid elements")
            
            testIdElements.filter { el ->
                val testId = el.attr("data-testid")
                testId.contains("product", ignoreCase = true) || 
                testId.contains("item", ignoreCase = true) ||
                testId.contains("card", ignoreCase = true)
            }.take(15).forEach { element ->
                val productUrl = element.selectFirst("a[href*='/item/'], a[href]")?.attr("abs:href")
                val img2 = element.selectFirst("img[alt], img[src]")
                val name2 = img2?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                    ?: element.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                        ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                val price2 = extractSmartPrice(element, platformName)
                val originalPrice2 = if (price2 != null) extractSmartOriginalPrice(element, price2, platformName) else null
                val imageUrl2 = img2?.attr("abs:src")
                
                if (name2 != null && price2 != null && price2 > 0) {
                    results.add(createProduct(
                        ProductData(name2, price2, originalPrice2, imageUrl2, productUrl),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
        }
        
        // Strategy 3: Look for specific Swiggy class patterns
        if (results.isEmpty()) {
            val swiggySelectors = listOf(
                "[class*='ProductCard']",
                "[class*='product-card']",
                "[class*='ItemCard']"
            )
            
            for (selector in swiggySelectors) {
                try {
                    val elements = doc.select(selector)
                    if (elements.size >= 2) {
                        logD(TAG, "[$platformName] Swiggy selector '$selector' found ${elements.size} elements")
                        elements.take(12).forEach { element ->
                            val productUrl = element.selectFirst("a[href]")?.attr("abs:href")
                            extractProductFromElement(element)?.let { data ->
                                results.add(createProduct(
                                    data.copy(url = productUrl ?: data.url),
                                    platformName, platformColor, deliveryTime, baseUrl
                                ))
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                } catch (e: Exception) { }
            }
        }
        
        logD(TAG, "[$platformName] Instamart-specific found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    /**
     * ZEPTO: Uses Next.js with optimized image loading
     * Product URLs follow pattern: /pn/{product-slug}/pvid/{variant-id}
     */
    private fun extractZeptoProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying Zepto-specific extraction")
        
        // Zepto URL patterns (check multiple patterns)
        val zeptoUrlPatterns = listOf(
            "a[href*='/pn/']",           // /pn/product-name/pvid/12345
            "a[href*='/prn/']",          // Alternate pattern
            "a[href*='/product/']",      // Generic product pattern
            "a[href*='zeptonow.com/p']"  // Any product path
        )
        
        val seenUrls = mutableSetOf<String>()
        
        // Strategy 1: Find product links with Zepto-specific patterns
        for (pattern in zeptoUrlPatterns) {
            val productLinks = doc.select(pattern)
            logD(TAG, "[$platformName] Pattern '$pattern' found ${productLinks.size} links")
            
            for (link in productLinks.take(15)) {
                var href = link.attr("href")
                if (href.isBlank()) continue
                
                // Convert to absolute URL
                if (!href.startsWith("http")) {
                    href = if (href.startsWith("/")) {
                        "https://www.zeptonow.com$href"
                    } else {
                        "https://www.zeptonow.com/$href"
                    }
                }
                
                // Skip search/category URLs
                if (href.contains("/search") || href.contains("/category") || href.contains("/c/")) continue
                if (seenUrls.contains(href)) continue
                seenUrls.add(href)
                
                // Find the product container
                val container = findProductContainer(link) ?: link
                
                // Extract product info
                val img = container.selectFirst("img[alt], img[src]")
                val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                    ?: container.selectFirst("h2, h3, h4, [class*='name'], [class*='title']")?.text()
                        ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                
                // Use Zepto-specific price extraction
                val price = extractZeptoPrice(container)
                val originalPrice = if (price != null) extractZeptoOriginalPrice(container, price) else null
                val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() && !it.contains("placeholder") }
                    ?: img?.attr("abs:data-src")
                
                if (name != null && price != null && price > 0) {
                    logD(TAG, "[$platformName] ✓ Extracted: $name = ₹$price (MRP: ${originalPrice ?: "N/A"}) -> $href")
                    results.add(createProduct(
                        ProductData(name, price, originalPrice, imageUrl, href),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
            
            if (results.isNotEmpty()) break
        }
        
        // Strategy 2: If no links found, try data attributes and construct URL from product ID
        if (results.isEmpty()) {
            val zeptoSelectors = listOf(
                "[data-testid*='product']",
                "[data-product-id]",
                "[class*='ProductCard']",
                "[class*='product-card']"
            )
            
            for (selector in zeptoSelectors) {
                try {
                    val elements = doc.select(selector)
                    if (elements.size >= 2) {
                        logD(TAG, "[$platformName] Zepto selector '$selector' found ${elements.size} elements")
                        elements.take(12).forEach { element ->
                            // Try to find any product link
                            var productUrl = element.selectFirst("a[href*='/pn/'], a[href*='/prn/'], a[href*='/product/']")?.attr("href")
                            
                            // Convert to absolute if relative
                            if (productUrl != null && !productUrl.startsWith("http")) {
                                productUrl = "https://www.zeptonow.com$productUrl"
                            }
                            
                            // Try to get product ID and construct URL
                            if (productUrl == null) {
                                val productId = element.attr("data-product-id").takeIf { it.isNotBlank() }
                                    ?: element.attr("data-id").takeIf { it.isNotBlank() }
                                if (productId != null) {
                                    productUrl = "https://www.zeptonow.com/pn/product/pvid/$productId"
                                }
                            }
                            
                            // If still no URL, try any link but validate it
                            if (productUrl == null) {
                                val anyLink = element.selectFirst("a[href]")?.attr("href")
                                if (anyLink != null && !anyLink.contains("/search") && !anyLink.contains("/category")) {
                                    productUrl = if (anyLink.startsWith("http")) anyLink else "https://www.zeptonow.com$anyLink"
                                }
                            }
                            
                            // Use Zepto-specific price extraction
                            val img = element.selectFirst("img[alt], img[src]")
                            val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                                ?: element.selectFirst("h2, h3, h4, [class*='name'], [class*='title']")?.text()
                                    ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                            
                            val price = extractZeptoPrice(element)
                            val originalPrice = if (price != null) extractZeptoOriginalPrice(element, price) else null
                            val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
                            
                            if (name != null && price != null && price > 0) {
                                val finalUrl = productUrl
                                if (finalUrl != null && finalUrl.contains("zeptonow.com") && !finalUrl.contains("/search")) {
                                    results.add(createProduct(
                                        ProductData(name, price, originalPrice, imageUrl, finalUrl),
                                        platformName, platformColor, deliveryTime, baseUrl
                                    ))
                                    logD(TAG, "[$platformName] ✓ Extracted via selector: $name = ₹$price -> $finalUrl")
                                } else {
                                    logW(TAG, "[$platformName] ⚠ No valid URL for: $name")
                                }
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                } catch (e: Exception) { 
                    logE(TAG, "[$platformName] Selector error: ${e.message}")
                }
            }
        }
        
        logD(TAG, "[$platformName] Zepto-specific found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    /**
     * Zepto-specific price extraction
     * Zepto typically shows prices like: "₹82" for selling price, "₹121" strikethrough for MRP
     * And "₹39 OFF" or "Save ₹39" for discounts - we need to IGNORE these
     */
    private fun extractZeptoPrice(element: Element): Double? {
        // Strategy 1: Look for specific Zepto price selectors
        val zeptoPriceSelectors = listOf(
            "[data-testid='selling-price']",
            "[data-testid*='product-price']:not([data-testid*='mrp']):not([data-testid*='original'])",
            "[class*='sellingPrice']",
            "[class*='selling-price']",
            "[class*='DiscountedPrice']",  // Current price after discount
            "[class*='currentPrice']"
        )
        
        for (selector in zeptoPriceSelectors) {
            try {
                val priceEl = element.selectFirst(selector)
                if (priceEl != null) {
                    val text = priceEl.text()
                    // Make sure it's not a savings text
                    if (!text.lowercase().contains("save") && !text.lowercase().contains("off")) {
                        val price = extractPrice(text)
                        if (price != null && price > 0) {
                            logD(TAG, "[Zepto] Found price via '$selector': ₹$price")
                            return price
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        
        // Strategy 2: Parse all prices from element and filter out savings
        val allText = element.text()
        
        // Find all price-like patterns
        val priceMatches = PRICE_REGEX.findAll(allText).toList()
        if (priceMatches.isEmpty()) return null
        
        // Parse prices with context
        val validPrices = mutableListOf<Double>()
        
        for (match in priceMatches) {
            val price = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (price <= 0) continue
            
            // Get text before this price (to check for "save", "off", etc.)
            val beforeIdx = maxOf(0, match.range.first - 25)
            val beforeText = allText.substring(beforeIdx, match.range.first).lowercase()
            
            // Get text after this price
            val afterIdx = minOf(allText.length, match.range.last + 1)
            val afterText = allText.substring(afterIdx).take(15).lowercase()
            
            // Skip if this is a savings/discount amount
            val isSavings = beforeText.contains("save") ||
                           beforeText.contains("you pay") == false && beforeText.endsWith("- ") ||
                           beforeText.contains("discount") ||
                           afterText.startsWith(" off") ||
                           afterText.startsWith("off") ||
                           afterText.startsWith(" discount")
            
            // Skip per-unit prices
            val isPerUnit = afterText.startsWith("/") ||
                           Regex("""^[\s/]*\d+\s*(g|gm|kg|ml|l)\b""").containsMatchIn(afterText)
            
            if (!isSavings && !isPerUnit) {
                validPrices.add(price)
                logD(TAG, "[Zepto] Valid price candidate: ₹$price (before: '${beforeText.takeLast(15)}', after: '${afterText.take(10)}')")
            } else {
                logD(TAG, "[Zepto] Skipping price ₹$price (savings=$isSavings, perUnit=$isPerUnit)")
            }
        }
        
        // Sort and pick the most likely selling price
        // Selling price is typically the FIRST valid price shown (reading order)
        // Or the second lowest if there are 3+ valid prices
        if (validPrices.isEmpty()) return null
        
        val sortedPrices = validPrices.sorted()
        
        // If only 1-2 prices, the lowest is likely the selling price
        // If 3+ prices, sometimes lowest is a weird value, use second lowest
        val price = when {
            sortedPrices.size == 1 -> sortedPrices[0]
            sortedPrices.size == 2 -> sortedPrices[0]  // Lower is selling, higher is MRP
            else -> {
                // With 3+ prices, check if lowest is suspiciously low
                val lowest = sortedPrices[0]
                val secondLowest = sortedPrices[1]
                val highest = sortedPrices.last()
                
                // If lowest is less than 30% of highest, it's probably a savings amount
                if (lowest < highest * 0.3 && secondLowest >= highest * 0.3) {
                    secondLowest
                } else {
                    lowest
                }
            }
        }
        
        logD(TAG, "[Zepto] Selected selling price: ₹$price (from ${validPrices.size} candidates: $validPrices)")
        return price
    }
    
    /**
     * Zepto-specific MRP/original price extraction
     */
    private fun extractZeptoOriginalPrice(element: Element, sellingPrice: Double): Double? {
        return extractSmartOriginalPrice(element, sellingPrice, "Zepto")
    }
    
    // ==================== UNIVERSAL SMART PRICE EXTRACTION ====================
    
    /**
     * Universal smart price extraction that filters out savings/discount amounts.
     * This logic applies to ALL platforms that show "Save ₹X" or "₹X OFF" alongside prices.
     * 
     * Common patterns across platforms:
     * - "₹82" (selling price) + "₹121" (MRP) + "₹39 OFF" or "Save ₹39" (savings - IGNORE)
     * - "₹199" (MRP, struck) + "₹149" (selling) + "You Save ₹50" (savings - IGNORE)
     */
    private fun extractSmartPrice(element: Element, platformName: String): Double? {
        // Strategy 1: Look for platform-specific price selectors first
        val priceSelectors = listOf(
            // Selling price selectors (most reliable)
            "[data-testid='selling-price']",
            "[data-testid*='product-price']:not([data-testid*='mrp']):not([data-testid*='original']):not([data-testid*='save'])",
            "[class*='sellingPrice']",
            "[class*='selling-price']",
            "[class*='DiscountedPrice']",
            "[class*='currentPrice']",
            "[class*='offer-price']",
            "[class*='final-price']",
            "[class*='sp ']:not([class*='mrp'])",  // sp = selling price
            // Amazon specific
            "span.a-price span.a-offscreen",
            "span.a-price-whole"
        )
        
        for (selector in priceSelectors) {
            try {
                val priceEl = element.selectFirst(selector)
                if (priceEl != null) {
                    val text = priceEl.text()
                    // Make sure it's not a savings text
                    if (!isSavingsText(text)) {
                        val price = extractPrice(text)
                        if (price != null && price > 0) {
                            logD(TAG, "[$platformName] Found price via selector '$selector': ₹$price")
                            return price
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        
        // Strategy 2: Parse all prices and filter out savings
        val allText = element.text()
        val priceMatches = PRICE_REGEX.findAll(allText).toList()
        if (priceMatches.isEmpty()) return null
        
        val validPrices = mutableListOf<Double>()
        val savingsPrices = mutableListOf<Double>()
        
        for (match in priceMatches) {
            val price = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (price <= 0) continue
            
            // Get context before this price
            val beforeIdx = maxOf(0, match.range.first - 30)
            val beforeText = allText.substring(beforeIdx, match.range.first).lowercase()
            
            // Get context after this price
            val afterIdx = minOf(allText.length, match.range.last + 1)
            val afterText = allText.substring(afterIdx).take(20).lowercase()
            
            // Check if this is a savings/discount amount
            val isSavings = beforeText.contains("save") ||
                           beforeText.contains("you save") ||
                           beforeText.contains("discount") ||
                           beforeText.endsWith("- ") ||
                           beforeText.endsWith("−") ||
                           afterText.startsWith(" off") ||
                           afterText.startsWith("off") ||
                           afterText.startsWith(" discount") ||
                           afterText.startsWith("% off")
            
            // Check if per-unit price
            val isPerUnit = afterText.startsWith("/") ||
                           Regex("""^[\s/]*\d+\s*(g|gm|kg|ml|l|pc|piece)\b""", RegexOption.IGNORE_CASE).containsMatchIn(afterText)
            
            if (isSavings) {
                savingsPrices.add(price)
                logD(TAG, "[$platformName] Identified savings amount: ₹$price")
            } else if (!isPerUnit) {
                validPrices.add(price)
            }
        }
        
        if (validPrices.isEmpty()) return null
        
        // Smart selection: with 3+ prices, lowest might be savings that slipped through
        val sortedPrices = validPrices.sorted()
        val price = when {
            sortedPrices.size == 1 -> sortedPrices[0]
            sortedPrices.size == 2 -> sortedPrices[0]
            else -> {
                val lowest = sortedPrices[0]
                val secondLowest = sortedPrices[1]
                val highest = sortedPrices.last()
                
                // If lowest is suspiciously low (less than 30% of highest), skip it
                if (lowest < highest * 0.3 && secondLowest >= highest * 0.3) {
                    logD(TAG, "[$platformName] Skipping suspicious low price ₹$lowest, using ₹$secondLowest")
                    secondLowest
                } else {
                    lowest
                }
            }
        }
        
        logD(TAG, "[$platformName] Selected price: ₹$price (valid: $validPrices, savings: $savingsPrices)")
        return price
    }
    
    /**
     * Universal smart MRP/original price extraction
     */
    private fun extractSmartOriginalPrice(element: Element, sellingPrice: Double, platformName: String): Double? {
        // Look for MRP/strikethrough selectors
        val mrpSelectors = listOf(
            "[data-testid*='mrp']",
            "[data-testid*='original-price']",
            "[class*='mrp']",
            "[class*='MRP']",
            "[class*='originalPrice']",
            "[class*='original-price']",
            "[class*='strikethrough']",
            "[class*='was-price']",
            "[class*='list-price']",
            "del", "s", "strike"
        )
        
        for (selector in mrpSelectors) {
            try {
                val mrpEl = element.selectFirst(selector)
                if (mrpEl != null) {
                    val price = extractPrice(mrpEl.text())
                    if (price != null && price > sellingPrice) {
                        logD(TAG, "[$platformName] Found MRP via '$selector': ₹$price")
                        return price
                    }
                }
            } catch (e: Exception) { }
        }
        
        // Fallback: find any price higher than selling price (but not savings)
        val allText = element.text()
        val allPrices = PRICE_REGEX.findAll(allText).mapNotNull { match ->
            val price = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            
            // Check it's not a savings amount
            val afterIdx = minOf(allText.length, match.range.last + 1)
            val afterText = allText.substring(afterIdx).take(15).lowercase()
            if (afterText.startsWith(" off") || afterText.startsWith("off")) {
                return@mapNotNull null
            }
            
            price
        }.filter { it > sellingPrice }.toList()
        
        return allPrices.minOrNull()
    }
    
    /**
     * Check if text indicates a savings/discount amount
     */
    private fun isSavingsText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("save") ||
               lower.contains("off") ||
               lower.contains("discount") ||
               lower.contains("you pay") == false && lower.contains("- ₹")
    }
    
    /**
     * BIGBASKET: Traditional e-commerce with good SEO
     * Product URLs follow pattern: /pd/{product-slug}/{product-id}
     */
    private fun extractBigBasketProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying BigBasket-specific extraction")
        
        // Strategy 1: Find product links first
        val productLinks = doc.select("a[href*='/pd/'], a[href*='/product/']")
        logD(TAG, "[$platformName] Found ${productLinks.size} BigBasket product links")
        
        val seenUrls = mutableSetOf<String>()
        
        for (link in productLinks.take(15)) {
            val href = link.attr("abs:href")
            if (href.isBlank() || seenUrls.contains(href)) continue
            if (href.contains("/search") || href.contains("/category") || href.contains("/cl/")) continue
            seenUrls.add(href)
            
            val container = findProductContainer(link) ?: link
            
            val img = container.selectFirst("img[alt], img[src]")
            val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                ?: container.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                    ?.takeIf { it.length in 5..120 && isValidProductName(it) }
            
            // Use smart price extraction to filter out savings amounts
            val price = extractSmartPrice(container, platformName)
            val originalPrice = if (price != null) extractSmartOriginalPrice(container, price, platformName) else null
            val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            
            if (name != null && price != null && price > 0) {
                results.add(createProduct(
                    ProductData(name, price, originalPrice, imageUrl, href),
                    platformName, platformColor, deliveryTime, baseUrl
                ))
                logD(TAG, "[$platformName] Extracted: $name = ₹$price -> $href")
            }
        }
        
        // Strategy 2: BigBasket selectors
        if (results.isEmpty()) {
            val bbSelectors = listOf(
                "[qa='product']",
                "[data-qa='product']",
                "[class*='ProdCard']",
                "[class*='prod-card']"
            )
            
            for (selector in bbSelectors) {
                try {
                    val elements = doc.select(selector)
                    if (elements.size >= 2) {
                        logD(TAG, "[$platformName] BigBasket selector '$selector' found ${elements.size} elements")
                        elements.take(12).forEach { element ->
                            val productUrl = element.selectFirst("a[href*='/pd/'], a[href]")?.attr("abs:href")
                            val img2 = element.selectFirst("img[alt], img[src]")
                            val name2 = img2?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                                ?: element.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                                    ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                            val price2 = extractSmartPrice(element, platformName)
                            val originalPrice2 = if (price2 != null) extractSmartOriginalPrice(element, price2, platformName) else null
                            val imageUrl2 = img2?.attr("abs:src")
                            
                            if (name2 != null && price2 != null && price2 > 0) {
                                results.add(createProduct(
                                    ProductData(name2, price2, originalPrice2, imageUrl2, productUrl),
                                    platformName, platformColor, deliveryTime, baseUrl
                                ))
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                } catch (e: Exception) { }
            }
        }
        
        logD(TAG, "[$platformName] BigBasket-specific found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    /**
     * FLIPKART: Uses complex React with obfuscated classes
     * Product URLs follow pattern: /{product-slug}/p/{item-id}?pid={product-id}
     */
    private fun extractFlipkartProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying Flipkart-specific extraction")
        
        // Strategy 1: Find product links first (most reliable)
        // Flipkart product URLs contain 'pid=' or '/p/'
        val flipkartLinks = doc.select("a[href*='pid='], a[href*='/p/']")
        logD(TAG, "[$platformName] Found ${flipkartLinks.size} Flipkart product links")
        
        val seenUrls = mutableSetOf<String>()
        
        for (link in flipkartLinks.take(20)) {
            val href = link.attr("abs:href")
            if (href.isBlank() || seenUrls.contains(href)) continue
            // Skip search, category, and other non-product pages
            if (href.contains("/search?") || href.contains("/explore/") || !href.contains("pid=") && !href.contains("/p/")) continue
            seenUrls.add(href)
            
            val container = findProductContainer(link) ?: link
            
            val img = container.selectFirst("img[alt], img[src]")
            val name = img?.attr("alt")?.takeIf { it.length in 5..150 && isValidProductName(it) }
                ?: container.selectFirst("[class*='name'], [class*='title'], h2, h3, div[title]")?.text()
                    ?.takeIf { it.length in 5..150 && isValidProductName(it) }
            
            // Use smart price extraction to filter out savings amounts
            val price = extractSmartPrice(container, platformName)
            val originalPrice = if (price != null) extractSmartOriginalPrice(container, price, platformName) else null
            val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            
            if (name != null && price != null && price > 0) {
                results.add(createProduct(
                    ProductData(name, price, originalPrice, imageUrl, href),
                    platformName, platformColor, deliveryTime, baseUrl
                ))
                logD(TAG, "[$platformName] Extracted: $name = ₹$price -> $href")
            }
        }
        
        // Strategy 2: Data attributes fallback
        if (results.isEmpty()) {
            doc.select("[data-id], [data-tkid]").take(12).forEach { element ->
                val productUrl = element.selectFirst("a[href*='pid='], a[href*='/p/']")?.attr("abs:href")
                    ?: element.selectFirst("a[href]")?.attr("abs:href")
                val img2 = element.selectFirst("img[alt], img[src]")
                val name2 = img2?.attr("alt")?.takeIf { it.length in 5..150 && isValidProductName(it) }
                    ?: element.selectFirst("[class*='name'], [class*='title'], h2, h3")?.text()
                        ?.takeIf { it.length in 5..150 && isValidProductName(it) }
                val price2 = extractSmartPrice(element, platformName)
                val originalPrice2 = if (price2 != null) extractSmartOriginalPrice(element, price2, platformName) else null
                val imageUrl2 = img2?.attr("abs:src")
                
                if (name2 != null && price2 != null && price2 > 0) {
                    results.add(createProduct(
                        ProductData(name2, price2, originalPrice2, imageUrl2, productUrl),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
        }
        
        logD(TAG, "[$platformName] Flipkart-specific found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    /**
     * JIOMART: Uses React with specific selectors
     * Product URLs follow pattern: /p/{product-slug}/{product-id} or /buy/{product-slug}
     */
    private fun extractJioMartProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying JioMart-specific extraction")
        
        // Strategy 1: Find product links first
        val productLinks = doc.select("a[href*='/p/'], a[href*='/buy/'], a[href*='/dp/']")
        logD(TAG, "[$platformName] Found ${productLinks.size} JioMart product links")
        
        val seenUrls = mutableSetOf<String>()
        
        for (link in productLinks.take(15)) {
            val href = link.attr("abs:href")
            if (href.isBlank() || seenUrls.contains(href)) continue
            if (href.contains("/search") || href.contains("/c/") || href.contains("/category")) continue
            seenUrls.add(href)
            
            val container = findProductContainer(link) ?: link
            
            val img = container.selectFirst("img[alt], img[src]")
            val name = img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                ?: container.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                    ?.takeIf { it.length in 5..120 && isValidProductName(it) }
            
            // Use smart price extraction to filter out savings amounts
            val price = extractSmartPrice(container, platformName)
            val originalPrice = if (price != null) extractSmartOriginalPrice(container, price, platformName) else null
            val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            
            if (name != null && price != null && price > 0) {
                results.add(createProduct(
                    ProductData(name, price, originalPrice, imageUrl, href),
                    platformName, platformColor, deliveryTime, baseUrl
                ))
                logD(TAG, "[$platformName] Extracted: $name = ₹$price -> $href")
            }
        }
        
        // Strategy 2: JioMart selectors
        if (results.isEmpty()) {
            val jioSelectors = listOf(
                "[class*='plp-card']",
                "[class*='product-card']",
                "[data-sku]"
            )
            
            for (selector in jioSelectors) {
                try {
                    val elements = doc.select(selector)
                    if (elements.size >= 2) {
                        logD(TAG, "[$platformName] JioMart selector '$selector' found ${elements.size} elements")
                        elements.take(12).forEach { element ->
                            val productUrl = element.selectFirst("a[href*='/p/'], a[href*='/buy/'], a[href]")?.attr("abs:href")
                            val img2 = element.selectFirst("img[alt], img[src]")
                            val name2 = img2?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }
                                ?: element.selectFirst("[class*='name'], [class*='title'], h2, h3, h4")?.text()
                                    ?.takeIf { it.length in 5..120 && isValidProductName(it) }
                            val price2 = extractSmartPrice(element, platformName)
                            val originalPrice2 = if (price2 != null) extractSmartOriginalPrice(element, price2, platformName) else null
                            val imageUrl2 = img2?.attr("abs:src")
                            
                            if (name2 != null && price2 != null && price2 > 0) {
                                results.add(createProduct(
                                    ProductData(name2, price2, originalPrice2, imageUrl2, productUrl),
                                    platformName, platformColor, deliveryTime, baseUrl
                                ))
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                } catch (e: Exception) { }
            }
        }
        
        logD(TAG, "[$platformName] JioMart-specific found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    /**
     * AMAZON: Good SEO with structured data
     * Product URLs follow pattern: /dp/{ASIN} or /gp/product/{ASIN}
     */
    private fun extractAmazonProducts(
        doc: Document,
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying Amazon-specific extraction")
        
        // Strategy 1: Find product links first (most reliable for URLs)
        val productLinks = doc.select("a[href*='/dp/'], a[href*='/gp/product/']")
        logD(TAG, "[$platformName] Found ${productLinks.size} Amazon product links")
        
        val seenUrls = mutableSetOf<String>()
        
        for (link in productLinks.take(20)) {
            val href = link.attr("abs:href")
            if (href.isBlank() || seenUrls.contains(href)) continue
            if (!href.contains("/dp/") && !href.contains("/gp/product/")) continue
            
            // Clean URL - remove query params after ASIN
            val cleanUrl = href.split("?").first().split("/ref=").first()
            if (seenUrls.contains(cleanUrl)) continue
            seenUrls.add(cleanUrl)
            
            val container = findProductContainer(link) ?: link
            
            val img = container.selectFirst("img[alt], img[src]")
            val name = container.selectFirst("h2 span, .a-text-normal")?.text()?.takeIf { it.length in 5..150 && isValidProductName(it) }
                ?: img?.attr("alt")?.takeIf { it.length in 5..150 && isValidProductName(it) }
            
            // Amazon-specific price extraction (they have structured price elements)
            val priceWhole = container.selectFirst(".a-price-whole")?.text()?.replace(",", "")
            val priceFraction = container.selectFirst(".a-price-fraction")?.text() ?: "00"
            val price = priceWhole?.let { "$it.$priceFraction".toDoubleOrNull() }
                ?: extractSmartPrice(container, platformName)
            
            val originalPrice = if (price != null) extractSmartOriginalPrice(container, price, platformName) else null
            val imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            
            if (name != null && price != null && price > 0) {
                results.add(createProduct(
                    ProductData(name, price, originalPrice, imageUrl, cleanUrl),
                    platformName, platformColor, deliveryTime, baseUrl
                ))
                logD(TAG, "[$platformName] Extracted: $name = ₹$price -> $cleanUrl")
            }
        }
        
        // Strategy 2: Amazon selectors (fallback)
        if (results.isEmpty()) {
            val amazonSelectors = listOf(
                "[data-asin]:has(img)",
                "[data-component-type='s-search-result']",
                "[cel_widget_id*='MAIN-SEARCH_RESULTS']"
            )
            
            for (selector in amazonSelectors) {
                try {
                    val elements = doc.select(selector)
                    if (elements.size >= 2) {
                        logD(TAG, "[$platformName] Amazon selector '$selector' found ${elements.size} elements")
                        elements.take(12).forEach { element ->
                            // Get product URL
                            val productUrl = element.selectFirst("a[href*='/dp/']")?.attr("abs:href")?.split("?")?.first()
                                ?: element.selectFirst("a[href]")?.attr("abs:href")
                            
                            // Extract product info
                            val elImg = element.selectFirst("img[alt], img[src]")
                            val elName = element.selectFirst("h2 span, .a-text-normal")?.text()?.takeIf { it.length in 5..150 && isValidProductName(it) }
                                ?: elImg?.attr("alt")?.takeIf { it.length in 5..150 && isValidProductName(it) }
                            
                            // Amazon-specific price extraction with smart fallback
                            val priceWhole = element.selectFirst(".a-price-whole")?.text()?.replace(",", "")
                            val priceFraction = element.selectFirst(".a-price-fraction")?.text() ?: "00"
                            val elPrice = priceWhole?.let { "$it.$priceFraction".toDoubleOrNull() }
                                ?: extractSmartPrice(element, platformName)
                            
                            val elOriginalPrice = if (elPrice != null) extractSmartOriginalPrice(element, elPrice, platformName) else null
                            val elImageUrl = elImg?.attr("abs:src")?.takeIf { it.isNotBlank() }
                            
                            if (elName != null && elPrice != null && elPrice > 0) {
                                results.add(createProduct(
                                    ProductData(elName, elPrice, elOriginalPrice, elImageUrl, productUrl),
                                    platformName, platformColor, deliveryTime, baseUrl
                                ))
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                } catch (e: Exception) { }
            }
        }
        
        logD(TAG, "[$platformName] Amazon-specific found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }.take(10)
    }
    
    /**
     * Text pattern extraction - last resort fallback
     * Finds products by analyzing text patterns
     */
    private fun extractFromTextPatterns(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<Product> {
        val results = mutableListOf<Product>()
        
        logD(TAG, "[$platformName] Trying text pattern extraction")
        
        // Find all text nodes that look like product names near prices
        val allElements = doc.body()?.allElements ?: return emptyList()
        
        val priceElements = allElements.filter { el ->
            val text = el.ownText()
            text.isNotBlank() && PRICE_REGEX.containsMatchIn(text) && text.length < 30
        }
        
        logD(TAG, "[$platformName] Found ${priceElements.size} elements with prices")
        
        val processedContainers = mutableSetOf<Element>()
        
        for (priceEl in priceElements.take(30)) {
            // Find the closest container with a name
            var container: Element? = priceEl.parent()
            repeat(5) {
                if (container?.parent() != null && container?.text()?.length ?: 0 < 500) {
                    container = container?.parent()
                }
            }
            
            if (container != null && container !in processedContainers) {
                processedContainers.add(container!!)
                
                // Try to find a name in this container
                var name: String? = null
                
                // Look for text that's not a price
                container!!.select("span, div, p, h1, h2, h3, h4, a").forEach { el ->
                    val text = el.ownText().trim()
                    if (name == null && 
                        text.length in 5..100 && 
                        isValidProductName(text) &&
                        !PRICE_REGEX.containsMatchIn(text)) {
                        name = text
                    }
                }
                
                val price = extractPriceFromElement(container!!)
                val imageUrl = container!!.selectFirst("img")?.attr("src")
                
                if (name != null && price != null && price > 0) {
                    results.add(createProduct(
                        ProductData(name!!, price, null, imageUrl, null),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
            
            if (results.size >= 10) break
        }
        
        logD(TAG, "[$platformName] Text pattern found ${results.size} products")
        return results.distinctBy { it.name.lowercase() }
    }
    
    /**
     * Helper to find products in a JSON string
     */
    private fun findProductsInJsonString(
        jsonStr: String,
        results: MutableList<Product>,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ) {
        try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            findProductsInJsonRecursive(json, results, platformName, platformColor, deliveryTime, baseUrl, 0)
        } catch (e: Exception) {
            // Try to find product-like patterns
            val productPattern = Regex(""""name"\s*:\s*"([^"]+)".*?"price"\s*:\s*(\d+(?:\.\d+)?)""")
            productPattern.findAll(jsonStr).forEach { match ->
                val name = match.groupValues[1]
                val price = match.groupValues[2].toDoubleOrNull()
                if (name.length in 5..100 && price != null && price > 0 && isValidProductName(name)) {
                    results.add(createProduct(
                        ProductData(name, price, null, null, null),
                        platformName, platformColor, deliveryTime, baseUrl
                    ))
                }
            }
        }
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Extract product data from any HTML element
     */
    private fun extractProductFromElement(element: Element): ProductData? {
        // Find image
        val img = element.selectFirst("img[src], img[data-src], img[srcset]")
        var imageUrl = img?.attr("abs:src")?.takeIf { it.isNotBlank() && !it.contains("placeholder") }
            ?: img?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        
        // Find name using multiple strategies (priority order)
        var name: String? = null
        
        // 1. Image alt (accessibility - very reliable)
        if (name == null) {
            img?.attr("alt")?.takeIf { it.length in 5..120 && isValidProductName(it) }?.let {
                name = it.trim()
            }
        }
        
        // 2. ARIA label
        if (name == null) {
            element.attr("aria-label")?.takeIf { it.length in 5..120 && isValidProductName(it) }?.let {
                name = it.trim()
            }
        }
        
        // 3. Title attribute
        if (name == null) {
            element.attr("title")?.takeIf { it.length in 5..120 && isValidProductName(it) }?.let {
                name = it.trim()
            }
            element.selectFirst("[title]")?.attr("title")?.takeIf { it.length in 5..120 && isValidProductName(it) }?.let {
                name = it.trim()
            }
        }
        
        // 4. Headings (semantic HTML)
        if (name == null) {
            element.select("h1, h2, h3, h4, h5, h6").firstOrNull { 
                val text = it.text().trim()
                text.length in 5..120 && isValidProductName(text) && !PRICE_REGEX.containsMatchIn(text)
            }?.let { name = it.text().trim() }
        }
        
        // 5. Link text
        if (name == null) {
            element.select("a").firstOrNull { link ->
                val text = link.text().trim()
                text.length in 5..100 && isValidProductName(text) && !PRICE_REGEX.containsMatchIn(text)
            }?.let { name = it.text().trim() }
        }
        
        // 6. Span/div with data attributes
        if (name == null) {
            element.select("[data-name], [data-title], [data-product-name]").firstOrNull()?.let { el ->
                val text = el.attr("data-name").takeIf { it.isNotBlank() }
                    ?: el.attr("data-title").takeIf { it.isNotBlank() }
                    ?: el.attr("data-product-name").takeIf { it.isNotBlank() }
                if (text != null && text.length in 5..120 && isValidProductName(text)) {
                    name = text.trim()
                }
            }
        }
        
        // 7. Span/div with substantial text
        if (name == null) {
            element.select("span, div, p").firstOrNull { el ->
                val text = el.ownText().trim()
                text.length in 5..100 && 
                isValidProductName(text) && 
                !PRICE_REGEX.containsMatchIn(text) &&
                !text.matches(Regex("^[\\d\\s,\\.]+$"))
            }?.let { name = it.ownText().trim() }
        }
        
        // Extract price
        val price = extractPriceFromElement(element)
        val originalPrice = if (price != null) extractOriginalPriceFromElement(element, price) else null
        
        // Extract URL - prioritize product-specific links
        val productUrl = findBestProductUrl(element)
        
        // Validate
        if (name.isNullOrBlank() || price == null || price <= 0) return null
        
        return ProductData(
            name = name!!.trim().take(120),
            price = price,
            originalPrice = originalPrice,
            imageUrl = imageUrl,
            url = productUrl
        )
    }
    
    /**
     * Extract price from element text
     */
    private fun extractPriceFromElement(element: Element): Double? {
        // Strategy 1: Look for specific price selectors first (more reliable)
        val priceSelectors = listOf(
            "[class*='selling'][class*='price']",  // selling-price, sellingPrice
            "[class*='offer'][class*='price']",    // offer-price, offerPrice
            "[class*='final'][class*='price']",    // final-price, finalPrice
            "[class*='discounted']",               // discounted price
            "[data-testid*='price']:not([data-testid*='mrp']):not([data-testid*='original']):not([data-testid*='save'])",
            "[class*='sp']:not([class*='mrp'])",   // sp = selling price
            "span.a-price span.a-offscreen",       // Amazon
            "span.a-price-whole",                  // Amazon
            "[class*='price']:not([class*='mrp']):not([class*='original']):not([class*='strike']):not([class*='save']):not([class*='discount'])"
        )
        
        for (selector in priceSelectors) {
            try {
                val priceEl = element.selectFirst(selector)
                if (priceEl != null) {
                    val priceText = priceEl.ownText().ifBlank { priceEl.text() }
                    val price = extractPrice(priceText)
                    if (price != null && price > 0) {
                        logD(TAG, "Found price via selector '$selector': ₹$price")
                        return price
                    }
                }
            } catch (e: Exception) { }
        }
        
        // Strategy 2: Find price elements and filter out savings/per-unit prices
        val allText = element.text()
        val matches = PRICE_REGEX.findAll(allText).toList()
        
        if (matches.isEmpty()) return null
        
        // Parse all prices with context
        val pricesWithContext = matches.mapNotNull { match ->
            val price = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            if (price <= 0) return@mapNotNull null
            
            // Check context BEFORE the price match
            val startIdx = maxOf(0, match.range.first - 30)
            val beforeMatch = allText.substring(startIdx, match.range.first).lowercase()
            
            // Check if this is a SAVINGS/DISCOUNT amount (not a selling price)
            val isSavings = beforeMatch.contains("save") ||
                           beforeMatch.contains("off ") ||
                           beforeMatch.contains("discount") ||
                           beforeMatch.contains("you save") ||
                           beforeMatch.endsWith("- ") ||
                           beforeMatch.endsWith("−") // minus sign
            
            // Check context AFTER the price match
            val afterMatch = allText.substring(minOf(match.range.last + 1, allText.length)).take(20)
            val isPerUnit = afterMatch.let { after ->
                after.startsWith("/") || 
                after.startsWith(" /") ||
                after.startsWith("per", ignoreCase = true) ||
                after.startsWith(" per", ignoreCase = true) ||
                after.startsWith(" off", ignoreCase = true) || // "₹18 off"
                Regex("""^[\s/]*\d+\s*(g|gm|kg|ml|l|pc|piece)""", RegexOption.IGNORE_CASE).containsMatchIn(after)
            }
            
            Triple(price, isSavings, isPerUnit)
        }
        
        // Filter out savings and per-unit prices
        val validPrices = pricesWithContext.filter { !it.second && !it.third }.map { it.first }
        val savingsPrices = pricesWithContext.filter { it.second }.map { it.first }
        val perUnitPrices = pricesWithContext.filter { it.third && !it.second }.map { it.first }
        
        logD(TAG, "Price analysis - Valid: $validPrices, Savings: $savingsPrices, PerUnit: $perUnitPrices")
        
        // Use valid prices if available
        if (validPrices.isNotEmpty()) {
            // Get the SECOND lowest if there are multiple (often lowest is savings, second is selling)
            // But if only one or two valid prices, use the lowest
            val sortedPrices = validPrices.sorted()
            val price = when {
                sortedPrices.size >= 3 -> {
                    // With 3+ prices: lowest is often savings, take second lowest as selling price
                    // Unless prices are very close (within 10%), then take lowest
                    val lowest = sortedPrices[0]
                    val secondLowest = sortedPrices[1]
                    if (secondLowest <= lowest * 1.5 && lowest >= 20) lowest else secondLowest
                }
                else -> sortedPrices.firstOrNull()
            }
            logD(TAG, "Using valid price: ₹$price (from ${validPrices.size} candidates)")
            return price
        }
        
        // If only per-unit prices, use highest (likely total price)
        if (perUnitPrices.isNotEmpty()) {
            val price = perUnitPrices.sorted().lastOrNull()
            logW(TAG, "⚠ Only per-unit prices found, using highest: ₹$price")
            return price
        }
        
        return null
    }
    
    /**
     * Extract original price (MRP) from element
     */
    private fun extractOriginalPriceFromElement(element: Element, sellingPrice: Double): Double? {
        val text = element.text()
        val matches = PRICE_REGEX.findAll(text).toList()
        
        if (matches.size < 2) return null
        
        val prices = matches.mapNotNull { 
            it.groupValues[1].replace(",", "").toDoubleOrNull() 
        }.filter { it > 0 && it > sellingPrice }.sorted()
        
        return prices.lastOrNull() // Highest price is usually MRP
    }
    
    /**
     * Extract price from text string
     */
    private fun extractPrice(text: String): Double? {
        val match = PRICE_REGEX.find(text) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }
    
    /**
     * Find the best product URL from an element
     * Prioritizes product-specific URL patterns over generic links
     */
    private fun findBestProductUrl(element: Element): String? {
        // Product URL patterns for different platforms (sorted by specificity)
        val productPatterns = listOf(
            // Amazon
            "a[href*='/dp/']",
            "a[href*='/gp/product/']",
            // Flipkart
            "a[href*='pid=']",
            "a[href*='/p/itm']",
            // Zepto - multiple patterns
            "a[href*='/pn/']",
            "a[href*='/pvid/']",
            "a[href*='zeptonow.com/p']",
            // Blinkit  
            "a[href*='/prn/']",
            "a[href*='/prid/']",
            "a[href*='blinkit.com/p']",
            // BigBasket
            "a[href*='/pd/']",
            "a[href*='/sp']",
            "a[href*='bigbasket.com/p']",
            // JioMart
            "a[href*='/p/']",
            "a[href*='/buy/']",
            // Instamart
            "a[href*='/item/']",
            "a[href*='swiggy.com/instamart']",
            // Generic product patterns
            "a[href*='/product/']",
            "a[href*='/products/']",
            "a[href*='product_id=']",
            "a[href*='productId=']"
        )
        
        // Try to find a link matching product patterns
        for (pattern in productPatterns) {
            val link = element.selectFirst(pattern)
            if (link != null) {
                val href = link.attr("abs:href")
                if (href.isNotBlank() && isValidProductUrl(href)) {
                    return href
                }
            }
        }
        
        // Check parent elements for product links (up to 3 levels)
        var parent = element.parent()
        repeat(3) {
            if (parent == null) return@repeat
            for (pattern in productPatterns) {
                val link = parent.selectFirst(pattern)
                if (link != null) {
                    val href = link.attr("abs:href")
                    if (href.isNotBlank() && isValidProductUrl(href)) {
                        return href
                    }
                }
            }
            parent = parent.parent()
        }
        
        // Fallback: any link that's not a category/search/navigation link
        val allLinks = element.select("a[href]")
        for (link in allLinks) {
            val href = link.attr("abs:href")
            if (href.isNotBlank() && isValidProductUrl(href)) {
                return href
            }
        }
        
        return null
    }
    
    /**
     * Check if URL looks like a valid product URL (not search/category/navigation)
     */
    private fun isValidProductUrl(url: String): Boolean {
        if (url.isBlank()) return false
        
        // Exclude common non-product URLs
        val excludePatterns = listOf(
            "/search", "/s?", "/category", "/c/", "/cl/",
            "/login", "/signup", "/register", "/cart",
            "/wishlist", "/account", "/help", "/about",
            "/contact", "/faq", "/terms", "/privacy",
            "javascript:", "#", "mailto:", "tel:"
        )
        
        val lowerUrl = url.lowercase()
        for (pattern in excludePatterns) {
            if (lowerUrl.contains(pattern)) return false
        }
        
        // Must start with http
        if (!url.startsWith("http")) return false
        
        return true
    }
    
    /**
     * Find the product container (card) that contains an element
     */
    private fun findProductContainer(element: Element): Element? {
        var current = element.parent()
        var bestContainer: Element? = null
        
        repeat(6) {
            current?.let { el ->
                val hasImage = el.select("img").isNotEmpty()
                val hasPrice = PRICE_REGEX.containsMatchIn(el.text())
                val notTooLarge = el.text().length < 1000
                
                if (hasImage && hasPrice && notTooLarge) {
                    bestContainer = el
                }
                current = el.parent()
            }
        }
        
        return bestContainer
    }
    
    /**
     * Check if a product name is valid
     */
    private fun isValidProductName(name: String): Boolean {
        val normalized = name.lowercase().trim()
        
        // Check blacklist
        if (INVALID_NAMES.any { normalized == it || normalized.startsWith("$it ") || normalized.endsWith(" $it") }) {
            return false
        }
        
        // Must be reasonable length
        if (name.length < 4 || name.length > 150) return false
        
        // Must not be just numbers or prices
        if (name.matches(Regex("^[₹\\d,\\.\\s%off]+$", RegexOption.IGNORE_CASE))) return false
        
        // Should contain some letters
        if (!name.any { it.isLetter() }) return false
        
        // Should not be just common words
        if (normalized in listOf("new", "sale", "hot", "best", "top", "popular")) return false
        
        return true
    }
    
    /**
     * Create a Product from ProductData
     */
    private fun createProduct(
        data: ProductData,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): Product {
        val discount = data.originalPrice?.let {
            if (it > data.price) "${((it - data.price) / it * 100).toInt()}% off" else null
        }
        
        // Ensure URL is absolute and valid
        val productUrl = ensureAbsoluteUrl(data.url, baseUrl)
        
        // Log URL for debugging
        if (productUrl == baseUrl || data.url.isNullOrBlank()) {
            logW(TAG, "[$platformName] ⚠ No product URL for: ${data.name.take(40)} - using baseUrl")
        } else {
            logD(TAG, "[$platformName] ✓ Product URL: ${data.name.take(30)} -> $productUrl")
        }
        
        return Product(
            name = data.name,
            price = data.price,
            originalPrice = data.originalPrice,
            discount = discount,
            platform = platformName,
            platformColor = platformColor,
            deliveryTime = deliveryTime,
            url = productUrl,
            imageUrl = data.imageUrl
        )
    }
    
    /**
     * Ensure URL is absolute (starts with http/https)
     */
    private fun ensureAbsoluteUrl(url: String?, baseUrl: String): String {
        if (url.isNullOrBlank()) return baseUrl
        
        return when {
            // Already absolute
            url.startsWith("http://") || url.startsWith("https://") -> {
                // Clean tracking parameters
                url.split("?ref=").first()
                   .split("/ref=").first()
                   .split("?tag=").first()
            }
            // Relative URL starting with /
            url.startsWith("/") -> {
                val base = baseUrl.trimEnd('/')
                "$base$url".split("?ref=").first().split("/ref=").first()
            }
            // Relative URL without /
            else -> {
                val base = baseUrl.trimEnd('/')
                "$base/$url".split("?ref=").first().split("/ref=").first()
            }
        }
    }
    
    /**
     * Log debug info when all strategies fail
     */
    private fun logDebugInfo(doc: Document, platformName: String) {
        logD(TAG, "[$platformName] === Debug Info ===")
        logD(TAG, "[$platformName] JSON-LD scripts: ${doc.select("script[type='application/ld+json']").size}")
        logD(TAG, "[$platformName] __NEXT_DATA__: ${doc.selectFirst("script#__NEXT_DATA__") != null}")
        logD(TAG, "[$platformName] Microdata items: ${doc.select("[itemtype]").size}")
        logD(TAG, "[$platformName] Data-testid elements: ${doc.select("[data-testid]").size}")
        logD(TAG, "[$platformName] Images: ${doc.select("img").size}")
        logD(TAG, "[$platformName] Links: ${doc.select("a[href]").size}")
        logD(TAG, "[$platformName] Has price pattern: ${PRICE_REGEX.containsMatchIn(doc.text())}")
        
        // Sample some elements
        doc.select("img[alt]").take(3).forEach { img ->
            logD(TAG, "[$platformName] Sample img alt: ${img.attr("alt").take(50)}")
        }
    }
    
    // ==================== Data Classes ====================
    
    data class ProductData(
        val name: String,
        val price: Double,
        val originalPrice: Double?,
        val imageUrl: String?,
        val url: String? = null
    )
    
    data class Strategy(
        val name: String,
        val extract: () -> List<Product>
    )
}
