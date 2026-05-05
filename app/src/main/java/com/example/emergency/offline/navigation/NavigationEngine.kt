package com.example.emergency.offline.navigation

import android.util.Log
import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.routing.TurnStep
import com.mapbox.mapboxsdk.geometry.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine for an active navigation session (plan section 8 step 7.5).
 *
 * Lifecycle:
 *
 * ```
 *   Preview --- start() --> Navigating --- tick() --> Navigating
 *                              |  ^                       |
 *                              |  |                       |
 *                  reroute() --+  +-- newRoute()          v
 *                              v                       Arrived
 *                          Rerouting
 * ```
 *
 *   * **Preview**: route is loaded but no GPS feed yet - the user is
 *     looking at the polyline, distance, ETA on the route-info card.
 *   * **Navigating**: each `tick(location)` runs the route-progress
 *     tracker, the maneuver scheduler, and the off-route detector. The
 *     UI binds to [state] for the live banner / ETA / off-route banner.
 *   * **Rerouting**: an off-route event has fired and the engine is
 *     waiting on a fresh route from the caller (we don't own the
 *     [OfflineRouter] call here - the UI does, because re-routing needs
 *     access to RegionStore + CatalogProvider too).
 *   * **Arrived**: the snapped point is within `arrivedThresholdM` of
 *     the destination. Terminal - caller resets to Preview to start a
 *     new session.
 *
 * Single-threaded: callers tick from one place (the UI's location
 * collector). No internal locking.
 */
class NavigationEngine(
    private val initialRoute: OfflineRouter.Result,
    private val profile: NavigationProfile,
    private val onRerouteRequested: () -> Unit = {},
) {
    private val _state = MutableStateFlow<NavigationState>(NavigationState.Preview(initialRoute))
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val scheduler = ManeuverScheduler(profile)
    private val offRoute = OffRouteDetector(profile)
    private val camera = CameraController(profile)

    /** The currently-active route - replaced on [applyNewRoute]. */
    private var route: OfflineRouter.Result = initialRoute

    /** Move from Preview -> Navigating. Idempotent. */
    fun start() {
        val current = _state.value
        if (current is NavigationState.Navigating || current is NavigationState.Rerouting) return
        scheduler.reset()
        offRoute.reset()
        _state.value = NavigationState.Navigating(
            route = route,
            progress = null,
            firedTrigger = null,
            offRouteEvent = null,
            cameraFrame = null,
        )
    }

    /** Caller passes a fresh GPS fix every update. */
    fun tick(
        rawFix: LatLng,
        speedMps: Double = 0.0,
        gpsHeadingDeg: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val active = _state.value as? NavigationState.Navigating
            ?: _state.value as? NavigationState.Rerouting
            ?: return // Preview / Arrived - no-op

        val progress = RouteProgressTracker.snap(
            polyline = route.polyline,
            steps = route.steps,
            rawFix = rawFix,
        )

        if (progress.arrived) {
            _state.value = NavigationState.Arrived(route)
            return
        }

        val fired = scheduler.tick(progress.currentStepIndex, progress.distanceToNextStepMeters)
        val off = offRoute.tick(progress.deviationMeters, nowMs)
        val nextPoint = route.polyline.getOrNull(progress.snappedSegmentIndex + 1)
        val frame = if (nextPoint != null) {
            camera.frameAlongPolyline(progress.snappedPoint, nextPoint, speedMps)
        } else {
            camera.frameFor(progress.snappedPoint, speedMps, gpsHeadingDeg)
        }

        if (off != null && active is NavigationState.Navigating) {
            // Hand the trigger to the host UI; transition to Rerouting
            // and let it re-route via OfflineRouter, then call
            // applyNewRoute() with the result.
            Log.d(
                TAG,
                "Off-route: deviation=${off.deviationM.toInt()}m > ${off.thresholdM.toInt()}m; requesting reroute",
            )
            _state.value = NavigationState.Rerouting(
                route = route,
                progress = progress,
                offRouteEvent = off,
                cameraFrame = frame,
            )
            onRerouteRequested()
            return
        }

        _state.value = NavigationState.Navigating(
            route = route,
            progress = progress,
            firedTrigger = fired,
            offRouteEvent = off,
            cameraFrame = frame,
        )
    }

    /**
     * Caller invokes this after re-routing succeeds; engine swaps in the
     * new polyline + steps and goes back to Navigating.
     */
    fun applyNewRoute(newRoute: OfflineRouter.Result) {
        route = newRoute
        scheduler.reset()
        offRoute.reset()
        _state.value = NavigationState.Navigating(
            route = newRoute,
            progress = null,
            firedTrigger = null,
            offRouteEvent = null,
            cameraFrame = null,
        )
    }

    /** Caller invokes this when the reroute attempt failed. */
    fun rerouteFailed(reason: String) {
        _state.value = NavigationState.RerouteFailed(route, reason)
    }

    companion object {
        private const val TAG = "NavigationEngine"
    }
}

/**
 * Sealed snapshot of the engine's state. UI binds to one [StateFlow] of
 * these so it never has to peek into the engine's internals.
 */
sealed class NavigationState {
    abstract val route: OfflineRouter.Result

    data class Preview(override val route: OfflineRouter.Result) : NavigationState()

    data class Navigating(
        override val route: OfflineRouter.Result,
        /** null on the very first tick (before any GPS fix). */
        val progress: RouteProgressTracker.Progress?,
        /** Fires once per ladder rung; null on ticks where nothing crossed. */
        val firedTrigger: ManeuverScheduler.Fired?,
        /** Non-null on the tick a sustained deviation broke through. */
        val offRouteEvent: OffRouteDetector.Event?,
        val cameraFrame: CameraController.Frame?,
    ) : NavigationState()

    data class Rerouting(
        override val route: OfflineRouter.Result,
        val progress: RouteProgressTracker.Progress,
        val offRouteEvent: OffRouteDetector.Event,
        val cameraFrame: CameraController.Frame?,
    ) : NavigationState()

    data class RerouteFailed(
        override val route: OfflineRouter.Result,
        val reason: String,
    ) : NavigationState()

    data class Arrived(override val route: OfflineRouter.Result) : NavigationState()
}
