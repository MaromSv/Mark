package com.example.emergency.offline.routing

import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.pack.BoundingBox
import com.example.emergency.offline.pack.CatalogEntry
import com.example.emergency.offline.pack.RegionType
import com.mapbox.mapboxsdk.geometry.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOutcomeTest {

    private fun entry(id: String, name: String) = CatalogEntry(
        id = id, name = name, type = RegionType.COUNTRY,
        bbox = BoundingBox(0.0, 0.0, 1.0, 1.0),
        sizeBytes = 1, version = 1,
        url = "https://x/$id", sha256 = "0".repeat(64),
    )

    @Test
    fun userMessageMentionsSuggestedPacksWhenOutsideAndKnown() {
        val outcome = RouteOutcome.OutsideDownloadedRegion(
            missingPacks = listOf(entry("de", "Germany")),
            uncoveredEndpoints = listOf(RouteOutcome.Endpoint.TO),
        )
        val msg = outcome.userMessage()
        assertTrue("$msg should mention Germany", "Germany" in msg)
        assertTrue("$msg should suggest install action", "Install" in msg)
    }

    @Test
    fun userMessageHandlesUnknownDestinationGracefully() {
        val outcome = RouteOutcome.OutsideDownloadedRegion(
            missingPacks = emptyList(),
            uncoveredEndpoints = listOf(RouteOutcome.Endpoint.FROM, RouteOutcome.Endpoint.TO),
        )
        val msg = outcome.userMessage()
        // No catalog match - message must NOT promise a fix it can't deliver.
        assertTrue("$msg should not say 'Install'", "Install" !in msg)
        assertTrue("$msg should explain there's no pack", "outside" in msg)
    }

    @Test
    fun successOrNullExtensionRoundTrips() {
        val payload = OfflineRouter.Result(
            polyline = listOf(LatLng(52.0, 5.0), LatLng(52.1, 5.1)),
            distanceM = 1234.0, durationS = 600.0,
        )
        val ok: RouteOutcome = RouteOutcome.Success(payload)
        val nope: RouteOutcome = RouteOutcome.NoRouteFound("disconnected island")
        assertEquals(payload, ok.successOrNull())
        assertNull(nope.successOrNull())
        assertEquals(emptyList<CatalogEntry>(), nope.missingPacksOrEmpty())
    }

    @Test
    fun isRecoverableFlagsActionableOutcomes() {
        assertTrue(RouteOutcome.Success(OfflineRouter.Result(emptyList(), 0.0, 0.0)).isRecoverable)
        assertTrue(RouteOutcome.OutsideDownloadedRegion(emptyList(),
            listOf(RouteOutcome.Endpoint.FROM)).isRecoverable)
        assertTrue(!RouteOutcome.NoRouteFound("x").isRecoverable)
        assertTrue(!RouteOutcome.GraphLoadFailed(RuntimeException("x")).isRecoverable)
    }
}
