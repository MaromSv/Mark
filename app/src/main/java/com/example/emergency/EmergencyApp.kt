package com.example.emergency

import android.app.Application
import com.example.emergency.agent.tools.rag.RagBootstrap
import com.example.emergency.offline.OfflineBootstrap

/**
 * Custom [Application] that bootstraps long-running init work at process
 * start so it overlaps with the user navigating from the home screen
 * instead of blocking them later:
 *   - Offline tile/route asset staging (~470 MB APK->filesDir copy)
 *   - Medical RAG retriever warm-up (~1-2 s: model copy + ORT session +
 *     embeddings.bin + BM25 index)
 *
 * Registered via `android:name=".EmergencyApp"` in the manifest.
 */
class EmergencyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineBootstrap.start(this)
        RagBootstrap.start(this)
    }
}
