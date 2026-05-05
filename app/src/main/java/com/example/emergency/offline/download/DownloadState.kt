package com.example.emergency.offline.download

/**
 * Lifecycle of a single pack download (plan section 8 step 4).
 *
 * ```
 * Idle -> Queued -> Downloading -> Verifying -> Installing -> Installed
 *                  |                                 |
 *                  +---> Paused(reason)               +---> Failed(reason)
 * ```
 *
 * `Paused` is reachable from `Downloading` only - once verification
 * starts, abandoning halfway is a `Failed`, not a pause (we can't resume
 * a verify; just retry). `Failed` and `Installed` are terminal.
 *
 * Why a sealed class instead of an enum: the active state needs to carry
 * payload (download progress; pause / failure reasons) so observers can
 * render meaningful UI without a parallel side-channel.
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Queued : DownloadState()

    /**
     * Active byte transfer. [bytesTotal] may be -1 if the catalog didn't
     * advertise a size and the server didn't return Content-Length on the
     * range response - UI should show indeterminate progress in that case.
     */
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : DownloadState()

    /** SHA-256ing the full tarball before extraction. */
    data object Verifying : DownloadState()

    /** Extracting tar.gz + per-file sha256 + atomic rename. */
    data object Installing : DownloadState()

    /** Pack is on disk and registered. Terminal. */
    data object Installed : DownloadState()

    /** Network gone, user paused, or storage full. Resumable. */
    data class Paused(val reason: String) : DownloadState()

    /** Hard failure - checksum mismatch, tar parse error, IO error. Terminal. */
    data class Failed(val reason: String) : DownloadState()

    val isTerminal: Boolean get() = this is Installed || this is Failed
}

/**
 * Best-effort transition validator. Returns true when [from] -> [to] is a
 * sane progression for a single pack. Used by [PackDownloader] to log a
 * warning (not crash) when an unexpected transition happens - the goal is
 * catching bugs in the orchestrator without crashing a user mid-install.
 */
internal fun isValidTransition(from: DownloadState, to: DownloadState): Boolean = when (from) {
    DownloadState.Idle -> to is DownloadState.Queued || to is DownloadState.Downloading
    DownloadState.Queued -> to is DownloadState.Downloading ||
        to is DownloadState.Paused || to is DownloadState.Failed
    is DownloadState.Downloading -> to is DownloadState.Downloading ||
        to is DownloadState.Verifying ||
        to is DownloadState.Paused ||
        to is DownloadState.Failed
    DownloadState.Verifying -> to is DownloadState.Installing || to is DownloadState.Failed
    DownloadState.Installing -> to is DownloadState.Installed || to is DownloadState.Failed
    is DownloadState.Paused -> to is DownloadState.Queued ||
        to is DownloadState.Downloading ||
        to is DownloadState.Failed
    DownloadState.Installed, is DownloadState.Failed -> false
}
