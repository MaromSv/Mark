package com.example.emergency.offline

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Stages the bundled (Tier-0, plan section 3) blobs from APK assets into
 * [Context.getFilesDir] so they can be opened as regular files:
 *   - BRouter profiles (`trekking.brf`, `fastbike.brf`, `car-fast.brf`,
 *     `lookups.dat`) - global, profile-agnostic; apply to any installed pack.
 *   - The global vector skeleton (`skeleton.mbtiles`, plan section 3 Tier 0)
 *     when present - built by `scripts/build-pack/skeleton-build.sh` and may
 *     be absent during dev iteration; staging tolerates that.
 *
 * Per-region detail packs (Tier 1/2) are NOT staged here - they're
 * downloaded straight to `filesDir/regions/<id>/` by PackDownloader
 * (plan section 4) and surfaced via `RegionStore`.
 *
 * Required because:
 *   - BRouter's RoutingEngine reads .rd5 segment files via random-access
 *     [java.io.RandomAccessFile] - which only works against real files,
 *     not [android.content.res.AssetManager] streams. The lookups.dat /
 *     .brf profiles are read the same way.
 *   - The MBTiles tile pack is a SQLite database and must be opened by
 *     path; AAPT-stored assets aren't directly addressable by SQLite.
 *
 * Idempotent - bumping [VERSION] forces a re-copy, otherwise the
 * existing staged files are reused. The version file lives in [filesDir]
 * so a fresh install always starts with VERSION=-1 and triggers a copy.
 */
object OfflineAssets {

    private const val TAG = "OfflineAssets"

    // Bump when the bundled assets change so devices already updated to
    // a newer APK re-copy on next launch.
    //   1 = PDOK raster pyramid
    //   2 = OpenMapTiles vector pack (Step 1, single bundled NL pack)
    //   3 = Tier-0 split: bundled skeleton + per-region downloadable packs
    //       (Step 3, plan section 3). Bumping forces cleanup of the legacy
    //       `tiles/` and `brouter/segments/` dirs left over in filesDir
    //       from previous installs.
    private const val VERSION = 3
    private const val VERSION_FILE = "offline-assets.version"

    // Asset roots inside the APK.
    private const val ASSET_BROUTER_PROFILES = "bundled/brouter-profiles"
    private const val ASSET_SKELETON_MBTILES = "bundled/skeleton.mbtiles"

    // Files we expect to find inside the brouter-profiles asset dir. Listed
    // explicitly so we don't depend on AssetManager.list() ordering and so
    // missing files fail loudly instead of silently leaving the stage
    // half-populated.
    private val PROFILE_FILES = listOf(
        "trekking.brf",
        "fastbike.brf",
        "car-fast.brf",
        "lookups.dat",
    )

    /**
     * Where each Tier-0 asset lives once staged. `skeletonMbtiles` may
     * point at a non-existent file when the bundled skeleton wasn't built
     * (see scripts/build-pack/skeleton-build.sh). Callers must check
     * existence before opening it.
     */
    data class Paths(
        val profilesDir: File,
        val skeletonMbtiles: File,
    )

    /**
     * Returns where each asset will live once staged, regardless of whether
     * the copy has happened yet. Cheap; safe to call from the main thread.
     */
    fun pathsFor(context: Context): Paths {
        val base = context.filesDir
        return Paths(
            profilesDir = File(base, "bundled/brouter-profiles"),
            skeletonMbtiles = File(base, "bundled/skeleton.mbtiles"),
        )
    }

    /**
     * True when the version stamp matches the current build AND every
     * required staged file exists. The skeleton mbtiles is NOT required
     * - it's an optional Tier-0 asset and absence is handled by callers.
     */
    fun isStaged(context: Context): Boolean {
        if (readVersion(context) != VERSION) return false
        val paths = pathsFor(context)
        if (PROFILE_FILES.any { !File(paths.profilesDir, it).exists() }) return false
        // If the skeleton was bundled into the APK, require it to be staged
        // too; otherwise treat absence as "no skeleton shipped - that's OK".
        if (assetExists(context.assets, ASSET_SKELETON_MBTILES) &&
            !paths.skeletonMbtiles.exists()) {
            return false
        }
        return true
    }

