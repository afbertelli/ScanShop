package com.example

import com.example.data.model.DomainDiscoveryHint
import com.example.data.model.ProductAttribute
import com.example.data.model.QueryIntentProfile
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.data.search.NeuralProductMatcher
import com.example.data.search.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralProductMatcherTest {

    private val matcher = NeuralProductMatcher()

    @Test
    fun `head and compatibility tail splits accessory connectors cleanly`() {
        val split = TextNormalizer.splitHeadAndCompatibilityTail(
            "Capa Silicone Transparente para iPhone 15 Pro Max"
        )
        assertEquals("capa silicone transparente", split.head)
        assertEquals("iphone 15 pro max", split.tail)
    }

    @Test
    fun `neural matcher approves exact PT and EN products and rejects accessories or wrong models`() {
        val profile = QueryIntentProfile(
            rawQuery = "fone jbl tune 520bt bluetooth preto",
            itemType = "Fone de ouvido",
            canonicalTermsPt = listOf("fone de ouvido", "fone", "headphone"),
            canonicalTermsEn = listOf("headphones", "wireless headphone"),
            brand = "JBL",
            line = "Tune",
            model = "520BT",
            alphanumericCodes = listOf("520bt"),
            requiredQualities = listOf("bluetooth"),
            optionalQualities = listOf("preto"),
            isAccessoryIntent = false,
            expectedDomains = listOf(
                DomainDiscoveryHint(
                    domainId = "MLB-HEADPHONES",
                    domainName = "Fones de ouvido",
                    categoryId = "MLB3697",
                    categoryName = "Fones de Ouvido",
                    attributes = listOf(ProductAttribute("Marca", "JBL"))
                )
            )
        )

        val candidates = listOf(
            RawStoreCandidate(
                id = "sp_exact",
                store = StoreType.SHOPEE,
                title = "Fone de Ouvido Sem Fio JBL Tune 520BT Bluetooth Preto Original",
                imageUrl = "https://example.com/img1.jpg",
                productUrl = "https://shopee.com.br/item/1",
                affiliateUrl = "https://shopee.com.br/item/1",
                categoryPath = "Eletronicos Fones de Ouvido",
                sourceRank = 0
            ),
            RawStoreCandidate(
                id = "ae_exact_en",
                store = StoreType.ALIEXPRESS,
                title = "JBL TUNE 520BT Wireless Bluetooth Headphones On Ear Black",
                imageUrl = "https://example.com/img2.jpg",
                productUrl = "https://aliexpress.com/item/2.html",
                affiliateUrl = "https://aliexpress.com/item/2.html",
                categoryPath = "Consumer Electronics > Earphones & Headphones",
                sourceRank = 1
            ),
            RawStoreCandidate(
                id = "sp_earpad_accessory",
                store = StoreType.SHOPEE,
                title = "Almofada Earpad Espuma de Reposicao para JBL Tune 520BT 510BT",
                imageUrl = "https://example.com/img3.jpg",
                productUrl = "https://shopee.com.br/item/3",
                affiliateUrl = "https://shopee.com.br/item/3",
                sourceRank = 0
            ),
            RawStoreCandidate(
                id = "ml_wrong_model",
                store = StoreType.MERCADO_LIVRE,
                title = "Fone de Ouvido JBL Tune 510BT Bluetooth Preto",
                imageUrl = "https://example.com/img4.jpg",
                productUrl = "https://mercadolivre.com.br/p/4",
                affiliateUrl = "https://mercadolivre.com.br/p/4",
                domainId = "MLB-HEADPHONES",
                sourceRank = 0
            )
        )

        val scored = matcher.scoreAndFilter(profile, candidates).associateBy { it.candidate.id }

        val exactPt = scored.getValue("sp_exact")
        assertFalse(exactPt.hardRejected)
        assertTrue(exactPt.breakdown.neuralProbability > 0.70f)

        val exactEn = scored.getValue("ae_exact_en")
        assertFalse(exactEn.hardRejected)
        assertTrue(exactEn.breakdown.neuralProbability > 0.65f)

        val accessory = scored.getValue("sp_earpad_accessory")
        assertTrue(accessory.hardRejected)
        assertTrue(accessory.breakdown.neuralProbability < 0.35f)

        val wrongModel = scored.getValue("ml_wrong_model")
        assertTrue(wrongModel.hardRejected)
        assertTrue(wrongModel.breakdown.neuralProbability < 0.45f)
    }

    @Test
    fun `neural matcher catches compatibility trap and voltage conflict`() {
        val iphoneProfile = QueryIntentProfile(
            rawQuery = "iphone 15 128gb",
            itemType = "Smartphone",
            canonicalTermsPt = listOf("smartphone", "celular", "iphone"),
            canonicalTermsEn = listOf("smartphone", "iphone"),
            brand = "Apple",
            model = "iPhone 15",
            alphanumericCodes = listOf("15", "128gb"),
            requiredQualities = listOf("128gb"),
            isAccessoryIntent = false
        )

        val phone = RawStoreCandidate(
            id = "ml_phone",
            store = StoreType.MERCADO_LIVRE,
            title = "Apple iPhone 15 (128 GB) - Preto",
            imageUrl = "",
            productUrl = "https://mercadolivre.com.br/p/1",
            affiliateUrl = "https://mercadolivre.com.br/p/1"
        )
        val caseTrap = RawStoreCandidate(
            id = "sp_case",
            store = StoreType.SHOPEE,
            title = "Capinha Silicone Magsafe para Apple iPhone 15 128gb",
            imageUrl = "",
            productUrl = "https://shopee.com.br/2",
            affiliateUrl = "https://shopee.com.br/2"
        )

        val results = matcher.scoreAndFilter(iphoneProfile, listOf(phone, caseTrap)).associateBy { it.candidate.id }
        assertFalse(results.getValue("ml_phone").hardRejected)
        assertTrue(results.getValue("ml_phone").breakdown.neuralProbability > 0.70f)
        assertTrue(results.getValue("sp_case").hardRejected)
        assertTrue(results.getValue("sp_case").breakdown.neuralProbability < 0.35f)
    }

    @Test
    fun `smartv normalization matches Samsung Smart TV and blocks remotes led bars and wall mounts`() {
        assertEquals("smart tv Samsung", TextNormalizer.normalizeSearchQueryText("smartv Samsung"))

        val tvProfile = QueryIntentProfile(
            rawQuery = "smartv Samsung",
            itemType = "Televisores",
            canonicalTermsPt = listOf("smart tv", "tv", "televisor", "televisores"),
            canonicalTermsEn = listOf("smart tv", "television"),
            brand = "Samsung",
            isAccessoryIntent = false,
            expectedDomains = listOf(
                DomainDiscoveryHint(
                    domainId = "MLB-TELEVISIONS",
                    domainName = "Televisores",
                    categoryId = "MLB1002",
                    categoryName = "TVs",
                    attributes = listOf(ProductAttribute("Marca", "Samsung"))
                )
            )
        )

        val realTv = RawStoreCandidate(
            id = "sp_tv",
            store = StoreType.SHOPEE,
            title = "Smart TV Samsung 50 Polegadas Crystal UHD 4K Tizen",
            imageUrl = "",
            productUrl = "https://shopee.com.br/1",
            affiliateUrl = "https://shopee.com.br/1"
        )
        val remote = RawStoreCandidate(
            id = "ae_remote",
            store = StoreType.ALIEXPRESS,
            title = "Controle Remoto Original para Smart TV Samsung 4K",
            imageUrl = "",
            productUrl = "https://aliexpress.com/2",
            affiliateUrl = "https://aliexpress.com/2"
        )
        val ledBar = RawStoreCandidate(
            id = "ae_led",
            store = StoreType.ALIEXPRESS,
            title = "Kit Barra de LED para TV Samsung 43 UN43T5300",
            imageUrl = "",
            productUrl = "https://aliexpress.com/3",
            affiliateUrl = "https://aliexpress.com/3"
        )
        val mount = RawStoreCandidate(
            id = "ml_mount",
            store = StoreType.MERCADO_LIVRE,
            title = "Suporte de Parede Articulado para Smart TV Samsung 55",
            imageUrl = "",
            productUrl = "https://mercadolivre.com.br/4",
            affiliateUrl = "https://mercadolivre.com.br/4"
        )

        val results = matcher.scoreAndFilter(tvProfile, listOf(realTv, remote, ledBar, mount))
            .associateBy { it.candidate.id }

        assertFalse(results.getValue("sp_tv").hardRejected)
        assertTrue(results.getValue("sp_tv").breakdown.neuralProbability > 0.65f)
        assertTrue(results.getValue("ae_remote").hardRejected)
        assertTrue(results.getValue("ae_led").hardRejected)
        assertTrue(results.getValue("ml_mount").hardRejected)
    }

    @Test
    fun `tphone typo normalizes to smartphone and phone titles with screen or battery specs pass while spare parts fail`() {
        assertEquals("smartphone Samsung", TextNormalizer.normalizeSearchQueryText("tphone Samsung"))

        val phoneProfile = QueryIntentProfile(
            rawQuery = "tphone Samsung",
            itemType = "smartphone",
            canonicalTermsPt = listOf("smartphone", "celular", "telefone"),
            canonicalTermsEn = listOf("smartphone", "mobile phone", "cell phone", "android phone"),
            brand = "Samsung",
            isAccessoryIntent = false,
            expectedDomains = listOf(
                DomainDiscoveryHint(
                    domainId = "MLB-CELLPHONES",
                    domainName = "Celulares e Smartphones",
                    categoryId = "MLB1055",
                    categoryName = "Celulares e Smartphones",
                    attributes = listOf(ProductAttribute("Marca", "Samsung"))
                )
            )
        )

        val realPhonePt = RawStoreCandidate(
            id = "sp_phone",
            store = StoreType.SHOPEE,
            title = "Smartphone Samsung Galaxy A55 5G 128GB Tela 6.6 Super AMOLED Bateria 5000mAh Carregador",
            imageUrl = "",
            productUrl = "https://shopee.com.br/1",
            affiliateUrl = "https://shopee.com.br/1"
        )
        val realPhoneEn = RawStoreCandidate(
            id = "ae_phone",
            store = StoreType.ALIEXPRESS,
            title = "Original Samsung Galaxy S24 Ultra 5G Mobile Phone 256GB AMOLED Screen 5000mAh Battery",
            imageUrl = "",
            productUrl = "https://aliexpress.com/1",
            affiliateUrl = "https://aliexpress.com/1",
            categoryPath = "Phones & Telecommunications > Mobile Phones"
        )
        val spareScreen = RawStoreCandidate(
            id = "ae_screen",
            store = StoreType.ALIEXPRESS,
            title = "Tela Display LCD Touch para Samsung Galaxy A54 Original",
            imageUrl = "",
            productUrl = "https://aliexpress.com/2",
            affiliateUrl = "https://aliexpress.com/2"
        )
        val spareBattery = RawStoreCandidate(
            id = "ae_battery",
            store = StoreType.ALIEXPRESS,
            title = "Bateria Original EB-BS901ABY para Samsung Galaxy S22",
            imageUrl = "",
            productUrl = "https://aliexpress.com/3",
            affiliateUrl = "https://aliexpress.com/3"
        )
        val phoneCase = RawStoreCandidate(
            id = "sp_case",
            store = StoreType.SHOPEE,
            title = "Capa Silicone Transparente para Samsung Galaxy A55",
            imageUrl = "",
            productUrl = "https://shopee.com.br/4",
            affiliateUrl = "https://shopee.com.br/4"
        )
        val giftPhone = RawStoreCandidate(
            id = "sp_gift_phone",
            store = StoreType.SHOPEE,
            title = "Smartphone Samsung Galaxy A55 5G 128GB Com Pelicula de Brinde",
            imageUrl = "",
            productUrl = "https://shopee.com.br/5",
            affiliateUrl = "https://shopee.com.br/5"
        )
        val galaxyWatch = RawStoreCandidate(
            id = "sp_watch",
            store = StoreType.SHOPEE,
            title = "Smartwatch Samsung Galaxy Watch 6 44mm Bluetooth",
            imageUrl = "",
            productUrl = "https://shopee.com.br/6",
            affiliateUrl = "https://shopee.com.br/6"
        )

        val results = matcher.scoreAndFilter(
            phoneProfile,
            listOf(realPhonePt, realPhoneEn, spareScreen, spareBattery, phoneCase, giftPhone, galaxyWatch)
        ).associateBy { it.candidate.id }

        assertFalse(results.getValue("sp_phone").hardRejected)
        assertTrue(results.getValue("sp_phone").breakdown.neuralProbability > 0.65f)
        assertFalse(results.getValue("ae_phone").hardRejected)
        assertTrue(results.getValue("ae_phone").breakdown.neuralProbability > 0.65f)
        assertFalse(results.getValue("sp_gift_phone").hardRejected)
        assertTrue(results.getValue("sp_gift_phone").breakdown.neuralProbability > 0.65f)
        assertTrue(results.getValue("ae_screen").hardRejected)
        assertTrue(results.getValue("ae_battery").hardRejected)
        assertTrue(results.getValue("sp_case").hardRejected)
        assertTrue(results.getValue("sp_watch").hardRejected)
    }

    @Test
    fun `english aliexpress phone title with trailing case film gift passes while pure case fails`() {
        val phoneProfile = QueryIntentProfile(
            rawQuery = "smartphone Samsung",
            itemType = "smartphone",
            canonicalTermsPt = listOf("smartphone", "celular", "telefone"),
            canonicalTermsEn = listOf("smartphone", "mobile phone", "cell phone", "android phone"),
            brand = "Samsung",
            isAccessoryIntent = false,
            expectedDomains = listOf(
                DomainDiscoveryHint(
                    domainId = "MLB-CELLPHONES",
                    domainName = "Celulares e Smartphones",
                    categoryId = "MLB1055",
                    categoryName = "Celulares e Smartphones",
                    attributes = listOf(ProductAttribute("Marca", "Samsung"))
                )
            )
        )

        val aliPhoneWithGift = RawStoreCandidate(
            id = "ae_phone_gift",
            store = StoreType.ALIEXPRESS,
            title = "Original Unlocked Samsung Galaxy S22 Ultra 5G Mobile Phone With Case Film Protector",
            imageUrl = "",
            productUrl = "https://aliexpress.com/item/100.html",
            affiliateUrl = "https://aliexpress.com/item/100.html",
            categoryPath = "Phones & Telecommunications > Mobile Phones"
        )
        val aliPureCase = RawStoreCandidate(
            id = "ae_pure_case",
            store = StoreType.ALIEXPRESS,
            title = "Case Cover Silicone Samsung Galaxy S24 Ultra",
            imageUrl = "",
            productUrl = "https://aliexpress.com/item/101.html",
            affiliateUrl = "https://aliexpress.com/item/101.html"
        )

        val aliLongTitleWithGift = RawStoreCandidate(
            id = "ae_long_phone_gift",
            store = StoreType.ALIEXPRESS,
            title = "Original Global Version Samsung Galaxy S24 Ultra 5G 12GB RAM 256GB ROM Snapdragon 8 Gen 3 AMOLED 120Hz NFC Smartphone With Case Film",
            imageUrl = "",
            productUrl = "https://aliexpress.com/item/102.html",
            affiliateUrl = "https://aliexpress.com/item/102.html"
        )
        val aliCellphoneCompound = RawStoreCandidate(
            id = "ae_cellphone",
            store = StoreType.ALIEXPRESS,
            title = "Samsung Galaxy S23 Ultra 5G Original Android Cellphone With Protective Film",
            imageUrl = "",
            productUrl = "https://aliexpress.com/item/103.html",
            affiliateUrl = "https://aliexpress.com/item/103.html"
        )

        val results = matcher.scoreAndFilter(
            phoneProfile,
            listOf(aliPhoneWithGift, aliPureCase, aliLongTitleWithGift, aliCellphoneCompound)
        ).associateBy { it.candidate.id }

        assertFalse(results.getValue("ae_phone_gift").hardRejected)
        assertTrue(results.getValue("ae_phone_gift").breakdown.neuralProbability > 0.65f)
        assertFalse(results.getValue("ae_long_phone_gift").hardRejected)
        assertTrue(results.getValue("ae_long_phone_gift").breakdown.neuralProbability > 0.65f)
        assertFalse(results.getValue("ae_cellphone").hardRejected)
        assertTrue(results.getValue("ae_cellphone").breakdown.neuralProbability > 0.65f)
        assertTrue(results.getValue("ae_pure_case").hardRejected)
    }

    @Test
    fun `x99 query accepts motherboard and xeon kit titles while rejecting spare tcon boards and cooler accessories`() {
        val x99Profile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "Placa-mãe X99",
            canonicalTermsPt = listOf("placa mae x99", "placa mae", "kit x99", "kit xeon"),
            canonicalTermsEn = listOf("x99 motherboard", "lga 2011-3 motherboard"),
            alphanumericCodes = listOf("x99"),
            isAccessoryIntent = false
        )

        val motherboard = RawStoreCandidate(
            id = "ml_x99_board",
            store = StoreType.MERCADO_LIVRE,
            title = "Placa Mãe X99 LGA 2011-3 DDR4 M.2 NVMe Para Processador Xeon",
            imageUrl = "https://http2.mlstatic.com/D_111111-O.webp",
            productUrl = "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB90112233",
            affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-x99/p/MLB90112233"
        )
        val kitXeon = RawStoreCandidate(
            id = "ml_x99_kit",
            store = StoreType.MERCADO_LIVRE,
            title = "Kit Placa Mãe Machinist X99 Pr9 + Xeon E5 2680 V4 + 16GB DDR4",
            imageUrl = "https://http2.mlstatic.com/D_222222-O.webp",
            productUrl = "https://produto.mercadolivre.com.br/MLB-90223344-kit-x99",
            affiliateUrl = "https://produto.mercadolivre.com.br/MLB-90223344-kit-x99"
        )
        val coolerAccessory = RawStoreCandidate(
            id = "ml_x99_cooler",
            store = StoreType.MERCADO_LIVRE,
            title = "Suporte Cooler Dual Fan para Placa Mae X99 LGA 2011-3",
            imageUrl = "",
            productUrl = "https://produto.mercadolivre.com.br/MLB-90334455-suporte-x99",
            affiliateUrl = "https://produto.mercadolivre.com.br/MLB-90334455-suporte-x99"
        )

        val results = matcher.scoreAndFilter(x99Profile, listOf(motherboard, kitXeon, coolerAccessory))
            .associateBy { it.candidate.id }

        assertFalse(results.getValue("ml_x99_board").hardRejected)
        assertTrue(results.getValue("ml_x99_board").breakdown.neuralProbability > 0.50f)
        assertFalse(results.getValue("ml_x99_kit").hardRejected)
        assertTrue(results.getValue("ml_x99_kit").breakdown.neuralProbability > 0.50f)
        assertTrue(results.getValue("ml_x99_cooler").hardRejected)
    }

    @Test
    fun `explicit raw alphanumeric code rejects off-topic product even with spurious domain hint`() {
        val spuriousProfile = QueryIntentProfile(
            rawQuery = "x99",
            itemType = "Grampo Automotivo",
            canonicalTermsPt = listOf("grampo", "grampo automotivo"),
            canonicalTermsEn = listOf("clip"),
            alphanumericCodes = listOf("x99"),
            isAccessoryIntent = false,
            expectedDomains = listOf(
                DomainDiscoveryHint(
                    domainId = "MLB-AUTOMOTIVE_CLIPS",
                    domainName = "Grampos automotivos",
                    categoryId = "MLB9999",
                    categoryName = "Grampos",
                    attributes = emptyList()
                )
            )
        )
        val junkClip = RawStoreCandidate(
            id = "ml_clip",
            store = StoreType.MERCADO_LIVRE,
            title = "10 Grampo Churrasqueira Porsche Panamera 2006 A 2012",
            imageUrl = "",
            productUrl = "https://www.mercadolivre.com.br/grampo-porsche/p/MLB123",
            affiliateUrl = "https://www.mercadolivre.com.br/grampo-porsche/p/MLB123"
        )
        val scored = matcher.scoreAndFilter(spuriousProfile, listOf(junkClip)).single()
        assertTrue(scored.hardRejected)
    }

    @Test
    fun `placa mae search rejects all car license plates vehicle mounts decorative signs and gpus while approving real motherboards`() {
        val heuristicProfile = com.example.ai.GeminiQueryAndVerifierEngine()
            .buildHeuristicProfile("placa mae", emptyList())

        assertFalse(
            "Bare 'placa' must never pollute canonicalTermsPt for 'placa mae'",
            heuristicProfile.canonicalTermsPt.any { TextNormalizer.normalize(it) == "placa" }
        )

        val candidates = listOf(
            RawStoreCandidate(
                id = "ml_mobo_am4",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe Asus Prime A520M-E Socket AM4 DDR4 Micro ATX",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_1001-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-a520m-e/p/MLB1001",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-asus-prime-a520m-e/p/MLB1001",
                priceText = "R$ 429,90"
            ),
            RawStoreCandidate(
                id = "ml_mobo_intel",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mãe H61 LGA 1155 DDR3 HDMI NVMe M.2 Para Processador Intel",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_1002-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-mae-h61-lga-1155/p/MLB1002",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-mae-h61-lga-1155/p/MLB1002",
                priceText = "R$ 219,00"
            ),
            RawStoreCandidate(
                id = "ml_car_plate_mercosul",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Mercosul Carro Alumínio Padrão Novo Original",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2001-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2001-placa-mercosul-carro-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2001-placa-mercosul-carro-_JM",
                priceText = "R$ 89,90"
            ),
            RawStoreCandidate(
                id = "ml_car_plate_pair",
                store = StoreType.MERCADO_LIVRE,
                title = "Par De Placa De Carro Mercosul Dianteira E Traseira",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2002-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2002-par-placa-carro-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2002-par-placa-carro-_JM",
                priceText = "R$ 159,00"
            ),
            RawStoreCandidate(
                id = "ml_car_plate_holder",
                store = StoreType.MERCADO_LIVRE,
                title = "Moldura Suporte Porta Placa Veicular Automotiva Preto",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2003-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2003-porta-placa-veicular-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2003-porta-placa-veicular-_JM",
                priceText = "R$ 34,90"
            ),
            RawStoreCandidate(
                id = "ml_vague_plate_with_car_url",
                store = StoreType.MERCADO_LIVRE,
                title = "Par De Placas Alumínio Padrão Novo 40x13cm",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2004-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2004-placa-mercosul-carro-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2004-placa-mercosul-carro-_JM",
                priceText = "R$ 120,00"
            ),
            RawStoreCandidate(
                id = "ml_car_plate_mae_a_bordo",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Carro Mãe a Bordo Automotiva Personalizada",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2005-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2005-placa-carro-mae-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2005-placa-carro-mae-_JM",
                priceText = "R$ 29,90"
            ),
            RawStoreCandidate(
                id = "ml_decor_dia_das_maes",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa Decorativa Homenagem Dia Das Mães MDF",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2006-O.webp",
                productUrl = "https://produto.mercadolivre.com.br/MLB-2006-placa-decorativa-mae-_JM",
                affiliateUrl = "https://produto.mercadolivre.com.br/MLB-2006-placa-decorativa-mae-_JM",
                priceText = "R$ 39,90"
            ),
            RawStoreCandidate(
                id = "ml_gpu_wrong_placa",
                store = StoreType.MERCADO_LIVRE,
                title = "Placa De Vídeo GeForce RTX 4060 8GB GDDR6 Asus",
                imageUrl = "https://http2.mlstatic.com/D_NQ_NP_2007-O.webp",
                productUrl = "https://www.mercadolivre.com.br/placa-de-video-rtx-4060/p/MLB2007",
                affiliateUrl = "https://www.mercadolivre.com.br/placa-de-video-rtx-4060/p/MLB2007",
                priceText = "R$ 1.899,00"
            )
        )

        val scored = matcher.scoreAndFilter(heuristicProfile, candidates).associateBy { it.candidate.id }

        assertFalse(scored.getValue("ml_mobo_am4").hardRejected)
        assertTrue(scored.getValue("ml_mobo_am4").breakdown.neuralProbability > 0.60f)
        assertFalse(scored.getValue("ml_mobo_intel").hardRejected)
        assertTrue(scored.getValue("ml_mobo_intel").breakdown.neuralProbability > 0.60f)

        assertTrue(scored.getValue("ml_car_plate_mercosul").hardRejected)
        assertTrue(scored.getValue("ml_car_plate_pair").hardRejected)
        assertTrue(scored.getValue("ml_car_plate_holder").hardRejected)
        assertTrue(scored.getValue("ml_vague_plate_with_car_url").hardRejected)
        assertTrue(scored.getValue("ml_car_plate_mae_a_bordo").hardRejected)
        assertTrue(scored.getValue("ml_decor_dia_das_maes").hardRejected)
        assertTrue(scored.getValue("ml_gpu_wrong_placa").hardRejected)
    }
}
