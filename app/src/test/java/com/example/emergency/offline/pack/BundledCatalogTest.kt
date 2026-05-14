package com.example.emergency.offline.pack

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sanity check that `app/src/main/assets/bundled/catalog.json` parses
 * cleanly through [CatalogManifest.parse]. The full [CatalogProvider]
 * needs an Android `Context` to read from `AssetManager` - out of scope
 * for a pure JVM unit test - so we read the file directly from the
 * source tree instead.
 */
class BundledCatalogTest {

    @Test
    fun bundledCatalogParsesAndCoversSeededRegions() {
        // Project layout: this test runs from app/, so the bundled asset
        // lives at src/main/assets/bundled/catalog.json relative to here.
        val candidates = listOf(
            File("src/main/assets/bundled/catalog.json"),
            File("app/src/main/assets/bundled/catalog.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("bundled catalog.json not found; tried $candidates")
        val parsed = CatalogManifest.parse(file.readText())
        assertTrue("schema bumped without test update", parsed.schemaVersion == CatalogManifest.CURRENT_SCHEMA_VERSION)
        val ids = parsed.packs.map { it.id }.toSet()
        listOf("nl", "be", "ch", "monaco").forEach {
            assertTrue("expected '$it' in bundled catalog (got $ids)", it in ids)
        }
        // Every entry must have a non-degenerate bbox + non-zero size.
        parsed.packs.forEach { entry ->
            assertTrue(entry.sizeBytes > 0)
            assertTrue(entry.url.startsWith("http"))
            assertTrue(entry.bbox.east > entry.bbox.west)
            assertTrue(entry.bbox.north > entry.bbox.south)
        }
    }
}
