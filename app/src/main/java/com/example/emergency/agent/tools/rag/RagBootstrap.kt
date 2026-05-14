package com.example.emergency.agent.tools.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide warm-up for the medical RAG retriever.
 *
 * First call to [HybridRetriever] takes ~1-2s: model.onnx is copied out of
 * the APK to filesDir, the ORT session initialises, chunks.json + the int8
 * vectors are parsed, and BM25 postings are built. By kicking that off at
 * Application.onCreate() it overlaps with home-screen inflation and the
 * Gemma model load, so the first medical query feels instant.
 *
 * The retriever instance is returned via [get] (suspending — blocks only
 * if warm-up is still running). [MedicalRagTool] consumes it.
 */
object RagBootstrap {

    private const val TAG = "RagBootstrap"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    @Volatile private var instance: HybridRetriever? = null
    @Volatile private var failure: Throwable? = null
    private val lock = Object()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val r = HybridRetriever(appContext)
                synchronized(lock) {
                    instance = r
                    lock.notifyAll()
                }
                Log.d(TAG, "RAG ready in ${System.currentTimeMillis() - t0} ms")
            } catch (t: Throwable) {
                Log.e(TAG, "RAG warm-up failed", t)
                synchronized(lock) {
                    failure = t
                    lock.notifyAll()
                }
            }
        }
    }

    /**
     * Returns the retriever, blocking the calling (background) thread if
     * warm-up is still in flight. Returns null if warm-up failed — caller
     * should surface a friendly error.
     */
    fun get(): HybridRetriever? {
        instance?.let { return it }
        synchronized(lock) {
            while (instance == null && failure == null) {
                lock.wait()
            }
            return instance
        }
    }

    fun failureMessage(): String? = failure?.message ?: failure?.javaClass?.simpleName
}
