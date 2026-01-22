package com.pricehunt.data.search

import com.pricehunt.data.model.Product
import kotlin.math.abs

/**
 * Intelligent Search System
 * 
 * Adds context-awareness to search queries and result ranking:
 * 1. Detects search intent (fruit, dairy, snack, etc.)
 * 2. Parses and normalizes quantities (200g, 1kg, 500ml, 1L)
 * 3. Calculates per-unit price for fair comparison
 * 4. Ranks results by relevance and quantity match
 * 5. Filters out loosely related items
 */
object SearchIntelligence {
    
    // Quantity parsing patterns - handles formats like "500g", "1 L", "1.5kg", "500 ml", "1L", "(500 g)"
    private val QUANTITY_PATTERN = Regex(
        """(\d+(?:\.\d+)?)\s*(g|gm|gram|grams|kg|kilo|kilos|kilogram|ml|l|ltr|litre|liter|lt|pc|pcs|piece|pieces|pack|nos|unit|units)(?:\s|$|\)|,)""",
        RegexOption.IGNORE_CASE
    )
    
    // Additional patterns for extracting quantity
    private val QUANTITY_PATTERNS = listOf(
        // "500g", "1kg", "500ml", "1l"
        Regex("""(\d+(?:\.\d+)?)\s*(g|gm|kg|ml|l|ltr|lt)\b""", RegexOption.IGNORE_CASE),
        // "500 g", "1 L", "500 ml"
        Regex("""(\d+(?:\.\d+)?)\s+(g|gm|kg|ml|l|ltr|lt)\b""", RegexOption.IGNORE_CASE),
        // "(500g)", "(1 L)", "| 500ml"
        Regex("""[|(]\s*(\d+(?:\.\d+)?)\s*(g|gm|kg|ml|l|ltr|lt)\s*[|)]?""", RegexOption.IGNORE_CASE),
        // "500gm", "500 gm"
        Regex("""(\d+(?:\.\d+)?)\s*gm""", RegexOption.IGNORE_CASE),
        // "1 Litre", "500 Gram"
        Regex("""(\d+(?:\.\d+)?)\s*(litre|liter|gram|kilogram)s?""", RegexOption.IGNORE_CASE),
        // "Pack of 6", "6 pcs", "6 pieces"  
        Regex("""(?:pack\s+of\s+)?(\d+)\s*(pc|pcs|piece|pieces|nos|units?)""", RegexOption.IGNORE_CASE)
    )
    
    // Unit conversion to base units (grams for weight, ml for volume)
    private val UNIT_TO_BASE = mapOf(
        // Weight -> grams
        "g" to 1.0,
        "gm" to 1.0,
        "gram" to 1.0,
        "grams" to 1.0,
        "kg" to 1000.0,
        "kilo" to 1000.0,
        "kilos" to 1000.0,
        "kilogram" to 1000.0,
        // Volume -> ml
        "ml" to 1.0,
        "l" to 1000.0,
        "ltr" to 1000.0,
        "litre" to 1000.0,
        "liter" to 1000.0,
        // Count -> pieces
        "pc" to 1.0,
        "pcs" to 1.0,
        "piece" to 1.0,
        "pieces" to 1.0,
        "pack" to 1.0,
        "nos" to 1.0,
        "unit" to 1.0,
        "units" to 1.0
    )
    
    // Unit type classification
    private val UNIT_TYPE = mapOf(
        "g" to "weight", "gm" to "weight", "gram" to "weight", "grams" to "weight",
        "kg" to "weight", "kilo" to "weight", "kilos" to "weight", "kilogram" to "weight",
        "ml" to "volume", "l" to "volume", "ltr" to "volume", "litre" to "volume", "liter" to "volume",
        "pc" to "count", "pcs" to "count", "piece" to "count", "pieces" to "count",
        "pack" to "count", "nos" to "count", "unit" to "count", "units" to "count"
    )

