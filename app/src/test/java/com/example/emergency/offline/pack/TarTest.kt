package com.example.emergency.offline.pack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

class TarTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    @Test
    fun extractsRegularFiles() {
        val bytes = TarFixtures.buildTarGz(listOf(
            "manifest.json" to """{"a":1}""".toByteArray(),
            "routing/E0_N50.rd5" to ByteArray(1500) { (it and 0x7F).toByte() },
            "tiles.mbtiles" to ByteArray(513) { (it % 13).toByte() }, // crosses block boundary
        ))
        val dest = tmp.newFolder()
        val written = GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            Tar.extract(gz, dest)
        }
        assertEquals(
            listOf("manifest.json", "routing/E0_N50.rd5", "tiles.mbtiles"),
            written,
        )
        assertEquals("""{"a":1}""", java.io.File(dest, "manifest.json").readText())
        assertEquals(1500, java.io.File(dest, "routing/E0_N50.rd5").length())
        assertEquals(513, java.io.File(dest, "tiles.mbtiles").length())
        // The 1500-byte body should round-trip exactly - no padding leak.
        val rd5 = java.io.File(dest, "routing/E0_N50.rd5").readBytes()
        for (i in 0 until 1500) {
            assertEquals(i.toByte().toInt() and 0x7F, rd5[i].toInt() and 0xFF)
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val bytes = TarFixtures.buildTarGz(listOf(
            "../escape.txt" to "nope".toByteArray(),
        ))
        val dest = tmp.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { Tar.extract(it, dest) }
        }
    }

    @Test
    fun corruptedHeaderChecksumThrows() {
        val good = TarFixtures.buildTarGz(listOf(
            "ok.txt" to "hi".toByteArray(),
        ))
        // Decompress, flip a header byte, recompress so the gzip layer is fine
        // and the failure surfaces inside Tar instead of in GZIPInputStream.
        val raw = GZIPInputStream(ByteArrayInputStream(good)).readBytes().toMutableList()
        raw[20] = (raw[20].toInt() xor 0x01).toByte() // mid-name byte
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(raw.toByteArray()) }
        val dest = tmp.newFolder()
        assertThrows(IOException::class.java) {
            GZIPInputStream(ByteArrayInputStream(baos.toByteArray())).use {
                Tar.extract(it, dest)
            }
        }
    }
}
