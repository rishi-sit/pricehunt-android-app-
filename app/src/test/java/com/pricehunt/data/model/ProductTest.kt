package com.pricehunt.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProductTest {
    
    // ==================== Discount Percentage Tests ====================
    
    @Test
    fun `discountPercentage returns correct value when originalPrice is higher`() {
        val product = createTestProduct(price = 80.0, originalPrice = 100.0)
        
        assertThat(product.discountPercentage).isEqualTo(20)
    }
    
    @Test
    fun `discountPercentage returns null when originalPrice is null`() {
        val product = createTestProduct(price = 80.0, originalPrice = null)
        
        assertThat(product.discountPercentage).isNull()
    }
    
    @Test
    fun `discountPercentage returns null when originalPrice equals price`() {
        val product = createTestProduct(price = 100.0, originalPrice = 100.0)
        
        assertThat(product.discountPercentage).isNull()
    }
    
    @Test
    fun `discountPercentage returns null when originalPrice is lower than price`() {
        val product = createTestProduct(price = 100.0, originalPrice = 80.0)
        
        assertThat(product.discountPercentage).isNull()
    }
    
    @Test
    fun `discountPercentage calculates 50 percent discount correctly`() {
        val product = createTestProduct(price = 50.0, originalPrice = 100.0)
        assertThat(product.discountPercentage).isEqualTo(50)
    }
    
    @Test
    fun `discountPercentage calculates 10 percent discount correctly`() {
        val product = createTestProduct(price = 90.0, originalPrice = 100.0)
        assertThat(product.discountPercentage).isEqualTo(10)
    }
    
    @Test
    fun `discountPercentage calculates 75 percent discount correctly`() {
        val product = createTestProduct(price = 25.0, originalPrice = 100.0)
        assertThat(product.discountPercentage).isEqualTo(75)
    }
    
    @Test
    fun `discountPercentage rounds down for fractional discounts`() {
        // 33.33% discount should round to 33
        val product = createTestProduct(price = 200.0, originalPrice = 300.0)
        assertThat(product.discountPercentage).isEqualTo(33)
    }
    
    // ==================== Product Property Tests ====================
    
    @Test
    fun `product preserves all properties correctly`() {
        val product = Product(
            name = "Test Product",
            price = 99.99,
            originalPrice = 149.99,
            discount = "33% off",
            platform = "Amazon",
            platformColor = Platforms.AMAZON_COLOR,
            url = "https://amazon.com/product",
            imageUrl = "https://amazon.com/image.jpg",
            rating = 4.5,
            deliveryTime = "2-3 days",
            available = true
        )
        
        assertThat(product.name).isEqualTo("Test Product")
        assertThat(product.price).isEqualTo(99.99)
        assertThat(product.originalPrice).isEqualTo(149.99)
        assertThat(product.discount).isEqualTo("33% off")
        assertThat(product.platform).isEqualTo("Amazon")
        assertThat(product.platformColor).isEqualTo(Platforms.AMAZON_COLOR)
        assertThat(product.url).isEqualTo("https://amazon.com/product")
        assertThat(product.imageUrl).isEqualTo("https://amazon.com/image.jpg")
        assertThat(product.rating).isEqualTo(4.5)
        assertThat(product.deliveryTime).isEqualTo("2-3 days")
        assertThat(product.available).isTrue()
    }
    
    @Test
    fun `product defaults available to true`() {
        val product = Product(
            name = "Test",
            price = 100.0,
            platform = "Test",
            platformColor = 0xFF000000,
            url = "https://test.com",
            deliveryTime = "1 day"
        )
        
        assertThat(product.available).isTrue()
    }
    
    @Test
    fun `unavailable product has available false`() {
        val product = createTestProduct(available = false)
        
        assertThat(product.available).isFalse()
    }
    
    // ==================== Platform-Specific Product Tests ====================
    
    @Test
    fun `product from Zepto has correct platform color`() {
        val product = createTestProduct(platform = Platforms.ZEPTO)
        
        assertThat(product.platformColor).isEqualTo(Platforms.ZEPTO_COLOR)
    }
    
    @Test
    fun `product from Blinkit has correct platform color`() {
        val product = createTestProduct(platform = Platforms.BLINKIT)
        
        assertThat(product.platformColor).isEqualTo(Platforms.BLINKIT_COLOR)
    }
    
    @Test
    fun `product from Amazon has correct platform color`() {
        val product = createTestProduct(platform = Platforms.AMAZON)
        
        assertThat(product.platformColor).isEqualTo(Platforms.AMAZON_COLOR)
    }
    
    @Test
    fun `product from Flipkart has correct platform color`() {
        val product = createTestProduct(platform = Platforms.FLIPKART)
        
        assertThat(product.platformColor).isEqualTo(Platforms.FLIPKART_COLOR)
    }
    
    @Test
    fun `product from BigBasket has correct platform color`() {
        val product = createTestProduct(platform = Platforms.BIGBASKET)
        
        assertThat(product.platformColor).isEqualTo(Platforms.BIGBASKET_COLOR)
    }
    
    @Test
    fun `product from Instamart has correct platform color`() {
        val product = createTestProduct(platform = Platforms.INSTAMART)
        
        assertThat(product.platformColor).isEqualTo(Platforms.INSTAMART_COLOR)
    }
    
    // ==================== Grocery Product Tests ====================
    
    @Test
    fun `milk product has valid properties`() {
        val product = Product(
            name = "Amul Toned Milk 500ml",
            price = 29.0,
            platform = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            url = "https://zepto.com/milk",
            deliveryTime = "10-15 mins",
            available = true
        )
        
        assertThat(product.name).contains("Milk")
        assertThat(product.price).isGreaterThan(0.0)
        assertThat(product.platform).isEqualTo(Platforms.ZEPTO)
    }
    
    @Test
    fun `bread product has valid properties`() {
        val product = Product(
            name = "Britannia Bread 400g",
            price = 40.0,
            platform = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            url = "https://blinkit.com/bread",
            deliveryTime = "8-12 mins",
            available = true
        )
        
        assertThat(product.name).contains("Bread")
        assertThat(product.price).isGreaterThan(0.0)
    }
    
    @Test
    fun `eggs product has valid properties`() {
        val product = Product(
            name = "Farm Fresh Eggs 12pcs",
            price = 90.0,
            platform = Platforms.BIGBASKET,
            platformColor = Platforms.BIGBASKET_COLOR,
            url = "https://bigbasket.com/eggs",
            deliveryTime = "2-4 hours",
            available = true
        )
        
        assertThat(product.name).contains("Eggs")
        assertThat(product.price).isGreaterThan(0.0)
    }
    
    @Test
    fun `rice product from Amazon has valid properties`() {
        val product = Product(
            name = "India Gate Basmati Rice 5kg",
            price = 450.0,
            originalPrice = 550.0,
            discount = "18% off",
            platform = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            url = "https://amazon.in/rice",
            imageUrl = "https://amazon.in/rice.jpg",
            rating = 4.5,
            deliveryTime = "1-3 days",
            available = true
        )
        
        assertThat(product.name).contains("Rice")
        assertThat(product.originalPrice).isGreaterThan(product.price)
        assertThat(product.discountPercentage).isEqualTo(18)
    }
    
    @Test
    fun `atta product with discount has valid discount percentage`() {
        val product = Product(
            name = "Aashirvaad Atta 10kg",
            price = 450.0,
            originalPrice = 550.0,
            discount = "18% off",
            platform = Platforms.FLIPKART,
            platformColor = Platforms.FLIPKART_COLOR,
            url = "https://flipkart.com/atta",
            deliveryTime = "2-4 days",
            available = true
        )
        
        assertThat(product.discountPercentage).isEqualTo(18)
    }
    
    @Test
    fun `oil product has valid properties`() {
        val product = Product(
            name = "Fortune Sunflower Oil 1L",
            price = 180.0,
            platform = Platforms.JIOMART,
            platformColor = Platforms.JIOMART_COLOR,
            url = "https://jiomart.com/oil",
            deliveryTime = "1-3 days",
            available = true
        )
        
        assertThat(product.name).contains("Oil")
        assertThat(product.price).isGreaterThan(0.0)
    }
    
    @Test
    fun `sugar product has valid properties`() {
        val product = Product(
            name = "Sugar 1kg",
            price = 48.0,
            platform = Platforms.AMAZON_FRESH,
            platformColor = Platforms.AMAZON_FRESH_COLOR,
            url = "https://amazon.in/fresh/sugar",
            deliveryTime = "2-4 hours",
            available = true
        )
        
        assertThat(product.name).contains("Sugar")
        assertThat(product.price).isGreaterThan(0.0)
    }
    
    @Test
    fun `dal product with rating has valid properties`() {
        val product = Product(
            name = "Toor Dal 1kg",
            price = 150.0,
            platform = Platforms.FLIPKART,
            platformColor = Platforms.FLIPKART_COLOR,
            url = "https://flipkart.com/dal",
            rating = 4.2,
            deliveryTime = "2-4 days",
            available = true
        )
        
        assertThat(product.name).contains("Dal")
        assertThat(product.rating).isEqualTo(4.2)
    }
    
    // ==================== Rating Tests ====================
    
    @Test
    fun `product with 5 star rating is valid`() {
        val product = createTestProduct(rating = 5.0)
        assertThat(product.rating).isEqualTo(5.0)
    }
    
    @Test
    fun `product with no rating has null rating`() {
        val product = createTestProduct(rating = null)
        assertThat(product.rating).isNull()
    }
    
    @Test
    fun `product with fractional rating is valid`() {
        val product = createTestProduct(rating = 4.3)
        assertThat(product.rating).isEqualTo(4.3)
    }
    
    // ==================== Price Tests ====================
    
    @Test
    fun `product price can be very low for grocery items`() {
        val product = createTestProduct(price = 5.0) // ₹5 for a small item
        assertThat(product.price).isEqualTo(5.0)
    }
    
    @Test
    fun `product price can be high for bulk items`() {
        val product = createTestProduct(price = 2500.0) // ₹2500 for bulk rice
        assertThat(product.price).isEqualTo(2500.0)
    }
    
    @Test
    fun `product with zero price is technically valid`() {
        val product = createTestProduct(price = 0.0)
        assertThat(product.price).isEqualTo(0.0)
    }
    
    // ==================== URL Tests ====================
    
    @Test
    fun `product URL can be https`() {
        val product = createTestProduct(url = "https://amazon.in/product")
        assertThat(product.url).startsWith("https://")
    }
    
    @Test
    fun `product URL can contain query parameters`() {
        val product = createTestProduct(url = "https://amazon.in/product?id=123&ref=search")
        assertThat(product.url).contains("?")
    }
    
    // ==================== Image URL Tests ====================
    
    @Test
    fun `product can have image URL`() {
        val product = createTestProduct(imageUrl = "https://amazon.in/image.jpg")
        assertThat(product.imageUrl).isNotNull()
    }
    
    @Test
    fun `product can have null image URL`() {
        val product = createTestProduct(imageUrl = null)
        assertThat(product.imageUrl).isNull()
    }
    
    // ==================== Delivery Time Tests ====================
    
    @Test
    fun `quick commerce product has fast delivery time`() {
        val product = Product(
            name = "Milk",
            price = 50.0,
            platform = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            url = "https://zepto.com/milk",
            deliveryTime = "10-15 mins",
            available = true
        )
        
        assertThat(product.deliveryTime).contains("min")
    }
    
    @Test
    fun `e-commerce product has slower delivery time`() {
        val product = Product(
            name = "Rice",
            price = 500.0,
            platform = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            url = "https://amazon.in/rice",
            deliveryTime = "1-3 days",
            available = true
        )
        
        assertThat(product.deliveryTime).contains("days")
    }
    
    // ==================== Equality Tests ====================
    
    @Test
    fun `two products with same properties are equal`() {
        val product1 = createTestProduct(name = "Milk", price = 50.0, platform = Platforms.ZEPTO)
        val product2 = createTestProduct(name = "Milk", price = 50.0, platform = Platforms.ZEPTO)
        
        assertThat(product1).isEqualTo(product2)
    }
    
    @Test
    fun `two products with different prices are not equal`() {
        val product1 = createTestProduct(name = "Milk", price = 50.0, platform = Platforms.ZEPTO)
        val product2 = createTestProduct(name = "Milk", price = 55.0, platform = Platforms.ZEPTO)
        
        assertThat(product1).isNotEqualTo(product2)
    }
    
    @Test
    fun `two products with different platforms are not equal`() {
        val product1 = createTestProduct(name = "Milk", price = 50.0, platform = Platforms.ZEPTO)
        val product2 = createTestProduct(name = "Milk", price = 50.0, platform = Platforms.BLINKIT)
        
        assertThat(product1).isNotEqualTo(product2)
    }
    
    // ==================== Helper Methods ====================
    
    private fun createTestProduct(
        name: String = "Test Product",
        price: Double = 100.0,
        originalPrice: Double? = null,
        discount: String? = null,
        platform: String = Platforms.AMAZON,
        platformColor: Long = Platforms.getColor(platform),
        url: String = "https://test.com/product",
        imageUrl: String? = null,
        rating: Double? = null,
        deliveryTime: String = "2-3 days",
        available: Boolean = true
    ) = Product(
        name = name,
        price = price,
        originalPrice = originalPrice,
        discount = discount,
        platform = platform,
        platformColor = platformColor,
        url = url,
        imageUrl = imageUrl,
        rating = rating,
        deliveryTime = deliveryTime,
        available = available
    )
}
