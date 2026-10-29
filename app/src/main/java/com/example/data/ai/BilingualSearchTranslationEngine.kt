package com.example.data.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Motor Inteligente de Tradução Bilíngue e Normalização de Produtos (Bilingual Search & Translation Engine):
 * 1. Traduz consultas do usuário (Português <-> Inglês) para maximizar o retorno das APIs (AliExpress e Shopee).
 * 2. Limpa e traduz títulos bizarros de tradução automática do AliExpress para Português Brasileiro natural e claro.
 * 3. Identifica falsos amigos de tradução (ex: 'tapete' traduzido de 'desk mat' / 'mousepad') para manter as buscas precisas.
 */
object BilingualSearchTranslationEngine {

    /**
     * Termos e frases frequentes de hardware, periféricos e eletrônicos:
     * Mapeamento Bidirecional Português <-> Inglês.
     */
    private val PT_TO_EN_PHRASES: List<Pair<String, String>> = listOf(
        // Periféricos
        "mouse gamer sem fio" to "wireless gaming mouse",
        "mouse gamer com fio" to "wired gaming mouse",
        "mouse gamer" to "gaming mouse",
        "mouse sem fio" to "wireless mouse",
        "mouse optico" to "optical mouse",
        "mouse bluetooth" to "bluetooth mouse",
        "mouse vertical" to "vertical ergonomic mouse",
        "teclado mecanico sem fio" to "wireless mechanical keyboard",
        "teclado mecanico gamer" to "gaming mechanical keyboard",
        "teclado mecanico" to "mechanical keyboard",
        "teclado gamer" to "gaming keyboard",
        "teclado sem fio" to "wireless keyboard",
        "teclado e mouse" to "keyboard and mouse combo",
        "fone de ouvido sem fio" to "wireless earbuds",
        "fone de ouvido bluetooth" to "bluetooth earphones",
        "fones de ouvido" to "earphones headphones",
        "fone de ouvido" to "earphones headphones",
        "fone bluetooth" to "bluetooth earbuds",
        "fone sem fio" to "wireless earbuds",
        "headset gamer" to "gaming headset",
        "headphone sem fio" to "wireless headphones",
        "mousepad gamer" to "gaming mousepad desk mat",
        "tapete de mouse" to "mousepad desk mat",
        "tapete de rato" to "mousepad desk mat",
        "mousepad grande" to "large desk mat mousepad",
        "apoio de pulso" to "wrist rest support",

        // Componentes de PC & Hardware
        "placa de video" to "graphics card gpu",
        "placa grafica" to "graphics card gpu",
        "placa mae" to "motherboard mainboard",
        "placa-mae" to "motherboard mainboard",
        "kit placa mae" to "motherboard combo kit cpu",
        "kit xeon" to "xeon x99 motherboard kit",
        "processador" to "cpu processor",
        "memoria ram" to "ram memory ddr4 ddr5",
        "memoria ddr4" to "ddr4 ram memory",
        "memoria ddr5" to "ddr5 ram memory",
        "disco solido" to "nvme ssd",
        "fonte de alimentacao" to "atx power supply psu",
        "fonte atx" to "atx power supply psu",
        "fonte gamer" to "pc power supply psu",
        "gabinete gamer" to "pc gaming case",
        "gabinete aquario" to "aquarium pc gaming case",
        "water cooler" to "liquid cpu water cooler",
        "air cooler" to "cpu air cooler heatsink",
        "cooler para processador" to "cpu cooler fan",
        "pasta termica" to "thermal paste compound",

        // Dispositivos & Eletrônicos
        "smart tv 4k" to "smart tv 4k television",
        "smart tv" to "smart tv television",
        "televisao" to "smart tv television",
        "televisor" to "smart tv television",
        "monitor gamer" to "gaming monitor 144hz 165hz",
        "monitor curvo" to "curved gaming monitor",
        "monitor de pc" to "pc computer monitor",
        "relogio inteligente" to "smartwatch smart band",
        "smartwatch" to "smartwatch smart band",
        "celular" to "smartphone android mobile",
        "smartphone" to "smartphone android mobile",
        "notebook gamer" to "gaming laptop computer",
        "notebook" to "laptop computer",
        "tablet" to "tablet android ipad",
        "mesa digitalizadora" to "drawing graphics tablet",
        "caixa de som bluetooth" to "bluetooth speaker portable",
        "caixa de som" to "portable speaker audio",
        "projetor 4k" to "4k home theater projector",
        "projetor" to "smart video projector",
        "drone com camera" to "drone with 4k camera",
        "drone" to "camera drone quadcopter",
        "carregador rapido" to "fast charger quick charge",
        "cabo tipo c" to "usb type-c cable",
        "carregador por inducao" to "wireless charger pad",
        "oculos de sol" to "sunglasses polarized",
        "tenis de corrida" to "running sneakers shoes",
        "tenis casual" to "casual sneakers shoes",
        "mochila impermeavel" to "waterproof backpack"
    )