    // Common grocery categories with their typical items
    private val CATEGORY_KEYWORDS = mapOf(
        "fruit" to listOf("apple", "banana", "orange", "mango", "grape", "strawberry", "kiwi", 
            "papaya", "watermelon", "pomegranate", "pineapple", "guava", "pear", "peach", 
            "cherry", "blueberry", "raspberry", "lemon", "lime", "coconut", "fig", "dates",
            "litchi", "lychee", "jackfruit", "custard apple", "sapota", "chikoo", "plum"),
        
        "vegetable" to listOf("potato", "tomato", "onion", "carrot", "cabbage", "spinach",
            "broccoli", "cauliflower", "capsicum", "cucumber", "beans", "peas", "corn",
            "ladyfinger", "okra", "bhindi", "brinjal", "eggplant", "beetroot", "radish",
            "ginger", "garlic", "coriander", "mint", "curry leaves", "mushroom", "lettuce"),
        
        "dairy" to listOf("milk", "curd", "yogurt", "paneer", "cheese", "butter", "ghee",
            "cream", "buttermilk", "lassi", "khoya", "mawa"),
        
        "bread" to listOf("bread", "pav", "bun", "roti", "naan", "paratha", "chapati", 
            "toast", "bagel", "croissant"),
        
        "rice" to listOf("rice", "basmati", "biryani rice", "sona masoori", "kolam"),
        
        "egg" to listOf("egg", "eggs", "anda"),
        
        "meat" to listOf("chicken", "mutton", "fish", "prawns", "shrimp", "lamb", "pork"),
        
        "snack" to listOf("chips", "namkeen", "biscuit", "cookies", "wafer", "popcorn", 
            "mixture", "mathri", "chakli")
    )

    // Words that indicate a derived/processed product (not the primary item)
    private val DERIVATIVE_INDICATORS = listOf(
        "juice", "jam", "jelly", "sauce", "syrup", "flavour", "flavor", "flavored", 
        "flavoured", "essence", "extract", "candy", "chocolate", "ice cream", "icecream",
        "shake", "smoothie", "squash", "drink", "beverage", "powder", "mix", "bar",
        "cake", "pastry", "muffin", "cookie", "biscuit", "wafer", "toffee", "gummy",
        "preserve", "marmalade", "spread", "topping", "filling", "yogurt", "yoghurt",
        "milkshake", "lassi", "sharbat", "sherbet"
    )

    // Words that indicate fresh/raw produce
    private val FRESH_INDICATORS = listOf(
        "fresh", "organic", "natural", "raw", "whole", "pack", "kg", "gm", "gram",
        "500g", "250g", "1kg", "bunch", "piece", "pcs", "nos"
    )

    /**
     * Analyze search query and determine intent
     */
    fun analyzeQuery(query: String): SearchIntent {
        val normalizedQuery = query.lowercase().trim()
        val words = normalizedQuery.split(" ").filter { it.isNotBlank() }
        
        // Parse quantity from query (e.g., "milk 500ml" -> 500ml)
        val requestedQuantity = parseQuantity(normalizedQuery)
        
        // Remove quantity from query to get primary keyword
        val queryWithoutQuantity = QUANTITY_PATTERN.replace(normalizedQuery, "").trim()
        val primaryWords = queryWithoutQuantity.split(" ").filter { it.isNotBlank() }
        
        // Single word searches are most ambiguous - likely want the primary item
        val isSingleWord = primaryWords.size == 1
        
        // Detect category
        val detectedCategory = detectCategory(normalizedQuery)
        
        // Check if query explicitly mentions a derivative
        val isExplicitDerivative = DERIVATIVE_INDICATORS.any { normalizedQuery.contains(it) }
        
        // Check if query mentions fresh/raw
        val wantsFresh = FRESH_INDICATORS.any { normalizedQuery.contains(it) }
        
        return SearchIntent(
            originalQuery = query,
            normalizedQuery = normalizedQuery,
            primaryKeyword = primaryWords.firstOrNull() ?: words.firstOrNull() ?: query,
            category = detectedCategory,
            isSingleWordSearch = isSingleWord,
            wantsPrimaryItem = isSingleWord && !isExplicitDerivative,
            wantsFresh = wantsFresh,
            isExplicitDerivative = isExplicitDerivative,
            requestedQuantity = requestedQuantity
        )
    }
    
