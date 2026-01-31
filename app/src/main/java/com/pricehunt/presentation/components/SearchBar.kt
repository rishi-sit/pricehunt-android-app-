package com.pricehunt.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pricehunt.presentation.theme.Primary
import com.pricehunt.presentation.theme.Surface
import com.pricehunt.presentation.theme.SurfaceVariant
import com.pricehunt.presentation.theme.TextSecondary
import com.pricehunt.presentation.theme.OnSurface

@Composable
fun SearchBar(
    query: String,
    pincode: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onPincodeChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    // New parameters for suggestions
    suggestions: List<String> = emptyList(),
    showSuggestions: Boolean = false,
    onSuggestionClick: (String) -> Unit = {},
    onDismissSuggestions: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search input with suggestions dropdown
        Box {
            Column {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Search for any product...",
                            color = TextSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextSecondary
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceVariant,
                        unfocusedContainerColor = SurfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Primary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { 
                        focusManager.clearFocus()  // Dismiss keyboard
                        onDismissSuggestions()
                        onSearch() 
                    })
                )
                
                // Suggestions dropdown
                AnimatedVisibility(
                    visible = showSuggestions && suggestions.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = SurfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                "💡 Try searching for:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            suggestions.forEach { suggestion ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSuggestionClick(suggestion) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        suggestion,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Pincode and search button row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pincode input
            TextField(
                value = pincode,
                onValueChange = { if (it.length <= 6) onPincodeChange(it.filter { c -> c.isDigit() }) },
                modifier = Modifier.width(120.dp),
                placeholder = {
                    Text("Pincode", color = TextSecondary)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Primary
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            
            // Search button
            Button(
                onClick = {
                    focusManager.clearFocus()  // Dismiss keyboard
                    onDismissSuggestions()
                    onSearch()
                },
                enabled = query.isNotBlank() && !isSearching,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Color.Black,
                    disabledContainerColor = Primary.copy(alpha = 0.5f)
                )
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        "Compare Prices",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

