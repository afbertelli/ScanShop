package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.MatchedProduct
import com.example.data.model.PromoPipelineStage
import com.example.data.model.StoreFetchReport
import com.example.data.model.StoreType
import com.example.ui.components.StoreSectionCard
import com.example.ui.components.shareProduct
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.ShopeeOrange
import com.example.ui.util.DeepLinkUtils
import com.example.ui.viewmodel.PromoUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PromoScreen(
    state: PromoUiState,
    onRefresh: () -> Unit,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: ((MatchedProduct) -> Unit)? = null,
    onLoadMoreStore: (StoreType) -> Unit = {},
    onRetryStore: (StoreType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val orderedStores = remember {
        listOf(StoreType.MERCADO_LIVRE, StoreType.SHOPEE, StoreType.ALIEXPRESS)
    }

    val updatedLabel = remember(state.sessionResult?.updatedAtMillis) {
        val ts = state.sessionResult?.updatedAtMillis
        if (ts != null && ts > 0L) {
            "Atualizado às ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))}"
        } else {
            "Mais vendidos da semana por loja"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("promo_screen")
    ) {
        // Header strip matching SearchBarComponent footprint with Refresh button instead of search input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(BorderStroke(0.5.dp, Color(0xFFE2E8F0)))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFFFFF1EE)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalOffer,
                        contentDescription = null,
                        tint = AliExpressRed,
                        modifier = Modifier
                            .padding(7.dp)
                            .size(16.dp)
                    )
                }
                Column {
                    Text(
                        text = "Melhores da Semana",
                        fontSize = 13.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = updatedLabel,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(AliExpressRed, ShopeeOrange)
                        )
                    )
                    .clickable(enabled = !state.isRefreshing) { onRefresh() }
                    .padding(horizontal = 12.dp)
                    .testTag("btn_refresh_promos"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Atualizar promoções",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = if (state.isRefreshing) "Atualizando" else "Atualizar",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        when {
            state.stage is PromoPipelineStage.Loading && state.sessionResult == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = AliExpressRed,
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "Buscando os melhores da semana",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Consultando ofertas em alta no Mercado Livre, Shopee e AliExpress.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFFFF1EE),
                                border = BorderStroke(1.dp, AliExpressRed.copy(alpha = 0.30f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("promo_waiting_highlight_banner")
                            ) {
                                Text(
                                    text = stringResource(R.string.search_waiting_highlight),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AliExpressRed,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            state.stage is PromoPipelineStage.Error && state.sessionResult == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, Color(0xFFFECACA))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Não foi possível carregar as promoções",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = (state.stage as PromoPipelineStage.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            else -> {
                val result = state.sessionResult
                val reportsByStore = remember(result?.storeReports) {
                    result?.storeReports?.associateBy { it.store }.orEmpty()
                }
                val productsByStore = remember(result?.products) {
                    result?.products?.groupBy { it.store }.orEmpty()
                }

                val openStoreHandler = remember(context) {
                    { product: MatchedProduct ->
                        DeepLinkUtils.openAffiliateUrl(
                            context = context,
                            store = product.store,
                            affiliateUrl = product.affiliateUrl,
                            deepLinkUrl = product.deepLinkUrl
                        )
                    }
                }
                val shareProductHandler = remember(context) {
                    { product: MatchedProduct ->
                        shareProduct(context, product)
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val targetSectionHeight = ((maxHeight - 28.dp) / 3f).coerceAtLeast(134.dp)
                    val targetCardHeight = (targetSectionHeight - 42.dp).coerceAtLeast(96.dp)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("promo_results_list"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(
                            items = orderedStores,
                            key = { it.name }
                        ) { storeType ->
                            val report = reportsByStore[storeType] ?: StoreFetchReport(
                                store = storeType,
                                rawCandidatesCount = 0,
                                approvedCount = 0
                            )
                            val storeProducts = productsByStore[storeType].orEmpty()
                            val storeLoadMore = remember(storeType, onLoadMoreStore) { { onLoadMoreStore(storeType) } }
                            val storeRetry = remember(storeType, onRetryStore) { { onRetryStore(storeType) } }
                            StoreSectionCard(
                                store = storeType,
                                report = report,
                                products = storeProducts,
                                emptyFallbackLabel = "Nenhuma oferta semanal disponível em ${storeType.displayName} no momento. Toque em Atualizar.",
                                onOpenStore = openStoreHandler,
                                onShare = shareProductHandler,
                                favoriteIds = favoriteIds,
                                onToggleFavorite = onToggleFavorite,
                                isLoadingMore = storeType in state.loadingMoreStores,
                                hasMore = state.storeHasMore[storeType] ?: false,
                                onLoadMore = storeLoadMore,
                                onRetryStore = storeRetry,
                                minSectionHeight = targetSectionHeight,
                                cardHeight = targetCardHeight
                            )
                        }
                    }
                }
            }
        }
    }
}
