package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.model.SellerBadgeType
import com.example.data.model.SellerTrustInfo
import com.example.data.model.StoreType
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.ShopeeOrange

/**
 * Selo de Vendedor Verificado com pontuação de confiabilidade.
 */
@Composable
fun VerifiedSellerBadge(
    sellerTrust: SellerTrustInfo,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val (bgColor, borderColor, contentColor, icon) = when (sellerTrust.badgeType) {
        SellerBadgeType.OFFICIAL -> Quadruple(
            Color(0xFFFEF3C7),
            Color(0xFFFDE68A),
            Color(0xFF92400E),
            "🛡️"
        )
        SellerBadgeType.VERIFIED -> Quadruple(
            Color(0xFFECFDF5),
            Color(0xFFA7F3D0),
            Color(0xFF047857),
            "✓"
        )
        SellerBadgeType.TRUSTED -> Quadruple(
            Color(0xFFF5F3FF),
            Color(0xFFDDD6FE),
            Color(0xFF6D28D9),
            "★"
        )
        SellerBadgeType.STANDARD -> Quadruple(
            Color(0xFFF1F5F9),
            Color(0xFFCBD5E1),
            Color(0xFF475569),
            "•"
        )
    }

    val labelText = if (compact) {
        when (sellerTrust.badgeType) {
            SellerBadgeType.OFFICIAL -> "Loja Oficial"
            SellerBadgeType.VERIFIED -> "Verificado ${sellerTrust.trustScore}%"
            SellerBadgeType.TRUSTED -> "Confiável ${sellerTrust.trustScore}%"
            SellerBadgeType.STANDARD -> "${sellerTrust.trustScore}% Confiável"
        }
    } else {
        when (sellerTrust.badgeType) {
            SellerBadgeType.OFFICIAL -> "🛡️ Loja Oficial • ${sellerTrust.trustScore}% Confiabilidade"
            SellerBadgeType.VERIFIED -> "✓ Vendedor Verificado • ${sellerTrust.trustScore}%"
            SellerBadgeType.TRUSTED -> "★ Vendedor Confiável • ${sellerTrust.trustScore}%"
            SellerBadgeType.STANDARD -> "${sellerTrust.trustScore}% Confiabilidade"
        }
    }

    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        border = BorderStroke(0.6.dp, borderColor),
        modifier = modifier.testTag("seller_badge_${sellerTrust.sellerName.replace(" ", "_")}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (compact) 1.5.dp else 2.5.dp)
        ) {
            Text(
                text = "$icon $labelText",
                color = contentColor,
                fontSize = if (compact) 8.sp else 9.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Bottom Sheet Explicativa com Detalhamento Completo do Algoritmo de Confiabilidade do Vendedor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellerTrustSheet(
    product: Product,
    onDismiss: () -> Unit
) {
    val trust = product.sellerTrust ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val storeColor = if (product.store == StoreType.ALIEXPRESS) AliExpressRed else ShopeeOrange

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("sheet_seller_trust")
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (product.store == StoreType.ALIEXPRESS) Color(0xFFFEE2E2) else Color(0xFFFFEDD5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (product.store == StoreType.ALIEXPRESS) Icons.Default.ShoppingCart else Icons.Default.ShoppingBag,
                            contentDescription = null,
                            tint = storeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Auditoria de Vendedor IA",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = trust.sellerName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp).testTag("btn_close_seller_trust")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = Color(0xFF64748B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Score Highlight Card
            val (badgeBg, badgeBorder, badgeTextColor, badgeTitle) = when (trust.badgeType) {
                SellerBadgeType.OFFICIAL -> Quadruple(
                    Color(0xFFFEF9C3),
                    Color(0xFFFDE047),
                    Color(0xFF854D0E),
                    "🛡️ Loja Oficial Verificada"
                )
                SellerBadgeType.VERIFIED -> Quadruple(
                    Color(0xFFECFDF5),
                    Color(0xFFA7F3D0),
                    Color(0xFF047857),
                    "✓ Selo de Vendedor Verificado"
                )
                SellerBadgeType.TRUSTED -> Quadruple(
                    Color(0xFFF5F3FF),
                    Color(0xFFDDD6FE),
                    Color(0xFF6D28D9),
                    "★ Vendedor com Boa Reputação"
                )
                SellerBadgeType.STANDARD -> Quadruple(
                    Color(0xFFF8FAFC),
                    Color(0xFFE2E8F0),
                    Color(0xFF475569),
                    "Vendedor sob Monitoramento"
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = badgeBg),
                border = BorderStroke(1.dp, badgeBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = badgeTitle,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (trust.isVerified) {
                                "Aprovado no algoritmo antifraude e consistência de entregas do ScanShop."
                            } else {
                                "Vendedor regular com índice aceitável no marketplace."
                            },
                            fontSize = 11.5.sp,
                            color = badgeTextColor.copy(alpha = 0.85f),
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Circular score gauge
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(BorderStroke(2.dp, badgeTextColor), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${trust.trustScore}",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                color = badgeTextColor
                            )
                            Text(
                                text = "Score",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4 Metrics Grid
            Text(
                text = "Indicadores de Confiança",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricItem(
                    icon = Icons.Default.Star,
                    iconTint = Color(0xFFEAB308),
                    label = "Avaliação",
                    value = "★ %.1f".format(java.util.Locale.US, trust.rating),
                    sub = "${trust.positiveRatingPercent}% positivas",
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    icon = Icons.Default.ThumbUp,
                    iconTint = DealGreen,
                    label = "Histórico",
                    value = if (trust.salesCount >= 1000) "+${trust.salesCount / 1000}k" else "+${trust.salesCount}",
                    sub = "pedidos entregues",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricItem(
                    icon = if (trust.isNationalStock) Icons.Default.Public else Icons.Default.LocalShipping,
                    iconTint = Color(0xFF2563EB),
                    label = "Origem & Envio",
                    value = if (trust.isNationalStock) "Estoque Brasil 🇧🇷" else "Internacional",
                    sub = if (trust.hasDeliveryGuarantee) "Envio Garantido" else "Rastreamento",
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    icon = Icons.Default.Security,
                    iconTint = Color(0xFF7C3AED),
                    label = "Garantia",
                    value = "${trust.returnGuaranteeDays} dias",
                    sub = "Devolução grátis",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Algorithmic Reasons List
            if (trust.evaluationReasons.isNotEmpty()) {
                Text(
                    text = "Critérios Auditados pelo Algoritmo",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    trust.evaluationReasons.forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = DealGreen,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = reason,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("btn_close_seller_sheet"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Entendi",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        border = BorderStroke(0.6.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B)
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = sub,
                fontSize = 9.5.sp,
                color = Color(0xFF94A3B8)
            )
        }
    }
}
