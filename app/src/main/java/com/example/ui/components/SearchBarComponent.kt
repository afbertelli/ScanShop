package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ProductFilter
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.ShopeeOrange

@Composable
fun SearchBarComponent(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSearch: (String) -> Unit,
    recentSearches: List<String>,
    selectedFilter: ProductFilter,
    onFilterSelected: (ProductFilter) -> Unit,
    modifier: Modifier = Modifier,
    onVoiceSearchClick: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current

    // Professional light search panel with clean subtle divider
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(BorderStroke(0.5.dp, Color(0xFFE2E8F0)))
            .padding(top = 10.dp, bottom = 10.dp)
    ) {
        // Main Search Bar with dedicated action button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Crisp search pill container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Color(0xFFF8FAFC))
                    .border(
                        BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        RoundedCornerShape(23.dp)
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = AliExpressRed,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Buscar produto (ex: Fone, Smartwatch)...",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_input_field"),
                            textStyle = TextStyle(
                                fontSize = 13.5.sp,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Medium
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(AliExpressRed),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    onSearch(query)
                                }
                            )
                        )
                    }

                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onQueryChanged("")
                                onSearch("")
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpar busca",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (onVoiceSearchClick != null) {
                        IconButton(
                            onClick = onVoiceSearchClick,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("voice_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Pesquisar por voz",
                                tint = AliExpressRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action "Buscar" button with dual-store gradient
            Box(
                modifier = Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(AliExpressRed, ShopeeOrange)
                        )
                    )
                    .clickable {
                        focusManager.clearFocus()
                        onSearch(query)
                    }
                    .padding(horizontal = 16.dp)
                    .testTag("btn_search_action"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Buscar",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Recent searches chips row (if any)
        if (recentSearches.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = Color(0xFF98A2B3),
                    modifier = Modifier.size(13.dp)
                )
                recentSearches.forEach { term ->
                    SuggestionChip(
                        onClick = {
                            onQueryChanged(term)
                            onSearch(term)
                        },
                        label = {
                            Text(
                                text = term,
                                fontSize = 11.sp,
                                fontWeight = if (query.equals(term, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (query.equals(term, ignoreCase = true)) {
                                AliExpressRed.copy(alpha = 0.1f)
                            } else {
                                Color.White
                            },
                            labelColor = if (query.equals(term, ignoreCase = true)) {
                                AliExpressRed
                            } else {
                                Color(0xFF344054)
                            }
                        ),
                        border = BorderStroke(
                            0.5.dp,
                            if (query.equals(term, ignoreCase = true)) AliExpressRed.copy(alpha = 0.3f) else Color(0xFFD0D5DD)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Professional Filter and Sort Pills Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { onFilterSelected(filter) },
                    label = {
                        Text(
                            text = filter.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color.White
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AliExpressRed,
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFFF8FAFC),
                        labelColor = Color(0xFF334155)
                    ),
                    border = BorderStroke(
                        0.5.dp,
                        if (isSelected) AliExpressRed else Color(0xFFE2E8F0)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
}
