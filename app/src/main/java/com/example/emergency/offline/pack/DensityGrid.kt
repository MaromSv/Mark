package com.example.emergency.offline.pack

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Per-cell density lookup that powers the live size estimate on the
 * Custom-bbox region picker (plan section 8 step 5). Maps a draggable rectangle
 * to expected pack size in <1 ms by summing cell-area x cell-density
 * over the cells the rectangle covers.
 *
 * The grid is shipped as `bundled/density-grid.bin` in the APK (plan section 3
 * Tier 0); the build script that generates it lives at
 * `scripts/build-pack/density-grid-build.py`.
 *
 * ## Binary format ("DGR1")
 *
 * ```
 * offset  size  field
 * 0       4     magic "DGR1" (ASCII)
 * 4       4     float32 LE - cellSizeDeg (typically 0.5)
 * 8       4     int32 LE   - nCols
 * 12      4     int32 LE   - nRows
 * 16      ...     uint16 LE per cell, in TENTHS of KB/km^2 ("decikb/km^2"):
 *                 stored value = round(kb_per_km2 * 10).
 *                 Row 0 = south edge (lat = -90), col 0 = west edge (lon = -180).
 *                 Cell (col,row) covers
 *                   lon in [-180 + col*cs, -180 + (col+1)*cs],
 *                   lat in [-90  + row*cs, -90  + (row+1)*cs]
 *                 where cs = cellSizeDeg.
 * ```
 *
 * The 0.1-KB unit gives sub-KB precision for rural cells (~=0.3 KB/km^2
 * baseline) without forcing a wider type; uint16 still tops out at
 * 6553 KB/km^2, well above any plausible urban density.
 *
 * 0.5 deg x 720x360 = 519 KB. Smaller cell sizes are valid but should keep
 * the same byte order so old builds can still parse new grids.
 */
class DensityGrid internal constructor(
    val cellSizeDeg: Double,
    val nCols: Int,
    val nRows: Int,
    /** Raw cells, decikb/km^2 - the wire unit. Use [kbPerKm2] to read in KB/km^2. */
    private val deciKbPerKm2: ShortArray,
) {
    init {
        require(cellSizeDeg > 0) { "cellSizeDeg must be positive" }
        require(deciKbPerKm2.size == nCols * nRows) {
            "expected ${nCols * nRows} cells, got ${deciKbPerKm2.size}"
        }
    }

    /** KB/km^2 at the given cell. Out-of-range indices return 0. */
    fun kbPerKm2(col: Int, row: Int): Double {
        if (col < 0 || col >= nCols || row < 0 || row >= nRows) return 0.0
        return (deciKbPerKm2[row * nCols + col].toInt() and 0xFFFF) / 10.0
    }

    /**
     * Estimate the byte size of a region pack covering [bbox]. Sums
     * cell-area x cell-density across every cell the bbox intersects;
     * fractional cells contribute fractional area so a 50 km box that
     * doesn't align to the 0.5 deg grid still gets a sensible answer.
     *
     * Plan target: within +/-15 % of the actual pack size for that bbox.
     */
    fun estimateBytes(bbox: BoundingBox): Long {
        val cs = cellSizeDeg
        val firstCol = floor((bbox.west  + 180.0) / cs).toInt()
        val lastCol  = floor((bbox.east  + 180.0 - EPS) / cs).toInt()
        val firstRow = floor((bbox.south + 90.0)  / cs).toInt()
        val lastRow  = floor((bbox.north + 90.0 - EPS) / cs).toInt()

        var totalKb = 0.0
        for (row in max(0, firstRow)..min(nRows - 1, lastRow)) {
            val cellSouth = -90.0 + row * cs
            val cellNorth = cellSouth + cs
            val s = max(bbox.south, cellSouth)
            val n = min(bbox.north, cellNorth)
            if (n <= s) continue
            val midLat   = (s + n) / 2.0
            val heightKm = BoundingBox.KM_PER_DEG_LAT * (n - s)
            val cosLat   = cos(midLat * PI / 180.0)

            for (col in max(0, firstCol)..min(nCols - 1, lastCol)) {
                val cellWest = -180.0 + col * cs
                val cellEast = cellWest + cs
                val w = max(bbox.west, cellWest)
                val e = min(bbox.east, cellEast)
                if (e <= w) continue
                val widthKm =
                    BoundingBox.KM_PER_DEG_LON_AT_EQUATOR * cosLat * (e - w)
                val deci = deciKbPerKm2[row * nCols + col].toInt() and 0xFFFF
                totalKb += deci * 0.1 * widthKm * heightKm
            }
        }
        return (totalKb * 1024.0).roundToLong()
    }

    companion object {
        private const val MAGIC = "DGR1"
        private const val EPS = 1e-9

        /**
         * Reads the grid from [input]. Does not close the stream - the
         * caller manages that, mirroring the rest of the offline-pack code.
         * Throws [IOException] on a bad magic or truncated body.
         */
        fun load(input: InputStream): DensityGrid {
            val header = readFully(input, 16, "DensityGrid header")
            val magic = String(header, 0, 4, Charsets.US_ASCII)
            if (magic != MAGIC) {
                throw IOException("bad density grid magic: '$magic'")
            }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cellSize = buf.getFloat(4).toDouble()
            val nCols    = buf.getInt(8)
            val nRows    = buf.getInt(12)
            require(nCols in 1..100_000 && nRows in 1..100_000) {
                "grid dimensions out of range: ${nCols}x$nRows"
            }
            val cellCount = nCols * nRows
            val body = readFully(input, cellCount * 2, "DensityGrid body")
            val data = ShortArray(cellCount)
            val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until cellCount) data[i] = bb.short
            return DensityGrid(cellSize, nCols, nRows, data)
        }

        private fun readFully(input: InputStream, n: Int, what: String): ByteArray {
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(out, read, n - read)
                if (r < 0) throw EOFException("$what truncated at $read/$n")
                read += r
            }
            return out
        }
    }
}
