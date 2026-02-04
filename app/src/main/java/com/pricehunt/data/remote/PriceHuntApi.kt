package com.pricehunt.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * API interface for PriceHunt backend server.
 * v2.1: Added AI-powered fallback scraping endpoints
 */
interface PriceHuntApi {
    
    @GET("/api/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("pincode") pincode: String
    ): SearchResponse
    
    @GET("/api/platforms")
    suspend fun getPlatforms(): PlatformsResponse
    
    // ========================================================================
    // AI-Powered Smart Search Endpoints
    // ========================================================================
    
    /**
     * Smart search with AI filtering.
     * Sends scraped products to backend for AI analysis.
     */
    @POST("/api/smart-search")
    suspend fun smartSearch(@Body request: SmartSearchRequest): SmartSearchResponse
    
    /**
     * Match products across platforms.
     * Groups similar products (same brand, size) from different platforms.
     */
    @POST("/api/match-products")
    suspend fun matchProducts(@Body request: MatchProductsRequest): MatchProductsResponse
    
    /**
     * Combined smart search + product matching.
     * This is the recommended endpoint for best results.
     */
    @POST("/api/smart-search-and-match")
    suspend fun smartSearchAndMatch(@Body request: SmartSearchRequest): SmartSearchAndMatchResponse
    
    /**
     * Understand query intent.
     */
    @GET("/api/understand-query")
    suspend fun understandQuery(@Query("q") query: String): QueryUnderstandingResponse
    
    /**
     * Health check.
     */
    @GET("/api/health")
    suspend fun healthCheck(): HealthResponse
    
    // ========================================================================
    // NEW: AI Fallback Scraping Endpoints (v2.1)
    // ========================================================================
    
    /**
     * AI-powered product extraction from raw HTML.
     * Use this when client-side scraping fails but WebView captured HTML.
     */
    @POST("/api/ai-extract")
    suspend fun aiExtract(@Body request: AIExtractRequest): AIExtractResponse
    
    /**
     * AI extraction for multiple platforms at once.
     * More efficient than calling ai-extract multiple times.
     */
    @POST("/api/ai-extract-multi")
    suspend fun aiExtractMulti(@Body request: AIExtractMultiRequest): AIExtractMultiResponse
    
    /**
     * Complete pipeline: Extract + Filter + Match.
     * Ultimate fallback - handles everything server-side.
     */
    @POST("/api/smart-extract-and-filter")
    suspend fun smartExtractAndFilter(@Body request: AIExtractMultiRequest): SmartExtractResponse
}

// ============================================================================
// Request Models
// ============================================================================

data class SmartSearchRequest(
    @SerializedName("query") val query: String,
    @SerializedName("products") val products: List<ApiProductInput>,
    @SerializedName("pincode") val pincode: String = "560001",
    @SerializedName("strict_mode") val strictMode: Boolean = true,
    @SerializedName("platform_results") val platformResults: Map<String, List<ApiProductInput>>? = null
)

data class MatchProductsRequest(
    @SerializedName("products") val products: List<ApiProductInput>
)

/**
 * Request for AI-powered HTML extraction (fallback scraping)
 */
data class AIExtractRequest(
    @SerializedName("html") val html: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("search_query") val searchQuery: String,
    @SerializedName("base_url") val baseUrl: String
)

/**
 * Request for extracting from multiple platform HTMLs
 */
data class AIExtractMultiRequest(
    @SerializedName("platforms") val platforms: List<PlatformHtml>,
    @SerializedName("search_query") val searchQuery: String
)

data class PlatformHtml(
    @SerializedName("platform") val platform: String,
    @SerializedName("html") val html: String,
    @SerializedName("base_url") val baseUrl: String
)

data class ApiProductInput(
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("original_price") val originalPrice: Double? = null,
    @SerializedName("discount") val discount: String? = null,
    @SerializedName("platform") val platform: String,
    @SerializedName("url") val url: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("rating") val rating: Double? = null,
    @SerializedName("delivery_time") val deliveryTime: String? = null,
    @SerializedName("available") val available: Boolean = true
)

