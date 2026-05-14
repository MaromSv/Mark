package com.example.emergency.offline

import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.example.emergency.offline.pack.CatalogEntry
import com.example.emergency.offline.pack.RegionPack
import com.example.emergency.offline.pack.RegionResolver
import com.example.emergency.offline.routing.RouteOutcome
import com.example.emergency.offline.routing.TurnStep
import com.example.emergency.offline.routing.VoiceHintParser
import com.mapbox.mapboxsdk.geometry.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thin Kotlin wrapper around [btools.router.RoutingEngine] that produces a
 * route entirely from on-device data - no network involved. Replaces the
 * previous online call to brouter.de.
 *
 * Each call instantiates a fresh [RoutingEngine] (BRouter is not designed
 * to be reused across queries - it owns mutable caches keyed off the
 * active request). The engine reads the routing graph from `.rd5` segment
 * files staged by per-region packs (plan section 3 / section 8 step 4).
 *
 * **Multi-pack handling (plan section 6).** BRouter's [RoutingEngine] takes a
 * single `segmentDir`. To union the segments of every installed pack we
 * maintain a hardlink farm under `filesDir/regions/_active/segments/` -
 * cheap (no byte copy), and rebuilt only when the installed-pack set
 * actually changes (hashed by sorted ids). Adjacent packs that ship the
 * same 5 degx5 deg BRouter tile (e.g. NL and BE both contain `E0_N50.rd5`) are
 * deduplicated by filename; first-installed wins, which is fine because
 * BRouter segments at the same coordinates are byte-identical regardless
 * of which country pack ships them.
 *
 * Profile names map 1:1 to the Mode.brouterProfile values in
 * InteractiveMap (`walk`, `fastbike`, `car-fast`). Profiles are
 * global and stay bundled in the APK under `bundled/brouter-profiles/`.
 */
object OfflineRouter {

    private const val TAG = "OfflineRouter"

    // Per-profile multipliers applied to BRouter's `track.totalSeconds`
    // before we hand the duration to the UI / nav engine. BRouter's
    // kinematic / StdModel give free-flow times that ignore stops at
    // intersections, signals and headwinds, which is fine for routing
    // decisions but consistently under-estimates real-world ETA. These
    // factors were calibrated against Google Maps for short-to-medium
    // urban routes in NL: walk +18 %, bike +55 %. Car-fast already
    // tracks well because per-way `maxspeed` tags clamp it.
    private val TIME_FUDGE_BY_PROFILE = mapOf(
        "walk" to 1.18,
        "trekking" to 1.55,
        "fastbike" to 1.55,
        "car-fast" to 1.0,
    )

    // When the initial route is degenerate (BRouter snapped both endpoints
    // to the same node, or the destination isn't reachable by the active
    // profile - common for car routing into pedestrian zones), probe in a
    // spiral *around* the destination. We try a handful of bearings at each
    // expanding radius and keep the route whose snapped endpoint lands
    // closest to the original POI. Probing around (not toward the user)
    // matters: a toilet inside a pedestrian square should resolve to the
    // road bordering that square, not to a road halfway to the user that
    // happens to pass a different toilet.
    //
    // Layers go small-to-large; the first layer that produces *any* hit
    // wins (no point widening the search if the closer ring already
    // resolved). 4 cardinal + 4 intercardinal = 8 directions per layer.
    private val FALLBACK_RADII_M = listOf(60.0, 150.0, 350.0, 800.0)
    private val FALLBACK_BEARINGS_DEG = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

    data class Result(
        val polyline: List<LatLng>,
        val distanceM: Double,
        val durationS: Double,
        /**
         * Turn-by-turn maneuvers extracted from BRouter's voice hints
         * (plan section 8 step 7). Empty when BRouter didn't emit any (e.g. a
         * single-segment route) or when the parser couldn't read the
         * jar's voice-hint shape - routing keeps working either way.
         */
        val steps: List<TurnStep> = emptyList(),
    )

