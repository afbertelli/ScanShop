package com.example.data.network

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.example.data.model.PriceFormatter
import com.example.data.model.RawStoreCandidate
import com.example.data.model.StoreType
import com.example.data.search.HtmlTextDecoder
import com.example.ui.util.DeepLinkUtils
import java.lang.ref.WeakReference
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
 * Extracts live Mercado Livre search/promo listings via an invisible 1x1 Android Chromium WebView
 * attached behind the Compose root in the active Activity window so Android 14/15 phones do not
 * throttle JS hydration or IntersectionObserver lazy-loading, while blocking image downloads for
 * speed and backfilling lazy-placeholder images directly from __PRELOADED_STATE__ / __NORDIC_STATE__.
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
                    ensureAttachedToHostWindow(webView)
                    runCatching { webView.onResume() }
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun getOrCreateWebView(context: Context): WebView? {
        reusableWebView?.let { return it }
        return runCatching {
            WebView(context.applicationContext).apply {
                alpha = 0f
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isLongClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setOnTouchListener { _, _ -> false }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.loadsImagesAutomatically = true
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
            }.also {
                reusableWebView = it
                attachedWebViewRef = WeakReference(it)
            }
        }.onFailure { err ->
            Log.w(WEBVIEW_TAG, "Unable to create offscreen WebView: ${err.message}")
        }.getOrNull()
    }

    private fun ensureAttachedToHostWindow(webView: WebView) {
        val activity = hostActivityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching {
            val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (webView.parent !== contentRoot) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.visibility = View.VISIBLE
                webView.alpha = 0f
                // Insert at index 0 behind ComposeView with full viewport so IntersectionObserver
                // and responsive Polycard hydration execute normally without intercepting touches.
                contentRoot.addView(
                    webView,
                    0,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            attachedWebViewRef = WeakReference(webView)
        }
    }

    fun getCookiesForUrl(url: String): String {
        if (appContext == null || url.isBlank()) return ""
        return runCatching {
            CookieManager.getInstance().getCookie(url).orEmpty().trim()
        }.getOrDefault("")
    }

    private suspend fun loadAndExtract(
        webView: WebView,
        url: String,
        query: String,
        domainHint: String
    ): List<RawStoreCandidate> {
        val loaded = awaitPageLoad(webView, url)
        if (!loaded) return emptyList()

        runCatching {
            webView.evaluateJavascript(
                "window.scrollBy(0, 720); window.dispatchEvent(new Event('scroll')); window.dispatchEvent(new Event('resize'));",
                null
            )
        }
        delay(450L)
        if (isBlockedUrl(webView.url.orEmpty())) return emptyList()

        val firstRaw = evaluateExtractorJs(webView)
        if (firstRaw.isNotBlank()) {
            val firstObj = runCatching { JSONObject(firstRaw) }.getOrNull()
            if (firstObj?.optBoolean("blocked", false) == true) return emptyList()
            val firstParsed = mergeExtractedJsonAndHtml(firstRaw, query, domainHint)
            if (firstParsed.isNotEmpty() && firstParsed.count { it.imageUrl.isNotBlank() && it.priceText.isNotBlank() } >= 6) {
                return firstParsed
            }
        }

        // Brief retry wait for late JS hydration / lazy images when DOM had few complete items on first tick
        runCatching {
            webView.evaluateJavascript(
                "window.scrollBy(0, 960); window.dispatchEvent(new Event('scroll')); window.dispatchEvent(new Event('resize'));",
                null
            )
        }
        delay(750L)
        if (isBlockedUrl(webView.url.orEmpty())) return emptyList()
        val secondRaw = evaluateExtractorJs(webView)
        if (secondRaw.isBlank()) {
            return if (firstRaw.isNotBlank()) mergeExtractedJsonAndHtml(firstRaw, query, domainHint) else emptyList()
        }
        val secondParsed = mergeExtractedJsonAndHtml(secondRaw, query, domainHint)
        return if (secondParsed.isNotEmpty()) secondParsed else {
            if (firstRaw.isNotBlank()) mergeExtractedJsonAndHtml(firstRaw, query, domainHint) else emptyList()
        }
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
                val lowerReq = reqUrl.lowercase()
                if (lowerReq.isNotBlank() &&
                    !lowerReq.startsWith("http://") &&
                    !lowerReq.startsWith("https://") &&
                    !lowerReq.startsWith("about:")
                ) {
                    // Block meli:// / intent:// / market:// redirects to the installed ML app
                    // without aborting the web listing load.
                    return true
                }
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
        val rootObj = runCatching { JSONObject(jsonPayload) }.getOrNull()
        val stateHtml = rootObj?.optString("stateHtml", "").orEmpty()
        val htmlSlice = rootObj?.optString("html", "").orEmpty()

        val stateCandidates = if (stateHtml.isNotBlank()) {
            MercadoLivreHtmlParser.parseListingHtml(stateHtml, query, domainHint)
        } else {
            emptyList()
        }
        val htmlCandidates = if (htmlSlice.isNotBlank()) {
            MercadoLivreHtmlParser.parseListingHtml(htmlSlice, query, domainHint)
        } else {
            emptyList()
        }
        if (stateCandidates.isEmpty() && htmlCandidates.isEmpty()) return jsonCandidates

        val merged = LinkedHashMap<String, RawStoreCandidate>()
        jsonCandidates.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }
        stateCandidates.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }
        htmlCandidates.forEach { item ->
            MercadoLivreHtmlParser.mergeCandidate(merged, item.copy(sourceRank = merged.size))
        }
        return merged.values.toList()
    }

    companion object {
        @Volatile
        private var hostActivityRef: WeakReference<Activity>? = null

        @Volatile
        private var attachedWebViewRef: WeakReference<WebView>? = null

        fun attachHostActivity(activity: Activity) {
            hostActivityRef = WeakReference(activity)
        }

        fun detachHostActivity(activity: Activity) {
            if (hostActivityRef?.get() === activity) {
                hostActivityRef = null
            }
            val wv = attachedWebViewRef?.get() ?: return
            runCatching {
                val parent = wv.parent as? ViewGroup
                if (parent != null && parent.context === activity) {
                    parent.removeView(wv)
                }
            }
        }

        private val EXTRACTOR_JS = """
            (function() {
              try {
                var href = location.href || '';
                if (href.indexOf('account-verification') !== -1 || href.indexOf('/login') !== -1) {
                  return JSON.stringify({ blocked: true, href: href, items: [], stateHtml: '', html: '' });
                }
                function normMlbId(str) {
                  if (!str) return '';
                  var m = String(str).match(/MLBU?-?\d{6,}/i);
                  return m ? m[0].replace(/-/g, '').toUpperCase() : '';
                }
                function buildMlPicUrl(picId) {
                  if (!picId) return '';
                  var s = String(picId).trim();
                  if (!s) return '';
                  if (s.indexOf('http://') === 0 || s.indexOf('https://') === 0) return s;
                  if (s.indexOf('//') === 0) return 'https:' + s;
                  if (/^[A-Za-z0-9_-]{8,}$/.test(s)) {
                    return 'https://http2.mlstatic.com/D_NQ_NP_' + s + '-O.jpg';
                  }
                  return '';
                }
                function formatBrlNum(val) {
                  var n = Number(val);
                  if (!isFinite(n) || n <= 0) return '';
                  var fixed = n.toFixed(2).replace('.', ',');
                  return 'R$ ' + fixed;
                }
                var stateById = {};
                var stateOrder = [];
                function upsertState(id, patch) {
                  var key = normMlbId(id);
                  if (!key) return;
                  var cur = stateById[key];
                  if (!cur) {
                    cur = { id: key, title: '', url: '', img: '', price: '' };
                    stateById[key] = cur;
                    stateOrder.push(key);
                  }
                  if (!cur.title && patch.title) cur.title = String(patch.title).trim();
                  if (!cur.url && patch.url) cur.url = String(patch.url).trim();
                  if ((!cur.img || cur.img.indexOf('mlstatic.com') === -1) && patch.img) cur.img = String(patch.img).trim();
                  if (!cur.price && patch.price) cur.price = String(patch.price).trim();
                }
                function inspectStateNode(node, depth) {
                  if (!node || typeof node !== 'object' || depth > 11) return;
                  if (Array.isArray(node)) {
                    var lim = Math.min(node.length, 160);
                    for (var a = 0; a < lim; a++) inspectStateNode(node[a], depth + 1);
                    return;
                  }
                  // Polycard structure (modern ML search)
                  if (node.metadata && (node.pictures || node.components)) {
                    var meta = node.metadata || {};
                    var rawUrl = meta.url || meta.permalink || '';
                    if (rawUrl && rawUrl.indexOf('http') !== 0) {
                      rawUrl = rawUrl.indexOf('/') === 0 ? ('https://www.mercadolivre.com.br' + rawUrl) : ('https://' + rawUrl);
                    }
                    var pid = meta.id || normMlbId(rawUrl);
                    var pTitle = '';
                    var pPrice = '';
                    var pImg = '';
                    if (node.pictures && Array.isArray(node.pictures.pictures) && node.pictures.pictures.length > 0) {
                      var pic0 = node.pictures.pictures[0];
                      pImg = buildMlPicUrl((pic0 && (pic0.id || pic0.url || pic0.src)) || '');
                    }
                    if (Array.isArray(node.components)) {
                      for (var c = 0; c < node.components.length; c++) {
                        var comp = node.components[c];
                        if (!comp) continue;
                        if (comp.type === 'title' && comp.title && comp.title.text && !pTitle) {
                          pTitle = comp.title.text;
                        }
                        if (comp.type === 'price' && comp.price && !pPrice) {
                          var cp = comp.price.current_price || comp.price;
                          if (cp && cp.value) pPrice = formatBrlNum(cp.value);
                        }
                      }
                    }
                    if (pid && (pImg || pPrice || pTitle)) {
                      upsertState(pid, { title: pTitle, url: rawUrl, img: pImg, price: pPrice });
                    }
                  }
                  // Classic ML item JSON structure
                  if (typeof node.id === 'string' && /^MLBU?\d{6,}/i.test(node.id)) {
                    var cUrl = node.permalink || node.url || '';
                    var cTitle = node.title || node.name || '';
                    var cImg = '';
                    if (Array.isArray(node.pictures) && node.pictures.length > 0) {
                      cImg = buildMlPicUrl(node.pictures[0].secure_url || node.pictures[0].url || node.pictures[0].id || '');
                    }
                    if (!cImg && node.thumbnail_id) cImg = buildMlPicUrl(node.thumbnail_id);
                    if (!cImg && (node.secure_thumbnail || node.thumbnail)) cImg = buildMlPicUrl(node.secure_thumbnail || node.thumbnail);
                    var cPrice = '';
                    if (typeof node.price === 'number') cPrice = formatBrlNum(node.price);
                    else if (node.price && typeof node.price.amount === 'number') cPrice = formatBrlNum(node.price.amount);
                    if (cImg || cPrice || cTitle) {
                      upsertState(node.id, { title: cTitle, url: cUrl, img: cImg, price: cPrice });
                    }
                  }
                  var keys = Object.keys(node);
                  for (var k = 0; k < keys.length; k++) {
                    var key = keys[k];
                    if (key === 'filters' || key === 'available_filters' || key === 'available_sorts' || key === 'breadcrumbs' || key === 'seo') continue;
                    var child = node[key];
                    if (child && typeof child === 'object') inspectStateNode(child, depth + 1);
                  }
                }
                function parseScriptStateById(elId) {
                  var el = document.getElementById(elId);
                  if (el && el.textContent && el.textContent.length < 3500000) {
                    try { inspectStateNode(JSON.parse(el.textContent), 0); } catch (ignored) {}
                  }
                }
                if (window.__PRELOADED_STATE__) {
                  try { inspectStateNode(window.__PRELOADED_STATE__, 0); } catch (ignored) {}
                } else {
                  parseScriptStateById('__PRELOADED_STATE__');
                }
                if (window.__NORDIC_STATE__) {
                  try { inspectStateNode(window.__NORDIC_STATE__, 0); } catch (ignored) {}
                } else {
                  parseScriptStateById('__NORDIC_STATE__');
                }
                var ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (var ld = 0; ld < ldScripts.length; ld++) {
                  try {
                    var parsedLd = JSON.parse(ldScripts[ld].textContent || '');
                    var ldArr = Array.isArray(parsedLd) ? parsedLd : (parsedLd && parsedLd['@graph'] ? parsedLd['@graph'] : [parsedLd]);
                    for (var li = 0; li < ldArr.length; li++) {
                      var prod = ldArr[li];
                      if (!prod || typeof prod !== 'object') continue;
                      var ldUrl = prod.url || (prod.offers && prod.offers.url) || '';
                      var ldId = normMlbId(ldUrl || prod.sku || prod.productID || '');
                      var ldImg = Array.isArray(prod.image) ? prod.image[0] : (prod.image || '');
                      var ldPrice = (prod.offers && prod.offers.price) ? formatBrlNum(prod.offers.price) : '';
                      if (ldId && (ldImg || ldPrice)) {
                        upsertState(ldId, { title: prod.name || '', url: ldUrl, img: ldImg, price: ldPrice });
                      }
                    }
                  } catch (ignoredLd) {}
                }
                function pickUrlFromSrcset(val) {
                  if (!val) return '';
                  var parts = val.split(',');
                  for (var p = parts.length - 1; p >= 0; p--) {
                    var u = parts[p].trim().split(/\s+/)[0] || '';
                    if (u && u.indexOf('data:image') !== 0 && u.indexOf('frontend-assets') === -1 && u.indexOf('/badges/') === -1) {
                      return u;
                    }
                  }
                  return '';
                }
                function pickCardImg(card) {
                  var imgs = card.querySelectorAll('img.poly-component__picture, img.ui-search-result-image__element, picture img, img');
                  for (var k = 0; k < imgs.length; k++) {
                    var im = imgs[k];
                    var cand = im.getAttribute('data-src') || im.getAttribute('data-lazy') || im.getAttribute('data-zoom') || im.getAttribute('src') || '';
                    if (cand.indexOf('data:image') === 0 || cand.indexOf('frontend-assets') !== -1 || cand.indexOf('/badges/') !== -1 || cand.indexOf('.svg') !== -1) {
                      cand = pickUrlFromSrcset(im.getAttribute('data-srcset') || im.getAttribute('srcset') || '');
                    }
                    if (cand && cand.indexOf('data:image') !== 0 && cand.indexOf('frontend-assets') === -1 && cand.indexOf('/badges/') === -1 && cand.indexOf('.svg') === -1) {
                      return cand;
                    }
                  }
                  var sources = card.querySelectorAll('picture source');
                  for (var s = 0; s < sources.length; s++) {
                    var srcUrl = pickUrlFromSrcset(sources[s].getAttribute('data-srcset') || sources[s].getAttribute('srcset') || '');
                    if (srcUrl) return srcUrl;
                  }
                  return '';
                }
                var cards = document.querySelectorAll('.poly-card, li.ui-search-layout__item, div.ui-search-result__wrapper, .andes-card.poly-card');
                var items = [];
                var seen = {};
                var seenIds = {};
                for (var i = 0; i < cards.length && items.length < 56; i++) {
                  var card = cards[i];
                  var linkEl = card.querySelector('a.poly-component__title, a.ui-search-link, a.ui-search-result__link, h2 a, h3 a, a[href*="/p/MLB"], a[href*="/up/MLB"], a[href*="produto.mercadolivre"], a[href*="MLB-"]');
                  if (!linkEl) continue;
                  var u = linkEl.href || linkEl.getAttribute('href') || '';
                  if (!u || seen[u]) continue;
                  seen[u] = true;
                  var cardId = normMlbId(u);
                  if (cardId) seenIds[cardId] = true;
                  var st = cardId ? stateById[cardId] : null;
                  var title = (linkEl.innerText || linkEl.textContent || linkEl.getAttribute('title') || '').trim();
                  if (title.length < 5) {
                    var altHeading = card.querySelector('h2, h3, .poly-component__title');
                    if (altHeading) title = (altHeading.innerText || altHeading.textContent || '').trim();
                  }
                  if (title.length < 5 && st && st.title) title = st.title;
                  var priceBox = card.querySelector('.poly-price__current .andes-money-amount:not(.andes-money-amount--previous), .poly-component__price .andes-money-amount:not(.andes-money-amount--previous), .ui-search-price__second-line .andes-money-amount:not(.andes-money-amount--previous), .ui-search-price .andes-money-amount:not(.andes-money-amount--previous)');
                  if (!priceBox) {
                    var allAmounts = card.querySelectorAll('.andes-money-amount');
                    for (var j = 0; j < allAmounts.length; j++) {
                      var candidateBox = allAmounts[j];
                      if (candidateBox.classList.contains('andes-money-amount--previous') ||
                          candidateBox.closest('.poly-price__installments, .ui-search-price__subtitles, .andes-money-amount--previous, s, del, .ui-search-price__part--small')) {
                        continue;
                      }
                      priceBox = candidateBox;
                      break;
                    }
                  }
                  var priceStr = '';
                  if (priceBox) {
                    var frac = priceBox.querySelector('.andes-money-amount__fraction, .price-tag-fraction');
                    var cents = priceBox.querySelector('.andes-money-amount__cents, .price-tag-cents');
                    if (frac && frac.textContent) {
                      var f = frac.textContent.replace(/[^0-9]/g, '');
                      var c = (cents && cents.textContent) ? cents.textContent.replace(/[^0-9]/g, '').slice(0, 2) : '00';
                      if (f.length > 0) {
                        priceStr = 'R$ ' + f + ',' + (c.length === 1 ? c + '0' : (c.length === 0 ? '00' : c));
                      }
                    } else {
                      var ariaP = priceBox.getAttribute('aria-label') || '';
                      if (ariaP.toLowerCase().indexOf('reais') !== -1) priceStr = ariaP;
                    }
                  }
                  if (!priceStr) {
                    var legacyFrac = card.querySelector('.price-tag-fraction');
                    var legacyCents = card.querySelector('.price-tag-cents');
                    if (legacyFrac && legacyFrac.textContent && !legacyFrac.closest('s, del, .poly-price__installments')) {
                      var lf = legacyFrac.textContent.replace(/[^0-9]/g, '');
                      var lc = (legacyCents && legacyCents.textContent) ? legacyCents.textContent.replace(/[^0-9]/g, '').slice(0, 2) : '00';
                      if (lf.length > 0) {
                        priceStr = 'R$ ' + lf + ',' + (lc.length === 1 ? lc + '0' : (lc.length === 0 ? '00' : lc));
                      }
                    }
                  }
                  if (!priceStr) {
                    var itemPropPrice = card.querySelector('[itemprop="price"]');
                    if (itemPropPrice) {
                      var rawProp = (itemPropPrice.getAttribute('content') || itemPropPrice.textContent || '').trim();
                      if (rawProp) priceStr = rawProp;
                    }
                  }
                  var imgUrl = pickCardImg(card);
                  // Backfill lazy-load placeholder images or missing prices directly from JS state map!
                  if (!imgUrl && st && st.img) imgUrl = st.img;
                  if (!priceStr && st && st.price) priceStr = st.price;
                  if (title.length >= 5) {
                    items.push({ title: title, url: u, price: priceStr, img: imgUrl });
                  }
                }
                // Append any additional complete items from JS state if DOM did not hydrate all cards yet
                for (var si = 0; si < stateOrder.length && items.length < 56; si++) {
                  var sk = stateOrder[si];
                  if (seenIds[sk]) continue;
                  var se = stateById[sk];
                  if (se && se.title && se.title.length >= 5 && se.url && !seen[se.url]) {
                    seen[se.url] = true;
                    seenIds[sk] = true;
                    items.push({ title: se.title, url: se.url, price: se.price || '', img: se.img || '' });
                  }
                }
                var stateHtml = '';
                var stateScript = document.getElementById('__PRELOADED_STATE__') || document.getElementById('__NORDIC_STATE__');
                if (stateScript && stateScript.textContent) {
                  var rawState = stateScript.textContent;
                  if (rawState.length <= 1600000) {
                    stateHtml = '<script id="' + stateScript.id + '">' + rawState + '</' + 'script>';
                  }
                } else if (window.__PRELOADED_STATE__) {
                  try {
                    var strState = JSON.stringify(window.__PRELOADED_STATE__);
                    if (strState && strState.length <= 1600000) {
                      stateHtml = '<script id="__PRELOADED_STATE__">' + strState + '</' + 'script>';
                    }
                  } catch (ignoredStateErr) {}
                } else if (window.__NORDIC_STATE__) {
                  try {
                    var strNordic = JSON.stringify(window.__NORDIC_STATE__);
                    if (strNordic && strNordic.length <= 1600000) {
                      stateHtml = '<script id="__NORDIC_STATE__">' + strNordic + '</' + 'script>';
                    }
                  } catch (ignoredNordicErr) {}
                }
                var html = document.documentElement ? document.documentElement.outerHTML : '';
                if (html.length > 1600000) html = html.slice(0, 1600000);
                return JSON.stringify({ blocked: false, href: href, items: items, stateHtml: stateHtml, html: html });
              } catch (e) {
                return JSON.stringify({ blocked: false, href: '', items: [], stateHtml: '', html: '' });
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
                if (numeric > 0.0 && !rawPrice.contains("reais", ignoreCase = true)) {
                    PriceFormatter.formatBrl(numeric)
                } else {
                    MercadoLivreHtmlParser.extractBrlPrice(rawPrice)
                        .ifBlank { PriceFormatter.formatBrl(numeric) }
                }
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
