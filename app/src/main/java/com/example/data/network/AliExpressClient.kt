package com.example.data.network

import com.example.BuildConfig
import com.example.data.model.PriceFormatter
import com.example.data.model.ProductAttribute
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.ui.util.DeepLinkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TreeMap
import kotlin.coroutines.resume

class AliExpressClient {

    private val client = NetworkClientProvider.httpClient

    @Volatile
    private var preferredSignPrefix: String? = null

    suspend fun searchProducts(
        queriesPt: List<String>,
        queriesEn: List<String>
    ): StoreFetchOutcome = withContext(Dispatchers.IO) {
        val appKey = BuildConfig.ALI_APP_KEY.trim()
        val appSecret = BuildConfig.ALI_APP_SECRET.trim()
        val trackingId = BuildConfig.ALI_TRACKING_ID.trim()

        if (appKey.isBlank() || appSecret.isBlank()) {
            return@withContext StoreFetchOutcome(
                candidates = emptyList(),
                statusMessage = "AliExpress: credenciais de afiliado não configuradas."
            )
        }

        val tasks = mutableListOf<Triple<String, String, String?>>()
        val ptClean = queriesPt.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val enClean = queriesEn.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        ptClean.firstOrNull()?.let { q ->
            tasks.add(Triple(q, "PT", "LAST_VOLUME_DESC"))
        }
        ptClean.getOrNull(1)?.let { q ->
            if (tasks.none { it.first.equals(q, ignoreCase = true) }) {
                tasks.add(Triple(q, "PT", "LAST_VOLUME_DESC"))
            }
        }
        enClean.firstOrNull()?.let { q ->
            tasks.add(Triple(q, "EN", "LAST_VOLUME_DESC"))
        }
        enClean.getOrNull(1)?.let { q ->
            if (tasks.none { it.first.equals(q, ignoreCase = true) }) {
                tasks.add(Triple(q, "EN", "LAST_VOLUME_DESC"))
            }
        }

        if (tasks.isEmpty()) return@withContext StoreFetchOutcome(emptyList())

        val batches = coroutineScope {
            tasks.map { (kw, lang, sortMode) ->
                async { fetchQuery(appKey, appSecret, trackingId, kw, lang, sortMode) }
            }
        }.awaitAll()

        val merged = LinkedHashMap<String, RawStoreCandidate>()
        var lastError: String? = null
        for (batch in batches) {
            if (batch.errorMessage != null) lastError = batch.errorMessage
            for (item in batch.items) {
                merged.putIfAbsent(item.id, item)
            }
        }

        if (merged.isEmpty()) {
            val defaultSortTasks = listOfNotNull(
                ptClean.firstOrNull()?.let { it to "PT" },
                enClean.firstOrNull()?.let { it to "EN" }
            ).distinctBy { it.first.lowercase() }
            if (defaultSortTasks.isNotEmpty()) {
                val fallbackBatches = coroutineScope {
                    defaultSortTasks.map { (kw, lang) ->
                        async { fetchQuery(appKey, appSecret, trackingId, kw, lang, null) }
                    }
                }.awaitAll()
                for (batch in fallbackBatches) {
                    if (batch.errorMessage != null && lastError == null) lastError = batch.errorMessage
                    for (item in batch.items) {
                        merged.putIfAbsent(item.id, item)
                    }
                }
            }
        }

        StoreFetchOutcome(
            candidates = merged.values.toList(),
            statusMessage = if (merged.isEmpty()) lastError ?: "AliExpress não retornou itens para esta busca." else null
        )
    }