    /**
     * Parse quantity from text (query or product name)
     * Tries multiple patterns to handle various formats
     */
    fun parseQuantity(text: String): ParsedQuantity? {
        val lowerText = text.lowercase()
        
        // Try each pattern until we find a match
        for (pattern in QUANTITY_PATTERNS) {
            val match = pattern.find(lowerText)
            if (match != null && match.groupValues.size >= 3) {
                val value = match.groupValues[1].toDoubleOrNull() ?: continue
                val rawUnit = match.groupValues[2].lowercase()
                
                // Normalize unit names
                val unit = when (rawUnit) {
                    "lt" -> "l"
                    "litre", "liter" -> "l"
                    "gram" -> "g"
                    "kilogram" -> "kg"
                    else -> rawUnit
                }
                
                val baseMultiplier = UNIT_TO_BASE[unit] ?: continue
                val unitType = UNIT_TYPE[unit] ?: continue
                val normalizedValue = value * baseMultiplier
                
                return ParsedQuantity(
                    originalValue = value,
                    originalUnit = unit,
                    normalizedValue = normalizedValue,
                    baseUnit = when (unitType) {
                        "weight" -> "g"
                        "volume" -> "ml"
                        "count" -> "pc"
                        else -> unit
                    },
                    unitType = unitType
                )
            }
        }
        
        // Fallback: try the main pattern
        val match = QUANTITY_PATTERN.find(lowerText) ?: return null
        
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val rawUnit = match.groupValues[2].lowercase()
        
        // Normalize unit names  
        val unit = when (rawUnit) {
            "lt" -> "l"
            "litre", "liter" -> "l"
            "gram" -> "g"
            "kilogram" -> "kg"
            else -> rawUnit
        }
        
        val baseMultiplier = UNIT_TO_BASE[unit] ?: return null
        val unitType = UNIT_TYPE[unit] ?: return null
        val normalizedValue = value * baseMultiplier
        
        return ParsedQuantity(
            originalValue = value,
            originalUnit = unit,
            normalizedValue = normalizedValue,
            baseUnit = when (unitType) {
                "weight" -> "g"
                "volume" -> "ml"
                "count" -> "pc"
                else -> unit
            },
            unitType = unitType
        )
    }

    /**
     * Detect the category of the search term
     */
    private fun detectCategory(query: String): String? {
        for ((category, keywords) in CATEGORY_KEYWORDS) {
            if (keywords.any { query.contains(it) || it.contains(query) }) {
                return category
            }
        }
        return null
    }

    /**
     * Rank and filter products based on search intent
     */
    fun rankResults(products: List<Product>, intent: SearchIntent): List<Product> {
        if (products.isEmpty()) return products
        
        // Score each product
        val scoredProducts = products.map { product ->
            val score = calculateRelevanceScore(product, intent)
            val productQuantity = parseQuantity(product.name)
            Pair(product, ScoredProduct(score, productQuantity))
        }
        
        // Sort by score (higher is better) and filter out very low scores
        val minScore = if (intent.wantsPrimaryItem) 30 else 10
        
        return scoredProducts
            .filter { it.second.score >= minScore }
            .sortedByDescending { it.second.score }
            .map { it.first }
    }
    
    /**
     * Enhanced ranking that groups by quantity for comparison
     * Returns products grouped by similar quantities
     */
    fun rankAndGroupByQuantity(products: List<Product>, intent: SearchIntent): QuantityGroupedResults {
        if (products.isEmpty()) return QuantityGroupedResults(emptyList(), null)
        
        // Parse quantities and score all products
        val analyzedProducts = products.mapNotNull { product ->
            val score = calculateRelevanceScore(product, intent)
            if (score < 10) return@mapNotNull null
            
            val quantity = parseQuantity(product.name)
            val perUnitPrice = if (quantity != null && quantity.normalizedValue > 0) {
                product.price / quantity.normalizedValue
            } else null
            
            AnalyzedProduct(
                product = product,
                relevanceScore = score,
                quantity = quantity,
                perUnitPrice = perUnitPrice
            )
        }
        
        // If user requested specific quantity, prioritize matching products
        val requestedQty = intent.requestedQuantity
        if (requestedQty != null) {
            val (matching, others) = analyzedProducts.partition { ap ->
                ap.quantity != null && 
                ap.quantity.unitType == requestedQty.unitType &&
                isQuantitySimilar(ap.quantity.normalizedValue, requestedQty.normalizedValue)
            }
            
            val sortedMatching = matching.sortedWith(
                compareByDescending<AnalyzedProduct> { it.relevanceScore }
                    .thenBy { it.perUnitPrice ?: Double.MAX_VALUE }
            )
            
            val sortedOthers = others.sortedByDescending { it.relevanceScore }
            
            return QuantityGroupedResults(
                products = (sortedMatching + sortedOthers).map { it.product },
                matchingQuantity = requestedQty,
                analyzedProducts = sortedMatching + sortedOthers
            )
        }
        
        // No specific quantity requested - sort by relevance, then by per-unit price
        val sorted = analyzedProducts.sortedWith(
            compareByDescending<AnalyzedProduct> { it.relevanceScore }
                .thenBy { it.perUnitPrice ?: Double.MAX_VALUE }
        )
        
        return QuantityGroupedResults(
            products = sorted.map { it.product },
            matchingQuantity = null,
            analyzedProducts = sorted
        )
    }
    
