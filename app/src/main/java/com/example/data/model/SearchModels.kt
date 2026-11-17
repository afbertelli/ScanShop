package com.example.data.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object PriceFormatter {
    private val brSymbols = DecimalFormatSymbols(
        Locale.Builder().setLanguage("pt").setRegion("BR").build()
    ).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }
    private val formatter = DecimalFormat("#,##0.00", brSymbols)
    private val nonNumericPriceCharsRegex = Regex("[^0-9.,]")
    private val thousandsGroupedIntRegex = Regex("""^\d{1,3}(\.\d{3})+$""")

    fun formatBrl(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return ""
        return "R$ ${formatter.format(value)}"
    }

    fun formatBrl(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return ""
        val cleaned = text
            .replace("R$", "", ignoreCase = true)
            .replace(nonNumericPriceCharsRegex, "")
            .trim()
        if (cleaned.isBlank()) return ""
        val normalized = when {
            cleaned.contains(',') && cleaned.contains('.') -> {
                if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                    cleaned.replace(".", "").replace(',', '.')
                } else {
                    cleaned.replace(",", "")
                }
            }
            cleaned.contains(',') -> cleaned.replace(',', '.')
            thousandsGroupedIntRegex.matches(cleaned) -> cleaned.replace(".", "")
            else -> cleaned
        }
        val numeric = normalized.toDoubleOrNull() ?: return ""
        return formatBrl(numeric)
    }
}

data class ProductAttribute(
    val name: String,
    val value: String
)

data class DomainDiscoveryHint(
    val domainId: String,
    val domainName: String,
    val categoryId: String,
    val categoryName: String,
    val attributes: List<ProductAttribute>
)

data class RawStoreCandidate(
    val id: String,
    val store: StoreType,
    val title: String,
    val imageUrl: String,
    val productUrl: String,
    val affiliateUrl: String,
    val priceText: String = "",
    val categoryPath: String = "",
    val domainId: String = "",
    val attributes: List<ProductAttribute> = emptyList(),
    val sourceQueryVariant: String = "",
    val sourceRank: Int = 0
)

data class QueryIntentProfile(
    val rawQuery: String,
    val itemType: String,
    val canonicalTermsPt: List<String>,
    val canonicalTermsEn: List<String>,
    val brand: String? = null,
    val line: String? = null,
    val model: String? = null,
    val alphanumericCodes: List<String> = emptyList(),
    val requiredQualities: List<String> = emptyList(),
    val optionalQualities: List<String> = emptyList(),
    val isAccessoryIntent: Boolean = false,
    val expectedDomains: List<DomainDiscoveryHint> = emptyList(),
    val antiTargetTerms: List<String> = emptyList(),
    val searchQueriesPt: List<String> = emptyList(),
    val searchQueriesEn: List<String> = emptyList()
)

enum class MatchVerdict(val label: String) {
    EXACT_MATCH("Correspondência Exata"),
    VARIANT_MATCH("Variante Compatível"),
    REJECTED("Descartado")
}

data class NeuralFeatureBreakdown(
    val semanticEmbeddingScore: Float,
    val alphanumericModelScore: Float,
    val brandAndLineScore: Float,
    val itemHeadNounScore: Float,
    val specQualityScore: Float,
    val domainAlignmentScore: Float,
    val compatibilityTrapScore: Float,
    val sourceRelevanceSignal: Float,
    val neuralProbability: Float
)

data class MatchedProduct(
    val id: String,
    val store: StoreType,
    val title: String,
    val imageUrl: String,
    val productUrl: String,
    val affiliateUrl: String,
    val deepLinkUrl: String = "",
    val priceText: String = "",
    val categoryLabel: String = "",
    val matchedSpecs: List<String> = emptyList(),
    val assertivenessScore: Float,
    val matchVerdict: MatchVerdict,
    val matchReason: String,
    val featureBreakdown: NeuralFeatureBreakdown
)

data class StoreFetchReport(
    val store: StoreType,
    val rawCandidatesCount: Int,
    val approvedCount: Int,
    val statusMessage: String? = null,
    val diagnosticDetail: String? = null,
    val hasMore: Boolean? = null
)

sealed interface SearchPipelineStage {
    data object Idle : SearchPipelineStage
    data object AnalyzingIntent : SearchPipelineStage
    data object FetchingStores : SearchPipelineStage
    data object NeuralAndAiScoring : SearchPipelineStage
    data object Completed : SearchPipelineStage
    data class Error(val message: String) : SearchPipelineStage
}

data class SearchSessionResult(
    val intentProfile: QueryIntentProfile,
    val products: List<MatchedProduct>,
    val storeReports: List<StoreFetchReport>,
    val usedAiVerifier: Boolean,
    val aiNotice: String? = null
)

sealed interface PromoPipelineStage {
    data object Idle : PromoPipelineStage
    data object Loading : PromoPipelineStage
    data object Completed : PromoPipelineStage
    data class Error(val message: String) : PromoPipelineStage
}

data class PromoSessionResult(
    val products: List<MatchedProduct>,
    val storeReports: List<StoreFetchReport>,
    val updatedAtMillis: Long
)

