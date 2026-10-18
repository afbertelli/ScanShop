package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AliShopDatabase
import com.example.data.local.FavoriteProductEntity
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.data.repository.AffiliateProductRepository
import com.example.data.repository.FavoriteRepository
import com.example.data.repository.ProductFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: AliShopDatabase
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var affiliateRepository: AffiliateProductRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AliShopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        favoriteRepository = FavoriteRepository(db.favoriteDao())
        affiliateRepository = AffiliateProductRepository()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read app_name string from context matches ScanShop`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ScanShop", appName)
    }

    @Test
    fun `affiliate search executes safely and handles query`() = runBlocking {
        val emptyResult = affiliateRepository.searchProducts("", ProductFilter.RELEVANCE)
        assertEquals(0, emptyResult.aliExpressProducts.size)
        assertEquals(0, emptyResult.shopeeProducts.size)

        val result = affiliateRepository.searchProducts("Fone Bluetooth", ProductFilter.RELEVANCE)
        assertNotNull(result)
    }

    @Test
    fun `add and remove product from Room favorites`() = runBlocking {
        val product = Product(
            id = "ali_test_01",
            store = StoreType.ALIEXPRESS,
            title = "Fone Bluetooth Teste",
            imageUrl = "https://example.com/img.jpg",
            originalPrice = 100.0,
            promoPrice = 45.0,
            discountPercentage = 55,
            rating = 4.8,
            salesCount = 1000,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = true,
            affiliateUrl = "https://s.click.aliexpress.com/test",
            deepLinkUrl = "aliexpress://test"
        )

        favoriteRepository.addFavorite(product)
        val favorites = favoriteRepository.allFavorites.first()
        assertEquals(1, favorites.size)
        assertEquals("ali_test_01", favorites[0].id)

        val isFav = favoriteRepository.isFavorite("ali_test_01").first()
        assertTrue(isFav)

        favoriteRepository.removeFavorite("ali_test_01")
        val emptyFavorites = favoriteRepository.allFavorites.first()
        assertEquals(0, emptyFavorites.size)
    }

    @Test
    fun `verify seller trust evaluation on product`() {
        val product = Product(
            id = "shp_seller_test",
            store = StoreType.SHOPEE,
            title = "Xiaomi Redmi Note 13 4G Loja Oficial",
            imageUrl = "https://example.com/redmi.jpg",
            originalPrice = 1299.0,
            promoPrice = 899.0,
            discountPercentage = 30,
            rating = 4.9,
            salesCount = 4200,
            freeShipping = true,
            nationalSeller = true,
            couponAvailable = true,
            affiliateUrl = "https://shopee.com.br",
            deepLinkUrl = "shopee://item/test"
        )

        val sellerTrust = com.example.data.ai.SellerTrustEngine.evaluateSeller(product)
        assertTrue(sellerTrust.isVerified)
        assertTrue(sellerTrust.trustScore >= 80)
        assertNotNull(sellerTrust.badgeText)
    }
}
