package com.example.data.search

import com.example.data.model.NeuralFeatureBreakdown
import com.example.data.model.QueryIntentProfile
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

data class NeuralScoredCandidate(
    val candidate: RawStoreCandidate,
    val breakdown: NeuralFeatureBreakdown,
    val hardRejected: Boolean,
    val hardRejectReason: String? = null
)

/**
 * Multi-stage Neural Matcher:
 * 1) 128-dim signed feature-hashed character trigram + token bigram cosine embedding layer.
 * 2) 8-feature cross-attention alignment extractor (alphanumeric model, brand/line, head noun, specs, domain, trap guard).
 * 3) Feed-forward MLP (8 -> 16 [tanh] -> 8 [SiLU] -> 1 [sigmoid]) with positional anti-accessory & contradiction gates.
 */
class NeuralProductMatcher {

    companion object {
        private const val EMBEDDING_DIM = 128

        // Terms that unambiguously identify an accessory/cover/mount anywhere in the product head
        val STRICT_ACCESSORY_TERMS = setOf(
            "capa", "capinha", "case", "cover", "bumper", "shell", "silicone",
            "pelicula", "peliculas", "vidro temperado", "tempered glass", "protector", "film",
            "suporte", "tripod", "tripe", "bracket",
            "pulseira", "bracelete", "strap",
            "almofada", "earpad", "earpads", "espuma", "borrachinha", "eartips",
            "controle remoto", "remote control",
            "barra de led", "barras de led", "kit barra", "led strip", "backlight",
            "tcon", "inverter", "peca", "reposicao", "replacement", "spare",
            "adesivo", "sticker", "caixa vazia", "empty box", "manual", "dummy", "ficticio",
            "estojo", "pouch", "tv box"
        )

        // Terms that are specs inside a real product ("Tela 6.7", "Bateria 5000mAh"),
        // and only indicate a standalone accessory/part when appearing as the LEADING subject (first 3 tokens) of the title.
        val LEADING_SUBJECT_PART_TERMS = setOf(
            "tela", "display", "lcd", "touch", "bateria", "battery",
            "cabo", "cabos", "cable", "carregador", "charger", "fonte",
            "adaptador", "adapter", "conector", "controle",
            "antena", "bolsa", "bag", "mochila", "holder", "stand", "mount"
        )

        private val SPARE_BOARD_PART_PHRASES = setOf(
            "placa tcon", "placa t con", "placa logica", "placa principal",
            "placa fonte", "placa inverter", "placa de carga", "placa conector",
            "placa reposicao", "placa display"
        )

        val CONFLICTING_PRODUCT_CATEGORIES = setOf(
            "smartwatch", "relogio", "watch", "tablet", "ipad",
            "buds", "earbuds", "fone", "headphone", "headset",
            "soundbar", "geladeira", "lavadora", "microondas", "aspirador",
            "monitor", "impressora", "pendrive", "ssd", "notebook", "laptop",
            "mouse", "teclado"
        )

        private val modelVariantTokens = setOf(
            "pro", "max", "plus", "ultra", "mini", "lite", "fe", "se", "air", "ti", "super", "xt", "gt", "neo", "edge", "note"
        )
        private val extraPenaltyModifiers = setOf("pro", "max", "ultra", "plus", "mini", "lite", "fe")
        private val giftExtraTerms = setOf(
            "capa", "capinha", "pelicula", "peliculas",
            "case", "cover", "bumper", "shell", "silicone",
            "protector", "film", "adesivo", "sticker", "manual"
        )
        private val competingBrandsPool = listOf(
            "samsung", "apple", "xiaomi", "motorola", "lg", "sony", "philips", "tcl",
            "lenovo", "dell", "acer", "asus", "hp", "gigabyte", "msi", "asrock", "amd", "intel"
        )
        private val capacitySpecRegex = Regex("\\b\\d+(gb|tb)\\b")

        private val W1: Array<FloatArray> = arrayOf(
            floatArrayOf(1.1f, 1.8f, 1.4f, 1.6f, 1.1f, 0.7f, 1.5f, 0.3f),
            floatArrayOf(0.8f, 2.1f, 1.2f, 1.4f, 0.8f, 0.5f, 1.6f, 0.2f),
            floatArrayOf(1.3f, 0.9f, 1.5f, 1.7f, 1.0f, 0.9f, 1.3f, 0.2f),
            floatArrayOf(-0.6f, 0.2f, 0.1f, 1.9f, 0.2f, 0.4f, 2.4f, 0.1f),
            floatArrayOf(0.9f, -1.7f, 0.5f, 0.8f, 0.3f, 0.3f, 0.7f, 0.1f),
            floatArrayOf(0.5f, 1.9f, 1.9f, 0.9f, 0.6f, 0.4f, 0.9f, 0.1f),
            floatArrayOf(0.7f, 1.5f, -1.6f, 0.9f, 0.5f, 0.4f, 0.8f, 0.1f),
            floatArrayOf(0.6f, 1.0f, 0.8f, 1.1f, 2.0f, 1.2f, 0.8f, 0.1f),
            floatArrayOf(0.8f, 1.2f, 0.9f, 1.3f, 1.4f, 1.5f, 0.9f, 0.2f),
            floatArrayOf(1.6f, 1.7f, 1.3f, 1.1f, 0.9f, 1.2f, 1.4f, 0.2f),
            floatArrayOf(1.4f, 1.4f, 1.1f, 0.8f, 1.1f, 1.4f, 1.2f, 0.1f),
            floatArrayOf(1.2f, 1.0f, 0.8f, -2.0f, 0.2f, -0.5f, -2.5f, 0.0f),
            floatArrayOf(0.4f, -2.0f, -1.2f, 0.5f, -0.8f, 0.2f, 0.3f, 0.0f),
            floatArrayOf(1.0f, 1.6f, 1.3f, 1.5f, 1.2f, 0.8f, 1.4f, 0.25f),
            floatArrayOf(0.9f, 1.3f, 1.2f, 1.4f, 1.3f, 1.0f, 1.3f, 0.15f),
            floatArrayOf(0.5f, 0.8f, 0.9f, 1.2f, 0.9f, 1.1f, 1.1f, 0.35f)
        )
        private val B1 = floatArrayOf(
            -4.1f, -3.9f, -3.8f, -2.8f, -1.1f, -3.5f, -1.4f, -3.4f,
            -3.7f, -4.0f, -3.5f, 0.4f, 0.5f, -4.0f, -3.8f, -2.9f
        )

        private val W2: Array<FloatArray> = arrayOf(
            floatArrayOf(1.4f, 1.5f, 1.2f, 1.3f, -1.1f, 1.4f, -0.9f, 1.2f, 1.2f, 1.3f, 1.0f, -1.9f, -1.7f, 1.4f, 1.3f, 0.8f),
            floatArrayOf(1.3f, 1.6f, 1.0f, 1.5f, -0.9f, 1.3f, -0.8f, 1.1f, 1.1f, 1.4f, 1.1f, -2.1f, -1.6f, 1.5f, 1.2f, 0.7f),
            floatArrayOf(1.1f, 1.2f, 1.4f, 1.2f, -1.0f, 1.2f, -0.7f, 1.4f, 1.3f, 1.1f, 1.2f, -1.8f, -1.4f, 1.2f, 1.4f, 0.9f),
            floatArrayOf(0.9f, 1.1f, 1.1f, 1.7f, -0.7f, 1.0f, -0.6f, 1.0f, 1.1f, 1.0f, 0.9f, -2.3f, -1.3f, 1.1f, 1.1f, 0.6f),
            floatArrayOf(1.0f, 1.3f, 0.9f, 0.8f, -0.8f, 1.1f, -0.5f, 1.2f, 1.2f, 1.6f, 1.5f, -1.6f, -1.5f, 1.2f, 1.1f, 0.7f),
            floatArrayOf(1.2f, 1.4f, 1.2f, 1.3f, -1.2f, 1.4f, -1.0f, 1.3f, 1.4f, 1.2f, 1.0f, -2.0f, -1.8f, 1.5f, 1.3f, 0.8f),
            floatArrayOf(-1.2f, -1.3f, -1.0f, -1.4f, 1.4f, -1.1f, 1.2f, -1.0f, -1.0f, -1.1f, -0.9f, 2.2f, 2.0f, -1.3f, -1.1f, -0.6f),
            floatArrayOf(0.8f, 1.0f, 0.9f, 1.1f, -0.6f, 0.9f, -0.5f, 1.1f, 1.0f, 0.9f, 0.9f, -1.5f, -1.2f, 1.0f, 1.0f, 0.7f)
        )
        private val B2 = floatArrayOf(-0.4f, -0.45f, -0.35f, -0.3f, -0.35f, -0.45f, 0.2f, -0.25f)

        private val W3 = floatArrayOf(1.15f, 1.25f, 1.05f, 1.15f, 0.95f, 1.20f, -1.65f, 0.85f)
        private const val B3 = -0.55f

        private fun hasUnitSuffix(code: String): Boolean {
            return code.endsWith("gb") || code.endsWith("tb") || code.endsWith("v") ||
                code.endsWith("w") || code.endsWith("hz") || code.endsWith("mah") || code.endsWith("pol")
        }
    }

