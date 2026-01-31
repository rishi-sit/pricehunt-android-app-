package com.pricehunt.data.scrapers.api

import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct API scraper - calls internal APIs of platforms directly.
 * This bypasses WebView rendering issues completely.
 * 
 * Many platforms expose GraphQL or REST APIs that mobile apps use.
 * We can call these directly to get product data.
 */
@Singleton
class DirectApiScraper @Inject constructor() {
    
    companion object {
        private const val TAG = "DirectApiScraper"
        
        // Common headers to mimic mobile app
        private val COMMON_HEADERS = mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "en-IN,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    
    /**
     * Scrape products from a platform using its API
     */
    suspend fun scrape(
        platform: String,
        query: String,
        pincode: String
    ): ApiScrapeResult = withContext(Dispatchers.IO) {
        log("$platform: Starting API scrape for '$query'")
        
        try {
            when (platform) {
                Platforms.ZEPTO -> scrapeZeptoApi(query, pincode)
                Platforms.BLINKIT -> scrapeBlinkitApi(query, pincode)
                Platforms.BIGBASKET -> scrapeBigBasketApi(query, pincode)
                Platforms.INSTAMART -> scrapeInstamartApi(query, pincode)
                Platforms.JIOMART -> scrapeJioMartApi(query, pincode)
                Platforms.JIOMART_QUICK -> scrapeJioMartApi(query, pincode) // Same API
                Platforms.AMAZON -> scrapeAmazonApi(query, pincode)
                Platforms.AMAZON_FRESH -> scrapeAmazonFreshApi(query, pincode)
                Platforms.FLIPKART -> scrapeFlipkartApi(query, pincode)
                Platforms.FLIPKART_MINUTES -> scrapeFlipkartMinutesApi(query, pincode)
                else -> ApiScrapeResult.NotSupported(platform)
            }
        } catch (e: Exception) {
            log("$platform: API scrape failed: ${e.message}")
            ApiScrapeResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    // ==================== ZEPTO API ====================
    private fun scrapeZeptoApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        fun buildRequest(baseUrl: String): Request {
            return Request.Builder()
                .url("$baseUrl/api/v3/search/?query=$encodedQuery&pageNumber=1&mode=AUTOSUGGEST")
                .apply {
                    COMMON_HEADERS.forEach { (k, v) -> header(k, v) }
                    header("User-Agent", "ZeptoCustomer/6.0.0 Android/13")
                    header("X-App-Version", "6.0.0")
                    header("X-Device-Id", "android-${System.currentTimeMillis()}")
                    header("X-Store-Id", getZeptoStoreId(pincode))
                    header("Cookie", "pincode=$pincode; lat=12.9716; lng=77.5946; storeId=${getZeptoStoreId(pincode)}")
                }
                .get()
                .build()
        }

        // Zepto's internal search API (fallback to web domain if DNS fails)
        val primaryResult = executeAndParse(buildRequest("https://api.zeptonow.com"), Platforms.ZEPTO) { json ->
            parseZeptoResponse(json)
        }

        if (primaryResult is ApiScrapeResult.Failure &&
            primaryResult.reason.contains("resolve host", ignoreCase = true)) {
            return executeAndParse(buildRequest("https://www.zeptonow.com"), Platforms.ZEPTO) { json ->
                parseZeptoResponse(json)
            }
        }

        return primaryResult
    }
    
    private fun getZeptoStoreId(pincode: String): String {
        // Map common pincodes to store IDs (Bangalore default)
        return when {
            pincode.startsWith("560") -> "8b7f5a42-e3c7-4b9a-a1d1-5e6f7c8d9e0f" // Bangalore
            pincode.startsWith("400") -> "a1b2c3d4-e5f6-7890-abcd-ef1234567890" // Mumbai
            pincode.startsWith("110") -> "b2c3d4e5-f6a7-8901-bcde-f12345678901" // Delhi
            else -> "8b7f5a42-e3c7-4b9a-a1d1-5e6f7c8d9e0f" // Default
        }
    }
    
    private fun parseZeptoResponse(json: JSONObject): List<Product> {
        val products = mutableListOf<Product>()
        
        // Try different response structures
        val items = json.optJSONArray("products") 
            ?: json.optJSONObject("data")?.optJSONArray("products")
            ?: json.optJSONArray("items")
            ?: json.optJSONObject("data")?.optJSONArray("items")
            ?: return emptyList()
        
        for (i in 0 until minOf(items.length(), 15)) {
            try {
                val item = items.getJSONObject(i)
                val name = item.optString("name") 
                    .ifEmpty { item.optString("productName") }
                    .ifEmpty { item.optString("title") }
                
                val price = item.optDouble("sellingPrice", 0.0)
                    .takeIf { it > 0 } 
                    ?: item.optDouble("price", 0.0)
                    .takeIf { it > 0 }
                    ?: item.optJSONObject("pricing")?.optDouble("selling_price", 0.0)
                    ?: 0.0
                
                if (name.isNotEmpty() && price > 0) {
                    products.add(Product(
                        name = name,
                        price = price,
                        originalPrice = item.optDouble("mrp").takeIf { it > price },
                        imageUrl = item.optString("imageUrl")
                            .ifEmpty { item.optJSONArray("images")?.optString(0) ?: "" },
                        platform = Platforms.ZEPTO,
                        platformColor = Platforms.ZEPTO_COLOR,
                        deliveryTime = "10 mins",
                        url = "https://www.zeptonow.com/product/${item.optString("id", item.optString("productId"))}",
                        available = item.optBoolean("inStock", true)
                    ))
                }
            } catch (e: Exception) {
                log("Zepto: Error parsing item $i: ${e.message}")
            }
        }
        
        return products
    }
    
    // ==================== BLINKIT API ====================
    private fun scrapeBlinkitApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // Blinkit GraphQL API
        val graphqlQuery = """
            {"query":"query SearchProducts(${'$'}q: String!, ${'$'}page: Int) { searchProducts(query: ${'$'}q, page: ${'$'}page) { products { id name price mrp imageUrl inStock } } }","variables":{"q":"$query","page":1}}
        """.trimIndent()
        
        val request = Request.Builder()
            .url("https://blinkit.com/v2/search?q=$encodedQuery")
            .apply {
                COMMON_HEADERS.forEach { (k, v) -> header(k, v) }
                header("User-Agent", "Blinkit/9.0.0 Android/13")
                header("X-Device-Type", "android")
                header("Cookie", "pincode=$pincode; lat=12.9716; lng=77.5946; city=Bangalore")
            }
            .get()
            .build()
        
        return executeAndParse(request, Platforms.BLINKIT) { json ->
            parseBlinkitResponse(json)
        }
    }
    
    private fun parseBlinkitResponse(json: JSONObject): List<Product> {
        val products = mutableListOf<Product>()
        
        val items = json.optJSONArray("products")
            ?: json.optJSONObject("data")?.optJSONArray("products")
            ?: json.optJSONObject("data")?.optJSONObject("searchProducts")?.optJSONArray("products")
            ?: json.optJSONArray("objects")
            ?: return emptyList()
        
        for (i in 0 until minOf(items.length(), 15)) {
            try {
                val item = items.getJSONObject(i)
                val name = item.optString("name").ifEmpty { item.optString("product_name") }
                val price = item.optDouble("price", 0.0)
                    .takeIf { it > 0 }
                    ?: item.optJSONObject("pricing")?.optDouble("discounted_price", 0.0)
                    ?: 0.0
                
                if (name.isNotEmpty() && price > 0) {
                    products.add(Product(
                        name = name,
                        price = price,
                        originalPrice = item.optDouble("mrp").takeIf { it > price },
                        imageUrl = item.optString("imageUrl").ifEmpty { item.optString("image_url") },
                        platform = Platforms.BLINKIT,
                        platformColor = Platforms.BLINKIT_COLOR,
                        deliveryTime = "10 mins",
                        url = "https://blinkit.com/prn/${item.optString("id")}",
                        available = item.optBoolean("inStock", item.optBoolean("in_stock", true))
                    ))
                }
            } catch (e: Exception) {
                log("Blinkit: Error parsing item $i: ${e.message}")
            }
        }
        
        return products
    }
    
    // ==================== BIGBASKET API ====================
    private fun scrapeBigBasketApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val request = Request.Builder()
            .url("https://www.bigbasket.com/listing-svc/v2/products?type=ps&slug=$encodedQuery&page=1")
            .apply {
                COMMON_HEADERS.forEach { (k, v) -> header(k, v) }
                header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                header("X-BB-Channel", "mobile")
                header("Referer", "https://www.bigbasket.com/ps/?q=$encodedQuery")
                header("Origin", "https://www.bigbasket.com")
                header("X-Entry-Context-Id", "100")
                header("X-Entry-Context", "bb-b2c")
                header("Cookie", "bb_pincode=$pincode; bb_home_pincode=$pincode; bb_locSrc=manual; x-entry-context-id=100; x-entry-context=bb-b2c")
            }
            .get()
            .build()
        
        return executeAndParse(request, Platforms.BIGBASKET) { json ->
            parseBigBasketResponse(json)
        }
    }
    
