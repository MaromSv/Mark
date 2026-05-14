package com.example.emergency.agent.tools.rag

import kotlin.math.ln

/**
 * Compact BM25 over the chunk corpus. Built once at startup from chunk text.
 *
 * No stemming — for medical text "burn" / "burns" / "burning" stay distinct
 * on purpose; the embedding side handles morphological variation. We just
 * need exact-term recall here (drug names, abbreviations like STEMI / NAC,
 * dosages like "0.3 mg").
 */
class Bm25Index(
    private val docs: List<List<String>>,    // tokenized chunks
    private val k1: Float = 1.5f,
    private val b: Float = 0.75f,
) {
    private val docLen: IntArray = IntArray(docs.size) { docs[it].size }
    private val avgDocLen: Float = if (docs.isEmpty()) 1f else docLen.average().toFloat()

    // term → list of (docIndex, tf)
    private val postings: Map<String, List<IntArray>>

    // term → idf
    private val idf: Map<String, Float>

    init {
        val raw = HashMap<String, MutableList<IntArray>>()
        for ((i, tokens) in docs.withIndex()) {
            val tf = HashMap<String, Int>()
            for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
            for ((term, count) in tf) {
                raw.getOrPut(term) { mutableListOf() }.add(intArrayOf(i, count))
            }
        }
        postings = raw
        val n = docs.size.coerceAtLeast(1)
        idf = postings.mapValues { (_, plist) ->
            val df = plist.size
            // Smoothed idf (Lucene-style), always positive.
            ln(1f + (n - df + 0.5f) / (df + 0.5f))
        }
    }

    fun search(query: List<String>, k: Int): List<Pair<Int, Float>> {
        if (query.isEmpty() || docs.isEmpty()) return emptyList()
        val scores = HashMap<Int, Float>()
        for (term in query.toSet()) {
            val plist = postings[term] ?: continue
            val termIdf = idf[term] ?: continue
            for (entry in plist) {
                val docIdx = entry[0]
                val tf = entry[1].toFloat()
                val dl = docLen[docIdx].toFloat()
                val denom = tf + k1 * (1f - b + b * dl / avgDocLen)
                val contribution = termIdf * (tf * (k1 + 1f)) / denom
                scores.merge(docIdx, contribution) { a, c -> a + c }
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(k)
            .map { it.key to it.value }
    }

    companion object {
        private val tokenSplit = Regex("[^a-z0-9]+")

        /** Lowercase + split on non-alphanumerics. Keeps short tokens (e.g. "iv", "po"). */
        fun tokenize(text: String): List<String> =
            tokenSplit.split(text.lowercase()).filter { it.isNotEmpty() }
    }
}
