package com.aggregatorx.app.di

import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.engine.auth.TokenInjectorInterceptor
import com.aggregatorx.app.engine.auth.TokenStore
import com.aggregatorx.app.engine.network.AuditInterceptor
import com.aggregatorx.app.engine.network.ProxyVPNEngine
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlin.random.Random

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Global OkHttpClient. Wires in:
     *  - AuditInterceptor : audit-log every request/response (success + error).
     *  - HttpLoggingInterceptor : header-level logs in dev.
     *  - StealthInterceptor : per-request UA rotation and Netherlands-style
     *    geo headers via [ProxyVPNEngine].
     *  - HumanDelayInterceptor : 250–1500 ms jitter on outbound calls so a
     *    burst of provider scrapes doesn't look like a robotic fan-out.
     *  - Proxy : rotated through [ProxyVPNEngine] when one is currently active.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        auditLogDao: AuditLogDao,
        proxyEngine: ProxyVPNEngine,
        tokenStore: TokenStore
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        val builder = OkHttpClient.Builder()
            // Order matters: stealth headers first (UA / geo) → token injection
            // (so the captured Authorization is layered on top of the stealth
            // request) → human delay → logging → audit (last, sees final state).
            .addInterceptor(StealthInterceptor(proxyEngine))
            .addInterceptor(TokenInjectorInterceptor(tokenStore))
            .addInterceptor(HumanDelayInterceptor())
            .addInterceptor(loggingInterceptor)
            .addInterceptor(AuditInterceptor(auditLogDao))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        // The proxy itself is selected at OkHttp build time. The
        // StealthInterceptor will still cycle the UA on every request, but
        // the underlying socket stays bound to a single proxy for the
        // lifetime of this client. Rotation happens on engine reinit.
        proxyEngine.getCurrentProxy()?.let { cfg ->
            val type = if (cfg.type.name == "SOCKS5") Proxy.Type.SOCKS else Proxy.Type.HTTP
            builder.proxy(Proxy(type, InetSocketAddress(cfg.host, cfg.port)))
        }

        return builder.build()
    }

    /**
     * Adds a rotating User-Agent and the Netherlands geo headers to every
     * outbound request. Pulls a fresh UA on each call from
     * [EngineUtils.USER_AGENTS] so scraping bursts don't collapse onto a
     * single fingerprint.
     */
    private class StealthInterceptor(
        private val proxyEngine: ProxyVPNEngine
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            val builder = original.newBuilder()
                .header("User-Agent", EngineUtils.getRandomUserAgent())
                .header("Accept-Language", "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7")

            // Only spoof geo headers if the proxy engine is enabled — otherwise
            // an honest request beats a half-faked one.
            if (proxyEngine.isProxyEnabled()) {
                builder.header("X-Forwarded-For", "185.107.56.37")
                builder.header("X-Real-IP", "185.107.56.37")
                builder.header("CF-IPCountry", "NL")
            }
            return chain.proceed(builder.build())
        }
    }

    /**
     * Adds 250–1500 ms of jitter to every request. Important for scraping
     * pipelines that fan out across many providers in parallel — without
     * this, the combined edge profile looks like a stress test.
     */
    private class HumanDelayInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            try {
                Thread.sleep(Random.nextLong(250, 1500))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return chain.proceed(chain.request())
        }
    }
}
