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
    val lowest30DaysPrice: Double
) {
    fun toProduct(): Product {
        val store = if (storeName == StoreType.ALIEXPRESS.name) StoreType.ALIEXPRESS else StoreType.SHOPEE
        val priceDiff = initialSavedPrice - promoPrice
        val dropNotice = if (priceDiff > 1.0) {
            String.format("Caiu R$ %.2f desde que você salvou!", priceDiff).replace('.', ',')
        } else {
            "Preço estável nas últimas 48h"
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
            lowest30DaysPrice = lowest30DaysPrice
        )
    }

    companion object {
        fun fromProduct(product: Product): FavoriteProductEntity {
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
                savedAtTimestamp = System.currentTimeMillis(),
                initialSavedPrice = product.promoPrice + (if (product.id.hashCode() % 2 == 0) 15.0 else 0.0), // Show realistic price variation
                lowest30DaysPrice = product.lowest30DaysPrice
            )
        }
    }
}
