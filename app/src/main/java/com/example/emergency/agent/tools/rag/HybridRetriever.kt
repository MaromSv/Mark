package com.example.emergency.agent.tools.rag

import android.content.Context
import org.json.JSONObject

data class Chunk(
    val docTitle: String,
    val category: String,
    val section: String,
    val priority: String,
    val text: String,
)

data class RetrievedChunk(val chunk: Chunk, val score: Float)

/**
 * Hybrid (BM25 + cosine) retriever over the prebuilt RAG index.
 *
 * Fusion uses Reciprocal Rank Fusion (k=60, the standard). RRF is robust
 * to score-scale differences between BM25 and cosine and avoids the
 * tuning headache of weighted-sum fusion.
 */
class HybridRetriever(context: Context) {

    val chunks: List<Chunk>
    private val embedder: Embedder
    private val vectorIndex: VectorIndex
    private val bm25: Bm25Index

    init {
        chunks = loadChunks(context)
        embedder = Embedder(context)
        vectorIndex = VectorIndex(context)
        check(vectorIndex.count == chunks.size) {
            "Chunk count mismatch: chunks.json=${chunks.size}, embeddings.bin=${vectorIndex.count}. " +
                "Rebuild the RAG index (data-pipeline/build_rag_index.py)."
        }
        val tokenized = chunks.map { Bm25Index.tokenize("${it.docTitle} ${it.section} ${it.text}") }
        bm25 = Bm25Index(tokenized)
    }

    /** Returns top [k] chunks by fused BM25 + cosine rank. */
    fun retrieve(query: String, k: Int = 4, perSideK: Int = 24): List<RetrievedChunk> {
        if (query.isBlank()) return emptyList()

        val qVec = embedder.embed(query)
        val cosine = vectorIndex.search(qVec, perSideK)
        val lex = bm25.search(Bm25Index.tokenize(query), perSideK)

        // Reciprocal Rank Fusion. score = sum_over_sources(1 / (rrfK + rank))
        val rrfK = 60f
        val fused = HashMap<Int, Float>()
        for ((rank, hit) in cosine.withIndex()) {
            fused.merge(hit.first, 1f / (rrfK + rank + 1)) { a, b -> a + b }
        }
        for ((rank, hit) in lex.withIndex()) {
            fused.merge(hit.first, 1f / (rrfK + rank + 1)) { a, b -> a + b }
        }

        return fused.entries
            .sortedByDescending { it.value }
            .take(k)
            .map { RetrievedChunk(chunks[it.key], it.value) }
    }

    private fun loadChunks(context: Context): List<Chunk> {
        val raw = context.assets.open("rag/chunks.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("chunks")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Chunk(
                docTitle = o.getString("title"),
                category = o.optString("category", ""),
                section = o.optString("section", ""),
                priority = o.optString("priority", "normal"),
                text = o.getString("text"),
            )
        }
    }

    fun close() {
        embedder.close()
    }
}
