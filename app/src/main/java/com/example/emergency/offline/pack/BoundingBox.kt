package com.example.emergency.offline.pack

import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Geographic bounding box in WGS84, expressed as `[west, south, east, north]`
 * - the same order used by GeoJSON, Mapbox/MapLibre and the manifest format
 * (see plan section 6). Construction validates the box is non-degenerate and in
 * range; out-of-range or inverted coordinates fail loudly because every
 * downstream consumer (DensityGrid, RegionResolver, OfflineRouter pre-flight)
 * assumes a sane box.
 *
 * Antimeridian-crossing boxes are not supported. Callers that need them must
 * split into two boxes; we don't have any such regions on the roadmap.
 */
data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west < east) { "west ($west) must be < east ($east)" }
        require(south < north) { "south ($south) must be < north ($north)" }
        require(west in -180.0..180.0 && east in -180.0..180.0) {
            "longitudes out of range: $west, $east"
        }
        require(south in -90.0..90.0 && north in -90.0..90.0) {
            "latitudes out of range: $south, $north"
        }
    }

    fun contains(lat: Double, lon: Double): Boolean =
        lon in west..east && lat in south..north

    fun intersect(other: BoundingBox): BoundingBox? {
        val w = max(west, other.west); val e = min(east, other.east)
        val s = max(south, other.south); val n = min(north, other.north)
        return if (w >= e || s >= n) null else BoundingBox(w, s, e, n)
    }

    /**
     * Area in km^2, using equirectangular approximation at the midpoint
     * latitude. Good to ~1 % for boxes up to ~5 deg tall, which covers every
     * sub-country region the picker can produce.
     */
    fun areaKm2(): Double {
        val midLat = (south + north) / 2.0
        val widthKm =
            KM_PER_DEG_LON_AT_EQUATOR * cos(midLat * PI / 180.0) * (east - west)
        val heightKm = KM_PER_DEG_LAT * (north - south)
        return widthKm * heightKm
    }

    fun toJson(): JSONArray = JSONArray().apply {
        put(west); put(south); put(east); put(north)
    }

    companion object {
        const val KM_PER_DEG_LAT = 111.320
        const val KM_PER_DEG_LON_AT_EQUATOR = 111.320

        fun fromJson(arr: JSONArray): BoundingBox {
            require(arr.length() == 4) {
                "bbox must have 4 elements, got ${arr.length()}"
            }
            return BoundingBox(
                west = arr.getDouble(0),
                south = arr.getDouble(1),
                east = arr.getDouble(2),
                north = arr.getDouble(3),
            )
        }
    }
}
