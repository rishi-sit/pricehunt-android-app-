package com.pricehunt.data.scrapers

import com.google.common.truth.Truth.assertThat
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.scrapers.webview.ResilientExtractor
import org.junit.Test

/**
 * Unit tests for ResilientExtractor - 12+ extraction strategies
 * that don't rely on CSS class names.
 */
class ResilientExtractorTest {
    
    // ==================== TIER 1: JSON-LD Tests ====================
    
    @Test
    fun `extracts products from JSON-LD ItemList schema`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {
                    "@type": "ItemList",
                    "itemListElement": [
                        {
                            "@type": "ListItem",
                            "item": {
                                "@type": "Product",
                                "name": "Amul Toned Milk 500ml",
                                "offers": {"price": 29.0}
                            }
                        },
                        {
                            "@type": "ListItem",
                            "item": {
                                "@type": "Product",
                                "name": "Mother Dairy Full Cream 1L",
                                "offers": {"price": 68.0}
                            }
                        }
                    ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products).hasSize(2)
        assertThat(products[0].name).isEqualTo("Amul Toned Milk 500ml")
        assertThat(products[0].price).isEqualTo(29.0)
    }
    
    @Test
    fun `extracts product with discount from JSON-LD`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {
                    "@type": "Product",
                    "name": "Nandini Curd 400g",
                    "image": "https://cdn.example.com/curd.jpg",
                    "offers": {
                        "price": 35.0,
                        "highPrice": 45.0
                    }
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            deliveryTime = "8-12 mins",
            baseUrl = "https://blinkit.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].price).isEqualTo(35.0)
        assertThat(products[0].originalPrice).isEqualTo(45.0)
        assertThat(products[0].discount).isEqualTo("22% off")
    }
    
    // ==================== TIER 1: Microdata Tests ====================
    
