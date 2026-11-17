package com.example

import com.example.data.model.PriceFormatter
import com.example.data.model.QueryIntentProfile
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.data.network.MercadoLivreClient
import com.example.data.network.MercadoLivreHtmlParser
import com.example.data.network.NetworkClientProvider
import com.example.data.network.SearchEngineImageHit
import com.example.data.search.NeuralProductMatcher
import com.example.data.search.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceAndCoreUtilsTest {

    @Test
    fun `sha256Hex and hmacSha256UpperHex match exact RFC vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            NetworkClientProvider.sha256Hex("")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NetworkClientProvider.sha256Hex("abc")
        )
        assertEquals(
            "B0344C61D8DB38535CA8AFCEAF0BF12B881DC200C9833DA726E9376C2E32CFF7",
            NetworkClientProvider.hmacSha256UpperHex(
                secret = "\u000b".repeat(20),
                data = "Hi There"
            )
        )
    }

    @Test
    fun `priceFormatter handles numbers brl strings us strings and rejects non positive values`() {
        assertEquals("R$ 1.299,90", PriceFormatter.formatBrl(1299.9))
        assertEquals("R$ 1.299,90", PriceFormatter.formatBrl("R$ 1.299,90"))
        assertEquals("R$ 489,50", PriceFormatter.formatBrl("489.5"))
        assertEquals("R$ 1.299,00", PriceFormatter.formatBrl("1.299"))
        assertEquals("R$ 1.499,99", PriceFormatter.formatBrl("1,499.99"))
        assertEquals("", PriceFormatter.formatBrl(0.0))
        assertEquals("", PriceFormatter.formatBrl(-15.0))
        assertEquals("", PriceFormatter.formatBrl(Double.NaN))
        assertEquals("", PriceFormatter.formatBrl("sem preco"))
    }

    @Test
    fun `textNormalizer preserves accuracy across ascii fast path diacritics and lru cache hits`() {
        val firstAscii = TextNormalizer.normalize("Smartv Samsung 50 4K 128 GB Wi-Fi")
        val secondAscii = TextNormalizer.normalize("Smartv Samsung 50 4K 128 GB Wi-Fi")
        assertEquals("smart tv samsung 50 4k 128gb wifi", firstAscii)
        assertEquals(firstAscii, secondAscii)

        val firstAccents = TextNormalizer.normalize("Placa Mãe X99 Promoção Edição Especial USB-C")
        val secondAccents = TextNormalizer.normalize("Placa Mãe X99 Promoção Edição Especial USB-C")
        assertEquals("placa mae x99 promocao edicao especial usbc", firstAccents)
        assertEquals(firstAccents, secondAccents)

        assertEquals("smartphone Samsung iPhone 15", TextNormalizer.normalizeSearchQueryText("tphone Samsung iPhone15"))
    }

    @Test
    fun `neuralMatcher prepared query context scores batch and single candidates consistently`() {
        val matcher = NeuralProductMatcher()
        val profile = QueryIntentProfile(
            rawQuery = "ssd nvme 1tb kingston",
            itemType = "SSD NVMe",
            canonicalTermsPt = listOf("ssd", "ssd nvme", "disco solido"),
            canonicalTermsEn = listOf("nvme ssd", "solid state drive"),
            brand = "Kingston",
            alphanumericCodes = listOf("1tb"),
            requiredQualities = listOf("nvme", "1tb"),
            isAccessoryIntent = false
        )
        val valid = RawStoreCandidate(
            id = "c1",
            store = StoreType.SHOPEE,
            title = "SSD Kingston NV2 1TB NVMe M.2 2280 Original",
            imageUrl = "https://example.com/ssd.jpg",
            productUrl = "https://shopee.com.br/ssd-kingston-1tb",
            affiliateUrl = "https://shopee.com.br/ssd-kingston-1tb"
        )
        val caseTrap = RawStoreCandidate(
            id = "c2",
            store = StoreType.ALIEXPRESS,
            title = "Case Gaveta Externa para SSD NVMe 1TB Kingston",
            imageUrl = "https://example.com/case.jpg",
            productUrl = "https://aliexpress.com/case-ssd",
            affiliateUrl = "https://aliexpress.com/case-ssd"
        )

        val batchResults = matcher.scoreAndFilter(profile, listOf(valid, caseTrap)).associateBy { it.candidate.id }
        assertFalse(batchResults.getValue("c1").hardRejected)
        assertTrue(batchResults.getValue("c2").hardRejected)

        val singleScored = matcher.scoreCandidate(profile, FloatArray(128) { 0f }, valid)
        assertFalse(singleScored.hardRejected)
        assertEquals(
            batchResults.getValue("c1").breakdown.alphanumericModelScore,
            singleScored.breakdown.alphanumericModelScore,
            0.0001f
        )
    }

    @Test
    fun `applyImageHits and findBestFuzzyPrice preserve exact matching after precomputed optimization`() {
        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_MLB33445566",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 Machinist PR9 DDR4",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-33445566-placa-mae-x99-machinist-pr9-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-33445566-placa-mae-x99-machinist-pr9-_JM"
            ),
            RawStoreCandidate(
                id = "ml_no_match",
                store = StoreType.MERCADO_LIVRE,
                title = "Monitor Gamer Curvo 27 Polegadas",
                imageUrl = "",
                productUrl = "https://www.mercadolivre.com.br/monitor-gamer/p/MLB777777",
                affiliateUrl = "https://www.mercadolivre.com.br/monitor-gamer/p/MLB777777"
            )
        )
        val hits = listOf(
            SearchEngineImageHit(
                title = "Placa Mãe X99 Machinist PR9",
                pageUrl = "https://produto.mercadolivre.com.br/MLB-33445566-placa-mae-x99-machinist-pr9-_JM",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_334455-O.webp",
                thumbUrl = "https://th.bing.com/th?id=abc"
            )
        )

        val updated = MercadoLivreHtmlParser.applyImageHits(candidates, hits)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_334455-O.webp", updated[0].imageUrl)
        assertEquals("", updated[1].imageUrl)

        val client = MercadoLivreClient()
        val entries = listOf(
            "Placa Mae X99 Machinist PR9 DDR4 LGA 2011" to "R$ 429,90",
            "Cabo SATA para Placa Mae X99" to "R$ 19,90"
        )
        assertEquals(
            "R$ 429,90",
            client.findBestFuzzyPrice(TextNormalizer.normalize("Placa Mãe X99 Machinist PR9"), entries)
        )
        assertNull(
            client.findBestFuzzyPrice(TextNormalizer.normalize("Monitor Gamer 27 Polegadas"), entries)
        )
    }
}
