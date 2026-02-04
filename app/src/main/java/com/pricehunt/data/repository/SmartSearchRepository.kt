package com.pricehunt.data.repository

import android.util.Log
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for AI-powered smart search using backend AI integration (Groq/Mistral/Gemini).
 * 
 * Flow:
 * 1. Android app scrapes products from all platforms (using WebView scrapers)
 * 2. Scraped products are sent to backend `/api/smart-search-and-match`
 * 3. Backend uses AI (Mistral) to:
 *    - Filter out irrelevant products (e.g., "milkshake" when searching "milk")
 *    - Match similar products across platforms
 *    - Find best deals
 * 4. Returns filtered, matched, and ranked results
 */
@Singleton
class SmartSearchRepository @Inject constructor(
    private val api: PriceHuntApi
) {
    private companion object {
        const val MAX_PLATFORM_ITEMS = 10
    }

    /**
     * Perform AI-powered smart search on scraped products.
     * 
     * @param query User's search query
     * @param scrapedProducts Products scraped from all platforms (from local scrapers)
     * @param pincode Delivery pincode
     * @param strictMode If true, be strict about filtering (recommended for single words)
     * @param platformResults Optional platform-wise products (preferred for backend)
     */
    suspend fun smartSearch(
        query: String,
        scrapedProducts: List<Product>,
        pincode: String = "560001",
        strictMode: Boolean = true,
        platformResults: Map<String, List<Product>>? = null
    ): SmartSearchResult = withContext(Dispatchers.IO) {
        try {
            // Ensure we have platform-wise data for backend intelligence
            val normalizedPlatformResults = platformResults?.let { results ->
                val base = Platforms.ALL.associate { platform ->
                    val items = results[platform.name].orEmpty().take(MAX_PLATFORM_ITEMS)
                    platform.name to items
                }
                base
            }

            // Convert local Product model to API input model (flattened)
            val apiProducts = if (normalizedPlatformResults != null) {
                normalizedPlatformResults.values.flatten().map { it.toApiInput() }
            } else {
                scrapedProducts.map { it.toApiInput() }
            }

            val apiPlatformResults = normalizedPlatformResults?.mapValues { (_, products) ->
                products.map { it.toApiInput() }
            }
            
            val request = SmartSearchRequest(
                query = query,
                products = apiProducts,
                pincode = pincode,
                strictMode = strictMode,
                platformResults = apiPlatformResults
            )
            
            val response = api.smartSearchAndMatch(request)
            
            // Log AI provider info for debugging
            val aiMeta = response.aiMeta
            Log.i("SmartSearchRepo", "🤖 AI Provider: ${aiMeta?.provider ?: "unknown"}, " +
                "Model: ${aiMeta?.model ?: "unknown"}, " +
                "Latency: ${aiMeta?.latencyMs ?: 0}ms, " +
                "Products: ${response.allProducts.size}, " +
                "Groups: ${response.productGroups.size}")
            if (aiMeta?.fallbackReason != null) {
                Log.w("SmartSearchRepo", "⚠️ Fallback: ${aiMeta.fallbackReason}")
            }
            
            SmartSearchResult.Success(
                query = response.query,
                aiPowered = response.aiPowered,
                queryUnderstanding = response.queryUnderstanding,
                relevantProducts = response.allProducts.map { it.toProduct() },
                productGroups = response.productGroups.map { it.toLocalGroup() },
                bestDeal = response.bestDeal?.toProduct(),
                filteredOut = response.filteredOut,
                stats = response.stats
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()?.take(300)
            println("SmartSearchRepository: HTTP ${e.code()} - ${errorBody ?: e.message()}")
            SmartSearchResult.Error("HTTP ${e.code()}")
        } catch (e: Exception) {
            println("SmartSearchRepository: Error (${e.javaClass.simpleName}) - ${e.message}")
            SmartSearchResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Just filter products without matching (faster).
     */
    suspend fun filterProducts(
        query: String,
        products: List<Product>,
        pincode: String = "560001",
        strictMode: Boolean = true,
        platformResults: Map<String, List<Product>>? = null
    ): FilterResult = withContext(Dispatchers.IO) {
        try {
            val normalizedPlatformResults = platformResults?.let { results ->
                val base = Platforms.ALL.associate { platform ->
                    val items = results[platform.name].orEmpty().take(MAX_PLATFORM_ITEMS)
                    platform.name to items
                }
                base
            }

            val apiProducts = if (normalizedPlatformResults != null) {
                normalizedPlatformResults.values.flatten().map { it.toApiInput() }
            } else {
                products.map { it.toApiInput() }
            }

            val apiPlatformResults = normalizedPlatformResults?.mapValues { (_, items) ->
                items.map { it.toApiInput() }
            }
            
            val request = SmartSearchRequest(
                query = query,
                products = apiProducts,
                pincode = pincode,
                strictMode = strictMode,
                platformResults = apiPlatformResults
            )
            
            val response = api.smartSearch(request)
            
            // Log AI provider info for debugging
            val aiMeta = response.aiMeta
            Log.i("SmartSearchRepo", "🤖 AI Provider: ${aiMeta?.provider ?: "unknown"}, " +
                "Model: ${aiMeta?.model ?: "unknown"}, " +
                "Latency: ${aiMeta?.latencyMs ?: 0}ms, " +
                "Results: ${response.results.size}, " +
                "Filtered: ${response.filteredOut.size}")
            if (aiMeta?.fallbackReason != null) {
                Log.w("SmartSearchRepo", "⚠️ Fallback: ${aiMeta.fallbackReason}")
            }
            
            FilterResult.Success(
                aiPowered = response.aiPowered,
                relevantProducts = response.results.map { it.toProduct() },
                filteredOut = response.filteredOut,
                bestDeal = response.bestDeal?.toProduct()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()?.take(300)
            println("SmartSearchRepository: HTTP ${e.code()} - ${errorBody ?: e.message()}")
            FilterResult.Error("HTTP ${e.code()}")
        } catch (e: Exception) {
            println("SmartSearchRepository: Error (${e.javaClass.simpleName}) - ${e.message}")
            FilterResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Match products across platforms (without filtering).
     */
    suspend fun matchProducts(
        products: List<Product>
    ): MatchResult = withContext(Dispatchers.IO) {
        try {
            val apiProducts = products.map { it.toApiInput() }
            
            val request = MatchProductsRequest(products = apiProducts)
            val response = api.matchProducts(request)
            
            MatchResult.Success(
                aiPowered = response.aiPowered,
                productGroups = response.productGroups.map { it.toLocalGroup() },
                unmatchedProducts = response.unmatchedProducts.map { it.toProduct() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()?.take(300)
            println("SmartSearchRepository: HTTP ${e.code()} - ${errorBody ?: e.message()}")
            MatchResult.Error("HTTP ${e.code()}")
        } catch (e: Exception) {
            println("SmartSearchRepository: Error (${e.javaClass.simpleName}) - ${e.message}")
            MatchResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Understand query intent (for debugging/display).
     */
    suspend fun understandQuery(query: String): QueryUnderstanding? = withContext(Dispatchers.IO) {
        try {
            val response = api.understandQuery(query)
            response.understanding
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if backend AI is available.
     */
    suspend fun isAiAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.healthCheck()
            response.aiAvailable
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// Result sealed classes
// ============================================================================

sealed class SmartSearchResult {
    data class Success(
        val query: String,
        val aiPowered: Boolean,
        val queryUnderstanding: Map<String, Any>?,
        val relevantProducts: List<Product>,
        val productGroups: List<LocalProductGroup>,
        val bestDeal: Product?,
        val filteredOut: List<FilteredProduct>,
        val stats: CombinedStats
    ) : SmartSearchResult()
    
    data class Error(val message: String) : SmartSearchResult()
}

sealed class FilterResult {
    data class Success(
        val aiPowered: Boolean,
        val relevantProducts: List<Product>,
        val filteredOut: List<FilteredProduct>,
        val bestDeal: Product?
    ) : FilterResult()
    
    data class Error(val message: String) : FilterResult()
}

sealed class MatchResult {
    data class Success(
        val aiPowered: Boolean,
        val productGroups: List<LocalProductGroup>,
        val unmatchedProducts: List<Product>
    ) : MatchResult()
    
    data class Error(val message: String) : MatchResult()
}

/**
 * Local product group model for UI.
 */
data class LocalProductGroup(
    val canonicalName: String,
    val brand: String?,
    val quantity: String?,
    val products: List<Product>,
    val bestDeal: Product?,
    val priceRange: String,
    val savings: Double?
)

// ============================================================================
// Extension functions for model conversion
// ============================================================================

/**
 * Convert local Product to API input.
 */
private fun Product.toApiInput(): ApiProductInput {
    return ApiProductInput(
        name = name,
        price = price,
        originalPrice = originalPrice,
        discount = discount,
        platform = platform,
        url = url,
        imageUrl = imageUrl,
        rating = rating,
        deliveryTime = deliveryTime,
        available = available
    )
}

/**
 * Convert API product with relevance to local Product.
 */
private fun ApiProductWithRelevance.toProduct(): Product {
    return Product(
        name = name,
        price = price,
        originalPrice = originalPrice,
        discount = discount,
        platform = platform,
        platformColor = Platforms.getColor(platform),
        url = url ?: "",
        imageUrl = imageUrl,
        rating = rating,
        deliveryTime = deliveryTime ?: "",
        available = available ?: true
    )
}

/**
 * Convert API product to local Product.
 */
private fun ApiProduct.toProduct(): Product {
    return Product(
        name = name,
        price = price,
        originalPrice = originalPrice,
        discount = discount,
        platform = platform,
        platformColor = Platforms.getColor(platform),
        url = url ?: "",
        imageUrl = imageUrl,
        rating = rating,
        deliveryTime = deliveryTime ?: "",
        available = available
    )
}

/**
 * Convert API ProductGroup to local model.
 */
private fun ProductGroup.toLocalGroup(): LocalProductGroup {
    return LocalProductGroup(
        canonicalName = canonicalName,
        brand = brand,
        quantity = quantity,
        products = products.map { it.toProduct() },
        bestDeal = bestDeal?.let { deal ->
            Product(
                name = deal.name ?: canonicalName,
                price = deal.price ?: 0.0,
                originalPrice = null,
                discount = null,
                platform = deal.platform ?: "",
                platformColor = Platforms.getColor(deal.platform ?: ""),
                url = deal.url ?: "",
                imageUrl = deal.imageUrl,
                rating = null,
                deliveryTime = "",
                available = true
            )
        },
        priceRange = priceRange,
        savings = savings
    )
}
