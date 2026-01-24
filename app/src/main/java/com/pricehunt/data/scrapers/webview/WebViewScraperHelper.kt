package com.pricehunt.data.scrapers.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for WebView-based scraping.
 * Uses a headless WebView to render JavaScript-heavy pages and extract product data.
 * 
 * Anti-bot bypass techniques:
 * 1. Proper location cookies with real pincode
 * 2. WebView fingerprint spoofing (navigator properties)
 * 3. Chrome-like User-Agent without WebView markers
 * 4. Canvas/WebGL fingerprint randomization
 */
@Singleton
class WebViewScraperHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L  // Increased for slow networks
        private const val PAGE_LOAD_DELAY_MS = 15000L   // Increased for heavy client-side rendering
        
        // Default location: Bangalore (560081)
        private const val DEFAULT_PINCODE = "560081"
        private const val DEFAULT_LAT = "12.9352"
        private const val DEFAULT_LON = "77.6245"
        private const val DEFAULT_CITY = "Bangalore"
        
        // Real Chrome User-Agent (not WebView)
        private val CHROME_USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.143 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.101 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; OnePlus 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
        )
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPincode = DEFAULT_PINCODE
    private var currentLat = DEFAULT_LAT
    private var currentLon = DEFAULT_LON
    
    /**
     * Set the location for scraping (called from scrapers with user's pincode)
     */
    fun setLocation(pincode: String, lat: String = DEFAULT_LAT, lon: String = DEFAULT_LON) {
        currentPincode = pincode.ifBlank { DEFAULT_PINCODE }
        currentLat = lat
        currentLon = lon
        println("WebView: Location set to pincode=$currentPincode, lat=$currentLat, lon=$currentLon")
    }
    
    /**
     * JavaScript to inject BEFORE page loads to spoof WebView fingerprint.
     * This makes the WebView appear as a real Chrome browser.
     */
    private val ANTI_DETECTION_SCRIPT = """
        (function() {
            // Override webdriver detection
            Object.defineProperty(navigator, 'webdriver', {
                get: () => false,
                configurable: true
            });
            
            // Remove automation indicators
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
            
            // Override plugins (WebView has none, Chrome has some)
            Object.defineProperty(navigator, 'plugins', {
                get: () => {
                    const plugins = {
                        0: { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                        1: { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                        2: { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' },
                        length: 3,
                        item: function(i) { return this[i]; },
                        namedItem: function(name) { 
                            for (let i = 0; i < this.length; i++) {
                                if (this[i].name === name) return this[i];
                            }
                            return null;
                        },
                        refresh: function() {}
                    };
                    return plugins;
                },
                configurable: true
            });
            
            // Override languages
            Object.defineProperty(navigator, 'languages', {
                get: () => ['en-IN', 'en-US', 'en'],
                configurable: true
            });
            
            // Override platform
            Object.defineProperty(navigator, 'platform', {
                get: () => 'Linux armv8l',
                configurable: true
            });
            
            // Override hardware concurrency
            Object.defineProperty(navigator, 'hardwareConcurrency', {
                get: () => 8,
                configurable: true
            });
            
            // Override device memory
            Object.defineProperty(navigator, 'deviceMemory', {
                get: () => 8,
                configurable: true
            });
            
            // Override maxTouchPoints
            Object.defineProperty(navigator, 'maxTouchPoints', {
                get: () => 5,
                configurable: true
            });
            
            // Override connection
            if (navigator.connection) {
                Object.defineProperty(navigator.connection, 'effectiveType', {
                    get: () => '4g',
                    configurable: true
                });
            }
            
            // Override geolocation to return Bangalore coordinates
            if (navigator.geolocation) {
                const fakePosition = {
                    coords: {
                        latitude: 12.9716,
                        longitude: 77.5946,
                        accuracy: 100,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: Date.now()
                };
                
                navigator.geolocation.getCurrentPosition = function(success, error, options) {
                    console.log('Geolocation: Returning fake position');
                    setTimeout(() => success(fakePosition), 100);
                };
                
                navigator.geolocation.watchPosition = function(success, error, options) {
                    console.log('Geolocation: Returning fake watch position');
                    setTimeout(() => success(fakePosition), 100);
                    return 1;
                };
                
                navigator.geolocation.clearWatch = function(id) {};
            }
            
            // Override permissions query for notifications
            const originalQuery = window.Notification && Notification.requestPermission;
            if (originalQuery) {
                Notification.requestPermission = () => Promise.resolve('default');
            }
            
            // Canvas fingerprint randomization
            const originalGetContext = HTMLCanvasElement.prototype.getContext;
            HTMLCanvasElement.prototype.getContext = function(type, attributes) {
                const context = originalGetContext.call(this, type, attributes);
                if (type === '2d' && context) {
                    const originalGetImageData = context.getImageData;
                    context.getImageData = function(sx, sy, sw, sh) {
                        const imageData = originalGetImageData.call(this, sx, sy, sw, sh);
                        // Add subtle noise to prevent fingerprinting
                        for (let i = 0; i < imageData.data.length; i += 4) {
                            imageData.data[i] = Math.max(0, Math.min(255, imageData.data[i] + (Math.random() * 2 - 1)));
                        }
                        return imageData;
                    };
                }
                return context;
            };
            
            // WebGL fingerprint protection
            const getParameterProxyHandler = {
                apply: function(target, thisArg, args) {
                    const param = args[0];
                    const result = Reflect.apply(target, thisArg, args);
                    // Mask specific WebGL parameters
                    if (param === 37445) return 'Google Inc. (Qualcomm)'; // UNMASKED_VENDOR_WEBGL
                    if (param === 37446) return 'ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)'; // UNMASKED_RENDERER_WEBGL
                    return result;
                }
            };
            
            try {
                const canvas = document.createElement('canvas');
                const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                if (gl) {
                    gl.getParameter = new Proxy(gl.getParameter, getParameterProxyHandler);
                }
                const gl2 = canvas.getContext('webgl2');
                if (gl2) {
                    gl2.getParameter = new Proxy(gl2.getParameter, getParameterProxyHandler);
                }
            } catch(e) {}
            
            // Override automation detection
            Object.defineProperty(window, 'chrome', {
                value: {
                    runtime: {
                        connect: function() {},
                        sendMessage: function() {}
                    },
                    loadTimes: function() { return {}; },
                    csi: function() { return {}; },
                    app: {}
                },
                configurable: true
            });
            
            // Prevent Cloudflare turnstile detection
            Object.defineProperty(document, 'hidden', {
                get: () => false,
                configurable: true
            });
            
            Object.defineProperty(document, 'visibilityState', {
                get: () => 'visible',
                configurable: true
            });
            
            console.log('Anti-detection script injected successfully');
        })();
    """.trimIndent()
    
    /**
     * Set comprehensive location cookies for all platforms.
     * Uses the user's pincode (560081 by default).
     */
    private fun setLocationCookies(url: String, pincode: String = currentPincode) {
        val cookieManager = CookieManager.getInstance()
        val domain = url.substringAfter("://").substringBefore("/")
        val baseDomain = domain.replace("www.", "")
        
        // Clear old cookies first
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        
        // Common cookies for all platforms
        val commonCookies = listOf(
            "pincode=$pincode",
            "lat=$currentLat",
            "lon=$currentLon",
            "lng=$currentLon",
            "city=$DEFAULT_CITY",
            "location_set=true",
            "user_location={\"lat\":$currentLat,\"lon\":$currentLon,\"pincode\":\"$pincode\",\"city\":\"$DEFAULT_CITY\"}"
        )
        
        // Set for both www and non-www
        listOf(domain, baseDomain, ".$baseDomain").forEach { d ->
            commonCookies.forEach { cookie ->
                cookieManager.setCookie(d, "$cookie; path=/; SameSite=Lax")
            }
        }
        
        // Platform-specific cookies
        when {
            "blinkit.com" in domain -> {
                val blinkitCookies = listOf(
                    "gr_1_lat=$currentLat",
                    "gr_1_lon=$currentLon",
                    "gr_1_locality=Bangalore",
                    "gr_1_address_id=default_address",
                    "serviceability=1",
                    "_gr_lat=$currentLat",
                    "_gr_lon=$currentLon",
                    "address=%7B%22lat%22%3A$currentLat%2C%22lon%22%3A$currentLon%2C%22pincode%22%3A%22$pincode%22%7D",
                    "user_pincode=$pincode",
                    "blinkit_lat=$currentLat",
                    "blinkit_lon=$currentLon"
                )
                blinkitCookies.forEach { cookie ->
                    cookieManager.setCookie(domain, "$cookie; path=/; SameSite=Lax")
                }
            }
            "zepto" in domain || "zeptonow.com" in domain -> {
                val zeptoCookies = listOf(
                    "latitude=$currentLat",
                    "longitude=$currentLon",
                    "storeId=default_store",
                    "serviceability_pincode=$pincode",
                    "user_pincode=$pincode",
                    "zepto_lat=$currentLat",
                    "zepto_lon=$currentLon",
                    "address_id=default",
                    "delivery_location={\"lat\":$currentLat,\"lng\":$currentLon,\"pincode\":\"$pincode\"}"
                )
                zeptoCookies.forEach { cookie ->
                    cookieManager.setCookie(domain, "$cookie; path=/; SameSite=Lax")
                }
            }
            "bigbasket.com" in domain -> {
                val bbCookies = listOf(
                    "ts=pincode~$pincode",
                    "_bb_locSrc=default",
                    "_bb_loid=default_loc",
                    "bb_pincode=$pincode",
                    "bb_lat=$currentLat",
                    "bb_lon=$currentLon",
                    "ufi=eyJhcyI6eyJsYXQiOiIkcurrentLat\",\"lon\":\"$currentLon\"}}",
                    "x-entry-context-id=100",
                    "x-entry-context=bb-b2c"
                )
                bbCookies.forEach { cookie ->
                    cookieManager.setCookie(domain, "$cookie; path=/; SameSite=Lax")
                }
            }
            "swiggy.com" in domain -> {
                // Swiggy/Instamart requires comprehensive location data
                val sessionId = java.util.UUID.randomUUID().toString()
                val swiggyCookies = listOf(
                    // Core location cookies
                    "lat=$currentLat",
                    "lng=$currentLon",
                    "pincode=$pincode",
                    // Swiggy-specific location format
                    "userLocation={\"lat\":$currentLat,\"lng\":$currentLon,\"address\":\"$DEFAULT_CITY\",\"area\":\"$DEFAULT_CITY\",\"city\":\"$DEFAULT_CITY\",\"pincode\":\"$pincode\"}",
                    "swiggy_lat=$currentLat",
                    "swiggy_lng=$currentLon",
                    // Session cookies
                    "_guest_tid=$sessionId",
                    "swgy_uuid=$sessionId",
                    "_sid=$sessionId",
                    // Instamart attribution
                    "imOrderAttribution=instamart",
                    "address_id=default",
                    // Location flags
                    "isLocationSet=true",
                    "locationPromptShown=true",
                    "addressSelected=true",
                    // Prevent location prompts
                    "showLocationPopup=false",
                    "locationAccessDenied=false"
                )
                swiggyCookies.forEach { cookie ->
                    cookieManager.setCookie(domain, "$cookie; path=/; SameSite=Lax; Secure")
                    cookieManager.setCookie(".$domain", "$cookie; path=/; SameSite=Lax; Secure")
                }
                // Also set for www subdomain
                cookieManager.setCookie("www.swiggy.com", "lat=$currentLat; path=/")
                cookieManager.setCookie("www.swiggy.com", "lng=$currentLon; path=/")
                println("WebView: Set comprehensive Swiggy cookies for pincode=$pincode")
            }
            "jiomart.com" in domain -> {
                val jiomartCookies = listOf(
                    "pincode=$pincode",
                    "lat=$currentLat",
                    "long=$currentLon",
                    "city=$DEFAULT_CITY",
                    "jm_pincode=$pincode",
                    "jm_lat=$currentLat",
                    "jm_lon=$currentLon",
                    "user_location={\"pincode\":\"$pincode\",\"lat\":$currentLat,\"lon\":$currentLon}",
                    "delivery_pincode=$pincode",
                    "store_id=default"
                )
                jiomartCookies.forEach { cookie ->
                    cookieManager.setCookie(domain, "$cookie; path=/; SameSite=Lax")
                }
            }
            "flipkart.com" in domain -> {
                // Flipkart grocery/minutes requires pincode for delivery
                val flipkartCookies = listOf(
                    "pincode=$pincode",
                    "vw=1",
                    "T=TI${System.currentTimeMillis()}",
                    "rt=null",
                    "ud=1.${java.util.UUID.randomUUID()}",
                    "AMCV_17EB401053DAF4840A490D4C%40AdobeOrg=-1124106680%7CMCIDTS%7C19746%7CMCMID%7C${java.util.UUID.randomUUID()}",
                    "at=ZXlKMWMyVnlTV1FpT2lKVlUwVlNJaXdpYzI5MWNtTmxJam9pUjFWRlUxUWlmUT09",
                    "SN=${java.util.UUID.randomUUID()}",
                    "K-ACTION=null",
                    "gpv_pn=grocery-supermart-store",
                    "gpv_pn_t=GROCERY",
                    "s_fid=${java.util.UUID.randomUUID()}-${java.util.UUID.randomUUID()}",
                    "s_cc=true",
                    "s_sq=%5B%5BB%5D%5D",
                    "Network-Type=4g",
                    "AMCVS_17EB401053DAF4840A490D4C%40AdobeOrg=1",
                    "location_pincode=$pincode",
                    "delivery_pincode=$pincode",
                    "user_pincode=$pincode",
                    "grocery_pincode=$pincode"
                )
                flipkartCookies.forEach { cookie ->
                    cookieManager.setCookie(domain, "$cookie; path=/; SameSite=Lax")
                }
                // Also set for grocery subdomain
                cookieManager.setCookie("grocery.flipkart.com", "pincode=$pincode; path=/; SameSite=Lax")
                cookieManager.setCookie("grocery.flipkart.com", "delivery_pincode=$pincode; path=/; SameSite=Lax")
            }
        }
        
        cookieManager.flush()
        println("WebView: Set location cookies for $domain with pincode=$pincode")
    }
    
    /**
     * Load a URL in WebView and extract the page HTML after JavaScript execution.
     */
    suspend fun loadAndGetHtml(
        url: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        waitForSelector: String? = null,
        pincode: String = currentPincode
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<String?>()
            
            val webView = createWebView()
            var antiDetectionInjected = false
            
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Inject anti-detection script BEFORE page loads
                    if (!antiDetectionInjected) {
                        view?.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
                        antiDetectionInjected = true
                        println("WebView: Anti-detection script injected on page start")
                    }
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // Re-inject anti-detection in case page cleared it
                    view?.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
                    
                    // Wait a bit for JavaScript to execute, then extract HTML
                    mainHandler.postDelayed({
                        if (waitForSelector != null) {
                            // Wait for specific element to appear
                            waitForElement(webView, waitForSelector) { found ->
                                if (found) {
                                    extractHtml(webView, result)
                                } else {
                                    // Try extracting anyway after timeout
                                    extractHtml(webView, result)
                                }
                            }
                        } else {
                            extractHtml(webView, result)
                        }
                    }, PAGE_LOAD_DELAY_MS)
                }
                
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
                
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    // Handle SSL errors gracefully (some platforms have certificate issues)
                    handler?.proceed()
                }
                
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    println("WebView: Error loading ${request?.url}: ${error?.description}")
                }
            }
            
            try {
                // Set location cookies before loading
                setLocationCookies(url, pincode)
                webView.loadUrl(url)
            } catch (e: Exception) {
                println("WebView: Error loading URL: ${e.message}")
                result.complete(null)
            }
            
            val html = result.await()
            
            // Cleanup
            mainHandler.post {
                webView.stopLoading()
                webView.destroy()
            }
            
            html
        }
    }
    
    /**
     * Load a URL and execute custom JavaScript to extract data.
     */
    suspend fun loadAndExecuteJs(
        url: String,
        jsScript: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        customHeaders: Map<String, String> = emptyMap(),
        pincode: String = currentPincode
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<String?>()
            
            val webView = createWebView()
            var antiDetectionInjected = false
            
            // Add JavaScript interface to receive results
            val jsInterface = JsResultInterface { jsResult ->
                result.complete(jsResult)
            }
            webView.addJavascriptInterface(jsInterface, "AndroidScraper")
            
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    println("WebView [loadAndExecuteJs]: Page started loading: $url")
                    // Inject anti-detection script BEFORE page loads
                    if (!antiDetectionInjected) {
                        view?.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
                        antiDetectionInjected = true
                        println("WebView [loadAndExecuteJs]: Anti-detection script injected on page start")
                    }
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    println("WebView [loadAndExecuteJs]: Page finished loading: $url")
                    
                    // Re-inject anti-detection in case page cleared it
                    view?.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
                    
                    // First, dump the HTML to see what we're actually getting
                    mainHandler.postDelayed({
                        view?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            val cleanHtml = html?.let {
                                if (it.startsWith("\"") && it.endsWith("\"")) {
                                    it.substring(1, it.length - 1)
                                        .replace("\\n", "\n")
                                        .replace("\\\"", "\"")
                                } else it
                            }
                            println("WebView: Loaded HTML sample (first 1500 chars): ${cleanHtml?.take(1500)}")
                            
                            // Check for common blocking indicators
                            cleanHtml?.lowercase()?.let { lowerHtml ->
                                if (lowerHtml.contains("access denied")) println("WebView: ⚠️ ACCESS DENIED detected")
                                if (lowerHtml.contains("captcha")) println("WebView: ⚠️ CAPTCHA detected")
                                if (lowerHtml.contains("blocked")) println("WebView: ⚠️ BLOCKED detected")
                                if (lowerHtml.contains("cloudflare")) println("WebView: ⚠️ CLOUDFLARE detected")
                                if (lowerHtml.contains("just a moment")) println("WebView: ⚠️ Cloudflare challenge detected")
                                if (lowerHtml.contains("robot")) println("WebView: ⚠️ Robot detection detected")
                                if (lowerHtml.contains("verify")) println("WebView: ⚠️ Verification required")
                            }
                        }
                    }, 2000)
                    
                    // Wait for page to fully render, then execute script
                    mainHandler.postDelayed({
                        val wrappedScript = """
                            (function() {
                                try {
                                    console.log('WebView: Starting extraction for', window.location.href);
                                    console.log('WebView: Document title:', document.title);
                                    console.log('WebView: Body length:', document.body.innerHTML.length);
                                    console.log('WebView: Checking for products...');
                                    
                                    // Check for blocking indicators
                                    var bodyText = document.body.innerText.toLowerCase();
                                    if (bodyText.includes('access denied') || bodyText.includes('blocked') || 
                                        bodyText.includes('captcha') || bodyText.includes('just a moment')) {
                                        console.log('WebView: ⚠️ Page appears to be blocked or requires verification');
                                    }
                                    
                                    var extractedResult = (function() { $jsScript })();
                                    console.log('WebView: Extracted products:', extractedResult ? extractedResult.length : 0);
                                    if (extractedResult && extractedResult.length > 0) {
                                        console.log('WebView: First product:', JSON.stringify(extractedResult[0]));
                                    }
                                    return JSON.stringify(extractedResult || []);
                                } catch(e) {
                                    console.log('WebView: Extraction error:', e.message, e.stack);
                                    return JSON.stringify([]);
                                }
                            })();
                        """.trimIndent()
                        
                        // Use evaluateJavascript callback instead of JS interface
                        view?.evaluateJavascript(wrappedScript) { jsResult ->
                            println("WebView: evaluateJavascript callback received: ${jsResult?.take(100)}")
                            
                            // The result comes wrapped in quotes and escaped
                            val cleanResult = jsResult?.let {
                                if (it == "null" || it.isBlank()) {
                                    null
                                } else if (it.startsWith("\"") && it.endsWith("\"")) {
                                    // Unescape the JSON string
                                    it.substring(1, it.length - 1)
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")
                                } else {
                                    it
                                }
                            }
                            
                            println("WebView: Clean result: ${cleanResult?.take(100)}")
                            
                            if (!result.isCompleted) {
                                result.complete(cleanResult)
                            }
                        }
                        
                        // Safety timeout in case evaluateJavascript callback never fires
                        mainHandler.postDelayed({
                            if (!result.isCompleted) {
                                println("WebView: Extraction callback timeout - completing with null")
                                result.complete(null)
                            }
                        }, 10000) // 10 second timeout for extraction
                    }, PAGE_LOAD_DELAY_MS)
                }
                
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }
                
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    println("WebView: Error loading ${request?.url}: ${error?.description}")
                }
            }
            
            try {
                // Set location cookies before loading
                setLocationCookies(url, pincode)
                
                // Load with custom headers if provided
                if (customHeaders.isNotEmpty()) {
                    webView.loadUrl(url, customHeaders)
                } else {
                    webView.loadUrl(url)
                }
                
                // Safety timeout is handled in onPageFinished callback
                // Add an absolute max timeout in case page never loads
                mainHandler.postDelayed({
                    if (!result.isCompleted) {
                        println("WebView: Absolute timeout (40s) - page never loaded, completing with null")
                        result.complete(null)
                    }
                }, 40000)  // 40 seconds absolute max
                
            } catch (e: Exception) {
                println("WebView: Error loading URL: ${e.message}")
                result.complete(null)
            }
            
            val jsResult = result.await()
            
            // Cleanup
            mainHandler.post {
                webView.removeJavascriptInterface("AndroidScraper")
                webView.stopLoading()
                webView.destroy()
            }
            
            jsResult
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        // Select a random User-Agent to avoid fingerprinting
        val userAgent = CHROME_USER_AGENTS.random()
        
        return WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                
                // Use NO_CACHE to avoid serving stale blocked pages
                cacheMode = WebSettings.LOAD_NO_CACHE
                
                // Use a REAL Chrome User-Agent (critical - no "wv" or "WebView" markers)
                // The default WebView UA contains "; wv)" which is detected by anti-bot systems
                userAgentString = userAgent
                
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
                
                // Enable images - some sites check if images are loaded
                blockNetworkImage = false
                loadsImagesAutomatically = true
                
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Enable geolocation
                setGeolocationEnabled(true)
                
                // Allow file access (some sites check this)
                allowFileAccess = true
                allowContentAccess = true
                
                // Modern browser settings
                mediaPlaybackRequiresUserGesture = false
                
                // Set safe browsing to false to avoid Google checks
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }
            
            // Enable cookies with proper settings
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            
            // Enable console logging for debugging
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        println("WebView Console: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                    }
                    return true
                }
                
                // Handle geolocation permission
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: android.webkit.GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }
            
            println("WebView: Created with User-Agent: $userAgent")
        }
    }
    
    private fun extractHtml(webView: WebView, result: CompletableDeferred<String?>) {
        webView.evaluateJavascript(
            "(function() { return document.documentElement.outerHTML; })();"
        ) { html ->
            // The result comes back as a JSON-escaped string
            val cleanHtml = html?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    it.substring(1, it.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\u003C", "<")
                        .replace("\\u003E", ">")
                } else {
                    it
                }
            }
            result.complete(cleanHtml)
        }
    }
    
    private fun waitForElement(
        webView: WebView,
        selector: String,
        maxAttempts: Int = 10,
        callback: (Boolean) -> Unit
    ) {
        var attempts = 0
        
        fun check() {
            webView.evaluateJavascript(
                "(function() { return document.querySelector('$selector') !== null; })();"
            ) { result ->
                attempts++
                if (result == "true") {
                    callback(true)
                } else if (attempts < maxAttempts) {
                    mainHandler.postDelayed({ check() }, 500)
                } else {
                    callback(false)
                }
            }
        }
        
        check()
    }
    
    /**
     * JavaScript interface for receiving results from WebView.
     */
    private class JsResultInterface(private val onResult: (String?) -> Unit) {
        @JavascriptInterface
        fun onResult(result: String?) {
            onResult.invoke(result)
        }
    }
}

