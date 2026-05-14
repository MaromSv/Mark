package com.example.emergency.offline.pack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Source of truth for "which region packs are installed on this device"
 * (plan section 6). Backed by a single JSON registry file
 * (`filesDir/regions/installed.json`); the actual pack bytes live in
 * `filesDir/regions/<id>/` next to it.
 *
 * Why JSON, not Room (deferred from the section 6 sketch): the dataset is
 * tiny - at most a few dozen rows, written ~once per install/delete and
 * read once per process start. A whole SQLite-backed Room module (kapt
 * annotation processor + DAOs + migrations) would be heavier than the
 * problem requires. Swap to Room before we ship multi-device sync or
 * concurrent writers; today, neither exists.
 *
 * Thread-safety: writes go through [save] under a mutex on `this`; reads
 * are served from [_state] without locking. [PackDownloader] is the only
 * writer; UI consumes [state].
 */
class RegionStore private constructor(
    private val regionsRoot: File,
    private val registryFile: File,
) {

    private val _state = MutableStateFlow<List<RegionPack>>(emptyList())
    val state: StateFlow<List<RegionPack>> = _state.asStateFlow()

    /** Snapshot of installed packs. Cheap; reads from in-memory cache. */
    fun list(): List<RegionPack> = _state.value

    fun get(id: String): RegionPack? = _state.value.firstOrNull { it.id == id }

    /**
     * Inserts or replaces the pack row. The pack's bytes must already be in
     * place under `regionsRoot/<id>/` - this is the bookkeeping half of the
     * install, called by [com.example.emergency.offline.download.PackDownloader]
     * after the atomic rename.
     */
    @Synchronized
    fun add(pack: RegionPack) {
        val updated = _state.value.filterNot { it.id == pack.id } + pack
        _state.value = updated.sortedBy { it.id }
        save()
        Log.d(TAG, "Registered ${pack.id} (${pack.sizeBytes / 1024 / 1024} MB)")
    }

    /**
     * Removes the registry row and deletes the pack directory. Returns true
     * iff both succeeded - a partial delete (registry gone, files lingering
     * because something held a file handle) returns false and logs.
     */
    @Synchronized
    fun delete(id: String): Boolean {
        val pack = _state.value.firstOrNull { it.id == id } ?: return false
        _state.value = _state.value.filterNot { it.id == id }
        save()
        val deleted = pack.rootDir.deleteRecursively()
        if (!deleted) Log.w(TAG, "Couldn't fully delete ${pack.rootDir} - registry already cleared")
        return deleted
    }

    /**
     * Stamps the pack's [RegionPack.lastUsedAt] to "now" so the picker can
     * surface most-recently-used packs first. Cheap - caller doesn't need
     * to throttle.
     */
    @Synchronized
    fun touchLastUsed(id: String, now: Long = System.currentTimeMillis()) {
        val current = _state.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        _state.value = current.toMutableList().also {
            it[idx] = current[idx].copy(lastUsedAt = now)
        }
        save()
    }

    /** Resolves the on-disk root for a pack id whether it's installed or not. */
    fun rootDirFor(id: String): File = RegionPack.rootFor(regionsRoot, id)

    /**
     * Re-reads the registry from disk, dropping any in-memory rows whose
     * pack directory has since vanished (e.g. user cleared app data outside
     * us). Called from [open] at construction.
     */
    private fun loadFromDisk(): List<RegionPack> {
        if (!registryFile.exists()) return emptyList()
        val raw = registryFile.readText()
        if (raw.isBlank()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "registry parse failed; treating as empty", t)
            return emptyList()
        }
        val out = mutableListOf<RegionPack>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pack = parseRow(o) ?: continue
            if (!pack.rootDir.exists()) {
                Log.w(TAG, "Dropping ${pack.id}: rootDir ${pack.rootDir} missing")
                continue
            }
            out += pack
        }
        return out.sortedBy { it.id }
    }

    private fun parseRow(o: JSONObject): RegionPack? = try {
        val id = o.getString("id")
        RegionPack(
            id = id,
            name = o.getString("name"),
            type = RegionType.fromJsonString(o.getString("type")),
            bbox = BoundingBox.fromJson(o.getJSONArray("bbox")),
            version = o.getInt("version"),
            sizeBytes = o.getLong("sizeBytes"),
            installedAt = o.getLong("installedAt"),
            lastUsedAt = o.getLong("lastUsedAt"),
            rootDir = RegionPack.rootFor(regionsRoot, id),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Skipping malformed registry row", t)
        null
    }

    private fun rowToJson(pack: RegionPack): JSONObject = JSONObject().apply {
        put("id", pack.id)
        put("name", pack.name)
        put("type", pack.type.toJsonString())
        put("bbox", pack.bbox.toJson())
        put("version", pack.version)
        put("sizeBytes", pack.sizeBytes)
        put("installedAt", pack.installedAt)
        put("lastUsedAt", pack.lastUsedAt)
    }

    /**
     * Persists the in-memory state. Writes to a sibling `.partial` file and
     * renames so a crash mid-write can never leave a half-written registry.
     */
    private fun save() {
        regionsRoot.mkdirs()
        val arr = JSONArray()
        _state.value.forEach { arr.put(rowToJson(it)) }
        val tmp = File(registryFile.parentFile, "${registryFile.name}.partial")
        tmp.writeText(arr.toString(2))
        if (registryFile.exists()) registryFile.delete()
        check(tmp.renameTo(registryFile)) { "registry rename failed: $tmp -> $registryFile" }
    }

    companion object {
        private const val TAG = "RegionStore"
        private const val REGISTRY_FILE = "installed.json"

        @Volatile private var instance: RegionStore? = null

        /**
         * Process-wide singleton. Safe to call from any thread; first call
         * blocks briefly to read the registry from disk.
         */
        fun get(context: Context): RegionStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val regionsRoot = File(context.applicationContext.filesDir,
                    RegionPack.REGIONS_SUBDIR)
                regionsRoot.mkdirs()
                val store = RegionStore(regionsRoot, File(regionsRoot, REGISTRY_FILE))
                store._state.value = store.loadFromDisk()
                Log.d(TAG, "Loaded ${store._state.value.size} installed pack(s)")
                instance = store
                return store
            }
        }

        /** Test-only constructor: skips the application-singleton machinery. */
        internal fun forTest(regionsRoot: File): RegionStore {
            regionsRoot.mkdirs()
            val store = RegionStore(regionsRoot, File(regionsRoot, REGISTRY_FILE))
            store._state.value = store.loadFromDisk()
            return store
        }
    }
}
