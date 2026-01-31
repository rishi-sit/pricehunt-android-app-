package com.pricehunt.data.repository

import com.google.common.truth.Truth.assertThat
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.remote.PriceHuntApi
import com.pricehunt.data.remote.SearchResponse
import com.pricehunt.data.scrapers.FallbackScraperManager
import com.pricehunt.data.scrapers.SelfHealingScraper
import com.pricehunt.data.scrapers.api.DirectApiScraper
import com.pricehunt.data.scrapers.health.PlatformHealthMonitor
import com.pricehunt.data.scrapers.http.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ProductRepositoryTest {
    
    @MockK
    private lateinit var api: PriceHuntApi
    
    @MockK
    private lateinit var cacheManager: CacheManager
    
    @MockK
    private lateinit var amazonScraper: AmazonScraper
    
    @MockK
    private lateinit var amazonFreshScraper: AmazonFreshScraper
    
    @MockK
    private lateinit var flipkartScraper: FlipkartScraper
    
    @MockK
    private lateinit var flipkartMinutesScraper: FlipkartMinutesScraper
    
    @MockK
    private lateinit var jioMartScraper: JioMartScraper
    
    @MockK
    private lateinit var jioMartQuickScraper: JioMartQuickScraper
    
    @MockK
    private lateinit var bigBasketScraper: BigBasketScraper
    
    @MockK
    private lateinit var zeptoScraper: ZeptoScraper
    
    @MockK
    private lateinit var blinkitScraper: BlinkitScraper
    
    @MockK
    private lateinit var instamartScraper: InstamartScraper

    @MockK
    private lateinit var fallbackManager: FallbackScraperManager

    @MockK
    private lateinit var selfHealingScraper: SelfHealingScraper

    @MockK
    private lateinit var healthMonitor: PlatformHealthMonitor

    @MockK
    private lateinit var directApiScraper: DirectApiScraper
    
    private lateinit var repository: ProductRepository
    
    @Before
    fun setup() {
        MockKAnnotations.init(this)
        
        // Setup platform names
        every { amazonScraper.platformName } returns Platforms.AMAZON
        every { amazonFreshScraper.platformName } returns Platforms.AMAZON_FRESH
        every { flipkartScraper.platformName } returns Platforms.FLIPKART
        every { flipkartMinutesScraper.platformName } returns Platforms.FLIPKART_MINUTES
        every { jioMartScraper.platformName } returns Platforms.JIOMART
        every { jioMartQuickScraper.platformName } returns Platforms.JIOMART_QUICK
        every { bigBasketScraper.platformName } returns Platforms.BIGBASKET
        every { zeptoScraper.platformName } returns Platforms.ZEPTO
        every { blinkitScraper.platformName } returns Platforms.BLINKIT
        every { instamartScraper.platformName } returns Platforms.INSTAMART
        
        repository = ProductRepository(
            api = api,
            cacheManager = cacheManager,
            amazonScraper = amazonScraper,
            amazonFreshScraper = amazonFreshScraper,
            flipkartScraper = flipkartScraper,
            flipkartMinutesScraper = flipkartMinutesScraper,
            jioMartScraper = jioMartScraper,
            jioMartQuickScraper = jioMartQuickScraper,
            bigBasketScraper = bigBasketScraper,
            zeptoScraper = zeptoScraper,
            blinkitScraper = blinkitScraper,
            instamartScraper = instamartScraper,
            fallbackManager = fallbackManager,
            selfHealingScraper = selfHealingScraper,
            healthMonitor = healthMonitor,
            directApiScraper = directApiScraper
        )
    }
    
    // ==================== Best Deal Tests ====================
    
    @Test
    fun `findBestDeal returns cheapest available product across all platforms`() {
        // Given - Products from multiple platforms
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Amazon Milk", 150.0, Platforms.AMAZON),
                createTestProduct("Amazon Milk 2L", 100.0, Platforms.AMAZON)
            ),
            Platforms.FLIPKART to listOf(
                createTestProduct("Flipkart Milk", 120.0, Platforms.FLIPKART)
            ),
            Platforms.ZEPTO to listOf(
                createTestProduct("Zepto Milk", 80.0, Platforms.ZEPTO)
            ),
            Platforms.BLINKIT to listOf(
                createTestProduct("Blinkit Milk", 85.0, Platforms.BLINKIT)
            ),
            Platforms.BIGBASKET to listOf(
                createTestProduct("BigBasket Milk", 90.0, Platforms.BIGBASKET)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal).isNotNull()
        assertThat(bestDeal?.name).isEqualTo("Zepto Milk")
        assertThat(bestDeal?.price).isEqualTo(80.0)
        assertThat(bestDeal?.platform).isEqualTo(Platforms.ZEPTO)
    }
    
    @Test
    fun `findBestDeal ignores unavailable products`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Unavailable", 50.0, Platforms.AMAZON, available = false),
                createTestProduct("Available", 100.0, Platforms.AMAZON, available = true)
            ),
            Platforms.ZEPTO to listOf(
                createTestProduct("Zepto Unavailable", 30.0, Platforms.ZEPTO, available = false)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.name).isEqualTo("Available")
        assertThat(bestDeal?.price).isEqualTo(100.0)
    }
    
    @Test
    fun `findBestDeal ignores products with zero price`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Zero Price", 0.0, Platforms.AMAZON),
                createTestProduct("Valid", 100.0, Platforms.AMAZON)
            ),
            Platforms.FLIPKART to listOf(
                createTestProduct("Flipkart Zero", 0.0, Platforms.FLIPKART)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.name).isEqualTo("Valid")
    }
    
    @Test
    fun `findBestDeal returns null for empty results`() {
        // Given
        val results = emptyMap<String, List<Product>>()
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal).isNull()
    }
    
    @Test
    fun `findBestDeal returns null when all products unavailable`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Unavailable1", 50.0, Platforms.AMAZON, available = false),
                createTestProduct("Unavailable2", 100.0, Platforms.AMAZON, available = false)
            ),
            Platforms.ZEPTO to listOf(
                createTestProduct("Unavailable3", 30.0, Platforms.ZEPTO, available = false)
            ),
            Platforms.BLINKIT to listOf(
                createTestProduct("Unavailable4", 40.0, Platforms.BLINKIT, available = false)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal).isNull()
    }
    
    @Test
    fun `findBestDeal handles products with same price from different platforms`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Amazon Product", 50.0, Platforms.AMAZON)
            ),
            Platforms.ZEPTO to listOf(
                createTestProduct("Zepto Product", 50.0, Platforms.ZEPTO)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal).isNotNull()
        assertThat(bestDeal?.price).isEqualTo(50.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest milk across quick commerce`() {
        // Given
        val results = mapOf(
            Platforms.ZEPTO to listOf(createTestProduct("Zepto Milk", 29.0, Platforms.ZEPTO)),
            Platforms.BLINKIT to listOf(createTestProduct("Blinkit Milk", 32.0, Platforms.BLINKIT)),
            Platforms.INSTAMART to listOf(createTestProduct("Instamart Milk", 30.0, Platforms.INSTAMART)),
            Platforms.BIGBASKET to listOf(createTestProduct("BigBasket Milk", 28.0, Platforms.BIGBASKET))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.BIGBASKET)
        assertThat(bestDeal?.price).isEqualTo(28.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest bread across all platforms`() {
        // Given
        val results = mapOf(
            Platforms.ZEPTO to listOf(createTestProduct("Zepto Bread", 45.0, Platforms.ZEPTO)),
            Platforms.BLINKIT to listOf(createTestProduct("Blinkit Bread", 42.0, Platforms.BLINKIT)),
            Platforms.AMAZON to listOf(createTestProduct("Amazon Bread", 40.0, Platforms.AMAZON)),
            Platforms.FLIPKART to listOf(createTestProduct("Flipkart Bread", 38.0, Platforms.FLIPKART))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.FLIPKART)
        assertThat(bestDeal?.price).isEqualTo(38.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest eggs`() {
        // Given
        val results = mapOf(
            Platforms.ZEPTO to listOf(createTestProduct("Zepto Eggs 6pcs", 48.0, Platforms.ZEPTO)),
            Platforms.BLINKIT to listOf(createTestProduct("Blinkit Eggs 6pcs", 52.0, Platforms.BLINKIT)),
            Platforms.AMAZON_FRESH to listOf(createTestProduct("Fresh Eggs 6pcs", 45.0, Platforms.AMAZON_FRESH))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.AMAZON_FRESH)
        assertThat(bestDeal?.price).isEqualTo(45.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest rice from e-commerce`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(createTestProduct("Amazon Rice 5kg", 450.0, Platforms.AMAZON)),
            Platforms.FLIPKART to listOf(createTestProduct("Flipkart Rice 5kg", 420.0, Platforms.FLIPKART)),
            Platforms.JIOMART to listOf(createTestProduct("JioMart Rice 5kg", 400.0, Platforms.JIOMART))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.JIOMART)
        assertThat(bestDeal?.price).isEqualTo(400.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest atta with discount`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Aashirvaad Atta 10kg", 450.0, Platforms.AMAZON, originalPrice = 550.0)
            ),
            Platforms.FLIPKART to listOf(
                createTestProduct("Fortune Atta 10kg", 420.0, Platforms.FLIPKART, originalPrice = 500.0)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.FLIPKART)
        assertThat(bestDeal?.price).isEqualTo(420.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest oil`() {
        // Given
        val results = mapOf(
            Platforms.ZEPTO to listOf(createTestProduct("Zepto Oil 1L", 180.0, Platforms.ZEPTO)),
            Platforms.AMAZON to listOf(createTestProduct("Amazon Oil 1L", 170.0, Platforms.AMAZON)),
            Platforms.BIGBASKET to listOf(createTestProduct("BigBasket Oil 1L", 175.0, Platforms.BIGBASKET))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.AMAZON)
        assertThat(bestDeal?.price).isEqualTo(170.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest sugar`() {
        // Given
        val results = mapOf(
            Platforms.BIGBASKET to listOf(
                createTestProduct("Sugar 1kg", 48.0, Platforms.BIGBASKET),
                createTestProduct("Sugar 2kg", 92.0, Platforms.BIGBASKET),
                createTestProduct("Sugar 5kg", 220.0, Platforms.BIGBASKET)
            )
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.name).isEqualTo("Sugar 1kg")
        assertThat(bestDeal?.price).isEqualTo(48.0)
    }
    
    @Test
    fun `findBestDeal finds cheapest dal`() {
        // Given
        val results = mapOf(
            Platforms.AMAZON to listOf(createTestProduct("Toor Dal 1kg", 150.0, Platforms.AMAZON)),
            Platforms.FLIPKART to listOf(createTestProduct("Toor Dal 1kg", 145.0, Platforms.FLIPKART)),
            Platforms.JIOMART to listOf(createTestProduct("Toor Dal 1kg", 140.0, Platforms.JIOMART))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.JIOMART)
        assertThat(bestDeal?.price).isEqualTo(140.0)
    }
    
    // ==================== Platform Tests ====================
    
    @Test
    fun `getPlatforms returns all 10 platforms`() {
        // When
        val platforms = repository.getPlatforms()
        
        // Then
        assertThat(platforms).isEqualTo(Platforms.ALL)
        assertThat(platforms).hasSize(10)
    }
    
    @Test
    fun `getPlatforms includes all quick commerce platforms`() {
        // When
        val platforms = repository.getPlatforms()
        val platformNames = platforms.map { it.name }
        
        // Then
        assertThat(platformNames).contains(Platforms.ZEPTO)
        assertThat(platformNames).contains(Platforms.BLINKIT)
        assertThat(platformNames).contains(Platforms.INSTAMART)
        assertThat(platformNames).contains(Platforms.FLIPKART_MINUTES)
        assertThat(platformNames).contains(Platforms.JIOMART_QUICK)
        assertThat(platformNames).contains(Platforms.AMAZON_FRESH)
        assertThat(platformNames).contains(Platforms.BIGBASKET)
    }
    
    @Test
    fun `getPlatforms includes all e-commerce platforms`() {
        // When
        val platforms = repository.getPlatforms()
        val platformNames = platforms.map { it.name }
        
        // Then
        assertThat(platformNames).contains(Platforms.AMAZON)
        assertThat(platformNames).contains(Platforms.FLIPKART)
        assertThat(platformNames).contains(Platforms.JIOMART)
    }
    
    // ==================== Cache Tests ====================
    
    @Test
    fun `clearCache calls cacheManager clearAll`() = runTest {
        // Given
        coEvery { cacheManager.clearAll() } just Runs
        
        // When
        repository.clearCache()
        
        // Then
        coVerify { cacheManager.clearAll() }
    }
    
    @Test
    fun `getCacheStats returns stats from cacheManager`() = runTest {
        // Given
        val expectedStats = CacheStats(totalEntries = 15, hitsSinceStartup = 8)
        coEvery { cacheManager.getStats() } returns expectedStats
        
        // When
        val stats = repository.getCacheStats()
        
        // Then
        assertThat(stats).isEqualTo(expectedStats)
        assertThat(stats.totalEntries).isEqualTo(15)
        assertThat(stats.hitsSinceStartup).isEqualTo(8)
    }
    
    // ==================== Search (Non-streaming) Tests ====================
    
    @Test
    fun `search returns aggregated results from all scrapers`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        val amazonProducts = listOf(createTestProduct("Amazon Milk", 100.0, Platforms.AMAZON))
        coEvery { amazonScraper.search("milk", "560001") } returns amazonProducts
        
        // When
        val results = repository.search("milk", "560001")
        
        // Then
        assertThat(results).containsKey(Platforms.AMAZON)
        assertThat(results[Platforms.AMAZON]).hasSize(1)
        assertThat(results[Platforms.AMAZON]?.first()?.name).isEqualTo("Amazon Milk")
    }
    
    @Test
    fun `search returns results from multiple platforms`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        val amazonProducts = listOf(createTestProduct("Amazon Milk", 100.0, Platforms.AMAZON))
        val zeptoProducts = listOf(createTestProduct("Zepto Milk", 80.0, Platforms.ZEPTO))
        val blinkitProducts = listOf(createTestProduct("Blinkit Milk", 85.0, Platforms.BLINKIT))
        
        coEvery { amazonScraper.search("milk", "560001") } returns amazonProducts
        coEvery { zeptoScraper.search("milk", "560001") } returns zeptoProducts
        coEvery { blinkitScraper.search("milk", "560001") } returns blinkitProducts
        
        // When
        val results = repository.search("milk", "560001")
        
        // Then
        assertThat(results).containsKey(Platforms.AMAZON)
        assertThat(results).containsKey(Platforms.ZEPTO)
        assertThat(results).containsKey(Platforms.BLINKIT)
        assertThat(results[Platforms.AMAZON]?.first()?.price).isEqualTo(100.0)
        assertThat(results[Platforms.ZEPTO]?.first()?.price).isEqualTo(80.0)
        assertThat(results[Platforms.BLINKIT]?.first()?.price).isEqualTo(85.0)
    }
    
    @Test
    fun `search milk returns results from quick commerce platforms`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        val zeptoProducts = listOf(createTestProduct("Amul Toned Milk 500ml", 29.0, Platforms.ZEPTO))
        val blinkitProducts = listOf(createTestProduct("Mother Dairy Full Cream 1L", 68.0, Platforms.BLINKIT))
        val instamartProducts = listOf(createTestProduct("Nandini Milk 500ml", 25.0, Platforms.INSTAMART))
        val bigBasketProducts = listOf(createTestProduct("Amul Gold 1L", 72.0, Platforms.BIGBASKET))
        val amazonFreshProducts = listOf(createTestProduct("Nestle Milk 1L", 70.0, Platforms.AMAZON_FRESH))
        
        coEvery { zeptoScraper.search("milk", "560001") } returns zeptoProducts
        coEvery { blinkitScraper.search("milk", "560001") } returns blinkitProducts
        coEvery { instamartScraper.search("milk", "560001") } returns instamartProducts
        coEvery { bigBasketScraper.search("milk", "560001") } returns bigBasketProducts
        coEvery { amazonFreshScraper.search("milk", "560001") } returns amazonFreshProducts
        
        // When
        val results = repository.search("milk", "560001")
        
        // Then
        assertThat(results[Platforms.ZEPTO]).isNotEmpty()
        assertThat(results[Platforms.BLINKIT]).isNotEmpty()
        assertThat(results[Platforms.INSTAMART]).isNotEmpty()
        assertThat(results[Platforms.BIGBASKET]).isNotEmpty()
        assertThat(results[Platforms.AMAZON_FRESH]).isNotEmpty()
    }
    
    @Test
    fun `search rice returns results from e-commerce platforms`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        val amazonProducts = listOf(createTestProduct("India Gate Basmati 5kg", 450.0, Platforms.AMAZON))
        val flipkartProducts = listOf(createTestProduct("Fortune Basmati 5kg", 420.0, Platforms.FLIPKART))
        val jioMartProducts = listOf(createTestProduct("Daawat Basmati 5kg", 400.0, Platforms.JIOMART))
        
        coEvery { amazonScraper.search("rice", "560001") } returns amazonProducts
        coEvery { flipkartScraper.search("rice", "560001") } returns flipkartProducts
        coEvery { jioMartScraper.search("rice", "560001") } returns jioMartProducts
        
        // When
        val results = repository.search("rice", "560001")
        
        // Then
        assertThat(results[Platforms.AMAZON]).isNotEmpty()
        assertThat(results[Platforms.FLIPKART]).isNotEmpty()
        assertThat(results[Platforms.JIOMART]).isNotEmpty()
        
        // Verify best deal
        val bestDeal = repository.findBestDeal(results)
        assertThat(bestDeal?.platform).isEqualTo(Platforms.JIOMART)
        assertThat(bestDeal?.price).isEqualTo(400.0)
    }
    
    @Test
    fun `search bread returns results from all 10 platforms`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        // Setup all platforms to return bread
        coEvery { zeptoScraper.search("bread", "560001") } returns listOf(createTestProduct("Zepto Bread", 40.0, Platforms.ZEPTO))
        coEvery { blinkitScraper.search("bread", "560001") } returns listOf(createTestProduct("Blinkit Bread", 42.0, Platforms.BLINKIT))
        coEvery { flipkartMinutesScraper.search("bread", "560001") } returns listOf(createTestProduct("Flipkart Minutes Bread", 45.0, Platforms.FLIPKART_MINUTES))
        coEvery { jioMartQuickScraper.search("bread", "560001") } returns listOf(createTestProduct("JioMart Quick Bread", 38.0, Platforms.JIOMART_QUICK))
        coEvery { instamartScraper.search("bread", "560001") } returns listOf(createTestProduct("Instamart Bread", 41.0, Platforms.INSTAMART))
        coEvery { amazonFreshScraper.search("bread", "560001") } returns listOf(createTestProduct("Amazon Fresh Bread", 44.0, Platforms.AMAZON_FRESH))
        coEvery { bigBasketScraper.search("bread", "560001") } returns listOf(createTestProduct("BigBasket Bread", 43.0, Platforms.BIGBASKET))
        coEvery { amazonScraper.search("bread", "560001") } returns listOf(createTestProduct("Amazon Bread", 50.0, Platforms.AMAZON))
        coEvery { flipkartScraper.search("bread", "560001") } returns listOf(createTestProduct("Flipkart Bread", 48.0, Platforms.FLIPKART))
        coEvery { jioMartScraper.search("bread", "560001") } returns listOf(createTestProduct("JioMart Bread", 46.0, Platforms.JIOMART))
        
        // When
        val results = repository.search("bread", "560001")
        
        // Then - All 10 platforms should have results
        assertThat(results).hasSize(10)
        
        // Verify best deal is JioMart Quick (cheapest at ₹38)
        val bestDeal = repository.findBestDeal(results)
        assertThat(bestDeal?.platform).isEqualTo(Platforms.JIOMART_QUICK)
        assertThat(bestDeal?.price).isEqualTo(38.0)
    }
    
    // ==================== Product-Specific Tests ====================
    
    @Test
    fun `product with discount has correct discount percentage`() {
        // Given
        val product = createTestProduct(
            name = "Aashirvaad Atta 10kg",
            price = 450.0,
            platform = Platforms.AMAZON,
            originalPrice = 550.0,
            discount = "18% off"
        )
        
        // Then
        assertThat(product.discountPercentage).isEqualTo(18)
    }
    
    @Test
    fun `product with rating has valid rating`() {
        // Given
        val product = createTestProduct(
            name = "Toor Dal 1kg",
            price = 150.0,
            platform = Platforms.AMAZON,
            rating = 4.5
        )
        
        // Then
        assertThat(product.rating).isEqualTo(4.5)
    }
    
    @Test
    fun `quick commerce product has fast delivery time`() {
        // Given
        val product = createTestProduct(
            name = "Zepto Oil 1L",
            price = 180.0,
            platform = Platforms.ZEPTO,
            deliveryTime = "10-15 mins"
        )
        
        // Then
        assertThat(product.deliveryTime).isEqualTo("10-15 mins")
    }
    
    @Test
    fun `e-commerce product has slower delivery time`() {
        // Given
        val product = createTestProduct(
            name = "Amazon Oil 1L",
            price = 170.0,
            platform = Platforms.AMAZON,
            deliveryTime = "1-3 days"
        )
        
        // Then
        assertThat(product.deliveryTime).isEqualTo("1-3 days")
    }
    
    // ==================== Partial Results Tests (Some Platforms Return Empty) ====================
    
    @Test
    fun `search returns results only from Amazon, Amazon Fresh, Flipkart when other platforms fail`() = runTest {
        // Given - Only 3 platforms return results, rest return empty
        setupAllScrapersToReturnEmpty()
        
        val amazonProducts = listOf(createTestProduct("Amazon Grapes", 120.0, Platforms.AMAZON))
        val amazonFreshProducts = listOf(createTestProduct("Fresh Grapes 500g", 85.0, Platforms.AMAZON_FRESH))
        val flipkartProducts = listOf(createTestProduct("Flipkart Red Grapes", 95.0, Platforms.FLIPKART))
        
        coEvery { amazonScraper.search("grape", "560001") } returns amazonProducts
        coEvery { amazonFreshScraper.search("grape", "560001") } returns amazonFreshProducts
        coEvery { flipkartScraper.search("grape", "560001") } returns flipkartProducts
        
        // When
        val results = repository.search("grape", "560001")
        
        // Then - Only 3 platforms have results
        assertThat(results[Platforms.AMAZON]).isNotEmpty()
        assertThat(results[Platforms.AMAZON_FRESH]).isNotEmpty()
        assertThat(results[Platforms.FLIPKART]).isNotEmpty()
        
        // Other platforms should be empty
        assertThat(results[Platforms.ZEPTO]).isEmpty()
        assertThat(results[Platforms.BLINKIT]).isEmpty()
        assertThat(results[Platforms.INSTAMART]).isEmpty()
        assertThat(results[Platforms.BIGBASKET]).isEmpty()
        assertThat(results[Platforms.JIOMART]).isEmpty()
        assertThat(results[Platforms.JIOMART_QUICK]).isEmpty()
        assertThat(results[Platforms.FLIPKART_MINUTES]).isEmpty()
        
        // Best deal should still be found from available results
        val bestDeal = repository.findBestDeal(results)
        assertThat(bestDeal?.platform).isEqualTo(Platforms.AMAZON_FRESH)
        assertThat(bestDeal?.price).isEqualTo(85.0)
    }
    
    @Test
    fun `search returns empty results from all quick commerce platforms`() = runTest {
        // Given - All quick commerce platforms return empty, only e-commerce works
        setupAllScrapersToReturnEmpty()
        
        val amazonProducts = listOf(createTestProduct("Amazon Product", 100.0, Platforms.AMAZON))
        val flipkartProducts = listOf(createTestProduct("Flipkart Product", 95.0, Platforms.FLIPKART))
        
        coEvery { amazonScraper.search("rare item", "560001") } returns amazonProducts
        coEvery { flipkartScraper.search("rare item", "560001") } returns flipkartProducts
        
        // When
        val results = repository.search("rare item", "560001")
        
        // Then - Quick commerce platforms are empty
        assertThat(results[Platforms.ZEPTO]).isEmpty()
        assertThat(results[Platforms.BLINKIT]).isEmpty()
        assertThat(results[Platforms.INSTAMART]).isEmpty()
        assertThat(results[Platforms.FLIPKART_MINUTES]).isEmpty()
        assertThat(results[Platforms.JIOMART_QUICK]).isEmpty()
        assertThat(results[Platforms.AMAZON_FRESH]).isEmpty()
        assertThat(results[Platforms.BIGBASKET]).isEmpty()
        
        // E-commerce platforms have results
        assertThat(results[Platforms.AMAZON]).isNotEmpty()
        assertThat(results[Platforms.FLIPKART]).isNotEmpty()
    }
    
    @Test
    fun `search returns empty results from all e-commerce platforms`() = runTest {
        // Given - All e-commerce platforms return empty, only quick commerce works
        setupAllScrapersToReturnEmpty()
        
        val zeptoProducts = listOf(createTestProduct("Zepto Product", 50.0, Platforms.ZEPTO))
        val blinkitProducts = listOf(createTestProduct("Blinkit Product", 55.0, Platforms.BLINKIT))
        
        coEvery { zeptoScraper.search("quick item", "560001") } returns zeptoProducts
        coEvery { blinkitScraper.search("quick item", "560001") } returns blinkitProducts
        
        // When
        val results = repository.search("quick item", "560001")
        
        // Then - E-commerce platforms are empty
        assertThat(results[Platforms.AMAZON]).isEmpty()
        assertThat(results[Platforms.FLIPKART]).isEmpty()
        assertThat(results[Platforms.JIOMART]).isEmpty()
        
        // Quick commerce platforms have results
        assertThat(results[Platforms.ZEPTO]).isNotEmpty()
        assertThat(results[Platforms.BLINKIT]).isNotEmpty()
    }
    
    @Test
    fun `search returns empty results from all platforms`() = runTest {
        // Given - All platforms return empty (product not found anywhere)
        setupAllScrapersToReturnEmpty()
        
        // When
        val results = repository.search("nonexistent product xyz", "560001")
        
        // Then - All platforms should be empty
        assertThat(results[Platforms.AMAZON]).isEmpty()
        assertThat(results[Platforms.AMAZON_FRESH]).isEmpty()
        assertThat(results[Platforms.FLIPKART]).isEmpty()
        assertThat(results[Platforms.FLIPKART_MINUTES]).isEmpty()
        assertThat(results[Platforms.ZEPTO]).isEmpty()
        assertThat(results[Platforms.BLINKIT]).isEmpty()
        assertThat(results[Platforms.INSTAMART]).isEmpty()
        assertThat(results[Platforms.BIGBASKET]).isEmpty()
        assertThat(results[Platforms.JIOMART]).isEmpty()
        assertThat(results[Platforms.JIOMART_QUICK]).isEmpty()
        
        // Best deal should be null
        val bestDeal = repository.findBestDeal(results)
        assertThat(bestDeal).isNull()
    }
    
    @Test
    fun `search returns results from single platform only`() = runTest {
        // Given - Only Flipkart returns results
        setupAllScrapersToReturnEmpty()
        
        val flipkartProducts = listOf(
            createTestProduct("Flipkart Exclusive Product", 299.0, Platforms.FLIPKART),
            createTestProduct("Another Flipkart Product", 199.0, Platforms.FLIPKART)
        )
        coEvery { flipkartScraper.search("flipkart exclusive", "560001") } returns flipkartProducts
        
        // When
        val results = repository.search("flipkart exclusive", "560001")
        
        // Then
        assertThat(results[Platforms.FLIPKART]).hasSize(2)
        assertThat(results.filter { it.value.isNotEmpty() }).hasSize(1)
        
        val bestDeal = repository.findBestDeal(results)
        assertThat(bestDeal?.price).isEqualTo(199.0)
    }
    
    @Test
    fun `findBestDeal works with partial results from 3 platforms`() {
        // Given - Only Amazon, Amazon Fresh, and Flipkart have results
        val results = mapOf(
            Platforms.AMAZON to listOf(createTestProduct("Amazon Grape", 150.0, Platforms.AMAZON)),
            Platforms.AMAZON_FRESH to listOf(createTestProduct("Fresh Grape", 100.0, Platforms.AMAZON_FRESH)),
            Platforms.FLIPKART to listOf(createTestProduct("Flipkart Grape", 120.0, Platforms.FLIPKART)),
            Platforms.ZEPTO to emptyList(),
            Platforms.BLINKIT to emptyList(),
            Platforms.INSTAMART to emptyList(),
            Platforms.BIGBASKET to emptyList(),
            Platforms.JIOMART to emptyList(),
            Platforms.JIOMART_QUICK to emptyList(),
            Platforms.FLIPKART_MINUTES to emptyList()
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal).isNotNull()
        assertThat(bestDeal?.platform).isEqualTo(Platforms.AMAZON_FRESH)
        assertThat(bestDeal?.price).isEqualTo(100.0)
    }
    
    @Test
    fun `findBestDeal with mixed available and empty platforms`() {
        // Given - Some platforms have results, others empty
        val results = mapOf(
            Platforms.AMAZON to listOf(createTestProduct("Amazon Product", 200.0, Platforms.AMAZON)),
            Platforms.ZEPTO to emptyList(),
            Platforms.BLINKIT to listOf(createTestProduct("Blinkit Product", 180.0, Platforms.BLINKIT)),
            Platforms.INSTAMART to emptyList(),
            Platforms.FLIPKART to listOf(createTestProduct("Flipkart Product", 190.0, Platforms.FLIPKART))
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal?.platform).isEqualTo(Platforms.BLINKIT)
        assertThat(bestDeal?.price).isEqualTo(180.0)
    }
    
    @Test
    fun `search handles scraper exceptions gracefully`() = runTest {
        // Given - Some scrapers throw exceptions
        setupAllScrapersToReturnEmpty()
        
        coEvery { amazonScraper.search("error item", "560001") } throws RuntimeException("Network error")
        coEvery { zeptoScraper.search("error item", "560001") } throws RuntimeException("Timeout")
        coEvery { flipkartScraper.search("error item", "560001") } returns listOf(
            createTestProduct("Flipkart Product", 100.0, Platforms.FLIPKART)
        )
        
        // When
        val results = repository.search("error item", "560001")
        
        // Then - Failed scrapers return empty, working ones return results
        assertThat(results[Platforms.AMAZON]).isEmpty()
        assertThat(results[Platforms.ZEPTO]).isEmpty()
        assertThat(results[Platforms.FLIPKART]).isNotEmpty()
    }
    
    @Test
    fun `search grape returns partial results realistically`() = runTest {
        // Given - Simulating real scenario where only few platforms have grapes
        setupAllScrapersToReturnEmpty()
        
        // Only Amazon, Flipkart, and Amazon Fresh return results (like real scenario)
        coEvery { amazonScraper.search("grape", "560001") } returns listOf(
            createTestProduct("Green Grapes 500g", 89.0, Platforms.AMAZON),
            createTestProduct("Black Grapes 1kg", 180.0, Platforms.AMAZON)
        )
        coEvery { amazonFreshScraper.search("grape", "560001") } returns listOf(
            createTestProduct("Fresh Grapes", 75.0, Platforms.AMAZON_FRESH)
        )
        coEvery { flipkartScraper.search("grape", "560001") } returns listOf(
            createTestProduct("Red Globe Grapes", 95.0, Platforms.FLIPKART)
        )
        
        // When
        val results = repository.search("grape", "560001")
        
        // Then
        val platformsWithResults = results.filter { it.value.isNotEmpty() }
        assertThat(platformsWithResults).hasSize(3)
        
        // Verify the total products found
        val totalProducts = results.values.flatten().size
        assertThat(totalProducts).isEqualTo(4)
        
        // Verify best deal
        val bestDeal = repository.findBestDeal(results)
        assertThat(bestDeal?.name).isEqualTo("Fresh Grapes")
        assertThat(bestDeal?.price).isEqualTo(75.0)
    }
    
    @Test
    fun `count platforms with results`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        coEvery { amazonScraper.search("test", "560001") } returns listOf(
            createTestProduct("Amazon Product", 100.0, Platforms.AMAZON)
        )
        coEvery { flipkartScraper.search("test", "560001") } returns listOf(
            createTestProduct("Flipkart Product", 90.0, Platforms.FLIPKART)
        )
        coEvery { amazonFreshScraper.search("test", "560001") } returns listOf(
            createTestProduct("Fresh Product", 80.0, Platforms.AMAZON_FRESH)
        )
        
        // When
        val results = repository.search("test", "560001")
        
        // Then
        val platformsWithResults = results.count { it.value.isNotEmpty() }
        val platformsWithoutResults = results.count { it.value.isEmpty() }
        
        assertThat(platformsWithResults).isEqualTo(3)
        assertThat(platformsWithoutResults).isEqualTo(7) // 10 total - 3 with results
    }
    
    @Test
    fun `all quick commerce platforms return empty for non-grocery item`() = runTest {
        // Given - Electronics search (not available on quick commerce)
        setupAllScrapersToReturnEmpty()
        
        coEvery { amazonScraper.search("laptop", "560001") } returns listOf(
            createTestProduct("HP Laptop", 45000.0, Platforms.AMAZON)
        )
        coEvery { flipkartScraper.search("laptop", "560001") } returns listOf(
            createTestProduct("Dell Laptop", 42000.0, Platforms.FLIPKART)
        )
        
        // Quick commerce shouldn't have laptops
        // (already set to return empty in setupAllScrapersToReturnEmpty)
        
        // When
        val results = repository.search("laptop", "560001")
        
        // Then
        assertThat(results[Platforms.AMAZON]).isNotEmpty()
        assertThat(results[Platforms.FLIPKART]).isNotEmpty()
        
        // All quick commerce should be empty
        assertThat(results[Platforms.ZEPTO]).isEmpty()
        assertThat(results[Platforms.BLINKIT]).isEmpty()
        assertThat(results[Platforms.INSTAMART]).isEmpty()
        assertThat(results[Platforms.FLIPKART_MINUTES]).isEmpty()
        assertThat(results[Platforms.JIOMART_QUICK]).isEmpty()
        assertThat(results[Platforms.AMAZON_FRESH]).isEmpty()
        assertThat(results[Platforms.BIGBASKET]).isEmpty()
    }
    
    @Test
    fun `bestDeal is null when only platforms have unavailable products`() {
        // Given - All products are unavailable
        val results = mapOf(
            Platforms.AMAZON to listOf(
                createTestProduct("Product 1", 100.0, Platforms.AMAZON, available = false)
            ),
            Platforms.FLIPKART to listOf(
                createTestProduct("Product 2", 90.0, Platforms.FLIPKART, available = false)
            ),
            Platforms.ZEPTO to emptyList(),
            Platforms.BLINKIT to emptyList()
        )
        
        // When
        val bestDeal = repository.findBestDeal(results)
        
        // Then
        assertThat(bestDeal).isNull()
    }
    
    @Test
    fun `search returns correct platform count summary`() = runTest {
        // Given
        setupAllScrapersToReturnEmpty()
        
        // 4 platforms return results
        coEvery { amazonScraper.search("item", "560001") } returns listOf(
            createTestProduct("Amazon Item", 100.0, Platforms.AMAZON)
        )
        coEvery { flipkartScraper.search("item", "560001") } returns listOf(
            createTestProduct("Flipkart Item", 95.0, Platforms.FLIPKART)
        )
        coEvery { zeptoScraper.search("item", "560001") } returns listOf(
            createTestProduct("Zepto Item", 85.0, Platforms.ZEPTO)
        )
        coEvery { blinkitScraper.search("item", "560001") } returns listOf(
            createTestProduct("Blinkit Item", 90.0, Platforms.BLINKIT)
        )
        
        // When
        val results = repository.search("item", "560001")
        
        // Then
        val summary = mapOf(
            "totalPlatforms" to 10,
            "platformsWithResults" to results.count { it.value.isNotEmpty() },
            "platformsWithoutResults" to results.count { it.value.isEmpty() },
            "totalProducts" to results.values.flatten().size
        )
        
        assertThat(summary["platformsWithResults"]).isEqualTo(4)
        assertThat(summary["platformsWithoutResults"]).isEqualTo(6)
        assertThat(summary["totalProducts"]).isEqualTo(4)
    }
    
    // ==================== Helper Methods ====================
    
    private fun setupAllScrapersToReturnEmpty() {
        coEvery { cacheManager.get(any(), any(), any()) } returns (null to false)
        coEvery { cacheManager.set(any(), any(), any(), any()) } just Runs
        
        coEvery { amazonScraper.search(any(), any()) } returns emptyList()
        coEvery { amazonFreshScraper.search(any(), any()) } returns emptyList()
        coEvery { flipkartScraper.search(any(), any()) } returns emptyList()
        coEvery { flipkartMinutesScraper.search(any(), any()) } returns emptyList()
        coEvery { jioMartScraper.search(any(), any()) } returns emptyList()
        coEvery { jioMartQuickScraper.search(any(), any()) } returns emptyList()
        coEvery { bigBasketScraper.search(any(), any()) } returns emptyList()
        coEvery { zeptoScraper.search(any(), any()) } returns emptyList()
        coEvery { blinkitScraper.search(any(), any()) } returns emptyList()
        coEvery { instamartScraper.search(any(), any()) } returns emptyList()
    }
    
    private fun createTestProduct(
        name: String,
        price: Double,
        platform: String,
        available: Boolean = true,
        originalPrice: Double? = null,
        discount: String? = null,
        rating: Double? = null,
        deliveryTime: String = "2-3 days"
    ) = Product(
        name = name,
        price = price,
        platform = platform,
        platformColor = Platforms.getColor(platform),
        url = "https://test.com/product",
        deliveryTime = deliveryTime,
        available = available,
        originalPrice = originalPrice,
        discount = discount,
        rating = rating
    )
}