/**
 * JavaScript extraction scripts for different platforms.
 * Updated to match actual 2024/2025 HTML structures.
 */
object WebViewExtractionScripts {
    
    /**
     * Generic script to extract product data from a search results page.
     */
    val GENERIC_PRODUCT_EXTRACTION = """
        var products = [];
        
        // Try multiple common selectors
        var selectors = [
            '[class*="product-card"]', '[class*="ProductCard"]', '[class*="productCard"]',
            '[data-testid*="product"]', '[class*="item-card"]', '[class*="ItemCard"]',
            'a[href*="/product"]', 'a[href*="/p/"]', 'a[href*="/pd/"]'
        ];
        
        for (var s = 0; s < selectors.length && products.length === 0; s++) {
            var cards = document.querySelectorAll(selectors[s]);
            cards.forEach(function(card, index) {
                if (index >= 10 || products.length >= 10) return;
                try {
                    var name = card.querySelector('h3, h4, h5, [class*="name"], [class*="title"], [class*="Name"], [class*="Title"]');
                    var priceMatch = card.textContent.match(/₹\s*([\d,]+(?:\.\d{2})?)/);
                    var img = card.querySelector('img');
                    var link = card.tagName === 'A' ? card : card.querySelector('a');
                    
                    if (name && priceMatch) {
                        products.push({
                            name: name.textContent.trim().substring(0, 100),
                            price: parseFloat(priceMatch[1].replace(/,/g, '')),
                            imageUrl: img ? (img.src || img.dataset.src) : null,
                            url: link ? link.href : null
                        });
                    }
                } catch(e) {}
            });
        }
        
        return products;
    """.trimIndent()
    
