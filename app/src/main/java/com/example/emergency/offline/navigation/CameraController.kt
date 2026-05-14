package com.example.emergency.offline.navigation

import com.mapbox.mapboxsdk.geometry.LatLng
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Camera math for the navigation follow-mode (plan section 8 step 7.5).
 *
 * Pure data - the actual `MapboxMap.cameraPosition` mutation lives in
 * the UI layer. The controller turns "current snapped point + heading +
 * speed" into a [Frame] (target, zoom, bearing, tilt) that the UI can
 * apply with `CameraUpdateFactory.newCameraPosition(...)`.
 *
 * Stateful: keeps the previous bearing so we can damp jitter at low
 * speeds. The heuristic for the damping is "below 5 km/h, lock to the
 * previous bearing" - at walking pace GPS heading is essentially noise.
 */
class CameraController(private val profile: NavigationProfile) {

    /** What the UI hands to MapLibre. */
    data class Frame(
        val target: LatLng,
        val zoom: Double,
        val bearing: Double,
        val tilt: Double,
    )

    private var lastBearing: Double = 0.0

    /**
     * @param snapped where the user is on the route.
     * @param speedMps GPS-reported ground speed; non-finite or negative
     *   values are treated as 0.
     * @param gpsHeadingDeg compass-style heading from GPS, 0 = north,
     *   clockwise positive. Pass null when the GPS hasn't fixed a
     *   heading yet (stationary, just acquired).
     */
    fun frameFor(
        snapped: LatLng,
        speedMps: Double,
        gpsHeadingDeg: Double?,
    ): Frame {
        val safeSpeed = if (speedMps.isFinite() && speedMps >= 0) speedMps else 0.0
        val bearing = pickBearing(safeSpeed, gpsHeadingDeg)
        val zoom = zoomFor(safeSpeed)
        val tilt = tiltFor(profile)
        lastBearing = bearing
        return Frame(target = snapped, zoom = zoom, bearing = bearing, tilt = tilt)
    }

    /** Rebuild a frame from a polyline forward direction (no GPS heading). */
    fun frameAlongPolyline(
        snapped: LatLng,
        nextPoint: LatLng,
        speedMps: Double,
    ): Frame {
        val bearing = bearingDeg(snapped, nextPoint)
        return frameFor(snapped, speedMps, bearing)
    }

    /** Below 5 km/h, the heading is unreliable - keep the previous one. */
    private fun pickBearing(speedMps: Double, gpsHeadingDeg: Double?): Double {
        val speedKmh = speedMps * 3.6
        if (speedKmh < SLOW_LOCK_KMH || gpsHeadingDeg == null || !gpsHeadingDeg.isFinite()) {
            return lastBearing
        }
        return ((gpsHeadingDeg % 360) + 360) % 360
    }

    /**
     * Zoom by speed, matching the bands Google Maps uses:
     *   * walk-pace (<= 7 km/h) -> very close (z18)
     *   * bike-pace (<= 25 km/h) -> close (z17)
     *   * urban driving (<= 60 km/h) -> medium (z15.5)
     *   * highway -> wide (z14)
     */
    private fun zoomFor(speedMps: Double): Double {
        val kmh = speedMps * 3.6
        return when {
            kmh <= 7 -> 18.0
            kmh <= 25 -> 17.0
            kmh <= 60 -> 15.5
            else -> 14.0
        }
    }

    /** Tilt by profile - walking gets 2D top-down, vehicles get a pitch. */
    private fun tiltFor(profile: NavigationProfile): Double = when (profile) {
        NavigationProfile.Walking -> 0.0
        NavigationProfile.Biking -> 30.0
        NavigationProfile.Driving -> 45.0
        NavigationProfile.Highway -> 50.0
    }

    /** Initial-heading great-circle bearing in degrees, 0 = north. */
    private fun bearingDeg(from: LatLng, to: LatLng): Double {
        val lat1 = from.latitude * PI / 180
        val lat2 = to.latitude * PI / 180
        val dLon = (to.longitude - from.longitude) * PI / 180
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = atan2(y, x) * 180 / PI
        return ((deg % 360) + 360) % 360
    }

    companion object {
        /** Below this speed, GPS heading is too noisy to trust. */
        const val SLOW_LOCK_KMH = 5.0
    }
}
