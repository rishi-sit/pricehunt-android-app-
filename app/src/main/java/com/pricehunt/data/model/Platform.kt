package com.pricehunt.data.model

/**
 * Platform constants and colors.
 */
object Platforms {
    // Platform Colors (ARGB format)
    const val AMAZON_COLOR = 0xFFFF9900
    const val AMAZON_FRESH_COLOR = 0xFF5EA03E
    const val FLIPKART_COLOR = 0xFF2874F0
    const val FLIPKART_MINUTES_COLOR = 0xFFFFCE00
    const val JIOMART_COLOR = 0xFF0078AD
    const val BIGBASKET_COLOR = 0xFF84C225
    const val ZEPTO_COLOR = 0xFF8B5CF6
    const val BLINKIT_COLOR = 0xFFF8CB46
    const val INSTAMART_COLOR = 0xFFFC8019
    
    // Platform Names
    const val AMAZON = "Amazon"
    const val AMAZON_FRESH = "Amazon Fresh"
    const val FLIPKART = "Flipkart"
    const val FLIPKART_MINUTES = "Flipkart Minutes"
    const val JIOMART = "JioMart"
    const val JIOMART_QUICK = "JioMart Quick"
    const val BIGBASKET = "BigBasket"
    const val ZEPTO = "Zepto"
    const val BLINKIT = "Blinkit"
    const val INSTAMART = "Instamart"
    
    // Quick Commerce Platforms (5 min cache TTL)
    val QUICK_COMMERCE = setOf(
        AMAZON_FRESH, FLIPKART_MINUTES, JIOMART_QUICK,
        BIGBASKET, ZEPTO, BLINKIT, INSTAMART
    )
    
    // E-Commerce Platforms (15 min cache TTL)
    val ECOMMERCE = setOf(AMAZON, FLIPKART, JIOMART)
    
    // All platforms in display order
    val ALL = listOf(
        Platform(AMAZON_FRESH, AMAZON_FRESH_COLOR, "2-4 hours", true),
        Platform(FLIPKART_MINUTES, FLIPKART_MINUTES_COLOR, "10-45 mins", true),
        Platform(JIOMART_QUICK, JIOMART_COLOR, "10-30 mins", true),
        Platform(BIGBASKET, BIGBASKET_COLOR, "2-4 hours", true),
        Platform(ZEPTO, ZEPTO_COLOR, "10-15 mins", true),
        Platform(AMAZON, AMAZON_COLOR, "1-3 days", false),
        Platform(FLIPKART, FLIPKART_COLOR, "2-4 days", false),
        Platform(JIOMART, JIOMART_COLOR, "1-3 days", false),
        Platform(BLINKIT, BLINKIT_COLOR, "8-12 mins", true),
        Platform(INSTAMART, INSTAMART_COLOR, "15-30 mins", true),
    )
    
    fun getColor(platformName: String): Long {
        return when (platformName) {
            AMAZON -> AMAZON_COLOR
            AMAZON_FRESH -> AMAZON_FRESH_COLOR
            FLIPKART -> FLIPKART_COLOR
            FLIPKART_MINUTES -> FLIPKART_MINUTES_COLOR
            JIOMART, JIOMART_QUICK -> JIOMART_COLOR
            BIGBASKET -> BIGBASKET_COLOR
            ZEPTO -> ZEPTO_COLOR
            BLINKIT -> BLINKIT_COLOR
            INSTAMART -> INSTAMART_COLOR
            else -> 0xFF888888
        }
    }
}

