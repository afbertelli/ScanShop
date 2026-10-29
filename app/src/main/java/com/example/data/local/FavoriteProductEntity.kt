package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Product
import com.example.data.model.StoreType

@Entity(tableName = "favorite_products")
data class FavoriteProductEntity(
    @PrimaryKey
    val id: String,
    val storeName: String,
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
    val couponText: String?,
    val affiliateUrl: String,
    val deepLinkUrl: String,
    val savedAtTimestamp: Long,
    val initialSavedPrice: Double,
    val lowest30DaysPrice: Double,
    val priceDropPercentage: Double = 0.0,
    val hasPriceDropAlert: Boolean = false,
    val lastNotifiedPrice: Double = promoPrice,
    val lastCheckedTimestamp: Long = savedAtTimestamp
) {
    fun toProduct(): Product {
        val store = if (storeName == StoreType.ALIEXPRESS.name) StoreType.ALIEXPRESS else StoreType.SHOPEE
        val calculatedDropPercent = if (initialSavedPrice > 0.0 && promoPrice < initialSavedPrice) {
            ((initialSavedPrice - promoPrice) / initialSavedPrice) * 100.0
        } else {
            0.0
        }
        val effectiveDropPercent = if (priceDropPercentage > 0.0) priceDropPercentage else calculatedDropPercent
        val isAlert = hasPriceDropAlert || effectiveDropPercent >= 10.0

        val dropNotice = if (isAlert) {
            String.format("🔥 Queda de %.0f%%! (R$ %.2f ➔ R$ %.2f)", effectiveDropPercent, initialSavedPrice, promoPrice).replace('.', ',')
        } else if (initialSavedPrice - promoPrice > 1.0) {
            String.format("Caiu R$ %.2f desde que você salvou!", initialSavedPrice - promoPrice).replace('.', ',')
        } else {
            "Preço monitorado e estável"
        }

        return Product(
            id = id,
            store = store,
            title = title,
            imageUrl = imageUrl,
            originalPrice = originalPrice,
            promoPrice = promoPrice,
            discountPercentage = discountPercentage,
            rating = rating,
            salesCount = salesCount,
            freeShipping = freeShipping,
            nationalSeller = nationalSeller,
            couponAvailable = couponAvailable,
            couponText = couponText,
            affiliateUrl = affiliateUrl,
            deepLinkUrl = deepLinkUrl,
            priceDropNotice = dropNotice,
            lowest30DaysPrice = lowest30DaysPrice,
            priceDropPercentage = effectiveDropPercent,
            hasPriceDropAlert = isAlert,
            initialSavedPrice = initialSavedPrice
        )
    }

    companion object {
        fun fromProduct(product: Product): FavoriteProductEntity {
            val now = System.currentTimeMillis()
            val initial = if (product.initialSavedPrice > 0.0) product.initialSavedPrice else product.promoPrice
            return FavoriteProductEntity(
                id = product.id,
                storeName = product.store.name,
                title = product.title,
                imageUrl = product.imageUrl,
                originalPrice = product.originalPrice,
                promoPrice = product.promoPrice,
                discountPercentage = product.discountPercentage,
                rating = product.rating,
                salesCount = product.salesCount,
                freeShipping = product.freeShipping,
                nationalSeller = product.nationalSeller,
                couponAvailable = product.couponAvailable,
                couponText = product.couponText,
                affiliateUrl = product.affiliateUrl,
                deepLinkUrl = product.deepLinkUrl,
                savedAtTimestamp = now,
                initialSavedPrice = initial,
                lowest30DaysPrice = product.lowest30DaysPrice,
                priceDropPercentage = product.priceDropPercentage,
                hasPriceDropAlert = product.hasPriceDropAlert,
                lastNotifiedPrice = product.promoPrice,
                lastCheckedTimestamp = now
            )
        }
    }
}
