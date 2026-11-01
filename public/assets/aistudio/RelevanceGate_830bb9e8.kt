package com.example.data.repository

import com.example.data.ai.BilingualSearchTranslationEngine
import com.example.data.model.Product
import java.text.Normalizer
import java.util.Locale

/**
 * Resultado da validação do RelevanceGate.
 * Contém decisão binária (aprovado/rejeitado), motivo detalhado da rejeição e score de confiança.
 */
data class GateResult(
    val isApproved: Boolean,
    val rejectionReason: String? = null,
    val matchConfidence: Int = 0
)

/**
 * Filtro único, rigoroso e determinístico de relevância para o ScanShop.
 *
 * Regra de Ouro: É preferível mostrar menos resultados ou nenhum a mostrar produtos errados.
 *
 * Avalia:
 * 1. Normalização bilíngue profunda (PT-BR e EN)
 * 2. Eliminação estrita de lixo, falsos amigos e acessórios não solicitados
 * 3. Validação de conflito de gênero (masculino/feminino/infantil)
 * 4. Obrigatoriedade de marca detectada (ou seus aliases autorizados)
 * 5. Obrigatoriedade e não-conflito de modelo/código alfanumérico (ex: X99, B450, iPhone 13, G502)
 * 6. Obrigatoriedade de unidades, especificações e tamanhos citados (ex: 43 polegadas, 128gb, ddr4, 144hz)
 * 7. Obrigatoriedade de indicadores reais da categoria detectada
 * 8. Guarda de preço mínimo (minPriceGuard) contra peças avulsas
 * 9. Cobertura completa de termos para buscas genéricas (todas as palavras significativas)
 */
object RelevanceGate {

    private val STOP_WORDS: Set<String> = setOf(
        "de", "da", "do", "das", "dos", "para", "pra", "com", "sem", "e", "em", "no", "na",
        "nos", "nas", "por", "um", "uma", "uns", "umas", "o", "a", "os", "as", "se", "ou"
    )

    private val SPURIOUS_JUNK: List<String> = listOf(
        "moletom", "vestido", "calcinha", "sutia", "barco", "carburador", "rele de guarnicao",
        "motor de barco", "boneca", "pelucia", "motocicleta", "compactador de placas"
    )

    /**
     * Avalia um produto completo contra a busca e a intenção analisada.
     */
    fun evaluate(product: Product, query: String, intent: QueryIntent): GateResult {
        return evaluate(
            title = product.title,
            price = product.promoPrice,
            query = query,
            intent = intent
        )
    }

