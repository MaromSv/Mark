package com.example.emergency.offline.navigation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OffRouteDetectorTest {

    @Test
    fun firesAfterThreeConsecutiveOffTicks() {
        val d = OffRouteDetector(NavigationProfile.Walking)
        val now = 1_000L
        assertNull(d.tick(deviationMeters = 50.0, nowMs = now))
        assertNull(d.tick(deviationMeters = 60.0, nowMs = now + 1000))
        val fired = d.tick(deviationMeters = 70.0, nowMs = now + 2000)
        assertNotNull(fired)
    }

    @Test
    fun singleSpikeDoesNotFire() {
        val d = OffRouteDetector(NavigationProfile.Walking)
        assertNull(d.tick(50.0, 1_000))
        assertNull(d.tick(2.0, 2_000))    // back on route
        assertNull(d.tick(50.0, 3_000))
        assertNull(d.tick(3.0, 4_000))    // back on route
    }

    @Test
    fun cooldownSuppressesRefires() {
        val d = OffRouteDetector(NavigationProfile.Driving)
        // Drive (threshold 80m). Three consecutive ticks -> fires.
        d.tick(100.0, 1_000)
        d.tick(110.0, 2_000)
        val first = d.tick(120.0, 3_000)
        assertNotNull(first)
        // Even though we're still off-route, the cooldown silences us
        // for 10s after the last fire.
        d.tick(130.0, 4_000)
        d.tick(130.0, 5_000)
        d.tick(130.0, 6_000)
        d.tick(130.0, 12_000) // still inside the 10s cooldown
        assertNull(d.tick(130.0, 12_500))
        // Past the cooldown - needs three new consecutive ticks again.
        val tooSoon = d.tick(130.0, 14_000)
        assertNull(tooSoon)
        d.tick(130.0, 15_000)
        val refired = d.tick(130.0, 16_000)
        assertNotNull(refired)
    }

    @Test
    fun thresholdsScaleByProfile() {
        val walk = OffRouteDetector(NavigationProfile.Walking)
        val drive = OffRouteDetector(NavigationProfile.Driving)
        // 50 m: above walk's 30, below drive's 80.
        walk.tick(50.0, 0); walk.tick(50.0, 1000)
        assertNotNull(walk.tick(50.0, 2000))
        drive.tick(50.0, 0); drive.tick(50.0, 1000)
        assertNull(drive.tick(50.0, 2000))
    }

    @Test
    fun resetClearsCooldown() {
        val d = OffRouteDetector(NavigationProfile.Walking)
        d.tick(50.0, 0); d.tick(50.0, 1000)
        assertNotNull(d.tick(50.0, 2000))
        d.reset()
        // Three more - fires immediately because cooldown is cleared.
        d.tick(50.0, 3_000); d.tick(50.0, 4_000)
        assertNotNull(d.tick(50.0, 5_000))
    }
}