// ============================================================================
// Response Models
// ============================================================================

data class SearchResponse(
    @SerializedName("query") val query: String,
    @SerializedName("pincode") val pincode: String?,
    @SerializedName("results") val results: List<ApiProduct>,
    @SerializedName("lowest_price") val lowestPrice: ApiProduct?,
    @SerializedName("total_platforms") val totalPlatforms: Int?
)

data class SmartSearchResponse(
    @SerializedName("query") val query: String,
    @SerializedName("pincode") val pincode: String?,
    @SerializedName("ai_powered") val aiPowered: Boolean,
    @SerializedName("ai_meta") val aiMeta: AiMeta?,
    @SerializedName("query_understanding") val queryUnderstanding: Map<String, Any>?,
    @SerializedName("results") val results: List<ApiProductWithRelevance>,
    @SerializedName("filtered_out") val filteredOut: List<FilteredProduct>,
    @SerializedName("best_deal") val bestDeal: ApiProductWithRelevance?,
    @SerializedName("stats") val stats: SearchStats
)

data class AiMeta(
    @SerializedName("provider") val provider: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("latency_ms") val latencyMs: Int?,
    @SerializedName("fallback_reason") val fallbackReason: String?
)

data class MatchProductsResponse(
    @SerializedName("ai_powered") val aiPowered: Boolean,
    @SerializedName("product_groups") val productGroups: List<ProductGroup>,
    @SerializedName("unmatched_products") val unmatchedProducts: List<ApiProduct>,
    @SerializedName("stats") val stats: MatchStats
)

data class SmartSearchAndMatchResponse(
    @SerializedName("query") val query: String,
    @SerializedName("pincode") val pincode: String?,
    @SerializedName("ai_powered") val aiPowered: Boolean,
    @SerializedName("ai_meta") val aiMeta: AiMeta?,
    @SerializedName("query_understanding") val queryUnderstanding: Map<String, Any>?,
    @SerializedName("product_groups") val productGroups: List<ProductGroup>,
    @SerializedName("all_products") val allProducts: List<ApiProductWithRelevance>,
    @SerializedName("best_deal") val bestDeal: ApiProductWithRelevance?,
    @SerializedName("filtered_out") val filteredOut: List<FilteredProduct>,
    @SerializedName("stats") val stats: CombinedStats
)

data class QueryUnderstandingResponse(
    @SerializedName("query") val query: String,
    @SerializedName("ai_powered") val aiPowered: Boolean,
    @SerializedName("understanding") val understanding: QueryUnderstanding
)

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String,
    @SerializedName("ai_available") val aiAvailable: Boolean,
    @SerializedName("ai_scraper_available") val aiScraperAvailable: Boolean? = null
)

// ============================================================================
// AI Extraction Response Models
// ============================================================================

data class AIExtractResponse(
    @SerializedName("platform") val platform: String,
    @SerializedName("search_query") val searchQuery: String,
    @SerializedName("products") val products: List<AIExtractedProduct>,
    @SerializedName("products_found") val productsFound: Int,
    @SerializedName("extraction_method") val extractionMethod: String,
    @SerializedName("confidence") val confidence: Double,
    @SerializedName("ai_powered") val aiPowered: Boolean,
    @SerializedName("error") val error: String?
)

data class AIExtractMultiResponse(
    @SerializedName("search_query") val searchQuery: String,
    @SerializedName("results") val results: Map<String, AIExtractResponse>,
    @SerializedName("total_products") val totalProducts: Int,
    @SerializedName("ai_powered") val aiPowered: Boolean
)

data class SmartExtractResponse(
    @SerializedName("search_query") val searchQuery: String,
    @SerializedName("stats") val stats: ExtractStats,
    @SerializedName("extraction_results") val extractionResults: Map<String, AIExtractResponse>,
    @SerializedName("filtered_products") val filteredProducts: List<ApiProductWithRelevance>,
    @SerializedName("product_groups") val productGroups: List<ProductGroup>,
    @SerializedName("best_deal") val bestDeal: ApiProductWithRelevance?,
    @SerializedName("ai_powered") val aiPowered: Boolean
)

