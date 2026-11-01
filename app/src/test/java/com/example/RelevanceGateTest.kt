package com.example

import com.example.data.ai.AiSearchService
import com.example.data.model.ComparisonResult
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.data.repository.QueryIntentEngine
import com.example.data.repository.RelevanceGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários JUnit 4 para validação estrita do RelevanceGate e regressão do refineComparison.
 */
class RelevanceGateTest {

    private fun checkRelevance(title: String, query: String, price: Double = 0.0): Boolean {
        val intent = QueryIntentEngine.parseQueryIntent(query)
        val result = RelevanceGate.evaluate(
            title = title,
            price = price,
            query = query,
            intent = intent
        )
        return result.isApproved
    }

    @Test
    fun testMouseGamer() {
        val query = "mouse gamer"
        // Aceitar produto legítimo
        assertTrue(checkRelevance("Mouse Gamer Sem Fio RGB 12000 DPI", query))

        // Rejeitar acessórios e tapetes
        assertFalse(checkRelevance("Tapete de mesa gamer grande", query))
        assertFalse(checkRelevance("Pés de mouse skates", query))
        assertFalse(checkRelevance("Suporte para mouse", query))
    }

    @Test
    fun testPlacaMaeX99() {
        val query = "placa mae x99"
        // Aceitar placa-mãe x99 real
        assertTrue(checkRelevance("Placa Mãe X99 LGA2011-3 DDR4", query))

        // Rejeitar chipset diferente (B450), módulo TPM e espelho traseiro
        assertFalse(checkRelevance("Placa Mãe B450", query))
        assertFalse(checkRelevance("Módulo TPM 2.0", query))
        assertFalse(checkRelevance("Espelho I/O para placa x99", query))
    }

    @Test
    fun testSmartTvSamsung43() {
        val query = "smart tv samsung 43"
        // Aceitar TV Samsung 43 legítima
        assertTrue(checkRelevance("Smart TV Samsung 43 polegadas 4K", query, price = 1899.0))

        // Rejeitar controle remoto, suporte de parede e marca concorrente (LG)
        assertFalse(checkRelevance("Controle remoto Samsung TV", query, price = 25.0))
        assertFalse(checkRelevance("Suporte de parede TV 43", query, price = 39.0))
        assertFalse(checkRelevance("Smart TV LG 43", query, price = 1799.0))
    }

    @Test
    fun testIPhone13() {
        val query = "iphone 13"
        // Aceitar iPhone 13 real
        assertTrue(checkRelevance("iPhone 13 128GB", query, price = 3500.0))

        // Rejeitar capa e modelo divergente (iPhone 12)
        assertFalse(checkRelevance("Capa iPhone 13", query, price = 20.0))
        assertFalse(checkRelevance("iPhone 12 128GB", query, price = 2800.0))
    }

    @Test
    fun testTenisNikeMasculino() {
        val query = "tenis nike masculino"
        // Aceitar tênis Nike masculino
        assertTrue(checkRelevance("Tênis Nike Air Max Masculino", query))

        // Rejeitar tênis feminino e cadarço avulso
        assertFalse(checkRelevance("Tênis Nike Feminino", query))
        assertFalse(checkRelevance("Cadarço para tênis Nike", query))
    }

    @Test
    fun testFoneBluetooth() {
        val query = "fone bluetooth"
        // Aceitar fone TWS
        assertTrue(checkRelevance("Fone de Ouvido Bluetooth TWS", query))

        // Rejeitar almofada para fone
        assertFalse(checkRelevance("Almofada para fone de ouvido", query))
    }

    @Test
    fun testBuscaGenericaCafeteiraItaliana() {
        val query = "cafeteira italiana 6 xicaras"
        // Aceitar quando todos os termos significativos estão presentes
        assertTrue(checkRelevance("Cafeteira Italiana 6 Xícaras em Aço Inox", query))

        // Rejeitar item parcial ou divergente
        assertFalse(checkRelevance("Xícara de porcelana", query))
    }

