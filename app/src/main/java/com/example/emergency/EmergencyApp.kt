package com.example.emergency

import android.app.Application
import com.example.emergency.offline.OfflineBootstrap

/**
 * Custom [Application] that bootstraps the offline-asset staging job at
 * process start, so the 470 MB APK->filesDir copy overlaps with the user
 * navigating from the home screen to the map instead of being a 9-second
 * full-screen blocker.
 *
 * Registered via `android:name=".EmergencyApp"` in the manifest.
 */
class EmergencyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineBootstrap.start(this)
    }
}
