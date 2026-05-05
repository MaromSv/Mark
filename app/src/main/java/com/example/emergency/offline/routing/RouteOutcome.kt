package com.example.emergency.offline.routing

import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.pack.CatalogEntry

/**
 * Typed result of an offline routing attempt (plan section 6, section 8 step 6).
 *
 * Replaces the old `Result?` shape so the UI can render a *specific*
 * actionable message for every failure mode instead of a generic "Routing
 * failed". This is the user-visible cost of the offline guarantee - when
 * we can't honour a route, we say *why* and (when relevant) *what to do*.
 *
 * Cases:
 *   * [Success] - happy path, polyline + distance + duration ready to render.
 *   * [OutsideDownloadedRegion] - at least one endpoint sits outside the
 *     union of installed pack bboxes. [missingPacks] holds the catalog
 *     entries the user would need to install for the request to succeed,
 *     so the picker can offer a one-tap "Get Germany" button. May be empty
 *     if the catalog itself doesn't carry a pack covering the endpoint.
 *   * [NoRouteFound] - both endpoints are in covered regions but BRouter
 *     can't connect them (disconnected island, profile excludes everything,
 *     too-narrow corridor, etc.). [reason] echoes BRouter's `errorMessage`.
 *   * [GraphLoadFailed] - BRouter threw - corrupt `.rd5`, missing profile,
 *     I/O error. [cause] is the original throwable for log forwarding.
 */
sealed class RouteOutcome {

    data class Success(val result: OfflineRouter.Result) : RouteOutcome()

    data class OutsideDownloadedRegion(
        val missingPacks: List<CatalogEntry>,
        /** Endpoints that fell outside the installed union, for messaging. */
        val uncoveredEndpoints: List<Endpoint>,
    ) : RouteOutcome()

    data class NoRouteFound(val reason: String) : RouteOutcome()

    data class GraphLoadFailed(val cause: Throwable) : RouteOutcome()

    /** Which endpoint was missing - UI uses this to phrase the banner. */
    enum class Endpoint { FROM, TO }

    /**
     * Short user-facing label. Layer-1 message only - the UI is free to
     * augment with action buttons (e.g. "Open picker") based on the
     * concrete subtype.
     */
    fun userMessage(): String = when (this) {
        is Success -> "Route ready"
        is OutsideDownloadedRegion -> {
            val missingNames = missingPacks.joinToString { it.name }
            val noun = if (uncoveredEndpoints.size > 1) "endpoints are" else "endpoint is"
            if (missingPacks.isEmpty()) {
                "Your $noun outside any downloadable region we have a pack for."
            } else {
                "Your $noun outside the installed maps. Install $missingNames to route here."
            }
        }
        is NoRouteFound -> "No route found: $reason"
        is GraphLoadFailed -> "Routing graph error: ${cause.message ?: cause.javaClass.simpleName}"
    }

    /** True for success or recoverable errors (user can act). */
    val isRecoverable: Boolean
        get() = this is Success || this is OutsideDownloadedRegion
}

/**
 * Helper for legacy call sites that still expect a polyline-or-null. Drop
 * this once every caller has migrated to the [RouteOutcome] API.
 */
fun RouteOutcome.successOrNull(): OfflineRouter.Result? =
    (this as? RouteOutcome.Success)?.result

/**
 * Convenience: same as `(this as? OutsideDownloadedRegion)?.missingPacks`,
 * for UI code that wants to one-line the "offer a download" branch.
 */
fun RouteOutcome.missingPacksOrEmpty(): List<CatalogEntry> =
    (this as? RouteOutcome.OutsideDownloadedRegion)?.missingPacks.orEmpty()