    @Test
    fun testRegressaoBugPreenchimentoArtificial() = runBlocking {
        val aiService = AiSearchService()
        val query = "placa mae x99"

        // Criar 5 produtos genuínos de placa-mãe X99
        val approvedAli = (1..5).map { idx ->
            Product(
                id = "ali_approved_$idx",
                store = StoreType.ALIEXPRESS,
                title = "Placa Mãe X99 LGA 2011-3 Machinist MR9A Pro DDR4 v$idx",
                imageUrl = "https://example.com/mobo$idx.jpg",
                originalPrice = 500.0,
                promoPrice = 380.0,
                discountPercentage = 24,
                rating = 4.9,
                salesCount = 800,
                freeShipping = true,
                nationalSeller = false,
                couponAvailable = false,
                affiliateUrl = "https://ali.com/item/$idx",
                deepLinkUrl = "aliexpress://item/$idx"
            )
        }

        // Criar 40 produtos reprovados (espelhos, TPM, B450, cabos)
        val rejectedAli = (1..40).map { idx ->
            val junkTitle = when (idx % 4) {
                0 -> "Espelho I/O Shield Traseiro Placa X99 Mod $idx"
                1 -> "Módulo TPM 2.0 para Placa Mãe $idx"
                2 -> "Placa Mãe B450 Gaming AM4 $idx"
                else -> "Cabo SATA 3 para Placa X99 $idx"
            }
            Product(
                id = "ali_rejected_$idx",
                store = StoreType.ALIEXPRESS,
                title = junkTitle,
                imageUrl = "https://example.com/junk$idx.jpg",
                originalPrice = 40.0,
                promoPrice = 25.0,
                discountPercentage = 30,
                rating = 4.2,
                salesCount = 50,
                freeShipping = false,
                nationalSeller = false,
                couponAvailable = false,
                affiliateUrl = "https://ali.com/junk/$idx",
                deepLinkUrl = "aliexpress://junk/$idx"
            )
        }

        val baseResult = ComparisonResult(
            query = query,
            aliExpressProducts = approvedAli + rejectedAli, // Total 45 itens
            shopeeProducts = emptyList(),
            unifiedSorted = approvedAli + rejectedAli
        )

        val refined = aiService.refineComparison(query, baseResult)

        // Verificação estrita: deve devolver EXATAMENTE 5 produtos, NUNCA completar para 35 ou 50!
        assertEquals("Deve conter exatamente os 5 produtos aprovados pelo filtro", 5, refined.aliExpressProducts.size)
        assertEquals("Nenhum produto deve ter badge artificial de 85% Compatível",
            0, refined.aliExpressProducts.count { it.aiConfidenceBadge == "85% Compatível" })

        // Todos os 5 retornados devem ser legítimos X99
        for (prod in refined.aliExpressProducts) {
            assertTrue(prod.title.contains("X99", ignoreCase = true))
            assertFalse(prod.title.contains("Espelho", ignoreCase = true))
            assertFalse(prod.title.contains("TPM", ignoreCase = true))
            assertFalse(prod.title.contains("B450", ignoreCase = true))
        }
    }

    @Test
    fun testCenariosRejeicaoNovos() {
        // mouse gamer | Mouse Sem Fio Silencioso Escritório Ergonômico Vertical 1600DPI
        assertFalse(checkRelevance("Mouse Sem Fio Silencioso Escritório Ergonômico Vertical 1600DPI", "mouse gamer"))

        // mouse sem fio | Mouse com fio USB Óptico 1200DPI
        assertFalse(checkRelevance("Mouse com fio USB Óptico 1200DPI", "mouse sem fio"))

        // teclado mecanico | Teclado USB com fio de membrana ABNT2 escritório
        assertFalse(checkRelevance("Teclado USB com fio de membrana ABNT2 escritório", "teclado mecanico"))

        // fone bluetooth | Fone de ouvido com fio P2 3.5mm com microfone
        assertFalse(checkRelevance("Fone de ouvido com fio P2 3.5mm com microfone", "fone bluetooth"))

        // smartwatch | Relógio de Pulso Masculino Quartzo Analógico Aço Inoxidável
        assertFalse(checkRelevance("Relógio de Pulso Masculino Quartzo Analógico Aço Inoxidável", "smartwatch"))

        // notebook gamer | Notebook Intel Celeron 4GB 128GB escritório estudante
        assertFalse(checkRelevance("Notebook Intel Celeron 4GB 128GB escritório estudante", "notebook gamer"))

        // ssd 1tb | Caixa Externa Enclosure para SSD M.2 NVMe 1TB USB-C
        assertFalse(checkRelevance("Caixa Externa Enclosure para SSD M.2 NVMe 1TB USB-C", "ssd 1tb"))

        // cadeira gamer | Cadeira de Escritório Giratória Malha Ergonômica
        assertFalse(checkRelevance("Cadeira de Escritório Giratória Malha Ergonômica", "cadeira gamer"))
    }

