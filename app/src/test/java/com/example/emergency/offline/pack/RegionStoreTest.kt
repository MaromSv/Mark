package com.example.emergency.offline.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RegionStoreTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private fun mkPack(root: File, id: String): RegionPack {
        val packDir = File(root, id).apply { mkdirs() }
        File(packDir, "manifest.json").writeText("{}")
        return RegionPack(
            id = id,
            name = id.uppercase(),
            type = RegionType.COUNTRY,
            bbox = BoundingBox(0.0, 0.0, 1.0, 1.0),
            version = 1,
            sizeBytes = 12345,
            installedAt = 1000,
            lastUsedAt = 1000,
            rootDir = packDir,
        )
    }

    @Test
    fun addAndListRoundTrip() {
        val root = tmp.newFolder()
        val store = RegionStore.forTest(root)
        val nl = mkPack(root, "nl")
        store.add(nl)
        assertEquals(listOf("nl"), store.list().map { it.id })
        assertEquals(nl, store.get("nl"))
    }

    @Test
    fun persistsAcrossInstances() {
        val root = tmp.newFolder()
        val first = RegionStore.forTest(root)
        val be = mkPack(root, "be")
        val nl = mkPack(root, "nl")
        first.add(be); first.add(nl)

        val second = RegionStore.forTest(root)
        assertEquals(listOf("be", "nl"), second.list().map { it.id })
        assertEquals(12345, second.get("nl")!!.sizeBytes)
    }

    @Test
    fun deleteRemovesRowAndFiles() {
        val root = tmp.newFolder()
        val store = RegionStore.forTest(root)
        val nl = mkPack(root, "nl")
        store.add(nl)
        assertTrue(store.delete("nl"))
        assertNull(store.get("nl"))
        assertTrue(!nl.rootDir.exists())
        // Reloading from disk also sees the empty registry.
        val reloaded = RegionStore.forTest(root)
        assertTrue(reloaded.list().isEmpty())
    }

    @Test
    fun touchLastUsedUpdatesTimestamp() {
        val root = tmp.newFolder()
        val store = RegionStore.forTest(root)
        store.add(mkPack(root, "ch"))
        assertEquals(1000, store.get("ch")!!.lastUsedAt)
        store.touchLastUsed("ch", now = 9999)
        assertEquals(9999, store.get("ch")!!.lastUsedAt)
        // Touching an unknown id is a no-op (doesn't throw).
        store.touchLastUsed("nope", now = 1)
    }

    @Test
    fun dropsRowsWhosePackDirDisappeared() {
        val root = tmp.newFolder()
        val store = RegionStore.forTest(root)
        val nl = mkPack(root, "nl")
        store.add(nl)
        // Simulate user clearing app cache outside of us.
        nl.rootDir.deleteRecursively()
        val reloaded = RegionStore.forTest(root)
        assertTrue(
            "should drop rows whose pack dir is gone",
            reloaded.list().none { it.id == "nl" },
        )
    }
}
