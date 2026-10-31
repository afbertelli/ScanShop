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
}
