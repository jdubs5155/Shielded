package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aggregatorx.app.engine.auth.TokenStore
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Container for everything a headless render produced — full DOM, the
 * video sources we found in the DOM, and any tokens we sniffed out of the
 * window context (auth headers, JWTs, csrf tokens, etc.).
 */
data class HeadlessExtraction(
    val html: String,
    val videoSources: List<String>,
    val tokens: Map<String, String>
)

/**
 * Hidden WebView based scraper. The WebView is created on the main thread,
 * navigated to the target URL, and then queried via injected JavaScript that
 * calls back into Kotlin via the `AggregatorBridge` JS interface.
 *
 * The bridge exposes three documented entry points used elsewhere in the
 * scraping pipeline:
 *  - `getHtml()`         → returns `document.documentElement.outerHTML`
 *  - `getVideoSources()` → returns a JSON array of <video>/<source>/HLS URLs
 *  - `extractTokens()`   → returns a JSON object with JWTs, csrf tokens,
 *                          and any `authorization`-flavoured globals it can
 *                          reach from the window context
 *
 * Each navigation gets a freshly-rotated User-Agent, and a 2–8 s
 * "human" delay is inserted before the navigation completes to make
 * traffic look less robotic.
 */
@Singleton
class HeadlessBrowserHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore
) {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    // Per-navigation deferred wired via the JS bridge so callbacks return
    // straight to the suspend function awaiting them.
    @Volatile private var htmlDeferred: CompletableDeferred<String>? = null
    @Volatile private var sourcesDeferred: CompletableDeferred<List<String>>? = null
    @Volatile private var tokensDeferred: CompletableDeferred<Map<String, String>>? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun getOrCreateWebView(): WebView = withContext(Dispatchers.Main) {
        webView ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = EngineUtils.getRandomUserAgent()
            // Stealth-ish defaults — minimize easy bot fingerprints.
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            addJavascriptInterface(JsBridge(), "AggregatorBridge")
            webView = this
        }.also { webView = it }
    }

    /**
     * Inner JS bridge. Methods are invoked from the injected JavaScript
     * payload below and route results to the matching CompletableDeferred.
     */
    private inner class JsBridge {
        @JavascriptInterface
        fun onHtml(html: String) {
            htmlDeferred?.complete(html)
        }

        @JavascriptInterface
        fun onVideoSources(jsonArray: String) {
            val list = parseJsonStringArray(jsonArray)
            sourcesDeferred?.complete(list)
        }

        @JavascriptInterface
        fun onTokens(jsonObject: String) {
            val map = parseJsonStringMap(jsonObject)
            tokensDeferred?.complete(map)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Navigate to [url] and return the rendered HTML once `onPageFinished` fires
     * (plus a small human delay).
     */
    suspend fun getHtml(url: String): String =
        navigateAndExtract(url).html

    /**
     * Navigate to [url] and harvest <video>, <source>, .m3u8, .mpd URLs from
     * the DOM and from any inline scripts.
     */
    suspend fun getVideoSources(url: String): List<String> =
        navigateAndExtract(url).videoSources

    /**
     * Navigate to [url] and pull out any JWTs / auth-flavoured tokens reachable
     * from the rendered page.
     */
    suspend fun extractTokens(url: String): Map<String, String> =
        navigateAndExtract(url).tokens

    /**
     * Single-pass navigation that performs all three extractions, useful when
     * a caller wants HTML and tokens together without paying for two loads.
     */
    suspend fun navigateAndExtract(url: String): HeadlessExtraction = withContext(Dispatchers.Main) {
        applyHumanDelay()

        val view = getOrCreateWebView()
        // Rotate the UA on every navigation so consecutive requests don't share
        // the same fingerprint — works around lightweight WAF heuristics.
        view.settings.userAgentString = EngineUtils.getRandomUserAgent()

        val htmlD    = CompletableDeferred<String>().also { htmlDeferred = it }
        val sourcesD = CompletableDeferred<List<String>>().also { sourcesDeferred = it }
        val tokensD  = CompletableDeferred<Map<String, String>>().also { tokensDeferred = it }

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                view?.evaluateJavascript(EXTRACTION_JS, null)
            }
        }
        view.loadUrl(url)

        // 30-second hard ceiling per extraction kind — beyond that we surface
        // empty results instead of hanging the scraper.
        val html    = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) { htmlD.await() } ?: ""
        val sources = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) { sourcesD.await() } ?: emptyList()
        val tokens  = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) { tokensD.await() } ?: emptyMap()

        // Auto-feed everything we found into the TokenStore. The store
        // dedupes / decodes / scores, so it's safe to call eagerly.
        if (tokens.isNotEmpty())   tokenStore.recordCapturedHeaders(url, tokens)
        if (html.isNotEmpty())     tokenStore.recordCapturedTokens(url, html)

        HeadlessExtraction(html = html, videoSources = sources, tokens = tokens)
    }

    /**
     * Backward-compatible alias for older callers that wanted just the
     * URLs of any extractable video sources.
     */
    suspend fun extractVideoUrls(url: String): List<String> = getVideoSources(url)

    /**
     * Click an element by CSS selector — used to dismiss popups, advance
     * paginators, or accept cookie banners.
     */
    fun clickElement(selector: String) {
        handler.post {
            webView?.evaluateJavascript(
                "(function(){var el=document.querySelector(${jsString(selector)});if(el){el.click();}})();",
                null
            )
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** Realistic 2-8 s human-like delay between requests. */
    private suspend fun applyHumanDelay() {
        delay(Random.nextLong(2_000L, 8_000L))
    }

    private fun parseJsonStringArray(jsonArray: String): List<String> = try {
        val arr = JSONArray(jsonArray)
        (0 until arr.length()).mapNotNull { i -> arr.optString(i)?.takeIf { it.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseJsonStringMap(jsonObject: String): Map<String, String> = try {
        val obj = JSONObject(jsonObject)
        obj.keys().asSequence().associateWith { key -> obj.optString(key, "") }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        private const val EXTRACTION_TIMEOUT_MS = 30_000L

        /**
         * One injected JS payload that fires three callbacks back to Kotlin.
         * Defensive everywhere — every step is wrapped in try/catch so a
         * single broken page doesn't take down the whole pipeline.
         */
        private val EXTRACTION_JS = """
            (function() {
              try {
                // --- HTML --------------------------------------------------
                try {
                  AggregatorBridge.onHtml(document.documentElement.outerHTML);
                } catch (e) {
                  AggregatorBridge.onHtml('');
                }

                // --- Video sources ----------------------------------------
                try {
                  var sources = [];
                  document.querySelectorAll('video, source').forEach(function(n) {
                    var s = n.getAttribute('src') || n.src;
                    if (s) sources.push(s);
                  });
                  // Sniff inline scripts for HLS / DASH / MP4 URLs.
                  var html = document.documentElement.outerHTML;
                  var re = /(https?:\/\/[^\s'"<>]+\.(?:m3u8|mpd|mp4)(?:\?[^\s'"<>]*)?)/g;
                  var m;
                  while ((m = re.exec(html)) !== null) sources.push(m[1]);
                  // De-dup
                  sources = sources.filter(function(v, i, a) { return a.indexOf(v) === i; });
                  AggregatorBridge.onVideoSources(JSON.stringify(sources));
                } catch (e) {
                  AggregatorBridge.onVideoSources('[]');
                }

                // --- Tokens (JWT, csrf, authorization) --------------------
                try {
                  var tokens = {};
                  // Scan meta tags
                  document.querySelectorAll('meta[name], meta[property]').forEach(function(m) {
                    var k = (m.getAttribute('name') || m.getAttribute('property') || '').toLowerCase();
                    if (k && (k.indexOf('csrf') !== -1 || k.indexOf('token') !== -1 || k.indexOf('auth') !== -1)) {
                      tokens[k] = m.getAttribute('content') || '';
                    }
                  });
                  // Scan input[type=hidden]
                  document.querySelectorAll('input[type=hidden]').forEach(function(i) {
                    var n = (i.getAttribute('name') || '').toLowerCase();
                    if (n && (n.indexOf('csrf') !== -1 || n.indexOf('token') !== -1 || n.indexOf('auth') !== -1)) {
                      tokens[n] = i.value || '';
                    }
                  });
                  // Scan reachable globals
                  ['authToken','jwt','accessToken','idToken','csrfToken'].forEach(function(name) {
                    try {
                      var v = window[name];
                      if (typeof v === 'string' && v.length > 0) tokens[name] = v;
                    } catch (_) {}
                  });
                  // JWT regex sweep through inline scripts.
                  var jwtRe = /eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+/g;
                  var jm; var jwtIdx = 0;
                  var html2 = document.documentElement.outerHTML;
                  while ((jm = jwtRe.exec(html2)) !== null) {
                    tokens['jwt_' + (jwtIdx++)] = jm[0];
                  }
                  AggregatorBridge.onTokens(JSON.stringify(tokens));
                } catch (e) {
                  AggregatorBridge.onTokens('{}');
                }
              } catch (outer) {
                AggregatorBridge.onHtml('');
                AggregatorBridge.onVideoSources('[]');
                AggregatorBridge.onTokens('{}');
              }
            })();
        """.trimIndent()
    }
}
