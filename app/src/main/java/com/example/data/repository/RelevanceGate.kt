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
        val normTitle = normalize(title)
        val normQuery = normalize(if (query.isNotBlank()) query else intent.cleanedQuery)
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
        if (BilingualSearchTranslationEngine.isFalseFriendAccessory(normTitle, intent.detectedCategory)) {
            return GateResult(false, "Acessório ou peça detectada como falso amigo para a categoria")
        }

        // 3. Validação de Acessórios e Peças Avulsas
        // Se a busca NÃO pediu acessório, rejeita quando o título for de acessório/peça
        val queryWantsAccessory = isAccessoryIntent(normQuery)
        if (!queryWantsAccessory) {
            // Checa acessórios específicos da categoria
            for (acc in intent.accessoryFilterTerms) {
                val normAcc = normalize(acc)
                if (normAcc.isNotBlank() && containsWordOrPhrase(normTitle, normAcc) && !containsWordOrPhrase(normQuery, normAcc)) {
                    if (!isFalsePositiveAccessory(normTitle, normAcc, intent)) {
                        return GateResult(false, "Acessório/peça da categoria não solicitado ('$acc')")
                    }
                }
            }

            // Checa acessórios universais
            for (acc in QueryIntentEngine.UNIVERSAL_ACCESSORIES) {
                val normAcc = normalize(acc)
                if (normAcc.isNotBlank() && containsWordOrPhrase(normTitle, normAcc) && !containsWordOrPhrase(normQuery, normAcc)) {
                    if (!isFalsePositiveAccessory(normTitle, normAcc, intent)) {
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

        // 5. Validação Estrita de Marca
        val detectedBrand = intent.detectedBrand
        if (!detectedBrand.isNullOrBlank()) {
            val normBrand = normalize(detectedBrand)
            val brandAliases = (intent.brandAliases + (QueryIntentEngine.KNOWN_BRANDS[normBrand] ?: emptySet()))
                .map { normalize(it) }
                .toSet()

            val brandMatched = brandAliases.any { alias ->
                containsWordOrPhrase(normTitle, alias) || compactTitle.contains(alias.replace("[^a-z0-9]".toRegex(), ""))
            }

            if (!brandMatched) {
                return GateResult(false, "Marca '$detectedBrand' não encontrada no título")
            }

            // Verifica se uma marca concorrente foi colocada no título sem a marca solicitada
            val conflictingBrand = findConflictingBrand(normTitle, normBrand, brandAliases)
            if (conflictingBrand != null) {
                return GateResult(false, "Marca conflitante detectada ('$conflictingBrand') em vez de '$detectedBrand'")
            }
        }

        // 6. Validação de Modelo / Código Alfanumérico
        val detectedModel = intent.detectedHardwareModel
        val queryModelTokens = extractModelTokens(normQuery)
        val targetModels = if (!detectedModel.isNullOrBlank()) {
            (listOf(detectedModel) + queryModelTokens).distinct()
        } else {
            queryModelTokens
        }

        for (modelToken in targetModels) {
            val normModel = normalize(modelToken)
            val compactModel = normModel.replace("[^a-z0-9]".toRegex(), "")
            if (compactModel.length >= 2) {
                // O modelo deve aparecer no título (com ou sem pontuação/espaços)
                val modelFound = containsWordOrPhrase(normTitle, normModel) || compactTitle.contains(compactModel)
                if (!modelFound) {
                    return GateResult(false, "Modelo '$modelToken' pesquisado não encontrado no título")
                }

                // Verifica se há modelo conflitante da mesma família (ex: pesquisou x99 e veio b450; pesquisou iphone 13 e veio iphone 12)
                val modelConflict = checkModelConflict(normQuery, normTitle, normModel)
                if (modelConflict != null) {
                    return GateResult(false, modelConflict)
                }
            }
        }

        // 7. Validação de Números com Unidade ou Tamanho (ex: 43 polegadas, 128gb, 16gb, ddr4, 144hz, 6 xícaras)
        val unitSpecs = extractUnitSpecs(normQuery)
        for (spec in unitSpecs) {
            val normSpec = normalize(spec)
            val compactSpec = normSpec.replace("[^a-z0-9]".toRegex(), "")
            val specFound = containsWordOrPhrase(normTitle, normSpec) || compactTitle.contains(compactSpec)
            if (!specFound) {
                // Tenta mapear equivalências como 43" ou 43 pol
                val altFound = checkUnitEquivalence(normTitle, normSpec)
                if (!altFound) {
                    return GateResult(false, "Especificação ou tamanho '$spec' não encontrado no título")
                }
            }
        }

        // 8. Validação de Categoria e Indicadores Obrigatórios
        val detectedCat = intent.detectedCategory
        if (!detectedCat.isNullOrBlank()) {
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

        // 9. minPriceGuard (Guarda contra peças e miudezas em categorias caras)
        if (intent.minPriceGuard > 0.0 && price > 0.0) {
            val threshold = intent.minPriceGuard * 0.40
            if (price < threshold) {
                // Se o preço for muito baixo e o título tiver menção a peça, reposição ou componente avulso
                val partKeywords = listOf("peca", "reposicao", "parafuso", "modulo", "conector", "espelho", "tpm")
                if (partKeywords.any { normTitle.contains(it) }) {
                    return GateResult(false, "Preço (R$ $price) muito abaixo do mínimo esperado para a categoria (R$ ${intent.minPriceGuard}) com indicação de peça avulsa")
                }
            }
        }

        // 10. Busca Sem Marca, Categoria ou Modelo Detectados (ex: 'cafeteira italiana 6 xicaras')
        // Exige que TODOS os termos significativos apareçam no título (tolera 1 termo ausente apenas para 4+ termos)
        if (detectedBrand.isNullOrBlank() && detectedCat.isNullOrBlank() && targetModels.isEmpty()) {
            val significantTerms = extractSignificantTerms(normQuery)
            if (significantTerms.isNotEmpty()) {
                var missingCount = 0
                val missingTerms = mutableListOf<String>()

                for (term in significantTerms) {
                    val satisfied = QueryIntentEngine.tokenSatisfied(term, normTitle) ||
                            containsWordOrPhrase(normTitle, term) ||
                            compactTitle.contains(term)
                    if (!satisfied) {
                        missingCount++
                        missingTerms.add(term)
                    }
                }

                val maxToleratedMissing = if (significantTerms.size >= 4) 1 else 0
                if (missingCount > maxToleratedMissing) {
                    return GateResult(
                        false,
                        "Termos significativos ausentes no título: ${missingTerms.joinToString(", ")}"
                    )
                }
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
            .replace("[_\\-/]+".toRegex(), " ")
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
        if (normAcc.contains("cabo") || normAcc.contains("fio") || normAcc.contains("switch") ||
            normAcc.contains("grip tape") || normAcc.contains("tape") || normAcc.contains("skate") ||
            normAcc.contains("pezinho")) {
            val isMainProduct = intent.requiredCategoryIndicators.any { containsWordOrPhrase(normTitle, it) }
            val isAccessoryFirst = normTitle.startsWith(normAcc) || normTitle.contains("$normAcc para") || normTitle.contains("$normAcc de")
            if (isMainProduct && !isAccessoryFirst) {
                return true
            }
        }
        if (normAcc in listOf("suporte", "suportes")) {
            val isSupportSpec = normTitle.contains("suporte a") ||
                    normTitle.contains("suporte ddr") ||
                    normTitle.contains("suporte lga") ||
                    normTitle.contains("suporte vesa") ||
                    normTitle.contains("suporte ajustavel") ||
                    normTitle.contains("com suporte")
            val isMainProduct = intent.requiredCategoryIndicators.any { containsWordOrPhrase(normTitle, it) }
            if (isSupportSpec && isMainProduct && !normTitle.startsWith("suporte ") && !normTitle.contains("suporte de ") && !normTitle.contains("suporte para ")) {
                return true
            }
        }
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
     * Verifica equivalências de unidades como 43 polegadas <-> 43" <-> 43 pol <-> 43pol.
     */
    private fun checkUnitEquivalence(normTitle: String, spec: String): Boolean {
        val numberMatch = "\\d+".toRegex().find(spec) ?: return false
        val num = numberMatch.value
        val patterns = listOf(
            "$num polegadas", "$num polegada", "$num pol", "$num\"", "$num \"",
            "${num}pol", "$num-polegadas", "tamanho $num"
        )
        return patterns.any { normTitle.contains(it) } || containsWordOrPhrase(normTitle, num)
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
