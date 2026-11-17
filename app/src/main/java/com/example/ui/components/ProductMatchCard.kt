package com.example.ui.components

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.example.data.model.MatchVerdict
import com.example.data.model.MatchedProduct
import com.example.data.model.StoreType
import com.example.data.search.HtmlTextDecoder
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.DealGreenBg
import com.example.ui.theme.DealGreenBorder
import com.example.ui.theme.MercadoLivreBlue
import com.example.ui.theme.MercadoLivreYellow
import com.example.ui.theme.ShopeeOrange
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductMatchCard(
    product: MatchedProduct,
    onOpenStore: () -> Unit,
    onShare: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showNeuralDetails by remember(product.id) { mutableStateOf(false) }

    val assertPercent = (product.assertivenessScore * 100f).roundToInt().coerceIn(0, 100)
    val isExact = product.matchVerdict == MatchVerdict.EXACT_MATCH

    val (storeBg, storeFg) = when (product.store) {
        StoreType.MERCADO_LIVRE -> MercadoLivreYellow to MercadoLivreBlue
        StoreType.SHOPEE -> ShopeeOrange to Color.White
        StoreType.ALIEXPRESS -> AliExpressRed to Color.White
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenStore)
            .testTag("product_match_card_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isExact) 1.2.dp else 1.dp,
            color = if (isExact) DealGreenBorder else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                val fullImageUrl = remember(product.imageUrl) {
                    HtmlTextDecoder.normalizeImageUrl(product.imageUrl)
                }
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF8FAFC)),
                    contentAlignment = Alignment.Center
                ) {
                    if (fullImageUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = fullImageUrl,
                            contentDescription = product.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF1F5F9)))
                            },
                            error = { err ->
                                Log.w("ProductMatchCard", "Image load failed: $fullImageUrl", err.result.throwable)
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ShoppingBag,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = storeBg
                        ) {
                            Text(
                                text = product.store.displayName,
                                color = storeFg,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isExact) DealGreenBg else Color(0xFFF1F5F9),
                            border = BorderStroke(
                                0.8.dp,
                                if (isExact) DealGreenBorder else Color(0xFFCBD5E1)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = if (isExact) DealGreen else Color(0xFF334155),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isExact) "$assertPercent% Exato" else "$assertPercent% Variante",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isExact) DealGreen else Color(0xFF334155)
                                )
                            }
                        }
                    }

                    val cleanTitle = remember(product.id, product.title, product.productUrl, product.store) {
                        sanitizeCardTitle(product.title, product.productUrl, product.store)
                    }
                    Text(
                        text = cleanTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (product.priceText.isNotBlank()) {
                        Text(
                            text = product.priceText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = DealGreen,
                            maxLines = 1
                        )
                    }

                    if (product.matchedSpecs.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            product.matchedSpecs.take(4).forEach { spec ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF8FAFC),
                                    border = BorderStroke(0.6.dp, Color(0xFFE2E8F0))
                                ) {
                                    Text(
                                        text = spec,
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF334155),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFF8FAFC),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showNeuralDetails = !showNeuralDetails }
                    .testTag("neural_reason_toggle_${product.id}")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = DealGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = product.matchReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                                maxLines = if (showNeuralDetails) 4 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = if (showNeuralDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Ver detalhes da rede neural",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = showNeuralDetails) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            NeuralMetricBar("Semântica & Embeddings", product.featureBreakdown.semanticEmbeddingScore)
                            NeuralMetricBar("Modelo & Código Exato", product.featureBreakdown.alphanumericModelScore)
                            NeuralMetricBar("Marca & Linha", product.featureBreakdown.brandAndLineScore)
                            NeuralMetricBar("Núcleo do Item (Anti-Acessório)", product.featureBreakdown.itemHeadNounScore)
                            NeuralMetricBar("Qualidades & Specs", product.featureBreakdown.specQualityScore)
                            NeuralMetricBar("Domínio / Categoria", product.featureBreakdown.domainAlignmentScore)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenStore,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AliExpressRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_open_store_${product.id}")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Abrir na ${product.store.displayName}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onToggleFavorite != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isFavorite) Color(0xFFFFF1EE) else Color(0xFFF8FAFC),
                        border = BorderStroke(
                            1.dp,
                            if (isFavorite) AliExpressRed.copy(alpha = 0.45f) else Color(0xFFE2E8F0)
                        )
                    ) {
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_favorite_${product.id}")
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remover dos favoritos" else "Salvar nos favoritos",
                                tint = if (isFavorite) AliExpressRed else Color(0xFF475569)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_share_${product.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartilhar produto",
                            tint = Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CarouselProductMatchCard(
    product: MatchedProduct,
    onOpenStore: () -> Unit,
    onShare: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    cardHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier
) {
    val effectiveHeight = cardHeight ?: 132.dp
    val imageSize = (effectiveHeight - 68.dp).coerceIn(34.dp, 72.dp)
    val titleMaxLines = when {
        effectiveHeight >= 152.dp -> 5
        effectiveHeight >= 122.dp -> 4
        else -> 3
    }
    val storeBtnColor = when (product.store) {
        StoreType.MERCADO_LIVRE -> MercadoLivreBlue
        StoreType.SHOPEE -> ShopeeOrange
        StoreType.ALIEXPRESS -> AliExpressRed
    }

    Card(
        modifier = modifier
            .width(184.dp)
            .let { base ->
                if (cardHeight != null) base.height(cardHeight) else base
            }
            .clickable(onClick = onOpenStore)
            .testTag("carousel_product_card_${product.id}"),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    if (cardHeight != null) base.weight(1f) else base
                }
                .padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            val carouselImageUrl = remember(product.imageUrl) {
                HtmlTextDecoder.normalizeImageUrl(product.imageUrl)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base ->
                        if (cardHeight != null) base.weight(1f) else base
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(imageSize)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF8FAFC)),
                    contentAlignment = Alignment.Center
                ) {
                    if (carouselImageUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = carouselImageUrl,
                            contentDescription = product.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(imageSize),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF1F5F9)))
                            },
                            error = { err ->
                                Log.w("CarouselProductCard", "Image load failed: $carouselImageUrl", err.result.throwable)
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ShoppingBag,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                val cleanCarouselTitle = remember(product.id, product.title, product.productUrl, product.store) {
                    sanitizeCardTitle(product.title, product.productUrl, product.store)
                }
                Text(
                    text = cleanCarouselTitle,
                    fontSize = 10.sp,
                    lineHeight = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            val hasPrice = product.priceText.isNotBlank()
            val missingPriceLabel = if (product.store == StoreType.MERCADO_LIVRE) {
                "Ver preço no ML"
            } else {
                "Ver preço"
            }
            Text(
                text = if (hasPrice) product.priceText else missingPriceLabel,
                fontSize = if (hasPrice) 12.sp else 10.sp,
                lineHeight = 13.5.sp,
                fontWeight = if (hasPrice) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = when {
                    hasPrice -> DealGreen
                    product.store == StoreType.MERCADO_LIVRE -> MercadoLivreBlue
                    else -> MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product_price_${product.id}")
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = storeBtnColor,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = onOpenStore)
                        .testTag("btn_open_store_${product.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Abrir",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (onToggleFavorite != null) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (isFavorite) Color(0xFFFFF1EE) else Color(0xFFF8FAFC),
                        border = BorderStroke(
                            1.dp,
                            if (isFavorite) AliExpressRed.copy(alpha = 0.45f) else Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier
                            .width(32.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onToggleFavorite)
                            .testTag("btn_favorite_${product.id}")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remover dos favoritos" else "Salvar nos favoritos",
                                tint = if (isFavorite) AliExpressRed else Color(0xFF475569),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .width(32.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = onShare)
                        .testTag("btn_share_${product.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartilhar produto",
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NeuralMetricBar(
    label: String,
    value: Float
) {
    val pct = (value.coerceIn(0f, 1f) * 100f).roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 10.5.sp, color = Color(0xFF475569))
            Text(
                text = "$pct%",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (pct >= 70) DealGreen else Color(0xFF334155)
            )
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (pct >= 70) DealGreen else ShopeeOrange,
            trackColor = Color(0xFFE2E8F0)
        )
    }
}

private val cardUrlInTextRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val cardWwwInTextRegex = Regex("""\bwww\.[^\s]+""", RegexOption.IGNORE_CASE)
private val cardWsRegex = Regex("\\s+")
private val cardSlugMlbPrefixRegex = Regex("""^MLB-?\d+-""", RegexOption.IGNORE_CASE)
private val cardBareMlbIdRegex = Regex("""^MLB\d+$""", RegexOption.IGNORE_CASE)

private fun sanitizeCardTitle(rawTitle: String, productUrl: String, store: StoreType): String {
    val decoded = HtmlTextDecoder.decode(rawTitle).trim()
    val lower = decoded.lowercase()
    val looksLikeLink = decoded.isBlank() ||
        lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("www.") ||
        lower.contains("://") ||
        lower.contains("mercadolivre.com") ||
        lower.contains("mercadolibre.com") ||
        lower.contains(" › ") ||
        (!decoded.contains(' ') && (decoded.contains('/') || lower.contains(".com")))

    if (!looksLikeLink) {
        return decoded
            .replace(cardUrlInTextRegex, "")
            .replace(cardWwwInTextRegex, "")
            .replace(cardWsRegex, " ")
            .trim()
    }

    val noQuery = productUrl.substringBefore("?").substringBefore("#").trimEnd('/')
    val segments = noQuery.split('/').filter { it.isNotBlank() && !it.startsWith("http", true) }
    val pIdx = segments.indexOfFirst { it.equals("p", ignoreCase = true) }
    val slug = when {
        pIdx > 0 -> segments[pIdx - 1]
        else -> segments.firstOrNull { seg ->
            cardSlugMlbPrefixRegex.containsMatchIn(seg)
        } ?: segments.filterNot {
            it.contains("mercadolivre", true) ||
                it.contains("shopee", true) ||
                it.contains("aliexpress", true) ||
                it.equals("p", true) ||
                cardBareMlbIdRegex.matches(it)
        }.maxByOrNull { it.length }.orEmpty()
    }
    val derived = HtmlTextDecoder.decodeUrlSlugToTitle(slug)
    if (derived.length >= 5) return derived
    return "Produto ${store.displayName}"
}
