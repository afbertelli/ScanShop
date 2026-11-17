package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.MatchVerdict
import com.example.data.model.MatchedProduct
import com.example.data.model.NeuralFeatureBreakdown
import com.example.data.model.StoreType

@Entity(tableName = "favorites")
data class FavoriteProductEntity(
    @PrimaryKey val id: String,
    val storeName: String,
    val title: String,
    val imageUrl: String,
    val productUrl: String,
    val affiliateUrl: String,
    val deepLinkUrl: String,
    val priceText: String,
    val categoryLabel: String,
    val matchReason: String,
    val assertivenessScore: Float,
    val matchVerdictName: String,
    val savedAtMillis: Long = System.currentTimeMillis()
) {
    fun toMatchedProduct(): MatchedProduct {
        val store = StoreType.entries.firstOrNull { it.name == storeName } ?: StoreType.MERCADO_LIVRE
        val verdict = MatchVerdict.entries.firstOrNull { it.name == matchVerdictName } ?: MatchVerdict.EXACT_MATCH
        return MatchedProduct(
            id = id,
            store = store,
            title = title,
            imageUrl = imageUrl,
            productUrl = productUrl,
            affiliateUrl = affiliateUrl,
            deepLinkUrl = deepLinkUrl,
            priceText = priceText,
            categoryLabel = categoryLabel,
            matchedSpecs = emptyList(),
            assertivenessScore = assertivenessScore,
            matchVerdict = verdict,
            matchReason = matchReason,
            featureBreakdown = NeuralFeatureBreakdown(
                semanticEmbeddingScore = assertivenessScore,
                alphanumericModelScore = assertivenessScore,
                brandAndLineScore = assertivenessScore,
                itemHeadNounScore = assertivenessScore,
                specQualityScore = assertivenessScore,
                domainAlignmentScore = assertivenessScore,
                compatibilityTrapScore = 0f,
                sourceRelevanceSignal = 1f,
                neuralProbability = assertivenessScore
            )
        )
    }

    companion object {
        fun fromMatchedProduct(product: MatchedProduct): FavoriteProductEntity =
            FavoriteProductEntity(
                id = product.id,
                storeName = product.store.name,
                title = product.title,
                imageUrl = product.imageUrl,
                productUrl = product.productUrl,
                affiliateUrl = product.affiliateUrl,
                deepLinkUrl = product.deepLinkUrl,
                priceText = product.priceText,
                categoryLabel = product.categoryLabel,
                matchReason = product.matchReason,
                assertivenessScore = product.assertivenessScore,
                matchVerdictName = product.matchVerdict.name,
                savedAtMillis = System.currentTimeMillis()
            )
    }
}