    private val EN_TO_PT_WORDS: Map<String, String> = mapOf(
        "wireless" to "sem fio",
        "bluetooth" to "bluetooth",
        "gaming" to "gamer",
        "game" to "gamer",
        "mouse" to "mouse",
        "mice" to "mouses",
        "rat" to "mouse",
        "keyboard" to "teclado",
        "mechanical" to "mecânico",
        "motherboard" to "placa-mãe",
        "mainboard" to "placa-mãe",
        "graphics card" to "placa de vídeo",
        "video card" to "placa de vídeo",
        "earbuds" to "fones de ouvido",
        "earphones" to "fones de ouvido",
        "headset" to "headset",
        "headphones" to "fones de ouvido",
        "speaker" to "caixa de som",
        "desk mat" to "mousepad",
        "mousepad" to "mousepad",
        "mouse pad" to "mousepad",
        "power supply" to "fonte de alimentação",
        "case" to "gabinete",
        "screen" to "tela",
        "display" to "tela / monitor",
        "wrist rest" to "apoio de pulso",
        "charger" to "carregador",
        "cable" to "cabo",
        "watch" to "relógio",
        "smartwatch" to "smartwatch"
    )

    /**
     * Dicionário de Correção e Limpeza de Títulos Estranhos do AliExpress (PT-PT / Máquina -> PT-BR Natural).
     */
    private val ALIBABA_TRANSLATION_CLEANUP: List<Pair<Regex, String>> = listOf(
        // Correção de Rato -> Mouse
        Regex("(?i)\\brato\\s+para\\s+jogos\\b") to "Mouse Gamer",
        Regex("(?i)\\brato\\s+de\\s+jogos\\b") to "Mouse Gamer",
        Regex("(?i)\\brato\\s+sem\\s+fio\\b") to "Mouse Sem Fio",
        Regex("(?i)\\brato\\s+com\\s+fio\\b") to "Mouse Com Fio",
        Regex("(?i)\\brato\\s+optico\\b") to "Mouse Óptico",
        Regex("(?i)\\brato\\s+óptico\\b") to "Mouse Óptico",
        Regex("(?i)\\brato\\s+bluetooth\\b") to "Mouse Bluetooth",
        Regex("(?i)\\brato\\s+ergonomico\\b") to "Mouse Ergonômico",
        Regex("(?i)\\brato\\s+ergonómico\\b") to "Mouse Ergonômico",
        Regex("(?i)\\brato\\b") to "Mouse",

        // Correção de Tapete de Rato -> Mousepad / Desk Mat
        Regex("(?i)\\btapete\\s+de\\s+rato\\b") to "Mousepad Desk Mat",
        Regex("(?i)\\btapete\\s+de\\s+mouse\\b") to "Mousepad Desk Mat",
        Regex("(?i)\\balmofada\\s+de\\s+rato\\b") to "Mousepad",
        Regex("(?i)\\balmofada\\s+de\\s+mouse\\b") to "Mousepad",
        Regex("(?i)\\btapete\\s+do\\s+rato\\b") to "Mousepad",
        Regex("(?i)\\btapete\\s+para\\s+jogos\\b") to "Mousepad Gamer",
        Regex("(?i)\\btapete\\s+mesa\\b") to "Deskpad / Mousepad de Mesa",

        // Correção de Áudio e Telefonia
        Regex("(?i)\\bauscutadores\\b") to "Fones de Ouvido",
        Regex("(?i)\\bauscultadores\\b") to "Fones de Ouvido",
        Regex("(?i)\\bauricular\\s+bluetooth\\b") to "Fone Bluetooth",
        Regex("(?i)\\bauriculares\\b") to "Fones de Ouvido",
        Regex("(?i)\\btelemovel\\b") to "Celular",
        Regex("(?i)\\btelemóvel\\b") to "Celular",
        Regex("(?i)\\becra\\s+tatil\\b") to "Tela Touchscreen",
        Regex("(?i)\\becrã\\s+táctil\\b") to "Tela Touchscreen",
        Regex("(?i)\\becra\\b") to "Tela",
        Regex("(?i)\\becrã\\b") to "Tela",

        // Correção de Hardware
        Regex("(?i)\\bcartao\\s+grafico\\b") to "Placa de Vídeo",
        Regex("(?i)\\bcartão\\s+gráfico\\b") to "Placa de Vídeo",
        Regex("(?i)\\bplaca-mãe\\s+do\\s+computador\\s+de\\s+mesa\\b") to "Placa-Mãe Desktop",
        Regex("(?i)\\bplaca\\s+mae\\s+do\\s+computador\\b") to "Placa-Mãe",
        Regex("(?i)\\bunidade\\s+de\\s+estado\\s+solido\\b") to "SSD",
        Regex("(?i)\\bunidade\\s+de\\s+estado\\s+sólido\\b") to "SSD",
        Regex("(?i)\\bcaixa\\s+do\\s+computador\\b") to "Gabinete",
        Regex("(?i)\\bcaixa\\s+de\\s+jogos\\b") to "Gabinete Gamer",
        Regex("(?i)\\bchave\\s+mecanica\\s+para\\s+teclado\\b") to "Switches Mecânicos",
        Regex("(?i)\\bchaves\\s+mecanicas\\b") to "Switches Mecânicos",
        Regex("(?i)\\btampa\\s+de\\s+chave\\b") to "Keycaps",
        Regex("(?i)\\btampas\\s+de\\s+chaves\\b") to "Keycaps",
        Regex("(?i)\\bapoio\\s+de\\s+pulso\\s+do\\s+rato\\b") to "Apoio de Pulso",
        Regex("(?i)\\bapoiador\\s+de\\s+pulso\\b") to "Apoio de Pulso",
        Regex("(?i)\\bdescanso\\s+de\\s+pulso\\b") to "Apoio de Pulso"
    )

