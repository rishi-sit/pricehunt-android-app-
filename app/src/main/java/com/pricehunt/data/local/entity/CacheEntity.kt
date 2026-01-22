package com.pricehunt.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for caching search results.
 */
@Entity(tableName = "search_cache")
data class CacheEntity(
    @PrimaryKey
    val cacheKey: String, // query_platform_pincode
    val query: String,
    val platform: String,
    val pincode: String,
    val resultsJson: String, // JSON serialized List<Product>
    val timestamp: Long = System.currentTimeMillis()
)