    /**
     * Avalia título e preço contra a busca e a intenção analisada.
     */
    fun evaluate(
        title: String,
        price: Double = 0.0,
        query: String,
        intent: QueryIntent
    ): GateResult {
        val normTitle = canonUnits(normalize(title))
        val normQuery = canonUnits(normalize(if (query.isNotBlank()) query else intent.cleanedQuery))
        val compactTitle = normTitle.replace("[^a-z0-9]".toRegex(), "")
        val compactQuery = normQuery.replace("[^a-z0-9]".toRegex(), "")

        if (normTitle.isBlank()) {
            return GateResult(false, "Título vazio ou inválido")
        }

        // 1. Rejeição de lixo irrelevante absoluto
        for (junk in SPURIOUS_JUNK) {
            if (normTitle.contains(junk) && !normQuery.contains(junk)) {
                return GateResult(false, "Produto descartado como item espúrio ('$junk')")
            }
        }

        // 2. Falsos Amigos de Tradução da Categoria (BilingualSearchTranslationEngine)
        // Só vale quando a busca NÃO pediu um acessório (ex.: "suporte celular carro", "mousepad gamer").
        val queryWantsAccessory = isAccessoryIntent(normQuery)
        if (!queryWantsAccessory &&
            BilingualSearchTranslationEngine.isFalseFriendAccessory(normTitle, intent.detectedCategory)) {
            return GateResult(false, "Acessório ou peça detectada como falso amigo para a categoria")
        }

        // 2.5. Regra universal do "para": o produto principal é o que vem ANTES de "para/compatível com".
        // Se nada da busca aparece antes do marcador, o título é de acessório/peça/refil (Tampa para Panela,
        // Alça para Bolsa, Filtro para Robô Aspirador...). Funciona para qualquer produto, sem lista por categoria.
        if (isAccessoryByParaRule(normTitle, normQuery)) {
            return GateResult(false, "Acessório/peça: o produto principal antes de 'para' não corresponde à busca")
        }

        // 3. Validação de Acessórios e Peças Avulsas
        // Se a busca NÃO pediu acessório, rejeita quando o título for de acessório/peça.
        // "Com controle remoto", "com dissipador" etc. são itens inclusos, não acessório: ficam fora da checagem.
        val accTitle = stripIncludedAccessories(normTitle)
        if (!queryWantsAccessory) {
            // Checa acessórios específicos da categoria
            for (acc in intent.accessoryFilterTerms) {
                val normAcc = normalize(acc)
                if (normAcc.isNotBlank() && containsWordOrPhrase(accTitle, normAcc) && !containsWordOrPhrase(normQuery, normAcc)) {
                    if (!isFalsePositiveAccessory(normTitle, normAcc, intent) && !isDescriptorNotAccessory(normTitle, normAcc, normQuery)) {
                        return GateResult(false, "Acessório/peça da categoria não solicitado ('$acc')")
                    }
                }
            }

            // Checa acessórios universais
            for (acc in QueryIntentEngine.UNIVERSAL_ACCESSORIES) {
                val normAcc = normalize(acc)
                if (normAcc.isNotBlank() && containsWordOrPhrase(accTitle, normAcc) && !containsWordOrPhrase(normQuery, normAcc)) {
                    if (!isFalsePositiveAccessory(normTitle, normAcc, intent) && !isDescriptorNotAccessory(normTitle, normAcc, normQuery)) {
                        return GateResult(false, "Acessório universal não solicitado ('$acc')")
                    }
                }
            }
        }

        // 4. Conflito de Gênero
        val genderConflict = checkGenderConflict(normQuery, normTitle)
        if (genderConflict != null) {
            return GateResult(false, genderConflict)
        }

        // 4.5. Pares Opostos de Conexão e Tecnologia
        val queryHasWireless = listOf("sem fio", "sem-fio", "semfio", "wireless", "bluetooth").any { containsWordOrPhrase(normQuery, it) }
        val queryHasWired = listOf("com fio", "com-fio", "wired", "fio usb", "cabo usb", "p2").any { containsWordOrPhrase(normQuery, it) }
        val titleHasWireless = listOf("sem fio", "sem-fio", "semfio", "wireless", "bluetooth", "2.4g", "tws").any { containsWordOrPhrase(normTitle, it) }
        val titleHasWired = listOf("com fio", "com-fio", "wired", "fio usb", "cabo usb", "p2").any { containsWordOrPhrase(normTitle, it) }

        if (queryHasWireless && titleHasWired && !titleHasWireless) {
            return GateResult(false, "Conflito de conexão: busca pediu sem fio/wireless/bluetooth, mas título indica produto com fio")
        }
        if (queryHasWired && titleHasWireless && !titleHasWired) {
            return GateResult(false, "Conflito de conexão: busca pediu produto com fio, mas título indica sem fio")
        }

        val queryHasMechanical = listOf("mecanico", "mecanica", "mechanical").any { containsWordOrPhrase(normQuery, it) }
        val queryHasMembrane = listOf("membrana", "membrane").any { containsWordOrPhrase(normQuery, it) }
        val titleHasMechanical = listOf("mecanico", "mecanica", "mechanical").any { containsWordOrPhrase(normTitle, it) }
        val titleHasMembrane = listOf("membrana", "membrane").any { containsWordOrPhrase(normTitle, it) }

        if (queryHasMechanical && titleHasMembrane && !titleHasMechanical) {
            return GateResult(false, "Conflito de tecnologia: busca pediu teclado mecânico, mas título indica membrana")
        }
        if (queryHasMembrane && titleHasMechanical && !queryHasMechanical) {
            return GateResult(false, "Conflito de tecnologia: busca pediu membrana, mas título indica mecânico")
        }

        // 5. Validação de Marca (com exceção para marca deduzida por chip/modelo)
        val detectedBrand = intent.detectedBrand
        val detectedModel = intent.detectedHardwareModel
        val queryModelTokens = extractModelTokens(normQuery)
        val targetModels = if (!detectedModel.isNullOrBlank()) {
            (listOf(detectedModel) + queryModelTokens).distinct()
        } else {
            queryModelTokens
        }

        if (!detectedBrand.isNullOrBlank()) {
            val normBrand = normalize(detectedBrand)

            // Verifica se o usuário digitou explicitamente a marca na busca
            val userTypedBrandExplicitly = containsWordOrPhrase(normQuery, normBrand) ||
                    (normBrand == "apple" && containsWordOrPhrase(normQuery, "apple")) ||
                    (normBrand == "samsung" && containsWordOrPhrase(normQuery, "samsung")) ||
                    (normBrand == "xiaomi" && containsWordOrPhrase(normQuery, "xiaomi")) ||
                    (normBrand == "intel" && containsWordOrPhrase(normQuery, "intel")) ||
                    (normBrand == "amd" && containsWordOrPhrase(normQuery, "amd")) ||
                    (normBrand == "nvidia" && containsWordOrPhrase(normQuery, "nvidia"))

            // Se a marca foi deduzida de um modelo/chip (RTX/GTX -> NVIDIA; RX/Radeon/Ryzen -> AMD; etc.)
            val isDeducedBrandFromModel = !userTypedBrandExplicitly && isBrandDeducedFromModel(normQuery, normBrand)

            val modelPresentInTitle = targetModels.any { modelToken ->
                val normModel = normalize(modelToken)
                val compactModel = normModel.replace("[^a-z0-9]".toRegex(), "")
                containsWordOrPhrase(normTitle, normModel) || (compactModel.length >= 2 && compactTitle.contains(compactModel))
            } || containsWordOrPhrase(normTitle, "rtx") || containsWordOrPhrase(normTitle, "gtx") ||
               containsWordOrPhrase(normTitle, "rx") || containsWordOrPhrase(normTitle, "ryzen") ||
               containsWordOrPhrase(normTitle, "xeon") || containsWordOrPhrase(normTitle, "iphone") ||
               containsWordOrPhrase(normTitle, "galaxy") || containsWordOrPhrase(normTitle, "redmi") ||
               containsWordOrPhrase(normTitle, "poco")

            val skipBrandRequirement = isDeducedBrandFromModel && modelPresentInTitle

            if (!skipBrandRequirement) {
                val brandAliases = (intent.brandAliases + (QueryIntentEngine.KNOWN_BRANDS[normBrand] ?: emptySet()) + setOf(normBrand))
                    .map { normalize(it) }
                    .toSet()

                val brandMatched = brandAliases.any { alias ->
                    containsWordOrPhrase(normTitle, alias) || compactTitle.contains(alias.replace("[^a-z0-9]".toRegex(), ""))
                }

                if (!brandMatched) {
                    return GateResult(false, "Marca '$detectedBrand' não encontrada no título")
                }
            }

            // Sempre verifica se uma marca concorrente foi colocada no título
            val brandAliasesForConflict = (intent.brandAliases + (QueryIntentEngine.KNOWN_BRANDS[normBrand] ?: emptySet()) + setOf(normBrand))
                .map { normalize(it) }
                .toSet()
            val conflictingBrand = findConflictingBrand(normTitle, normBrand, brandAliasesForConflict)
            if (conflictingBrand != null) {
                return GateResult(false, "Marca conflitante detectada ('$conflictingBrand') em vez de '$detectedBrand'")
            }
        }

        // 6. Validação de Modelo / Código Alfanumérico
        for (modelToken in targetModels) {
            val normModel = normalize(modelToken)
            val compactModel = normModel.replace("[^a-z0-9]".toRegex(), "")
            if (compactModel.length >= 2) {
                val modelFound = containsWordOrPhrase(normTitle, normModel) || compactTitle.contains(compactModel)
                if (!modelFound) {
                    return GateResult(false, "Modelo '$modelToken' pesquisado não encontrado no título")
                }

                val modelConflict = checkModelConflict(normQuery, normTitle, normModel)
                if (modelConflict != null) {
                    return GateResult(false, modelConflict)
                }
            }
        }

        // 7. Validação de Números com Unidade ou Tamanho (ex: 43 polegadas, 16gb, 1tb, 144hz, 6 xícaras)
        val unitSpecs = extractUnitSpecs(normQuery)
        for (spec in unitSpecs) {
            val normSpec = normalize(spec)
            val compactSpec = normSpec.replace("[^a-z0-9]".toRegex(), "")
            val specFound = containsWordOrPhrase(normTitle, normSpec) || compactTitle.contains(compactSpec)
            if (!specFound) {
                val altFound = checkUnitEquivalence(normTitle, normSpec)
                if (!altFound) {
                    return GateResult(false, "Especificação ou tamanho '$spec' não encontrado no título")
                }
            }
        }

        // 8. Validação de Categoria e Indicadores Obrigatórios
        val detectedCat = intent.detectedCategory
        if (!detectedCat.isNullOrBlank()) {
            if (detectedCat == "smartwatch" || normQuery.contains("smartwatch") || normQuery.contains("smart watch")) {
                val smartWatchExplicit = listOf("smartwatch", "smart watch", "relogio inteligente", "smartband", "smart band")
                    .any { containsWordOrPhrase(normTitle, it) || compactTitle.contains(it.replace(" ", "")) }

                val hasWatchBase = containsWordOrPhrase(normTitle, "relogio") || containsWordOrPhrase(normTitle, "watch")
                val smartMarkers = listOf(
                    "bluetooth", "chamada", "chamadas", "nfc", "gps", "fitness", "monitor cardiaco", "cardiaco",
                    "amoled", "android", "ios", "notificac", "notificacao", "passos", "esporte", "tracker",
                    "pressao", "oximetro", "spo2", "app", "aplicativo", "touch"
                )
                val hasSmartMarker = smartMarkers.any { containsWordOrPhrase(normTitle, it) }

                if (!smartWatchExplicit && !(hasWatchBase && hasSmartMarker)) {
                    return GateResult(false, "Relógio tradicional/analógico não aceito para busca de smartwatch")
                }
            } else {
                val requiredIndicators = intent.requiredCategoryIndicators
                if (requiredIndicators.isNotEmpty()) {
                    val hasIndicator = requiredIndicators.any { ind ->
                        val normInd = normalize(ind)
                        containsWordOrPhrase(normTitle, normInd) || compactTitle.contains(normInd.replace("[^a-z0-9]".toRegex(), ""))
                    }
                    if (!hasIndicator) {
                        return GateResult(false, "Nenhum indicador da categoria '$detectedCat' encontrado no título")
                    }
                }
            }
        }

        // 9. minPriceGuard (Guarda contra peças e miudezas em categorias caras)
        if (intent.minPriceGuard > 0.0 && price > 0.0) {
            val threshold = intent.minPriceGuard * 0.40
            if (price < threshold) {
                val partKeywords = listOf("peca", "reposicao", "parafuso", "modulo", "conector", "espelho", "tpm")
                if (partKeywords.any { normTitle.contains(it) }) {
                    return GateResult(false, "Preço (R$ $price) muito abaixo do mínimo esperado para a categoria (R$ ${intent.minPriceGuard}) com indicação de peça avulsa")
                }
            }
        }

        // 10. Qualificadores Obrigatórios (Estendido para todas as buscas)
        val remainingQualifiers = extractRemainingQualifiers(normQuery, intent, targetModels, unitSpecs)
        for (qualifier in remainingQualifiers) {
            // O título "compacto" (sem espaços) só vale para termos compostos ou longos; em termos curtos
            // ele gera falso positivo (ex.: "aaa" dentro de "pilha aa alcalina" -> "pilhaaalcalina").
            val compactAllowed = qualifier.contains(" ") || qualifier.length >= 6
            val satisfied = tokenOk(qualifier, normTitle) ||
                    containsWordOrPhrase(normTitle, qualifier) ||
                    (compactAllowed && compactTitle.contains(qualifier.replace(" ", "")))
            if (!satisfied) {
                return GateResult(
                    false,
                    "Qualificador obrigatório '$qualifier' da busca não encontrado no título"
                )
            }
        }

        return GateResult(
            isApproved = true,
            matchConfidence = 95
        )
    }