    /**
     * Traduz e gera os melhores termos de busca bilíngues para a consulta.
     * Retorna a lista contendo a consulta original, sua versão em inglês (se buscado em português)
     * e variações estratégicas de alta conversão.
     */
    fun generateSearchTerms(rawQuery: String): List<String> {
        val query = rawQuery.trim()
        if (query.isBlank()) return emptyList()

        val normalized = normalizeText(query)
        val terms = mutableListOf<String>()
        terms.add(query)

        // 1. Verifica correspondência direta de frase completa ou prefixo
        for ((ptPhrase, enPhrase) in PT_TO_EN_PHRASES) {
            val normPt = normalizeText(ptPhrase)
            if (normalized == normPt || normalized.contains(normPt)) {
                // Adiciona o termo em inglês correspondente
                val enTranslated = normalized.replace(normPt, enPhrase).trim()
                terms.add(enTranslated)
                terms.add(enPhrase)
            }
        }

        // 2. Se a busca contém palavras em inglês, adiciona equivalentes em português
        for ((enWord, ptWord) in EN_TO_PT_WORDS) {
            if (normalized.contains(enWord)) {
                val ptTranslated = normalized.replace(enWord, ptWord).trim()
                terms.add(ptTranslated)
            }
        }

        // 3. Regras específicas de eletrônicos para máxima precisão no AliExpress
        when {
            normalized.contains("mouse gamer") -> {
                terms.add("gaming mouse")
                terms.add("wireless gaming mouse")
            }
            normalized.contains("teclado mecanico") -> {
                terms.add("mechanical keyboard")
                terms.add("gaming mechanical keyboard")
            }
            normalized.contains("fone bluetooth") || normalized.contains("fone sem fio") || normalized.contains("fone de ouvido") -> {
                terms.add("wireless earbuds")
                terms.add("tws bluetooth earphones")
            }
            normalized.contains("placa de video") || normalized.contains("placa de vídeo") -> {
                terms.add("graphics card")
                terms.add("video card gpu")
            }
            normalized.contains("placa mae") || normalized.contains("placa-mae") || normalized.contains("placa mãe") -> {
                terms.add("motherboard")
                terms.add("mainboard")
            }
            normalized.contains("smart tv") -> {
                terms.add("smart tv")
                terms.add("4k smart television")
            }
            normalized.contains("smartwatch") || normalized.contains("relogio inteligente") -> {
                terms.add("smartwatch")
                terms.add("smart band")
            }
        }

        return terms.distinct().filter { it.isNotBlank() }
    }

