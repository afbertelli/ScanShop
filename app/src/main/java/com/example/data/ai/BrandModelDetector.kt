package com.example.data.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Detector Neural Especializado de Marcas, Modelos e Tipos de Produto.
 * Elimina falsos positivos (ex: "Original", "Novo", "Mini", "Cabo" como marca)
 * e garante identificação técnica exata e profissional para o comparador de e-commerce.
 */
object BrandModelDetector {

    private fun norm(text: String): String {
        val nfkd = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return nfkd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private data class BrandEntry(val canonical: String, val aliases: List<String>)

    private val MASTER_BRANDS = listOf(
        // Smartphones & Grandes Fabricantes de Tech
        BrandEntry("Apple", listOf("apple", "iphone", "ipad", "macbook", "airpods", "apple watch")),
        BrandEntry("Samsung", listOf("samsung", "galaxy")),
        BrandEntry("Xiaomi", listOf("xiaomi", "redmi", "poco", "black shark", "mi band", "pocophone")),
        BrandEntry("Motorola", listOf("motorola", "moto g", "moto edge", "lenovo moto")),
        BrandEntry("Huawei", listOf("huawei", "honor")),
        BrandEntry("Realme", listOf("realme")),
        BrandEntry("Infinix", listOf("infinix")),
        BrandEntry("OnePlus", listOf("oneplus")),
        BrandEntry("Google", listOf("google", "pixel")),
        BrandEntry("Sony", listOf("sony", "xperia", "playstation", "bravia")),
        BrandEntry("LG", listOf("lg", "nanocell", "ultragear")),
        BrandEntry("TCL", listOf("tcl", "semp tcl")),
        BrandEntry("Lenovo", listOf("lenovo", "thinkpad", "ideapad", "legion")),
        BrandEntry("Dell", listOf("dell", "alienware", "inspiron", "vostro", "latitude")),
        BrandEntry("ASUS", listOf("asus", "rog", "tuf", "zenbook", "vivobook")),
        BrandEntry("Acer", listOf("acer", "nitro", "predator", "aspire", "swift")),
        BrandEntry("HP", listOf("hp", "pavilion", "omen", "victus", "probook")),
        BrandEntry("Nintendo", listOf("nintendo", "switch")),
        BrandEntry("Microsoft", listOf("microsoft", "xbox", "surface")),

        // Hardware, Placas-Mãe e GPUs
        BrandEntry("Machinist", listOf("machinist")),
        BrandEntry("Huananzhi", listOf("huananzhi")),
        BrandEntry("Jginyue", listOf("jginyue", "jingyue")),
        BrandEntry("SZMZ", listOf("szmz")),
        BrandEntry("MSI", listOf("msi")),
        BrandEntry("Gigabyte", listOf("gigabyte", "aorus")),
        BrandEntry("ASRock", listOf("asrock")),
        BrandEntry("Soyo", listOf("soyo")),
        BrandEntry("MLLSE", listOf("mllse")),
        BrandEntry("Peladn", listOf("peladn")),
        BrandEntry("Galax", listOf("galax")),
        BrandEntry("Zotac", listOf("zotac")),
        BrandEntry("PNY", listOf("pny")),
        BrandEntry("Sapphire", listOf("sapphire")),
        BrandEntry("PowerColor", listOf("powercolor")),
        BrandEntry("XFX", listOf("xfx")),
        BrandEntry("Biostar", listOf("biostar")),
        BrandEntry("Colorful", listOf("colorful", "igame")),
        BrandEntry("Maxsun", listOf("maxsun")),
        BrandEntry("Intel", listOf("intel", "core i3", "core i5", "core i7", "core i9", "xeon")),
        BrandEntry("AMD", listOf("amd", "ryzen", "radeon")),
        BrandEntry("NVIDIA", listOf("nvidia", "geforce", "rtx", "gtx")),

        // Armazenamento, Memória RAM e SSD
        BrandEntry("Kingston", listOf("kingston", "fury")),
        BrandEntry("SanDisk", listOf("sandisk")),
        BrandEntry("Crucial", listOf("crucial", "ballistix")),
        BrandEntry("Lexar", listOf("lexar")),
        BrandEntry("Netac", listOf("netac")),
        BrandEntry("XrayDisk", listOf("xraydisk", "xray disk")),
        BrandEntry("Fanxiang", listOf("fanxiang")),
        BrandEntry("Goldenfir", listOf("goldenfir")),
        BrandEntry("KingSpec", listOf("kingspec", "king spec")),
        BrandEntry("Western Digital", listOf("western digital", "wd green", "wd blue", "wd black", "wd_black")),
        BrandEntry("Seagate", listOf("seagate", "barracuda", "ironwolf")),
        BrandEntry("Corsair", listOf("corsair", "vengeance")),
        BrandEntry("XPG", listOf("xpg", "adata")),
        BrandEntry("Asgard", listOf("asgard")),
        BrandEntry("Juhor", listOf("juhor")),
        BrandEntry("G.Skill", listOf("g.skill", "gskill", "ripjaws", "trident z")),

        // Áudio, Fones e Caixas de Som
        BrandEntry("JBL", listOf("jbl")),
        BrandEntry("Anker", listOf("anker", "soundcore")),
        BrandEntry("Baseus", listOf("baseus")),
        BrandEntry("Ugreen", listOf("ugreen")),
        BrandEntry("QCY", listOf("qcy")),
        BrandEntry("Haylou", listOf("haylou")),
        BrandEntry("Edifier", listOf("edifier")),
        BrandEntry("Sennheiser", listOf("sennheiser")),
        BrandEntry("Beats", listOf("beats")),
        BrandEntry("Bose", listOf("bose")),
        BrandEntry("Philips", listOf("philips")),
        BrandEntry("KZ", listOf("kz audio", "kz zsn", "kz edx", "kz zst", "kz casto", "kz pr")),
        BrandEntry("Moondrop", listOf("moondrop")),
        BrandEntry("Tranya", listOf("tranya")),

        // Smartwatches e Wearables
        BrandEntry("Amazfit", listOf("amazfit")),
        BrandEntry("Colmi", listOf("colmi")),
        BrandEntry("Zeblaze", listOf("zeblaze")),
        BrandEntry("Mibro", listOf("mibro")),

        // Periféricos Gamer & Escritório
        BrandEntry("Logitech", listOf("logitech", "logi")),
        BrandEntry("Razer", listOf("razer")),
        BrandEntry("Redragon", listOf("redragon")),
        BrandEntry("Attack Shark", listOf("attack shark")),
        BrandEntry("Machenike", listOf("machenike")),
        BrandEntry("Ajazz", listOf("ajazz")),
        BrandEntry("Darmoshark", listOf("darmoshark")),
        BrandEntry("VGN", listOf("vgn")),
        BrandEntry("Epomaker", listOf("epomaker")),
        BrandEntry("Royal Kludge", listOf("royal kludge", "rk61", "rk84", "rk71")),
        BrandEntry("Keychron", listOf("keychron")),
        BrandEntry("SteelSeries", listOf("steelseries")),
        BrandEntry("HyperX", listOf("hyperx")),

        // Vestuário e Tênis
        BrandEntry("Nike", listOf("nike", "air force", "air max", "dunk", "jordan")),
        BrandEntry("Adidas", listOf("adidas", "ultraboost", "superstar", "stan smith", "samba", "gazelle")),
        BrandEntry("Puma", listOf("puma")),
        BrandEntry("Mizuno", listOf("mizuno")),
        BrandEntry("Olympikus", listOf("olympikus")),
        BrandEntry("Asics", listOf("asics", "gel-")),
        BrandEntry("New Balance", listOf("new balance")),
        BrandEntry("Under Armour", listOf("under armour")),
        BrandEntry("Fila", listOf("fila")),
        BrandEntry("Vans", listOf("vans", "old skool")),
        BrandEntry("Converse", listOf("converse", "all star")),
        BrandEntry("Oakley", listOf("oakley")),
        BrandEntry("Ray-Ban", listOf("ray-ban", "rayban"))
    )

    private val STOPWORDS = setOf(
        "original", "novo", "nova", "mini", "super", "cabo", "kit", "placa", "adaptador",
        "cartao", "smart", "relogio", "tenis", "camisa", "fone", "fone de ouvido", "celular",
        "smartphone", "oficial", "versao global", "global version", "promocao", "frete gratis",
        "envio imediato", "pronta entrega", "brasil", "estoque", "capa", "capinha", "pelicula",
        "pulseira", "suporte", "gamer", "pro", "ultra", "max", "plus", "lite",
        "mouse", "rato", "teclado", "fones", "mae", "video", "tv", "smarttv", "televisor",
        "cafeteira", "sapato", "bluetooth", "wireless", "caixa", "som", "cooler", "fonte",
        "gabinete", "memoria", "ssd", "monitor", "computador", "processador", "drone", "tablet"
    )

    fun isKnownBrand(name: String): Boolean {
        val n = norm(name)
        return MASTER_BRANDS.any { it.canonical.equals(name, ignoreCase = true) || it.aliases.any { a -> norm(a) == n } }
    }

    /**
     * Detecta a Marca exata a partir do título do produto com precisão profissional
     */
    fun detectBrand(title: String, isQuery: Boolean = false): String? {
        val clean = norm(title)

        // 1. Busca por correspondência exata de alias com limites estritos de palavras (longest match first)
        for (entry in MASTER_BRANDS) {
            for (alias in entry.aliases.sortedByDescending { it.length }) {
                val pattern = Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(clean)) {
                    return entry.canonical
                }
            }
        }

        // Se for consulta de busca, NUNCA adivinhe marca a partir da primeira palavra
        if (isQuery) return null

        // 2. Extração heurística da primeira palavra apenas se não for palavra genérica de parada
        val tokens = title.trim().split("\\s+".toRegex()).filter { it.length >= 3 }
        for (token in tokens.take(3)) {
            val tNorm = norm(token)
            if (!STOPWORDS.contains(tNorm) && !tNorm.any { it.isDigit() }) {
                // Se parecer nome de fabricante
                if (token.all { it.isLetter() } && token.length in 3..15) {
                    return token.replaceFirstChar { it.uppercase() }
                }
            }
        }

        return null
    }