    suspend fun searchProductsPage(
        queriesPt: List<String>,
        queriesEn: List<String>,
        page: Int
    ): StoreFetchOutcome = withContext(Dispatchers.IO) {
        if (page <= 1) return@withContext searchProducts(queriesPt, queriesEn)
        val appKey = BuildConfig.ALI_APP_KEY.trim()
        val appSecret = BuildConfig.ALI_APP_SECRET.trim()
        val trackingId = BuildConfig.ALI_TRACKING_ID.trim()
        if (appKey.isBlank() || appSecret.isBlank()) {
            return@withContext StoreFetchOutcome(emptyList())
        }

        val tasks = mutableListOf<Triple<String, String, String?>>()
        val ptFirst = queriesPt.map { it.trim() }.firstOrNull { it.isNotBlank() }
        val enFirst = queriesEn.map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (!ptFirst.isNullOrBlank()) tasks.add(Triple(ptFirst, "PT", "LAST_VOLUME_DESC"))
        if (!enFirst.isNullOrBlank() && !enFirst.equals(ptFirst, ignoreCase = true)) {
            tasks.add(Triple(enFirst, "EN", "LAST_VOLUME_DESC"))
        }
        if (tasks.isEmpty()) return@withContext StoreFetchOutcome(emptyList())

        val batches = coroutineScope {
            tasks.map { (kw, lang, sortMode) ->
                async { fetchQuery(appKey, appSecret, trackingId, kw, lang, sortMode, pageNo = page) }
            }
        }.awaitAll()

        val merged = LinkedHashMap<String, RawStoreCandidate>()
        for (batch in batches) {
            batch.items.forEach { merged.putIfAbsent(it.id, it) }
        }
        StoreFetchOutcome(candidates = merged.values.toList())
    }

    suspend fun fetchWeeklyHotProducts(): StoreFetchOutcome = withContext(Dispatchers.IO) {
        fetchWeeklyHotProductsPage(page = 1)
    }

    suspend fun fetchWeeklyHotProductsPage(page: Int): StoreFetchOutcome = withContext(Dispatchers.IO) {
        val appKey = BuildConfig.ALI_APP_KEY.trim()
        val appSecret = BuildConfig.ALI_APP_SECRET.trim()
        val trackingId = BuildConfig.ALI_TRACKING_ID.trim()

        if (appKey.isBlank() || appSecret.isBlank()) {
            return@withContext StoreFetchOutcome(
                candidates = emptyList(),
                statusMessage = "AliExpress: credenciais de afiliado não configuradas."
            )
        }

        val hotBatch = fetchHotProductsPage(
            appKey = appKey,
            appSecret = appSecret,
            trackingId = trackingId,
            language = "PT",
            sortMode = "LAST_VOLUME_DESC",
            pageNo = page
        )

        val merged = LinkedHashMap<String, RawStoreCandidate>()
        var lastError = hotBatch.errorMessage
        hotBatch.items.forEach { merged.putIfAbsent(it.id, it) }

        if (merged.size < 15) {
            val fallbackBatch = fetchQuery(
                appKey = appKey,
                appSecret = appSecret,
                trackingId = trackingId,
                keywords = "ofertas mais vendidos",
                language = "PT",
                sortMode = "LAST_VOLUME_DESC",
                pageNo = page
            )
            if (fallbackBatch.errorMessage != null && merged.isEmpty()) {
                lastError = fallbackBatch.errorMessage
            }
            fallbackBatch.items.forEach { merged.putIfAbsent(it.id, it) }
        }

        StoreFetchOutcome(
            candidates = merged.values.toList(),
            statusMessage = if (merged.isEmpty()) lastError ?: "AliExpress não retornou ofertas da semana no momento." else null
        )
    }

    private data class AliBatch(
        val items: List<RawStoreCandidate>,
        val errorMessage: String? = null
    )