    /**
     * Remove acentos e converte para minúsculas.
     */
    fun normalize(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[_\\-/(),;!?\\[\\]{}]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    /**
     * Verifica se uma palavra ou frase inteira está contida no texto.
     */
    private fun containsWordOrPhrase(text: String, phrase: String): Boolean {
        if (phrase.isBlank()) return true
        if (phrase.contains(" ")) {
            return text.contains(phrase)
        }
        val pattern = "\\b${Regex.escape(phrase)}\\b"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) || text.contains(phrase)
    }

    /**
     * Identifica se a própria consulta do usuário é por um acessório.
     */
    private fun isAccessoryIntent(normQuery: String): Boolean {
        val accessoryMarkers = listOf(
            "capa", "capinha", "case", "pelicula", "suporte", "pulseira", "cabo",
            "carregador", "fone para", "mousepad", "tapete", "skin", "almofada",
            "espelho", "tpm", "adaptador"
        )
        return accessoryMarkers.any { containsWordOrPhrase(normQuery, it) }
    }

    /**
     * Evita falso positivo de acessório quando um produto real menciona inclusões/atributos técnicos.
     * Ex: 'Mouse Gamer ... com cabo trançado' ou 'Placa Mãe ... suporte a LGA 2011'
     */
    private fun isFalsePositiveAccessory(normTitle: String, normAcc: String, intent: QueryIntent): Boolean {
        val isMainNoun = normTitle.startsWith(normAcc) ||
                normTitle.startsWith("$normAcc ") ||
                normTitle.contains("$normAcc para ") ||
                normTitle.contains("$normAcc de ") ||
                normTitle.contains("$normAcc p/ ")

        // Se o acessório for o substantivo principal da frase, NUNCA é falso positivo (é acessório real)
        if (isMainNoun) {
            return false
        }

        // 1. Memória RAM com dissipador
        if (normAcc in listOf("dissipador", "dissipadores", "heatsink")) {
            val hasRamEvidence = (normTitle.contains("ram") || normTitle.contains("memoria") || normTitle.contains("ddr")) &&
                    (normTitle.contains("ddr3") || normTitle.contains("ddr4") || normTitle.contains("ddr5") || normTitle.contains("dimm"))
            if (hasRamEvidence) {
                return true
            }
        }

        // 2. Placa de vídeo com ventoinha / fan / cooler / dual fan
        if (normAcc in listOf("ventoinha", "ventoinhas", "fan", "fans", "cooler", "coolers", "ventilador")) {
            val hasGpuEvidence = (normTitle.contains("rtx") || normTitle.contains("gtx") || normTitle.contains("rx ") ||
                    normTitle.contains("placa de video") || normTitle.contains("placa grafica") || normTitle.contains("gpu")) &&
                    (normTitle.contains("gb") || normTitle.contains("gddr"))
            if (hasGpuEvidence) {
                return true
            }
        }

        // 3. Mouses e Periféricos com cabo, switch, grip tape, skates ou pezinhos
        if (normAcc.contains("cabo") || normAcc.contains("fio") || normAcc.contains("switch") ||
            normAcc.contains("grip tape") || normAcc.contains("tape") || normAcc.contains("skate") ||
            normAcc.contains("pezinho")) {
            val isMainProduct = intent.requiredCategoryIndicators.any { containsWordOrPhrase(normTitle, it) } ||
                    normTitle.contains("mouse") || normTitle.contains("teclado")
            val isAccessoryFirst = normTitle.startsWith(normAcc) || normTitle.contains("$normAcc para") || normTitle.contains("$normAcc de")
            if (isMainProduct && !isAccessoryFirst) {
                return true
            }
        }

        // 4. Placas-mãe, Monitores ou TVs mencionando suporte técnico (ex: suporte a LGA, suporte VESA)
        if (normAcc in listOf("suporte", "suportes")) {
            val isSupportSpec = normTitle.contains("suporte a") ||
                    normTitle.contains("suporte ddr") ||
                    normTitle.contains("suporte lga") ||
                    normTitle.contains("suporte vesa") ||
                    normTitle.contains("suporte ajustavel") ||
                    normTitle.contains("com suporte")
            val isMainProduct = intent.requiredCategoryIndicators.any { containsWordOrPhrase(normTitle, it) } ||
                    normTitle.contains("placa mae") || normTitle.contains("monitor") || normTitle.contains("tv")
            if (isSupportSpec && isMainProduct && !normTitle.startsWith("suporte ") && !normTitle.contains("suporte de ") && !normTitle.contains("suporte para ")) {
                return true
            }
        }

        // 5. Produtos com adaptador incluso
        if (normAcc in listOf("adaptador", "adaptadores")) {
            val isAdapterIncluded = normTitle.contains("com adaptador") || normTitle.contains("adaptador incluso")
            val isMainProduct = intent.requiredCategoryIndicators.any { containsWordOrPhrase(normTitle, it) }
            if (isAdapterIncluded && isMainProduct && !normTitle.startsWith("adaptador ") && !normTitle.contains("adaptador para ")) {
                return true
            }
        }

        return false
    }

