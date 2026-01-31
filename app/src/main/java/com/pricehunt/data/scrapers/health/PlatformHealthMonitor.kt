package com.pricehunt.data.scrapers.health

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform Health Monitor - Tracks scraping success/failure rates and automatically
 * disables failing platforms to prevent wasted resources.
 * 
 * Features:
 * 1. Real-time success rate tracking per platform
 * 2. Automatic disable when success rate drops below threshold
 * 3. Automatic re-enable with exponential backoff for retries
 * 4. Persistent storage of health metrics across app restarts
 * 5. Circuit breaker pattern to prevent cascading failures
 * 
 * Circuit Breaker States:
 * - CLOSED: Platform is healthy, normal operation
 * - OPEN: Platform is failing, skip scraping
 * - HALF_OPEN: Testing if platform has recovered
 */
@Singleton
class PlatformHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "platform_health"
        private const val KEY_PREFIX_SUCCESS = "success_"
        private const val KEY_PREFIX_TOTAL = "total_"
        private const val KEY_PREFIX_LAST_FAILURE = "last_failure_"
        private const val KEY_PREFIX_CONSECUTIVE_FAILURES = "consecutive_failures_"
        private const val KEY_PREFIX_CIRCUIT_STATE = "circuit_state_"
        private const val KEY_PREFIX_LAST_STRUCTURE_HASH = "structure_hash_"
        
        // Thresholds
        private const val MIN_SAMPLES_FOR_DECISION = 3  // Need at least 3 attempts before disabling
        private const val SUCCESS_RATE_THRESHOLD = 0.2  // Disable if success rate < 20%
        private const val CONSECUTIVE_FAILURES_THRESHOLD = 3  // Open circuit after 3 consecutive failures
        private const val MAX_SAMPLES = 20  // Rolling window size
        
        // Backoff timing (in milliseconds)
        private const val INITIAL_BACKOFF_MS = 60_000L  // 1 minute
        private const val MAX_BACKOFF_MS = 3600_000L    // 1 hour max
        private const val BACKOFF_MULTIPLIER = 2.0
        
        // Tags for logging
        private const val TAG = "PlatformHealth"
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // In-memory state for fast access
    private val healthData = ConcurrentHashMap<String, PlatformHealth>()
    
    // Observable state for UI
    private val _platformStates = MutableStateFlow<Map<String, PlatformState>>(emptyMap())
    val platformStates: StateFlow<Map<String, PlatformState>> = _platformStates.asStateFlow()
    
    init {
        loadPersistedState()
    }
    
    /**
     * Record the result of a scraping attempt
     */
    fun recordResult(platform: String, success: Boolean, productCount: Int = 0, htmlHash: String? = null) {
        val health = healthData.getOrPut(platform) { PlatformHealth(platform) }
        
        synchronized(health) {
            // Update metrics
            health.totalAttempts++
            if (success && productCount > 0) {
                health.successfulAttempts++
                health.consecutiveFailures = 0
                health.lastSuccessTime = System.currentTimeMillis()
                health.lastProductCount = productCount
                
                // Store structure hash for change detection
                if (htmlHash != null) {
                    health.lastStructureHash = htmlHash
                }
            } else {
                health.consecutiveFailures++
                health.lastFailureTime = System.currentTimeMillis()
                health.lastFailureReason = if (productCount == 0) "No products found" else "Unknown"
            }
            
            // Keep rolling window
            if (health.totalAttempts > MAX_SAMPLES) {
                // Adjust counts to maintain rolling window
                val ratio = health.successfulAttempts.toDouble() / health.totalAttempts
                health.totalAttempts = MAX_SAMPLES
                health.successfulAttempts = (MAX_SAMPLES * ratio).toInt()
            }
            
            // Update circuit breaker state
            updateCircuitState(health)
            
            // Persist state
            persistHealth(health)
            
            // Update observable state
            updatePlatformStates()
        }
        
        log("$platform: ${if (success) "✓" else "✗"} (${health.successRate}% success, ${health.consecutiveFailures} consecutive failures, state=${health.circuitState})")
    }
    
    /**
     * Check if a platform should be scraped
     */
    fun shouldScrape(platform: String): Boolean {
        val health = healthData[platform] ?: return true  // Unknown platform = try it
        
        return when (health.circuitState) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                // Check if enough time has passed for retry
                val backoffTime = calculateBackoff(health.consecutiveFailures)
                val timeSinceLastFailure = System.currentTimeMillis() - health.lastFailureTime
                
                if (timeSinceLastFailure >= backoffTime) {
                    // Transition to HALF_OPEN for testing
                    health.circuitState = CircuitState.HALF_OPEN
                    persistHealth(health)
                    updatePlatformStates()
                    log("$platform: Circuit HALF_OPEN - testing recovery after ${backoffTime/1000}s")
                    true
                } else {
                    val remainingTime = (backoffTime - timeSinceLastFailure) / 1000
                    log("$platform: Circuit OPEN - skipping (retry in ${remainingTime}s)")
                    false
                }
            }
            CircuitState.HALF_OPEN -> true  // Allow test request
        }
    }
    
    /**
     * Get health status for a platform
     */
    fun getHealth(platform: String): PlatformHealth? = healthData[platform]
    
    /**
     * Get all platform health data
     */
    fun getAllHealth(): Map<String, PlatformHealth> = healthData.toMap()
    
    /**
     * Force reset a platform (for manual recovery)
     */
    fun resetPlatform(platform: String) {
        healthData[platform] = PlatformHealth(platform)
        persistHealth(healthData[platform]!!)
        updatePlatformStates()
        log("$platform: Health reset manually")
    }
    
    /**
     * Reset all platforms
     */
    fun resetAll() {
        healthData.clear()
        prefs.edit().clear().apply()
        updatePlatformStates()
        log("All platforms reset")
    }
    
    /**
     * Check if HTML structure has changed (potential breakage indicator)
     */
    fun hasStructureChanged(platform: String, newHash: String): Boolean {
        val health = healthData[platform] ?: return false
        val oldHash = health.lastStructureHash
        
        if (oldHash != null && oldHash != newHash) {
            log("$platform: ⚠️ Structure change detected! Old=$oldHash, New=$newHash")
            health.structureChangeCount++
            return true
        }
        return false
    }
    
    /**
     * Get platforms that are currently disabled
     */
    fun getDisabledPlatforms(): List<String> {
        return healthData.filter { it.value.circuitState == CircuitState.OPEN }
            .map { it.key }
    }
    
    /**
     * Get platforms that are healthy
     */
    fun getHealthyPlatforms(): List<String> {
        return healthData.filter { it.value.circuitState == CircuitState.CLOSED }
            .map { it.key }
    }
    
    // ==================== Private Methods ====================
    
    private fun updateCircuitState(health: PlatformHealth) {
        val previousState = health.circuitState
        
        when (health.circuitState) {
            CircuitState.CLOSED -> {
                // Check if we should open the circuit
                if (health.consecutiveFailures >= CONSECUTIVE_FAILURES_THRESHOLD) {
                    health.circuitState = CircuitState.OPEN
                    log("${health.platform}: Circuit OPENED - ${health.consecutiveFailures} consecutive failures")
                } else if (health.totalAttempts >= MIN_SAMPLES_FOR_DECISION && 
                           health.successRate < SUCCESS_RATE_THRESHOLD) {
                    health.circuitState = CircuitState.OPEN
                    log("${health.platform}: Circuit OPENED - success rate ${health.successRate}% < ${SUCCESS_RATE_THRESHOLD * 100}%")
                }
            }
            CircuitState.HALF_OPEN -> {
                // Check the result of the test request
                if (health.consecutiveFailures == 0) {
                    // Success! Close the circuit
                    health.circuitState = CircuitState.CLOSED
                    health.consecutiveFailures = 0
                    log("${health.platform}: Circuit CLOSED - recovery successful!")
                } else {
                    // Still failing, re-open
                    health.circuitState = CircuitState.OPEN
                    log("${health.platform}: Circuit re-OPENED - recovery failed")
                }
            }
            CircuitState.OPEN -> {
                // State transitions happen in shouldScrape()
            }
        }
        
        if (previousState != health.circuitState) {
            updatePlatformStates()
        }
    }
    
    private fun calculateBackoff(consecutiveFailures: Int): Long {
        val backoff = INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, (consecutiveFailures - 1).coerceAtLeast(0).toDouble())
        return backoff.toLong().coerceAtMost(MAX_BACKOFF_MS)
    }
    
    private fun persistHealth(health: PlatformHealth) {
        prefs.edit().apply {
            putInt(KEY_PREFIX_SUCCESS + health.platform, health.successfulAttempts)
            putInt(KEY_PREFIX_TOTAL + health.platform, health.totalAttempts)
            putLong(KEY_PREFIX_LAST_FAILURE + health.platform, health.lastFailureTime)
            putInt(KEY_PREFIX_CONSECUTIVE_FAILURES + health.platform, health.consecutiveFailures)
            putString(KEY_PREFIX_CIRCUIT_STATE + health.platform, health.circuitState.name)
            health.lastStructureHash?.let { putString(KEY_PREFIX_LAST_STRUCTURE_HASH + health.platform, it) }
            apply()
        }
    }
    
    private fun loadPersistedState() {
        // Get all platform keys
        val platforms = prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX_TOTAL) }
            .map { it.removePrefix(KEY_PREFIX_TOTAL) }
        
        for (platform in platforms) {
            val health = PlatformHealth(platform).apply {
                successfulAttempts = prefs.getInt(KEY_PREFIX_SUCCESS + platform, 0)
                totalAttempts = prefs.getInt(KEY_PREFIX_TOTAL + platform, 0)
                lastFailureTime = prefs.getLong(KEY_PREFIX_LAST_FAILURE + platform, 0)
                consecutiveFailures = prefs.getInt(KEY_PREFIX_CONSECUTIVE_FAILURES + platform, 0)
                circuitState = try {
                    CircuitState.valueOf(prefs.getString(KEY_PREFIX_CIRCUIT_STATE + platform, "CLOSED") ?: "CLOSED")
                } catch (e: Exception) {
                    CircuitState.CLOSED
                }
                lastStructureHash = prefs.getString(KEY_PREFIX_LAST_STRUCTURE_HASH + platform, null)
            }
            healthData[platform] = health
        }
        
        updatePlatformStates()
        log("Loaded health data for ${platforms.size} platforms")
    }
    
    private fun updatePlatformStates() {
        _platformStates.value = healthData.map { (platform, health) ->
            platform to PlatformState(
                platform = platform,
                isEnabled = health.circuitState != CircuitState.OPEN,
                successRate = health.successRate,
                consecutiveFailures = health.consecutiveFailures,
                circuitState = health.circuitState,
                lastSuccessTime = health.lastSuccessTime,
                lastFailureTime = health.lastFailureTime
            )
        }.toMap()
    }
    
    private fun log(message: String) {
        println("$TAG: $message")
        try {
            android.util.Log.d(TAG, message)
        } catch (e: Exception) {
            // Running in unit test environment
        }
    }
}

/**
 * Health data for a platform
 */
data class PlatformHealth(
    val platform: String,
    var successfulAttempts: Int = 0,
    var totalAttempts: Int = 0,
    var consecutiveFailures: Int = 0,
    var lastFailureTime: Long = 0,
    var lastSuccessTime: Long = 0,
    var lastFailureReason: String? = null,
    var lastProductCount: Int = 0,
    var circuitState: CircuitState = CircuitState.CLOSED,
    var lastStructureHash: String? = null,
    var structureChangeCount: Int = 0
) {
    val successRate: Int
        get() = if (totalAttempts > 0) (successfulAttempts * 100 / totalAttempts) else 0
}

/**
 * Circuit breaker states
 */
enum class CircuitState {
    CLOSED,     // Normal operation
    OPEN,       // Failing, skip requests
    HALF_OPEN   // Testing recovery
}

/**
 * Observable state for UI
 */
data class PlatformState(
    val platform: String,
    val isEnabled: Boolean,
    val successRate: Int,
    val consecutiveFailures: Int,
    val circuitState: CircuitState,
    val lastSuccessTime: Long,
    val lastFailureTime: Long
)
