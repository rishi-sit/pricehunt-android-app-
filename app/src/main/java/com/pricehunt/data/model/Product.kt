package com.pricehunt.data.model

import androidx.compose.ui.graphics.Color

/**
 * Represents a product from any e-commerce platform.
 */
data class Product(
    val name: String,
    val price: Double,
    val originalPrice: Double? = null,
    val discount: String? = null,
    val platform: String,
    val platformColor: Long,
    val url: String,
    val imageUrl: String? = null,
    val rating: Double? = null,
    val deliveryTime: String,
    val available: Boolean = true
) {
    val discountPercentage: Int?
        get() = if (originalPrice != null && originalPrice > price) {
            ((originalPrice - price) / originalPrice * 100).toInt()
        } else null
    
    val color: Color
        get() = Color(platformColor)
}

/**
 * Platform configuration.
 */
data class Platform(
    val name: String,
    val color: Long,
    val deliveryTime: String,
    val isQuickCommerce: Boolean
)

/**
 * Search event emitted during streaming search.
 */
sealed class SearchEvent {
    data class Started(val platforms: List<String>) : SearchEvent()
    data class PlatformResult(
        val platform: String,
        val products: List<Product>,
        val cached: Boolean = false
    ) : SearchEvent()
    data object Completed : SearchEvent()
    data class Error(val message: String) : SearchEvent()
}

