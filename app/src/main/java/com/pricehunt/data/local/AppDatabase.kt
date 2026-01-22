package com.pricehunt.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pricehunt.data.local.dao.CacheDao
import com.pricehunt.data.local.entity.CacheEntity

/**
 * Room database for the app.
 */
@Database(
    entities = [CacheEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

