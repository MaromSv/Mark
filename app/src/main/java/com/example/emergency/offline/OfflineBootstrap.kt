package com.example.emergency.offline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide owner of the offline-asset staging job.
 *
 * Why this exists: previously the 470 MB APK->filesDir copy happened inside the
 * map composable's [androidx.compose.runtime.LaunchedEffect], hidden behind a
 * full-screen blocking overlay. That meant the user saw a 9-second wall every
 * time they entered the map screen. By moving the kick-off to
 * [com.example.emergency.EmergencyApp.onCreate], the copy starts the moment
 * the process boots and runs in parallel with everything else (home screen,
 * GPS permission prompt, map view inflation). By the time the user actually
 * navigates to the map, staging is usually already done - and even if it
 * isn't, the map UI is fully interactive while a small progress pill informs
 * the user.
 *
 * Idempotent: [start] guards against re-entry with an [AtomicBoolean]. Safe to
 * call from any thread.
 */
object OfflineBootstrap {

    private const val TAG = "OfflineBootstrap"

    /**
     * Snapshot of where the staging job is. Map UI keys off this to decide
     * whether to load the offline tile style or wait.
     */
    sealed interface Status {
        /** No work has been requested yet. */
        data object Idle : Status

        /** Copy in progress. [done] / [total] are file counts, not bytes. */
        data class Staging(val done: Int, val total: Int) : Status

        /** Staging finished - [paths] are ready to use. */
        data class Ready(val paths: OfflineAssets.Paths) : Status

        /** Copy failed; UI surfaces [message] and the map falls back gracefully. */
        data class Failed(val message: String) : Status
    }

    private val _state = MutableStateFlow<Status>(Status.Idle)
    val state: StateFlow<Status> = _state.asStateFlow()

    // Survives configuration changes; tied to process lifetime so it never
    // leaks an Activity context. SupervisorJob means a thrown copy doesn't
    // poison future launches.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val started = AtomicBoolean(false)

    /**
     * Kick off staging if it hasn't been started yet. Returns immediately;
     * progress is observable via [state].
     *
     * Pass an [android.app.Application] (or any long-lived context) so we
     * never accidentally hold an Activity ref.
     */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext

        // Fast path: assets were staged on a previous launch. Skip the IO
        // dispatch entirely so the very first state emission is already Ready.
        if (OfflineAssets.isStaged(appContext)) {
            Log.d(TAG, "Already staged on disk - skipping copy")
            _state.value = Status.Ready(OfflineAssets.pathsFor(appContext))
            return
        }

        Log.d(TAG, "Starting first-launch asset staging")
        _state.value = Status.Staging(done = 0, total = 1)
        scope.launch {
            try {
                val paths = OfflineAssets.ensureStaged(appContext) { done, total ->
                    _state.value = Status.Staging(done, total)
                }
                _state.value = Status.Ready(paths)
                Log.d(TAG, "Staging complete")
            } catch (t: Throwable) {
                Log.e(TAG, "Staging failed", t)
                _state.value = Status.Failed(t.message ?: "Failed to prepare offline data")
            }
        }
    }
}
