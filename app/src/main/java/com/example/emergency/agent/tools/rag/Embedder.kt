package com.example.emergency.agent.tools.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * MiniLM (all-MiniLM-L6-v2) text embedder running on ONNX Runtime.
 *
 * Pipeline: tokenize (WordPiece) → ONNX forward → mean-pool over tokens
 * (masked) → L2-normalize. Output is a [DIM]-dim float vector that can be
 * cosine-compared against the prebuilt embeddings.bin.
 */
class Embedder(context: Context) {

    companion object {
        const val DIM = 384
        private const val MODEL_ASSET = "rag/model.onnx"
        private const val MAX_TOKENS = 384
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: BertTokenizer

    init {
        // ONNX Runtime needs a real file path (or byte[]). assets are inside
        // the APK zip — copy the model out to filesDir on first run. ~90MB,
        // happens once.
        val modelFile = File(context.filesDir, "rag_model.onnx")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            context.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { input.copyTo(it) }
            }
        }
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        tokenizer = BertTokenizer(context)
    }

    /** Embed a single string. Returns a normalized DIM-vector. */
    fun embed(text: String): FloatArray {
        val enc = tokenizer.encode(text, maxLen = MAX_TOKENS)
        val ids = enc.ids
        val mask = enc.attentionMask
        val typeIds = enc.typeIds
        val len = ids.size

        val idsBuf = LongBuffer.allocate(len)
        val maskBuf = LongBuffer.allocate(len)
        val typeBuf = LongBuffer.allocate(len)
        for (i in 0 until len) {
            idsBuf.put(ids[i])
            maskBuf.put(mask[i])
            typeBuf.put(typeIds[i])
        }
        idsBuf.rewind(); maskBuf.rewind(); typeBuf.rewind()

        val shape = longArrayOf(1L, len.toLong())
        val inputs = mutableMapOf<String, OnnxTensor>()
        OnnxTensor.createTensor(env, idsBuf, shape).use { idsT ->
            OnnxTensor.createTensor(env, maskBuf, shape).use { maskT ->
                OnnxTensor.createTensor(env, typeBuf, shape).use { typeT ->
                    inputs["input_ids"] = idsT
                    inputs["attention_mask"] = maskT
                    // BERT-family ONNX exports usually expose token_type_ids;
                    // include only if the graph wants it.
                    if ("token_type_ids" in session.inputNames) {
                        inputs["token_type_ids"] = typeT
                    }
                    session.run(inputs).use { result ->
                        // Output: [1, seq_len, hidden] (last_hidden_state).
                        // Use FloatBuffer accessor — materializing a 3D
                        // float[][][] from JNI is ~5-10x slower per call.
                        // Result.get(int) returns OnnxValue directly; only
                        // get(String) is Optional-wrapped.
                        val out = result.get(0) as OnnxTensor
                        return meanPoolAndNormalize(out, mask, len)
                    }
                }
            }
        }
    }

    private fun meanPoolAndNormalize(
        tensor: OnnxTensor,           // shape [1, seq_len, hidden], row-major
        mask: LongArray,
        len: Int,
    ): FloatArray {
        val fb = tensor.floatBuffer
        val out = FloatArray(DIM)
        var count = 0
        for (i in 0 until len) {
            if (mask[i] == 0L) continue
            count++
            val rowStart = i * DIM
            for (d in 0 until DIM) out[d] += fb.get(rowStart + d)
        }
        if (count == 0) count = 1
        var sumSq = 0.0
        for (d in 0 until DIM) {
            out[d] /= count
            sumSq += out[d] * out[d]
        }
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
        for (d in 0 until DIM) out[d] /= norm
        return out
    }

    fun close() {
        session.close()
    }
}
