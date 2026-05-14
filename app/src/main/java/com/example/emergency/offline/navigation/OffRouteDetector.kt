package com.example.emergency.offline.navigation

/**
 * Decides when to trigger a reroute (plan section 8 step 7.5).
 *
 * Rule: perpendicular deviation from the polyline exceeds the per-profile
 * threshold for *three consecutive ticks*. Single bad GPS fixes (urban
 * canyons, multipath) get filtered out. After firing, a 10-second
 * cooldown prevents reroute storms while the engine is recomputing.
 *
 * Stateful - single instance per active route. `tick(...)` returns
 * non-null only on the firing tick; subsequent ticks during the cooldown
 * return null, and once the cooldown elapses the consecutive-tick counter
 * resets so a fresh sustained deviation can fire again.
 */
class OffRouteDetector(private val profile: NavigationProfile) {

    /** Per-profile deviation threshold in metres. */
    private val thresholdM: Double = when (profile) {
        NavigationProfile.Walking -> 30.0
        NavigationProfile.Biking -> 50.0
        NavigationProfile.Driving -> 80.0
        NavigationProfile.Highway -> 80.0
    }

    private var consecutiveOff = 0
    private var lastFiredAtMs = 0L

    /**
     * Feed the latest [deviationMeters] (from RouteProgressTracker) and
     * the wall clock [nowMs]. Returns a non-null event on the tick that
     * crosses the consecutive-tick threshold; null otherwise.
     */
    fun tick(deviationMeters: Double, nowMs: Long): Event? {
        val inCooldown = nowMs - lastFiredAtMs < COOLDOWN_MS
        if (inCooldown) {
            // Don't accumulate while we're cooling down - once the engine
            // has issued a reroute, the next polyline arrives and we
            // start fresh.
            consecutiveOff = 0
            return null
        }

        if (deviationMeters > thresholdM) {
            consecutiveOff += 1
        } else {
            consecutiveOff = 0
        }

        if (consecutiveOff >= REQUIRED_CONSECUTIVE_TICKS) {
            lastFiredAtMs = nowMs
            consecutiveOff = 0
            return Event(thresholdM = thresholdM, deviationM = deviationMeters)
        }
        return null
    }

    /**
     * Hard reset - call after the engine has applied a fresh polyline so
     * the detector doesn't immediately re-fire against the new geometry.
     */
    fun reset() {
        consecutiveOff = 0
        lastFiredAtMs = 0L
    }

    data class Event(val thresholdM: Double, val deviationM: Double)

    companion object {
        const val REQUIRED_CONSECUTIVE_TICKS = 3
        const val COOLDOWN_MS = 10_000L
    }
}
