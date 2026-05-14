package com.example.emergency.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.example.emergency.agent.Tool
import com.example.emergency.agent.ToolResult
import com.example.emergency.offline.OfflineAssets
import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.pack.CatalogProvider
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.offline.routing.RouteOutcome
import com.example.emergency.offline.routing.StepFormatter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mapbox.mapboxsdk.geometry.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Real routing for the chat agent (plan section 8 step 8). Replaces the
 * hardcoded "shelter" branch that used to live in [GpsLocationTool] -
 * destinations are routed through [OfflineRouter] against the actually-
 * installed packs, with honest [RouteOutcome] error handling.
 *
 * **Destination accepted forms** (parsed in this order):
 *   1. `"<lat>,<lon>"` - explicit coordinates, e.g. `"52.374,4.890"`.
 *   2. A POI category supported by [PoiRepository] / [FindNearestTool] -
 *      we resolve it to the nearest matching POI and route there. Most
 *      common LLM ask: "take me to the nearest hospital".
 *
 * Geocoded place names (e.g. "Anne Frank House") are **not supported**
 * - the app has no geocoder. The LLM is instructed in the system prompt
 * to either pass coords or pick a category from the catalog.
 *
 * **Profile param**: `walk` (default) | `bike` | `drive`. Maps to
 * BRouter's `walk` / `fastbike` / `car-fast`.
 *
 * **Result JSON shape**: includes `name`/`category`/`lat`/`lon` so the
 * existing chat->map handoff (`AppNavHost.parseFindNearestDestination`)
 * picks it up unchanged and renders the polyline + lets the user tap
 * "Start navigation" - same UX as `find_nearest`, plus distance / ETA /
 * step count for the LLM's follow-up summary.
 */
class RouteTool(private val context: Context) {

    fun getTool(): Tool = Tool(
        name = "route_to",
        description =
            "Routes from the user's GPS to a destination. Required param: " +
                "destination (either 'lat,lon' coords like '52.374,4.890', or one " +
                "of the find_nearest categories like 'hospital'/'pharmacy'/'shelter'). " +
                "Optional param: profile (walk|bike|drive, default walk). " +
                "Returns JSON {name, category, lat, lon, distance_m, duration_s, " +
                "step_count, first_steps[]}; opens the map with the polyline drawn. " +
                "On failure (no pack covering the destination) returns an honest " +
                "error and lists the pack ids the user needs to install.",
        execute = ::execute,
    )

    private suspend fun execute(params: Map<String, String>): ToolResult {
        val rawDestination = params["destination"]?.trim()
            ?: return ToolResult(false, "", "Missing required param 'destination'.")
        val profileName = (params["profile"] ?: "walk").lowercase()
        val brouterProfile = mapProfile(profileName)
            ?: return ToolResult(
                false, "",
                "Unknown profile '$profileName'. Use walk, bike, or drive.",
            )

        // Resolve destination -> (name, category, lat, lon).
        val dest = resolveDestination(rawDestination)
            ?: return ToolResult(
                false, "",
                "Couldn't resolve destination '$rawDestination'. Use 'lat,lon' coords " +
                    "or a known POI category (e.g. hospital, pharmacy, shelter).",
            )

        // GPS for the origin.
        val origin = getCurrentLocation()
            ?: return ToolResult(
                false, "",
                "GPS not ready - can't route without a current position.",
            )

        // Pull installed packs + catalog + bundled brouter profiles dir.
        val store = RegionStore.get(context)
        val catalog = CatalogProvider.get(context).entries
        val installed = store.list()
        val profilesDir = OfflineAssets.pathsFor(context).profilesDir
        val activeRoot = File(context.filesDir, "regions/_active")

        val outcome = OfflineRouter.route(
            from = LatLng(origin.latitude, origin.longitude),
            to = LatLng(dest.lat, dest.lon),
            profileName = brouterProfile,
            profilesDir = profilesDir,
            installedPacks = installed,
            catalog = catalog,
            activeRoot = activeRoot,
        )
        return formatOutcome(dest, outcome)
    }

    private fun formatOutcome(dest: ResolvedDestination, outcome: RouteOutcome): ToolResult =
        Companion.formatOutcome(dest, outcome)

    // --- Destination resolution ------------------------------------------

    internal data class ResolvedDestination(
        val name: String,
        val category: String,
        val lat: Double,
        val lon: Double,
    )

