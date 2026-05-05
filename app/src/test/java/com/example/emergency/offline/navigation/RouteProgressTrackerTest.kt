package com.example.emergency.offline.navigation

import com.example.emergency.offline.routing.TurnCommand
import com.example.emergency.offline.routing.TurnStep
import com.mapbox.mapboxsdk.geometry.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressTrackerTest {

    /**
     * Build a polyline of three points spaced ~111 m apart in latitude
     * (1 minute of arc ~= 1.852 km, so 0.001 deg ~= 111 m). Total length
     * ~222 m.
     */
    private val polyline = listOf(
        LatLng(52.000, 5.000),
        LatLng(52.001, 5.000),
        LatLng(52.002, 5.000),
    )

    private fun step(idx: Int) = TurnStep(
        location = polyline[idx],
        command = TurnCommand.TurnLeft,
        distanceToNextMeters = 111.0,
        streetName = "Hoofdstraat",
        indexInPolyline = idx,
    )

    @Test
    fun snapsExactlyOnPolyline() {
        val onLine = LatLng(52.0005, 5.000) // halfway between pts 0 and 1
        val p = RouteProgressTracker.snap(polyline, emptyList(), onLine)
        assertEquals(0, p.snappedSegmentIndex)
        assertTrue("deviation should be near zero, was ${p.deviationMeters}", p.deviationMeters < 1.0)
        // Halfway through first segment + full second segment remaining.
        assertTrue("traveled ~= 55 m, was ${p.traveledMeters}", p.traveledMeters in 50.0..60.0)
        assertTrue("remaining ~= 167 m, was ${p.remainingMeters}", p.remainingMeters in 160.0..175.0)
    }

    @Test
    fun reportsPerpendicularDeviation() {
        // Same midpoint but offset 50m east-ish. 0.0007 deg lon at lat 52
        // ~= 48 m of east shift.
        val offRoute = LatLng(52.0005, 5.0007)
        val p = RouteProgressTracker.snap(polyline, emptyList(), offRoute)
        assertTrue(
            "deviation should be ~48 m, was ${p.deviationMeters}",
            p.deviationMeters in 35.0..60.0,
        )
    }

    @Test
    fun snapsToNearestSegmentWhenPastTheEnd() {
        // Way past the end - should snap to the last node.
        val past = LatLng(52.005, 5.0)
        val p = RouteProgressTracker.snap(polyline, emptyList(), past)
        assertEquals(1, p.snappedSegmentIndex) // last segment
        assertEquals(polyline.last(), p.snappedPoint)
    }

    @Test
    fun arrivedFlagsFiresWhenWithinThreshold() {
        val nearEnd = LatLng(52.00198, 5.000) // ~2 m before final point
        val p = RouteProgressTracker.snap(polyline, emptyList(), nearEnd, arrivedThresholdM = 25.0)
        assertTrue("expected arrived, remaining=${p.remainingMeters}", p.arrived)
    }

    @Test
    fun currentStepIndexAdvancesPastReachedManeuvers() {
        val steps = listOf(step(1), step(2))
        // Just before step 1 - currentStep should be index 0 (first upcoming).
        val before = LatLng(52.0009, 5.000)
        val pBefore = RouteProgressTracker.snap(polyline, steps, before)
        assertEquals(0, pBefore.currentStepIndex)
        assertTrue(
            "distanceToNextStep should be small, was ${pBefore.distanceToNextStepMeters}",
            pBefore.distanceToNextStepMeters in 0.0..15.0,
        )

        // Past step 1, before step 2 -> currentStep advances to 1.
        val between = LatLng(52.0015, 5.000)
        val pBetween = RouteProgressTracker.snap(polyline, steps, between)
        assertEquals(1, pBetween.currentStepIndex)
    }

    @Test
    fun emptyStepsListYieldsMinusOneStepIndex() {
        val p = RouteProgressTracker.snap(polyline, emptyList(), polyline[1])
        assertEquals(-1, p.currentStepIndex)
        assertEquals(0.0, p.distanceToNextStepMeters, 0.001)
    }
}
