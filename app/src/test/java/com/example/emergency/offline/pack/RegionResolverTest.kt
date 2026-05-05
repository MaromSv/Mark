package com.example.emergency.offline.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionResolverTest {

    private fun entry(id: String, bbox: BoundingBox) = CatalogEntry(
        id = id, name = id.uppercase(), type = RegionType.COUNTRY,
        bbox = bbox, sizeBytes = 1, version = 1,
        url = "https://x/$id.tar.gz", sha256 = "0".repeat(64),
    )

    @Test
    fun smallerCoveringBboxWinsOverLargerOne() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val randstad = entry("nl-randstad", BoundingBox(4.2, 51.85, 5.2, 52.55))
        val mc = entry("mc", BoundingBox(7.39, 43.72, 7.45, 43.76))
        val amsterdam = doubleArrayOf(52.37, 4.90)
        val result = RegionResolver.coveringPacks(
            listOf(nl, randstad, mc),
            lat = amsterdam[0], lon = amsterdam[1],
        )
        assertEquals(listOf("nl-randstad", "nl"), result.map { it.id })
    }

    @Test
    fun coveringPacksRespectsMaxResults() {
        val a = entry("a", BoundingBox(0.0, 0.0, 10.0, 10.0))
        val b = entry("b", BoundingBox(2.0, 2.0, 8.0, 8.0))
        val c = entry("c", BoundingBox(3.0, 3.0, 6.0, 6.0))
        val res = RegionResolver.coveringPacks(listOf(a, b, c), lat = 4.0, lon = 4.0, maxResults = 2)
        assertEquals(listOf("c", "b"), res.map { it.id })
    }

    @Test
    fun bestMatchReturnsNullWhenNothingCovers() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        // Tokyo
        assertNull(RegionResolver.bestMatch(listOf(nl), lat = 35.68, lon = 139.69))
    }

    @Test
    fun bestMatchPicksTightestCoveringEntry() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val randstad = entry("nl-randstad", BoundingBox(4.2, 51.85, 5.2, 52.55))
        val best = RegionResolver.bestMatch(
            listOf(nl, randstad),
            lat = 52.37, lon = 4.90,
        )
        assertEquals("nl-randstad", best?.id)
    }

    @Test
    fun zeroMaxResultsReturnsEmpty() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        assertEquals(
            emptyList<CatalogEntry>(),
            RegionResolver.coveringPacks(listOf(nl), 52.0, 5.0, maxResults = 0),
        )
    }

    // --- Multi-region helpers (Step 6) --------------------------------------

    private fun installed(id: String, bbox: BoundingBox): RegionPack = RegionPack(
        id = id, name = id.uppercase(), type = RegionType.COUNTRY,
        bbox = bbox, version = 1, sizeBytes = 1, installedAt = 0, lastUsedAt = 0,
        rootDir = java.io.File("/tmp/$id"),
    )

    @Test
    fun isCoveredByInstalledReportsUnion() {
        val nl = installed("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val be = installed("be", BoundingBox(2.5, 49.5, 6.4, 51.5))
        val packs = listOf(nl, be)
        // Brussels: BE
        assertEquals(true, RegionResolver.isCoveredByInstalled(packs, 50.85, 4.35))
        // Amsterdam: NL
        assertEquals(true, RegionResolver.isCoveredByInstalled(packs, 52.37, 4.90))
        // Berlin: neither
        assertEquals(false, RegionResolver.isCoveredByInstalled(packs, 52.52, 13.40))
    }

    @Test
    fun missingForRouteReturnsBothEndpointsWhenNeitherCovered() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val de = entry("de", BoundingBox(5.86, 47.27, 15.05, 55.06))
        val catalog = listOf(nl, de)
        // No packs installed - both endpoints contribute.
        val missing = RegionResolver.missingForRoute(
            catalog = catalog, installed = emptyList(),
            fromLat = 52.37, fromLon = 4.90,    // Amsterdam (NL)
            toLat = 52.52, toLon = 13.40,        // Berlin (DE)
        )
        assertEquals(setOf("nl", "de"), missing.map { it.id }.toSet())
    }

    @Test
    fun missingForRouteSkipsAlreadyInstalled() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val de = entry("de", BoundingBox(5.86, 47.27, 15.05, 55.06))
        val catalog = listOf(nl, de)
        val installed = listOf(installed("nl", nl.bbox))
        val missing = RegionResolver.missingForRoute(
            catalog = catalog, installed = installed,
            fromLat = 52.37, fromLon = 4.90,    // Amsterdam (NL - installed)
            toLat = 52.52, toLon = 13.40,        // Berlin (DE - not installed)
        )
        assertEquals(listOf("de"), missing.map { it.id })
    }

    @Test
    fun missingForRouteEmptyWhenBothCovered() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val installed = listOf(installed("nl", nl.bbox))
        val missing = RegionResolver.missingForRoute(
            catalog = listOf(nl), installed = installed,
            fromLat = 52.37, fromLon = 4.90,    // Amsterdam
            toLat = 52.09, toLon = 5.12,        // Utrecht
        )
        assertEquals(emptyList<CatalogEntry>(), missing)
    }

    @Test
    fun missingForRouteHonoursDeduplicationWhenSameEndpointPack() {
        val nl = entry("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val missing = RegionResolver.missingForRoute(
            catalog = listOf(nl), installed = emptyList(),
            // Both endpoints in NL, same suggested pack.
            fromLat = 52.37, fromLon = 4.90,
            toLat = 52.09, toLon = 5.12,
        )
        assertEquals(listOf("nl"), missing.map { it.id })
    }
}
