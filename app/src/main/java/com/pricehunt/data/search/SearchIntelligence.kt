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
        """(\d+(?:[.,]\d+)?)\s*(g|gm|gram|grams|kg|kilo|kilos|kilogram|ml|l|ltr|litre|liter|lt|pc|pcs|piece|pieces|pack|nos|unit|units)(?:\s|$|\)|,)""",
        RegexOption.IGNORE_CASE
    )
    
    // All supported unit patterns (for regex building)
    private const val WEIGHT_UNITS = "g|gm|gram|grams|kg|kilo|kilos|kilogram|kilograms|mg|milligram|milligrams|lb|lbs|pound|pounds|oz|ounce|ounces"
    private const val VOLUME_UNITS = "ml|l|lt|ltr|litre|liter|litres|liters|cl|dl|fl oz|floz|gallon|gallons|pint|pints|cup|cups"
    private const val COUNT_UNITS = "pc|pcs|piece|pieces|pack|packs|nos|no|unit|units|egg|eggs|dozen|doz|pair|pairs|set|sets|roll|rolls|sheet|sheets|slice|slices|serving|servings|portion|portions|sachet|sachets|pouch|pouches|stick|sticks|bar|bars|cube|cubes|capsule|capsules|cap|caps|tablet|tablets|tab|tabs|strip|strips|bottle|bottles|can|cans|box|boxes|jar|jars|tin|tins|bag|bags|bunch|bunches|pull|pulls|wipe|wipes|ply"
    private const val LENGTH_UNITS = "m|meter|meters|metre|metres|cm|mm|inch|inches|ft|feet|foot|yard|yards|yd"
    private const val ALL_UNITS = "$WEIGHT_UNITS|$VOLUME_UNITS|$COUNT_UNITS|$LENGTH_UNITS"
    
    // Additional patterns for extracting quantity - ordered by specificity (more specific first)
    private val QUANTITY_PATTERNS = listOf(
        // Combo/multiplier patterns (MUST be first to capture full quantity like "2x500g" = 1000g)
        // "2x500g", "2 x 500ml", "3X1kg"
        Regex("""(\d+)\s*[xX×]\s*(\d+(?:\.\d+)?)\s*($WEIGHT_UNITS|$VOLUME_UNITS)\b""", RegexOption.IGNORE_CASE),
        // "500g x 2", "1L × 4", "200ml x 6"
        Regex("""(\d+(?:\.\d+)?)\s*($WEIGHT_UNITS|$VOLUME_UNITS)\s*[xX×]\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        
        // Pack patterns with size - "Pack of 6 x 200ml", "6-Pack 330ml"
        Regex("""(?:pack\s+of\s+)?(\d+)\s*[xX×-]?\s*(?:pack\s+)?(\d+(?:\.\d+)?)\s*($WEIGHT_UNITS|$VOLUME_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Net weight/volume patterns - "Net Wt. 500g", "Net Weight: 1kg", "Net Content: 1L"
        Regex("""net\s*(?:wt\.?|weight|content|vol\.?|volume)[\s:]*(\d+(?:\.\d+)?)\s*($ALL_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Parenthetical at end - "Oil (1L)", "Butter (500g)", "(Pack of 6)", "(100 Tablets)"
        Regex("""\(\s*(\d+(?:\.\d+)?)\s*($ALL_UNITS)\s*\)""", RegexOption.IGNORE_CASE),
        Regex("""\(\s*(?:pack\s+of\s+)?(\d+)\s*($COUNT_UNITS)\s*\)""", RegexOption.IGNORE_CASE),
        
        // Comma-separated format - "Flour, 1kg", "Oil, 500ml", "Tissue, 100 Pulls"
        Regex(""",\s*(\d+(?:\.\d+)?)\s*($ALL_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Pipe-separated format - "| 500g" or "500ml |"
        Regex("""\|\s*(\d+(?:\.\d+)?)\s*($ALL_UNITS)""", RegexOption.IGNORE_CASE),
        
        // Hyphen at end - "Mustard Oil - 1L", "Sugar - 500g", "Tissue Box - 200 Pulls"
        Regex("""-\s*(\d+(?:\.\d+)?)\s*($ALL_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Tablets/Capsules specific - "Strip of 10", "10 Tab Strip", "30 Capsules"
        Regex("""(?:strip\s+of\s+)?(\d+)\s*(tablet|tablets|tab|tabs|capsule|capsules|cap|caps)\b""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*(tablet|tab|capsule|cap)s?\s*(?:strip|pack)?\b""", RegexOption.IGNORE_CASE),
        
        // Wipes/Pulls specific - "72 Wipes", "100 Pulls"
        Regex("""(\d+)\s*(wipe|wipes|pull|pulls|sheet|sheets)\b""", RegexOption.IGNORE_CASE),
        
        // Dozen format - "1 Dozen", "2 Doz"
        Regex("""(\d+(?:\.\d+)?)\s*(dozen|doz)\b""", RegexOption.IGNORE_CASE),
        
        // Standard weight/volume formats - "500g", "1kg", "500ml", "1l", "1.5L"
        Regex("""(\d+(?:\.\d+)?)\s*($WEIGHT_UNITS|$VOLUME_UNITS)\b""", RegexOption.IGNORE_CASE),
        // With space - "500 g", "1 L", "500 ml"
        Regex("""(\d+(?:\.\d+)?)\s+($WEIGHT_UNITS|$VOLUME_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Full word units - "1 Litre", "500 Gram", "2 Litres", "100 Grams"
        Regex("""(\d+(?:\.\d+)?)\s*(litres?|liters?|grams?|kilograms?|milligrams?|ounces?|pounds?|gallons?|pints?|cups?|inches?|meters?|metres?|feet|foot)\b""", RegexOption.IGNORE_CASE),
        
        // "500gm", "500 gm" (common Indian format)
        Regex("""(\d+(?:\.\d+)?)\s*gm\b""", RegexOption.IGNORE_CASE),
        
        // Pouch/Bottle/Jar with size - "1L Pouch", "500ml Bottle"
        Regex("""(\d+(?:\.\d+)?)\s*($WEIGHT_UNITS|$VOLUME_UNITS)\s*(?:pouch|bottle|jar|can|pack|box|bag|sachet|tin|tube|container)\b""", RegexOption.IGNORE_CASE),
        
        // Pieces/count formats - "Pack of 6", "6 pcs", "6 pieces", "6 Nos", "Set of 4"
        Regex("""(?:pack|set|box|bundle)\s+of\s+(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*($COUNT_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Eggs special - "6 Eggs", "12 eggs", "30 Eggs Pack"
        Regex("""(\d+)\s*(eggs?)\b""", RegexOption.IGNORE_CASE),
        
        // Length formats - "5m", "100cm", "12 inches"
        Regex("""(\d+(?:\.\d+)?)\s*($LENGTH_UNITS)\b""", RegexOption.IGNORE_CASE),
        
        // Large numbers with comma - "1,000g", "1,500ml" 
        Regex("""(\d{1,3}(?:,\d{3})+)\s*($ALL_UNITS)\b""", RegexOption.IGNORE_CASE)
    )

    private val COUNT_ONLY_PATTERNS = listOf(
        Regex("""(?:pack|set|box|bundle)\s+of\s+(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)\s*(pc|pcs|piece|pieces|pack|packs|nos|no|unit|units|egg|eggs)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)\s*(dozen|doz)\b""", RegexOption.IGNORE_CASE)
    )
    
    // Unit conversion to base units (grams for weight, ml for volume, pieces for count, etc.)
    private val UNIT_TO_BASE = mapOf(
        // Weight -> grams (base)
        "g" to 1.0,
        "gm" to 1.0,
        "gram" to 1.0,
        "grams" to 1.0,
        "kg" to 1000.0,
        "kilo" to 1000.0,
        "kilos" to 1000.0,
        "kilogram" to 1000.0,
        "kilograms" to 1000.0,
        "mg" to 0.001,
        "milligram" to 0.001,
        "milligrams" to 0.001,
        "quintal" to 100000.0,
        "ton" to 1000000.0,
        "tonne" to 1000000.0,
        "lb" to 453.592,
        "lbs" to 453.592,
        "pound" to 453.592,
        "pounds" to 453.592,
        "oz" to 28.3495,
        "ounce" to 28.3495,
        "ounces" to 28.3495,
        
        // Volume -> ml (base)
        "ml" to 1.0,
        "l" to 1000.0,
        "lt" to 1000.0,
        "ltr" to 1000.0,
        "litre" to 1000.0,
        "liter" to 1000.0,
        "litres" to 1000.0,
        "liters" to 1000.0,
        "cl" to 10.0,
        "dl" to 100.0,
        "fl oz" to 29.5735,
        "floz" to 29.5735,
        "gallon" to 3785.41,
        "gallons" to 3785.41,
        "pint" to 473.176,
        "pints" to 473.176,
        "cup" to 236.588,
        "cups" to 236.588,
        
        // Count -> pieces (base)
        "pc" to 1.0,
        "pcs" to 1.0,
        "piece" to 1.0,
        "pieces" to 1.0,
        "pack" to 1.0,
        "packs" to 1.0,
        "nos" to 1.0,
        "no" to 1.0,
        "unit" to 1.0,
        "units" to 1.0,
        "egg" to 1.0,
        "eggs" to 1.0,
        "dozen" to 12.0,
        "doz" to 12.0,
        "pair" to 2.0,
        "pairs" to 2.0,
        "set" to 1.0,
        "sets" to 1.0,
        "roll" to 1.0,
        "rolls" to 1.0,
        "sheet" to 1.0,
        "sheets" to 1.0,
        "slice" to 1.0,
        "slices" to 1.0,
        "serving" to 1.0,
        "servings" to 1.0,
        "portion" to 1.0,
        "portions" to 1.0,
        "sachet" to 1.0,
        "sachets" to 1.0,
        "pouch" to 1.0,
        "pouches" to 1.0,
        "stick" to 1.0,
        "sticks" to 1.0,
        "bar" to 1.0,
        "bars" to 1.0,
        "cube" to 1.0,
        "cubes" to 1.0,
        "capsule" to 1.0,
        "capsules" to 1.0,
        "cap" to 1.0,
        "caps" to 1.0,
        "tablet" to 1.0,
        "tablets" to 1.0,
        "tab" to 1.0,
        "tabs" to 1.0,
        "strip" to 1.0,
        "strips" to 1.0,
        "bottle" to 1.0,
        "bottles" to 1.0,
        "can" to 1.0,
        "cans" to 1.0,
        "box" to 1.0,
        "boxes" to 1.0,
        "jar" to 1.0,
        "jars" to 1.0,
        "tin" to 1.0,
        "tins" to 1.0,
        "bag" to 1.0,
        "bags" to 1.0,
        "bunch" to 1.0,
        "bunches" to 1.0,
        
        // Length -> cm (base)
        "m" to 100.0,
        "meter" to 100.0,
        "meters" to 100.0,
        "metre" to 100.0,
        "metres" to 100.0,
        "cm" to 1.0,
        "mm" to 0.1,
        "inch" to 2.54,
        "inches" to 2.54,
        "in" to 2.54,
        "ft" to 30.48,
        "feet" to 30.48,
        "foot" to 30.48,
        "yard" to 91.44,
        "yards" to 91.44,
        "yd" to 91.44,
        
        // Area -> sq cm (base)
        "sqm" to 10000.0,
        "sqft" to 929.03,
        "sqin" to 6.4516,
        "sqcm" to 1.0,
        "acre" to 40468564.224,
        
        // Pulls/Wipes (tissues, wipes, etc.)
        "pull" to 1.0,
        "pulls" to 1.0,
        "wipe" to 1.0,
        "wipes" to 1.0,
        "ply" to 1.0
    )
    
    // Unit type classification
    private val UNIT_TYPE = mapOf(
        // Weight
        "g" to "weight", "gm" to "weight", "gram" to "weight", "grams" to "weight",
        "kg" to "weight", "kilo" to "weight", "kilos" to "weight", 
        "kilogram" to "weight", "kilograms" to "weight",
        "mg" to "weight", "milligram" to "weight", "milligrams" to "weight",
        "quintal" to "weight", "ton" to "weight", "tonne" to "weight",
        "lb" to "weight", "lbs" to "weight", "pound" to "weight", "pounds" to "weight",
        "oz" to "weight", "ounce" to "weight", "ounces" to "weight",
        
        // Volume
        "ml" to "volume", "l" to "volume", "lt" to "volume", "ltr" to "volume", 
        "litre" to "volume", "liter" to "volume", "litres" to "volume", "liters" to "volume",
        "cl" to "volume", "dl" to "volume",
        "fl oz" to "volume", "floz" to "volume",
        "gallon" to "volume", "gallons" to "volume",
        "pint" to "volume", "pints" to "volume",
        "cup" to "volume", "cups" to "volume",
        
        // Count (general items)
        "pc" to "count", "pcs" to "count", "piece" to "count", "pieces" to "count",
        "pack" to "count", "packs" to "count", "nos" to "count", "no" to "count",
        "unit" to "count", "units" to "count",
        "egg" to "count", "eggs" to "count",
        "dozen" to "count", "doz" to "count",
        "pair" to "count", "pairs" to "count",
        "set" to "count", "sets" to "count",
        "roll" to "count", "rolls" to "count",
        "sheet" to "count", "sheets" to "count",
        "slice" to "count", "slices" to "count",
        "serving" to "count", "servings" to "count",
        "portion" to "count", "portions" to "count",
        "sachet" to "count", "sachets" to "count",
        "pouch" to "count", "pouches" to "count",
        "stick" to "count", "sticks" to "count",
        "bar" to "count", "bars" to "count",
        "cube" to "count", "cubes" to "count",
        "capsule" to "count", "capsules" to "count",
        "cap" to "count", "caps" to "count",
        "tablet" to "count", "tablets" to "count",
        "tab" to "count", "tabs" to "count",
        "strip" to "count", "strips" to "count",
        "bottle" to "count", "bottles" to "count",
        "can" to "count", "cans" to "count",
        "box" to "count", "boxes" to "count",
        "jar" to "count", "jars" to "count",
        "tin" to "count", "tins" to "count",
        "bag" to "count", "bags" to "count",
        "bunch" to "count", "bunches" to "count",
        "pull" to "count", "pulls" to "count",
        "wipe" to "count", "wipes" to "count",
        "ply" to "count",
        
        // Length
        "m" to "length", "meter" to "length", "meters" to "length",
        "metre" to "length", "metres" to "length",
        "cm" to "length", "mm" to "length",
        "inch" to "length", "inches" to "length", "in" to "length",
        "ft" to "length", "feet" to "length", "foot" to "length",
        "yard" to "length", "yards" to "length", "yd" to "length",
        
        // Area
        "sqm" to "area", "sqft" to "area", "sqin" to "area", "sqcm" to "area", "acre" to "area"
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
    // GENERIC: Works for ANY product - if these words appear alongside the search term,
    // it's likely a processed/derivative product, not the primary item
    private val DERIVATIVE_INDICATORS = listOf(
        // Beverages
        "juice", "squash", "drink", "beverage", "shake", "smoothie", "cocktail", "mocktail",
        "wine", "beer", "ale", "cider", "vinegar", "cordial", "nectar", "punch",
        // Preserved/processed
        "jam", "jelly", "preserve", "marmalade", "compote", "conserve",
        "sauce", "ketchup", "puree", "paste", "chutney", "pickle", "achar", "relish",
        "syrup", "honey", "molasses", "concentrate", "extract", "essence",
        // Dried/powdered
        "powder", "dried", "dehydrated", "flakes", "granules", "crystals",
        "flour", "starch", "bran", "husk", "fiber", "fibre",
        // Snacks/processed foods
        "chips", "crisps", "wafers", "fries", "fritters", "bhujia", "namkeen",
        "candy", "toffee", "gummy", "lollipop", "chew", "mint",
        "chocolate", "fudge", "caramel", "brittle",
        "cake", "pastry", "muffin", "cookie", "biscuit", "cracker", "rusk",
        "bar", "bite", "ball", "roll", "stick",
        // Dairy derivatives
        "ice cream", "icecream", "kulfi", "gelato", "sorbet", "frozen",
        "milkshake", "lassi", "sharbat", "sherbet", "buttermilk", "whey",
        "yogurt", "yoghurt", "curd rice", "raita",
        // Flavored/modified
        "flavour", "flavor", "flavored", "flavoured", "infused", "scented",
        "spread", "butter", "cream", "topping", "filling", "glaze", "icing",
        // Oils and extracts
        "oil", "seed oil", "essential oil", "seeds", "seed",
        // Other processed forms
        "pulp", "peel", "zest", "rind", "skin",
        "capsule", "tablet", "supplement", "vitamin",
        "soap", "shampoo", "lotion", "cream", "moisturizer", "cleanser"
    )

    // Extra strict filtering for "milk" queries when AI is unavailable
    private val MILK_DERIVATIVE_INDICATORS = listOf(
        "milkshake", "shake", "powder", "condensed", "evaporated",
        "ice cream", "icecream", "flavoured", "flavored", "chocolate",
        "drink", "beverage", "coffee", "tea", "syrup", "whitener", "formula"
    )
    
    // GENERIC: Compound word suffixes that create different products
    // e.g., "grape" vs "grapefruit", "pine" vs "pineapple", "straw" vs "strawberry"
    private val COMPOUND_SUFFIXES = listOf(
        "fruit", "berry", "apple", "melon", "nut", "seed", "leaf", "root", "grass",
        "flower", "blossom", "peel", "skin", "stem", "stalk"
    )
    
    // GENERIC: Words that indicate the search term is used as a FLAVOR/TYPE, not the main product
    // e.g., "mango ice cream" - mango is the flavor, ice cream is the product
    private val FLAVOR_CONTEXT_WORDS = listOf(
        "flavour", "flavor", "flavored", "flavoured", "taste", "tasting",
        "scented", "fragrance", "aroma", "infused", "based", "style", "type"
    )
    
    // GENERIC: Product type words - when search term appears BEFORE these, 
    // search term is likely a modifier, not the main product
    private val PRODUCT_TYPE_WORDS = listOf(
        // Beverages
        "juice", "drink", "shake", "smoothie", "tea", "coffee", "water", "soda", "pop",
        "wine", "beer", "vodka", "rum", "whiskey", "liqueur",
        // Foods
        "cake", "pie", "tart", "pudding", "custard", "mousse", "parfait",
        "ice cream", "icecream", "gelato", "sorbet", "kulfi", "popsicle",
        "jam", "jelly", "preserve", "marmalade", "compote",
        "sauce", "chutney", "pickle", "relish", "dip", "spread",
        "chips", "crisps", "wafers", "fries", "snack",
        "candy", "chocolate", "fudge", "toffee", "gummy", "lollipop",
        "cookie", "biscuit", "cracker", "wafer",
        "yogurt", "yoghurt", "lassi", "milkshake", "smoothie",
        "bread", "loaf", "roll", "bun", "muffin", "cupcake", "donut",
        // Non-food
        "soap", "shampoo", "lotion", "cream", "oil", "perfume", "fragrance",
        "candle", "air freshener", "room spray"
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
     * Tries multiple patterns to handle various formats including combo packs
     */
    fun parseQuantity(text: String): ParsedQuantity? {
        val lowerText = text.lowercase()
        
        // Handle comma-formatted numbers first (e.g., "1,000g" -> "1000g")
        val cleanedText = lowerText.replace(Regex("""(\d),(\d{3})"""), "$1$2")
        
        // Try each pattern until we find a match
        for ((index, pattern) in QUANTITY_PATTERNS.withIndex()) {
            matchLoop@ for (match in pattern.findAll(cleanedText)) {
                val groupCount = match.groupValues.size - 1

                // Handle combo/multiplier patterns specially (first 3 patterns)
                when (index) {
                    0 -> {
                        // "2x500g" pattern: group1=count, group2=size, group3=unit
                        if (match.groupValues.size >= 4) {
                            val count = match.groupValues[1].toDoubleOrNull() ?: continue@matchLoop
                            val size = match.groupValues[2].toDoubleOrNull() ?: continue@matchLoop
                            if (count <= 0 || size <= 0) continue@matchLoop
                            val rawUnit = normalizeUnit(match.groupValues[3])
                            
                            val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: continue@matchLoop
                            val unitType = UNIT_TYPE[rawUnit] ?: continue@matchLoop
                            val totalValue = count * size
                            val normalizedValue = totalValue * baseMultiplier
                            if (totalValue <= 0 || normalizedValue <= 0) continue@matchLoop
                            
                            return ParsedQuantity(
                                originalValue = totalValue,
                                originalUnit = rawUnit,
                                normalizedValue = normalizedValue,
                                baseUnit = getBaseUnit(unitType),
                                unitType = unitType
                            )
                        }
                    }
                    1 -> {
                        // "500g x 2" pattern: group1=size, group2=unit, group3=count
                        if (match.groupValues.size >= 4) {
                            val size = match.groupValues[1].toDoubleOrNull() ?: continue@matchLoop
                            val rawUnit = normalizeUnit(match.groupValues[2])
                            val count = match.groupValues[3].toDoubleOrNull() ?: continue@matchLoop
                            if (count <= 0 || size <= 0) continue@matchLoop
                            
                            val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: continue@matchLoop
                            val unitType = UNIT_TYPE[rawUnit] ?: continue@matchLoop
                            val totalValue = count * size
                            val normalizedValue = totalValue * baseMultiplier
                            if (totalValue <= 0 || normalizedValue <= 0) continue@matchLoop
                            
                            return ParsedQuantity(
                                originalValue = totalValue,
                                originalUnit = rawUnit,
                                normalizedValue = normalizedValue,
                                baseUnit = getBaseUnit(unitType),
                                unitType = unitType
                            )
                        }
                    }
                    2 -> {
                        // "Pack of 6 x 200ml" pattern: group1=count, group2=size, group3=unit
                        if (match.groupValues.size >= 4) {
                            val count = match.groupValues[1].toDoubleOrNull() ?: continue@matchLoop
                            val size = match.groupValues[2].toDoubleOrNull() ?: continue@matchLoop
                            if (count <= 0 || size <= 0) continue@matchLoop
                            val rawUnit = normalizeUnit(match.groupValues[3])
                            
                            val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: continue@matchLoop
                            val unitType = UNIT_TYPE[rawUnit] ?: continue@matchLoop
                            val totalValue = count * size
                            val normalizedValue = totalValue * baseMultiplier
                            if (totalValue <= 0 || normalizedValue <= 0) continue@matchLoop
                            
                            return ParsedQuantity(
                                originalValue = totalValue,
                                originalUnit = rawUnit,
                                normalizedValue = normalizedValue,
                                baseUnit = getBaseUnit(unitType),
                                unitType = unitType
                            )
                        }
                    }
                    else -> {
                        if (groupCount == 1) {
                            val count = match.groupValues[1].toDoubleOrNull() ?: continue@matchLoop
                            if (count <= 0) continue@matchLoop
                            val matchText = match.value.lowercase()
                            val rawUnit = normalizeUnit(
                                when {
                                    matchText.contains("pack") -> "pack"
                                    matchText.contains("set") -> "set"
                                    matchText.contains("box") -> "box"
                                    matchText.contains("bundle") -> "bundle"
                                    else -> "pc"
                                }
                            )

                            val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: continue@matchLoop
                            val unitType = UNIT_TYPE[rawUnit] ?: continue@matchLoop
                            val normalizedValue = count * baseMultiplier
                            if (normalizedValue <= 0) continue@matchLoop

                            return ParsedQuantity(
                                originalValue = count,
                                originalUnit = rawUnit,
                                normalizedValue = normalizedValue,
                                baseUnit = getBaseUnit(unitType),
                                unitType = unitType
                            )
                        }

                        // Standard pattern: group1=value, group2=unit
                        if (match.groupValues.size >= 3) {
                            val value = match.groupValues[1].toDoubleOrNull() ?: continue@matchLoop
                            if (value <= 0) continue@matchLoop
                            val rawUnit = normalizeUnit(match.groupValues[2])
                            
                            val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: continue@matchLoop
                            val unitType = UNIT_TYPE[rawUnit] ?: continue@matchLoop
                            val normalizedValue = value * baseMultiplier
                            if (normalizedValue <= 0) continue@matchLoop
                            
                            return ParsedQuantity(
                                originalValue = value,
                                originalUnit = rawUnit,
                                normalizedValue = normalizedValue,
                                baseUnit = getBaseUnit(unitType),
                                unitType = unitType
                            )
                        }
                    }
                }
            }
        }
        
        // Fallback: try the main pattern
        val match = QUANTITY_PATTERN.find(cleanedText) ?: return null
        
        val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val rawUnit = normalizeUnit(match.groupValues[2])
        
        val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: return null
        val unitType = UNIT_TYPE[rawUnit] ?: return null
        val normalizedValue = value * baseMultiplier
        if (value <= 0 || normalizedValue <= 0) return null
        
        return ParsedQuantity(
            originalValue = value,
            originalUnit = rawUnit,
            normalizedValue = normalizedValue,
            baseUnit = getBaseUnit(unitType),
            unitType = unitType
        )
    }

    fun parseCountQuantity(text: String): ParsedQuantity? {
        val lowerText = text.lowercase()
        val cleanedText = lowerText.replace(Regex("""(\d),(\d{3})"""), "$1$2")

        for (pattern in COUNT_ONLY_PATTERNS) {
            for (match in pattern.findAll(cleanedText)) {
                val count = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
                if (count <= 0) continue
                val rawUnit = match.groupValues.getOrNull(2)?.let { normalizeUnit(it) } ?: "pc"

                val baseMultiplier = UNIT_TO_BASE[rawUnit] ?: (UNIT_TO_BASE["pc"] ?: 1.0)
                val unitType = UNIT_TYPE[rawUnit] ?: "count"
                val normalizedValue = count * baseMultiplier
                if (normalizedValue <= 0) continue

                return ParsedQuantity(
                    originalValue = count,
                    originalUnit = rawUnit,
                    normalizedValue = normalizedValue,
                    baseUnit = getBaseUnit(unitType),
                    unitType = unitType
                )
            }
        }

        return null
    }
    
    /**
     * Normalize unit names to standard form
     */
    private fun normalizeUnit(rawUnit: String): String {
        return when (rawUnit.lowercase()) {
            // Volume
            "lt", "ltr", "litre", "liter", "litres", "liters" -> "l"
            "floz", "fl oz" -> "floz"
            // Weight  
            "gram", "grams" -> "g"
            "kilogram", "kilograms" -> "kg"
            "milligram", "milligrams" -> "mg"
            "ounce", "ounces" -> "oz"
            "pound", "pounds" -> "lb"
            // Count
            "piece", "pieces" -> "pc"
            "nos", "no" -> "pc"
            "unit", "units" -> "pc"
            "egg", "eggs" -> "egg"
            "tablet", "tablets" -> "tab"
            "capsule", "capsules" -> "capsule"
            "wipe", "wipes" -> "wipe"
            "pull", "pulls" -> "pull"
            "sheet", "sheets" -> "sheet"
            "roll", "rolls" -> "roll"
            "sachet", "sachets" -> "sachet"
            "slice", "slices" -> "slice"
            "serving", "servings" -> "serving"
            "stick", "sticks" -> "stick"
            "pair", "pairs" -> "pair"
            "dozen", "doz" -> "dozen"
            "bunch", "bunches" -> "bunch"
            // Length
            "meter", "meters", "metre", "metres" -> "m"
            "inch", "inches" -> "inch"
            "feet", "foot" -> "ft"
            "yard", "yards" -> "yd"
            else -> rawUnit.lowercase()
        }
    }
    
    /**
     * Get base unit for unit type
     */
    private fun getBaseUnit(unitType: String): String {
        return when (unitType) {
            "weight" -> "g"
            "volume" -> "ml"
            "count" -> "pc"
            "length" -> "cm"
            "area" -> "sqcm"
            else -> "g"
        }
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
     * GENERIC: Check if keyword appears as a standalone word in text
     * "grape" matches "fresh grape 500g" but NOT "grapefruit" or "grapeseed"
     * 
     * This is the core of generic relevance - it ensures we match the EXACT word,
     * not compound words or partial matches.
     */
    fun isExactWordMatch(text: String, keyword: String): Boolean {
        // Match keyword as a complete word, optionally with 's' for plurals
        // \b = word boundary, ensures "grape" doesn't match "grapefruit"
        val pattern = Regex("\\b${Regex.escape(keyword)}s?\\b", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }
    
    /**
     * GENERIC: Check if the search keyword is part of a compound word in the product name
     * e.g., "grape" in "grapefruit" or "grapeseed" - these are DIFFERENT products
     * 
     * Returns true if keyword appears as part of a compound word (should be penalized)
     */
    private fun isCompoundWordMatch(productName: String, keyword: String): Boolean {
        val nameLower = productName.lowercase()
        val keywordLower = keyword.lowercase()
        
        // If keyword doesn't appear at all, not a compound match
        if (!nameLower.contains(keywordLower)) return false
        
        // If it's an exact word match, it's NOT a compound
        if (isExactWordMatch(nameLower, keywordLower)) return false
        
        // Check if keyword is followed by compound suffixes (e.g., grape+fruit)
        for (suffix in COMPOUND_SUFFIXES) {
            if (nameLower.contains(keywordLower + suffix)) return true
        }
        
        // Check if keyword is preceded by another word without space (e.g., pineapple)
        val keywordIndex = nameLower.indexOf(keywordLower)
        if (keywordIndex > 0) {
            val charBefore = nameLower[keywordIndex - 1]
            // If character before is a letter, it's part of a compound word
            if (charBefore.isLetter()) return true
        }
        
        // Check if keyword is followed by letters without space
        val endIndex = keywordIndex + keywordLower.length
        if (endIndex < nameLower.length) {
            val charAfter = nameLower[endIndex]
            // If character after is a letter (not 's' for plural), it's a compound
            if (charAfter.isLetter() && charAfter != 's') return true
            // Check for 's' followed by more letters
            if (charAfter == 's' && endIndex + 1 < nameLower.length && nameLower[endIndex + 1].isLetter()) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * GENERIC: Check if the search term is used as a FLAVOR/MODIFIER rather than the main product
     * e.g., "mango ice cream" - mango is the flavor, not the product
     * e.g., "strawberry shake" - strawberry is the flavor, shake is the product
     * 
     * IMPORTANT: If the search term ITSELF is a product type (e.g., "oil", "juice", "bread"),
     * we should NOT penalize products - user is looking for that specific product type.
     * 
     * Returns true if the keyword is a modifier (should be heavily penalized)
     */
    private fun isKeywordUsedAsModifier(productName: String, keyword: String): Boolean {
        val nameLower = productName.lowercase()
        val keywordLower = keyword.lowercase()
        
        // IMPORTANT: If the search term is itself a product type, DON'T penalize
        // e.g., searching for "oil" should show oil products, not penalize them
        // e.g., searching for "juice" should show juice products
        if (PRODUCT_TYPE_WORDS.any { it.equals(keywordLower, ignoreCase = true) }) {
            return false
        }
        
        // If keyword doesn't appear as exact word, can't be a modifier
        if (!isExactWordMatch(nameLower, keywordLower)) return false
        
        // Check if any product type word appears AFTER the keyword
        // e.g., "mango [juice]", "strawberry [ice cream]", "orange [marmalade]"
        val keywordIndex = nameLower.indexOf(keywordLower)
        val afterKeyword = nameLower.substring(keywordIndex + keywordLower.length)
        
        for (productType in PRODUCT_TYPE_WORDS) {
            if (afterKeyword.contains(productType)) {
                return true
            }
        }
        
        // Check if flavor context words appear in the name
        for (flavorWord in FLAVOR_CONTEXT_WORDS) {
            if (nameLower.contains(flavorWord)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * GENERIC: Check if product is a derivative/processed form
     * Works for ANY product by checking against derivative indicators
     * 
     * IMPORTANT: This should only be used when the search term is NOT itself
     * a derivative type. The calling function should handle this check.
     */
    private fun isDerivativeProduct(productName: String): Boolean {
        val nameLower = productName.lowercase()
        return DERIVATIVE_INDICATORS.any { indicator ->
            // Check as whole word to avoid false positives
            // e.g., "powder" should match but not "powerful"
            Regex("\\b${Regex.escape(indicator)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(nameLower)
        }
    }

    private fun isMilkDerivative(productName: String): Boolean {
        val nameLower = productName.lowercase()
        return MILK_DERIVATIVE_INDICATORS.any { indicator ->
            Regex("\\b${Regex.escape(indicator)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(nameLower)
        }
    }
    
    /**
     * Check if the search keyword itself is a derivative/product type
     * e.g., "juice", "oil", "jam", "chips" are all valid product types
     */
    private fun isKeywordAProductType(keyword: String): Boolean {
        val keywordLower = keyword.lowercase()
        return DERIVATIVE_INDICATORS.any { it.equals(keywordLower, ignoreCase = true) } ||
               PRODUCT_TYPE_WORDS.any { it.equals(keywordLower, ignoreCase = true) }
    }
    
    /**
     * GENERIC: Calculate how relevant a product is to the search keyword
     * Returns a score adjustment:
     *   Positive = more relevant
     *   Negative = less relevant (penalize)
     *   Very negative (-100) = should be filtered out
     * 
     * SMART: Handles cases where the search term itself is a product type
     * (e.g., "oil", "juice", "chips") - doesn't penalize these cases
     */
    private fun calculateGenericRelevance(productName: String, keyword: String): Int {
        val nameLower = productName.lowercase()
        val keywordLower = keyword.lowercase()
        
        // Check if keyword is itself a product type (oil, juice, chips, etc.)
        val keywordIsProductType = isKeywordAProductType(keyword)
        
        // 1. Compound word check - completely different product
        // BUT: be careful with product type keywords
        if (!keywordIsProductType && isCompoundWordMatch(productName, keyword)) {
            // "grapefruit" when searching "grape" = different product
            return -100
        }
        
        // 2. Check if keyword is used as modifier/flavor
        // SKIP if keyword is a product type (user searching for that type specifically)
        if (!keywordIsProductType && isKeywordUsedAsModifier(productName, keyword)) {
            // "mango ice cream" when searching "mango" = mango is just flavor
            return -60
        }
        
        // 3. Check if it's a derivative product
        // SKIP if keyword is itself a derivative/product type
        if (!keywordIsProductType && isDerivativeProduct(productName)) {
            // "mango juice" when searching "mango" = processed form
            return -40
        }
        
        // 4. Exact word match bonus
        if (isExactWordMatch(nameLower, keywordLower)) {
            return 30  // Good match
        }
        
        // 5. Contains keyword but not as exact word
        if (nameLower.contains(keywordLower)) {
            // For product type keywords, substring match is okay
            // e.g., "sunflower oil" contains "oil"
            if (keywordIsProductType) {
                return 20  // Still a valid match
            }
            return -20  // Suspicious for non-product-type keywords
        }
        
        return 0
    }

    /**
     * Rank and filter products based on search intent
     * 
     * Enhanced filtering with:
     * - Stricter minimum scores for fruits/vegetables (to filter out juices, sauces, etc.)
     * - Category-aware thresholds
     * - Logging for debugging
     */
    fun rankResults(products: List<Product>, intent: SearchIntent): List<Product> {
        if (products.isEmpty()) return products
        
        // Score each product
        val scoredProducts = products.map { product ->
            val score = calculateRelevanceScore(product, intent)
            val productQuantity = parseQuantity(product.name)
            Pair(product, ScoredProduct(score, productQuantity))
        }
        
        // Determine minimum score based on search intent and category
        // Balance between filtering irrelevant items and not being too strict
        val minScore = when {
            // Fruits and vegetables need filtering (exclude juices, pickles, etc.)
            intent.wantsPrimaryItem && intent.category in listOf("fruit", "vegetable") -> 35
            // Dairy - moderate filtering (exclude milkshakes but allow milk products)
            intent.wantsPrimaryItem && intent.category == "dairy" -> 25
            // Other primary item searches
            intent.wantsPrimaryItem -> 20
            // Explicit derivative search (e.g., "mango juice") - lenient
            intent.isExplicitDerivative -> 10
            // Default - lenient to avoid over-filtering
            else -> 15
        }
        
        val filtered = scoredProducts.filter { it.second.score >= minScore }
        val sorted = filtered.sortedByDescending { it.second.score }
        
        // Log filtering results for debugging
        val filteredCount = products.size - filtered.size
        if (filteredCount > 0) {
            println("SearchIntelligence: 🔍 Filtered out $filteredCount/${products.size} products (minScore: $minScore)")
            // Log top filtered out items for debugging
            scoredProducts
                .filter { it.second.score < minScore && it.second.score > -100 }
                .sortedByDescending { it.second.score }
                .take(3)
                .forEach { (product, scored) ->
                    println("  ↳ Filtered: '${product.name.take(40)}' (score: ${scored.score})")
                }
        }
        
        // Log top results
        sorted.take(3).forEach { (product, scored) ->
            println("SearchIntelligence: ✅ Top: '${product.name.take(40)}' (score: ${scored.score})")
        }
        
        return sorted.map { it.first }
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
     * Intelligently formats based on unit type and quantity for meaningful comparison
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
        
        // Smart formatting based on unit type and quantity size
        val (displayValue, displayUnit) = getSmartDisplayFormat(quantity, perUnit)
        
        println("SearchIntelligence: ✓ ${product.name.take(30)} → ₹${String.format("%.1f", displayValue)}/$displayUnit")
        
        return PerUnitPrice(
            pricePerBaseUnit = perUnit,
            displayPrice = displayValue,
            displayUnit = displayUnit,
            productQuantity = quantity,
            unitType = quantity.unitType
        )
    }

    fun calculatePerPiecePrice(product: Product): PerUnitPrice? {
        val countQuantity = parseCountQuantity(product.name) ?: return null
        if (countQuantity.normalizedValue <= 0) return null

        val perPiece = product.price / countQuantity.normalizedValue
        val displayUnit = getCountDisplayUnit(countQuantity.originalUnit)

        return PerUnitPrice(
            pricePerBaseUnit = perPiece,
            displayPrice = perPiece,
            displayUnit = displayUnit,
            productQuantity = countQuantity,
            unitType = "count"
        )
    }
    
    /**
     * Smart formatting logic for per-unit price display
     * Chooses the most meaningful display format based on quantity type and size
     */
    private fun getSmartDisplayFormat(quantity: ParsedQuantity, perUnit: Double): Pair<Double, String> {
        return when (quantity.unitType) {
            "weight" -> {
                // Use per kg when large or expressed in kg, otherwise per 100gm
                if (quantity.normalizedValue >= 1000 || quantity.originalUnit == "kg") {
                    Pair(perUnit * 1000, "kg")
                } else {
                    Pair(perUnit * 100, "100gm")
                }
            }
            "volume" -> {
                // Use per ltr when large or expressed in liters, otherwise per 100ml
                if (quantity.normalizedValue >= 1000 || quantity.originalUnit == "l") {
                    Pair(perUnit * 1000, "ltr")
                } else {
                    Pair(perUnit * 100, "100ml")
                }
            }
            "count" -> {
                // For count items: display per piece or per unit based on context
                val displayUnit = getCountDisplayUnit(quantity.originalUnit)
                Pair(perUnit, displayUnit)
            }
            "length" -> {
                // For length: display per meter if large, per cm otherwise
                when {
                    quantity.normalizedValue >= 100 -> Pair(perUnit * 100, "m")
                    else -> Pair(perUnit, "cm")
                }
            }
            "area" -> {
                // For area: display per sq ft or sq m
                when {
                    quantity.normalizedValue >= 10000 -> Pair(perUnit * 10000, "sq m")
                    else -> Pair(perUnit * 929.03, "sq ft")
                }
            }
            else -> Pair(perUnit, quantity.baseUnit)
        }
    }
    
    /**
     * Get appropriate display unit for count-based items
     */
    private fun getCountDisplayUnit(originalUnit: String): String {
        return when (originalUnit.lowercase()) {
            "egg", "eggs" -> "egg"
            "tablet", "tablets", "tab", "tabs" -> "tab"
            "capsule", "capsules", "cap", "caps" -> "cap"
            "wipe", "wipes" -> "wipe"
            "pull", "pulls" -> "pull"
            "sheet", "sheets" -> "sheet"
            "roll", "rolls" -> "roll"
            "sachet", "sachets" -> "sachet"
            "slice", "slices" -> "slice"
            "serving", "servings" -> "serving"
            "stick", "sticks" -> "stick"
            "bar", "bars" -> "bar"
            "pair", "pairs" -> "pair"
            "dozen", "doz" -> "piece"
            "bunch", "bunches" -> "bunch"
            "pack", "packs", "box", "boxes", "bundle" -> "pack"
            else -> "piece"
        }
    }

    /**
     * Calculate relevance score for a product (0-100)
     * 
     * GENERIC SCORING that works for ANY product:
     * - Compound word detection (grape vs grapefruit)
     * - Modifier/flavor detection (mango vs mango ice cream)
     * - Derivative product detection (apple vs apple juice)
     * - Position-based scoring
     * - Quantity matching
     */
    private fun calculateRelevanceScore(product: Product, intent: SearchIntent): Int {
        val name = product.name.lowercase()
        val primaryKeyword = intent.primaryKeyword

        // Special-case "milk" to avoid obvious derivatives when AI is offline
        if (primaryKeyword == "milk" && intent.wantsPrimaryItem && !intent.isExplicitDerivative) {
            if (isMilkDerivative(name)) {
                println("SearchIntelligence: ❌ Filtered milk-derivative: '${product.name.take(40)}'")
                return -100
            }
        }
        
        // GENERIC RELEVANCE CHECK - works for ANY product
        val genericRelevance = calculateGenericRelevance(product.name, primaryKeyword)
        
        // Early exit for clearly irrelevant products
        if (genericRelevance <= -100) {
            println("SearchIntelligence: ❌ Filtered (compound/different product): '${product.name.take(40)}'")
            return -100
        }
        
        var score = 0
        
        // 1. Apply generic relevance adjustment
        score += genericRelevance
        
        // 2. Exact WORD match bonus
        val hasExactWordMatch = isExactWordMatch(name, primaryKeyword)
        if (hasExactWordMatch) {
            score += 40
        } else if (name.contains(primaryKeyword)) {
            // Partial match - be cautious
            score += 10
        } else {
            // No keyword match at all
            return 5
        }
        
        // 3. Position bonus - where does the keyword appear?
        val nameWords = name.split(Regex("[\\s,\\-()]+")).filter { it.isNotBlank() }
        val firstWord = nameWords.firstOrNull()?.lowercase() ?: ""
        
        if (firstWord == primaryKeyword || (hasExactWordMatch && nameWords.indexOf(primaryKeyword) == 0)) {
            // Name STARTS with keyword - highest relevance
            score += 30
        } else if (nameWords.take(3).any { it == primaryKeyword || it == "${primaryKeyword}s" }) {
            // Keyword in first 3 words as exact word
            score += 20
        } else if (hasExactWordMatch) {
            // Keyword appears somewhere as exact word
            score += 10
        }
        
        // 4. Fresh/organic indicator bonus (when user wants primary item)
        if (intent.wantsPrimaryItem || intent.wantsFresh) {
            if (FRESH_INDICATORS.any { indicator ->
                Regex("\\b${Regex.escape(indicator)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)
            }) {
                score += 15
            }
        }
        
        // 5. Category match bonus
        if (intent.category != null) {
            val categoryKeywords = CATEGORY_KEYWORDS[intent.category] ?: emptyList()
            if (categoryKeywords.any { name.contains(it) }) {
                score += 10
            }
        }
        
        // 6. Additional derivative penalty when user wants primary item
        // BUT: Skip if the search term itself is a product type (e.g., "juice", "oil", "chips")
        if (intent.wantsPrimaryItem && !intent.isExplicitDerivative && !isKeywordAProductType(primaryKeyword)) {
            if (isDerivativeProduct(product.name)) {
                // Extra penalty on top of generic relevance
                score -= 20
                println("SearchIntelligence: ⚠️ Derivative: '${product.name.take(40)}'")
            }
        }
        
        // 7. Quantity match bonus
        val requestedQty = intent.requestedQuantity
        if (requestedQty != null) {
            val productQty = parseQuantity(name)
            if (productQty != null && 
                productQty.unitType == requestedQty.unitType &&
                isQuantitySimilar(productQty.normalizedValue, requestedQty.normalizedValue)) {
                score += 20
            }
        }
        
        // 8. Name length penalty (prefer shorter, more specific names)
        when {
            name.length > 100 -> score -= 15
            name.length > 80 -> score -= 10
            name.length > 60 -> score -= 5
        }
        
        // 9. Bonus for having quantity indicator (likely actual product listing)
        val hasQuantity = QUANTITY_PATTERN.containsMatchIn(name)
        if (hasQuantity) {
            score += 10
        }
        
        // 10. Penalty for "combo" or "pack of" when not explicitly requested
        if (!intent.originalQuery.contains("combo", ignoreCase = true) &&
            !intent.originalQuery.contains("pack of", ignoreCase = true)) {
            if (name.contains("combo") || Regex("\\bpack\\s+of\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                score -= 10
            }
        }
        
        return score.coerceIn(-100, 100)
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
     * 
     * Platform-specific optimizations:
     * - BigBasket: Add "fresh" for produce
     * - Amazon: Add product type qualifier
     * - Flipkart: Works well with direct queries
     */
    fun getOptimizedQuery(query: String, platform: String): String {
        val intent = analyzeQuery(query)
        val platformLower = platform.lowercase()
        
        // For fruits/vegetables single word searches
        if (intent.isSingleWordSearch && intent.category in listOf("fruit", "vegetable")) {
            return when {
                platformLower.contains("bigbasket") -> "fresh $query"
                platformLower.contains("amazon") && !platformLower.contains("fresh") -> "$query fresh fruit vegetable"
                platformLower.contains("jiomart") -> "fresh $query"
                else -> query
            }
        }
        
        // For dairy single word searches
        if (intent.isSingleWordSearch && intent.category == "dairy") {
            return when {
                platformLower.contains("bigbasket") -> "$query dairy"
                platformLower.contains("amazon") && !platformLower.contains("fresh") -> "$query dairy fresh"
                else -> query
            }
        }
        
        // For common ambiguous searches, add qualifiers
        val ambiguousTerms = mapOf(
            "oil" to "cooking oil",
            "salt" to "cooking salt",
            "sugar" to "sugar white"
        )
        
        if (intent.isSingleWordSearch && ambiguousTerms.containsKey(query.lowercase())) {
            return when {
                platformLower.contains("amazon") -> ambiguousTerms[query.lowercase()] ?: query
                else -> query
            }
        }
        
        return query
    }
    
    /**
     * Check if a product is likely a duplicate of another product
     * Used for deduplication across platforms
     */
    fun areSimilarProducts(product1: Product, product2: Product): Boolean {
        val name1 = product1.name.lowercase()
            .replace(Regex("""[^\w\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val name2 = product2.name.lowercase()
            .replace(Regex("""[^\w\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        
        // Extract key terms (brand, product name, quantity)
        val words1 = name1.split(" ").filter { it.length > 2 }.take(5).toSet()
        val words2 = name2.split(" ").filter { it.length > 2 }.take(5).toSet()
        
        // Check overlap
        val intersection = words1.intersect(words2)
        val union = words1.union(words2)
        
        if (union.isEmpty()) return false
        
        val similarity = intersection.size.toDouble() / union.size.toDouble()
        
        // Also check if quantities match
        val qty1 = parseQuantity(product1.name)
        val qty2 = parseQuantity(product2.name)
        
        val quantityMatch = if (qty1 != null && qty2 != null) {
            qty1.unitType == qty2.unitType && 
            isQuantitySimilar(qty1.normalizedValue, qty2.normalizedValue)
        } else {
            true // If we can't parse quantities, don't factor it
        }
        
        // Consider similar if >60% word overlap AND quantities match
        return similarity > 0.6 && quantityMatch
    }
    
    /**
     * Group similar products across platforms for comparison view
     * Returns a map of "canonical name" to list of products from different platforms
     */
    fun groupSimilarProducts(products: List<Product>): Map<String, List<Product>> {
        if (products.isEmpty()) return emptyMap()
        
        val groups = mutableListOf<MutableList<Product>>()
        
        for (product in products) {
            // Find existing group this product belongs to
            var addedToGroup = false
            for (group in groups) {
                if (group.any { areSimilarProducts(it, product) }) {
                    // Don't add duplicates from the same platform
                    if (group.none { it.platform == product.platform }) {
                        group.add(product)
                    }
                    addedToGroup = true
                    break
                }
            }
            
            if (!addedToGroup) {
                groups.add(mutableListOf(product))
            }
        }
        
        // Convert to map with canonical name
        return groups
            .filter { it.isNotEmpty() }
            .associate { group ->
                // Use the shortest name as canonical (usually most specific)
                val canonicalName = group.minByOrNull { it.name.length }?.name ?: group.first().name
                canonicalName to group.sortedBy { it.price }
            }
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
 * Supports all measurement types: weight, volume, count, length, area
 */
data class ParsedQuantity(
    val originalValue: Double,      // e.g., 500
    val originalUnit: String,       // e.g., "ml"
    val normalizedValue: Double,    // e.g., 500.0 (in base units - g, ml, pc, cm, sqcm)
    val baseUnit: String,           // e.g., "ml"
    val unitType: String            // "weight", "volume", "count", "length", "area"
) {
    /**
     * Smart display string for the quantity
     * Automatically converts to larger units when appropriate
     */
    fun toDisplayString(): String {
        return when (unitType) {
            "weight" -> when {
                normalizedValue >= 1000 -> "${formatNumber(normalizedValue / 1000)} kg"
                normalizedValue < 1 -> "${formatNumber(normalizedValue * 1000)} mg"
                else -> formatQuantity(originalValue, originalUnit)
            }
            "volume" -> when {
                normalizedValue >= 1000 -> "${formatNumber(normalizedValue / 1000)} L"
                else -> formatQuantity(originalValue, originalUnit)
            }
            "length" -> when {
                normalizedValue >= 100 -> "${formatNumber(normalizedValue / 100)} m"
                else -> formatQuantity(originalValue, originalUnit)
            }
            "count" -> {
                val unit = when (originalUnit.lowercase()) {
                    "dozen", "doz" -> "dozen"
                    else -> originalUnit
                }
                "${originalValue.toInt()} $unit"
            }
            else -> formatQuantity(originalValue, originalUnit)
        }
    }
    
    private fun formatQuantity(value: Double, unit: String): String {
        return "${formatNumber(value)} $unit"
    }

    private fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.1f", value)
        }
    }
}

/**
 * Per-unit price calculation result
 * Supports all measurement types: weight, volume, count, length, area
 */
data class PerUnitPrice(
    val pricePerBaseUnit: Double,   // Price per base unit (gram, ml, piece, cm, sqcm)
    val displayPrice: Double,       // Price formatted for display (per 100g, per L, per pc, etc.)
    val displayUnit: String,        // Display unit string ("100g", "L", "pc", "tab", "wipe", etc.)
    val productQuantity: ParsedQuantity,
    val unitType: String = "weight" // "weight", "volume", "count", "length", "area"
) {
    /**
     * Smart display string that formats price appropriately
     * - Shows decimal for small amounts (< ₹10)
     * - Shows whole number for larger amounts
     */
    fun toDisplayString(): String {
        val priceStr = when {
            displayPrice < 1 -> String.format("%.2f", displayPrice)
            displayPrice < 10 -> String.format("%.1f", displayPrice)
            else -> displayPrice.toInt().toString()
        }
        return "₹$priceStr/$displayUnit"
    }
    
    /**
     * Get a comparison-friendly string for sorting/display
     * e.g., "₹45.5 per 100g" or "₹12 per tablet"
     */
    fun toComparisonString(): String {
        val priceStr = when {
            displayPrice < 1 -> String.format("%.2f", displayPrice)
            displayPrice < 10 -> String.format("%.1f", displayPrice)
            else -> displayPrice.toInt().toString()
        }
        return "₹$priceStr per $displayUnit"
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
