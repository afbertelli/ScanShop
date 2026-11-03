package com.example

import com.example.data.ai.AiSearchService
import com.example.data.model.ComparisonResult
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.data.repository.GeminiGrayZoneAuditor
import com.example.data.repository.RelevanceGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários para divisão de lotes de auditoria e decisão da conferência com Gemini.
 */
class AiSearchServiceAuditTest {

    @Test
    fun testSplitIntoAuditBatchesComCemItens() {
        val items = (1..100).map { "item_$it" }
        val batches = AiSearchService.splitIntoAuditBatches(items, batchSize = 30, maxItems = 90)

        // Devolve 3 lotes de 30
        assertEquals(3, batches.size)
        assertEquals(30, batches[0].size)
        assertEquals(30, batches[1].size)
        assertEquals(30, batches[2].size)

        // 10 itens ficam sem conferência (passam do limite de 90)
        val audittedCount = batches.flatten().size
        assertEquals(90, audittedCount)
        assertEquals(10, items.size - audittedCount)
    }

    @Test
    fun testSplitIntoAuditBatchesComZeroItens() {
        val batches = AiSearchService.splitIntoAuditBatches(emptyList<String>(), batchSize = 30, maxItems = 90)
        assertTrue(batches.isEmpty())
    }

    @Test
    fun testRefineComparisonComAuditorFalso() = runBlocking {
        val aiService = AiSearchService()
        val query = "tapete automotivo"

        // Item 1: Limpador de tapete (cai na zona cinzenta por headMismatch) - auditor reprova (false)
        val prodRejected = Product(
            id = "prod_rejected",
            store = StoreType.ALIEXPRESS,
            title = "Limpador de Tapete Automotivo Spray Limpeza",
            imageUrl = "https://example.com/cleaner.jpg",
            originalPrice = 45.0,
            promoPrice = 30.0,
            discountPercentage = 33,
            rating = 4.8,
            salesCount = 200,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://ali.com/1",
            deepLinkUrl = "aliexpress://1"
        )

        // Item 2: Tapete genuíno que possa cair na zona cinzenta - auditor aprova (true)
        val prodApproved = Product(
            id = "prod_approved",
            store = StoreType.ALIEXPRESS,
            title = "Tapete Automotivo Borracha Universal 5 Peças Preto",
            imageUrl = "https://example.com/mat.jpg",
            originalPrice = 120.0,
            promoPrice = 85.0,
            discountPercentage = 29,
            rating = 4.9,
            salesCount = 500,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://ali.com/2",
            deepLinkUrl = "aliexpress://2"
        )

        // Item 3: Produto cinzento que não consta na resposta do auditor (simula timeout ou omissão)
        val prodMissing = Product(
            id = "prod_missing",
            store = StoreType.SHOPEE,
            title = "Protetor Tapete Automotivo Impermeável Lavável",
            imageUrl = "https://example.com/missing.jpg",
            originalPrice = 90.0,
            promoPrice = 60.0,
            discountPercentage = 33,
            rating = 4.7,
            salesCount = 150,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://shopee.com/3",
            deepLinkUrl = "shopee://3"
        )

        val fakeAuditor = object : GeminiGrayZoneAuditor {
            override suspend fun auditBatch(query: String, items: List<Pair<String, String>>): Map<String, Boolean> {
                val map = mutableMapOf<String, Boolean>()
                for ((id, _) in items) {
                    when (id) {
                        "prod_rejected" -> map[id] = false
                        "prod_approved" -> map[id] = true
                        // "prod_missing" é omitido propositalmente para simular item ausente do mapa
                    }
                }
                return map
            }
        }

        RelevanceGate.grayZoneAuditor = fakeAuditor
        try {
            val baseResult = ComparisonResult(
                query = query,
                aliExpressProducts = listOf(prodRejected, prodApproved),
                shopeeProducts = listOf(prodMissing),
                unifiedSorted = listOf(prodRejected, prodApproved, prodMissing)
            )

            val refined = aiService.refineComparison(query, baseResult)
            val remainingIds = (refined.aliExpressProducts + refined.shopeeProducts).map { it.id }

            // Item com resposta false é removido
            assertFalse("Item com resposta false deve ser removido", remainingIds.contains("prod_rejected"))

            // Item com resposta true fica
            assertTrue("Item com resposta true deve ser mantido", remainingIds.contains("prod_approved"))

            // Item ausente do mapa fica (não é descartado por falha/omissão)
            assertTrue("Item ausente do mapa de auditoria deve ser mantido", remainingIds.contains("prod_missing"))
        } finally {
            RelevanceGate.grayZoneAuditor = null
        }
    }
}