    /**
     * Traduz, limpa e embeleza títulos de produtos retornados pelas APIs do AliExpress e Shopee.
     * Transforma termos estranhos de tradução mecânica (como "rato para jogos" ou "tapete de rato")
     * em Português Brasileiro claro, além de eliminar repetições excessivas.
     */
    fun translateAndCleanTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return rawTitle
        var cleaned = rawTitle.trim()

        // 1. Aplica substituições de correção de tradução
        for ((regex, replacement) in ALIBABA_TRANSLATION_CLEANUP) {
            cleaned = cleaned.replace(regex, replacement)
        }

        // 2. Remove pontuações estranhas no início e fim
        cleaned = cleaned.replace("^[\\s,.-]+".toRegex(), "")
            .replace("[\\s,.-]+$".toRegex(), "")

        // 3. Limpa palavras duplicadas consecutivas geradas por robôs de SEO
        // Ex: "Mouse Mouse Gamer Sem Fio" -> "Mouse Gamer Sem Fio"
        val words = cleaned.split("\\s+".toRegex())
        val deduplicated = mutableListOf<String>()
        for (w in words) {
            if (deduplicated.isEmpty() || !deduplicated.last().equals(w, ignoreCase = true)) {
                deduplicated.add(w)
            }
        }

        val finalTitle = deduplicated.joinToString(" ").trim()
        return if (finalTitle.isNotBlank()) finalTitle else rawTitle
    }

    /**
     * Identifica se um produto é um acessório/falso amigo traduzido incorretamente
     * para a categoria buscada (cobre todas as categorias de tecnologia, periféricos e eletrônicos).
     * Exemplo: Detecta que 'tapete' ou 'desk mat' é um mousepad e NÃO um mouse,
     * que 'capinha' é acessório e NÃO um celular, que 'almofada' é acessório e NÃO um fone, etc.
     */
    fun isFalseFriendAccessory(title: String, targetCategory: String?): Boolean {
        val norm = normalizeText(title)

        // 1. Padrão universal de compatibilidade direta: se o título começa com "capa para", "suporte para", "tapete para", etc.
        val universalPrefixes = listOf(
            "capa para ", "capinha para ", "case para ", "estojo para ", "pelicula para ", "película para ",
            "suporte para ", "suporte de ", "braco articulado para ", "pulseira para ", "pulseiras para ",
            "almofada para ", "almofadas para ", "espuma para ", "cabo para ", "adaptador para ",
            "adesivo para ", "adesivos para ", "fita antiderrapante para ", "bolsa para ",
            "controle remoto para ", "barra de led para ", "placa t-con para ", "espelho para ",
            "modulo tpm ", "trava para ", "trocador de ", "helice para ", "helices para ",
            "tapete de mesa para ", "tapete para ", "tapete de rato para ", "tapete de mouse para ",
            "mousepad para ", "mouse pad para ", "deskpad para ", "desk mat para ", "tapete de mesa "
        )
        for (pref in universalPrefixes) {
            if (norm.startsWith(pref)) {
                return true
            }
        }

        if (targetCategory == null) return false

        when (targetCategory.lowercase(Locale.ROOT)) {
            "mouse" -> {
                val isPadOrMat = norm.contains("tapete") || norm.contains("mousepad") ||
                        norm.contains("mouse pad") || norm.contains("desk mat") ||
                        norm.contains("deskpad") || norm.contains("almofada de rato") ||
                        norm.contains("almofada de mouse") || norm.contains("apoio de pulso") ||
                        norm.contains("descanso de pulso") || norm.contains("skates mouse") ||
                        norm.contains("grip tape") || norm.contains("fita antiderrapante")

                val isRealMouseHardware = norm.contains("mouse sem fio") || norm.contains("mouse gamer") ||
                        norm.contains("mouse optico") || norm.contains("mouse óptico") || norm.contains("mouse bluetooth") ||
                        norm.contains("gaming mouse") || norm.contains("wireless mouse") || norm.contains("mouse recarregavel") ||
                        norm.contains("mouse usb") || (norm.contains("mouse") && !norm.startsWith("mousepad") && !norm.startsWith("mouse pad")) ||
                        (norm.contains("rato") && !norm.startsWith("tapete de rato"))

                if (isPadOrMat && !isRealMouseHardware) return true
            }

            "teclado" -> {
                val isKeycapsOrSwitches = norm.contains("keycap") || norm.contains("keycaps") ||
                        norm.contains("switches") || norm.contains("switch avulso") ||
                        norm.contains("puxador de tecla") || norm.contains("apenas teclas") ||
                        norm.contains("trocador de switch") || norm.contains("lubrificante switch")

                val isRealKeyboardHardware = norm.contains("teclado mecanico") || norm.contains("teclado gamer") ||
                        norm.contains("mechanical keyboard") || norm.contains("gaming keyboard") ||
                        norm.contains("teclado sem fio") || norm.contains("wireless keyboard") ||
                        norm.contains("teclado bluetooth") || norm.contains("teclado usb") ||
                        (norm.contains("teclado") && !norm.startsWith("capa para teclado") && !norm.startsWith("adesivo para teclado"))

                if (isKeycapsOrSwitches && !isRealKeyboardHardware) return true
            }

            "fone" -> {
                val isEarPadOrCase = norm.contains("almofada") || norm.contains("almofadas") ||
                        norm.contains("earpads") || norm.contains("espuma para fone") ||
                        norm.contains("case silicone") || norm.contains("capinha para fone") ||
                        norm.contains("estojo vazio") || norm.contains("cabo de reposicao")

                val isRealAudioDevice = norm.contains("fone bluetooth") || norm.contains("fone de ouvido") ||
                        norm.contains("headset gamer") || norm.contains("earbuds") ||
                        norm.contains("headphones") || norm.contains("airpods") || norm.contains("tws") ||
                        (norm.contains("fone") && !norm.startsWith("capa para fone") && !norm.startsWith("almofada para fone"))

                if (isEarPadOrCase && !isRealAudioDevice) return true
            }

            "smartwatch" -> {
                val isStrapOrCase = norm.contains("pulseira") || norm.contains("pulseiras") ||
                        norm.contains("case para relogio") || norm.contains("pelicula relogio") ||
                        norm.contains("cabo carregador relogio") || norm.contains("bezel protetor")

                val isRealWatchDevice = norm.contains("smartwatch") || norm.contains("relogio inteligente") ||
                        norm.contains("smart watch") || norm.contains("smart band") || norm.contains("galaxy watch") ||
                        norm.contains("apple watch") || norm.contains("huawei watch") ||
                        (norm.contains("relogio") && !norm.startsWith("pulseira para relogio"))

                if (isStrapOrCase && !isRealWatchDevice) return true
            }

            "celular" -> {
                val isPhoneCaseOrGlass = norm.contains("capinha") || norm.contains("capa silicone") ||
                        norm.contains("pelicula de vidro") || norm.contains("suporte veicular")

                val isRealPhoneDevice = norm.contains("smartphone") || norm.contains("celular") ||
                        norm.contains("iphone") || norm.contains("xiaomi redmi") || norm.contains("galaxy s")

                if (isPhoneCaseOrGlass && !isRealPhoneDevice) return true
            }

            "smart tv", "tv" -> {
                val isTvAccessory = norm.contains("suporte articulado") || norm.contains("suporte de parede para tv") ||
                        norm.contains("controle remoto para") || norm.contains("barra de led para tv") ||
                        norm.contains("placa t-con") || norm.contains("fita led tv")

                val isRealTv = norm.contains("smart tv") || norm.contains("televisao") ||
                        norm.contains("tv 4k") || norm.contains("tv led") || norm.contains("tv box")

                if (isTvAccessory && !isRealTv) return true
            }

            "monitor" -> {
                val isMonitorArm = norm.contains("braco articulado") || norm.contains("suporte articulado para monitor") ||
                        norm.contains("luminaria para monitor") || norm.contains("lightbar")

                val isRealMonitor = norm.contains("monitor gamer") || norm.contains("monitor 144hz") ||
                        norm.contains("monitor curvo") || norm.contains("monitor 24") || norm.contains("monitor 27")

                if (isMonitorArm && !isRealMonitor) return true
            }

            "placa de video" -> {
                val isGpuAccessory = norm.contains("suporte gpu") || norm.contains("suporte de placa de video") ||
                        norm.contains("cabo riser") || norm.contains("ventoinha de reposicao")

                val isRealGpu = norm.contains("placa de video") || norm.contains("graphics card") ||
                        norm.contains("geforce rtx") || norm.contains("radeon rx") || norm.contains("gtx ")

                if (isGpuAccessory && !isRealGpu) return true
            }

            "placa mae" -> {
                val isMoboPart = norm.contains("modulo tpm") || norm.contains("tpm 2.0") ||
                        norm.contains("espelho io") || norm.contains("suporte cooler") ||
                        norm.contains("trava cpu") || norm.contains("bancada de teste")

                val isRealMoboHardware = norm.contains("placa mae") || norm.contains("motherboard") ||
                        norm.contains("mainboard") || norm.contains("kit x99") || norm.contains("combo x99")

                if (isMoboPart && !isRealMoboHardware) return true
            }

            "drone" -> {
                val isDroneAccessory = norm.contains("helice para drone") || norm.contains("helices para drone") ||
                        norm.contains("protetor de helice") || norm.contains("trem de pouso")

                val isRealDrone = norm.contains("drone com camera") || norm.contains("drone 4k") ||
                        norm.contains("quadcopter") || norm.contains("drone gps")

                if (isDroneAccessory && !isRealDrone) return true
            }
        }

        return false
    }

    private fun normalizeText(text: String): String {
        val nfkd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return nfkd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}
