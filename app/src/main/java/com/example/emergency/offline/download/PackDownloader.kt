package com.example.emergency.offline.download

import android.content.Context
import android.util.Log
import com.example.emergency.offline.pack.CatalogEntry
import com.example.emergency.offline.pack.ChecksumVerifier
import com.example.emergency.offline.pack.PackManifest
import com.example.emergency.offline.pack.RegionPack
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.offline.pack.RegionType
import com.example.emergency.offline.pack.Tar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads, verifies and installs region packs (plan section 8 step 4).
 *
 * One coroutine per active pack id, owned by a process-wide
 * [SupervisorJob]; UI binds to [state] and triggers work via [download] /
 * [cancel] / [delete]. Successful installs land in [RegionStore]; the bytes
 * are placed under `filesDir/regions/<id>/` (see [RegionPack]).
 *
 * Pipeline (matches `DownloadState`):
 *
 * ```
 * Queued
 *   -> Downloading(progress)        // GET <url>, Range: bytes=N- if .partial exists
 *   -> Verifying                    // sha256(partial) == CatalogEntry.sha256
 *   -> Installing                   // gunzip + untar into installing/, per-file sha256, rename
 *   -> Installed                    // RegionStore.add(...) + delete partial
 * ```
 *
 * **WorkManager is intentionally deferred** (plan section 8 step 4). For Step 4's
 * scope (download + resume + verify + install + list + delete), a coroutine
 * tied to the application scope is enough - the user is staring at the
 * progress bar. WorkManager is the right answer once we want downloads to
 * survive process death (start the download, lock the screen, walk away);
 * dropping it in is a wrapper around [downloadInner], not a rewrite.
 *
 * **Resumability across process restarts** still works: the .partial file
 * lives on disk, so the next [download] call sees it and issues a `Range`
 * GET. We just don't restart automatically - the user has to tap again.
 *
 * Disk layout this class touches:
 * ```
 * filesDir/regions/
 *   installed.json             RegionStore registry (peer of these dirs)
 *   <id>/                      installed pack root (post-rename)
 *   <id>.installing/           atomic-install staging dir (deleted on failure)
 *   tmp/<id>.partial           in-flight tarball download
 * ```
 */
