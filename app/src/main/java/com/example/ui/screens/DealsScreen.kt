package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.ui.components.CompactProductCard
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.ShopeeOrange
import com.example.ui.viewmodel.DealsUiState

@Composable
fun DealsScreen(
    state: DealsUiState,
    favoriteIds: Set<String>,
    onStoreFilterSelected: (StoreType?) -> Unit,
    onToggleFavorite: (Product) -> Unit,
    onRefreshDeals: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val aliDeals = state.superDeals.filter { it.store == StoreType.ALIEXPRESS }
    val shopeeDeals = state.superDeals.filter { it.store == StoreType.SHOPEE }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Deals Screen Header with Refresh Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = AliExpressRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = " Super Ofertas & Promoções",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Descontos acima de 40% em tempo real",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            IconButton(
                onClick = onRefreshDeals,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF1F5F9))
                    .testTag("refresh_deals_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Atualizar Ofertas",
                    tint = AliExpressRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Store Filter Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // All stores
            FilterChip(
                selected = state.selectedStore == null,
                onClick = { onStoreFilterSelected(null) },
                label = { Text("Todas as Lojas (${state.superDeals.size})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AliExpressRed,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                border = null
            )

            // AliExpress
            FilterChip(
                selected = state.selectedStore == StoreType.ALIEXPRESS,
                onClick = { onStoreFilterSelected(StoreType.ALIEXPRESS) },
                label = { Text("AliExpress (${aliDeals.size})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AliExpressRed,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                border = null
            )

            // Shopee
            FilterChip(
                selected = state.selectedStore == StoreType.SHOPEE,
                onClick = { onStoreFilterSelected(StoreType.SHOPEE) },
                label = { Text("Shopee (${shopeeDeals.size})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ShopeeOrange,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                border = null
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AliExpressRed)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Carregando ofertas oficiais em tempo real...",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }
        } else if (state.superDeals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = AliExpressRed,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Nenhuma oferta carregada no momento",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Toque no botão abaixo para buscar as super promoções de hoje diretamente nas APIs oficiais.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onRefreshDeals,
                        colors = ButtonDefaults.buttonColors(containerColor = AliExpressRed),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("load_deals_empty_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buscar Ofertas Agora", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            when (state.selectedStore) {
                StoreType.ALIEXPRESS -> {
                    if (aliDeals.isEmpty()) {
                        EmptyStoreDeals(
                            storeName = "AliExpress",
                            onShowAllStores = { onStoreFilterSelected(null) },
                            onRefresh = onRefreshDeals
                        )
                    } else {
                        // AliExpress only grid
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                                .testTag("deals_ali_grid"),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(aliDeals, key = { it.id }) { product ->
                                CompactProductCard(
                                    product = product,
                                    isFavorite = favoriteIds.contains(product.id),
                                    onToggleFavorite = { onToggleFavorite(product) }
                                )
                            }
                        }
                    }
                }
                StoreType.SHOPEE -> {
                    if (shopeeDeals.isEmpty()) {
                        EmptyStoreDeals(
                            storeName = "Shopee",
                            onShowAllStores = { onStoreFilterSelected(null) },
                            onRefresh = onRefreshDeals
                        )
                    } else {
                        // Shopee only grid
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                                .testTag("deals_shopee_grid"),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(shopeeDeals, key = { it.id }) { product ->
                                CompactProductCard(
                                    product = product,
                                    isFavorite = favoriteIds.contains(product.id),
                                    onToggleFavorite = { onToggleFavorite(product) }
                                )
                            }
                        }
                    }
                }
                null -> {
                    // Side-by-Side Dual Store Comparison View
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                            .testTag("deals_grid"),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Store Header Row separating columns (AliExpress on Left, Shopee on Right)
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left Header: AliExpress
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
                                                text = "${aliDeals.size}",
                                                color = AliExpressRed,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                // Central Badge: VS
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0F172A))
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

                                // Right Header: Shopee
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
                                                text = "${shopeeDeals.size}",
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
                        }

                        // Synchronized Columns: AliExpress on the left, Shopee on the right
                        val maxRows = maxOf(aliDeals.size, shopeeDeals.size)
                        items(count = maxRows) { index ->
                            val aliItem = aliDeals.getOrNull(index)
                            val shopeeItem = shopeeDeals.getOrNull(index)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Left Column (AliExpress)
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

                                // Right Column (Shopee)
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
        }
    }
}

@Composable
private fun EmptyStoreDeals(
    storeName: String,
    onShowAllStores: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF1F5F9)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingBag,
                    contentDescription = null,
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Nenhuma oferta exclusiva no $storeName",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Não encontramos ofertas acima de 40% OFF nesta loja no momento. Veja todas as lojas ou tente atualizar.",
                fontSize = 12.5.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onShowAllStores,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Ver Todas as Lojas", fontSize = 12.5.sp)
                }
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = AliExpressRed),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Atualizar", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
