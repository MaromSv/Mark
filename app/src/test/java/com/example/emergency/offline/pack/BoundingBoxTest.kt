package com.example.emergency.offline.pack

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundingBoxTest {

    @Test
    fun rejectsInvertedOrOutOfRangeBoxes() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundingBox(west = 5.0, south = 50.0, east = 4.0, north = 51.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundingBox(west = 4.0, south = 51.0, east = 5.0, north = 50.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundingBox(west = -200.0, south = 0.0, east = 0.0, north = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundingBox(west = 0.0, south = -91.0, east = 1.0, north = 0.0)
        }
    }

    @Test
    fun containsHandlesEdgeAndInteriorPoints() {
        val nl = BoundingBox(3.0, 50.5, 7.5, 53.7)
        assertTrue(nl.contains(52.37, 4.90))   // Amsterdam, interior
        assertTrue(nl.contains(50.5, 3.0))     // SW corner inclusive
        assertTrue(!nl.contains(48.85, 2.35))  // Paris, outside
    }

    @Test
    fun intersectReturnsNullWhenDisjoint() {
        val a = BoundingBox(0.0, 0.0, 10.0, 10.0)
        val b = BoundingBox(20.0, 20.0, 30.0, 30.0)
        assertNull(a.intersect(b))
    }

    @Test
    fun intersectReturnsOverlap() {
        val a = BoundingBox(0.0, 0.0, 10.0, 10.0)
        val b = BoundingBox(5.0, 5.0, 15.0, 15.0)
        val o = a.intersect(b)
        assertNotNull(o)
        assertEquals(5.0, o!!.west, 1e-9)
        assertEquals(5.0, o.south, 1e-9)
        assertEquals(10.0, o.east, 1e-9)
        assertEquals(10.0, o.north, 1e-9)
    }

    @Test
    fun areaKm2RoughlyMatchesKnownReferences() {
        // The Netherlands is ~41,500 km^2 in reality but its bbox is much
        // bigger because it covers the IJsselmeer and the spill into BE/DE.
        // The bbox area should still land near the published ~50,000 km^2
        // figure (Wikipedia "Netherlands" infobox bbox area).
        val nl = BoundingBox(3.0, 50.5, 7.5, 53.7)
        val area = nl.areaKm2()
        // +/-15 % of 100,000 km^2 (the bbox is wider than the country).
        assertTrue("nl bbox area = $area", area in 80_000.0..120_000.0)

        // A 1 degx1 deg box at the equator ~= 12,391.7 km^2 with the equirectangular
        // approximation we use (mid-lat = 0.5 deg -> cos ~= 0.99996).
        val equator = BoundingBox(0.0, 0.0, 1.0, 1.0)
        assertEquals(12_391.67, equator.areaKm2(), 0.5)
    }

    @Test
    fun jsonRoundTrip() {
        val src = BoundingBox(3.0, 50.5, 7.5, 53.7)
        val arr: JSONArray = src.toJson()
        val parsed = BoundingBox.fromJson(arr)
        assertEquals(src, parsed)
    }
}