class PackDownloader private constructor(
    private val regionsRoot: File,
    private val tmpRoot: File,
    private val store: RegionStore,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    /** Per-pack active state. Absent ids are implicitly [DownloadState.Idle]. */
    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** Snapshot accessor mirroring `RegionStore.list()` for terseness in callers. */
    fun stateOf(id: String): DownloadState =
        _state.value[id] ?: DownloadState.Idle

    /**
     * Kicks off (or resumes) the download for [entry]. Idempotent - calling
     * twice while already downloading is a no-op. Returns immediately;
     * progress is on [state].
     */
    @Synchronized
    fun download(entry: CatalogEntry) {
        val current = stateOf(entry.id)
        if (current is DownloadState.Downloading ||
            current is DownloadState.Verifying ||
            current is DownloadState.Installing ||
            current == DownloadState.Queued) {
            Log.d(TAG, "${entry.id}: already in flight ($current)")
            return
        }
        // Already-installed check: skip iff the catalog version matches the
        // installed version. When the catalog advertises a newer pack
        // (e.g. v2 on the server vs v1 on disk), fall through to the
        // download flow so the user can refresh in-place. The downloader
        // overwrites the same regions/<id>/ dir atomically via the
        // .installing/ rename; no manual delete needed.
        val installed = store.get(entry.id)
        if (current == DownloadState.Installed &&
            installed != null &&
            installed.version >= entry.version) {
            Log.d(
                TAG,
                "${entry.id}: already installed at v${installed.version} " +
                    "(catalog v${entry.version}); skipping",
            )
            return
        }
        if (installed != null && installed.version < entry.version) {
            Log.d(
                TAG,
                "${entry.id}: installed v${installed.version} but catalog has " +
                    "v${entry.version} - downloading the upgrade",
            )
        }
        transition(entry.id, DownloadState.Queued)
        val job = scope.launch(Dispatchers.IO) {
            try {
                downloadInner(entry)
            } catch (ce: CancellationException) {
                // User cancelled - keep .partial on disk so a resume works.
                Log.d(TAG, "${entry.id}: cancelled; .partial preserved for resume")
                transition(entry.id, DownloadState.Paused("cancelled"))
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "${entry.id}: download failed", t)
                transition(entry.id, DownloadState.Failed(t.message ?: t.javaClass.simpleName))
            } finally {
                synchronized(this@PackDownloader) { jobs.remove(entry.id) }
            }
        }
        synchronized(this) { jobs[entry.id] = job }
    }

    /**
     * Cancels an active download. The .partial file is preserved so a later
     * [download] resumes from where we left off. Cleans up any half-extracted
     * `<id>.installing/` directory.
     */
    @Synchronized
    fun cancel(id: String) {
        jobs[id]?.cancel()
        File(regionsRoot, "$id.installing").deleteRecursively()
    }

    /**
     * Removes a pack from disk and the registry. Calls [RegionStore.delete]
     * which deletes the rootDir bytes. Also wipes any leftover .partial /
     * .installing artefacts in case a previous install was interrupted.
     */
    fun delete(id: String) {
        cancel(id)
        store.delete(id)
        File(regionsRoot, "$id.installing").deleteRecursively()
        partialFor(id).delete()
        transition(id, DownloadState.Idle)
    }

    private suspend fun downloadInner(entry: CatalogEntry) {
        tmpRoot.mkdirs()
        val partial = partialFor(entry.id)
        val installing = File(regionsRoot, "${entry.id}.installing")
        installing.deleteRecursively() // clean stale half-installs

        // Drop a stale partial that's left over from a different version of
        // the same pack (e.g. v1 was 735 MB, v2 is 451 MB - resuming would
        // ask the server for a Range past EOF and get HTTP 416). The
        // partial filename isn't versioned by design (resume across version
        // bumps is rare), so we just throw it away whenever its size
        // exceeds what we now expect.
        if (entry.sizeBytes > 0 && partial.exists() && partial.length() >= entry.sizeBytes) {
            Log.d(
                TAG,
                "${entry.id}: discarding stale ${partial.length() / 1024 / 1024} MB " +
                    "partial (catalog now expects ${entry.sizeBytes / 1024 / 1024} MB)",
            )
            partial.delete()
        }

        // --- DOWNLOAD ---------------------------------------------------
        transition(entry.id, DownloadState.Downloading(partial.takeIf { it.exists() }?.length() ?: 0L,
            entry.sizeBytes))
        val totalBytes = httpClient.fetchToFile(entry.url, partial) { done, total ->
            transition(entry.id, DownloadState.Downloading(done, total))
        }
        if (entry.sizeBytes > 0 && totalBytes != entry.sizeBytes) {
            throw IOException("downloaded $totalBytes bytes, catalog said ${entry.sizeBytes}")
        }

        // --- VERIFY TARBALL ---------------------------------------------
        transition(entry.id, DownloadState.Verifying)
        val tarballResult = ChecksumVerifier.verifyTarball(partial, entry.sha256)
        if (tarballResult !is ChecksumVerifier.Result.Ok) {
            partial.delete() // bad bytes, drop them so a retry re-downloads from zero
            throw IOException("tarball verify failed: $tarballResult")
        }

        // --- INSTALL (extract -> per-file verify -> atomic rename) --------
        transition(entry.id, DownloadState.Installing)
        installing.mkdirs()
        partial.inputStream().use { fileIn ->
            GZIPInputStream(fileIn).use { gz ->
                Tar.extract(gz, installing)
            }
        }

        val manifestFile = File(installing, "manifest.json")
        if (!manifestFile.exists()) {
            installing.deleteRecursively()
            throw IOException("pack ${entry.id} is missing manifest.json")
        }
        val manifest = PackManifest.parse(manifestFile.readText())
        val perFile = ChecksumVerifier.verifyPack(installing, manifest)
        if (perFile !is ChecksumVerifier.Result.Ok) {
            installing.deleteRecursively()
            throw IOException("per-file verify failed: $perFile")
        }

        // --- ATOMIC RENAME ---------------------------------------------
        // Last cancellation gate before we mutate the on-disk pack tree.
        // Anything past this point must run to completion or leave the
        // installing/ dir behind for the next download to re-extract.
        currentCoroutineContext().ensureActive()
        // Old version (if any) -> temp suffix -> renameFrom installing ->
        // delete the temp old. Two renames so a crash mid-swap leaves
        // *something* installed (either old or new) instead of nothing.
        val installed = RegionPack.rootFor(regionsRoot, entry.id)
        if (installed.exists()) {
            val previousBackup = File(regionsRoot, "${entry.id}.previous")
            previousBackup.deleteRecursively()
            check(installed.renameTo(previousBackup)) {
                "couldn't move existing pack aside: $installed"
            }
            check(installing.renameTo(installed)) {
                "couldn't promote new pack: $installing -> $installed"
            }
            previousBackup.deleteRecursively()
        } else {
            check(installing.renameTo(installed)) {
                "couldn't promote new pack: $installing -> $installed"
            }
        }
        partial.delete()

        val now = System.currentTimeMillis()
        store.add(
            RegionPack(
                id = entry.id,
                name = entry.name,
                type = entry.type,
                bbox = entry.bbox,
                version = entry.version,
                sizeBytes = manifest.totalSizeBytes,
                installedAt = now,
                lastUsedAt = now,
                rootDir = installed,
            ),
        )
        transition(entry.id, DownloadState.Installed)
    }

    private fun transition(id: String, next: DownloadState) {
        val previous = stateOf(id)
        if (previous == next) return
        if (!isValidTransition(previous, next)) {
            // Don't crash - log and apply anyway. Real-world cancel/resume
            // races can produce technically-illegal transitions and
            // crashing mid-install is worse than a noisy log.
            Log.w(TAG, "$id: unexpected transition $previous -> $next")
        }
        _state.value = _state.value.toMutableMap().also { it[id] = next }
    }

    private fun partialFor(id: String): File = File(tmpRoot, "$id.partial")

    /**
     * Pluggable HTTP layer so tests can swap in a `file://` reader without
     * spinning up a server. The default uses [HttpURLConnection] with
     * Range-resume.
     */
    interface HttpClient {
        /**
         * Streams [url] into [destFile], appending if the file already has
         * bytes. Returns the final total size. [onProgress] fires roughly
         * every 256 KB.
         */
        suspend fun fetchToFile(
            url: String,
            destFile: File,
            onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
        ): Long
    }

    companion object {
        private const val TAG = "PackDownloader"

        @Volatile private var instance: PackDownloader? = null

        fun get(context: Context): PackDownloader {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val app = context.applicationContext
                val regionsRoot = File(app.filesDir, RegionPack.REGIONS_SUBDIR)
                val tmpRoot = File(regionsRoot, "tmp")
                val store = RegionStore.get(app)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val downloader = PackDownloader(
                    regionsRoot = regionsRoot,
                    tmpRoot = tmpRoot,
                    store = store,
                    httpClient = HttpUrlConnectionClient(),
                    scope = scope,
                )
                instance = downloader
                return downloader
            }
        }

        /** Test-only ctor: callers supply their own [scope] / [httpClient]. */
        internal fun forTest(
            regionsRoot: File,
            store: RegionStore,
            httpClient: HttpClient,
            scope: CoroutineScope,
        ): PackDownloader = PackDownloader(
            regionsRoot = regionsRoot,
            tmpRoot = File(regionsRoot, "tmp").also { it.mkdirs() },
            store = store,
            httpClient = httpClient,
            scope = scope,
        )
    }
}

