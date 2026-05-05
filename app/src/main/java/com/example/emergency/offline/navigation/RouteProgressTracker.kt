package com.example.emergency.offline.navigation

import com.example.emergency.offline.routing.TurnStep
import com.mapbox.mapboxsdk.geometry.LatLng
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Snaps a GPS fix to the active route's polyline and computes the live
 * routing-state numbers the navigation UI reads (plan section 8 step 7.5).
 *
 * Pure math, no Android imports - exhaustively unit-testable. The actual
 * GPS subscription lives in the UI layer; the engine just feeds each new
 * fix in through [snap].
 *
 * One [Progress] snapshot per call. The tracker keeps no internal state
 * between calls - re-snapping the same fix yields the same answer, which
 * makes the engine's tick loop trivially idempotent.
 *
 * Coordinate frame: WGS84 in degrees. Distances are computed in metres
 * via haversine for cross-segment summation and via a small flat-earth
 * approximation for *within* a single segment (sub-mm error at the
 * spans we care about).
 */
object RouteProgressTracker {

    /** All numbers a nav UI / scheduler / off-route detector needs. */
    data class Progress(
        /** Snapped position on the polyline. */
        val snappedPoint: LatLng,
        /** Polyline node index *before* (or equal to) the snap point. */
        val snappedSegmentIndex: Int,
        /** Perpendicular metres from [rawFix] to the polyline at snap. */
        val deviationMeters: Double,
        /** Index of the maneuver the user is currently approaching. */
        val currentStepIndex: Int,
        /** Metres from the snapped point to that next maneuver. */
        val distanceToNextStepMeters: Double,
        /** Metres covered along the route so far. */
        val traveledMeters: Double,
        /** Metres remaining from the snap to the route end. */
        val remainingMeters: Double,
        /** True if the user has reached the final point (within [arrivedThresholdM]). */
        val arrived: Boolean,
    )

    /**
     * @param polyline ordered node sequence the BRouter response returned.
     * @param steps optional turn-by-turn list (Step 7); when present, the
     *   tracker snaps `currentStepIndex` to the nearest upcoming maneuver.
     * @param rawFix the new GPS fix.
     * @param arrivedThresholdM how close to the destination counts as
     *   arrived. 25 m matches Google Maps' walking arrival.
     */
    fun snap(
        polyline: List<LatLng>,
        steps: List<TurnStep>,
        rawFix: LatLng,
        arrivedThresholdM: Double = 25.0,
    ): Progress {
        require(polyline.size >= 2) { "polyline needs at least two points" }

        // --- Find nearest segment --------------------------------------
        var bestSegment = 0
        var bestDist = Double.POSITIVE_INFINITY
        var bestSnap = polyline[0]
        var bestT = 0.0
        for (i in 0 until polyline.lastIndex) {
            val (snap, t) = projectOnSegment(rawFix, polyline[i], polyline[i + 1])
            val d = haversineMeters(rawFix, snap)
            if (d < bestDist) {
                bestDist = d
                bestSnap = snap
                bestSegment = i
                bestT = t
            }
        }

        // --- Distance traveled = sum(prefix segments) + partial of current ---
        var traveled = 0.0
        for (i in 0 until bestSegment) {
            traveled += haversineMeters(polyline[i], polyline[i + 1])
        }
        traveled += haversineMeters(polyline[bestSegment], bestSnap)

        // --- Remaining = current partial + sum(suffix segments) ---------
        var remaining = haversineMeters(bestSnap, polyline[bestSegment + 1])
        for (i in bestSegment + 1 until polyline.lastIndex) {
            remaining += haversineMeters(polyline[i], polyline[i + 1])
        }

        // --- Current step + distance to it ------------------------------
        val (currentStep, distToStep) = nextStep(steps, polyline, bestSegment, bestSnap)

        val arrived = remaining <= arrivedThresholdM
        return Progress(
            snappedPoint = bestSnap,
            snappedSegmentIndex = bestSegment,
            deviationMeters = bestDist,
            currentStepIndex = currentStep,
            distanceToNextStepMeters = distToStep,
            traveledMeters = traveled,
            remainingMeters = remaining,
            arrived = arrived,
        )
    }

    /** Returns (currentStepIndex, metresFromSnapToThatStep). */
    private fun nextStep(
        steps: List<TurnStep>,
        polyline: List<LatLng>,
        snappedSegment: Int,
        snappedPoint: LatLng,
    ): Pair<Int, Double> {
        if (steps.isEmpty()) return -1 to 0.0
        // The "next" maneuver is the first whose indexInPolyline is
        // strictly past where we are now (or at the boundary). If we're
        // already past every step (final maneuver done), report the last.
        val nextIdx = steps.indexOfFirst { it.indexInPolyline > snappedSegment }
        val stepIdx = if (nextIdx < 0) steps.lastIndex else nextIdx
        val step = steps[stepIdx]
        // Distance from snap to step.location:
        //   * whatever's left of the current segment to its end
        //   * + any whole intermediate segments
        //   * + partial into the segment ending at step.indexInPolyline
        if (step.indexInPolyline <= snappedSegment) {
            // Past the step already - report 0.
            return stepIdx to 0.0
        }
        var d = haversineMeters(snappedPoint, polyline[snappedSegment + 1])
        for (i in snappedSegment + 1 until step.indexInPolyline) {
            if (i + 1 > polyline.lastIndex) break
            d += haversineMeters(polyline[i], polyline[i + 1])
        }
        return stepIdx to d
    }

    /**
     * Projects [point] onto the great-circle segment [a]-[b]. Returns
     * (snappedPoint, t) where `t` is the parametric position [0, 1] from
     * `a` (0) to `b` (1). Flat-earth approximation - fine for the
     * sub-kilometre segments BRouter emits.
     */
    private fun projectOnSegment(point: LatLng, a: LatLng, b: LatLng): Pair<LatLng, Double> {
        // Convert to a small local Cartesian frame around `a`. 1 degree of
        // latitude is ~111.32 km; longitude scales by cos(lat).
        val cosLat = cos(a.latitude * PI / 180.0)
        val ax = 0.0
        val ay = 0.0
        val bx = (b.longitude - a.longitude) * cosLat
        val by = (b.latitude - a.latitude)
        val px = (point.longitude - a.longitude) * cosLat
        val py = (point.latitude - a.latitude)
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-15) return a to 0.0
        val rawT = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val t = max(0.0, min(1.0, rawT))
        val snapLon = a.longitude + (b.longitude - a.longitude) * t
        val snapLat = a.latitude + (b.latitude - a.latitude) * t
        return LatLng(snapLat, snapLon) to t
    }

    /** Haversine distance in metres. */
    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = (b.latitude - a.latitude) * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
