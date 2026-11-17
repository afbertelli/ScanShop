package com.example.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MatchedProduct
import com.example.data.model.StoreFetchReport
import com.example.data.model.StoreType
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.MercadoLivreBlue
import com.example.ui.theme.MercadoLivreYellow
import com.example.ui.theme.ShopeeOrange

@Composable
fun StoreSectionCard(
    store: StoreType,
    report: StoreFetchReport,
    products: List<MatchedProduct>,
    emptyFallbackLabel: String,
    onOpenStore: (MatchedProduct) -> Unit,
    onShare: (MatchedProduct) -> Unit,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: ((MatchedProduct) -> Unit)? = null,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onRetryStore: (() -> Unit)? = null,
    minSectionHeight: androidx.compose.ui.unit.Dp? = null,
    cardHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier
) {
    val (badgeBg, badgeFg, accentBorder) = when (store) {
        StoreType.MERCADO_LIVRE -> Triple(MercadoLivreYellow, MercadoLivreBlue, MercadoLivreBlue.copy(alpha = 0.28f))
        StoreType.SHOPEE -> Triple(ShopeeOrange, Color.White, ShopeeOrange.copy(alpha = 0.35f))
        StoreType.ALIEXPRESS -> Triple(AliExpressRed, Color.White, AliExpressRed.copy(alpha = 0.35f))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { base ->
                if (minSectionHeight != null) base.heightIn(min = minSectionHeight) else base
            }
            .testTag("store_section_${store.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accentBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = badgeBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storefront,
                            contentDescription = null,
                            tint = badgeFg,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = store.displayName,
                            color = badgeFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "${products.size} ofertas",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            if (products.isNotEmpty()) {
                val firstId = products.first().id
                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
                val latestHasMore by rememberUpdatedState(hasMore)
                val latestIsLoadingMore by rememberUpdatedState(isLoadingMore)
                val latestProductCount by rememberUpdatedState(products.size)
                val latestOnLoadMore by rememberUpdatedState(onLoadMore)
                var lastAutoTriggeredCount by remember(firstId) { mutableIntStateOf(-1) }

                LaunchedEffect(rowState, firstId) {
                    snapshotFlow {
                        val layoutInfo = rowState.layoutInfo
                        val total = layoutInfo.totalItemsCount
                        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        Triple(total, lastVisible, latestIsLoadingMore)
                    }
                        .distinctUntilChanged()
                        .collect { (total, lastVisible, loadingNow) ->
                            if (!latestHasMore || loadingNow || latestProductCount == 0) return@collect
                            val nearEnd = total > 0 && lastVisible >= (total - 2).coerceAtLeast(0)
                            if (nearEnd && lastAutoTriggeredCount != latestProductCount) {
                                lastAutoTriggeredCount = latestProductCount
                                latestOnLoadMore()
                            }
                        }
                }

                LazyRow(
                    state = rowState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("carousel_${store.name.lowercase()}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    items(products, key = { it.id }) { product ->
                        val itemOpenStore = remember(product.id, onOpenStore) { { onOpenStore(product) } }
                        val itemShare = remember(product.id, onShare) { { onShare(product) } }
                        val itemToggleFavorite = remember(product.id, onToggleFavorite) {
                            onToggleFavorite?.let { cb -> { cb(product) } }
                        }
                        CarouselProductMatchCard(
                            product = product,
                            onOpenStore = itemOpenStore,
                            onShare = itemShare,
                            isFavorite = product.id in favoriteIds,
                            onToggleFavorite = itemToggleFavorite,
                            cardHeight = cardHeight
                        )
                    }
                    if (isLoadingMore) {
                        item(key = "loading_more_${store.name}") {
                            Card(
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(cardHeight ?: 152.dp)
                                    .testTag("loading_more_${store.name.lowercase()}"),
                                shape = RoundedCornerShape(11.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ),
                                border = BorderStroke(1.dp, accentBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = badgeBg,
                                        strokeWidth = 2.5.dp
                                    )
                                    Text(
                                        text = "Mais...",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    } else if (hasMore) {
                        item(key = "load_more_btn_${store.name}") {
                            Card(
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(cardHeight ?: 132.dp)
                                    .clickable {
                                        lastAutoTriggeredCount = products.size
                                        onLoadMore()
                                    }
                                    .testTag("btn_load_more_${store.name.lowercase()}"),
                                shape = RoundedCornerShape(11.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                ),
                                border = BorderStroke(1.dp, accentBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Carregar mais ${store.displayName}",
                                        tint = badgeBg,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Mais\nofertas",
                                        fontSize = 9.5.sp,
                                        lineHeight = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(0.8.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base ->
                            if (cardHeight != null) base.heightIn(min = cardHeight) else base
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                    ) {
                        val emptyMsg = report.statusMessage ?: emptyFallbackLabel
                        Text(
                            text = emptyMsg,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = Color(0xFF475569),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onRetryStore != null) {
                            OutlinedButton(
                                onClick = onRetryStore,
                                enabled = !isLoadingMore,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, accentBorder),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("btn_retry_${store.name.lowercase()}")
                            ) {
                                val retryColor = if (store == StoreType.MERCADO_LIVRE) MercadoLivreBlue else badgeBg
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = retryColor,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = retryColor,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isLoadingMore) "Tentando..." else "Tentar novamente",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = retryColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


fun shareProduct(context: Context, product: MatchedProduct) {
    val shareBody = "${product.title}\nLoja: ${product.store.displayName}\n${product.affiliateUrl}"
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, product.title)
        putExtra(Intent.EXTRA_TEXT, shareBody)
    }
    val chooser = Intent.createChooser(sendIntent, "Compartilhar produto")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
