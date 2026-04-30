package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A headless (off-screen) WebView helper to resolve dynamic JavaScript-heavy sites.
 */
@Singleton
class HeadlessBrowserHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getOrCreateWebView(): WebView = withContext(Dispatchers.Main) {
        if (webView == null) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // Note: Basic settings only. 
                    // Specialized stealth/header spoofing is not included.
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                    }
                }
            }
        }
        webView!!
    }

    /**
     * Navigates to a URL and extracts the fully rendered HTML.
     */
    suspend fun getHtml(url: String): String = suspendCancellableCoroutine { continuation ->
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        mainHandler.post {
            viewModelScopeLaunch {
                val view = getOrCreateWebView()
                
                view.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun processHtml(html: String) {
                        if (continuation.isActive) continuation.resume(html)
                    }
                }, "HTMLOUT")

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.loadUrl("javascript:window.HTMLOUT.processHtml('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>');")
                    }
                }
                
                view.loadUrl(url)
            }
        }
    }

    private fun viewModelScopeLaunch(block: suspend () -> Unit) {
        // Internal helper to ensure WebView operations stay on Main Thread
        block
    }

    /**
     * Executes a specific JS script and returns the result.
     * Useful for clicking elements or finding hidden tokens.
     */
    suspend fun executeScript(script: String): String = suspendCancellableCoroutine { continuation ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView?.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result ?: "")
            }
        }
    }
}
