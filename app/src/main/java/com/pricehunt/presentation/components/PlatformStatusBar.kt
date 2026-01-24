package com.pricehunt.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pricehunt.data.model.Platforms
import com.pricehunt.presentation.screens.home.PlatformStatus
import com.pricehunt.presentation.theme.*

@Composable
fun PlatformStatusBar(
    platformStatus: Map<String, PlatformStatus>,
    modifier: Modifier = Modifier
) {
    if (platformStatus.isEmpty()) return
    
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(platformStatus.entries.toList()) { (platform, status) ->
            PlatformStatusChip(
                platform = platform,
                status = status
            )
        }
    }
}

@Composable
private fun PlatformStatusChip(
    platform: String,
    status: PlatformStatus
) {
    val platformColor = Color(Platforms.getColor(platform))
    
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    Surface(
        modifier = Modifier
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = when (status) {
            PlatformStatus.PENDING -> SurfaceVariant
            PlatformStatus.LOADING -> platformColor.copy(alpha = alpha * 0.3f)
            PlatformStatus.COMPLETED -> platformColor.copy(alpha = 0.15f)
            PlatformStatus.CACHED -> Primary.copy(alpha = 0.15f)
            PlatformStatus.FAILED -> Color(0xFFFF5252).copy(alpha = 0.15f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (status) {
                PlatformStatus.PENDING -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TextTertiary)
                    )
                }
                PlatformStatus.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = platformColor,
                        strokeWidth = 2.dp
                    )
                }
                PlatformStatus.COMPLETED -> {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Completed",
                        modifier = Modifier.size(14.dp),
                        tint = platformColor
                    )
                }
                PlatformStatus.CACHED -> {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = "Cached",
                        modifier = Modifier.size(14.dp),
                        tint = Primary
                    )
                }
                PlatformStatus.FAILED -> {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252))
                    )
                }
            }
            
            Text(
                text = platform,
                style = MaterialTheme.typography.labelSmall,
                color = when (status) {
                    PlatformStatus.PENDING -> TextTertiary
                    PlatformStatus.LOADING -> platformColor
                    PlatformStatus.COMPLETED -> platformColor
                    PlatformStatus.CACHED -> Primary
                    PlatformStatus.FAILED -> Color(0xFFFF5252)
                }
            )
        }
    }
}

