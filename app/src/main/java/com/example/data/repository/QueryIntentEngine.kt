package com.example.data.repository

import com.example.data.ai.BilingualSearchTranslationEngine
import com.example.data.ai.BrandModelDetector
import com.example.data.ai.NeuralSpecEngine
import com.example.data.ai.SellerTrustEngine
import com.example.data.model.Product
import com.example.data.model.ProductTechnicalSpecs
import java.text.Normalizer
import java.util.Locale

data class QueryIntent(
    val cleanedQuery: String,
    val detectedBrand: String? = null,
    val brandAliases: Set<String> = emptySet(),
    val detectedCategory: String? = null,
    val detectedHardwareModel: String? = null,
    val detectedSubModel: String? = null,
    val isTechDevice: Boolean = false,
    val minPriceGuard: Double = 0.0,
    val keywords: List<String> = emptyList(),
    val accessoryFilterTerms: List<String> = emptyList(),
    val requiredCategoryIndicators: List<String> = emptyList(),
    val technicalSpecs: ProductTechnicalSpecs? = null
)

data class CategoryDef(
    val key: String,
    val synonyms: List<String>,
    val isTechDevice: Boolean,
    val minPriceGuard: Double,
    val accessoryFilter: List<String>,
    val requiredIndicators: List<String> = emptyList()
)

object QueryIntentEngine {

    val UNIVERSAL_ACCESSORIES: List<String> = listOf(
        "adaptador", "adaptadores", "cabo", "cabos", "carregador", "carregadores",
        "suporte", "suportes", "apoio de", "descanso de", "apoio para", "descanso para",
        "capa", "capas", "capinha", "capinhas", "case", "cases", "pelicula", "peliculas",
        "fita", "fitas", "adesivo", "adesivos", "skin", "skins", "estojo", "estojos",
        "bolsa", "bolsas", "chaveiro", "chaveiros", "boneco", "miniatura", "fantasia", "cosplay",
        "almofada", "almofadas", "espuma", "espumas", "protetor", "protetores",
        "limpador", "pincel", "aspirador", "organizador", "dock", "docking",
        "pezinhos", "skates mouse", "glide", "grip tape", "parafuso", "parafusos",
        "bracket", "espelho traseiro", "dissipador", "pasta termica", "extensor",
        "conversor", "receptor", "dongle", "antena", "controle remoto",
        "mouse pad", "mousepad", "desk pad", "deskpad", "tapete",
        "kit mobilador", "mobilador", "keycap", "keycaps", "switch avulso", "switches avulsos",
        "cadarco", "cadarcos", "palmilha", "palmilhas"
    )

