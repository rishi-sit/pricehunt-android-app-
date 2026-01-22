package com.pricehunt.data.scrapers

import com.google.gson.JsonElement
import com.pricehunt.data.model.Product
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Base class for all platform scrapers.
 * Provides common HTTP functionality and parsing utilities.
 */
abstract class BaseScraper {
    
    abstract val platformName: String
    abstract val platformColor: Long
    abstract val deliveryTime: String
    abstract val baseUrl: String
    
    protected val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    /**
     * Search for products on this platform.
     */
    abstract suspend fun search(query: String, pincode: String): List<Product>
    
    /**
     * Fetch HTML from URL.
     */
    protected suspend fun fetchHtml(url: String): Document? {
        return try {
            val request = Request.Builder()
                .url(url)
                .headers(getHeaders())
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: return null
                Jsoup.parse(html)
            } else {
                println("$platformName: HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("$platformName: Error fetching - ${e.message}")
            null
        }
    }
    
    /**
     * Get randomized headers to avoid detection.
     */
    protected open fun getHeaders(): Headers {
        return Headers.Builder()
            .add("User-Agent", getRandomUserAgent())
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Accept-Language", "en-IN,en-GB;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Accept-Encoding", "gzip, deflate")
            .add("Connection", "keep-alive")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Cache-Control", "max-age=0")
            .build()
    }
    
    /**
     * Parse price string to Double.
     */
    protected fun parsePrice(priceStr: String?): Double {
        if (priceStr.isNullOrBlank()) return 0.0
        val cleaned = priceStr.replace(Regex("[₹,\\s]"), "")
        return cleaned.toDoubleOrNull() ?: 0.0
    }
    
    /**
     * Create a Product result.
     */
    protected fun createProduct(
        name: String,
        price: Double,
        originalPrice: Double? = null,
        discount: String? = null,
        url: String,
        imageUrl: String? = null,
        rating: Double? = null,
        available: Boolean = true
    ): Product {
        return Product(
            name = name.take(120),
            price = price,
            originalPrice = originalPrice,
            discount = discount,
            platform = platformName,
            platformColor = platformColor,
            url = url,
            imageUrl = imageUrl,
            rating = rating,
            deliveryTime = deliveryTime,
            available = available
        )
    }
    
    companion object {
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-S906N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        
        fun getRandomUserAgent(): String {
            return USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
        }
        
        /**
         * Safely get a string from a JsonElement, handling null and JsonNull.
         */
        fun JsonElement?.safeAsString(): String? {
            return if (this != null && !this.isJsonNull && this.isJsonPrimitive) {
                this.asString
            } else null
        }
        
        /**
         * Safely get a double from a JsonElement, handling null and JsonNull.
         */
        fun JsonElement?.safeAsDouble(): Double? {
            return if (this != null && !this.isJsonNull && this.isJsonPrimitive) {
                try { this.asDouble } catch (e: Exception) { null }
            } else null
        }
    }
}

