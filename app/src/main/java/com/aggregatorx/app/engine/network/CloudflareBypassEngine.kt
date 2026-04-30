package com.aggregatorx.app.engine.network

import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Specialized engine to handle bot detection and Cloudflare challenges
 * using the headless WebView.
 */
@Singleton
class CloudflareBypassEngine @Inject constructor(
    private val headlessBrowser: HeadlessBrowserHelper,
    private val auditLogDao: AuditLogDao
) {

    /**
     * Attempts to resolve a URL that is currently being challenged.
     */
    suspend fun resolve(url: String): String {
        auditLogDao.insertLog(
            AuditLogEntity(
                actionType = "BYPASS_ATTEMPT",
                providerName = null,
                details = "Attempting to resolve challenge for: $url"
            )
        )

        // Load the page and wait for the challenge to complete automatically via WebView
        val initialHtml = headlessBrowser.getHtml(url)
        
        // Polling logic to wait for the "Just a moment" screen to disappear
        var currentHtml = initialHtml
        var attempts = 0
        while (isChallengeActive(currentHtml) && attempts < 5) {
            delay(3000) // Wait for JS challenges to execute
            currentHtml = headlessBrowser.getHtml(url)
            attempts++
        }

        if (isChallengeActive(currentHtml)) {
            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = "BYPASS_FAILURE",
                    providerName = null,
                    details = "Failed to solve challenge after $attempts attempts",
                    isSuccess = false
                )
            )
        } else {
            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = "BYPASS_SUCCESS",
                    providerName = null,
                    details = "Challenge resolved successfully"
                )
            )
        }

        return currentHtml
    }

    private fun isChallengeActive(html: String): Boolean {
        return html.contains("cf-challenge", ignoreCase = true) || 
               html.contains("checking your browser", ignoreCase = true)
    }
}
