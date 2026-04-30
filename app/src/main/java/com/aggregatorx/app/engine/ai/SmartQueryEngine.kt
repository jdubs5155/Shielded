package com.aggregatorx.app.engine.ai

import com.aggregatorx.app.data.database.AggregatorDao
import com.aggregatorx.app.data.model.ResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generates "smart" test queries when the UI doesn't have one.
 *
 * Three sources, in priority order:
 *  1. **Liked-item summary** — extracts the most common 2-grams across the
 *     user's liked titles. Catches their actual interests.
 *  2. **Recent successful queries** — pulled from the local result history
 *     by sniffing the most common terms in cached titles.
 *  3. **Built-in seed list** — broad topics so the very first run still has
 *     something to scrape against.
 *
 * The engine is deterministic-but-shuffled: the same call gives the same
 * candidate ordering until the underlying data changes, so the user can
 * tell whether a re-run produced different scrape behavior because the
 * site changed (vs. because we asked something different).
 */
@Singleton
class SmartQueryEngine @Inject constructor(
    private val dao: AggregatorDao
) {

    /**
     * Pick the best test query right now. Always returns a non-blank string.
     *
     * The result is intentionally NOT pre-polished by the LLM here —
     * `AggregatorRepository.performSearch` already routes the query through
     * `NLPQueryEngine.rewriteQuery`, so polishing here would double up and
     * waste an inference call.
     */
    suspend fun nextQuery(): String = withContext(Dispatchers.IO) {
        val candidates = candidateQueries()
        candidates.firstOrNull()?.takeIf { it.isNotBlank() } ?: SEED_QUERIES.random()
    }

    /**
     * Full ranked list (top-N) — handy for a "shuffle" UI affordance or for
     * a background warmup that wants to run more than one query.
     */
    suspend fun candidateQueries(limit: Int = 10): List<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()

        // 1) Liked items
        val likedTitles = dao.getEnabledProviders().first()
            .flatMap { dao.getResultsByProviderOnce(it.name) }
            .filter { it.isLiked }
            .map { it.title }
        out.addAll(extractKeyPhrases(likedTitles, max = limit))

        // 2) Recent cached results, even if not liked.
        if (out.size < limit) {
            val cachedTitles = dao.getEnabledProviders().first()
                .flatMap { dao.getResultsByProviderOnce(it.name).take(50) }
                .map { it.title }
            out.addAll(extractKeyPhrases(cachedTitles, max = limit - out.size))
        }

        // 3) Seed list — guarantees a non-empty result on a fresh install.
        if (out.size < limit) {
            SEED_QUERIES.shuffled(Random(System.currentTimeMillis() / 60_000))
                .take(limit - out.size)
                .forEach { out.add(it) }
        }

        out.toList()
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Returns up to [max] short queries derived from the most frequent
     * non-trivial 1- and 2-gram phrases across [titles].
     */
    private fun extractKeyPhrases(titles: List<String>, max: Int): List<String> {
        if (titles.isEmpty() || max <= 0) return emptyList()

        val unigrams = mutableMapOf<String, Int>()
        val bigrams  = mutableMapOf<String, Int>()

        titles.forEach { title ->
            val tokens = title.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 3 && it !in STOPWORDS }
            tokens.forEach { tk -> unigrams.merge(tk, 1) { a, b -> a + b } }
            for (i in 0 until tokens.size - 1) {
                val bg = "${tokens[i]} ${tokens[i + 1]}"
                bigrams.merge(bg, 1) { a, b -> a + b }
            }
        }

        // Bigrams beat unigrams when both have frequency >= 2.
        val ranked = (
            bigrams.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.map { it.key } +
            unigrams.entries.sortedByDescending { it.value }.map { it.key }
        ).distinct()

        return ranked.take(max)
    }

    companion object {
        private val SEED_QUERIES = listOf(
            "open source android apps",
            "machine learning tutorials",
            "kotlin coroutines guide",
            "documentary filmmaking",
            "travel netherlands amsterdam",
            "live concert recordings",
            "indie game development",
            "retro computing",
            "data privacy news",
            "self hosted services"
        )

        private val STOPWORDS = setOf(
            "this", "that", "with", "from", "your", "have", "been", "were",
            "they", "what", "when", "where", "which", "their", "there", "about",
            "into", "more", "than", "then", "just", "like", "also", "such",
            "some", "only", "very", "much", "many", "most", "other", "over",
            "even", "well", "back"
        )
    }
}