    /**
     * Blinkit-specific extraction script - Updated for 2025 structure.
     * Uses aggressive DOM scraping with multiple fallback strategies.
     */
    val BLINKIT_EXTRACTION = """
        var products = [];
        console.log('Blinkit: Starting extraction...');
        console.log('Blinkit: URL:', window.location.href);
        console.log('Blinkit: Title:', document.title);
        console.log('Blinkit: Body length:', document.body.innerHTML.length);
        
        // Debug: Log some page content
        var bodyText = document.body.innerText.substring(0, 500);
        console.log('Blinkit: Body preview:', bodyText.substring(0, 200));
        
        // Check if we're on a location selection page
        if (bodyText.toLowerCase().includes('select location') || 
            bodyText.toLowerCase().includes('enter your address') ||
            bodyText.toLowerCase().includes('detect my location')) {
            console.log('Blinkit: Appears to be location selection page');
        }
        
        // Method 1: Find all links with /prn/ (Blinkit product links)
        var productLinks = document.querySelectorAll('a[href*="/prn/"]');
        console.log('Blinkit: Found', productLinks.length, '/prn/ links');
        
        var seenNames = {};
        productLinks.forEach(function(link, idx) {
            if (products.length >= 15) return;
            try {
                var text = link.textContent.trim();
                
                // Try to find price in link or parent
                var priceMatch = text.match(/₹\s*([\d,]+)/);
                if (!priceMatch) {
                    var parent = link.parentElement;
                    if (parent) {
                        text = parent.textContent.trim();
                        priceMatch = text.match(/₹\s*([\d,]+)/);
                    }
                }
                if (!priceMatch) return;
                
                var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                if (price <= 0 || price > 50000) return;
                
                var name = text.split('₹')[0].trim();
                name = name.replace(/\s*(ADD|Add|add|\+|−)\s*$/i, '').trim();
                name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack|unit|pieces?|gm)s?\s*$/i, '').trim();
                name = name.replace(/\s+/g, ' ').trim();
                
                if (name.length < 3 || name.length > 120 || seenNames[name]) return;
                seenNames[name] = true;
                
                var img = link.querySelector('img') || link.parentElement?.querySelector('img');
                
                products.push({
                    name: name.substring(0, 100),
                    price: price,
                    imageUrl: img ? (img.src || img.dataset.src) : null,
                    url: link.href
                });
                console.log('Blinkit: Added from link:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('Blinkit: Link error:', e.message); }
        });
        
        // Method 2: Generic element search for price patterns
        if (products.length === 0) {
            console.log('Blinkit: Trying generic extraction...');
            var allElements = document.querySelectorAll('div, a, article');
            
            allElements.forEach(function(el) {
                if (products.length >= 15) return;
                var text = el.textContent || '';
                
                // Must have price and be reasonably sized
                if (!text.match(/₹\s*\d+/) || text.length > 400 || text.length < 10) return;
                
                // Skip containers with too many prices
                var priceCount = (text.match(/₹/g) || []).length;
                if (priceCount > 3) return;
                
                var hasImg = el.querySelector('img');
                if (!hasImg && !el.closest('a')) return;
                
                try {
                    var priceMatch = text.match(/₹\s*([\d,]+)/);
                    if (!priceMatch) return;
                    
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price <= 0 || price > 50000) return;
                    
                    var name = text.split('₹')[0].trim();
                    name = name.replace(/\s*(ADD|Add|add|\+|−)\s*$/i, '').trim();
                    name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack|unit|pieces?|gm)s?\s*$/i, '').trim();
                    name = name.replace(/\s+/g, ' ').trim();
                    
                    if (name.length < 3 || name.length > 120 || seenNames[name]) return;
                    seenNames[name] = true;
                    
                    var img = el.querySelector('img') || el.closest('a')?.querySelector('img');
                    var link = el.closest('a') || el.querySelector('a');
                    
                    products.push({
                        name: name.substring(0, 100),
                        price: price,
                        imageUrl: img ? (img.src || img.dataset.src) : null,
                        url: link ? link.href : window.location.href
                    });
                    console.log('Blinkit: Generic added:', name.substring(0, 30), '₹' + price);
                } catch(e) {}
            });
        }
        
        // Method 3: Fallback - scan body text for product patterns
        if (products.length === 0) {
            console.log('Blinkit: Trying text pattern extraction...');
            var lines = document.body.innerText.split('\n');
            var currentName = '';
            
            lines.forEach(function(line) {
                if (products.length >= 10) return;
                line = line.trim();
                
                var priceMatch = line.match(/₹\s*([\d,]+)/);
                if (priceMatch && currentName.length >= 5 && currentName.length <= 100) {
                    // Skip if name looks like just a quantity (e.g., "450 ml", "1 l")
                    if (currentName.match(/^\d+\s*(g|kg|ml|l|pcs|pack|gm|x\s*\d+)s?$/i)) {
                        currentName = '';
                        return;
                    }
                    
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price > 0 && price < 10000 && !seenNames[currentName]) {
                        seenNames[currentName] = true;
                        products.push({
                            name: currentName,
                            price: price,
                            imageUrl: null,
                            url: window.location.href
                        });
                        console.log('Blinkit: Text pattern added:', currentName.substring(0, 30), '₹' + price);
                    }
                    currentName = '';
                } else if (line.length >= 5 && line.length <= 100 && !line.match(/₹/) && !line.match(/^\d+$/) && !line.match(/^\d+\s*(g|kg|ml|l|pcs|pack|gm)s?$/i)) {
                    // Only accept lines that look like product names (not quantities)
                    currentName = line;
                }
            });
        }
        
        console.log('Blinkit: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * BigBasket-specific extraction script - Updated for 2024/2025 structure.
     * BigBasket uses Next.js with dynamic rendering.
     */
    val BIGBASKET_EXTRACTION = """
        var products = [];
        console.log('BigBasket: Starting extraction...');
        