    /**
     * Turns a raw `destination` param into a [ResolvedDestination]. Coord
     * form wins outright; otherwise falls through to POI lookup.
     */
    private suspend fun resolveDestination(raw: String): ResolvedDestination? {
        parseCoords(raw)?.let { (lat, lon) ->
            return ResolvedDestination(
                name = "Coordinates",
                category = "place",
                lat = lat,
                lon = lon,
            )
        }
        // Fall through: treat as POI category.
        val origin = getCurrentLocation() ?: return null
        val category = normalizeCategory(raw) ?: return null
        val poi = PoiRepository.findNearest(context, category, origin.latitude, origin.longitude)
            ?: return null
        return ResolvedDestination(
            name = poi.name,
            category = poi.category,
            lat = poi.lat,
            lon = poi.lon,
        )
    }

    companion object {
        private const val MAX_STEPS_IN_RESULT = 5

        /**
         * Pure result formatter - no Android dependencies. Lives on the
         * companion so tests can call it without instantiating the tool.
         */
        internal fun formatOutcome(dest: ResolvedDestination, outcome: RouteOutcome): ToolResult =
            when (outcome) {
                is RouteOutcome.Success -> {
                    val r = outcome.result
                    val firstSteps = StepFormatter.formatAll(r.steps).take(MAX_STEPS_IN_RESULT)
                    val json = JSONObject().apply {
                        put("name", dest.name)
                        put("category", dest.category)
                        put("lat", dest.lat)
                        put("lon", dest.lon)
                        put("distance_m", r.distanceM.toInt())
                        put("duration_s", r.durationS.toInt())
                        put("step_count", r.steps.size)
                        put("first_steps", JSONArray(firstSteps))
                    }
                    ToolResult(success = true, data = json.toString())
                }
                is RouteOutcome.OutsideDownloadedRegion -> {
                    val missing = outcome.missingPacks.map { it.id }
                    val data = JSONObject().apply {
                        put("error", "outside_downloaded_region")
                        put("message", outcome.userMessage())
                        put("missing_packs", JSONArray(missing))
                        put("uncovered", JSONArray(outcome.uncoveredEndpoints.map { it.name }))
                    }.toString()
                    ToolResult(
                        success = false,
                        data = data,
                        error = outcome.userMessage(),
                    )
                }
                is RouteOutcome.NoRouteFound -> ToolResult(
                    success = false,
                    data = "",
                    error = "No route found: ${outcome.reason}",
                )
                is RouteOutcome.GraphLoadFailed -> ToolResult(
                    success = false,
                    data = "",
                    error = "Routing graph error: ${outcome.cause.message ?: "unknown"}",
                )
            }

        /** Pure helper exposed for tests. */
        internal fun parseCoords(raw: String): Pair<Double, Double>? {
            val trimmed = raw.trim().removePrefix("(").removeSuffix(")")
            val parts = trimmed.split(",", limit = 2)
            if (parts.size != 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }

        /** Pure helper exposed for tests. */
        internal fun mapProfile(name: String): String? = when (name.lowercase()) {
            "walk", "walking", "foot", "hiking" -> "walk"
            "trekking" -> "trekking"
            "bike", "biking", "cycle", "cycling", "fastbike" -> "fastbike"
            "drive", "driving", "car", "car-fast" -> "car-fast"
            else -> null
        }

        /**
         * Reuses the same alias map FindNearestTool keeps so the LLM can
         * pass any of the synonyms it already learned for find_nearest.
         */
        internal fun normalizeCategory(input: String): String? {
            val cleaned = input.trim().lowercase().replace(' ', '_')
            if (cleaned in SUPPORTED_CATEGORIES) return cleaned
            return CATEGORY_ALIASES[cleaned]
        }

        private val SUPPORTED_CATEGORIES = setOf(
            "hospital", "doctor", "first_aid", "aed", "pharmacy", "police", "fire",
            "shelter", "water", "toilet", "metro", "parking_underground", "bunker",
            "fuel", "supermarket", "atm", "phone", "school", "community", "worship",
        )

        // Trimmed copy of FindNearestTool.aliases - keeps the two tools in
        // sync without a circular dep. Update both if a new alias lands.
        private val CATEGORY_ALIASES = mapOf(
            "defibrillator" to "aed",
            "emergency_room" to "hospital",
            "er" to "hospital",
            "drugstore" to "pharmacy",
            "chemist" to "pharmacy",
            "police_station" to "police",
            "fire_station" to "fire",
            "gas_station" to "fuel",
            "petrol_station" to "fuel",
            "wc" to "toilet",
            "drinking_water" to "water",
        )
    }

    // --- GPS helper (mirrors FindNearestTool) ----------------------------

    private suspend fun getCurrentLocation(): Location? {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        return suspendCancellableCoroutine { continuation ->
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            } catch (e: SecurityException) {
                continuation.resume(null)
            }
            continuation.invokeOnCancellation { cts.cancel() }
        }
    }
}
