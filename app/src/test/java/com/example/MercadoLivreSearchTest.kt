package com.example

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ScanShopDatabase
import com.example.data.model.DomainDiscoveryHint
import com.example.data.model.MatchVerdict
import com.example.data.model.MatchedProduct
import com.example.data.model.NeuralFeatureBreakdown
import com.example.data.model.PriceFormatter
import com.example.data.model.QueryIntentProfile
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.ai.GeminiQueryAndVerifierEngine
import com.example.data.network.MercadoLivreClient
import com.example.data.network.MercadoLivreHtmlParser
import com.example.data.network.MercadoLivreWebViewExtractor
import com.example.data.network.SearchEngineImageHit
import com.example.data.network.parseWebViewExtractedJson
import com.example.data.repository.FavoriteRepository
import com.example.data.search.HtmlTextDecoder
import com.example.data.search.NeuralProductMatcher
import com.example.data.search.RealTimeSearchOrchestrator
import com.example.data.model.SearchSessionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MercadoLivreSearchTest {

    @Test
    fun `htmlTextDecoder repairs HTML entities, mojibake, unicode escapes and broken mae slugs`() {
        assertEquals("Placa Mãe X99 DDR4", HtmlTextDecoder.decode("Placa M&atilde;e X99 DDR4"))
        assertEquals("Placa Mãe X99 DDR4", HtmlTextDecoder.decode("Placa M&#227;e X99 DDR4"))
        assertEquals("Placa Mãe X99 DDR4", HtmlTextDecoder.decode("""Placa M\u00e3e X99 DDR4"""))
        assertEquals("Kit Placa Mãe X99", HtmlTextDecoder.decode("Kit Placa MÃ£e X99"))
        assertEquals("Kit Placa Mãe X99", HtmlTextDecoder.decode("Kit Placa MÃƒÂ£e X99"))
        assertEquals("Placa Mãe X99", HtmlTextDecoder.decode("Placa M\uFFFDe X99"))

        val slug1 = MercadoLivreHtmlParser.deriveTitleFromUrl(
            "https://www.mercadolivre.com.br/placa-m-e-x99-lga-2011-3-ddr4/p/MLB44556677"
        )
        assertTrue("Expected 'Placa Mãe' in $slug1", slug1.contains("Placa Mãe"))

        val slug2 = MercadoLivreHtmlParser.deriveTitleFromUrl(
            "https://produto.mercadolivre.com.br/MLB-44556677-placa-me-x99-ddr4-nvme-_JM"
        )
        assertTrue("Expected 'Placa Mãe' in $slug2", slug2.contains("Placa Mãe"))
    }

    @Test
    fun `decodeHttpBytes handles ISO-8859-1 mojibake and charset meta cleanly`() {
        val utf8Bytes = "<html><head><meta charset=\"UTF-8\"></head><body><h1>Placa Mãe X99</h1></body></html>".toByteArray(Charsets.UTF_8)
        val decoded = MercadoLivreHtmlParser.decodeHttpBytes(utf8Bytes, Charsets.ISO_8859_1)
        assertTrue("Expected accented Mãe in $decoded", decoded.contains("Placa Mãe X99"))
    }

    @Test
    fun `parseListingHtml extracts and merges card DOM, lazy images, JSON-LD, and prices`() {
        val html = """
            <script type="application/ld+json">
            {"itemListElement":[{"item":{"name":"Placa M&atilde;e X99 Machinist DDR4","url":"https://www.mercadolivre.com.br/placa-me-x99-machinist/p/MLB44556677","offers":{"price":389.90}}}]}
            </script>
            <li class="ui-search-layout__item">
              <div class="poly-card">
                <img class="poly-component__picture" data-src="https://http2.mlstatic.com/D_NQ_NP_991122-O.webp" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" />
                <a class="poly-component__title" href="https://www.mercadolivre.com.br/placa-me-x99-machinist/p/MLB44556677">Placa M&atilde;e X99 Machinist DDR4</a>
                <div class="poly-price__current">
                  <span class="andes-money-amount__fraction">389</span>
                  <span class="andes-money-amount__cents">90</span>
                </div>
              </div>
            </li>
        """.trimIndent()

        val items = MercadoLivreHtmlParser.parseListingHtml(html, "x99", "MLB1692")
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("ml_MLB44556677", item.id)
        assertEquals("Placa Mãe X99 Machinist DDR4", item.title)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_991122-O.webp", item.imageUrl)
        assertTrue(item.priceText.contains("389,90"))
        assertTrue(item.affiliateUrl.contains("matt_tool=75816733"))
    }

    @Test
    fun `parseBingWebHtml and parseBingImagesHtml extract complete product and attach mlstatic photo`() {
        val b64 = Base64.encodeToString(
            "https://www.mercadolivre.com.br/placa-mae-x99-huananzhi/p/MLB33445566".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val bingWebHtml = """
            <ol id="b_results">
                <li class="b_algo">
                    <div class="b_tpcn"><ul><li>Patrocinado</li></ul></div>
                    <h2><a href="https://www.bing.com/ck/a?!&&p=1&u=a1$b64&ntb=1">Placa M&atilde;e X99 Huananzhi QD4 LGA 2011-3</a></h2>
                    <div class="b_caption"><p>A partir de R&#36; 459,90 em 12x</p><ul><li>Frete</li></ul></div>
                </li>
            </ol>
        """.trimIndent()

        val webItems = MercadoLivreHtmlParser.parseBingWebHtml(bingWebHtml, "x99", "")
        assertEquals(1, webItems.size)
        assertEquals("Placa Mãe X99 Huananzhi QD4 LGA 2011-3", webItems.first().title)
        assertTrue(webItems.first().priceText.contains("459,90"))

        val bingImgHtml = """
            <a class="iusc" m="{&quot;purl&quot;:&quot;https://www.mercadolivre.com.br/placa-mae-x99-huananzhi/p/MLB33445566&quot;,&quot;murl&quot;:&quot;http://http2.mlstatic.com/D_NQ_NP_778899-O.webp&quot;,&quot;turl&quot;:&quot;https://tse1.mm.bing.net/th?id=OIP.xyz&quot;,&quot;t&quot;:&quot;Placa Mãe X99 Huananzhi&quot;}"></a>
        """.trimIndent()
        val hits = MercadoLivreHtmlParser.parseBingImagesHtml(bingImgHtml)
        val enriched = MercadoLivreHtmlParser.applyImageHits(webItems, hits)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_778899-O.webp", enriched.first().imageUrl)
    }

    @Test
    fun `parseSearchApiResponse parses official ML search JSON preserving API order, HTTPS images, and clean commission URLs`() {
        val apiJson = """
            {
              "paging": { "total": 120, "offset": 0, "limit": 50 },
              "results": [
                {
                  "id": "MLB55667788",
                  "title": "Placa Mãe X99 DDR4 LGA 2011-3 NVMe",
                  "permalink": "http://www.mercadolivre.com.br/placa-mae-x99-ddr4/p/MLB55667788#position=1&type=item",
                  "thumbnail": "http://http2.mlstatic.com/D_123-I.jpg",
                  "thumbnail_id": "123",
                  "price": 419.50,
                  "category_id": "MLB1692",
                  "domain_id": "MLB-MOTHERBOARDS"
                },
                {
                  "id": "MLB99001122",
                  "title": "Segundo Item Exato da API",
                  "permalink": "https://produto.mercadolivre.com.br/MLB-99001122-item-_JM?foo=bar#tracking_id=abc",
                  "secure_thumbnail": "",
                  "thumbnail": "",
                  "thumbnail_id": "998877-MLA",
                  "price": null,
                  "sale_price": { "amount": 129.90, "currency_id": "BRL" },
                  "category_id": "MLB1692",
                  "domain_id": "MLB-MOTHERBOARDS"
                }
              ]
            }
        """.trimIndent()

        val outcome = MercadoLivreClient().parseSearchApiResponse(apiJson, "x99")
        assertTrue(outcome.hasMore)
        assertEquals(2, outcome.candidates.size)

        val first = outcome.candidates[0]
        assertEquals("ml_MLB55667788", first.id)
        assertEquals("Placa Mãe X99 DDR4 LGA 2011-3 NVMe", first.title)
        assertEquals("https://http2.mlstatic.com/D_123-I.jpg", first.imageUrl)
        assertEquals("R$ 419,50", first.priceText)
        assertFalse(first.affiliateUrl.contains("#"))
        assertEquals(
            "https://www.mercadolivre.com.br/placa-mae-x99-ddr4/p/MLB55667788?matt_tool=75816733&matt_word=scanshop&forceInApp=true",
            first.affiliateUrl
        )

        val second = outcome.candidates[1]
        assertEquals("ml_MLB99001122", second.id)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_998877-MLA-O.jpg", second.imageUrl)
        assertEquals("R$ 129,90", second.priceText)
        assertEquals(
            "https://produto.mercadolivre.com.br/MLB-99001122-item-_JM?foo=bar&matt_tool=75816733&matt_word=scanshop&forceInApp=true",
            second.affiliateUrl
        )
    }

    @Test
    fun `parseSearchApiResponse parses catalog products-search JSON with name, pictures, buy_box_winner and commission URLs`() {
        val catalogJson = """
            {
              "paging": { "total": 2, "offset": 0, "limit": 50 },
              "results": [
                {
                  "id": "MLB29988776",
                  "name": "Placa Mãe X99 Machinist PR9 LGA 2011-3",
                  "domain_id": "MLB-MOTHERBOARDS",
                  "pictures": [
                    { "id": "889900-MLB", "url": "http://http2.mlstatic.com/D_NQ_NP_889900-MLB-O.jpg" }
                  ],
                  "buy_box_winner": {
                    "item_id": "MLB39988776",
                    "price": 389.90,
                    "currency_id": "BRL",
                    "permalink": "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB29988776#wid=MLB39988776"
                  }
                },
                {
                  "id": "MLB11223344",
                  "name": "Kit Xeon X99 E5 2666 V3 16GB DDR4",
                  "pictures": [
                    { "id": "554433-MLA" }
                  ],
                  "buy_box_winner": null
                }
              ]
            }
        """.trimIndent()

        val outcome = MercadoLivreClient().parseSearchApiResponse(catalogJson, "x99")
        assertFalse(outcome.hasMore)
        assertEquals(2, outcome.candidates.size)

        val first = outcome.candidates[0]
        assertEquals("ml_MLB29988776", first.id)
        assertEquals("Placa Mãe X99 Machinist PR9 LGA 2011-3", first.title)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_889900-MLB-O.jpg", first.imageUrl)
        assertEquals("R$ 389,90", first.priceText)
        assertEquals(
            "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB29988776?matt_tool=75816733&matt_word=scanshop&forceInApp=true",
            first.affiliateUrl
        )

        val second = outcome.candidates[1]
        assertEquals("ml_MLB11223344", second.id)
        assertEquals("Kit Xeon X99 E5 2666 V3 16GB DDR4", second.title)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_554433-MLA-O.jpg", second.imageUrl)
        assertEquals("", second.priceText)
        assertEquals(
            "https://www.mercadolivre.com.br/p/MLB11223344?matt_tool=75816733&matt_word=scanshop&forceInApp=true",
            second.affiliateUrl
        )
    }

    @Test
    fun `neuralProductMatcher approves x99 motherboard candidates cleanly`() {
        val profile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "Placa mãe",
            canonicalTermsPt = listOf("placa mae", "kit x99", "placa mãe x99"),
            canonicalTermsEn = listOf("x99 motherboard"),
            alphanumericCodes = listOf("x99"),
            requiredQualities = listOf("x99"),
            expectedDomains = listOf(
                DomainDiscoveryHint("MLB1692", "Placas-mãe", "MLB1692", "Placas-mãe", emptyList())
            ),
            searchQueriesPt = listOf("x99", "placa mae x99")
        )
        val cand = RawStoreCandidate(
            id = "ml_MLB44556677",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 LGA 2011-3 DDR4 NVMe Machinist",
            imageUrl = "https://http2.mlstatic.com/D_NQ_NP_991122-O.webp",
            productUrl = "https://www.mercadolivre.com.br/placa-me-x99-machinist/p/MLB44556677",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-me-x99-machinist/p/MLB44556677",
            priceText = "R$ 389,90",
            domainId = "MLB1692"
        )
        val scored = NeuralProductMatcher().scoreAndFilter(profile, listOf(cand))
        assertEquals(1, scored.size)
        assertFalse("Should not hard-reject valid X99 motherboard", scored.first().hardRejected)
        assertTrue(scored.first().breakdown.neuralProbability >= 0.45f)
    }

    @Test
    fun `parseBingImagesHtml handles double-encoded quotes and imgpt fallback`() {
        val bingImgHtml = """
            <a class="iusc" m="{&quot;purl&quot;:&quot;https://www.mercadolivre.com.br/monitor-24/p/MLB101010&quot;,&quot;murl&quot;:&quot;https:\/\/http2.mlstatic.com\/D_NQ_NP_112233-O.webp&quot;,&quot;turl&quot;:&quot;https://tse1.mm.bing.net/th?id=OIP.abc&quot;,&quot;t&quot;:&quot;Monitor 24&amp;quot; Gamer Placa M&amp;atilde;e&quot;}"></a>
            <img class="mimg" src="/th?id=OIP.fallback99" alt="Kit Placa Mãe X99" />
        """.trimIndent()
        val hits = MercadoLivreHtmlParser.parseBingImagesHtml(bingImgHtml)
        assertEquals(2, hits.size)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_112233-O.webp", hits[0].imageUrl)
        assertTrue(hits[0].title.contains("Monitor 24"))
        assertEquals("https://www.bing.com/th?id=OIP.fallback99", hits[1].thumbUrl)
    }

    @Test
    fun `parseListingHtml extracts polycard items buried after 5000 preloaded state noise nodes`() {
        val noiseEntries = (1..5200).joinToString(",") { "\"k$it\":\"v$it\"" }
        val stateHtml = """
            <script id="__PRELOADED_STATE__" type="application/json">
            {
              "headerNoise": {$noiseEntries},
              "initialState": {
                "results": [
                  {
                    "id": "MLB88776655",
                    "polycard": {
                      "metadata": {
                        "id": "MLB88776655",
                        "url": "www.mercadolivre.com.br/placa-me-x99-k9/p/MLB88776655"
                      },
                      "pictures": {
                        "pictures": [
                          { "id": "901234-MLA5566" }
                        ]
                      },
                      "components": [
                        { "type": "title", "title": { "text": "Placa Mãe X99 K9 LGA 2011-3 DDR4" } },
                        { "type": "price", "price": { "current_price": { "value": 499.90, "currency": "BRL" } } }
                      ]
                    }
                  }
                ]
              }
            }
            </script>
        """.trimIndent()

        val items = MercadoLivreHtmlParser.parseListingHtml(stateHtml, "x99", "")
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("ml_MLB88776655", item.id)
        assertEquals("Placa Mãe X99 K9 LGA 2011-3 DDR4", item.title)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_901234-MLA5566-O.jpg", item.imageUrl)
        assertTrue(item.priceText.contains("499,90"))
    }

    @Test
    fun `parseSingleProductPageHtml picks real current price instead of shipping or crossed-out price`() {
        val pdpHtml = """
            <html>
            <head>
                <meta property="og:title" content="Placa M&atilde;e X99 Machinist MR9A Pro" />
                <meta property="og:image" content="https://http2.mlstatic.com/D_NQ_NP_654321-O.jpg" />
                <script type="application/ld+json">
                {"@type":"Product","name":"Placa Mãe X99 Machinist MR9A Pro","image":["https://http2.mlstatic.com/D_NQ_NP_654321-O.jpg"],"offers":{"price":549.90,"priceCurrency":"BRL"}}
                </script>
            </head>
            <body>
                <div class="shipping">{"shipping":{"price":19.90}}</div>
                <s class="andes-money-amount andes-money-amount--previous"><span class="andes-money-amount__fraction">899</span></s>
                <div class="ui-pdp-price__second-line">
                    <span class="andes-money-amount"><span class="andes-money-amount__fraction">549</span><span class="andes-money-amount__cents">90</span></span>
                </div>
            </body>
            </html>
        """.trimIndent()

        val (title, img, price) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            pdpHtml,
            "https://www.mercadolivre.com.br/placa-me-x99-mr9a/p/MLB99887766"
        )
        assertEquals("Placa Mãe X99 Machinist MR9A Pro", title)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_654321-O.jpg", img)
        assertTrue("Expected R$ 549,90 but got $price", price.contains("549,90"))
    }

    @Test
    fun `normalizeImageUrl and parseBingWebHtml reject Bing ODLS grey globe and Yahoo static icons`() {
        assertEquals("", HtmlTextDecoder.normalizeImageUrl("https://www.bing.com/th?id=ODLS.ab12cd34"))
        assertEquals("", HtmlTextDecoder.normalizeImageUrl("https://s.yimg.com/pv/static/img/yahoo-icon.png"))
        assertEquals("", HtmlTextDecoder.normalizeImageUrl("https://www.bing.com/sa/simg/Flag.png"))
        assertEquals(
            "https://tse1.mm.bing.net/th?id=OIP.validPhoto123",
            HtmlTextDecoder.normalizeImageUrl("https://tse1.mm.bing.net/th?id=OIP.validPhoto123")
        )

        val b64 = Base64.encodeToString(
            "https://produto.mercadolivre.com.br/MLB-1122334455-placa-mae-machinist-x99-u9-_JM".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val bingWithGlobe = """
            <ol id="b_results">
                <li class="b_algo">
                    <h2><a href="https://www.bing.com/ck/a?!&&p=1&u=a1$b64&ntb=1">Placa Mãe Machinist X99 U9 Com Tpm 2.0</a></h2>
                    <img src="/th?id=ODLS.998877" />
                    <div class="b_caption"><p>Assine Meli+ por R$ 14,99. Compre por R$ 479,90 em até 12x.</p></div>
                </li>
            </ol>
        """.trimIndent()
        val parsed = MercadoLivreHtmlParser.parseBingWebHtml(bingWithGlobe, "x99", "")
        assertEquals(1, parsed.size)
        assertEquals("", parsed.first().imageUrl)
        assertTrue("Expected R$ 479,90 instead of 14,99, got ${parsed.first().priceText}", parsed.first().priceText.contains("479,90"))
    }

    @Test
    fun `extractBrlPrice skips Meli plus, frete, parcela and installment snippets`() {
        assertEquals(
            "R$ 389,90",
            MercadoLivreHtmlParser.extractBrlPrice("Assine o Meli+ Total por R$ 14,99/mês. Placa Mãe X99 R$ 389,90")
        )
        assertEquals(
            "R$ 512,00",
            MercadoLivreHtmlParser.extractBrlPrice("Frete a partir de R$ 14,99 | 12x sem juros de R$ 42,67 | Preço R$ 512,00")
        )
        assertEquals(
            "",
            MercadoLivreHtmlParser.extractBrlPrice("Assine Meli+ por R$ 14,99 por mês e ganhe frete R$ 19,90")
        )
    }

    @Test
    fun `mergeCandidate deduplicates identical titles across different MLB IDs`() {
        val map = LinkedHashMap<String, RawStoreCandidate>()
        val first = RawStoreCandidate(
            id = "ml_MLB11111111",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe Machinist X99 U9 Com Tpm 2.0 Lga2011 Xeon Branco",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-x99-u9/p/MLB11111111",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-x99-u9/p/MLB11111111",
            priceText = "R$ 499,90"
        )
        val second = RawStoreCandidate(
            id = "ml_MLB22222222",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe Machinist X99 U9 Com Tpm 2.0 Lga2011 Xeon Branco",
            imageUrl = "https://http2.mlstatic.com/D_NQ_NP_123456-O.webp",
            productUrl = "https://produto.mercadolivre.com.br/MLB-22222222-placa-mae-machinist-x99-u9-_JM",
            affiliateUrl = "https://produto.mercadolivre.com.br/MLB-22222222-placa-mae-machinist-x99-u9-_JM",
            priceText = ""
        )
        MercadoLivreHtmlParser.mergeCandidate(map, first)
        MercadoLivreHtmlParser.mergeCandidate(map, second)
        assertEquals(1, map.size)
        val merged = map.values.first()
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_123456-O.webp", merged.imageUrl)
        assertEquals("R$ 499,90", merged.priceText)
    }

    @Test
    fun `parseYahooImagesHtml and parseGoogleImagesHtml keep product thumbnails and skip placeholders`() {
        val yahooHtml = """
            <img src="https://s.yimg.com/pv/static/img/logo.png" alt="Yahoo" />
            <a href="https://br.images.search.yahoo.com/images/view?imgurl=http2.mlstatic.com%2FD_NQ_NP_554433-O.jpg">link</a>
            <img data-src="https://sp.yimg.com/ib/th?id=OIP.yahooThumb1" alt="Placa Mãe X99 Machinist" />
        """.trimIndent()
        val yHits = MercadoLivreHtmlParser.parseYahooImagesHtml(yahooHtml)
        assertEquals(2, yHits.size)
        assertTrue(yHits.any { it.preferredImageUrl == "https://http2.mlstatic.com/D_NQ_NP_554433-O.jpg" })
        assertTrue(yHits.any { it.preferredImageUrl == "https://sp.yimg.com/ib/th?id=OIP.yahooThumb1" })

        val googleHtml = """
            <img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" alt="placeholder" />
            <img src="https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQabc123" alt="Placa Mãe X99 Xeon" />
        """.trimIndent()
        val gHits = MercadoLivreHtmlParser.parseGoogleImagesHtml(googleHtml)
        assertEquals(1, gHits.size)
        assertEquals("https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQabc123", gHits.first().preferredImageUrl)
    }

    @Test
    fun `neuralProductMatcher keeps unattributed Mercado Livre motherboards without specs and rejects accessories`() {
        val profile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "Placa Mãe",
            canonicalTermsPt = listOf("placa mae", "placa mae x99", "kit x99"),
            canonicalTermsEn = listOf("x99 motherboard"),
            alphanumericCodes = listOf("x99"),
            requiredQualities = listOf("x99"),
            expectedDomains = emptyList(),
            searchQueriesPt = listOf("placa mae x99", "x99")
        )
        val candidates = listOf(
            RawStoreCandidate(
                id = "bg_1",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Machinist X99 U9 Com Tpm 2.0 Lga2011 Xeon Branco",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-111-placa-mae-machinist-x99-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-111-placa-mae-machinist-x99-_JM",
                priceText = ""
            ),
            RawStoreCandidate(
                id = "yh_2",
                store = StoreType.MERCADO_LIVRE,
                title = "Kit Placa Mãe X99 Huananzhi Qd4 Xeon E5 2666 V3 Ddr4",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_1122-O.webp",
                productUrl = "https://www.mercadolivre.com.br/kit-placa-mae-x99/p/MLB222",
                affiliateUrl = "https://www.mercadolivre.com.br/kit-placa-mae-x99/p/MLB222",
                priceText = "R$ 689,00"
            ),
            RawStoreCandidate(
                id = "bg_3",
                store = StoreType.MERCADO_LIVRE,
                title = "Suporte Cooler Lga 2011 Para Placa X99",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-333-suporte-x99-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-333-suporte-x99-_JM",
                priceText = "R$ 29,90"
            )
        )
        val scored = NeuralProductMatcher().scoreAndFilter(profile, candidates)
        val approvedIds = scored.filter { !it.hardRejected && it.breakdown.neuralProbability >= 0.34f }.map { it.candidate.id }
        assertTrue("Expected bg_1 to pass neural matcher", "bg_1" in approvedIds)
        assertTrue("Expected yh_2 to pass neural matcher", "yh_2" in approvedIds)
        assertFalse("Expected accessory suporte bg_3 to be rejected", "bg_3" in approvedIds)
    }

    @Test
    fun `parseListingHtml and parseSingleProductPageHtml extract Ofertas polycard picture id and srcset`() {
        val listingHtml = """
            <li class="ui-search-layout__item">
              <div class="poly-card">
                <picture>
                  <source srcset="data:image/gif;base64,AAAA 1x, https://http2.mlstatic.com/D_NQ_NP_223344-O.webp 2x" />
                  <img class="poly-component__picture" src="data:image/gif;base64,AAAA" />
                </picture>
                <a class="poly-component__title" href="https://www.mercadolivre.com.br/placa-mae-x99-pro/p/MLB123456">Placa Mãe X99 Pro DDR4</a>
                <div class="poly-price__current"><span class="andes-money-amount__fraction">499</span></div>
              </div>
            </li>
            <script id="__PRELOADED_STATE__">
            {"results":[{"id":"MLB778899","polycard":{"metadata":{"id":"MLB778899","url":"https://www.mercadolivre.com.br/kit-x99-xeon/p/MLB778899"},"components":[{"type":"title","title":{"text":"Kit Placa Mãe X99 Xeon E5"}},{"type":"picture","picture":{"pictures":[{"id":"665544-MLA9900"}]}},{"type":"price","price":{"current_price":{"value":629.0}}}]}}]}
            </script>
        """.trimIndent()
        val parsed = MercadoLivreHtmlParser.parseListingHtml(listingHtml, "x99", "")
        assertEquals(2, parsed.size)
        val domItem = parsed.first { it.id == "ml_MLB123456" }
        val stateItem = parsed.first { it.id == "ml_MLB778899" }
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_223344-O.webp", domItem.imageUrl)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_665544-MLA9900-O.jpg", stateItem.imageUrl)

        val pdpNoOg = """
            <html><body>
              <h1 class="ui-pdp-title">Placa Mãe X99 Machinist K9</h1>
              <script>window.__PRELOADED_STATE__ = {"pictures":[{"id":"887711-MLB2233"}]};</script>
              <div class="ui-pdp-price__second-line"><span class="andes-money-amount__fraction">459</span></div>
            </body></html>
        """.trimIndent()
        val (_, pdpImg, pdpPrice) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            pdpNoOg,
            "https://www.mercadolivre.com.br/placa-mae-x99-k9/p/MLB9988"
        )
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_887711-MLB2233-O.jpg", pdpImg)
        assertTrue(pdpPrice.contains("459"))
    }

    @Test
    fun `parseYahooImagesHtml, parseGoogleImagesHtml, and applyImageHits preserve nearby titles and populate all items`() {
        val yahooHtml = """
            <a href="https://br.images.search.yahoo.com/images/view?imgurl=http2.mlstatic.com%2FD_NQ_NP_909090-O.webp&rurl=https%3A%2F%2Fwww.mercadolivre.com.br%2Fplaca-mae-x99-machinist%2Fp%2FMLB101010&tit=Placa+M%C3%A3e+X99+Machinist+U9">img</a>
            <img data-src="https://sp.yimg.com/ib/th?id=OIP.qiyida99" alt="Placa Mãe X99 Qiyida D4 Xeon" />
        """.trimIndent()
        val yHits = MercadoLivreHtmlParser.parseYahooImagesHtml(yahooHtml)
        assertEquals(2, yHits.size)
        assertTrue(yHits[0].title.contains("Machinist"))
        assertTrue(yHits[0].pageUrl.contains("MLB101010"))

        val googleHtml = """
            <div><a href="https://www.mercadolivre.com.br/kit-x99-huananzhi-qd4/p/MLB202020">Kit</a>
            <img src="https://http2.mlstatic.com/D_NQ_NP_707070-O.jpg" alt="Kit Placa Mãe X99 Huananzhi QD4" /></div>
        """.trimIndent()
        val gHits = MercadoLivreHtmlParser.parseGoogleImagesHtml(googleHtml)
        assertTrue(gHits.any { it.preferredImageUrl == "https://http2.mlstatic.com/D_NQ_NP_707070-O.jpg" && it.title.contains("Huananzhi") })

        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_MLB101010",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 Machinist U9",
                imageUrl = "",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist/p/MLB101010",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist/p/MLB101010",
                priceText = "R$ 499,00"
            ),
            RawStoreCandidate(
                id = "ml_MLB303030",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 Qiyida D4",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-303030-placa-mae-x99-qiyida-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-303030-placa-mae-x99-qiyida-_JM",
                priceText = "R$ 429,00"
            )
        )
        val updated = MercadoLivreHtmlParser.applyImageHits(candidates, yHits + gHits)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_909090-O.webp", updated[0].imageUrl)
        assertEquals("https://sp.yimg.com/ib/th?id=OIP.qiyida99", updated[1].imageUrl)
    }

    @Test
    fun `neuralMatcher allows para-processador main products and code-only titles while rejecting cases`() {
        val profile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "Placa Mãe",
            canonicalTermsPt = listOf("placa mae", "placa mae x99", "x99"),
            canonicalTermsEn = listOf("x99 motherboard"),
            alphanumericCodes = listOf("x99"),
            brand = "Intel",
            requiredQualities = listOf("ddr4"),
            expectedDomains = emptyList(),
            searchQueriesPt = listOf("placa mae x99", "x99")
        )
        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_MLBU334455",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Para Processador Intel Xeon X99 Lga 2011 Ddr4",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_111-O.webp",
                productUrl = "https://www.mercadolivre.com.br/up/MLBU334455",
                affiliateUrl = "https://www.mercadolivre.com.br/up/MLBU334455",
                priceText = "R$ 499,00"
            ),
            RawStoreCandidate(
                id = "bg_kit",
                store = StoreType.MERCADO_LIVRE,
                title = "Kit Xeon X99 E5 2670 V3 DDR4 Memoria 16GB",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-556677-kit-xeon-x99-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-556677-kit-xeon-x99-_JM",
                priceText = "R$ 689,00"
            ),
            RawStoreCandidate(
                id = "bg_capa",
                store = StoreType.MERCADO_LIVRE,
                title = "Capa Protetora Para Placa X99 Xeon",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-889900-capa-x99-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-889900-capa-x99-_JM",
                priceText = "R$ 39,90"
            )
        )
        val scored = NeuralProductMatcher().scoreAndFilter(profile, candidates)
        val byId = scored.associateBy { it.candidate.id }

        val mainProd = byId.getValue("ml_MLBU334455")
        assertFalse("Placa mãe para processador must not be hardRejected", mainProd.hardRejected)
        assertTrue("Expected score >= 0.34, got ${mainProd.breakdown.neuralProbability}", mainProd.breakdown.neuralProbability >= 0.34f)

        val kit = byId.getValue("bg_kit")
        assertFalse("Kit missing brand keyword in title must not be hardRejected", kit.hardRejected)
        assertTrue("Expected kit score >= 0.22, got ${kit.breakdown.neuralProbability}", kit.breakdown.neuralProbability >= 0.22f)

        val caseItem = byId.getValue("bg_capa")
        assertTrue("Expected accessory case to stay hardRejected", caseItem.hardRejected)
    }

    @Test
    fun `extractFirstValidJsonArray ignores citation brackets and markdown around JSON array`() {
        val noisyResponse = """
            Aqui estão os resultados [1]:
            ```json
            [
              {"title":"Placa Mãe X99 Machinist","url":"https://www.mercadolivre.com.br/up/MLBU998877","price":"R$ 499,90"},
              {"title":"Kit X99 Xeon E5","url":"https://produto.mercadolivre.com.br/MLB-123456-kit-x99-_JM","price":"R$ 650,00"}
            ]
            ```
            Fonte consultada no Google [2] e [3].
        """.trimIndent()
        val arr = MercadoLivreHtmlParser.extractFirstValidJsonArray(noisyResponse)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
        assertEquals("Placa Mãe X99 Machinist", arr.getJSONObject(0).getString("title"))
        assertTrue(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/up/MLBU998877"))
        assertEquals("MLBU998877", MercadoLivreHtmlParser.extractMlbId("https://www.mercadolivre.com.br/up/MLBU998877"))
    }

    @Test
    fun `parseBingWebHtml breadcrumb cite fallback and parseGoogleWebHtml extract ML results`() {
        val bingBreadcrumbHtml = """
            <li class="b_algo">
              <h2><a href="https://www.bing.com/ck/a?!&&u=not_base64">Placa Mãe Huananzhi X99 QD4 DDR4</a></h2>
              <cite>https://www.mercadolivre.com.br › placa-mae-huananzhi-x99 › p › MLB445566</cite>
              <div class="b_caption"><p>R$ 589,90 em até 12x</p></div>
            </li>
        """.trimIndent()
        val bingParsed = MercadoLivreHtmlParser.parseBingWebHtml(bingBreadcrumbHtml, "x99", "")
        assertEquals(1, bingParsed.size)
        assertEquals("https://www.mercadolivre.com.br/placa-mae-huananzhi-x99/p/MLB445566", bingParsed.first().productUrl)
        assertTrue(bingParsed.first().priceText.contains("589,90"))

        val googleWebHtml = """
            <div class="g">
              <a href="/url?q=https://produto.mercadolivre.com.br/MLB-77889911-placa-mae-x99-pro-_JM&sa=U">
                Placa Mãe X99 Pro Lga 2011 Ddr4
              </a>
              <div>R$ 479,00 · Em estoque</div>
            </div>
        """.trimIndent()
        val googleParsed = MercadoLivreHtmlParser.parseGoogleWebHtml(googleWebHtml, "x99", "")
        assertEquals(1, googleParsed.size)
        assertEquals("https://produto.mercadolivre.com.br/MLB-77889911-placa-mae-x99-pro-_JM", googleParsed.first().productUrl)
        assertTrue(googleParsed.first().priceText.contains("479,00"))
    }

    @Test
    fun `ofertas and mais-vendidos parsing filters out category hubs and extracts real promo cards`() {
        assertFalse(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/ofertas"))
        assertFalse(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/ofertas?container_id=MLB1051"))
        assertFalse(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/mais-vendidos"))
        assertFalse(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/mais-vendidos/MLB1051"))
        assertFalse(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/c/celulares-e-telefones"))
        assertTrue(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/smartphone-samsung/p/MLB123456"))
        assertTrue(MercadoLivreHtmlParser.isValidProductUrl("https://www.mercadolivre.com.br/up/MLBU998877"))

        val promoHtml = """
            <nav>
              <a href="https://www.mercadolivre.com.br/ofertas?container_id=MLB1051">Ofertas em Celulares</a>
              <a href="https://www.mercadolivre.com.br/mais-vendidos/MLB1648">Mais vendidos em Informática</a>
            </nav>
            <ol class="items_container">
              <li class="promotion-item">
                <a class="promotion-item__link-container" href="https://www.mercadolivre.com.br/fone-jbl-tune-520bt/p/MLB554433">
                  <img data-src="https://http2.mlstatic.com/D_NQ_NP_102030-O.webp" />
                  <p class="promotion-item__title">Fone De Ouvido JBL Tune 520BT Bluetooth</p>
                  <div class="promotion-item__price"><span class="andes-money-amount__fraction">229</span><span class="andes-money-amount__cents">90</span></div>
                </a>
              </li>
            </ol>
            <script id="__PRELOADED_STATE__">
              {"initialState":{"items":[{"id":"MLBU778899","title":"Air Fryer Mondial 4L Inox","sale_price":289.90,"thumbnail":"https://http2.mlstatic.com/D_NQ_NP_405060-O.jpg"}]}}
            </script>
        """.trimIndent()

        val parsed = MercadoLivreHtmlParser.parseListingHtml(promoHtml, "mais_vendidos_p1", "MLB1051")
        assertEquals(2, parsed.size)
        val domPromo = parsed.first { it.id == "ml_MLB554433" }
        assertTrue(domPromo.title.contains("JBL Tune 520BT"))
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_102030-O.webp", domPromo.imageUrl)
        assertTrue(domPromo.priceText.contains("229,90"))

        val jsonPromo = parsed.first { it.id == "ml_MLBU778899" }
        assertEquals("https://www.mercadolivre.com.br/up/MLBU778899", jsonPromo.productUrl)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_405060-O.jpg", jsonPromo.imageUrl)
        assertTrue(jsonPromo.priceText.contains("289,90"))
    }

    @Test
    fun `parseListingHtml picks current price after crossed-out previous price on mais-vendidos cards and skips banners`() {
        val longPreviousBlock = " ".repeat(640)
        val html = """
            <div class="andes-card andes-landings-card">
              <a href="https://www.mercadolivre.com.br/ofertas">Banner Imperdivel</a>
            </div>
            <div class="andes-card poly-card poly-card--grid-card poly-card--large">
              <div class="poly-card__portada">
                <img class="poly-component__picture" src="https://http2.mlstatic.com/D_Q_NP_2X_112233-MLA-V.webp" />
              </div>
              <div class="poly-card__content">
                <a class="poly-component__title" href="https://www.mercadolivre.com.br/smartwatch-amazfit-bip-5/p/MLB28901234">Smartwatch Amazfit Bip 5 Tela 1.91 Bluetooth</a>
                <div class="poly-component__price">
                  <s class="andes-money-amount andes-money-amount--previous andes-money-amount--cents-comma" aria-label="Antes: 499 reais">
                    <span class="andes-money-amount__currency-symbol">R$</span>
                    <span class="andes-money-amount__fraction">499</span>
                  </s>
                  $longPreviousBlock
                  <div class="poly-price__current">
                    <span class="andes-money-amount andes-money-amount--cents-comma" aria-label="Agora: 404 reais com 10 centavos">
                      <span class="andes-money-amount__currency-symbol">R$</span>
                      <span class="andes-money-amount__fraction">404</span>
                      <span class="andes-money-amount__cents">10</span>
                    </span>
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()

        val items = MercadoLivreHtmlParser.parseListingHtml(html, "mais_vendidos_p1", "Mais Vendidos")
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("ml_MLB28901234", item.id)
        assertTrue("Expected R$ 404,10 instead of previous price, got ${item.priceText}", item.priceText.contains("404,10"))
        assertFalse("Should never pick previous price 499", item.priceText.contains("499"))
        assertEquals("https://http2.mlstatic.com/D_Q_NP_2X_112233-MLA-V.webp", item.imageUrl)
    }

    @Test
    fun `parseBingWebHtml handles stylesheet link bloat and wrapped a-h2 links and parseYahooHtml handles nested h3`() {
        val cssLinks = (1..28).joinToString("") { """<link rel="stylesheet" href="https://www.bing.com/rp/style_$it.css?v=123456789012345678901234567890" />""" }
        val b64 = Base64.encodeToString(
            "https://www.mercadolivre.com.br/up/MLBU77665544".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val bingDesktopHtml = """
            <ol id="b_results">
              <li class="b_algo">
                $cssLinks
                <a class="tilk" href="https://www.bing.com/ck/a?!&&p=1&u=a1$b64&ntb=1">
                  <h2>Kit Placa Mãe X99 Xeon E5 2680 V4 16GB DDR4</h2>
                </a>
                <div class="b_caption"><p>Compre no Mercado Livre por R$ 549,90 em até 12x</p></div>
              </li>
            </ol>
        """.trimIndent()

        val bingItems = MercadoLivreHtmlParser.parseBingWebHtml(bingDesktopHtml, "x99", "")
        assertEquals(1, bingItems.size)
        assertEquals("https://www.mercadolivre.com.br/up/MLBU77665544", bingItems.first().productUrl)
        assertTrue(bingItems.first().title.contains("Placa Mãe X99"))
        assertTrue(bingItems.first().priceText.contains("549,90"))

        val yahooUsHtml = """
            <div class="dd algo algo-sr">
              <div class="compTitle">
                <a href="https://r.search.yahoo.com/_ylt=abc/RV=2/RE=1/RO=10/RU=https%3A%2F%2Fproduto.mercadolivre.com.br%2FMLB-29478035-placa-mae-x99-machinist-_JM/RK=2/RS=xyz">
                  <span class="d-ib va-mid">mercadolivre.com.br › informatica › MLB-29478035</span>
                  <h3 class="title"><span>Placa Mãe X99 Machinist PR9 LGA 2011-3 DDR4</span></h3>
                </a>
              </div>
              <div class="compText"><p>Oferta por R$ 419,00 com frete grátis.</p></div>
            </div>
        """.trimIndent()

        val yahooItems = MercadoLivreHtmlParser.parseYahooHtml(yahooUsHtml, "x99", "")
        assertEquals(1, yahooItems.size)
        assertEquals("https://produto.mercadolivre.com.br/MLB-29478035-placa-mae-x99-machinist-_JM", yahooItems.first().productUrl)
        assertEquals("Placa Mãe X99 Machinist PR9 LGA 2011-3 DDR4", yahooItems.first().title)
        assertTrue(yahooItems.first().priceText.contains("419,00"))
    }

    @Test
    fun `parseBingRssXml extracts direct ML links, decodes titles and prices, and skips lista URLs`() {
        val rssXml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>Lista X99 no Mercado Livre Brasil</title>
                  <link>https://lista.mercadolivre.com.br/x99</link>
                  <description>Encontre X99 com frete grátis</description>
                </item>
                <item>
                  <title>Placa M&amp;atilde;e X99 Machinist PR9 LGA 2011-3 DDR4 - Mercado Livre</title>
                  <link>https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB28944511</link>
                  <description><![CDATA[Compre Placa Mãe X99 Machinist PR9 por R$ 429,90 em até 12x no Mercado Livre.]]></description>
                </item>
                <item>
                  <title>Kit Xeon X99 E5 2670 V3 16GB DDR4</title>
                  <link>https://produto.mercadolivre.com.br/MLB-33998877-kit-xeon-x99-e5-2670-v3-_JM</link>
                  <description>Frete grátis no dia Compre agora.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = MercadoLivreHtmlParser.parseBingRssXml(rssXml, "x99", "")
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB28944511", first.productUrl)
        assertTrue(first.title.contains("Placa Mãe X99 Machinist PR9"))
        assertFalse(first.title.contains("Mercado Livre"))
        assertTrue("Expected R$ 429,90 in description, got ${first.priceText}", first.priceText.contains("429,90"))
        assertTrue(first.affiliateUrl.contains("matt_tool=75816733"))

        val second = items[1]
        assertEquals("https://produto.mercadolivre.com.br/MLB-33998877-kit-xeon-x99-e5-2670-v3-_JM", second.productUrl)
        assertEquals("", second.priceText)
    }

    @Test
    fun `priceFormatter and extractBrlPrice parse Brazilian thousands without cents cleanly`() {
        assertEquals("R$ 4.140,00", PriceFormatter.formatBrl("R$ 4.140"))
        assertEquals("R$ 4.140,00", PriceFormatter.formatBrl("4.140"))
        assertEquals("R$ 1.299,00", PriceFormatter.formatBrl("1.299"))
        assertEquals("R$ 419,50", PriceFormatter.formatBrl("419.50"))
        assertEquals("R$ 1.489,90", PriceFormatter.formatBrl("1.489,90"))
        assertEquals(
            "R$ 4.140,00",
            MercadoLivreHtmlParser.extractBrlPrice("Kit X99 Dual CPU no Mercado Livre por R$ 4.140 em 12x sem juros de R$ 345,00")
        )
        assertEquals(
            "",
            MercadoLivreHtmlParser.extractBrlPrice("Taxa com juros de R$ 4,14 e envio R$ 14,99")
        )
    }

    @Test
    fun `buildCompactModelQuery and parseGroundedPriceResponse resolve indexed JSON, text lines, and groundingSupports`() {
        assertEquals(
            "Placa Mãe Intel X99 Machinist Pr9 Ddr4",
            MercadoLivreHtmlParser.buildCompactModelQuery("Placa Mãe Intel X99 Machinist Pr9 Ddr4 Lga 2011-3 V3 E V4 Cor ...")
        )
        assertEquals(
            "Kit X99 Placa Mãe Dual Cpu 2680",
            MercadoLivreHtmlParser.buildCompactModelQuery("Kit X99 Placa Mãe Dual Cpu X99 + 2680 V4 + 128gb Ddr4")
        )

        val candidates = listOf(
            RawStoreCandidate(
                id = "bg_111",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Intel X99 Machinist Pr9 Ddr4",
                imageUrl = "",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-pr9/p/MLB111",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-pr9/p/MLB111"
            ),
            RawStoreCandidate(
                id = "bg_222",
                store = StoreType.MERCADO_LIVRE,
                title = "Kit X99 Huananzhi Qd4 Xeon E5 2666 V3",
                imageUrl = "",
                productUrl = "https://produto.mercadolivre.com.br/MLB-222-kit-x99-qd4-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-222-kit-x99-qd4-_JM"
            ),
            RawStoreCandidate(
                id = "bg_333",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Kllisre X99 D4 Xeon 2680 V4",
                imageUrl = "",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-kllisre-x99/p/MLB333",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-kllisre-x99/p/MLB333"
            ),
            RawStoreCandidate(
                id = "bg_444",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Atermiter X99 Turbo D4",
                imageUrl = "",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-atermiter-x99/p/MLB444",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-atermiter-x99/p/MLB444"
            )
        )

        val rawResponse = """
            ```json
            [
              {"i": 1, "price": "R$ 439,90"},
              {"i": "3", "price": 519.0},
              {"i": 4, "price": "R$ 0,00"}
            ]
            ```
            [2] Kit X99 Huananzhi Qd4 sai por R$ 789,00 no Mercado Livre.
        """.trimIndent()
        val supports = listOf("A Placa Mãe Atermiter X99 Turbo está custando R$ 469,50 hoje.")

        val parsed = MercadoLivreHtmlParser.parseGroundedPriceResponse(rawResponse, candidates, supports)
        assertEquals("R$ 439,90", parsed["bg_111"])
        assertEquals("R$ 789,00", parsed["bg_222"])
        assertEquals("R$ 519,00", parsed["bg_333"])
        assertEquals("R$ 469,50", parsed["bg_444"])
    }

    @Test
    fun `extractBrlPrice rejects tiny discount fee amounts and crossed-out prices while picking real product price`() {
        assertEquals(
            "",
            MercadoLivreHtmlParser.extractBrlPrice("Economize até R$ 4,14 com pagamento no Pix e cupom")
        )
        assertEquals(
            "R$ 499,00",
            MercadoLivreHtmlParser.extractBrlPrice("De R$ 899,00 por R$ 499,00 no Mercado Livre")
        )
        assertEquals(
            "R$ 489,90",
            MercadoLivreHtmlParser.extractBrlPrice("Taxa de R$ 4,14 aplicada · Placa Mãe X99 PR9 R$ 489,90")
        )
    }

    @Test
    fun `parseNextDataPriceCatalog, parseGroundedCatalogPrices, and applyPriceMapToCandidates enrich matching models accurately`() {
        val nextDataHtml = """
            <html><head><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"products":[
              {"name":"Placa Mãe Qiyida X99 H9s Ddr4 Vermelha Lga 2011-3","price":455.91},
              {"name":"Placa Mae Machinist Pr9 X99 Micro Atx Black Green Xeon","priceValue":497.0},
              {"name":"Controle Remoto Receptor Net Line X99 HD","price":26.04}
            ]}}}
            </script></head></html>
        """.trimIndent()
        val catalog = MercadoLivreHtmlParser.parseNextDataPriceCatalog(nextDataHtml)
        assertEquals(3, catalog.size)

        val sweepPairs = MercadoLivreHtmlParser.parseGroundedCatalogPrices(
            """[{"title":"Placa Mãe Machinist X99 U9 Lga 2011 Xeon Branco","price":"R$ 650,00"}]""",
            supportSegments = listOf("Kit X99 Placa Mãe Dual Cpu 2680 V4 128gb Ddr4 custa R$ 1.249,00.")
        )
        assertEquals(2, sweepPairs.size)

        val client = MercadoLivreClient()
        val priceMap = LinkedHashMap<String, String>()
        catalog.forEach { (k, v) -> priceMap[k] = v }
        sweepPairs.forEach { (k, v) -> priceMap[k] = v }

        val candidates = listOf(
            RawStoreCandidate(
                id = "bg_qiyida",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Qiyida X99 H9s Ddr4 Lga 2011-3 Xeon",
                imageUrl = "https://http2.mlstatic.com/D_1-O.jpg",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-qiyida-x99-h9s/p/MLB101",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-qiyida-x99-h9s/p/MLB101",
                priceText = ""
            ),
            RawStoreCandidate(
                id = "bg_pr9",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Intel X99 Machinist Pr9 Ddr4 Lga 2011-3",
                imageUrl = "https://http2.mlstatic.com/D_2-O.jpg",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-pr9/p/MLB102",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-pr9/p/MLB102",
                priceText = ""
            ),
            RawStoreCandidate(
                id = "bg_u9_bad_price",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Machinist X99 U9 Com Tpm 2.0 Lga2011 Xeon Branco",
                imageUrl = "https://http2.mlstatic.com/D_3-O.jpg",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-u9/p/MLB103",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-machinist-u9/p/MLB103",
                priceText = "R$ 4,14"
            ),
            RawStoreCandidate(
                id = "bg_f8_unlisted",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Huananzhi X99 F8D Dual Cpu Lga2011",
                imageUrl = "https://http2.mlstatic.com/D_4-O.jpg",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-huananzhi-f8d/p/MLB104",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-huananzhi-f8d/p/MLB104",
                priceText = ""
            )
        )

        val updated = client.applyPriceMapToCandidates(candidates, priceMap)
        assertEquals("R$ 455,91", updated[0].priceText)
        assertEquals("R$ 497,00", updated[1].priceText)
        assertEquals("R$ 650,00", updated[2].priceText)
        assertEquals("", updated[3].priceText)
    }

    @Test
    fun `applyImageHits matches live product thumbnails by title overlap and MLB ID without static blocks`() {
        val mlProduct = RawStoreCandidate(
            id = "ml_MLB998811",
            store = StoreType.MERCADO_LIVRE,
            title = "Furadeira Parafusadeira Impacto 21V Brushless",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/furadeira-parafusadeira-21v/p/MLB998811",
            affiliateUrl = "https://www.mercadolivre.com.br/furadeira-parafusadeira-21v/p/MLB998811"
        )

        val unrelatedHits = listOf(
            SearchEngineImageHit(
                title = "Cafeteira Expresso Inox 20 Bar",
                pageUrl = "https://outro.example.com/cafeteira",
                imageUrl = "https://outro.example.com/cafeteira.jpg",
                thumbUrl = "https://tse1.mm.bing.net/th?id=OIP.other1"
            )
        )
        val afterUnrelated = MercadoLivreHtmlParser.applyImageHits(listOf(mlProduct), unrelatedHits)
        assertEquals("", afterUnrelated.first().imageUrl)

        val mixedHits = unrelatedHits + SearchEngineImageHit(
            title = "Furadeira Parafusadeira 21V Brushless Com Maleta",
            pageUrl = "https://produto.mercadolivre.com.br/MLB-776655-furadeira-21v-_JM",
            imageUrl = "https://http2.mlstatic.com/D_NQ_NP_887766-O.webp",
            thumbUrl = "https://tse1.mm.bing.net/th?id=OIP.drill1"
        )
        val afterMixed = MercadoLivreHtmlParser.applyImageHits(listOf(mlProduct), mixedHits)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_887766-O.webp", afterMixed.first().imageUrl)

        val bingGenericHtml = """
            <img class="mimg" src="/th?id=OIP.genericOnly" alt="furadeira" />
            <img class="mimg" src="/th?id=OIP.genericRes" alt="Resultado de imagem para furadeira" />
        """.trimIndent()
        assertTrue(MercadoLivreHtmlParser.parseBingImagesHtml(bingGenericHtml).isEmpty())
    }

    @Test
    fun `buildHeuristicProfile uses live query tokens and discovery hints without static synonym tables`() {
        val hint = com.example.data.model.DomainDiscoveryHint(
            domainId = "MLB-DRILLS",
            domainName = "Furadeira",
            categoryId = "MLB1574",
            categoryName = "Ferramentas",
            attributes = emptyList()
        )
        val profile = GeminiQueryAndVerifierEngine().buildHeuristicProfile("parafusadeira 21v", listOf(hint))
        assertTrue(profile.searchQueriesPt.any { it.equals("parafusadeira 21v", ignoreCase = true) })
        assertTrue(profile.canonicalTermsPt.any { it.equals("Furadeira", ignoreCase = true) })
        assertTrue(profile.alphanumericCodes.contains("21v"))
    }

    @Test
    fun `image fallbacks recover titled Bing thumbnails and verify live extractor helpers`() {
        val bingLabeledHtml = """
            <img class="mimg" src="/th?id=OIP.drillLabeled99" alt="Resultado de imagem para Furadeira Parafusadeira 21V Brushless" />
            <img class="mimg" src="/th?id=OIP.genericOnly" alt="Resultado de imagem para furadeira" />
        """.trimIndent()
        val parsedHits = MercadoLivreHtmlParser.parseBingImagesHtml(bingLabeledHtml)
        assertEquals(1, parsedHits.size)
        assertEquals("Furadeira Parafusadeira 21V Brushless", parsedHits.first().title)
        assertEquals("https://www.bing.com/th?id=OIP.drillLabeled99", parsedHits.first().preferredImageUrl)

        val drillCand = RawStoreCandidate(
            id = "bg_drill_21v",
            store = StoreType.MERCADO_LIVRE,
            title = "Furadeira Parafusadeira 21V Brushless Bivolt",
            imageUrl = "",
            productUrl = "https://produto.mercadolivre.com.br/MLB-771122-furadeira-21v-_JM",
            affiliateUrl = "https://produto.mercadolivre.com.br/MLB-771122-furadeira-21v-_JM"
        )
        val afterCrossMatch = MercadoLivreHtmlParser.applyImageHits(listOf(drillCand), parsedHits)
        assertEquals("https://www.bing.com/th?id=OIP.drillLabeled99", afterCrossMatch.first().imageUrl)

        val verifiedFallbackHits = listOf(
            SearchEngineImageHit(
                title = "",
                pageUrl = "",
                imageUrl = "",
                thumbUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcUnlabeledThumb"
            ),
            SearchEngineImageHit(
                title = "Furadeira Impacto 21V Maleta",
                pageUrl = "https://loja.example.com/furadeira",
                imageUrl = "",
                thumbUrl = "https://sp.yimg.com/ib/th?id=OIP.validDrill"
            )
        )
        val fallbackHit = MercadoLivreHtmlParser.pickFallbackCandidateImageHit(
            candidateTitle = drillCand.title,
            productUrl = drillCand.productUrl,
            hits = verifiedFallbackHits
        )
        assertEquals("https://sp.yimg.com/ib/th?id=OIP.validDrill", fallbackHit?.preferredImageUrl)
    }

    @Test
    fun `image parsers handle data-src after blank src, relative th-id paths, script thumbs, and D_ pic IDs`() {
        val bingLazyHtml = """
            <div class="iuscp" data-tag="m:{&quot;purl&quot;:&quot;https://www.mercadolivre.com.br/placa-mae-x99-k9/p/MLB554433&quot;,&quot;extra&quot;:1}">
              <img class="mimg" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-src="/th/id/OIP.lazyRel99?w=200" alt="Placa Mãe X99 K9 DDR4" />
            </div>
        """.trimIndent()
        val bingHits = MercadoLivreHtmlParser.parseBingImagesHtml(bingLazyHtml)
        assertEquals(1, bingHits.size)
        assertEquals("https://www.bing.com/th/id/OIP.lazyRel99?w=200", bingHits.first().preferredImageUrl)
        assertEquals("https://www.mercadolivre.com.br/placa-mae-x99-k9/p/MLB554433", bingHits.first().pageUrl)

        val googleScriptHtml = """
            <div>
              <img src="data:image/gif;base64,AAAA" data-src="https://encrypted-tbn1.gstatic.com/images?q=tbn:ANd9GcQImgTag1" alt="Kit Placa Mãe X99" />
              <script>
                var _data = ["https://www.mercadolivre.com.br/kit-x99-xeon/p/MLB776611","https://encrypted-tbn2.gstatic.com/images?q=tbn:ANd9GcQScriptThumb2"];
              </script>
            </div>
        """.trimIndent()
        val gHits = MercadoLivreHtmlParser.parseGoogleImagesHtml(googleScriptHtml)
        assertTrue(gHits.any { it.preferredImageUrl.contains("ANd9GcQImgTag1") })
        assertTrue(gHits.any { it.preferredImageUrl.contains("ANd9GcQScriptThumb2") && it.pageUrl.contains("MLB776611") })

        val stateDPrefixHtml = """
            <script id="__PRELOADED_STATE__">
            {"results":[{"id":"MLB665511","permalink":"https://www.mercadolivre.com.br/kit-x99/p/MLB665511","components":[{"type":"title","title":{"text":"Kit X99 Machinist PR9"}},{"type":"picture","picture":{"pictures":[{"id":"D_NQ_NP_2X_445566-MLA"}]}}]}]}
            </script>
        """.trimIndent()
        val stateItems = MercadoLivreHtmlParser.parseListingHtml(stateDPrefixHtml, "x99", "")
        assertEquals(1, stateItems.size)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_2X_445566-MLA-O.jpg", stateItems.first().imageUrl)
    }

    @Test
    fun `parseSingleProductPageHtml extracts prices from LD+JSON array, graph, and currency-first current_price`() {
        val ldArrayGraphHtml = """
            <html>
            <head>
              <title>Placa Mãe X99 Huananzhi BD4</title>
              <script type="application/ld+json">
              [
                {"@type":"BreadcrumbList"},
                {"@graph":[
                  {"@type":"Product","name":"Placa Mãe X99 Huananzhi BD4","image":{"url":"https://http2.mlstatic.com/D_NQ_NP_314159-O.webp"},"offers":[{"lowPrice":"589.90"}]}
                ]}
              ]
              </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        val (title1, img1, price1) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            ldArrayGraphHtml,
            "https://www.mercadolivre.com.br/placa-mae-x99-bd4/p/MLB314159"
        )
        assertEquals("Placa Mãe X99 Huananzhi BD4", title1)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_314159-O.webp", img1)
        assertEquals("R$ 589,90", price1)

        val currentPriceHtml = """
            <html><body>
              <h1 class="ui-pdp-title">Kit Placa Mãe X99 E5 2680 V4</h1>
              <script>
                window.__PRELOADED_STATE__ = {"price":{"current_price":{"currency_id":"BRL","value":749.50}},"thumbnail_id":"998811-MLB"};
              </script>
            </body></html>
        """.trimIndent()
        val (title2, img2, price2) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            currentPriceHtml,
            "https://produto.mercadolivre.com.br/MLB-998811-kit-x99-_JM"
        )
        assertEquals("Kit Placa Mãe X99 E5 2680 V4", title2)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_998811-MLB-O.jpg", img2)
        assertEquals("R$ 749,50", price2)
    }

    @Test
    fun `unlabeled Yahoo and Google thumbnails never forge titles from nearMlUrl to bypass hardware filter`() {
        val candidate = RawStoreCandidate(
            id = "ml_MLB776611",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 Machinist PR9 LGA 2011 DDR4",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB776611",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB776611"
        )

        val googleUnlabeledNearMl = """
            <script>
              var _d = ["https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB776611","https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQCarPlateThumb"];
            </script>
        """.trimIndent()
        val gHits = MercadoLivreHtmlParser.parseGoogleImagesHtml(googleUnlabeledNearMl)
        assertEquals(1, gHits.size)
        assertEquals("", gHits.first().title)
        assertEquals(
            "",
            MercadoLivreHtmlParser.applyImageHits(listOf(candidate), gHits).first().imageUrl
        )
        assertEquals(
            null,
            MercadoLivreHtmlParser.pickFallbackCandidateImageHit(candidate.title, candidate.productUrl, gHits)
        )

        val yahooUnlabeledNearMl = """
            <div>
              <a href="https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB776611">link</a>
              <img src="https://sp.yimg.com/ib/th?id=OIP.UnlabeledPlate" alt="" />
            </div>
        """.trimIndent()
        val yHits = MercadoLivreHtmlParser.parseYahooImagesHtml(yahooUnlabeledNearMl)
        assertEquals(1, yHits.size)
        assertEquals("", yHits.first().title)
        assertEquals(
            "",
            MercadoLivreHtmlParser.applyImageHits(listOf(candidate), yHits).first().imageUrl
        )
        assertEquals(
            null,
            MercadoLivreHtmlParser.pickFallbackCandidateImageHit(candidate.title, candidate.productUrl, yHits)
        )
    }

    @Test
    fun `components JSONObject pictures and Bing Yahoo tile aria-label fallbacks recover real product photos`() {
        val stateJsonObjComponentsHtml = """
            <script id="__PRELOADED_STATE__">
            {"results":[{"id":"MLB882233","permalink":"https://www.mercadolivre.com.br/kit-x99-xeon-ddr4/p/MLB882233","components":{"title":{"text":"Kit Placa Mãe X99 Xeon E5 2680 V4 DDR4"},"price":{"current_price":{"value":699.90}},"picture":{"pictures":[{"id":"D_NQ_NP_882233-MLB"}]}}}]}
            </script>
        """.trimIndent()
        val parsedItems = MercadoLivreHtmlParser.parseListingHtml(stateJsonObjComponentsHtml, "x99", "")
        assertEquals(1, parsedItems.size)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_882233-MLB-O.jpg", parsedItems.first().imageUrl)
        assertEquals("R$ 699,90", parsedItems.first().priceText)

        val itempropHtml = """
            <html><head>
              <title>Placa Mãe X99 PR9 LGA 2011</title>
              <meta itemprop="image" content="https://http2.mlstatic.com/D_NQ_NP_774411-MLB-O.webp" />
            </head><body></body></html>
        """.trimIndent()
        val (_, itemPropImg, _) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            itempropHtml,
            "https://www.mercadolivre.com.br/placa-mae-x99-pr9/p/MLB774411"
        )
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_774411-MLB-O.webp", itemPropImg)

        val bingTileCaptionHtml = """
            <li class="b_algo">
              <div class="iuscp">
                <a class="inflnk" aria-label="Placa Mãe X99 Machinist PR9 LGA 2011 DDR4" href="/images/search"></a>
                <img src="/th/id/OIP.ValidHardwareTile?w=200" alt="" />
              </div>
            </li>
            <li class="b_algo">
              <div class="iuscp">
                <a class="inflnk" aria-label="Produto aleatorio sem relacao" href="/images/search"></a>
                <img src="/th/id/OIP.UnrelatedTile?w=200" alt="" />
              </div>
            </li>
        """.trimIndent()
        val bingHits = MercadoLivreHtmlParser.parseBingImagesHtml(bingTileCaptionHtml)
        assertEquals(2, bingHits.size)
        val cand = RawStoreCandidate(
            id = "ml_MLB774411",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 Machinist PR9 LGA 2011 DDR4",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-pr9/p/MLB774411",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-pr9/p/MLB774411"
        )
        val bestBing = MercadoLivreHtmlParser.pickFallbackCandidateImageHit(cand.title, cand.productUrl, bingHits)
        assertTrue(bestBing?.preferredImageUrl?.contains("ValidHardwareTile") == true)

        val yahooTileAriaHtml = """
            <li class="ld">
              <a href="/search/images?rurl=https%3A%2F%2Fwww.mercadolivre.com.br%2Fplaca-mae-x99%2Fp%2FMLB774411" aria-label="Placa Mãe X99 Machinist PR9 DDR4">
                <img src="https://sp.yimg.com/ib/th?id=OIP.YahooHardwareTile" alt="" />
              </a>
            </li>
        """.trimIndent()
        val yahooHits = MercadoLivreHtmlParser.parseYahooImagesHtml(yahooTileAriaHtml)
        assertEquals(1, yahooHits.size)
        assertEquals("Placa Mãe X99 Machinist PR9 DDR4", yahooHits.first().title)
        val bestYahoo = MercadoLivreHtmlParser.pickFallbackCandidateImageHit(cand.title, cand.productUrl, yahooHits)
        assertTrue(bestYahoo?.preferredImageUrl?.contains("YahooHardwareTile") == true)
    }

    @Test
    fun `lightweight ML image matching and orchestrator pass live candidates in real time without static blocks`() {
        val mlCand = RawStoreCandidate(
            id = "ml_MLB334455",
            store = StoreType.MERCADO_LIVRE,
            title = "Microfone Condensador USB Cardioide",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/microfone-usb/p/MLB334455",
            affiliateUrl = "https://www.mercadolivre.com.br/microfone-usb/p/MLB334455",
            priceText = "R$ 289,00"
        )
        val nonOipThumbHit = SearchEngineImageHit(
            title = "Microfone Condensador USB Cardioide Preto",
            pageUrl = "https://www.mercadolivre.com.br/microfone-usb/p/MLB334455",
            imageUrl = "",
            thumbUrl = "https://tse2.mm.bing.net/th?id=AB123456&pid=Api"
        )
        val matched = MercadoLivreHtmlParser.applyImageHits(listOf(mlCand), listOf(nonOipThumbHit))
        assertEquals("https://tse2.mm.bing.net/th?id=AB123456&pid=Api", matched.first().imageUrl)

        val orchestrator = RealTimeSearchOrchestrator()
        val profile = QueryIntentProfile(
            rawQuery = "microfone usb",
            itemType = "microfone",
            canonicalTermsPt = listOf("microfone"),
            canonicalTermsEn = listOf("usb microphone"),
            searchQueriesPt = listOf("microfone usb"),
            searchQueriesEn = listOf("usb microphone")
        )
        val initialSession = SearchSessionResult(
            intentProfile = profile,
            products = emptyList(),
            storeReports = listOf(
                com.example.data.model.StoreFetchReport(StoreType.MERCADO_LIVRE, 0, 0)
            ),
            usedAiVerifier = false
        )
        val mlCandFirst = RawStoreCandidate(
            id = "ml_MLB111111",
            store = StoreType.MERCADO_LIVRE,
            title = "Microfone USB Condensador Sem Imagem",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/microfone-usb-sem-imagem/p/MLB111111",
            affiliateUrl = "https://www.mercadolivre.com.br/microfone-usb-sem-imagem/p/MLB111111",
            priceText = ""
        )
        val updated = runBlocking {
            orchestrator.verifySingleStoreCandidates(
                currentSession = initialSession,
                store = StoreType.MERCADO_LIVRE,
                candidates = listOf(
                    mlCandFirst,
                    mlCand.copy(imageUrl = "https://http2.mlstatic.com/D_NQ_NP_334455-O.webp")
                )
            )
        }
        assertEquals(2, updated.products.size)
        // Product with both price & image should rank first
        assertEquals("ml_MLB334455", updated.products[0].id)
        assertEquals("R$ 289,00", updated.products[0].priceText)
        assertEquals("ml_MLB111111", updated.products[1].id)
    }

    @Test
    fun `favoriteRepository stores, toggles, removes, and clears only saved favorite cards`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScanShopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = FavoriteRepository(db.favoriteDao())
            val sample = MatchedProduct(
                id = "ml_MLB777888",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 QD4",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_777888-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB777888",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB777888",
                priceText = "R$ 499,90",
                assertivenessScore = 1f,
                matchVerdict = MatchVerdict.EXACT_MATCH,
                matchReason = "Resultado direto da API Mercado Livre",
                featureBreakdown = NeuralFeatureBreakdown(
                    semanticEmbeddingScore = 1f,
                    alphanumericModelScore = 1f,
                    brandAndLineScore = 1f,
                    itemHeadNounScore = 1f,
                    specQualityScore = 1f,
                    domainAlignmentScore = 1f,
                    compatibilityTrapScore = 0f,
                    sourceRelevanceSignal = 1f,
                    neuralProbability = 1f
                )
            )
            runBlocking {
                assertTrue(repo.favorites.first().isEmpty())
                repo.toggleFavorite(sample)
                val saved = repo.favorites.first()
                assertEquals(1, saved.size)
                assertEquals("ml_MLB777888", saved.first().id)

                repo.toggleFavorite(sample)
                assertTrue(repo.favorites.first().isEmpty())

                repo.toggleFavorite(sample)
                assertEquals(1, repo.favorites.first().size)
                repo.clearAll()
                assertTrue(repo.favorites.first().isEmpty())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `parseGroundedPriceResponse and applyPriceMapToCandidates fill missing prices reliably`() {
        val candidates = listOf(
            RawStoreCandidate(
                id = "bi_1",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 Machinist PR9 LGA 2011-3 DDR4",
                imageUrl = "https://http2.mlstatic.com/D_1-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB1001",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB1001",
                priceText = ""
            ),
            RawStoreCandidate(
                id = "bg_2",
                store = StoreType.MERCADO_LIVRE,
                title = "Kit Xeon X99 E5 2680 V4 16GB Memoria DDR4",
                imageUrl = "https://http2.mlstatic.com/D_2-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2002-kit-xeon-x99-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2002-kit-xeon-x99-_JM",
                priceText = ""
            ),
            RawStoreCandidate(
                id = "yh_3",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Huananzhi X99 QD4 NVMe M.2",
                imageUrl = "https://http2.mlstatic.com/D_3-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-huananzhi-x99-qd4/p/MLB3003",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-huananzhi-x99-qd4/p/MLB3003",
                priceText = "R$ 14,99"
            )
        )

        val groundedJson = """
            [
              {"item": 1, "title": "Placa Mãe X99 Machinist PR9", "price": 429.9},
              {"title": "Kit Xeon E5 2680 V4 16GB", "price": "R$ 689,50"},
              {"i": 3, "title": "Huananzhi X99 QD4", "price": "R$ 599,00"}
            ]
        """.trimIndent()

        val priceMap = MercadoLivreHtmlParser.parseGroundedPriceResponse(groundedJson, candidates)
        val enriched = MercadoLivreClient().applyPriceMapToCandidates(candidates, priceMap)

        assertEquals("R$ 429,90", enriched[0].priceText)
        assertEquals("R$ 689,50", enriched[1].priceText)
        assertEquals("R$ 599,00", enriched[2].priceText)
    }

    @Test
    fun `orchestrator filters out off-topic Mercado Livre junk while approving relevant x99 boards`() {
        val orchestrator = RealTimeSearchOrchestrator()
        val profile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "placa mae x99",
            canonicalTermsPt = listOf("placa mae", "x99", "lga 2011"),
            canonicalTermsEn = listOf("x99 motherboard"),
            alphanumericCodes = listOf("x99"),
            searchQueriesPt = listOf("placa mae x99"),
            searchQueriesEn = listOf("x99 motherboard")
        )
        val session = SearchSessionResult(
            intentProfile = profile,
            products = emptyList(),
            storeReports = listOf(com.example.data.model.StoreFetchReport(StoreType.MERCADO_LIVRE, 0, 0)),
            usedAiVerifier = false
        )
        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_MLB9001",
                store = StoreType.MERCADO_LIVRE,
                title = "10 Grampo Churrasqueira Porsche Panamera 2006 A 2012",
                imageUrl = "https://http2.mlstatic.com/D_9001-O.webp",
                productUrl = "https://www.mercadolivre.com.br/grampo-porsche-panamera/p/MLB9001",
                affiliateUrl = "https://www.mercadolivre.com.br/grampo-porsche-panamera/p/MLB9001",
                priceText = "R$ 49,90"
            ),
            RawStoreCandidate(
                id = "ml_MLB9002",
                store = StoreType.MERCADO_LIVRE,
                title = "Suporte Cooler Para Placa Mãe X99 LGA 2011",
                imageUrl = "https://http2.mlstatic.com/D_9002-O.webp",
                productUrl = "https://www.mercadolivre.com.br/suporte-cooler-x99/p/MLB9002",
                affiliateUrl = "https://www.mercadolivre.com.br/suporte-cooler-x99/p/MLB9002",
                priceText = "R$ 29,90"
            ),
            RawStoreCandidate(
                id = "ml_MLB9003",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 Machinist PR9 LGA 2011-3 DDR4 Xeon",
                imageUrl = "https://http2.mlstatic.com/D_9003-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB9003",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB9003",
                priceText = "R$ 429,90"
            )
        )

        val updated = runBlocking {
            orchestrator.verifySingleStoreCandidates(
                currentSession = session,
                store = StoreType.MERCADO_LIVRE,
                candidates = candidates
            )
        }
        val approvedIds = updated.products.map { it.id }
        assertTrue(approvedIds.contains("ml_MLB9003"))
        assertFalse(approvedIds.contains("ml_MLB9001"))
        assertFalse(approvedIds.contains("ml_MLB9002"))
    }

    @Test
    fun `extractBrlPrice handles spoken reais strings and skips chrome links when card containers absent`() {
        assertEquals(
            "R$ 404,10",
            MercadoLivreHtmlParser.extractBrlPrice("Placa Mãe X99 por 404 reais com 10 centavos no Mercado Livre")
        )
        assertEquals(
            "",
            MercadoLivreHtmlParser.extractBrlPrice("Parcele em 12x de 39 reais com frete de 18 reais")
        )

        val chromeOnlyHtml = """
            <html>
              <header>
                <nav>
                  <a href="https://www.mercadolivre.com.br/oferta-relampago-smartphone-xyz/p/MLB123456">Smartphone XYZ Oferta Header</a>
                </nav>
              </header>
              <body>
                <div class="banner">
                  <a href="https://www.mercadolivre.com.br/produto-sem-card-container/p/MLB654321">Produto Sem Card Container</a>
                </div>
              </body>
            </html>
        """.trimIndent()
        val parsed = MercadoLivreHtmlParser.parseListingHtml(chromeOnlyHtml, "smartphone", "")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `nested b_caption divs, nested Yahoo dd, integer price before installments, and PDP fallbacks extract prices cleanly`() {
        // 1) Bing Web nested <div> inside b_caption before <p> price
        val bingNestedHtml = """
            <ol id="b_results">
              <li class="b_algo">
                <h2><a href="https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB445566">Placa Mãe X99 Machinist PR9 LGA 2011-3</a></h2>
                <div class="b_caption">
                  <div class="b_attribution"><cite>https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB445566</cite></div>
                  <p class="b_lineclamp3">Frete grátis no dia ✓ Compre Placa Mãe X99 Machinist PR9 por R$ 459,90 em 10x sem juros.</p>
                </div>
              </li>
            </ol>
        """.trimIndent()
        val bingItems = MercadoLivreHtmlParser.parseBingWebHtml(bingNestedHtml, "placa mae x99", "")
        assertEquals(1, bingItems.size)
        assertEquals("R$ 459,90", bingItems.first().priceText)

        // 2) Yahoo nested <div class="dd..."> before <div class="compText"> price
        val yahooNestedHtml = """
            <ol class="reg searchCenterMiddle">
              <li>
                <div class="dd algo algo-sr">
                  <div class="compTitle"><a href="https://r.search.yahoo.com/_ylt=x/RU=https%3A%2F%2Fwww.mercadolivre.com.br%2Fkit-xeon-x99-e5-2680%2Fp%2FMLB778899/RK=2/RS=y">Kit Xeon X99 E5 2680 V4 + 16GB DDR4</a></div>
                  <div class="dd fz-ms"><span class="fc-falcon">mercadolivre.com.br</span></div>
                  <div class="compText"><p>Oferta imperdível no Mercado Livre por R$ 689,00 com entrega rápida.</p></div>
                </div>
              </li>
            </ol>
        """.trimIndent()
        val yahooItems = MercadoLivreHtmlParser.parseYahooHtml(yahooNestedHtml, "kit x99", "")
        assertEquals(1, yahooItems.size)
        assertEquals("R$ 689,00", yahooItems.first().priceText)

        // 3) Listing integer price without cents followed immediately by installment cents (must NOT bleed ,58 onto 499!)
        val listingHtml = """
            <div class="poly-card">
              <a class="poly-component__title" href="https://www.mercadolivre.com.br/placa-mae-x99-qd4/p/MLB556677">Placa Mãe X99 QD4 Huananzhi DDR4</a>
              <div class="poly-price__current">
                <span class="andes-money-amount">
                  <span class="andes-money-amount__fraction">499</span>
                </span>
              </div>
              <span class="poly-price__installments">12x <span class="andes-money-amount"><span class="andes-money-amount__fraction">41</span><span class="andes-money-amount__cents">58</span></span></span>
            </div>
        """.trimIndent()
        val listingItems = MercadoLivreHtmlParser.parseListingHtml(listingHtml, "placa mae x99", "")
        assertEquals(1, listingItems.size)
        assertEquals("R$ 499,00", listingItems.first().priceText)

        // 4) Single PDP og:price:amount fallback
        val pdpOgPriceHtml = """
            <html><head>
              <meta property="og:title" content="Placa Mãe X99 Qiyida D4 LGA 2011-3" />
              <meta property="og:image" content="https://http2.mlstatic.com/D_NQ_NP_998877-O.webp" />
              <meta property="og:price:amount" content="379.50" />
            </head><body></body></html>
        """.trimIndent()
        val (_, _, ogPrice) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            pdpOgPriceHtml,
            "https://www.mercadolivre.com.br/placa-mae-x99-qiyida-d4/p/MLB998877"
        )
        assertEquals("R$ 379,50", ogPrice)

        // 5) Grounded price JSON using "preco" / "valor" keys and unindexed fuzzy model match
        val groundedTargets = listOf(
            RawStoreCandidate(
                id = "bg_99",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 Qiyida D4 LGA 2011-3 DDR4",
                imageUrl = "",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-qiyida-d4/p/MLB998877",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-qiyida-d4/p/MLB998877"
            )
        )
        val groundedPriceMap = MercadoLivreHtmlParser.parseGroundedPriceResponse(
            """[{"title":"Qiyida X99 D4","preco":"R$ 389,90"}]""",
            groundedTargets
        )
        val enriched = MercadoLivreClient().applyPriceMapToCandidates(groundedTargets, groundedPriceMap)
        assertEquals("R$ 389,90", enriched.first().priceText)
    }

    @Test
    fun `parseWebViewExtractedJson parses live DOM items cleanly and aborts on account-verification`() {
        val validJson = """
            {
              "blocked": false,
              "href": "https://lista.mercadolivre.com.br/x99",
              "items": [
                {
                  "title": "Placa Mãe X99 Machinist PR9 LGA 2011-3",
                  "url": "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB33445566",
                  "price": "R$ 429,90",
                  "img": "http://http2.mlstatic.com/D_NQ_NP_334455-O.jpg"
                },
                {
                  "title": "Kit Xeon X99 E5 2680 V4 16GB DDR4",
                  "url": "https://produto.mercadolivre.com.br/MLB-77889900-kit-xeon-x99-_JM",
                  "price": "R$ 1299,00",
                  "img": "data:image/gif;base64,R0lGODlhAQABAAAAACw="
                },
                {
                  "title": "Link Inválido Categoria",
                  "url": "https://www.mercadolivre.com.br/c/informatica",
                  "price": "R$ 100,00",
                  "img": "https://http2.mlstatic.com/D_NQ_NP_111111-O.jpg"
                }
              ]
            }
        """.trimIndent()

        val parsed = parseWebViewExtractedJson(validJson, "x99", "")
        assertEquals(2, parsed.size)
        assertEquals("R$ 429,90", parsed[0].priceText)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_334455-O.jpg", parsed[0].imageUrl)
        assertEquals("R$ 1.299,00", parsed[1].priceText)
        assertEquals("", parsed[1].imageUrl)

        val blockedJson = """
            {
              "blocked": true,
              "href": "https://www.mercadolivre.com.br/gz/account-verification?go=x99",
              "items": [
                {
                  "title": "Placa Mãe X99",
                  "url": "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB123",
                  "price": "R$ 429,90",
                  "img": ""
                }
              ]
            }
        """.trimIndent()
        assertTrue(parseWebViewExtractedJson(blockedJson, "x99", "").isEmpty())
    }

    @Test
    fun `parseMultigetItemsJson parses batch MLB item prices and mlstatic images cleanly`() {
        val multigetPayload = """
            [
              {
                "code": 200,
                "body": {
                  "id": "MLB33445566",
                  "title": "Placa Mãe X99 Machinist PR9 DDR4",
                  "price": 459.9,
                  "sale_price": { "amount": 419.5 },
                  "currency_id": "BRL",
                  "secure_thumbnail": "https://http2.mlstatic.com/D_NQ_NP_334455-I.jpg"
                }
              },
              {
                "code": 404,
                "body": { "id": "MLB99999999" }
              },
              {
                "code": 200,
                "body": {
                  "id": "MLB55667788",
                  "title": "Kit Xeon E5 2680 V4",
                  "price": 689.0,
                  "currency_id": "BRL",
                  "pictures": [
                    { "secure_url": "https://http2.mlstatic.com/D_NQ_NP_556677-O.webp" }
                  ]
                }
              }
            ]
        """.trimIndent()

        val map = MercadoLivreClient().parseMultigetItemsJson(multigetPayload)
        assertEquals(2, map.size)
        val first = map["MLB33445566"]
        assertNotNull(first)
        assertEquals("Placa Mãe X99 Machinist PR9 DDR4", first!!.first)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_334455-I.jpg", first.second)
        assertEquals("R$ 419,50", first.third)

        val second = map["MLB55667788"]
        assertNotNull(second)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_556677-O.webp", second!!.second)
        assertEquals("R$ 689,00", second.third)
    }

    @Test
    fun `extractDirectItemIdForMultiget and applyMultigetMapToCandidates patch missing prices and images`() {
        val client = MercadoLivreClient()
        assertEquals(
            "MLB33445566",
            client.extractDirectItemIdForMultiget("https://produto.mercadolivre.com.br/MLB-33445566-placa-mae-x99-_JM")
        )
        assertEquals(
            "MLB99887766",
            client.extractDirectItemIdForMultiget("https://www.mercadolivre.com.br/placa-mae-x99/p/MLB123456?wid=MLB99887766")
        )
        assertEquals(
            null,
            client.extractDirectItemIdForMultiget("https://www.mercadolivre.com.br/placa-mae-x99/p/MLB123456")
        )
        assertEquals(
            null,
            client.extractDirectItemIdForMultiget("https://www.mercadolivre.com.br/up/MLBU778899")
        )

        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_MLB33445566",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe X99 PR9",
                imageUrl = "https://th.bing.com/th?id=placeholder",
                productUrl = "https://produto.mercadolivre.com.br/MLB-33445566-placa-mae-x99-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-33445566-placa-mae-x99-_JM",
                priceText = ""
            ),
            RawStoreCandidate(
                id = "ml_MLB55667788",
                store = StoreType.MERCADO_LIVRE,
                title = "Kit Xeon X99 E5 2680",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_556677-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-55667788-kit-xeon-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-55667788-kit-xeon-_JM",
                priceText = "R$ 12,00"
            )
        )
        val patched = client.applyMultigetMapToCandidates(
            candidates = candidates,
            directIdByCandidateIdx = mapOf(0 to "MLB33445566", 1 to "MLB55667788"),
            resolvedByItemId = mapOf(
                "MLB33445566" to Triple("Placa Mãe X99 Machinist PR9 DDR4", "https://http2.mlstatic.com/D_NQ_NP_334455-O.jpg", "R$ 429,90"),
                "MLB55667788" to Triple("Kit", "", "R$ 689,00")
            )
        )
        assertEquals("Placa Mãe X99 Machinist PR9 DDR4", patched[0].title)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_334455-O.jpg", patched[0].imageUrl)
        assertEquals("R$ 429,90", patched[0].priceText)
        assertEquals("R$ 689,00", patched[1].priceText)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_556677-O.webp", patched[1].imageUrl)
    }

    @Test
    fun `webViewExtractor degrades safely without context and gemini classifies high demand 503 as transient`() {
        val noContextResult = runBlocking {
            MercadoLivreWebViewExtractor(null).fetchListingCandidates(
                url = "https://lista.mercadolivre.com.br/x99",
                query = "x99",
                domainHint = "",
                timeoutMs = 300L
            )
        }
        assertTrue(noContextResult.isEmpty())

        val engine = GeminiQueryAndVerifierEngine()
        assertTrue(engine.isTransientNetworkError(RuntimeException("This model is currently experiencing high demand. Spikes in demand are usually temporarily mitigated.")))
        assertTrue(engine.isTransientNetworkError(RuntimeException("HTTP 503 Server overloaded")))
        assertTrue(engine.isTransientNetworkError(java.net.UnknownHostException("offline")))
        assertFalse(engine.isTransientNetworkError(IllegalArgumentException("Schema validation syntax failure")))
    }

    @Test
    fun `applyImageHits and pickFallbackCandidateImageHit refuse wrong models, capacities, accessories, and ML UI badges`() {
        assertEquals("", HtmlTextDecoder.normalizeImageUrl("https://http2.mlstatic.com/frontend-assets/ml-web-navigation/ui-navigation/6.6.0/mercadolibre/logo_large.png"))
        assertEquals("", HtmlTextDecoder.normalizeImageUrl("https://http2.mlstatic.com/resources/badges/full_icon.png"))
        assertEquals(
            "https://http2.mlstatic.com/D_NQ_NP_123456-MLA-O.webp",
            HtmlTextDecoder.normalizeImageUrl("https://http2.mlstatic.com/D_NQ_NP_123456-MLA-O.webp")
        )

        val s24 = RawStoreCandidate(
            id = "ml_MLB555001",
            store = StoreType.MERCADO_LIVRE,
            title = "Smartphone Samsung Galaxy S24 Ultra 256GB",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/samsung-galaxy-s24-ultra-256gb/p/MLB555001",
            affiliateUrl = "https://www.mercadolivre.com.br/samsung-galaxy-s24-ultra-256gb/p/MLB555001"
        )
        val wrongHits = listOf(
            SearchEngineImageHit(
                title = "Smartphone Samsung Galaxy A15 128GB",
                pageUrl = "https://www.mercadolivre.com.br/samsung-galaxy-a15/p/MLB999001",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_111111-O.webp",
                thumbUrl = ""
            ),
            SearchEngineImageHit(
                title = "Capinha Silicone Samsung Galaxy S24 Ultra",
                pageUrl = "https://www.mercadolivre.com.br/capinha-s24-ultra/p/MLB999002",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_222222-O.webp",
                thumbUrl = ""
            ),
            SearchEngineImageHit(
                title = "Smartphone Samsung Galaxy S24 Ultra 512GB",
                pageUrl = "https://www.mercadolivre.com.br/samsung-galaxy-s24-512gb/p/MLB999003",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_333333-O.webp",
                thumbUrl = ""
            )
        )
        assertEquals("", MercadoLivreHtmlParser.applyImageHits(listOf(s24), wrongHits).first().imageUrl)
        assertEquals(
            null,
            MercadoLivreHtmlParser.pickFallbackCandidateImageHit(s24.title, s24.productUrl, wrongHits)
        )

        val exactHit = SearchEngineImageHit(
            title = "Samsung Galaxy S24 Ultra 256GB Titânio",
            pageUrl = "https://www.mercadolivre.com.br/galaxy-s24-ultra-256gb/p/MLB888777",
            imageUrl = "https://http2.mlstatic.com/D_NQ_NP_444444-O.webp",
            thumbUrl = ""
        )
        val withExact = MercadoLivreHtmlParser.applyImageHits(listOf(s24), wrongHits + exactHit)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_444444-O.webp", withExact.first().imageUrl)
    }

    @Test
    fun `PDP and search-results parsers handle nested gallery CBT IDs, data-zoom after blank src, previous-class prices, and recommendations cutoff`() {
        val pdpHtml = """
            <html><body>
              <h1 class="ui-pdp-title">SSD NVMe Kingston 1TB PCIe 4.0</h1>
              <img class="ui-pdp-image ui-pdp-gallery__figure__image" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-zoom="https://http2.mlstatic.com/D_NQ_NP_778899-CBT74829102938_032024-O.webp" />
              <div class="ui-pdp-price">
                <s class="andes-money-amount ui-pdp-price__part andes-money-amount--previous">
                  <span class="andes-money-amount__fraction">699</span>
                </s>
                <div class="ui-pdp-price__subtitles">em 12x <span class="andes-money-amount"><span class="andes-money-amount__fraction">49</span><span class="andes-money-amount__cents">90</span></span></div>
              </div>
              <script>
                window.__PRELOADED_STATE__ = {"initialState":{"components":{"price":{"type":"price","value":{"currency_id":"BRL","value":479.90}}}}};
              </script>
              <section id="recommendations">
                <h2>Quem viu também comprou</h2>
                <img class="ui-pdp-image" src="https://http2.mlstatic.com/D_NQ_NP_999000-O.webp" />
                <div class="ui-pdp-price__second-line"><span class="andes-money-amount__fraction">199</span></div>
              </section>
            </body></html>
        """.trimIndent()
        val (pdpTitle, pdpImg, pdpPrice) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            pdpHtml,
            "https://www.mercadolivre.com.br/ssd-kingston-1tb/p/MLB665544"
        )
        assertEquals("SSD NVMe Kingston 1TB PCIe 4.0", pdpTitle)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_778899-CBT74829102938_032025-O.webp".replace("032025", "032024"), pdpImg)
        assertEquals("R$ 479,90", pdpPrice)

        val pdpNestedPicturesHtml = """
            <html><body>
              <h1 class="ui-pdp-title">Headphone Bluetooth Importado</h1>
              <script>
                window.__PRELOADED_STATE__ = {"gallery":{"pictures":[{"size":{"width":500,"height":500},"id":"839201-CBT74829102938_032024"}]}};
              </script>
            </body></html>
        """.trimIndent()
        val (_, cbtImg, _) = MercadoLivreHtmlParser.parseSingleProductPageHtml(
            pdpNestedPicturesHtml,
            "https://www.mercadolivre.com.br/headphone-bt/p/MLB112200"
        )
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_839201-CBT74829102938_032024-O.jpg", cbtImg)

        val searchStateHtml = """
            <script id="__PRELOADED_STATE__">
            {"pageStoreState":{"initialState":{"search":{"results":[{"id":"MLB770011","permalink":"https://www.mercadolivre.com.br/monitor-lg-27/p/MLB770011","components":[{"type":"title","title":{"text":"Monitor Gamer LG 27 Full HD 144Hz"}},{"type":"picture","picture":{"pictures":[{"id":"554433-CBT8899"}]}},{"type":"price","price":{"current_price":{"value":899.0}}}]}]}}}}
            </script>
        """.trimIndent()
        val stateItems = MercadoLivreHtmlParser.parseListingHtml(searchStateHtml, "monitor lg 27", "")
        assertEquals(1, stateItems.size)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_554433-CBT8899-O.jpg", stateItems.first().imageUrl)
        assertEquals("R$ 899,00", stateItems.first().priceText)
    }

    @Test
    fun `placa mae Mercado Livre search never approves car plates and never pairs car plate photos`() {
        val orchestrator = RealTimeSearchOrchestrator()
        val profile = GeminiQueryAndVerifierEngine().buildHeuristicProfile("placa mae", emptyList())
        val session = SearchSessionResult(
            intentProfile = profile,
            products = emptyList(),
            storeReports = listOf(com.example.data.model.StoreFetchReport(StoreType.MERCADO_LIVRE, 0, 0)),
            usedAiVerifier = false
        )

        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_MLB7001",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Gigabyte B550M Aorus Elite AM4 DDR4",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_7001-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-gigabyte-b550m/p/MLB7001",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-gigabyte-b550m/p/MLB7001",
                priceText = "R$ 749,90"
            ),
            RawStoreCandidate(
                id = "ml_MLB7002",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa De Carro Mercosul Original Alumínio",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_7002-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-7002-placa-de-carro-mercosul-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-7002-placa-de-carro-mercosul-_JM",
                priceText = "R$ 79,90"
            ),
            RawStoreCandidate(
                id = "ml_MLB7003",
                store = StoreType.MERCADO_LIVRE,
                title = "Suporte Porta Placa Veicular Carro Moto Mercosul",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_7003-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-7003-porta-placa-carro-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-7003-porta-placa-carro-_JM",
                priceText = "R$ 29,90"
            )
        )

        val updated = runBlocking {
            orchestrator.verifySingleStoreCandidates(
                currentSession = session,
                store = StoreType.MERCADO_LIVRE,
                candidates = candidates
            )
        }
        assertEquals(listOf("ml_MLB7001"), updated.products.map { it.id })

        val moboNoImg = RawStoreCandidate(
            id = "ml_MLB7010",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe Original Nova Preta",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-original/p/MLB7010",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-original/p/MLB7010",
            priceText = "R$ 349,90"
        )
        val carPlateImageHits = listOf(
            SearchEngineImageHit(
                title = "Placa Original Nova Preta Carro Mercosul",
                pageUrl = "https://produto.mercadolivre.com.br/MLB-8888-placa-carro-mercosul-_JM",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_8888-O.webp",
                thumbUrl = ""
            ),
            SearchEngineImageHit(
                title = "Placa Original Nova Preta Alumínio",
                pageUrl = "https://produto.mercadolivre.com.br/MLB-8889-placa-veicular-_JM",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_8889-O.webp",
                thumbUrl = ""
            )
        )
        val afterImg = MercadoLivreHtmlParser.applyImageHits(listOf(moboNoImg), carPlateImageHits)
        assertEquals("", afterImg.first().imageUrl)
    }

    @Test
    fun `byId image matching rejects car plate and unlabeled photos on motherboard listings`() {
        val mobo = RawStoreCandidate(
            id = "ml_MLB555001",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe Asus Prime B550M-A AM4 DDR4",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-b550m/p/MLB555001",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-b550m/p/MLB555001",
            priceText = "R$ 699,90"
        )
        // Even if a noisy image parser accidentally pairs a car plate image or blank-title image with MLB555001,
        // byId must NEVER attach it to a motherboard candidate.
        val poisonedByIdHits = listOf(
            SearchEngineImageHit(
                title = "Placa Mercosul Carro Alumínio Detran",
                pageUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-b550m/p/MLB555001",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_CARPLATE1-O.webp",
                thumbUrl = ""
            ),
            SearchEngineImageHit(
                title = "",
                pageUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-b550m/p/MLB555001",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_UNLABELED1-O.webp",
                thumbUrl = ""
            ),
            SearchEngineImageHit(
                title = "Placa Alumínio Estampada Preta",
                pageUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-b550m/p/MLB555001",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_GENERICPLATE-O.webp",
                thumbUrl = ""
            )
        )

        val afterApply = MercadoLivreHtmlParser.applyImageHits(listOf(mobo), poisonedByIdHits)
        assertEquals("", afterApply.first().imageUrl)
        assertEquals(
            null,
            MercadoLivreHtmlParser.pickBestCompatibleImageHit(mobo.title, mobo.productUrl, poisonedByIdHits)
        )

        // A genuine motherboard hit with matching ID and motherboard title MUST still succeed.
        val validMoboHit = SearchEngineImageHit(
            title = "Placa Mãe Asus Prime B550M-A AM4 DDR4",
            pageUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-b550m/p/MLB555001",
            imageUrl = "https://http2.mlstatic.com/D_NQ_NP_REALMOBO-O.webp",
            thumbUrl = ""
        )
        val afterValid = MercadoLivreHtmlParser.applyImageHits(listOf(mobo), poisonedByIdHits + validMoboHit)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_REALMOBO-O.webp", afterValid.first().imageUrl)
    }

    @Test
    fun `cross-tile image HTML does not leak motherboard URL onto neighboring car plate thumbnail`() {
        val googleAdjacentTilesHtml = """
            <div>
              <a href="https://www.mercadolivre.com.br/placa-mae-b550m-aorus/p/MLB901122">Placa Mãe B550M Aorus</a>
              <img src="https://http2.mlstatic.com/D_NQ_NP_REALMOBO901-O.jpg" alt="Placa Mãe Gigabyte B550M Aorus Elite AM4" />
            </div>
            <div>
              <a href="https://produto.mercadolivre.com.br/MLB-901199-placa-carro-mercosul-_JM">Placa Carro Mercosul</a>
              <img src="https://http2.mlstatic.com/D_NQ_NP_CARPLATE902-O.jpg" alt="Placa Mercosul Carro Alumínio" />
            </div>
        """.trimIndent()
        val gHits = MercadoLivreHtmlParser.parseGoogleImagesHtml(googleAdjacentTilesHtml)
        val carHit = gHits.firstOrNull { it.preferredImageUrl.contains("CARPLATE902") }
        assertTrue(carHit == null || !carHit.pageUrl.contains("MLB901122"))
        assertTrue(carHit == null || !MercadoLivreHtmlParser.isSafeMotherboardImageHit(carHit))

        val bingAdjacentTilesHtml = """
            <ul>
              <li class="iuscp"><a href="https://www.mercadolivre.com.br/placa-mae-x99-machinist/p/MLB777888">link</a>
                <img class="mimg" src="/th/id/OIP.realMoboThumb" alt="Placa Mãe X99 Machinist LGA 2011" /></li>
              <li class="iuscp"><a href="https://produto.mercadolivre.com.br/MLB-777999-suporte-placa-carro-_JM">link</a>
                <img class="mimg" src="/th/id/OIP.carPlateThumb" alt="Suporte Placa Carro Mercosul" /></li>
            </ul>
        """.trimIndent()
        val bHits = MercadoLivreHtmlParser.parseBingImagesHtml(bingAdjacentTilesHtml)
        val carBingHit = bHits.firstOrNull { it.preferredImageUrl.contains("carPlateThumb") }
        assertTrue(carBingHit == null || !carBingHit.pageUrl.contains("MLB777888"))
        assertTrue(carBingHit == null || !MercadoLivreHtmlParser.isSafeMotherboardImageHit(carBingHit))

        val moboCand = RawStoreCandidate(
            id = "bg_MLB777888",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 Machinist LGA 2011",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist/p/MLB777888",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-machinist/p/MLB777888"
        )
        val applied = MercadoLivreHtmlParser.applyImageHits(listOf(moboCand), gHits + bHits)
        assertTrue(applied.first().imageUrl.contains("realMoboThumb"))
        assertFalse(applied.first().imageUrl.contains("CARPLATE"))
        assertFalse(applied.first().imageUrl.contains("carPlateThumb"))
    }

    @Test
    fun `x99 motherboard with m2 spec matches compatible x99 photo and cards with photos sort first`() {
        val x99M2 = RawStoreCandidate(
            id = "bg_MLB801001",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 LGA 2011-3 DDR4 NVMe M.2",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-ddr4-m2/p/MLB801001",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-ddr4-m2/p/MLB801001",
            priceText = "R$ 399,90"
        )
        val hits = listOf(
            SearchEngineImageHit(
                title = "Placa Carro Mercosul X99 Alumínio",
                pageUrl = "https://produto.mercadolivre.com.br/MLB-801999-placa-carro-_JM",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_CARPLATE_X99-O.jpg",
                thumbUrl = ""
            ),
            SearchEngineImageHit(
                title = "Placa Mãe X99 LGA 2011-3 DDR4 Xeon E5 NVMe",
                pageUrl = "https://www.mercadolivre.com.br/placa-mae-x99-xeon-e5/p/MLB801555",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_X99_MOBO_OK-O.webp",
                thumbUrl = ""
            )
        )
        val matched = MercadoLivreHtmlParser.applyImageHits(listOf(x99M2), hits)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_X99_MOBO_OK-O.webp", matched.first().imageUrl)

        val orchestrator = RealTimeSearchOrchestrator()
        val blankExact = MatchedProduct(
            id = "ml_MLB9001",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 Machinist DDR4 LGA 2011-3",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-sem-foto/p/MLB9001",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-sem-foto/p/MLB9001",
            deepLinkUrl = "",
            priceText = "R$ 420,00",
            categoryLabel = "",
            matchedSpecs = emptyList(),
            assertivenessScore = 0.92f,
            matchVerdict = MatchVerdict.EXACT_MATCH,
            matchReason = "Exato",
            featureBreakdown = NeuralFeatureBreakdown(
                semanticEmbeddingScore = 1f,
                alphanumericModelScore = 1f,
                brandAndLineScore = 1f,
                itemHeadNounScore = 1f,
                specQualityScore = 1f,
                domainAlignmentScore = 1f,
                compatibilityTrapScore = 0f,
                sourceRelevanceSignal = 1f,
                neuralProbability = 0.92f
            )
        )
        val photoVariant = blankExact.copy(
            id = "ml_MLB9002",
            title = "Kit X99 Huananzhi DDR4 Xeon",
            imageUrl = "https://http2.mlstatic.com/D_NQ_NP_X99_WITH_PHOTO-O.webp",
            priceText = "R$ 549,90",
            assertivenessScore = 0.58f,
            matchVerdict = MatchVerdict.VARIANT_MATCH
        )
        val sorted = orchestrator.sortMatchedMlProducts(listOf(blankExact, photoVariant))
        assertEquals(listOf("ml_MLB9002", "ml_MLB9001"), sorted.map { it.id })
    }

    @Test
    fun `offline heuristic expands bare x99 query to placa mae with automotive anti-terms without misclassifying cpu queries`() {
        val engine = com.example.ai.GeminiQueryAndVerifierEngine()
        val x99Profile = engine.buildHeuristicProfile("X99", emptyList())
        assertEquals("Placa-mãe", x99Profile.itemType)
        assertTrue(
            "Expected expanded 'placa mae' query first in offline heuristic: ${x99Profile.searchQueriesPt}",
            x99Profile.searchQueriesPt.first().lowercase().contains("placa mae") &&
                x99Profile.searchQueriesPt.first().lowercase().contains("x99")
        )
        assertTrue(
            "Expected automotive anti-terms on bare x99 fallback",
            x99Profile.antiTargetTerms.any { it.contains("mercosul", ignoreCase = true) }
        )

        // Ensure explicit CPU / cooler queries on a socket are NOT misclassified as motherboards
        assertFalse(NeuralProductMatcher.isMotherboardQueryText("processador ryzen am4"))
        assertFalse(NeuralProductMatcher.isMotherboardQueryText("cooler lga2011"))
        assertTrue(NeuralProductMatcher.isMotherboardQueryText("x99"))
        assertTrue(NeuralProductMatcher.isMotherboardQueryText("b550m"))
    }

    @Test
    fun `buildImageFallbackCandidates generates webp, non-2x, and -F retries for mlstatic URLs`() {
        val variants = HtmlTextDecoder.buildImageFallbackCandidates(
            "https://http2.mlstatic.com/D_NQ_NP_2X_998877-MLA123456-O.jpg"
        )
        assertEquals(
            "https://http2.mlstatic.com/D_NQ_NP_2X_998877-MLA123456-O.jpg",
            variants.first()
        )
        assertTrue(variants.any { it.endsWith("-O.webp") })
        assertTrue(variants.any { it.contains("D_NQ_NP_998877") && !it.contains("2X_") })
        assertTrue(variants.size in 2..4)

        val nonMl = HtmlTextDecoder.buildImageFallbackCandidates("https://example.com/photo.jpg")
        assertEquals(listOf("https://example.com/photo.jpg"), nonMl)
    }

    @Test
    fun `parseWebViewExtractedJson preserves state-backfilled mlstatic images and ignores data-image placeholders`() {
        val payload = """
            {
              "blocked": false,
              "href": "https://lista.mercadolivre.com.br/placa-mae-x99",
              "items": [
                {
                  "title": "Placa Mãe X99 Machinist PR9 DDR4 LGA 2011-3",
                  "url": "https://www.mercadolivre.com.br/placa-mae-x99-machinist-pr9/p/MLB3210001",
                  "price": "R$ 449,90",
                  "img": "https://http2.mlstatic.com/D_NQ_NP_900111-MLB3210001-O.jpg"
                },
                {
                  "title": "Placa Mãe X99 Huananzhi QD4 Xeon DDR4",
                  "url": "https://www.mercadolivre.com.br/placa-mae-x99-huananzhi-qd4/p/MLB3210002",
                  "price": "R$ 589,00",
                  "img": "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                }
              ],
              "stateHtml": "",
              "html": ""
            }
        """.trimIndent()

        val parsed = com.example.data.network.parseWebViewExtractedJson(payload, "placa mae x99", "")
        assertEquals(2, parsed.size)
        assertEquals("https://http2.mlstatic.com/D_NQ_NP_900111-MLB3210001-O.jpg", parsed[0].imageUrl)
        assertEquals("R$ 449,90", parsed[0].priceText)
        assertEquals("", parsed[1].imageUrl)
        assertEquals("R$ 589,00", parsed[1].priceText)
    }

    @Test
    fun `parseNextDataMediaAndPriceCatalog extracts verified photos alongside prices and peer backfill rescues blank ML cards safely`() {
        val zoomHtml = """
            <html><head><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"products":[
              {"name":"Placa Mãe X99 Machinist PR9 LGA 2011-3 DDR4","price":449.90,"thumbnail":"https://i.zst.com.br/thumbs/12/x99-pr9.jpg"}
            ]}}}
            </script></head></html>
        """.trimIndent()
        val (prices, imgHits) = MercadoLivreHtmlParser.parseNextDataMediaAndPriceCatalog(zoomHtml)
        assertEquals(1, prices.size)
        assertEquals(1, imgHits.size)
        assertEquals("https://i.zst.com.br/thumbs/12/x99-pr9.jpg", imgHits.first().imageUrl)

        val orchestrator = RealTimeSearchOrchestrator()
        val blankMl = MatchedProduct(
            id = "bg_MLB9911",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 Xeon E5 LGA 2011-3 DDR4 NVMe",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99-e5/p/MLB9911",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99-e5/p/MLB9911",
            deepLinkUrl = "",
            priceText = "R$ 419,90",
            categoryLabel = "",
            matchedSpecs = emptyList(),
            assertivenessScore = 0.85f,
            matchVerdict = MatchVerdict.EXACT_MATCH,
            matchReason = "Exato",
            featureBreakdown = NeuralFeatureBreakdown(
                semanticEmbeddingScore = 1f,
                alphanumericModelScore = 1f,
                brandAndLineScore = 1f,
                itemHeadNounScore = 1f,
                specQualityScore = 1f,
                domainAlignmentScore = 1f,
                compatibilityTrapScore = 0f,
                sourceRelevanceSignal = 1f,
                neuralProbability = 0.85f
            )
        )
        val peers = listOf(
            RawStoreCandidate(
                id = "sp_car",
                store = StoreType.SHOPEE,
                title = "Placa Carro Mercosul X99 Alumínio",
                imageUrl = "https://cf.shopee.com.br/file/carplate.jpg",
                productUrl = "https://shopee.com.br/car",
                affiliateUrl = "https://shopee.com.br/car"
            ),
            RawStoreCandidate(
                id = "sp_x99",
                store = StoreType.SHOPEE,
                title = "Placa Mãe X99 Machinist PR9 LGA 2011-3 DDR4 NVMe",
                imageUrl = "https://cf.shopee.com.br/file/mobo_x99_real.jpg",
                productUrl = "https://shopee.com.br/x99",
                affiliateUrl = "https://shopee.com.br/x99"
            )
        )
        val rescued = orchestrator.backfillStillMissingMlPhotosFromVerifiedPeers(listOf(blankMl), peers)
        assertEquals("https://cf.shopee.com.br/file/mobo_x99_real.jpg", rescued.first().imageUrl)
    }
}




