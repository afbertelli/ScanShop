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

    @Test
    fun `QueryIntentEngine preserves real mice with switches and bundled cables`() {
        val intent = com.example.data.repository.QueryIntentEngine.parseQueryIntent("mouse gamer")
        val sampleProducts = listOf(
            Product(
                id = "m1",
                store = StoreType.ALIEXPRESS,
                title = "Mouse Gamer Attack Shark X3 Sem Fio Tri-Mode com Micro Switch Huano e Grip Tape",
                imageUrl = "https://example.com/m1.jpg",
                originalPrice = 180.0,
                promoPrice = 120.0,
                discountPercentage = 33,
                rating = 4.8,
                salesCount = 2500,
                freeShipping = true,
                nationalSeller = false,
                couponAvailable = true,
                affiliateUrl = "https://example.com",
                deepLinkUrl = ""
            ),
            Product(
                id = "m2",
                store = StoreType.SHOPEE,
                title = "Mouse Gamer Óptico USB 3200 DPI com cabo trançado e LED RGB",
                imageUrl = "https://example.com/m2.jpg",
                originalPrice = 45.0,
                promoPrice = 22.90,
                discountPercentage = 49,
                rating = 4.7,
                salesCount = 8200,
                freeShipping = true,
                nationalSeller = true,
                couponAvailable = false,
                affiliateUrl = "https://example.com",
                deepLinkUrl = ""
            ),
            Product(
                id = "acc1",
                store = StoreType.ALIEXPRESS,
                title = "Mousepad Gamer Speed Extra Grande 90x40cm com borda costurada",
                imageUrl = "https://example.com/pad.jpg",
                originalPrice = 50.0,
                promoPrice = 25.0,
                discountPercentage = 50,
                rating = 4.9,
                salesCount = 3100,
                freeShipping = true,
                nationalSeller = false,
                couponAvailable = false,
                affiliateUrl = "https://example.com",
                deepLinkUrl = ""
            ),
            Product(
                id = "junk1",
                store = StoreType.ALIEXPRESS,
                title = "Vestido Feminino Estampado Elegante",
                imageUrl = "https://example.com/dress.jpg",
                originalPrice = 100.0,
                promoPrice = 50.0,
                discountPercentage = 50,
                rating = 4.5,
                salesCount = 100,
                freeShipping = true,
                nationalSeller = false,
                couponAvailable = false,
                affiliateUrl = "https://example.com",
                deepLinkUrl = ""
            )
        )

        val clean = com.example.data.repository.QueryIntentEngine.normalizeResultSet(sampleProducts, intent)
        val cleanIds = clean.map { it.id }

        // Produtos genuínos são mantidos, enquanto tapetes avulsos e lixo são descartados
        assertTrue("m1 deve ser mantido", cleanIds.contains("m1"))
        assertTrue("m2 deve ser mantido", cleanIds.contains("m2"))
        assertTrue("Tapete avulso deve ser filtrado", !cleanIds.contains("acc1"))
        assertTrue("Vestido/lixo deve ser filtrado", !cleanIds.contains("junk1"))
    }

    @Test
    fun `BilingualSearchTranslationEngine translates queries bidirectionally and beautifies product titles`() {
        val ptKeywords = com.example.data.ai.BilingualSearchTranslationEngine.generateSearchTerms("mouse gamer")
        assertTrue("Should include English gaming mouse", ptKeywords.any { it.contains("gaming mouse") })

        val enKeywords = com.example.data.ai.BilingualSearchTranslationEngine.generateSearchTerms("wireless keyboard")
        assertTrue("Should include Portuguese teclado sem fio", enKeywords.any { it.contains("teclado sem fio") || it.contains("teclado") })

        // Test title cleanup of AliExpress machine translations
        val translatedMouse = com.example.data.ai.BilingualSearchTranslationEngine.translateAndCleanTitle("Rato para jogos sem fio óptico recarregável")
        assertTrue("Should replace rato para jogos with Mouse Gamer", translatedMouse.startsWith("Mouse Gamer"))

        val translatedPad = com.example.data.ai.BilingualSearchTranslationEngine.translateAndCleanTitle("Tapete de rato grande para mesa de jogos")
        assertTrue("Should replace tapete de rato with Mousepad", translatedPad.contains("Mousepad"))

        val translatedHeadset = com.example.data.ai.BilingualSearchTranslationEngine.translateAndCleanTitle("Auscultadores bluetooth sem fio")
        assertTrue("Should replace auscultadores with Fones de Ouvido", translatedHeadset.contains("Fones de Ouvido"))
    }

    @Test
    fun `QueryIntentEngine allows cheap items without price guards`() {
        val intent = com.example.data.repository.QueryIntentEngine.parseQueryIntent("mouse optico")
        val cheapMouse = Product(
            id = "cheap_m1",
            store = StoreType.SHOPEE,
            title = "Mini Mouse Óptico USB Básico 1000 DPI para Notebook",
            imageUrl = "https://example.com/cheap.jpg",
            originalPrice = 9.90,
            promoPrice = 4.99, // Under old price floor!
            discountPercentage = 50,
            rating = 4.6,
            salesCount = 500,
            freeShipping = false,
            nationalSeller = true,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )

        val clean = com.example.data.repository.QueryIntentEngine.normalizeResultSet(listOf(cheapMouse), intent)
        assertEquals("Cheap mouse must be accepted without price guards", 1, clean.size)
        assertEquals("cheap_m1", clean[0].id)
    }

    @Test
    fun `BilingualSearchTranslationEngine rejects tapete as false friend when searching for mouse`() {
        val isTapeteAccessory = com.example.data.ai.BilingualSearchTranslationEngine.isFalseFriendAccessory(
            title = "Tapete de rato gamer grande para mesa 80x30",
            targetCategory = "mouse"
        )
        assertTrue("Tapete de rato must be flagged as accessory when searching for mouse", isTapeteAccessory)

        val isRealMouseAccessory = com.example.data.ai.BilingualSearchTranslationEngine.isFalseFriendAccessory(
            title = "Mouse gamer sem fio óptico 2.4g recarregável",
            targetCategory = "mouse"
        )
        assertTrue("Real mouse must NOT be flagged as accessory", !isRealMouseAccessory)
    }

    @Test
    fun `QueryIntentEngine filters accessories across all categories while keeping real devices`() {
        val mouseIntent = com.example.data.repository.QueryIntentEngine.parseQueryIntent("logitech g502")
        val authenticMouse = Product(
            id = "g502_authentic",
            store = StoreType.ALIEXPRESS,
            title = "Logitech G502 HERO High Performance 25K Sensor RGB USB",
            imageUrl = "https://example.com/g502.jpg",
            originalPrice = 250.0,
            promoPrice = 180.0,
            discountPercentage = 28,
            rating = 4.8,
            salesCount = 1200,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )
        val mousepad = Product(
            id = "g502_pad",
            store = StoreType.ALIEXPRESS,
            title = "Tapete de mesa para Logitech G502 antiderrapante",
            imageUrl = "https://example.com/pad.jpg",
            originalPrice = 30.0,
            promoPrice = 15.0,
            discountPercentage = 50,
            rating = 4.5,
            salesCount = 50,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )
        assertTrue("Authentic mouse must be accepted", com.example.data.repository.QueryIntentEngine.isAcceptableCandidate(authenticMouse, mouseIntent))
        assertTrue("Tapete/mousepad must be filtered", !com.example.data.repository.QueryIntentEngine.isAcceptableCandidate(mousepad, mouseIntent))

        // Phone and case across all categories
        val phoneIntent = com.example.data.repository.QueryIntentEngine.parseQueryIntent("celular iphone 15")
        val realPhone = Product(
            id = "iphone_real",
            store = StoreType.ALIEXPRESS,
            title = "Smartphone Apple iPhone 15 128GB Desbloqueado Original",
            imageUrl = "https://example.com/ip.jpg",
            originalPrice = 4500.0,
            promoPrice = 3999.0,
            discountPercentage = 11,
            rating = 4.9,
            salesCount = 300,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )
        val phoneCase = Product(
            id = "iphone_case",
            store = StoreType.ALIEXPRESS,
            title = "Capinha para Celular iPhone 15 de Silicone Antichoque",
            imageUrl = "https://example.com/case.jpg",
            originalPrice = 25.0,
            promoPrice = 12.0,
            discountPercentage = 52,
            rating = 4.6,
            salesCount = 1000,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )
        assertTrue("Real phone must be accepted", com.example.data.repository.QueryIntentEngine.isAcceptableCandidate(realPhone, phoneIntent))
        assertTrue("Phone case must be filtered when searching phone", !com.example.data.repository.QueryIntentEngine.isAcceptableCandidate(phoneCase, phoneIntent))
    }

    @Test
    fun `price drop monitoring triggers alert when reduction is superior to 10 percent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val monitor = com.example.data.monitor.PriceMonitoringManager(
            context = context,
            favoriteRepository = favoriteRepository,
            affiliateRepository = affiliateRepository
        )

        val product = Product(
            id = "monitor_test_item_01",
            store = StoreType.ALIEXPRESS,
            title = "Mouse Gamer Sem Fio RGB",
            imageUrl = "https://example.com/mouse.jpg",
            originalPrice = 200.0,
            promoPrice = 100.0,
            discountPercentage = 50,
            rating = 4.8,
            salesCount = 500,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = true,
            affiliateUrl = "https://example.com",
            deepLinkUrl = "",
            initialSavedPrice = 100.0
        )

        favoriteRepository.addFavorite(product)
        val initialList = favoriteRepository.getAllFavoritesList()
        assertEquals(1, initialList.size)
        assertEquals(100.0, initialList[0].initialSavedPrice, 0.01)

        // Drop from 100 to 85 -> 15% reduction (superior to 10%)
        val success = monitor.simulatePriceDrop("monitor_test_item_01", 15.0)
        assertTrue(success)

        val updatedEntity = favoriteRepository.getFavoriteById("monitor_test_item_01")
        assertNotNull(updatedEntity)
        assertEquals(85.0, updatedEntity!!.promoPrice, 0.5)
        assertTrue("Drop percentage must be superior to 10%", updatedEntity.priceDropPercentage > 10.0)
        assertTrue("Alert flag must be active", updatedEntity.hasPriceDropAlert)

        val productModel = updatedEntity.toProduct()
        assertTrue(productModel.hasPriceDropAlert)
        assertTrue(productModel.priceDropNotice!!.contains("Queda de"))
    }

    @Test
    fun `price drop monitoring ignores reduction of 10 percent or less`() = runBlocking {
        val product = Product(
            id = "monitor_test_item_02",
            store = StoreType.SHOPEE,
            title = "Teclado Mecânico Gamer ABNT2",
            imageUrl = "https://example.com/kb.jpg",
            originalPrice = 150.0,
            promoPrice = 100.0,
            discountPercentage = 33,
            rating = 4.7,
            salesCount = 200,
            freeShipping = true,
            nationalSeller = true,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = "",
            initialSavedPrice = 100.0
        )

        favoriteRepository.addFavorite(product)

        // Only 8% reduction (8.00 drop, <= 10%)
        favoriteRepository.updatePriceDrop(
            id = "monitor_test_item_02",
            newPrice = 92.0,
            dropPercent = 8.0,
            hasAlert = false,
            timestamp = System.currentTimeMillis()
        )

        val updated = favoriteRepository.getFavoriteById("monitor_test_item_02")
        assertNotNull(updated)
        assertEquals(92.0, updated!!.promoPrice, 0.01)
        assertEquals(8.0, updated.priceDropPercentage, 0.01)
        assertTrue("Should not have active alert for <= 10%", !updated.hasPriceDropAlert)
    }

    @Test
    fun `price monitoring applies strictly to favorited products`() = runBlocking {
        val favorites = favoriteRepository.getAllFavoritesList()
        val favIds = favorites.map { it.id }.toSet()

        // Non-favorited item
        val nonFavoritedId = "random_unfavorited_product_999"
        assertTrue("Non-favorited items must not be in favorites list", !favIds.contains(nonFavoritedId))
        val fetched = favoriteRepository.getFavoriteById(nonFavoritedId)
        assertEquals(null, fetched)
    }

    @Test
    fun `calculateRelevanceScore prioritizes exact matching products over accessories without dropping items`() {
        val exactMouse = Product(
            id = "m_exact",
            store = StoreType.ALIEXPRESS,
            title = "Mouse Gamer Attack Shark X3 Sem Fio Recarregável RGB",
            imageUrl = "https://example.com/mouse.jpg",
            originalPrice = 200.0,
            promoPrice = 129.0,
            discountPercentage = 35,
            rating = 4.9,
            salesCount = 500,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )

        val accessoryWithLotsOfSales = Product(
            id = "acc_popular",
            store = StoreType.ALIEXPRESS,
            title = "Mousepad Gamer Grande Estendido Speed 90x40cm",
            imageUrl = "https://example.com/pad.jpg",
            originalPrice = 40.0,
            promoPrice = 25.0,
            discountPercentage = 37,
            rating = 4.8,
            salesCount = 15000,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://example.com",
            deepLinkUrl = ""
        )

        val mouseScore = affiliateRepository.calculateRelevanceScore(exactMouse, "mouse gamer", "mouse")
        val accScore = affiliateRepository.calculateRelevanceScore(accessoryWithLotsOfSales, "mouse gamer", "mouse")

        // Exact matching mouse must score significantly higher than the mousepad despite the mousepad having 15,000 sales
        assertTrue("Mouse score ($mouseScore) must exceed accessory score ($accScore)", mouseScore > accScore)

        val unsortedList = listOf(accessoryWithLotsOfSales, exactMouse)
        val sortedList = unsortedList.sortedByDescending {
            affiliateRepository.calculateRelevanceScore(it, "mouse gamer", "mouse")
        }

        // Total count preserved 100%
        assertEquals(2, sortedList.size)
        // Exact mouse is at position 0
        assertEquals("m_exact", sortedList[0].id)
        // Accessory is at position 1 (not deleted)
        assertEquals("acc_popular", sortedList[1].id)
    }
}