    /**
     * Check if two quantities are similar (within 20% tolerance)
     */
    private fun isQuantitySimilar(qty1: Double, qty2: Double): Boolean {
        if (qty1 == 0.0 || qty2 == 0.0) return false
        val ratio = qty1 / qty2
        return ratio in 0.8..1.2
    }
    
    /**
     * Calculate per-unit price for a product
     */
    fun calculatePerUnitPrice(product: Product): PerUnitPrice? {
        val quantity = parseQuantity(product.name)
        
        if (quantity == null) {
            // Debug: show what we're trying to parse
            if (product.name.contains(Regex("""\d"""))) {
                println("SearchIntelligence: ❌ Couldn't parse quantity from '${product.name}'")
            }
            return null
        }
        
        if (quantity.normalizedValue <= 0) return null
        
        val perUnit = product.price / quantity.normalizedValue
        
        // Format nicely (per 100g, per 100ml, or per piece)
        val (displayValue, displayUnit) = when (quantity.unitType) {
            "weight" -> Pair(perUnit * 100, "100g")
            "volume" -> Pair(perUnit * 100, "100ml")
            "count" -> Pair(perUnit, "pc")
            else -> Pair(perUnit, quantity.baseUnit)
        }
        
        println("SearchIntelligence: ✓ ${product.name.take(30)} → ₹${String.format("%.1f", displayValue)}/$displayUnit")
        
        return PerUnitPrice(
            pricePerBaseUnit = perUnit,
            displayPrice = displayValue,
            displayUnit = displayUnit,
            productQuantity = quantity
        )
    }

    /**
     * Calculate relevance score for a product (0-100)
     */
    private fun calculateRelevanceScore(product: Product, intent: SearchIntent): Int {
        val name = product.name.lowercase()
        val primaryKeyword = intent.primaryKeyword
        
        var score = 0
        
        // 1. Exact keyword match bonus (40 points)
        if (name.contains(primaryKeyword)) {
            score += 40
        }
        
        // 2. Primary item bonus (30 points) - name starts with or is primarily the search term
        val nameWords = name.split(" ", ",", "-", "(", ")").filter { it.isNotBlank() }
        if (nameWords.firstOrNull()?.contains(primaryKeyword) == true) {
            score += 30
        } else if (nameWords.take(3).any { it.contains(primaryKeyword) }) {
            score += 20
        }
        
        // 3. Fresh/organic indicator bonus (15 points)
        if (intent.wantsPrimaryItem || intent.wantsFresh) {
            if (FRESH_INDICATORS.any { name.contains(it) }) {
                score += 15
            }
        }
        
        // 4. Category match bonus (10 points)
        if (intent.category != null) {
            val categoryKeywords = CATEGORY_KEYWORDS[intent.category] ?: emptyList()
            if (categoryKeywords.any { name.contains(it) }) {
                score += 10
            }
        }
        
        // 5. Derivative penalty (-30 points if user wants primary item)
        if (intent.wantsPrimaryItem && !intent.isExplicitDerivative) {
            val isDerivative = DERIVATIVE_INDICATORS.any { name.contains(it) }
            if (isDerivative) {
                score -= 30
            }
        }
        
        // 6. Quantity match bonus (20 points if matches requested quantity)
        val requestedQty = intent.requestedQuantity
        if (requestedQty != null) {
            val productQty = parseQuantity(name)
            if (productQty != null && 
                productQty.unitType == requestedQty.unitType &&
                isQuantitySimilar(productQty.normalizedValue, requestedQty.normalizedValue)) {
                score += 20
            }
        }
        
        // 7. Name length penalty (prefer shorter, more specific names)
        if (name.length > 50) {
            score -= 5
        }
        if (name.length > 80) {
            score -= 5
        }
        
        // 8. Bonus for having quantity indicator (likely actual product)
        val hasQuantity = QUANTITY_PATTERN.containsMatchIn(name)
        if (hasQuantity) {
            score += 10
        }
        
        return score.coerceIn(0, 100)
    }

