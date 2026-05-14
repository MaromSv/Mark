package com.example.emergency.offline.pack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Source of truth for "which region packs are available to download"
 * (plan section 6).
 *
 * Three layered sources, queried in this order at startup:
 *   1. **Cached** - `filesDir/bundled/catalog.json` (last successful
 *      remote fetch). Picked first so launches after an offline session
 *      still see the freshest catalog the device has ever seen.
 *   2. **Bundled** - `assets/bundled/catalog.json` shipped in the APK.
 *      Used on first launch and as the offline fallback when both the
 *      cache and remote are unavailable.
 *   3. **Remote** - `REMOTE_CATALOG_URL` (the file that GitHub Actions
 *      regenerates after every pack-build run, served from the same
 *      repo). Fetched in the background; on success the in-memory state
 *      flips to the fresh copy and the cache is overwritten so the next
 *      launch picks it up immediately.
 *
 * Singleton-per-process so picker views, the recommender, and the
 * storage manager all observe the same `entries` flow.
 */
class CatalogProvider private constructor(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {

    private val _catalog = MutableStateFlow(CatalogManifest(
        schemaVersion = CatalogManifest.CURRENT_SCHEMA_VERSION,
        lastUpdated = "1970-01-01T00:00:00Z",
        packs = emptyList(),
    ))

    val catalog: StateFlow<CatalogManifest> = _catalog.asStateFlow()

    /** Convenience accessor - most callers only care about the entry list. */
    val entries: List<CatalogEntry> get() = _catalog.value.packs

    fun get(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    /**
     * Loads the freshest catalog available locally:
     *   * Prefer the cached copy at `filesDir/bundled/catalog.json` if
     *     present (it was written by a previous successful remote
     *     refresh and is at least as fresh as the bundled snapshot).
     *   * Otherwise fall back to the APK-bundled snapshot.
     *
     * Cheap and synchronous - both files are < 100 KB.
     */
    fun loadFromDisk() {
        val cached = File(appContext.filesDir, CACHE_PATH)
        if (cached.exists()) {
            try {
                val parsed = CatalogManifest.parse(cached.readText())
                _catalog.value = parsed
                Log.d(TAG, "Loaded ${parsed.packs.size} entries from cache (${cached.absolutePath})")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Cached catalog unparseable; falling back to bundled", t)
            }
        }
        val raw = appContext.assets.open(BUNDLED_PATH).bufferedReader()
            .use { it.readText() }
        val parsed = CatalogManifest.parse(raw)
        _catalog.value = parsed
        Log.d(TAG, "Loaded ${parsed.packs.size} entries from bundled $BUNDLED_PATH")
    }

    /**
     * Best-effort background refresh from the remote URL. On success:
     *   * Updates the in-memory state so the picker re-renders with new
     *     entries.
     *   * Writes the fresh JSON to `filesDir/bundled/catalog.json` so
     *     the next launch starts from this copy.
     *
     * On failure (no network, bad JSON, server down) the existing state
     * stays put - the user keeps seeing whatever was loaded from disk.
     * Safe to call repeatedly; no debouncing because each attempt is
     * cheap (~few KB download).
     */
    fun refreshFromRemote(remoteUrl: String = REMOTE_CATALOG_URL) {
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { fetchText(remoteUrl) }
                val parsed = CatalogManifest.parse(raw)
                _catalog.value = parsed
                writeCache(raw)
                Log.d(TAG, "Refreshed catalog from $remoteUrl: ${parsed.packs.size} packs")
            } catch (t: Throwable) {
                Log.w(TAG, "Remote catalog refresh failed (keeping cached): ${t.message}")
            }
        }
    }

    private fun fetchText(remoteUrl: String): String {
        val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code from $remoteUrl")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeCache(json: String) {
        val cache = File(appContext.filesDir, CACHE_PATH)
        cache.parentFile?.mkdirs()
        val tmp = File(cache.parentFile, "${cache.name}.partial")
        tmp.writeText(json)
        if (cache.exists()) cache.delete()
        if (!tmp.renameTo(cache)) {
            Log.w(TAG, "Catalog cache rename failed: $tmp -> $cache")
        }
    }

    companion object {
        private const val TAG = "CatalogProvider"
        private const val BUNDLED_PATH = "bundled/catalog.json"
        private const val CACHE_PATH = "bundled/catalog.json"

        /**
         * Where to fetch the live catalog from.
         *
         * **You must edit this** before publishing the app. Replace
         * `<owner>/<repo>` with your GitHub org + repo name; the build
         * workflow commits the freshest catalog.json to the path below
         * after every run, served via raw.githubusercontent.com (free,
         * no setup, immediate cache-busting on commit).
         *
         * Repo: github.com/MaromSv/Anthropic-x-Whale-Hackathon-app
         * Branch: feature/offline-map-improvements (the workflow commits the
         * regenerated catalog back to whichever branch it runs on; switch this
         * URL when you merge the work to main).
         */
        const val REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/MaromSv/Anthropic-x-Whale-Hackathon-app/refs/heads/feature/offline-map-improvements/app/src/main/assets/bundled/catalog.json"

        @Volatile private var instance: CatalogProvider? = null

        fun get(context: Context): CatalogProvider {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val provider = CatalogProvider(
                    appContext = context.applicationContext,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
                provider.loadFromDisk()
                provider.refreshFromRemote() // fire-and-forget
                instance = provider
                return provider
            }
        }
    }
}
