package com.example.emergency.offline.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DensityGridTest {

    /**
     * Builds a DGR1-format byte stream. The `cellKbPerKm2` lambda returns
     * KB/km^2 as a Double; the helper converts to the on-disk decikb/km^2
     * unit so tests can express densities in their natural unit.
     * Mirrors the encoding in scripts/build-pack/density-grid-build.py.
     */
    private fun buildGrid(
        cellSizeDeg: Double,
        nCols: Int,
        nRows: Int,
        cellKbPerKm2: (col: Int, row: Int) -> Double,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("DGR1".toByteArray(Charsets.US_ASCII))
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putFloat(cellSizeDeg.toFloat())
        header.putInt(nCols)
        header.putInt(nRows)
        out.write(header.array())
        val body =
            ByteBuffer.allocate(nCols * nRows * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until nRows) {
            for (col in 0 until nCols) {
                val deci = (cellKbPerKm2(col, row) * 10).toInt().coerceIn(0, 0xFFFF)
                body.putShort(deci.toShort())
            }
        }
        out.write(body.array())
        return out.toByteArray()
    }

    private fun loadGrid(bytes: ByteArray): DensityGrid =
        DensityGrid.load(ByteArrayInputStream(bytes))

    @Test
    fun roundTripsHeaderAndCellValues() {
        // Encode KB/km^2 directly; the helper handles the x10 conversion.
        val bytes = buildGrid(0.5, 720, 360) { col, row ->
            ((col + row) and 0xFF).toDouble()
        }
        val grid = loadGrid(bytes)
        assertEquals(0.5, grid.cellSizeDeg, 1e-6)
        assertEquals(720, grid.nCols)
        assertEquals(360, grid.nRows)
        assertEquals(0.0, grid.kbPerKm2(0, 0), 1e-9)
        assertEquals(255.0, grid.kbPerKm2(255, 0), 1e-9)
        assertEquals(255.0, grid.kbPerKm2(127, 128), 1e-9)
    }

    @Test
    fun rejectsBadMagic() {
        val bad = "XXXX".toByteArray() + ByteArray(12 + 8)
        assertThrows(IOException::class.java) {
            DensityGrid.load(ByteArrayInputStream(bad))
        }
    }

    @Test
    fun rejectsTruncatedBody() {
        val full = buildGrid(0.5, 4, 4) { _, _ -> 7.0 }
        val truncated = full.copyOfRange(0, full.size - 5)
        assertThrows(IOException::class.java) {
            DensityGrid.load(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun estimateScalesLinearlyWithDensity() {
        // Uniform 10 KB/km^2 over a 1 deg band centred on the equator.
        val grid = loadGrid(buildGrid(1.0, 360, 180) { _, _ -> 10.0 })
        val box = BoundingBox(0.0, 0.0, 1.0, 1.0)
        val expectedKm2 = box.areaKm2()
        val expectedBytes = (expectedKm2 * 10 * 1024).toLong()
        val got = grid.estimateBytes(box)
        assertEquals(expectedBytes.toDouble(), got.toDouble(), expectedBytes * 0.01)
    }

    @Test
    fun estimateHandlesSubKbPrecision() {
        // The decikb wire unit should preserve a 0.3 KB/km^2 baseline that an
        // integer KB/km^2 format would round to zero. NL-sized box at 0.3
        // KB/km^2 should still come out near 30 MB.
        val grid = loadGrid(buildGrid(1.0, 360, 180) { _, _ -> 0.3 })
        val nl = BoundingBox(3.0, 50.5, 7.5, 53.7)
        val got = grid.estimateBytes(nl)
        assertTrue(
            "estimate at 0.3 KB/km^2 baseline = $got bytes (${got / 1024 / 1024} MB)",
            got > 20L * 1024 * 1024,
        )
    }

    @Test
    fun estimateHandlesFractionalCells() {
        val grid = loadGrid(buildGrid(1.0, 360, 180) { _, _ -> 100.0 })
        val full = BoundingBox(0.0, 0.0, 1.0, 1.0)
        val half = BoundingBox(0.0, 0.0, 0.5, 1.0)
        val fullBytes = grid.estimateBytes(full)
        val halfBytes = grid.estimateBytes(half)
        assertEquals(fullBytes / 2.0, halfBytes.toDouble(), fullBytes * 0.005)
    }

    @Test
    fun estimateClampsOutOfRangeBboxToGridBounds() {
        val grid = loadGrid(buildGrid(1.0, 360, 180) { _, _ -> 1.0 })
        val planet = BoundingBox(-180.0, -90.0, 180.0, 90.0)
        val got = grid.estimateBytes(planet)
        // Earth surface ~= 510e6 km^2; equirectangular sum overestimates near
        // the poles, so allow a wide band and just check magnitude.
        val approxKb = 510_000_000.0
        assertTrue(
            "planet estimate $got KB ~= ${got / 1024} KB",
            got / 1024.0 in approxKb * 0.5..approxKb * 1.5,
        )
    }

    @Test
    fun estimateMatchesPlanReferencePacks() {
        // Plan section 3 reference table. Build a grid that hard-codes a per-region
        // density derived from the published pack size / area, then verify
        // the estimator round-trips within +/-15 % - the success criterion in
        // section 8 step 2.
        //
        // This doesn't validate the *grid* (that needs the real
        // density-grid.bin); it validates that the *estimator math* does
        // not introduce its own bias on top of the grid.
        data class Ref(val name: String, val bbox: BoundingBox, val sizeMb: Int)
        val refs = listOf(
            Ref("nl",     BoundingBox(3.0, 50.5, 7.5, 53.7),       80),
            Ref("ca",     BoundingBox(-124.5, 32.5, -114.0, 42.0), 400),
            Ref("bay",    BoundingBox(-122.6, 37.2, -121.7, 38.0), 120),
            Ref("nyc",    BoundingBox(-74.05, 40.55, -73.70, 40.92), 70),
            Ref("ch",     BoundingBox(5.95, 45.8, 10.50, 47.81),   150),
        )
        for (r in refs) {
            val areaKm2 = r.bbox.areaKm2()
            val targetBytes = r.sizeMb * 1024L * 1024L
            val kbPerKm2 = (targetBytes / 1024.0 / areaKm2).coerceAtLeast(0.1)
            val grid = loadGrid(buildGrid(0.5, 720, 360) { _, _ -> kbPerKm2 })
            val got = grid.estimateBytes(r.bbox)
            val ratio = got.toDouble() / targetBytes
            assertTrue(
                "${r.name}: estimate=$got target=$targetBytes ratio=$ratio",
                ratio in 0.85..1.15,
            )
        }
    }
}
