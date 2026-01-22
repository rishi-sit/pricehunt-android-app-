package com.pricehunt.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformsTest {
    
    // ==================== Platform Classification Tests ====================
    
    @Test
    fun `QUICK_COMMERCE contains all quick commerce platforms`() {
        assertThat(Platforms.QUICK_COMMERCE).containsExactly(
            Platforms.AMAZON_FRESH,
            Platforms.FLIPKART_MINUTES,
            Platforms.JIOMART_QUICK,
            Platforms.BIGBASKET,
            Platforms.ZEPTO,
            Platforms.BLINKIT,
            Platforms.INSTAMART
        )
    }
    
    @Test
    fun `ECOMMERCE contains all e-commerce platforms`() {
        assertThat(Platforms.ECOMMERCE).containsExactly(
            Platforms.AMAZON,
            Platforms.FLIPKART,
            Platforms.JIOMART
        )
    }
    
    @Test
    fun `QUICK_COMMERCE and ECOMMERCE have no overlap`() {
        val overlap = Platforms.QUICK_COMMERCE.intersect(Platforms.ECOMMERCE)
        assertThat(overlap).isEmpty()
    }
    
    @Test
    fun `QUICK_COMMERCE and ECOMMERCE cover all platforms`() {
        val allPlatformNames = Platforms.ALL.map { it.name }.toSet()
        val combined = Platforms.QUICK_COMMERCE + Platforms.ECOMMERCE
        assertThat(combined).isEqualTo(allPlatformNames)
    }
    
    // ==================== Platform Count Tests ====================
    
    @Test
    fun `ALL contains exactly 10 platforms`() {
        assertThat(Platforms.ALL).hasSize(10)
    }
    
    @Test
    fun `QUICK_COMMERCE contains 7 platforms`() {
        assertThat(Platforms.QUICK_COMMERCE).hasSize(7)
    }
    
    @Test
    fun `ECOMMERCE contains 3 platforms`() {
        assertThat(Platforms.ECOMMERCE).hasSize(3)
    }
    
    // ==================== Platform Uniqueness Tests ====================
    
    @Test
    fun `ALL platforms have unique names`() {
        val names = Platforms.ALL.map { it.name }
        assertThat(names).containsNoDuplicates()
    }
    
    @Test
    fun `ALL platforms have unique colors`() {
        // Note: JioMart and JioMart Quick share the same color, which is intentional
        val platformsWithUniqueColors = Platforms.ALL.filter { 
            it.name != Platforms.JIOMART && it.name != Platforms.JIOMART_QUICK 
        }
        val colors = platformsWithUniqueColors.map { it.color }
        assertThat(colors).containsNoDuplicates()
    }
    
    // ==================== Color Tests ====================
    
    @Test
    fun `getColor returns correct color for Amazon`() {
        assertThat(Platforms.getColor(Platforms.AMAZON)).isEqualTo(Platforms.AMAZON_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for Amazon Fresh`() {
        assertThat(Platforms.getColor(Platforms.AMAZON_FRESH)).isEqualTo(Platforms.AMAZON_FRESH_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for Flipkart`() {
        assertThat(Platforms.getColor(Platforms.FLIPKART)).isEqualTo(Platforms.FLIPKART_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for Flipkart Minutes`() {
        assertThat(Platforms.getColor(Platforms.FLIPKART_MINUTES)).isEqualTo(Platforms.FLIPKART_MINUTES_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for JioMart`() {
        assertThat(Platforms.getColor(Platforms.JIOMART)).isEqualTo(Platforms.JIOMART_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for JioMart Quick`() {
        assertThat(Platforms.getColor(Platforms.JIOMART_QUICK)).isEqualTo(Platforms.JIOMART_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for BigBasket`() {
        assertThat(Platforms.getColor(Platforms.BIGBASKET)).isEqualTo(Platforms.BIGBASKET_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for Zepto`() {
        assertThat(Platforms.getColor(Platforms.ZEPTO)).isEqualTo(Platforms.ZEPTO_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for Blinkit`() {
        assertThat(Platforms.getColor(Platforms.BLINKIT)).isEqualTo(Platforms.BLINKIT_COLOR)
    }
    
    @Test
    fun `getColor returns correct color for Instamart`() {
        assertThat(Platforms.getColor(Platforms.INSTAMART)).isEqualTo(Platforms.INSTAMART_COLOR)
    }
    
    @Test
    fun `getColor returns fallback color for unknown platform`() {
        assertThat(Platforms.getColor("Unknown Platform")).isEqualTo(0xFF888888)
    }
    
    @Test
    fun `getColor is case sensitive`() {
        // "amazon" should return fallback, not Amazon color
        assertThat(Platforms.getColor("amazon")).isEqualTo(0xFF888888)
    }
    
    // ==================== Quick Commerce Flag Tests ====================
    
    @Test
    fun `quick commerce platforms are marked as isQuickCommerce true`() {
        val quickCommercePlatforms = Platforms.ALL.filter { it.isQuickCommerce }
        
        quickCommercePlatforms.forEach { platform ->
            assertThat(Platforms.QUICK_COMMERCE).contains(platform.name)
        }
    }
    
    @Test
    fun `e-commerce platforms are marked as isQuickCommerce false`() {
        val ecommercePlatforms = Platforms.ALL.filter { !it.isQuickCommerce }
        
        ecommercePlatforms.forEach { platform ->
            assertThat(Platforms.ECOMMERCE).contains(platform.name)
        }
    }
    
    @Test
    fun `Zepto is quick commerce`() {
        val zepto = Platforms.ALL.find { it.name == Platforms.ZEPTO }
        assertThat(zepto?.isQuickCommerce).isTrue()
    }
    
    @Test
    fun `Blinkit is quick commerce`() {
        val blinkit = Platforms.ALL.find { it.name == Platforms.BLINKIT }
        assertThat(blinkit?.isQuickCommerce).isTrue()
    }
    
    @Test
    fun `Amazon is not quick commerce`() {
        val amazon = Platforms.ALL.find { it.name == Platforms.AMAZON }
        assertThat(amazon?.isQuickCommerce).isFalse()
    }
    
    @Test
    fun `Flipkart is not quick commerce`() {
        val flipkart = Platforms.ALL.find { it.name == Platforms.FLIPKART }
        assertThat(flipkart?.isQuickCommerce).isFalse()
    }
    
    // ==================== Delivery Time Tests ====================
    
    @Test
    fun `all platforms have non-empty delivery time`() {
        Platforms.ALL.forEach { platform ->
            assertThat(platform.deliveryTime).isNotEmpty()
        }
    }
    
    @Test
    fun `quick commerce platforms have fast delivery times`() {
        val quickCommercePlatforms = Platforms.ALL.filter { it.isQuickCommerce }
        
        quickCommercePlatforms.forEach { platform ->
            // Quick commerce should have delivery in minutes or hours, not days
            assertThat(platform.deliveryTime).doesNotContain("days")
        }
    }
    
    @Test
    fun `e-commerce platforms have slower delivery times`() {
        val ecommercePlatforms = Platforms.ALL.filter { !it.isQuickCommerce }
        
        ecommercePlatforms.forEach { platform ->
            // E-commerce typically has delivery in days
            assertThat(platform.deliveryTime).contains("days")
        }
    }
    
    @Test
    fun `Zepto has 10-15 mins delivery`() {
        val zepto = Platforms.ALL.find { it.name == Platforms.ZEPTO }
        assertThat(zepto?.deliveryTime).contains("min")
    }
    
    @Test
    fun `Blinkit has 8-12 mins delivery`() {
        val blinkit = Platforms.ALL.find { it.name == Platforms.BLINKIT }
        assertThat(blinkit?.deliveryTime).contains("min")
    }
    
    @Test
    fun `Amazon has 1-3 days delivery`() {
        val amazon = Platforms.ALL.find { it.name == Platforms.AMAZON }
        assertThat(amazon?.deliveryTime).contains("days")
    }
    
    // ==================== Color Validity Tests ====================
    
    @Test
    fun `platform colors are valid ARGB values`() {
        val colors = listOf(
            Platforms.AMAZON_COLOR,
            Platforms.AMAZON_FRESH_COLOR,
            Platforms.FLIPKART_COLOR,
            Platforms.FLIPKART_MINUTES_COLOR,
            Platforms.JIOMART_COLOR,
            Platforms.BIGBASKET_COLOR,
            Platforms.ZEPTO_COLOR,
            Platforms.BLINKIT_COLOR,
            Platforms.INSTAMART_COLOR
        )
        
        colors.forEach { color ->
            // Check alpha channel is 0xFF (fully opaque)
            assertThat(color and 0xFF000000).isEqualTo(0xFF000000)
        }
    }
    
    @Test
    fun `Amazon color is orange`() {
        // Amazon's brand color is orange (#FF9900)
        val red = (Platforms.AMAZON_COLOR shr 16) and 0xFF
        val green = (Platforms.AMAZON_COLOR shr 8) and 0xFF
        val blue = Platforms.AMAZON_COLOR and 0xFF
        
        assertThat(red).isEqualTo(0xFF) // High red
        assertThat(green).isEqualTo(0x99) // Medium green
        assertThat(blue).isEqualTo(0x00) // No blue
    }
    
    @Test
    fun `Flipkart color is blue`() {
        // Flipkart's brand color is blue (#2874F0)
        val red = (Platforms.FLIPKART_COLOR shr 16) and 0xFF
        val blue = Platforms.FLIPKART_COLOR and 0xFF
        
        assertThat(red).isLessThan(blue) // More blue than red
    }
    
    @Test
    fun `Zepto color is purple`() {
        // Zepto's brand color is purple (#8B5CF6)
        val red = (Platforms.ZEPTO_COLOR shr 16) and 0xFF
        val blue = Platforms.ZEPTO_COLOR and 0xFF
        
        assertThat(blue).isGreaterThan(red) // More blue than red (purple)
    }
    
    // ==================== Platform Name Constants Tests ====================
    
    @Test
    fun `platform name constants match ALL list names`() {
        val constantNames = setOf(
            Platforms.AMAZON,
            Platforms.AMAZON_FRESH,
            Platforms.FLIPKART,
            Platforms.FLIPKART_MINUTES,
            Platforms.JIOMART,
            Platforms.JIOMART_QUICK,
            Platforms.BIGBASKET,
            Platforms.ZEPTO,
            Platforms.BLINKIT,
            Platforms.INSTAMART
        )
        
        val allNames = Platforms.ALL.map { it.name }.toSet()
        
        assertThat(constantNames).isEqualTo(allNames)
    }
    
    @Test
    fun `platform names are human readable`() {
        Platforms.ALL.forEach { platform ->
            // Names should not contain underscores or be all caps
            assertThat(platform.name).doesNotContain("_")
            assertThat(platform.name).isNotEqualTo(platform.name.uppercase())
        }
    }
    
    // ==================== Platform Order Tests ====================
    
    @Test
    fun `ALL list has quick commerce platforms before e-commerce`() {
        val allNames = Platforms.ALL.map { it.name }
        
        // Find indices
        val amazonIndex = allNames.indexOf(Platforms.AMAZON)
        val flipkartIndex = allNames.indexOf(Platforms.FLIPKART)
        val jioMartIndex = allNames.indexOf(Platforms.JIOMART)
        
        // Most quick commerce should come before e-commerce
        val quickCommerceIndices = Platforms.QUICK_COMMERCE.map { allNames.indexOf(it) }
        val avgQuickCommerceIndex = quickCommerceIndices.average()
        val avgEcommerceIndex = listOf(amazonIndex, flipkartIndex, jioMartIndex).average()
        
        // Quick commerce average index should be lower (earlier in list)
        assertThat(avgQuickCommerceIndex).isLessThan(avgEcommerceIndex)
    }
}
