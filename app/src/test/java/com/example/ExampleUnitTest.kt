package com.example

import com.example.data.ai.AiSearchService
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testAiSearchServicePlacaMaeMsi() {
        val aiService = AiSearchService()
        val insight = aiService.analyzeLocal("placa mae msi")

        assertEquals("MSI", insight.detectedBrand)
        assertEquals("placa mae", insight.categoryKey)
        assertFalse(insight.isAccessoryIntent)
        assertTrue(insight.optimizedQueries.any { it.contains("motherboard", ignoreCase = true) })
        assertTrue(insight.optimizedQueries.any { it.contains("placa-mãe", ignoreCase = true) })

        // Real motherboard tests
        val realMsiMotherboard1 = "Placa-Mãe MSI MAG B560M MORTAR WIFI LGA1200 DDR4 Micro ATX"
        val realMsiMotherboard2 = "【Japan Quality】MSI PRO H610M-B DDR4 Motherboard Micro-ATX"
        val realMsiMotherboard3 = "PLACA MAE MSI PRO A620AM-B EVO AM5"

        assertTrue(aiService.matchesProductTitle(realMsiMotherboard1, "placa mae msi", insight))
        assertTrue(aiService.matchesProductTitle(realMsiMotherboard2, "placa mae msi", insight))
        assertTrue(aiService.matchesProductTitle(realMsiMotherboard3, "placa mae msi", insight))

        // Non-motherboard MSI product should be rejected
        val msiGpu = "Placa de Vídeo MSI GeForce RTX 3060 Ventus 2X"
        assertFalse(aiService.matchesProductTitle(msiGpu, "placa mae msi", insight))

        // Espelhos (I/O shields) and TPM accessories must be rejected
        val espelho1 = "espelho placa mãe MSI (MS-7592)"
        val espelho2 = "Espelho Placa Mae MSI H410M PRO VH"
        val espelho3 = "Espelho Placa Mãe MSI B450 Gaming Plus Max"
        val tpmMod = "Modulo TPM 2.0 Placa Mae X99 Xeon 14-1 Pinos LPC Asus e MSI Windows 11"
        val decorativo = "Quadro Decorativo Santa Maria Mãe de Deus Placa Decorativa MDF"

        assertFalse(aiService.matchesProductTitle(espelho1, "placa mae msi", insight))
        assertFalse(aiService.matchesProductTitle(espelho2, "placa mae msi", insight))
        assertFalse(aiService.matchesProductTitle(espelho3, "placa mae msi", insight))
        assertFalse(aiService.matchesProductTitle(tpmMod, "placa mae msi", insight))
        assertFalse(aiService.matchesProductTitle(decorativo, "placa mae msi", insight))
    }

    @Test
    fun testAiSearchServiceSmartphonesAndDrones() {
        val aiService = AiSearchService()
        val insightS23 = aiService.analyzeLocal("samsung s23")
        assertEquals("Samsung", insightS23.detectedBrand)
        assertEquals("s23", insightS23.detectedModel)

        val realS23 = "Smartphone Samsung Galaxy S23 5G 128GB"
        assertTrue(aiService.matchesProductTitle(realS23, "samsung s23", insightS23))

        val insightPoco = aiService.analyzeLocal("poco x6")
        val realPoco = "Smartphone Xiaomi POCO X6 Pro 5G 256GB"
        assertTrue(aiService.matchesProductTitle(realPoco, "poco x6", insightPoco))
    }

    @Test
    fun testAiSearchServiceSmartTvMonitorAndSmartphone() {
        val aiService = AiSearchService()

        // 1. smartv & smart tv
        val insightSmartv = aiService.analyzeLocal("smartv")
        assertEquals("smart tv", insightSmartv.categoryKey)
        assertTrue(insightSmartv.optimizedQueries.any { it.contains("smart tv 32", ignoreCase = true) })

        val insightSmartTvSamsung = aiService.analyzeLocal("smartv samsung")
        assertEquals("smart tv", insightSmartTvSamsung.categoryKey)
        assertEquals("Samsung", insightSmartTvSamsung.detectedBrand)
        assertTrue(insightSmartTvSamsung.optimizedQueries.any { it.contains("smart tv samsung", ignoreCase = true) })

        val realSamsungTv = "Smart TV Samsung 43\" QLED Full HD QN43Q5F"
        val tvRemote = "Controle Remoto Samsung Smartv 4k Mu6100 Nu7000"
        val tvMount = "Suporte Articulado para Smart TV LED 43\" LG ThinQ"

        assertTrue(aiService.matchesProductTitle(realSamsungTv, "smartv samsung", insightSmartTvSamsung, 1864.0))
        assertFalse(aiService.matchesProductTitle(tvRemote, "smartv samsung", insightSmartTvSamsung, 197.0))
        assertFalse(aiService.matchesProductTitle(tvMount, "smartv samsung", insightSmartTvSamsung, 116.0))

        // 2. monitor (de pc) & monitor de pc
        val insightMonitorPc = aiService.analyzeLocal("monitor (de pc)")
        assertEquals("monitor", insightMonitorPc.categoryKey)
        assertTrue(insightMonitorPc.optimizedQueries.any { it.contains("monitor gamer 24", ignoreCase = true) })

        val realMonitor = "Monitor Gamer 24 Polegadas Full HD Ultra - MN802"
        val tireMonitor = "Sensor de pressão dos pneus TPMS para Monitoramento da Pressão dos Pneus"
        val bloodPressure = "Bolsa de Armazenamento para Monitor de Pressão Arterial"
        val monitorArm = "Suporte Articulado a Gás para Monitor 17 a 35 Polegadas"

        assertTrue(aiService.matchesProductTitle(realMonitor, "monitor (de pc)", insightMonitorPc, 1098.0))
        assertFalse(aiService.matchesProductTitle(tireMonitor, "monitor (de pc)", insightMonitorPc, 757.0))
        assertFalse(aiService.matchesProductTitle(bloodPressure, "monitor (de pc)", insightMonitorPc, 26.0))
        assertFalse(aiService.matchesProductTitle(monitorArm, "monitor (de pc)", insightMonitorPc, 190.0))

        // 3. smartphone & smartphone samsung
        val insightSmartphone = aiService.analyzeLocal("smartphone")
        assertEquals("smartphone", insightSmartphone.categoryKey)
        assertTrue(insightSmartphone.optimizedQueries.any { it.contains("smartphone 128gb", ignoreCase = true) })

        val insightSmartphoneSamsung = aiService.analyzeLocal("smartphone samsung")
        assertEquals("smartphone", insightSmartphoneSamsung.categoryKey)
        assertEquals("Samsung", insightSmartphoneSamsung.detectedBrand)
        assertTrue(insightSmartphoneSamsung.optimizedQueries.any { it.contains("galaxy 128gb", ignoreCase = true) })

        val realGalaxyA54 = "Samsung Galaxy A54 - Preto | 128 GB"
        val phoneCase = "Capa Case Silicone Compativel com o Samsung S21 ULTRA"
        val phoneMount = "Suporte magnetico para telemovel e smartphone veicular"
        val robotDog = "Cão Robô Controlado por Controle Remoto Inteligente Brinquedo Mecânico"

        assertTrue(aiService.matchesProductTitle(realGalaxyA54, "smartphone samsung", insightSmartphoneSamsung, 1701.0))
        assertFalse(aiService.matchesProductTitle(phoneCase, "smartphone samsung", insightSmartphoneSamsung, 15.0))
        assertFalse(aiService.matchesProductTitle(phoneMount, "smartphone samsung", insightSmartphoneSamsung, 25.0))
        assertFalse(aiService.matchesProductTitle(robotDog, "smartphone", insightSmartphone, 249.0))
    }

    @Test
    fun testDeveloperCompanyInfoAndAboutScreen() {
        val companyName = "Adimilson Fernando Bertelli Informatica ME"
        val developerEmail = "AFBERTELLI@gmail.com"
        assertTrue(companyName.isNotBlank())
        assertTrue(developerEmail.contains("@"))
        assertEquals("Adimilson Fernando Bertelli Informatica ME", companyName)
    }

    @Test
    fun testSellerTrustEngineEvaluation() {
        val officialProduct = com.example.data.model.Product(
            id = "shp_official_01",
            store = com.example.data.model.StoreType.SHOPEE,
            title = "Samsung Galaxy S23 5G 128GB - Loja Oficial",
            imageUrl = "https://example.com/s23.jpg",
            originalPrice = 3999.0,
            promoPrice = 2899.0,
            discountPercentage = 27,
            rating = 4.9,
            salesCount = 3500,
            freeShipping = true,
            nationalSeller = true,
            couponAvailable = true,
            affiliateUrl = "https://shopee.com.br",
            deepLinkUrl = "shopee://item/123"
        )

        val officialTrust = com.example.data.ai.SellerTrustEngine.evaluateSeller(officialProduct)
        assertTrue("Official store must be verified", officialTrust.isVerified)
        assertTrue("Trust score should be >= 90", officialTrust.trustScore >= 90)
        assertEquals(com.example.data.model.SellerBadgeType.OFFICIAL, officialTrust.badgeType)
        assertTrue(officialTrust.badgeText.contains("Loja Oficial"))

        val verifiedMerchantProduct = com.example.data.model.Product(
            id = "ali_choice_01",
            store = com.example.data.model.StoreType.ALIEXPRESS,
            title = "Fone Bluetooth Baseus Bowie M2s Cancelamento Ruído Choice",
            imageUrl = "https://example.com/baseus.jpg",
            originalPrice = 299.0,
            promoPrice = 149.0,
            discountPercentage = 50,
            rating = 4.8,
            salesCount = 2400,
            freeShipping = true,
            nationalSeller = false,
            couponAvailable = true,
            couponText = "Cupom Choice",
            affiliateUrl = "https://aliexpress.com",
            deepLinkUrl = "aliexpress://item/456"
        )

        val verifiedTrust = com.example.data.ai.SellerTrustEngine.evaluateSeller(verifiedMerchantProduct)
        assertTrue("High volume merchant must be verified", verifiedTrust.isVerified)
        assertTrue("Trust score should be >= 80", verifiedTrust.trustScore >= 80)

        val poorSellerProduct = com.example.data.model.Product(
            id = "shp_poor_01",
            store = com.example.data.model.StoreType.SHOPEE,
            title = "Produto Genérico Sem Marca",
            imageUrl = "https://example.com/poor.jpg",
            originalPrice = 100.0,
            promoPrice = 90.0,
            discountPercentage = 10,
            rating = 3.6,
            salesCount = 12,
            freeShipping = false,
            nationalSeller = false,
            couponAvailable = false,
            affiliateUrl = "https://shopee.com.br",
            deepLinkUrl = "shopee://item/789"
        )

        val poorTrust = com.example.data.ai.SellerTrustEngine.evaluateSeller(poorSellerProduct)
        assertFalse("Poor rated seller must not be verified", poorTrust.isVerified)
        assertTrue("Poor trust score must be below 75", poorTrust.trustScore < 75)
    }
}

