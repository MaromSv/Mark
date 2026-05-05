package com.example.emergency.offline.pack

import java.io.File

/**
 * A region pack that has been downloaded, verified and installed on this
 * device. Plain data - the source of truth for "what is installed" lives
 * in [com.example.emergency.offline.pack.RegionStore] (Room DB, plan section 6).
 *
 * Path layout under [Context.getFilesDir] (plan section 3):
 *
 * ```
 * filesDir/regions/<id>/
 *   tiles.mbtiles      - vector basemap, served by VectorTileServer
 *   routing/*.rd5      - BRouter graph segments
 *   pois.geojson       - emergency POIs in this region
 *   manifest.json      - PackManifest (per-file sha256, see Manifest.kt)
 * ```
 */
data class RegionPack(
    val id: String,
    val name: String,
    val type: RegionType,
    val bbox: BoundingBox,
    val version: Int,
    val sizeBytes: Long,
    /** Epoch millis the pack was first installed. */
    val installedAt: Long,
    /** Epoch millis the pack was most recently used for tiles or routing. */
    val lastUsedAt: Long,
    /** Absolute path to the pack's root directory on disk. */
    val rootDir: File,
) {
    val tilesFile: File    get() = File(rootDir, "tiles.mbtiles")
    val routingDir: File   get() = File(rootDir, "routing")
    val poisFile: File     get() = File(rootDir, "pois.geojson")
    val manifestFile: File get() = File(rootDir, "manifest.json")

    fun covers(lat: Double, lon: Double): Boolean = bbox.contains(lat, lon)

    companion object {
        const val REGIONS_SUBDIR = "regions"

        fun rootFor(filesDir: File, id: String): File =
            File(filesDir, "$REGIONS_SUBDIR/$id")
    }
}
