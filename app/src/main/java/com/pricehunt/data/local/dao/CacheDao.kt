package com.pricehunt.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pricehunt.data.local.entity.CacheEntity

/**
 * Data Access Object for search cache operations.
 */
@Dao
interface CacheDao {
    
    @Query("SELECT * FROM search_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): CacheEntity?
    
    @Query("SELECT * FROM search_cache WHERE query = :query AND pincode = :pincode")
    suspend fun getByQueryAndPincode(query: String, pincode: String): List<CacheEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: CacheEntity)
    
    @Query("DELETE FROM search_cache WHERE cacheKey = :key")
    suspend fun delete(key: String)
    
    @Query("DELETE FROM search_cache")
    suspend fun clearAll()
    
    @Query("DELETE FROM search_cache WHERE timestamp < :expiryTime")
    suspend fun deleteExpired(expiryTime: Long)
    
    @Query("SELECT COUNT(*) FROM search_cache")
    suspend fun getCount(): Int
    
    @Query("SELECT COUNT(*) FROM search_cache WHERE timestamp >= :since")
    suspend fun getHitsSince(since: Long): Int
}

