package com.example.emergency.offline.pack

/**
 * Picks catalog entries that are useful for a given GPS location
 * (plan section 8 step 5, "Recommended" tab).
 *
 * Strategy: filter to packs whose [BoundingBox] contains the point, then
 * sort by area ascending - smaller bbox = more specific suggestion.
 * "Netherlands" beats "Europe" when both cover Amsterdam; "Randstad"
 * beats both. Cap to [maxResults] so the picker doesn't dump 50 entries
 * on a user standing in central Tokyo.
 *
 * Pure function; no Android imports - easy to unit-test.
 */
object RegionResolver {

    /** Default cap for picker rows; the plan calls for "top 3 covering packs". */
    const val DEFAULT_MAX_RESULTS = 3

    fun coveringPacks(
        catalog: List<CatalogEntry>,
        lat: Double,
        lon: Double,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<CatalogEntry> {
        require(maxResults >= 0) { "maxResults must be non-negative" }
        if (maxResults == 0) return emptyList()
        return catalog
            .filter { it.bbox.contains(lat, lon) }
            .sortedBy { it.bbox.areaKm2() }
            .take(maxResults)
    }

    /**
     * Returns the single best-matching pack - the smallest bbox that
     * covers the point, or null if no pack does. Convenient for the
     * one-tap "Get [Country]" hero card.
     */
    fun bestMatch(
        catalog: List<CatalogEntry>,
        lat: Double,
        lon: Double,
    ): CatalogEntry? = coveringPacks(catalog, lat, lon, maxResults = 1).firstOrNull()

    /** Same as [coveringPacks] but reads the on-device installed list. */
    fun coveringInstalled(
        installed: List<RegionPack>,
        lat: Double,
        lon: Double,
    ): List<RegionPack> =
        installed.filter { it.bbox.contains(lat, lon) }
            .sortedBy { it.bbox.areaKm2() }

    /** True if at least one installed pack contains [lat]/[lon]. */
    fun isCoveredByInstalled(installed: List<RegionPack>, lat: Double, lon: Double): Boolean =
        installed.any { it.bbox.contains(lat, lon) }

    /**
     * For an A->B route request, returns the catalog entries the user
     * needs to install for both endpoints to be inside the union of
     * installed bboxes. If the user is already covered for both endpoints
     * the list is empty.
     *
     * If neither the catalog nor the installed set covers an endpoint,
     * that endpoint contributes nothing - the caller still gets an empty
     * list, but the [OfflineRouter] pre-flight reports the uncovered
     * endpoint via [com.example.emergency.offline.routing.RouteOutcome.OutsideDownloadedRegion.uncoveredEndpoints]
     * so the UI can phrase the message honestly ("we don't have a pack
     * for there yet").
     */
    fun missingForRoute(
        catalog: List<CatalogEntry>,
        installed: List<RegionPack>,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): List<CatalogEntry> {
        val needed = mutableListOf<CatalogEntry>()
        if (!isCoveredByInstalled(installed, fromLat, fromLon)) {
            bestMatch(catalog, fromLat, fromLon)?.let { needed += it }
        }
        if (!isCoveredByInstalled(installed, toLat, toLon)) {
            bestMatch(catalog, toLat, toLon)?.let { entry ->
                if (needed.none { it.id == entry.id }) needed += entry
            }
        }
        return needed
    }
}
