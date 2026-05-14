package com.example.emergency.offline

import com.example.emergency.offline.pack.BoundingBox
import com.example.emergency.offline.pack.RegionPack
import com.example.emergency.offline.pack.RegionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for [OfflineRouter.mergeSegments] - the hardlink-farm
 * helper that unions every installed pack's `routing/*.rd5` under a
 * single dir for BRouter to consume.
 *
 * The full `OfflineRouter.route(...)` integration test needs a real
 * BRouter routing graph and is deferred to instrumentation/manual.
 */
class OfflineRouterMergeTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private fun pack(root: File, id: String, segmentNames: List<String>): RegionPack {
        val packDir = File(root, "regions/$id").apply { mkdirs() }
        val routing = File(packDir, "routing").apply { mkdirs() }
        for (name in segmentNames) {
            File(routing, name).writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        return RegionPack(
            id = id, name = id.uppercase(), type = RegionType.COUNTRY,
            bbox = BoundingBox(0.0, 0.0, 1.0, 1.0),
            version = 1, sizeBytes = 1, installedAt = 0, lastUsedAt = 0,
            rootDir = packDir,
        )
    }

    @Test
    fun emptyInstalledReturnsNull() {
        val activeRoot = tmp.newFolder("active")
        assertNull(OfflineRouter.mergeSegments(emptyList(), activeRoot))
    }

    @Test
    fun mergesUniqueSegmentsFromAllPacks() {
        val root = tmp.newFolder("filesDir")
        val activeRoot = File(root, "regions/_active").apply { mkdirs() }
        val nl = pack(root, "nl", listOf("E0_N50.rd5", "E5_N50.rd5"))
        val be = pack(root, "be", listOf("E0_N50.rd5"))                 // shared
        val ch = pack(root, "ch", listOf("E5_N45.rd5", "E10_N45.rd5"))

        val merged = OfflineRouter.mergeSegments(listOf(nl, be, ch), activeRoot)!!
        val names = merged.list().orEmpty().toSortedSet()
        assertEquals(
            sortedSetOf("E0_N50.rd5", "E5_N50.rd5", "E5_N45.rd5", "E10_N45.rd5"),
            names,
        )
    }

    @Test
    fun rebuildsWhenInstalledSetChanges() {
        val root = tmp.newFolder("filesDir")
        val activeRoot = File(root, "regions/_active").apply { mkdirs() }
        val nl = pack(root, "nl", listOf("E0_N50.rd5"))
        val be = pack(root, "be", listOf("E5_N50.rd5"))

        val firstMerge = OfflineRouter.mergeSegments(listOf(nl), activeRoot)!!
        assertEquals(setOf("E0_N50.rd5"), firstMerge.list().orEmpty().toSet())

        // Add BE and re-merge - the new segment must appear.
        val secondMerge = OfflineRouter.mergeSegments(listOf(nl, be), activeRoot)!!
        assertEquals(
            setOf("E0_N50.rd5", "E5_N50.rd5"),
            secondMerge.list().orEmpty().toSet(),
        )

        // Removing NL again wipes its segment.
        val thirdMerge = OfflineRouter.mergeSegments(listOf(be), activeRoot)!!
        assertEquals(setOf("E5_N50.rd5"), thirdMerge.list().orEmpty().toSet())
    }

    @Test
    fun cachedDirReturnedWhenSetUnchanged() {
        val root = tmp.newFolder("filesDir")
        val activeRoot = File(root, "regions/_active").apply { mkdirs() }
        val nl = pack(root, "nl", listOf("E0_N50.rd5"))

        val a = OfflineRouter.mergeSegments(listOf(nl), activeRoot)!!
        val mtime = File(a, "E0_N50.rd5").lastModified()
        Thread.sleep(20) // ensure measurable mtime delta if rebuild ever runs
        val b = OfflineRouter.mergeSegments(listOf(nl), activeRoot)!!
        assertEquals(a, b)
        assertEquals(mtime, File(b, "E0_N50.rd5").lastModified())
    }

    @Test
    fun missingRoutingDirIsToleratedNotFatal() {
        val root = tmp.newFolder("filesDir")
        val activeRoot = File(root, "regions/_active").apply { mkdirs() }
        // Pack with no routing/ dir - should be skipped without throwing.
        val packDir = File(root, "regions/broken").apply { mkdirs() }
        val broken = RegionPack(
            id = "broken", name = "BROKEN", type = RegionType.COUNTRY,
            bbox = BoundingBox(0.0, 0.0, 1.0, 1.0),
            version = 1, sizeBytes = 1, installedAt = 0, lastUsedAt = 0,
            rootDir = packDir,
        )
        val merged = OfflineRouter.mergeSegments(listOf(broken), activeRoot)
        // Empty but non-null - caller decides whether empty is a failure.
        assertTrue(merged != null)
        assertEquals(0, merged!!.list().orEmpty().size)
    }
}