    /**
     * Copies the bundled assets to [filesDir] if they aren't already present
     * for the current [VERSION]. Safe to call repeatedly - short-circuits on
     * subsequent launches.
     *
     * Runs on [Dispatchers.IO]; payload is small (under 30 MB once skeleton
     * is built; under 100 KB without it) so this is fast.
     */
    suspend fun ensureStaged(
        context: Context,
        onProgress: (stagedFiles: Int, totalFiles: Int) -> Unit = { _, _ -> },
    ): Paths = withContext(Dispatchers.IO) {
        val paths = pathsFor(context)

        if (isStaged(context)) {
            Log.d(TAG, "Already staged at v$VERSION - skipping copy")
            val total = totalFiles(context)
            onProgress(total, total)
            return@withContext paths
        }

        Log.d(TAG, "Staging Tier-0 bundled assets to ${context.filesDir}")
        cleanupLegacyStaging(context)

        paths.profilesDir.mkdirs()
        paths.skeletonMbtiles.parentFile?.mkdirs()

        val total = totalFiles(context)
        var done = 0
        onProgress(done, total)

        // Profiles: tiny text/binary files, copy in one shot.
        for (name in PROFILE_FILES) {
            copyAsset(
                context,
                "$ASSET_BROUTER_PROFILES/$name",
                File(paths.profilesDir, name),
            )
            done++; onProgress(done, total)
        }

        // Skeleton mbtiles: optional. Skip silently if not bundled - the
        // map UI falls back to the style.json background until a regional
        // pack is installed.
        if (assetExists(context.assets, ASSET_SKELETON_MBTILES)) {
            copyAsset(context, ASSET_SKELETON_MBTILES, paths.skeletonMbtiles)
            done++; onProgress(done, total)
        } else {
            Log.w(TAG, "Skeleton mbtiles not bundled - run scripts/build-pack/skeleton-build.sh")
        }

        writeVersion(context, VERSION)
        Log.d(TAG, "Stage complete: v$VERSION")
        paths
    }

    /**
     * Removes leftover NL-specific files from previous app versions
     * (`filesDir/tiles/nl.mbtiles`, `filesDir/brouter/segments/<rd5 files>`).
     * Together those total ~470 MB on disk on devices that ran v1/v2 of
     * the staging code. Best-effort: any file that resists deletion just
     * stays - it's wasted bytes, not a correctness bug.
     */
    private fun cleanupLegacyStaging(context: Context) {
        val base = context.filesDir
        listOf(File(base, "tiles"), File(base, "brouter")).forEach { dir ->
            if (!dir.exists()) return@forEach
            val before = dir.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L }
            val ok = dir.deleteRecursively()
            Log.d(
                TAG,
                "Legacy cleanup: ${dir.name} (${before / 1024 / 1024} MB) " +
                    if (ok) "deleted" else "partial - leftover bytes are inert",
            )
        }
    }

    private fun totalFiles(context: Context): Int =
        PROFILE_FILES.size +
            if (assetExists(context.assets, ASSET_SKELETON_MBTILES)) 1 else 0

    private fun assetExists(assets: AssetManager, path: String): Boolean =
        try {
            assets.open(path).close(); true
        } catch (_: IOException) {
            false
        }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.partial")
        // Fast path: openFd() works because every large asset is in the
        // noCompress list (.rd5/.mbtiles/.brf/.dat). The returned descriptor
        // points at a byte range INSIDE the APK file itself, which lets us
        // ask the kernel to do the copy via FileChannel.transferTo() ->
        // sendfile(2) on Linux. Skips the JVM-side 1 MB bounce buffer and
        // halves staging time on flash storage.
        val afd = context.assets.openFd(assetPath)
        afd.use {
            FileInputStream(it.fileDescriptor).channel.use { src ->
                FileOutputStream(tmp).channel.use { dst ->
                    val length = it.length
                    val baseOffset = it.startOffset
                    var copied = 0L
                    while (copied < length) {
                        val n = src.transferTo(baseOffset + copied, length - copied, dst)
                        if (n <= 0) break
                        copied += n
                    }
                    if (copied != length) {
                        error("Short read on $assetPath: $copied/$length bytes")
                    }
                }
            }
        }
        // Atomic rename so a half-written file can never be mistaken for a
        // staged one if the process is killed mid-copy.
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "Failed to rename ${tmp.name} -> ${dest.name}" }
        Log.d(TAG, "Staged ${dest.name} (${dest.length() / 1024} KB)")
    }

    private fun readVersion(context: Context): Int =
        runCatching {
            File(context.filesDir, VERSION_FILE).readText().trim().toInt()
        }.getOrDefault(-1)

    private fun writeVersion(context: Context, v: Int) {
        File(context.filesDir, VERSION_FILE).writeText(v.toString())
    }
}
