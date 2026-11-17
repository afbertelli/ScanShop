package com.example.data.search

import com.example.ai.GeminiQueryAndVerifierEngine
import com.example.data.model.MatchVerdict
import com.example.data.model.MatchedProduct
import com.example.data.model.NeuralFeatureBreakdown
import com.example.data.model.QueryIntentProfile
import com.example.data.model.RawStoreCandidate
import com.example.data.model.SearchPipelineStage
import com.example.data.model.SearchSessionResult
import com.example.data.model.StoreFetchReport
import com.example.data.model.StoreType
import com.example.data.network.AliExpressClient
import com.example.data.network.MercadoLivreClient
import com.example.data.network.ShopeeClient
import com.example.data.network.StoreFetchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RealTimeSearchOrchestrator(
    val mercadoLivreClient: MercadoLivreClient = MercadoLivreClient(),
    private val shopeeClient: ShopeeClient = ShopeeClient(),
    private val aliExpressClient: AliExpressClient = AliExpressClient(),
    private val neuralMatcher: NeuralProductMatcher = NeuralProductMatcher(),
    private val geminiEngine: GeminiQueryAndVerifierEngine = GeminiQueryAndVerifierEngine()
) {

    private data class CachedIntentEntry(
        val profile: QueryIntentProfile,
        val timestampMs: Long
    )

    private val intentCacheLock = Any()
    private val intentCache = object : LinkedHashMap<String, CachedIntentEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedIntentEntry>?): Boolean =
            size > INTENT_CACHE_MAX_ENTRIES
    }

    suspend fun executeSearch(
        rawQuery: String,
        onStageChanged: (SearchPipelineStage) -> Unit
    ): SearchSessionResult = withContext(Dispatchers.Default) {
        val cleanRaw = rawQuery.trim()
        val normalizedQuery = TextNormalizer.normalizeSearchQueryText(cleanRaw)
        val cacheKey = cleanRaw.lowercase()
        val nowMs = System.currentTimeMillis()
        val cachedIntent = synchronized(intentCacheLock) {
            intentCache[cacheKey]?.takeIf { (nowMs - it.timestampMs) <= INTENT_CACHE_TTL_MS }
        }

        // Stage 1: Fetch Mercado Livre domain hints + OAuth warmup + AI intent understanding
        onStageChanged(SearchPipelineStage.AnalyzingIntent)
        val tokenWarmupJob = async {
            withTimeoutOrNull(2_000L) { mercadoLivreClient.warmupOAuthToken() }
        }
        val (intentProfile, intentNotice) = if (cachedIntent != null) {
            cachedIntent.profile to null
        } else {
            val domainHints = withTimeoutOrNull(1_800L) {
                mercadoLivreClient.discoverDomainHints(cleanRaw)
            }.orEmpty()
            val (profile, notice) = geminiEngine.understandQuery(cleanRaw, domainHints)
            if (notice == null && cacheKey.isNotBlank()) {
                synchronized(intentCacheLock) {
                    intentCache[cacheKey] = CachedIntentEntry(profile, System.currentTimeMillis())
                }
            }
            profile to notice
        }
        tokenWarmupJob.start()

        // Stage 2: Query all 3 stores in real time in parallel
        onStageChanged(SearchPipelineStage.FetchingStores)
        val queriesPt = intentProfile.searchQueriesPt.ifEmpty { listOf(normalizedQuery) }
        val queriesEn = intentProfile.searchQueriesEn.ifEmpty { listOf(normalizedQuery) }

        val mlDeferred = async {
            withTimeoutOrNull(46_000L) {
                mercadoLivreClient.searchProducts(cleanRaw, queriesPt)
            } ?: StoreFetchOutcome(emptyList(), "Mercado Livre demorou para responder. Tente novamente.", hasMore = false)
        }
        val shopeeDeferred = async {
            withTimeoutOrNull(28_000L) {
                shopeeClient.searchProducts(queriesPt)
            } ?: StoreFetchOutcome(emptyList(), "Shopee demorou para responder. Tente novamente.")
        }
        val aliDeferred = async {
            withTimeoutOrNull(28_000L) {
                aliExpressClient.searchProducts(queriesPt, queriesEn)
            } ?: StoreFetchOutcome(emptyList(), "AliExpress demorou para responder. Tente novamente.")
        }

        val mlOutcome = mlDeferred.await()
        val shopeeOutcome = shopeeDeferred.await()
        val aliOutcome = aliDeferred.await()

        val otherRawCandidates = (shopeeOutcome.candidates + aliOutcome.candidates)
            .distinctBy { it.id }

        // Stage 3: Neural relevance filtering for Mercado Livre; Neural + AI verification for Shopee/AliExpress
        onStageChanged(SearchPipelineStage.NeuralAndAiScoring)
        val mlApproved = filterAndScoreMercadoLivreCandidates(intentProfile, mlOutcome.candidates)
        val neuralScored = neuralMatcher.scoreAndFilter(intentProfile, otherRawCandidates)
        val aiOutcome = geminiEngine.verifyCandidates(intentProfile, neuralScored)

        val combinedMatches = mergeAndSortSearchResults(mlApproved, aiOutcome.matches)
        val approvedByStore = combinedMatches.groupBy { it.store }
        val neuralByStore = neuralScored.groupBy { it.candidate.store }

        fun buildStoreDiagnostic(
            store: StoreType,
            outcome: StoreFetchOutcome
        ): Pair<String?, String> {
            val rawCount = outcome.candidates.size
            val approvedCount = approvedByStore[store]?.size ?: 0
            if (rawCount == 0) {
                val baseMsg = outcome.statusMessage ?: "${store.displayName}: 0 anúncios retornados."
                return baseMsg to "${store.displayName}: 0 coletados — $baseMsg"
            }
            if (store == StoreType.MERCADO_LIVRE) {
                val detail = "${store.displayName}: $rawCount anúncios coletados -> $approvedCount aprovados."
                val cleanEmptyMsg = "Nenhum anúncio compatível encontrado em ${store.displayName} para esta busca."
                val statusMsg = outcome.statusMessage ?: if (approvedCount == 0) cleanEmptyMsg else null
                return statusMsg to detail
            }
            val storeScored = neuralByStore[store].orEmpty()
            val neuralRejected = storeScored.filter { it.hardRejected }
            val topRejectReason = neuralRejected
                .mapNotNull { it.hardRejectReason }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            val detail = if (approvedCount > 0) {
                "${store.displayName}: $rawCount anúncios coletados -> $approvedCount aprovados."
            } else {
                val sampleTitles = storeScored.take(2)
                    .joinToString(" | ") { "\"${it.candidate.title.take(42)}...\"" }
                val reasonSuffix = topRejectReason?.let { " Motivo: $it" } ?: ""
                val sampleSuffix = if (sampleTitles.isNotBlank()) " Exemplos: $sampleTitles" else ""
                "${store.displayName}: $rawCount coletados, nenhum item compatível aprovado.$reasonSuffix$sampleSuffix"
            }
            val cleanEmptyMsg = "Nenhum anúncio compatível encontrado em ${store.displayName} para esta busca."
            val statusMsg = outcome.statusMessage ?: if (approvedCount == 0) cleanEmptyMsg else null
            return statusMsg to detail
        }

        val (mlStatus, mlDetail) = buildStoreDiagnostic(StoreType.MERCADO_LIVRE, mlOutcome)
        val (spStatus, spDetail) = buildStoreDiagnostic(StoreType.SHOPEE, shopeeOutcome)
        val (aeStatus, aeDetail) = buildStoreDiagnostic(StoreType.ALIEXPRESS, aliOutcome)

        val storeReports = listOf(
            StoreFetchReport(
                store = StoreType.MERCADO_LIVRE,
                rawCandidatesCount = mlOutcome.candidates.size,
                approvedCount = approvedByStore[StoreType.MERCADO_LIVRE]?.size ?: 0,
                statusMessage = mlStatus,
                diagnosticDetail = mlDetail,
                hasMore = mlOutcome.hasMore
            ),
            StoreFetchReport(
                store = StoreType.SHOPEE,
                rawCandidatesCount = shopeeOutcome.candidates.size,
                approvedCount = approvedByStore[StoreType.SHOPEE]?.size ?: 0,
                statusMessage = spStatus,
                diagnosticDetail = spDetail
            ),
            StoreFetchReport(
                store = StoreType.ALIEXPRESS,
                rawCandidatesCount = aliOutcome.candidates.size,
                approvedCount = approvedByStore[StoreType.ALIEXPRESS]?.size ?: 0,
                statusMessage = aeStatus,
                diagnosticDetail = aeDetail
            )
        )

        val combinedNotice = listOfNotNull(intentNotice, aiOutcome.notice)
            .distinct()
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

        onStageChanged(SearchPipelineStage.Completed)

        SearchSessionResult(
            intentProfile = intentProfile,
            products = combinedMatches,
            storeReports = storeReports,
            usedAiVerifier = aiOutcome.usedAi,
            aiNotice = combinedNotice
        )
    }

    suspend fun verifySingleStoreCandidates(
        currentSession: SearchSessionResult,
        store: StoreType,
        candidates: List<RawStoreCandidate>
    ): SearchSessionResult = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext currentSession

        if (store == StoreType.MERCADO_LIVRE) {
            val mlApproved = filterAndScoreMercadoLivreCandidates(currentSession.intentProfile, candidates)
            val updatedProducts = mergeAndSortSearchResults(
                mlApproved,
                currentSession.products.filter { it.store != StoreType.MERCADO_LIVRE }
            )
            val detail = "${store.displayName}: ${candidates.size} anúncios coletados -> ${mlApproved.size} aprovados."
            val updatedReports = currentSession.storeReports.map { report ->
                if (report.store == store) {
                    report.copy(
                        rawCandidatesCount = candidates.size,
                        approvedCount = mlApproved.size,
                        statusMessage = if (mlApproved.isEmpty()) detail else null,
                        diagnosticDetail = detail
                    )
                } else {
                    report
                }
            }
            return@withContext currentSession.copy(
                products = updatedProducts,
                storeReports = updatedReports
            )
        }

        val neuralScored = neuralMatcher.scoreAndFilter(currentSession.intentProfile, candidates)
        val aiOutcome = geminiEngine.verifyCandidates(currentSession.intentProfile, neuralScored)
        val newStoreMatches = aiOutcome.matches.filter { it.store == store }

        val updatedProducts = (currentSession.products.filter { it.store != store } + newStoreMatches)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.assertivenessScore }
            )

        val neuralRejected = neuralScored.filter { it.hardRejected }
        val topRejectReason = neuralRejected
            .mapNotNull { it.hardRejectReason }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val passedNeuralCount = neuralScored.count { !it.hardRejected && it.breakdown.neuralProbability >= 0.28f }
        val evaluatedByAiCount = if (passedNeuralCount >= 5) {
            passedNeuralCount.coerceAtMost(25)
        } else {
            (passedNeuralCount + (candidates.size - passedNeuralCount).coerceAtMost(18)).coerceAtMost(25)
        }
        val aiRejectedCount = (evaluatedByAiCount - newStoreMatches.size).coerceAtLeast(0)

        val detail = if (newStoreMatches.isNotEmpty()) {
            "${store.displayName}: ${candidates.size} anúncios coletados -> ${newStoreMatches.size} aprovados (${neuralRejected.size} barrados na Rede Neural, $aiRejectedCount pelo Gemini)."
        } else {
            val sampleTitles = neuralScored.take(2)
                .joinToString(" | ") { "\"${it.candidate.title.take(42)}...\"" }
            val reasonSuffix = topRejectReason?.let { " Motivo: $it" } ?: ""
            val sampleSuffix = if (sampleTitles.isNotBlank()) " Exemplos: $sampleTitles" else ""
            "${store.displayName}: ${candidates.size} coletados -> 0 exatos (${neuralRejected.size} barrados na Rede Neural, $aiRejectedCount pelo Gemini).$reasonSuffix$sampleSuffix"
        }

        val updatedReports = currentSession.storeReports.map { report ->
            if (report.store == store) {
                report.copy(
                    rawCandidatesCount = candidates.size,
                    approvedCount = newStoreMatches.size,
                    statusMessage = if (newStoreMatches.isEmpty()) detail else null,
                    diagnosticDetail = detail
                )
            } else {
                report
            }
        }

        currentSession.copy(
            products = updatedProducts,
            storeReports = updatedReports,
            usedAiVerifier = currentSession.usedAiVerifier || aiOutcome.usedAi
        )
    }

    suspend fun fetchWeeklyBestDeals(): com.example.data.model.PromoSessionResult = withContext(Dispatchers.Default) {
        val mlDeferred = async { mercadoLivreClient.fetchWeeklyBestSellers() }
        val shopeeDeferred = async { shopeeClient.fetchWeeklyBestDeals() }
        val aliDeferred = async { aliExpressClient.fetchWeeklyHotProducts() }

        val mlOutcome = mlDeferred.await()
        val shopeeOutcome = shopeeDeferred.await()
        val aliOutcome = aliDeferred.await()

        val mlProducts = candidatesToPromoProducts(mlOutcome.candidates)
        val spProducts = candidatesToPromoProducts(shopeeOutcome.candidates)
        val aeProducts = candidatesToPromoProducts(aliOutcome.candidates)

        val reports = listOf(
            StoreFetchReport(
                store = StoreType.MERCADO_LIVRE,
                rawCandidatesCount = mlOutcome.candidates.size,
                approvedCount = mlProducts.size,
                statusMessage = mlOutcome.statusMessage
            ),
            StoreFetchReport(
                store = StoreType.SHOPEE,
                rawCandidatesCount = shopeeOutcome.candidates.size,
                approvedCount = spProducts.size,
                statusMessage = shopeeOutcome.statusMessage
            ),
            StoreFetchReport(
                store = StoreType.ALIEXPRESS,
                rawCandidatesCount = aliOutcome.candidates.size,
                approvedCount = aeProducts.size,
                statusMessage = aliOutcome.statusMessage
            )
        )

        com.example.data.model.PromoSessionResult(
            products = (mlProducts + spProducts + aeProducts).distinctBy { it.id },
            storeReports = reports,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun candidatesToPromoProducts(
        candidates: List<com.example.data.model.RawStoreCandidate>
    ): List<com.example.data.model.MatchedProduct> {
        return candidates.distinctBy { it.id }.take(40).map { candidate ->
            com.example.data.model.MatchedProduct(
                id = candidate.id,
                store = candidate.store,
                title = candidate.title,
                imageUrl = candidate.imageUrl,
                productUrl = candidate.productUrl,
                affiliateUrl = candidate.affiliateUrl,
                deepLinkUrl = "",
                priceText = candidate.priceText,
                categoryLabel = candidate.categoryPath,
                matchedSpecs = emptyList(),
                assertivenessScore = 1f,
                matchVerdict = com.example.data.model.MatchVerdict.EXACT_MATCH,
                matchReason = "Mais vendido da semana",
                featureBreakdown = neutralPromoBreakdown
            )
        }
    }

    suspend fun loadMoreSearchResults(
        currentSession: SearchSessionResult,
        store: StoreType,
        nextPage: Int
    ): Triple<SearchSessionResult, Int, Boolean> = withContext(Dispatchers.Default) {
        val rawQuery = currentSession.intentProfile.rawQuery.trim()
        val existingIds = currentSession.products.mapTo(HashSet()) { it.id }
        val queriesPt = currentSession.intentProfile.searchQueriesPt.ifEmpty {
            listOf(TextNormalizer.normalizeSearchQueryText(rawQuery))
        }
        val queriesEn = currentSession.intentProfile.searchQueriesEn.ifEmpty { queriesPt }

        if (store == StoreType.MERCADO_LIVRE) {
            var currentPage = nextPage.coerceAtLeast(2)
            var lastAttemptedPage = currentPage - 1
            var attempts = 0
            var totalFresh = 0
            var lastHasMore = true
            val collectedMl = mutableListOf<MatchedProduct>()

            while (collectedMl.isEmpty() && attempts < 2 && currentPage <= 10) {
                attempts++
                val pageToFetch = currentPage
                lastAttemptedPage = pageToFetch
                val outcome = withTimeoutOrNull(34_000L) {
                    mercadoLivreClient.searchProductsPage(
                        query = rawQuery,
                        page = pageToFetch,
                        queryVariants = queriesPt
                    )
                }
                if (outcome == null) {
                    return@withContext Triple(currentSession, pageToFetch - 1, true)
                }
                lastHasMore = outcome.hasMore
                val freshCandidates = outcome.candidates.filter { it.id !in existingIds }
                val pageProducts = filterAndScoreMercadoLivreCandidates(currentSession.intentProfile, freshCandidates)
                    .filter { existingIds.add(it.id) }
                totalFresh += freshCandidates.size
                collectedMl.addAll(pageProducts)
                if (collectedMl.isNotEmpty() || !outcome.hasMore) break
                currentPage++
            }

            if (collectedMl.isEmpty()) {
                val updatedReports = currentSession.storeReports.map { report ->
                    if (report.store == store) report.copy(hasMore = lastHasMore) else report
                }
                return@withContext Triple(
                    currentSession.copy(storeReports = updatedReports),
                    lastAttemptedPage.coerceIn(1, 10),
                    lastHasMore
                )
            }
            val appendedProducts = (currentSession.products + collectedMl).distinctBy { it.id }
            val updatedReports = currentSession.storeReports.map { report ->
                if (report.store == store) {
                    report.copy(
                        rawCandidatesCount = report.rawCandidatesCount + totalFresh,
                        approvedCount = appendedProducts.count { it.store == store },
                        statusMessage = null,
                        hasMore = lastHasMore
                    )
                } else {
                    report
                }
            }
            return@withContext Triple(
                currentSession.copy(
                    products = appendedProducts,
                    storeReports = updatedReports
                ),
                lastAttemptedPage.coerceIn(1, 10),
                lastHasMore
            )
        }

        val collectedMatches = mutableListOf<MatchedProduct>()

        // Fetch live page(s); if a page only overlaps already-shown items or yields few matches, advance automatically up to 3 pages
        var currentPage = nextPage.coerceAtLeast(2)
        var attempts = 0
        var consecutiveEmptyRaw = 0
        var totalFreshCandidates = 0
        var stillHasMore = true

        while (collectedMatches.size < 10 && attempts < 3 && currentPage <= 12) {
            val targetPage = currentPage
            val pageTimeoutMs = 22_000L
            val outcome = withTimeoutOrNull(pageTimeoutMs) {
                when (store) {
                    StoreType.SHOPEE -> shopeeClient.searchProductsPage(
                        queries = queriesPt,
                        page = targetPage,
                        excludeIds = existingIds
                    )
                    StoreType.ALIEXPRESS -> aliExpressClient.searchProductsPage(
                        queriesPt = queriesPt,
                        queriesEn = queriesEn,
                        page = targetPage
                    )
                    StoreType.MERCADO_LIVRE -> StoreFetchOutcome(emptyList())
                }
            }
            if (outcome == null) {
                stillHasMore = currentPage < 12
                break
            }
            if (outcome.candidates.isEmpty()) {
                consecutiveEmptyRaw++
                if (consecutiveEmptyRaw >= 2) {
                    stillHasMore = false
                    break
                }
                attempts++
                currentPage++
                continue
            }
            consecutiveEmptyRaw = 0

            attempts++
            val freshCandidates = outcome.candidates.filter { it.id !in existingIds }
            if (freshCandidates.isEmpty()) {
                currentPage++
                continue
            }

            totalFreshCandidates += freshCandidates.size
            val neuralScored = neuralMatcher.scoreAndFilter(currentSession.intentProfile, freshCandidates)
            val verifiedPage = geminiEngine.verifyAdditionalCandidates(
                profile = currentSession.intentProfile,
                overflowCandidates = neuralScored,
                limit = 25
            ).filter { it.store == store && existingIds.add(it.id) }
            collectedMatches.addAll(verifiedPage)

            if (collectedMatches.size >= 8) {
                stillHasMore = currentPage < 12
                break
            }
            currentPage++
        }

        if (collectedMatches.isEmpty()) {
            return@withContext Triple(currentSession, currentPage, stillHasMore && currentPage < 12)
        }

        val appendedProducts = (currentSession.products + collectedMatches).distinctBy { it.id }
        val updatedReports = currentSession.storeReports.map { report ->
            if (report.store == store) {
                report.copy(
                    rawCandidatesCount = report.rawCandidatesCount + totalFreshCandidates,
                    approvedCount = appendedProducts.count { it.store == store },
                    statusMessage = null
                )
            } else {
                report
            }
        }

        Triple(
            currentSession.copy(
                products = appendedProducts,
                storeReports = updatedReports
            ),
            currentPage,
            stillHasMore && currentPage < 12
        )
    }

    suspend fun retryWeeklyStore(
        currentPromo: com.example.data.model.PromoSessionResult,
        store: StoreType
    ): com.example.data.model.PromoSessionResult = withContext(Dispatchers.Default) {
        val outcome = withTimeoutOrNull(25_000L) {
            when (store) {
                StoreType.MERCADO_LIVRE -> mercadoLivreClient.fetchWeeklyBestSellers()
                StoreType.SHOPEE -> shopeeClient.fetchWeeklyBestDeals()
                StoreType.ALIEXPRESS -> aliExpressClient.fetchWeeklyHotProducts()
            }
        } ?: com.example.data.network.StoreFetchOutcome(
            candidates = emptyList(),
            statusMessage = "Tempo limite ao consultar ofertas de ${store.displayName}."
        )

        val freshProducts = candidatesToPromoProducts(outcome.candidates)
        val mergedProducts = (currentPromo.products.filter { it.store != store } + freshProducts)
            .distinctBy { it.id }
        val updatedReports = currentPromo.storeReports.map { report ->
            if (report.store == store) {
                report.copy(
                    rawCandidatesCount = outcome.candidates.size,
                    approvedCount = freshProducts.size,
                    statusMessage = outcome.statusMessage
                )
            } else {
                report
            }
        }

        currentPromo.copy(
            products = mergedProducts,
            storeReports = updatedReports,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    suspend fun loadMoreWeeklyDeals(
        currentPromo: com.example.data.model.PromoSessionResult,
        store: StoreType,
        nextPage: Int
    ): Pair<com.example.data.model.PromoSessionResult, Boolean> = withContext(Dispatchers.Default) {
        val existingIds = currentPromo.products.mapTo(HashSet()) { it.id }
        val outcome = withTimeoutOrNull(25_000L) {
            when (store) {
                StoreType.MERCADO_LIVRE -> mercadoLivreClient.fetchWeeklyBestSellersPage(nextPage)
                StoreType.SHOPEE -> shopeeClient.fetchWeeklyBestDealsPage(nextPage, excludeIds = existingIds)
                StoreType.ALIEXPRESS -> aliExpressClient.fetchWeeklyHotProductsPage(nextPage)
            }
        } ?: return@withContext currentPromo to (nextPage < 10)

        val freshCandidates = outcome.candidates.filter { it.id !in existingIds }
        if (freshCandidates.isEmpty()) {
            return@withContext currentPromo to (outcome.candidates.isNotEmpty() && nextPage < 10)
        }

        val newProducts = candidatesToPromoProducts(freshCandidates).filter { it.id !in existingIds }
        if (newProducts.isEmpty()) {
            return@withContext currentPromo to (nextPage < 10)
        }

        val appendedProducts = (currentPromo.products + newProducts).distinctBy { it.id }
        val updatedReports = currentPromo.storeReports.map { report ->
            if (report.store == store) {
                report.copy(
                    rawCandidatesCount = report.rawCandidatesCount + freshCandidates.size,
                    approvedCount = appendedProducts.count { it.store == store },
                    statusMessage = null
                )
            } else {
                report
            }
        }

        currentPromo.copy(
            products = appendedProducts,
            storeReports = updatedReports
        ) to (nextPage < 10)
    }

    suspend fun backfillStoreToMinimum(
        currentSession: SearchSessionResult,
        store: StoreType,
        targetCount: Int = 30,
        startPage: Int = 1
    ): Triple<SearchSessionResult, Int, Boolean> = withContext(Dispatchers.Default) {
        val rawQuery = currentSession.intentProfile.rawQuery.trim()
        val existingIds = currentSession.products.mapTo(HashSet()) { it.id }
        val newVerifiedForStore = mutableListOf<MatchedProduct>()
        var currentCount = currentSession.products.count { it.store == store }
        var highestPage = startPage
        var stillHasMore = if (store == StoreType.MERCADO_LIVRE) {
            currentSession.storeReports.firstOrNull { it.store == store }?.hasMore ?: (currentCount > 0)
        } else {
            currentCount > 0
        }

        if (currentCount >= targetCount) {
            return@withContext Triple(currentSession, highestPage, stillHasMore || store != StoreType.MERCADO_LIVRE)
        }

        // Fetch extra pages live until reaching targetCount (>= 30 offers)
        val queriesPt = currentSession.intentProfile.searchQueriesPt.ifEmpty {
            listOf(TextNormalizer.normalizeSearchQueryText(rawQuery))
        }
        val queriesEn = currentSession.intentProfile.searchQueriesEn.ifEmpty { queriesPt }

        val maxExtraPages = if (store == StoreType.MERCADO_LIVRE) 4 else 2
        val backfillTimeoutMs = if (store == StoreType.MERCADO_LIVRE) 34_000L else 22_000L
        var extraPageFetches = 0
        var totalRawAdded = 0
        var consecutiveEmptyFreshPages = 0

        while (currentCount < targetCount && extraPageFetches < maxExtraPages &&
            (currentCount > 0 || (store == StoreType.MERCADO_LIVRE && stillHasMore))
        ) {
            if (store == StoreType.MERCADO_LIVRE && !stillHasMore) break
            val nextPage = highestPage + 1
            extraPageFetches++
            val outcome = withTimeoutOrNull(backfillTimeoutMs) {
                when (store) {
                    StoreType.MERCADO_LIVRE -> mercadoLivreClient.searchProductsPage(
                        query = rawQuery,
                        page = nextPage,
                        queryVariants = queriesPt
                    )
                    StoreType.SHOPEE -> shopeeClient.searchProductsPage(
                        queries = queriesPt,
                        page = nextPage,
                        excludeIds = existingIds
                    )
                    StoreType.ALIEXPRESS -> aliExpressClient.searchProductsPage(
                        queriesPt = queriesPt,
                        queriesEn = queriesEn,
                        page = nextPage
                    )
                }
            } ?: break

            highestPage = nextPage
            if (outcome.candidates.isEmpty()) {
                stillHasMore = if (store == StoreType.MERCADO_LIVRE) outcome.hasMore else highestPage < 8
                break
            }

            if (store == StoreType.MERCADO_LIVRE) {
                stillHasMore = outcome.hasMore
            }

            val freshCandidates = outcome.candidates.filter { it.id !in existingIds }
            totalRawAdded += freshCandidates.size
            if (freshCandidates.isEmpty()) {
                consecutiveEmptyFreshPages++
                if (consecutiveEmptyFreshPages >= 2) break
                continue
            }

            val verifiedPage = if (store == StoreType.MERCADO_LIVRE) {
                filterAndScoreMercadoLivreCandidates(currentSession.intentProfile, freshCandidates)
                    .filter { it.id !in existingIds }
            } else {
                val scored = neuralMatcher.scoreAndFilter(currentSession.intentProfile, freshCandidates)
                geminiEngine.verifyAdditionalCandidates(
                    profile = currentSession.intentProfile,
                    overflowCandidates = scored,
                    limit = (targetCount - currentCount + 6).coerceAtLeast(20)
                ).filter { it.store == store && it.id !in existingIds }
            }

            var addedThisPage = 0
            verifiedPage.forEach { item ->
                if (existingIds.add(item.id)) {
                    newVerifiedForStore.add(item)
                    currentCount++
                    addedThisPage++
                }
            }
            if (addedThisPage == 0) {
                consecutiveEmptyFreshPages++
                if (consecutiveEmptyFreshPages >= 2) break
            } else {
                consecutiveEmptyFreshPages = 0
            }
        }

        val updatedProducts = if (newVerifiedForStore.isEmpty()) {
            currentSession.products
        } else {
            (currentSession.products + newVerifiedForStore).distinctBy { it.id }
        }
        val updatedReports = currentSession.storeReports.map { report ->
            if (report.store == store) {
                report.copy(
                    rawCandidatesCount = report.rawCandidatesCount + totalRawAdded,
                    approvedCount = updatedProducts.count { it.store == store },
                    statusMessage = if (updatedProducts.any { it.store == store }) null else report.statusMessage,
                    hasMore = if (store == StoreType.MERCADO_LIVRE) stillHasMore else report.hasMore
                )
            } else {
                report
            }
        }

        Triple(
            currentSession.copy(
                products = updatedProducts,
                storeReports = updatedReports
            ),
            highestPage,
            stillHasMore
        )
    }

    suspend fun retrySingleStore(
        currentSession: SearchSessionResult,
        store: StoreType
    ): SearchSessionResult = withContext(Dispatchers.Default) {
        val rawQuery = currentSession.intentProfile.rawQuery.trim()
        val queriesPt = currentSession.intentProfile.searchQueriesPt.ifEmpty {
            listOf(TextNormalizer.normalizeSearchQueryText(rawQuery))
        }
        val queriesEn = currentSession.intentProfile.searchQueriesEn.ifEmpty { queriesPt }

        val timeoutMs = if (store == StoreType.MERCADO_LIVRE) 46_000L else 28_000L
        val outcome = withTimeoutOrNull(timeoutMs) {
            when (store) {
                StoreType.MERCADO_LIVRE -> mercadoLivreClient.searchProducts(rawQuery, queriesPt)
                StoreType.SHOPEE -> shopeeClient.searchProducts(queriesPt)
                StoreType.ALIEXPRESS -> aliExpressClient.searchProducts(queriesPt, queriesEn)
            }
        } ?: StoreFetchOutcome(emptyList(), "${store.displayName} demorou para responder.")

        if (outcome.candidates.isEmpty()) {
            val updatedReports = currentSession.storeReports.map { report ->
                if (report.store == store) {
                    report.copy(
                        statusMessage = outcome.statusMessage ?: report.statusMessage,
                        hasMore = if (store == StoreType.MERCADO_LIVRE) outcome.hasMore else report.hasMore
                    )
                } else {
                    report
                }
            }
            return@withContext currentSession.copy(storeReports = updatedReports)
        }

        val approvedForStore: List<MatchedProduct> = if (store == StoreType.MERCADO_LIVRE) {
            filterAndScoreMercadoLivreCandidates(currentSession.intentProfile, outcome.candidates)
        } else {
            val scored = neuralMatcher.scoreAndFilter(currentSession.intentProfile, outcome.candidates)
            geminiEngine.verifyAdditionalCandidates(
                profile = currentSession.intentProfile,
                overflowCandidates = scored,
                limit = 30
            ).filter { it.store == store }
        }

        val updatedProducts = mergeAndSortSearchResults(
            if (store == StoreType.MERCADO_LIVRE) approvedForStore else currentSession.products.filter { it.store == StoreType.MERCADO_LIVRE },
            if (store == StoreType.MERCADO_LIVRE) currentSession.products.filter { it.store != StoreType.MERCADO_LIVRE } else {
                (currentSession.products.filter { it.store != store } + approvedForStore).distinctBy { it.id }
            }
        )
        val updatedReports = currentSession.storeReports.map { report ->
            if (report.store == store) {
                report.copy(
                    rawCandidatesCount = outcome.candidates.size,
                    approvedCount = approvedForStore.size,
                    statusMessage = if (approvedForStore.isEmpty()) {
                        outcome.statusMessage ?: "Nenhum anúncio compatível encontrado em ${store.displayName}."
                    } else null,
                    hasMore = if (store == StoreType.MERCADO_LIVRE) outcome.hasMore else report.hasMore
                )
            } else {
                report
            }
        }
        currentSession.copy(
            products = updatedProducts,
            storeReports = updatedReports
        )
    }

    private fun filterAndScoreMercadoLivreCandidates(
        profile: QueryIntentProfile,
        candidates: List<RawStoreCandidate>
    ): List<MatchedProduct> {
        if (candidates.isEmpty()) return emptyList()
        val distinctCandidates = candidates.distinctBy { it.id }
        val scored = neuralMatcher.scoreAndFilter(profile, distinctCandidates)
        val nonRejected = scored.filter { !it.hardRejected }
        val approvedScored = nonRejected.filter { it.breakdown.neuralProbability >= 0.24f }
            .ifEmpty {
                nonRejected.filter {
                    it.breakdown.semanticEmbeddingScore >= 0.25f || it.breakdown.alphanumericModelScore >= 0.50f
                }
            }

        return approvedScored
            .map { s ->
                val c = s.candidate
                val verdict = if (s.breakdown.neuralProbability >= 0.65f) {
                    MatchVerdict.EXACT_MATCH
                } else {
                    MatchVerdict.VARIANT_MATCH
                }
                val reason = if (s.breakdown.alphanumericModelScore >= 0.70f) {
                    "Modelo e categoria validados no Mercado Livre."
                } else {
                    "Correspondência validada no Mercado Livre."
                }
                MatchedProduct(
                    id = c.id,
                    store = c.store,
                    title = c.title,
                    imageUrl = c.imageUrl,
                    productUrl = c.productUrl,
                    affiliateUrl = c.affiliateUrl,
                    deepLinkUrl = "",
                    priceText = c.priceText,
                    categoryLabel = c.categoryPath,
                    matchedSpecs = c.attributes.take(3).map { "${it.name}: ${it.value}" },
                    assertivenessScore = s.breakdown.neuralProbability,
                    matchVerdict = verdict,
                    matchReason = reason,
                    featureBreakdown = s.breakdown
                )
            }
            .sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.priceText.isNotBlank() && it.imageUrl.isNotBlank() }
                    .thenByDescending { it.priceText.isNotBlank() }
                    .thenByDescending { it.assertivenessScore }
            )
    }

    private fun mergeAndSortSearchResults(
        mlProducts: List<MatchedProduct>,
        otherProducts: List<MatchedProduct>
    ): List<MatchedProduct> {
        val sortedOthers = otherProducts
            .distinctBy { it.id }
            .sortedWith(
                compareBy<MatchedProduct> { it.matchVerdict.ordinal }
                    .thenByDescending { it.assertivenessScore }
            )
        return (mlProducts + sortedOthers).distinctBy { it.id }
    }

    private companion object {
        const val INTENT_CACHE_MAX_ENTRIES = 16
        const val INTENT_CACHE_TTL_MS = 10L * 60L * 1000L

        val neutralPromoBreakdown = NeuralFeatureBreakdown(
            semanticEmbeddingScore = 1f,
            alphanumericModelScore = 1f,
            brandAndLineScore = 1f,
            itemHeadNounScore = 1f,
            specQualityScore = 1f,
            domainAlignmentScore = 1f,
            compatibilityTrapScore = 0f,
            sourceRelevanceSignal = 1f,
            neuralProbability = 1f
        )
    }
}
