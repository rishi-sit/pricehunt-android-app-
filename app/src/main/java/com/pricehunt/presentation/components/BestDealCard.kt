package com.pricehunt.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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
fun BestDealCard(
    product: Product?,
    onViewClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSearching: Boolean = false,
    platformsCompleted: Int = 0,
    totalPlatforms: Int = 10
) {
    // Track previous best deal to detect changes
    var previousProduct by remember { mutableStateOf<Product?>(null) }
    var showUpdateAnimation by remember { mutableStateOf(false) }
    
    // Detect when best deal changes to a better one
    LaunchedEffect(product) {
        if (product != null && previousProduct != null && 
            product.price < (previousProduct?.price ?: Double.MAX_VALUE)) {
            showUpdateAnimation = true
            kotlinx.coroutines.delay(600)
            showUpdateAnimation = false
        }
        previousProduct = product
    }
    
    // Pulse animation for when best deal updates
    val pulseScale by animateFloatAsState(
        targetValue = if (showUpdateAnimation) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pulse"
    )
    
    AnimatedVisibility(
        visible = product != null || isSearching,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .scale(pulseScale),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = if (product != null) {
                                listOf(
                                    Primary.copy(alpha = 0.2f),
                                    Color(Platforms.getColor(product.platform)).copy(alpha = 0.1f)
                                )
                            } else {
                                listOf(
                                    SurfaceVariant.copy(alpha = 0.5f),
                                    SurfaceVariant.copy(alpha = 0.3f)
                                )
                            }
                        )
                    )
                    .padding(16.dp)
            ) {
                if (product != null) {
                    // Show best deal content
                    BestDealContent(
                        product = product,
                        onViewClick = onViewClick,
                        showUpdateAnimation = showUpdateAnimation,
                        isSearching = isSearching,
                        platformsCompleted = platformsCompleted,
                        totalPlatforms = totalPlatforms
                    )
                } else if (isSearching) {
                    // Show searching state
                    SearchingForBestDeal(
                        platformsCompleted = platformsCompleted,
                        totalPlatforms = totalPlatforms
                    )
                }
            }
        }
    }
}

@Composable
private fun BestDealContent(
    product: Product,
    onViewClick: (String) -> Unit,
    showUpdateAnimation: Boolean,
    isSearching: Boolean,
    platformsCompleted: Int,
    totalPlatforms: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with update indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Animated icon when deal updates
                AnimatedContent(
                    targetState = showUpdateAnimation,
                    transitionSpec = {
                        scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
                    },
                    label = "icon"
                ) { isUpdating ->
                    Icon(
                        if (isUpdating) Icons.Default.TrendingDown else Icons.Default.LocalOffer,
                        contentDescription = null,
                        tint = if (isUpdating) Color(0xFF4CAF50) else Primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                AnimatedContent(
                    targetState = showUpdateAnimation,
                    transitionSpec = {
                        slideInVertically { -it } + fadeIn() togetherWith 
                        slideOutVertically { it } + fadeOut()
                    },
                    label = "title"
                ) { isUpdating ->
                    Text(
                        if (isUpdating) "🎉 Better Deal Found!" else "Best Deal Found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isUpdating) Color(0xFF4CAF50) else Primary
                    )
                }
            }
            
            // Progress indicator while searching
            if (isSearching) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                        Text(
                            "$platformsCompleted/$totalPlatforms",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Product Image
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceVariant)
            ) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            
            // Product Info with animated price
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = OnSurface
                )
                
                // Platform badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(Platforms.getColor(product.platform)).copy(alpha = 0.2f)
                ) {
                    Text(
                        product.platform,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(Platforms.getColor(product.platform))
                    )
                }
                
                // Animated Price
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedContent(
                        targetState = product.price,
                        transitionSpec = {
                            slideInVertically { -it } + fadeIn() togetherWith 
                            slideOutVertically { it } + fadeOut()
                        },
                        label = "price"
                    ) { price ->
                        Text(
                            "₹${price.toInt()}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Primary
                        )
                    }
                    product.originalPrice?.let { orig ->
                        Text(
                            "₹${orig.toInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = TextDecoration.LineThrough,
                            color = TextTertiary
                        )
                    }
                    product.discount?.let { disc ->
                        Text(
                            disc,
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary
                        )
                    }
                }
                
                // Per-unit price for fair comparison (e.g., ₹21.8/100ml)
                val perUnitPrice = remember(product) {
                    SearchIntelligence.calculatePerUnitPrice(product)
                }
                perUnitPrice?.let { pup ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                    ) {
                        Text(
                            "💰 ${pup.toDisplayString()} - Best Value!",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                // Delivery time
                Text(
                    "Delivery: ${product.deliveryTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
        
        // View Button
        Button(
            onClick = { onViewClick(product.url) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = Color.Black
            )
        ) {
            Text("View on ${product.platform}")
        }
    }
}

@Composable
private fun SearchingForBestDeal(
    platformsCompleted: Int,
    totalPlatforms: Int
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Primary
            )
            Text(
                "Finding Best Deal...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
        }
        
        // Progress bar
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = if (totalPlatforms > 0) platformsCompleted.toFloat() / totalPlatforms else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Primary,
                trackColor = SurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Searching $platformsCompleted of $totalPlatforms platforms",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
    }
}
