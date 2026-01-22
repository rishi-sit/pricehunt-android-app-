package com.pricehunt.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * API interface for PriceHunt backend server.
 */
interface PriceHuntApi {
    
    @GET("/api/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("pincode") pincode: String
    ): SearchResponse
    
    @GET("/api/platforms")
    suspend fun getPlatforms(): PlatformsResponse
}

data class SearchResponse(
    @SerializedName("query") val query: String,
    @SerializedName("pincode") val pincode: String?,
    @SerializedName("results") val results: List<ApiProduct>,
    @SerializedName("lowest_price") val lowestPrice: ApiProduct?,
    @SerializedName("total_platforms") val totalPlatforms: Int?
)

data class ApiProduct(
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("original_price") val originalPrice: Double?,
    @SerializedName("discount") val discount: String?,
    @SerializedName("platform") val platform: String,
    @SerializedName("url") val url: String,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("rating") val rating: Double?,
    @SerializedName("available") val available: Boolean,
    @SerializedName("delivery_time") val deliveryTime: String
)

data class PlatformsResponse(
    @SerializedName("platforms") val platforms: List<PlatformInfo>
)

data class PlatformInfo(
    @SerializedName("name") val name: String,
    @SerializedName("delivery_time") val deliveryTime: String
)

