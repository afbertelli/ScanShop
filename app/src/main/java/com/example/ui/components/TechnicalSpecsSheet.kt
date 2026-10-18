package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.ai.NeuralSpecEngine
import com.example.data.model.Product
import com.example.data.model.ProductTechnicalSpecs
import com.example.data.model.StoreType
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.ShopeeOrange
import com.example.ui.util.DeepLinkUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TechnicalSpecsSheet(
    product: Product,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val storeColor = if (product.store == StoreType.ALIEXPRESS) AliExpressRed else ShopeeOrange
    val specs: ProductTechnicalSpecs = product.technicalSpecs
        ?: NeuralSpecEngine.extractSpecsFromProduct(product.title, product.promoPrice)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.testTag("technical_specs_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Header: Top Banner with AI Neural Glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF4338CA))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
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
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF818CF8).copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFFA5B4FC),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Ficha Técnica Neural IA",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Análise semântica profunda de especificações",
                                color = Color(0xFFC7D2FE),
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .testTag("btn_close_specs")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Product Summary Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
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
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = product.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = product.formattedPromoPrice,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = storeColor
                            )
                            if (product.freeShipping) {
                                Text(
                                    text = "• Frete Grátis",
                                    fontSize = 11.sp,
                                    color = DealGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // AI Match Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFEDE9FE))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF7C3AED),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Correspondência IA: ${specs.matchConfidence}% Confiabilidade",
                                color = Color(0xFF6D28D9),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Quick Highlight Chips
            if (specs.keyHighlightChips.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = "DESTAQUES IDENTIFICADOS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        specs.keyHighlightChips.forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFEEF2FF))
                                    .border(1.dp, Color(0xFFC7D2FE), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = chip,
                                    color = Color(0xFF3730A3),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Specification Sections
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Section 1: Identificação & Modelo
                SpecSectionCard(
                    title = "Identificação & Modelo",
                    icon = Icons.Default.PhoneIphone,
                    accentColor = Color(0xFF3B82F6),
                    rows = listOfNotNull(
                        "Tipo de Produto" to specs.productType,
                        "Marca / Fabricante" to specs.brand,
                        "Modelo Principal" to specs.model,
                        "Submodelo / Código" to specs.subModelOrPartNumber,
                        "Ano de Lançamento" to specs.releaseYear,
                        "Cor / Acabamento" to specs.colorFinish
                    )
                )

                // Section 2: Hardware & Desempenho
                if (specs.processorCpuGpu != null || specs.ramMemory != null || specs.storageCapacity != null) {
                    SpecSectionCard(
                        title = "Hardware & Desempenho",
                        icon = Icons.Default.Memory,
                        accentColor = Color(0xFF8B5CF6),
                        rows = listOfNotNull(
                            "Processador (CPU) / GPU" to specs.processorCpuGpu,
                            "Memória RAM" to specs.ramMemory,
                            "Armazenamento Interno" to specs.storageCapacity,
                            "Expansibilidade" to specs.expandability
                        )
                    )
                }

                // Section 3: Tela & Display
                if (specs.displaySpecs != null) {
                    SpecSectionCard(
                        title = "Tela & Painel",
                        icon = Icons.Default.Tv,
                        accentColor = Color(0xFF06B6D4),
                        rows = listOf(
                            "Especificações da Tela" to specs.displaySpecs
                        )
                    )
                }

                // Section 4: Energia & Bateria
                if (specs.batterySpecs != null || specs.chargingTypeVoltage != null) {
                    SpecSectionCard(
                        title = "Bateria & Alimentação",
                        icon = Icons.Default.BatteryChargingFull,
                        accentColor = Color(0xFF10B981),
                        rows = listOfNotNull(
                            "Capacidade / Autonomia" to specs.batterySpecs,
                            "Tipo de Carga / Tensão" to specs.chargingTypeVoltage
                        )
                    )
                }

                // Section 5: Conectividade & Portas
                if (specs.wirelessConnectivity != null || specs.physicalPorts != null || specs.simCardSlots != null) {
                    SpecSectionCard(
                        title = "Conectividade & Portas",
                        icon = Icons.Default.Wifi,
                        accentColor = Color(0xFFF59E0B),
                        rows = listOfNotNull(
                            "Sem Fio (Wi-Fi/Bluetooth/5G)" to specs.wirelessConnectivity,
                            "Portas Físicas (USB/HDMI/LAN)" to specs.physicalPorts,
                            "Slots de Cartão (SIM/eSIM)" to specs.simCardSlots
                        )
                    )
                }

                // Section 6: Construção & Proteção
                if (specs.buildMaterial != null || specs.protectionRating != null || specs.dimensionsWeight != null) {
                    SpecSectionCard(
                        title = "Construção & Proteção",
                        icon = Icons.Default.Security,
                        accentColor = Color(0xFF6366F1),
                        rows = listOfNotNull(
                            "Material de Construção" to specs.buildMaterial,
                            "Grau de Proteção (IP)" to specs.protectionRating,
                            "Dimensões & Peso" to specs.dimensionsWeight,
                            "Design & Ergonomia" to specs.designErgonomics
                        )
                    )
                }

                // Section 7: Câmeras & Sensores
                if (specs.cameraSpecs != null || specs.soundMicrophone != null || specs.sensorsBiometrics != null) {
                    SpecSectionCard(
                        title = "Câmeras & Multimídia",
                        icon = Icons.Default.Speed,
                        accentColor = Color(0xFFEC4899),
                        rows = listOfNotNull(
                            "Câmeras" to specs.cameraSpecs,
                            "Áudio & Microfones" to specs.soundMicrophone,
                            "Sensores & Biometria" to specs.sensorsBiometrics
                        )
                    )
                }

                // Section 8: Homologação & Embalagem
                if (specs.certificationsWarranty != null || specs.packagingContents != null || specs.operatingSystem != null) {
                    SpecSectionCard(
                        title = "Software & Garantia",
                        icon = Icons.Default.CheckCircle,
                        accentColor = Color(0xFF14B8A6),
                        rows = listOfNotNull(
                            "Sistema / BIOS" to specs.operatingSystem,
                            "Compatibilidade" to specs.ecosystemCompatibility,
                            "Homologação & Garantia" to specs.certificationsWarranty,
                            "Conteúdo da Embalagem" to specs.packagingContents,
                            "Pegada Ecológica" to specs.ecologicalFootprint
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { DeepLinkUtils.openAffiliateProduct(context, product) },
                    modifier = Modifier
                        .weight(1.5f)
                        .height(44.dp)
                        .testTag("specs_btn_open_store"),
                    colors = ButtonDefaults.buttonColors(containerColor = storeColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Ir para ${product.store.displayName}",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }

                OutlinedButton(
                    onClick = {
                        val textToCopy = buildString {
                            appendLine("📊 Ficha Técnica: ${product.title}")
                            appendLine("Preço: ${product.formattedPromoPrice} na ${product.store.displayName}")
                            specs.brand?.let { appendLine("Marca: $it") }
                            specs.model?.let { appendLine("Modelo: $it") }
                            specs.subModelOrPartNumber?.let { appendLine("Submodelo: $it") }
                            specs.processorCpuGpu?.let { appendLine("CPU/GPU: $it") }
                            specs.ramMemory?.let { appendLine("RAM: $it") }
                            specs.storageCapacity?.let { appendLine("Armazenamento: $it") }
                            specs.displaySpecs?.let { appendLine("Tela: $it") }
                            specs.batterySpecs?.let { appendLine("Bateria: $it") }
                            specs.wirelessConnectivity?.let { appendLine("Conectividade: $it") }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Ficha Técnica", textToCopy))
                        Toast.makeText(context, "Ficha técnica copiada para a área de transferência!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("specs_btn_copy"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Copiar",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecSectionCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    rows: List<Pair<String, String?>>
) {
    val validRows = rows.filter { !it.second.isNullOrBlank() }
    if (validRows.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            validRows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color(0xFFF1F5F9))
                            .padding(vertical = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = label,
                        fontSize = 11.5.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = value ?: "-",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.4f)
                    )
                }
            }
        }
    }
}
