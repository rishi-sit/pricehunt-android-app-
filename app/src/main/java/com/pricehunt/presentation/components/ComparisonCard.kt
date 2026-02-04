package com.pricehunt.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pricehunt.data.model.Platforms
import com.pricehunt.data.model.Product
import com.pricehunt.data.search.SearchIntelligence
import com.pricehunt.presentation.theme.*

/**
 * Comparison Card shows a single product across multiple platforms
 * Allows easy comparison of prices for the same/similar product
 */
@Composable
fun ComparisonCard(
    productName: String,
    products: List<Product>,
    onProductClick: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    if (products.isEmpty()) return
    
    // Sort by price (cheapest first)
    val sortedProducts = products.sortedBy { it.price }
    val cheapestProduct = sortedProducts.first()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Product name header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    productName.take(50) + if (productName.length > 50) "..." else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                // Number of platforms badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        "${products.size} platforms",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary
                    )
                }
            }
            
            // Horizontal scrollable list of platform prices
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sortedProducts.forEachIndexed { index, product ->
                    val isCheapest = index == 0
                    PlatformPriceChip(
                        product = product,
                        isCheapest = isCheapest,
                        savings = if (!isCheapest && sortedProducts.size > 1) {
                            sortedProducts[index].price - cheapestProduct.price
                        } else null,
                        onClick = { onProductClick(product) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformPriceChip(
    product: Product,
    isCheapest: Boolean,
    savings: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platformColor = Color(Platforms.getColor(product.platform))
    val perUnitPrice = remember(product) {
        SearchIntelligence.calculatePerUnitPrice(product)
    }
    val perPiecePrice = remember(product) {
        SearchIntelligence.calculatePerPiecePrice(product)
    }
    
    Card(
        modifier = modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCheapest) 
                Color(0xFF4CAF50).copy(alpha = 0.1f) 
            else 
                SurfaceVariant
        ),
        border = if (isCheapest) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4CAF50))
            )
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Cheapest badge
            if (isCheapest) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Text(
                        "CHEAPEST",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // Platform name
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = platformColor.copy(alpha = 0.15f)
            ) {
                Text(
                    product.platform,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = platformColor,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Product image (small)
            if (product.imageUrl != null) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    AsyncImage(
                        model = product.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            // Price
            Text(
                "₹${product.price.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCheapest) Color(0xFF4CAF50) else OnSurface
            )
            
            // Per-unit price
            perUnitPrice?.let { pup ->
                Text(
                    pup.toDisplayString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            if (perPiecePrice != null && perUnitPrice?.unitType != "count") {
                Text(
                    perPiecePrice.toDisplayString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            
            // Savings indicator (for non-cheapest)
            savings?.let { diff ->
                if (diff > 0) {
                    Text(
                        "+₹${diff.toInt()} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = Error
                    )
                }
            }
            
            // Delivery time
            Text(
                product.deliveryTime,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Toggle button to switch between regular and comparison view
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewModeToggle(
    isComparisonView: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = !isComparisonView,
            onClick = { onToggle(false) },
            label = { Text("By Platform") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Primary.copy(alpha = 0.2f),
                selectedLabelColor = Primary
            )
        )
        FilterChip(
            selected = isComparisonView,
            onClick = { onToggle(true) },
            label = { Text("Compare Similar") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Primary.copy(alpha = 0.2f),
                selectedLabelColor = Primary
            )
        )
    }
}