    /**
     * Valida conflito de gênero entre a busca e o título.
     */
    private fun checkGenderConflict(normQuery: String, normTitle: String): String? {
        val queryMale = containsWordOrPhrase(normQuery, "masculino") || containsWordOrPhrase(normQuery, "homem")
        val queryFemale = containsWordOrPhrase(normQuery, "feminino") || containsWordOrPhrase(normQuery, "mulher")
        val queryChild = containsWordOrPhrase(normQuery, "infantil") || containsWordOrPhrase(normQuery, "menino") || containsWordOrPhrase(normQuery, "menina")

        val titleMale = containsWordOrPhrase(normTitle, "masculino")
        val titleFemale = containsWordOrPhrase(normTitle, "feminino")
        val titleChild = containsWordOrPhrase(normTitle, "infantil")

        if (queryMale && titleFemale && !titleMale) {
            return "Conflito de gênero: pesquisado 'masculino', encontrado 'feminino'"
        }
        if (queryFemale && titleMale && !titleFemale) {
            return "Conflito de gênero: pesquisado 'feminino', encontrado 'masculino'"
        }
        if (queryChild && (titleMale || titleFemale) && !titleChild) {
            return "Conflito de gênero: pesquisado 'infantil', encontrado produto adulto"
        }
        return null
    }

