package com.pricehunt.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pricehunt.data.local.dao.CacheDao
import com.pricehunt.data.local.entity.CacheEntity
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages caching of search results with TTL.
 * Quick commerce platforms: 5 min TTL
 * E-commerce platforms: 15 min TTL
 */
@Singleton
class CacheManager @Inject constructor(
    private val cacheDao: CacheDao,
    private val gson: Gson
) {
    companion object {
        private const val QUICK_COMMERCE_TTL = 5 * 60 * 1000L // 5 minutes
        private const val ECOMMERCE_TTL = 15 * 60 * 1000L // 15 minutes
        private const val STALE_WHILE_REVALIDATE = 2 * 60 * 1000L // 2 minutes grace period
    }
    
    private val productListType = object : TypeToken<List<Product>>() {}.type
    
    /**
     * Get cached results for a platform.
     * Returns Pair<List<Product>?, Boolean> where Boolean indicates if cache is stale.
     */
    suspend fun get(query: String, platform: String, pincode: String): Pair<List<Product>?, Boolean> {
        val key = generateKey(query, platform, pincode)
        val cached = cacheDao.get(key) ?: return null to false
        
        val ttl = getTtl(platform)
        val age = System.currentTimeMillis() - cached.timestamp
        
        return when {
            age <= ttl -> {
                // Fresh cache
                parseProducts(cached.resultsJson) to false
            }
            age <= ttl + STALE_WHILE_REVALIDATE -> {
                // Stale but usable
                parseProducts(cached.resultsJson) to true
            }
            else -> {
                // Expired
                cacheDao.delete(key)
                null to false
            }
        }
    }
    
    /**
     * Store results in cache.
     */
    suspend fun set(query: String, platform: String, pincode: String, products: List<Product>) {
        val key = generateKey(query, platform, pincode)
        val entity = CacheEntity(
            cacheKey = key,
            query = query.lowercase().trim(),
            platform = platform,
            pincode = pincode,
            resultsJson = gson.toJson(products),
            timestamp = System.currentTimeMillis()
        )
        cacheDao.insert(entity)
    }
    
    /**
     * Clear all cache.
     */
    suspend fun clearAll() {
        cacheDao.clearAll()
    }
    
    /**
     * Clean up expired entries.
     */
    suspend fun cleanup() {
        // Delete entries older than max TTL + grace period
        val maxTtl = ECOMMERCE_TTL + STALE_WHILE_REVALIDATE
        val expiryTime = System.currentTimeMillis() - maxTtl
        cacheDao.deleteExpired(expiryTime)
    }
    
    /**
     * Get cache statistics.
     */
    suspend fun getStats(): CacheStats {
        return CacheStats(
            totalEntries = cacheDao.getCount(),
            hitsSinceStartup = cacheDao.getHitsSince(System.currentTimeMillis() - 3600000)
        )
    }
    
    private fun generateKey(query: String, platform: String, pincode: String): String {
        return "${query.lowercase().trim()}_${platform}_$pincode"
    }
    
    private fun getTtl(platform: String): Long {
        return if (platform in Platforms.QUICK_COMMERCE) {
            QUICK_COMMERCE_TTL
        } else {
            ECOMMERCE_TTL
        }
    }
    
    private fun parseProducts(json: String): List<Product>? {
        return try {
            gson.fromJson(json, productListType)
        } catch (e: Exception) {
            null
        }
    }
}

data class CacheStats(
    val totalEntries: Int,
    val hitsSinceStartup: Int
)

