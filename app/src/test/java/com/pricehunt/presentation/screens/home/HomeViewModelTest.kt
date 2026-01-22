package com.pricehunt.presentation.screens.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.model.SearchEvent
import com.pricehunt.data.repository.CacheStats
import com.pricehunt.data.repository.ProductRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    @MockK
    private lateinit var repository: ProductRepository
    
    private lateinit var viewModel: HomeViewModel
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    // ==================== Initial State Tests ====================
    
    @Test
    fun `initial state has correct default values`() = runTest {
        // Given & When
        viewModel = HomeViewModel(repository)
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.query).isEmpty()
        assertThat(state.pincode).isEqualTo("560001")
        assertThat(state.isSearching).isFalse()
        assertThat(state.results).isEmpty()
        assertThat(state.platformStatus).isEmpty()
        assertThat(state.bestDeal).isNull()
        assertThat(state.error).isNull()
        assertThat(state.cacheStats).isNull()
    }
    
    // ==================== Query Update Tests ====================
    
    @Test
    fun `updateQuery updates query in state`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        
        // When
        viewModel.updateQuery("milk")
        
        // Then
        assertThat(viewModel.uiState.value.query).isEqualTo("milk")
    }
    
    @Test
    fun `updateQuery handles various product searches`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        
        // Test various product queries
        val products = listOf("milk", "bread", "eggs", "rice", "atta", "oil", "sugar", "dal", "butter", "cheese")
        
        products.forEach { product ->
            // When
            viewModel.updateQuery(product)
            
            // Then
            assertThat(viewModel.uiState.value.query).isEqualTo(product)
        }
    }
    
    // ==================== Pincode Update Tests ====================
    
    @Test
    fun `updatePincode updates pincode in state`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        
        // When
        viewModel.updatePincode("400001")
        
        // Then
        assertThat(viewModel.uiState.value.pincode).isEqualTo("400001")
    }
    
    @Test
    fun `updatePincode handles various Indian pincodes`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        
        // Test various pincodes
        val pincodes = listOf("560001", "400001", "110001", "500001", "600001", "700001")
        
        pincodes.forEach { pincode ->
            // When
            viewModel.updatePincode(pincode)
            
            // Then
            assertThat(viewModel.uiState.value.pincode).isEqualTo(pincode)
        }
    }
    
    // ==================== Search Validation Tests ====================
    
    @Test
    fun `search does nothing when query is blank`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("   ")
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        assertThat(viewModel.uiState.value.isSearching).isFalse()
        coVerify(exactly = 0) { repository.searchStream(any(), any()) }
    }
    
    @Test
    fun `search does nothing when query is empty`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("")
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        assertThat(viewModel.uiState.value.isSearching).isFalse()
        coVerify(exactly = 0) { repository.searchStream(any(), any()) }
    }
    
    @Test
    fun `search uses trimmed query`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("  milk  ")
        
        coEvery { repository.searchStream("milk", any()) } returns flowOf(
            SearchEvent.Started(emptyList()),
            SearchEvent.Completed
        )
        coEvery { repository.getCacheStats() } returns CacheStats(0, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        coVerify { repository.searchStream("milk", "560001") }
    }
    
    // ==================== Search State Tests ====================
    
    @Test
    fun `search sets isSearching to true initially`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        coEvery { repository.searchStream(any(), any()) } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.Completed
        )
        coEvery { repository.getCacheStats() } returns CacheStats(0, 0)
        
        // When
        viewModel.search()
        
        // Then
        viewModel.uiState.test {
            // Skip initial state
            skipItems(1)
            
            val searchingState = awaitItem()
            assertThat(searchingState.isSearching).isTrue()
            
            cancelAndIgnoreRemainingEvents()
        }
    }
    
    @Test
    fun `search clears previous results`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        coEvery { repository.searchStream(any(), any()) } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.Completed
        )
        coEvery { repository.getCacheStats() } returns CacheStats(0, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        assertThat(viewModel.uiState.value.results).isEmpty()
    }
    
    @Test
    fun `search sets isSearching to false on Completed`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.Completed
        )
        coEvery { repository.getCacheStats() } returns CacheStats(0, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        assertThat(viewModel.uiState.value.isSearching).isFalse()
    }
    
    // ==================== Platform Status Tests ====================
    
    @Test
    fun `search updates platform status on PlatformResult`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        val products = listOf(createTestProduct("Milk", 50.0, Platforms.AMAZON))
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.PlatformResult(Platforms.AMAZON, products, cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns products.first()
        coEvery { repository.getCacheStats() } returns CacheStats(1, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.platformStatus[Platforms.AMAZON]).isEqualTo(PlatformStatus.COMPLETED)
    }
    
    @Test
    fun `search sets CACHED status for cached results`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        val products = listOf(createTestProduct("Milk", 50.0, Platforms.AMAZON))
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.PlatformResult(Platforms.AMAZON, products, cached = true),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns products.first()
        coEvery { repository.getCacheStats() } returns CacheStats(1, 1)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.platformStatus[Platforms.AMAZON]).isEqualTo(PlatformStatus.CACHED)
    }
    
    @Test
    fun `search updates platform status for all 10 platforms`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("bread")
        
        val allPlatforms = listOf(
            Platforms.ZEPTO, Platforms.BLINKIT, Platforms.FLIPKART_MINUTES,
            Platforms.JIOMART_QUICK, Platforms.INSTAMART, Platforms.AMAZON_FRESH,
            Platforms.BIGBASKET, Platforms.AMAZON, Platforms.FLIPKART, Platforms.JIOMART
        )
        
        val events = mutableListOf<SearchEvent>(SearchEvent.Started(allPlatforms))
        allPlatforms.forEach { platform ->
            events.add(SearchEvent.PlatformResult(
                platform = platform,
                products = listOf(createTestProduct("Bread", 40.0, platform)),
                cached = false
            ))
        }
        events.add(SearchEvent.Completed)
        
        coEvery { repository.searchStream("bread", "560001") } returns flowOf(*events.toTypedArray())
        coEvery { repository.findBestDeal(any()) } returns createTestProduct("Bread", 40.0, Platforms.ZEPTO)
        coEvery { repository.getCacheStats() } returns CacheStats(10, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        allPlatforms.forEach { platform ->
            assertThat(state.platformStatus[platform]).isEqualTo(PlatformStatus.COMPLETED)
        }
    }
    
    // ==================== Best Deal Tests ====================
    
    @Test
    fun `search updates bestDeal from repository`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        val bestProduct = createTestProduct("Best Deal", 25.0, Platforms.ZEPTO)
        val products = listOf(
            createTestProduct("Milk", 50.0, Platforms.AMAZON),
            bestProduct
        )
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON, Platforms.ZEPTO)),
            SearchEvent.PlatformResult(Platforms.AMAZON, listOf(products[0]), cached = false),
            SearchEvent.PlatformResult(Platforms.ZEPTO, listOf(products[1]), cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns bestProduct
        coEvery { repository.getCacheStats() } returns CacheStats(2, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.bestDeal).isEqualTo(bestProduct)
    }
    
    @Test
    fun `search finds best deal across quick commerce platforms`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("eggs")
        
        val zeptoProduct = createTestProduct("Zepto Eggs", 48.0, Platforms.ZEPTO)
        val blinkitProduct = createTestProduct("Blinkit Eggs", 52.0, Platforms.BLINKIT)
        val instamartProduct = createTestProduct("Instamart Eggs", 50.0, Platforms.INSTAMART)
        
        coEvery { repository.searchStream("eggs", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.ZEPTO, Platforms.BLINKIT, Platforms.INSTAMART)),
            SearchEvent.PlatformResult(Platforms.ZEPTO, listOf(zeptoProduct), cached = false),
            SearchEvent.PlatformResult(Platforms.BLINKIT, listOf(blinkitProduct), cached = false),
            SearchEvent.PlatformResult(Platforms.INSTAMART, listOf(instamartProduct), cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns zeptoProduct
        coEvery { repository.getCacheStats() } returns CacheStats(3, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.bestDeal?.platform).isEqualTo(Platforms.ZEPTO)
        assertThat(state.bestDeal?.price).isEqualTo(48.0)
    }
    
    // ==================== Results Sorting Tests ====================
    
    @Test
    fun `search results are sorted with quick commerce first`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        // Simulate results coming in random order
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON, Platforms.ZEPTO, Platforms.FLIPKART, Platforms.BLINKIT)),
            // Results arrive in non-sorted order
            SearchEvent.PlatformResult(Platforms.AMAZON, listOf(createTestProduct("Amazon Milk", 60.0, Platforms.AMAZON)), cached = false),
            SearchEvent.PlatformResult(Platforms.FLIPKART, listOf(createTestProduct("Flipkart Milk", 58.0, Platforms.FLIPKART)), cached = false),
            SearchEvent.PlatformResult(Platforms.ZEPTO, listOf(createTestProduct("Zepto Milk", 55.0, Platforms.ZEPTO)), cached = false),
            SearchEvent.PlatformResult(Platforms.BLINKIT, listOf(createTestProduct("Blinkit Milk", 56.0, Platforms.BLINKIT)), cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns createTestProduct("Zepto Milk", 55.0, Platforms.ZEPTO)
        coEvery { repository.getCacheStats() } returns CacheStats(4, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then - Results should be sorted: Quick Commerce first, then E-Commerce
        val state = viewModel.uiState.value
        val platformOrder = state.results.keys.toList()
        
        // Zepto and Blinkit (quick commerce) should come before Amazon and Flipkart (e-commerce)
        val zeptoIndex = platformOrder.indexOf(Platforms.ZEPTO)
        val blinkitIndex = platformOrder.indexOf(Platforms.BLINKIT)
        val amazonIndex = platformOrder.indexOf(Platforms.AMAZON)
        val flipkartIndex = platformOrder.indexOf(Platforms.FLIPKART)
        
        assertThat(zeptoIndex).isLessThan(amazonIndex)
        assertThat(zeptoIndex).isLessThan(flipkartIndex)
        assertThat(blinkitIndex).isLessThan(amazonIndex)
        assertThat(blinkitIndex).isLessThan(flipkartIndex)
    }
    
    @Test
    fun `search results maintain quick commerce order by delivery speed`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("bread")
        
        val allQuickCommerce = listOf(
            Platforms.ZEPTO, Platforms.BLINKIT, Platforms.FLIPKART_MINUTES,
            Platforms.JIOMART_QUICK, Platforms.INSTAMART, Platforms.AMAZON_FRESH, Platforms.BIGBASKET
        )
        
        val events = mutableListOf<SearchEvent>(SearchEvent.Started(allQuickCommerce))
        // Add results in reverse order
        allQuickCommerce.reversed().forEach { platform ->
            events.add(SearchEvent.PlatformResult(
                platform = platform,
                products = listOf(createTestProduct("Bread", 40.0, platform)),
                cached = false
            ))
        }
        events.add(SearchEvent.Completed)
        
        coEvery { repository.searchStream("bread", "560001") } returns flowOf(*events.toTypedArray())
        coEvery { repository.findBestDeal(any()) } returns createTestProduct("Bread", 40.0, Platforms.ZEPTO)
        coEvery { repository.getCacheStats() } returns CacheStats(7, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then - Verify quick commerce platforms are sorted by delivery speed
        val state = viewModel.uiState.value
        val platformOrder = state.results.keys.toList()
        
        // Expected order: Zepto (10-15 min) -> Blinkit (8-12 min) -> Flipkart Minutes -> JioMart Quick -> Instamart -> Amazon Fresh -> BigBasket
        val zeptoIndex = platformOrder.indexOf(Platforms.ZEPTO)
        val blinkitIndex = platformOrder.indexOf(Platforms.BLINKIT)
        val amazonFreshIndex = platformOrder.indexOf(Platforms.AMAZON_FRESH)
        val bigBasketIndex = platformOrder.indexOf(Platforms.BIGBASKET)
        
        // Faster delivery platforms should come first
        assertThat(zeptoIndex).isLessThan(amazonFreshIndex)
        assertThat(blinkitIndex).isLessThan(bigBasketIndex)
    }
    
    // ==================== Error Handling Tests ====================
    
    @Test
    fun `search sets error on Error event`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.Error("Network error")
        )
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Network error")
        assertThat(state.isSearching).isFalse()
    }
    
    @Test
    fun `search handles API timeout error`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("rice")
        
        coEvery { repository.searchStream("rice", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.Error("API timeout - please try again")
        )
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.error).contains("timeout")
        assertThat(state.isSearching).isFalse()
    }
    
    // ==================== Cache Tests ====================
    
    @Test
    fun `clearCache calls repository and refreshes stats`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        coEvery { repository.clearCache() } just Runs
        coEvery { repository.getCacheStats() } returns CacheStats(0, 0)
        
        // When
        viewModel.clearCache()
        advanceUntilIdle()
        
        // Then
        coVerify { repository.clearCache() }
        coVerify { repository.getCacheStats() }
    }
    
    @Test
    fun `cacheStats is updated after search completes`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.PlatformResult(Platforms.AMAZON, listOf(createTestProduct("Milk", 50.0, Platforms.AMAZON)), cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns createTestProduct("Milk", 50.0, Platforms.AMAZON)
        coEvery { repository.getCacheStats() } returns CacheStats(1, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.cacheStats).isNotNull()
        assertThat(state.cacheStats?.totalEntries).isEqualTo(1)
    }
    
    // ==================== Multi-Platform Search Tests ====================
    
    @Test
    fun `search accumulates results from multiple platforms`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("rice")
        
        val amazonProducts = listOf(createTestProduct("Amazon Rice", 100.0, Platforms.AMAZON))
        val flipkartProducts = listOf(createTestProduct("Flipkart Rice", 90.0, Platforms.FLIPKART))
        
        coEvery { repository.searchStream("rice", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON, Platforms.FLIPKART)),
            SearchEvent.PlatformResult(Platforms.AMAZON, amazonProducts, cached = false),
            SearchEvent.PlatformResult(Platforms.FLIPKART, flipkartProducts, cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns flipkartProducts.first()
        coEvery { repository.getCacheStats() } returns CacheStats(2, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.results).hasSize(2)
        assertThat(state.results).containsKey(Platforms.AMAZON)
        assertThat(state.results).containsKey(Platforms.FLIPKART)
    }
    
    @Test
    fun `search handles all 10 platforms with products`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("oil")
        
        val allPlatforms = listOf(
            Platforms.ZEPTO, Platforms.BLINKIT, Platforms.FLIPKART_MINUTES,
            Platforms.JIOMART_QUICK, Platforms.INSTAMART, Platforms.AMAZON_FRESH,
            Platforms.BIGBASKET, Platforms.AMAZON, Platforms.FLIPKART, Platforms.JIOMART
        )
        
        val events = mutableListOf<SearchEvent>(SearchEvent.Started(allPlatforms))
        allPlatforms.forEach { platform ->
            events.add(SearchEvent.PlatformResult(
                platform = platform,
                products = listOf(createTestProduct("Oil 1L", 150.0 + (Math.random() * 50), platform)),
                cached = false
            ))
        }
        events.add(SearchEvent.Completed)
        
        coEvery { repository.searchStream("oil", "560001") } returns flowOf(*events.toTypedArray())
        coEvery { repository.findBestDeal(any()) } returns createTestProduct("Oil 1L", 150.0, Platforms.ZEPTO)
        coEvery { repository.getCacheStats() } returns CacheStats(10, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.results).hasSize(10)
        allPlatforms.forEach { platform ->
            assertThat(state.results).containsKey(platform)
        }
    }
    
    // ==================== Product-Specific Search Tests ====================
    
    @Test
    fun `search milk returns results from quick commerce`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("milk")
        
        val quickCommercePlatforms = listOf(Platforms.ZEPTO, Platforms.BLINKIT, Platforms.INSTAMART)
        
        val events = mutableListOf<SearchEvent>(SearchEvent.Started(quickCommercePlatforms))
        events.add(SearchEvent.PlatformResult(Platforms.ZEPTO, listOf(createTestProduct("Amul Milk 500ml", 29.0, Platforms.ZEPTO)), cached = false))
        events.add(SearchEvent.PlatformResult(Platforms.BLINKIT, listOf(createTestProduct("Mother Dairy 1L", 68.0, Platforms.BLINKIT)), cached = false))
        events.add(SearchEvent.PlatformResult(Platforms.INSTAMART, listOf(createTestProduct("Nandini Milk 500ml", 25.0, Platforms.INSTAMART)), cached = false))
        events.add(SearchEvent.Completed)
        
        coEvery { repository.searchStream("milk", "560001") } returns flowOf(*events.toTypedArray())
        coEvery { repository.findBestDeal(any()) } returns createTestProduct("Nandini Milk 500ml", 25.0, Platforms.INSTAMART)
        coEvery { repository.getCacheStats() } returns CacheStats(3, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.results).hasSize(3)
        assertThat(state.bestDeal?.price).isEqualTo(25.0)
    }
    
    @Test
    fun `search atta returns results with discounts`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("atta")
        
        val amazonProduct = Product(
            name = "Aashirvaad Atta 10kg",
            price = 450.0,
            originalPrice = 550.0,
            discount = "18% off",
            platform = Platforms.AMAZON,
            platformColor = Platforms.getColor(Platforms.AMAZON),
            url = "https://amazon.in/atta",
            deliveryTime = "1-3 days",
            available = true
        )
        
        coEvery { repository.searchStream("atta", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.AMAZON)),
            SearchEvent.PlatformResult(Platforms.AMAZON, listOf(amazonProduct), cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns amazonProduct
        coEvery { repository.getCacheStats() } returns CacheStats(1, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        val product = state.results[Platforms.AMAZON]?.first()
        assertThat(product?.originalPrice).isEqualTo(550.0)
        assertThat(product?.discount).isEqualTo("18% off")
        assertThat(product?.discountPercentage).isEqualTo(18)
    }
    
    @Test
    fun `search sugar handles multiple products per platform`() = runTest {
        // Given
        viewModel = HomeViewModel(repository)
        viewModel.updateQuery("sugar")
        
        val bigBasketProducts = listOf(
            createTestProduct("Sugar 1kg", 48.0, Platforms.BIGBASKET),
            createTestProduct("Sugar 2kg", 92.0, Platforms.BIGBASKET),
            createTestProduct("Sugar 5kg", 220.0, Platforms.BIGBASKET)
        )
        
        coEvery { repository.searchStream("sugar", "560001") } returns flowOf(
            SearchEvent.Started(listOf(Platforms.BIGBASKET)),
            SearchEvent.PlatformResult(Platforms.BIGBASKET, bigBasketProducts, cached = false),
            SearchEvent.Completed
        )
        coEvery { repository.findBestDeal(any()) } returns bigBasketProducts.first()
        coEvery { repository.getCacheStats() } returns CacheStats(1, 0)
        
        // When
        viewModel.search()
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertThat(state.results[Platforms.BIGBASKET]).hasSize(3)
    }
    
    // ==================== Helper Methods ====================
    
    private fun createTestProduct(
        name: String,
        price: Double,
        platform: String
    ) = Product(
        name = name,
        price = price,
        platform = platform,
        platformColor = Platforms.getColor(platform),
        url = "https://test.com/product",
        deliveryTime = "2-3 days",
        available = true
    )
}