    /**
     * Detecta marcas conflitantes.
     */
    private fun findConflictingBrand(normTitle: String, searchedBrand: String, searchedAliases: Set<String>): String? {
        val majorBrands = listOf("samsung", "apple", "lg", "xiaomi", "motorola", "huawei", "sony", "asus", "dell", "lenovo", "acer", "nike", "adidas", "logitech", "redragon")
        for (other in majorBrands) {
            if (other != searchedBrand && !searchedAliases.contains(other)) {
                if (containsWordOrPhrase(normTitle, other)) {
                    // Confirma se a marca pesquisada realmente não está presente
                    val searchedFound = searchedAliases.any { containsWordOrPhrase(normTitle, it) }
                    if (!searchedFound) {
                        return other
                    }
                }
            }
        }
        return null
    }

    /**
     * Extrai tokens de modelos alfanuméricos da query (ex: X99, B450, RTX 4060, iPhone 13, G502, S23, S24).
     */
    private fun extractModelTokens(normQuery: String): List<String> {
        val tokens = mutableListOf<String>()

        // 1. Padrões específicos de hardware e celulares
        val regexes = listOf(
            "\\b(iphone\\s*\\d{1,2}(?:\\s*(?:pro|max|plus|mini))?)\\b".toRegex(),
            "\\b(rtx\\s*\\d{3,4}(?:\\s*ti)?)\\b".toRegex(),
            "\\b(gtx\\s*\\d{3,4}(?:\\s*ti)?)\\b".toRegex(),
            "\\b(rx\\s*\\d{3,4}(?:\\s*xt)?)\\b".toRegex(),
            "\\b([xbahz]\\d{2,3}[a-z]?)\\b".toRegex(), // x99, b450, b550, h610, a320, z790
            "\\b(g\\d{3,4})\\b".toRegex(), // g502, g305, g203
            "\\b(s\\d{2,3}(?:\\s*(?:ultra|plus|fe))?)\\b".toRegex(), // s23, s24
            "\\b(poco\\s*[xmf]\\d+)\\b".toRegex(), // poco x6, poco m6
            "\\b(redmi\\s*note\\s*\\d+)\\b".toRegex(),
            "\\b(air\\s*max(?:\\s*\\d+)?)\\b".toRegex()
        )

        for (regex in regexes) {
            val matches = regex.findAll(normQuery)
            for (m in matches) {
                tokens.add(m.value.trim())
            }
        }

        // 2. Termos alfanuméricos isolados que contêm dígitos e letras (ex: x99, b450)
        val rawTokens = normQuery.split("\\s+".toRegex())
        for (t in rawTokens) {
            // Ignora capacidades de memória/armazenamento e frequências/tamanhos (ex: 16gb, 1tb, 144hz, 43pol, 4k)
            if (t.matches("\\d+(?:gb|tb|mb|hz|pol|k|w|v|mah|g|xicaras?)".toRegex())) {
                continue
            }
            if (t.any { it.isDigit() } && t.any { it.isLetter() } && t.length >= 3) {
                tokens.add(t)
            }
        }

        return tokens.distinct()
    }

    /**
     * Detecta conflito de modelos da mesma linha (ex: pesquisou iPhone 13 e encontrou iPhone 12 ou 14).
     */
    private fun checkModelConflict(normQuery: String, normTitle: String, normModel: String): String? {
        // iPhone check:
        val iphoneQueryMatch = "iphone\\s*(\\d{1,2})".toRegex().find(normQuery)
        if (iphoneQueryMatch != null) {
            val queryNum = iphoneQueryMatch.groupValues[1]
            val iphoneTitleMatch = "iphone\\s*(\\d{1,2})".toRegex().find(normTitle)
            if (iphoneTitleMatch != null) {
                val titleNum = iphoneTitleMatch.groupValues[1]
                if (titleNum != queryNum && !normTitle.contains("iphone $queryNum") && !normTitle.contains("iphone$queryNum")) {
                    return "Modelo iPhone divergente: pesquisado iPhone $queryNum, encontrado iPhone $titleNum"
                }
            }
        }

        // Chipset de Placa Mãe check: (ex: x99 vs b450)
        val moboChips = listOf("x99", "x79", "b450", "b550", "b650", "a320", "a520", "h610", "b760", "z790")
        val searchedMobo = moboChips.firstOrNull { normQuery.contains(it) }
        if (searchedMobo != null) {
            val titleMobo = moboChips.firstOrNull { normTitle.contains(it) }
            if (titleMobo != null && titleMobo != searchedMobo) {
                return "Chipset de placa-mãe divergente: pesquisado $searchedMobo, encontrado $titleMobo"
            }
        }

        // Processadores Intel (ex: i5 12400 vs i3 10100)
        val intelCoreMatchQuery = "\\b(i3|i5|i7|i9)\\b".toRegex().find(normQuery)
        val intelCoreMatchTitle = "\\b(i3|i5|i7|i9)\\b".toRegex().find(normTitle)
        if (intelCoreMatchQuery != null && intelCoreMatchTitle != null && intelCoreMatchQuery.value != intelCoreMatchTitle.value) {
            return "Família de processador divergente: pesquisado ${intelCoreMatchQuery.value}, encontrado ${intelCoreMatchTitle.value}"
        }

        // AirPods (ex: airpods pro 2 vs airpods 3)
        if (normQuery.contains("airpods")) {
            val airpodsProQuery = normQuery.contains("pro")
            val airpodsProTitle = normTitle.contains("pro")
            if (airpodsProQuery && !airpodsProTitle) {
                return "Modelo AirPods divergente: pesquisado AirPods Pro, encontrado modelo comum"
            }
        }

        return null
    }