    private fun parseBigBasketResponse(json: JSONObject): List<Product> {
        val products = mutableListOf<Product>()
        
        val tabs = json.optJSONArray("tabs") ?: json.optJSONObject("response")?.optJSONArray("tabs")
        if (tabs != null && tabs.length() > 0) {
            val productList = tabs.getJSONObject(0).optJSONArray("product_info") ?: return emptyList()
            
            for (i in 0 until minOf(productList.length(), 15)) {
                try {
                    val item = productList.getJSONObject(i)
                    val children = item.optJSONArray("children")
                    val productData = if (children != null && children.length() > 0) {
                        children.getJSONObject(0)
                    } else {
                        item
                    }
                    
                    val pricing = productData.optJSONObject("pricing") ?: continue
                    val name = productData.optString("prod_name")
                        .ifEmpty { productData.optJSONObject("brand")?.optString("name") + " " + productData.optString("prod_name") }
                    val price = pricing.optJSONObject("discount")?.optDouble("prim_price", 0.0)
                        ?: pricing.optDouble("selling_price", 0.0)
                    
                    if (name.isNotEmpty() && price > 0) {
                        products.add(Product(
                            name = name.trim(),
                            price = price,
                            originalPrice = pricing.optDouble("mrp").takeIf { it > price },
                            imageUrl = productData.optJSONArray("images")?.optString(0)
                                ?: productData.optString("image_url"),
                            platform = Platforms.BIGBASKET,
                            platformColor = Platforms.BIGBASKET_COLOR,
                            deliveryTime = "1-2 hours",
                            url = "https://www.bigbasket.com/pd/${productData.optString("sku")}",
                            available = productData.optInt("availability_status", 1) > 0
                        ))
                    }
                } catch (e: Exception) {
                    log("BigBasket: Error parsing item $i: ${e.message}")
                }
            }
        }
        
        return products
    }
    