        // Debug: Log page structure
        console.log('BigBasket: URL:', window.location.href);
        console.log('BigBasket: Title:', document.title);
        
        // Method 1: Find elements containing both image and price (product cards)
        var allDivs = document.querySelectorAll('div, li, article, a');
        var potentialProducts = [];
        
        allDivs.forEach(function(el) {
            var text = el.textContent || '';
            // Look for elements with price that aren't too large
            if (text.match(/₹\s*\d+/) && text.length < 600 && text.length > 5) {
                var hasImg = el.querySelector('img');
                if (hasImg || el.closest('a')) {
                    potentialProducts.push(el);
                }
            }
        });
        
        console.log('BigBasket: Found', potentialProducts.length, 'potential product elements');
        
        // Log some selectors for debugging
        var selectors = [
            'li[qa="product"]',
            '[data-qa="product"]',
            'a[href*="/pd/"]',
            'a[href*="/ps/"]',
            'div[class*="product"]',
            'div[class*="Product"]',
            'div[class*="item"]'
        ];
        selectors.forEach(function(sel) {
            var found = document.querySelectorAll(sel);
            if (found.length > 0) console.log('BigBasket: Selector', sel, 'found', found.length);
        });
        
        // Method 2: Extract from potential products - filter out containers
        var seenNames = {};
        potentialProducts.forEach(function(el, index) {
            if (products.length >= 15) return;
            try {
                var text = el.textContent.trim();
                
                // Skip if this element contains too many prices (it's a container)
                var allPrices = text.match(/₹\s*[\d,]+/g) || [];
                if (allPrices.length > 4) return;
                
                // Extract price
                var priceMatches = text.match(/₹\s*([\d,]+(?:\.\d{2})?)/g);
                if (!priceMatches || priceMatches.length === 0) return;
                
                var price = parseFloat(priceMatches[0].replace(/[₹,\s]/g, ''));
                if (price <= 0 || price > 50000) return;
                
                // Extract name - text before price
                var parts = text.split(/₹/);
                var name = parts[0].trim();
                
                // Clean name
                name = name.replace(/\s*(ADD|Add|add|ADDED|\+|−|Out of Stock|out of stock)\s*$/i, '').trim();
                name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack|unit|pieces?)s?\s*$/i, '').trim();
                name = name.replace(/\s+/g, ' ').trim();
                
                if (name.length < 3 || name.length > 150) return;
                
                // Skip duplicates
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                // Get image
                var img = el.querySelector('img');
                if (!img) img = el.closest('a')?.querySelector('img');
                if (!img) img = el.parentElement?.querySelector('img');
                var imageUrl = img ? (img.src || img.dataset.src || img.dataset.original) : null;
                
                // Skip if image is a logo/icon
                if (imageUrl && (imageUrl.includes('logo') || imageUrl.includes('icon') || imageUrl.includes('sprite'))) {
                    imageUrl = null;
                }
                
                // Get link
                var link = el.closest('a') || el.querySelector('a[href*="/pd/"]') || el.querySelector('a');
                var url = link ? link.href : window.location.href;
                
                // Get MRP (usually second price, should be higher)
                var mrp = null;
                if (priceMatches.length > 1) {
                    mrp = parseFloat(priceMatches[1].replace(/[₹,\s]/g, ''));
                    if (mrp <= price) mrp = null;
                }
                
                products.push({
                    name: name.substring(0, 100),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: url
                });
                console.log('BigBasket: Added:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('BigBasket: Error:', e.message); }
        });
        
