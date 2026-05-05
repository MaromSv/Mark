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
 * InteractiveMap (`trekking`, `fastbike`, `car-fast`). Profiles are
 * global and stay bundled in the APK under `bundled/brouter-profiles/`.
 */
object OfflineRouter {

    private const val TAG = "OfflineRouter"

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

        // --- Run BRouter --------------------------------------------------
        try {
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
                ?: return@withContext RouteOutcome.NoRouteFound(
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
            Log.e(TAG, "BRouter failed for profile=$profileName", t)
            RouteOutcome.GraphLoadFailed(t)
        }
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