    /**
     * Extrai especificações numéricas com unidade (ex: 43 polegadas, 128gb, ddr4, 144hz, 6 xicaras).
     */
    private fun extractUnitSpecs(normQuery: String): List<String> {
        val specs = mutableListOf<String>()
        val regexes = listOf(
            "\\b(\\d{1,3}\\s*(?:polegadas?|pol|\"))\\b".toRegex(),
            "\\b(\\d{1,4}\\s*(?:gb|tb|mb))\\b".toRegex(),
            "\\b(\\d{2,4}\\s*(?:hz))\\b".toRegex(),
            "\\b(ddr[345])\\b".toRegex(),
            "\\b(\\d{1,2}\\s*(?:xicaras?|xicara))\\b".toRegex(),
            "\\b(\\d{1,3}(?:[.,]\\d)?\\s*(?:litro|metro))\\b".toRegex(),
            "\\b(4k|8k|full\\s*hd)\\b".toRegex()
        )

        for (regex in regexes) {
            val matches = regex.findAll(normQuery)
            for (m in matches) {
                specs.add(m.value.trim())
            }
        }

        // Se pesquisou "smart tv 43", captura "43" como especificação de tamanho
        if (normQuery.contains("tv") || normQuery.contains("smartv") || normQuery.contains("monitor")) {
            val tvSizeMatch = "\\b(32|40|42|43|50|55|58|60|65|70|75|85)\\b".toRegex().find(normQuery)
            if (tvSizeMatch != null) {
                specs.add(tvSizeMatch.value)
            }
        }

        return specs.distinct()
    }

    /**
     * Verifica equivalências de unidades como 43 polegadas <-> 43" <-> 43 pol, e capacidades de memória/armazenamento.
     */
    private fun checkUnitEquivalence(normTitle: String, spec: String): Boolean {
        if (spec.contains("gb") || spec.contains("tb") || spec.contains("g")) {
            if (checkCapacityEquivalence(normTitle, spec)) {
                return true
            }
        }

        val numberMatch = "\\d+".toRegex().find(spec) ?: return false
        val num = numberMatch.value
        val patterns = listOf(
            "$num polegadas", "$num polegada", "$num pol", "$num\"", "$num \"",
            "${num}pol", "$num-polegadas", "tamanho $num"
        )
        return patterns.any { normTitle.contains(it) } || containsWordOrPhrase(normTitle, num)
    }

    /**
     * Verifica equivalências de capacidade (ex: 16gb aceita 16G, 16 GB, e kits 2x8GB).
     */
    private fun checkCapacityEquivalence(normTitle: String, spec: String): Boolean {
        val cleanSpec = spec.lowercase(Locale.ROOT).replace("\\s+".toRegex(), "")

        if (cleanSpec == "1tb" || cleanSpec == "1000gb" || cleanSpec == "1024gb") {
            val tbPatterns = listOf(
                "\\b1\\s*tb\\b".toRegex(),
                "\\b1\\s*t\\b".toRegex(),
                "\\b1000\\s*gb?\\b".toRegex(),
                "\\b1024\\s*gb?\\b".toRegex(),
                "\\b1\\s*tera\\b".toRegex()
            )
            return tbPatterns.any { it.containsMatchIn(normTitle) }
        }

        val gbMatch = "(\\d+)\\s*g[b]?".toRegex().find(cleanSpec) ?: return false
        val targetGb = gbMatch.groupValues[1].toIntOrNull() ?: return false

        // 1. Notação direta: 16gb, 16 gb, 16g, 16 g
        val directPatterns = listOf(
            "\\b$targetGb\\s*gb\\b".toRegex(),
            "\\b$targetGb\\s*g\\b".toRegex(),
            "\\b$targetGb\\s*gigas?\\b".toRegex()
        )
        if (directPatterns.any { it.containsMatchIn(normTitle) }) {
            return true
        }

        // 2. Kits: 2x8GB, 2x8G, 2 x 8 gb, 4x4GB, etc.
        val kitRegex = "(\\d+)\\s*[x*]\\s*(\\d+)\\s*g[b]?".toRegex()
        for (m in kitRegex.findAll(normTitle)) {
            val count = m.groupValues[1].toIntOrNull() ?: continue
            val each = m.groupValues[2].toIntOrNull() ?: continue
            if (count * each == targetGb) {
                return true
            }
        }

        return false
    }