    /**
     * Routes [from] -> [to] using the union of [installedPacks]'s segments.
     * Pre-flights against pack bboxes and returns a typed [RouteOutcome] for
     * every failure path - see plan section 6 for the full case enumeration.
     *
     * [catalog] is consulted only when the pre-flight fails, so the caller
     * can suggest the right pack(s) to install. Pass an empty list if no
     * suggestion is possible (e.g. no catalog loaded).
     *
     * [activeRoot] is the per-process scratch dir for the merged segments
     * farm - typically `filesDir/regions/_active/`. Created on demand.
     */
    suspend fun route(
        from: LatLng,
        to: LatLng,
        profileName: String,
        profilesDir: File,
        installedPacks: List<RegionPack>,
        catalog: List<CatalogEntry>,
        activeRoot: File,
    ): RouteOutcome = withContext(Dispatchers.IO) {
        val profileFile = File(profilesDir, "$profileName.brf")
        if (!profileFile.exists()) {
            return@withContext RouteOutcome.GraphLoadFailed(
                IllegalStateException("Routing profile missing: $profileName.brf"),
            )
        }

        // --- Pre-flight: are both endpoints inside the union of installed bboxes? ---
        val uncovered = mutableListOf<RouteOutcome.Endpoint>()
        if (!RegionResolver.isCoveredByInstalled(installedPacks, from.latitude, from.longitude)) {
            uncovered += RouteOutcome.Endpoint.FROM
        }
        if (!RegionResolver.isCoveredByInstalled(installedPacks, to.latitude, to.longitude)) {
            uncovered += RouteOutcome.Endpoint.TO
        }
        if (uncovered.isNotEmpty()) {
            val missing = RegionResolver.missingForRoute(
                catalog, installedPacks,
                from.latitude, from.longitude,
                to.latitude, to.longitude,
            )
            Log.d(
                TAG,
                "Pre-flight: uncovered=$uncovered, suggesting ${missing.map { it.id }}",
            )
            return@withContext RouteOutcome.OutsideDownloadedRegion(
                missingPacks = missing,
                uncoveredEndpoints = uncovered,
            )
        }

        // --- Build/refresh merged segments dir ----------------------------
        val segmentsDir = try {
            mergeSegments(installedPacks, activeRoot)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to merge segments", t)
            return@withContext RouteOutcome.GraphLoadFailed(t)
        }
        if (segmentsDir == null || segmentsDir.list()?.isEmpty() != false) {
            return@withContext RouteOutcome.GraphLoadFailed(
                IllegalStateException(
                    "Installed packs ${installedPacks.map { it.id }} have no .rd5 segments",
                ),
            )
        }

        // --- Run BRouter (with degenerate-route fallback) -----------------
        val initial = runBrouterRoute(segmentsDir, profileFile, from, to)
        if (initial is RouteOutcome.Success && initial.result.polyline.size > 1) {
            return@withContext fudgeTime(initial, profileName)
        }

        // Initial attempt failed or returned a degenerate polyline. Most
        // common cause: the destination POI sits in a zone the active
        // profile can't enter (a toilet inside a pedestrian square for
        // car routing). Probe outward from the POI in a spiral; among
        // every probe that produces a real route, keep the one whose
        // snapped endpoint is geographically closest to the original POI.
        // The user sees the original POI marker plus a route that ends
        // at the nearest reachable point - never on a road halfway to a
        // *different* same-category POI somewhere else on the map.
        Log.d(TAG, "Initial route degenerate ($initial); probing around POI")
        for (radiusM in FALLBACK_RADII_M) {
            var bestForLayer: Pair<RouteOutcome.Success, Double>? = null
            for (bearing in FALLBACK_BEARINGS_DEG) {
                val probe = pointAtBearing(to, bearing, radiusM)
                val attempt = runBrouterRoute(segmentsDir, profileFile, from, probe)
                if (attempt is RouteOutcome.Success && attempt.result.polyline.size > 1) {
                    val endpoint = attempt.result.polyline.last()
                    val gap = haversineMeters(endpoint, to)
                    if (bestForLayer == null || gap < bestForLayer.second) {
                        bestForLayer = attempt to gap
                    }
                }
            }
            if (bestForLayer != null) {
                Log.d(
                    TAG,
                    "Spiral fallback hit at radius=${radiusM}m, endpoint ${"%.0f".format(bestForLayer.second)}m from POI",
                )
                return@withContext fudgeTime(bestForLayer.first, profileName)
            }
        }
        // Nothing worked. If the original outcome was already a typed
        // failure (NoRouteFound, etc), bubble it up. If it was a
        // degenerate Success, convert it to NoRouteFound so the UI
        // doesn't render the contradictory "Route ready" message in red
        // for a route that has no usable polyline.
        Log.w(TAG, "Spiral fallback exhausted; surfacing failure to UI")
        when (initial) {
            is RouteOutcome.Success -> RouteOutcome.NoRouteFound(
                "Destination not reachable for $profileName from this location",
            )
            else -> initial
        }
    }

