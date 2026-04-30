package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeadlessBrowserHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        if (webView == null) {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-A326U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36"
                // Stealth: Disable webdriver flags
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHtmlExtracted(html: String, deferred: CompletableDeferred<String>) {
                        deferred.complete(html)
                    }
                }, "AndroidInterface")
            }
        }
        return webView!!
    }

    /**
     * Executes headless navigation and returns full DOM HTML after JS execution.
     */
    suspend fun getHtml(url: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        val view = getOrCreateWebView()

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Execute JS to grab the DOM and pass it back to our interface
                view?.evaluateJavascript(
                    "(function() { return document.documentElement.outerHTML; })();"
                ) { html ->
                    deferred.complete(html.trim('"').replace("\\u003C", "<").replace("\\\"", "\""))
                }
            }
        }
        view.loadUrl(url)
        deferred.await()
    }

    /**
     * Advanced: Maps the DOM to identify potential API endpoints or hidden video sources.
     */
    suspend fun discoverEndpoints(): List<String> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<List<String>>()
        val script = """
            (function() {
                val links = Array.from(document.querySelectorAll('a, script, source'));
                return links.map(l => l.src || l.href).filter(link => link && link.includes('api') || link.includes('.m3u8'));
            })();
        """.trimIndent()

        getOrCreateWebView().evaluateJavascript(script) { result ->
            // Parsing the JS array string returned
            deferred.complete(result.split(",").map { it.trim('"').replace("[", "").replace("]", "") })
        }
        deferred.await()
    }

    /**
     * Simulates human-like interaction for pagination or bypassing simple gates.
     */
    fun clickElement(selector: String) {
        handler.post {
            getOrCreateWebView().evaluateJavascript(
                "document.querySelector('$selector')?.click();", null
            )
        }
    }
}