    /**
     * Identifica se a marca foi deduzida a partir de um chip ou modelo na busca.
     */
    private fun isBrandDeducedFromModel(normQuery: String, normBrand: String): Boolean {
        return when (normBrand) {
            "nvidia" -> listOf("rtx", "gtx", "geforce").any { containsWordOrPhrase(normQuery, it) }
            "amd" -> listOf("rx", "radeon", "ryzen").any { containsWordOrPhrase(normQuery, it) }
            "intel" -> listOf("core i", "i3", "i5", "i7", "i9", "xeon").any { containsWordOrPhrase(normQuery, it) }
            "apple" -> listOf("iphone", "airpods", "ipad", "macbook").any { containsWordOrPhrase(normQuery, it) }
            "samsung" -> listOf("galaxy").any { containsWordOrPhrase(normQuery, it) }
            "xiaomi" -> listOf("redmi", "poco", "mi band").any { containsWordOrPhrase(normQuery, it) }
            else -> false
        }
    }

    /**
     * Extrai termos qualificadores obrigatórios que sobram na busca após remover marca, modelo, categoria e unidade.
     */
    private fun extractRemainingQualifiers(
        normQuery: String,
        intent: QueryIntent,
        targetModels: List<String>,
        unitSpecs: List<String>
    ): List<String> {
        var q = normQuery

        val qualifiers = mutableListOf<String>()
        val compoundQualifiers = listOf(
            "sem fio", "sem-fio", "com fio", "com-fio",
            "micro switch", "grip tape", "ar condicionado"
        )
        for (compound in compoundQualifiers) {
            if (q.contains(compound)) {
                qualifiers.add(compound)
                q = q.replace(compound, " ")
            }
        }

        for (spec in unitSpecs) {
            val normSpec = normalize(spec)
            q = q.replace(normSpec, " ")
            val numOnly = "\\d+".toRegex().find(normSpec)?.value
            if (numOnly != null) {
                q = q.replace("\\b$numOnly\\b".toRegex(), " ")
            }
        }

        for (m in targetModels) {
            val normModel = normalize(m)
            q = q.replace(normModel, " ")
            for (part in normModel.split("\\s+".toRegex())) {
                if (part.length >= 2) {
                    q = q.replace("\\b${Regex.escape(part)}\\b".toRegex(), " ")
                }
            }
        }

        val detectedBrand = intent.detectedBrand
        if (!detectedBrand.isNullOrBlank()) {
            val normBrand = normalize(detectedBrand)
            q = q.replace("\\b${Regex.escape(normBrand)}\\b".toRegex(), " ")
            for (alias in intent.brandAliases) {
                val normAlias = normalize(alias)
                q = q.replace("\\b${Regex.escape(normAlias)}\\b".toRegex(), " ")
            }
        }

        val detectedCat = intent.detectedCategory
        if (!detectedCat.isNullOrBlank()) {
            val catWords = when (detectedCat) {
                "mouse" -> listOf("mouse", "mouses", "rato", "mice")
                "teclado" -> listOf("teclado", "teclados", "keyboard")
                "fone" -> listOf("fone", "fones", "headset", "headphone", "earbuds", "earphones", "tws", "ouvido")
                "smartwatch" -> listOf("smartwatch", "smart watch", "relogio", "watch", "smartband", "band", "inteligente")
                "smart tv" -> listOf("smart tv", "smarttv", "smartv", "tv", "televisao", "televisor", "television", "smart")
                "monitor" -> listOf("monitor", "monitores", "display", "screen", "pc", "computador", "desktop")
                "celular" -> listOf("celular", "celulares", "smartphone", "smartphones", "telefone", "phone")
                "notebook" -> listOf("notebook", "notebooks", "laptop", "laptops")
                "cadeira" -> listOf("cadeira", "cadeiras", "poltrona")
                "placa mae" -> listOf("placa mae", "placa-mae", "motherboard", "mainboard", "mobo", "placa", "mae")
                "placa de video" -> listOf("placa de video", "placa grafica", "gpu", "video", "placa", "grafica", "rtx", "gtx", "rx", "radeon")
                "processador" -> listOf("processador", "cpu", "processadores", "intel", "amd")
                "memoria ram" -> listOf("memoria ram", "memória ram", "memoria", "memória", "ram", "ddr3", "ddr4", "ddr5")
                "ssd" -> listOf("ssd", "nvme", "m.2", "disco solido")
                "gabinete" -> listOf("gabinete", "chassi", "case")
                "cooler" -> listOf("cooler", "coolers", "refrigeracao")
                "fonte" -> listOf("fonte", "psu", "power supply")
                "camera" -> listOf("camera", "câmera", "cam")
                "drone" -> listOf("drone", "quadcopter")
                "projetor" -> listOf("projetor", "projector")
                "tablet" -> listOf("tablet", "ipad", "pad")
                else -> listOf(detectedCat)
            }
            for (cw in catWords) {
                val normCw = normalize(cw)
                if (normCw.contains(" ")) {
                    q = q.replace(normCw, " ")
                } else {
                    q = q.replace("\\b${Regex.escape(normCw)}\\b".toRegex(), " ")
                }
            }
        }

        q = q.replace("\\b(masculino|feminino|homem|mulher|infantil|menino|menina)\\b".toRegex(), " ")

        val remainingTokens = q.split("\\s+".toRegex())
            .map { it.trim().replace("[^a-z0-9]".toRegex(), "") }
            .filter { it.length >= 2 && !STOP_WORDS.contains(it) }

        for (token in remainingTokens) {
            qualifiers.add(token)
        }

        return qualifiers.distinct()
    }

    // ------------------------------------------------------------------
    // Regras universais (valem para qualquer produto)
    // ------------------------------------------------------------------

    private val PARA_MARKER = Regex("\\b(para|pra|compativel com|compativel|adequado para|for)\\b")

