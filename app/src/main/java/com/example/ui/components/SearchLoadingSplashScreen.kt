package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
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
 * Tela de espera / Splash interativa durante a busca em tempo real.
 * Substitui o indicador circular simples e comunica ativamente todas as etapas
 * que o motor inteligente do ScanShop realiza nos 2 a 4 segundos de busca.
 */
@Composable
fun SearchLoadingSplashScreen(
    query: String,
    modifier: Modifier = Modifier
) {
    // Rotação suave do anel de pulso
    val infiniteTransition = rememberInfiniteTransition(label = "splash_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween( durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate_ring"
    )

    // Efeito de pulso de escala do logo central
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Avanço visual dos passos do algoritmo do ScanShop
    var activeStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // Passo 0: Conexão
        delay(600)
        activeStep = 1 // Filtro Anti-Acessórios
        delay(900)
        activeStep = 2 // Reputação e Vendedores Ruins
        delay(1000)
        activeStep = 3 // Menor Preço e Balanço
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_loading_splash_screen")
            .background(Color(0xFFFAFAFC)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1. Logo com anel animado de gradiente duplo (AliExpress Red & Shopee Orange)
            Box(
                modifier = Modifier.size(110.dp),
                contentAlignment = Alignment.Center
            ) {
                // Anel pulsante rotativo
                Box(
                    modifier = Modifier
                        .size(104.dp)
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

                // Fundo branco interno para efeito de aro fino
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )

                // Logo ScanShop com leve respiração
                Box(modifier = Modifier.scale(pulseScale)) {
                    ScanShopLogo(size = 72.dp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Título principal solicitado pelo usuário
            Text(
                text = "Aguarde: o ScanShop está separando os melhores produtos",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B),
                textAlign = TextAlign.Center,
                lineHeight = 23.sp,
                modifier = Modifier.fillMaxWidth(0.92f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 3. Subtítulo com destaque para eliminação de maus vendedores
            Text(
                text = "Eliminando vendedores ruins e produtos mal avaliados...",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = AliExpressRed,
                textAlign = TextAlign.Center
            )

            if (query.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                ) {
                    Text(
                        text = "Termo: \"$query\"",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D4ED8),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 4. Barra de progresso contínua com gradiente oficial
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFE2E8F0))
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = ShopeeOrange,
                    trackColor = Color(0xFFE2E8F0)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. Card com checklist dos passos da inteligência em tempo real
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SplashStepItem(
                        icon = Icons.Default.Sync,
                        title = "Consultando Shopee BR & AliExpress em paralelo",
                        description = "Requisições simultâneas em menos de 4 segundos",
                        isDone = activeStep >= 1,
                        isActive = activeStep == 0
                    )

                    SplashStepItem(
                        icon = Icons.Default.FilterAlt,
                        title = "Filtro Anti-Acessórios & Itens Desconexos",
                        description = "Eliminando capas, películas, suportes e peças incompatíveis",
                        isDone = activeStep >= 2,
                        isActive = activeStep == 1
                    )

                    SplashStepItem(
                        icon = Icons.Default.Security,
                        title = "Auditoria de Vendedores & Reputação",
                        description = "Descartando lojas sem reputação ou com notas baixas",
                        isDone = activeStep >= 3,
                        isActive = activeStep == 2
                    )

                    SplashStepItem(
                        icon = Icons.Default.PriceCheck,
                        title = "Balanço 50/50 & Menor Preço Garantido",
                        description = "Distribuição simétrica e cálculo do melhor custo-benefício",
                        isDone = activeStep >= 3,
                        isActive = activeStep == 3
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 6. Rodapé de confiança e transparência
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF7E22CE),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Busca em tempo real com links 100% oficiais e seguros",
                    fontSize = 11.5.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SplashStepItem(
    icon: ImageVector,
    title: String,
    description: String,
    isDone: Boolean,
    isActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Indicador de status
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDone -> DealGreen.copy(alpha = 0.15f)
                        isActive -> ShopeeOrange.copy(alpha = 0.15f)
                        else -> Color(0xFFF1F5F9)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = DealGreen,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) ShopeeOrange else Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = if (isActive || isDone) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive || isDone) Color(0xFF1E293B) else Color(0xFF64748B)
            )
            Text(
                text = description,
                fontSize = 10.5.sp,
                color = if (isActive) AliExpressRed else Color(0xFF94A3B8),
                lineHeight = 13.sp
            )
        }
    }
}