    /**
     * One BRouter routing call. Wrapped so [route] can retry with
     * shifted destinations without redoing pre-flight or segment-merge
     * work. Returns [RouteOutcome.Success] (possibly with a degenerate
     * polyline), [RouteOutcome.NoRouteFound], or [RouteOutcome.GraphLoadFailed].
     */
    private fun runBrouterRoute(
        segmentsDir: File,
        profileFile: File,
        from: LatLng,
        to: LatLng,
    ): RouteOutcome {
        return try {
            // RoutingContext.localFunction is the absolute path to the .brf
            // profile. readGlobalConfig() also expects a global config file
            // alongside it; we don't ship one, so we can't call it. The
            // 5-arg engine constructor invokes profile parsing internally
            // when first needed.
            val rc = RoutingContext().apply {
                localFunction = profileFile.absolutePath
                // Mode 1 = "auto" / locus-style voice hints. Reflectively
                // because older BRouter jars use the typo'd name
                // `turnInstructionMode` while some forks expose `voicehints`
                // - try both before giving up. Plan section 8 step 7.
                setTurnInstructionMode(this, 1)
            }

            val waypoints = listOf(
                makeNode("from", from.latitude, from.longitude),
                makeNode("to", to.latitude, to.longitude),
            )

            val engine = RoutingEngine(
                /* outfileBase  = */ null,
                /* logfileBase  = */ null,
                /* segmentDir   = */ segmentsDir,
                /* waypoints    = */ waypoints,
                /* routingCtx   = */ rc,
            ).apply {
                // BRouter's typo, not ours - silences the chatty System.out
                // logging during routing.
                quite = true
            }

            // 0 = no wall-clock timeout. Routing within a country completes
            // in sub-second for walking distances; long-haul car routes can
            // take a few seconds on cold caches.
            engine.doRun(0L)

            val track = engine.foundTrack
                ?: return RouteOutcome.NoRouteFound(
                    engine.errorMessage ?: "BRouter returned no track and no error message",
                )

            // BRouter stores coordinates as int microdegrees offset by
            // (180, 90) so they fit unsigned ranges. Using the Java
            // accessors explicitly because Kotlin's bean-property heuristic
            // for all-caps prefixes is fragile.
            val polyline = track.nodes.map { el ->
                LatLng(
                    el.getILat() / 1_000_000.0 - 90.0,
                    el.getILon() / 1_000_000.0 - 180.0,
                )
            }
            val steps = VoiceHintParser.parse(track, polyline)
            RouteOutcome.Success(
                Result(
                    polyline = polyline,
                    distanceM = track.distance.toDouble(),
                    durationS = track.totalSeconds.toDouble(),
                    steps = steps,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "BRouter call failed", t)
            RouteOutcome.GraphLoadFailed(t)
        }
    }

    /**
     * Multiplies [success]'s `durationS` by the fudge factor for
     * [profileName], so the UI sees a more realistic ETA than BRouter's
     * raw kinematic estimate. Distance and polyline are untouched.
     */
    private fun fudgeTime(success: RouteOutcome.Success, profileName: String): RouteOutcome.Success {
        val factor = TIME_FUDGE_BY_PROFILE[profileName] ?: return success
        if (factor == 1.0) return success
        return RouteOutcome.Success(
            success.result.copy(durationS = success.result.durationS * factor),
        )
    }

    /**
     * Great-circle destination of moving [distanceM] metres from [from]
     * along compass [bearingDeg] (0 = north, 90 = east). Used to seed the
     * spiral probe around an unreachable POI.
     */
    private fun pointAtBearing(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val r = 6_371_000.0
        val phi1 = from.latitude * PI / 180.0
        val lam1 = from.longitude * PI / 180.0
        val bearing = bearingDeg * PI / 180.0
        val angDist = distanceM / r
        val phi = asin(sin(phi1) * cos(angDist) + cos(phi1) * sin(angDist) * cos(bearing))
        val lam = lam1 + atan2(
            sin(bearing) * sin(angDist) * cos(phi1),
            cos(angDist) - sin(phi1) * sin(phi),
        )
        return LatLng(phi * 180.0 / PI, lam * 180.0 / PI)
    }

    /**
     * Great-circle distance between [a] and [b] in metres. Used to score
     * spiral-probe routes by how close their snapped endpoint lands to
     * the original POI.
     */
    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val phi1 = a.latitude * PI / 180.0
        val phi2 = b.latitude * PI / 180.0
        val dPhi = (b.latitude - a.latitude) * PI / 180.0
        val dLam = (b.longitude - a.longitude) * PI / 180.0
        val s1 = sin(dPhi / 2)
        val s2 = sin(dLam / 2)
        val h = s1 * s1 + cos(phi1) * cos(phi2) * s2 * s2
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    // --- Merged segments farm ------------------------------------------------

    /**
     * Snapshot of which packs the merged dir was last built from. Compared
     * by sorted-id list so a no-op call is cheap.
     */
    @Volatile private var activeSnapshot: List<String> = emptyList()
    @Volatile private var activeDir: File? = null

    /**
     * Returns the merged segments dir, rebuilding only if [installedPacks]
     * has changed since the last call. Returns null when [installedPacks]
     * is empty (no segments to merge).
     */
    @Synchronized
    internal fun mergeSegments(installedPacks: List<RegionPack>, activeRoot: File): File? {
        if (installedPacks.isEmpty()) return null
        val ids = installedPacks.map { it.id }.sorted()
        val cached = activeDir
        if (ids == activeSnapshot && cached != null && cached.exists()) {
            return cached
        }
        val dest = File(activeRoot, "segments")
        // Wipe + relink on every change. Hardlinks are cheap to create and
        // tear down on a single filesystem; correctness > optimisation.
        dest.deleteRecursively()
        check(dest.mkdirs()) { "couldn't create $dest" }

        var linked = 0
        for (pack in installedPacks) {
            val routing = pack.routingDir
            if (!routing.isDirectory) {
                Log.w(TAG, "${pack.id} has no routing/ dir at ${routing.absolutePath}")
                continue
            }
            for (rd5 in routing.listFiles().orEmpty()) {
                if (!rd5.isFile || !rd5.name.endsWith(".rd5")) continue
                val link = File(dest, rd5.name)
                if (link.exists()) continue // first pack wins on dedup
                try {
                    Files.createLink(link.toPath(), rd5.toPath())
                } catch (t: Throwable) {
                    // Hardlinks fail across filesystems; fall back to a
                    // straight copy so routing still works (just costs the
                    // bytes once).
                    Log.w(TAG, "hardlink failed for ${rd5.name}, copying instead", t)
                    rd5.copyTo(link, overwrite = true)
                }
                linked++
            }
        }
        Log.d(TAG, "Merged $linked .rd5 segments from ${ids.size} pack(s) -> ${dest.absolutePath}")
        activeSnapshot = ids
        activeDir = dest
        return dest
    }

    private fun makeNode(name: String, lat: Double, lon: Double): OsmNodeNamed =
        OsmNodeNamed().apply {
            this.name = name
            ilon = ((lon + 180.0) * 1_000_000 + 0.5).toInt()
            ilat = ((lat + 90.0) * 1_000_000 + 0.5).toInt()
        }

    /**
     * Sets the voice-hint mode on a [RoutingContext] in a way that works
     * across BRouter jar versions. Newer 1.7.x exposes
     * `turnInstructionMode: Int`; older / forked builds use `voicehints`.
     * Try fields first, then setters; swallow if neither exists (we still
     * route, just without turn instructions).
     */
    private fun setTurnInstructionMode(rc: RoutingContext, mode: Int) {
        val cls = rc.javaClass
        for (field in listOf("turnInstructionMode", "voicehints")) {
            try {
                cls.getField(field).setInt(rc, mode); return
            } catch (_: NoSuchFieldException) {
                // try next
            } catch (_: IllegalAccessException) {
                // try next
            }
        }
        for (method in listOf("setTurnInstructionMode", "setVoicehints")) {
            try {
                cls.getMethod(method, Int::class.javaPrimitiveType).invoke(rc, mode); return
            } catch (_: NoSuchMethodException) {
                // try next
            }
        }
        Log.w(TAG, "RoutingContext exposes no turnInstructionMode hook - turn-by-turn disabled")
    }
}