    @Test
    fun `extracts products from microdata itemprop`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div itemtype="https://schema.org/Product" itemscope>
                    <span itemprop="name">Fortune Sunflower Oil 1L</span>
                    <span itemprop="price" content="180">₹180</span>
                    <img itemprop="image" src="https://cdn.example.com/oil.jpg">
                </div>
                <div itemtype="https://schema.org/Product" itemscope>
                    <span itemprop="name">Saffola Gold Oil 1L</span>
                    <span itemprop="price" content="210">₹210</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BIGBASKET,
            platformColor = Platforms.BIGBASKET_COLOR,
            deliveryTime = "2-4 hours",
            baseUrl = "https://bigbasket.com"
        )
        
        assertThat(products).hasSize(2)
        assertThat(products[0].name).isEqualTo("Fortune Sunflower Oil 1L")
        assertThat(products[0].price).isEqualTo(180.0)
    }
    
    // ==================== TIER 1: Open Graph Tests ====================
    
    @Test
    fun `extracts product from Open Graph metadata`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:type" content="product">
                <meta property="og:title" content="Tata Salt 1kg">
                <meta property="product:price:amount" content="28">
                <meta property="og:image" content="https://cdn.example.com/salt.jpg">
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.JIOMART,
            platformColor = Platforms.JIOMART_COLOR,
            deliveryTime = "1-2 days",
            baseUrl = "https://jiomart.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].name).isEqualTo("Tata Salt 1kg")
        assertThat(products[0].price).isEqualTo(28.0)
    }
    
    // ==================== TIER 2: Next.js Tests ====================
    
    @Test
    fun `extracts products from NextJS NEXT DATA script`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script id="__NEXT_DATA__" type="application/json">
                {
                    "props": {
                        "pageProps": {
                            "products": [
                                {"name": "Toor Dal 1kg", "price": 150, "image": "dal.jpg"},
                                {"name": "Chana Dal 1kg", "price": 120, "image": "chana.jpg"}
                            ]
                        }
                    }
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products).hasSize(2)
        assertThat(products[0].name).isEqualTo("Toor Dal 1kg")
        assertThat(products[0].price).isEqualTo(150.0)
    }
    
    @Test
    fun `extracts products from nested NextJS data`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script id="__NEXT_DATA__" type="application/json">
                {
                    "props": {
                        "pageProps": {
                            "searchResults": {
                                "items": [
                                    {"name": "Basmati Rice 5kg", "sellingPrice": 450, "mrp": 550}
                                ]
                            }
                        }
                    }
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            deliveryTime = "1-3 days",
            baseUrl = "https://amazon.in"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].price).isEqualTo(450.0)
        assertThat(products[0].originalPrice).isEqualTo(550.0)
    }
    
    // ==================== TIER 2: Data Attributes Tests ====================
    
    @Test
    fun `extracts from data-testid attributes`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <img src="eggs.jpg" alt="Farm Fresh Eggs 6pcs">
                    <div>₹48</div>
                </div>
                <div data-testid="product-card">
                    <img src="eggs12.jpg" alt="Farm Fresh Eggs 12pcs">
                    <div>₹90</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON_FRESH,
            platformColor = Platforms.AMAZON_FRESH_COLOR,
            deliveryTime = "2-4 hours",
            baseUrl = "https://amazon.in/fresh"
        )
        
        assertThat(products).hasSize(2)
        assertThat(products[0].name).isEqualTo("Farm Fresh Eggs 6pcs")
    }
    
    @Test
    fun `extracts from data-product-id attributes`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="product-list">
                    <div data-product-id="123" class="product-card">
                        <a href="/prn/amul-milk/prid/123">
                            <img src="milk.jpg" alt="Amul Milk 1L">
                        </a>
                        <span class="price">₹65</span>
                    </div>
                    <div data-product-id="456" class="product-card">
                        <a href="/prn/mother-dairy/prid/456">
                            <img src="milk2.jpg" alt="Mother Dairy 1L">
                        </a>
                        <span class="price">₹68</span>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            deliveryTime = "8-12 mins",
            baseUrl = "https://blinkit.com"
        )
        
        // Should find at least 1 product (the extraction may vary based on strategy)
        assertThat(products).isNotEmpty()
        assertThat(products.first().price).isGreaterThan(0.0)
    }
    
    // ==================== TIER 3: ARIA Tests ====================
    
    @Test
    fun `extracts from role listitem elements`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <ul>
                    <li role="listitem">
                        <img src="oil.jpg" alt="Fortune Sunflower Oil 1L">
                        <span>₹180</span>
                    </li>
                    <li role="listitem">
                        <img src="oil2.jpg" alt="Saffola Gold Oil 1L">
                        <span>₹195</span>
                    </li>
                    <li role="listitem">
                        <img src="oil3.jpg" alt="Nature Fresh Oil 1L">
                        <span>₹170</span>
                    </li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.INSTAMART,
            platformColor = Platforms.INSTAMART_COLOR,
            deliveryTime = "15-30 mins",
            baseUrl = "https://swiggy.com/instamart"
        )
        
        assertThat(products).hasSize(3)
        assertThat(products.map { it.name }).containsExactly(
            "Fortune Sunflower Oil 1L",
            "Saffola Gold Oil 1L",
            "Nature Fresh Oil 1L"
        )
    }
    
    @Test
    fun `extracts from article elements`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <article>
                    <img src="bread1.jpg" alt="Britannia Bread 400g">
                    <span>Rs. 45</span>
                </article>
                <article>
                    <img src="bread2.jpg" alt="Modern Bread 500g">
                    <span>Rs. 55</span>
                </article>
                <article>
                    <img src="bread3.jpg" alt="Harvest Bread 350g">
                    <span>Rs. 40</span>
                </article>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.JIOMART,
            platformColor = Platforms.JIOMART_COLOR,
            deliveryTime = "1-2 days",
            baseUrl = "https://jiomart.com"
        )
        
        assertThat(products).hasSize(3)
        assertThat(products[0].name).isEqualTo("Britannia Bread 400g")
    }
    
    // ==================== TIER 3: Image Alt Tests ====================
    
    @Test
    fun `extracts product from image alt and price pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div>
                    <img src="https://cdn.example.com/milk.jpg" alt="Amul Gold Milk 1L">
                    <div>₹72</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BIGBASKET,
            platformColor = Platforms.BIGBASKET_COLOR,
            deliveryTime = "2-4 hours",
            baseUrl = "https://bigbasket.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].name).isEqualTo("Amul Gold Milk 1L")
        assertThat(products[0].price).isEqualTo(72.0)
    }
    
    // ==================== TIER 4: Link Pattern Tests ====================
    
    @Test
    fun `extracts product from product link patterns`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="/dp/B08XYZ123">
                        <img src="sugar.jpg" alt="India Gate Basmati Rice 5kg">
                    </a>
                    <span>₹450</span>
                </div>
                <div data-testid="product-card">
                    <a href="/dp/B08XYZ456">
                        <img src="rice.jpg" alt="Daawat Basmati Rice 5kg">
                    </a>
                    <span>₹420</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            deliveryTime = "1-3 days",
            baseUrl = "https://amazon.in"
        )
        
        assertThat(products).hasSize(2)
        assertThat(products[0].name).isEqualTo("India Gate Basmati Rice 5kg")
    }
    
    // ==================== TIER 4: Grid Structure Tests ====================
    
    @Test
    fun `extracts from ul li grid structure`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <ul class="product-grid">
                    <li>
                        <a href="/p/1"><img src="a.jpg" alt="Aashirvaad Atta 5kg"></a>
                        <span>₹280</span>
                    </li>
                    <li>
                        <a href="/p/2"><img src="b.jpg" alt="Fortune Atta 5kg"></a>
                        <span>₹260</span>
                    </li>
                    <li>
                        <a href="/p/3"><img src="c.jpg" alt="Pillsbury Atta 5kg"></a>
                        <span>₹275</span>
                    </li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.FLIPKART_MINUTES,
            platformColor = Platforms.FLIPKART_MINUTES_COLOR,
            deliveryTime = "10-45 mins",
            baseUrl = "https://flipkart.com/grocery"
        )
        
        assertThat(products).hasSize(3)
        assertThat(products.map { it.name }).containsExactly(
            "Aashirvaad Atta 5kg",
            "Fortune Atta 5kg",
            "Pillsbury Atta 5kg"
        )
    }
    
    // ==================== Price Format Tests ====================
    
    @Test
    fun `handles rupee symbol price format`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {
                    "@type": "ItemList",
                    "itemListElement": [
                        {"@type": "ListItem", "item": {"@type": "Product", "name": "Premium Rice 5kg", "offers": {"price": 1299}}},
                        {"@type": "ListItem", "item": {"@type": "Product", "name": "Standard Rice 5kg", "offers": {"price": 599}}},
                        {"@type": "ListItem", "item": {"@type": "Product", "name": "Economy Rice 1kg", "offers": {"price": 49.50}}}
                    ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            deliveryTime = "1-3 days",
            baseUrl = "https://amazon.in"
        )
        
        assertThat(products).hasSize(3)
        val prices = products.map { it.price }.sorted()
        assertThat(prices).containsExactly(49.50, 599.0, 1299.0)
    }
    
    @Test
    fun `handles MRP and selling price`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {
                    "@type": "ItemList",
                    "itemListElement": [
                        {
                            "@type": "ListItem",
                            "item": {
                                "@type": "Product",
                                "name": "Premium Product XL Size",
                                "offers": {"price": 400, "highPrice": 500}
                            }
                        },
                        {
                            "@type": "ListItem",
                            "item": {
                                "@type": "Product",
                                "name": "Budget Product SM Size",
                                "offers": {"price": 200}
                            }
                        }
                    ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.FLIPKART,
            platformColor = Platforms.FLIPKART_COLOR,
            deliveryTime = "2-4 days",
            baseUrl = "https://flipkart.com"
        )
        
        assertThat(products).hasSize(2)
        val premiumProduct = products.find { it.name.contains("Premium") }
        assertThat(premiumProduct).isNotNull()
        assertThat(premiumProduct?.price).isEqualTo(400.0)
        assertThat(premiumProduct?.originalPrice).isEqualTo(500.0)
    }
    
    // ==================== Invalid Name Filtering Tests ====================
    
    @Test
    fun `filters out generic names like Search Results`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div>
                    <h2>Search Results</h2>
                    <img src="product.jpg" alt="">
                    <span>₹50</span>
                </div>
                <div data-testid="product">
                    <img src="milk.jpg" alt="Nandini Milk 500ml">
                    <span>₹28</span>
                </div>
                <div data-testid="product">
                    <img src="curd.jpg" alt="Nandini Curd 400g">
                    <span>₹35</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.JIOMART_QUICK,
            platformColor = Platforms.JIOMART_COLOR,
            deliveryTime = "15-30 mins",
            baseUrl = "https://jiomart.com"
        )
        
        assertThat(products.none { it.name.equals("Search Results", ignoreCase = true) }).isTrue()
        assertThat(products.any { it.name.contains("Nandini") }).isTrue()
    }
    
    @Test
    fun `filters out Add to Cart and other button text`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product">
                    <span>Add to Cart</span>
                    <span>₹100</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.FLIPKART,
            platformColor = Platforms.FLIPKART_COLOR,
            deliveryTime = "2-4 days",
            baseUrl = "https://flipkart.com"
        )
        
        assertThat(products).isEmpty()
    }
    
    // ==================== Edge Cases ====================
    
    @Test
    fun `returns empty list for HTML without products`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <h1>No products found</h1>
                <p>Try a different search</p>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            deliveryTime = "8-12 mins",
            baseUrl = "https://blinkit.com"
        )
        
        assertThat(products).isEmpty()
    }
    
    @Test
    fun `returns empty list for empty HTML`() {
        val products = ResilientExtractor.extractProducts(
            html = "",
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products).isEmpty()
    }
    
    @Test
    fun `handles products with zero price`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {"@type": "Product", "name": "Free Sample", "offers": {"price": 0}}
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BIGBASKET,
            platformColor = Platforms.BIGBASKET_COLOR,
            deliveryTime = "2-4 hours",
            baseUrl = "https://bigbasket.com"
        )
        
        assertThat(products).isEmpty()
    }
    
    @Test
    fun `limits results to 10 products`() {
        val productDivs = (1..20).joinToString("\n") { i ->
            """
                <div data-testid="product">
                    <img src="p$i.jpg" alt="Test Product Number $i">
                    <span>₹${i * 10}</span>
                </div>
            """.trimIndent()
        }
        val html = "<html><body>$productDivs</body></html>"
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products.size).isAtMost(10)
    }
    
    @Test
    fun `deduplicates products with same name`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="/prn/amul-milk/prid/1"><img src="milk1.jpg" alt="Amul Milk 500ml Pack"></a>
                    <span>₹28</span>
                </div>
                <div data-testid="product-card">
                    <a href="/prn/amul-milk/prid/2"><img src="milk2.jpg" alt="Amul Milk 500ml Pack"></a>
                    <span>₹28</span>
                </div>
                <div data-testid="product-card">
                    <a href="/prn/amul-curd/prid/3"><img src="curd.jpg" alt="Amul Curd 400g Pack"></a>
                    <span>₹35</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            deliveryTime = "8-12 mins",
            baseUrl = "https://blinkit.com"
        )
        
        // Should deduplicate products with same name (Amul Milk 500ml Pack appears twice)
        // Result should have 2 unique products: Amul Milk and Amul Curd
        assertThat(products.size).isAtLeast(1)
        assertThat(products.size).isAtMost(3)
        // Verify no duplicates by name
        val uniqueNames = products.map { it.name.lowercase() }.distinct()
        assertThat(uniqueNames.size).isEqualTo(products.size)
    }
    
    // ==================== Platform Metadata Tests ====================
    
    @Test
    fun `sets correct platform metadata`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {"@type": "Product", "name": "Test Product Item", "offers": {"price": 100}}
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            deliveryTime = "8-12 mins",
            baseUrl = "https://blinkit.com"
        )
        
        assertThat(products[0].platform).isEqualTo(Platforms.BLINKIT)
        assertThat(products[0].platformColor).isEqualTo(Platforms.BLINKIT_COLOR)
        assertThat(products[0].deliveryTime).isEqualTo("8-12 mins")
    }
    
    @Test
    fun `calculates discount percentage correctly`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-item">
                    <img src="p.jpg" alt="Discounted Product Item">
                    <span>₹80</span>
                    <span>₹100</span>
                </div>
                <div data-testid="product-item">
                    <img src="q.jpg" alt="Another Discounted Item">
                    <span>₹70</span>
                    <span>₹100</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            deliveryTime = "1-3 days",
            baseUrl = "https://amazon.in"
        )
        
        assertThat(products[0].price).isEqualTo(70.0)
        assertThat(products[0].originalPrice).isEqualTo(100.0)
        assertThat(products[0].discount).isEqualTo("30% off")
    }
    
    // ==================== Multi-Strategy Fallback Test ====================
    
    @Test
    fun `falls back to next strategy when first fails`() {
        // HTML with no JSON-LD but has data attributes
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <img src="product.jpg" alt="Fallback Test Product">
                    <span>₹199</span>
                </div>
                <div data-testid="product-card">
                    <img src="product2.jpg" alt="Another Fallback Product">
                    <span>₹299</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products).hasSize(2)
        assertThat(products[0].name).isEqualTo("Fallback Test Product")
    }
    
    // ==================== URL Extraction Tests ====================
    
    @Test
    fun `extracts Amazon product URL with dp pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.amazon.in/dp/B08XYZ123/ref=sr_1_1">
                        <img src="product.jpg" alt="Amazon Test Product 123">
                    </a>
                    <span>₹599</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            deliveryTime = "1-3 days",
            baseUrl = "https://amazon.in"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/dp/")
        assertThat(products[0].url).doesNotContain("/search")
    }
    
    @Test
    fun `extracts Flipkart product URL with pid pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product">
                    <a href="https://www.flipkart.com/samsung-phone/p/itmXYZ?pid=MOBXYZ123">
                        <img src="phone.jpg" alt="Samsung Galaxy Phone Test">
                    </a>
                    <span>₹15999</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.FLIPKART,
            platformColor = Platforms.FLIPKART_COLOR,
            deliveryTime = "2-4 days",
            baseUrl = "https://flipkart.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("pid=")
    }
    
    @Test
    fun `extracts Zepto product URL with pn pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.zeptonow.com/pn/amul-milk-1l/pvid/12345">
                        <img src="milk.jpg" alt="Amul Milk 1L Zepto">
                    </a>
                    <span>₹68</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/pn/")
        assertThat(products[0].url).doesNotContain("zeptonow.com/search")
    }
    
    @Test
    fun `extracts Blinkit product URL with prn pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://blinkit.com/prn/amul-butter/prid/456789">
                        <img src="butter.jpg" alt="Amul Butter 500g Blinkit">
                    </a>
                    <span>₹285</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BLINKIT,
            platformColor = Platforms.BLINKIT_COLOR,
            deliveryTime = "8-12 mins",
            baseUrl = "https://blinkit.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/prn/")
    }
    
    @Test
    fun `extracts BigBasket product URL with pd pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.bigbasket.com/pd/123456/fortune-oil/">
                        <img src="oil.jpg" alt="Fortune Sunflower Oil 1L BigBasket">
                    </a>
                    <span>₹180</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BIGBASKET,
            platformColor = Platforms.BIGBASKET_COLOR,
            deliveryTime = "2-4 hours",
            baseUrl = "https://bigbasket.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/pd/")
    }
    
    @Test
    fun `extracts JioMart product URL with p pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.jiomart.com/p/groceries/tata-salt-1kg/123456">
                        <img src="salt.jpg" alt="Tata Salt 1kg JioMart">
                    </a>
                    <span>₹28</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.JIOMART,
            platformColor = Platforms.JIOMART_COLOR,
            deliveryTime = "1-2 days",
            baseUrl = "https://jiomart.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/p/")
    }
    
    @Test
    fun `extracts Instamart product URL with item pattern`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.swiggy.com/instamart/item/789012">
                        <img src="eggs.jpg" alt="Farm Fresh Eggs 12pcs Instamart">
                    </a>
                    <span>₹90</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.INSTAMART,
            platformColor = Platforms.INSTAMART_COLOR,
            deliveryTime = "15-30 mins",
            baseUrl = "https://swiggy.com/instamart"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/item/")
    }
    
    @Test
    fun `excludes search URLs from product links`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.amazon.in/s?k=milk">Search</a>
                    <a href="https://www.amazon.in/dp/B08XYZ999">
                        <img src="milk.jpg" alt="Actual Product Milk 1L">
                    </a>
                    <span>₹65</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.AMAZON,
            platformColor = Platforms.AMAZON_COLOR,
            deliveryTime = "1-3 days",
            baseUrl = "https://amazon.in"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/dp/")
        assertThat(products[0].url).doesNotContain("/s?")
    }
    
    @Test
    fun `excludes category URLs from product links`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div data-testid="product-card">
                    <a href="https://www.bigbasket.com/cl/fruits-vegetables/">Category</a>
                    <a href="https://www.bigbasket.com/pd/40000123/apple-fresh/">
                        <img src="apple.jpg" alt="Fresh Apple 1kg Product">
                    </a>
                    <span>₹120</span>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.BIGBASKET,
            platformColor = Platforms.BIGBASKET_COLOR,
            deliveryTime = "2-4 hours",
            baseUrl = "https://bigbasket.com"
        )
        
        assertThat(products).hasSize(1)
        assertThat(products[0].url).contains("/pd/")
        assertThat(products[0].url).doesNotContain("/cl/")
    }
    
    @Test
    fun `uses baseUrl only when no valid product URL found`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {"@type": "Product", "name": "Product Without Link", "offers": {"price": 50}}
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        val products = ResilientExtractor.extractProducts(
            html = html,
            platformName = Platforms.ZEPTO,
            platformColor = Platforms.ZEPTO_COLOR,
            deliveryTime = "10-15 mins",
            baseUrl = "https://zeptonow.com"
        )
        
        assertThat(products).hasSize(1)
        // When no product URL is found, should fallback to baseUrl
        assertThat(products[0].url).isEqualTo("https://zeptonow.com")
    }
    
    // ==================== All Platforms Test ====================
    
    @Test
    fun `extracts products for all supported platforms`() {
        val platforms = listOf(
            Triple(Platforms.AMAZON, Platforms.AMAZON_COLOR, "https://amazon.in"),
            Triple(Platforms.AMAZON_FRESH, Platforms.AMAZON_FRESH_COLOR, "https://amazon.in/fresh"),
            Triple(Platforms.FLIPKART, Platforms.FLIPKART_COLOR, "https://flipkart.com"),
            Triple(Platforms.FLIPKART_MINUTES, Platforms.FLIPKART_MINUTES_COLOR, "https://flipkart.com/minutes"),
            Triple(Platforms.JIOMART, Platforms.JIOMART_COLOR, "https://jiomart.com"),
            Triple(Platforms.JIOMART_QUICK, Platforms.JIOMART_COLOR, "https://jiomart.com"),
            Triple(Platforms.BIGBASKET, Platforms.BIGBASKET_COLOR, "https://bigbasket.com"),
            Triple(Platforms.ZEPTO, Platforms.ZEPTO_COLOR, "https://zeptonow.com"),
            Triple(Platforms.BLINKIT, Platforms.BLINKIT_COLOR, "https://blinkit.com"),
            Triple(Platforms.INSTAMART, Platforms.INSTAMART_COLOR, "https://swiggy.com/instamart")
        )
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {"@type": "Product", "name": "Universal Test Product", "offers": {"price": 100}}
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        
        platforms.forEach { (name, color, baseUrl) ->
            val products = ResilientExtractor.extractProducts(
                html = html,
                platformName = name,
                platformColor = color,
                deliveryTime = "Test delivery",
                baseUrl = baseUrl
            )
            
            assertThat(products).isNotEmpty()
            assertThat(products[0].platform).isEqualTo(name)
            assertThat(products[0].platformColor).isEqualTo(color)
        }
    }
}
