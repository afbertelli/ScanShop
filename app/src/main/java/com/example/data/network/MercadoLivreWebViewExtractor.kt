package com.example.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.PriceFormatter
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.data.search.HtmlTextDecoder
import com.example.ui.util.DeepLinkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

private const val WEBVIEW_TAG = "MlWebViewExtractor"

/**
 * Extracts live Mercado Livre search/promo listings via an offscreen Android Chromium WebView
 * so JS-rendered DOM prices, __PRELOADED_STATE__, and mlstatic.com images load cleanly with
 * real browser TLS fingerprinting and cookies while blocking image network fetches for speed.
 *
 * When [appContext] is null (e.g., JVM unit tests) or WebView is unavailable, degrades cleanly
 * to an empty list without throwing.
 */
internal class MercadoLivreWebViewExtractor(
    private val appContext: Context?
) {
    private val extractMutex = Mutex()
    private var reusableWebView: WebView? = null

    suspend fun fetchListingCandidates(
        url: String,
        query: String,
        domainHint: String,
        timeoutMs: Long = 9_500L
    ): List<RawStoreCandidate> {
        val ctx = appContext ?: return emptyList()
        if (url.isBlank()) return emptyList()

        return try {
            extractMutex.withLock {
                withContext(Dispatchers.Main) {
                    val webView = getOrCreateWebView(ctx) ?: return@withContext emptyList()
                    try {
                        withTimeoutOrNull(timeoutMs) {
                            loadAndExtract(
                                webView = webView,
                                url = url,
                                query = query,
                                domainHint = domainHint
                            )
                        }.orEmpty()
                    } finally {
                        runCatching {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(WEBVIEW_TAG, "WebView extraction skipped: ${e.message}")
            emptyList()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(context: Context): WebView? {
        reusableWebView?.let { return it }
        return runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                val baseUa = settings.userAgentString.orEmpty()
                if (baseUa.isNotBlank()) {
                    settings.userAgentString = baseUa
                        .replace("; wv)", ")")
                        .replace("; wv ", "; ")
                        .replace("Version/4.0 ", "")
                        .trim()
                }
                runCatching {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                }
                runCatching {
                    layout(0, 0, 1080, 1920)
                }
            }.also { reusableWebView = it }
        }.onFailure { err ->
            Log.w(WEBVIEW_TAG, "Unable to create offscreen WebView: ${err.message}")
        }.getOrNull()
    }

    private suspend fun loadAndExtract(
        webView: WebView,
        url: String,
        query: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        val loaded = awaitPageLoad(webView, url)
        if (!loaded) return emptyList()

        delay(450L)
        if (isBlockedUrl(webView.url.orEmpty())) return emptyList()

        val firstRaw = evaluateExtractorJs(webView)
        if (firstRaw.isNotBlank()) {
            val firstObj = runCatching { JSONObject(firstRaw) }.getOrNull()
            if (firstObj?.optBoolean("blocked", false) == true) return emptyList()
            val firstParsed = mergeExtractedJsonAndHtml(firstRaw, query, domainHint)
            if (firstParsed.isNotEmpty()) {
                return firstParsed
            }
        }

        // Brief retry wait for late JS hydration when DOM had 0 items on first tick
        delay(800L)
        if (isBlockedUrl(webView.url.orEmpty())) return emptyList()
        val secondRaw = evaluateExtractorJs(webView)
        if (secondRaw.isBlank()) return emptyList()
        return mergeExtractedJsonAndHtml(secondRaw, query, domainHint)
    }

    private suspend fun awaitPageLoad(
        webView: WebView,
        targetUrl: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        var finished = false
        fun finishOnce(ok: Boolean) {
            if (!finished && cont.isActive) {
                finished = true
                cont.resume(ok)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val reqUrl = request?.url?.toString().orEmpty()
                if (request?.isForMainFrame == true && isBlockedUrl(reqUrl)) {
                    finishOnce(false)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val current = loadedUrl.orEmpty()
                if (current.isBlank() || current.startsWith("about:", ignoreCase = true)) return
                if (isBlockedUrl(current)) {
                    finishOnce(false)
                } else {
                    finishOnce(true)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    finishOnce(false)
                }
            }
        }

        cont.invokeOnCancellation {
            runCatching { webView.stopLoading() }
        }
        webView.loadUrl(targetUrl)
    }

    private suspend fun evaluateExtractorJs(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(EXTRACTOR_JS) { rawResult ->
                if (!cont.isActive) return@evaluateJavascript
                val decoded = decodeJsEvaluationString(rawResult)
                cont.resume(decoded)
            }
        }

    private fun decodeJsEvaluationString(rawResult: String?): String {
        if (rawResult.isNullOrBlank() || rawResult == "null") return ""
        return runCatching {
            JSONArray("[$rawResult]").optString(0, "")
        }.getOrDefault("")
    }

    private fun isBlockedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("account-verification") ||
            lower.contains("auth.mercadolivre") ||
            lower.contains("/login") ||
            lower.contains("/gz/account-verification")
    }

    private fun mergeExtractedJsonAndHtml(
        jsonPayload: String,
        query: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        val jsonCandidates = parseWebViewExtractedJson(jsonPayload, query, domainHint)
        val htmlSlice = runCatching { JSONObject(jsonPayload).optString("html", "") }.getOrDefault("")
        val htmlCandidates = if (htmlSlice.isNotBlank()) {
            MercadoLivreHtmlParser.parseListingHtml(htmlSlice, query, domainHint)
        } else {
            emptyList()
        }
        if (htmlCandidates.isEmpty()) return jsonCandidates
        if (jsonCandidates.isEmpty()) return htmlCandidates

        val merged = LinkedHashMap<String, RawStoreCandidate>()
        jsonCandidates.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }
        htmlCandidates.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }
        return merged.values.toList()
    }

    companion object {
        private val EXTRACTOR_JS = """
            (function() {
              try {
                var href = location.href || '';
                if (href.indexOf('account-verification') !== -1 || href.indexOf('/login') !== -1) {
                  return JSON.stringify({ blocked: true, href: href, items: [], html: '' });
                }
                var cards = document.querySelectorAll('.poly-card, li.ui-search-layout__item, div.ui-search-result__wrapper, .andes-card.poly-card');
                var items = [];
                var seen = {};
                for (var i = 0; i < cards.length && items.length < 52; i++) {
                  var card = cards[i];
                  var linkEl = card.querySelector('a.poly-component__title, a.ui-search-link, a.ui-search-result__link, h2 a, h3 a, a[href*="/p/MLB"], a[href*="/up/MLB"], a[href*="produto.mercadolivre"], a[href*="MLB-"]');
                  if (!linkEl) continue;
                  var u = linkEl.href || linkEl.getAttribute('href') || '';
                  if (!u || seen[u]) continue;
                  seen[u] = true;
                  var title = (linkEl.innerText || linkEl.textContent || linkEl.getAttribute('title') || '').trim();
                  if (title.length < 5) {
                    var altHeading = card.querySelector('h2, h3, .poly-component__title');
                    if (altHeading) title = (altHeading.innerText || altHeading.textContent || '').trim();
                  }
                  var priceBox = card.querySelector('.poly-price__current .andes-money-amount, .ui-search-price__second-line .andes-money-amount');
                  if (!priceBox) {
                    var allAmounts = card.querySelectorAll('.andes-money-amount');
                    for (var j = 0; j < allAmounts.length; j++) {
                      var candidateBox = allAmounts[j];
                      if (candidateBox.closest('.poly-price__installments, .ui-search-price__subtitles, .andes-money-amount--previous, s, del, .ui-search-price__part--small')) {
                        continue;
                      }
                      priceBox = candidateBox;
                      break;
                    }
                  }
                  var priceStr = '';
                  if (priceBox) {
                    var frac = priceBox.querySelector('.andes-money-amount__fraction');
                    var cents = priceBox.querySelector('.andes-money-amount__cents');
                    if (frac && frac.textContent) {
                      var f = frac.textContent.replace(/[^0-9]/g, '');
                      var c = (cents && cents.textContent) ? cents.textContent.replace(/[^0-9]/g, '').slice(0, 2) : '00';
                      if (f.length > 0) {
                        priceStr = 'R$ ' + f + ',' + (c.length === 1 ? c + '0' : (c.length === 0 ? '00' : c));
                      }
                    }
                  }
                  var imgEl = card.querySelector('img.poly-component__picture, img.ui-search-result-image__element, img');
                  var imgUrl = '';
                  if (imgEl) {
                    imgUrl = imgEl.getAttribute('data-src') || imgEl.getAttribute('data-lazy') || imgEl.getAttribute('src') || '';
                    if (imgUrl.indexOf('data:image') === 0) {
                      imgUrl = imgEl.getAttribute('data-src') || imgEl.getAttribute('data-lazy') || '';
                    }
                  }
                  if (title.length >= 5) {
                    items.push({ title: title, url: u, price: priceStr, img: imgUrl });
                  }
                }
                var html = document.documentElement ? document.documentElement.outerHTML : '';
                if (html.length > 1800000) html = html.slice(0, 1800000);
                return JSON.stringify({ blocked: false, href: href, items: items, html: html });
              } catch (e) {
                return JSON.stringify({ blocked: false, href: '', items: [], html: '' });
              }
            })();
        """.trimIndent()
    }
}

internal fun parseWebViewExtractedJson(
    jsonPayload: String,
    query: String,
    domainHint: String
): List<RawStoreCandidate> {
    val trimmed = jsonPayload.trim()
    if (!trimmed.startsWith("{")) return emptyList()
    return runCatching {
        val root = JSONObject(trimmed)
        if (root.optBoolean("blocked", false)) return emptyList()
        val href = root.optString("href", "").lowercase()
        if (href.contains("account-verification") || href.contains("/login")) return emptyList()

        val arr = root.optJSONArray("items") ?: return emptyList()
        val collected = LinkedHashMap<String, RawStoreCandidate>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url", "").trim()
            val normalizedUrl = MercadoLivreHtmlParser.normalizeProductUrl(rawUrl)
            if (!MercadoLivreHtmlParser.isValidProductUrl(normalizedUrl)) continue

            val rawTitle = obj.optString("title", "").trim()
            val cleanTitle = MercadoLivreHtmlParser.sanitizeCandidateTitle(rawTitle, normalizedUrl)
            if (cleanTitle.length < 5) continue

            val rawPrice = obj.optString("price", "").trim()
            val formattedPrice = if (rawPrice.isNotBlank()) {
                val numeric = MercadoLivreHtmlParser.parseBrlAmount(rawPrice)
                if (numeric > 0.0) PriceFormatter.formatBrl(numeric)
                else MercadoLivreHtmlParser.extractBrlPrice(rawPrice)
            } else ""

            val rawImg = obj.optString("img", "").trim()
            val cleanImg = if (rawImg.startsWith("data:image", ignoreCase = true)) {
                ""
            } else {
                HtmlTextDecoder.normalizeImageUrl(rawImg)
            }

            MercadoLivreHtmlParser.mergeCandidate(
                collected,
                RawStoreCandidate(
                    id = MercadoLivreHtmlParser.canonicalCandidateId(normalizedUrl, "wv"),
                    store = StoreType.MERCADO_LIVRE,
                    title = cleanTitle,
                    imageUrl = cleanImg,
                    productUrl = normalizedUrl,
                    affiliateUrl = DeepLinkUtils.formatMercadoLivreAffiliateUrl(normalizedUrl),
                    priceText = formattedPrice,
                    categoryPath = domainHint,
                    domainId = domainHint,
                    sourceQueryVariant = query,
                    sourceRank = collected.size
                )
            )
        }
        collected.values.toList()
    }.getOrDefault(emptyList())
}
