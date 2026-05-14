package com.example.emergency.agent.tools

import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.pack.BoundingBox
import com.example.emergency.offline.pack.CatalogEntry
import com.example.emergency.offline.pack.RegionType
import com.example.emergency.offline.routing.RouteOutcome
import com.example.emergency.offline.routing.TurnCommand
import com.example.emergency.offline.routing.TurnStep
import com.mapbox.mapboxsdk.geometry.LatLng
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteToolTest {

    // --- parseCoords -----------------------------------------------------

    @Test
    fun parseCoordsAcceptsPlainPair() {
        assertEquals(52.374 to 4.890, RouteTool.parseCoords("52.374,4.890"))
        assertEquals(52.374 to 4.890, RouteTool.parseCoords("52.374, 4.890"))
        assertEquals(-33.85 to 151.20, RouteTool.parseCoords("-33.85,151.20"))
        // Tolerates wrapping parens - the LLM occasionally adds them.
        assertEquals(52.374 to 4.890, RouteTool.parseCoords("(52.374, 4.890)"))
    }

    @Test
    fun parseCoordsRejectsOutOfRange() {
        assertNull(RouteTool.parseCoords("100,0"))   // lat > 90
        assertNull(RouteTool.parseCoords("0,200"))   // lon > 180
        assertNull(RouteTool.parseCoords("-91,0"))   // lat < -90
    }

    @Test
    fun parseCoordsRejectsNonNumeric() {
        assertNull(RouteTool.parseCoords("hospital"))
        assertNull(RouteTool.parseCoords("52.374"))           // single value
        assertNull(RouteTool.parseCoords("foo,bar"))
        assertNull(RouteTool.parseCoords("52..,4..,extra"))
    }

    // --- mapProfile ------------------------------------------------------

    @Test
    fun mapProfileAcceptsKnownAliases() {
        assertEquals("walk", RouteTool.mapProfile("walk"))
        assertEquals("walk", RouteTool.mapProfile("walking"))
        assertEquals("walk", RouteTool.mapProfile("Foot"))   // case insensitive
        assertEquals("fastbike", RouteTool.mapProfile("bike"))
        assertEquals("fastbike", RouteTool.mapProfile("cycling"))
        assertEquals("car-fast", RouteTool.mapProfile("drive"))
        assertEquals("car-fast", RouteTool.mapProfile("car"))
        assertNull(RouteTool.mapProfile("teleport"))
    }

    // --- normalizeCategory ----------------------------------------------

    @Test
    fun normalizeCategoryHandlesAliasesAndCase() {
        assertEquals("hospital", RouteTool.normalizeCategory("hospital"))
        assertEquals("hospital", RouteTool.normalizeCategory("emergency_room"))
        assertEquals("hospital", RouteTool.normalizeCategory("ER"))
        assertEquals("aed", RouteTool.normalizeCategory("defibrillator"))
        assertEquals("water", RouteTool.normalizeCategory("drinking water"))    // space -> underscore
        assertNull(RouteTool.normalizeCategory("teleporter"))
    }

    // --- formatOutcome --------------------------------------------------

    private val dest = RouteTool.ResolvedDestination(
        name = "Anne Frank House",
        category = "place",
        lat = 52.375,
        lon = 4.884,
    )

    private fun makeRoute(): OfflineRouter.Result = OfflineRouter.Result(
        polyline = listOf(LatLng(52.374, 4.890), LatLng(52.375, 4.884)),
        distanceM = 1234.0,
        durationS = 900.0,
        steps = listOf(
            TurnStep(LatLng(52.374, 4.890), TurnCommand.Continue, 100.0, "Stationsplein", 0),
            TurnStep(LatLng(52.374, 4.888), TurnCommand.TurnLeft, 200.0, "Damrak", 1),
            TurnStep(LatLng(52.375, 4.884), TurnCommand.Arrive, 0.0, null, 1),
        ),
    )

    @Test
    fun successFormatsJsonWithExpectedFields() {
        val result = RouteTool.formatOutcome(dest, RouteOutcome.Success(makeRoute()))
        assertTrue(result.success)
        val json = JSONObject(result.data)
        assertEquals("Anne Frank House", json.getString("name"))
        assertEquals("place", json.getString("category"))
        assertEquals(52.375, json.getDouble("lat"), 1e-6)
        assertEquals(4.884, json.getDouble("lon"), 1e-6)
        assertEquals(1234, json.getInt("distance_m"))
        assertEquals(900, json.getInt("duration_s"))
        assertEquals(3, json.getInt("step_count"))
        val steps = json.getJSONArray("first_steps")
        assertEquals(3, steps.length())
        assertTrue("first step should mention Stationsplein", "Stationsplein" in steps.getString(0))
    }

    @Test
    fun outsideRegionEmitsErrorWithMissingPacks() {
        val outcome = RouteOutcome.OutsideDownloadedRegion(
            missingPacks = listOf(
                CatalogEntry(
                    id = "de", name = "Germany", type = RegionType.COUNTRY,
                    bbox = BoundingBox(5.86, 47.27, 15.05, 55.06),
                    sizeBytes = 1, version = 1,
                    url = "https://x", sha256 = "0".repeat(64),
                ),
            ),
            uncoveredEndpoints = listOf(RouteOutcome.Endpoint.TO),
        )
        val result = RouteTool.formatOutcome(dest, outcome)
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue("error should suggest install", "Germany" in result.error!!)
        val payload = JSONObject(result.data)
        assertEquals("outside_downloaded_region", payload.getString("error"))
        assertEquals(listOf("de"), List(payload.getJSONArray("missing_packs").length()) {
            payload.getJSONArray("missing_packs").getString(it)
        })
    }

    @Test
    fun noRouteFoundEmitsExplicitError() {
        val result = RouteTool.formatOutcome(
            dest, RouteOutcome.NoRouteFound("disconnected island"),
        )
        assertFalse(result.success)
        assertTrue("disconnected island" in (result.error ?: ""))
    }

    @Test
    fun graphLoadFailedEmitsExplicitError() {
        val result = RouteTool.formatOutcome(
            dest, RouteOutcome.GraphLoadFailed(IllegalStateException("corrupt rd5")),
        )
        assertFalse(result.success)
        assertTrue("corrupt rd5" in (result.error ?: ""))
    }
}
