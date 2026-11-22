package com.example.data.network

import android.net.Uri
import android.util.Base64
import com.example.data.model.PriceFormatter
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.data.search.HtmlTextDecoder
import com.example.data.search.NeuralProductMatcher
import com.example.data.search.TextNormalizer
import com.example.ui.util.DeepLinkUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.Charset

internal data class SearchEngineImageHit(
    val title: String,
    val pageUrl: String,
    val imageUrl: String,
    val thumbUrl: String
) {
    val preferredImageUrl: String
        get() = when {
            imageUrl.startsWith("https://", true) && imageUrl.contains("mlstatic.com", true) -> imageUrl
            thumbUrl.startsWith("https://", true) -> thumbUrl
            imageUrl.startsWith("https://", true) -> imageUrl
            else -> ""
        }
}

internal object MercadoLivreHtmlParser {

    private val mlbRegex = Regex("""(MLBU?)-?(\d+)""", RegexOption.IGNORE_CASE)
    private val urlInTextRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val wwwInTextRegex = Regex("""\bwww\.[^\s]+""", RegexOption.IGNORE_CASE)
    private val trailingStoreRegex = Regex("""\s*[-|•]\s*(?:Mercado\s*Livre(?:\s*Brasil)?|Frete\s*gr[áa]tis|Ofertas).*$""", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<[^>]+>")
    private val wsRegex = Regex("\\s+")
    private val metaCharsetRegex = Regex("""<meta[^>]+charset=["']?\s*([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
    private val brlPriceRegex = Regex("""(?:R\$\s*|BRL\s*)(\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?|\d+(?:,\d{1,2})?)""", RegexOption.IGNORE_CASE)

    fun decodeHttpBytes(bytes: ByteArray, contentTypeCharset: Charset? = null): String {
        if (bytes.isEmpty()) return ""
        val probe = String(bytes, 0, bytes.size.coerceAtMost(2048), Charsets.ISO_8859_1)
        val metaCharsetName = metaCharsetRegex.find(probe)?.groupValues?.getOrNull(1)?.trim()
        val chosenCharset = when {
            metaCharsetName?.equals("utf-8", ignoreCase = true) == true -> Charsets.UTF_8
            contentTypeCharset != null -> contentTypeCharset
            !metaCharsetName.isNullOrBlank() -> runCatching { Charset.forName(metaCharsetName) }.getOrDefault(Charsets.UTF_8)
            else -> Charsets.UTF_8
        }
        val primary = String(bytes, chosenCharset)
        val utf8Check = if (chosenCharset != Charsets.UTF_8) String(bytes, Charsets.UTF_8) else primary
        val raw = if (chosenCharset != Charsets.UTF_8 && !utf8Check.contains('\uFFFD') && utf8Check.count { it == 'Ã' } < primary.count { it == 'Ã' }) {
            utf8Check
        } else {
            primary
        }
        return HtmlTextDecoder.decodeUrlSafe(raw)
    }

    fun extractMlbId(url: String): String? {
        val m = mlbRegex.find(url) ?: return null
        return "${m.groupValues[1].uppercase()}${m.groupValues[2]}"
    }

    fun canonicalCandidateId(url: String, prefix: String = "ml"): String {
        val mlb = extractMlbId(url)
        if (!mlb.isNullOrBlank()) return "ml_$mlb"
        val cleanUrl = url.substringBefore("?").substringBefore("#").trimEnd('/')
        return "${prefix}_${cleanUrl.hashCode()}"
    }

    fun normalizeProductUrl(rawUrl: String): String {
        val trimmed = HtmlTextDecoder.decodeUrlSafe(rawUrl).trim()
        val withHost = when {
            trimmed.startsWith("https://", true) -> trimmed
            trimmed.startsWith("http://", true) -> "https://" + trimmed.substring(7)
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://www.mercadolivre.com.br$trimmed"
            trimmed.contains("mercadolivre.com.br", true) -> "https://$trimmed"
            else -> return ""
        }
        return withHost.substringBefore("#").trim()
    }

    private val blockedProductUrlFragments = listOf(
        "/ajuda", "/contato", "/blog", "developers.", "conta.", "myaccount.",
        "auth.mercadolivre", "account-verification", "/login", "/registration",
        "/vender", "/assinaturas", "/mercado-pago", "/gz/", "click1.mercadolivre",
        "/c/", "/categorias", "/cupons", "/supermercado", "/lojas-oficiais",
        "/importados", "/landing", "/nav/", "/institucional", "/club",
        "/black-friday", "/promocoes", "/relampago"
    )
    private val directCatalogPathRegex = Regex("""mercadolivre\.com\.br/p/[a-z0-9]+""", RegexOption.IGNORE_CASE)
    private val urlOrDomainMarkers = listOf(
        "mercadolivre.com", "mercadolibre.com", "vertexaisearch.",
        "bing.com/ck", "duckduckgo.com", "r.search.yahoo"
    )
    private val slugMlbPrefixRegex = Regex("""^MLBU?-?\d+-""", RegexOption.IGNORE_CASE)
    private val bareMlbIdRegex = Regex("""^MLBU?\d+$""", RegexOption.IGNORE_CASE)
    private val reaisPriceRegex = Regex(
        """(\d{1,3}(?:\.\d{3})*|\d+)\s+reais(?:\s+com\s+(\d{1,2})\s+centavos)?""",
        RegexOption.IGNORE_CASE
    )
    private const val BRL_PRICE_CONNECTORS =
        """(?:\s+(?:o|a|os|as|de|do|da|por|no|na|em|com|total|plus|essencial|partir|apenas|at[ée]|sem\s+juros)){0,4}"""
    private val nonPriceBeforeRegex = Regex(
        """(?:meli\s*\+|assin\w*|mensal\w*|anual\w*|frete(?!\s+gr[áa]tis)|envio(?!\s+gr[áa]tis)|cupom|cashback|econom\w*|desconto|b[ôo]nus|cr[ée]dito|vale|juros|iof|taxa\w*|tarifa\w*|imposto\w*|diferen[çc]a|parcel\w*|em\s+at[ée]|\b\d{1,2}\s*x\b)$BRL_PRICE_CONNECTORS\s*[:-]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val nonPriceAfterRegex = Regex(
        """^\s*(?:/\s*m[êe]s|por\s+m[êe]s|mensa(?:l|is)|de\s+frete|de\s+envio|sem\s+juros|de\s+desconto|de\s+cashback|de\s+economia|de\s+b[ôo]nus|de\s+taxa|no\s+meli\+|em\s+cupom|off\b)""",
        RegexOption.IGNORE_CASE
    )
    private val crossOutBeforeRegex = Regex("""(?:^|\s|[|(])(?:antes|era|de)\s*$""", RegexOption.IGNORE_CASE)
    private val crossOutFollowRegex = Regex("""^\s*(?:por|agora)\s*(?:R\$|\d+\s+reais)""", RegexOption.IGNORE_CASE)
    private val tinyNoiseContextRegex = Regex(
        """(?:meli\s*\+|cupom|cashback|econom\w*|desconto|juros|iof|taxa\w*|tarifa\w*|imposto\w*|parcel\w*|mensal\w*|assin\w*|off\b|\b\d{1,2}\s*x\b)""",
        RegexOption.IGNORE_CASE
    )
    private val nonDigitsDotRegex = Regex("[^0-9.]")

    fun isValidProductUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains("mercadolivre.com.br")) return false
        if (blockedProductUrlFragments.any { it in lower }) return false
        val hasDirectProductMarker = lower.contains("/p/mlb") ||
            lower.contains("/up/mlb") ||
            lower.contains("mlb-") ||
            lower.contains("produto.mercadolivre") ||
            lower.contains("_jm") ||
            lower.contains("/social/ml") ||
            directCatalogPathRegex.containsMatchIn(lower)
        if (hasDirectProductMarker) return true
        if (lower.contains("/mais-vendidos") || lower.contains("/ofertas") || lower.contains("lista.mercadolivre")) {
            return false
        }
        val pathOnly = lower.substringAfter("mercadolivre.com.br", "").substringBefore("?").substringBefore("#").trim('/')
        if (pathOnly.isBlank()) return false
        val firstSeg = pathOnly.substringBefore('/')
        return firstSeg.length >= 12 && firstSeg.count { it == '-' } >= 2
    }