    val SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("gamer", "gaming", "jogo", "jogos", "game"),
        setOf("sem fio", "sem-fio", "semfio", "wireless", "bluetooth", "fio"),
        setOf("mecanico", "mecanica", "mechanical"),
        setOf("fone", "fones", "headset", "headphone", "earbuds", "earphones", "tws", "ouvido", "auricular"),
        setOf("mouse", "mouses", "rato", "mice"),
        setOf("teclado", "teclados", "keyboard"),
        setOf("tv", "smart tv", "smarttv", "smartv", "televisao", "televisor", "television", "smart"),
        setOf("celular", "smartphone", "telefone", "phone"),
        setOf("relogio", "smartwatch", "smart watch", "smartband", "inteligente", "watch"),
        setOf("placa mae", "placa-mae", "motherboard", "mainboard", "mobo", "mae", "placa"),
        setOf("placa de video", "placa grafica", "gpu", "graphics card", "video card", "video"),
        setOf("cadeira", "poltrona", "assento")
    )

    fun tokenSatisfied(token: String, text: String): Boolean {
        if (text.contains(token)) return true
        val lowerToken = token.lowercase(Locale.ROOT)
        for (group in SYNONYM_GROUPS) {
            if (group.any { it.equals(lowerToken, ignoreCase = true) }) {
                if (group.any { text.contains(it) }) return true
            }
        }
        return false
    }

    val KNOWN_BRANDS: Map<String, Set<String>> = mapOf(
        "samsung" to setOf("samsung", "galaxy", "crystal uhd", "qled", "neo qled", "odyssey"),
        "huawei" to setOf("huawei", "watch gt", "watch fit", "band 8", "band 9", "band 10", "huawei watch", "huawei band"),
        "xiaomi" to setOf("xiaomi", "redmi", "poco", "mi band"),
        "apple" to setOf("apple", "iphone", "ipad", "macbook", "airpods"),
        "lg" to setOf("lg", "nanocell", "ultragear", "oled"),
        "motorola" to setOf("motorola", "moto g", "moto edge"),
        "sony" to setOf("sony", "playstation", "ps5", "bravia"),
        "jbl" to setOf("jbl", "flip", "charge", "boombox", "tune", "wave"),
        "nike" to setOf("nike", "air max", "jordan"),
        "adidas" to setOf("adidas", "ultraboost", "forum"),
        "dell" to setOf("dell", "inspiron", "vostro", "alienware"),
        "lenovo" to setOf("lenovo", "thinkpad", "ideapad", "legion"),
        "asus" to setOf("asus", "zenfone", "rog", "tuf", "prime"),
        "acer" to setOf("acer", "nitro", "aspire", "predator"),
        "realme" to setOf("realme"),
        "philco" to setOf("philco"),
        "tcl" to setOf("tcl"),
        "aoc" to setOf("aoc"),
        "logitech" to setOf("logitech", "lightsync", "g pro"),
        "redragon" to setOf("redragon"),
        "kingston" to setOf("kingston", "fury"),
        "sandisk" to setOf("sandisk"),
        "machinist" to setOf("machinist", "mr9a", "k9", "x99-rs9", "x99-pr9", "d8 max"),
        "huananzhi" to setOf("huananzhi", "f8", "f8d", "bd4", "tf"),
        "qiyida" to setOf("qiyida", "ed4", "h9s"),
        "soyo" to setOf("soyo", "dragon", "monarch"),
        "jginyue" to setOf("jginyue", "titanium d4"),
        "revenger" to setOf("revenger"),
        "intel" to setOf("intel", "xeon", "core i", "core i3", "core i5", "core i7", "core i9"),
        "amd" to setOf("amd", "ryzen", "radeon"),
        "gigabyte" to setOf("gigabyte", "aorus"),
        "msi" to setOf("msi", "mag", "mpg", "tomahawk", "mortar"),
        "asrock" to setOf("asrock", "taichi", "steel legend"),
        "corsair" to setOf("corsair", "vengeance"),
        "xpg" to setOf("xpg", "spectrix")
    )

    val KNOWN_CATEGORIES: List<CategoryDef> = listOf(
        CategoryDef(
            key = "placa mae",
            synonyms = listOf(
                "placa mae", "placa-mae", "placa mãe", "placamae", "motherboard", "mainboard", "mobo",
                "x99", "x79", "b450", "b550", "b650", "x570", "b660", "b760", "h610", "h81", "h110",
                "a320", "a520", "lga 2011", "lga 2011-3", "lga2011", "lga 1700", "lga 1200",
                "kit x99", "kit placa mae", "kit placa-mae", "kit placa", "combo x99", "kit xeon"
            ),
            isTechDevice = true,
            minPriceGuard = 75.0,
            accessoryFilter = listOf(
                "modulo tpm", "tpm 2.0", "tpm2.0", "tpm", "chip tpm", "tpm pin", "lpc pin", "tpm header",
                "io shield", "i/o shield", "i o shield", "espelho", "espelho traseiro", "placa traseira",
                "suporte cooler", "suporte de montagem", "bracket", "backplate", "trava cpu", "suporte lga",
                "parafuso", "parafusos", "standoff", "dissipador m.2", "dissipador", "thermal pad", "pasta termica",
                "cabo sata", "cabo rgb", "splitter", "bateria cmos", "cr2032", "bateria rtc",
                "tampa de silicone", "porta obd", "tampas anti-poeira", "anti-entu", "obd", "carro", "veicular",
                "rack de chassi", "suporte de placa principal", "suporte de placa", "chassi de estudio", "estudio aberto",
                "bancada de teste", "suporte atx", "gabinete aberto", "chassi",
                "placa decorativa", "placa de vidro", "placa mdf", "quadro decorativo", "controle remoto", "tv box",
                "cosplay", "acrilico", "boneco", "jogo de terror", "cracha", "adesivo", "etb", "camada estendida",
                "1 conjunto de acessorios diy", "acessorios diy", "radiador e backplane", "adequado para placa",
                "para placa-mae", "para placa mae"
            ),
            requiredIndicators = listOf(
                "placa mae", "placa-mae", "placamae", "motherboard", "mainboard", "mobo",
                "x99", "x79", "b450", "b550", "b650", "b760", "h610", "lga2011", "lga 2011",
                "kit x99", "combo x99", "kit placa", "combo placa", "kit xeon", "kit gamer x99"
            )
        ),
        CategoryDef(
            key = "placa de video",
            synonyms = listOf("placa de video", "placadevideo", "gpu", "rtx", "gtx", "rx", "radeon", "geforce", "graphics card"),
            isTechDevice = true,
            minPriceGuard = 130.0,
            accessoryFilter = listOf(
                "suporte gpu", "suporte de placa de video", "cabo riser", "ventoinha", "ventilador",
                "fan para", "backplate", "espelho", "pasta termica", "capa tampa", "placa decorativa",
                "suporte acrilico", "suporte rgb", "adaptador pcie", "cabo 8 pinos"
            ),
            requiredIndicators = listOf("placa de video", "rtx", "gtx", "radeon", "geforce", "gpu", "graphics card", "video card", "placa grafica", "placa gráfica")
        ),
        CategoryDef(
            key = "processador",
            synonyms = listOf("processador", "cpu", "xeon", "ryzen", "core i3", "core i5", "core i7", "core i9", "athlon", "intel core", "amd ryzen"),
            isTechDevice = true,
            minPriceGuard = 35.0,
            accessoryFilter = listOf(
                "cooler para", "air cooler", "water cooler", "bracket", "suporte cooler", "pasta termica",
                "adesivo intel", "adesivo amd", "chaveiro cpu", "trava cpu"
            ),
            requiredIndicators = listOf("processador", "cpu", "xeon", "ryzen", "core i", "intel core", "amd ryzen", "athlon")
        ),
        CategoryDef(
            key = "memoria ram",
            synonyms = listOf("memoria ram", "memória ram", "memoriaram", "ram ddr4", "ram ddr5", "ram ddr3", "memoria ddr4", "memoria ddr5"),
            isTechDevice = true,
            minPriceGuard = 35.0,
            accessoryFilter = listOf(
                "dissipador para ram", "dissipador de memoria", "case memoria", "adesivo", "iluminacao rgb para ram"
            ),
            requiredIndicators = listOf("memoria", "memória", "ram", "ddr4", "ddr5", "ddr3", "dimm")
        ),
        CategoryDef(
            key = "ssd",
            synonyms = listOf("ssd", "nvme", "m.2 nvme", "disco solido", "ssd sata", "m2 nvme", "ssd m.2"),
            isTechDevice = true,
            minPriceGuard = 45.0,
            accessoryFilter = listOf(
                "dissipador m.2", "dissipador ssd", "case para ssd", "case nvme", "gaveta para hd",
                "cabo sata", "adaptador pcie m.2", "parafuso m.2", "enclosure nvme"
            ),
            requiredIndicators = listOf("ssd", "nvme", "disco solido", "m.2", "sata iii", "pcie")
        ),
        CategoryDef(
            key = "fonte",
            synonyms = listOf("fonte atx", "fonte gamer", "fonte de alimentacao", "fonte pc", "power supply", "psu"),
            isTechDevice = true,
            minPriceGuard = 90.0,
            accessoryFilter = listOf(
                "cabo de forca", "cabo extensor", "cabo sleeve", "testador de fonte", "adaptador 24 pinos",
                "filtro de linha", "adaptador pcie", "divisor cabo"
            ),
            requiredIndicators = listOf("fonte", "power supply", "psu", "atx")
        ),
        CategoryDef(
            key = "gabinete",
            synonyms = listOf("gabinete", "gabinete gamer", "gabinete aquario", "case gamer", "chassi gamer"),
            isTechDevice = true,
            minPriceGuard = 90.0,
            accessoryFilter = listOf(
                "filtro de poeira", "fita led", "suporte de headset", "fan avulsa", "ventoinha para gabinete",
                "parafusos gabinete", "painel vidro avulso"
            ),
            requiredIndicators = listOf("gabinete", "case gamer", "chassi")
        ),
        CategoryDef(
            key = "cooler",
            synonyms = listOf("water cooler", "watercooler", "air cooler", "cooler cpu", "cooler para processador"),
            isTechDevice = true,
            minPriceGuard = 45.0,
            accessoryFilter = listOf(
                "pasta termica", "bracket", "suporte cooler", "adaptador am4", "controladora rgb",
                "divisor rgb", "cabo splitter"
            ),
            requiredIndicators = listOf("cooler", "water cooler", "air cooler", "refrigeracao")
        ),
        CategoryDef(
            key = "smart tv",
            synonyms = listOf(
                "smart tv", "smarttv", "smartv", "televisao", "televisor", "tv 32", "tv 43", "tv 50", "tv 55", "tv 65", "tv 75",
                "tv 4k", "tv oled", "tv qled", "neo qled", "crystal uhd", "tv gamer", "tv tizen", "google tv", "android tv"
            ),
            isTechDevice = true,
            minPriceGuard = 280.0,
            accessoryFilter = listOf(
                "controle remoto", "controle de tv", "suporte para tv", "suporte de tv", "suporte articulado",
                "cabo hdmi", "placa principal tv", "placa fonte tv", "placa t-con", "barra de led", "barras de led",
                "fita de led tv", "capa para tv", "pelicula para tv", "pe de tv", "pes de tv", "base para tv",
                "adaptador wireless tv", "conversor digital", "antena digital", "antena tv", "teclado para tv"
            ),
            requiredIndicators = listOf("smart tv", "smarttv", "smartv", "televisao", "televisor", "tv", "monitor smart", "smart monitor", "tv box", "tv stick", "fire stick", "chromecast", "television")
        ),
        CategoryDef(
            key = "monitor",
            synonyms = listOf("monitor", "monitor gamer", "monitor pc", "monitor de pc", "monitor 144hz", "monitor 24", "monitor 27", "monitor curvo"),
            isTechDevice = true,
            minPriceGuard = 90.0,
            accessoryFilter = listOf(
                "suporte articulado", "suporte monitor", "braco articulado", "cabo displayport",
                "fonte para monitor", "capa monitor", "filtro de privacidade", "luz de monitor",
                "monitor de pressao", "monitor de bebe", "sensor de pressao"
            ),
            requiredIndicators = listOf("monitor", "display", "screen")
        ),
        CategoryDef(
            key = "smartwatch",
            synonyms = listOf("smartwatch", "relogio inteligente", "smart watch", "relogio smartwatch", "smartband"),
            isTechDevice = true,
            minPriceGuard = 25.0,
            accessoryFilter = listOf(
                "pulseira", "pulseiras", "pelicula", "peliculas", "case para smartwatch", "capinha para smartwatch",
                "carregador para smartwatch", "cabo carregador relogio", "bezel", "aro protetor"
            ),
            requiredIndicators = listOf("smartwatch", "relogio", "smart watch", "band", "watch", "smart band")
        ),
        CategoryDef(
            key = "celular",
            synonyms = listOf("celular", "smartphone", "telefone", "iphone", "galaxy", "redmi", "poco"),
            isTechDevice = true,
            minPriceGuard = 80.0,
            accessoryFilter = listOf(
                "capinha", "capa para", "case para", "pelicula", "suporte veicular", "cabo usb",
                "carregador", "adaptador otg", "fone de ouvido avulso", "cordao", "anel suporte"
            ),
            requiredIndicators = listOf("celular", "smartphone", "phone", "galaxy", "iphone", "redmi", "poco", "moto", "xiaomi")
        ),
        CategoryDef(
            key = "fone",
            synonyms = listOf("fone de ouvido", "fones de ouvido", "fone bluetooth", "fones bluetooth", "fone", "fones", "headset", "earbuds", "headphone", "airpods", "earphones", "tws", "fone sem fio"),
            isTechDevice = true,
            minPriceGuard = 10.0,
            accessoryFilter = listOf(
                "almofada", "cabo avulso", "case para fone", "capinha para fone", "borrachinha", "tiara",
                "adaptador p2", "estojo vazio", "espuma para fone", "case silicone fone", "bolsa para fone", "caixa organizadora", "suporte para fone"
            ),
            requiredIndicators = listOf("fone", "fones", "headset", "earbuds", "headphone", "tws", "bluetooth", "airpods", "earphones")
        ),
        CategoryDef(
            key = "mouse",
            synonyms = listOf("mouse gamer", "mouse sem fio", "mouse", "mouses", "gaming mouse", "mouse optico", "mouse bluetooth", "g502", "g305", "g203", "deathadder", "dragonfly", "attack shark", "delux"),
            isTechDevice = true,
            minPriceGuard = 10.0,
            accessoryFilter = listOf(
                "mouse pad", "mousepad", "mouse-pad", "tapete", "fantasia", "adesivo", "case para mouse",
                "pes de mouse", "skates mouse", "skate", "grip tape", "cabo para mouse", "suporte de headset",
                "suporte celular", "suporte de telefone", "apoio de pulso", "descanso de pulso", "apoio de braco",
                "skin", "pezinhos", "fita antiderrapante", "fitas antiderrapantes", "estojo para mouse",
                "bolsa para mouse", "capa para mouse", "cosplay", "pelucia", "boneco", "suporte de mouse",
                "encoder", "micro switch"
            ),
            requiredIndicators = listOf("mouse", "mice", "rato", "g502", "g305", "g203", "g403", "g pro", "deathadder", "viper", "dragonfly", "attack shark")
        ),
        CategoryDef(
            key = "teclado",
            synonyms = listOf("teclado mecanico", "teclado mecânico", "teclado gamer", "teclado", "teclados", "mechanical keyboard", "teclado sem fio"),
            isTechDevice = true,
            minPriceGuard = 20.0,
            accessoryFilter = listOf(
                "suporte para telefone", "suporte de telefone", "adesivo", "mouse pad", "mousepad",
                "apenas teclas", "somente teclas", "keycaps", "keycap", "tecla avulsa", "teclas avulsas",
                "switch avulso", "trocador de switch", "ferramenta de clique", "chaveiro", "brinquedo",
                "fidget", "tapete de rato", "piano digital", "instrumento musical", "estojo para teclado",
                "apoio de pulso", "descanso de pulso", "lubrificante", "puxador de tecla"
            ),
            requiredIndicators = listOf("teclado", "keyboard")
        ),
        CategoryDef(
            key = "cadeira gamer",
            synonyms = listOf("cadeira gamer", "cadeira de escritorio", "cadeira ergonomica", "poltrona gamer"),
            isTechDevice = false,
            minPriceGuard = 150.0,
            accessoryFilter = listOf("almofada", "capa para cadeira", "rodinha", "rodizio", "braco avulso", "suporte de copo", "pistao"),
            requiredIndicators = listOf("cadeira", "poltrona", "assento")
        ),
        CategoryDef(
            key = "caixa de som",
            synonyms = listOf("caixa de som", "caixa bluetooth", "speaker", "alexa", "echo dot", "jbl flip", "jbl boombox", "soundbar"),
            isTechDevice = true,
            minPriceGuard = 35.0,
            accessoryFilter = listOf(
                "capa de silicone", "case de transporte", "suporte de parede", "alca para caixa",
                "cabo de carregamento", "fonte para alexa", "suporte alexa"
            ),
            requiredIndicators = listOf("caixa de som", "speaker", "soundbar", "echo", "alexa", "jbl", "boombox")
        ),
        CategoryDef(
            key = "projetor",
            synonyms = listOf("projetor", "mini projetor", "projetor 4k", "projetor hy300", "projetor hy320", "data show"),
            isTechDevice = true,
            minPriceGuard = 75.0,
            accessoryFilter = listOf(
                "tripe para projetor", "suporte de teto", "tela de projecao", "bolsa para projetor",
                "controle remoto para projetor", "lente avulsa"
            ),
            requiredIndicators = listOf("projetor", "projector")
        ),
        CategoryDef(
            key = "drone",
            synonyms = listOf("drone", "mini drone", "drone 4k", "dji drone", "quadcopter"),
            isTechDevice = true,
            minPriceGuard = 70.0,
            accessoryFilter = listOf(
                "helice", "helices", "protetor de helice", "bateria para drone", "bolsa para drone",
                "braco do drone", "motores para drone", "trem de pouso"
            ),
            requiredIndicators = listOf("drone", "quadcopter")
        ),
        CategoryDef(
            key = "tablet",
            synonyms = listOf("tablet", "ipad", "galaxy tab", "xiaomi pad"),
            isTechDevice = true,
            minPriceGuard = 200.0,
            accessoryFilter = listOf(
                "capa para tablet", "capa ipad", "pelicula tablet", "caneta touch avulsa",
                "teclado para tablet", "suporte tablet"
            ),
            requiredIndicators = listOf("tablet", "ipad", "pad")
        ),
        CategoryDef(
            key = "tv box",
            synonyms = listOf("tv box", "tv stick", "fire stick", "fire tv", "chromecast", "mi tv stick", "roku express"),
            isTechDevice = true,
            minPriceGuard = 70.0,
            accessoryFilter = listOf(
                "controle remoto para", "cabo extensor", "fonte para tv box", "suporte para tv stick"
            ),
            requiredIndicators = listOf("tv box", "tv stick", "stick", "fire tv", "chromecast", "roku")
        ),
        CategoryDef(
            key = "camera",
            synonyms = listOf("camera de seguranca", "camera ip", "camera wifi", "camera fotografica", "camera"),
            isTechDevice = true,
            minPriceGuard = 50.0,
            accessoryFilter = listOf(
                "suporte de camera", "capa camera", "cartao de memoria avulso", "cabo extensor", "fonte para camera"
            ),
            requiredIndicators = listOf("camera", "câmera", "cam")
        ),
        CategoryDef(
            key = "notebook",
            synonyms = listOf("notebook", "laptop"),
            isTechDevice = true,
            minPriceGuard = 400.0,
            accessoryFilter = listOf("capa para notebook", "suporte para notebook", "fonte notebook", "carregador notebook", "adesivo")
        )
    )

    fun cleanSearchQuery(query: String): String {
        var q = query.trim().lowercase(Locale.ROOT)
        val nfkd = Normalizer.normalize(q, Normalizer.Form.NFD)
        q = nfkd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")

        val fillerPrefixes = listOf(
            "eu quero um ", "eu quero uma ", "eu quero ", "quero um ", "quero uma ", "quero ",
            "onde comprar ", "comprar ", "menor preco de ", "menor preco ", "melhor preco ",
            "mais barato ", "barato ", "qual o melhor ", "promocao de ", "oferta de ", "ofertas de ", "preco de "
        )
        for (filler in fillerPrefixes) {
            if (q.startsWith(filler)) {
                q = q.removePrefix(filler).trim()
            }
        }

        q = q.replace("smartv smart tv", "smart tv")
            .replace("smart tv smartv", "smart tv")
            .replace("smartv", "smart tv")
            .replace("smarttv", "smart tv")
            .replace("relogio smartwatch", "smartwatch")
            .replace("relogio inteligente", "smartwatch")
            .replace("smart watch", "smartwatch")
            .replace("celular smartphone", "celular")
            .replace("fones de ouvido", "fone de ouvido")
            .replace("fones bluetooth", "fone bluetooth")
            .replace("fones", "fone")
            .replace("mouses", "mouse")
            .replace("teclados", "teclado")
            .replace("relogios", "relogio")
            .replace("monitores", "monitor")
            .replace("placa-mae", "placa mae")
            .replace("placamae", "placa mae")

        val tokens = q.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val uniqueTokens = mutableListOf<String>()
        for (t in tokens) {
            if (uniqueTokens.isEmpty() || uniqueTokens.last() != t) {
                uniqueTokens.add(t)
            }
        }
        return uniqueTokens.joinToString(" ").trim()
    }

    fun parseQueryIntent(rawQuery: String): QueryIntent {
        val cleaned = cleanSearchQuery(rawQuery)
        val lower = cleaned.lowercase(Locale.ROOT)

        var detectedBrand: String? = null
        var brandAliases: Set<String> = emptySet()
        for ((brandKey, aliases) in KNOWN_BRANDS) {
            val matched = aliases.any { alias ->
                val r = Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE)
                r.containsMatchIn(lower) || lower.contains(alias)
            }
            if (matched) {
                detectedBrand = brandKey
                brandAliases = aliases
                break
            }
        }

        var matchedCatDef: CategoryDef? = null
        for (cat in KNOWN_CATEGORIES) {
            val matched = cat.synonyms.any { syn ->
                val normSyn = Normalizer.normalize(syn, Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                val r = Regex("\\b${Regex.escape(normSyn)}\\b", RegexOption.IGNORE_CASE)
                r.containsMatchIn(lower) || lower.contains(normSyn)
            }
            if (matched) {
                matchedCatDef = cat
                break
            }
        }

        val hardwareModelMatch = Regex("\\b(x99|x79|b450|b550|x570|b660|b760|h610|h81|h110|lga\\s*2011|lga\\s*1700|lga\\s*1200)\\b", RegexOption.IGNORE_CASE).find(lower)
        val detectedHardwareModel = hardwareModelMatch?.value?.uppercase(Locale.ROOT)?.replace("\\s+".toRegex(), " ")

        if (detectedHardwareModel != null && matchedCatDef == null) {
            matchedCatDef = KNOWN_CATEGORIES.firstOrNull { it.key == "placa mae" }
        }

        val neuralSpecs = NeuralSpecEngine.extractSpecsFromQuery(cleaned)
        val finalBrand = detectedBrand ?: neuralSpecs.brand?.takeIf {
            it != "Oficial" && it != "Gamer / OEM" &&
                    (KNOWN_BRANDS.containsKey(it.lowercase(Locale.ROOT)) || BrandModelDetector.isKnownBrand(it))
        }
        val finalHardwareModel = detectedHardwareModel ?: neuralSpecs.model

        val keywords = mutableListOf<String>()

        if (matchedCatDef?.key == "placa mae") {
            if (finalHardwareModel != null) {
                keywords.add("placa mae $finalHardwareModel")
                keywords.add("kit $finalHardwareModel")
                keywords.add("kit placa mae $finalHardwareModel")
                keywords.add("$finalHardwareModel motherboard")
                if (finalBrand != null) {
                    keywords.add("placa mae $finalBrand $finalHardwareModel")
                }
                keywords.add(cleaned)
            } else if (finalBrand != null) {
                keywords.add("placa mae $finalBrand")
                keywords.add("$finalBrand motherboard")
                keywords.add(cleaned)
            } else {
                keywords.add("placa mae")
                keywords.add("kit placa mae")
                keywords.add(cleaned)
            }
        } else if (finalBrand != null && matchedCatDef != null) {
            val brand = finalBrand
            val cat = matchedCatDef.key
            keywords.add("$cat $brand")
            keywords.add("$brand $cat")

            when (cat) {
                "smart tv" -> {
                    val sizeMatch = Regex("\\b(32|43|50|55|65|75)\\b").find(cleaned)?.value
                    if (sizeMatch != null) {
                        keywords.add("smart tv $brand $sizeMatch")
                        keywords.add("tv $sizeMatch $brand")
                        keywords.add("$brand $sizeMatch 4k")
                    } else {
                        keywords.add("smart tv $brand")
                        keywords.add("smart tv $brand 43")
                        keywords.add("smart tv $brand 32")
                        keywords.add("smart tv $brand 50")
                        keywords.add("tv $brand")
                        keywords.add("$brand tv 4k")
                        keywords.add("smart monitor $brand")
                        keywords.add("smart tv box 4k")
                    }
                }
                "smartwatch" -> {
                    keywords.add("$brand watch")
                    keywords.add("$brand band")
                    keywords.add("smartwatch $brand")
                }
                "celular" -> {
                    if (finalHardwareModel != null) {
                        keywords.add("$brand $finalHardwareModel")
                    } else {
                        keywords.add("celular $brand")
                        keywords.add("smartphone $brand")
                    }
                }
                "fone" -> {
                    keywords.add("fone $brand")
                    keywords.add("fone bluetooth $brand")
                    keywords.add("headset $brand")
                }
                "mouse" -> {
                    keywords.add("mouse $brand")
                    keywords.add("mouse gamer $brand")
                    keywords.add("gaming mouse $brand")
                }
                "teclado" -> {
                    keywords.add("teclado $brand")
                    keywords.add("teclado mecanico $brand")
                    keywords.add("teclado gamer $brand")
                }
                "placa de video" -> {
                    keywords.add("placa de video $brand")
                    keywords.add("gpu $brand")
                }
                else -> {
                    keywords.add("$cat $brand")
                }
            }
            keywords.add(cleaned)
        } else if (matchedCatDef != null) {
            val cat = matchedCatDef.key
            keywords.add(cat)
            when (cat) {
                "smart tv" -> {
                    keywords.add("smart tv 4k")
                    keywords.add("smart tv box 4k")
                    keywords.add("tv led 4k")
                    keywords.add("smart tv android")
                    keywords.add("televisao 4k")
                    keywords.add("tv box android")
                }
                "mouse" -> {
                    keywords.add("mouse gamer")
                    keywords.add("mouse gamer sem fio")
                    keywords.add("gaming mouse")
                    keywords.add("mouse optico gamer")
                }
                "teclado" -> {
                    keywords.add("teclado mecanico")
                    keywords.add("teclado gamer mecanico")
                    keywords.add("mechanical keyboard")
                    keywords.add("teclado sem fio gamer")
                }
                "fone" -> {
                    keywords.add("fone de ouvido")
                    keywords.add("fone bluetooth")
                    keywords.add("headset gamer")
                    keywords.add("earphones bluetooth")
                    keywords.add("wireless earbuds")
                }
                "placa de video" -> {
                    keywords.add("graphics card gpu")
                    keywords.add("placa de video rtx")
                    keywords.add("placa de video rx")
                    keywords.add("geforce rtx")
                }
                "notebook" -> {
                    keywords.add("laptop gamer")
                    keywords.add("notebook gamer")
                    keywords.add("laptop")
                }
                "smartwatch" -> {
                    keywords.add("smartwatch relogio")
                    keywords.add("smart watch relogio")
                    keywords.add("relogio inteligente")
                }
                "celular" -> {
                    keywords.add("smartphone 128gb")
                    keywords.add("smartphone xiaomi")
                    keywords.add("smartphone")
                }
                "monitor" -> {
                    keywords.add("monitor gamer")
                    keywords.add("monitor 144hz")
                }
            }
            keywords.add(cleaned)
        } else {
            keywords.add(cleaned)
        }

        val bilingualKeywords = BilingualSearchTranslationEngine.generateSearchTerms(cleaned)
        keywords.addAll(bilingualKeywords)

        return QueryIntent(
            cleanedQuery = cleaned,
            detectedBrand = finalBrand,
            brandAliases = brandAliases,
            detectedCategory = matchedCatDef?.key,
            detectedHardwareModel = finalHardwareModel,
            detectedSubModel = neuralSpecs.subModelOrPartNumber?.takeIf { sm ->
                !sm.contains("Edição", ignoreCase = true) &&
                !sm.contains("Série", ignoreCase = true) &&
                sm.any { it.isDigit() }
            },
            isTechDevice = matchedCatDef?.isTechDevice ?: (neuralSpecs.productType != null),
            minPriceGuard = matchedCatDef?.minPriceGuard ?: 0.0,
            keywords = keywords.distinct(),
            accessoryFilterTerms = matchedCatDef?.accessoryFilter ?: emptyList(),
            requiredCategoryIndicators = matchedCatDef?.requiredIndicators ?: emptyList(),
            technicalSpecs = neuralSpecs
        )
    }

    fun isAcceptableCandidate(prod: Product, intent: QueryIntent): Boolean {
        val gateResult = RelevanceGate.evaluate(prod, intent.cleanedQuery, intent)
        return gateResult.isApproved
    }

    fun normalizeResultSet(products: List<Product>, intent: QueryIntent): List<Product> {
        if (products.isEmpty()) return emptyList()

        return products.filter { prod ->
            isAcceptableCandidate(prod, intent)
        }.map { prod ->
            val specs = prod.technicalSpecs ?: NeuralSpecEngine.extractSpecsFromProduct(
                title = prod.title,
                price = prod.promoPrice,
                referenceQuerySpecs = intent.technicalSpecs
            )
            val trust = prod.sellerTrust ?: SellerTrustEngine.evaluateSeller(prod, intent.cleanedQuery)
            prod.copy(
                technicalSpecs = specs,
                sellerTrust = trust
            )
        }
    }
}