    @Test
    fun testCenariosAceitacaoNovos() {
        // memoria ram 16gb | Memória RAM 16G DDR4 3200MHz Desktop Gamer
        assertTrue(checkRelevance("Memória RAM 16G DDR4 3200MHz Desktop Gamer", "memoria ram 16gb"))

        // memoria ram 16gb | Kit Memória RAM 2x8GB DDR4 3200MHz Dissipador
        assertTrue(checkRelevance("Kit Memória RAM 2x8GB DDR4 3200MHz Dissipador", "memoria ram 16gb"))

        // rtx 4060 | Placa de Vídeo RTX 4060 8GB GDDR6 Gaming OC
        assertTrue(checkRelevance("Placa de Vídeo RTX 4060 8GB GDDR6 Gaming OC", "rtx 4060"))

        // rtx 3060 | Placa Gráfica Gaming RTX 3060 12GB Ventoinha Dupla
        assertTrue(checkRelevance("Placa Gráfica Gaming RTX 3060 12GB Ventoinha Dupla", "rtx 3060"))
    }

    @Test
    fun testCenariosManterObrigatorios() {
        // ACEITAR: mouse gamer | 2024 Novo Mouse Sem Fio Bluetooth 2.4G Recarregável Silencioso Ergonômico Gamer Para Computador Laptop PC
        assertTrue(checkRelevance("2024 Novo Mouse Sem Fio Bluetooth 2.4G Recarregável Silencioso Ergonômico Gamer Para Computador Laptop PC", "mouse gamer"))

        // ACEITAR: teclado mecanico | Teclado Mecânico Sem Fio 75% Hot Swap RGB Gasket Bluetooth 5.0 2.4G
        assertTrue(checkRelevance("Teclado Mecânico Sem Fio 75% Hot Swap RGB Gasket Bluetooth 5.0 2.4G", "teclado mecanico"))

        // ACEITAR: fone bluetooth | TWS Fones De Ouvido Sem Fio Bluetooth 5.3 Redução de Ruído HiFi Esportes à prova d água
        assertTrue(checkRelevance("TWS Fones De Ouvido Sem Fio Bluetooth 5.3 Redução de Ruído HiFi Esportes à prova d água", "fone bluetooth"))

        // ACEITAR: smartwatch | Série 9 Smart Watch Homens Mulheres Bluetooth Chamada Fitness Tracker NFC
        assertTrue(checkRelevance("Série 9 Smart Watch Homens Mulheres Bluetooth Chamada Fitness Tracker NFC", "smartwatch"))

        // REJEITAR: smart tv | TV Box 4K Android 13 Conversor Smart Wifi
        assertFalse(checkRelevance("TV Box 4K Android 13 Conversor Smart Wifi", "smart tv", price = 120.0))

        // REJEITAR: processador intel core i5 12400 | Processador Intel Core i3 10100 LGA1200
        assertFalse(checkRelevance("Processador Intel Core i3 10100 LGA1200", "processador intel core i5 12400"))

        // REJEITAR: airpods pro 2 | Apple AirPods 3 com estojo de carregamento
        assertFalse(checkRelevance("Apple AirPods 3 com estojo de carregamento", "airpods pro 2"))

        // REJEITAR: monitor gamer 144hz | Monitor 24 polegadas 75Hz Full HD escritório
        assertFalse(checkRelevance("Monitor 24 polegadas 75Hz Full HD escritório", "monitor gamer 144hz"))
    }
}
