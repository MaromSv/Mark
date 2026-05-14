package com.example.emergency.offline.download

import com.example.emergency.offline.pack.BoundingBox
import com.example.emergency.offline.pack.CatalogEntry
import com.example.emergency.offline.pack.ChecksumVerifier
import com.example.emergency.offline.pack.PackFile
import com.example.emergency.offline.pack.PackManifest
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.offline.pack.RegionType
import com.example.emergency.offline.pack.TarFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackDownloaderTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private fun buildPackBytes(id: String, bbox: BoundingBox): Pair<ByteArray, PackManifest> {
        val tilesBody = ByteArray(2048) { (it % 199).toByte() }
        val rd5Body   = ByteArray(1500) { (it % 251).toByte() }
        // Per-file sha256 for the manifest: we can hash the body bytes directly
        // since they go into the tarball verbatim.
        val tilesSha = ChecksumVerifier.sha256(tilesBody.inputStream())
        val rd5Sha   = ChecksumVerifier.sha256(rd5Body.inputStream())

        // Build manifest first so it can list itself and the data files.
        val provisional = PackManifest(
            schemaVersion = PackManifest.CURRENT_SCHEMA_VERSION,
            id = id, name = id.uppercase(), type = RegionType.COUNTRY,
            bbox = bbox, version = 1, createdAt = "2026-01-01T00:00:00Z",
            files = listOf(
                PackFile("tiles.mbtiles", tilesBody.size.toLong(), tilesSha),
                PackFile("routing/E0_N50.rd5", rd5Body.size.toLong(), rd5Sha),
            ),
        )
        val manifestBytes = provisional.toJson().toString(2).toByteArray()
        val tar = TarFixtures.buildTarGz(listOf(
            "manifest.json"          to manifestBytes,
            "tiles.mbtiles"          to tilesBody,
            "routing/E0_N50.rd5"     to rd5Body,
        ))
        return tar to provisional
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun happyPath_endToEnd() = runBlocking {
        val regionsRoot = tmp.newFolder("regions")
        val store = RegionStore.forTest(regionsRoot)
        val (tarBytes, _) = buildPackBytes("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val tarballSha = ChecksumVerifier.sha256(tarBytes.inputStream())
        val client = ByteArrayHttpClient(mapOf("https://example/nl-v1.tar.gz" to tarBytes))
        val downloader = PackDownloader.forTest(regionsRoot, store, client, newScope())
        val entry = CatalogEntry(
            id = "nl", name = "Netherlands", type = RegionType.COUNTRY,
            bbox = BoundingBox(3.0, 50.5, 7.5, 53.7),
            sizeBytes = tarBytes.size.toLong(),
            version = 1,
            url = "https://example/nl-v1.tar.gz",
            sha256 = tarballSha,
        )

        downloader.download(entry)
        downloader.state.map { it["nl"] }
            .first { it is DownloadState.Installed || it is DownloadState.Failed }

        assertEquals(DownloadState.Installed, downloader.stateOf("nl"))
        val installed = store.get("nl")
        assertNotNull(installed)
        assertTrue(File(installed!!.rootDir, "tiles.mbtiles").exists())
        assertTrue(File(installed.rootDir, "routing/E0_N50.rd5").exists())
        // .partial was cleaned up.
        assertTrue(!File(regionsRoot, "tmp/nl.partial").exists())
    }

    @Test
    fun checksumMismatch_failsAndKeepsRegistryClean() = runBlocking {
        val regionsRoot = tmp.newFolder("regions")
        val store = RegionStore.forTest(regionsRoot)
        val (tarBytes, _) = buildPackBytes("ch", BoundingBox(5.95, 45.8, 10.5, 47.81))
        val client = ByteArrayHttpClient(mapOf("https://example/ch.tar.gz" to tarBytes))
        val downloader = PackDownloader.forTest(regionsRoot, store, client, newScope())
        val entry = CatalogEntry(
            id = "ch", name = "Switzerland", type = RegionType.COUNTRY,
            bbox = BoundingBox(5.95, 45.8, 10.5, 47.81),
            sizeBytes = tarBytes.size.toLong(),
            version = 1,
            url = "https://example/ch.tar.gz",
            sha256 = "ff".repeat(32), // wrong on purpose
        )

        downloader.download(entry)
        downloader.state.map { it["ch"] }.first { it is DownloadState.Failed }

        assertNull(store.get("ch"))
        assertTrue(!File(regionsRoot, "ch").exists())
        assertTrue(!File(regionsRoot, "ch.installing").exists())
        // Bad bytes get scrubbed so the next attempt re-downloads from zero.
        assertTrue(!File(regionsRoot, "tmp/ch.partial").exists())
    }

    @Test
    fun resumesFromExistingPartial() = runBlocking {
        val regionsRoot = tmp.newFolder("regions")
        val store = RegionStore.forTest(regionsRoot)
        val (tarBytes, _) = buildPackBytes("nl", BoundingBox(3.0, 50.5, 7.5, 53.7))
        val tarballSha = ChecksumVerifier.sha256(tarBytes.inputStream())

        // Pre-stage a half-finished partial (simulates a previous app run
        // that was killed mid-download).
        val tmpDir = File(regionsRoot, "tmp").apply { mkdirs() }
        val partial = File(tmpDir, "nl.partial")
        partial.writeBytes(tarBytes.sliceArray(0 until tarBytes.size / 2))

        val client = ByteArrayHttpClient(mapOf("https://example/nl.tar.gz" to tarBytes))
        val downloader = PackDownloader.forTest(regionsRoot, store, client, newScope())
        val entry = CatalogEntry(
            id = "nl", name = "Netherlands", type = RegionType.COUNTRY,
            bbox = BoundingBox(3.0, 50.5, 7.5, 53.7),
            sizeBytes = tarBytes.size.toLong(), version = 1,
            url = "https://example/nl.tar.gz", sha256 = tarballSha,
        )
        downloader.download(entry)
        downloader.state.map { it["nl"] }
            .first { it is DownloadState.Installed || it is DownloadState.Failed }

        assertEquals(DownloadState.Installed, downloader.stateOf("nl"))
        assertNotNull(store.get("nl"))
        assertTrue("partial should be cleaned up after install", !partial.exists())
    }

    @Test
    fun deleteRemovesEverything() = runBlocking {
        val regionsRoot = tmp.newFolder("regions")
        val store = RegionStore.forTest(regionsRoot)
        val (tarBytes, _) = buildPackBytes("be", BoundingBox(2.5, 49.5, 6.4, 51.5))
        val tarballSha = ChecksumVerifier.sha256(tarBytes.inputStream())
        val client = ByteArrayHttpClient(mapOf("https://example/be.tar.gz" to tarBytes))
        val downloader = PackDownloader.forTest(regionsRoot, store, client, newScope())
        val entry = CatalogEntry(
            id = "be", name = "Belgium", type = RegionType.COUNTRY,
            bbox = BoundingBox(2.5, 49.5, 6.4, 51.5),
            sizeBytes = tarBytes.size.toLong(), version = 1,
            url = "https://example/be.tar.gz", sha256 = tarballSha,
        )
        downloader.download(entry)
        downloader.state.map { it["be"] }.first { it is DownloadState.Installed }
        assertNotNull(store.get("be"))

        downloader.delete("be")
        assertNull(store.get("be"))
        assertTrue(!File(regionsRoot, "be").exists())
        assertEquals(DownloadState.Idle, downloader.stateOf("be"))
    }
}

/**
 * In-memory [PackDownloader.HttpClient] that streams bytes from a fixture
 * map. Honours `destFile`'s existing length as a resume offset, mirroring
 * the production client's range behaviour without an HTTP server.
 */
private class ByteArrayHttpClient(
    private val byUrl: Map<String, ByteArray>,
) : PackDownloader.HttpClient {
    override suspend fun fetchToFile(
        url: String,
        destFile: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
    ): Long {
        val body = byUrl[url] ?: error("no fixture for $url")
        destFile.parentFile?.mkdirs()
        val from = if (destFile.exists()) destFile.length().toInt() else 0
        if (from > body.size) {
            // Resume past the end -> start over (matches what
            // HttpUrlConnectionClient does when the server ignores Range).
            destFile.delete()
            destFile.writeBytes(body)
        } else {
            destFile.outputStream(/* append = */ from > 0).use {
                it.write(body, from, body.size - from)
            }
        }
        onProgress(body.size.toLong(), body.size.toLong())
        return body.size.toLong()
    }
}