    fun looksLikeUrlOrDomain(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return true
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("www.") || lower.startsWith("//") ||
            lower.contains("://") || lower.contains(" › ") || lower.contains(" > ")
        ) {
            return true
        }
        if (urlOrDomainMarkers.any { it in lower }) return true
        return !t.contains(' ') && (t.contains('/') || lower.contains(".com"))
    }

    fun cleanText(rawHtmlText: String): String {
        val noTags = rawHtmlText.replace(htmlTagRegex, " ")
        return HtmlTextDecoder.decode(noTags)
            .replace(urlInTextRegex, " ")
            .replace(wwwInTextRegex, " ")
            .replace(trailingStoreRegex, "")
            .replace(wsRegex, " ")
            .trim()
    }

    fun deriveTitleFromUrl(url: String): String {
        if (url.isBlank()) return ""
        val noQuery = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val segments = noQuery.split('/').filter { it.isNotBlank() && !it.startsWith("http", true) }
        val pIdx = segments.indexOfFirst { it.equals("p", ignoreCase = true) }
        val candidateSlug = when {
            pIdx > 0 -> segments[pIdx - 1]
            else -> segments.firstOrNull { seg ->
                slugMlbPrefixRegex.containsMatchIn(seg)
            } ?: segments.filterNot {
                it.contains("mercadolivre", true) ||
                    it.equals("p", true) ||
                    it.equals("up", true) ||
                    bareMlbIdRegex.matches(it)
            }.maxByOrNull { it.length }.orEmpty()
        }
        if (candidateSlug.isBlank()) return ""
        val decoded = HtmlTextDecoder.decodeUrlSlugToTitle(candidateSlug)
        if (decoded.length < 5 || looksLikeUrlOrDomain(decoded)) return ""
        return decoded
    }

    fun sanitizeCandidateTitle(rawTitle: String, productUrl: String): String {
        val cleaned = cleanText(rawTitle)
        if (cleaned.length >= 6 && !looksLikeUrlOrDomain(cleaned)) {
            return cleaned
        }
        return deriveTitleFromUrl(productUrl)
    }

    fun extractBrlPrice(rawText: String): String {
        val decoded = HtmlTextDecoder.decode(rawText)

        val validCandidates = mutableListOf<Pair<Double, String>>()
        fun evaluateMatch(start: Int, end: Int, formatted: String) {
            if (formatted.isBlank()) return
            val numeric = parseBrlAmount(formatted)
            if (numeric <= 0.0) return

            val before = decoded.substring((start - 56).coerceAtLeast(0), start)
            val after = decoded.substring((end + 1).coerceAtMost(decoded.length), (end + 36).coerceAtMost(decoded.length))
            if (nonPriceBeforeRegex.containsMatchIn(before) || nonPriceAfterRegex.containsMatchIn(after)) {
                return
            }
            if (crossOutBeforeRegex.containsMatchIn(before) && crossOutFollowRegex.containsMatchIn(after)) {
                return
            }
            if (numeric < 15.0) {
                val wideBefore = decoded.substring((start - 85).coerceAtLeast(0), start)
                val wideAfter = decoded.substring((end + 1).coerceAtMost(decoded.length), (end + 45).coerceAtMost(decoded.length))
                if (tinyNoiseContextRegex.containsMatchIn(wideBefore) || tinyNoiseContextRegex.containsMatchIn(wideAfter)) {
                    return
                }
            }
            validCandidates.add(numeric to formatted)
        }

        for (m in brlPriceRegex.findAll(decoded)) {
            evaluateMatch(m.range.first, m.range.last, PriceFormatter.formatBrl(m.groupValues[1]))
        }

        if (validCandidates.isEmpty()) {
            for (m in reaisPriceRegex.findAll(decoded)) {
                val frac = m.groupValues[1].trim()
                val cents = m.groupValues[2].trim()
                val normalizedNum = if (cents.isNotBlank()) {
                    "${frac.replace(".", "")}.${cents.padStart(2, '0')}"
                } else {
                    frac
                }
                evaluateMatch(m.range.first, m.range.last, PriceFormatter.formatBrl(normalizedNum))
            }
        }

        if (validCandidates.isEmpty()) return ""
        val first = validCandidates.first()
        if (first.first < 15.0) {
            val realistic = validCandidates.firstOrNull { it.first >= 15.0 }
            if (realistic != null) return realistic.second
        }
        return first.second
    }

    fun parseBrlAmount(priceText: String): Double {
        val raw = priceText.replace("R$", "", ignoreCase = true)
            .replace(".", "")
            .replace(",", ".")
            .replace(nonDigitsDotRegex, "")
            .trim()
        return raw.toDoubleOrNull() ?: 0.0
    }

    private val mlPicIdPattern = Regex("""^(?:D_(?:N?Q_NP_|Q_NP_)?(?:\dX_)?)?\d{4,}-[A-Z]{2,4}\d*(?:_\d+)?$""", RegexOption.IGNORE_CASE)

    private fun isTrustedProductPhotoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("https://")) return false
        if (lower.contains("frontend-assets") ||
            lower.contains("/badges/") ||
            lower.contains("/banners/") ||
            lower.contains("/org-img/") ||
            lower.contains("meliplus") ||
            lower.contains("full_icon")
        ) {
            return false
        }
        return lower.contains("mlstatic.com/d_") ||
            lower.contains("mlstatic.com/s_") ||
            lower.contains("mm.bing.net/th") ||
            lower.contains("bing.com/th") ||
            lower.contains("yimg.com/") ||
            lower.contains("gstatic.com/images")
    }

    private fun buildMlStaticPicUrl(rawIdOrUrl: String): String {
        val raw = rawIdOrUrl.trim()
        if (raw.isBlank()) return ""
        if (raw.startsWith("http", true) || raw.startsWith("//")) {
            return HtmlTextDecoder.normalizeImageUrl(raw)
        }
        val looksLikeMlPicId = raw.contains("ML", true) ||
            raw.contains("CBT", true) ||
            raw.startsWith("D_", true) ||
            mlPicIdPattern.matches(raw)
        if (!looksLikeMlPicId) return ""
        val prefixAdded = if (raw.startsWith("D_", true)) raw else "D_NQ_NP_$raw"
        val lower = prefixAdded.lowercase()
        val withSuffix = when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".png") -> prefixAdded
            lower.endsWith("-o") || lower.endsWith("-v") -> "$prefixAdded.jpg"
            else -> "$prefixAdded-O.jpg"
        }
        return "https://http2.mlstatic.com/$withSuffix"
    }

    private fun normalizeBingOrRemoteThumbUrl(rawSrc: String): String {
        val decoded = decodeXmlAttrOnce(rawSrc).trim()
        if (decoded.isBlank() || decoded.startsWith("data:image", true)) return ""
        if (decoded.startsWith("/th?", true) || decoded.startsWith("/th/", true)) {
            return HtmlTextDecoder.normalizeImageUrl("https://www.bing.com$decoded")
        }
        return HtmlTextDecoder.normalizeImageUrl(decoded)
    }

    private val imgSrcAttrMultiRegex = Regex(
        """(data-src|data-lazy|data-iurl|data-zoom|data-srcset|srcset|src)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    private fun extractImgTagPhotoUrl(imgTag: String): String {
        val ordered = mutableListOf<Pair<Int, String>>()
        for (m in imgSrcAttrMultiRegex.findAll(imgTag)) {
            val attr = m.groupValues[1].lowercase()
            val rawVal = m.groupValues[2]
            val priority = when {
                attr == "data-src" || attr == "data-lazy" || attr == "data-iurl" || attr == "data-zoom" -> 0
                attr == "data-srcset" || attr == "srcset" -> 1
                else -> 2
            }
            if (attr.endsWith("srcset")) {
                for (entry in rawVal.split(',')) {
                    val candidate = entry.trim().substringBefore(" ").trim()
                    if (candidate.isNotBlank()) ordered.add(priority to candidate)
                }
            } else {
                ordered.add(priority to rawVal)
            }
        }
        ordered.sortBy { it.first }
        for ((_, candidate) in ordered) {
            val normalized = normalizeBingOrRemoteThumbUrl(candidate)
            if (isTrustedProductPhotoUrl(normalized)) return normalized
        }
        return ""
    }

    private val nearbyMlPageUrlRegex = Regex(
        """https?://[^"'\s<>\\]*mercadolivre\.com\.br[^"'\s<>\\]*""",
        RegexOption.IGNORE_CASE
    )
    private val ldJsonScriptRegex = Regex(
        """<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )

    private fun extractNearbyMlPageUrl(rawWindow: String): String {
        val cleanWindow = decodeXmlAttrOnce(rawWindow).replace("\\/", "/")
        val candidate = nearbyMlPageUrlRegex.find(cleanWindow)?.value.orEmpty()
        return normalizeProductUrl(candidate)
    }

    fun mergeCandidate(
        collected: MutableMap<String, RawStoreCandidate>,
        incoming: RawStoreCandidate
    ) {
        val cleanTitle = sanitizeCandidateTitle(incoming.title, incoming.productUrl)
        if (cleanTitle.length < 5 || !isValidProductUrl(incoming.productUrl)) return
        val cleanImage = HtmlTextDecoder.normalizeImageUrl(incoming.imageUrl)
        val cleanPrice = incoming.priceText.trim()
        val normalized = incoming.copy(
            title = cleanTitle,
            imageUrl = cleanImage,
            priceText = cleanPrice,
            affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(incoming.productUrl)
        )
        val incomingTitleKey = TextNormalizer.normalize(cleanTitle)
        val existingKey = if (collected.containsKey(normalized.id)) {
            normalized.id
        } else {
            collected.entries.firstOrNull { (_, ex) ->
                TextNormalizer.normalize(ex.title) == incomingTitleKey
            }?.key
        }
        val existing = if (existingKey != null) collected[existingKey] else null
        if (existingKey == null || existing == null) {
            collected[normalized.id] = normalized
            return
        }
        val bestTitle = when {
            looksLikeUrlOrDomain(existing.title) && !looksLikeUrlOrDomain(normalized.title) -> normalized.title
            normalized.title.contains("Mãe") && !existing.title.contains("Mãe") -> normalized.title
            existing.title.length + 4 < normalized.title.length -> normalized.title
            else -> existing.title
        }
        val bestImage = when {
            existing.imageUrl.isBlank() -> normalized.imageUrl
            !existing.imageUrl.contains("mlstatic.com", true) && normalized.imageUrl.contains("mlstatic.com", true) -> normalized.imageUrl
            else -> existing.imageUrl
        }
        val bestPrice = existing.priceText.ifBlank { normalized.priceText }
        val bestUrl = if (normalized.productUrl.length < existing.productUrl.length && normalized.imageUrl.isNotBlank()) {
            normalized.productUrl
        } else existing.productUrl
        collected[existingKey] = existing.copy(
            title = bestTitle,
            imageUrl = bestImage,
            priceText = bestPrice,
            productUrl = bestUrl,
            affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(bestUrl)
        )
    }

    fun parseListingHtml(
        html: String,
        query: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        if (html.isBlank()) return emptyList()
        val collected = LinkedHashMap<String, RawStoreCandidate>()
        extractFromLdJson(html, query, domainHint, collected)
        extractFromEmbeddedState(html, query, domainHint, collected)
        extractFromCardBlocks(html, query, domainHint, collected)
        return collected.values.toList()
    }

    private fun optPositiveDouble(obj: JSONObject?, key: String): Double? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        val numeric = obj.optDouble(key, Double.NaN)
        if (numeric.isFinite() && numeric > 0.0) return numeric
        val raw = obj.optString(key, "").trim()
        if (raw.isBlank()) return null
        val cleaned = if (raw.contains(',') && raw.contains('.')) {
            raw.replace(".", "").replace(',', '.')
        } else {
            raw.replace(',', '.')
        }.replace(nonDigitsDotRegex, "")
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun extractLdOffersPrice(rawOffers: Any?): String {
        val offerObj = when (rawOffers) {
            is JSONObject -> rawOffers
            is JSONArray -> rawOffers.optJSONObject(0)
            else -> null
        } ?: return ""
        val direct = optPositiveDouble(offerObj, "price")
            ?: optPositiveDouble(offerObj, "lowPrice")
            ?: optPositiveDouble(offerObj, "highPrice")
            ?: optPositiveDouble(offerObj.optJSONObject("priceSpecification"), "price")
        if (direct != null) return PriceFormatter.formatBrl(direct)
        val rawStr = offerObj.optString("price", "")
            .ifBlank { offerObj.optString("lowPrice", "") }
            .ifBlank { offerObj.optString("highPrice", "") }
        return PriceFormatter.formatBrl(rawStr)
    }

    private fun extractFromLdJson(
        html: String,
        query: String,
        domainHint: String,
        out: MutableMap<String, RawStoreCandidate>
    ) {
        for (match in ldJsonScriptRegex.findAll(html)) {
            val rawJson = match.groupValues[1].trim()
            if (rawJson.isBlank()) continue
            runCatching {
                val obj = JSONObject(rawJson)
                val items = obj.optJSONArray("itemListElement")
                    ?: obj.optJSONObject("mainEntity")?.optJSONArray("itemListElement")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val elem = items.optJSONObject(i) ?: continue
                        val item = elem.optJSONObject("item") ?: elem
                        val title = item.optString("name", "").trim()
                        val url = normalizeProductUrl(item.optString("url", item.optString("@id", "")))
                        if (url.isBlank()) continue
                        val image = when (val imgVal = item.opt("image")) {
                            is String -> HtmlTextDecoder.normalizeImageUrl(imgVal)
                            is JSONArray -> HtmlTextDecoder.normalizeImageUrl(imgVal.optString(0, ""))
                            else -> ""
                        }
                        val ldPrice = extractLdOffersPrice(item.opt("offers"))
                        mergeCandidate(
                            out,
                            RawStoreCandidate(
                                id = canonicalCandidateId(url, "ld"),
                                store = StoreType.MERCADO_LIVRE,
                                title = title,
                                imageUrl = image,
                                productUrl = url,
                                affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(url),
                                priceText = ldPrice,
                                categoryPath = domainHint,
                                domainId = domainHint,
                                sourceQueryVariant = query,
                                sourceRank = out.size
                            )
                        )
                    }
                }
            }
        }
    }

    private fun extractFromEmbeddedState(
        html: String,
        query: String,
        domainHint: String,
        out: MutableMap<String, RawStoreCandidate>
    ) {
        val markers = listOf("__PRELOADED_STATE__", "__NORDIC_STATE__", "__NEXT_DATA__")
        for (marker in markers) {
            val idx = html.indexOf(marker)
            if (idx < 0) continue
            val startBrace = html.indexOf('{', idx)
            if (startBrace < 0) continue
            val jsonSlice = extractBalancedJsonObject(html, startBrace, maxChars = 2_400_000) ?: continue
            runCatching {
                val root = JSONObject(jsonSlice)
                // Fast-path directly into ML's known result & promo arrays before full tree DFS
                val priorityNodes = listOfNotNull(
                    root.optJSONObject("pageStoreState")?.optJSONObject("initialState")?.optJSONArray("results"),
                    root.optJSONObject("pageStoreState")?.optJSONObject("initialState")?.optJSONObject("search")?.optJSONArray("results"),
                    root.optJSONObject("pageState")?.optJSONObject("initialState")?.optJSONArray("results"),
                    root.optJSONObject("pageState")?.optJSONObject("initialState")?.optJSONObject("search")?.optJSONArray("results"),
                    root.optJSONObject("initialState")?.optJSONArray("results"),
                    root.optJSONObject("initialState")?.optJSONObject("search")?.optJSONArray("results"),
                    root.optJSONObject("initialState")?.optJSONArray("items"),
                    root.optJSONObject("initialState")?.optJSONArray("sections"),
                    root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONArray("results"),
                    root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONArray("items"),
                    root.optJSONObject("search")?.optJSONArray("results"),
                    root.optJSONArray("results"),
                    root.optJSONArray("items"),
                    root.optJSONArray("promotions")
                )
                for (arr in priorityNodes) {
                    scanStateJson(arr, query, domainHint, out, depth = 0, counter = intArrayOf(0))
                }
                if (out.size < 45) {
                    scanStateJson(root, query, domainHint, out, depth = 0, counter = intArrayOf(0))
                }
            }
        }
    }

    private fun scanStateJson(
        node: Any?,
        query: String,
        domainHint: String,
        out: MutableMap<String, RawStoreCandidate>,
        depth: Int,
        counter: IntArray
    ) {
        if (depth > 18 || out.size >= 60 || counter[0] > 70_000 || node == null) return
        counter[0]++
        when (node) {
            is JSONObject -> {
                val polycard = node.optJSONObject("polycard") ?: node
                val metadata = polycard.optJSONObject("metadata") ?: node.optJSONObject("metadata")
                val explicitIdRaw = metadata?.optString("id", "").orEmpty()
                    .ifBlank { node.optString("id", "") }
                    .ifBlank { polycard.optString("id", "") }
                    .trim()
                val rawUrl = node.optString("permalink", "")
                    .ifBlank { polycard.optString("permalink", "") }
                    .ifBlank { node.optString("url", "") }
                    .ifBlank { metadata?.optString("url", "").orEmpty() }
                val synthesizedUrl = if (rawUrl.isBlank() && bareMlbIdRegex.matches(explicitIdRaw)) {
                    val up = explicitIdRaw.uppercase()
                    if (up.startsWith("MLBU")) "https://www.mercadolivre.com.br/up/$up"
                    else "https://www.mercadolivre.com.br/p/$up"
                } else rawUrl
                val fullUrl = normalizeProductUrl(synthesizedUrl)

                val componentsRaw = polycard.opt("components") ?: node.opt("components")
                var compTitle = ""
                var compPrice = 0.0
                var compImg = ""
                when (componentsRaw) {
                    is JSONObject -> {
                        compTitle = when (val tComp = componentsRaw.opt("title")) {
                            is JSONObject -> tComp.optString("text", "")
                            is JSONArray -> tComp.optJSONObject(0)?.optString("text", "").orEmpty()
                            is String -> tComp
                            else -> ""
                        }
                        val pObj = componentsRaw.optJSONObject("price")
                        val cur = pObj?.optJSONObject("current_price")
                        compPrice = optPositiveDouble(cur, "value")
                            ?: optPositiveDouble(cur, "amount")
                            ?: optPositiveDouble(pObj, "value")
                            ?: optPositiveDouble(pObj, "amount")
                            ?: 0.0
                        val picObj = componentsRaw.optJSONObject("picture") ?: componentsRaw.optJSONObject("pictures")
                        val firstPic = picObj?.optJSONArray("pictures")?.optJSONObject(0)
                            ?: picObj?.optJSONArray("items")?.optJSONObject(0)
                        val picId = firstPic?.optString("id", "").orEmpty()
                            .ifBlank { picObj?.optString("id", "").orEmpty() }
                            .trim()
                        compImg = picObj?.optString("secure_url", "").orEmpty()
                            .ifBlank { picObj?.optString("url", "").orEmpty() }
                            .ifBlank { firstPic?.optString("secure_url", "").orEmpty() }
                            .ifBlank { firstPic?.optString("url", "").orEmpty() }
                            .ifBlank { buildMlStaticPicUrl(picId) }
                    }
                    is JSONArray -> {
                        for (ci in 0 until componentsRaw.length()) {
                            val cObj = componentsRaw.optJSONObject(ci) ?: continue
                            val cType = cObj.optString("type", "").lowercase()
                            if (compTitle.isBlank() && (cType == "title" || cObj.has("title"))) {
                                compTitle = cObj.optJSONObject("title")?.optString("text", "").orEmpty()
                                    .ifBlank { cObj.optString("text", "") }
                            }
                            if (compPrice <= 0.0 && (cType == "price" || cObj.has("price"))) {
                                val pInner = cObj.optJSONObject("price") ?: cObj
                                val cur = pInner.optJSONObject("current_price") ?: pInner
                                compPrice = optPositiveDouble(cur, "value")
                                    ?: optPositiveDouble(cur, "amount")
                                    ?: optPositiveDouble(pInner, "price")
                                    ?: 0.0
                            }
                            if (compImg.isBlank() && (cType == "picture" || cObj.has("picture") || cObj.has("pictures"))) {
                                val picNode = cObj.optJSONObject("picture") ?: cObj.optJSONObject("pictures")
                                val nestedPic = picNode?.optJSONArray("pictures")?.optJSONObject(0)
                                    ?: picNode?.optJSONArray("items")?.optJSONObject(0)
                                    ?: cObj.optJSONArray("pictures")?.optJSONObject(0)
                                val picId = nestedPic?.optString("id", "").orEmpty()
                                    .ifBlank { picNode?.optString("id", "").orEmpty() }
                                    .ifBlank { cObj.optString("id", "").takeIf { cType == "picture" }.orEmpty() }
                                    .trim()
                                compImg = cObj.optString("secure_url", "")
                                    .ifBlank { cObj.optString("url", "") }
                                    .ifBlank { picNode?.optString("secure_url", "").orEmpty() }
                                    .ifBlank { picNode?.optString("url", "").orEmpty() }
                                    .ifBlank { nestedPic?.optString("secure_url", "").orEmpty() }
                                    .ifBlank { nestedPic?.optString("url", "").orEmpty() }
                                    .ifBlank { buildMlStaticPicUrl(picId) }
                            }
                        }
                    }
                }

                val titleObjText = (polycard.opt("title") as? JSONObject)?.optString("text", "").orEmpty()
                val rawTitle = (polycard.opt("title") as? String)?.ifBlank { null }
                    ?: (node.opt("title") as? String)?.ifBlank { null }
                    ?: titleObjText.ifBlank { null }
                    ?: compTitle.ifBlank { null }
                    ?: polycard.optString("name", "").ifBlank { null }
                    ?: node.optString("name", "")

                if (fullUrl.isNotBlank() && isValidProductUrl(fullUrl) && rawTitle.isNotBlank()) {
                    val picsRaw = polycard.opt("pictures") ?: node.opt("pictures")
                    val picArr = when (picsRaw) {
                        is JSONArray -> picsRaw
                        is JSONObject -> picsRaw.optJSONArray("pictures") ?: picsRaw.optJSONArray("items")
                        else -> null
                    }
                    val firstPicObj = picArr?.optJSONObject(0)
                    val firstPicId = firstPicObj?.optString("id", "")?.trim().orEmpty()
                    val firstPicUrl = firstPicObj?.optString("secure_url", "")?.ifBlank {
                        firstPicObj.optString("url", "")
                    }?.ifBlank { buildMlStaticPicUrl(firstPicId) }.orEmpty()
                    val singlePicObj = polycard.optJSONObject("picture") ?: node.optJSONObject("picture")
                    val singlePicId = singlePicObj?.optString("id", "")?.trim().orEmpty()
                    val singlePicUrl = singlePicObj?.optString("secure_url", "")
                        ?.ifBlank { singlePicObj.optString("url", "") }
                        ?.ifBlank { buildMlStaticPicUrl(singlePicId) }
                        .orEmpty()
                    val thumbId = polycard.optString("thumbnail_id", "")
                        .ifBlank { node.optString("thumbnail_id", "") }.trim()
                    val rawImg = polycard.optString("thumbnail", "")
                        .ifBlank { node.optString("thumbnail", "") }
                        .ifBlank { node.optString("secure_thumbnail", "") }
                        .ifBlank { (node.opt("picture") as? String).orEmpty() }
                        .ifBlank { node.optString("image", "") }
                        .ifBlank { compImg }
                        .ifBlank { firstPicUrl }
                        .ifBlank { singlePicUrl }
                        .ifBlank { buildMlStaticPicUrl(thumbId) }
                    val imageUrl = HtmlTextDecoder.normalizeImageUrl(rawImg)

                    val priceObj = polycard.optJSONObject("price")
                        ?: node.optJSONObject("price")
                        ?: node.optJSONObject("price_info")
                    val priceVal = optPositiveDouble(priceObj?.optJSONObject("current_price"), "value")
                        ?: optPositiveDouble(priceObj?.optJSONObject("current_price"), "amount")
                        ?: optPositiveDouble(priceObj, "amount")
                        ?: optPositiveDouble(priceObj, "value")
                        ?: compPrice.takeIf { it > 0.0 }
                        ?: optPositiveDouble(node, "sale_price")
                        ?: optPositiveDouble(node, "discount_price")
                        ?: optPositiveDouble(node, "price")
                        ?: optPositiveDouble(metadata, "price")
                        ?: 0.0
                    val priceText = PriceFormatter.formatBrl(priceVal)
                    val explicitId = explicitIdRaw
                    val candId = if (explicitId.startsWith("MLB", true)) {
                        "ml_${explicitId.uppercase()}"
                    } else canonicalCandidateId(fullUrl)

                    mergeCandidate(
                        out,
                        RawStoreCandidate(
                            id = candId,
                            store = StoreType.MERCADO_LIVRE,
                            title = rawTitle,
                            imageUrl = imageUrl,
                            productUrl = fullUrl,
                            affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(fullUrl),
                            priceText = priceText,
                            categoryPath = domainHint,
                            domainId = domainHint,
                            sourceQueryVariant = query,
                            sourceRank = out.size
                        )
                    )
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    scanStateJson(node.opt(keys.next()), query, domainHint, out, depth + 1, counter)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    scanStateJson(node.opt(i), query, domainHint, out, depth + 1, counter)
                }
            }
        }
    }

    private val htmlChromeStripRegex = Regex(
        """<(?:header|nav|footer|script|style)[^>]*>[\s\S]*?</(?:header|nav|footer|script|style)>""",
        RegexOption.IGNORE_CASE
    )
    private val cardSplitRegex = Regex(
        """(?=<li\b[^>]*class=["'][^"']*\b(?:ui-search-layout__item|promotion-item|ui-recommendations-card|dynamic-carousel__item)\b|<div\b[^>]*class=["'][^"']*\b(?:poly-card|andes-card|ui-search-result__wrapper|promotion-item__container|promotion-item|ui-recommendations-card)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val anchorAttrsRegex = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val hrefAttrRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val classAttrRegex = Regex("""class\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val cardImgAttrRegex = Regex("""(?:data-src|data-lazy|data-srcset|srcset|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val cardCurrentPriceRegex = Regex(
        """(?:poly-price__current|ui-search-price__second-line|promotion-item__price)[\s\S]{0,900}?andes-money-amount__fraction[^>]*>([^<]+)<(?:(?:(?!(?:andes-money-amount__fraction|installment|poly-price__installments|ui-search-price__subtitles))[\s\S]){0,140}?andes-money-amount__cents[^>]*>([^<]+)<)?""",
        RegexOption.IGNORE_CASE
    )
    private val cardFallbackMoneyRegex = Regex(
        """<span[^>]*class=["'][^"']*andes-money-amount(?![^"']*--previous)[^"']*["'][^>]*>[\s\S]{0,450}?andes-money-amount__fraction[^>]*>([^<]+)<(?:(?:(?!(?:andes-money-amount__fraction|installment|poly-price__installments|ui-search-price__subtitles))[\s\S]){0,140}?andes-money-amount__cents[^>]*>([^<]+)<)?""",
        RegexOption.IGNORE_CASE
    )
    private val cardAriaPriceRegex = Regex(
        """(?:poly-price__current|ui-search-price__second-line|promotion-item__price)[\s\S]{0,600}?aria-label=["']([^"']*reais[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val mlStaticCardDirectRegex = Regex(
        """(?:https?:)?(?:\\?/)?(?:\\?/)(?:http2|img|ml[ab]-s[12]-p)\.mlstatic\.com/D_[^"'\s<>\\]+\.(?:jpg|jpeg|webp|png)""",
        RegexOption.IGNORE_CASE
    )

    private val priorityCardImgTagRegex = Regex(
        """<(?:img|source)\b[^>]*(?:poly-component__picture|ui-search-result-image__element|promotion-item__img)[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val anyCardPictureSourceRegex = Regex(
        """<picture[^>]*>([\s\S]{0,1200}?)</picture>""",
        RegexOption.IGNORE_CASE
    )

    internal fun extractFromCardBlocks(
        html: String,
        query: String,
        domainHint: String,
        out: MutableMap<String, RawStoreCandidate>
    ) {
        val cleanBodyHtml = html.replace(htmlChromeStripRegex, " ")

        val blocksToScan = cleanBodyHtml.split(cardSplitRegex).drop(1)
        if (blocksToScan.isEmpty()) return

        for (rawCard in blocksToScan) {
            if (out.size >= 60) break
            val cardHtml = rawCard.take(14_000)

            val candidateLinks = anchorAttrsRegex.findAll(cardHtml).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val rawHref = hrefAttrRegex.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                val resolvedUrl = normalizeProductUrl(rawHref)
                if (resolvedUrl.isBlank() || !isValidProductUrl(resolvedUrl)) return@mapNotNull null
                val cls = classAttrRegex.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                val text = cleanText(m.groupValues[2])
                val isTitleClass = cls.contains("poly-component__title", true) ||
                    cls.contains("ui-search-link", true) ||
                    cls.contains("promotion-item__link-container", true) ||
                    cls.contains("promotion-item__title", true) ||
                    cls.contains("ui-recommendations-card__title", true) ||
                    cls.contains("andes-card__title", true)
                Triple(resolvedUrl, text, isTitleClass)
            }.toList()

            if (candidateLinks.isEmpty()) continue
            val chosen = candidateLinks.firstOrNull { it.third && it.second.length >= 6 }
                ?: candidateLinks.maxByOrNull { it.second.length }
                ?: continue
            val url = chosen.first
            val title = sanitizeCandidateTitle(chosen.second, url)
            if (title.length < 6) continue

            val priorityPictureImg = anyCardPictureSourceRegex.find(cardHtml)?.groupValues?.getOrNull(1)
                ?.let { picBlock ->
                    cardImgAttrRegex.findAll(picBlock)
                        .flatMap { m ->
                            m.groupValues[1].split(',').asSequence().mapNotNull { entry ->
                                val src = entry.trim().substringBefore(" ").trim()
                                HtmlTextDecoder.normalizeImageUrl(src).takeIf {
                                    it.contains("mlstatic.com", true) && isTrustedProductPhotoUrl(it)
                                }
                            }
                        }
                        .firstOrNull()
                }
                .orEmpty()

            val priorityClassImg = if (priorityPictureImg.isBlank()) {
                priorityCardImgTagRegex.findAll(cardHtml)
                    .mapNotNull { m ->
                        extractImgTagPhotoUrl(m.value).takeIf { it.contains("mlstatic.com", true) }
                    }
                    .firstOrNull()
                    .orEmpty()
            } else ""

            val imgFromAttr = if (priorityPictureImg.isBlank() && priorityClassImg.isBlank()) {
                cardImgAttrRegex.findAll(cardHtml)
                    .flatMap { m ->
                        m.groupValues[1].split(',').asSequence().mapNotNull { entry ->
                            val candidateSrc = entry.trim().substringBefore(" ").trim()
                            HtmlTextDecoder.normalizeImageUrl(candidateSrc).takeIf {
                                it.isNotBlank() && it.contains("mlstatic.com", true) && isTrustedProductPhotoUrl(it)
                            }
                        }
                    }
                    .firstOrNull()
                    .orEmpty()
            } else ""

            val mlStaticDirect = if (priorityPictureImg.isBlank() && priorityClassImg.isBlank() && imgFromAttr.isBlank()) {
                mlStaticCardDirectRegex
                    .findAll(cardHtml)
                    .map { HtmlTextDecoder.normalizeImageUrl(it.value) }
                    .firstOrNull { it.isNotBlank() && isTrustedProductPhotoUrl(it) }
                    .orEmpty()
            } else ""

            val imageUrl = priorityPictureImg
                .ifBlank { priorityClassImg }
                .ifBlank { imgFromAttr }
                .ifBlank { mlStaticDirect }

            val ariaPrice = cardAriaPriceRegex.find(cardHtml)?.groupValues?.getOrNull(1)
                ?.let { extractBrlPrice(it) }.orEmpty()
            val priceMatch = cardCurrentPriceRegex.find(cardHtml) ?: cardFallbackMoneyRegex.find(cardHtml)
            val frac = priceMatch?.groupValues?.getOrNull(1).orEmpty().replace(".", "").trim()
            val cents = priceMatch?.groupValues?.getOrNull(2).orEmpty().trim()
            val priceText = ariaPrice.ifBlank {
                if (frac.isNotBlank()) {
                    PriceFormatter.formatBrl(if (cents.isNotBlank()) "$frac.$cents" else frac)
                } else {
                    extractBrlPrice(cardHtml.replace(htmlTagRegex, " "))
                }
            }

            mergeCandidate(
                out,
                RawStoreCandidate(
                    id = canonicalCandidateId(url),
                    store = StoreType.MERCADO_LIVRE,
                    title = title,
                    imageUrl = imageUrl,
                    productUrl = url,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(url),
                    priceText = priceText,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = query,
                    sourceRank = out.size
                )
            )
        }
    }

    private val pdpTitleH1Regex = Regex("""<h1[^>]*class=["'][^"']*ui-pdp-title[^"']*["'][^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
    private val htmlTitleTagRegex = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val preloadLinkTagRegex = Regex("""<link[^>]+rel=["']preload["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val hrefQuoteRegex = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val pdpUiImgTagRegex = Regex("""<img[^>]*class=["'][^"']*ui-pdp-image[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val statePicturesKeyRegex = Regex(""""pictures"\s*:\s*\[""")
    private val statePicturesIdRegex = Regex(""""pictures"\s*:\s*\[\s*\{[^}]*"id"\s*:\s*"([^"]+)"""")
    private val stateThumbnailIdRegex = Regex(""""thumbnail_id"\s*:\s*"([^"]+)"""")
    private val metaItemPropImgForwardRegex = Regex("""<meta[^>]+itemprop=["']image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val metaItemPropImgReverseRegex = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+itemprop=["']image["']""", RegexOption.IGNORE_CASE)
    private val mlStaticPdpDirectRegex = Regex(
        """(?:https?:)?(?:\\?/)?(?:\\?/)(?:http2|img|ml[ab]-s[12]-p)\.mlstatic\.com/D_(?:NQ_NP_|Q_NP_)?[^"'\s\\<>]+?\.(?:jpg|jpeg|webp|png)""",
        RegexOption.IGNORE_CASE
    )
    private val metaItemPropPriceForwardRegex = Regex("""<meta[^>]+itemprop=["']price["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val metaItemPropPriceReverseRegex = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*itemprop=["']price["']""", RegexOption.IGNORE_CASE)
    private val jsonStateNestedPriceRegex = Regex(
        """"(?:current_price|price_to_show|buy_box_winner|price)"\s*:\s*\{(?:[^{}]|\{[^{}]*\}){0,260}?"(?:value|amount|price)"\s*:\s*"?(\d+(?:\.\d{1,2})?)"?"""
    )
    private val jsonStateObjPriceRegex = Regex(""""(?:current_price|sale_price|price)"\s*:\s*\{[^}]{0,180}?"(?:value|amount)"\s*:\s*"?(\d+(?:\.\d{1,2})?)"?""")
    private val jsonStateAmountPriceRegex = Regex(""""(?:price_amount|lowPrice)"\s*:\s*"?(\d+(?:\.\d{1,2})?)"?""")
    private val jsonStateSimplePriceRegex = Regex(""""price"\s*:\s*"?(\d+(?:\.\d{1,2})?)"?""")
    private val pdpAriaPriceRegex = Regex("""ui-pdp-price__second-line[\s\S]{0,600}?aria-label=["']([^"']*reais[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val pdpMainPriceBlockRegex = Regex(
        """ui-pdp-price__second-line[\s\S]{0,600}?andes-money-amount__fraction[^>]*>([^<]+)<(?:(?:(?!(?:andes-money-amount__fraction|installment|ui-pdp-price__subtitles))[\s\S]){0,140}?andes-money-amount__cents[^>]*>([^<]+)<)?""",
        RegexOption.IGNORE_CASE
    )
    private val pdpAnyNonPrevRegex = Regex(
        """<(?:span|div)[^>]*class=["'][^"']*andes-money-amount(?![^"']*--previous)[^"']*["'][^>]*>[\s\S]{0,220}?andes-money-amount__fraction[^>]*>([^<]+)<(?:(?:(?!(?:andes-money-amount__fraction|installment|ui-pdp-price__subtitles))[\s\S]){0,140}?andes-money-amount__cents[^>]*>([^<]+)<)?""",
        RegexOption.IGNORE_CASE
    )
    private val pdpInstallmentContextRegex = Regex(
        """(?:ui-pdp-price__subtitles|ui-pdp-price__installments|installment|parcel|\b\d{1,2}\s*x\b|andes-money-amount--previous|<s\b|<del\b)""",
        RegexOption.IGNORE_CASE
    )
    private val pdpRelatedBoundaryMarkers = listOf(
        "id=\"recommendations\"",
        "class=\"ui-recommendations",
        "ui-pdp-recommendations",
        "Quem viu também comprou",
        "Quem comprou, também comprou",
        "Produtos relacionados",
        "Mais anúncios do vendedor"
    )

    private fun extractPdpMainSectionHtml(html: String): String {
        var earliestCut = html.length
        for (marker in pdpRelatedBoundaryMarkers) {
            val idx = html.indexOf(marker, startIndex = 0, ignoreCase = true)
            if (idx in 120 until earliestCut) {
                earliestCut = idx
            }
        }
        return if (earliestCut < html.length) html.substring(0, earliestCut) else html
    }

    private fun extractPdpStateGalleryImage(html: String): String {
        for (m in statePicturesKeyRegex.findAll(html)) {
            val bracketIdx = html.indexOf('[', m.range.first)
            if (bracketIdx < 0) continue
            val slice = extractBalancedJsonBracket(html, bracketIdx, '[', ']', 24_000) ?: continue
            val arr = runCatching { JSONArray(slice) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val picObj = arr.optJSONObject(i) ?: continue
                val rawUrl = picObj.optString("secure_url", "")
                    .ifBlank { picObj.optString("url", "") }
                    .ifBlank { buildMlStaticPicUrl(picObj.optString("id", "")) }
                val clean = HtmlTextDecoder.normalizeImageUrl(rawUrl)
                if (clean.contains("mlstatic.com", true) && isTrustedProductPhotoUrl(clean)) {
                    return clean
                }
            }
        }
        val statePicId = statePicturesIdRegex
            .find(html)?.groupValues?.getOrNull(1)
            ?.ifBlank { null }
            ?: stateThumbnailIdRegex.find(html)?.groupValues?.getOrNull(1)
        val built = statePicId?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
            buildMlStaticPicUrl(id).ifBlank { "https://http2.mlstatic.com/D_NQ_NP_${id}-O.jpg" }
        }.orEmpty()
        return HtmlTextDecoder.normalizeImageUrl(built)
    }

    fun parseSingleProductPageHtml(
        html: String,
        productUrl: String
    ): Triple<String, String, String> {
        if (html.isBlank() || html.contains("account-verification", true)) {
            return Triple("", "", "")
        }
        val mainSectionHtml = extractPdpMainSectionHtml(html)

        // 1) Title
        val ogTitle = cleanText(extractMetaContent(html, "og:title"))
            .ifBlank {
                cleanText(pdpTitleH1Regex.find(mainSectionHtml)?.groupValues?.getOrNull(1).orEmpty())
            }
            .ifBlank {
                cleanText(htmlTitleTagRegex.find(html)?.groupValues?.getOrNull(1).orEmpty())
            }
        val safeTitle = if (ogTitle.length >= 6 && !looksLikeUrlOrDomain(ogTitle) && !ogTitle.equals("Mercado Livre", true)) {
            ogTitle
        } else ""

        // 2) Structured LD+JSON for product image & offers price (supports object, array, or @graph)
        var ldImage = ""
        var ldPrice = ""
        fun inspectLdObject(obj: JSONObject) {
            if (ldImage.isBlank()) {
                ldImage = when (val imgVal = obj.opt("image")) {
                    is String -> HtmlTextDecoder.normalizeImageUrl(imgVal)
                    is JSONArray -> HtmlTextDecoder.normalizeImageUrl(imgVal.optString(0, ""))
                    is JSONObject -> HtmlTextDecoder.normalizeImageUrl(imgVal.optString("url", ""))
                    else -> ""
                }
            }
            val offersRaw = obj.opt("offers")
            if (offersRaw != null && ldPrice.isBlank()) {
                ldPrice = extractLdOffersPrice(offersRaw)
            }
            val graph = obj.optJSONArray("@graph")
            if (graph != null) {
                for (gi in 0 until graph.length()) {
                    graph.optJSONObject(gi)?.let { inspectLdObject(it) }
                }
            }
        }
        for (match in ldJsonScriptRegex.findAll(html)) {
            val rawJson = match.groupValues[1].trim()
            if (rawJson.isBlank()) continue
            runCatching {
                if (rawJson.startsWith("[")) {
                    val arr = JSONArray(rawJson)
                    for (ai in 0 until arr.length()) {
                        arr.optJSONObject(ai)?.let { inspectLdObject(it) }
                    }
                } else {
                    inspectLdObject(JSONObject(rawJson))
                }
            }
        }

        // 3) Image (meta/LD+JSON/preload/DOM ui-pdp-image/embedded gallery pictures/bounded main-section mlstatic)
        val preloadTag = preloadLinkTagRegex
            .findAll(html)
            .map { it.value }
            .firstOrNull { it.contains("image", true) }
            .orEmpty()
        val preloadImg = hrefQuoteRegex.find(preloadTag)?.groupValues?.getOrNull(1).orEmpty()
        val pdpDomImg = pdpUiImgTagRegex
            .findAll(mainSectionHtml)
            .mapNotNull { m ->
                extractImgTagPhotoUrl(m.value).takeIf {
                    it.contains("mlstatic.com", true) && isTrustedProductPhotoUrl(it)
                }
            }
            .firstOrNull()
            .orEmpty()
        val stateImgUrl = extractPdpStateGalleryImage(html)

        val itemPropImg = metaItemPropImgForwardRegex.find(html)?.groupValues?.getOrNull(1)
            ?: metaItemPropImgReverseRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val ogImg = HtmlTextDecoder.normalizeImageUrl(extractMetaContent(html, "og:image"))
            .ifBlank { HtmlTextDecoder.normalizeImageUrl(extractMetaContent(html, "twitter:image")) }
            .ifBlank { HtmlTextDecoder.normalizeImageUrl(itemPropImg) }
            .ifBlank { ldImage }
            .ifBlank { HtmlTextDecoder.normalizeImageUrl(preloadImg) }
            .ifBlank { pdpDomImg }
            .ifBlank { stateImgUrl }
            .ifBlank {
                mlStaticPdpDirectRegex
                    .findAll(mainSectionHtml)
                    .map { HtmlTextDecoder.normalizeImageUrl(it.value) }
                    .firstOrNull { it.isNotBlank() && isTrustedProductPhotoUrl(it) && !it.contains("logo", true) }
                    .orEmpty()
            }
        val safeImg = if (ogImg.contains("logo", true)) "" else ogImg

        // 4) Price (prioritize explicit product meta/LD+JSON/pdp-second-line/nested JSON state over previous/installment/related)
        val itemPropPrice = metaItemPropPriceForwardRegex.find(html)?.groupValues?.getOrNull(1)
            ?: metaItemPropPriceReverseRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val metaPrice = PriceFormatter.formatBrl(
            extractMetaContent(html, "product:price:amount")
                .ifBlank { extractMetaContent(html, "og:price:amount") }
                .ifBlank { extractMetaContent(html, "twitter:data1") }
                .ifBlank { itemPropPrice }
        )
        val jsonStatePrice = run {
            val m = jsonStateObjPriceRegex.find(html)?.groupValues?.getOrNull(1)
                ?: jsonStateNestedPriceRegex.findAll(html).mapNotNull { match ->
                    val rawNum = match.groupValues[1]
                    val v = rawNum.toDoubleOrNull()
                    if (v != null && v >= 5.0 && v < 500_000.0) rawNum else null
                }.firstOrNull()
                ?: jsonStateAmountPriceRegex.find(html)?.groupValues?.getOrNull(1)
                ?: jsonStateSimplePriceRegex
                    .findAll(html)
                    .mapNotNull { match ->
                        val rawNum = match.groupValues[1]
                        val v = rawNum.toDoubleOrNull()
                        if (v != null && v >= 5.0 && v < 500_000.0) rawNum else null
                    }
                    .firstOrNull()
            PriceFormatter.formatBrl(m)
        }
        val mainWithoutChrome = mainSectionHtml.replace(htmlChromeStripRegex, " ")
        val pdpAriaPrice = pdpAriaPriceRegex.find(mainWithoutChrome)?.groupValues?.getOrNull(1)
            ?.let { extractBrlPrice(it) }
            .orEmpty()
        val mainPriceBlockMatch = pdpMainPriceBlockRegex.find(mainWithoutChrome)
        val anyNonPrevMatch = if (mainPriceBlockMatch == null) {
            pdpAnyNonPrevRegex.findAll(mainWithoutChrome).firstOrNull { match ->
                val beforeCtx = mainWithoutChrome.substring((match.range.first - 140).coerceAtLeast(0), match.range.first)
                !pdpInstallmentContextRegex.containsMatchIn(beforeCtx)
            }
        } else null
        val domMatch = mainPriceBlockMatch ?: anyNonPrevMatch
        val domPrice = pdpAriaPrice.ifBlank {
            if (domMatch != null) {
                val f = domMatch.groupValues[1].replace(".", "").trim()
                val c = domMatch.groupValues[2].trim()
                PriceFormatter.formatBrl(if (c.isNotBlank()) "$f.$c" else f)
            } else ""
        }

        val strippedVisibleText = mainWithoutChrome.replace(htmlTagRegex, " ")

        val safePrice = metaPrice
            .ifBlank { ldPrice }
            .ifBlank { domPrice }
            .ifBlank { jsonStatePrice }
            .ifBlank { extractBrlPrice(strippedVisibleText) }

        return Triple(safeTitle, safeImg, safePrice)
    }

    fun extractMetaContent(html: String, propertyOrName: String): String {
        val escaped = Regex.escape(propertyOrName)
        val forward = Regex(
            """<meta[^>]+(?:property|name)=["']$escaped["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!forward.isNullOrBlank()) return HtmlTextDecoder.decode(forward.trim())
        val reverse = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$escaped["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        return HtmlTextDecoder.decode(reverse.orEmpty().trim())
    }

    private val bingChunkSplitRegex = Regex("""(?=<li\b[^>]*class=["'][^"']*\bb_algo\b)""", RegexOption.IGNORE_CASE)
    private val linkTagRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val bingH2LinkRegex = Regex("""<h2[^>]*>[\s\S]*?<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>|<a\b[^>]*href=["']([^"']+)["'][^>]*>\s*<h2[^>]*>([\s\S]*?)</h2>\s*</a>""", RegexOption.IGNORE_CASE)
    private val anyAnchorHrefRegex = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val citeTagRegex = Regex("""<cite[^>]*>([\s\S]*?)</cite>""", RegexOption.IGNORE_CASE)
    private val bingImgSrcRegex = Regex("""(?:data-src|data-lazy|src)=["']((?:https?:)?//[^"']+|/th(?:\?|/)[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val citeBreadcrumbRegex = Regex("""\s*[›>]\s*""")
    private val multiSlashRegex = Regex("""/+""")
    private val pTagRegex = Regex("""<p\b[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    private val bingSnippetDivRegex = Regex("""<div\b[^>]*class=["'][^"']*\b(?:b_factrow|b_snippet|b_vlist2col|b_rich)\b[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
    private val mlStaticStandardRegex = Regex("""https?://(?:http2|img|ml[ab]-s[12]-p)\.mlstatic\.com/D_[^"'\s<>]+\.(?:jpg|webp|jpeg)""", RegexOption.IGNORE_CASE)

    fun parseBingWebHtml(
        html: String,
        queryLabel: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        if (html.isBlank()) return emptyList()
        val out = LinkedHashMap<String, RawStoreCandidate>()

        val blocks = html.split(bingChunkSplitRegex).drop(1)
        for (rawBlock in blocks) {
            if (out.size >= 35) break
            val block = rawBlock.replace(linkTagRegex, "").take(8500)
            val h2Match = bingH2LinkRegex.find(block)
            val h2Href = h2Match?.let { it.groupValues[1].ifBlank { it.groupValues[3] } }.orEmpty()
            val h2Inner = h2Match?.let { it.groupValues[2].ifBlank { it.groupValues[4] } }.orEmpty()
            val fallbackAnchor = if (h2Href.isBlank()) {
                anyAnchorHrefRegex.findAll(block).firstOrNull { m ->
                    val href = unwrapBingRedirectUrl(HtmlTextDecoder.decode(m.groupValues[1].trim()))
                    val inner = cleanText(m.groupValues[2])
                    isValidProductUrl(href) && inner.length >= 6 && !looksLikeUrlOrDomain(inner)
                }
            } else null

            val rawHref = if (h2Href.isNotBlank()) {
                HtmlTextDecoder.decode(h2Href.trim())
            } else if (fallbackAnchor != null) {
                HtmlTextDecoder.decode(fallbackAnchor.groupValues[1].trim())
            } else continue
            val rawAnchorInner = if (h2Inner.isNotBlank()) h2Inner else fallbackAnchor?.groupValues?.getOrNull(2).orEmpty()
            val rawCiteHtml = citeTagRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val rawCite = HtmlTextDecoder.decode(rawCiteHtml.replace(htmlTagRegex, " "))
                .replace(wsRegex, " ")
                .trim()
            val citeUrlCandidate = rawCite
                .replace(citeBreadcrumbRegex, "/")
                .replace(wsRegex, "")
                .replace(multiSlashRegex, "/")
                .replaceFirst("https:/", "https://")
                .replaceFirst("http:/", "http://")
            val resolvedUrl = normalizeProductUrl(unwrapBingRedirectUrl(rawHref))
                .takeIf { it.contains("mercadolivre.com.br", true) }
                ?: citeUrlCandidate.takeIf { it.contains("mercadolivre.com.br", true) }?.let { normalizeProductUrl(it) }
                ?: continue

            if (!isValidProductUrl(resolvedUrl)) continue
            val title = sanitizeCandidateTitle(rawAnchorInner, resolvedUrl)
            if (title.length < 6) continue

            // Extract snippet paragraphs/factrows/rich blocks cleanly (avoid nested-div truncation inside b_caption)
            val blockWithoutCite = block.replace(citeTagRegex, " ")
            val snippetFragments = buildString {
                pTagRegex.findAll(blockWithoutCite).forEach {
                    append(' ').append(it.groupValues[1])
                }
                bingSnippetDivRegex.findAll(blockWithoutCite).forEach {
                    append(' ').append(it.groupValues[1])
                }
            }
            val captionPrice = if (snippetFragments.isNotBlank()) {
                extractBrlPrice("$title ${cleanText(snippetFragments)}")
            } else ""
            val priceText = captionPrice.ifBlank {
                extractBrlPrice("$title ${cleanText(blockWithoutCite)}")
            }
            val mlImg = mlStaticStandardRegex
                .find(block)?.value?.let { HtmlTextDecoder.normalizeImageUrl(it) }
                ?: extractCompatibleSnippetImg(block, title)

            mergeCandidate(
                out,
                RawStoreCandidate(
                    id = canonicalCandidateId(resolvedUrl, "bg"),
                    store = StoreType.MERCADO_LIVRE,
                    title = title,
                    imageUrl = mlImg,
                    productUrl = resolvedUrl,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(resolvedUrl),
                    priceText = priceText,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = queryLabel,
                    sourceRank = out.size
                )
            )
        }
        return out.values.toList()
    }

    private fun decodeXmlAttrOnce(raw: String): String {
        return raw
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private val murlFieldRegex = Regex(""""murl"\s*:\s*"([^"]+)"""")
    private val turlFieldRegex = Regex(""""turl"\s*:\s*"([^"]+)"""")
    private val purlFieldRegex = Regex(""""purl"\s*:\s*"([^"]+)"""")
    private val titleFieldRegex = Regex(""""t"\s*:\s*"([^"]+)"""")
    private val bingMetaMRegex = Regex("""\bm=(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val bingMediaUrlRegex = Regex("""mediaurl=(https?%3A%2F%2F[^&"'\s]+)""", RegexOption.IGNORE_CASE)
    private val rurlPurlRegex = Regex("""(?:rurl|purl)=([^&"'\s\\]+)""", RegexOption.IGNORE_CASE)
    private val attrTitleRegex = Regex("""(?:alt|aria-label|title)=["']([^"']{4,140})["']""", RegexOption.IGNORE_CASE)
    private val altAttrAllRegex = Regex("""(?:alt|aria-label|title)=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val imgTagRegex = Regex("""<img\b[^>]+>""", RegexOption.IGNORE_CASE)
    private val containerCaptionRegex = Regex("""<a\b[^>]*class=["'][^"']*inflnk[^"']*["'][^>]*aria-label=["']([^"']+)["']|<div\b[^>]*class=["'][^"']*infnmpt[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
    private val stripResultPrefixRegex = Regex("""^(?:resultado\s+de\s+imagem\s+(?:para|de)|image\s+result\s+for)\s*[:-]?\s*""", RegexOption.IGNORE_CASE)
    private val titParamRegex = Regex("""(?:tit|tt)=([^&"'\s\\]+)""", RegexOption.IGNORE_CASE)
    private val imgUrlParamRegex = Regex("""(?:imgurl|murl)=([^&"'\s\\]+)""", RegexOption.IGNORE_CASE)
    private val ariaLabelBoundedRegex = Regex("""aria-label=["']([^"']{4,140})["']""", RegexOption.IGNORE_CASE)
    private val gstaticThumbRegex = Regex("""https://encrypted-tbn\d+\.gstatic\.com/images\?q=tbn:[^"'\s<>\\]+""", RegexOption.IGNORE_CASE)
    private val bingShopSplitRegex = Regex("""(?=<li\b[^>]*class=["'][^"']*br-item|<div\b[^>]*class=["'][^"']*br-card)""", RegexOption.IGNORE_CASE)
    private val simpleImgSrcAttrRegex = Regex("""(?:data-src|data-lazy|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ddgAnchorRegex = Regex("""<a\b[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

    fun parseBingImagesHtml(html: String): List<SearchEngineImageHit> {
        if (html.isBlank()) return emptyList()
        val hits = mutableListOf<SearchEngineImageHit>()
        val seenUrls = HashSet<String>()

        fun addHit(title: String, purl: String, murl: String, turl: String) {
            val cleanMurl = HtmlTextDecoder.normalizeImageUrl(murl)
            val cleanTurl = HtmlTextDecoder.normalizeImageUrl(turl)
            val cleanPurl = HtmlTextDecoder.decode(purl).trim()
            val cleanTitle = cleanText(title)
            val key = cleanMurl.ifBlank { cleanTurl }
            if (key.startsWith("https://") && seenUrls.add(key)) {
                hits.add(
                    SearchEngineImageHit(
                        title = cleanTitle,
                        pageUrl = cleanPurl,
                        imageUrl = cleanMurl,
                        thumbUrl = cleanTurl
                    )
                )
            }
        }

        for (match in bingMetaMRegex.findAll(html)) {
            if (hits.size >= 45) break
            val rawAttr = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (!rawAttr.contains("murl", true) && !rawAttr.contains("turl", true)) continue
            // Single-pass XML attribute decode prevents &amp;quot; from breaking inner JSON quotes
            val singleDecoded = decodeXmlAttrOnce(rawAttr)
            val jsonParsed = runCatching {
                val obj = JSONObject(singleDecoded)
                addHit(
                    title = obj.optString("t", ""),
                    purl = obj.optString("purl", ""),
                    murl = obj.optString("murl", ""),
                    turl = obj.optString("turl", "")
                )
                true
            }.getOrDefault(false)

            if (!jsonParsed) {
                val murl = murlFieldRegex.find(singleDecoded)?.groupValues?.getOrNull(1).orEmpty()
                val turl = turlFieldRegex.find(singleDecoded)?.groupValues?.getOrNull(1).orEmpty()
                val purl = purlFieldRegex.find(singleDecoded)?.groupValues?.getOrNull(1).orEmpty()
                val t = titleFieldRegex.find(singleDecoded)?.groupValues?.getOrNull(1).orEmpty()
                addHit(title = t, purl = purl, murl = murl, turl = turl)
            }
        }

        // Fallback 1: mediaurl= query parameters inside Bing image detail links (preserve nearby purl/title)
        if (hits.size < 15) {
            for (m in bingMediaUrlRegex.findAll(html)) {
                if (hits.size >= 45) break
                val decoded = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull().orEmpty()
                val window = html.substring(
                    (m.range.first - 450).coerceAtLeast(0),
                    (m.range.last + 450).coerceAtMost(html.length)
                )
                val rawPurl = rurlPurlRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()
                val decodedPurl = runCatching { URLDecoder.decode(rawPurl, "UTF-8") }.getOrNull().orEmpty()
                val mlPageUrl = normalizeProductUrl(decodedPurl)
                    .ifBlank { extractNearbyMlPageUrl(window) }
                val altTitle = attrTitleRegex
                    .find(window)?.groupValues?.getOrNull(1).orEmpty()
                    .ifBlank { if (decoded.contains("mlstatic.com", true)) deriveTitleFromUrl(mlPageUrl) else "" }
                addHit(title = altTitle, purl = mlPageUrl, murl = decoded, turl = "")
            }
        }

        // Fallback 2: Inline <img> thumbnails from Bing image tiles handling data-src + /th/id/ relative URLs
        if (hits.size < 16) {
            for (m in imgTagRegex.findAll(html)) {
                if (hits.size >= 45) break
                val tag = m.value
                val fullSrc = extractImgTagPhotoUrl(tag)
                if (fullSrc.isBlank()) continue
                val tileStart = html.lastIndexOf("<li", m.range.first).takeIf { it >= 0 && m.range.first - it < 900 }
                    ?: html.lastIndexOf("class=\"iuscp", m.range.first).takeIf { it >= 0 && m.range.first - it < 900 }
                    ?: (m.range.first - 250).coerceAtLeast(0)
                val nextLi = html.indexOf("<li", m.range.last + 1).takeIf { it > m.range.last && it - m.range.last < 450 }
                val closeLi = html.indexOf("</li>", m.range.last + 1).takeIf { it > m.range.last && it - m.range.last < 450 }
                val nextIuscp = html.indexOf("class=\"iuscp", m.range.last + 1).takeIf { it > m.range.last && it - m.range.last < 450 }
                val tileEnd = listOfNotNull(nextLi, closeLi, nextIuscp)
                    .minOrNull()
                    ?: (m.range.last + 250).coerceAtMost(html.length)
                val tileSlice = html.substring(tileStart, tileEnd.coerceAtLeast(m.range.last + 1))
                val tileCaption = containerCaptionRegex.find(tileSlice)?.let { cap ->
                    cap.groupValues[1].ifBlank { cap.groupValues[2] }
                }.orEmpty()
                val rawAlt = cleanText(altAttrAllRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty())
                    .ifBlank { cleanText(tileCaption) }
                val cleanAlt = rawAlt.replace(stripResultPrefixRegex, "").trim()
                val nearMlUrl = extractNearbyMlPageUrl(tileSlice)
                val altTokens = TextNormalizer.tokenize(cleanAlt).filter { it.length >= 2 }
                if (altTokens.size < 2) {
                    continue
                }
                addHit(title = cleanAlt, purl = nearMlUrl, murl = "", turl = fullSrc)
            }
        }
        return hits
    }

    private fun extractBoundedTileWindow(
        html: String,
        matchRange: IntRange,
        maxBefore: Int,
        maxAfter: Int,
        boundaryMarkers: List<String>
    ): String {
        var start = (matchRange.first - maxBefore).coerceAtLeast(0)
        var end = (matchRange.last + 1 + maxAfter).coerceAtMost(html.length)
        for (marker in boundaryMarkers) {
            val prevIdx = html.lastIndexOf(marker, (matchRange.first - 1).coerceAtLeast(0), ignoreCase = true)
            if (prevIdx >= start) {
                start = (prevIdx + marker.length).coerceAtMost(matchRange.first)
            }
            val nextIdx = html.indexOf(marker, (matchRange.last + 1).coerceAtMost(html.length), ignoreCase = true)
            if (nextIdx in (matchRange.last + 1) until end) {
                end = nextIdx
            }
        }
        return html.substring(start.coerceAtMost(matchRange.first), end.coerceAtLeast(matchRange.last + 1))
    }

    fun parseYahooImagesHtml(html: String): List<SearchEngineImageHit> {
        if (html.isBlank()) return emptyList()
        val hits = mutableListOf<SearchEngineImageHit>()
        val seenUrls = HashSet<String>()

        // Direct murl/imgurl parameters embedded in Yahoo Images JSON/links (Yahoo often omits https:// before host)
        for (m in imgUrlParamRegex.findAll(html)) {
            if (hits.size >= 35) break
            val decodedParam = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()?.trim().orEmpty()
            if (decodedParam.isBlank()) continue
            val raw = when {
                decodedParam.startsWith("http://", true) || decodedParam.startsWith("https://", true) -> decodedParam
                decodedParam.startsWith("//") -> "https:$decodedParam"
                else -> "https://${decodedParam.trimStart('/')}"
            }
            val clean = HtmlTextDecoder.normalizeImageUrl(raw)
            if (clean.startsWith("https://") && seenUrls.add(clean)) {
                val liStart = html.lastIndexOf("<li", m.range.first).takeIf { it >= 0 && m.range.first - it < 900 }
                val liEnd = html.indexOf("</li>", m.range.last).takeIf { it > m.range.last && it - m.range.last < 900 }
                val aStart = html.lastIndexOf("<a", m.range.first).takeIf { it >= 0 && m.range.first - it < 450 }
                val aEnd = html.indexOf("</a>", m.range.last).takeIf { it > m.range.last && it - m.range.last < 450 }
                val window = when {
                    aStart != null && aEnd != null -> html.substring(aStart, aEnd + 4)
                    liStart != null && liEnd != null -> html.substring(liStart, liEnd)
                    else -> extractBoundedTileWindow(
                        html = html,
                        matchRange = m.range,
                        maxBefore = 280,
                        maxAfter = 340,
                        boundaryMarkers = listOf("imgurl=", "murl=", "<li", "</li>")
                    )
                }
                val rawRurl = rurlPurlRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()
                val decodedRurl = runCatching { URLDecoder.decode(rawRurl, "UTF-8") }.getOrNull().orEmpty()
                val rawPageUrl = if (decodedRurl.contains("mercadolivre", true)) {
                    normalizeProductUrl(if (decodedRurl.startsWith("http", true)) decodedRurl else "https://$decodedRurl")
                } else {
                    extractNearbyMlPageUrl(window)
                }
                val rawTit = titParamRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()
                val decodedTit = runCatching { URLDecoder.decode(rawTit, "UTF-8") }.getOrNull().orEmpty()
                val nearAlt = altAttrAllRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()
                val nearTitle = cleanText(decodedTit)
                    .ifBlank { cleanText(nearAlt) }
                    .ifBlank { if (clean.contains("mlstatic.com", true) && decodedRurl.contains("mercadolivre", true)) deriveTitleFromUrl(rawPageUrl) else "" }
                hits.add(SearchEngineImageHit(title = nearTitle, pageUrl = rawPageUrl, imageUrl = clean, thumbUrl = ""))
            }
        }

        for (m in imgTagRegex.findAll(html)) {
            if (hits.size >= 45) break
            val tag = m.value
            val cleanSrc = extractImgTagPhotoUrl(tag)
            if (cleanSrc.isBlank() || !seenUrls.add(cleanSrc)) continue
            val liStart = html.lastIndexOf("<li", m.range.first).takeIf { it >= 0 && m.range.first - it < 900 }
            val liEnd = html.indexOf("</li>", m.range.last).takeIf { it > m.range.last && it - m.range.last < 900 }
            val tileSlice = if (liStart != null && liEnd != null) html.substring(liStart, liEnd) else ""
            val tileAria = if (tileSlice.isNotBlank()) {
                ariaLabelBoundedRegex.find(tileSlice)?.groupValues?.getOrNull(1).orEmpty()
                    .ifBlank {
                        titParamRegex.find(tileSlice)?.groupValues?.getOrNull(1)?.let { rawT ->
                            runCatching { URLDecoder.decode(rawT, "UTF-8") }.getOrNull()
                        }.orEmpty()
                    }
            } else ""
            val alt = cleanText(altAttrAllRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty())
                .ifBlank { cleanText(tileAria) }
            val boundedWindow = tileSlice.ifBlank {
                extractBoundedTileWindow(
                    html = html,
                    matchRange = m.range,
                    maxBefore = 240,
                    maxAfter = 180,
                    boundaryMarkers = listOf("<img", "<li", "</li>")
                )
            }
            val nearMlUrl = extractNearbyMlPageUrl(boundedWindow)
            hits.add(
                SearchEngineImageHit(
                    title = alt,
                    pageUrl = nearMlUrl,
                    imageUrl = "",
                    thumbUrl = cleanSrc
                )
            )
        }
        return hits
    }

    fun parseGoogleImagesHtml(html: String): List<SearchEngineImageHit> {
        if (html.isBlank()) return emptyList()
        val hits = mutableListOf<SearchEngineImageHit>()
        val seen = HashSet<String>()

        for (m in mlStaticCardDirectRegex.findAll(html)) {
            if (hits.size >= 35) break
            val clean = HtmlTextDecoder.normalizeImageUrl(m.value)
            if (clean.startsWith("https://") && seen.add(clean)) {
                val window = extractBoundedTileWindow(
                    html = html,
                    matchRange = m.range,
                    maxBefore = 320,
                    maxAfter = 320,
                    boundaryMarkers = listOf("mlstatic.com/D_", "encrypted-tbn", "<li", "</li>")
                )
                val imgTagStart = html.lastIndexOf("<img", m.range.first).takeIf { it >= 0 && m.range.first - it < 220 }
                val imgTagEnd = imgTagStart?.let { s ->
                    html.indexOf('>', m.range.last).takeIf { it > m.range.last && it - s < 500 }
                }
                val enclosingImgTag = if (imgTagStart != null && imgTagEnd != null) {
                    html.substring(imgTagStart, imgTagEnd + 1)
                } else ""
                val mlPageUrl = extractNearbyMlPageUrl(window)
                val tagAlt = if (enclosingImgTag.isNotBlank()) {
                    altAttrAllRegex.find(enclosingImgTag)?.groupValues?.getOrNull(1).orEmpty()
                } else {
                    altAttrAllRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()
                }
                val nearTitle = cleanText(tagAlt)
                hits.add(SearchEngineImageHit(title = nearTitle, pageUrl = mlPageUrl, imageUrl = clean, thumbUrl = ""))
            }
        }

        for (m in imgTagRegex.findAll(html)) {
            if (hits.size >= 45) break
            val tag = m.value
            val clean = extractImgTagPhotoUrl(tag)
            if (clean.isBlank() || !seen.add(clean)) continue
            val wrapperBefore = html.substring((m.range.first - 160).coerceAtLeast(0), m.range.first)
            val wrapperAria = ariaLabelBoundedRegex
                .findAll(wrapperBefore).lastOrNull()?.groupValues?.getOrNull(1).orEmpty()
            val alt = cleanText(altAttrAllRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty())
                .ifBlank { cleanText(wrapperAria) }
            val nearWindow = extractBoundedTileWindow(
                html = html,
                matchRange = m.range,
                maxBefore = 280,
                maxAfter = 240,
                boundaryMarkers = listOf("<img", "<li", "</li>")
            )
            val nearMlUrl = extractNearbyMlPageUrl(nearWindow)
            hits.add(
                SearchEngineImageHit(
                    title = alt,
                    pageUrl = nearMlUrl,
                    imageUrl = "",
                    thumbUrl = clean
                )
            )
        }

        // Script-embedded Google udm=2 thumbnails
        if (hits.size < 20) {
            for (m in gstaticThumbRegex.findAll(html)) {
                if (hits.size >= 45) break
                val clean = HtmlTextDecoder.normalizeImageUrl(m.value)
                if (clean.startsWith("https://") && seen.add(clean)) {
                    val window = extractBoundedTileWindow(
                        html = html,
                        matchRange = m.range,
                        maxBefore = 300,
                        maxAfter = 260,
                        boundaryMarkers = listOf("encrypted-tbn", "mlstatic.com/D_")
                    )
                    val mlPageUrl = extractNearbyMlPageUrl(window)
                    val nearAlt = altAttrAllRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()
                    val nearTitle = cleanText(nearAlt)
                    hits.add(SearchEngineImageHit(title = nearTitle, pageUrl = mlPageUrl, imageUrl = "", thumbUrl = clean))
                }
            }
        }
        return hits
    }

    fun parseBingShoppingHtml(
        html: String,
        queryLabel: String,
        domainHint: String
    ): Pair<List<RawStoreCandidate>, List<SearchEngineImageHit>> {
        if (html.isBlank()) return emptyList<RawStoreCandidate>() to emptyList()
        val candidates = LinkedHashMap<String, RawStoreCandidate>()
        val imageHits = mutableListOf<SearchEngineImageHit>()

        for (rawCard in html.split(bingShopSplitRegex).drop(1)) {
            val card = rawCard.take(7000)
            val anchors = anyAnchorHrefRegex.findAll(card).toList()
            val title = anchors.map { cleanText(it.groupValues[2]) }
                .firstOrNull { it.length >= 6 && !looksLikeUrlOrDomain(it) }
                .orEmpty()
            if (title.isBlank()) continue
            val priceText = extractBrlPrice(cleanText(card))
            val imgUrl = simpleImgSrcAttrRegex.findAll(card)
                .map { m -> normalizeBingOrRemoteThumbUrl(m.groupValues[1]) }
                .firstOrNull { isTrustedProductPhotoUrl(it) }
                .orEmpty()
            if (imgUrl.isNotBlank()) {
                imageHits.add(SearchEngineImageHit(title = title, pageUrl = "", imageUrl = "", thumbUrl = imgUrl))
            }
            val mlHref = anchors.map { normalizeProductUrl(unwrapBingRedirectUrl(decodeXmlAttrOnce(it.groupValues[1]))) }
                .firstOrNull { isValidProductUrl(it) }
            if (!mlHref.isNullOrBlank()) {
                mergeCandidate(
                    candidates,
                    RawStoreCandidate(
                        id = canonicalCandidateId(mlHref, "bs"),
                        store = StoreType.MERCADO_LIVRE,
                        title = title,
                        imageUrl = imgUrl,
                        productUrl = mlHref,
                        affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(mlHref),
                        priceText = priceText,
                        categoryPath = domainHint,
                        domainId = domainHint,
                        sourceQueryVariant = queryLabel,
                        sourceRank = candidates.size
                    )
                )
            }
        }
        return candidates.values.toList() to imageHits
    }

    private val ambiguousPhotoWords = setOf(
        "kit", "para", "com", "sem", "novo", "nova", "original",
        "preto", "branco", "preta", "branca", "azul", "vermelho", "cinza", "prata", "verde",
        "capa", "capinha", "suporte", "cabo", "fonte",
        "placa", "placas",
        "caixa", "peca", "item", "produto", "oferta", "barato", "promo"
    )
    private val accessoryTrapWords = setOf(
        "capa", "capinha", "pelicula", "suporte", "cabo", "carregador", "adaptador",
        "controle", "case", "bolsa", "mochila", "bateria", "adesivo", "protetor",
        "carcaca", "tampa", "dobradica", "peca", "sucata"
    )
    private val genericTechSpecCodes = setOf(
        "ddr3", "ddr4", "ddr5", "v2", "v3", "v4", "v5", "lga", "2011", "lga2011", "2011v3", "lga2011v3", "20113",
        "m2", "sata3", "pcie3", "pcie4", "usb30", "usb20", "e5", "20", "30", "10", "1000",
        "4k", "5g", "4g", "110v", "220v", "usb2", "usb3", "usb", "2x", "x99", "x79", "am4", "am5"
    )
    private val capacityPhotoTokenRegex = Regex("""^\d+(?:gb|tb|mah|w|hz|l|ml|pol)$""")

    private class PreparedImageHit(
        val hit: SearchEngineImageHit,
        val preferredUrl: String,
        val pageMlbId: String?,
        val isMlCdnPhoto: Boolean,
        val isMlPage: Boolean,
        val isTrustedPhoto: Boolean,
        val hitTokens: Set<String>,
        val hitNormTextWithUrl: String,
        val isSafeForMotherboard: Boolean
    )

    internal fun hasMotherboardPhotoSignals(tokens: Set<String>, normText: String): Boolean {
        return normText.contains("placa mae") ||
            normText.contains("motherboard") ||
            normText.contains("mainboard") ||
            tokens.any { it in NeuralProductMatcher.MOTHERBOARD_HARDWARE_TOKENS }
    }

    internal fun hasAutomotiveOrNonMotherboardPhotoSignals(tokens: Set<String>, normText: String): Boolean {
        return tokens.any { it in NeuralProductMatcher.AUTOMOTIVE_AND_PLATE_CONFLICT_TOKENS } ||
            NeuralProductMatcher.NON_MOTHERBOARD_PLACA_PHRASES.any { normText.contains(it) }
    }

    internal fun isSafeMotherboardImageHit(hit: SearchEngineImageHit): Boolean {
        val rawTitle = hit.title.trim()
        if (rawTitle.isBlank()) return false
        val normTitle = TextNormalizer.normalize(rawTitle)
        val titleTokens = TextNormalizer.tokenize(normTitle, dropStopWords = false).toSet()
        val normCombined = "$normTitle ${TextNormalizer.normalize(hit.pageUrl)}".trim()
        val combinedTokens = TextNormalizer.tokenize(normCombined, dropStopWords = false).toSet()
        if (hasAutomotiveOrNonMotherboardPhotoSignals(combinedTokens, normCombined)) return false
        return hasMotherboardPhotoSignals(titleTokens, normTitle)
    }

    private fun isSnippetImgCompatibleWithTitle(imgTag: String, candidateTitle: String): Boolean {
        val normCand = TextNormalizer.normalize(candidateTitle)
        val candTokens = TextNormalizer.tokenize(normCand, dropStopWords = false).toSet()
        if (!hasMotherboardPhotoSignals(candTokens, normCand)) return true
        val alt = cleanText(altAttrAllRegex.find(imgTag)?.groupValues?.getOrNull(1).orEmpty())
        if (alt.isBlank()) return true
        val normAlt = TextNormalizer.normalize(alt)
        val altTokens = TextNormalizer.tokenize(normAlt, dropStopWords = false).toSet()
        if (hasAutomotiveOrNonMotherboardPhotoSignals(altTokens, normAlt)) return false
        if (("placa" in altTokens || "placas" in altTokens) && !hasMotherboardPhotoSignals(altTokens, normAlt)) {
            return false
        }
        return true
    }

    private fun extractCompatibleSnippetImg(blockHtml: String, candidateTitle: String, mlStaticPreferredOnly: Boolean = false): String {
        val matchedTags = imgTagRegex.findAll(blockHtml).toList()
        if (matchedTags.isNotEmpty()) {
            val urls = matchedTags.mapNotNull { m ->
                val tag = m.value
                if (!isSnippetImgCompatibleWithTitle(tag, candidateTitle)) return@mapNotNull null
                val rawSrc = bingImgSrcRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty()
                val normalized = normalizeBingOrRemoteThumbUrl(rawSrc)
                normalized.takeIf { isTrustedProductPhotoUrl(it) }
            }
            val mlFirst = urls.firstOrNull { it.contains("mlstatic.com", true) }
            if (!mlFirst.isNullOrBlank()) return mlFirst
            if (!mlStaticPreferredOnly) {
                return urls.firstOrNull().orEmpty()
            }
            return ""
        }
        if (mlStaticPreferredOnly) return ""
        return bingImgSrcRegex.findAll(blockHtml)
            .map { rawSrc -> normalizeBingOrRemoteThumbUrl(rawSrc.groupValues[1]) }
            .firstOrNull { img -> isTrustedProductPhotoUrl(img) }
            .orEmpty()
    }

    private fun isByIdHitCompatible(
        candTokens: Set<String>,
        candNormText: String,
        hit: SearchEngineImageHit,
        hitNormTextWithUrl: String
    ): Boolean {
        val candIsMotherboard = hasMotherboardPhotoSignals(candTokens, candNormText)
        val hitAllTokens = TextNormalizer.tokenize(hitNormTextWithUrl, dropStopWords = false).toSet()
        val hitHasAutoConflict = hasAutomotiveOrNonMotherboardPhotoSignals(hitAllTokens, hitNormTextWithUrl)
        if (candIsMotherboard) {
            if (hitHasAutoConflict) return false
            // Never accept an unlabeled or non-motherboard search-engine hit for a motherboard even when pageMlbId matches
            if (!isSafeMotherboardImageHit(hit)) return false
        } else if (hasAutomotiveOrNonMotherboardPhotoSignals(candTokens, candNormText) &&
            hasMotherboardPhotoSignals(hitAllTokens, hitNormTextWithUrl)
        ) {
            return false
        }
        if (hit.title.isNotBlank()) {
            val hitTitleTokens = TextNormalizer.tokenize(hit.title).filter { it.length >= 2 }.toSet()
            val candAcc = candTokens.intersect(accessoryTrapWords)
            val hitAcc = hitTitleTokens.intersect(accessoryTrapWords)
            if ((candAcc.isEmpty() && hitAcc.isNotEmpty()) || (candAcc.isNotEmpty() && hitAcc.isEmpty())) {
                return false
            }
        }
        return true
    }

    private fun computeCompatiblePhotoScore(
        candTokens: Set<String>,
        candNormText: String,
        hitTokens: Set<String>,
        hitNormTextWithUrl: String,
        isMlCdnPhoto: Boolean,
        isMlPage: Boolean,
        isTrustedPhoto: Boolean,
        isSafeForMotherboard: Boolean
    ): Double {
        if (candTokens.isEmpty() || hitTokens.isEmpty()) return -1.0

        // Guard 1: Accessory vs Main Product mismatch (never put a case/cable/mount photo on a main product or vice-versa)
        val candAcc = candTokens.intersect(accessoryTrapWords)
        val hitAcc = hitTokens.intersect(accessoryTrapWords)
        if ((candAcc.isEmpty() && hitAcc.isNotEmpty()) || (candAcc.isNotEmpty() && hitAcc.isEmpty())) {
            return -1.0
        }

        // Guard 2: Capacity mismatch when both specify capacities (e.g. 256GB vs 128GB, 1TB vs 240GB)
        val candCaps = candTokens.filter { capacityPhotoTokenRegex.matches(it) }.toSet()
        val hitCaps = hitTokens.filter { capacityPhotoTokenRegex.matches(it) }.toSet()
        if (candCaps.isNotEmpty() && hitCaps.isNotEmpty() && candCaps.none { it in hitCaps }) {
            return -1.0
        }

        // Guard 3: Specific alphanumeric model code conflict (e.g. S24 vs A15, PR9 vs QD4, 2680 vs 2670)
        val candSpecificCodes = candTokens.filter { tok ->
            tok.any(Char::isDigit) && tok !in genericTechSpecCodes && !capacityPhotoTokenRegex.matches(tok)
        }.toSet()
        val hitSpecificCodes = hitTokens.filter { tok ->
            tok.any(Char::isDigit) && tok !in genericTechSpecCodes && !capacityPhotoTokenRegex.matches(tok)
        }.toSet()
        val sharedSpecificCodes = candSpecificCodes.intersect(hitSpecificCodes)
        if (candSpecificCodes.isNotEmpty() && hitSpecificCodes.isNotEmpty() && sharedSpecificCodes.isEmpty()) {
            return -1.0
        }

        // Guard 4: Motherboard vs Car License Plate / Automotive / Non-motherboard 'placa' conflict
        val candIsMotherboard = hasMotherboardPhotoSignals(candTokens, candNormText)
        val hitAllTokens = TextNormalizer.tokenize(hitNormTextWithUrl, dropStopWords = false).toSet()
        val hitHasAutoConflict = hasAutomotiveOrNonMotherboardPhotoSignals(hitAllTokens, hitNormTextWithUrl)
        if (candIsMotherboard) {
            if (hitHasAutoConflict || !isSafeForMotherboard) return -1.0
            if (("placa" in hitAllTokens || "placas" in hitAllTokens) &&
                !hasMotherboardPhotoSignals(hitAllTokens, hitNormTextWithUrl)
            ) {
                return -1.0
            }
        } else if (hasAutomotiveOrNonMotherboardPhotoSignals(candTokens, candNormText) &&
            hasMotherboardPhotoSignals(hitAllTokens, hitNormTextWithUrl)
        ) {
            return -1.0
        }

        var commonCount = 0
        var meaningfulCommon = 0
        for (tok in candTokens) {
            if (tok in hitTokens) {
                commonCount++
                if (tok !in ambiguousPhotoWords || tok.any(Char::isDigit)) {
                    meaningfulCommon++
                }
            }
        }
        if (commonCount < 2 || meaningfulCommon < 1) return -1.0
        if (meaningfulCommon < 2 && sharedSpecificCodes.isEmpty()) return -1.0

        val minSize = minOf(candTokens.size, hitTokens.size).coerceAtLeast(1)
        val unionSize = (candTokens.size + hitTokens.size - commonCount).coerceAtLeast(1)
        val containment = commonCount.toDouble() / minSize.toDouble()
        val jaccard = commonCount.toDouble() / unionSize.toDouble()

        val passesThreshold = (sharedSpecificCodes.isNotEmpty() && containment >= 0.40) ||
            (containment >= 0.50 && (jaccard >= 0.22 || meaningfulCommon >= 3))
        if (!passesThreshold) return -1.0

        val bonus = when {
            isMlCdnPhoto -> 0.25
            isMlPage -> 0.15
            isTrustedPhoto -> 0.06
            else -> 0.0
        }
        val modelBonus = if (sharedSpecificCodes.isNotEmpty()) 0.12 else 0.0
        return 0.55 * containment + 0.45 * jaccard + bonus + modelBonus
    }

    private fun scorePreparedImageHit(
        candTokens: Set<String>,
        candNormText: String,
        prepared: PreparedImageHit
    ): Double = computeCompatiblePhotoScore(
        candTokens = candTokens,
        candNormText = candNormText,
        hitTokens = prepared.hitTokens,
        hitNormTextWithUrl = prepared.hitNormTextWithUrl,
        isMlCdnPhoto = prepared.isMlCdnPhoto,
        isMlPage = prepared.isMlPage,
        isTrustedPhoto = prepared.isTrustedPhoto,
        isSafeForMotherboard = prepared.isSafeForMotherboard
    )

    fun scoreCompatibleImageHit(
        candidateTitle: String,
        hit: SearchEngineImageHit
    ): Double {
        val chosenUrl = hit.preferredImageUrl
        if (chosenUrl.isBlank()) return -1.0

        val normCand = TextNormalizer.normalize(candidateTitle)
        val candTokens = TextNormalizer.tokenize(normCand).filter { it.length >= 2 }.toSet()
        if (candTokens.isEmpty()) return -1.0

        val isMlCdnPhoto = chosenUrl.contains("mlstatic.com", true) || hit.imageUrl.contains("mlstatic.com", true)
        val effectiveTitle = if (isMlCdnPhoto) {
            hit.title.ifBlank { deriveTitleFromUrl(hit.pageUrl) }
        } else {
            hit.title
        }
        val hitTokens = TextNormalizer.tokenize(effectiveTitle).filter { it.length >= 2 }.toSet()
        val hitNormTextWithUrl = "${TextNormalizer.normalize(effectiveTitle)} ${TextNormalizer.normalize(hit.pageUrl)}".trim()
        return computeCompatiblePhotoScore(
            candTokens = candTokens,
            candNormText = normCand,
            hitTokens = hitTokens,
            hitNormTextWithUrl = hitNormTextWithUrl,
            isMlCdnPhoto = isMlCdnPhoto,
            isMlPage = hit.pageUrl.contains("mercadolivre.com.br", true),
            isTrustedPhoto = isTrustedProductPhotoUrl(chosenUrl),
            isSafeForMotherboard = isSafeMotherboardImageHit(hit)
        )
    }

    fun pickBestCompatibleImageHit(
        candidateTitle: String,
        productUrl: String,
        hits: List<SearchEngineImageHit>
    ): SearchEngineImageHit? {
        if (hits.isEmpty()) return null
        val normCand = TextNormalizer.normalize(candidateTitle)
        val candTokens = TextNormalizer.tokenize(normCand).filter { it.length >= 2 }.toSet()
        val candMlb = extractMlbId(productUrl)
        if (!candMlb.isNullOrBlank()) {
            val byId = hits.firstOrNull { hit ->
                val url = hit.preferredImageUrl
                val isMlCdn = url.contains("mlstatic.com", true)
                val hitNormTextWithUrl = "${TextNormalizer.normalize(hit.title)} ${TextNormalizer.normalize(hit.pageUrl)}".trim()
                extractMlbId(hit.pageUrl) == candMlb &&
                    url.isNotBlank() &&
                    (isMlCdn || hit.title.isNotBlank()) &&
                    isByIdHitCompatible(candTokens, normCand, hit, hitNormTextWithUrl)
            }
            if (byId != null) return byId
        }
        var bestHit: SearchEngineImageHit? = null
        var bestScore = 0.0
        for (hit in hits) {
            val score = scoreCompatibleImageHit(candidateTitle, hit)
            if (score > bestScore) {
                bestScore = score
                bestHit = hit
            }
        }
        return bestHit
    }

    /**
     * Picks a compatible live HTTPS product photo matching by MLB ID or strict title/model/capacity compatibility.
     * Never attaches an unrelated image when no compatible match exists.
     */
    fun pickFallbackCandidateImageHit(
        candidateTitle: String,
        productUrl: String,
        hits: List<SearchEngineImageHit>
    ): SearchEngineImageHit? {
        if (candidateTitle.isBlank() || hits.isEmpty()) return null
        return pickBestCompatibleImageHit(candidateTitle, productUrl, hits)
    }

    fun applyImageHits(
        candidates: List<RawStoreCandidate>,
        hits: List<SearchEngineImageHit>
    ): List<RawStoreCandidate> {
        if (candidates.isEmpty() || hits.isEmpty()) return candidates
        if (candidates.none { it.imageUrl.isBlank() }) return candidates

        val preparedHits = hits.mapNotNull { hit ->
            val preferredUrl = hit.preferredImageUrl
            if (preferredUrl.isBlank()) return@mapNotNull null
            val isMlCdnPhoto = preferredUrl.contains("mlstatic.com", true) || hit.imageUrl.contains("mlstatic.com", true)
            val effectiveTitle = if (isMlCdnPhoto) {
                hit.title.ifBlank { deriveTitleFromUrl(hit.pageUrl) }
            } else {
                hit.title
            }
            val hitTokens = TextNormalizer.tokenize(effectiveTitle).filter { it.length >= 2 }.toSet()
            val hitNormTextWithUrl = "${TextNormalizer.normalize(effectiveTitle)} ${TextNormalizer.normalize(hit.pageUrl)}".trim()
            PreparedImageHit(
                hit = hit,
                preferredUrl = preferredUrl,
                pageMlbId = extractMlbId(hit.pageUrl),
                isMlCdnPhoto = isMlCdnPhoto,
                isMlPage = hit.pageUrl.contains("mercadolivre.com.br", true),
                isTrustedPhoto = isTrustedProductPhotoUrl(preferredUrl),
                hitTokens = hitTokens,
                hitNormTextWithUrl = hitNormTextWithUrl,
                isSafeForMotherboard = isSafeMotherboardImageHit(hit)
            )
        }
        if (preparedHits.isEmpty()) return candidates

        return candidates.map { candidate ->
            if (candidate.imageUrl.isNotBlank()) return@map candidate
            val normCand = TextNormalizer.normalize(candidate.title)
            val candTokens = TextNormalizer.tokenize(normCand)
                .filter { it.length >= 2 }
                .toSet()
            val candMlb = extractMlbId(candidate.productUrl)
            if (!candMlb.isNullOrBlank()) {
                val byId = preparedHits.firstOrNull { prep ->
                    prep.pageMlbId == candMlb &&
                        (prep.isMlCdnPhoto || prep.hit.title.isNotBlank()) &&
                        isByIdHitCompatible(candTokens, normCand, prep.hit, prep.hitNormTextWithUrl)
                }
                if (byId != null) return@map candidate.copy(imageUrl = byId.preferredUrl)
            }
            if (candTokens.isEmpty()) return@map candidate
            var bestPrep: PreparedImageHit? = null
            var bestScore = 0.0
            for (prep in preparedHits) {
                val score = scorePreparedImageHit(candTokens, normCand, prep)
                if (score > bestScore) {
                    bestScore = score
                    bestPrep = prep
                }
            }
            if (bestPrep != null) candidate.copy(imageUrl = bestPrep.preferredUrl) else candidate
        }
    }

    fun parseDuckDuckGoHtml(
        html: String,
        queryLabel: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        if (html.isBlank()) return emptyList()
        val out = LinkedHashMap<String, RawStoreCandidate>()
        for (match in ddgAnchorRegex.findAll(html)) {
            if (out.size >= 25) break
            val rawHref = HtmlTextDecoder.decode(match.groupValues[1].trim())
            val resolvedUrl = normalizeProductUrl(unwrapDuckDuckGoUrl(rawHref))
            if (!isValidProductUrl(resolvedUrl)) continue
            val title = sanitizeCandidateTitle(match.groupValues[2], resolvedUrl)
            if (title.length < 6) continue

            val afterWindow = cleanText(
                html.substring(match.range.last.coerceAtLeast(0), (match.range.last + 900).coerceAtMost(html.length))
            )
            val priceText = extractBrlPrice("$title $afterWindow")
            mergeCandidate(
                out,
                RawStoreCandidate(
                    id = canonicalCandidateId(resolvedUrl, "ddg"),
                    store = StoreType.MERCADO_LIVRE,
                    title = title,
                    imageUrl = "",
                    productUrl = resolvedUrl,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(resolvedUrl),
                    priceText = priceText,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = queryLabel,
                    sourceRank = out.size
                )
            )
        }
        return out.values.toList()
    }

    private val yahooHeadingInsideAnchorRegex = Regex("""<(?:h[234]|span[^>]*class=["'][^"']*fw-600[^"']*["'])[^>]*>([\s\S]*?)</(?:h[234]|span)>""", RegexOption.IGNORE_CASE)
    private val imgSrcAttrSimpleRegex = Regex("""(?:data-src|src)=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val bingRssItemRegex = Regex("""<item>([\s\S]*?)</item>""", RegexOption.IGNORE_CASE)
    private val bingRssTitleRegex = Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val bingRssLinkRegex = Regex("""<link>([\s\S]*?)</link>""", RegexOption.IGNORE_CASE)
    private val bingRssDescRegex = Regex("""<description>([\s\S]*?)</description>""", RegexOption.IGNORE_CASE)
    private val compactShippingBadgeRegex = Regex("""\b(?:frete\s+gr[áa]tis|envio\s+imediato|pronta\s+entrega|com\s+nota\s+fiscal|em\s+estoque|12x\s+sem\s+juros)\b""", RegexOption.IGNORE_CASE)
    private val compactPunctRegex = Regex("""[+/|\\,;:\[\]()]+""")
    private val multiDotsRegex = Regex("""\.{2,}""")
    private val compactStopWords = setOf(
        "de", "da", "do", "dos", "das", "para", "com", "sem", "em", "na", "no",
        "e", "ou", "cor", "preto", "preta", "branco", "branca", "verde", "azul",
        "vermelho", "vermelha", "cinza", "prata", "novo", "nova", "original",
        "lacrado", "lacrada", "nf", "uso"
    )
    private val pureNumericOrBrlRegex = Regex("""^(?:R\$\s*|BRL\s*)?\d[\d.,]*$""", RegexOption.IGNORE_CASE)
    private val jsonPriceKeys = listOf("price", "preco", "preço", "valor", "price_brl")
    private val indexedLineRegex = Regex("""^\s*[-*•]?\s*\[?(\d{1,2})\]?(?:\s*[:.\-)\]]|\s+-)\s*(.+)$""")
    private val bracketPrefixRegex = Regex("""^\[\d+\]\s*""")
    private val groundedBulletRegex = Regex("""^\s*(?:[-*•]|\d+[.)])\s*(.+?)\s*(?:[:-]|—|–)\s*(R\$\s*[\d.,]+.*)$""")
    private val nextDataPriceKeys = listOf("price", "priceValue", "minPrice", "bestPrice", "lowerPrice")
    private val markdownCodeFenceJsonRegex = Regex("""```json\s*""", RegexOption.IGNORE_CASE)
    private val yahooRuRegex = Regex("""/RU=([^/]+)/""", RegexOption.IGNORE_CASE)

    fun parseYahooHtml(
        html: String,
        queryLabel: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        if (html.isBlank()) return emptyList()
        val out = LinkedHashMap<String, RawStoreCandidate>()
        for (match in anyAnchorHrefRegex.findAll(html)) {
            if (out.size >= 25) break
            val rawHref = HtmlTextDecoder.decode(match.groupValues[1].trim())
            val resolvedUrl = normalizeProductUrl(unwrapYahooUrl(rawHref))
            if (!isValidProductUrl(resolvedUrl)) continue
            val innerHtml = match.groupValues[2]
            val headingInsideAnchor = yahooHeadingInsideAnchorRegex
                .find(innerHtml)?.groupValues?.getOrNull(1)
            val rawInnerClean = cleanText(headingInsideAnchor ?: innerHtml)
            if (rawInnerClean.length < 6) continue
            val title = sanitizeCandidateTitle(rawInnerClean, resolvedUrl)
            if (title.length < 6) continue

            val cardEnd = html.indexOf("</li>", match.range.last).takeIf { it > match.range.last && it - match.range.last < 3500 }
                ?: html.indexOf("<div class=\"dd algo", match.range.last).takeIf { it > match.range.last && it - match.range.last < 3500 }
                ?: (match.range.last + 1400).coerceAtMost(html.length)
            val snippetHtml = html.substring(match.range.last.coerceAtLeast(0), cardEnd)
            val blockHtml = html.substring(
                (match.range.first - 250).coerceAtLeast(0),
                cardEnd
            )
            val priceText = extractBrlPrice("$title ${cleanText(snippetHtml)}")
            val imgUrl = extractCompatibleSnippetImg(blockHtml, title)
            mergeCandidate(
                out,
                RawStoreCandidate(
                    id = canonicalCandidateId(resolvedUrl, "yh"),
                    store = StoreType.MERCADO_LIVRE,
                    title = title,
                    imageUrl = imgUrl,
                    productUrl = resolvedUrl,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(resolvedUrl),
                    priceText = priceText,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = queryLabel,
                    sourceRank = out.size
                )
            )
        }
        return out.values.toList()
    }

    fun parseGoogleWebHtml(
        html: String,
        queryLabel: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        if (html.isBlank()) return emptyList()
        val out = LinkedHashMap<String, RawStoreCandidate>()

        for (match in anyAnchorHrefRegex.findAll(html)) {
            if (out.size >= 25) break
            val rawHref = HtmlTextDecoder.decode(match.groupValues[1].trim())
            val unwrapped = if (rawHref.startsWith("/url?", true) || rawHref.contains("google.com/url?", true)) {
                val full = if (rawHref.startsWith("/")) "https://www.google.com$rawHref" else rawHref
                runCatching {
                    val uri = Uri.parse(full)
                    uri.getQueryParameter("q") ?: uri.getQueryParameter("url") ?: ""
                }.getOrNull().orEmpty()
            } else {
                rawHref
            }
            val resolvedUrl = normalizeProductUrl(unwrapped)
            if (!isValidProductUrl(resolvedUrl)) continue
            val rawInner = cleanText(match.groupValues[2])
            if (rawInner.length < 6 || looksLikeUrlOrDomain(rawInner)) continue
            val title = sanitizeCandidateTitle(rawInner, resolvedUrl)
            if (title.length < 6) continue

            val blockHtml = html.substring(
                (match.range.first - 200).coerceAtLeast(0),
                (match.range.last + 750).coerceAtMost(html.length)
            )
            val priceText = extractBrlPrice("$title ${cleanText(blockHtml)}")
            val imgUrl = extractCompatibleSnippetImg(blockHtml, title, mlStaticPreferredOnly = true)

            mergeCandidate(
                out,
                RawStoreCandidate(
                    id = canonicalCandidateId(resolvedUrl, "gw"),
                    store = StoreType.MERCADO_LIVRE,
                    title = title,
                    imageUrl = imgUrl,
                    productUrl = resolvedUrl,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(resolvedUrl),
                    priceText = priceText,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = queryLabel,
                    sourceRank = out.size
                )
            )
        }
        return out.values.toList()
    }

    fun parseBingRssXml(
        xml: String,
        queryLabel: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        if (xml.isBlank() || !xml.contains("<item>", ignoreCase = true)) return emptyList()
        val out = LinkedHashMap<String, RawStoreCandidate>()

        for (match in bingRssItemRegex.findAll(xml)) {
            if (out.size >= 30) break
            val block = match.groupValues[1]
            val rawLink = HtmlTextDecoder.decode(
                unwrapXmlCdata(bingRssLinkRegex.find(block)?.groupValues?.getOrNull(1).orEmpty().trim())
            )
            val resolvedUrl = normalizeProductUrl(rawLink)
            if (!isValidProductUrl(resolvedUrl)) continue
            val rawTitle = HtmlTextDecoder.decode(
                unwrapXmlCdata(bingRssTitleRegex.find(block)?.groupValues?.getOrNull(1).orEmpty().trim())
            )
            val title = sanitizeCandidateTitle(rawTitle, resolvedUrl)
            if (title.length < 6) continue
            val desc = cleanText(
                unwrapXmlCdata(bingRssDescRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
            )
            val priceText = extractBrlPrice("$title $desc")

            mergeCandidate(
                out,
                RawStoreCandidate(
                    id = canonicalCandidateId(resolvedUrl, "bg"),
                    store = StoreType.MERCADO_LIVRE,
                    title = title,
                    imageUrl = "",
                    productUrl = resolvedUrl,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(resolvedUrl),
                    priceText = priceText,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = queryLabel,
                    sourceRank = out.size
                )
            )
        }
        return out.values.toList()
    }

    private fun unwrapXmlCdata(raw: String): String {
        return raw.replace("<![CDATA[", "").replace("]]>", "").trim()
    }

    fun buildCompactModelQuery(title: String): String {
        val cleaned = cleanText(title)
            .replace(compactShippingBadgeRegex, " ")
            .replace(compactPunctRegex, " ")
            .replace(multiDotsRegex, " ")
            .replace(wsRegex, " ")
            .trim()
        if (cleaned.isBlank()) return ""

        val seen = HashSet<String>()
        val selected = mutableListOf<String>()
        for (rawTok in cleaned.split(' ')) {
            val tok = rawTok.trim('.', '-', '_')
            if (tok.isBlank()) continue
            val norm = TextNormalizer.normalize(tok)
            if (norm.isBlank() || norm in compactStopWords) continue
            if (norm.length < 2 && !norm[0].isDigit()) continue
            if (!seen.add(norm)) continue
            selected.add(tok)
            if (selected.size >= 7) break
        }
        val compact = selected.joinToString(" ").trim()
        return (if (compact.length > 62) compact.take(62).substringBeforeLast(' ').ifBlank { compact.take(62) } else compact)
            .ifBlank { cleaned.take(60) }
    }

    fun parseJsonPriceField(rawPrice: String): String {
        val trimmed = rawPrice.trim()
        if (trimmed.isBlank()) return ""
        val extracted = extractBrlPrice(trimmed)
        if (extracted.isNotBlank()) return extracted
        if (!pureNumericOrBrlRegex.matches(trimmed)) return ""
        return PriceFormatter.formatBrl(trimmed)
    }

    fun parseGroundedPriceResponse(
        rawText: String,
        items: List<RawStoreCandidate>,
        supportSegments: List<String> = emptyList()
    ): Map<String, String> {
        if (items.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val itemsById = items.associateBy { it.id }
        val preparedItems = items.map { cand ->
            val mlb = extractMlbId(cand.productUrl)
            val normTitle = TextNormalizer.normalize(cand.title)
            val tokens = TextNormalizer.tokenize(normTitle).filter { it.length >= 2 }.toSet()
            Triple(cand, mlb, normTitle to tokens)
        }

        fun recordMatch(candidate: RawStoreCandidate?, explicitId: String, titleText: String, formatted: String) {
            if (formatted.isBlank()) return
            if (candidate != null) {
                out.putIfAbsent(candidate.id, formatted)
                extractMlbId(candidate.productUrl)?.let { out.putIfAbsent(it.uppercase(), formatted) }
                val candNorm = TextNormalizer.normalize(candidate.title)
                if (candNorm.length >= 6) out.putIfAbsent(candNorm, formatted)
            }
            if (explicitId.isNotBlank()) {
                out.putIfAbsent(explicitId, formatted)
                extractMlbId(explicitId)?.let { out.putIfAbsent(it.uppercase(), formatted) }
            }
            val titleKey = TextNormalizer.normalize(titleText)
            if (titleKey.length >= 6) {
                out.putIfAbsent(titleKey, formatted)
            }
        }

        fun extractJsonPriceString(obj: JSONObject): String {
            for (key in jsonPriceKeys) {
                if (!obj.has(key) || obj.isNull(key)) continue
                val num = obj.optDouble(key, Double.NaN)
                if (num.isFinite() && num > 0.0) {
                    val formatted = PriceFormatter.formatBrl(num)
                    if (formatted.isNotBlank()) return formatted
                }
                val raw = obj.optString(key, "").trim()
                if (raw.isNotBlank()) {
                    val formatted = parseJsonPriceField(raw)
                    if (formatted.isNotBlank()) return formatted
                }
            }
            return ""
        }

        val arr = extractFirstValidJsonArray(rawText)
        if (arr != null) {
            for (k in 0 until arr.length()) {
                val obj = arr.optJSONObject(k) ?: continue
                val formatted = extractJsonPriceString(obj)
                if (formatted.isBlank()) continue

                val numIdx = when {
                    obj.has("i") -> obj.optString("i", "").trim().toIntOrNull()
                    obj.has("index") -> obj.optString("index", "").trim().toIntOrNull()
                    obj.has("item") -> obj.optString("item", "").trim().toIntOrNull()
                    obj.has("num") -> obj.optString("num", "").trim().toIntOrNull()
                    else -> obj.optString("id", "").trim().toIntOrNull()
                }
                val explicitId = obj.optString("id", "").trim()
                val titleText = HtmlTextDecoder.decode(obj.optString("title", obj.optString("name", ""))).trim()
                val normResponseTitle = TextNormalizer.normalize(titleText)
                val matchedCandidate = when {
                    numIdx != null && numIdx in 1..items.size -> items[numIdx - 1]
                    explicitId.isNotBlank() -> itemsById[explicitId]
                        ?: preparedItems.firstOrNull { (_, mlb, _) ->
                            !mlb.isNullOrBlank() && explicitId.contains(mlb, ignoreCase = true)
                        }?.first
                    else -> preparedItems.firstOrNull { (_, _, normPair) ->
                        normResponseTitle.length >= 6 && normPair.first == normResponseTitle
                    }?.first ?: run {
                        val respTokens = TextNormalizer.tokenize(normResponseTitle).filter { it.length >= 2 }.toSet()
                        if (respTokens.size >= 2) {
                            var bestCand: RawStoreCandidate? = null
                            var bestScore = Int.MIN_VALUE
                            for ((cand, _, normPair) in preparedItems) {
                                val candTokens = normPair.second
                                var common = 0
                                var hasModelToken = false
                                for (tok in respTokens) {
                                    if (tok in candTokens) {
                                        common++
                                        if (tok.any(Char::isDigit)) hasModelToken = true
                                    }
                                }
                                val minSize = minOf(respTokens.size, candTokens.size).coerceAtLeast(1)
                                val matchesRatio = (common >= 2 && common.toDouble() / minSize >= 0.45) ||
                                    (hasModelToken && common >= 2)
                                if (matchesRatio) {
                                    val sc = common + if (hasModelToken) 1 else 0
                                    if (sc > bestScore) {
                                        bestScore = sc
                                        bestCand = cand
                                    }
                                }
                            }
                            bestCand
                        } else null
                    } ?: items.getOrNull(k)?.takeIf {
                        arr.length() == items.size && !out.containsKey(it.id)
                    }
                }
                recordMatch(matchedCandidate, explicitId, titleText, formatted)
            }
        }

        // Line-based indexed or ID fallback (e.g. "1: R$ 429,90" or "[2] Placa Mãe - R$ 589,00")
        if (rawText.contains("R$", ignoreCase = true) && items.any { !out.containsKey(it.id) }) {
            for (line in rawText.lines()) {
                val idxMatch = indexedLineRegex.find(line)
                if (idxMatch != null) {
                    val idxNum = idxMatch.groupValues[1].toIntOrNull()
                    val linePrice = extractBrlPrice(idxMatch.groupValues[2])
                    if (idxNum != null && idxNum in 1..items.size && linePrice.isNotBlank()) {
                        val target = items[idxNum - 1]
                        if (!out.containsKey(target.id)) {
                            recordMatch(target, target.id, "", linePrice)
                        }
                        continue
                    }
                }
                val linePrice = extractBrlPrice(line)
                if (linePrice.isBlank()) continue
                for ((c, mlbId, _) in preparedItems) {
                    if (out.containsKey(c.id)) continue
                    if (line.contains(c.id, true) || (!mlbId.isNullOrBlank() && line.contains(mlbId, true))) {
                        recordMatch(c, c.id, "", linePrice)
                    }
                }
            }
        }

        // GroundingSupports snippet fallback for remaining unpriced candidates
        if (supportSegments.isNotEmpty() && items.any { !out.containsKey(it.id) }) {
            for (segText in supportSegments) {
                val segPrice = extractBrlPrice(segText)
                if (segPrice.isBlank()) continue
                val segTokens = TextNormalizer.tokenize(TextNormalizer.normalize(segText))
                    .filter { it.length >= 2 }
                    .toSet()
                if (segTokens.isEmpty()) continue
                for ((c, _, normPair) in preparedItems) {
                    if (out.containsKey(c.id)) continue
                    val cTokens = normPair.second
                    var commonCount = 0
                    var hasSignificantMatch = false
                    for (tok in cTokens) {
                        if (tok in segTokens) {
                            commonCount++
                            if (tok.length >= 5 || tok.any { it.isDigit() }) hasSignificantMatch = true
                        }
                    }
                    if (commonCount >= 2 && hasSignificantMatch) {
                        recordMatch(c, c.id, "", segPrice)
                    }
                }
            }
        }

        return out
    }

    fun parseGroundedCatalogPrices(
        rawText: String,
        supportSegments: List<String> = emptyList()
    ): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        fun putEntry(rawTitle: String, rawPrice: String) {
            val clean = cleanText(rawTitle).replace(bracketPrefixRegex, "").trim()
            val formatted = parseJsonPriceField(rawPrice)
            if (clean.length < 6 || looksLikeUrlOrDomain(clean) || formatted.isBlank()) return
            if (parseBrlAmount(formatted) < 10.0) return
            val key = TextNormalizer.normalize(clean)
            if (key.length >= 6) {
                result.putIfAbsent(clean, formatted)
            }
        }

        val arr = extractFirstValidJsonArray(rawText)
        if (arr != null) {
            for (k in 0 until arr.length()) {
                val obj = arr.optJSONObject(k) ?: continue
                val rawPriceVal = obj.optString("price", "")
                    .ifBlank { obj.optString("preco", "") }
                    .ifBlank { obj.optString("preço", "") }
                    .ifBlank { obj.optString("valor", "") }
                putEntry(
                    rawTitle = obj.optString("title", obj.optString("name", "")),
                    rawPrice = rawPriceVal
                )
            }
        }

        for (line in rawText.lines()) {
            val m = groundedBulletRegex.find(line) ?: continue
            val price = extractBrlPrice(m.groupValues[2])
            if (price.isNotBlank()) putEntry(m.groupValues[1], price)
        }

        for (seg in supportSegments) {
            val segPrice = extractBrlPrice(seg)
            if (segPrice.isNotBlank()) {
                val titlePart = seg.substringBefore("R$").trim(' ', ':', '-', '•', '.')
                if (titlePart.length >= 6) putEntry(titlePart, segPrice)
            }
        }

        return result.entries.map { it.key to it.value }
    }

    fun parseNextDataPriceCatalog(html: String): List<Pair<String, String>> {
        return parseNextDataMediaAndPriceCatalog(html).first
    }

    /**
     * Parses Zoom / Buscapé __NEXT_DATA__ JSON once to extract both verified BRL market prices
     * and verified titled product photos (zero extra network requests, 100% accessible from BR IPs).
     */
    fun parseNextDataMediaAndPriceCatalog(html: String): Pair<List<Pair<String, String>>, List<SearchEngineImageHit>> {
        if (html.isBlank()) return emptyList<Pair<String, String>>() to emptyList()
        val marker = "__NEXT_DATA__"
        val idx = html.indexOf(marker)
        if (idx < 0) return emptyList<Pair<String, String>>() to emptyList()
        val startBrace = html.indexOf('{', idx)
        if (startBrace < 0) return emptyList<Pair<String, String>>() to emptyList()
        val jsonSlice = extractBalancedJsonObject(html, startBrace, maxChars = 1_800_000)
            ?: return emptyList<Pair<String, String>>() to emptyList()
        val root = runCatching { JSONObject(jsonSlice) }.getOrNull()
            ?: return emptyList<Pair<String, String>>() to emptyList()

        val foundPrices = LinkedHashMap<String, Pair<String, String>>()
        val foundImages = LinkedHashMap<String, SearchEngineImageHit>()

        fun extractNodeImg(obj: JSONObject): String {
            val directKeys = listOf("thumbnail", "image", "imageUrl", "image_url", "img", "photo", "mainImage")
            for (k in directKeys) {
                val raw = obj.opt(k)
                when (raw) {
                    is String -> {
                        val norm = HtmlTextDecoder.normalizeImageUrl(raw)
                        if (norm.startsWith("https://")) return norm
                    }
                    is JSONObject -> {
                        val nested = raw.optString("url", "")
                            .ifBlank { raw.optString("src", "") }
                            .ifBlank { raw.optString("medium", "") }
                            .ifBlank { raw.optString("large", "") }
                        val norm = HtmlTextDecoder.normalizeImageUrl(nested)
                        if (norm.startsWith("https://")) return norm
                    }
                }
            }
            val imagesArr = obj.optJSONArray("images") ?: obj.optJSONArray("thumbnails")
            if (imagesArr != null && imagesArr.length() > 0) {
                val first = imagesArr.opt(0)
                val raw = when (first) {
                    is String -> first
                    is JSONObject -> first.optString("url", "").ifBlank { first.optString("src", "") }
                    else -> ""
                }
                val norm = HtmlTextDecoder.normalizeImageUrl(raw)
                if (norm.startsWith("https://")) return norm
            }
            return ""
        }

        fun walk(node: Any?, depth: Int, counter: IntArray) {
            if (depth > 16 || (foundPrices.size >= 60 && foundImages.size >= 60) || counter[0] > 40_000 || node == null) return
            counter[0]++
            when (node) {
                is JSONObject -> {
                    val rawName = node.optString("name", "")
                        .ifBlank { node.optString("title", "") }
                        .trim()
                    if (rawName.length >= 6 && !looksLikeUrlOrDomain(rawName)) {
                        val cleanTitle = cleanText(rawName)
                        val normKey = TextNormalizer.normalize(cleanTitle)
                        val priceVal = nextDataPriceKeys
                            .firstNotNullOfOrNull { key ->
                                node.optDouble(key, 0.0).takeIf { it.isFinite() && it >= 12.0 && it < 500_000.0 }
                            } ?: node.optJSONObject("price")?.optDouble("value", 0.0)
                            ?.takeIf { it.isFinite() && it >= 12.0 && it < 500_000.0 }
                            ?: 0.0
                        if (priceVal >= 12.0 && normKey.length >= 6) {
                            val formatted = PriceFormatter.formatBrl(priceVal)
                            if (formatted.isNotBlank()) {
                                foundPrices.putIfAbsent(normKey, cleanTitle to formatted)
                            }
                        }
                        if (normKey.length >= 6 && foundImages.size < 60) {
                            val imgUrl = extractNodeImg(node)
                            if (imgUrl.isNotBlank()) {
                                foundImages.putIfAbsent(
                                    normKey,
                                    SearchEngineImageHit(
                                        title = cleanTitle,
                                        pageUrl = "",
                                        imageUrl = imgUrl,
                                        thumbUrl = ""
                                    )
                                )
                            }
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()), depth + 1, counter)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i), depth + 1, counter)
                    }
                }
            }
        }
        walk(root, depth = 0, counter = intArrayOf(0))
        return foundPrices.values.toList() to foundImages.values.toList()
    }

    fun extractFirstValidJsonArray(rawText: String): JSONArray? {
        if (rawText.isBlank()) return null
        val cleaned = rawText
            .replace(markdownCodeFenceJsonRegex, "")
            .replace("```", "")
        var idx = cleaned.indexOf('[')
        while (idx >= 0) {
            val slice = extractBalancedJsonBracket(cleaned, idx, openChar = '[', closeChar = ']', maxChars = 40_000)
            if (slice != null) {
                val parsed = runCatching { JSONArray(slice) }.getOrNull()
                if (parsed != null && (slice.contains('{') || parsed.length() == 0)) {
                    return parsed
                }
            }
            idx = cleaned.indexOf('[', idx + 1)
        }
        return null
    }

    fun unwrapBingRedirectUrl(href: String): String {
        if (!href.contains("bing.com/ck/a", true)) return href
        val uParam = runCatching { Uri.parse(href).getQueryParameter("u") }.getOrNull().orEmpty()
        if (uParam.startsWith("http", true)) return uParam
        if (uParam.contains("http%3a", true)) {
            val decoded = runCatching { URLDecoder.decode(uParam, "UTF-8") }.getOrNull().orEmpty()
            if (decoded.startsWith("http", true)) return decoded
        }
        if (uParam.startsWith("a1") && uParam.length > 4) {
            val decoded = runCatching {
                String(Base64.decode(uParam.substring(2), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            }.getOrNull().orEmpty()
            if (decoded.startsWith("http", true)) return decoded
        }
        return href
    }

    fun unwrapDuckDuckGoUrl(href: String): String {
        val full = if (href.startsWith("//")) "https:$href" else href
        if (full.contains("duckduckgo.com/l/", true)) {
            val uddg = runCatching { Uri.parse(full).getQueryParameter("uddg") }.getOrNull().orEmpty()
            if (uddg.startsWith("http", true)) return uddg
        }
        return full
    }

    fun unwrapYahooUrl(href: String): String {
        if (href.contains("r.search.yahoo.com", true)) {
            val ru = yahooRuRegex.find(href)?.groupValues?.getOrNull(1)
            if (!ru.isNullOrBlank()) {
                val decoded = runCatching { URLDecoder.decode(ru, "UTF-8") }.getOrNull().orEmpty()
                if (decoded.startsWith("http", true)) return decoded
            }
        }
        return href
    }

    private fun extractBalancedJsonObject(text: String, startIndex: Int, maxChars: Int): String? {
        return extractBalancedJsonBracket(text, startIndex, openChar = '{', closeChar = '}', maxChars = maxChars)
    }

    private fun extractBalancedJsonBracket(
        text: String,
        startIndex: Int,
        openChar: Char,
        closeChar: Char,
        maxChars: Int
    ): String? {
        var depth = 0
        var inString = false
        var escaped = false
        val limit = (startIndex + maxChars).coerceAtMost(text.length)
        for (i in startIndex until limit) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == openChar) depth++
                else if (ch == closeChar) {
                    depth--
                    if (depth == 0) return text.substring(startIndex, i + 1)
                }
            }
        }
        return null
    }
}
