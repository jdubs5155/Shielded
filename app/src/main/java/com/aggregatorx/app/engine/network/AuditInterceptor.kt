package com.aggregatorx.app.engine.network

import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.data.model.AuditLogEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Interceptor that logs network activity to the Room database 
 * for security audit purposes.
 */
class AuditInterceptor @Inject constructor(
    private val auditLogDao: AuditLogDao
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            runBlocking {
                auditLogDao.insertLog(
                    AuditLogEntity(
                        actionType = "NETWORK_ERROR",
                        providerName = request.url.host,
                        details = "Request failed: ${e.message}",
                        isSuccess = false
                    )
                )
            }
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        runBlocking {
            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = "NETWORK_RESPONSE",
                    providerName = request.url.host,
                    details = "Method: ${request.method}, Code: ${response.code}, Duration: ${duration}ms",
                    isSuccess = response.isSuccessful
                )
            )
        }

        return response
    }
}