    private suspend fun fetchHotProductsPage(
        appKey: String,
        appSecret: String,
        trackingId: String,
        language: String,
        sortMode: String,
        pageNo: Int = 1
    ): AliBatch {
        val initialPrefix = preferredSignPrefix ?: ""
        val firstAttempt = executeSignedCall(
            appKey = appKey,
            appSecret = appSecret,
            trackingId = trackingId,
            method = "aliexpress.affiliate.hotproduct.query",
            responseRootKey = "aliexpress_affiliate_hotproduct_query_response",
            keywords = null,
            language = language,
            sortMode = sortMode,
            signPrefix = initialPrefix,
            pageSize = "40",
            pageNo = pageNo
        )
        if (firstAttempt.errorCode == "IncompleteSignature") {
            val fallbackPrefix = if (initialPrefix.isEmpty()) "/sync" else ""
            val second = executeSignedCall(
                appKey = appKey,
                appSecret = appSecret,
                trackingId = trackingId,
                method = "aliexpress.affiliate.hotproduct.query",
                responseRootKey = "aliexpress_affiliate_hotproduct_query_response",
                keywords = null,
                language = language,
                sortMode = sortMode,
                signPrefix = fallbackPrefix,
                pageSize = "40",
                pageNo = pageNo
            )
            if (second.items.isNotEmpty() || second.errorCode != "IncompleteSignature") {
                preferredSignPrefix = fallbackPrefix
            }
            return second.toBatch()
        }
        if (firstAttempt.items.isNotEmpty()) preferredSignPrefix = initialPrefix
        return firstAttempt.toBatch()
    }

    private suspend fun fetchQuery(
        appKey: String,
        appSecret: String,
        trackingId: String,
        keywords: String,
        language: String,
        sortMode: String?,
        pageNo: Int = 1
    ): AliBatch {
        val initialPrefix = preferredSignPrefix ?: ""
        val firstAttempt = executeSignedCall(
            appKey = appKey,
            appSecret = appSecret,
            trackingId = trackingId,
            method = "aliexpress.affiliate.product.query",
            responseRootKey = "aliexpress_affiliate_product_query_response",
            keywords = keywords,
            language = language,
            sortMode = sortMode,
            signPrefix = initialPrefix,
            pageSize = "40",
            pageNo = pageNo
        )
        if (firstAttempt.errorCode == "IncompleteSignature") {
            val fallbackPrefix = if (initialPrefix.isEmpty()) "/sync" else ""
            val second = executeSignedCall(
                appKey = appKey,
                appSecret = appSecret,
                trackingId = trackingId,
                method = "aliexpress.affiliate.product.query",
                responseRootKey = "aliexpress_affiliate_product_query_response",
                keywords = keywords,
                language = language,
                sortMode = sortMode,
                signPrefix = fallbackPrefix,
                pageSize = "40",
                pageNo = pageNo
            )
            if (second.items.isNotEmpty() || second.errorCode != "IncompleteSignature") {
                preferredSignPrefix = fallbackPrefix
            }
            return second.toBatch()
        }
        if (firstAttempt.items.isNotEmpty()) preferredSignPrefix = initialPrefix
        return firstAttempt.toBatch()
    }

    private data class InternalCallResult(
        val items: List<RawStoreCandidate>,
        val errorMessage: String? = null,
        val errorCode: String? = null
    ) {
        fun toBatch() = AliBatch(items, errorMessage)
    }