    private class PreparedQueryContext(
        val profile: QueryIntentProfile,
        val queryEmbedding: FloatArray
    ) {
        val normRawQuery = TextNormalizer.normalize(profile.rawQuery)
        val normRawNoSpace = normRawQuery.replace(" ", "")
        val queryTokensSet = TextNormalizer.tokenize(normRawQuery, dropStopWords = false).toSet()
        val queryModifiers = queryTokensSet.intersect(modelVariantTokens)

        val brandNorm = profile.brand?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotBlank() }
        val lineNorm = profile.line?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotBlank() }
        val explicitlyRequestedBrand = brandNorm != null && normRawQuery.contains(brandNorm)
        val competingBrands = if (brandNorm != null) {
            competingBrandsPool.filter { it != brandNorm }
        } else {
            emptyList()
        }

        val canonicalTermsWithTokens: List<Pair<String, List<String>>> =
            (profile.canonicalTermsPt + profile.canonicalTermsEn + profile.itemType)
                .map { TextNormalizer.normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .map { phrase -> phrase to TextNormalizer.tokenize(phrase) }

        val canonicalForHeadWithTokens: List<Pair<String, List<String>>> = run {
            val base = (profile.canonicalTermsPt + profile.canonicalTermsEn + profile.itemType)
                .map { TextNormalizer.normalize(it) }
                .filter { it.isNotBlank() }
            val resolved = base.ifEmpty {
                val rawBrandNorm = profile.brand?.let { TextNormalizer.normalize(it) }
                TextNormalizer.tokenize(profile.rawQuery)
                    .filter { it != rawBrandNorm && it !in profile.alphanumericCodes }
            }.distinct()
            resolved.map { phrase -> phrase to TextNormalizer.tokenize(phrase) }
        }

        val reqCodePairs: List<Pair<String, String>> = profile.alphanumericCodes
            .map { TextNormalizer.normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .map { it to it.replace(" ", "") }

        val nonUnitReqCodePairs: List<Pair<String, String>> = profile.alphanumericCodes
            .map { TextNormalizer.normalize(it) }
            .filter { code -> code.isNotBlank() && !hasUnitSuffix(code) }
            .map { it to it.replace(" ", "") }

        val hasExplicitRawNonUnitCode: Boolean = nonUnitReqCodePairs.any { (code, noSpace) ->
            code.length >= 3 && (normRawQuery.contains(code) || normRawNoSpace.contains(noSpace))
        }

        val reqModelNorm = profile.model?.let { TextNormalizer.normalize(it) }.orEmpty()
        val reqModelTokens = TextNormalizer.tokenize(reqModelNorm, dropStopWords = false)
        val hasPhoneTabletOrConsoleDomain = profile.expectedDomains.any {
            it.domainId.contains("CELLPHONE") || it.domainId.contains("TABLET") || it.domainId.contains("CONSOLE")
        }

        private val joinedQueryCanonical =
            "${profile.rawQuery} ${profile.itemType} ${profile.canonicalTermsPt.joinToString(" ")} ${profile.canonicalTermsEn.joinToString(" ")}"
        val queryRequestedTerms = TextNormalizer.tokenize(joinedQueryCanonical, dropStopWords = false).toSet()
        val queryContentTokens = TextNormalizer.tokenize(joinedQueryCanonical, dropStopWords = true)
            .filter { it.length >= 2 }
            .toSet()

        val strictTerms: Set<String> = (STRICT_ACCESSORY_TERMS + profile.antiTargetTerms.map { TextNormalizer.normalize(it) })
            .filter { it.isNotBlank() }
            .toSet()
        val strictPhraseTermsActive: List<String> = strictTerms.filter { it.contains(' ') && !normRawQuery.contains(it) }
        val spareBoardPhrasesActive: List<String> = SPARE_BOARD_PART_PHRASES.filter { !normRawQuery.contains(it) }

        val requiredQualityPairs: List<Pair<String, String>> = profile.requiredQualities
            .map { TextNormalizer.normalize(it) }
            .filter { it.isNotBlank() }
            .map { it to it.replace(" ", "") }
        val optionalQualities: List<String> = profile.optionalQualities
            .map { TextNormalizer.normalize(it) }
            .filter { it.isNotBlank() }

        val expectedDomainIds: Set<String> = profile.expectedDomains
            .map { it.domainId.uppercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val expectedDomainTokens: List<String> = profile.expectedDomains
            .flatMap { TextNormalizer.tokenize("${it.domainName} ${it.categoryName}") }
            .distinct()

        val trapKeyTerms: List<String> = buildList {
            profile.brand?.let { add(TextNormalizer.normalize(it)) }
            profile.model?.let { add(TextNormalizer.normalize(it)) }
            addAll(profile.alphanumericCodes.map { TextNormalizer.normalize(it) })
        }.filter { it.isNotBlank() }.distinct()
    }

    fun scoreAndFilter(
        profile: QueryIntentProfile,
        candidates: List<RawStoreCandidate>
    ): List<NeuralScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val ctx = PreparedQueryContext(profile, encodeQueryEmbedding(profile))

        return candidates.map { candidate ->
            scoreCandidateWithContext(ctx, candidate)
        }.sortedWith(
            compareBy<NeuralScoredCandidate> { it.hardRejected }
                .thenByDescending { it.breakdown.neuralProbability }
        )
    }

    fun scoreCandidate(
        profile: QueryIntentProfile,
        queryEmbedding: FloatArray,
        candidate: RawStoreCandidate
    ): NeuralScoredCandidate {
        return scoreCandidateWithContext(PreparedQueryContext(profile, queryEmbedding), candidate)
    }

    private fun scoreCandidateWithContext(
        ctx: PreparedQueryContext,
        candidate: RawStoreCandidate
    ): NeuralScoredCandidate {
        val profile = ctx.profile
        val split = TextNormalizer.splitHeadAndCompatibilityTail(candidate.title)
        val normTitle = TextNormalizer.normalize(candidate.title)
        val headTokens = TextNormalizer.tokenize(split.head)
        val headTokensSet = headTokens.toSet()
        val tailTokens = TextNormalizer.tokenize(split.tail)
        val allTitleTokens = TextNormalizer.tokenize(candidate.title)
        val attrValues = candidate.attributes.map { it.value }
        val attrText = candidate.attributes.joinToString(" ") { "${it.name} ${it.value}" }
        val normAttrs = TextNormalizer.normalize(attrText)
        val normCat = TextNormalizer.normalize(candidate.categoryPath)
        val combinedText = "$normTitle $normAttrs $normCat".trim()
        val combinedNoSpace = combinedText.replace(" ", "")

        val docTextForEmbedding = buildString {
            append(split.head)
            if (normAttrs.isNotBlank()) append(" ").append(normAttrs)
            if (candidate.categoryPath.isNotBlank()) append(" ").append(candidate.categoryPath)
        }
        val docEmbedding = encodeTextEmbedding(docTextForEmbedding, attrValues)
        val cosine = cosineSimilarity(ctx.queryEmbedding, docEmbedding).coerceIn(0f, 1f)

        val canonicalRecall = computePhraseRecall(ctx.canonicalTermsWithTokens, combinedText)
        val semanticScore = (0.55f * cosine + 0.45f * canonicalRecall).coerceIn(0f, 1f)

        val modelEval = evaluateAlphanumericAndModel(
            ctx = ctx,
            normHead = split.head,
            combinedText = combinedText,
            combinedNoSpace = combinedNoSpace,
            headTokens = headTokens,
            headTokensSet = headTokensSet,
            allTitleTokens = allTitleTokens
        )
        val brandLineScore = evaluateBrandAndLine(ctx, normTitle, normAttrs)
        val headEval = evaluateItemHeadNoun(ctx, split.head, headTokens, headTokensSet, tailTokens, combinedText)
        val specEval = evaluateSpecsAndQualities(ctx, combinedText, combinedNoSpace)
        val domainScore = evaluateDomainAlignment(ctx, candidate, normCat, attrValues)
        val trapScore = evaluateCompatibilityTrap(ctx, split.head, split.tail, headTokensSet, tailTokens, headEval.hasAntiAccessoryInHead)
        val rankSignal = (1f / (1f + 0.05f * candidate.sourceRank.coerceAtLeast(0))).coerceIn(0.2f, 1f)

        val features = floatArrayOf(
            semanticScore,
            modelEval.score,
            brandLineScore,
            headEval.score,
            specEval.score,
            domainScore,
            trapScore,
            rankSignal
        )

        var neuralProb = runMlpForward(features)

        var hardRejectReason: String? = null
        if (!profile.isAccessoryIntent && headEval.hasConflictingCategory) {
            neuralProb *= 0.20f
            hardRejectReason = "Categoria diferente da buscada (${profile.itemType})."
        } else if (!profile.isAccessoryIntent && headEval.hasAntiAccessoryInHead) {
            neuralProb *= 0.22f
            hardRejectReason = "Acessório ou peça detectada no núcleo do título."
        } else if (!profile.isAccessoryIntent && trapScore <= 0.25f) {
            neuralProb *= 0.28f
            hardRejectReason = "Item anunciado como compatível para o produto buscado."
        } else if (modelEval.hasHardModelConflict) {
            neuralProb *= 0.32f
            hardRejectReason = modelEval.conflictReason ?: "Modelo ou versão divergente do pesquisado."
        } else if (specEval.hasHardSpecConflict) {
            neuralProb *= 0.36f
            hardRejectReason = specEval.conflictReason ?: "Especificação técnica conflitante."
        } else if (brandLineScore < 0.15f && !profile.brand.isNullOrBlank()) {
            val allTitleTokenSet = allTitleTokens.toSet()
            val hasCompetingBrand = ctx.competingBrands.any { it in allTitleTokenSet }
            if (ctx.explicitlyRequestedBrand && hasCompetingBrand) {
                neuralProb *= 0.42f
                hardRejectReason = "Marca diferente da solicitada (${profile.brand})."
            } else {
                // Title omitted brand (common in snippets); penalize score slightly without hard-rejecting
                neuralProb *= 0.85f
            }
        }

        if (hardRejectReason == null && !profile.isAccessoryIntent) {
            val nonUnitReqCodePairs = ctx.nonUnitReqCodePairs
            val hasAnyReqNonUnitCode = nonUnitReqCodePairs.any { (code, noSpace) ->
                combinedText.contains(code) || combinedNoSpace.contains(noSpace)
            }
            val hasAnyTokenOverlap = if (ctx.queryContentTokens.isEmpty()) {
                false
            } else {
                val combinedTokensSet = TextNormalizer.tokenize(combinedText, dropStopWords = true).toSet()
                ctx.queryContentTokens.any { it in combinedTokensSet }
            }

            when {
                nonUnitReqCodePairs.isNotEmpty() && !hasAnyReqNonUnitCode &&
                    (headEval.score < 0.60f || (ctx.hasExplicitRawNonUnitCode && semanticScore < 0.72f)) -> {
                    neuralProb *= 0.25f
                    hardRejectReason = "Código ou modelo solicitado (${nonUnitReqCodePairs.first().first}) ausente no anúncio."
                }
                !hasAnyTokenOverlap && semanticScore < 0.16f && headEval.score < 0.60f -> {
                    neuralProb *= 0.22f
                    hardRejectReason = "Produto sem relação com os termos buscados."
                }
            }
        }

        val breakdown = NeuralFeatureBreakdown(
            semanticEmbeddingScore = semanticScore,
            alphanumericModelScore = modelEval.score,
            brandAndLineScore = brandLineScore,
            itemHeadNounScore = headEval.score,
            specQualityScore = specEval.score,
            domainAlignmentScore = domainScore,
            compatibilityTrapScore = trapScore,
            sourceRelevanceSignal = rankSignal,
            neuralProbability = neuralProb.coerceIn(0f, 1f)
        )

        return NeuralScoredCandidate(
            candidate = candidate,
            breakdown = breakdown,
            hardRejected = hardRejectReason != null,
            hardRejectReason = hardRejectReason
        )
    }

    private fun runMlpForward(x: FloatArray): Float {
        val h1 = FloatArray(16)
        for (i in 0 until 16) {
            var sum = B1[i]
            val row = W1[i]
            for (j in 0 until 8) {
                sum += row[j] * x[j]
            }
            h1[i] = tanh(sum.toDouble()).toFloat()
        }

        val h2 = FloatArray(8)
        for (i in 0 until 8) {
            var sum = B2[i]
            val row = W2[i]
            for (j in 0 until 16) {
                sum += row[j] * h1[j]
            }
            val sig = 1f / (1f + exp(-sum.toDouble()).toFloat())
            h2[i] = sum * sig
        }

        var outSum = B3
        for (i in 0 until 8) {
            outSum += W3[i] * h2[i]
        }
        return 1f / (1f + exp(-outSum.toDouble()).toFloat())
    }

    private fun encodeQueryEmbedding(profile: QueryIntentProfile): FloatArray {
        val weightedText = buildString {
            append(profile.itemType).append(' ')
            append(profile.canonicalTermsPt.joinToString(" ")).append(' ')
            append(profile.canonicalTermsEn.joinToString(" ")).append(' ')
            profile.brand?.let { append(it).append(' ') }
            profile.line?.let { append(it).append(' ') }
            profile.model?.let { append(it).append(' ') }
            append(profile.alphanumericCodes.joinToString(" ")).append(' ')
            append(profile.requiredQualities.joinToString(" "))
        }
        val priorityTerms = buildList {
            profile.brand?.let { add(it) }
            profile.model?.let { add(it) }
            addAll(profile.alphanumericCodes)
            addAll(profile.requiredQualities)
        }
        return encodeTextEmbedding(weightedText, priorityTerms)
    }

    private fun encodeTextEmbedding(text: String, highPriorityTerms: List<String>): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val grams = TextNormalizer.buildCharNGrams(text, n = 3)
        for (g in grams) {
            addFeature(vec, "g3:$g", 1.0f)
        }
        val tokens = TextNormalizer.tokenize(text, dropStopWords = true)
        for (t in tokens) {
            val weight = if (t.any { it.isDigit() }) 2.4f else 1.5f
            addFeature(vec, "w1:$t", weight)
        }
        for (i in 0 until tokens.size - 1) {
            addFeature(vec, "w2:${tokens[i]}_${tokens[i + 1]}", 1.8f)
        }
        for (term in highPriorityTerms) {
            val norm = TextNormalizer.normalize(term)
            if (norm.isNotBlank()) {
                addFeature(vec, "hp:$norm", 2.6f)
            }
        }
        l2Normalize(vec)
        return vec
    }

    private fun addFeature(vec: FloatArray, key: String, weight: Float) {
        val h1 = key.hashCode()
        val idx1 = abs(h1 % EMBEDDING_DIM)
        val sign1 = if ((h1 ushr 31) == 0) 1f else -1f
        vec[idx1] += sign1 * weight

        val h2 = (h1 * 0x9E3779B9.toInt()) xor (h1 ushr 16)
        val idx2 = abs(h2 % EMBEDDING_DIM)
        val sign2 = if ((h2 ushr 31) == 0) 1f else -1f
        vec[idx2] += sign2 * (weight * 0.5f)
    }

    private fun l2Normalize(vec: FloatArray) {
        var normSq = 0f
        for (v in vec) normSq += v * v
        if (normSq <= 1e-8f) return
        val inv = 1f / sqrt(normSq)
        for (i in vec.indices) vec[i] *= inv
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private data class ModelEval(
        val score: Float,
        val hasHardModelConflict: Boolean = false,
        val conflictReason: String? = null
    )

    private fun evaluateAlphanumericAndModel(
        ctx: PreparedQueryContext,
        normHead: String,
        combinedText: String,
        combinedNoSpace: String,
        headTokens: List<String>,
        headTokensSet: Set<String>,
        allTitleTokens: List<String>
    ): ModelEval {
        val reqCodePairs = ctx.reqCodePairs
        val reqModelTokens = ctx.reqModelTokens

        if (reqCodePairs.isEmpty() && reqModelTokens.isEmpty()) {
            return ModelEval(score = 0.78f)
        }

        val headNoSpace = normHead.replace(" ", "")
        var codeHits = 0f
        var firstMissingCode: String? = null
        var hasMissingNonSpecCode = false
        for ((code, codeNoSpace) in reqCodePairs) {
            val inHead = normHead.contains(code) || headNoSpace.contains(codeNoSpace)
            val inCombined = inHead || combinedText.contains(code) || combinedNoSpace.contains(codeNoSpace)
            when {
                inHead -> codeHits += 1.0f
                inCombined -> codeHits += 0.75f
                else -> {
                    if (firstMissingCode == null) firstMissingCode = code
                    if (!code.endsWith("gb") && !code.endsWith("tb") && !code.endsWith("v")) {
                        hasMissingNonSpecCode = true
                    }
                }
            }
        }
        val codeRatio = if (reqCodePairs.isNotEmpty()) codeHits / reqCodePairs.size else 0.75f

        var modelTokenHits = 0f
        for (mt in reqModelTokens) {
            if (mt in headTokensSet) modelTokenHits += 1.0f
            else if (mt in allTitleTokens || combinedText.contains(mt)) modelTokenHits += 0.7f
        }
        val modelTokenRatio = if (reqModelTokens.isNotEmpty()) modelTokenHits / reqModelTokens.size else codeRatio

        var conflictReason: String? = null

        if (firstMissingCode != null && hasMissingNonSpecCode) {
            val brandNorm = ctx.brandNorm
            if (!brandNorm.isNullOrBlank() && combinedText.contains(brandNorm)) {
                val hasNonSpecTitleNumber = headTokens.any { token ->
                    token.length >= 2 && token.any { it.isDigit() } && !hasUnitSuffix(token)
                }
                if (hasNonSpecTitleNumber) {
                    conflictReason = "Código de modelo divergente do solicitado ($firstMissingCode)."
                }
            }
        }

        val queryModifiers = ctx.queryModifiers
        val headModifiers = headTokensSet.intersect(modelVariantTokens)
        if (queryModifiers.isNotEmpty() && !headModifiers.containsAll(queryModifiers) && !combinedText.contains(queryModifiers.first())) {
            conflictReason = "Versão solicitada (${queryModifiers.joinToString(" ")}) não encontrada."
        } else if (reqModelTokens.isNotEmpty() && queryModifiers.isEmpty() && headModifiers.isNotEmpty() &&
            headModifiers.any { it in extraPenaltyModifiers } &&
            ctx.hasPhoneTabletOrConsoleDomain
        ) {
            return ModelEval(
                score = (0.45f * codeRatio + 0.35f * modelTokenRatio).coerceIn(0f, 0.72f),
                hasHardModelConflict = false
            )
        }

        val combinedScore = (0.60f * codeRatio + 0.40f * modelTokenRatio).coerceIn(0f, 1f)
        return ModelEval(
            score = if (conflictReason != null) (combinedScore * 0.35f) else combinedScore,
            hasHardModelConflict = conflictReason != null,
            conflictReason = conflictReason
        )
    }

    private fun evaluateBrandAndLine(
        ctx: PreparedQueryContext,
        normTitle: String,
        normAttrs: String
    ): Float {
        val brandNorm = ctx.brandNorm
        val lineNorm = ctx.lineNorm
        if (brandNorm == null && lineNorm == null) return 0.75f

        var score = 0f
        var weightSum = 0f

        if (brandNorm != null) {
            weightSum += 0.7f
            if (normTitle.contains(brandNorm) || normAttrs.contains(brandNorm)) score += 0.7f
        }
        if (lineNorm != null) {
            weightSum += 0.3f
            if (normTitle.contains(lineNorm) || normAttrs.contains(lineNorm)) score += 0.3f
        }
        return if (weightSum > 0f) (score / weightSum).coerceIn(0f, 1f) else 0.75f
    }

    private data class HeadEval(
        val score: Float,
        val hasAntiAccessoryInHead: Boolean,
        val hasConflictingCategory: Boolean = false
    )

    private fun evaluateItemHeadNoun(
        ctx: PreparedQueryContext,
        normHead: String,
        headTokens: List<String>,
        headSet: Set<String>,
        tailTokens: List<String>,
        combinedText: String
    ): HeadEval {
        val canonical = ctx.canonicalForHeadWithTokens

        val headHit = canonical.any { (phrase, pTokens) ->
            pTokens.isNotEmpty() && (normHead.contains(phrase) || pTokens.all { it in headSet })
        }
        val anyHit = canonical.any { (phrase, pTokens) ->
            pTokens.isNotEmpty() && (combinedText.contains(phrase) || pTokens.all { combinedText.contains(it) })
        }

        val queryRequestedTerms = ctx.queryRequestedTerms
        val isAccessoryIntent = ctx.profile.isAccessoryIntent

        val hasConflictingCategory = if (isAccessoryIntent) {
            false
        } else {
            val limit = minOf(4, headTokens.size)
            var found = false
            for (i in 0 until limit) {
                val token = headTokens[i]
                if (token in CONFLICTING_PRODUCT_CATEGORIES && token !in queryRequestedTerms) {
                    found = true
                    break
                }
            }
            found
        }

        val hasAntiAccessory = if (isAccessoryIntent) {
            false
        } else {
            var targetNounFirstIdx: Int? = null
            for ((_, pTokens) in canonical) {
                if (pTokens.isEmpty()) continue
                var maxIdx = -1
                var allFound = true
                for (pt in pTokens) {
                    val idx = headTokens.indexOf(pt)
                    if (idx < 0) {
                        allFound = false
                        break
                    }
                    if (idx > maxIdx) maxIdx = idx
                }
                if (allFound && (targetNounFirstIdx == null || maxIdx < targetNounFirstIdx)) {
                    targetNounFirstIdx = maxIdx
                }
            }

            val strictTerms = ctx.strictTerms
            var strictSingleHit = false
            for (idx in headTokens.indices) {
                val token = headTokens[idx]
                if (token !in strictTerms || token in queryRequestedTerms) continue
                if (token in giftExtraTerms && targetNounFirstIdx != null && idx > targetNounFirstIdx) {
                    continue
                }
                strictSingleHit = true
                break
            }

            val strictPhraseHit = !strictSingleHit && ctx.strictPhraseTermsActive.any { phrase ->
                normHead.contains(phrase)
            }
            val leadingPartHit = !strictSingleHit && !strictPhraseHit && run {
                val limit = minOf(3, headTokens.size)
                var hit = false
                for (i in 0 until limit) {
                    val token = headTokens[i]
                    if (token in LEADING_SUBJECT_PART_TERMS && token !in queryRequestedTerms) {
                        hit = true
                        break
                    }
                }
                hit
            }
            val spareBoardHit = !strictSingleHit && !strictPhraseHit && !leadingPartHit &&
                ctx.spareBoardPhrasesActive.any { phrase -> normHead.contains(phrase) }

            strictSingleHit || strictPhraseHit || leadingPartHit || spareBoardHit
        }

        val codeMatchedInHead = ctx.reqCodePairs.isNotEmpty() && run {
            val headNoSpace = normHead.replace(" ", "")
            ctx.reqCodePairs.any { (code, codeNoSpace) ->
                normHead.contains(code) || headNoSpace.contains(codeNoSpace)
            }
        }

        val baseScore = when {
            hasAntiAccessory || hasConflictingCategory -> 0.05f
            headHit -> 1.0f
            anyHit && tailTokens.isEmpty() -> 0.85f
            codeMatchedInHead && tailTokens.isEmpty() -> 0.78f
            anyHit || codeMatchedInHead -> 0.68f
            else -> 0.30f
        }
        return HeadEval(
            score = baseScore,
            hasAntiAccessoryInHead = hasAntiAccessory,
            hasConflictingCategory = hasConflictingCategory
        )
    }

    private data class SpecEval(
        val score: Float,
        val hasHardSpecConflict: Boolean = false,
        val conflictReason: String? = null
    )

    private fun evaluateSpecsAndQualities(
        ctx: PreparedQueryContext,
        combinedText: String,
        combinedNoSpace: String
    ): SpecEval {
        val required = ctx.requiredQualityPairs
        val optional = ctx.optionalQualities
        if (required.isEmpty() && optional.isEmpty()) return SpecEval(score = 0.80f)

        var reqHits = 0f
        var conflict: String? = null
        for ((spec, specNoSpace) in required) {
            val matched = combinedText.contains(spec) || combinedNoSpace.contains(specNoSpace)
            if (matched) {
                reqHits += 1f
            } else {
                if (spec == "110v" && combinedText.contains("220v") && !combinedText.contains("bivolt")) {
                    conflict = "Voltagem incompatível (anúncio 220v, busca 110v)."
                } else if (spec == "220v" && combinedText.contains("110v") && !combinedText.contains("bivolt")) {
                    conflict = "Voltagem incompatível (anúncio 110v, busca 220v)."
                } else if (spec.endsWith("gb") || spec.endsWith("tb")) {
                    val otherCapacities = capacitySpecRegex.findAll(combinedText).map { it.value }.toList()
                    if (otherCapacities.isNotEmpty() && spec !in otherCapacities) {
                        conflict = "Capacidade diferente da solicitada ($spec)."
                    }
                }
            }
        }

        val optHits = optional.count { combinedText.contains(it) }.toFloat()
        val reqRatio = if (required.isNotEmpty()) reqHits / required.size else 1f
        val optRatio = if (optional.isNotEmpty()) optHits / optional.size else 0.7f
        val score = (0.80f * reqRatio + 0.20f * optRatio).coerceIn(0f, 1f)

        return SpecEval(
            score = if (conflict != null) (score * 0.25f) else score,
            hasHardSpecConflict = conflict != null,
            conflictReason = conflict
        )
    }

    private fun evaluateDomainAlignment(
        ctx: PreparedQueryContext,
        candidate: RawStoreCandidate,
        normCat: String,
        attrValues: List<String>
    ): Float {
        if (ctx.expectedDomainIds.isEmpty() && ctx.profile.expectedDomains.isEmpty()) return 0.72f

        if (candidate.store == StoreType.MERCADO_LIVRE && candidate.domainId.isNotBlank()) {
            if (candidate.domainId.uppercase() in ctx.expectedDomainIds) return 1.0f
            return 0.40f
        }

        val attrValsNorm = if (attrValues.isEmpty()) "" else TextNormalizer.normalize(attrValues.joinToString(" "))
        val catNorm = if (attrValsNorm.isBlank()) normCat else "$normCat $attrValsNorm".trim()
        if (catNorm.isBlank()) return 0.65f

        val overlap = ctx.expectedDomainTokens.count { catNorm.contains(it) }
        return when {
            overlap >= 2 -> 0.95f
            overlap == 1 -> 0.82f
            else -> 0.60f
        }
    }

    private fun evaluateCompatibilityTrap(
        ctx: PreparedQueryContext,
        normHead: String,
        normTail: String,
        headTokensSet: Set<String>,
        tailTokens: List<String>,
        hasAntiAccessoryInHead: Boolean
    ): Float {
        if (ctx.profile.isAccessoryIntent) return 0.95f
        if (hasAntiAccessoryInHead) return 0.05f
        if (normTail.isBlank()) return 1.0f

        val keyTerms = ctx.trapKeyTerms
        if (keyTerms.isEmpty()) return 0.85f

        val tailSet = tailTokens.toSet()
        val inHead = keyTerms.count { term -> normHead.contains(term) || term in headTokensSet }
        val inTail = keyTerms.count { term -> normTail.contains(term) || term in tailSet }

        return when {
            inHead == 0 && inTail > 0 -> 0.05f
            inHead < inTail -> 0.35f
            else -> 0.95f
        }
    }

    private fun computePhraseRecall(
        phrasesWithTokens: List<Pair<String, List<String>>>,
        target: String
    ): Float {
        if (phrasesWithTokens.isEmpty() || target.isBlank()) return 0f
        val hits = phrasesWithTokens.count { (phrase, tokens) ->
            tokens.isNotEmpty() && (target.contains(phrase) || tokens.all { target.contains(it) })
        }
        if (hits == 0) return 0f
        return (0.68f + 0.32f * ((hits - 1).coerceAtMost(2) / 2f)).coerceIn(0f, 1f)
    }
}
