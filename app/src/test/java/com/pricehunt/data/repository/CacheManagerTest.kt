package com.pricehunt.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.pricehunt.data.local.dao.CacheDao
import com.pricehunt.data.local.entity.CacheEntity
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class CacheManagerTest {
    
    @MockK
    private lateinit var cacheDao: CacheDao
    
    private lateinit var gson: Gson
    private lateinit var cacheManager: CacheManager
    
    @Before
    fun setup() {
        MockKAnnotations.init(this)
        gson = Gson()
        cacheManager = CacheManager(cacheDao, gson)
    }
    
    @Test
    fun `get returns null for non-cached query`() = runTest {
        // Given
        coEvery { cacheDao.get(any()) } returns null
        
        // When
        val (products, isStale) = cacheManager.get("test", "Amazon", "560001")
        
        // Then
        assertThat(products).isNull()
        assertThat(isStale).isFalse()
    }
    
    @Test
    fun `get returns fresh cache for e-commerce within TTL`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct())
        val entity = createCacheEntity(
            products = testProducts,
            timestamp = System.currentTimeMillis() - 5 * 60 * 1000 // 5 minutes ago
        )
        coEvery { cacheDao.get(any()) } returns entity
        
        // When
        val (products, isStale) = cacheManager.get("test", Platforms.AMAZON, "560001")
        
        // Then
        assertThat(products).isNotNull()
        assertThat(products).hasSize(1)
        assertThat(isStale).isFalse()
    }
    
    @Test
    fun `get returns stale cache for e-commerce within grace period`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct())
        val entity = createCacheEntity(
            products = testProducts,
            timestamp = System.currentTimeMillis() - 16 * 60 * 1000 // 16 minutes ago (past 15 min TTL)
        )
        coEvery { cacheDao.get(any()) } returns entity
        
        // When
        val (products, isStale) = cacheManager.get("test", Platforms.AMAZON, "560001")
        
        // Then
        assertThat(products).isNotNull()
        assertThat(isStale).isTrue()
    }
    
    @Test
    fun `get deletes and returns null for expired cache`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct())
        val entity = createCacheEntity(
            products = testProducts,
            timestamp = System.currentTimeMillis() - 20 * 60 * 1000 // 20 minutes ago (past TTL + grace)
        )
        coEvery { cacheDao.get(any()) } returns entity
        coEvery { cacheDao.delete(any()) } just Runs
        
        // When
        val (products, isStale) = cacheManager.get("test", Platforms.AMAZON, "560001")
        
        // Then
        assertThat(products).isNull()
        assertThat(isStale).isFalse()
        coVerify { cacheDao.delete(any()) }
    }
    
    @Test
    fun `get returns fresh cache for quick commerce within TTL`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct(platform = Platforms.ZEPTO))
        val entity = createCacheEntity(
            products = testProducts,
            platform = Platforms.ZEPTO,
            timestamp = System.currentTimeMillis() - 3 * 60 * 1000 // 3 minutes ago
        )
        coEvery { cacheDao.get(any()) } returns entity
        
        // When
        val (products, isStale) = cacheManager.get("test", Platforms.ZEPTO, "560001")
        
        // Then
        assertThat(products).isNotNull()
        assertThat(isStale).isFalse()
    }
    
    @Test
    fun `get returns stale cache for quick commerce past TTL`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct(platform = Platforms.ZEPTO))
        val entity = createCacheEntity(
            products = testProducts,
            platform = Platforms.ZEPTO,
            timestamp = System.currentTimeMillis() - 6 * 60 * 1000 // 6 minutes ago (past 5 min TTL)
        )
        coEvery { cacheDao.get(any()) } returns entity
        
        // When
        val (products, isStale) = cacheManager.get("test", Platforms.ZEPTO, "560001")
        
        // Then
        assertThat(products).isNotNull()
        assertThat(isStale).isTrue()
    }
    
    @Test
    fun `set stores products in cache`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct())
        coEvery { cacheDao.insert(any()) } just Runs
        
        // When
        cacheManager.set("test", Platforms.AMAZON, "560001", testProducts)
        
        // Then
        coVerify { cacheDao.insert(match { 
            it.query == "test" && 
            it.platform == Platforms.AMAZON &&
            it.pincode == "560001"
        }) }
    }
    
    @Test
    fun `set generates correct cache key`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct())
        val capturedEntity = slot<CacheEntity>()
        coEvery { cacheDao.insert(capture(capturedEntity)) } just Runs
        
        // When
        cacheManager.set("Test Query", Platforms.AMAZON, "560001", testProducts)
        
        // Then
        assertThat(capturedEntity.captured.cacheKey).isEqualTo("test query_Amazon_560001")
    }
    
    @Test
    fun `set normalizes query to lowercase`() = runTest {
        // Given
        val testProducts = listOf(createTestProduct())
        val capturedEntity = slot<CacheEntity>()
        coEvery { cacheDao.insert(capture(capturedEntity)) } just Runs
        
        // When
        cacheManager.set("UPPERCASE QUERY", Platforms.AMAZON, "560001", testProducts)
        
        // Then
        assertThat(capturedEntity.captured.query).isEqualTo("uppercase query")
    }
    
    @Test
    fun `clearAll calls dao clearAll`() = runTest {
        // Given
        coEvery { cacheDao.clearAll() } just Runs
        
        // When
        cacheManager.clearAll()
        
        // Then
        coVerify { cacheDao.clearAll() }
    }
    
    @Test
    fun `cleanup deletes expired entries`() = runTest {
        // Given
        coEvery { cacheDao.deleteExpired(any()) } just Runs
        
        // When
        cacheManager.cleanup()
        
        // Then
        coVerify { cacheDao.deleteExpired(any()) }
    }
    
    @Test
    fun `getStats returns cache statistics`() = runTest {
        // Given
        coEvery { cacheDao.getCount() } returns 10
        coEvery { cacheDao.getHitsSince(any()) } returns 5
        
        // When
        val stats = cacheManager.getStats()
        
        // Then
        assertThat(stats.totalEntries).isEqualTo(10)
        assertThat(stats.hitsSinceStartup).isEqualTo(5)
    }
    
    private fun createTestProduct(
        name: String = "Test Product",
        price: Double = 100.0,
        platform: String = Platforms.AMAZON
    ) = Product(
        name = name,
        price = price,
        platform = platform,
        platformColor = Platforms.getColor(platform),
        url = "https://test.com/product",
        deliveryTime = "2-3 days"
    )
    
    private fun createCacheEntity(
        products: List<Product>,
        query: String = "test",
        platform: String = Platforms.AMAZON,
        pincode: String = "560001",
        timestamp: Long = System.currentTimeMillis()
    ) = CacheEntity(
        cacheKey = "${query.lowercase().trim()}_${platform}_$pincode",
        query = query.lowercase().trim(),
        platform = platform,
        pincode = pincode,
        resultsJson = gson.toJson(products),
        timestamp = timestamp
    )
}

