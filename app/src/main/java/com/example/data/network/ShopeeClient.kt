package com.example.data.network

import com.example.BuildConfig
import com.example.data.model.PriceFormatter
import com.example.data.model.ProductAttribute
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.ui.util.DeepLinkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ShopeeClient {

    private val client = NetworkClientProvider.httpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(18_000L, TimeUnit.MILLISECONDS)
        .build()
    private val jsonMediaType = "application/json".toMediaType()
    private val rateMutex = Mutex()
    private var lastCallTimestampMs = 0L

    suspend fun searchProducts(queries: List<String>): StoreFetchOutcome =
        withContext(Dispatchers.IO) {
            val appId = BuildConfig.SHOPEE_APP_ID.trim().trim('"', '\'')
            val secret = BuildConfig.SHOPEE_SECRET.trim().trim('"', '\'')
            if (appId.isBlank() || secret.isBlank()) {
                return@withContext StoreFetchOutcome(
                    candidates = emptyList(),
                    statusMessage = "Shopee: credenciais de afiliado não configuradas."
                )
            }

            val cleanQueries = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
            if (cleanQueries.isEmpty()) return@withContext StoreFetchOutcome(emptyList())

            val deadlineMs = System.currentTimeMillis() + 24_000L
            val merged = LinkedHashMap<String, RawStoreCandidate>()
            var lastError: String? = null

            for ((idx, q) in cleanQueries.withIndex()) {
                if (merged.size >= 30 || (idx > 0 && System.currentTimeMillis() >= deadlineMs - 3_000L)) {
                    break
                }
                val batch = fetchByKeywordWithRecall(
                    appId = appId,
                    secret = secret,
                    keyword = q,
                    deadlineMs = deadlineMs,
                    fetchExtraPage = merged.size < 25
                )
                if (batch.errorMessage != null) lastError = batch.errorMessage
                for (item in batch.items) {
                    merged.putIfAbsent(item.id, item)
                }
            }

            StoreFetchOutcome(
                candidates = merged.values.toList(),
                statusMessage = if (merged.isEmpty()) lastError ?: "Shopee não retornou itens para esta busca." else null
            )
        }

    suspend fun searchProductsPage(
        queries: List<String>,
        page: Int,
        excludeIds: Set<String> = emptySet()
    ): StoreFetchOutcome =
        withContext(Dispatchers.IO) {
            if (page <= 1) return@withContext searchProducts(queries)
            val appId = BuildConfig.SHOPEE_APP_ID.trim().trim('"', '\'')
            val secret = BuildConfig.SHOPEE_SECRET.trim().trim('"', '\'')
            if (appId.isBlank() || secret.isBlank()) {
                return@withContext StoreFetchOutcome(emptyList())
            }
            val cleanQueries = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
            if (cleanQueries.isEmpty()) return@withContext StoreFetchOutcome(emptyList())

            val deadlineMs = System.currentTimeMillis() + 18_000L
            val merged = LinkedHashMap<String, RawStoreCandidate>()
            fun freshCount(): Int = merged.keys.count { it !in excludeIds }

            val primaryRaw = cleanQueries.first()
            val primaryKw = escapeGraphqlString(primaryRaw)
            val altPage = (page - 1).coerceAtLeast(1)

            val barePagedQuery = """{ productOfferV2(keyword: "$primaryKw", page: $page, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
            val primaryRes = executeGraphqlWithRetry(appId, secret, barePagedQuery, primaryRaw)
            primaryRes.items.forEach { merged.putIfAbsent(it.id, it) }

            if (freshCount() < 14 && System.currentTimeMillis() < deadlineMs - 2_500L) {
                val sortPagedQuery = """{ productOfferV2(keyword: "$primaryKw", listType: 0, sortType: 2, page: $altPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
                val sortRes = executeGraphqlWithRetry(appId, secret, sortPagedQuery, primaryRaw)
                sortRes.items.forEach { merged.putIfAbsent(it.id, it) }
            }

            if (freshCount() < 16 && cleanQueries.size > 1 && System.currentTimeMillis() < deadlineMs - 2_500L) {
                val secondaryQuery = cleanQueries[1]
                val secEscaped = escapeGraphqlString(secondaryQuery)
                val secQuery = """{ productOfferV2(keyword: "$secEscaped", page: $altPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
                val secRes = executeGraphqlWithRetry(appId, secret, secQuery, secondaryQuery)
                secRes.items.forEach { merged.putIfAbsent(it.id, it) }
            }

            if (freshCount() < 16 && cleanQueries.size > 2 && System.currentTimeMillis() < deadlineMs - 2_500L) {
                val thirdQuery = cleanQueries[2]
                val thirdEscaped = escapeGraphqlString(thirdQuery)
                val thirdGql = """{ productOfferV2(keyword: "$thirdEscaped", page: $altPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
                val thirdRes = executeGraphqlWithRetry(appId, secret, thirdGql, thirdQuery)
                thirdRes.items.forEach { merged.putIfAbsent(it.id, it) }
            }

            if (freshCount() < 12 && System.currentTimeMillis() < deadlineMs - 2_500L) {
                val commPagedQuery = """{ productOfferV2(keyword: "$primaryKw", sortType: 5, page: $altPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
                val commRes = executeGraphqlWithRetry(appId, secret, commPagedQuery, primaryRaw)
                commRes.items.forEach { merged.putIfAbsent(it.id, it) }
            }

            if (freshCount() < 10 && System.currentTimeMillis() < deadlineMs - 2_500L) {
                val words = primaryRaw.split(wsRegex).filter { it.isNotBlank() }
                if (words.size > 2) {
                    val shortKw = escapeGraphqlString(words.take(2).joinToString(" "))
                    val shortQuery = """{ productOfferV2(keyword: "$shortKw", page: $altPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
                    val shortRes = executeGraphqlWithRetry(appId, secret, shortQuery, primaryRaw)
                    shortRes.items.forEach { merged.putIfAbsent(it.id, it) }
                }
            }

            if (freshCount() < 8 && page >= 3 && System.currentTimeMillis() < deadlineMs - 2_500L) {
                val priceSortPage = (page - 2).coerceAtLeast(1)
                val extraSortType = if (page % 2 == 0) 3 else 4
                val extraSortQuery = """{ productOfferV2(keyword: "$primaryKw", sortType: $extraSortType, page: $priceSortPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
                val extraRes = executeGraphqlWithRetry(appId, secret, extraSortQuery, primaryRaw)
                extraRes.items.forEach { merged.putIfAbsent(it.id, it) }
            }

            StoreFetchOutcome(candidates = merged.values.toList())
        }

    suspend fun fetchWeeklyBestDeals(): StoreFetchOutcome =
        fetchWeeklyBestDealsPage(page = 1)

    suspend fun fetchWeeklyBestDealsPage(
        page: Int,
        excludeIds: Set<String> = emptySet()
    ): StoreFetchOutcome =
        withContext(Dispatchers.IO) {
            val appId = BuildConfig.SHOPEE_APP_ID.trim().trim('"', '\'')
            val secret = BuildConfig.SHOPEE_SECRET.trim().trim('"', '\'')
            if (appId.isBlank() || secret.isBlank()) {
                return@withContext StoreFetchOutcome(
                    candidates = emptyList(),
                    statusMessage = "Shopee: credenciais de afiliado não configuradas."
                )
            }

            val deadlineMs = System.currentTimeMillis() + 22_000L
            val merged = LinkedHashMap<String, RawStoreCandidate>()
            var lastError: String? = null
            val targetPage = page.coerceAtLeast(1)
            fun freshCount(): Int = merged.keys.count { it !in excludeIds }

            val queries = listOf(
                """{ productOfferV2(listType: 1, sortType: 2, page: $targetPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }""",
                """{ productOfferV2(listType: 0, sortType: 5, page: $targetPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }""",
                """{ productOfferV2(listType: 2, page: $targetPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }""",
                """{ productOfferV2(page: $targetPage, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
            )

            for ((idx, gql) in queries.withIndex()) {
                if (idx > 0 && System.currentTimeMillis() >= deadlineMs - 2_500L) break
                val res = executeGraphqlWithRetry(appId, secret, gql, "weekly_top")
                if (res.errorMessage != null) lastError = res.errorMessage
                res.items.forEach { merged.putIfAbsent(it.id, it) }
                if (freshCount() >= 18) break
            }

            StoreFetchOutcome(
                candidates = merged.values.toList(),
                statusMessage = if (merged.isEmpty()) lastError ?: "Shopee não retornou ofertas da semana no momento." else null
            )
        }

    private data class ShopeeBatch(
        val items: List<RawStoreCandidate>,
        val errorMessage: String? = null,
        val isSystemOrTransientError: Boolean = false
    )

    private suspend fun fetchByKeywordWithRecall(
        appId: String,
        secret: String,
        keyword: String,
        deadlineMs: Long,
        fetchExtraPage: Boolean
    ): ShopeeBatch {
        val escapedKw = escapeGraphqlString(keyword)
        val merged = LinkedHashMap<String, RawStoreCandidate>()
        var lastError: String? = null

        // Primary query uses safe limit=20 without heavy sortType joins to maximize recall and stability;
        // if sparse or [10000], cascade through page 2, sortType 2, sortType 5, and simplified 2-word query.
        val bareKeywordPage1 = """{ productOfferV2(keyword: "$escapedKw", page: 1, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
        val bareKeywordPage2 = """{ productOfferV2(keyword: "$escapedKw", page: 2, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
        val salesPage1 = """{ productOfferV2(keyword: "$escapedKw", listType: 0, sortType: 2, page: 1, limit: 20) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
        val commissionSort = """{ productOfferV2(keyword: "$escapedKw", sortType: 5, page: 1, limit: 15) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""

        val primaryRes = executeGraphqlWithRetry(appId, secret, bareKeywordPage1, keyword)
        if (primaryRes.items.isNotEmpty()) {
            primaryRes.items.forEach { merged.putIfAbsent(it.id, it) }
            if (fetchExtraPage && merged.size < 25 && System.currentTimeMillis() < deadlineMs - 3_500L) {
                val page2Res = executeGraphqlWithRetry(appId, secret, bareKeywordPage2, keyword)
                page2Res.items.forEach { merged.putIfAbsent(it.id, it) }
            }
            return ShopeeBatch(merged.values.toList(), null)
        }
        if (primaryRes.errorMessage != null) lastError = primaryRes.errorMessage

        if (System.currentTimeMillis() < deadlineMs - 3_000L) {
            val salesRes = executeGraphqlWithRetry(appId, secret, salesPage1, keyword)
            if (salesRes.items.isNotEmpty()) {
                salesRes.items.forEach { merged.putIfAbsent(it.id, it) }
                return ShopeeBatch(merged.values.toList(), null)
            }
            if (salesRes.errorMessage != null) lastError = salesRes.errorMessage
        }

        if (System.currentTimeMillis() < deadlineMs - 3_000L) {
            val commRes = executeGraphqlWithRetry(appId, secret, commissionSort, keyword)
            if (commRes.items.isNotEmpty()) {
                commRes.items.forEach { merged.putIfAbsent(it.id, it) }
                return ShopeeBatch(merged.values.toList(), null)
            }
            if (commRes.errorMessage != null) lastError = commRes.errorMessage
        }

        val words = keyword.trim().split(wsRegex).filter { it.isNotBlank() }
        if (words.size > 2 && System.currentTimeMillis() < deadlineMs - 3_000L) {
            val shortKw = escapeGraphqlString(words.take(2).joinToString(" "))
            val shortQuery = """{ productOfferV2(keyword: "$shortKw", page: 1, limit: 15) { nodes { itemId productName productLink offerLink imageUrl priceMin priceMax } } }"""
            val shortRes = executeGraphqlWithRetry(appId, secret, shortQuery, keyword)
            if (shortRes.items.isNotEmpty()) {
                shortRes.items.forEach { merged.putIfAbsent(it.id, it) }
                return ShopeeBatch(merged.values.toList(), null)
            }
            if (shortRes.errorMessage != null) lastError = shortRes.errorMessage
        }

        return ShopeeBatch(
            items = emptyList(),
            errorMessage = lastError,
            isSystemOrTransientError = primaryRes.isSystemOrTransientError
        )
    }

    private suspend fun executeGraphqlWithRetry(
        appId: String,
        secret: String,
        graphqlQuery: String,
        keyword: String
    ): ShopeeBatch {
        val first = executeGraphql(appId, secret, graphqlQuery, keyword)
        if (first.items.isNotEmpty()) return first
        if (first.errorMessage?.contains("Cannot query field", ignoreCase = true) == true &&
            graphqlQuery.contains("priceMin")
        ) {
            val safeQuery = graphqlQuery.replace(" priceMin priceMax", "")
            return executeGraphql(appId, secret, safeQuery, keyword)
        }
        if (!first.isSystemOrTransientError) {
            return first
        }
        delay(600L)
        val second = executeGraphql(appId, secret, graphqlQuery, keyword)
        if (second.items.isNotEmpty() || !second.isSystemOrTransientError) {
            return second
        }
        delay(900L)
        return executeGraphql(appId, secret, graphqlQuery, keyword)
    }

    private suspend fun executeGraphql(
        appId: String,
        secret: String,
        graphqlQuery: String,
        keyword: String
    ): ShopeeBatch {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = 450L - (now - lastCallTimestampMs)
            if (waitMs > 0L) delay(waitMs)
            lastCallTimestampMs = System.currentTimeMillis()
        }

        val payload = JSONObject().apply {
            put("query", graphqlQuery)
        }.toString()

        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val signature = NetworkClientProvider.sha256Hex("$appId$timestamp$payload$secret")
        val authHeader = "SHA256 Credential=$appId, Timestamp=$timestamp, Signature=$signature"

        val request = Request.Builder()
            .url("https://open-api.affiliate.shopee.com.br/graphql")
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", authHeader)
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive || call.isCanceled()) return
                    val msg = when (e) {
                        is SocketTimeoutException, is InterruptedIOException ->
                            "Shopee demorou para responder. Toque em Tentar novamente."
                        else ->
                            "Falha de conexão com a API Shopee."
                    }
                    cont.resume(ShopeeBatch(emptyList(), msg, isSystemOrTransientError = true))
                }

                override fun onResponse(call: Call, response: Response) {
                    val batch = try {
                        response.use { res ->
                            val rawBody = res.body?.string().orEmpty()
                            if (!res.isSuccessful) {
                                val transient = res.code >= 500 || res.code == 429
                                ShopeeBatch(emptyList(), "Shopee respondeu com status ${res.code}.", transient)
                            } else {
                                parseGraphqlResponse(rawBody, keyword)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ShopeeBatch(emptyList(), "Resposta inválida da API Shopee.", isSystemOrTransientError = true)
                    }
                    if (cont.isActive) {
                        cont.resume(batch)
                    }
                }
            })
        }
    }

    private fun parseGraphqlResponse(rawBody: String, keyword: String): ShopeeBatch {
        val json = JSONObject(rawBody)
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val errObj = errors.optJSONObject(0)
            val msg = errObj?.optString("message", "").orEmpty()
            val code = errObj?.optJSONObject("extensions")?.optInt("code", 0) ?: 0
            val transient = code == 10000 || code == 10030 ||
                msg.contains("10000") || msg.contains("10030") ||
                msg.contains("System Error", ignoreCase = true)
            val formattedErr = when {
                code == 10000 || msg.contains("10000") || msg.contains("System Error", ignoreCase = true) ->
                    "Shopee temporariamente instável (erro 10000). Tente buscar novamente em instantes."
                msg.isNotBlank() -> "Shopee: $msg"
                else -> "Erro na API Shopee."
            }
            return ShopeeBatch(
                items = emptyList(),
                errorMessage = formattedErr,
                isSystemOrTransientError = transient
            )
        }

        val nodes = json.optJSONObject("data")
            ?.optJSONObject("productOfferV2")
            ?.optJSONArray("nodes")
            ?: JSONArray()

        val out = mutableListOf<RawStoreCandidate>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val itemId = node.optString("itemId", "").trim()
            val title = node.optString("productName", "").trim()
            val productLink = node.optString("productLink", "").trim()
            val offerLink = node.optString("offerLink", "").trim()
            val finalUrl = offerLink.ifBlank { DeepLinkUtils.formatShopeeAffiliateUrl(productLink) }
            if (itemId.isBlank() || title.isBlank() || finalUrl.isBlank()) continue

            val imageUrl = node.optString("imageUrl", "").trim().replace("http://", "https://")
            val shopName = node.optString("shopName", "").trim()
            val rawPrice = node.optString("priceMin", "")
                .ifBlank { node.optString("price", "") }
                .ifBlank { node.optString("priceMax", "") }
            val priceText = PriceFormatter.formatBrl(rawPrice)

            val attrs = buildList {
                if (shopName.isNotBlank()) add(ProductAttribute("Loja", shopName))
            }

            out.add(
                RawStoreCandidate(
                    id = "sp_$itemId",
                    store = StoreType.SHOPEE,
                    title = title,
                    imageUrl = imageUrl,
                    productUrl = productLink.ifBlank { finalUrl },
                    affiliateUrl = finalUrl,
                    priceText = priceText,
                    attributes = attrs,
                    sourceQueryVariant = keyword,
                    sourceRank = i
                )
            )
        }
        return ShopeeBatch(out)
    }

    private fun escapeGraphqlString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
    }

    private companion object {
        val wsRegex = Regex("\\s+")
    }
}
