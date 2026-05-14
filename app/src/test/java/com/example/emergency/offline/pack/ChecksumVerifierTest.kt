package com.example.emergency.offline.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ChecksumVerifierTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    @Test
    fun sha256MatchesReference() {
        val f = tmp.newFile("body.bin").apply {
            writeBytes(ByteArray(50_000) { (it % 251).toByte() })
        }
        val md = MessageDigest.getInstance("SHA-256")
        val expected = md.digest(f.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expected, ChecksumVerifier.sha256(f))
    }

    @Test
    fun verifyTarballOkAndMismatch() {
        val f = tmp.newFile("pack.tar.gz").apply { writeBytes("hello".toByteArray()) }
        val sha = ChecksumVerifier.sha256(f)
        assertTrue(ChecksumVerifier.verifyTarball(f, sha) is ChecksumVerifier.Result.Ok)
        assertTrue(
            ChecksumVerifier.verifyTarball(f, "0".repeat(64))
                is ChecksumVerifier.Result.Mismatch,
        )
    }

    @Test
    fun verifyPackChecksEverySize() {
        val packDir = tmp.newFolder()
        val ok = java.io.File(packDir, "ok.txt").apply { writeText("ok") }
        val wrongSize = java.io.File(packDir, "wrong.txt").apply { writeText("five!") }
        val manifest = PackManifest(
            schemaVersion = PackManifest.CURRENT_SCHEMA_VERSION,
            id = "x",
            name = "X",
            type = RegionType.CUSTOM,
            bbox = BoundingBox(0.0, 0.0, 1.0, 1.0),
            version = 1,
            createdAt = "2026-01-01T00:00:00Z",
            files = listOf(
                PackFile("ok.txt", ok.length(), ChecksumVerifier.sha256(ok)),
                PackFile("wrong.txt", wrongSize.length() + 100, ChecksumVerifier.sha256(wrongSize)),
            ),
        )
        val result = ChecksumVerifier.verifyPack(packDir, manifest)
        assertTrue(result is ChecksumVerifier.Result.WrongSize)
        assertEquals("wrong.txt", (result as ChecksumVerifier.Result.WrongSize).path)
    }

    @Test
    fun verifyPackReportsMissingFiles() {
        val packDir = tmp.newFolder()
        val manifest = PackManifest(
            schemaVersion = 1,
            id = "x",
            name = "X",
            type = RegionType.COUNTRY,
            bbox = BoundingBox(0.0, 0.0, 1.0, 1.0),
            version = 1,
            createdAt = "2026-01-01T00:00:00Z",
            files = listOf(PackFile("ghost.txt", 5, "0".repeat(64))),
        )
        val result = ChecksumVerifier.verifyPack(packDir, manifest)
        assertTrue(result is ChecksumVerifier.Result.Missing)
    }
}
