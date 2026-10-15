package com.example.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.repository.ProductFilter
import com.example.ui.components.CompactProductCard
import com.example.ui.components.SearchBarComponent
import com.example.ui.components.UnifiedProductCard
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.DealGreenBg
import com.example.ui.theme.ShopeeOrange
import com.example.ui.viewmodel.SearchUiState
import com.example.ui.viewmodel.ViewMode

@Composable
fun SearchScreen(
    state: SearchUiState,
    favoriteIds: Set<String>,
    onQueryChanged: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFilterSelected: (ProductFilter) -> Unit,
    onToggleFavorite: (Product) -> Unit,
    onToggleViewMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasResults = state.comparisonResult != null &&
            (state.comparisonResult.aliExpressProducts.isNotEmpty() || state.comparisonResult.shopeeProducts.isNotEmpty())

    val context = LocalContext.current

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()

            if (!spokenText.isNullOrBlank()) {
                onQueryChanged(spokenText)
                onSearch(spokenText)
                Toast.makeText(context, "Pesquisando por: \"$spokenText\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val startVoiceSearch = {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o produto ou categoria desejada...")
            }
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "Reconhecimento de voz não suportado neste aparelho.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    var isHeaderCollapsed by rememberSaveable { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()

    // Expand search bar when user triggers a new search
    LaunchedEffect(state.isSearching) {
        if (state.isSearching) {
            isHeaderCollapsed = false
        }
    }

    // Scroll listener: dragging/scrolling down collapses search + banner; pulling down at top expands
    val nestedScrollConnection = remember(hasResults) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (hasResults) {
                    // Scrolling down into products list
                    if (delta < -8f && !isHeaderCollapsed) {
                        isHeaderCollapsed = true
                    }
                    // Dragging down at the very top of the list
                    else if (delta > 14f && isHeaderCollapsed && lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset <= 10) {
                        isHeaderCollapsed = false
                    }
                }
                return Offset.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Collapsible Search Bar + Filters + Comparison Banner (collapses when dragged/scrolled down)
        AnimatedVisibility(
            visible = !isHeaderCollapsed || !hasResults,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(220))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            if (hasResults) {
                                if (delta < -8f && !isHeaderCollapsed) {
                                    isHeaderCollapsed = true
                                } else if (delta > 15f && isHeaderCollapsed) {
                                    isHeaderCollapsed = false
                                }
                            }
                        }
                    )
            ) {
                SearchBarComponent(
                    query = state.query,
                    onQueryChanged = onQueryChanged,
                    onSearch = onSearch,
                    recentSearches = state.recentSearches,
                    selectedFilter = state.filter,
                    onFilterSelected = onFilterSelected,
                    onVoiceSearchClick = startVoiceSearch
                )

                ComparisonHeaderBar(
                    state = state,
                    onCollapse = { isHeaderCollapsed = true },
                    canCollapse = hasResults
                )
            }
        }

        // Main content
        if (state.isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = AliExpressRed,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Consultando AliExpress & Shopee com IA...",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Inteligência Artificial ativada: analisando marca, modelo e ofertas em tempo real",
                        fontSize = 11.5.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        } else if (state.errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = AliExpressRed,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Erro ao buscar ofertas em tempo real",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.errorMessage,
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onSearch(state.query) },
                        colors = ButtonDefaults.buttonColors(containerColor = AliExpressRed),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Tentar novamente", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            val result = state.comparisonResult
            if (result == null || (result.aliExpressProducts.isEmpty() && result.shopeeProducts.isEmpty())) {
                EmptySearchState(
                    query = state.query,
                    onSelectSuggestion = { term ->
                        onQueryChanged(term)
                        onSearch(term)
                    },
                    onVoiceSearchClick = startVoiceSearch
                )
            } else {
                when (state.viewMode) {
                    ViewMode.SIDE_BY_SIDE -> {
                        SideBySideComparisonView(
                            aliProducts = result.aliExpressProducts,
                            shopeeProducts = result.shopeeProducts,
                            favoriteIds = favoriteIds,
                            onToggleFavorite = onToggleFavorite,
                            listState = lazyListState,
                            isHeaderCollapsed = isHeaderCollapsed,
                            onToggleCollapse = { isHeaderCollapsed = !isHeaderCollapsed }
                        )
                    }
                    ViewMode.UNIFIED_LIST -> {
                        UnifiedListView(
                            products = result.unifiedSortedProducts,
                            winnerId = result.bestOverallDeal?.id,
                            favoriteIds = favoriteIds,
                            onToggleFavorite = onToggleFavorite
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonHeaderBar(
    state: SearchUiState,
    onCollapse: () -> Unit = {},
    canCollapse: Boolean = false,
    modifier: Modifier = Modifier
) {
    val result = state.comparisonResult
    val best = result?.bestOverallDeal

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(0.5.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // AI Intelligence badge if active and available
                if (result?.aiInsight != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFF3E8FF))
                            .border(BorderStroke(0.5.dp, Color(0xFFD8B4FE)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Busca Inteligente",
                                tint = Color(0xFF7E22CE),
                                modifier = Modifier.size(11.5.dp)
                            )
                            Spacer(modifier = Modifier.width(3.5.dp))
                            Text(
                                text = result.aiInsight.summary ?: "IA Ativa",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6B21A8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (result.aiInsight.filteredAccessoriesCount > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        val filterLabel = when {
                            result.aiInsight.categoryKey == "placa mae" ->
                                "${result.aiInsight.filteredAccessoriesCount} espelhos/peças filtrados"
                            result.aiInsight.targetGender != null ->
                                "${result.aiInsight.filteredAccessoriesCount} conflitos de gênero/acessórios filtrados"
                            else ->
                                "${result.aiInsight.filteredAccessoriesCount} itens incompatíveis/peças filtrados"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFEF2F2))
                                .border(BorderStroke(0.5.dp, Color(0xFFFECACA)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "🛡️ $filterLabel",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDC2626),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Price comparison insight badge
                if (best != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(DealGreenBg)
                            .border(BorderStroke(0.5.dp, Color(0xFFA7F3D0)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = null,
                                tint = DealGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Menor: ${best.formattedPromoPrice} (${best.store.displayName})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DealGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ElectricBolt,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Comparador AliExpress x Shopee em Tempo Real",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    }
                }
            }

            // Collapse action indicator when items are loaded
            if (canCollapse) {
                Surface(
                    onClick = onCollapse,
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF1F5F9),
                    border = BorderStroke(0.5.dp, Color(0xFFCBD5E1))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Recolher busca",
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Recolher",
                            fontSize = 10.sp,
                            color = Color(0xFF475569),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SideBySideComparisonView(
    aliProducts: List<Product>,
    shopeeProducts: List<Product>,
    favoriteIds: Set<String>,
    onToggleFavorite: (Product) -> Unit,
    listState: LazyListState,
    isHeaderCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxRows = maxOf(aliProducts.size, shopeeProducts.size)

    Column(modifier = modifier.fillMaxSize()) {
        // Sticky Store Header Row with prominent VS badge / Quick expand when collapsed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Header (AliExpress)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFFF1EE))
                    .border(BorderStroke(1.dp, Color(0xFFFFCDC2)), RoundedCornerShape(10.dp))
                    .padding(vertical = 7.dp, horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AliExpressRed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingCart,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AliExpress",
                            color = AliExpressRed,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // Count Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(BorderStroke(0.5.dp, Color(0xFFFFB3A1)), RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${aliProducts.size}",
                            color = AliExpressRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Central Badge: VS (when expanded) or Expand Search button (when collapsed)
            if (isHeaderCollapsed) {
                Surface(
                    onClick = onToggleCollapse,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Expandir busca e filtros",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Busca",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F172A))
                        .clickable { onToggleCollapse() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "VS",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }

            // Right Header (Shopee)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFFF2ED))
                    .border(BorderStroke(1.dp, Color(0xFFFFCEBA)), RoundedCornerShape(10.dp))
                    .padding(vertical = 7.dp, horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ShopeeOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingBag,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Shopee",
                            color = ShopeeOrange,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // Count Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(BorderStroke(0.5.dp, Color(0xFFFFBA9E)), RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${shopeeProducts.size}",
                            color = ShopeeOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Synchronized Scrolling List of Pairs
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(count = maxRows) { index ->
                val aliItem = aliProducts.getOrNull(index)
                val shopeeItem = shopeeProducts.getOrNull(index)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Left Column Item
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        if (aliItem != null) {
                            CompactProductCard(
                                product = aliItem,
                                isFavorite = favoriteIds.contains(aliItem.id),
                                onToggleFavorite = { onToggleFavorite(aliItem) },
                                modifier = Modifier.fillMaxHeight()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFE4E7EC), RoundedCornerShape(10.dp))
                                    .background(Color(0xFFFAFAFA)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sem mais ofertas",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    // Right Column Item
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        if (shopeeItem != null) {
                            CompactProductCard(
                                product = shopeeItem,
                                isFavorite = favoriteIds.contains(shopeeItem.id),
                                onToggleFavorite = { onToggleFavorite(shopeeItem) },
                                modifier = Modifier.fillMaxHeight()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFE4E7EC), RoundedCornerShape(10.dp))
                                    .background(Color(0xFFFAFAFA)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sem mais ofertas",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedListView(
    products: List<Product>,
    winnerId: String?,
    favoriteIds: Set<String>,
    onToggleFavorite: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(products, key = { it.id }) { product ->
            UnifiedProductCard(
                product = product,
                isWinner = product.id == winnerId,
                isFavorite = favoriteIds.contains(product.id),
                onToggleFavorite = { onToggleFavorite(product) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptySearchState(
    query: String,
    onSelectSuggestion: (String) -> Unit = {},
    onVoiceSearchClick: () -> Unit = {}
) {
    val popularSuggestions = listOf(
        "Fone Bluetooth",
        "Smartwatch",
        "Teclado Mecânico",
        "Drone 4K",
        "Carregador 65W",
        "Mouse Sem Fio"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elegant gradient icon circle
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFFFF1EE), Color(0xFFFFF2ED))
                        )
                    )
                    .border(
                        BorderStroke(1.dp, Color(0xFFFFD5CC)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (query.isEmpty()) Icons.Default.Search else Icons.Default.SearchOff,
                    contentDescription = null,
                    tint = if (query.isEmpty()) AliExpressRed else Color(0xFF64748B),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (query.isEmpty()) "O que você quer comparar hoje?" else "Nenhum produto encontrado para \"$query\"",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (query.isEmpty()) 
                    "Pesquise qualquer produto para comparar preços reais no AliExpress e na Shopee lado a lado."
                else 
                    "Verifique a ortografia ou tente termos mais genéricos.",
                fontSize = 12.5.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center
            )

            if (query.isEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))

                // Direct Voice Search Action Button
                Surface(
                    onClick = onVoiceSearchClick,
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFFFFF1EE),
                    border = BorderStroke(1.dp, Color(0xFFFFD5CC)),
                    modifier = Modifier.testTag("empty_state_voice_search_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Falar produto",
                            tint = AliExpressRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Falar nome do produto",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AliExpressRed
                        )
                    }
                }
            }

            if (query.isEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Sugestões populares:",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF334155)
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    popularSuggestions.forEach { term ->
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectSuggestion(term) },
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "🔍 $term",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1E293B),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
