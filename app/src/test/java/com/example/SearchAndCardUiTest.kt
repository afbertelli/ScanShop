package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.example.data.model.MatchVerdict
import com.example.data.model.MatchedProduct
import com.example.data.model.NeuralFeatureBreakdown
import com.example.data.model.QueryIntentProfile
import com.example.data.model.SearchPipelineStage
import com.example.data.model.SearchSessionResult
import com.example.data.model.StoreFetchReport
import com.example.data.model.StoreType
import com.example.ui.components.CarouselProductMatchCard
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.ScanShopTheme
import com.example.ui.viewmodel.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchAndCardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleBreakdown = NeuralFeatureBreakdown(
        semanticEmbeddingScore = 0.9f,
        alphanumericModelScore = 0.95f,
        brandAndLineScore = 0.85f,
        itemHeadNounScore = 0.92f,
        specQualityScore = 0.88f,
        domainAlignmentScore = 0.9f,
        compatibilityTrapScore = 0f,
        sourceRelevanceSignal = 0.9f,
        neuralProbability = 0.92f
    )

    @Test
    fun `searchScreen never renders ai_notice_banner even when aiNotice contains high demand text`() {
        val profile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "placa mae x99",
            canonicalTermsPt = listOf("placa mae x99"),
            canonicalTermsEn = listOf("x99 motherboard"),
            searchQueriesPt = listOf("x99")
        )
        val mlProduct = MatchedProduct(
            id = "ml_test_1",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 Machinist PR9 LGA 2011-3",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB123456",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB123456",
            priceText = "R$ 429,90",
            categoryLabel = "Informática",
            matchedSpecs = listOf("X99"),
            assertivenessScore = 0.92f,
            matchVerdict = MatchVerdict.EXACT_MATCH,
            matchReason = "Correspondência exata.",
            featureBreakdown = sampleBreakdown
        )
        val state = SearchUiState(
            query = "x99",
            lastSubmittedQuery = "x99",
            stage = SearchPipelineStage.Completed,
            sessionResult = SearchSessionResult(
                intentProfile = profile,
                products = listOf(mlProduct),
                storeReports = listOf(
                    StoreFetchReport(StoreType.MERCADO_LIVRE, rawCandidatesCount = 1, approvedCount = 1),
                    StoreFetchReport(StoreType.SHOPEE, rawCandidatesCount = 0, approvedCount = 0),
                    StoreFetchReport(StoreType.ALIEXPRESS, rawCandidatesCount = 0, approvedCount = 0)
                ),
                usedAiVerifier = false,
                aiNotice = "This model is currently experiencing high demand. Spikes in demand are usually temporary."
            )
        )

        composeRule.setContent {
            ScanShopTheme {
                SearchScreen(
                    state = state,
                    onQueryChanged = {},
                    onSearch = {}
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            0,
            composeRule.onAllNodesWithTag("ai_notice_banner", useUnmergedTree = true).fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("high demand", substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
        composeRule.onNodeWithTag("search_results_list").assertIsDisplayed()
        composeRule.onNodeWithTag("product_price_ml_test_1", useUnmergedTree = true)
            .assertTextEquals("R$ 429,90")
    }

    @Test
    fun `carouselProductMatchCard renders Ver preco no ML or Ver preco instead of R$ -- when price is blank`() {
        val mlBlankPrice = MatchedProduct(
            id = "ml_no_price",
            store = StoreType.MERCADO_LIVRE,
            title = "Kit Xeon X99 E5 2680 V4",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/kit-xeon-x99/p/MLB777888",
            affiliateUrl = "https://www.mercadolivre.com.br/kit-xeon-x99/p/MLB777888",
            priceText = "",
            categoryLabel = "",
            matchedSpecs = emptyList(),
            assertivenessScore = 0.85f,
            matchVerdict = MatchVerdict.VARIANT_MATCH,
            matchReason = "Correspondência validada.",
            featureBreakdown = sampleBreakdown
        )
        val shopeeBlankPrice = mlBlankPrice.copy(
            id = "sh_no_price",
            store = StoreType.SHOPEE,
            productUrl = "https://shopee.com.br/kit-xeon-x99",
            affiliateUrl = "https://shopee.com.br/kit-xeon-x99"
        )
        val mlWithPrice = mlBlankPrice.copy(
            id = "ml_with_price",
            priceText = "R$ 549,90"
        )

        composeRule.setContent {
            ScanShopTheme {
                Column {
                    CarouselProductMatchCard(
                        product = mlBlankPrice,
                        onOpenStore = {},
                        onShare = {}
                    )
                    CarouselProductMatchCard(
                        product = shopeeBlankPrice,
                        onOpenStore = {},
                        onShare = {}
                    )
                    CarouselProductMatchCard(
                        product = mlWithPrice,
                        onOpenStore = {},
                        onShare = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("product_price_ml_no_price", useUnmergedTree = true)
            .assertTextEquals("Ver preço no ML")
        composeRule.onNodeWithTag("product_price_sh_no_price", useUnmergedTree = true)
            .assertTextEquals("Ver preço")
        composeRule.onNodeWithTag("product_price_ml_with_price", useUnmergedTree = true)
            .assertTextEquals("R$ 549,90")
        assertEquals(
            0,
            composeRule.onAllNodesWithText("R$ --", useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `productMatchCard renders Ver preco no ML or Ver preco when price is blank`() {
        val mlBlankPrice = MatchedProduct(
            id = "fav_ml_no_price",
            store = StoreType.MERCADO_LIVRE,
            title = "SSD NVMe 1TB Kingston",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/ssd-nvme/p/MLB999111",
            affiliateUrl = "https://www.mercadolivre.com.br/ssd-nvme/p/MLB999111",
            priceText = "",
            categoryLabel = "",
            matchedSpecs = emptyList(),
            assertivenessScore = 0.88f,
            matchVerdict = MatchVerdict.EXACT_MATCH,
            matchReason = "Correspondência exata.",
            featureBreakdown = sampleBreakdown
        )
        val aliBlankPrice = mlBlankPrice.copy(
            id = "fav_ali_no_price",
            store = StoreType.ALIEXPRESS,
            productUrl = "https://pt.aliexpress.com/item/123.html",
            affiliateUrl = "https://pt.aliexpress.com/item/123.html"
        )

        composeRule.setContent {
            ScanShopTheme {
                Column {
                    com.example.ui.components.ProductMatchCard(
                        product = mlBlankPrice,
                        onOpenStore = {},
                        onShare = {}
                    )
                    com.example.ui.components.ProductMatchCard(
                        product = aliBlankPrice,
                        onOpenStore = {},
                        onShare = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("product_match_price_fav_ml_no_price", useUnmergedTree = true)
            .assertTextEquals("Ver preço no ML")
        composeRule.onNodeWithTag("product_match_price_fav_ali_no_price", useUnmergedTree = true)
            .assertTextEquals("Ver preço")
    }
}
