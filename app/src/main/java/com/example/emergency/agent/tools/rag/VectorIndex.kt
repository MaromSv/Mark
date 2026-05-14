package com.example.emergency.agent.tools.rag

import android.content.Context
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory cosine-similarity index over int8-quantized embeddings.
 *
 * File layout (little-endian, written by data-pipeline/build_rag_index.py):
 *   for each of N rows:
 *     float32  scale          (4 bytes — quantization scale, dequant: f = i8 * scale)
 *     int8[D]  values         (D bytes)
 *
 * Total = N * (4 + D) bytes. For ~5k chunks at D=384: ~1.9 MB.
 *
 * Stored vectors are pre-normalized (build script uses normalize_embeddings=True),
 * so dot product on dequantized vectors == cosine similarity.
 */
class VectorIndex(
    context: Context,
    private val dim: Int = Embedder.DIM,
    asset: String = "rag/embeddings.bin",
) {

    private val scales: FloatArray
    private val data: ByteArray   // flat int8 store, row stride = dim
    val count: Int

    init {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val rowBytes = 4 + dim
        require(bytes.size % rowBytes == 0) {
            "embeddings.bin size ${bytes.size} not a multiple of row size $rowBytes"
        }
        count = bytes.size / rowBytes
        scales = FloatArray(count)
        data = ByteArray(count * dim)

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            scales[i] = bb.float
            bb.get(data, i * dim, dim)
        }
    }

    /** Returns top-K (rowIndex, cosine) pairs, sorted descending. */
    fun search(query: FloatArray, k: Int): List<Pair<Int, Float>> {
        require(query.size == dim) { "query dim ${query.size} != index dim $dim" }
        // Tiny min-heap kept at size k. For ~5k docs a full sort is also fine,
        // but this avoids a 5k-element allocation per query.
        val topK = ArrayList<Pair<Int, Float>>(k + 1)
        var threshold = -Float.MAX_VALUE

        for (row in 0 until count) {
            val score = dotInt8Row(query, row)
            if (topK.size < k) {
                topK.add(row to score)
                if (topK.size == k) {
                    topK.sortBy { it.second }
                    threshold = topK[0].second
                }
            } else if (score > threshold) {
                topK[0] = row to score
                // Re-establish min at index 0
                var i = 0
                while (i < k - 1 && topK[i].second > topK[i + 1].second) {
                    val tmp = topK[i]; topK[i] = topK[i + 1]; topK[i + 1] = tmp
                    i++
                }
                threshold = topK[0].second
            }
        }
        return topK.sortedByDescending { it.second }
    }

    /** dot(query, dequantize(row_i)). Hot loop — keep allocation-free. */
    private fun dotInt8Row(query: FloatArray, row: Int): Float {
        val scale = scales[row]
        val base = row * dim
        var acc = 0f
        for (d in 0 until dim) {
            // Kotlin Bytes are signed 8-bit (-128..127) — exactly what we
            // stored. Explicit toInt() because Float * Byte isn't a Kotlin
            // operator.
            acc += query[d] * data[base + d].toInt()
        }
        return acc * scale
    }
}
