package com.example.data.network

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import com.example.BuildConfig
import com.example.data.model.DomainDiscoveryHint
import com.example.data.model.PriceFormatter
import com.example.data.model.ProductAttribute
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.data.search.HtmlTextDecoder
import com.example.data.search.TextNormalizer
import com.example.ui.util.DeepLinkUtils
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "MercadoLivreClient"

data class StoreFetchOutcome(
    val candidates: List<RawStoreCandidate>,
    val statusMessage: String? = null,
    val hasMore: Boolean = false
)

/**
 * Mercado Livre client:
 * - Search (Busca) uses the official Mercado Livre API (/sites/MLB/search), falling back
 *   to the live search & grounding enrichment pipeline when PolicyAgent returns HTTP 403,
 *   and /products/search as last resort.
 * - Promoções (Melhores da Semana) uses live ML best-seller hubs, search engines, and grounding.
 */
class MercadoLivreClient(context: Context? = null) {

    private val appContext = context?.applicationContext
    private val client = NetworkClientProvider.httpClient
    private val webViewExtractor by lazy { MercadoLivreWebViewExtractor(appContext) }

    private val fastHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val quickApiClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val quickImageClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(11, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val quickPageClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val promoPageClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    // Concurrency permits for Search & Promoções enrichment
    private val bingSemaphore = Semaphore(10)
    private val bingImageSemaphore = Semaphore(10)
    private val groundingSemaphore = Semaphore(6)
    private val redirectSemaphore = Semaphore(20)
    private val apiMetadataSemaphore = Semaphore(10)
    private val pageFetchSemaphore = Semaphore(10)
    private val tokenMutex = Mutex()
    @Volatile
    private var memoryAccessToken: String? = null
    @Volatile
    private var memoryTokenExpiresAtMs: Long = 0L
    @Volatile
    private var lastOAuthError: String? = null
    @Volatile
    private var sitesSearchBlockedByPolicy: Boolean = false
    @Volatile
    private var lastGroundingError: String? = null

    private val groundedSearchModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch())
        )
    }

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val mobileUa: String by lazy {
        val raw = appContext?.let { ctx ->
            runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        raw?.replace("; wv)", ")")
            ?.replace("; wv ", "; ")
            ?.replace("Version/4.0 ", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private data class CachedHintsEntry(
        val hints: List<DomainDiscoveryHint>,
        val timestampMs: Long
    )

    private val domainHintsCacheLock = Any()
    private val domainHintsCache = object : LinkedHashMap<String, CachedHintsEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedHintsEntry>?): Boolean =
            size > DOMAIN_HINTS_CACHE_MAX_ENTRIES
    }

    suspend fun warmupOAuthToken() {
        if (hasConfiguredMlCredentials() && (memoryAccessToken.isNullOrBlank() || System.currentTimeMillis() >= memoryTokenExpiresAtMs)) {
            runCatching { getAccessToken(forceRefresh = false) }
        }
    }

    suspend fun discoverDomainHints(query: String): List<DomainDiscoveryHint> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()
            val cacheKey = clean.lowercase()
            val nowMs = System.currentTimeMillis()
            synchronized(domainHintsCacheLock) {
                domainHintsCache[cacheKey]?.takeIf { (nowMs - it.timestampMs) <= DOMAIN_HINTS_CACHE_TTL_MS }
            }?.let { return@withContext it.hints }

            val encoded = URLEncoder.encode(clean, "UTF-8")
            val req = Request.Builder()
                .url("https://api.mercadolibre.com/sites/MLB/domain_discovery/search?q=$encoded&limit=3")
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", mobileUa)
                .build()
            try {
                fastHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val body = resp.body?.bytes()?.let {
                        MercadoLivreHtmlParser.decodeHttpBytes(it, resp.body?.contentType()?.charset())
                    }.orEmpty()
                    if (!body.trimStart().startsWith("[")) return@withContext emptyList()
                    val arr = JSONArray(body)
                    val out = mutableListOf<DomainDiscoveryHint>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val domainId = obj.optString("domain_id", "").trim()
                        val domainName = obj.optString("domain_name", "").trim()
                        val catId = obj.optString("category_id", "").trim()
                        val catName = obj.optString("category_name", "").trim()
                        val attrsArr = obj.optJSONArray("attributes") ?: JSONArray()
                        val attrs = (0 until attrsArr.length()).mapNotNull { j ->
                            val a = attrsArr.optJSONObject(j) ?: return@mapNotNull null
                            val n = a.optString("name", a.optString("id", "")).trim()
                            val v = a.optString("value_name", "").trim()
                            if (n.isNotBlank() && v.isNotBlank()) ProductAttribute(n, v) else null
                        }
                        if (domainId.isNotBlank() || catName.isNotBlank()) {
                            out.add(DomainDiscoveryHint(domainId, domainName, catId, catName, attrs))
                        }
                    }
                    if (out.isNotEmpty()) {
                        synchronized(domainHintsCacheLock) {
                            domainHintsCache[cacheKey] = CachedHintsEntry(out, System.currentTimeMillis())
                        }
                    }
                    out
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun hasConfiguredMlCredentials(): Boolean {
        val clientId = BuildConfig.MERCADO_LIVRE_CLIENT_ID.trim().trim('"', '\'')
        val clientSecret = BuildConfig.MERCADO_LIVRE_CLIENT_SECRET.trim().trim('"', '\'')
        return clientId.isNotBlank() &&
            clientSecret.isNotBlank() &&
            !clientId.startsWith("YOUR_MERCADO_LIVRE", ignoreCase = true) &&
            !clientSecret.startsWith("YOUR_MERCADO_LIVRE", ignoreCase = true)
    }

    private suspend fun getAccessToken(forceRefresh: Boolean = false): String? {
        if (!hasConfiguredMlCredentials()) return null
        return tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceRefresh && !memoryAccessToken.isNullOrBlank() && now < memoryTokenExpiresAtMs) {
                return@withLock memoryAccessToken
            }
            val clientId = BuildConfig.MERCADO_LIVRE_CLIENT_ID.trim().trim('"', '\'')
            val clientSecret = BuildConfig.MERCADO_LIVRE_CLIENT_SECRET.trim().trim('"', '\'')
            val formBody = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()
            val req = Request.Builder()
                .url("https://api.mercadolibre.com/oauth/token")
                .post(formBody)
                .header("Accept", "application/json")
                .header("User-Agent", mobileUa)
                .build()
            try {
                fastHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.bytes()?.let {
                        MercadoLivreHtmlParser.decodeHttpBytes(it, resp.body?.contentType()?.charset())
                    }.orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "ML OAuth token request failed: HTTP ${resp.code}")
                        memoryAccessToken = null
                        memoryTokenExpiresAtMs = 0L
                        lastOAuthError = "OAuth HTTP ${resp.code}"
                        return@withLock null
                    }
                    if (!body.trimStart().startsWith("{")) {
                        lastOAuthError = "Resposta OAuth inválida"
                        return@withLock null
                    }
                    val json = JSONObject(body)
                    val token = json.optString("access_token", "").trim()
                    val expiresInSec = json.optLong("expires_in", 21_600L).coerceAtLeast(120L)
                    if (token.isNotBlank()) {
                        memoryAccessToken = token
                        memoryTokenExpiresAtMs = System.currentTimeMillis() + ((expiresInSec - 60L) * 1000L)
                        lastOAuthError = null
                        token
                    } else {
                        lastOAuthError = "Token ausente na resposta OAuth"
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ML OAuth token error", e)
                lastOAuthError = e.message ?: "Erro de conexão OAuth"
                null
            }
        }
    }

    private data class ApiGetResult(
        val code: Int,
        val body: String
    )

    private suspend fun executeAuthenticatedApiGet(
        url: String,
        credentialsConfigured: Boolean,
        httpClientOverride: okhttp3.OkHttpClient = fastHttpClient
    ): ApiGetResult {
        var token = getAccessToken(forceRefresh = false)
        for (attempt in 0..1) {
            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", mobileUa)
            if (!token.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $token")
            }
            httpClientOverride.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 401 && attempt == 0 && credentialsConfigured) {
                    token = getAccessToken(forceRefresh = true)
                    return@use
                }
                val body = resp.body?.bytes()?.let {
                    MercadoLivreHtmlParser.decodeHttpBytes(it, resp.body?.contentType()?.charset())
                }.orEmpty()
                return ApiGetResult(resp.code, body)
            }
        }
        return ApiGetResult(401, "")
    }

    suspend fun searchProducts(
        query: String,
        queryVariants: List<String> = emptyList()
    ): StoreFetchOutcome =
        searchProductsPage(query = query, page = 1, queryVariants = queryVariants)

    /**
     * Direct official Mercado Livre Search API call (/sites/MLB/search) with automatic fallback
     * to Promoções-grade live Mercado Livre search (ML listing pages + Search Engines + Google Search
     * Grounding + photo/price enrichment) whenever PolicyAgent restricts /sites/MLB/search with HTTP 403.
     */
    suspend fun searchProductsPage(
        query: String,
        page: Int,
        queryVariants: List<String> = emptyList()
    ): StoreFetchOutcome = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val expandedQueries = (queryVariants + cleanQuery)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { TextNormalizer.normalize(it) }
            .take(3)
        if (expandedQueries.isEmpty()) return@withContext StoreFetchOutcome(emptyList())

        val primaryQuery = expandedQueries.first()
        val targetPage = page.coerceAtLeast(1)
        val pageSize = 48
        val offset = (targetPage - 1) * pageSize
        val encodedQuery = URLEncoder.encode(primaryQuery, "UTF-8")
        val credentialsConfigured = hasConfiguredMlCredentials()

        val sitesUrl = "https://api.mercadolibre.com/sites/MLB/search?q=$encodedQuery&offset=$offset&limit=$pageSize"

        try {
            if (!sitesSearchBlockedByPolicy) {
                val sitesRes = executeAuthenticatedApiGet(sitesUrl, credentialsConfigured)
                if (sitesRes.code in 200..299) {
                    return@withContext parseSearchApiResponse(sitesRes.body, primaryQuery)
                }
                if (sitesRes.code == 403) {
                    // Mercado Livre PolicyAgent blocks /sites/MLB/search for general client_credentials tokens;
                    // use the same real-time search & enrichment pipeline used in Promoções.
                    sitesSearchBlockedByPolicy = true
                }
            }

            val liveOutcome = searchViaLivePromoPipeline(
                expandedQueries = expandedQueries,
                rawFallbackQuery = cleanQuery.ifBlank { primaryQuery },
                targetPage = targetPage,
                offset = offset
            )
            if (liveOutcome.candidates.isNotEmpty()) {
                return@withContext liveOutcome
            }

            // Last-resort fallback to official /products/search catalog ONLY if all live sources returned empty,
            // requiring at least one query token in the product title so unrelated OEM part matches are never shown.
            val catalogActiveUrl =
                "https://api.mercadolibre.com/products/search?status=active&site_id=MLB&q=$encodedQuery&offset=$offset&limit=$pageSize"
            val catalogRes = executeAuthenticatedApiGet(catalogActiveUrl, credentialsConfigured)
            if (catalogRes.code in 200..299) {
                val catOutcome = parseSearchApiResponse(catalogRes.body, primaryQuery)
                val queryTokens = TextNormalizer.tokenize(expandedQueries.joinToString(" ")).filter { it.length >= 2 }.toSet()
                val titleMatched = if (queryTokens.isEmpty()) {
                    catOutcome.candidates
                } else {
                    catOutcome.candidates.filter { cand ->
                        val titleTokens = TextNormalizer.tokenize(cand.title).toSet()
                        queryTokens.any { qt -> qt in titleTokens || TextNormalizer.normalize(cand.title).contains(qt) }
                    }
                }
                if (titleMatched.isNotEmpty()) {
                    val enriched = enrichCatalogPricesFromApi(titleMatched)
                    return@withContext catOutcome.copy(
                        candidates = enriched,
                        statusMessage = null
                    )
                }
            }

            return@withContext StoreFetchOutcome(
                candidates = emptyList(),
                statusMessage = "Nenhum anúncio encontrado no Mercado Livre para \"${cleanQuery.ifBlank { primaryQuery }}\" no momento.",
                hasMore = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Mercado Livre search failed", e)
            return@withContext StoreFetchOutcome(
                candidates = emptyList(),
                statusMessage = "Erro ao consultar o Mercado Livre: ${e.message ?: "falha de conexão"}.",
                hasMore = false
            )
        }
    }

    private suspend fun searchViaLivePromoPipeline(
        expandedQueries: List<String>,
        rawFallbackQuery: String,
        targetPage: Int,
        offset: Int
    ): StoreFetchOutcome = coroutineScope {
        val deadlineMs = System.currentTimeMillis() + (if (targetPage <= 1) 40_000L else 30_000L)
        val primaryQuery = expandedQueries.firstOrNull().orEmpty().ifBlank { rawFallbackQuery }
        val secondaryQuery = expandedQueries.getOrNull(1)
        val pageQuery = if (targetPage > 1 && expandedQueries.size > 1) {
            expandedQueries[(targetPage - 1) % expandedQueries.size]
        } else {
            primaryQuery
        }

        fun toMlSlug(q: String): String = TextNormalizer.normalize(q)
            .replace(mlSlugNonAlnumRegex, "-")
            .trim('-')

        val primarySlug = toMlSlug(pageQuery)
        val altSlug = secondaryQuery?.let(::toMlSlug)?.takeIf { it.isNotBlank() && it != primarySlug }

        // Keep OAuth token warm for /products/{id}/items & /items/{id} price enrichment even after /sites 403
        val tokenWarmupJob = launch {
            warmupOAuthToken()
        }

        val listingUrls = buildList {
            if (primarySlug.isNotBlank()) {
                val url = if (targetPage <= 1) {
                    "https://lista.mercadolivre.com.br/$primarySlug"
                } else {
                    "https://lista.mercadolivre.com.br/${primarySlug}_Desde_${offset + 1}"
                }
                add(url)
                if (targetPage <= 1 && altSlug != null) {
                    add("https://lista.mercadolivre.com.br/$altSlug")
                } else if (targetPage >= 2) {
                    add("https://lista.mercadolivre.com.br/${primarySlug}_Desde_${offset + 49}")
                }
            }
            if (targetPage <= 1) {
                add("https://www.mercadolivre.com.br/jm/search?as_word=${URLEncoder.encode(pageQuery, "UTF-8")}")
            }
        }

        var webViewBatch: List<RawStoreCandidate> = emptyList()
        val listingBatches = Array<List<RawStoreCandidate>>(listingUrls.size) { emptyList() }
        val collectedFallback = LinkedHashMap<String, RawStoreCandidate>()
        val harvestedTitlePrices = ConcurrentHashMap<String, String>()
        val mergeLock = Any()

        fun addFallbackBatch(items: List<RawStoreCandidate>) {
            if (items.isEmpty()) return
            synchronized(mergeLock) {
                items.forEach { MercadoLivreHtmlParser.mergeCandidate(collectedFallback, it) }
            }
        }

        val phase1BudgetMs = if (targetPage <= 1) 17_500L else 13_500L
        withTimeoutOrNull(phase1BudgetMs) {
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                val leadListingUrl = listingUrls.firstOrNull()
                if (!leadListingUrl.isNullOrBlank()) {
                    jobs += launch {
                        webViewBatch = webViewExtractor.fetchListingCandidates(
                            url = leadListingUrl,
                            query = pageQuery,
                            domainHint = "",
                            timeoutMs = if (targetPage <= 1) 9_500L else 7_500L
                        )
                    }
                }
                listingUrls.forEachIndexed { idx, listingUrl ->
                    jobs += launch {
                        val parsed = withTimeoutOrNull(8_000L) {
                            val html = fetchHtmlWithCookies(
                                url = listingUrl,
                                maxBytes = 3_500_000,
                                useDesktopUa = true,
                                clientOverride = promoPageClient
                            )
                            if (html.isNotBlank()) {
                                MercadoLivreHtmlParser.parseListingHtml(html, pageQuery, "")
                            } else emptyList()
                        }.orEmpty()
                        listingBatches[idx] = parsed
                    }
                }
                jobs += launch {
                    withTimeoutOrNull(13_000L) {
                        fetchViaSearchEngines(
                            query = pageQuery,
                            domainHint = "",
                            page = targetPage,
                            lightMode = false,
                            expandPool = true,
                            onBatch = ::addFallbackBatch
                        )
                    }
                }
                if (secondaryQuery != null && !secondaryQuery.equals(pageQuery, ignoreCase = true)) {
                    jobs += launch {
                        withTimeoutOrNull(11_000L) {
                            fetchViaSearchEngines(
                                query = secondaryQuery,
                                domainHint = "",
                                page = targetPage,
                                lightMode = true,
                                expandPool = false,
                                onBatch = ::addFallbackBatch
                            )
                        }
                    }
                }
                jobs += launch {
                    val groundedItems = withTimeoutOrNull(17_000L) {
                        fetchViaGoogleSearchGrounding(
                            query = pageQuery,
                            domainHint = "",
                            page = targetPage,
                            excludeUrls = emptySet(),
                            maxAiTimeoutMs = if (targetPage <= 1) 12_500L else 9_500L,
                            maxRedirectTimeoutMs = 4_000L,
                            harvestedPricesOut = harvestedTitlePrices,
                            skipHubPageFallback = true,
                            desiredCountLabel = if (targetPage <= 1) "24 a 32" else "18 a 26"
                        )
                    }.orEmpty()
                    addFallbackBatch(groundedItems)
                }
                if (targetPage <= 1) {
                    jobs += launch {
                        val altGroundQuery = secondaryQuery ?: "$primaryQuery preco mercado livre"
                        val extraGrounded = withTimeoutOrNull(17_000L) {
                            fetchViaGoogleSearchGrounding(
                                query = altGroundQuery,
                                domainHint = "",
                                page = 1,
                                excludeUrls = emptySet(),
                                maxAiTimeoutMs = 12_500L,
                                maxRedirectTimeoutMs = 4_000L,
                                harvestedPricesOut = harvestedTitlePrices,
                                skipHubPageFallback = true,
                                desiredCountLabel = "20 a 28"
                            )
                        }.orEmpty()
                        addFallbackBatch(extraGrounded)
                    }
                }
                jobs.forEach { it.join() }
            }
        }
        withTimeoutOrNull(600L) { tokenWarmupJob.join() }

        val merged = LinkedHashMap<String, RawStoreCandidate>()
        webViewBatch.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }
        listingBatches.forEach { batch ->
            batch.forEach { item ->
                MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
            }
        }
        collectedFallback.values.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }

        if (merged.isEmpty()) {
            return@coroutineScope StoreFetchOutcome(emptyList(), hasMore = false)
        }

        val enriched = prioritizeCandidates(
            enrichMissingImagesAndPrices(
                candidates = merged.values.toList(),
                fallbackSearchHint = primaryQuery,
                deadlineMs = deadlineMs,
                preHarvestedPrices = harvestedTitlePrices,
                includePricePageEnrichment = true,
                parallelPriceEnrichment = true
            )
        ).map { item ->
            item.copy(
                affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(item.productUrl)
            )
        }

        StoreFetchOutcome(
            candidates = enriched,
            statusMessage = null,
            hasMore = enriched.isNotEmpty() && targetPage < 10
        )
    }

    private suspend fun enrichCatalogPricesFromApi(
        candidates: List<RawStoreCandidate>
    ): List<RawStoreCandidate> {
        val indicesNeedingInfo = candidates.mapIndexedNotNull { idx, item ->
            if ((item.priceText.isBlank() || item.imageUrl.isBlank()) && item.id.startsWith("ml_MLB")) idx else null
        }.take(25)
        if (indicesNeedingInfo.isEmpty()) return candidates

        val token = getAccessToken(forceRefresh = false)
        val updates = ConcurrentHashMap<Int, Pair<String, String>>()

        withTimeoutOrNull(4_500L) {
            coroutineScope {
                indicesNeedingInfo.map { idx ->
                    async {
                        val item = candidates[idx]
                        val prodId = item.id.removePrefix("ml_").trim()
                        if (prodId.isBlank()) return@async
                        apiMetadataSemaphore.withPermit {
                            val (resolvedPrice, resolvedImg) = fetchCatalogProductPriceAndImage(
                                productId = prodId,
                                token = token,
                                needPrice = item.priceText.isBlank(),
                                needImage = item.imageUrl.isBlank()
                            )
                            if (resolvedPrice.isNotBlank() || resolvedImg.isNotBlank()) {
                                updates[idx] = resolvedPrice to resolvedImg
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        if (updates.isEmpty()) return candidates
        return candidates.mapIndexed { idx, item ->
            val patch = updates[idx] ?: return@mapIndexed item
            val newPrice = patch.first.ifBlank { item.priceText }
            val newImg = patch.second.ifBlank { item.imageUrl }
            if (newPrice == item.priceText && newImg == item.imageUrl) {
                item
            } else {
                item.copy(priceText = newPrice, imageUrl = newImg)
            }
        }
    }

    private fun fetchCatalogProductPriceAndImage(
        productId: String,
        token: String?,
        needPrice: Boolean,
        needImage: Boolean
    ): Pair<String, String> {
        var priceText = ""
        var imageUrl = ""

        if (needPrice) {
            runCatching {
                val reqBuilder = Request.Builder()
                    .url("https://api.mercadolibre.com/products/$productId/items?limit=10")
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", mobileUa)
                if (!token.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }
                quickApiClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.bytes()?.let {
                            MercadoLivreHtmlParser.decodeHttpBytes(it, resp.body?.contentType()?.charset())
                        }.orEmpty()
                        if (body.trimStart().startsWith("{")) {
                            val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
                            var bestPrice = Double.MAX_VALUE
                            for (i in 0 until results.length()) {
                                val row = results.optJSONObject(i) ?: continue
                                val currency = row.optString("currency_id", "BRL").trim()
                                if (currency.isNotBlank() && !currency.equals("BRL", ignoreCase = true)) continue
                                val p = row.optDouble("price", Double.NaN)
                                if (p.isFinite() && p > 0.0 && p < bestPrice) {
                                    bestPrice = p
                                }
                            }
                            if (bestPrice < Double.MAX_VALUE) {
                                priceText = PriceFormatter.formatBrl(bestPrice)
                            }
                        }
                    }
                }
            }
        }

        if ((needPrice && priceText.isBlank()) || needImage) {
            runCatching {
                val reqBuilder = Request.Builder()
                    .url("https://api.mercadolibre.com/products/$productId")
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", mobileUa)
                if (!token.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }
                quickApiClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.bytes()?.let {
                            MercadoLivreHtmlParser.decodeHttpBytes(it, resp.body?.contentType()?.charset())
                        }.orEmpty()
                        if (body.trimStart().startsWith("{")) {
                            val obj = JSONObject(body)
                            if (needPrice && priceText.isBlank()) {
                                val bbPrice = obj.optJSONObject("buy_box_winner")
                                    ?.optDouble("price", Double.NaN) ?: Double.NaN
                                if (bbPrice.isFinite() && bbPrice > 0.0) {
                                    priceText = PriceFormatter.formatBrl(bbPrice)
                                }
                            }
                            if (needImage && imageUrl.isBlank()) {
                                imageUrl = extractApiImageUrl(obj)
                            }
                        }
                    }
                }
            }
        }

        return priceText to imageUrl
    }

    private fun extractApiImageUrl(obj: JSONObject): String {
        val pictures = obj.optJSONArray("pictures")
        val firstPictureObj = if (pictures != null && pictures.length() > 0) pictures.optJSONObject(0) else null
        val rawPictureUrl = firstPictureObj?.optString("secure_url", "")?.trim().orEmpty()
            .ifBlank { firstPictureObj?.optString("url", "")?.trim().orEmpty() }
        val firstPictureId = firstPictureObj?.optString("id", "")?.trim().orEmpty()
        val rawThumb = obj.optString("secure_thumbnail", "").trim()
            .ifBlank { obj.optString("thumbnail", "").trim() }
            .ifBlank { rawPictureUrl }
        val thumbId = obj.optString("thumbnail_id", "").trim()
            .ifBlank { firstPictureId }
        return when {
            rawThumb.startsWith("https://", ignoreCase = true) -> rawThumb
            rawThumb.startsWith("http://", ignoreCase = true) -> "https://" + rawThumb.substring(7)
            rawThumb.startsWith("//") -> "https:$rawThumb"
            thumbId.isNotBlank() -> "https://http2.mlstatic.com/D_NQ_NP_${thumbId}-O.jpg"
            else -> ""
        }
    }

    internal fun parseSearchApiResponse(
        jsonBody: String,
        query: String
    ): StoreFetchOutcome {
        if (!jsonBody.trimStart().startsWith("{")) {
            return StoreFetchOutcome(
                candidates = emptyList(),
                statusMessage = "Resposta inválida da API do Mercado Livre.",
                hasMore = false
            )
        }
        return try {
            val root = JSONObject(jsonBody)
            val results = root.optJSONArray("results") ?: JSONArray()
            val paging = root.optJSONObject("paging")
            val total = paging?.optInt("total", 0) ?: 0
            val offset = paging?.optInt("offset", 0) ?: 0
            val hasMore = results.length() > 0 && (offset + results.length()) < total

            val candidates = ArrayList<RawStoreCandidate>(results.length())
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val itemId = obj.optString("id", "").trim()
                val title = obj.optString("title", "")
                    .ifBlank { obj.optString("name", "") }
                    .trim()
                val buyBox = obj.optJSONObject("buy_box_winner")
                val rawPermalink = obj.optString("permalink", "").trim()
                    .ifBlank { buyBox?.optString("permalink", "").orEmpty().trim() }
                val buyBoxItemId = buyBox?.optString("item_id", "").orEmpty().trim()
                val permalink = when {
                    rawPermalink.startsWith("https://", ignoreCase = true) -> rawPermalink
                    rawPermalink.startsWith("http://", ignoreCase = true) -> "https://" + rawPermalink.substring(7)
                    itemId.isNotBlank() -> "https://www.mercadolivre.com.br/p/$itemId"
                    buyBoxItemId.isNotBlank() -> "https://produto.mercadolivre.com.br/$buyBoxItemId"
                    else -> ""
                }
                if (title.isBlank() || permalink.isBlank()) continue

                val imageUrl = extractApiImageUrl(obj)

                val directPrice = obj.optDouble("price", Double.NaN)
                val saleAmount = obj.optJSONObject("sale_price")?.optDouble("amount", Double.NaN) ?: Double.NaN
                val buyBoxPrice = buyBox?.optDouble("price", Double.NaN) ?: Double.NaN
                val priceVal = when {
                    directPrice.isFinite() && directPrice > 0.0 -> directPrice
                    saleAmount.isFinite() && saleAmount > 0.0 -> saleAmount
                    buyBoxPrice.isFinite() && buyBoxPrice > 0.0 -> buyBoxPrice
                    else -> 0.0
                }
                val priceText = PriceFormatter.formatBrl(priceVal)
                val categoryId = obj.optString("category_id", "").trim()
                val domainId = obj.optString("domain_id", "").trim()
                val attrsArr = obj.optJSONArray("attributes") ?: JSONArray()
                val attrs = (0 until attrsArr.length()).mapNotNull { j ->
                    val a = attrsArr.optJSONObject(j) ?: return@mapNotNull null
                    val n = a.optString("name", a.optString("id", "")).trim()
                    val v = a.optString("value_name", "").trim()
                    if (n.isNotBlank() && v.isNotBlank()) ProductAttribute(n, v) else null
                }

                candidates.add(
                    RawStoreCandidate(
                        id = if (itemId.isNotBlank()) "ml_$itemId" else "ml_${permalink.hashCode()}",
                        store = StoreType.MERCADO_LIVRE,
                        title = title,
                        imageUrl = imageUrl,
                        productUrl = permalink,
                        affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(permalink),
                        priceText = priceText,
                        categoryPath = categoryId,
                        domainId = domainId,
                        attributes = attrs,
                        sourceQueryVariant = query,
                        sourceRank = i
                    )
                )
            }

            val status = if (candidates.isEmpty()) {
                "Nenhum anúncio encontrado na API do Mercado Livre para esta busca."
            } else null
            StoreFetchOutcome(candidates = candidates, statusMessage = status, hasMore = hasMore)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Mercado Livre API JSON", e)
            StoreFetchOutcome(
                candidates = emptyList(),
                statusMessage = "Erro ao ler resposta da API do Mercado Livre.",
                hasMore = false
            )
        }
    }

    private data class PromoHub(
        val url: String,
        val label: String,
        val catId: String,
        val searchTerm: String,
        val groundingTopic: String
    )

    private val promoHubs = listOf(
        PromoHub(
            url = "https://www.mercadolivre.com.br/mais-vendidos",
            label = "Mais Vendidos",
            catId = "MLB1051",
            searchTerm = "mais vendidos mercado livre ofertas",
            groundingTopic = "produtos mais vendidos da semana"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/celulares-e-telefones",
            label = "Celulares",
            catId = "MLB1051",
            searchTerm = "smartphone celular fone bluetooth",
            groundingTopic = "smartphones fones bluetooth e smartwatch mais vendidos"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/informatica",
            label = "Informática",
            catId = "MLB1648",
            searchTerm = "notebook ssd monitor teclado mouse",
            groundingTopic = "notebooks SSDs monitores e perifericos mais vendidos"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/eletrodomesticos",
            label = "Eletrodomésticos",
            catId = "MLB5726",
            searchTerm = "air fryer cafeteira aspirador geladeira",
            groundingTopic = "air fryer cafeteiras e eletrodomesticos mais vendidos"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/eletronicos-audio-e-video",
            label = "Eletrônicos",
            catId = "MLB1000",
            searchTerm = "smart tv caixa som jbl alexa",
            groundingTopic = "smart tvs caixas de som bluetooth e eletronicos"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/ferramentas",
            label = "Ferramentas",
            catId = "MLB1574",
            searchTerm = "furadeira parafusadeira kit ferramentas",
            groundingTopic = "furadeiras parafusadeiras e ferramentas mais vendidas"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/games",
            label = "Games",
            catId = "MLB1144",
            searchTerm = "playstation xbox nintendo switch controle",
            groundingTopic = "consoles controles e jogos mais vendidos"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/esportes-e-fitness",
            label = "Esportes",
            catId = "MLB1276",
            searchTerm = "tenis corrida creatina bicicleta esteira",
            groundingTopic = "tenis esportivos suplementos e artigos fitness"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/beleza-e-cuidado-pessoal",
            label = "Beleza",
            catId = "MLB1246",
            searchTerm = "perfume secador chapinha hidratante",
            groundingTopic = "perfumes secadores e cuidados pessoais mais vendidos"
        ),
        PromoHub(
            url = "https://www.mercadolivre.com.br/c/acessorios-para-veiculos",
            label = "Automotivo",
            catId = "MLB5672",
            searchTerm = "pneu som automotivo multimidia capacete",
            groundingTopic = "acessorios automotivos multimidia e capacetes"
        )
    )

    suspend fun fetchWeeklyBestSellers(): StoreFetchOutcome =
        fetchWeeklyBestSellersPage(page = 1)

    suspend fun fetchWeeklyBestSellersPage(page: Int): StoreFetchOutcome = withContext(Dispatchers.IO) {
        val targetPage = page.coerceAtLeast(1)
        val deadlineMs = System.currentTimeMillis() + 30_000L

        val selectedHubs = if (targetPage <= 1) {
            promoHubs.take(4)
        } else {
            val extraOffset = 4 + ((targetPage - 2) * 3)
            val rotated = (0 until 3).map { i -> promoHubs[(extraOffset + i) % promoHubs.size] }.distinct()
            if (targetPage == 2) {
                listOf(promoHubs.first().copy(url = "https://www.mercadolivre.com.br/mais-vendidos?page=2")) + rotated.take(2)
            } else {
                rotated
            }
        }
        val leadHub = selectedHubs.first()
        val altHub = selectedHubs.last()

        var webViewBestBatch: List<RawStoreCandidate> = emptyList()
        val hubBatches = Array<List<RawStoreCandidate>>(selectedHubs.size) { emptyList() }
        val collected = LinkedHashMap<String, RawStoreCandidate>()
        val harvestedTitlePrices = ConcurrentHashMap<String, String>()
        val mergeLock = Any()
        fun addBatch(items: List<RawStoreCandidate>) {
            if (items.isEmpty()) return
            synchronized(mergeLock) {
                items.forEach { MercadoLivreHtmlParser.mergeCandidate(collected, it) }
            }
        }

        // Launch WebView lead hub + verified desktop ML hubs + search engines + Google Search Grounding in parallel
        withTimeoutOrNull(18_500L) {
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                jobs += launch {
                    webViewBestBatch = webViewExtractor.fetchListingCandidates(
                        url = leadHub.url,
                        query = "mais_vendidos_p$targetPage",
                        domainHint = leadHub.label,
                        timeoutMs = 9_000L
                    )
                }
                selectedHubs.forEachIndexed { idx, hub ->
                    jobs += launch {
                        val parsed = withTimeoutOrNull(14_500L) {
                            val html = fetchHtmlWithCookies(
                                url = hub.url,
                                maxBytes = 3_500_000,
                                useDesktopUa = true,
                                clientOverride = promoPageClient
                            )
                            if (html.isNotBlank()) {
                                MercadoLivreHtmlParser.parseListingHtml(html, "mais_vendidos_p$targetPage", hub.label)
                            } else emptyList()
                        }.orEmpty()
                        hubBatches[idx] = parsed
                    }
                }
                jobs += launch {
                    withTimeoutOrNull(9_000L) {
                        fetchViaSearchEngines(leadHub.searchTerm, leadHub.catId, page = targetPage, onBatch = ::addBatch)
                    }
                }
                jobs += launch {
                    val items = withTimeoutOrNull(18_000L) {
                        fetchViaGoogleSearchGrounding(
                            query = "${leadHub.groundingTopic} ofertas mais vendidos do dia",
                            domainHint = leadHub.catId,
                            page = targetPage,
                            excludeUrls = emptySet(),
                            maxAiTimeoutMs = 13_000L,
                            maxRedirectTimeoutMs = 4_000L,
                            harvestedPricesOut = harvestedTitlePrices
                        )
                    }.orEmpty()
                    addBatch(items)
                }
                jobs.forEach { it.join() }
            }
        }

        // Round-robin interleave category hubs so Promoções displays balanced variety across categories
        val interleaved = LinkedHashMap<String, RawStoreCandidate>()
        val allPromoBatches = (listOf(webViewBestBatch) + hubBatches.toList()).filter { it.isNotEmpty() }
        if (allPromoBatches.isNotEmpty()) {
            val maxLen = allPromoBatches.maxOf { it.size }
            for (row in 0 until maxLen) {
                for (batch in allPromoBatches) {
                    val item = batch.getOrNull(row) ?: continue
                    val ranked = item.copy(sourceRank = interleaved.size)
                    MercadoLivreHtmlParser.mergeCandidate(interleaved, ranked)
                }
            }
        }
        val extraFromFallback = collected.values.mapIndexed { idx, item ->
            item.copy(sourceRank = interleaved.size + idx)
        }
        extraFromFallback.forEach { MercadoLivreHtmlParser.mergeCandidate(interleaved, it) }

        if (interleaved.size < 6 && (deadlineMs - System.currentTimeMillis()) > 4_000L) {
            withTimeoutOrNull(4_000L) {
                fetchViaSearchEngines(altHub.searchTerm, altHub.catId, page = 1) { items ->
                    synchronized(mergeLock) {
                        items.forEach { MercadoLivreHtmlParser.mergeCandidate(interleaved, it) }
                    }
                }
            }
        }

        val enriched = prioritizeCandidates(
            enrichMissingImagesAndPrices(
                candidates = interleaved.values.toList(),
                fallbackSearchHint = leadHub.searchTerm,
                deadlineMs = deadlineMs,
                preHarvestedPrices = harvestedTitlePrices
            )
        )
        val status = if (enriched.isEmpty()) {
            "Mercado Livre não retornou ofertas da semana no momento."
        } else null

        StoreFetchOutcome(candidates = enriched, statusMessage = status)
    }

    private suspend fun fetchViaSearchEngines(
        query: String,
        domainHint: String,
        page: Int,
        lightMode: Boolean = false,
        expandPool: Boolean = false,
        onBatch: (List<RawStoreCandidate>) -> Unit = {}
    ): List<RawStoreCandidate> = coroutineScope {
        val clean = query.trim()
        if (clean.isBlank()) return@coroutineScope emptyList()
        val siteExpr = URLEncoder.encode("site:mercadolivre.com.br $clean", "UTF-8")
        val pricedSiteExpr = URLEncoder.encode("site:mercadolivre.com.br $clean \"R$\"", "UTF-8")
        val prodExpr = URLEncoder.encode("site:produto.mercadolivre.com.br $clean", "UTF-8")
        val catExpr = URLEncoder.encode("site:mercadolivre.com.br/p/ $clean", "UTF-8")
        val bingOffset = ((page - 1).coerceAtLeast(0) * 20) + 1
        val bingNextOffset = bingOffset + 10
        val yahooOffset = ((page - 1).coerceAtLeast(0) * 20) + 1

        val combined = LinkedHashMap<String, RawStoreCandidate>()
        val lock = Any()
        fun mergeItems(items: List<RawStoreCandidate>) {
            if (items.isEmpty()) return
            synchronized(lock) { items.forEach { MercadoLivreHtmlParser.mergeCandidate(combined, it) } }
            onBatch(items)
        }

        // Launch live image search concurrently
        val imgDeferred = async {
            withTimeoutOrNull(if (expandPool) 7_500L else 5_500L) {
                if (lightMode) {
                    fetchBingImages("$clean mercadolivre", bingOffset)
                } else if (expandPool) {
                    val p1 = async { fetchBingImages("site:mercadolivre.com.br $clean", bingOffset) }
                    val p2 = async { fetchBingImages("$clean mercadolivre", bingOffset + 20) }
                    val p3 = if (page <= 1) async { fetchBingImages("$clean oferta mercadolivre", bingOffset + 40) } else null
                    (p1.await() + p2.await() + p3?.await().orEmpty())
                        .ifEmpty { fetchBingImages("$clean mercadolivre", bingOffset) }
                } else {
                    fetchBingImages("site:mercadolivre.com.br $clean", bingOffset)
                        .ifEmpty { fetchBingImages("$clean mercadolivre", bingOffset) }
                }
            }.orEmpty()
        }

        withTimeoutOrNull(if (lightMode) 7_000L else 12_000L) {
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                jobs += launch {
                    val html = bingSemaphore.withPermit {
                        fetchSimpleHtml("https://www.bing.com/search?q=$siteExpr&cc=BR&setlang=pt-BR&count=25&first=$bingOffset")
                    }
                    mergeItems(MercadoLivreHtmlParser.parseBingWebHtml(html, clean, domainHint))
                }
                if (!lightMode) {
                    jobs += launch {
                        val html = bingSemaphore.withPermit {
                            fetchSimpleHtml("https://www.bing.com/search?q=$siteExpr&cc=BR&setlang=pt-BR&count=20&first=$bingNextOffset")
                        }
                        mergeItems(MercadoLivreHtmlParser.parseBingWebHtml(html, clean, domainHint))
                    }
                    jobs += launch {
                        val pricedHtml = bingSemaphore.withPermit {
                            fetchSimpleHtml("https://www.bing.com/search?q=$pricedSiteExpr&cc=BR&setlang=pt-BR&count=25&first=$bingOffset")
                        }
                        mergeItems(MercadoLivreHtmlParser.parseBingWebHtml(pricedHtml, clean, domainHint))
                    }
                }
                if (page <= 1) {
                    jobs += launch {
                        val rss = bingSemaphore.withPermit {
                            fetchSimpleHtml("https://www.bing.com/search?q=$siteExpr&format=rss&count=30")
                        }
                        mergeItems(MercadoLivreHtmlParser.parseBingRssXml(rss, clean, domainHint))
                    }
                    if (!lightMode) {
                        jobs += launch {
                            val prodRss = bingSemaphore.withPermit {
                                fetchSimpleHtml("https://www.bing.com/search?q=$prodExpr&format=rss&count=25")
                            }
                            mergeItems(MercadoLivreHtmlParser.parseBingRssXml(prodRss, clean, domainHint))
                        }
                        jobs += launch {
                            val catHtml = bingSemaphore.withPermit {
                                fetchSimpleHtml("https://www.bing.com/search?q=$catExpr&cc=BR&setlang=pt-BR&count=20")
                            }
                            mergeItems(MercadoLivreHtmlParser.parseBingWebHtml(catHtml, clean, domainHint))
                        }
                        jobs += launch {
                            val ddgHtml = fetchSimpleHtml("https://html.duckduckgo.com/html/?q=$siteExpr&kl=br-pt")
                            mergeItems(MercadoLivreHtmlParser.parseDuckDuckGoHtml(ddgHtml, clean, domainHint))
                        }
                    }
                }
                jobs += launch {
                    val html = fetchSimpleHtml("https://br.search.yahoo.com/search?p=$siteExpr&n=20&b=$yahooOffset")
                    mergeItems(MercadoLivreHtmlParser.parseYahooHtml(html, clean, domainHint))
                }
                if (expandPool) {
                    jobs += launch {
                        val html2 = fetchSimpleHtml("https://br.search.yahoo.com/search?p=$siteExpr&n=20&b=${yahooOffset + 20}")
                        mergeItems(MercadoLivreHtmlParser.parseYahooHtml(html2, clean, domainHint))
                    }
                    jobs += launch {
                        val startParam = ((page - 1).coerceAtLeast(0)) * 20
                        val googleUrl = if (startParam > 0) {
                            "https://www.google.com/search?q=$siteExpr&hl=pt-BR&gl=BR&num=20&start=$startParam"
                        } else {
                            "https://www.google.com/search?q=$siteExpr&hl=pt-BR&gl=BR&num=20"
                        }
                        val googleWebHtml = withTimeoutOrNull(4_500L) {
                            fetchSimpleHtml(googleUrl)
                        }.orEmpty()
                        mergeItems(MercadoLivreHtmlParser.parseGoogleWebHtml(googleWebHtml, clean, domainHint))
                    }
                }
                jobs.forEach { it.join() }
            }
        }

        if (!expandPool && synchronized(lock) { combined.size } < 6 && page <= 1) {
            val googleWebHtml = withTimeoutOrNull(3_800L) {
                fetchSimpleHtml("https://www.google.com/search?q=$siteExpr&hl=pt-BR&gl=BR&num=20")
            }.orEmpty()
            mergeItems(MercadoLivreHtmlParser.parseGoogleWebHtml(googleWebHtml, clean, domainHint))
        }

        val snapshotHits = withTimeoutOrNull(2_000L) { imgDeferred.await() }.orEmpty()
        if (snapshotHits.isNotEmpty()) {
            val extraImageItems = mutableListOf<RawStoreCandidate>()
            val triggerSize = if (expandPool) 56 else 24
            val capSize = if (expandPool) 68 else 30
            synchronized(lock) {
                val updated = MercadoLivreHtmlParser.applyImageHits(combined.values.toList(), snapshotHits)
                combined.clear()
                updated.forEach { MercadoLivreHtmlParser.mergeCandidate(combined, it) }

                if (combined.size < triggerSize) {
                    for (hit in snapshotHits) {
                        if (combined.size >= capSize) break
                        val normalizedUrl = MercadoLivreHtmlParser.normalizeProductUrl(hit.pageUrl)
                        if (!MercadoLivreHtmlParser.isValidProductUrl(normalizedUrl)) continue
                        val title = MercadoLivreHtmlParser.sanitizeCandidateTitle(hit.title, normalizedUrl)
                        val img = hit.preferredImageUrl
                        if (title.length < 6 || img.isBlank()) continue
                        if (MercadoLivreHtmlParser.scoreCompatibleImageHit(clean, hit) <= 0.0 &&
                            !img.contains("mlstatic.com", true)
                        ) {
                            continue
                        }
                        if (MercadoLivreHtmlParser.scoreCompatibleImageHit(title, hit) <= 0.0) {
                            continue
                        }
                        val cand = RawStoreCandidate(
                            id = MercadoLivreHtmlParser.canonicalCandidateId(normalizedUrl, "bi"),
                            store = StoreType.MERCADO_LIVRE,
                            title = title,
                            imageUrl = img,
                            productUrl = normalizedUrl,
                            affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(normalizedUrl),
                            priceText = "",
                            categoryPath = domainHint,
                            domainId = domainHint,
                            sourceQueryVariant = clean,
                            sourceRank = combined.size
                        )
                        val sizeBefore = combined.size
                        MercadoLivreHtmlParser.mergeCandidate(combined, cand)
                        if (combined.size > sizeBefore) extraImageItems.add(cand)
                    }
                }
            }
            if (extraImageItems.isNotEmpty()) onBatch(extraImageItems)
        }
        synchronized(lock) { combined.values.toList() }
    }

    private suspend fun fetchViaGoogleSearchGrounding(
        query: String,
        domainHint: String,
        page: Int,
        excludeUrls: Set<String>,
        maxAiTimeoutMs: Long = 21_000L,
        maxRedirectTimeoutMs: Long = 4_200L,
        harvestedPricesOut: ConcurrentHashMap<String, String>? = null,
        skipHubPageFallback: Boolean = false,
        desiredCountLabel: String = "10 a 16"
    ): List<RawStoreCandidate> {
        return try {
            val pageHint = if (page > 1) " Traga anúncios diferentes dos primeiros resultados (página $page)." else ""
            val prompt = """
                Pesquise no Google anúncios reais e ativos no Mercado Livre Brasil (mercadolivre.com.br) para: "$query".$pageHint
                Retorne SOMENTE um array JSON no formato:
                [{"title":"nome completo do produto anunciado","url":"https://...mercadolivre.com.br/...","price":"R$ 489,90"}]
                Regras estritas:
                - Inclua de $desiredCountLabel anúncios individuais reais encontrados na Busca do Google no Mercado Livre Brasil (páginas com /p/MLB, /up/MLBU, produto.mercadolivre.com.br ou MLB-). NUNCA retorne links genéricos de categorias, lista.mercadolivre, /ofertas ou /mais-vendidos.
                - Em "title", escreva SOMENTE o nome legível do produto em português com acentos corretos. NUNCA coloque URL, https, www ou domínio em "title".
                - IMPORTANTE: Preencha "price" com o preço real em R$ exibido na Busca do Google (priorize o preço no Mercado Livre Brasil e, se o snippet do ML omitir R$, use o preço real do mesmo produto/modelo em reais exibido na Busca do Google Brasil). Use formato "R$ 489,90" ou "R$ 1.299,00"; nunca invente nem use "R$ 0,00".
            """.trimIndent()

            val response = withTimeoutOrNull(maxAiTimeoutMs) {
                groundingSemaphore.withPermit {
                    groundedSearchModel.generateContent(prompt)
                }
            } ?: return emptyList()

            val rawText = response.text.orEmpty()
            val out = LinkedHashMap<String, RawStoreCandidate>()
            val listPageUrls = mutableListOf<String>()

            val parsedArr = MercadoLivreHtmlParser.extractFirstValidJsonArray(rawText)
            val rawItems = if (parsedArr != null && parsedArr.length() > 0) {
                (0 until parsedArr.length()).mapNotNull { parsedArr.optJSONObject(it) }
            } else emptyList()

            // Always harvest title -> price pairs upfront so even items whose redirect drops enrich matching ML cards
            if (harvestedPricesOut != null) {
                for (obj in rawItems) {
                    val t = HtmlTextDecoder.decode(obj.optString("title", "")).trim()
                    val fmtP = MercadoLivreHtmlParser.parseJsonPriceField(
                        obj.optString("price", "")
                            .ifBlank { obj.optString("preco", "") }
                            .ifBlank { obj.optString("valor", "") }
                    )
                    if (t.length >= 6 && fmtP.isNotBlank()) {
                        harvestedPricesOut.putIfAbsent(TextNormalizer.normalize(t), fmtP)
                    }
                }
            }

            val groundingMeta = response.candidates.firstOrNull()?.groundingMetadata
            val chunks = groundingMeta?.groundingChunks.orEmpty()
            val priceByChunkIdx = HashMap<Int, String>()
            for (support in groundingMeta?.groundingSupports.orEmpty()) {
                val segText = support.segment?.text.orEmpty()
                val segPrice = MercadoLivreHtmlParser.extractBrlPrice(segText)
                if (segPrice.isNotBlank()) {
                    for (cIdx in support.groundingChunkIndices) {
                        priceByChunkIdx.putIfAbsent(cIdx, segPrice)
                    }
                }
            }

            // Single concurrent redirect resolution pass covering JSON item URLs + groundingChunks
            val allRawRedirects = LinkedHashSet<String>()
            rawItems.forEach { obj ->
                val rawUrl = obj.optString("url", "").trim()
                if (rawUrl.contains("vertexaisearch.cloud.google.com", true)) allRawRedirects += rawUrl
            }
            chunks.take(32).forEach { chunk ->
                val uri = chunk.web?.uri?.trim().orEmpty()
                if (uri.contains("vertexaisearch.cloud.google.com", true)) allRawRedirects += uri
            }

            val resolvedMap = ConcurrentHashMap<String, String>()
            if (allRawRedirects.isNotEmpty()) {
                withTimeoutOrNull(maxRedirectTimeoutMs) {
                    coroutineScope {
                        allRawRedirects.map { redirectUrl ->
                            async {
                                redirectSemaphore.withPermit {
                                    val resolved = resolveRedirectTarget(redirectUrl).orEmpty()
                                    if (resolved.isNotBlank()) resolvedMap[redirectUrl] = resolved
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            fun resolveUrl(rawUrl: String): String {
                val target = if (rawUrl.contains("vertexaisearch.cloud.google.com", true)) {
                    resolvedMap[rawUrl].orEmpty()
                } else rawUrl
                return MercadoLivreHtmlParser.normalizeProductUrl(target)
            }

            fun trackListHub(finalUrl: String) {
                if (finalUrl.contains("mercadolivre.com.br/c/", true) ||
                    finalUrl.contains("/mais-vendidos", true) ||
                    finalUrl.contains("lista.mercadolivre.com.br", true)
                ) {
                    listPageUrls.add(finalUrl)
                }
            }

            for (obj in rawItems) {
                val finalUrl = resolveUrl(obj.optString("url", "").trim())
                trackListHub(finalUrl)
                if (!MercadoLivreHtmlParser.isValidProductUrl(finalUrl) || finalUrl in excludeUrls) continue
                val rawTitle = HtmlTextDecoder.decode(obj.optString("title", ""))
                val price = MercadoLivreHtmlParser.parseJsonPriceField(obj.optString("price", ""))
                MercadoLivreHtmlParser.mergeCandidate(
                    out,
                    RawStoreCandidate(
                        id = MercadoLivreHtmlParser.canonicalCandidateId(finalUrl, "gs"),
                        store = StoreType.MERCADO_LIVRE,
                        title = rawTitle,
                        imageUrl = "",
                        productUrl = finalUrl,
                        affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(finalUrl),
                        priceText = price,
                        categoryPath = domainHint,
                        domainId = domainHint,
                        sourceQueryVariant = query,
                        sourceRank = out.size
                    )
                )
            }

            chunks.take(32).forEachIndexed { idx, chunk ->
                val web = chunk.web ?: return@forEachIndexed
                val uri = web.uri?.trim().orEmpty()
                if (uri.isBlank()) return@forEachIndexed
                val chunkTitle = web.title.orEmpty()
                val finalUrl = resolveUrl(uri)
                trackListHub(finalUrl)
                if (!MercadoLivreHtmlParser.isValidProductUrl(finalUrl) || finalUrl in excludeUrls) return@forEachIndexed
                val fallbackPrice = priceByChunkIdx[idx].orEmpty().ifBlank {
                    MercadoLivreHtmlParser.extractBrlPrice(chunkTitle)
                }
                MercadoLivreHtmlParser.mergeCandidate(
                    out,
                    RawStoreCandidate(
                        id = MercadoLivreHtmlParser.canonicalCandidateId(finalUrl, "gc"),
                        store = StoreType.MERCADO_LIVRE,
                        title = chunkTitle,
                        imageUrl = "",
                        productUrl = finalUrl,
                        affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(finalUrl),
                        priceText = fallbackPrice,
                        categoryPath = domainHint,
                        domainId = domainHint,
                        sourceQueryVariant = query,
                        sourceRank = out.size
                    )
                )
            }

            if (!skipHubPageFallback && out.size < 8 && listPageUrls.isNotEmpty()) {
                val parsedPages = withTimeoutOrNull(4_500L) {
                    coroutineScope {
                        listPageUrls.distinct().take(2).map { listUrl ->
                            async {
                                val html = fetchHtmlWithCookies(
                                    url = listUrl,
                                    maxBytes = 3_500_000,
                                    useDesktopUa = true,
                                    clientOverride = promoPageClient
                                )
                                if (html.isNotBlank()) {
                                    MercadoLivreHtmlParser.parseListingHtml(html, query, domainHint)
                                } else emptyList()
                            }
                        }.awaitAll().flatten()
                    }
                }.orEmpty()
                parsedPages.forEach { cand ->
                    if (cand.productUrl !in excludeUrls) MercadoLivreHtmlParser.mergeCandidate(out, cand)
                }
            }

            out.values.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastGroundingError = e.message?.take(90)
            Log.w(TAG, "Grounded ML search failed", e)
            emptyList()
        }
    }

    private suspend fun enrichMissingImagesAndPrices(
        candidates: List<RawStoreCandidate>,
        fallbackSearchHint: String,
        deadlineMs: Long,
        preHarvestedPrices: Map<String, String> = emptyMap(),
        includePricePageEnrichment: Boolean = false,
        parallelPriceEnrichment: Boolean = false
    ): List<RawStoreCandidate> = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope emptyList()

        var working = candidates.map { c ->
            val safeTitle = MercadoLivreHtmlParser.sanitizeCandidateTitle(c.title, c.productUrl)
                .ifBlank { HtmlTextDecoder.decode(c.title) }
            c.copy(
                title = safeTitle,
                imageUrl = HtmlTextDecoder.normalizeImageUrl(c.imageUrl)
            )
        }

        // Fast local pass: attach any already-harvested Grounding / SSR prices via exact or fuzzy title match
        if (preHarvestedPrices.isNotEmpty()) {
            working = applyPriceMapToCandidates(working, preHarvestedPrices)
        }

        // Fast batch lookup for direct MLB item URLs via /items?ids=... (up to 20 per call)
        if ((deadlineMs - System.currentTimeMillis()) > 2_500L) {
            working = enrichDirectItemsViaMultigetApi(working)
        }

        // Run Image/Page enrichment AND Price enrichment IN PARALLEL
        // Prioritize items that already have images so the top visible cards always receive verified prices.
        val hasNormalPricedSibling = working.any { parseBrlValue(it.priceText) >= 50.0 }
        fun isSuspiciousOrMissingPrice(item: RawStoreCandidate): Boolean {
            val v = parseBrlValue(item.priceText)
            return item.priceText.isBlank() ||
                (v in 0.01..24.99 && (hasNormalPricedSibling || item.id.startsWith("yh_") || item.id.startsWith("bg_") || item.id.startsWith("ddg_") || item.id.startsWith("ml_") || item.id.startsWith("gw_") || item.id.startsWith("wv_")))
        }
        val verifyCap = if (parallelPriceEnrichment) 28 else if (includePricePageEnrichment) 32 else 24
        val needPriceVerificationInitial = working.filter { isSuspiciousOrMissingPrice(it) }
            .sortedWith(
                compareByDescending<RawStoreCandidate> { it.priceText.isBlank() }
                    .thenByDescending { it.imageUrl.isNotBlank() }
                    .thenBy { it.sourceRank }
            )
            .take(verifyCap)

        val imagesDeferred = async {
            var imgWorking = working
            val remImgA = deadlineMs - System.currentTimeMillis()
            if (imgWorking.any { it.imageUrl.isBlank() } && fallbackSearchHint.isNotBlank() && remImgA > 1_500L) {
                val cleanHint = fallbackSearchHint
                    .replace(hintPromoCleanupRegex, " ")
                    .replace(wsRegex, " ")
                    .trim()
                    .ifBlank { fallbackSearchHint }
                val stepATimeout = (remImgA - 1_500L).coerceIn(2_500L, 6_500L)
                val mlDef = async {
                    withTimeoutOrNull(stepATimeout) {
                        fetchBingImages("$cleanHint mercadolivre", first = 1)
                    }.orEmpty()
                }
                val siteDef = async {
                    withTimeoutOrNull((stepATimeout - 500L).coerceAtLeast(2_000L)) {
                        fetchBingImages("site:mercadolivre.com.br $cleanHint", first = 1)
                    }.orEmpty()
                }
                val batchHits = mlDef.await() + siteDef.await()
                if (batchHits.isNotEmpty()) {
                    imgWorking = MercadoLivreHtmlParser.applyImageHits(imgWorking, batchHits)
                }
            }

            val pageCap = if (includePricePageEnrichment) 32 else 24
            val needEnrichment = imgWorking.withIndex()
                .filter { (_, cand) ->
                    cand.imageUrl.isBlank() ||
                        MercadoLivreHtmlParser.looksLikeUrlOrDomain(cand.title) ||
                        (includePricePageEnrichment && (isSuspiciousOrMissingPrice(cand) || !cand.imageUrl.contains("mlstatic.com", true)))
                }
                .sortedWith(
                    compareByDescending<IndexedValue<RawStoreCandidate>> { it.value.imageUrl.isBlank() }
                        .thenByDescending { isSuspiciousOrMissingPrice(it.value) }
                        .thenByDescending { !it.value.imageUrl.contains("mlstatic.com", true) }
                        .thenBy { it.value.sourceRank }
                )
                .take(pageCap)
            val remImgB = deadlineMs - System.currentTimeMillis()
            if (needEnrichment.isNotEmpty() && remImgB > 1_500L) {
                val maxStepB = if (includePricePageEnrichment) 16_000L else 14_000L
                val stepBTimeout = (remImgB - 500L).coerceIn(4_000L, maxStepB)
                val updatedMap = ConcurrentHashMap<Int, RawStoreCandidate>()
                withTimeoutOrNull(stepBTimeout) {
                    needEnrichment.mapIndexed { orderIdx, (idx, item) ->
                        async {
                            val enriched = enrichSingleCandidate(
                                candidate = item,
                                allowBingFallback = orderIdx < 24,
                                allowPricePageEnrichment = includePricePageEnrichment
                            )
                            updatedMap[idx] = enriched
                        }
                    }.awaitAll()
                }
                imgWorking = imgWorking.mapIndexed { idx, item -> updatedMap[idx] ?: item }
            }

            imgWorking
        }

        val pricesDeferred = async {
            if (needPriceVerificationInitial.isEmpty()) return@async emptyMap<String, String>()
            val mergedMap = ConcurrentHashMap<String, String>()
            val remInitial = deadlineMs - System.currentTimeMillis()
            if (remInitial < 3_000L) return@async emptyMap<String, String>()

            val cleanHint = fallbackSearchHint
                .replace(hintPromoCleanupRegex, " ")
                .replace(wsRegex, " ")
                .trim()
                .ifBlank { fallbackSearchHint }
            val oracleQueries = buildList {
                if (cleanHint.isNotBlank()) add(cleanHint)
                val core = buildCoreQuery(cleanHint)
                if (core.isNotBlank() && !core.equals(cleanHint, true)) add(core)
                needPriceVerificationInitial.take(2).forEach { cand ->
                    val compact = MercadoLivreHtmlParser.buildCompactModelQuery(cand.title)
                    if (compact.length >= 6 && none { it.equals(compact, true) }) add(compact)
                }
            }.distinctBy { TextNormalizer.normalize(it) }.take(3)

            if (parallelPriceEnrichment) {
                // Launch Zoom/Buscapé HTTP prices, broad sweep, AND balanced 8-item Grounding batches IN PARALLEL
                val oracleJob = launch {
                    val oraclePrices = withTimeoutOrNull(5_500L) {
                        fetchStructuredMarketPrices(oracleQueries)
                    }.orEmpty()
                    if (oraclePrices.isNotEmpty()) mergedMap.putAll(oraclePrices)
                }
                val sweepJob = launch {
                    if (cleanHint.isNotBlank() && (deadlineMs - System.currentTimeMillis()) >= 5_500L) {
                        groundingSemaphore.withPermit {
                            val sweepRes = fetchGroundedQueryPrices(
                                queryHint = cleanHint,
                                sampleTitles = needPriceVerificationInitial.take(18).map {
                                    MercadoLivreHtmlParser.buildCompactModelQuery(it.title)
                                },
                                timeoutMs = (deadlineMs - System.currentTimeMillis() - 400L).coerceIn(6_000L, 19_000L)
                            )
                            if (sweepRes.isNotEmpty()) mergedMap.putAll(sweepRes)
                        }
                    }
                }
                val microBatches = needPriceVerificationInitial.take(24).chunked(8)
                val microJobs = microBatches.map { batch ->
                    launch {
                        groundingSemaphore.withPermit {
                            val remForCall = deadlineMs - System.currentTimeMillis() - 350L
                            if (remForCall >= 4_500L) {
                                val batchRes = batchLookupPricesViaGoogleSearch(
                                    missing = batch,
                                    searchHint = cleanHint,
                                    timeoutMs = remForCall.coerceIn(5_000L, 19_000L)
                                )
                                if (batchRes.isNotEmpty()) mergedMap.putAll(batchRes)
                            }
                        }
                    }
                }
                oracleJob.join()
                sweepJob.join()
                microJobs.forEach { it.join() }

                val remRescue = deadlineMs - System.currentTimeMillis()
                if (remRescue >= 4_500L) {
                    val fuzzyEntries = prepareFuzzyPriceEntries(mergedMap)
                    val stillMissing = needPriceVerificationInitial
                        .filter { cand -> !hasResolvedPriceInMapPrepared(cand, mergedMap, fuzzyEntries) }
                        .take(8)
                    if (stillMissing.isNotEmpty()) {
                        val rescueBatches = stillMissing.chunked(8)
                        withTimeoutOrNull((remRescue - 250L).coerceAtLeast(4_000L)) {
                            val rescueJobs = rescueBatches.map { batch ->
                                launch {
                                    groundingSemaphore.withPermit {
                                        val remForRescue = deadlineMs - System.currentTimeMillis() - 250L
                                        if (remForRescue >= 4_000L) {
                                            val res = batchLookupPricesViaGoogleSearch(
                                                missing = batch,
                                                searchHint = cleanHint,
                                                timeoutMs = remForRescue.coerceIn(4_000L, 13_000L),
                                                broadFallbackMode = true
                                            )
                                            if (res.isNotEmpty()) mergedMap.putAll(res)
                                        }
                                    }
                                }
                            }
                            rescueJobs.forEach { it.join() }
                        }
                    }
                }
                return@async mergedMap
            }

            // Tier 1 (<1.8s deterministic HTTP): Structured Brazilian market prices from Zoom/Buscapé __NEXT_DATA__
            // AND parallel Tier 2 broad Google Search Grounding query price sweep
            val oracleJob = launch {
                val oraclePrices = withTimeoutOrNull(4_500L) {
                    fetchStructuredMarketPrices(oracleQueries)
                }.orEmpty()
                if (oraclePrices.isNotEmpty()) mergedMap.putAll(oraclePrices)
            }
            val sweepJob = launch {
                if (cleanHint.isNotBlank() && (deadlineMs - System.currentTimeMillis()) >= 7_000L) {
                    groundingSemaphore.withPermit {
                        val sweepRes = fetchGroundedQueryPrices(
                            queryHint = cleanHint,
                            sampleTitles = needPriceVerificationInitial.take(14).map {
                                MercadoLivreHtmlParser.buildCompactModelQuery(it.title)
                            },
                            timeoutMs = (deadlineMs - System.currentTimeMillis() - 500L).coerceIn(5_000L, 14_000L)
                        )
                        if (sweepRes.isNotEmpty()) mergedMap.putAll(sweepRes)
                    }
                }
            }

            // Wait quickly for deterministic Tier 1 HTTP prices before deciding which items need per-item Grounding
            oracleJob.join()

            val afterTier1Fuzzy = prepareFuzzyPriceEntries(mergedMap)
            val stillAfterTier1 = needPriceVerificationInitial
                .filter { cand -> !hasResolvedPriceInMapPrepared(cand, mergedMap, afterTier1Fuzzy) }

            // Tier 3: Focused micro-batches via Google Search Grounding so Gemini searches models individually
            val remForMicro = deadlineMs - System.currentTimeMillis()
            val microCap = if (includePricePageEnrichment) 28 else 14
            val microChunkSize = if (includePricePageEnrichment) 3 else 2
            if (stillAfterTier1.isNotEmpty() && remForMicro >= 3_500L) {
                val microTargets = stillAfterTier1.take(microCap)
                val microBatches = microTargets.chunked(microChunkSize)
                withTimeoutOrNull((remForMicro - 400L).coerceAtLeast(3_000L)) {
                    val jobs = microBatches.map { batch ->
                        launch {
                            groundingSemaphore.withPermit {
                                val remForCall = deadlineMs - System.currentTimeMillis() - 400L
                                if (remForCall >= 3_500L) {
                                    val batchRes = batchLookupPricesViaGoogleSearch(
                                        missing = batch,
                                        searchHint = cleanHint,
                                        timeoutMs = remForCall.coerceIn(3_500L, 15_000L)
                                    )
                                    if (batchRes.isNotEmpty()) mergedMap.putAll(batchRes)
                                }
                            }
                        }
                    }
                    jobs.forEach { it.join() }
                }
            }
            sweepJob.join()

            // Rescue pass if any top visible cards remain without prices and >= 6.5s budget remains
            val remAfterPrimary = deadlineMs - System.currentTimeMillis()
            if (remAfterPrimary >= 6_500L) {
                val fuzzyEntries = prepareFuzzyPriceEntries(mergedMap)
                val rescueCap = if (includePricePageEnrichment) 12 else 6
                val stillMissing = needPriceVerificationInitial
                    .filter { cand -> !hasResolvedPriceInMapPrepared(cand, mergedMap, fuzzyEntries) }
                    .take(rescueCap)
                if (stillMissing.isNotEmpty()) {
                    val rescueBatches = stillMissing.chunked(2)
                    withTimeoutOrNull((remAfterPrimary - 300L).coerceAtLeast(4_000L)) {
                        val rescueJobs = rescueBatches.map { batch ->
                            launch {
                                groundingSemaphore.withPermit {
                                    val remForRescue = deadlineMs - System.currentTimeMillis() - 350L
                                    if (remForRescue >= 4_000L) {
                                        val res = batchLookupPricesViaGoogleSearch(
                                            missing = batch,
                                            searchHint = cleanHint,
                                            timeoutMs = remForRescue.coerceIn(4_000L, 12_000L),
                                            broadFallbackMode = true
                                        )
                                        if (res.isNotEmpty()) mergedMap.putAll(res)
                                    }
                                }
                            }
                        }
                        rescueJobs.forEach { it.join() }
                    }
                }
            }
            mergedMap
        }

        val imgResolved = imagesDeferred.await()
        val priceResolvedMap = pricesDeferred.await()
        if (priceResolvedMap.isNotEmpty()) {
            applyPriceMapToCandidates(imgResolved, priceResolvedMap)
        } else {
            imgResolved
        }
    }

    private suspend fun enrichDirectItemsViaMultigetApi(
        candidates: List<RawStoreCandidate>
    ): List<RawStoreCandidate> {
        val directIdByCandidateIdx = HashMap<Int, String>()
        val uniqueIds = LinkedHashSet<String>()
        candidates.forEachIndexed { idx, cand ->
            val v = parseBrlValue(cand.priceText)
            val needsData = cand.priceText.isBlank() ||
                v in 0.01..24.99 ||
                cand.imageUrl.isBlank() ||
                !cand.imageUrl.contains("mlstatic.com", ignoreCase = true)
            if (!needsData) return@forEachIndexed
            val directId = extractDirectItemIdForMultiget(cand.productUrl) ?: return@forEachIndexed
            directIdByCandidateIdx[idx] = directId
            if (uniqueIds.size < 40) uniqueIds.add(directId)
        }
        if (uniqueIds.isEmpty()) return candidates

        val credsConfigured = hasConfiguredMlCredentials()
        val resolvedByItemId = ConcurrentHashMap<String, Triple<String, String, String>>()
        withTimeoutOrNull(3_200L) {
            coroutineScope {
                uniqueIds.toList().chunked(20).map { batchIds ->
                    async {
                        apiMetadataSemaphore.withPermit {
                            val idsParam = batchIds.joinToString(",")
                            val url = "https://api.mercadolibre.com/items?ids=$idsParam&attributes=id,title,price,currency_id,sale_price,secure_thumbnail,thumbnail,pictures"
                            val res = runCatching {
                                executeAuthenticatedApiGet(
                                    url = url,
                                    credentialsConfigured = credsConfigured,
                                    httpClientOverride = quickApiClient
                                )
                            }.getOrNull()
                            if (res != null && res.code in 200..299 && res.body.isNotBlank()) {
                                resolvedByItemId.putAll(parseMultigetItemsJson(res.body))
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        if (resolvedByItemId.isEmpty()) return candidates

        return applyMultigetMapToCandidates(candidates, directIdByCandidateIdx, resolvedByItemId)
    }

    internal fun applyMultigetMapToCandidates(
        candidates: List<RawStoreCandidate>,
        directIdByCandidateIdx: Map<Int, String>,
        resolvedByItemId: Map<String, Triple<String, String, String>>
    ): List<RawStoreCandidate> {
        if (resolvedByItemId.isEmpty()) return candidates
        return candidates.mapIndexed { idx, item ->
            val directId = directIdByCandidateIdx[idx] ?: return@mapIndexed item
            val (apiTitle, apiImg, apiPrice) = resolvedByItemId[directId] ?: return@mapIndexed item
            val bestTitle = if (apiTitle.length >= 6 && (MercadoLivreHtmlParser.looksLikeUrlOrDomain(item.title) || apiTitle.length > item.title.length)) {
                apiTitle
            } else item.title
            val bestImg = if (apiImg.isNotBlank() && (item.imageUrl.isBlank() || (!item.imageUrl.contains("mlstatic.com", true) && apiImg.contains("mlstatic.com", true)))) {
                apiImg
            } else item.imageUrl
            val curPriceVal = parseBrlValue(item.priceText)
            val newPriceVal = parseBrlValue(apiPrice)
            val bestPrice = if (apiPrice.isNotBlank() && (item.priceText.isBlank() || (curPriceVal in 0.01..24.99 && newPriceVal > curPriceVal))) {
                apiPrice
            } else item.priceText
            if (bestTitle == item.title && bestImg == item.imageUrl && bestPrice == item.priceText) {
                item
            } else {
                item.copy(title = bestTitle, imageUrl = bestImg, priceText = bestPrice)
            }
        }
    }

    internal fun extractDirectItemIdForMultiget(url: String): String? {
        if (url.isBlank()) return null
        val widMatch = widQueryRegex.find(url)
        if (widMatch != null) return widMatch.groupValues[1].uppercase()
        val lower = url.lowercase()
        if (lower.contains("/p/") || lower.contains("/up/") || lower.contains("mlbu")) return null
        val mlbId = MercadoLivreHtmlParser.extractMlbId(url) ?: return null
        return mlbId.uppercase().takeIf { directMlbRegex.matches(it) }
    }

    internal fun parseMultigetItemsJson(jsonBody: String): Map<String, Triple<String, String, String>> {
        val trimmed = jsonBody.trimStart()
        if (!trimmed.startsWith("[")) return emptyMap()
        return runCatching {
            val arr = JSONArray(trimmed)
            val out = LinkedHashMap<String, Triple<String, String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                if (entry.optInt("code", 0) !in 200..299) continue
                val body = entry.optJSONObject("body") ?: continue
                val id = body.optString("id", "").trim().uppercase()
                if (id.isBlank()) continue
                val title = body.optString("title", "").trim()
                val img = extractApiImageUrl(body)
                val currency = body.optString("currency_id", "BRL").trim()
                val priceText = if (currency.isBlank() || currency.equals("BRL", ignoreCase = true)) {
                    val saleAmount = body.optJSONObject("sale_price")?.optDouble("amount", Double.NaN) ?: Double.NaN
                    val directPrice = body.optDouble("price", Double.NaN)
                    val valNum = when {
                        saleAmount.isFinite() && saleAmount > 0.0 -> saleAmount
                        directPrice.isFinite() && directPrice > 0.0 -> directPrice
                        else -> 0.0
                    }
                    PriceFormatter.formatBrl(valNum)
                } else ""
                if (title.isNotBlank() || img.isNotBlank() || priceText.isNotBlank()) {
                    out[id] = Triple(title, img, priceText)
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    private data class PreparedFuzzyPriceEntry(
        val price: String,
        val priceVal: Double,
        val tokens: Set<String>,
        val firstToken: String?,
        val isAccessoryTokenPresent: Boolean,
        val isKit: Boolean,
        val capacityCodes: Set<String>,
        val codes: Set<String>,
        val specificCodes: Set<String>
    )

    internal fun applyPriceMapToCandidates(
        candidates: List<RawStoreCandidate>,
        priceMap: Map<String, String>
    ): List<RawStoreCandidate> {
        if (priceMap.isEmpty()) return candidates
        val preparedEntries = prepareFuzzyPriceEntries(priceMap)

        return candidates.map { item ->
            val mlbKey = MercadoLivreHtmlParser.extractMlbId(item.productUrl)?.uppercase().orEmpty()
            val normItemTitle = TextNormalizer.normalize(item.title)
            val found = priceMap[item.id]
                ?: mlbKey.takeIf { it.isNotBlank() }?.let { priceMap[it] }
                ?: priceMap[normItemTitle]
                ?: findBestFuzzyPricePrepared(normItemTitle, preparedEntries)
            val existingVal = parseBrlValue(item.priceText)
            val foundVal = parseBrlValue(found.orEmpty())
            when {
                !found.isNullOrBlank() && (item.priceText.isBlank() || (existingVal in 0.01..24.99 && foundVal > existingVal)) ->
                    item.copy(priceText = found)
                else -> item
            }
        }
    }

    private fun buildPreparedFuzzyEntry(normTitle: String, price: String): PreparedFuzzyPriceEntry? {
        val tokensList = TextNormalizer.tokenize(normTitle).filter { it.length >= 2 }
        if (tokensList.isEmpty()) return null
        val tokens = tokensList.toSet()
        val codes = tokens.filter { tok -> tok.any { it.isDigit() } }.toSet()
        return PreparedFuzzyPriceEntry(
            price = price,
            priceVal = parseBrlValue(price),
            tokens = tokens,
            firstToken = tokensList.firstOrNull(),
            isAccessoryTokenPresent = "controle" in tokens || "pelicula" in tokens || "capinha" in tokens,
            isKit = "kit" in tokens || "combo" in tokens || "conjunto" in tokens,
            capacityCodes = tokens.filter { capacityTokenRegex.matches(it) }.toSet(),
            codes = codes,
            specificCodes = codes - fuzzyGenericCodes
        )
    }

    private fun prepareFuzzyPriceEntries(priceMap: Map<String, String>): List<PreparedFuzzyPriceEntry> {
        return priceMap.entries.mapNotNull { (k, v) ->
            val looksLikeId = k.startsWith("ml_", true) || k.startsWith("bg_", true) ||
                k.startsWith("yh_", true) || k.startsWith("gs_", true) ||
                k.startsWith("gc_", true) || k.startsWith("bi_", true) ||
                bareMlbIdRegex.matches(k)
            if (looksLikeId) null else {
                val norm = TextNormalizer.normalize(k)
                if (norm.length < 6) null else buildPreparedFuzzyEntry(norm, v)
            }
        }
    }

    private fun findBestFuzzyPricePrepared(
        normItemTitle: String,
        entries: List<PreparedFuzzyPriceEntry>
    ): String? {
        if (normItemTitle.isBlank() || entries.isEmpty()) return null
        val itemTokens = TextNormalizer.tokenize(normItemTitle).filter { it.length >= 2 }.toSet()
        if (itemTokens.isEmpty()) return null
        val itemCodes = itemTokens.filter { tok -> tok.any { it.isDigit() } }.toSet()
        val itemSpecificCodes = itemCodes - fuzzyGenericCodes
        val itemIsAccessory = itemTokens.any { it in fuzzyAccessoryWords }
        val itemIsKit = "kit" in itemTokens || "combo" in itemTokens || "conjunto" in itemTokens
        val itemCapacityCodes = itemTokens.filter { capacityTokenRegex.matches(it) }.toSet()

        var bestPrice: String? = null
        var bestScore = 0.0
        for (entry in entries) {
            if (entry.priceVal < 12.0 && !itemIsAccessory) continue
            if (!itemIsAccessory && entry.firstToken in fuzzyAccessoryWords) continue
            if (!itemIsAccessory && entry.isAccessoryTokenPresent) continue
            if (itemIsKit != entry.isKit) continue
            if (itemCapacityCodes.isNotEmpty() && entry.capacityCodes.isNotEmpty() &&
                itemCapacityCodes.none { it in entry.capacityCodes }
            ) {
                continue
            }
            if (itemCodes.isNotEmpty() && entry.codes.isNotEmpty() &&
                itemCodes.none { it in entry.codes }
            ) {
                continue
            }
            val sharedSpecificCodes = itemSpecificCodes.intersect(entry.specificCodes)
            if (itemSpecificCodes.isNotEmpty() && entry.specificCodes.isNotEmpty()) {
                val itemModelsOnly = itemSpecificCodes - sharedSpecificCodes
                val entryModelsOnly = entry.specificCodes - sharedSpecificCodes
                if (sharedSpecificCodes.isEmpty() &&
                    itemModelsOnly.any { it.length in 2..6 } &&
                    entryModelsOnly.any { it.length in 2..6 }
                ) {
                    continue
                }
            }

            var common = 0
            for (tok in itemTokens) {
                if (tok in entry.tokens) common++
            }
            if (common < 2) continue
            val minSize = minOf(itemTokens.size, entry.tokens.size)
            val unionSize = itemTokens.size + entry.tokens.size - common
            val containment = common.toDouble() / minSize.toDouble()
            val jaccard = common.toDouble() / unionSize.toDouble()
            val hasSpecificModelMatch = sharedSpecificCodes.isNotEmpty()
            val passesThreshold = (hasSpecificModelMatch && common >= 3 && containment >= 0.45) ||
                (containment >= 0.60 && (jaccard >= 0.24 || common >= 3))
            val exactCodeBonus = if (hasSpecificModelMatch) 0.14 else 0.0
            val score = 0.65 * containment + 0.35 * jaccard + exactCodeBonus
            if (passesThreshold && score > bestScore) {
                bestScore = score
                bestPrice = entry.price
            }
        }
        return bestPrice
    }

    internal fun findBestFuzzyPrice(
        normItemTitle: String,
        entries: List<Pair<String, String>>
    ): String? {
        if (normItemTitle.isBlank() || entries.isEmpty()) return null
        val prepared = entries.mapNotNull { (title, price) -> buildPreparedFuzzyEntry(title, price) }
        return findBestFuzzyPricePrepared(normItemTitle, prepared)
    }

    private suspend fun enrichSingleCandidate(
        candidate: RawStoreCandidate,
        allowBingFallback: Boolean,
        allowPricePageEnrichment: Boolean = false
    ): RawStoreCandidate = coroutineScope {
        var title = MercadoLivreHtmlParser.sanitizeCandidateTitle(candidate.title, candidate.productUrl)
        var imageUrl = HtmlTextDecoder.normalizeImageUrl(candidate.imageUrl)
        var priceText = candidate.priceText

        val existingVal = parseBrlValue(priceText)
        val needImage = imageUrl.isBlank()
        val needTitle = MercadoLivreHtmlParser.looksLikeUrlOrDomain(title)
        val needPrice = allowPricePageEnrichment && (priceText.isBlank() || existingVal in 0.01..24.99)
        val wantMlCdnUpgrade = allowPricePageEnrichment && !imageUrl.contains("mlstatic.com", true)
        if (!needImage && !needTitle && !needPrice && !wantMlCdnUpgrade) {
            return@coroutineScope candidate.copy(title = title, imageUrl = imageUrl, priceText = priceText)
        }

        val titleWords = title.split(' ').filter { it.length >= 2 && it.any { ch -> ch.isLetterOrDigit() } }
        val shortTitleQuery = titleWords.take(5).joinToString(" ")

        // Run API metadata, Promoções-grade desktop ML product page HTML, and clean image fallback IN PARALLEL
        val hasMlbId = !MercadoLivreHtmlParser.extractMlbId(candidate.productUrl).isNullOrBlank()
        val apiDeferred = async {
            if (hasMlbId && (allowBingFallback || allowPricePageEnrichment)) {
                withTimeoutOrNull(4_800L) {
                    apiMetadataSemaphore.withPermit {
                        withTimeoutOrNull(4_500L) {
                            fetchPublicItemOrCatalogMetadata(candidate.productUrl)
                        } ?: Triple("", "", "")
                    }
                } ?: Triple("", "", "")
            } else {
                Triple("", "", "")
            }
        }
        val pageDeferred = async {
            val totalBudgetMs = if (allowPricePageEnrichment) 7_500L else 4_200L
            val innerTimeoutMs = if (allowPricePageEnrichment) 6_800L else 3_800L
            withTimeoutOrNull(totalBudgetMs) {
                pageFetchSemaphore.withPermit {
                    withTimeoutOrNull(innerTimeoutMs) {
                        fetchHtmlWithCookies(
                            url = candidate.productUrl,
                            maxBytes = 2_000_000,
                            useQuickClient = !allowPricePageEnrichment,
                            useDesktopUa = true,
                            clientOverride = if (allowPricePageEnrichment) promoPageClient else null
                        )
                    }.orEmpty()
                }
            }.orEmpty()
        }
        val imgFallbackDeferred = async {
            if (allowBingFallback && needImage && shortTitleQuery.isNotBlank()) {
                withTimeoutOrNull(6_000L) {
                    fetchBingImages("$shortTitleQuery mercadolivre", first = 1)
                }.orEmpty()
            } else emptyList()
        }

        val (apiTitle, apiImg, apiPrice) = withTimeoutOrNull(4_900L) { apiDeferred.await() } ?: Triple("", "", "")
        if (apiTitle.length >= 6 && (MercadoLivreHtmlParser.looksLikeUrlOrDomain(title) || apiTitle.length > title.length)) {
            title = apiTitle
        }
        if (apiImg.isNotBlank() && (imageUrl.isBlank() || (!imageUrl.contains("mlstatic.com", true) && apiImg.contains("mlstatic.com", true)))) {
            imageUrl = apiImg
        }
        if (apiPrice.isNotBlank() && (priceText.isBlank() || (parseBrlValue(priceText) in 0.01..24.99 && parseBrlValue(apiPrice) > parseBrlValue(priceText)))) {
            priceText = apiPrice
        }

        if (imageUrl.isBlank()) {
            val imgHits = withTimeoutOrNull(5_500L) { imgFallbackDeferred.await() }.orEmpty()
            if (imgHits.isNotEmpty()) {
                val best = MercadoLivreHtmlParser.pickFallbackCandidateImageHit(title, candidate.productUrl, imgHits)
                val resolved = HtmlTextDecoder.normalizeImageUrl(best?.preferredImageUrl.orEmpty())
                if (resolved.isNotBlank()) imageUrl = resolved
            }
        } else {
            imgFallbackDeferred.cancel()
        }

        val stillNeedsPrice = priceText.isBlank() || (allowPricePageEnrichment && parseBrlValue(priceText) in 0.01..24.99)
        val stillNeedsOfficialPhoto = imageUrl.isBlank() || (allowPricePageEnrichment && !imageUrl.contains("mlstatic.com", true))
        if (stillNeedsOfficialPhoto || stillNeedsPrice || MercadoLivreHtmlParser.looksLikeUrlOrDomain(title)) {
            val pageWaitMs = when {
                allowPricePageEnrichment && (imageUrl.isBlank() || priceText.isBlank()) -> 6_500L
                imageUrl.isBlank() -> 3_800L
                else -> 2_200L
            }
            val html = withTimeoutOrNull(pageWaitMs) { pageDeferred.await() }.orEmpty()
            if (html.isNotBlank()) {
                val (pTitle, pImg, pPrice) = MercadoLivreHtmlParser.parseSingleProductPageHtml(html, candidate.productUrl)
                if (pTitle.length >= 6 && (MercadoLivreHtmlParser.looksLikeUrlOrDomain(title) || pTitle.length > title.length)) {
                    title = pTitle
                }
                if (pImg.isNotBlank() && (imageUrl.isBlank() || (!imageUrl.contains("mlstatic.com", true) && pImg.contains("mlstatic.com", true)))) {
                    imageUrl = pImg
                }
                if (pPrice.isNotBlank() && (priceText.isBlank() || (parseBrlValue(priceText) in 0.01..24.99 && parseBrlValue(pPrice) > parseBrlValue(priceText)))) {
                    priceText = pPrice
                }
            } else {
                pageDeferred.cancel()
            }
        } else {
            pageDeferred.cancel()
        }

        candidate.copy(title = title, imageUrl = imageUrl, priceText = priceText)
    }

    private fun fetchPublicItemOrCatalogMetadata(productUrl: String): Triple<String, String, String> {
        val mlbId = MercadoLivreHtmlParser.extractMlbId(productUrl) ?: return Triple("", "", "")
        val isCatalog = productUrl.contains("/p/", ignoreCase = true) ||
            productUrl.contains("/up/", ignoreCase = true) ||
            mlbId.startsWith("MLBU", ignoreCase = true)
        val cachedToken = memoryAccessToken?.takeIf { it.isNotBlank() && System.currentTimeMillis() < memoryTokenExpiresAtMs }
        var resolvedTitle = ""
        var resolvedImg = ""
        var resolvedPrice = ""

        if (isCatalog) {
            val (catalogItemPrice, catalogItemImg) = fetchCatalogProductPriceAndImage(
                productId = mlbId,
                token = cachedToken,
                needPrice = true,
                needImage = true
            )
            if (catalogItemPrice.isNotBlank()) resolvedPrice = catalogItemPrice
            if (catalogItemImg.isNotBlank()) resolvedImg = catalogItemImg
            if (resolvedImg.isNotBlank() && resolvedPrice.isNotBlank()) {
                return Triple(resolvedTitle, resolvedImg, resolvedPrice)
            }
        }

        val endpoints = if (isCatalog) {
            listOf("https://api.mercadolibre.com/items/$mlbId")
        } else {
            listOf("https://api.mercadolibre.com/items/$mlbId", "https://api.mercadolibre.com/products/$mlbId")
        }

        for (endpoint in endpoints) {
            try {
                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", mobileUa)
                if (!cachedToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $cachedToken")
                }
                quickApiClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.bytes()?.let {
                        MercadoLivreHtmlParser.decodeHttpBytes(it, resp.body?.contentType()?.charset())
                    }.orEmpty()
                    if (!body.trimStart().startsWith("{")) return@use
                    val obj = JSONObject(body)
                    val title = obj.optString("title", "").ifBlank { obj.optString("name", "") }.trim()
                    val pic = obj.optJSONArray("pictures")?.optJSONObject(0)?.let { p ->
                        p.optString("secure_url", "").ifBlank { p.optString("url", "") }
                    }.orEmpty().ifBlank { obj.optString("thumbnail", "") }
                    val cleanImg = HtmlTextDecoder.normalizeImageUrl(pic)
                    val buyBoxPrice = obj.optJSONObject("buy_box_winner")?.optDouble("price", 0.0)?.takeIf { it > 0.0 }
                    val saleAmount = obj.optJSONObject("sale_price")?.optDouble("amount", 0.0)?.takeIf { it > 0.0 }
                    val directPrice = obj.optDouble("price", 0.0).takeIf { it > 0.0 }
                    val priceText = PriceFormatter.formatBrl(buyBoxPrice ?: saleAmount ?: directPrice ?: 0.0)
                    if (title.length > resolvedTitle.length) resolvedTitle = title
                    if (resolvedImg.isBlank() || (!resolvedImg.contains("mlstatic.com", true) && cleanImg.contains("mlstatic.com", true))) {
                        if (cleanImg.isNotBlank()) resolvedImg = cleanImg
                    }
                    if (resolvedPrice.isBlank() && priceText.isNotBlank()) {
                        resolvedPrice = priceText
                    }
                    if (resolvedImg.isNotBlank() && resolvedPrice.isNotBlank()) {
                        return Triple(resolvedTitle, resolvedImg, resolvedPrice)
                    }
                }
            } catch (_: Exception) {
                // Silent fallback to HTML/Search Engines/Grounding
            }
        }

        return if (resolvedImg.isNotBlank() || resolvedPrice.isNotBlank() || resolvedTitle.isNotBlank()) {
            Triple(resolvedTitle, resolvedImg, resolvedPrice)
        } else {
            Triple("", "", "")
        }
    }

    private fun hasResolvedPriceInMapPrepared(
        item: RawStoreCandidate,
        priceMap: Map<String, String>,
        fuzzyEntries: List<PreparedFuzzyPriceEntry>
    ): Boolean {
        if (priceMap.containsKey(item.id)) return true
        val mlbKey = MercadoLivreHtmlParser.extractMlbId(item.productUrl)?.uppercase().orEmpty()
        if (mlbKey.isNotBlank() && priceMap.containsKey(mlbKey)) return true
        val normTitle = TextNormalizer.normalize(item.title)
        if (normTitle.isNotBlank() && priceMap.containsKey(normTitle)) return true
        return !findBestFuzzyPricePrepared(normTitle, fuzzyEntries).isNullOrBlank()
    }

    private suspend fun fetchStructuredMarketPrices(
        queries: List<String>
    ): Map<String, String> = coroutineScope {
        if (queries.isEmpty()) return@coroutineScope emptyMap()
        val urls = queries.flatMap { q ->
            val enc = URLEncoder.encode(q.trim(), "UTF-8")
            listOf(
                "https://www.zoom.com.br/search?q=$enc",
                "https://www.buscape.com.br/search?q=$enc"
            )
        }.distinct().take(4)

        val out = ConcurrentHashMap<String, String>()
        val jobs = urls.map { url ->
            launch {
                val html = withTimeoutOrNull(4_000L) { fetchSimpleHtml(url) }.orEmpty()
                if (html.isNotBlank()) {
                    MercadoLivreHtmlParser.parseNextDataPriceCatalog(html).forEach { (title, price) ->
                        val normKey = TextNormalizer.normalize(title)
                        if (normKey.length >= 6 && price.isNotBlank()) {
                            out.putIfAbsent(normKey, price)
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        out
    }

    private suspend fun fetchGroundedQueryPrices(
        queryHint: String,
        sampleTitles: List<String>,
        timeoutMs: Long = 14_000L
    ): Map<String, String> {
        if (queryHint.isBlank()) return emptyMap()
        return try {
            val modelsHint = sampleTitles.take(10).joinToString("; ")
            val prompt = """
                Pesquise no Google Brasil os preços atuais em reais (R$) no Mercado Livre Brasil e lojas brasileiras para produtos de "$queryHint" (exemplos: $modelsHint).
                Retorne SOMENTE um array JSON no formato:
                [{"title":"nome completo do produto/modelo","price":"R$ 489,90"}]
                Regras:
                - Traga de 8 a 15 produtos reais com preços em R$ encontrados na Busca do Google Brasil (nunca invente valores, nunca use R$ 0,00).
                - Em "title", escreva o nome claro do modelo/kit conforme encontrado nos resultados.
            """.trimIndent()
            val response = withTimeoutOrNull(timeoutMs) {
                groundedSearchModel.generateContent(prompt)
            } ?: return emptyMap()

            val supports = response.candidates.firstOrNull()?.groundingMetadata?.groundingSupports
                ?.mapNotNull { it.segment?.text }
                .orEmpty()
            val out = LinkedHashMap<String, String>()
            MercadoLivreHtmlParser.parseGroundedCatalogPrices(response.text.orEmpty(), supports)
                .forEach { (title, price) ->
                    val normKey = TextNormalizer.normalize(title)
                    if (normKey.length >= 6) out.putIfAbsent(normKey, price)
                }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastGroundingError = e.message?.take(90)
            Log.w(TAG, "Broad Google Search price sweep failed", e)
            emptyMap()
        }
    }

    private suspend fun batchLookupPricesViaGoogleSearch(
        missing: List<RawStoreCandidate>,
        searchHint: String = "",
        timeoutMs: Long = 16_000L,
        broadFallbackMode: Boolean = false
    ): Map<String, String> {
        if (missing.isEmpty()) return emptyMap()
        return try {
            val block = missing.mapIndexed { idx, c ->
                val compact = MercadoLivreHtmlParser.buildCompactModelQuery(c.title)
                if (broadFallbackMode) {
                    "[${idx + 1}] ${compact.split(' ').take(4).joinToString(" ")}"
                } else {
                    "[${idx + 1}] $compact"
                }
            }.joinToString("\n")
            val prompt = """
                Pesquise no Google Brasil os preços atuais em reais (R$) dos ${missing.size} produtos numerados abaixo (${searchHint.ifBlank { "Brasil" }}).
                Retorne SOMENTE um array JSON neste formato:
                [{"i":1,"title":"nome do produto","price":"R$ 489,90"}]
                Regras obrigatórias:
                - Retorne EXATAMENTE ${missing.size} objetos no array JSON, um para cada índice "i" de 1 até ${missing.size}. NUNCA omita ou pule nenhum índice.
                - Em "price", informe o preço real em reais (R$) no Mercado Livre Brasil; se o anúncio exato não exibir o preço no snippet, pesquise o mesmo produto/modelo (ou equivalente mais próximo) no Mercado Livre Brasil ou em lojas brasileiras na Busca do Google e informe o preço atual real em R$.
                - Use formato "R$ 489,90" ou "R$ 1.299,00". NUNCA retorne null, vazio, "Consulte" nem "R$ 0,00".
                Produtos:
                $block
            """.trimIndent()
            val response = withTimeoutOrNull(timeoutMs) {
                groundedSearchModel.generateContent(prompt)
            }
            if (response == null) {
                Log.w(TAG, "Price lookup timed out after ${timeoutMs}ms for ${missing.size} items")
                return emptyMap()
            }
            val supports = response.candidates.firstOrNull()?.groundingMetadata?.groundingSupports
                ?.mapNotNull { it.segment?.text }
                .orEmpty()
            MercadoLivreHtmlParser.parseGroundedPriceResponse(response.text.orEmpty(), missing, supports)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastGroundingError = e.message?.take(90)
            Log.w(TAG, "Price lookup via Google Search failed", e)
            emptyMap()
        }
    }

    private suspend fun fetchBingImages(searchExpr: String, first: Int = 1): List<SearchEngineImageHit> = coroutineScope {
        val encoded = URLEncoder.encode(searchExpr, "UTF-8")
        // Omitting cc=BR&setlang=pt-BR and using HDRSC3 avoids Bing Images' empty JS-only shell response
        val bingHtml = bingImageSemaphore.withPermit {
            withTimeoutOrNull(4_800L) {
                fetchSimpleHtml("https://www.bing.com/images/search?q=$encoded&first=$first&form=HDRSC3", useQuickImageClient = true)
            }.orEmpty()
        }
        val bingHits = MercadoLivreHtmlParser.parseBingImagesHtml(bingHtml)
        val bareExpr = searchExpr
            .replace(siteColonRegex, " ")
            .replace(wsRegex, " ")
            .trim()
        val scopedFallbackExpr = if (bareExpr.contains("mercadolivre", true)) {
            bareExpr
        } else {
            "$bareExpr mercadolivre".trim()
        }
        val encodedScoped = URLEncoder.encode(scopedFallbackExpr, "UTF-8")
        val result = if (bingHits.size >= 5) {
            bingHits
        } else {
            val cleanBingDef = if (searchExpr.contains("site:", true) && scopedFallbackExpr.isNotBlank()) {
                async {
                    withTimeoutOrNull(4_500L) {
                        bingImageSemaphore.withPermit {
                            val html = fetchSimpleHtml(
                                "https://www.bing.com/images/search?q=$encodedScoped&first=$first&form=HDRSC3",
                                useQuickImageClient = true
                            )
                            MercadoLivreHtmlParser.parseBingImagesHtml(html)
                        }
                    }.orEmpty()
                }
            } else null
            val yahooDef = async {
                withTimeoutOrNull(4_200L) {
                    val yahooHtml = fetchSimpleHtml(
                        "https://images.search.yahoo.com/search/images?p=$encodedScoped&fr=sfp&ei=UTF-8",
                        useQuickImageClient = true
                    )
                    MercadoLivreHtmlParser.parseYahooImagesHtml(yahooHtml)
                }.orEmpty()
            }
            val googleDef = async {
                withTimeoutOrNull(4_200L) {
                    val googleHtml = fetchSimpleHtml(
                        "https://www.google.com/search?q=$encodedScoped&udm=2&hl=pt-BR&gl=BR",
                        useQuickImageClient = true
                    )
                    MercadoLivreHtmlParser.parseGoogleImagesHtml(googleHtml)
                }.orEmpty()
            }
            (bingHits + cleanBingDef?.await().orEmpty() + yahooDef.await() + googleDef.await())
        }
        result
    }

    private fun fetchSimpleHtml(url: String, useQuickImageClient: Boolean = false): String {
        return try {
            val caller = if (useQuickImageClient) quickImageClient else fastHttpClient
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", desktopUa)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")
                .build()
            caller.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val bytes = resp.body?.bytes() ?: return ""
                MercadoLivreHtmlParser.decodeHttpBytes(bytes, resp.body?.contentType()?.charset())
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchHtmlWithCookies(
        url: String,
        maxBytes: Int = 3_500_000,
        useQuickClient: Boolean = false,
        useDesktopUa: Boolean = false,
        clientOverride: okhttp3.OkHttpClient? = null
    ): String {
        var currentUrl = url
        var referer: String? = "https://www.mercadolivre.com.br/"
        val hopCookies = LinkedHashMap<String, String>()
        val httpCaller = clientOverride ?: if (useQuickClient) quickPageClient else noRedirectClient
        val userAgent = if (useDesktopUa) desktopUa else mobileUa

        for (hop in 0 until 4) {
            val cookieHeader = if (hopCookies.isNotEmpty()) {
                hopCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else ""
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)
            if (cookieHeader.isNotBlank()) reqBuilder.header("Cookie", cookieHeader)

            try {
                httpCaller.newCall(reqBuilder.build()).execute().use { resp ->
                    val locationHeader = resp.header("Location")?.trim().orEmpty()
                    val isRedirect = resp.code in listOf(301, 302, 303, 307, 308)
                    val isAuthChallengeRedirect = isRedirect &&
                        (locationHeader.contains("account-verification", true) || locationHeader.contains("/login", true))
                    if ((resp.isSuccessful || isRedirect) && !isAuthChallengeRedirect) {
                        for (sc in resp.headers("Set-Cookie")) {
                            val pair = sc.substringBefore(";").trim()
                            val eq = pair.indexOf('=')
                            if (eq > 0) {
                                val k = pair.substring(0, eq).trim()
                                val v = pair.substring(eq + 1).trim()
                                hopCookies[k] = v
                            }
                        }
                    }
                    if (isRedirect) {
                        if (locationHeader.isBlank() || isAuthChallengeRedirect) {
                            return ""
                        }
                        referer = currentUrl
                        currentUrl = resp.request.url.resolve(locationHeader)?.toString() ?: return ""
                        return@use
                    }
                    if (!resp.isSuccessful) return ""
                    val stream = resp.body?.byteStream() ?: return ""
                    val buffer = ByteArray(maxBytes)
                    var readTotal = 0
                    try {
                        while (readTotal < buffer.size) {
                            val r = stream.read(buffer, readTotal, buffer.size - readTotal)
                            if (r <= 0) break
                            readTotal += r
                        }
                    } catch (_: java.io.IOException) {
                        // Preserve partial HTML buffer read before socket timeout or truncation
                    }
                    if (readTotal <= 0) return ""
                    val html = MercadoLivreHtmlParser.decodeHttpBytes(
                        buffer.copyOf(readTotal),
                        resp.body?.contentType()?.charset()
                    )
                    if (html.contains("account-verification", true) &&
                        !html.contains("poly-card") &&
                        !html.contains("__PRELOADED_STATE__")
                    ) {
                        return ""
                    }
                    return html
                }
            } catch (_: Exception) {
                return ""
            }
        }
        return ""
    }

    private fun resolveRedirectTarget(redirectUrl: String): String? {
        var current = redirectUrl
        for (hop in 0 until 5) {
            try {
                // Single GET with followRedirects=false: resolves 302 Location immediately without downloading body
                val req = Request.Builder().url(current).get().header("User-Agent", desktopUa).build()
                quickPageClient.newCall(req).execute().use { resp ->
                    val loc = resp.header("Location")?.trim().orEmpty()
                    if (resp.code in 300..399 && loc.isNotBlank()) {
                        current = resp.request.url.resolve(loc)?.toString() ?: return null
                        if (current.contains("mercadolivre.com.br", true)) return current
                    } else if (resp.isSuccessful) {
                        val stream = resp.body?.byteStream() ?: return current.takeIf { it.contains("mercadolivre.com.br", true) }
                        val buf = ByteArray(16_384)
                        var readTotal = 0
                        while (readTotal < buf.size) {
                            val r = stream.read(buf, readTotal, buf.size - readTotal)
                            if (r <= 0) break
                            readTotal += r
                        }
                        val snippet = String(buf, 0, readTotal, Charsets.UTF_8)
                        val metaRefresh = metaRefreshRegex.find(snippet)?.groupValues?.getOrNull(1)
                        val jsRedirect = jsRedirectRegex.find(snippet)?.groupValues?.getOrNull(1)
                        val directMlHref = directMlHrefRegex.find(snippet)?.value
                        val nextTarget = metaRefresh ?: jsRedirect ?: directMlHref
                        if (!nextTarget.isNullOrBlank()) {
                            val resolved = resp.request.url.resolve(HtmlTextDecoder.decode(nextTarget))?.toString()
                                ?: HtmlTextDecoder.decode(nextTarget)
                            if (resolved.contains("mercadolivre.com.br", true)) return resolved
                            current = resolved
                        } else {
                            return current.takeIf { it.contains("mercadolivre.com.br", true) }
                        }
                    } else {
                        return current.takeIf { it.contains("mercadolivre.com.br", true) }
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return current.takeIf { it.contains("mercadolivre.com.br", true) }
    }

    private fun prioritizeCandidates(list: List<RawStoreCandidate>): List<RawStoreCandidate> {
        if (list.isEmpty()) return emptyList()
        val deduped = LinkedHashMap<String, RawStoreCandidate>()
        for (item in list) {
            val titleKey = TextNormalizer.normalize(item.title).ifBlank { item.id }
            val existing = deduped[titleKey]
            if (existing == null) {
                deduped[titleKey] = item
            } else {
                val bestImg = when {
                    existing.imageUrl.isBlank() -> item.imageUrl
                    !existing.imageUrl.contains("mlstatic.com", true) && item.imageUrl.contains("mlstatic.com", true) -> item.imageUrl
                    else -> existing.imageUrl
                }
                val exPrice = parseBrlValue(existing.priceText)
                val inPrice = parseBrlValue(item.priceText)
                val bestPrice = when {
                    existing.priceText.isBlank() -> item.priceText
                    exPrice in 0.01..24.99 && inPrice > exPrice -> item.priceText
                    else -> existing.priceText
                }
                deduped[titleKey] = existing.copy(imageUrl = bestImg, priceText = bestPrice)
            }
        }
        val cleanList = deduped.values.toList()
        return cleanList.sortedWith(
            compareByDescending<RawStoreCandidate> { it.imageUrl.isNotBlank() && it.priceText.isNotBlank() }
                .thenByDescending { it.priceText.isNotBlank() }
                .thenByDescending { it.imageUrl.isNotBlank() }
                .thenBy { it.sourceRank }
        )
    }

    private fun parseBrlValue(priceText: String): Double {
        return MercadoLivreHtmlParser.parseBrlAmount(priceText)
    }

    private fun buildCoreQuery(raw: String): String {
        val words = TextNormalizer.normalize(raw).split(' ').filter { it.length >= 2 }
        return if (words.size > 2) words.take(2).joinToString(" ") else ""
    }

    private companion object {
        const val DOMAIN_HINTS_CACHE_MAX_ENTRIES = 32
        const val DOMAIN_HINTS_CACHE_TTL_MS = 15L * 60L * 1000L

        val mlSlugNonAlnumRegex = Regex("[^a-z0-9]+")
        val widQueryRegex = Regex("""(?:[?&#]|%3F|%26)wid=(MLB\d+)""", RegexOption.IGNORE_CASE)
        val directMlbRegex = Regex("""^MLB\d+$""")
        val bareMlbIdRegex = Regex("""^MLBU?\d+$""", RegexOption.IGNORE_CASE)
        val capacityTokenRegex = Regex("""\d+(?:gb|tb)""")
        val hintPromoCleanupRegex = Regex("""\b(?:ofertas?|mais vendidos?|mercado livre)\b""", RegexOption.IGNORE_CASE)
        val wsRegex = Regex("\\s+")
        val siteColonRegex = Regex("""\bsite:\S+""", RegexOption.IGNORE_CASE)
        val metaRefreshRegex = Regex(
            """<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"'\s>]+)["']""",
            RegexOption.IGNORE_CASE
        )
        val jsRedirectRegex = Regex(
            """window\.location(?:\.replace|\s*\.\s*href\s*=|\s*=)\s*\(?["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val directMlHrefRegex = Regex(
            """https?://[^"'\s<>\\]*mercadolivre\.com\.br[^"'\s<>\\]*""",
            RegexOption.IGNORE_CASE
        )

        val fuzzyGenericCodes = setOf(
            "ddr3", "ddr4", "ddr5", "v3", "v4", "v5", "e5", "lga", "2011", "lga2011",
            "4k", "5g", "4g", "110v", "220v", "usb2", "usb3", "x99", "x79", "am4", "am5"
        )
        val fuzzyAccessoryWords = setOf(
            "controle", "capa", "capinha", "pelicula", "cabo", "carregador", "suporte", "adaptador", "adesivo"
        )
    }
}
