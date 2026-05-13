package com.example.emergency.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.navigation.NavigationProfile
import com.example.emergency.ui.screen.common.SubScreenTopBar
import com.example.emergency.ui.screen.map.InteractiveMap
import com.example.emergency.ui.screen.map.MapDestination
import com.example.emergency.ui.state.MapFilter
import com.example.emergency.ui.state.MapPoi
import com.example.emergency.ui.state.MapUiState
import com.example.emergency.ui.theme.EmergencyTheme

/**
 * Map sub-screen. Hosts the [SubScreenTopBar] (so the back-button contract
 * with [com.example.emergency.ui.nav.AppNavHost] keeps working) and an
 * interactive MapLibre map underneath.
 *
 * The placeholder filter chips and POI list that used to live here are gone:
 * the real map ships with clustering and tap-to-route, which supersedes them.
 * The legacy [onFilterClick] / [onPoiClick] callbacks are kept as no-ops so
 * the public signature stays stable for the navigation graph and any
 * existing previews.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    onBack: () -> Unit = {},
    onOpenRegions: () -> Unit = {},
    onStartNavigation: (OfflineRouter.Result, NavigationProfile, MapDestination?) -> Unit = { _, _, _ -> },
    initialDestination: MapDestination? = null,
    @Suppress("UNUSED_PARAMETER") onFilterClick: (MapFilter) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onPoiClick: (MapPoi) -> Unit = {},
) {
    val colors = EmergencyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        SubScreenTopBar(
            title = state.title,
            onBack = onBack,
            trailing = {
                IconButton(onClick = onOpenRegions) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = "Map regions",
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        InteractiveMap(
            modifier = Modifier.fillMaxSize(),
            initialDestination = initialDestination,
            onOpenRegions = onOpenRegions,
            onStartNavigation = onStartNavigation,
        )
    }
}
