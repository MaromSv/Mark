package com.example.emergency.offline.pack

import org.json.JSONArray
import org.json.JSONObject

/**
 * What kind of region a pack covers. Drives picker grouping and the
 * presets.json hierarchy.
 */
enum class RegionType {
    COUNTRY, STATE, METRO, CUSTOM;

    /** Lower-case spelling matches the manifest schema. */
    fun toJsonString(): String = name.lowercase()

    companion object {
        fun fromJsonString(s: String): RegionType = when (s.lowercase()) {
            "country" -> COUNTRY
            "state"   -> STATE
            "metro"   -> METRO
            "custom"  -> CUSTOM
            else      -> error("unknown region type: $s")
        }
    }
}

/**
 * Per-pack manifest - the `manifest.json` shipped *inside* each region
 * tar.gz (see plan section 3). Lists every file in the pack with its sha256
 * and byte length so [com.example.emergency.offline.download.PackDownloader]
 * can verify the install end-to-end before swapping it in.
 *
 * Distinct from [CatalogManifest], which is the *remote* index of all
 * available packs and only stores one sha256 (of the tar.gz) per pack.
 */
data class PackManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val type: RegionType,
    val bbox: BoundingBox,
    val version: Int,
    val createdAt: String,
    val files: List<PackFile>,
) {
    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("id", id)
        put("name", name)
        put("type", type.toJsonString())
        put("bbox", bbox.toJson())
        put("version", version)
        put("createdAt", createdAt)
        val arr = JSONArray()
        files.forEach { arr.put(it.toJson()) }
        put("files", arr)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun parse(json: String): PackManifest = parse(JSONObject(json))

        fun parse(o: JSONObject): PackManifest = PackManifest(
            schemaVersion = o.getInt("schemaVersion"),
            id            = o.getString("id"),
            name          = o.getString("name"),
            type          = RegionType.fromJsonString(o.getString("type")),
            bbox          = BoundingBox.fromJson(o.getJSONArray("bbox")),
            version       = o.getInt("version"),
            createdAt     = o.getString("createdAt"),
            files         = o.getJSONArray("files").let { arr ->
                List(arr.length()) { PackFile.parse(arr.getJSONObject(it)) }
            },
        )
    }
}

/**
 * One file inside a pack. `path` is relative to the pack root, with
 * forward-slash separators (e.g. `routing/E0_N50.rd5`).
 */
data class PackFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
    }

    companion object {
        fun parse(o: JSONObject) = PackFile(
            path      = o.getString("path"),
            sizeBytes = o.getLong("sizeBytes"),
            sha256    = o.getString("sha256"),
        )
    }
}

/**
 * Remote catalog of available region packs (plan section 6). Served from the
 * pack hosting endpoint and consumed by the region picker to populate
 * the Browse and Recommended tabs.
 */
data class CatalogManifest(
    val schemaVersion: Int,
    val lastUpdated: String,
    val packs: List<CatalogEntry>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun parse(json: String): CatalogManifest {
            val o = JSONObject(json)
            val arr = o.getJSONArray("packs")
            return CatalogManifest(
                schemaVersion = o.getInt("schemaVersion"),
                lastUpdated   = o.getString("lastUpdated"),
                packs         = List(arr.length()) {
                    CatalogEntry.parse(arr.getJSONObject(it))
                },
            )
        }
    }
}

/**
 * One entry in the remote catalog. Holds the download URL and the
 * sha256 of the tar.gz; the per-file sha256s live in the [PackManifest]
 * carried inside that archive.
 */
data class CatalogEntry(
    val id: String,
    val name: String,
    val type: RegionType,
    val bbox: BoundingBox,
    val sizeBytes: Long,
    val version: Int,
    val url: String,
    val sha256: String,
    val iso: String? = null,
    val country: String? = null,
) {
    companion object {
        fun parse(o: JSONObject) = CatalogEntry(
            id        = o.getString("id"),
            name      = o.getString("name"),
            type      = RegionType.fromJsonString(o.getString("type")),
            bbox      = BoundingBox.fromJson(o.getJSONArray("bbox")),
            sizeBytes = o.getLong("sizeBytes"),
            version   = o.getInt("version"),
            url       = o.getString("url"),
            sha256    = o.getString("sha256"),
            iso       = if (o.has("iso"))     o.getString("iso")     else null,
            country   = if (o.has("country")) o.getString("country") else null,
        )
    }
}