    // ==================== INSTAMART API ====================
    private fun scrapeInstamartApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val (lat, lng) = getPincodeCoordinates(pincode)
        val storeId = fetchSwiggyStoreId(lat, lng)
        val searchUrl = if (!storeId.isNullOrBlank()) {
            "https://www.swiggy.com/dapi/instamart/search?query=$encodedQuery&storeId=$storeId&lat=$lat&lng=$lng"
        } else {
            "https://www.swiggy.com/dapi/instamart/search?query=$encodedQuery&lat=$lat&lng=$lng"
        }
        
        val request = Request.Builder()
            .url(searchUrl)
            .apply {
                COMMON_HEADERS.forEach { (k, v) -> header(k, v) }
                header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                header("Referer", "https://www.swiggy.com/instamart/search?query=$encodedQuery")
                header("Cookie", "__SW=; _guest_tid=; lat=$lat; lng=$lng; pincode=$pincode")
            }
            .get()
            .build()
        
        return executeAndParse(request, Platforms.INSTAMART) { json ->
            parseInstamartResponse(json)
        }
    }
    
    private fun parseInstamartResponse(json: JSONObject): List<Product> {
        val products = mutableListOf<Product>()
        
        val data = json.optJSONObject("data") ?: return emptyList()
        val widgets = data.optJSONArray("widgets") ?: data.optJSONArray("cards") ?: return emptyList()
        
        for (w in 0 until widgets.length()) {
            val widget = widgets.optJSONObject(w) ?: continue
            val items = widget.optJSONArray("data") 
                ?: widget.optJSONObject("data")?.optJSONArray("products")
                ?: continue
            
            for (i in 0 until minOf(items.length(), 15 - products.size)) {
                try {
                    val item = items.getJSONObject(i)
                    val name = item.optString("display_name")
                        .ifEmpty { item.optString("name") }
                        .ifEmpty { item.optString("product_name") }
                    
                    val price = item.optJSONObject("price")?.optDouble("offer_price", 0.0)
                        ?: item.optDouble("price", 0.0)
                    
                    if (name.isNotEmpty() && price > 0) {
                        products.add(Product(
                            name = name,
                            price = price,
                            originalPrice = item.optJSONObject("price")?.optDouble("mrp")?.takeIf { it > price },
                            imageUrl = item.optJSONArray("images")?.optString(0)
                                ?: item.optString("image_url"),
                            platform = Platforms.INSTAMART,
                            platformColor = Platforms.INSTAMART_COLOR,
                            deliveryTime = "15-30 mins",
                            url = "https://www.swiggy.com/instamart/item/${item.optString("id")}",
                            available = item.optBoolean("in_stock", true)
                        ))
                    }
                } catch (e: Exception) {
                    log("Instamart: Error parsing item: ${e.message}")
                }
            }
            
            if (products.size >= 15) break
        }
        
        return products
    }

    private fun getPincodeCoordinates(pincode: String): Pair<String, String> {
        return when {
            pincode.startsWith("560") -> "12.9716" to "77.5946"   // Bangalore
            pincode.startsWith("110") -> "28.6139" to "77.2090"   // Delhi
            pincode.startsWith("400") -> "19.0760" to "72.8777"   // Mumbai
            pincode.startsWith("700") -> "22.5726" to "88.3639"   // Kolkata
            pincode.startsWith("600") -> "13.0827" to "80.2707"   // Chennai
            pincode.startsWith("500") -> "17.3850" to "78.4867"   // Hyderabad
            else -> "12.9716" to "77.5946" // Default to Bangalore
        }
    }

    private fun fetchSwiggyStoreId(lat: String, lng: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://www.swiggy.com/dapi/instamart/home?lat=$lat&lng=$lng")
                .apply {
                    COMMON_HEADERS.forEach { (k, v) -> header(k, v) }
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
                    header("Referer", "https://www.swiggy.com/instamart")
                }
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log("Instamart: storeId fetch HTTP ${response.code}")
                    return null
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null

                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                    ?: json.optJSONObject("response")?.optJSONObject("data")

                val storeId = data?.optString("storeId")
                    ?.takeIf { it.isNotBlank() }
                    ?: data?.optString("store_id")?.takeIf { it.isNotBlank() }
                    ?: data?.optJSONObject("instamart")?.optString("storeId")?.takeIf { it.isNotBlank() }
                    ?: data?.optJSONObject("store")?.optString("storeId")?.takeIf { it.isNotBlank() }

                if (storeId.isNullOrBlank()) {
                    log("Instamart: storeId not found in home payload")
                }

                storeId
            }
        } catch (e: Exception) {
            log("Instamart: storeId fetch error: ${e.message}")
            null
        }
    }
    
    // ==================== JIOMART API ====================
    private fun scrapeJioMartApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        fun buildRequest(url: String): Request {
            return Request.Builder()
                .url(url)
                .apply {
                    COMMON_HEADERS.forEach { (k, v) -> header(k, v) }
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    header("Referer", "https://www.jiomart.com/")
                    header("Cookie", "pincode=$pincode; city=Bangalore")
                }
                .get()
                .build()
        }

        val primaryResult = executeAndParseHtml(
            buildRequest("https://www.jiomart.com/catalogsearch/result/?q=$encodedQuery"),
            Platforms.JIOMART
        ) { html ->
            parseJioMartHtml(html)
        }

        if (primaryResult is ApiScrapeResult.Success) return primaryResult

        return executeAndParseHtml(
            buildRequest("https://www.jiomart.com/search/$encodedQuery"),
            Platforms.JIOMART
        ) { html ->
            parseJioMartHtml(html)
        }
    }
    
    private fun parseJioMartHtml(html: String): List<Product> {
        val products = mutableListOf<Product>()
        
        // Try to find __NEXT_DATA__ JSON
        val nextDataPattern = """<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val nextDataMatch = nextDataPattern.find(html)
        
        if (nextDataMatch != null) {
            try {
                val nextData = JSONObject(nextDataMatch.groupValues[1])
                val props = nextData.optJSONObject("props")?.optJSONObject("pageProps")
                val initialData = props?.optJSONObject("initialData") 
                    ?: props?.optJSONObject("data")
                    ?: return emptyList()
                
                val productList = initialData.optJSONArray("products") 
                    ?: initialData.optJSONObject("search")?.optJSONArray("products")
                    ?: return emptyList()
                
                for (i in 0 until minOf(productList.length(), 15)) {
                    val item = productList.getJSONObject(i)
                    val name = item.optString("name").ifEmpty { item.optString("productName") }
                    val price = item.optDouble("price", 0.0)
                        .takeIf { it > 0 }
                        ?: item.optJSONObject("pricing")?.optDouble("price", 0.0)
                        ?: 0.0
                    
                    if (name.isNotEmpty() && price > 0) {
                        products.add(Product(
                            name = name,
                            price = price,
                            originalPrice = item.optDouble("mrp").takeIf { it > price },
                            imageUrl = item.optString("imageUrl").ifEmpty { item.optString("image") },
                            platform = Platforms.JIOMART,
                            platformColor = Platforms.JIOMART_COLOR,
                            deliveryTime = "Same day",
                            url = "https://www.jiomart.com/p/${item.optString("id")}",
                            available = item.optBoolean("available", true)
                        ))
                    }
                }
            } catch (e: Exception) {
                log("JioMart: Error parsing __NEXT_DATA__: ${e.message}")
            }
        }
        
        // Fallback: regex-based extraction
        if (products.isEmpty()) {
            val pricePattern = """₹\s*([\d,]+(?:\.\d{2})?)""".toRegex()
            val productPattern = """<div[^>]*class="[^"]*plp-card[^"]*"[^>]*>.*?<h3[^>]*>([^<]+)</h3>.*?${pricePattern.pattern}""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            productPattern.findAll(html).take(15).forEach { match ->
                val name = match.groupValues[1].trim()
                val price = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: 0.0
                
                if (name.isNotEmpty() && price > 0) {
                    products.add(Product(
                        name = name,
                        price = price,
                        platform = Platforms.JIOMART,
                        platformColor = Platforms.JIOMART_COLOR,
                        deliveryTime = "Same day",
                        url = "https://www.jiomart.com/search/$name"
                    ))
                }
            }
        }
        
        return products
    }
    
    // ==================== AMAZON API ====================
    private fun scrapeAmazonApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val request = Request.Builder()
            .url("https://www.amazon.in/s?k=$encodedQuery&i=grocery")
            .apply {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                header("Accept", "text/html,application/xhtml+xml")
                header("Accept-Language", "en-IN,en;q=0.9")
                header("Cookie", "session-id=; pincode=$pincode")
            }
            .get()
            .build()
        
        return executeAndParseHtml(request, Platforms.AMAZON) { html ->
            parseAmazonHtml(html)
        }
    }
    
    private fun scrapeAmazonFreshApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val request = Request.Builder()
            .url("https://www.amazon.in/s?k=$encodedQuery&i=nowstore")
            .apply {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                header("Accept", "text/html,application/xhtml+xml")
                header("Accept-Language", "en-IN,en;q=0.9")
            }
            .get()
            .build()
        
        return executeAndParseHtml(request, Platforms.AMAZON_FRESH) { html ->
            parseAmazonHtml(html, isAmazonFresh = true)
        }
    }
    
    private fun parseAmazonHtml(html: String, isAmazonFresh: Boolean = false): List<Product> {
        val products = mutableListOf<Product>()
        val platform = if (isAmazonFresh) Platforms.AMAZON_FRESH else Platforms.AMAZON
        val platformColor = if (isAmazonFresh) Platforms.AMAZON_FRESH_COLOR else Platforms.AMAZON_COLOR
        val deliveryTime = if (isAmazonFresh) "2 hours" else "1-2 days"
        
        // Extract product data from Amazon HTML
        val productPattern = """data-asin="([A-Z0-9]+)"[^>]*>.*?<span[^>]*class="[^"]*a-text-normal[^"]*"[^>]*>([^<]+)</span>.*?<span[^>]*class="[^"]*a-price-whole[^"]*"[^>]*>([0-9,]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        productPattern.findAll(html).take(15).forEach { match ->
            val asin = match.groupValues[1]
            val name = match.groupValues[2].trim()
            val price = match.groupValues[3].replace(",", "").toDoubleOrNull() ?: 0.0
            
            if (name.isNotEmpty() && price > 0 && asin.isNotEmpty()) {
                val imagePattern = """data-asin="$asin"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val imageMatch = imagePattern.find(html)
                
                products.add(Product(
                    name = name,
                    price = price,
                    imageUrl = imageMatch?.groupValues?.getOrNull(1) ?: "",
                    platform = platform,
                    platformColor = platformColor,
                    deliveryTime = deliveryTime,
                    url = "https://www.amazon.in/dp/$asin",
                    available = true
                ))
            }
        }
        
        return products
    }
    
    // ==================== FLIPKART API ====================
    private fun scrapeFlipkartApi(query: String, pincode: String): ApiScrapeResult {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val request = Request.Builder()
            .url("https://www.flipkart.com/search?q=$encodedQuery&marketplace=GROCERY")
            .apply {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                header("Accept", "text/html,application/xhtml+xml")
                header("Cookie", "pincode=$pincode")
            }
            .get()
            .build()
        
        return executeAndParseHtml(request, Platforms.FLIPKART) { html ->
            parseFlipkartHtml(html)
        }
    }
    
    private fun scrapeFlipkartMinutesApi(query: String, pincode: String): ApiScrapeResult {
        // Flipkart Minutes uses the same API but with different parameters
        return scrapeFlipkartApi(query, pincode).let { result ->
            when (result) {
                is ApiScrapeResult.Success -> ApiScrapeResult.Success(
                    result.products.map { it.copy(
                        platform = Platforms.FLIPKART_MINUTES,
                        platformColor = Platforms.FLIPKART_MINUTES_COLOR,
                        deliveryTime = "15-30 mins"
                    )}
                )
                else -> result
            }
        }
    }
    
    private fun parseFlipkartHtml(html: String): List<Product> {
        val products = mutableListOf<Product>()
        
        // Try to find product data in script tags or HTML
        val pricePattern = """₹\s*([\d,]+)""".toRegex()
        val productBlockPattern = """<a[^>]*href="(/[^"]*pid=[^"]+)"[^>]*>.*?</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        // Alternative: look for _1AtVbE class (Flipkart product cards)
        val cardPattern = """<div[^>]*class="[^"]*_1AtVbE[^"]*"[^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        cardPattern.findAll(html).take(15).forEach { match ->
            val cardHtml = match.groupValues[1]
            
            // Extract name
            val namePattern = """<a[^>]*title="([^"]+)"[^>]*>|<div[^>]*class="[^"]*_4rR01T[^"]*"[^>]*>([^<]+)</div>""".toRegex()
            val nameMatch = namePattern.find(cardHtml)
            val name = nameMatch?.groupValues?.find { it.isNotEmpty() && it != nameMatch.value }?.trim() ?: return@forEach
            
            // Extract price
            val priceMatch = pricePattern.find(cardHtml)
            val price = priceMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return@forEach
            
            // Extract URL
            val urlPattern = """href="(/[^"]*)"[^>]*""".toRegex()
            val urlMatch = urlPattern.find(cardHtml)
            val url = urlMatch?.groupValues?.get(1)?.let { "https://www.flipkart.com$it" } ?: ""
            
            // Extract image
            val imagePattern = """src="(https://[^"]+\.(?:jpg|png|webp)[^"]*)"[^>]*""".toRegex()
            val imageMatch = imagePattern.find(cardHtml)
            val imageUrl = imageMatch?.groupValues?.get(1) ?: ""
            
            if (name.isNotEmpty() && price > 0) {
                products.add(Product(
                    name = name,
                    price = price,
                    imageUrl = imageUrl,
                    platform = Platforms.FLIPKART,
                    platformColor = Platforms.FLIPKART_COLOR,
                    deliveryTime = "1-2 days",
                    url = url,
                    available = true
                ))
            }
        }
        
        return products
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun executeAndParse(
        request: Request,
        platform: String,
        parser: (JSONObject) -> List<Product>
    ): ApiScrapeResult {
        return try {
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                log("$platform: HTTP ${response.code}")
                return ApiScrapeResult.Failure("HTTP ${response.code}")
            }
            
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                return ApiScrapeResult.Failure("Empty response")
            }
            
            // Try to parse as JSON
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                log("$platform: Not JSON, trying as HTML")
                return ApiScrapeResult.Failure("Not JSON response")
            }
            
            val products = parser(json)
            
            if (products.isEmpty()) {
                ApiScrapeResult.NoProducts
            } else {
                log("$platform: ✓ API extracted ${products.size} products")
                ApiScrapeResult.Success(products)
            }
        } catch (e: Exception) {
            log("$platform: API error: ${e.message}")
            ApiScrapeResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    private fun executeAndParseHtml(
        request: Request,
        platform: String,
        parser: (String) -> List<Product>
    ): ApiScrapeResult {
        return try {
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                log("$platform: HTTP ${response.code}")
                return ApiScrapeResult.Failure("HTTP ${response.code}")
            }
            
            val html = response.body?.string()
            if (html.isNullOrBlank() || html.length < 1000) {
                return ApiScrapeResult.Failure("Empty or too short HTML")
            }
            
            val products = parser(html)
            
            if (products.isEmpty()) {
                ApiScrapeResult.NoProducts
            } else {
                log("$platform: ✓ HTML extracted ${products.size} products")
                ApiScrapeResult.Success(products)
            }
        } catch (e: Exception) {
            log("$platform: HTML scrape error: ${e.message}")
            ApiScrapeResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    private fun log(message: String) {
        println("$TAG: $message")
    }
}

sealed class ApiScrapeResult {
    data class Success(val products: List<Product>) : ApiScrapeResult()
    data class Failure(val reason: String) : ApiScrapeResult()
    object NoProducts : ApiScrapeResult()
    data class NotSupported(val platform: String) : ApiScrapeResult()
}