    /** Palavras de marketing que podem vir antes do produto e não contam como "produto principal". */
    private val MARKETING_WORDS: Set<String> = setOf(
        "novo", "nova", "novos", "novas", "original", "quente", "oferta", "promocao", "presente", "presentes",
        "gift", "hot", "new", "venda", "frete", "gratis", "lancamento", "top", "melhor", "barato", "kit"
    )

    private val GENDER_WORDS: Set<String> = setOf(
        "masculino", "masculina", "feminino", "feminina", "homem", "mulher", "infantil", "menino", "menina"
    )

    /** Grupos extras de sinônimos que complementam QueryIntentEngine.SYNONYM_GROUPS. */
    private val EXTRA_SYNONYMS: List<Set<String>> = listOf(
        setOf("capa", "capinha", "capas", "capinhas", "case", "cover"),
        setOf("pelicula", "peliculas", "protetor de tela", "vidro temperado")
    )

    /**
     * Padroniza unidades para comparar busca e título: "1L", "1 lt", "1 litros" -> "1 litro";
     * "2m", "2 mts", "2 metros" -> "2 metro".
     */
    private fun canonUnits(text: String): String {
        var t = text
        t = Regex("\\b(\\d+(?:[.,]\\d+)?)\\s*(?:litros?|lts?|l)\\b").replace(t) { "${it.groupValues[1]} litro" }
        t = Regex("\\b(\\d+(?:[.,]\\d+)?)\\s*(?:metros?|mts?|m)\\b").replace(t) { "${it.groupValues[1]} metro" }
        return t
    }

    /** Singular simples em PT-BR: recarregaveis -> recarregavel, homens -> homem, pilhas -> pilha. */
    private fun singular(word: String): String {
        val w = word.trim()
        return when {
            w.length > 4 && w.endsWith("veis") -> w.dropLast(4) + "vel"
            w.length > 4 && w.endsWith("ais") -> w.dropLast(3) + "al"
            w.length > 4 && w.endsWith("oes") -> w.dropLast(3) + "ao"
            w.length > 3 && w.endsWith("ns") -> w.dropLast(2) + "m"
            w.length > 3 && w.endsWith("es") && !w.endsWith("ses") -> w.dropLast(1)
            w.length > 3 && w.endsWith("s") -> w.dropLast(1)
            else -> w
        }
    }

    /** True se o termo aparece no texto: exato, sinônimo, plural/singular ou sinônimo extra. */
    private fun tokenOk(token: String, text: String): Boolean {
        if (QueryIntentEngine.tokenSatisfied(token, text)) return true
        val words = text.split(" ").filter { it.isNotBlank() }
        val stem = singular(token)
        if (words.any { singular(it) == stem }) return true
        for (group in EXTRA_SYNONYMS) {
            if (group.any { it == token || singular(it) == stem }) {
                if (group.any { syn -> if (syn.contains(" ")) text.contains(syn) else words.any { singular(it) == singular(syn) } }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Um termo de acessório que aparece como descritor do produto principal não torna o título um acessório.
     * Ex.: busca "bateria externa 20000mah" e título "Power Bank 20000mAh Carregador Portátil Bateria Externa":
     * "carregador" descreve o produto, e a frase da busca ("bateria externa") aparece inteira no título.
     * Continua rejeitando quando o acessório é o substantivo principal (começa o título ou vem antes de "para/de").
     */
    private fun isDescriptorNotAccessory(normTitle: String, normAcc: String, normQuery: String): Boolean {
        if (normTitle.startsWith("$normAcc ") || normTitle.contains("$normAcc para ") || normTitle.contains("$normAcc de ")) {
            return false
        }
        val alphaTokens = extractSignificantTerms(normQuery)
            .filter { t -> t.all { it.isLetter() } && t !in GENDER_WORDS }
        if (alphaTokens.size < 2) return false
        return normTitle.contains(alphaTokens.joinToString(" "))
    }

    /** Remove expressões de itens inclusos ("com controle remoto", "com dissipador") antes da checagem de acessório. */
    private fun stripIncludedAccessories(normTitle: String): String {
        return Regex(
            "\\bcom (controle remoto|fonte|cabo|carregador|baterias?|adaptador|suporte|estojo|case|capa|dissipador|ventoinha|cooler|tripe|microfone)\\b"
        ).replace(normTitle, " ")
    }

    /**
     * Regra universal do "para": se o título tem "para/compatível com/for", o produto principal vem antes.
     * Quando nenhum termo da busca aparece nesse trecho, o título descreve acessório/peça/refil.
     */
    private fun isAccessoryByParaRule(normTitle: String, normQuery: String): Boolean {
        val m = PARA_MARKER.find(normTitle) ?: return false
        val prefixWords = normTitle.substring(0, m.range.first)
            .split(" ")
            .filter { it.isNotBlank() && it !in MARKETING_WORDS && !it.matches(Regex("\\d{4}")) }
        if (prefixWords.isEmpty()) return false

        val prefix = prefixWords.joinToString(" ")
        val tokens = extractSignificantTerms(normQuery).filter { it !in GENDER_WORDS }
        if (tokens.isEmpty()) return false

        return tokens.none { tokenOk(it, prefix) }
    }

    /**
     * Extrai termos significativos removendo palavras vazias.
     */
    private fun extractSignificantTerms(normQuery: String): List<String> {
        return normQuery.split("\\s+".toRegex())
            .map { it.trim() }
            .filter { it.length >= 2 && !STOP_WORDS.contains(it) }
    }
}
