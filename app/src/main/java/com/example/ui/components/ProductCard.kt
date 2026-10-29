package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.DealGreenBg
import com.example.ui.theme.DiscountBadge
import com.example.ui.theme.GoldStar
import com.example.ui.theme.ShopeeOrange
import com.example.ui.util.DeepLinkUtils

@Composable
fun StoreBadge(
    store: StoreType,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isAli = store == StoreType.ALIEXPRESS
    val bgColor = if (isAli) AliExpressRed else ShopeeOrange
    val label = store.displayName

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 2.dp else 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (isAli) Icons.Default.ShoppingCart else Icons.Default.ShoppingBag,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(if (compact) 10.dp else 12.dp)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = if (compact) 9.5.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun CompactProductCard(
    product: Product,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storeColor = if (product.store == StoreType.ALIEXPRESS) AliExpressRed else ShopeeOrange

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("compact_product_card_${product.id}")
            .clickable { DeepLinkUtils.openAffiliateProduct(context, product) },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            // Image Section: 1:1 Aspect Ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFFF1F5F9))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(product.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = product.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Store badge top-left
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    StoreBadge(store = product.store, compact = true)
                }

                // Discount badge bottom-left
                if (product.discountPercentage > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(DiscountBadge)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "-${product.discountPercentage}%",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Favorite button top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .shadow(elevation = 2.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(if (isFavorite) Color(0xFFFFF1F2) else Color.White)
                        .border(
                            BorderStroke(
                                width = if (isFavorite) 1.5.dp else 1.dp,
                                color = if (isFavorite) Color(0xFFE11D48) else Color(0xFFE2E8F0)
                            ),
                            shape = CircleShape
                        )
                        .clickable { onToggleFavorite() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remover dos favoritos" else "Adicionar aos favoritos",
                        tint = if (isFavorite) Color(0xFFE11D48) else Color(0xFF475569),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Product Details Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                // Badge de Relevância Inteligente
                if (product.isExactAiMatch) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFECFDF5))
                            .border(BorderStroke(0.5.dp, Color(0xFFA7F3D0)), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "★ Mais Relevante",
                            color = Color(0xFF047857),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Title (clean 2 lines)
                Text(
                    text = product.title,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Price Section: Prominent promo price + strikethrough original price
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = product.formattedPromoPrice,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = storeColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (product.originalPrice > product.promoPrice) {
                        Text(
                            text = product.formattedOriginalPrice,
                            fontSize = 10.5.sp,
                            color = Color(0xFF94A3B8),
                            textDecoration = TextDecoration.LineThrough,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Single Clean Metadata Line: Rating/Sales + Frete Grátis + Unica info Loja Oficial
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val ratingValue = when {
                        product.rating > 5.0 -> (product.rating / 20.0).coerceIn(1.0, 5.0)
                        product.rating > 0.0 -> product.rating
                        else -> 4.8
                    }
                    val formattedRating = String.format(java.util.Locale.US, "%.1f", ratingValue)
                    val salesText = when {
                        product.salesCount >= 1000 -> "${product.salesCount / 1000}k"
                        product.salesCount > 0 -> "${product.salesCount}"
                        else -> "100+"
                    }

                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Avaliação",
                        tint = GoldStar,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "$formattedRating • $salesText vend.",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Apenas UMA indicação de Loja Oficial (sem redundâncias)
                    val isOfficial = product.sellerTrust?.isVerified == true || product.brand == "Oficial"
                    if (isOfficial) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFECFDF5))
                                .border(BorderStroke(0.5.dp, Color(0xFFA7F3D0)), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "✓ Oficial",
                                color = Color(0xFF047857),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    } else if (product.freeShipping) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(DealGreenBg)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Frete Grátis",
                                color = DealGreen,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f, fill = true))

                // CTA Button - "Ir à Loja" de destaque, ergonômico e profissional
                Button(
                    onClick = { DeepLinkUtils.openAffiliateProduct(context, product) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .shadow(elevation = 1.5.dp, shape = RoundedCornerShape(10.dp))
                        .testTag("btn_buy_${product.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = storeColor
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Ir à Loja",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun UnifiedProductCard(
    product: Product,
    isWinner: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storeColor = if (product.store == StoreType.ALIEXPRESS) AliExpressRed else ShopeeOrange

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .testTag("unified_card_${product.id}")
            .clickable { DeepLinkUtils.openAffiliateProduct(context, product) },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Product Thumbnail with Store badge & Winner indicator
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF1F5F9))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(product.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = product.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                ) {
                    StoreBadge(store = product.store, compact = true)
                }

                if (isWinner) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(DealGreen)
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "MENOR PREÇO",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }

            // Info Column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    // Badge de Relevância Inteligente
                    if (product.isExactAiMatch) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 3.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFECFDF5))
                                .border(BorderStroke(0.5.dp, Color(0xFFA7F3D0)), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "★ Mais Relevante",
                                color = Color(0xFF047857),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    // Title
                    Text(
                        text = product.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    // Price Section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = product.formattedPromoPrice,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = storeColor
                        )
                        if (product.discountPercentage > 0) {
                            Text(
                                text = "-${product.discountPercentage}%",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = DiscountBadge
                            )
                        }
                        if (product.originalPrice > product.promoPrice) {
                            Text(
                                text = product.formattedOriginalPrice,
                                fontSize = 10.sp,
                                color = Color.Gray,
                                textDecoration = TextDecoration.LineThrough
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Clean Info Row: Avaliação + Única info Loja Oficial
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val ratingValue = when {
                            product.rating > 5.0 -> (product.rating / 20.0).coerceIn(1.0, 5.0)
                            product.rating > 0.0 -> product.rating
                            else -> 4.8
                        }
                        val formattedRating = String.format(java.util.Locale.US, "%.1f", ratingValue)
                        val salesText = when {
                            product.salesCount >= 1000 -> "${product.salesCount / 1000}k"
                            product.salesCount > 0 -> "${product.salesCount}"
                            else -> "100+"
                        }

                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Avaliação",
                            tint = GoldStar,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "$formattedRating • $salesText vend.",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )

                        // Apenas UMA info de Loja Oficial (sem redundâncias)
                        val isOfficial = product.sellerTrust?.isVerified == true || product.brand == "Oficial"
                        if (isOfficial) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFECFDF5))
                                    .border(BorderStroke(0.5.dp, Color(0xFFA7F3D0)), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "✓ Oficial",
                                    color = Color(0xFF047857),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (product.freeShipping) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "• Frete Grátis",
                                fontSize = 9.5.sp,
                                color = DealGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Action Row: "Ir à Loja" + Favorite Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { DeepLinkUtils.openAffiliateProduct(context, product) },
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .shadow(elevation = 1.5.dp, shape = RoundedCornerShape(8.dp))
                            .testTag("btn_buy_unified_${product.id}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = storeColor
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Ir à Loja ${product.store.displayName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = Color.White
                        )
                    }

                    Surface(
                        onClick = onToggleFavorite,
                        shape = CircleShape,
                        color = if (isFavorite) Color(0xFFFFF1F2) else Color.White,
                        border = BorderStroke(
                            width = if (isFavorite) 1.5.dp else 1.dp,
                            color = if (isFavorite) Color(0xFFE11D48) else Color(0xFFE2E8F0)
                        ),
                        shadowElevation = 1.dp,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remover dos favoritos" else "Adicionar aos favoritos",
                                tint = if (isFavorite) Color(0xFFE11D48) else Color(0xFF475569),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FavoriteProductCard(
    product: Product,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onSimulateDrop: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val storeColor = if (product.store == StoreType.ALIEXPRESS) AliExpressRed else ShopeeOrange
    val isPriceDropped = product.hasPriceDropAlert || product.priceDropPercentage >= 10.0

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("fav_card_${product.id}")
            .clickable { DeepLinkUtils.openAffiliateProduct(context, product) },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isPriceDropped) 1.5.dp else 1.dp,
            color = if (isPriceDropped) AliExpressRed else Color(0xFFE4E7EC)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isPriceDropped) Color(0xFFFFF7F7) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPriceDropped) 2.dp else 0.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Price drop alert top banner if drop > 10%
            if (isPriceDropped) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFEE2E2),
                    border = BorderStroke(0.5.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                                contentDescription = null,
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "QUEDA DE ${product.priceDropPercentage.toInt()}% DETECTADA!",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFB91C1C)
                            )
                        }

                        val savedFormatted = String.format("R$ %.2f", product.initialSavedPrice).replace('.', ',')
                        Text(
                            text = "De: $savedFormatted",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF991B1B)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFEEEEEE))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(product.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = product.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    ) {
                        StoreBadge(store = product.store, compact = true)
                    }

                    if (isPriceDropped) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0xFFDC2626))
                                .padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "-${product.priceDropPercentage.toInt()}% QUEDA",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    } else if (product.priceDropNotice != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(DealGreen)
                                .padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "MONITORADO",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Middle Info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = product.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = product.formattedPromoPrice,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isPriceDropped) Color(0xFFDC2626) else storeColor
                        )
                        if (isPriceDropped) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFDC2626))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "-${product.priceDropPercentage.toInt()}%",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        } else if (product.discountPercentage > 0) {
                            Text(
                                text = "-${product.discountPercentage}% OFF",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = DiscountBadge
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val priceHistoryText = if (isPriceDropped) {
                        "Preço inicial: " + String.format("R$ %.2f", product.initialSavedPrice).replace('.', ',')
                    } else {
                        "Menor 30d: " + String.format("R$ %.2f", product.lowest30DaysPrice).replace('.', ',')
                    }

                    Text(
                        text = priceHistoryText,
                        fontSize = 10.sp,
                        color = if (isPriceDropped) Color(0xFF991B1B) else Color.Gray,
                        maxLines = 1
                    )
                }

                // Delete button
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remover favorito",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Buy & Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { DeepLinkUtils.openAffiliateProduct(context, product) },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .shadow(elevation = 1.5.dp, shape = RoundedCornerShape(8.dp))
                        .testTag("btn_buy_fav_${product.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = storeColor
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Ir à Loja " + product.store.displayName + " (" + product.formattedPromoPrice + ")",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }

                if (onSimulateDrop != null) {
                    Surface(
                        onClick = onSimulateDrop,
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFEF3C7),
                        border = BorderStroke(0.5.dp, Color(0xFFFCD34D)),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("btn_test_drop_${product.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "⚡ -15%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { DeepLinkUtils.shareProduct(context, product) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF1F5F9))
                        .border(BorderStroke(0.5.dp, Color(0xFFCBD5E1)), RoundedCornerShape(8.dp))
                        .testTag("btn_share_fav_${product.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Compartilhar",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
