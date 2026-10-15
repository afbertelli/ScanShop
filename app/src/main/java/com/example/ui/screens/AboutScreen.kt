package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ScanShopLogo
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.ShopeeOrange

@Composable
fun AboutScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToDeals: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("about_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Brand Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // App Logo enlarged to 80.dp matching app icon
                    ScanShopLogo(
                        size = 80.dp,
                        modifier = Modifier.testTag("about_brand_logo")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "ScanShop",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF0F172A)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Comparador Inteligente de Preços",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AliExpressRed
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text(
                            text = "Versão 1.0.0 • Oficial",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF475569),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 2. Developer & Company Information (Adimilson Fernando Bertelli Informatica ME)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEFF6FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Business,
                                contentDescription = null,
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Desenvolvedor & Titularidade",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = "Empresa Responsável",
                                fontSize = 11.5.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Razão Social:",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Adimilson Fernando Bertelli Informatica ME",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Compromisso com transparência comercial, conformidade legal e respeito integral ao consumidor brasileiro.",
                                fontSize = 11.5.sp,
                                color = Color(0xFF475569),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. O que o ScanShop Faz (Resumo Geral)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "O que o ScanShop faz?",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    AboutFeatureItem(
                        icon = Icons.Default.Compare,
                        title = "Comparação Lado a Lado",
                        description = "Compara preços reais em tempo real entre AliExpress e Shopee para você economizar sempre."
                    )
                    AboutFeatureItem(
                        icon = Icons.Default.Mic,
                        title = "Pesquisa por Voz",
                        description = "Fale o nome do produto ou categoria para pesquisar com rapidez e praticidade sem precisar digitar."
                    )
                    AboutFeatureItem(
                        icon = Icons.Default.ShoppingBag,
                        title = "Histórico e Cupons",
                        description = "Identifica automaticamente menor preço dos últimos 30 dias, descontos reais e cupons disponíveis."
                    )
                }
            }
        }

        // 4. INFORMAÇÕES CRÍTICAS DE SEGURANÇA E TRANSPARÊNCIA (Mandatório)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                border = BorderStroke(1.dp, Color(0xFFFDE68A))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Avisos Importantes e Segurança",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    CriticalNoticeBullet(
                        title = "Nada é comprado dentro do nosso app",
                        description = "O ScanShop é estritamente uma ferramenta de busca e comparação. Nenhuma transação financeira, pagamento ou cobrança ocorre dentro deste aplicativo."
                    )

                    CriticalNoticeBullet(
                        title = "Redirecionamento Oficial e Seguro",
                        description = "Ao clicar em \"Comprar\" ou ver oferta, você é redirecionado diretamente para os aplicativos oficiais ou sites seguros do AliExpress e da Shopee via links de afiliados autorizados."
                    )

                    CriticalNoticeBullet(
                        title = "Privacidade e Proteção de Dados",
                        description = "Não solicitamos, não coletamos e não armazenamos nenhum dado bancário, número de cartão de crédito, CPF de pagamento ou senhas. Seus favoritos são salvos de forma 100% privada e local no seu próprio aparelho."
                    )

                    CriticalNoticeBullet(
                        title = "Entrega e Garantia",
                        description = "Toda entrega, suporte, garantia de envio e processamento fiscal são de inteira responsabilidade das plataformas AliExpress e Shopee e de seus respectivos vendedores credenciados."
                    )
                }
            }
        }

        // 5. Botões de Ação Rápida
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onNavigateToSearch,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("about_go_search_button"),
                    shape = RoundedCornerShape(23.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AliExpressRed)
                ) {
                    Text(
                        text = "Fazer Busca",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onNavigateToDeals,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("about_go_deals_button"),
                    shape = RoundedCornerShape(23.dp),
                    border = BorderStroke(1.dp, ShopeeOrange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ShopeeOrange)
                ) {
                    Text(
                        text = "Ver Ofertas",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutFeatureItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFF1EE)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AliExpressRed,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.5.sp,
                color = Color(0xFF64748B),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun CriticalNoticeBullet(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD97706))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF78350F)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            fontSize = 11.5.sp,
            color = Color(0xFF451A03),
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}
