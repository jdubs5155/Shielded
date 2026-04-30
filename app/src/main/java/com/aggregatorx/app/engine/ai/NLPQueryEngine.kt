package com.aggregatorx.app.engine.ai

import android.content.Context
import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.engine.ai.llm.LlamaCppBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local AI Engine using Dolphin 3.0 (Llama 3.1 8B GGUF) via llama.cpp.
 *
 * Responsibilities:
 *  - rewrite a user's natural-language query into a search-engine-friendly form,
 *  - power a continuous background refinement loop seeded from "liked" items,
 *  - analyze captured auth tokens (JWTs, headers) for the scraping pipeline.
 *
 * If the native llama.cpp library or the GGUF model isn't available
 * (see [LlamaCppBridge]), every method degrades to a deterministic
 * passthrough so the app stays functional in builds shipped without the
 * heavy native artifacts.
 */
@Singleton
class NLPQueryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLogDao: AuditLogDao,
    private val tokenAnalyzer: TokenAnalyzer
) {
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val aiScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val llama = LlamaCppBridge.get()

    @Volatile private var modelReady = false
    @Volatile private var continuousLoop: Job? = null

    /**
     * Lazy LLM init — happens once, off the calling thread, the first time
     * something asks the model to do work.
     */
    private suspend fun ensureModelReady() {
        if (modelReady) return
        withContext(Dispatchers.IO) {
            modelReady = llama.initialize(context)
            logAction("AI_LLM_INIT", "modelReady=$modelReady (available=${llama.isAvailable()})")
        }
    }

    /**
     * Rewrite a natural-language query into something better suited to
     * keyword-style provider search. Falls back to the original query if
     * the model isn't available.
     */
    suspend fun rewriteQuery(originalQuery: String): String = withContext(Dispatchers.Default) {
        if (originalQuery.isBlank()) return@withContext originalQuery

        _isProcessing.value = true
        try {
            ensureModelReady()
            logAction("AI_QUERY_REWRITE", "Optimizing query: $originalQuery")

            val prompt = buildString {
                appendLine("You rewrite user queries into concise keyword-style search queries.")
                appendLine("Return ONLY the rewritten query, no quoting, no commentary.")
                appendLine("User query: $originalQuery")
                append("Rewritten:")
            }
            val raw = if (llama.isAvailable()) {
                llama.generate(prompt, maxTokens = 32, temperature = 0.3f)
            } else {
                originalQuery
            }
            val cleaned = raw
                .substringAfter("Rewritten:", raw)
                .lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.trim('"', '\'')
                ?: originalQuery
            cleaned.ifBlank { originalQuery }
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Fire-and-forget single refinement: triggered from a "like" interaction
     * but does not start the continuous loop. Kept for legacy callers.
     */
    fun refineResults(likedItems: List<ResultItem>) {
        if (likedItems.isEmpty()) return
        aiScope.launch { computeRefinedQuery(likedItems) }
    }

    /**
     * Continuous background refinement loop. Each cycle:
     *  1. summarize liked items into a refined query (via LLM if available),
     *  2. push it back to the caller via [onNewQuery],
     *  3. wait [intervalMs], repeat until cancelled or the inputs go away.
     *
     * Calling this again with a non-empty liked set replaces the previous
     * loop — we never keep two refinement loops alive at once.
     */
    fun startRefinementLoop(
        likedItems: List<ResultItem>,
        intervalMs: Long = 60_000L,
        onNewQuery: (String) -> Unit
    ) {
        if (likedItems.isEmpty()) {
            continuousLoop?.cancel()
            continuousLoop = null
            return
        }

        continuousLoop?.cancel()
        continuousLoop = aiScope.launch {
            ensureModelReady()
            var lastEmitted: String? = null
            while (isActive) {
                _isProcessing.value = true
                val refined = try {
                    computeRefinedQuery(likedItems)
                } catch (_: Throwable) {
                    null
                }
                _isProcessing.value = false

                if (!refined.isNullOrBlank() && refined != lastEmitted) {
                    lastEmitted = refined
                    onNewQuery(refined)
                }
                delay(intervalMs)
            }
        }
    }

    /** Stop the continuous refinement loop, if one is active. */
    fun stopRefinementLoop() {
        continuousLoop?.cancel()
        continuousLoop = null
    }

    /**
     * Token Handling: decodes any extracted JWTs / auth headers and audits
     * whether they look re-usable for subsequent scraping.
     */
    suspend fun analyzeTokens(rawTokensBlob: String): List<JwtInfo> = withContext(Dispatchers.Default) {
        val jwts = tokenAnalyzer.extractJwts(rawTokensBlob)
        logAction(
            "AI_TOKEN_ANALYSIS",
            "Found ${jwts.size} JWT(s); reusable=${jwts.count { !it.isExpired }}"
        )
        jwts
    }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun computeRefinedQuery(likedItems: List<ResultItem>): String {
        val titles = likedItems.take(8).joinToString(" | ") { it.title }
        logAction("AI_REFINEMENT_LOOP", "Refining from ${likedItems.size} liked item(s)")

        if (!llama.isAvailable()) {
            // Fallback: extract the most common non-trivial words.
            return likedItems.flatMap { it.title.split(Regex("\\W+")) }
                .filter { it.length > 3 }
                .groupingBy { it.lowercase() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(4)
                .joinToString(" ") { it.key }
                .ifBlank { titles }
        }

        val prompt = buildString {
            appendLine("Given these titles a user liked:")
            appendLine(titles)
            appendLine("Write a concise search query (3-6 keywords) that would find more like them.")
            append("Refined query:")
        }
        val raw = llama.generate(prompt, maxTokens = 32, temperature = 0.4f)
        return raw.substringAfter("Refined query:", raw)
            .lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '\'')
            ?: titles
    }

    private suspend fun logAction(type: String, details: String) {
        try {
            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType = type,
                    providerName = "LocalLLM",
                    details = details
                )
            )
        } catch (_: Throwable) {
            /* audit log failure must never break inference */
        }
    }
}