    /**
     * Detecta o Modelo exato a partir do título do produto
     */
    fun detectModel(title: String, brand: String?): String? {
        val clean = norm(title)

        // Smartphones Apple
        if (clean.contains("iphone")) {
            val iphoneMatch = Regex("\\biphone\\s*(16\\s*pro\\s*max|16\\s*pro|16\\s*plus|16|15\\s*pro\\s*max|15\\s*pro|15\\s*plus|15|14\\s*pro\\s*max|14\\s*pro|14\\s*plus|14|13\\s*pro\\s*max|13\\s*pro|13\\s*mini|13|12\\s*pro\\s*max|12\\s*pro|12|11\\s*pro\\s*max|11\\s*pro|11|xr|xs\\s*max|xs|x|se\\s*3|se\\s*2|se|8\\s*plus|8|7)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (iphoneMatch != null) {
                return "iPhone " + iphoneMatch.groupValues[1].uppercase(Locale.ROOT)
            }
        }

        // Smartphones Samsung
        if (clean.contains("galaxy") || brand == "Samsung") {
            val sMatch = Regex("\\b(s24\\s*ultra|s24\\s*\\+|s24\\s*plus|s24|s23\\s*ultra|s23\\s*\\+|s23\\s*plus|s23|s22\\s*ultra|s22|s21\\s*fe|s21|s20\\s*fe|s20)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (sMatch != null) return "Galaxy " + sMatch.value.uppercase(Locale.ROOT)

            val aMatch = Regex("\\b(a55|a54|a53|a35|a34|a25|a24|a15|a14|a05s|a05|m54|m34|m14|z\\s*flip\\s*5|z\\s*fold\\s*5)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (aMatch != null) return "Galaxy " + aMatch.value.uppercase(Locale.ROOT)
        }

        // Smartphones Xiaomi / POCO / Redmi
        if (clean.contains("poco") || clean.contains("redmi") || brand == "Xiaomi") {
            val pocoMatch = Regex("\\bpoco\\s*(x6\\s*pro|x6|f6\\s*pro|f6|m6\\s*pro|m6|c65|f5\\s*pro|f5|x5\\s*pro|x5)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (pocoMatch != null) return "POCO " + pocoMatch.groupValues[1].uppercase(Locale.ROOT)

            val redmiMatch = Regex("\\bredmi\\s*(note\\s*13\\s*pro\\s*\\+|note\\s*13\\s*pro|note\\s*13|13c|12|note\\s*12\\s*pro|note\\s*12|10c|a3|a2)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (redmiMatch != null) return "Redmi " + redmiMatch.groupValues[1].uppercase(Locale.ROOT)
        }

        // Smartphones Motorola
        if (clean.contains("moto") || brand == "Motorola") {
            val motoMatch = Regex("\\bmoto\\s*(g84|g54|g34|g24|g14|g53|g73|edge\\s*50\\s*ultra|edge\\s*50\\s*pro|edge\\s*50\\s*fusion|edge\\s*40\\s*neo|edge\\s*40)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (motoMatch != null) return "Moto " + motoMatch.groupValues[1].uppercase(Locale.ROOT)
        }

        // Placas-Mãe (Motherboard)
        val moboSubMatch = Regex("\\b(mr9a[- ]pro|mr9a|mr9d|rs9|pr9|bd4|k9|d4|x99[- ]f8|x99[- ]tf|aorus[- ]elite|steel[- ]legend|tomahawk|mortar|pro[- ]vdh|ds3h|gaming[- ]x|prime)\\b", RegexOption.IGNORE_CASE).find(clean)
        val chipsetMatch = Regex("\\b(x99|x79|b450m?|b550m?|b650m?|x570|b660m?|b760m?|h610m?|h81|h110|a320m?|a520m?)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (chipsetMatch != null) {
            val chip = chipsetMatch.value.uppercase(Locale.ROOT)
            val sub = moboSubMatch?.value?.uppercase(Locale.ROOT)
            return if (sub != null && !sub.contains(chip)) "$chip $sub" else sub ?: chip
        }

        // Placas de Vídeo (GPU)
        val gpuMatch = Regex("\\b(rtx\\s*4090|rtx\\s*4080\\s*super|rtx\\s*4080|rtx\\s*4070\\s*ti\\s*super|rtx\\s*4070\\s*ti|rtx\\s*4070\\s*super|rtx\\s*4070|rtx\\s*4060\\s*ti|rtx\\s*4060|rtx\\s*3060\\s*ti|rtx\\s*3060|rtx\\s*3050|rtx\\s*2060\\s*super|rtx\\s*2060|gtx\\s*1660\\s*super|gtx\\s*1660|gtx\\s*1650|rx\\s*7800\\s*xt|rx\\s*7600|rx\\s*6700\\s*xt|rx\\s*6600|rx\\s*580)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (gpuMatch != null) {
            return gpuMatch.value.uppercase(Locale.ROOT)
        }

        // Processadores (CPU)
        val cpuMatch = Regex("\\b(ryzen\\s*7\\s*7800x3d|ryzen\\s*7\\s*5700x3d|ryzen\\s*7\\s*5700x|ryzen\\s*5\\s*5600x|ryzen\\s*5\\s*5600g|ryzen\\s*5\\s*5600|ryzen\\s*5\\s*4600g|i5[- ]12400f|i5[- ]12400|i5[- ]13400|i7[- ]13700k|i7[- ]14700k|e5[- ]2680\\s*v4|e5[- ]2670\\s*v3|e5[- ]2666\\s*v3)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (cpuMatch != null) {
            return cpuMatch.value.uppercase(Locale.ROOT)
        }

        // Fones de Ouvido e Áudio
        val audioMatch = Regex("\\b(lp40\\s*pro|lp40|bowie\\s*m2s|bowie\\s*m2|bowie\\s*e3|tune\\s*520bt|tune\\s*770nc|tune\\s*510bt|wave\\s*flex|wave\\s*buds|wh-1000xm5|wh-1000xm4|wf-1000xm5|t13\\s*anc|t13|air\\s*31|w820nb\\s*plus|soundcore\\s*q30|soundcore\\s*space\\s*one)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (audioMatch != null) {
            return audioMatch.value.uppercase(Locale.ROOT)
        }

        // Smartwatches
        val watchMatch = Regex("\\b(watch\\s*gt\\s*4|watch\\s*gt\\s*3|watch\\s*fit\\s*3|band\\s*9|band\\s*8|mi\\s*band\\s*8|mi\\s*band\\s*7|gtr\\s*4|gts\\s*4|t-rex\\s*2|galaxy\\s*watch\\s*6|galaxy\\s*watch\\s*5|apple\\s*watch\\s*ultra|apple\\s*watch\\s*series\\s*9)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (watchMatch != null) {
            return watchMatch.value.uppercase(Locale.ROOT)
        }

        // Smart TVs
        val tvMatch = Regex("\\b(crystal\\s*uhd\\s*[a-z0-9]+|qled\\s*[a-z0-9]+|oled\\s*[a-z0-9]+|nanocell\\s*[a-z0-9]+|c3|c4|b3|g3|au7700|cu7700|du7700|cu8000|q60c|q60d)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (tvMatch != null) {
            return tvMatch.value.uppercase(Locale.ROOT)
        }

        return null
    }

    /**
     * Identifica se o produto é acessório/peça periférica (capa, película, cabo, pulseira, espelho)
     */
    fun isAccessory(title: String): Boolean {
        val clean = norm(title)
        val accessoryTokens = listOf(
            "capa ", "capinha", "case para", "case de silicone", "pelicula", "pelicula de vidro",
            "cabo usb", "cabo lightning", "cabo tipo c", "adaptador otg", "pulseira para",
            "pulseira de silicone", "pulseira milanese", "suporte de celular", "suporte veicular",
            "espelho placa", "espelho io", "modulo tpm", "dissipador m2", "tampa traseira",
            "gaveta de chip", "bateria para reposicao", "carregador sem fio inducao",
            "fone sem fio capa", "estojo protetor", "cadarco", "palmilha",
            "tampa de silicone", "porta obd", "tampas anti-poeira", "anti-entu",
            "rack de chassi", "suporte de placa principal", "suporte de placa", "chassi de estudio",
            "bancada de teste", "suporte atx", "gabinete aberto"
        )
        return accessoryTokens.any { clean.contains(it) }
    }

    /**
     * Determina a categoria canônica do produto
     */
    fun detectProductType(title: String): String {
        val clean = norm(title)
        return when {
            isAccessory(title) -> "Acessório & Proteção"
            clean.contains("celular") || clean.contains("smartphone") || clean.contains("iphone") ||
                    clean.contains("galaxy") || clean.contains("redmi") || clean.contains("poco") -> "Smartphone (Celular)"
            clean.contains("placa mae") || clean.contains("motherboard") || clean.contains("mobo") ||
                    Regex("\\b(x99|x79|b450|b550|b650|b760|h610)\\b").containsMatchIn(clean) -> "Placa-Mãe (Motherboard)"
            clean.contains("placa de video") || clean.contains("gpu") || Regex("\\b(rtx|gtx|radeon|rx\\s*\\d+)\\b").containsMatchIn(clean) -> "Placa de Vídeo (GPU)"
            clean.contains("smart tv") || clean.contains("televisor") || (clean.contains("tv") && clean.contains("polegadas")) -> "Smart TV (Televisor)"
            clean.contains("notebook") || clean.contains("laptop") || clean.contains("macbook") -> "Notebook (Laptop)"
            clean.contains("fone") || clean.contains("headset") || clean.contains("headphone") || clean.contains("airpods") -> "Fone de Ouvido & Áudio"
            clean.contains("smartwatch") || clean.contains("relogio inteligente") || clean.contains("mi band") -> "Smartwatch & Wearable"
            clean.contains("processador") || clean.contains("cpu") || clean.contains("ryzen") || clean.contains("xeon") -> "Processador (CPU)"
            clean.contains("ssd") || clean.contains("nvme") || clean.contains("m.2") -> "Armazenamento SSD & NVMe"
            clean.contains("memoria ram") || clean.contains("ddr4") || clean.contains("ddr5") -> "Memória RAM"
            clean.contains("teclado") -> "Teclado Gamer / Mecânico"
            clean.contains("mouse") -> "Mouse Gamer / Óptico"
            clean.contains("tenis") -> "Tênis & Calçados"
            else -> "Eletrônicos & Tecnologia"
        }
    }
}