        // Method 3: Fallback - scan images with nearby prices
        if (products.length === 0) {
            console.log('BigBasket: Trying image-based extraction...');
            var images = document.querySelectorAll('img[src*="bigbasket"], img[src*="bb"], img[data-src]');
            console.log('BigBasket: Found', images.length, 'images');
            
            images.forEach(function(img) {
                if (products.length >= 10) return;
                try {
                    var parent = img.closest('li') || img.closest('a') || img.closest('div');
                    if (!parent) return;
                    
                    var text = parent.textContent.trim();
                    var priceMatch = text.match(/₹\s*([\d,]+)/);
                    if (!priceMatch) return;
                    
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price <= 0 || price > 50000) return;
                    
                    var name = text.split('₹')[0].trim();
                    name = name.replace(/\s*(ADD|Add)\s*$/i, '').trim();
                    if (name.length < 3 || seenNames[name]) return;
                    seenNames[name] = true;
                    
                    products.push({
                        name: name.substring(0, 100),
                        price: price,
                        imageUrl: img.src || img.dataset.src,
                        url: parent.href || window.location.href
                    });
                } catch(e) {}
            });
        }
        
        console.log('BigBasket: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * Instamart (Swiggy) specific extraction script - Updated for 2024/2025 structure.
     * Swiggy Instamart uses React with complex nested data structures.
     */
    val INSTAMART_EXTRACTION = """
        var products = [];
        console.log('Instamart: Starting extraction...');
        console.log('Instamart: URL:', window.location.href);
        
        // Debug: Check what's on the page
        var bodyText = document.body.innerText;
        console.log('Instamart: Body text length:', bodyText.length);
        console.log('Instamart: Body preview:', bodyText.substring(0, 300));
        
        // Check for price patterns - Swiggy uses special Unicode chars sometimes
        var rupeeCount = (bodyText.match(/₹/g) || []).length;
        var numericPrices = bodyText.match(/\b\d{2,4}\b/g) || [];
        console.log('Instamart: ₹ count:', rupeeCount, 'numeric patterns:', numericPrices.length);
        
        var seenNames = {};
        
        // Method 1: Look for product images with nearby text
        var images = document.querySelectorAll('img');
        console.log('Instamart: Total images:', images.length);
        
        // Filter to product-like images
        var productImages = [];
        images.forEach(function(img) {
            var src = img.src || img.dataset.src || '';
            if (src.includes('swiggy') || src.includes('cloudinary') || src.includes('instamart') || 
                src.includes('res.') || (src.includes('http') && img.width > 50)) {
                productImages.push(img);
            }
        });
        console.log('Instamart: Product-like images:', productImages.length);
        
        productImages.forEach(function(img, idx) {
            if (products.length >= 15) return;
            try {
                // Try multiple parent levels
                var containers = [
                    img.closest('a'),
                    img.parentElement,
                    img.parentElement?.parentElement,
                    img.parentElement?.parentElement?.parentElement,
                    img.parentElement?.parentElement?.parentElement?.parentElement
                ].filter(Boolean);
                
                for (var c = 0; c < containers.length && products.length < 15; c++) {
                    var container = containers[c];
                    var text = container.textContent.trim();
                    
                    // Skip if too large or too small
                    if (text.length < 5 || text.length > 400) continue;
                    
                    // Look for price patterns - try ₹, then numbers
                    var priceMatch = text.match(/₹\s*(\d+)/);
                    if (!priceMatch) {
                        // Look for standalone 2-4 digit numbers (likely prices)
                        var nums = text.match(/\b(\d{2,4})\b/g);
                        if (nums) {
                            for (var n = 0; n < nums.length; n++) {
                                var num = parseInt(nums[n]);
                                // Likely price range for groceries
                                if (num >= 10 && num <= 2000) {
                                    priceMatch = [nums[n], nums[n]];
                                    break;
                                }
                            }
                        }
                    }
                    if (!priceMatch) continue;
                    
                    var price = parseFloat(priceMatch[1]);
                    if (price <= 0 || price > 10000) continue;
                    
                    // Extract name - get text before numbers or clean it
                    var name = text.split(/₹|\b\d{2,4}\b/)[0].trim();
                    
                    // If name is too short, try getting first meaningful text
                    if (name.length < 3) {
                        var textParts = text.split(/\s+/);
                        name = textParts.slice(0, 5).join(' ');
                    }
                    
                    // Clean name
                    name = name.replace(/\s*(ADD|Add|add|\+|−|Out of Stock)\s*$/i, '').trim();
                    name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack|unit|gm|pc)s?\s*$/i, '').trim();
                    name = name.replace(/\s+/g, ' ').trim();
                    
                    if (name.length < 3 || name.length > 120) continue;
                    if (seenNames[name]) continue;
                    seenNames[name] = true;
                    
                    var link = container.closest('a') || container.querySelector('a');
                    
                    products.push({
                        name: name.substring(0, 100),
                        price: price,
                        imageUrl: img.src || img.dataset.src,
                        url: link ? link.href : window.location.href
                    });
                    console.log('Instamart: Added:', name.substring(0, 30), 'price:', price);
                    break; // Found product in this container, move to next image
                }
            } catch(e) { console.log('Instamart: Error:', e.message); }
        });
        
        // Method 2: Look for text patterns in body
        if (products.length === 0) {
            console.log('Instamart: Trying text pattern extraction...');
            var lines = bodyText.split('\n').filter(function(l) { return l.trim().length > 0; });
            console.log('Instamart: Lines to scan:', lines.length);
            
            var currentName = '';
            for (var i = 0; i < lines.length && products.length < 10; i++) {
                var line = lines[i].trim();
                
                // Check if line has a price-like number
                var priceMatch = line.match(/₹\s*(\d+)/) || line.match(/\b(\d{2,3})\b/);
                
                if (priceMatch && currentName.length >= 5) {
                    var price = parseFloat(priceMatch[1]);
                    if (price >= 10 && price <= 2000 && !seenNames[currentName]) {
                        seenNames[currentName] = true;
                        products.push({
                            name: currentName.substring(0, 100),
                            price: price,
                            imageUrl: null,
                            url: window.location.href
                        });
                        console.log('Instamart: Text pattern added:', currentName.substring(0, 30), 'price:', price);
                    }
                    currentName = '';
                } else if (line.length >= 5 && line.length <= 80 && !line.match(/^\d+$/) && 
                           !line.match(/^(ADD|Add|₹|\d+\s*(g|kg|ml|l))$/i)) {
                    currentName = line;
                }
            }
        }
        
        console.log('Instamart: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * JioMart specific extraction script - Updated for 2024/2025 structure.
     * JioMart uses Vue.js with dynamic rendering.
     */
    val JIOMART_EXTRACTION = """
        var products = [];
        console.log('JioMart: Starting extraction...');
        console.log('JioMart: URL:', window.location.href);
        
        // Method 1: Find product-like elements
        var allElements = document.querySelectorAll('div, a, li, article');
        var potentialProducts = [];
        
        allElements.forEach(function(el) {
            var text = el.textContent || '';
            if (text.match(/₹\s*\d+/) && text.length < 600 && text.length > 10) {
                var hasImg = el.querySelector('img');
                if (hasImg || el.closest('a')) {
                    potentialProducts.push(el);
                }
            }
        });
        
        console.log('JioMart: Found', potentialProducts.length, 'potential products');
        
        // Debug selectors
        var selectors = [
            '.plp-card-container',
            '.product-card',
            '[class*="ProductCard"]',
            '[data-sku]',
            'a[href*="/p/"]',
            '[class*="jm-"]',
            '[class*="product"]'
        ];
        selectors.forEach(function(sel) {
            var found = document.querySelectorAll(sel);
            if (found.length > 0) console.log('JioMart: Selector', sel, 'found', found.length);
        });
        
        // Method 2: Extract from potential products
        var seenNames = {};
        potentialProducts.forEach(function(el) {
            if (products.length >= 15) return;
            try {
                var text = el.textContent.trim();
                
                // Skip containers with too many prices
                var allPrices = text.match(/₹\s*[\d,]+/g) || [];
                if (allPrices.length > 4) return;
                
                var priceMatches = text.match(/₹\s*([\d,]+(?:\.\d{2})?)/g);
                if (!priceMatches) return;
                
                var price = parseFloat(priceMatches[0].replace(/[₹,\s]/g, ''));
                if (price <= 0 || price > 50000) return;
                
                // Extract name
                var parts = text.split(/₹/);
                var name = parts[0].trim();
                
                // Clean name
                name = name.replace(/\s*(ADD|Add|add|ADDED|\+|−|Out of Stock|Add to Cart)\s*$/i, '').trim();
                name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack|unit|pieces?)s?\s*$/i, '').trim();
                name = name.replace(/\s+/g, ' ').trim();
                
                if (name.length < 3 || name.length > 150) return;
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                // Get image
                var img = el.querySelector('img');
                if (!img) img = el.closest('a')?.querySelector('img');
                if (!img) img = el.parentElement?.querySelector('img');
                var imageUrl = img ? (img.src || img.dataset.src) : null;
                
                // Get link
                var link = el.closest('a') || el.querySelector('a[href*="/p/"]') || el.querySelector('a');
                var url = link ? link.href : window.location.href;
                
                // Get MRP
                var mrp = null;
                if (priceMatches.length > 1) {
                    mrp = parseFloat(priceMatches[1].replace(/[₹,\s]/g, ''));
                    if (mrp <= price) mrp = null;
                }
                
                products.push({
                    name: name.substring(0, 100),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: url
                });
                console.log('JioMart: Added:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('JioMart: Error:', e.message); }
        });
        
        // Method 3: Fallback - image-based extraction
        if (products.length === 0) {
            console.log('JioMart: Trying image-based extraction...');
            var images = document.querySelectorAll('img[src*="jiomart"], img[src*="jio"], img[data-src]');
            console.log('JioMart: Found', images.length, 'images');
            
            images.forEach(function(img) {
                if (products.length >= 10) return;
                try {
                    var parent = img.closest('a') || img.closest('div');
                    if (!parent) return;
                    
                    var text = parent.textContent.trim();
                    var priceMatch = text.match(/₹\s*([\d,]+)/);
                    if (!priceMatch) return;
                    
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price <= 0 || price > 50000) return;
                    
                    var name = text.split('₹')[0].trim();
                    name = name.replace(/\s*(ADD|Add)\s*$/i, '').trim();
                    if (name.length < 3 || seenNames[name]) return;
                    seenNames[name] = true;
                    
                    products.push({
                        name: name.substring(0, 100),
                        price: price,
                        imageUrl: img.src || img.dataset.src,
                        url: parent.href || window.location.href
                    });
                } catch(e) {}
            });
        }
        
        console.log('JioMart: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * JioMart OLD specific extraction script - keeping for reference.
     */
    val JIOMART_EXTRACTION_OLD = """
        var products = [];
        
        // Try Next.js data first
        var nextData = document.querySelector('#__NEXT_DATA__');
        if (nextData) {
            try {
                var data = JSON.parse(nextData.textContent);
                var pageProps = data.props?.pageProps || {};
                
                // Try multiple paths
                var items = pageProps.searchData?.products || 
                           pageProps.initialData?.data?.products ||
                           pageProps.searchResponse?.data?.products ||
                           pageProps.products ||
                           pageProps.data?.products || [];
                
                // Check for plp structure
                if (items.length === 0 && pageProps.plp?.data?.products) {
                    items = pageProps.plp.data.products;
                }
                
                items.slice(0, 10).forEach(function(item) {
                    if (!item) return;
                    var name = item.name || item.productName || item.product_name;
                    var price = item.selling_price || item.sellingPrice || item.offer_price || item.price;
                    
                    if (name && price) {
                        products.push({
                            name: name,
                            price: parseFloat(price),
                            mrp: item.mrp || item.maximum_retail_price || item.max_price,
                            imageUrl: item.image || item.imageUrl || item.product_image,
                            url: item.slug ? '/p/' + item.slug + '/p' : null
                        });
                    }
                });
            } catch(e) { console.log('JioMart NEXT_DATA error:', e); }
        }
        
        // Look for embedded product data
        if (products.length === 0) {
            var scripts = document.querySelectorAll('script');
            scripts.forEach(function(script) {
                if (products.length >= 10) return;
                var text = script.textContent || '';
                if (text.includes('"products"') && text.includes('"selling_price"')) {
                    try {
                        var match = text.match(/"products"\s*:\s*(\[[^\]]+\])/);
                        if (match) {
                            var items = JSON.parse(match[1]);
                            items.slice(0, 10).forEach(function(item) {
                                products.push({
                                    name: item.name,
                                    price: item.selling_price || item.price,
                                    mrp: item.mrp,
                                    imageUrl: item.image,
                                    url: '/p/' + item.slug
                                });
                            });
                        }
                    } catch(e) {}
                }
            });
        }
        
        // Fallback to DOM scraping with JioMart specific selectors
        if (products.length === 0) {
            var selectors = [
                'div[class*="plp-card"]',
                'div[class*="ProductCard"]',
                'div[class*="product-card"]',
                'a[class*="Product"]',
                'li[class*="product"]'
            ];
            
            for (var s = 0; s < selectors.length && products.length === 0; s++) {
                var cards = document.querySelectorAll(selectors[s]);
                cards.forEach(function(card, index) {
                    if (index >= 10 || products.length >= 10) return;
                    try {
                        var nameEl = card.querySelector('[class*="product-name"], [class*="title"], h3, h4, span[class*="name"]');
                        var name = nameEl ? nameEl.textContent.trim() : '';
                        if (name.length < 3) return;
                        
                        var priceMatch = card.textContent.match(/₹\s*([\d,]+)/);
                        if (!priceMatch) return;
                        
                        var img = card.querySelector('img');
                        var link = card.closest('a') || card.querySelector('a');
                        
                        products.push({
                            name: name,
                            price: parseFloat(priceMatch[1].replace(/,/g, '')),
                            imageUrl: img ? (img.src || img.dataset.src) : null,
                            url: link ? link.href : null
                        });
                    } catch(e) {}
                });
            }
        }
        
        return products;
    """.trimIndent()
    
    /**
     * Zepto specific extraction script.
     */
    val ZEPTO_EXTRACTION = """
        var products = [];
        console.log('Zepto: Starting extraction...');
        console.log('Zepto: URL:', window.location.href);
        console.log('Zepto: Body length:', document.body.innerHTML.length);
        console.log('Zepto: Body text sample:', document.body.innerText.substring(0, 500));
        
        // Check if we're on a location page
        var bodyText = document.body.innerText.toLowerCase();
        if (bodyText.includes('select location') || bodyText.includes('enter pincode') || 
            bodyText.includes('detect my location') || bodyText.includes('delivery location')) {
            console.log('Zepto: ⚠️ Location selection page detected');
        }
        
        var seenNames = {};
        
        // Method 0: Try to extract from __NEXT_DATA__ first (most reliable)
        try {
            var nextData = document.querySelector('script#__NEXT_DATA__');
            if (nextData) {
                var data = JSON.parse(nextData.textContent);
                console.log('Zepto: Found __NEXT_DATA__');
                var pageProps = data.props && data.props.pageProps;
                if (pageProps) {
                    // Try various paths for search results
                    var searchResults = pageProps.searchResults || pageProps.products || pageProps.items ||
                        (pageProps.initialState && pageProps.initialState.search && pageProps.initialState.search.products);
                    if (searchResults && searchResults.length > 0) {
                        searchResults.slice(0, 15).forEach(function(item) {
                            var name = item.name || item.title || item.productName;
                            var price = item.sellingPrice || item.price || (item.pricing && item.pricing.sellingPrice);
                            if (name && price && price > 0) {
                                var mrp = item.mrp || (item.pricing && item.pricing.mrp);
                                products.push({
                                    name: name.substring(0, 100),
                                    price: price,
                                    mrp: mrp > price ? mrp : null,
                                    imageUrl: item.image || item.imageUrl || item.thumbnail,
                                    url: window.location.href
                                });
                                console.log('Zepto: NextData added:', name.substring(0, 30), '₹' + price);
                            }
                        });
                    }
                }
            }
        } catch(e) { console.log('Zepto: NextData error:', e.message); }
        
        if (products.length > 0) {
            console.log('Zepto: Final products:', products.length);
            return products;
        }
        
        // Method 0.5: Try window.__INITIAL_STATE__ or similar global state
        try {
            var initialState = window.__INITIAL_STATE__ || window.__ZEPTO_DATA__ || window.__PRELOADED_STATE__;
            if (initialState) {
                console.log('Zepto: Found initial state');
                var items = initialState.search?.products || initialState.products || initialState.items;
                if (items && items.length > 0) {
                    items.slice(0, 15).forEach(function(item) {
                        var name = item.name || item.title;
                        var price = item.sellingPrice || item.price;
                        if (name && price && price > 0) {
                            products.push({
                                name: name.substring(0, 100),
                                price: price,
                                mrp: item.mrp > price ? item.mrp : null,
                                imageUrl: item.image || item.imageUrl,
                                url: window.location.href
                            });
                            console.log('Zepto: State added:', name.substring(0, 30), '₹' + price);
                        }
                    });
                }
            }
        } catch(e) { console.log('Zepto: Initial state error:', e.message); }
        
        if (products.length > 0) {
            console.log('Zepto: Final products:', products.length);
            return products;
        }
        
        // Method 1: Try all divs/articles that might contain product info
        // Zepto uses custom React components, look for price patterns anywhere
        var allContainers = document.querySelectorAll('div, article, section, li');
        console.log('Zepto: Scanning', allContainers.length, 'containers for products');
        
        allContainers.forEach(function(container, idx) {
            if (products.length >= 15) return;
            try {
                var text = container.innerText || container.textContent || '';
                if (text.length < 10 || text.length > 500) return;
                
                // Must have a price pattern
                var priceMatch = text.match(/₹\s*(\d+)/);
                if (!priceMatch) return;
                
                var price = parseFloat(priceMatch[1]);
                if (price <= 0 || price > 50000) return;
                
                // Must have a link
                var link = container.querySelector('a[href*="/pn/"]') || container.querySelector('a[href*="/product"]');
                if (!link) return;
                
                // Extract name - find text before the price or the link text
                var name = '';
                var linkText = link.innerText || link.textContent || '';
                if (linkText.length > 3 && linkText.length < 150) {
                    name = linkText.replace(/₹\d+/g, '').replace(/ADD/gi, '').replace(/OFF/gi, '').trim();
                }
                
                if (name.length < 3) {
                    // Try to extract from container text
                    name = text.split('₹')[0].replace(/ADD/gi, '').trim();
                }
                
                if (name.length < 3 || name.length > 120) return;
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                // Get MRP
                var allPrices = text.match(/₹\s*(\d+)/g);
                var mrp = null;
                if (allPrices && allPrices.length > 1) {
                    var mrpVal = parseFloat(allPrices[1].replace(/₹\s*/, ''));
                    if (mrpVal > price) mrp = mrpVal;
                }
                
                // Get image
                var img = container.querySelector('img');
                var imageUrl = img ? (img.src || img.dataset.src) : null;
                
                products.push({
                    name: name.substring(0, 100),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: link.href
                });
                console.log('Zepto: Container added:', name.substring(0, 30), '₹' + price);
            } catch(e) {}
        });
        
        if (products.length > 0) {
            console.log('Zepto: Final products:', products.length);
            return products;
        }
        
        // Method 2: Try direct link-based extraction (Zepto uses /pn/ links)
        var productLinks = document.querySelectorAll('a[href*="/pn/"], a[href*="/prn/"], a[href*="/product/"], a[href*="/cn/"]');
        console.log('Zepto: Found', productLinks.length, 'product links');
        
        productLinks.forEach(function(link, idx) {
            if (products.length >= 15) return;
            try {
                // Get text from the link AND its siblings/parent container
                var text = link.textContent.trim();
                
                // If link text is empty, try to get text from parent and siblings
                if (text.length < 5) {
                    var parent = link.parentElement;
                    while (parent && text.length < 10) {
                        text = parent.textContent.trim();
                        parent = parent.parentElement;
                        if (parent && parent.tagName === 'BODY') break;
                    }
                }
                
                console.log('Zepto: Link', idx, 'text:', text.substring(0, 60));
                
                // Zepto format: ADD₹price₹mrp₹discountOFF ProductName Quantity Rating
                // Extract the first price (selling price)
                var allPrices = text.match(/₹(\d+)/g);
                if (!allPrices || allPrices.length === 0) return;
                
                var price = parseFloat(allPrices[0].replace('₹', ''));
                if (price <= 0 || price > 50000) return;
                
                // Get MRP (second price, if higher)
                var mrp = null;
                if (allPrices.length > 1) {
                    var mrpVal = parseFloat(allPrices[1].replace('₹', ''));
                    if (mrpVal > price) mrp = mrpVal;
                }
                
                // Extract name - remove ADD, prices, OFF, and clean up
                var name = text;
                // Remove ADD prefix
                name = name.replace(/^ADD/i, '');
                // Remove all ₹XX patterns
                name = name.replace(/₹\d+/g, '');
                // Remove OFF suffix after discount
                name = name.replace(/OFF/gi, '');
                // Remove rating patterns like 4.3(747)
                name = name.replace(/\d+\.\d+\(\d+\)/g, '');
                // Remove quantity patterns at end
                name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack|unit|pieces?|gm|pc)s?\s*$/i, '');
                // Remove "1 pack (500 ml)" patterns
                name = name.replace(/\d+\s*pack\s*\(\d+\s*(g|kg|ml|l)\)/gi, '');
                // Clean up whitespace
                name = name.replace(/\s+/g, ' ').trim();
                
                if (name.length < 3 || name.length > 120) return;
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                // Get image
                var img = link.querySelector('img');
                if (!img) img = link.parentElement?.querySelector('img');
                var imageUrl = img ? (img.src || img.dataset.src) : null;
                
                products.push({
                    name: name.substring(0, 100),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: link.href
                });
                console.log('Zepto: Added:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('Zepto: Link error:', e.message); }
        });
        
        // Method 2: Fallback - find images with nearby prices
        if (products.length === 0) {
            console.log('Zepto: Trying image-based extraction...');
            // Try multiple image selectors
            var images = document.querySelectorAll('img[src*="cdn"], img[src*="zepto"], img[src*="cloudinary"], img[alt], picture img');
            console.log('Zepto: Found', images.length, 'images');
            
            images.forEach(function(img) {
                if (products.length >= 15) return;
                try {
                    var parent = img.closest('a[href*="/pn/"]') || img.closest('a') || img.closest('div');
                    if (!parent) return;
                    
                    var text = parent.textContent.trim();
                    var priceMatch = text.match(/₹(\d+)/);
                    if (!priceMatch) return;
                    
                    var price = parseFloat(priceMatch[1]);
                    if (price <= 0 || price > 50000) return;
                    
                    var name = text.replace(/^ADD/i, '').replace(/₹\d+/g, '').replace(/OFF/gi, '');
                    name = name.replace(/\d+\.\d+\(\d+\)/g, '').replace(/\s+/g, ' ').trim();
                    
                    if (name.length < 3 || seenNames[name]) return;
                    seenNames[name] = true;
                    
                    products.push({
                        name: name.substring(0, 100),
                        price: price,
                        imageUrl: img.src || img.dataset.src,
                        url: parent.href || window.location.href
                    });
                } catch(e) {}
            });
        }
        
        // Method 3: Text pattern extraction
        if (products.length === 0) {
            console.log('Zepto: Trying text pattern extraction...');
            var bodyText = document.body.innerText;
            var lines = bodyText.split('\n').filter(function(l) { return l.trim().length > 0; });
            console.log('Zepto: Lines to scan:', lines.length);
            
            var currentName = '';
            for (var i = 0; i < lines.length && products.length < 15; i++) {
                var line = lines[i].trim();
                
                // Check if line has ADD and price pattern
                if (line.match(/^ADD/) && line.match(/₹(\d+)/)) {
                    var allPrices = line.match(/₹(\d+)/g);
                    if (allPrices && allPrices.length > 0) {
                        var price = parseFloat(allPrices[0].replace('₹', ''));
                        if (price >= 5 && price <= 5000) {
                            var name = line.replace(/^ADD/i, '').replace(/₹\d+/g, '').replace(/OFF/gi, '');
                            name = name.replace(/\d+\.\d+\(\d+[kK]?\)/g, '').replace(/\s+/g, ' ').trim();
                            name = name.replace(/\d+\s*(g|kg|ml|l|pcs|pack)s?\s*$/i, '').trim();
                            
                            if (name.length >= 5 && !seenNames[name]) {
                                seenNames[name] = true;
                                products.push({
                                    name: name.substring(0, 100),
                                    price: price,
                                    imageUrl: null,
                                    url: window.location.href
                                });
                                console.log('Zepto: Text pattern added:', name.substring(0, 30), '₹' + price);
                            }
                        }
                    }
                }
            }
        }
        
        console.log('Zepto: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * Flipkart extraction script - handles both regular and grocery search results
     */
    val FLIPKART_EXTRACTION = """
        var products = [];
        console.log('Flipkart: Starting extraction...');
        console.log('Flipkart: URL:', window.location.href);
        
        var seenNames = {};
        
        // Method 1: Try to find product cards with various selectors
        var productSelectors = [
            'div[data-id]',
            'div._1sdMkc',
            'div.slAVV4',
            'div.tUxRFH',
            'div._75nlfW',
            'div.cPHDOP',
            'a._1fQZEK',
            'a.CGtC98'
        ];
        
        var allProducts = [];
        productSelectors.forEach(function(selector) {
            var found = document.querySelectorAll(selector);
            if (found.length > 0) {
                console.log('Flipkart: Found', found.length, 'with', selector);
                found.forEach(function(el) { allProducts.push(el); });
            }
        });
        
        // Deduplicate by getting unique parent containers
        var processedContainers = new Set();
        
        allProducts.forEach(function(el) {
            if (products.length >= 15) return;
            try {
                // Get the product container
                var container = el.closest('div[data-id]') || el.closest('div._1sdMkc') || 
                               el.closest('div.slAVV4') || el.closest('div.tUxRFH') || el;
                
                if (processedContainers.has(container)) return;
                processedContainers.add(container);
                
                // Get product name
                var name = '';
                var nameSelectors = ['a.wjcEIp', 'a.IRpwTa', 'div.KzDlHZ', 'a.s1Q9rs', 'div._4rR01T', 'a[title]'];
                for (var i = 0; i < nameSelectors.length; i++) {
                    var nameEl = container.querySelector(nameSelectors[i]);
                    if (nameEl) {
                        name = nameEl.textContent.trim() || nameEl.getAttribute('title') || '';
                        if (name.length >= 5) break;
                    }
                }
                if (name.length < 5) return;
                
                // Get price
                var price = 0;
                var priceSelectors = ['div.Nx9bqj', 'div._30jeq3', 'div.hl05eU span', 'span._30jeq3'];
                for (var i = 0; i < priceSelectors.length; i++) {
                    var priceEl = container.querySelector(priceSelectors[i]);
                    if (priceEl) {
                        var priceText = priceEl.textContent.replace(/[₹,]/g, '').trim();
                        price = parseFloat(priceText);
                        if (price > 0) break;
                    }
                }
                if (price <= 0) return;
                
                // Get MRP
                var mrp = null;
                var mrpEl = container.querySelector('div.yRaY8j') || container.querySelector('div._3I9_wc');
                if (mrpEl) {
                    var mrpText = mrpEl.textContent.replace(/[₹,]/g, '').trim();
                    mrp = parseFloat(mrpText);
                    if (mrp <= price) mrp = null;
                }
                
                // Get image
                var imageUrl = null;
                var imgEl = container.querySelector('img.DByuf4') || container.querySelector('img._396cs4') || 
                           container.querySelector('img');
                if (imgEl) {
                    imageUrl = imgEl.src || imgEl.dataset.src;
                }
                
                // Get URL
                var url = window.location.href;
                var linkEl = container.querySelector('a.CGtC98') || container.querySelector('a._1fQZEK') || 
                            container.querySelector('a[href*="/p/"]');
                if (linkEl) {
                    url = linkEl.href;
                }
                
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                products.push({
                    name: name.substring(0, 150),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: url
                });
                console.log('Flipkart: Added:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('Flipkart: Error:', e.message); }
        });
        
        // Method 2: Fallback - scan for price patterns with images
        if (products.length === 0) {
            console.log('Flipkart: Trying image-based fallback...');
            var images = document.querySelectorAll('img[src*="rukminim"], img[src*="flixcart"]');
            console.log('Flipkart: Found', images.length, 'product images');
            
            images.forEach(function(img) {
                if (products.length >= 15) return;
                try {
                    // Try multiple parent levels
                    var containers = [
                        img.closest('a[href*="/p/"]'),
                        img.closest('div[data-id]'),
                        img.parentElement?.parentElement?.parentElement,
                        img.parentElement?.parentElement
                    ].filter(Boolean);
                    
                    for (var c = 0; c < containers.length; c++) {
                        var container = containers[c];
                        var text = container.textContent.trim();
                        
                        // Look for price pattern
                        var priceMatch = text.match(/₹\s*(\d+(?:,\d+)*)/);
                        if (!priceMatch) continue;
                        
                        var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                        if (price <= 0 || price > 100000) continue;
                        
                        // Get name from image alt or text before price
                        var name = img.alt || '';
                        if (name.length < 5) {
                            name = text.split('₹')[0].trim();
                        }
                        name = name.replace(/\s+/g, ' ').substring(0, 100);
                        
                        if (name.length < 5 || seenNames[name]) continue;
                        seenNames[name] = true;
                        
                        var url = window.location.href;
                        var link = container.closest('a') || container.querySelector('a[href*="/p/"]');
                        if (link) url = link.href;
                        
                        products.push({
                            name: name,
                            price: price,
                            imageUrl: img.src,
                            url: url
                        });
                        console.log('Flipkart: Fallback added:', name.substring(0, 30), '₹' + price);
                        break;
                    }
                } catch(e) { console.log('Flipkart: Fallback error:', e.message); }
            });
        }
        
        // Method 3: Text pattern extraction from body
        if (products.length === 0) {
            console.log('Flipkart: Trying text pattern extraction...');
            var bodyText = document.body.innerText;
            var lines = bodyText.split('\n').filter(function(l) { return l.trim().length > 0; });
            console.log('Flipkart: Lines to scan:', lines.length);
            
            var currentName = '';
            for (var i = 0; i < lines.length && products.length < 15; i++) {
                var line = lines[i].trim();
                
                // Check if line has a price
                var priceMatch = line.match(/₹\s*(\d+(?:,\d+)*)/);
                
                if (priceMatch && currentName.length >= 5) {
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price >= 10 && price <= 50000 && !seenNames[currentName]) {
                        seenNames[currentName] = true;
                        products.push({
                            name: currentName.substring(0, 100),
                            price: price,
                            imageUrl: null,
                            url: window.location.href
                        });
                        console.log('Flipkart: Text pattern added:', currentName.substring(0, 30), '₹' + price);
                    }
                    currentName = '';
                } else if (line.length >= 5 && line.length <= 100 && !line.match(/^₹/) && 
                           !line.match(/^(ADD|Add|Buy|View|Sort|Filter|Sponsored)/i)) {
                    currentName = line;
                }
            }
        }
        
        console.log('Flipkart: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * Amazon extraction script - handles search results page
     */
    val AMAZON_EXTRACTION = """
        var products = [];
        console.log('Amazon: Starting extraction...');
        console.log('Amazon: URL:', window.location.href);
        
        var seenNames = {};
        
        // Method 1: Find search result items
        var searchResults = document.querySelectorAll('[data-component-type="s-search-result"]');
        console.log('Amazon: Found', searchResults.length, 'search results');
        
        searchResults.forEach(function(result) {
            if (products.length >= 15) return;
            try {
                // Skip sponsored items
                if (result.querySelector('.s-sponsored-label-info-icon')) return;
                
                var asin = result.getAttribute('data-asin');
                if (!asin) return;
                
                // Get name from image alt or title
                var name = '';
                var img = result.querySelector('img.s-image');
                if (img) {
                    name = img.alt || '';
                }
                if (name.length < 10) {
                    var titleEl = result.querySelector('h2 a span') || result.querySelector('h2 span') ||
                                  result.querySelector('span.a-text-normal');
                    if (titleEl) {
                        name = titleEl.textContent.trim();
                    }
                }
                if (name.length < 5) return;
                
                // Get price
                var price = 0;
                var priceEl = result.querySelector('span.a-price:not(.a-text-price) .a-offscreen') ||
                             result.querySelector('span.a-price-whole');
                if (priceEl) {
                    var priceText = priceEl.textContent.replace(/[₹,]/g, '').trim();
                    price = parseFloat(priceText);
                }
                if (price <= 0) return;
                
                // Get MRP
                var mrp = null;
                var mrpEl = result.querySelector('.a-price.a-text-price .a-offscreen');
                if (mrpEl) {
                    var mrpText = mrpEl.textContent.replace(/[₹,]/g, '').trim();
                    mrp = parseFloat(mrpText);
                    if (mrp <= price) mrp = null;
                }
                
                // Get image URL
                var imageUrl = img ? img.src : null;
                
                // Get product URL
                var url = 'https://www.amazon.in/dp/' + asin;
                
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                products.push({
                    name: name.substring(0, 150),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: url
                });
                console.log('Amazon: Added:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('Amazon: Error:', e.message); }
        });
        
        // Method 2: Fallback - find any product-like elements
        if (products.length === 0) {
            console.log('Amazon: Trying fallback extraction...');
            var images = document.querySelectorAll('img.s-image, img[src*="images-amazon"]');
            console.log('Amazon: Found', images.length, 'product images');
            
            images.forEach(function(img) {
                if (products.length >= 15) return;
                try {
                    var container = img.closest('[data-asin]') || img.closest('div');
                    if (!container) return;
                    
                    var name = img.alt || '';
                    if (name.length < 5) return;
                    
                    var text = container.textContent;
                    var priceMatch = text.match(/₹\s*(\d+(?:,\d+)*)/);
                    if (!priceMatch) return;
                    
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price <= 0 || price > 100000) return;
                    
                    if (seenNames[name]) return;
                    seenNames[name] = true;
                    
                    var asin = container.getAttribute('data-asin') || '';
                    var url = asin ? 'https://www.amazon.in/dp/' + asin : window.location.href;
                    
                    products.push({
                        name: name.substring(0, 150),
                        price: price,
                        imageUrl: img.src,
                        url: url
                    });
                    console.log('Amazon: Fallback added:', name.substring(0, 30), '₹' + price);
                } catch(e) {}
            });
        }
        
        console.log('Amazon: Final products:', products.length);
        return products;
    """.trimIndent()
    
    /**
     * Flipkart Minutes (Grocery) extraction script
     * Uses same approach as regular Flipkart but filters for grocery items
     */
    val FLIPKART_MINUTES_EXTRACTION = """
        var products = [];
        console.log('Flipkart Minutes: Starting extraction...');
        console.log('Flipkart Minutes: URL:', window.location.href);
        console.log('Flipkart Minutes: Title:', document.title);
        console.log('Flipkart Minutes: Body length:', document.body.innerHTML.length);
        
        var seenNames = {};
        
        // Check page state
        var bodyText = document.body.innerText.toLowerCase();
        console.log('Flipkart Minutes: Body text length:', bodyText.length);
        
        if (bodyText.includes('select your location') || bodyText.includes('enter pincode') ||
            bodyText.includes('choose delivery location')) {
            console.log('Flipkart Minutes: Location selection page detected');
        }
        
        // Method 1: Standard Flipkart product card selectors (2024/2025 structure)
        var productSelectors = [
            'div[data-id]',
            'div._1AtVbE',
            'div._2kHMtA',
            'div._4ddWXP',
            'div._1xHGtK',
            'div._13oc-S',
            'a[href*="/p/"]',
            'div._1sdMkc',
            'div.slAVV4'
        ];
        
        var allElements = [];
        productSelectors.forEach(function(selector) {
            try {
                var found = document.querySelectorAll(selector);
                if (found.length > 0) {
                    console.log('Flipkart Minutes: Found', found.length, 'with', selector);
                    found.forEach(function(el) { allElements.push(el); });
                }
            } catch(e) {}
        });
        
        console.log('Flipkart Minutes: Total elements to process:', allElements.length);
        
        var processedContainers = new Set();
        
        allElements.forEach(function(el) {
            if (products.length >= 15) return;
            try {
                var container = el.closest('div[data-id]') || el.closest('div._1AtVbE') || 
                               el.closest('div._2kHMtA') || el;
                
                if (processedContainers.has(container)) return;
                processedContainers.add(container);
                
                // Get product name - multiple selectors for different Flipkart layouts
                var name = '';
                var nameSelectors = [
                    'a.IRpwTa', 'a.wjcEIp', 'a.s1Q9rs', 
                    'div.KzDlHZ', 'div._4rR01T', 'span._2B099V',
                    'a[title]', 'div[title]'
                ];
                for (var i = 0; i < nameSelectors.length; i++) {
                    var nameEl = container.querySelector(nameSelectors[i]);
                    if (nameEl) {
                        name = nameEl.textContent.trim() || nameEl.getAttribute('title') || '';
                        if (name.length >= 5) break;
                    }
                }
                if (name.length < 5) return;
                
                // Get price
                var price = 0;
                var priceSelectors = ['div.Nx9bqj', 'div._30jeq3', 'span.Nx9bqj', 'span._30jeq3', 'div._25b18c'];
                for (var i = 0; i < priceSelectors.length; i++) {
                    var priceEl = container.querySelector(priceSelectors[i]);
                    if (priceEl) {
                        var priceText = priceEl.textContent.replace(/[₹,]/g, '').trim();
                        price = parseFloat(priceText);
                        if (price > 0) break;
                    }
                }
                if (price <= 0) return;
                
                // Get MRP
                var mrp = null;
                var mrpEl = container.querySelector('div.yRaY8j') || container.querySelector('div._3I9_wc');
                if (mrpEl) {
                    var mrpText = mrpEl.textContent.replace(/[₹,]/g, '').trim();
                    mrp = parseFloat(mrpText);
                    if (mrp <= price) mrp = null;
                }
                
                // Get image
                var imageUrl = null;
                var imgEl = container.querySelector('img.DByuf4') || container.querySelector('img._396cs4') || 
                           container.querySelector('img[src*="rukminim"]') || container.querySelector('img');
                if (imgEl) {
                    imageUrl = imgEl.src || imgEl.dataset.src;
                }
                
                // Get URL
                var url = window.location.href;
                var linkEl = container.querySelector('a[href*="/p/"]') || container.querySelector('a.CGtC98') || 
                            container.querySelector('a._1fQZEK') || container.querySelector('a[href*="/grocery"]');
                if (linkEl) {
                    url = linkEl.href;
                }
                
                if (seenNames[name]) return;
                seenNames[name] = true;
                
                products.push({
                    name: name.substring(0, 150),
                    price: price,
                    mrp: mrp,
                    imageUrl: imageUrl,
                    url: url
                });
                console.log('Flipkart Minutes: Added:', name.substring(0, 30), '₹' + price);
            } catch(e) { console.log('Flipkart Minutes: Error:', e.message); }
        });
        
        // Method 2: Image-based extraction
        if (products.length === 0) {
            console.log('Flipkart Minutes: Trying image-based fallback...');
            var images = document.querySelectorAll('img[src*="rukminim"], img[src*="flixcart"]');
            console.log('Flipkart Minutes: Found', images.length, 'product images');
            
            images.forEach(function(img) {
                if (products.length >= 15) return;
                try {
                    // Walk up to find the product container
                    var parent = img.parentElement;
                    for (var p = 0; p < 6 && parent; p++) {
                        var text = parent.textContent || '';
                        var priceMatch = text.match(/₹\s*(\d+(?:,\d+)*)/);
                        
                        if (priceMatch) {
                            var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                            if (price <= 0 || price > 50000) {
                                parent = parent.parentElement;
                                continue;
                            }
                            
                            var name = img.alt || '';
                            if (name.length < 5) {
                                // Try to find name from text before price
                                var beforePrice = text.split('₹')[0].trim();
                                name = beforePrice.split('\n').filter(function(l) { 
                                    return l.trim().length >= 5 && l.trim().length <= 100; 
                                }).pop() || '';
                            }
                            
                            if (name.length < 5 || seenNames[name]) {
                                parent = parent.parentElement;
                                continue;
                            }
                            
                            seenNames[name] = true;
                            
                            var url = window.location.href;
                            var link = parent.querySelector('a[href*="/p/"]') || parent.closest('a');
                            if (link) url = link.href;
                            
                            products.push({
                                name: name.substring(0, 100),
                                price: price,
                                imageUrl: img.src,
                                url: url
                            });
                            console.log('Flipkart Minutes: Image fallback added:', name.substring(0, 30), '₹' + price);
                            break;
                        }
                        parent = parent.parentElement;
                    }
                } catch(e) {}
            });
        }
        
        // Method 3: Aggressive text parsing
        if (products.length === 0) {
            console.log('Flipkart Minutes: Trying text extraction...');
            var allText = document.body.innerText;
            var lines = allText.split('\n').filter(function(l) { return l.trim().length > 0; });
            console.log('Flipkart Minutes: Lines to scan:', lines.length);
            
            var currentName = '';
            for (var i = 0; i < lines.length && products.length < 15; i++) {
                var line = lines[i].trim();
                var priceMatch = line.match(/₹\s*(\d+(?:,\d+)*)/);
                
                if (priceMatch && currentName.length >= 5) {
                    var price = parseFloat(priceMatch[1].replace(/,/g, ''));
                    if (price >= 5 && price <= 5000 && !seenNames[currentName]) {
                        seenNames[currentName] = true;
                        products.push({
                            name: currentName.substring(0, 100),
                            price: price,
                            imageUrl: null,
                            url: window.location.href
                        });
                        console.log('Flipkart Minutes: Text added:', currentName.substring(0, 30), '₹' + price);
                    }
                    currentName = '';
                } else if (line.length >= 5 && line.length <= 100 && !line.match(/^₹/) && 
                           !line.match(/^(ADD|Add|Buy|View|Sort|Filter|Sponsored|Delivery|Login|Sign)/i)) {
                    currentName = line;
                }
            }
        }
        
        console.log('Flipkart Minutes: Final products:', products.length);
        return products;
    """.trimIndent()
}