    /**
     * Get smart search suggestions based on query
     */
    fun getSuggestions(query: String): List<String> {
        val intent = analyzeQuery(query)
        val suggestions = mutableListOf<String>()
        
        // If single word search for a known category item, suggest specific versions
        if (intent.isSingleWordSearch && intent.category != null) {
            when (intent.category) {
                "fruit" -> {
                    suggestions.add("$query fresh")
                    suggestions.add("$query organic")
                }
                "vegetable" -> {
                    suggestions.add("$query fresh")
                    suggestions.add("$query organic")
                }
                "dairy" -> {
                    suggestions.add("$query full cream")
                    suggestions.add("$query toned")
                }
            }
        }
        
        // Add common derivatives as options
        if (intent.isSingleWordSearch) {
            suggestions.add("$query juice")
            suggestions.add("$query jam")
        }
        
        return suggestions.take(4)
    }

    /**
     * Modify query for better platform search results
     * Returns the original query if no modification needed
     */
    fun getOptimizedQuery(query: String, platform: String): String {
        val intent = analyzeQuery(query)
        
        // For fruits/vegetables single word searches, some platforms work better with "fresh" prefix
        if (intent.isSingleWordSearch && intent.category in listOf("fruit", "vegetable")) {
            return when {
                platform.contains("BigBasket", ignoreCase = true) -> "fresh $query"
                platform.contains("Zepto", ignoreCase = true) -> query // Zepto handles single words well
                platform.contains("Blinkit", ignoreCase = true) -> query
                platform.contains("Instamart", ignoreCase = true) -> query
                else -> query
            }
        }
        
        return query
    }
}

/**
 * Data class representing parsed search intent
 */
data class SearchIntent(
    val originalQuery: String,
    val normalizedQuery: String,
    val primaryKeyword: String,
    val category: String?,
    val isSingleWordSearch: Boolean,
    val wantsPrimaryItem: Boolean,
    val wantsFresh: Boolean,
    val isExplicitDerivative: Boolean,
    val requestedQuantity: ParsedQuantity? = null
)

/**
 * Parsed quantity from product name or search query
 */
data class ParsedQuantity(
    val originalValue: Double,      // e.g., 500
    val originalUnit: String,       // e.g., "ml"
    val normalizedValue: Double,    // e.g., 500.0 (in base units - g or ml)
    val baseUnit: String,           // e.g., "ml"
    val unitType: String            // "weight", "volume", or "count"
) {
    fun toDisplayString(): String {
        return when {
            originalValue >= 1000 && baseUnit == "g" -> "${(originalValue / 1000).toInt()}kg"
            originalValue >= 1000 && baseUnit == "ml" -> "${(originalValue / 1000).toInt()}L"
            else -> "${originalValue.toInt()}$originalUnit"
        }
    }
}

/**
 * Per-unit price calculation result
 */
data class PerUnitPrice(
    val pricePerBaseUnit: Double,   // Price per gram or ml
    val displayPrice: Double,       // Price per 100g/100ml for display
    val displayUnit: String,        // "100g", "100ml", or "pc"
    val productQuantity: ParsedQuantity
) {
    fun toDisplayString(): String {
        return "₹${String.format("%.1f", displayPrice)}/$displayUnit"
    }
}

/**
 * Analyzed product with scoring and quantity info
 */
data class AnalyzedProduct(
    val product: Product,
    val relevanceScore: Int,
    val quantity: ParsedQuantity?,
    val perUnitPrice: Double?       // Price per base unit (g or ml)
)

/**
 * Results grouped by quantity
 */
data class QuantityGroupedResults(
    val products: List<Product>,
    val matchingQuantity: ParsedQuantity?,
    val analyzedProducts: List<AnalyzedProduct> = emptyList()
)

/**
 * Internal scoring helper
 */
private data class ScoredProduct(
    val score: Int,
    val quantity: ParsedQuantity?
)
