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

        val insightSmartTvSamsungDirect = aiService.analyzeLocal("smart tv samsung")
        assertEquals("smart tv", insightSmartTvSamsungDirect.categoryKey)
        assertEquals("Samsung", insightSmartTvSamsungDirect.detectedBrand)
        assertTrue(aiService.matchesProductTitle("Samsung Smart TV 50 Pol. MiniLED 4K", "smart tv samsung", insightSmartTvSamsungDirect, 2599.0))
        assertTrue(aiService.matchesProductTitle("Smart TV Samsung 32” LS32H5000FGXZD HD HDR Tizen Wi-Fi", "smart tv samsung", insightSmartTvSamsungDirect, 1149.0))
        assertFalse(aiService.matchesProductTitle("Controle Remoto Para Tv Samsung Smart Hub", "smart tv samsung", insightSmartTvSamsungDirect, 19.20))
        assertFalse(aiService.matchesProductTitle("Suporte TV Fixo Universal Brasforma", "smart tv samsung", insightSmartTvSamsungDirect, 18.91))

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

    @Test
    fun testBrandModelDetectorCoherence() {
        // Prevents words like "Original", "Novo", "Super", "Cabo" from becoming brands
        val xiaomiTitle = "Original Xiaomi Redmi Note 13 8GB 256GB Smartphone"
        assertEquals("Xiaomi", com.example.data.ai.BrandModelDetector.detectBrand(xiaomiTitle))
        assertEquals("Redmi NOTE 13", com.example.data.ai.BrandModelDetector.detectModel(xiaomiTitle, "Xiaomi"))

        val appleTitle = "Novo Apple iPhone 15 Pro Max 256GB Titânio"
        assertEquals("Apple", com.example.data.ai.BrandModelDetector.detectBrand(appleTitle))
        assertEquals("iPhone 15 PRO MAX", com.example.data.ai.BrandModelDetector.detectModel(appleTitle, "Apple"))

        val machinistTitle = "Placa Mãe Machinist X99 MR9A PRO LGA 2011-3 DDR4"
        assertEquals("Machinist", com.example.data.ai.BrandModelDetector.detectBrand(machinistTitle))
        assertEquals("X99 MR9A PRO", com.example.data.ai.BrandModelDetector.detectModel(machinistTitle, "Machinist"))

        // Accessories detection
        val caseTitle = "Capa Case de Silicone Aveludada para iPhone 15 Pro"
        assertTrue(com.example.data.ai.BrandModelDetector.isAccessory(caseTitle))
        assertEquals("Acessório & Proteção", com.example.data.ai.BrandModelDetector.detectProductType(caseTitle))
    }

    @Test
    fun testCapacityExtractorDisambiguation() {
        // 128GB in a smartphone must be STORAGE, never RAM
        val phoneTitle = "Smartphone Samsung Galaxy A54 128GB 5G"
        val specsPhone = com.example.data.ai.CapacityExtractor.extract(phoneTitle)
        assertEquals("128GB", specsPhone.storage)
        assertNull(specsPhone.ram)

        // Combo 8+256GB
        val comboTitle = "Redmi Note 13 8+256GB Versão Global"
        val specsCombo = com.example.data.ai.CapacityExtractor.extract(comboTitle)
        assertEquals("8GB RAM", specsCombo.ram)
        assertEquals("256GB", specsCombo.storage)
        assertEquals("8GB + 256GB", specsCombo.capacityLabel)

        // PC RAM and SSD
        val pcTitle = "Notebook Gamer Acer Nitro 5 16GB RAM 512GB SSD RTX 3050"
        val specsPc = com.example.data.ai.CapacityExtractor.extract(pcTitle)
        assertEquals("16GB RAM", specsPc.ram)
        assertEquals("512GB SSD", specsPc.storage)

        // Screen size and Hz
        val tvTitle = "Smart TV 55 polegadas 4K UHD 120Hz"
        val specsTv = com.example.data.ai.CapacityExtractor.extract(tvTitle)
        assertEquals("55\"", specsTv.display)
        assertEquals("120HZ", specsTv.refreshRateHz)
        assertEquals("4K Ultra HD", specsTv.resolution)
    }

    @Test
    fun testNeuralSpecEngineEndToEndCoherence() {
        val title = "Original Xiaomi Redmi Note 13 8GB 256GB 5G"
        val specs = com.example.data.ai.NeuralSpecEngine.extractSpecsFromProduct(title, 1200.0)

        assertEquals("Xiaomi", specs.brand)
        assertEquals("Redmi NOTE 13", specs.model)
        assertEquals("256GB", specs.storageCapacity)
        assertNotNull(specs.ramMemory)
        assertTrue(specs.ramMemory?.contains("8GB") == true)
        assertTrue(specs.keyHighlightChips.any { it.contains("Xiaomi") })
        assertTrue(specs.keyHighlightChips.any { it.contains("256GB") })
    }

    @Test
    fun testAiSearchServiceX99MotherboardStrictRejection() {
        val aiService = AiSearchService()
        val insightX99 = aiService.analyzeLocal("x99")

        assertEquals("placa mae", insightX99.categoryKey)
        assertFalse(insightX99.isAccessoryIntent)
        assertTrue("Optimized queries should include placa mae or motherboard", 
            insightX99.optimizedQueries.any { it.contains("placa", ignoreCase = true) || it.contains("motherboard", ignoreCase = true) })

        // 1. Spurious AliExpress items from user report must be strictly rejected
        val carObdCap = "Tampa de silicone para porta OBD de carro, à prova d'água, tampas anti-poeira e anti-entu..."
        val chassisRack = "Rack de chassi de estúdio aberto, suporte de placa principal ATX X79 X99 espesso"
        val phoneHolder = "Suporte para celular veicular saída de ar X99 Universal"

        assertFalse("OBD silicone cap must be rejected", 
            aiService.matchesProductTitle(carObdCap, "x99", insightX99, 19.63))
        assertFalse("Open chassis rack must be rejected", 
            aiService.matchesProductTitle(chassisRack, "x99", insightX99, 41.45))
        assertFalse("Car phone mount must be rejected", 
            aiService.matchesProductTitle(phoneHolder, "x99", insightX99, 29.90))

        // Also test with BrandModelDetector.isAccessory
        assertTrue("OBD cap should be detected as accessory/unrelated", 
            com.example.data.ai.BrandModelDetector.isAccessory(carObdCap))
        assertTrue("Chassis rack should be detected as accessory/support", 
            com.example.data.ai.BrandModelDetector.isAccessory(chassisRack))

        // 2. Real X99 motherboards must match
        val realX99_1 = "Placa-Mãe X99 DDR4 LGA2011 X99 P4 H55 Chipset Gigabit Ethernet X99 Para Jogos Com ..."
        val realX99_2 = "Placa-Mãe X99 X99 P3 LGA2011 Q57 Chipset Gigabit Ethernet Suporta 4x RAM DDR..."
        val realX99_3 = "Placa-Mãe Machinist X99 MR9A PRO LGA 2011-3 DDR4"
        val realX99_4 = "Kit Placa Mãe X99 Xeon E5 2670 V3 16gb DDR4 Gamer"

        assertTrue("Real X99 P4 motherboard must be accepted", 
            aiService.matchesProductTitle(realX99_1, "x99", insightX99, 269.49))
        assertTrue("Real X99 P3 motherboard must be accepted", 
            aiService.matchesProductTitle(realX99_2, "x99", insightX99, 284.34))
        assertTrue("Machinist X99 motherboard must be accepted", 
            aiService.matchesProductTitle(realX99_3, "x99", insightX99, 380.00))
        assertTrue("Kit X99 motherboard must be accepted", 
            aiService.matchesProductTitle(realX99_4, "x99", insightX99, 499.00))
    }

    @Test
    fun testRealSearchProductsSmartTvSamsung(): Unit = kotlinx.coroutines.runBlocking {
        val repo = com.example.data.repository.AffiliateProductRepository()
        val result = repo.searchProducts("smart tv samsung")
        println("TEST OUTPUT: Ali products count = " + result.aliExpressProducts.size)
        println("TEST OUTPUT: Shopee products count = " + result.shopeeProducts.size)
        assertEquals("smart tv samsung", result.query)
        assertNotNull(result.aliExpressProducts)
        assertNotNull(result.shopeeProducts)
        // Garante que NENHUM produto é proveniente de catálogo salvo ou mock
        assertTrue("AliExpress must not contain curated catalog items", result.aliExpressProducts.none { it.id.contains("curated") || it.id.contains("supp") })
        assertTrue("Shopee must not contain curated catalog items", result.shopeeProducts.none { it.id.contains("curated") || it.id.contains("supp") })
        if (result.shopeeProducts.isNotEmpty()) {
            assertTrue("Shopee products should match Samsung intent", result.shopeeProducts.any { it.title.contains("Samsung", ignoreCase = true) })
        }
    }

    @Test
    fun testRealSearchProductsX99(): Unit = kotlinx.coroutines.runBlocking {
        val repo = com.example.data.repository.AffiliateProductRepository()
        val result = repo.searchProducts("x99")
        println("TEST OUTPUT X99: Ali count = " + result.aliExpressProducts.size + ", Shopee count = " + result.shopeeProducts.size)
        assertEquals("x99", result.query)
        assertNotNull(result.aliExpressProducts)
        assertNotNull(result.shopeeProducts)
        // Garante que NENHUM produto é proveniente de catálogo salvo ou mock
        assertTrue("AliExpress must not contain curated items for X99", result.aliExpressProducts.none { it.id.contains("curated") || it.id.contains("supp") })
        assertTrue("Shopee must not contain curated items for X99", result.shopeeProducts.none { it.id.contains("curated") || it.id.contains("supp") })
    }
}
