package com.example.emergency.offline.pack

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 verification helpers for downloaded packs (plan section 8 step 4).
 *
 * Two scopes:
 *   * **Tarball-level**: the catalog (plan section 6) ships one sha256 per pack
 *     URL; verifying that against the bytes-on-disk catches transport
 *     corruption and CDN/repo tampering before extraction.
 *   * **File-level**: the per-pack `manifest.json` (see [PackManifest])
 *     repeats sha256 + size for every artefact inside the tarball. After
 *     extraction we cross-check those so a half-extracted pack (process
 *     killed mid-write) can never look "installed".
 *
 * SHA-256 is used because (a) it's already in [java.security.MessageDigest]
 * with no extra deps, and (b) the upstream pack-build pipeline writes hex
 * sha256 strings in `manifest.json`. Streamed byte-by-byte so very large
 * packs don't allocate the whole file in memory.
 */
object ChecksumVerifier {

    /** Result of a verification attempt - sealed so callers must handle each case. */
    sealed class Result {
        data object Ok : Result()
        data class Mismatch(val path: String, val expected: String, val actual: String) : Result()
        data class WrongSize(val path: String, val expected: Long, val actual: Long) : Result()
        data class Missing(val path: String) : Result()

        val isOk: Boolean get() = this is Ok
    }

    /** SHA-256 hex of [file]'s bytes. Lower-case, 64 chars. */
    fun sha256(file: File): String =
        file.inputStream().use { sha256(it) }

    /** SHA-256 hex of every byte read from [input] until EOF. */
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    /**
     * Verifies a single tarball's bytes match [expectedSha256]. Used right
     * after the download finishes and *before* extraction - a corrupt tarball
     * never touches the on-disk pack tree.
     */
    fun verifyTarball(tarball: File, expectedSha256: String): Result {
        if (!tarball.exists()) return Result.Missing(tarball.absolutePath)
        val actual = sha256(tarball)
        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            Result.Ok
        } else {
            Result.Mismatch(tarball.absolutePath, expectedSha256.lowercase(), actual)
        }
    }

    /**
     * Verifies every file listed in [manifest] exists under [packRoot] with
     * the expected size and sha256. Returns the first failure encountered -
     * one bad file is a bad install regardless of what comes after.
     */
    fun verifyPack(packRoot: File, manifest: PackManifest): Result {
        for (entry in manifest.files) {
            val f = File(packRoot, entry.path)
            if (!f.exists()) return Result.Missing(entry.path)
            val size = f.length()
            if (size != entry.sizeBytes) {
                return Result.WrongSize(entry.path, entry.sizeBytes, size)
            }
            val actual = sha256(f)
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                return Result.Mismatch(entry.path, entry.sha256.lowercase(), actual)
            }
        }
        return Result.Ok
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val b = this[i].toInt() and 0xFF
            out[i * 2]     = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
