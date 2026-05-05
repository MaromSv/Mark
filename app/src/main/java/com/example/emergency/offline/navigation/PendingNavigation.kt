package com.example.emergency.offline.navigation

import com.example.emergency.offline.OfflineRouter

/**
 * Tiny process-wide handoff slot used to ferry an [OfflineRouter.Result]
 * from the route-preview screen ([com.example.emergency.ui.screen.MapScreen])
 * to the active-navigation screen ([com.example.emergency.ui.screen.navigation.NavigationScreen])
 * without serialising the whole polyline + steps through a navigation
 * argument string.
 *
 * Single-slot, latest-write-wins. The producer (RouteInfoCard's "Start"
 * button) writes immediately before navigating; the consumer reads on
 * mount and clears. If the slot is empty when NavigationScreen reads,
 * it renders an empty state and offers a back button - better than
 * crashing on a stale process restart.
 *
 * No persistence - survives in-memory navigation only. Process death
 * loses the pending route, which is the correct behaviour (the user
 * comes back to the map and re-taps anyway).
 */
object PendingNavigation {
    @Volatile var current: Handoff? = null

    data class Handoff(
        val route: OfflineRouter.Result,
        val profile: NavigationProfile,
    )

    fun take(): Handoff? {
        val h = current
        current = null
        return h
    }
}