data class AIExtractedProduct(
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("original_price") val originalPrice: Double?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("product_url") val productUrl: String?,
    @SerializedName("platform") val platform: String,
    @SerializedName("in_stock") val inStock: Boolean,
    @SerializedName("ai_extracted") val aiExtracted: Boolean?
)

data class ExtractStats(
    @SerializedName("platforms_processed") val platformsProcessed: Int,
    @SerializedName("total_extracted") val totalExtracted: Int,
    @SerializedName("after_filtering") val afterFiltering: Int,
    @SerializedName("product_groups") val productGroups: Int
)

// ============================================================================
// Data Models
// ============================================================================

data class ApiProduct(
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("original_price") val originalPrice: Double?,
    @SerializedName("discount") val discount: String?,
    @SerializedName("platform") val platform: String,
    @SerializedName("url") val url: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("rating") val rating: Double?,
    @SerializedName("available") val available: Boolean,
    @SerializedName("delivery_time") val deliveryTime: String?
)

data class ApiProductWithRelevance(
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("original_price") val originalPrice: Double?,
    @SerializedName("discount") val discount: String?,
    @SerializedName("platform") val platform: String,
    @SerializedName("url") val url: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("rating") val rating: Double?,
    @SerializedName("available") val available: Boolean?,
    @SerializedName("delivery_time") val deliveryTime: String?,
    @SerializedName("relevance_score") val relevanceScore: Int?,
    @SerializedName("relevance_reason") val relevanceReason: String?
)

data class FilteredProduct(
    @SerializedName("name") val name: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("filter_reason") val filterReason: String
)

data class ProductGroup(
    @SerializedName("canonical_name") val canonicalName: String,
    @SerializedName("brand") val brand: String?,
    @SerializedName("quantity") val quantity: String?,
    @SerializedName("products") val products: List<ApiProduct>,
    @SerializedName("best_deal") val bestDeal: BestDealInfo?,
    @SerializedName("price_range") val priceRange: String,
    @SerializedName("savings") val savings: Double?
)

data class BestDealInfo(
    @SerializedName("name") val name: String?,
    @SerializedName("price") val price: Double?,
    @SerializedName("platform") val platform: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("image_url") val imageUrl: String?
)

data class QueryUnderstanding(
    @SerializedName("original_query") val originalQuery: String?,
    @SerializedName("product_type") val productType: String?,
    @SerializedName("quantity") val quantity: String?,
    @SerializedName("brand") val brand: String?,
    @SerializedName("category") val category: String?,
    @SerializedName("is_specific") val isSpecific: Boolean?,
    @SerializedName("search_terms") val searchTerms: List<String>?,
    @SerializedName("exclude_terms") val excludeTerms: List<String>?
)

data class SearchStats(
    @SerializedName("total_input") val totalInput: Int,
    @SerializedName("total_relevant") val totalRelevant: Int,
    @SerializedName("total_filtered") val totalFiltered: Int
)

data class MatchStats(
    @SerializedName("total_products") val totalProducts: Int,
    @SerializedName("total_groups") val totalGroups: Int,
    @SerializedName("total_matched") val totalMatched: Int,
    @SerializedName("total_unmatched") val totalUnmatched: Int
)

data class CombinedStats(
    @SerializedName("input_products") val inputProducts: Int,
    @SerializedName("relevant_products") val relevantProducts: Int,
    @SerializedName("filtered_products") val filteredProducts: Int,
    @SerializedName("product_groups") val productGroups: Int,
    @SerializedName("matched_products") val matchedProducts: Int
)

data class PlatformsResponse(
    @SerializedName("platforms") val platforms: List<PlatformInfo>
)

data class PlatformInfo(
    @SerializedName("name") val name: String,
    @SerializedName("delivery_time") val deliveryTime: String
)

