package com.pricehunt.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Star
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

@Composable
fun ProductCard(
    product: Product,
    isCached: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platformColor = Color(Platforms.getColor(product.platform))
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Product Image
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceVariant)
            ) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                // Cached badge
                if (isCached) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Primary.copy(alpha = 0.9f)
                    ) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = "Cached",
                            modifier = Modifier
                                .size(16.dp)
                                .padding(2.dp),
                            tint = Color.Black
                        )
                    }
                }
            }
            
            // Product Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Platform badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = platformColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        product.platform,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = platformColor
                    )
                }
                
                // Product name
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = OnSurface
                )
                
                // Rating
                product.rating?.let { rating ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFFFC107)
                        )
                        Text(
                            rating.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                
                // Price row
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "₹${product.price.toInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnSurface
                    )
                    product.originalPrice?.let { orig ->
                        Text(
                            "₹${orig.toInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough,
                            color = TextTertiary
                        )
                    }
                    product.discount?.let { disc ->
                        Text(
                            disc,
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary
                        )
                    }
                }
                
                // Per-unit price for comparison (e.g., ₹4.5/100g) - shown as a badge
                val perUnitPrice = remember(product) {
                    SearchIntelligence.calculatePerUnitPrice(product)
                }
                perUnitPrice?.let { pup ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                    ) {
                        Text(
                            "💰 ${pup.toDisplayString()}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                // Delivery time
                Text(
                    product.deliveryTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

