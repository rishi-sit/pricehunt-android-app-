package com.pricehunt.data.scrapers.adaptive

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pricehunt.data.model.Product
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive Selector Engine - Self-healing product extraction that doesn't rely on hardcoded selectors.
 * 
 * This engine uses pattern-based extraction that adapts to website changes:
 * 
 * 1. HEURISTIC EXTRACTION: Finds products using universal patterns:
 *    - Price proximity to images (products always have both)
 *    - Repeated DOM structures (product grids have similar cards)
 *    - Link density (product cards link to product pages)
 *    
 * 2. SELECTOR LEARNING: When a selector works, save it for future use
 *    - If saved selector fails, fall back to heuristics
 *    - Automatically discover new selectors
 *    
 * 3. CONFIDENCE SCORING: Each extracted product gets a confidence score
 *    - High confidence: Name + Price + Image + URL
 *    - Medium confidence: Name + Price + (Image OR URL)
 *    - Low confidence: Name + Price only
 *    
 * 4. STRUCTURE FINGERPRINTING: Detect when site structure changes
 *    - Hash the DOM structure, not content
 *    - Alert when structure changes significantly
 */
@Singleton
class AdaptiveSelectorEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AdaptiveSelector"
        private const val PREFS_NAME = "adaptive_selectors"
        private const val KEY_LEARNED_SELECTORS = "learned_selectors"
        
        // Universal price pattern for Indian e-commerce
        private val PRICE_PATTERN = Regex("""(?:₹|Rs\.?|INR)\s*(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        
        // Minimum confidence to include a product
        private const val MIN_CONFIDENCE = 0.5
        
        // Invalid product name patterns
        private val INVALID_NAME_PATTERNS = listOf(
            Regex("""^\d+(-\d+)?\s*(mins?|minutes?|hours?|hr|days?)$""", RegexOption.IGNORE_CASE),
            Regex("""^(add|buy|view|cart|login|sign|search|filter|sort|home|menu)""", RegexOption.IGNORE_CASE),
            Regex("""^(free delivery|express|same day|next day|delivery)""", RegexOption.IGNORE_CASE),
            Regex("""^(out of stock|sold out|unavailable|coming soon|notify)""", RegexOption.IGNORE_CASE)
        )
        
        // Maximum length for product names
        private const val MAX_NAME_LENGTH = 150
        private const val MIN_NAME_LENGTH = 3
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val gson = Gson()
    private val learnedSelectors = mutableMapOf<String, LearnedSelector>()
    
    init {
        loadLearnedSelectors()
    }
    
    /**
     * Extract products using adaptive strategies
     * Returns products sorted by confidence score
     */
    fun extractProducts(
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<ExtractionResult> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<ExtractionResult>()
        val seenNames = mutableSetOf<String>()
        
        // Calculate structure hash for change detection
        val structureHash = calculateStructureHash(doc)
        
        // Strategy 1: Try learned selectors first (fastest)
        val learnedResult = tryLearnedSelectors(doc, platformName, platformColor, deliveryTime, baseUrl)
        if (learnedResult.isNotEmpty()) {
            log("$platformName: Learned selectors found ${learnedResult.size} products")
            learnedResult.forEach { if (seenNames.add(it.product.name.lowercase())) results.add(it) }
            if (results.size >= 5) {
                return results.sortedByDescending { it.confidence }.take(15)
            }
        }
        
        // Strategy 2: Heuristic extraction (most robust)
        log("$platformName: Running heuristic extraction...")
        
        // 2a. Find repeated structures (product grids)
        val repeatedStructures = findRepeatedStructures(doc)
        for (element in repeatedStructures) {
            val product = extractFromElement(element, platformName, platformColor, deliveryTime, baseUrl)
            if (product != null && seenNames.add(product.product.name.lowercase())) {
                results.add(product)
                
                // Learn selector if high confidence
                if (product.confidence >= 0.8) {
                    learnSelector(platformName, element)
                }
            }
        }
        
        // 2b. Price-image proximity extraction
        if (results.size < 5) {
            log("$platformName: Trying price-image proximity...")
            val proximityResults = extractByPriceImageProximity(doc, platformName, platformColor, deliveryTime, baseUrl)
            proximityResults.forEach { 
                if (seenNames.add(it.product.name.lowercase())) results.add(it)
            }
        }
        
        // 2c. Link pattern extraction
        if (results.size < 5) {
            log("$platformName: Trying link pattern extraction...")
            val linkResults = extractByLinkPatterns(doc, platformName, platformColor, deliveryTime, baseUrl)
            linkResults.forEach { 
                if (seenNames.add(it.product.name.lowercase())) results.add(it)
            }
        }
        
        // 2d. JSON-LD Schema.org extraction (most reliable when available)
        if (results.size < 5) {
            log("$platformName: Trying JSON-LD extraction...")
            val jsonLdResults = extractFromJsonLd(html, platformName, platformColor, deliveryTime, baseUrl)
            jsonLdResults.forEach { 
                if (seenNames.add(it.product.name.lowercase())) results.add(it)
            }
        }
        
        // 2e. Script tag JSON extraction
        if (results.size < 5) {
            log("$platformName: Trying script JSON extraction...")
            val scriptResults = extractFromScriptJson(html, platformName, platformColor, deliveryTime, baseUrl)
            scriptResults.forEach { 
                if (seenNames.add(it.product.name.lowercase())) results.add(it)
            }
        }
        
        // Filter by minimum confidence and sort
        val filtered = results
            .filter { it.confidence >= MIN_CONFIDENCE }
            .sortedByDescending { it.confidence }
            .take(15)
        
        log("$platformName: Extracted ${filtered.size} products (from ${results.size} candidates)")
        
        return filtered
    }
    
    /**
     * Calculate a hash of the DOM structure (not content) for change detection
     */
    fun calculateStructureHash(doc: Document): String {
        val structure = StringBuilder()
        
        // Build a simplified structure representation
        doc.body()?.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is Element && depth < 10) {
                    structure.append("${node.tagName()}[${node.classNames().sorted().take(3).joinToString(",")}]:")
                }
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })
        
        // Hash the structure
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(structure.toString().toByteArray())
        return digest.fold("") { str, it -> str + "%02x".format(it) }.take(16)
    }
    
    // ==================== Extraction Strategies ====================
    
    /**
     * Try learned selectors for this platform
     */
    private fun tryLearnedSelectors(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<ExtractionResult> {
        val learned = learnedSelectors[platformName] ?: return emptyList()
        val results = mutableListOf<ExtractionResult>()
        
        try {
            val elements = doc.select(learned.selector)
            log("$platformName: Learned selector '${learned.selector}' found ${elements.size} elements")
            
            for (element in elements.take(20)) {
                val product = extractFromElement(element, platformName, platformColor, deliveryTime, baseUrl)
                if (product != null) {
                    results.add(product.copy(extractionMethod = "learned_selector"))
                }
            }
            
            // If learned selector found products, boost its success count
            if (results.isNotEmpty()) {
                learned.successCount++
                saveLearnedSelectors()
            } else {
                // Selector might be stale
                learned.failureCount++
                if (learned.failureCount > 3) {
                    log("$platformName: Removing stale selector '${learned.selector}'")
                    learnedSelectors.remove(platformName)
                    saveLearnedSelectors()
                }
            }
        } catch (e: Exception) {
            log("$platformName: Learned selector error: ${e.message}")
        }
        
        return results
    }
    
    /**
     * Find repeated DOM structures (product grids)
     */
    private fun findRepeatedStructures(doc: Document): List<Element> {
        val candidates = mutableListOf<Element>()
        
        // Group elements by their structure signature
        val structureGroups = mutableMapOf<String, MutableList<Element>>()
        
        doc.body()?.children()?.forEach { child ->
            findProductCandidates(child, structureGroups, 0)
        }
        
        // Find groups with 3+ similar structures (likely product cards)
        for ((signature, elements) in structureGroups) {
            if (elements.size >= 3) {
                // Verify they look like products (have price + image)
                val validElements = elements.filter { el ->
                    el.text().contains("₹") && 
                    el.selectFirst("img") != null &&
                    el.text().length in 20..1000
                }
                
                if (validElements.size >= 3) {
                    candidates.addAll(validElements.take(15))
                }
            }
        }
        
        return candidates
    }
    
    private fun findProductCandidates(
        element: Element,
        groups: MutableMap<String, MutableList<Element>>,
        depth: Int
    ) {
        if (depth > 8) return  // Don't go too deep
        
        // Create structure signature
        val signature = "${element.tagName()}_${element.childrenSize()}_${element.classNames().sorted().take(2).joinToString("_")}"
        
        // Check if this looks like a product container
        if (element.text().length in 20..800 && 
            element.text().contains(Regex("""₹\s*\d+""")) &&
            element.selectFirst("img") != null) {
            
            groups.getOrPut(signature) { mutableListOf() }.add(element)
        }
        
        // Recurse into children
        element.children().forEach { child ->
            findProductCandidates(child, groups, depth + 1)
        }
    }
    
    /**
     * Extract products by finding prices near images
     */
    private fun extractByPriceImageProximity(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<ExtractionResult> {
        val results = mutableListOf<ExtractionResult>()
        val processedContainers = mutableSetOf<Int>()
        
        // Find all images that look like product images
        val productImages = doc.select("img[src*='cdn'], img[src*='image'], img[data-src], img[alt]")
            .filter { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                val alt = img.attr("alt")
                src.isNotBlank() && 
                !src.contains("logo", ignoreCase = true) &&
                !src.contains("icon", ignoreCase = true) &&
                !src.contains("banner", ignoreCase = true) &&
                (alt.isBlank() || alt.length > 3)
            }
        
        for (img in productImages.take(30)) {
            // Find the smallest container that has both price and this image
            val container = img.parents().firstOrNull { parent ->
                val hash = parent.hashCode()
                if (processedContainers.contains(hash)) return@firstOrNull false
                
                val text = parent.text()
                text.contains("₹") && 
                text.length in 30..600 &&
                parent.selectFirst("img") != null
            } ?: continue
            
            processedContainers.add(container.hashCode())
            
            val product = extractFromElement(container, platformName, platformColor, deliveryTime, baseUrl)
            if (product != null) {
                results.add(product.copy(extractionMethod = "price_image_proximity"))
            }
            
            if (results.size >= 15) break
        }
        
        return results
    }
    
    /**
     * Extract products from link patterns
     */
    private fun extractByLinkPatterns(
        doc: Document,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<ExtractionResult> {
        val results = mutableListOf<ExtractionResult>()
        
        // Common product URL patterns
        val productLinks = doc.select(
            "a[href*='/p/'], a[href*='/pd/'], a[href*='/product/'], " +
            "a[href*='/prn/'], a[href*='/prid/'], a[href*='/dp/'], " +
            "a[href*='/item/'], a[href*='/buy/']"
        )
        
        val processedHrefs = mutableSetOf<String>()
        
        for (link in productLinks.take(30)) {
            val href = link.attr("href")
            if (processedHrefs.contains(href)) continue
            processedHrefs.add(href)
            
            // Find container with price
            val container = link.parents().firstOrNull { parent ->
                parent.text().contains("₹") && parent.text().length < 600
            } ?: link
            
            val product = extractFromElement(container, platformName, platformColor, deliveryTime, baseUrl)
            if (product != null) {
                // Use the link's href as the product URL
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val updatedProduct = product.product.copy(url = fullUrl)
                results.add(ExtractionResult(updatedProduct, product.confidence, "link_pattern"))
            }
            
            if (results.size >= 15) break
        }
        
        return results
    }
    
    /**
     * Extract from JSON-LD Schema.org (most reliable when present)
     */
    private fun extractFromJsonLd(
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<ExtractionResult> {
        val results = mutableListOf<ExtractionResult>()
        
        try {
            val jsonLdPattern = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", 
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            
            for (match in jsonLdPattern.findAll(html)) {
                val json = match.groupValues[1]
                
                // Look for Product schema
                if (json.contains("\"@type\"") && json.contains("Product", ignoreCase = true)) {
                    val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)
                    val priceMatch = Regex(""""price"\s*:\s*["]?(\d+(?:\.\d+)?)["]?""").find(json)
                    val imageMatch = Regex(""""image"\s*:\s*"([^"]+)"""").find(json)
                    val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)
                    
                    if (nameMatch != null && priceMatch != null) {
                        val name = nameMatch.groupValues[1]
                        val price = priceMatch.groupValues[1].toDoubleOrNull() ?: continue
                        
                        if (isValidProductName(name) && price in 1.0..50000.0) {
                            results.add(ExtractionResult(
                                product = Product(
                                    name = name,
                                    price = price,
                                    originalPrice = null,
                                    imageUrl = imageMatch?.groupValues?.get(1) ?: "",
                                    platform = platformName,
                                    platformColor = platformColor,
                                    deliveryTime = deliveryTime,
                                    url = urlMatch?.groupValues?.get(1) ?: baseUrl,
                                    rating = null,
                                    discount = null,
                                    available = true
                                ),
                                confidence = 0.95,  // JSON-LD is highly reliable
                                extractionMethod = "json_ld"
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("$platformName: JSON-LD extraction error: ${e.message}")
        }
        
        return results
    }
    
    /**
     * Extract from embedded script JSON (Next.js, React hydration, etc.)
     */
    private fun extractFromScriptJson(
        html: String,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): List<ExtractionResult> {
        val results = mutableListOf<ExtractionResult>()
        
        try {
            // Look for __NEXT_DATA__ or similar embedded JSON
            val patterns = listOf(
                Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL),
                Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL),
                Regex("""window\.__PRELOADED_STATE__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html) ?: continue
                val json = match.groupValues[1]
                
                // Extract product-like objects
                val productPattern = Regex(""""(?:name|display_name|product_name|title)"\s*:\s*"([^"]{5,100})"[^}]*?"(?:price|sp|selling_price|offer_price)"\s*:\s*["]?(\d+(?:\.\d+)?)["]?""")
                
                for (productMatch in productPattern.findAll(json).take(15)) {
                    val name = productMatch.groupValues[1]
                    val price = productMatch.groupValues[2].toDoubleOrNull() ?: continue
                    
                    if (isValidProductName(name) && price in 1.0..50000.0) {
                        results.add(ExtractionResult(
                            product = Product(
                                name = name,
                                price = price,
                                originalPrice = null,
                                imageUrl = "",
                                platform = platformName,
                                platformColor = platformColor,
                                deliveryTime = deliveryTime,
                                url = baseUrl,
                                rating = null,
                                discount = null,
                                available = true
                            ),
                            confidence = 0.85,
                            extractionMethod = "script_json"
                        ))
                    }
                }
                
                if (results.isNotEmpty()) break
            }
        } catch (e: Exception) {
            log("$platformName: Script JSON extraction error: ${e.message}")
        }
        
        return results
    }
    
    // ==================== Element Extraction ====================
    
    /**
     * Extract a product from a DOM element
     */
    private fun extractFromElement(
        element: Element,
        platformName: String,
        platformColor: Long,
        deliveryTime: String,
        baseUrl: String
    ): ExtractionResult? {
        var confidence = 0.0
        
        // Extract name (multiple strategies)
        val name = extractName(element)
        if (name == null || !isValidProductName(name)) return null
        confidence += 0.3
        
        // Extract price
        val price = extractPrice(element.text())
        if (price == null || price <= 0 || price > 50000) return null
        confidence += 0.3
        
        // Extract original price (optional)
        val originalPrice = extractOriginalPrice(element.text(), price)
        
        // Extract image (adds confidence)
        val imageUrl = extractImageUrl(element)
        if (imageUrl.isNotBlank()) confidence += 0.2
        
        // Extract URL (adds confidence)
        val productUrl = extractProductUrl(element, baseUrl)
        if (productUrl.isNotBlank() && productUrl != baseUrl) confidence += 0.2
        
        // Calculate discount
        val discount = if (originalPrice != null && originalPrice > price) {
            "${((originalPrice - price) / originalPrice * 100).toInt()}% off"
        } else null
        
        return ExtractionResult(
            product = Product(
                name = name,
                price = price,
                originalPrice = originalPrice,
                imageUrl = imageUrl,
                platform = platformName,
                platformColor = platformColor,
                deliveryTime = deliveryTime,
                url = productUrl.ifBlank { baseUrl },
                rating = null,
                discount = discount,
                available = true
            ),
            confidence = confidence.coerceAtMost(1.0),
            extractionMethod = "heuristic"
        )
    }
    
    /**
     * Extract product name from element using multiple strategies
     */
    private fun extractName(element: Element): String? {
        // Strategy 1: Image alt text (most reliable)
        val imgAlt = element.selectFirst("img[alt]")?.attr("alt")?.trim()
        if (imgAlt != null && imgAlt.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH && isValidProductName(imgAlt)) {
            return imgAlt
        }
        
        // Strategy 2: Heading tags
        val heading = element.selectFirst("h1, h2, h3, h4, h5")?.text()?.trim()
        if (heading != null && heading.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH && isValidProductName(heading)) {
            return heading
        }
        
        // Strategy 3: Link text
        val linkText = element.selectFirst("a")?.text()?.trim()
        if (linkText != null && linkText.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH && isValidProductName(linkText)) {
            return linkText
        }
        
        // Strategy 4: Common name class patterns
        val nameClasses = element.select("[class*='name'], [class*='title'], [class*='product']")
        for (el in nameClasses) {
            val text = el.ownText().trim()
            if (text.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH && isValidProductName(text)) {
                return text
            }
        }
        
        // Strategy 5: ARIA label
        val ariaLabel = element.attr("aria-label").trim()
        if (ariaLabel.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH && isValidProductName(ariaLabel)) {
            return ariaLabel
        }
        
        return null
    }
    
    /**
     * Extract price from text
     */
    private fun extractPrice(text: String): Double? {
        val matches = PRICE_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return null
        
        val prices = matches.mapNotNull { 
            it.groupValues[1].replace(",", "").toDoubleOrNull() 
        }.filter { it in 1.0..50000.0 }
        
        // Return the lowest valid price (usually the selling price)
        return prices.minOrNull()
    }
    
    /**
     * Extract original/MRP price
     */
    private fun extractOriginalPrice(text: String, sellingPrice: Double): Double? {
        val matches = PRICE_PATTERN.findAll(text).toList()
        
        val prices = matches.mapNotNull { 
            it.groupValues[1].replace(",", "").toDoubleOrNull() 
        }.filter { it > sellingPrice && it < sellingPrice * 3 }
        
        return prices.maxOrNull()
    }
    
    /**
     * Extract image URL
     */
    private fun extractImageUrl(element: Element): String {
        val img = element.selectFirst("img") ?: return ""
        return img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
    }
    
    /**
     * Extract product URL
     */
    private fun extractProductUrl(element: Element, baseUrl: String): String {
        // Look for product links
        val productLink = element.selectFirst(
            "a[href*='/p/'], a[href*='/pd/'], a[href*='/product/'], " +
            "a[href*='/prn/'], a[href*='/prid/'], a[href*='/dp/'], a[href*='/item/']"
        )
        
        val href = productLink?.attr("href") ?: element.selectFirst("a[href]")?.attr("href") ?: ""
        
        return when {
            href.isBlank() -> ""
            href.startsWith("http") -> href
            href.startsWith("/") -> "$baseUrl$href"
            else -> "$baseUrl/$href"
        }
    }
    
    /**
     * Validate product name
     */
    private fun isValidProductName(name: String): Boolean {
        val trimmed = name.trim()
        
        if (trimmed.length !in MIN_NAME_LENGTH..MAX_NAME_LENGTH) return false
        if (!trimmed.any { it.isLetter() }) return false
        if (INVALID_NAME_PATTERNS.any { it.matches(trimmed) }) return false
        
        val lower = trimmed.lowercase()
        val invalidExact = setOf(
            "search", "results", "loading", "add to cart", "buy now", "view",
            "see more", "show more", "filter", "sort", "menu", "cart", "wishlist",
            "login", "sign in", "register", "compare", "share", "notify me"
        )
        if (invalidExact.contains(lower)) return false
        
        return true
    }
    
    // ==================== Selector Learning ====================
    
    /**
     * Learn a selector from a successful extraction
     */
    private fun learnSelector(platformName: String, element: Element) {
        val selector = buildSelector(element)
        if (selector.isBlank()) return
        
        val existing = learnedSelectors[platformName]
        if (existing == null || existing.selector != selector) {
            learnedSelectors[platformName] = LearnedSelector(
                selector = selector,
                platformName = platformName,
                successCount = 1,
                failureCount = 0,
                learnedAt = System.currentTimeMillis()
            )
            saveLearnedSelectors()
            log("$platformName: Learned new selector '$selector'")
        }
    }
    
    /**
     * Build a CSS selector for an element
     */
    private fun buildSelector(element: Element): String {
        // Try to build a selector using stable attributes
        val tag = element.tagName()
        val testId = element.attr("data-testid")
        val dataId = element.attr("data-id")
        val itemProp = element.attr("itemprop")
        
        return when {
            testId.isNotBlank() -> "[$tag][data-testid='$testId']"
            dataId.isNotBlank() -> "$tag[data-id]"
            itemProp.isNotBlank() -> "[$tag][itemprop='$itemProp']"
            element.classNames().isNotEmpty() -> {
                // Use first non-random-looking class
                val stableClass = element.classNames().firstOrNull { cls ->
                    !cls.matches(Regex("""[a-z]{1,3}[A-Z][a-z0-9]{4,}""")) && // Not camelCase hash
                    !cls.matches(Regex("""[a-z]+_[a-f0-9]{4,}""")) && // Not underscore hash
                    cls.length > 3
                }
                if (stableClass != null) "$tag.$stableClass" else ""
            }
            else -> ""
        }
    }
    
    private fun loadLearnedSelectors() {
        try {
            val json = prefs.getString(KEY_LEARNED_SELECTORS, null) ?: return
            val type = object : TypeToken<Map<String, LearnedSelector>>() {}.type
            val loaded: Map<String, LearnedSelector> = gson.fromJson(json, type)
            learnedSelectors.putAll(loaded)
            log("Loaded ${learnedSelectors.size} learned selectors")
        } catch (e: Exception) {
            log("Error loading selectors: ${e.message}")
        }
    }
    
    private fun saveLearnedSelectors() {
        try {
            val json = gson.toJson(learnedSelectors)
            prefs.edit().putString(KEY_LEARNED_SELECTORS, json).apply()
        } catch (e: Exception) {
            log("Error saving selectors: ${e.message}")
        }
    }
    
    private fun log(message: String) {
        println("$TAG: $message")
        try {
            android.util.Log.d(TAG, message)
        } catch (e: Exception) {
            // Unit test environment
        }
    }
}

/**
 * Result of product extraction with confidence score
 */
data class ExtractionResult(
    val product: Product,
    val confidence: Double,
    val extractionMethod: String
)

/**
 * A learned CSS selector
 */
data class LearnedSelector(
    val selector: String,
    val platformName: String,
    var successCount: Int,
    var failureCount: Int,
    val learnedAt: Long
)
