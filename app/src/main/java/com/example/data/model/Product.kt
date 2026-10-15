package com.example.data.model

import com.example.data.ai.AiSearchInsight
import java.text.NumberFormat
import java.util.Locale

data class Product(
    val id: String,
    val store: StoreType,
    val title: String,
    val imageUrl: String,
    val originalPrice: Double,
    val promoPrice: Double,
    val discountPercentage: Int,
    val rating: Double,
    val salesCount: Int,
    val freeShipping: Boolean,
    val nationalSeller: Boolean,
    val couponAvailable: Boolean,
    val couponText: String? = null,
    val affiliateUrl: String,
    val deepLinkUrl: String,
    val priceDropNotice: String? = null,
    val lowest30DaysPrice: Double = promoPrice,
    val isSuperDeal: Boolean = discountPercentage >= 40,
    val isExactAiMatch: Boolean = false,
    val aiMatchBadge: String? = null,
    val aiGender: String? = null
) {
    val formattedPromoPrice: String
        get() = formatBrl(promoPrice)

    val formattedOriginalPrice: String
        get() = formatBrl(originalPrice)

    private fun formatBrl(amount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
        return format.format(amount)
    }
}

data class ComparisonResult(
    val query: String,
    val aliExpressProducts: List<Product>,
    val shopeeProducts: List<Product>,
    val unifiedSorted: List<Product>,
    val winnerProduct: Product? = null,
    val averageAliExpressPrice: Double = 0.0,
    val averageShopeePrice: Double = 0.0,
    val priceDeltaPercentage: Double = 0.0,
    val aiInsight: AiSearchInsight? = null
) {
    val unifiedSortedProducts: List<Product>
        get() = unifiedSorted

    val bestOverallDeal: Product?
        get() = winnerProduct ?: unifiedSorted.minByOrNull { it.promoPrice }
}

