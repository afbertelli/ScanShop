package com.example.ai

import android.util.Log
import com.example.data.model.DomainDiscoveryHint
import com.example.data.model.MatchVerdict
import com.example.data.model.MatchedProduct
import com.example.data.model.QueryIntentProfile
import com.example.data.search.NeuralScoredCandidate
import com.example.data.search.TextNormalizer
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "GeminiSearchEngine"

data class AiVerificationOutcome(
    val matches: List<MatchedProduct>,
    val usedAi: Boolean,
    val notice: String? = null
)

class GeminiQueryAndVerifierEngine {

    private val intentSchema = Schema.obj(
        properties = mapOf(
            "itemType" to Schema.string(description = "Tipo exato do produto principal buscado em portugues"),
            "brand" to Schema.string(description = "Marca solicitada ou vazio"),
            "line" to Schema.string(description = "Linha do produto ou vazio"),
            "model" to Schema.string(description = "Modelo ou versao exata ou vazio"),
            "alphanumericCodes" to Schema.array(
                items = Schema.string(),
                description = "Codigos exatos de modelo, capacidade ou voltagem"
            ),
            "requiredQualities" to Schema.array(
                items = Schema.string(),
                description = "Qualidades e especificacoes essenciais pedidas na busca"
            ),
            "optionalQualities" to Schema.array(
                items = Schema.string(),
                description = "Preferencias secundarias como cor ou acabamento"
            ),
            "canonicalTermsPt" to Schema.array(
                items = Schema.string(),
                description = "Sinonimos diretos do produto principal em portugues"
            ),
            "canonicalTermsEn" to Schema.array(
                items = Schema.string(),
                description = "Termos exatos do produto em ingles para busca no AliExpress"
            ),
            "isAccessoryIntent" to Schema.boolean(description = "True apenas se o usuario buscou acessorio, capa, cabo, pelicula ou peca"),
            "antiTargetTerms" to Schema.array(
                items = Schema.string(),
                description = "Termos que identificam acessorios ou itens errados que devem ser descartados"
            ),
            "searchQueryPt" to Schema.string(description = "Query principal limpa em portugues"),
            "alternativeQueriesPt" to Schema.array(
                items = Schema.string(),
                description = "Ate 2 variacoes reais usadas por vendedores no Brasil (ex: 'celular Samsung', 'Samsung Galaxy')"
            ),
            "searchQueryEn" to Schema.string(description = "Query limpa e precisa em ingles mantendo marca e modelo"),
            "alternativeQueriesEn" to Schema.array(
                items = Schema.string(),
                description = "Ate 1 variacao em ingles para AliExpress (ex: 'Samsung mobile phone')"
            )
        ),
        optionalProperties = listOf("brand", "line", "model")
    )

