package com.example.emergency.offline.navigation

import com.example.emergency.offline.routing.TurnCommand
import com.mapbox.mapboxsdk.geometry.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthesises the next turn from the route polyline alone, for routes
 * where BRouter didn't emit voice hints (single-segment beelines, parser
 * misses, custom profiles). The maneuver banner falls back to this when
 * `route.steps` is empty so the user still gets a left/right cue.
 *
 * Algorithm: walk forward from the user's snapped position; the first
 * polyline node where the bearing change exceeds [minDeltaDeg] is the
 * next turn. If none exists, the destination itself is reported as the
 * next event (Arrive).
 *
 * Pure math, no Android imports - exhaustively unit-testable.
 */
object PolylineTurnSynthesizer {

    data class SyntheticTurn(
        val command: TurnCommand,
        val location: LatLng,
        val distanceMeters: Double,
        /** Polyline node index of this turn (or last index for Arrive). */
        val nodeIndex: Int,
    )

    /**
     * Returns the first significant bend ahead of [snappedPoint] on
     * [polyline], or an Arrive event if no bend remains. Returns null
     * only on a degenerate polyline (< 2 points).
     */
    fun nextTurn(
        polyline: List<LatLng>,
        snappedPoint: LatLng,
        snappedSegmentIndex: Int,
        minDeltaDeg: Double = 25.0,
    ): SyntheticTurn? {
        if (polyline.size < 2) return null
        val nextNodeIdx = (snappedSegmentIndex + 1).coerceAtMost(polyline.lastIndex)
        var dist = haversineMeters(snappedPoint, polyline[nextNodeIdx])
        // Walk through interior nodes (those with both an incoming and
        // outgoing segment). The last node is the destination - handled
        // by the Arrive fallback after the loop.
        for (i in nextNodeIdx until polyline.lastIndex) {
            if (i - 1 < 0) continue
            val deltaDeg = bearingDeltaDeg(
                polyline[i - 1], polyline[i], polyline[i + 1],
            )
            if (abs(deltaDeg) >= minDeltaDeg) {
                return SyntheticTurn(
                    command = classifyBend(deltaDeg),
                    location = polyline[i],
                    distanceMeters = dist,
                    nodeIndex = i,
                )
            }
            dist += haversineMeters(polyline[i], polyline[i + 1])
        }
        return SyntheticTurn(
            command = TurnCommand.Arrive,
            location = polyline.last(),
            distanceMeters = dist,
            nodeIndex = polyline.lastIndex,
        )
    }

    /**
     * The turn AFTER [first] - used to populate the "Then..." secondary
     * line on the maneuver banner.
     */
    fun turnAfter(
        polyline: List<LatLng>,
        first: SyntheticTurn,
        minDeltaDeg: Double = 25.0,
    ): SyntheticTurn? {
        if (first.command == TurnCommand.Arrive) return null
        if (polyline.size < 2) return null
        var dist = 0.0
        for (i in (first.nodeIndex + 1) until polyline.lastIndex) {
            if (i - 1 < 0) continue
            val deltaDeg = bearingDeltaDeg(
                polyline[i - 1], polyline[i], polyline[i + 1],
            )
            if (abs(deltaDeg) >= minDeltaDeg) {
                return SyntheticTurn(
                    command = classifyBend(deltaDeg),
                    location = polyline[i],
                    distanceMeters = dist,
                    nodeIndex = i,
                )
            }
            dist += haversineMeters(polyline[i], polyline[i + 1])
        }
        return null
    }

    private fun classifyBend(deltaDeg: Double): TurnCommand = when {
        deltaDeg in -15.0..15.0       -> TurnCommand.Continue
        deltaDeg in 15.0..45.0        -> TurnCommand.TurnSlightRight
        deltaDeg in 45.0..135.0       -> TurnCommand.TurnRight
        deltaDeg in 135.0..165.0      -> TurnCommand.TurnSharpRight
        deltaDeg >= 165.0             -> TurnCommand.UTurnRight
        deltaDeg in -45.0..-15.0      -> TurnCommand.TurnSlightLeft
        deltaDeg in -135.0..-45.0     -> TurnCommand.TurnLeft
        deltaDeg in -165.0..-135.0    -> TurnCommand.TurnSharpLeft
        deltaDeg <= -165.0            -> TurnCommand.UTurnLeft
        else                          -> TurnCommand.Continue
    }

    /** Forward bearing from [a] to [b] in degrees [0, 360). */
    private fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = atan2(y, x) * 180.0 / PI
        return (bearing + 360.0) % 360.0
    }

    /**
     * Bearing change at node [b] from segment [a]->[b] to [b]->[c].
     * Positive = right turn, negative = left turn, in degrees [-180, 180].
     */
    private fun bearingDeltaDeg(a: LatLng, b: LatLng, c: LatLng): Double {
        val incoming = bearingDeg(a, b)
        val outgoing = bearingDeg(b, c)
        var delta = outgoing - incoming
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }

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
