package com.pricehunt.presentation.screens.home

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pricehunt.presentation.components.*
import com.pricehunt.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    fun openUrl(url: String) {
        try {
            // Log URL for debugging
            android.util.Log.i("PriceHunt", "Opening URL: $url")
            
            // Show URL in toast for debugging
            val shortUrl = if (url.length > 50) url.take(50) + "..." else url
            Toast.makeText(context, "Opening: $shortUrl", Toast.LENGTH_LONG).show()
            
            // Validate URL
            if (url.isBlank() || !url.startsWith("http")) {
                Toast.makeText(context, "Invalid URL: $url", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Create intent with browser chooser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            
            // Try to resolve the intent first
            val resolvedActivity = intent.resolveActivity(context.packageManager)
            if (resolvedActivity != null) {
                context.startActivity(intent)
            } else {
                // Try with a chooser if no default browser
                val chooser = Intent.createChooser(intent, "Open with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            android.util.Log.e("PriceHunt", "Error opening URL: $url", e)
            Toast.makeText(context, "Cannot open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Background,
                        Color(0xFF0F0F15)
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "🏷️ PriceHunt",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Compare prices across 10+ platforms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
            
            // Search Bar
            item {
                SearchBar(
                    query = uiState.query,
                    pincode = uiState.pincode,
                    isSearching = uiState.isSearching,
                    onQueryChange = viewModel::updateQuery,
                    onPincodeChange = viewModel::updatePincode,
                    onSearch = viewModel::search
                )
            }
            
            // Platform Status Bar
            item {
                AnimatedVisibility(
                    visible = uiState.platformStatus.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        PlatformStatusBar(platformStatus = uiState.platformStatus)
                    }
                }
            }
            
            // Best Deal Card - Shows progress and updates in real-time
            item {
                BestDealCard(
                    product = uiState.bestDeal,
                    onViewClick = ::openUrl,
                    isSearching = uiState.isSearching,
                    platformsCompleted = uiState.platformsCompleted,
                    totalPlatforms = uiState.totalPlatforms
                )
            }
            
            // Cache Stats
            item {
                uiState.cacheStats?.let { stats ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Cache: ${stats.totalEntries} entries",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                        IconButton(
                            onClick = viewModel::clearCache,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear cache",
                                tint = TextTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            
            // Results Header with Price Disclaimer
            if (uiState.results.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            "All Results",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "📍 Showing prices for pincode: ${uiState.pincode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary
                        )
                        Text(
                            "💡 Set the same pincode in other apps for accurate prices",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }
            
            // Product Results grouped by platform
            uiState.results.forEach { (platform, products) ->
                if (products.isNotEmpty()) {
                    item(key = "header_$platform") {
                        Text(
                            platform,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(
                                com.pricehunt.data.model.Platforms.getColor(platform)
                            )
                        )
                    }
                    
                    itemsIndexed(
                        items = products,
                        key = { index, product -> "${platform}_${index}_${product.name.hashCode()}_${product.price}" }
                    ) { _, product ->
                        ProductCard(
                            product = product,
                            isCached = uiState.platformStatus[platform] == PlatformStatus.CACHED,
                            onClick = { openUrl(product.url) },
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .animateItemPlacement()
                        )
                    }
                }
            }
            
            // No results message
            if (!uiState.isSearching && uiState.results.isEmpty() && uiState.query.isNotBlank() && uiState.platformStatus.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No results found.\nTry a different search term.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            // Error message
            uiState.error?.let { error ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Error.copy(alpha = 0.1f)
                        )
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Error
                        )
                    }
                }
            }
        }
    }
}