    private val verifierSchema = Schema.obj(
        properties = mapOf(
            "evaluations" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "index" to Schema.integer(description = "Indice do candidato na lista"),
                        "verdict" to Schema.enumeration(
                            values = listOf(
                                "EXACT_MATCH",
                                "VARIANT_MATCH",
                                "ACCESSORY_OR_PART",
                                "WRONG_MODEL_OR_SPEC",
                                "UNRELATED"
                            ),
                            description = "Classificacao rigorosa de assertividade"
                        ),
                        "assertiveness" to Schema.integer(description = "Nota de 0 a 100 de fidelidade exata ao produto buscado"),
                        "reasonPt" to Schema.string(description = "Explicacao objetiva e curta em portugues"),
                        "matchedSpecs" to Schema.array(
                            items = Schema.string(),
                            description = "Atributos confirmados no anuncio"
                        )
                    )
                ),
                description = "Avaliacao de cada candidato enviado"
            )
        )
    )

    // Ultra-fast model for real-time structured intent extraction (isolates Stage 1 quota & latency from gemini-3.8-flash)
    private val intentModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = intentSchema
            }
        )
    }

    // Default frontier fallback if Flash-Lite fails or times out on an intent query
    private val fallbackIntentModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = intentSchema
            }
        )
    }

    // Precision verifier model
    private val verifierModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.05f
                responseMimeType = "application/json"
                responseSchema = verifierSchema
            }
        )
    }

    // Fast fallback verifier model if gemini-3.8-flash hits transient limits or timeout
    private val fallbackVerifierModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.05f
                responseMimeType = "application/json"
                responseSchema = verifierSchema
            }
        )
    }

    suspend fun understandQuery(
        rawQuery: String,
        discoveryHints: List<DomainDiscoveryHint>
    ): Pair<QueryIntentProfile, String?> {
        val heuristic = buildHeuristicProfile(rawQuery, discoveryHints)
        val hintsContext = if (discoveryHints.isEmpty()) {
            "Nenhum metadado previo."
        } else {
            discoveryHints.take(2).joinToString("\n") { hint ->
                val attrs = hint.attributes.take(5).joinToString(", ") { "${it.name}=${it.value}" }
                "- Dominio: ${hint.domainName} (${hint.domainId}), Attrs: [$attrs]"
            }
        }

        val prompt = """
            Extraia a intencao exata de compra para busca em e-commerce (AliExpress, Shopee, Mercado Livre).
            Corrija erros de digitacao e abreviacoes: "smartv" -> "Smart TV", "tphone"/"smartfone" -> "smartphone", "cel" -> "celular".
            Em canonicalTermsPt inclua sinonimos usados em anuncios no Brasil (ex: para celular: ["smartphone", "celular", "telefone"]; para tv: ["smart tv", "tv", "televisor"]).
            Em canonicalTermsEn inclua termos reais de anuncios em ingles (ex: para celular: ["smartphone", "mobile phone", "cell phone", "android phone"]; para tv: ["smart tv", "television"]).
            Gere searchQueryPt e alternativeQueriesPt com ate 3 formas reais como vendedores brasileiros intitulam esse produto (ex: "smartphone Samsung", "celular Samsung", "Samsung Galaxy").
            Gere searchQueryEn e alternativeQueriesEn com ate 2 termos em ingles para AliExpress. Nao invente modelo ou especificacoes ausentes na pesquisa.
            Pesquisa: "$rawQuery"
            Catalogo: $hintsContext
        """.trimIndent()

        return try {
            val json = withTimeout(60_000L) {
                val attemptBudgets = longArrayOf(20_000L, 24_000L, 14_000L)
                var lastError: Exception? = null
                var parsedJson: JSONObject? = null
                for (attemptIdx in attemptBudgets.indices) {
                    val budgetMs = attemptBudgets[attemptIdx]
                    try {
                        val response = withTimeoutOrNull(budgetMs) {
                            if (attemptIdx == 0) {
                                intentModel.generateContent(prompt)
                            } else {
                                fallbackIntentModel.generateContent(prompt)
                            }
                        }
                        if (response == null) {
                            Log.w(TAG, "AI intent attempt ${attemptIdx + 1} timed out after ${budgetMs}ms")
                            continue
                        }
                        val jsonText = response.text?.trim()?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("Resposta vazia da IA.")
                        parsedJson = JSONObject(jsonText)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "AI intent attempt ${attemptIdx + 1} failed: ${describeThrowableChain(e)}")
                        if (attemptIdx < attemptBudgets.lastIndex) {
                            delay(if (isTransientNetworkError(e)) 850L else 250L)
                        }
                    }
                }
                parsedJson ?: throw (lastError ?: IllegalStateException("Tempo limite na análise de intenção por IA."))
            }

            val normalizedRaw = TextNormalizer.normalizeSearchQueryText(rawQuery)
            val normRawForCompare = TextNormalizer.normalize(rawQuery)
            val rawTokensSet = TextNormalizer.tokenize(rawQuery, dropStopWords = false).toSet()

            val itemType = json.optString("itemType", heuristic.itemType).trim().ifBlank { heuristic.itemType }
            val rawAiBrand = json.optString("brand", "").trim().takeIf { it.isNotBlank() && it != "null" }
            val impliedBrand = when {
                "iphone" in rawTokensSet || "ipad" in rawTokensSet || "macbook" in rawTokensSet -> "Apple"
                "galaxy" in rawTokensSet -> "Samsung"
                "redmi" in rawTokensSet || "poco" in rawTokensSet -> "Xiaomi"
                "playstation" in rawTokensSet || "ps5" in rawTokensSet || "ps4" in rawTokensSet -> "Sony"
                "xbox" in rawTokensSet -> "Microsoft"
                else -> null
            }
            val brand = rawAiBrand?.takeIf { candidateBrand ->
                val normB = TextNormalizer.normalize(candidateBrand)
                normB.isNotBlank() && (normRawForCompare.contains(normB) || impliedBrand?.equals(candidateBrand, true) == true)
            } ?: heuristic.brand ?: impliedBrand

            val line = json.optString("line", "").trim().takeIf { it.isNotBlank() && it != "null" }
                ?.takeIf { normRawForCompare.contains(TextNormalizer.normalize(it)) }
                ?: heuristic.line
            val model = json.optString("model", "").trim().takeIf { it.isNotBlank() && it != "null" }
                ?.takeIf { m ->
                    val mTokens = TextNormalizer.tokenize(m, dropStopWords = true)
                    mTokens.isNotEmpty() && mTokens.any { it in rawTokensSet || normRawForCompare.contains(it) }
                }
                ?: heuristic.model

            val alphaCodes = (jsonArrayToList(json.optJSONArray("alphanumericCodes")) + heuristic.alphanumericCodes)
                .map { TextNormalizer.normalize(it) }
                .filter { code -> code.isNotBlank() && (normRawForCompare.contains(code) || normRawForCompare.replace(" ", "").contains(code.replace(" ", ""))) }
                .distinct()
                .ifEmpty { heuristic.alphanumericCodes }

            val aiReqQualities = jsonArrayToList(json.optJSONArray("requiredQualities"))
            val groundedReqQualities = aiReqQualities.filter { q ->
                val normQ = TextNormalizer.normalize(q)
                normQ.isNotBlank() && (normRawForCompare.contains(normQ) || normRawForCompare.replace(" ", "").contains(normQ.replace(" ", "")))
            }.ifEmpty { heuristic.requiredQualities }
            val extraQualities = aiReqQualities.filter { it !in groundedReqQualities }
            val requiredQualities = groundedReqQualities
            val optionalQualities = (jsonArrayToList(json.optJSONArray("optionalQualities")) + extraQualities).distinct()
            val canonicalPt = (jsonArrayToList(json.optJSONArray("canonicalTermsPt")) + heuristic.canonicalTermsPt + itemType + normalizedRaw)
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val canonicalEn = (jsonArrayToList(json.optJSONArray("canonicalTermsEn")) + heuristic.canonicalTermsEn)
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val isAccessoryIntent = json.optBoolean("isAccessoryIntent", heuristic.isAccessoryIntent)
            val antiTerms = jsonArrayToList(json.optJSONArray("antiTargetTerms"))
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()

            val qPtMain = json.optString("searchQueryPt", normalizedRaw).trim().ifBlank { normalizedRaw }
            val altPt = jsonArrayToList(json.optJSONArray("alternativeQueriesPt"))
            val qEnMain = json.optString("searchQueryEn", "").trim()
            val altEn = jsonArrayToList(json.optJSONArray("alternativeQueriesEn"))

            val synonymPtQuery = canonicalPt
                .firstOrNull { !it.equals(qPtMain, true) && !it.equals(itemType, true) && !it.contains("4k", true) && !it.contains("qled", true) && !it.contains("oled", true) }
                ?.let { syn ->
                    (listOf(syn) + listOfNotNull(brand, line, model) + alphaCodes)
                        .joinToString(" ").trim()
                }
            val brandLineModelOnly = (listOfNotNull(brand, line, model) + alphaCodes)
                .joinToString(" ").trim()
                .takeIf { it.isNotBlank() && it.split(' ').size >= 2 }

            val ptVariants = buildList {
                if (qPtMain.isNotBlank()) add(qPtMain)
                if (normalizedRaw.isNotBlank() && none { it.equals(normalizedRaw, true) }) add(normalizedRaw)
                altPt.forEach { alt ->
                    if (alt.isNotBlank() && none { it.equals(alt, true) }) add(alt)
                }
                if (!synonymPtQuery.isNullOrBlank() && none { it.equals(synonymPtQuery, true) }) add(synonymPtQuery)
                if (!brandLineModelOnly.isNullOrBlank() && none { it.equals(brandLineModelOnly, true) }) add(brandLineModelOnly)
            }.distinctBy { TextNormalizer.normalize(it) }.take(3)

            val enVariants = buildList {
                if (qEnMain.isNotBlank()) add(qEnMain)
                altEn.forEach { alt ->
                    if (alt.isNotBlank() && none { it.equals(alt, true) }) add(alt)
                }
                canonicalEn.take(2).forEach { enTerm ->
                    val candidateEn = (listOfNotNull(brand, line, model) + enTerm + alphaCodes)
                        .joinToString(" ").trim()
                    if (candidateEn.isNotBlank() && none { it.equals(candidateEn, true) }) add(candidateEn)
                }
            }.distinctBy { TextNormalizer.normalize(it) }.take(2)

            val enriched = QueryIntentProfile(
                rawQuery = rawQuery.trim(),
                itemType = itemType,
                canonicalTermsPt = canonicalPt,
                canonicalTermsEn = canonicalEn,
                brand = brand,
                line = line,
                model = model,
                alphanumericCodes = alphaCodes,
                requiredQualities = requiredQualities,
                optionalQualities = optionalQualities,
                isAccessoryIntent = isAccessoryIntent,
                expectedDomains = discoveryHints,
                antiTargetTerms = antiTerms,
                searchQueriesPt = ptVariants,
                searchQueriesEn = enVariants
            )
            enriched to null
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI intent understanding timed out", e)
            heuristic to "Tempo limite na análise de intenção por IA."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = describeThrowableChain(e)
            Log.e(TAG, "AI intent understanding failed: $detail", e)
            heuristic to "Falha na análise de intenção por IA."
        }
    }

    suspend fun verifyCandidates(
        profile: QueryIntentProfile,
        neuralCandidates: List<NeuralScoredCandidate>
    ): AiVerificationOutcome {
        val primaryPool = neuralCandidates
            .filter { !it.hardRejected && it.breakdown.neuralProbability >= 0.28f }
        val primaryIds = primaryPool.map { it.candidate.id }.toSet()

        // AI rescue pass: if any store collected raw listings but <5 passed neural threshold,
        // send top non-accessory candidates from that store to Gemini so nothing real is silently dropped.
        val rescueAccessoryTokens = setOf(
            "capa", "capinha", "pelicula", "bateria", "tela", "cabo", "carregador", "suporte", "controle",
            "case", "cover", "glass", "protector", "tempered", "lcd", "display", "touch",
            "battery", "charger", "cable", "holder", "mount", "stylus", "film", "lens", "housing", "frame", "flex"
        )
        val rescuePool = neuralCandidates
            .groupBy { it.candidate.store }
            .values
            .flatMap { storeList ->
                val storePrimaryCount = storeList.count { it.candidate.id in primaryIds }
                if (storePrimaryCount >= 5) {
                    emptyList()
                } else {
                    storeList
                        .filter { it.candidate.id !in primaryIds }
                        .filterNot { scored ->
                            if (profile.isAccessoryIntent) return@filterNot false
                            val headTokens = TextNormalizer.tokenize(
                                TextNormalizer.splitHeadAndCompatibilityTail(scored.candidate.title).head
                            ).take(5)
                            val catNorm = TextNormalizer.normalize(scored.candidate.categoryPath)
                            headTokens.any { it in rescueAccessoryTokens } ||
                                catNorm.contains("bags cases") ||
                                catNorm.contains("screen protector") ||
                                catNorm.contains("replacement") ||
                                catNorm.contains("charger") ||
                                catNorm.contains("cable")
                        }
                        .sortedByDescending { it.breakdown.neuralProbability }
                        .take(18)
                }
            }

        val combinedPool = (primaryPool + rescuePool).distinctBy { it.candidate.id }
        if (combinedPool.isEmpty()) {
            return AiVerificationOutcome(matches = emptyList(), usedAi = true)
        }

        // Verify each store in its own isolated batches (max 20 items/batch) so one store's 40+ items never crowd out another store
        val batches = combinedPool
            .groupBy { it.candidate.store }
            .values
            .flatMap { storeList ->
                storeList
                    .sortedByDescending { it.breakdown.neuralProbability }
                    .take(25)
                    .chunked(20)
            }

        val batchOutcomes = coroutineScope {
            batches.map { batch ->
                async { verifySingleBatch(profile, batch) }
            }
        }.awaitAll()

        val initialMatches = batchOutcomes.flatMap { it.matches }
            .distinctBy { it.id }
            .toMutableList()

        // Store-level safety net: if a store returned valid non-accessory items that passed neural checks, never drop the store to 0
        val storesWithMatches = initialMatches.mapTo(HashSet()) { it.store }
        neuralCandidates
            .groupBy { it.candidate.store }
            .forEach { (store, storeList) ->
                if (store !in storesWithMatches) {
                    val primaryRescued = storeList
                        .filter { !it.hardRejected && it.breakdown.neuralProbability >= 0.22f }
                        .sortedByDescending { it.breakdown.neuralProbability }
                        .take(10)
                    val rescued = primaryRescued.ifEmpty {
                        storeList
                            .filter { !it.hardRejected }
                            .sortedByDescending { it.breakdown.neuralProbability }
                            .take(6)
                    }.map { scored ->
                        MatchedProduct(
                            id = scored.candidate.id,
                            store = scored.candidate.store,
                            title = scored.candidate.title,
                            imageUrl = scored.candidate.imageUrl,
                            productUrl = scored.candidate.productUrl,
                            affiliateUrl = scored.candidate.affiliateUrl,
                            priceText = scored.candidate.priceText,
                            categoryLabel = scored.candidate.categoryPath,
                            matchedSpecs = scored.candidate.attributes.take(3).map { "${it.name}: ${it.value}" },
                            assertivenessScore = scored.breakdown.neuralProbability,
                            matchVerdict = if (scored.breakdown.neuralProbability >= 0.65f) MatchVerdict.EXACT_MATCH else MatchVerdict.VARIANT_MATCH,
                            matchReason = "Correspondência validada pela Rede Neural.",
                            featureBreakdown = scored.breakdown
                        )
                    }
                    initialMatches.addAll(rescued)
                }
            }

        val allMatches = initialMatches
            .distinctBy { it.id }
            .sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.assertivenessScore }
            )
        val anyUsedAi = batchOutcomes.any { it.usedAi }
        val firstNotice = batchOutcomes.firstNotNullOfOrNull { it.notice }

        return AiVerificationOutcome(
            matches = allMatches,
            usedAi = anyUsedAi,
            notice = firstNotice
        )
    }

    suspend fun verifyAdditionalCandidates(
        profile: QueryIntentProfile,
        overflowCandidates: List<NeuralScoredCandidate>,
        limit: Int = 30
    ): List<MatchedProduct> {
        val strict = overflowCandidates
            .filter { !it.hardRejected && it.breakdown.neuralProbability >= 0.25f }
            .sortedByDescending { it.breakdown.neuralProbability }
            .take(limit)
        val eligible = if (strict.size >= 8) {
            strict
        } else {
            val strictIds = strict.mapTo(HashSet()) { it.candidate.id }
            val extra = overflowCandidates
                .filter { !it.hardRejected && it.candidate.id !in strictIds && it.breakdown.neuralProbability >= 0.14f }
                .sortedByDescending { it.breakdown.neuralProbability }
                .take((limit - strict.size).coerceAtLeast(0))
            strict + extra
        }
        if (eligible.isEmpty()) return emptyList()

        val batches = eligible.chunked(20)
        val outcomes = coroutineScope {
            batches.map { batch ->
                async { verifySingleBatch(profile, batch) }
            }
        }.awaitAll()

        return outcomes.flatMap { it.matches }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.assertivenessScore }
            )
    }

    private suspend fun verifySingleBatch(
        profile: QueryIntentProfile,
        batchPool: List<NeuralScoredCandidate>
    ): AiVerificationOutcome {
        val candidatesPayload = batchPool.mapIndexed { idx, scored ->
            val c = scored.candidate
            val attrSummary = c.attributes.take(4).joinToString("; ") { "${it.name}: ${it.value}" }
            buildString {
                append("[$idx] ${c.store.displayName} | \"${c.title.replace("\"", "'")}\"")
                if (c.categoryPath.isNotBlank()) append(" | Cat=${c.categoryPath}")
                if (attrSummary.isNotBlank()) append(" | [$attrSummary]")
            }
        }.joinToString("\n")

        val cleanTargetQuery = profile.searchQueriesPt.firstOrNull().orEmpty().ifBlank { profile.rawQuery }
        val prompt = """
            Você é o verificador de assertividade do ScanShop. Ignore preço, vendas e reputação.
            Busca original do usuário: "${profile.rawQuery}" | Produto buscado: "$cleanTargetQuery" (Item: ${profile.itemType}, Sinônimos aceitos: ${(profile.canonicalTermsPt + profile.canonicalTermsEn).take(6)}, Marca: ${profile.brand ?: "qualquer"}, Modelo: ${listOfNotNull(profile.line, profile.model).joinToString(" ").ifBlank { "qualquer" }}, Códigos: ${profile.alphanumericCodes}, Qualidades: ${profile.requiredQualities}).
            Quer acessório? ${if (profile.isAccessoryIntent) "SIM" else "NÃO — rejeite capas, controles remotos avulsos, barras de LED, suportes, cabos avulsos, telas/peças de reposição, smartwatches ou fones se buscou smartphone como ACCESSORY_OR_PART ou UNRELATED. ATENÇÃO: NÃO rejeite um aparelho completo só porque a ficha técnica no título cita 'Tela 6.6', 'Bateria 5000mAh' ou 'Carregador'; para buscas de chipset, modelo ou código técnico, aceite todos os produtos principais e kits compatíveis; aceite TODOS os modelos válidos da marca/categoria pedida em português ou inglês"}.
            Classifique cada índice como EXACT_MATCH, VARIANT_MATCH, ACCESSORY_OR_PART, WRONG_MODEL_OR_SPEC ou UNRELATED.

            $candidatesPayload
        """.trimIndent()

        return try {
            val root = withTimeout(60_000L) {
                val attemptBudgets = longArrayOf(32_000L, 24_000L)
                var lastError: Exception? = null
                var parsedRoot: JSONObject? = null
                for (attemptIdx in attemptBudgets.indices) {
                    val budgetMs = attemptBudgets[attemptIdx]
                    try {
                        val response = withTimeoutOrNull(budgetMs) {
                            if (attemptIdx == 0) {
                                verifierModel.generateContent(prompt)
                            } else {
                                fallbackVerifierModel.generateContent(prompt)
                            }
                        }
                        if (response == null) {
                            Log.w(TAG, "AI verifier attempt ${attemptIdx + 1} timed out after ${budgetMs}ms")
                            continue
                        }
                        val jsonText = response.text?.trim()?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("Resposta vazia do verificador IA.")
                        parsedRoot = JSONObject(jsonText)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "AI verifier attempt ${attemptIdx + 1} failed: ${describeThrowableChain(e)}")
                        if (attemptIdx < attemptBudgets.lastIndex) {
                            delay(if (isTransientNetworkError(e)) 800L else 250L)
                        }
                    }
                }
                parsedRoot ?: throw (lastError ?: IllegalStateException("Tempo limite na verificação por IA."))
            }
            val evaluations = root.optJSONArray("evaluations") ?: JSONArray()

            val evalByIndex = HashMap<Int, JSONObject>(evaluations.length())
            for (i in 0 until evaluations.length()) {
                val item = evaluations.optJSONObject(i) ?: continue
                val idx = item.optInt("index", -1)
                if (idx in batchPool.indices) {
                    evalByIndex[idx] = item
                }
            }

            val approved = mutableListOf<MatchedProduct>()
            batchPool.forEachIndexed { idx, scored ->
                val eval = evalByIndex[idx]
                if (eval == null) {
                    if (!scored.hardRejected && scored.breakdown.neuralProbability >= 0.44f) {
                        approved.add(
                            MatchedProduct(
                                id = scored.candidate.id,
                                store = scored.candidate.store,
                                title = scored.candidate.title,
                                imageUrl = scored.candidate.imageUrl,
                                productUrl = scored.candidate.productUrl,
                                affiliateUrl = scored.candidate.affiliateUrl,
                                priceText = scored.candidate.priceText,
                                categoryLabel = scored.candidate.categoryPath,
                                matchedSpecs = scored.candidate.attributes.take(3).map { "${it.name}: ${it.value}" },
                                assertivenessScore = scored.breakdown.neuralProbability,
                                matchVerdict = MatchVerdict.VARIANT_MATCH,
                                matchReason = "Correspondência validada pela Rede Neural.",
                                featureBreakdown = scored.breakdown
                            )
                        )
                    }
                    return@forEachIndexed
                }
                val verdictStr = eval.optString("verdict", "UNRELATED")
                val matchVerdict = when (verdictStr) {
                    "EXACT_MATCH" -> MatchVerdict.EXACT_MATCH
                    "VARIANT_MATCH" -> MatchVerdict.VARIANT_MATCH
                    else -> MatchVerdict.REJECTED
                }
                if (matchVerdict == MatchVerdict.REJECTED) return@forEachIndexed

                val aiAssert = (eval.optInt("assertiveness", 0).coerceIn(0, 100)) / 100f
                if (aiAssert < 0.35f) return@forEachIndexed

                val reasonPt = eval.optString("reasonPt", "").trim()
                    .ifBlank { "Correspondência validada por IA e Rede Neural." }
                val matchedSpecs = jsonArrayToList(eval.optJSONArray("matchedSpecs"))
                    .ifEmpty {
                        scored.candidate.attributes.take(3).map { "${it.name}: ${it.value}" }
                    }

                val fusedScore = (0.62f * aiAssert + 0.38f * scored.breakdown.neuralProbability)
                    .coerceIn(0f, 1f)

                approved.add(
                    MatchedProduct(
                        id = scored.candidate.id,
                        store = scored.candidate.store,
                        title = scored.candidate.title,
                        imageUrl = scored.candidate.imageUrl,
                        productUrl = scored.candidate.productUrl,
                        affiliateUrl = scored.candidate.affiliateUrl,
                        priceText = scored.candidate.priceText,
                        categoryLabel = scored.candidate.categoryPath,
                        matchedSpecs = matchedSpecs,
                        assertivenessScore = fusedScore,
                        matchVerdict = matchVerdict,
                        matchReason = reasonPt,
                        featureBreakdown = scored.breakdown
                    )
                )
            }

            val sorted = approved.sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.assertivenessScore }
            )
            AiVerificationOutcome(matches = sorted, usedAi = true)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI verification timed out", e)
            fallbackToNeuralOnly(batchPool, "Verificação por IA atingiu tempo limite; filtro neural aplicado.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = describeThrowableChain(e)
            Log.e(TAG, "AI verification failed: $detail", e)
            fallbackToNeuralOnly(batchPool, "Verificação por IA indisponível; filtro neural aplicado.")
        }
    }

    private fun fallbackToNeuralOnly(
        pool: List<NeuralScoredCandidate>,
        notice: String
    ): AiVerificationOutcome {
        val neuralApproved = pool
            .filter { !it.hardRejected && it.breakdown.neuralProbability >= 0.28f }
            .map { scored ->
                val verdict = if (scored.breakdown.neuralProbability >= 0.72f) {
                    MatchVerdict.EXACT_MATCH
                } else {
                    MatchVerdict.VARIANT_MATCH
                }
                MatchedProduct(
                    id = scored.candidate.id,
                    store = scored.candidate.store,
                    title = scored.candidate.title,
                    imageUrl = scored.candidate.imageUrl,
                    productUrl = scored.candidate.productUrl,
                    affiliateUrl = scored.candidate.affiliateUrl,
                    priceText = scored.candidate.priceText,
                    categoryLabel = scored.candidate.categoryPath,
                    matchedSpecs = scored.candidate.attributes.take(3).map { "${it.name}: ${it.value}" },
                    assertivenessScore = scored.breakdown.neuralProbability,
                    matchVerdict = verdict,
                    matchReason = "Validado pela Rede Neural (semântica + marca + núcleo anti-acessório).",
                    featureBreakdown = scored.breakdown
                )
            }
            .sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.assertivenessScore }
            )
        return AiVerificationOutcome(matches = neuralApproved, usedAi = false, notice = notice)
    }

    fun buildHeuristicProfile(
        rawQuery: String,
        hints: List<DomainDiscoveryHint>
    ): QueryIntentProfile {
        val cleanQuery = TextNormalizer.normalizeSearchQueryText(rawQuery)
        val tokens = TextNormalizer.tokenize(cleanQuery, dropStopWords = true)
        val codes = TextNormalizer.extractAlphanumericCodes(cleanQuery)
        val primaryHint = hints.firstOrNull()

        val normalizedCleanQuery = TextNormalizer.normalize(cleanQuery)
        val brand = primaryHint?.attributes
            ?.firstOrNull { it.name.equals("Marca", true) || it.name.equals("BRAND", true) }
            ?.value
            ?.takeIf { brandVal ->
                val normB = TextNormalizer.normalize(brandVal)
                normB.isNotBlank() && normalizedCleanQuery.contains(normB)
            }
        val line = primaryHint?.attributes
            ?.firstOrNull { it.name.equals("Linha", true) || it.name.equals("LINE", true) }
            ?.value
            ?.takeIf { lineVal ->
                val normL = TextNormalizer.normalize(lineVal)
                normL.isNotBlank() && normalizedCleanQuery.contains(normL)
            }
        val model = primaryHint?.attributes
            ?.firstOrNull { it.name.equals("Modelo", true) || it.name.equals("MODEL", true) }
            ?.value
            ?.takeIf { modelVal ->
                val normM = TextNormalizer.normalize(modelVal)
                normM.isNotBlank() && normalizedCleanQuery.contains(normM)
            }

        val accessoryMarkers = setOf("capa", "capinha", "pelicula", "cabo", "carregador", "adaptador", "suporte", "pulseira", "estojo", "almofada", "case", "controle")
        val isAccessory = tokens.any { it in accessoryMarkers }
        val itemType = primaryHint?.domainName?.takeIf { it.isNotBlank() }
            ?: tokens.firstOrNull { it !in codes && !it.equals(brand, true) }
            ?: cleanQuery

        val ptTerms = listOfNotNull(itemType, primaryHint?.domainName, primaryHint?.categoryName)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val hintTermQuery = ptTerms.firstOrNull { !cleanQuery.contains(it, ignoreCase = true) }
            ?.let { term ->
                (listOfNotNull(term, brand, line, model) + codes)
                    .distinct()
                    .joinToString(" ")
                    .trim()
            }
        val ptQueries = listOfNotNull(cleanQuery, hintTermQuery, rawQuery.trim())
            .filter { it.isNotBlank() }
            .distinctBy { TextNormalizer.normalize(it) }
            .take(3)
        val enTerms = listOfNotNull(brand, line, model).plus(codes).filter { it.isNotBlank() }.distinct()
        val enQueries = listOf(enTerms.joinToString(" ").trim().ifBlank { cleanQuery }).distinct()

        return QueryIntentProfile(
            rawQuery = rawQuery.trim(),
            itemType = itemType,
            canonicalTermsPt = ptTerms,
            canonicalTermsEn = enTerms,
            brand = brand,
            line = line,
            model = model,
            alphanumericCodes = codes,
            requiredQualities = codes,
            isAccessoryIntent = isAccessory,
            expectedDomains = hints,
            searchQueriesPt = ptQueries,
            searchQueriesEn = enQueries
        )
    }

    internal fun isTransientNetworkError(error: Throwable): Boolean {
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is UnknownHostException || cur is SocketTimeoutException || cur is IOException) {
                return true
            }
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("unavailable") || msg.contains("timeout") || msg.contains("429") ||
                msg.contains("503") || msg.contains("500") || msg.contains("rate") || msg.contains("quota") ||
                msg.contains("high demand") || msg.contains("spikes") || msg.contains("overloaded") ||
                msg.contains("temporarily")
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun describeThrowableChain(error: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 4) {
            val name = cur::class.java.simpleName.ifBlank { "Throwable" }
            val msg = cur.message?.take(120)?.trim().orEmpty()
            parts += if (msg.isNotBlank()) "$name($msg)" else name
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i, "").trim().takeIf { it.isNotBlank() }
        }
    }
}
