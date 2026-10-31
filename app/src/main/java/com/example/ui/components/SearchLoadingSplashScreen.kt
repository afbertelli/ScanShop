package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.ShopeeOrange
import kotlinx.coroutines.delay

/**
 * Modal Popup Flutuante em Destaque na frente do app durante a busca em tempo real.
 * Exibe animação fluida de ~2.4s com auditoria e checklist das plataformas.
 * Perfeitamente dimensionado para caber em qualquer tela sem cortes.
 */
@Composable
fun SearchLoadingSplashScreen(
    query: String,
    modifier: Modifier = Modifier
) {
    // Rotação suave do anel de pulso
    val infiniteTransition = rememberInfiniteTransition(label = "modal_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate_ring"
    )

    // Efeito de pulso de escala do logo central
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Barra de progresso animada de 0% a 100% durante os 2.4s
    val animatedProgress = remember { Animatable(0.08f) }
    var activeStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2400, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(Unit) {
        delay(600)
        activeStep = 1 // Filtro Anti-Acessórios
        delay(600)
        activeStep = 2 // Reputação dos Vendedores
        delay(600)
        activeStep = 3 // Balanço e Menor Preço
        delay(500)
        activeStep = 4 // Concluído
    }

    // Fundo escurecido translúcido cobrindo a tela inteira
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_loading_modal_overlay")
            .background(Color(0x990F172A))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        // Card Popup Flutuante centralizado e compacto
        Card(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .padding(horizontal = 12.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(22.dp),
                    spotColor = Color.Black.copy(alpha = 0.4f)
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Logo animado compacto
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .rotate(rotation)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        AliExpressRed,
                                        ShopeeOrange,
                                        Color(0xFF7E22CE),
                                        AliExpressRed
                                    )
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )

                    Box(modifier = Modifier.scale(pulseScale)) {
                        ScanShopLogo(size = 42.dp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Título do Modal
                Text(
                    text = "Auditando Ofertas em Tempo Real",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "Eliminando vendedores ruins e produtos mal avaliados...",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AliExpressRed,
                    textAlign = TextAlign.Center
                )

                // 3. Chip de termo pesquisado
                if (query.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(0.5.dp, Color(0xFFBFDBFE))
                    ) {
                        Text(
                            text = "Termo: \"$query\"",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1D4ED8),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 4. Barra de Progresso visual
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFE2E8F0))
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress.value },
                        modifier = Modifier.fillMaxSize(),
                        color = ShopeeOrange,
                        trackColor = Color(0xFFE2E8F0)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 5. Checklist de Auditoria
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(0.5.dp, Color(0xFFE2E8F0))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModalStepItem(
                            icon = Icons.Default.Sync,
                            title = "Consultando Shopee BR & AliExpress em paralelo",
                            subtitle = "Conectando às APIs em tempo real",
                            isDone = activeStep >= 1,
                            isActive = activeStep == 0
                        )

                        ModalStepItem(
                            icon = Icons.Default.FilterAlt,
                            title = "Filtro de Falsos Amigos & Acessórios Avulsos",
                            subtitle = "Priorizando o produto principal pesquisado",
                            isDone = activeStep >= 2,
                            isActive = activeStep == 1
                        )

                        ModalStepItem(
                            icon = Icons.Default.Security,
                            title = "Auditoria de Vendedores & Reputação Oficial",
                            subtitle = "Avaliando selos oficiais e notas dos compradores",
                            isDone = activeStep >= 3,
                            isActive = activeStep == 2
                        )

                        ModalStepItem(
                            icon = Icons.Default.PriceCheck,
                            title = "Balanço 50/50 & Menor Preço Garantido",
                            subtitle = "Calculando economia e soft ranking",
                            isDone = activeStep >= 4,
                            isActive = activeStep == 3
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 6. Rodapé Roxo em destaque — 100% nítido, completo e visível
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF3E8FF),
                    border = BorderStroke(0.5.dp, Color(0xFFD8B4FE))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF7E22CE),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Busca ao vivo com links 100% seguros",
                            fontSize = 10.5.sp,
                            color = Color(0xFF6B21A8),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalStepItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDone -> DealGreen.copy(alpha = 0.15f)
                        isActive -> ShopeeOrange.copy(alpha = 0.15f)
                        else -> Color(0xFFE2E8F0)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = DealGreen,
                    modifier = Modifier.size(15.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) ShopeeOrange else Color(0xFF94A3B8),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = if (isActive || isDone) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isActive || isDone) Color(0xFF0F172A) else Color(0xFF64748B),
                maxLines = 1
            )
            Text(
                text = subtitle,
                fontSize = 9.sp,
                color = if (isActive) AliExpressRed else Color(0xFF94A3B8),
                maxLines = 1
            )
        }
    }
}