    private suspend fun executeSignedCall(
        appKey: String,
        appSecret: String,
        trackingId: String,
        method: String,
        responseRootKey: String,
        keywords: String?,
        language: String,
        sortMode: String?,
        signPrefix: String,
        pageSize: String,
        pageNo: Int = 1
    ): InternalCallResult {
        val params = TreeMap<String, String>()
        params["app_key"] = appKey
        params["format"] = "json"
        if (!keywords.isNullOrBlank()) {
            params["keywords"] = keywords
        }
        params["method"] = method
        params["page_no"] = pageNo.coerceAtLeast(1).toString()
        params["page_size"] = pageSize
        params["ship_to_country"] = "BR"
        params["sign_method"] = "sha256"
        if (!sortMode.isNullOrBlank()) {
            params["sort"] = sortMode
        }
        params["target_currency"] = "BRL"
        params["target_language"] = language
        params["timestamp"] = System.currentTimeMillis().toString()
        if (trackingId.isNotBlank()) {
            params["tracking_id"] = trackingId
        }
        params["v"] = "2.0"

        val baseString = buildString {
            append(signPrefix)
            for ((k, v) in params) {
                append(k).append(v)
            }
        }
        val sign = NetworkClientProvider.hmacSha256UpperHex(appSecret, baseString)

        val urlBuilder = "https://api-sg.aliexpress.com/sync".toHttpUrl().newBuilder()
        for ((k, v) in params) {
            urlBuilder.addQueryParameter(k, v)
        }
        urlBuilder.addQueryParameter("sign", sign)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("Accept", "application/json")
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive || call.isCanceled()) return
                    cont.resume(InternalCallResult(emptyList(), "Falha de conexão com a API AliExpress."))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { res ->
                            val body = res.body?.string().orEmpty()
                            if (!res.isSuccessful) {
                                InternalCallResult(emptyList(), "AliExpress respondeu com status ${res.code}.")
                            } else {
                                parseAliExpressJson(body, responseRootKey, keywords)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        InternalCallResult(emptyList(), "Falha ao processar resposta da API AliExpress.")
                    }
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }
            })
        }
    }

    private fun parseAliExpressJson(
        body: String,
        responseRootKey: String,
        keywords: String?
    ): InternalCallResult {
        val root = JSONObject(body)
        val errorResponse = root.optJSONObject("error_response")
        if (errorResponse != null) {
            val code = errorResponse.optString("code", "")
            val msg = errorResponse.optString("msg", "Erro na API AliExpress.")
            return InternalCallResult(emptyList(), "AliExpress: $msg", code)
        }

        val respResult = root.optJSONObject(responseRootKey)
            ?.optJSONObject("resp_result")
        val respCode = respResult?.optInt("resp_code", 200) ?: 200
        if (respCode != 200) {
            val msg = respResult?.optString("resp_msg", "Erro na consulta AliExpress.").orEmpty()
            return InternalCallResult(emptyList(), "AliExpress: $msg")
        }

        val productsArray = respResult
            ?.optJSONObject("result")
            ?.optJSONObject("products")
            ?.optJSONArray("product")
            ?: JSONArray()

        val out = mutableListOf<RawStoreCandidate>()
        for (i in 0 until productsArray.length()) {
            val p = productsArray.optJSONObject(i) ?: continue
            val id = p.optString("product_id", "").trim()
            val title = p.optString("product_title", "").trim()
            val detailUrl = p.optString("product_detail_url", "").trim()
            val promoLink = p.optString("promotion_link", "").trim()
            val finalUrl = promoLink.ifBlank { DeepLinkUtils.formatAliExpressAffiliateUrl(detailUrl) }
            if (id.isBlank() || title.isBlank() || finalUrl.isBlank()) continue

            val imageUrl = p.optString("product_main_image_url", "").trim()
                .replace("http://", "https://")
            val cat1 = p.optString("first_level_category_name", "").trim()
            val cat2 = p.optString("second_level_category_name", "").trim()
            val categoryPath = listOf(cat1, cat2).filter { it.isNotBlank() }.joinToString(" > ")
            val rawPrice = p.optString("target_sale_price", "")
                .ifBlank { p.optString("target_app_sale_price", "") }
                .ifBlank { p.optString("sale_price", "") }
                .ifBlank { p.optString("target_original_price", "") }
            val priceText = PriceFormatter.formatBrl(rawPrice)

            val attrs = buildList {
                if (cat2.isNotBlank()) add(ProductAttribute("Categoria", cat2))
                else if (cat1.isNotBlank()) add(ProductAttribute("Categoria", cat1))
                val type = p.optString("platform_product_type", "").trim()
                if (type.isNotBlank()) add(ProductAttribute("Tipo", type))
            }

            out.add(
                RawStoreCandidate(
                    id = "ae_$id",
                    store = StoreType.ALIEXPRESS,
                    title = title,
                    imageUrl = imageUrl,
                    productUrl = detailUrl.ifBlank { finalUrl },
                    affiliateUrl = finalUrl,
                    priceText = priceText,
                    categoryPath = categoryPath,
                    attributes = attrs,
                    sourceQueryVariant = keywords ?: "hot_weekly",
                    sourceRank = i
                )
            )
        }
        return InternalCallResult(out)
    }
}
