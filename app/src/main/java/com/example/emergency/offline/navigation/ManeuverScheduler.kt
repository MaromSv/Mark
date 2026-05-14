package com.example.emergency.offline.navigation

/**
 * Decides *when* to surface the next maneuver to the user (plan section 8 step 7.5).
 *
 * Each travel profile has a "trigger ladder" - a sequence of distance
 * thresholds at which the upcoming maneuver should be re-announced.
 * Walking gets two triggers (one warning + the imminent prompt); driving
 * on the highway gets four. The scheduler tracks which triggers have
 * already fired for the current step so we don't re-announce the same
 * "In 200 m, ..." twice as the user approaches.
 *
 * Pure logic - TTS / banner-swap is done by the caller. (TTS is out of
 * scope per the user's "no TTS" instruction; the scheduler still drives
 * the *visual* banner which is what shows imminent maneuvers.)
 *
 * Single-threaded: callers tick from the navigation engine on the main
 * thread, so internal state needs no synchronisation.
 */
class ManeuverScheduler(private val profile: NavigationProfile) {

    /** A trigger that just fired - caller swaps banner / records announcement. */
    data class Fired(
        val stepIndex: Int,
        val trigger: Trigger,
    )

    /** Entries in the ladder. NOW = "you should be doing the maneuver". */
    sealed class Trigger {
        data class At(val distanceM: Double) : Trigger()
        data object Now : Trigger()
    }

    /**
     * Per-profile ladders. Order matters - far-to-near. Each entry fires
     * exactly once per step. NOW fires when distanceToStep <= 25 m, which
     * is roughly the GPS noise floor - close enough to "you're at the turn"
     * for the banner swap.
     */
    private val ladder: List<Trigger> = when (profile) {
        NavigationProfile.Walking ->
            listOf(Trigger.At(80.0), Trigger.Now)
        NavigationProfile.Biking ->
            listOf(Trigger.At(200.0), Trigger.At(80.0), Trigger.Now)
        NavigationProfile.Driving ->
            listOf(Trigger.At(500.0), Trigger.At(200.0), Trigger.At(50.0), Trigger.Now)
        NavigationProfile.Highway ->
            listOf(Trigger.At(1000.0), Trigger.At(500.0), Trigger.At(200.0), Trigger.Now)
    }

    /** Track which ladder indices have already fired for the active step. */
    private var activeStepIndex: Int = -1
    private val fired: BooleanArray = BooleanArray(ladder.size)

    /**
     * Advance the scheduler for one tick.
     *   * If [currentStepIndex] changed since the last call, the per-step
     *     state resets so the new step's ladder fires from the top.
     *   * Returns the *deepest* trigger crossed since the last tick - if
     *     the user blew past the 200 m and 80 m markers in a single GPS
     *     update (sparse fixes / slow tick rate), we report the inner one.
     */
    fun tick(currentStepIndex: Int, distanceToStepM: Double): Fired? {
        if (currentStepIndex < 0) return null
        if (currentStepIndex != activeStepIndex) {
            activeStepIndex = currentStepIndex
            for (i in fired.indices) fired[i] = false
        }
        var hit: Trigger? = null
        var hitIdx = -1
        for ((i, trig) in ladder.withIndex()) {
            if (fired[i]) continue
            val crossed = when (trig) {
                Trigger.Now -> distanceToStepM <= NOW_THRESHOLD_M
                is Trigger.At -> distanceToStepM <= trig.distanceM
            }
            if (crossed) {
                hit = trig
                hitIdx = i
            }
        }
        if (hit == null) return null
        fired[hitIdx] = true
        return Fired(stepIndex = currentStepIndex, trigger = hit)
    }

    /** Resets back to "no triggers fired" - used when the engine reroutes. */
    fun reset() {
        activeStepIndex = -1
        for (i in fired.indices) fired[i] = false
    }

    companion object {
        /**
         * Distance threshold for the NOW trigger. 25 m matches roughly one
         * crosswalk-length of warning, which is how Google Maps phrases
         * "Turn right now" vs "Turn right in 50 m".
         */
        const val NOW_THRESHOLD_M: Double = 25.0
    }
}

/**
 * Travel profile for the navigation engine. Distinct from BRouter's
 * routing profile (`walk`/`fastbike`/`car-fast`) because it drives
 * UI behaviour (trigger ladder, off-route threshold, camera tilt), not
 * the routing graph - a user might be cycling on a route originally
 * planned for walking, and the *navigation* heuristics should adapt.
 */
enum class NavigationProfile {
    Walking, Biking, Driving, Highway,
}
