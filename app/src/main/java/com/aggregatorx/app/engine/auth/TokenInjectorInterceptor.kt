package com.aggregatorx.app.engine.auth

import com.aggregatorx.app.data.model.AuthTokenEntity
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that auto-injects the best captured token for the
 * outbound request's host and reports back to the [TokenStore] based on
 * the response.
 *
 * Behavior:
 *  - If the request **already** has the target header set, we leave it alone.
 *    This lets call-sites that explicitly want anonymous behavior force it
 *    by setting `Authorization: ` on the request before sending.
 *  - On 2xx response → [TokenStore.reportSuccess].
 *  - On 401/403     → [TokenStore.reportFailure] (which will mark the token
 *    FAILED and evict it from the cache so we don't keep poisoning requests).
 */
class TokenInjectorInterceptor(
    private val store: TokenStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host.lowercase()

        val candidate: AuthTokenEntity? = store.bestTokenForHost(host)
        val request = if (candidate != null && original.header(candidate.headerName) == null) {
            val headerValue = if (candidate.isBearer) "Bearer ${candidate.value}" else candidate.value
            original.newBuilder()
                .header(candidate.headerName, headerValue)
                .build()
        } else {
            original
        }

        val response = chain.proceed(request)

        if (candidate != null && request !== original) {
            // Only report when we actually injected — otherwise we'd score
            // tokens against requests they had nothing to do with.
            when (response.code) {
                in 200..299 -> store.reportSuccess(candidate)
                401, 403    -> store.reportFailure(candidate, response.code)
                in 400..499 -> store.reportFailure(candidate, response.code)
                // 5xx and network errors → don't blame the token.
            }
        }

        return response
    }
}