/**
 * Default [PackDownloader.HttpClient]. Issues a single GET with optional
 * `Range: bytes=N-` when [destFile] already holds N bytes; appends to the
 * existing file. Reads a fresh [HttpURLConnection] per call - Java's URL
 * connection pooling handles connection reuse for us.
 */
internal class HttpUrlConnectionClient : PackDownloader.HttpClient {

    override suspend fun fetchToFile(
        url: String,
        destFile: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val resumeFrom = if (destFile.exists()) destFile.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (resumeFrom > 0) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
            }
        }

        try {
            val code = conn.responseCode
            val rangeHonoured = code == HttpURLConnection.HTTP_PARTIAL
            // 416 = "Requested Range Not Satisfiable" - the partial on disk
            // is bigger than the resource (e.g. catalog now points at a
            // smaller v2 tarball but tmp/ still holds the v1 partial).
            // Drop the bad partial and retry from byte 0 with no Range
            // header. PackDownloader's own pre-check usually catches this,
            // but the safety net here covers any other version-bump path.
            if (code == 416) {
                Log.w("HttpUrlConnectionClient",
                    "HTTP 416 for $url - discarding stale partial and retrying from 0")
                conn.disconnect()
                destFile.delete()
                return@withContext fetchToFile(url, destFile, onProgress)
            }
            if (code !in 200..299) {
                throw IOException("HTTP $code ${conn.responseMessage} for $url")
            }
            if (resumeFrom > 0 && !rangeHonoured) {
                // Server ignored Range - start over, otherwise we'd
                // duplicate the prefix in the partial file.
                destFile.delete()
            }
            // Content-Length on a partial response is the *remaining* bytes;
            // add the resume offset back in to compute the final size.
            val contentLength = conn.contentLengthLong.takeIf { it >= 0 } ?: -1L
            val expectedTotal = if (contentLength < 0) -1L
                else if (rangeHonoured) resumeFrom + contentLength
                else contentLength

            val startedAt = if (rangeHonoured) resumeFrom else 0L
            FileOutputStream(destFile, /* append = */ rangeHonoured).use { out ->
                conn.inputStream.use { input ->
                    streamWithProgress(input, out, startedAt, expectedTotal, onProgress)
                }
            }
            destFile.length()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun streamWithProgress(
        input: InputStream,
        out: FileOutputStream,
        startedAt: Long,
        expectedTotal: Long,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var done = startedAt
        var sinceLastTick = 0L
        onProgress(done, expectedTotal)
        while (true) {
            // Cooperative cancellation - throws CancellationException if the
            // surrounding coroutine has been cancelled (PackDownloader.cancel).
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            sinceLastTick += n
            if (sinceLastTick >= PROGRESS_TICK_BYTES) {
                onProgress(done, expectedTotal)
                sinceLastTick = 0L
            }
        }
        onProgress(done, expectedTotal)
    }

    private companion object {
        const val PROGRESS_TICK_BYTES = 256L * 1024
    }
}
