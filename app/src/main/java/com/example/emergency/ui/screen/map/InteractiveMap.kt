package com.example.emergency.ui.screen.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.emergency.offline.MbtilesServer
import com.example.emergency.offline.OfflineAssets
import com.example.emergency.offline.OfflineBootstrap
import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.navigation.NavigationProfile
import com.example.emergency.offline.pack.CatalogProvider
import com.example.emergency.offline.pack.RegionPack
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.offline.routing.RouteOutcome
import com.example.emergency.offline.routing.successOrNull
import java.io.File
import com.google.android.gms.location.LocationServices
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.WellKnownTileServer
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonOptions
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "InteractiveMap"
// Bump whenever app/src/main/assets/pois-nl.geojson changes. The first launch
// after an upgrade will overwrite the cached copy in filesDir; otherwise the
// device keeps showing whatever was bundled at install time.
private const val POI_BUNDLE_VERSION = 2

internal val DAM_SQUARE = LatLng(52.3731, 4.8926)

// PDOK basemap only covers the Netherlands; outside this bbox we keep the
// camera anchored to Dam Square instead of flying into a tile-less void
// (most commonly the emulator's default Mountain View location).
internal val NL_BBOX_SW = LatLng(50.7, 3.3)
internal val NL_BBOX_NE = LatLng(53.6, 7.3)

internal fun LatLng.isInNL(): Boolean =
    latitude in NL_BBOX_SW.latitude..NL_BBOX_NE.latitude &&
        longitude in NL_BBOX_SW.longitude..NL_BBOX_NE.longitude

// POI categories present in assets/pois-nl.geojson. Listed once so icon
// registration, color stops and bottom-card mappings stay in sync.
private val POI_CATEGORIES = listOf(
    "hospital", "doctor", "first_aid", "aed", "pharmacy", "police", "fire",
    "water", "toilet", "bunker",
    "fuel", "supermarket", "atm", "phone",
)

private data class Poi(val name: String, val category: String, val lat: Double, val lon: Double)

internal data class RouteResult(
    val polyline: List<LatLng>,
    val distanceM: Double,
    val durationS: Double,
)

private enum class Mode(
    val brouterProfile: String,
    val label: String,
    val icon: ImageVector,
    val navProfile: NavigationProfile,
) {
    Walk("trekking", "Walk", Icons.Default.DirectionsWalk, NavigationProfile.Walking),
    Bike("fastbike", "Bike", Icons.Default.DirectionsBike, NavigationProfile.Biking),
    Drive("car-fast", "Drive", Icons.Default.DirectionsCar, NavigationProfile.Driving),
}

/**
 * Human-readable label for a POI category. Used as a fallback "name"
 * when an OSM POI has no `name` tag (very common for ATMs, AEDs, public
 * toilets, drinking-water taps). Mirrors `categoryLabel` in
 * ChatThreadScreen so the chat preview and the map info card agree.
 */
private fun displayLabelForCategory(category: String): String = when (category) {
    "atm"                 -> "ATM"
    "aed"                 -> "AED"
    "first_aid"           -> "Medical post"
    "parking_underground" -> "Parking"
    "wc", "toilet"        -> "Toilet"
    else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "hospital"            -> Icons.Default.LocalHospital
    "aed"                 -> Icons.Default.Favorite
    "pharmacy"            -> Icons.Default.LocalPharmacy
    "police"              -> Icons.Default.LocalPolice
    "fire"                -> Icons.Default.LocalFireDepartment
    "bunker"              -> Icons.Default.Shield
    "fuel"                -> Icons.Default.LocalGasStation
    "supermarket"         -> Icons.Default.LocalGroceryStore
    "atm"                 -> Icons.Default.LocalAtm
    "phone"               -> Icons.Default.Phone
    else                  -> Icons.Default.Place
}

private fun categoryColor(category: String): Color = when (category) {
    "hospital"            -> Color(0xFFE53935)
    "doctor"              -> Color(0xFFEC407A)
    "first_aid"           -> Color(0xFFD32F2F)
    "aed"                 -> Color(0xFFFB8C00)
    "pharmacy"            -> Color(0xFF43A047)
    "police"              -> Color(0xFF1E40AF)
    "fire"                -> Color(0xFFB71C1C)
    "water"               -> Color(0xFF29B6F6)
    "toilet"              -> Color(0xFF6D4C41)
    "bunker"              -> Color(0xFF424242)
    "fuel"                -> Color(0xFFF9A825)
    "supermarket"         -> Color(0xFF7CB342)
    "atm"                 -> Color(0xFF00ACC1)
    "phone"               -> Color(0xFF8E24AA)
    else                  -> Color(0xFF757575)
}

/**
 * Full-bleed interactive Compose map. Drop it inside any Box/Column and it
 * will fill the available space with a MapLibre view, GPS fix, POI clusters
 * and tap-to-route. Callers are responsible for the surrounding chrome
 * (top bar, status bar padding, etc.).
 *
 * [Mapbox.getInstance] is a singleton initializer and safe to call repeatedly,
 * so we run it on first composition without coordinating with the host
 * Activity.
 */
@Composable
fun InteractiveMap(
    modifier: Modifier = Modifier,
    initialDestination: MapDestination? = null,
    onOpenRegions: () -> Unit = {},
    onStartNavigation: (OfflineRouter.Result, NavigationProfile) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current

    // Idempotent - MapLibre guards internally against re-entry. Keeping it
    // here means callers don't need to remember to bootstrap from Application
    // or MainActivity. The .also { setConnected } returns the Mapbox
    // instance so `remember` has a non-Unit value to cache (Compose lint
    // requires `remember {}` to return something other than Unit).
    remember {
        Mapbox.getInstance(context, null, WellKnownTileServer.MapLibre).also {
            Mapbox.setConnected(true)
        }
    }

    // Region pipeline (plan section 6 / Step 6). RegionStore + CatalogProvider feed
    // the multi-pack OfflineRouter so we can pre-flight routes against the
    // installed-pack union and return typed outcomes for honest errors.
    val regionStore = remember { RegionStore.get(context) }
    val catalogProvider = remember { CatalogProvider.get(context) }
    val installedPacks: List<RegionPack> by regionStore.state.collectAsState()
    val catalog by catalogProvider.catalog.collectAsState()
    val activeRoot = remember { File(context.filesDir, "regions/_active") }

    var mode by remember { mutableStateOf(Mode.Walk) }
    var selectedPoi by remember {
        mutableStateOf<Poi?>(
            initialDestination?.let { Poi(it.name, it.category, it.lat, it.lon) }
        )
    }
    var routeResult by remember { mutableStateOf<RouteResult?>(null) }
    var routeOutcome by remember { mutableStateOf<RouteOutcome?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf(DAM_SQUARE) }
    var routeSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var userLocationSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var selectedDestSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // Captured once the underlying MapboxMap is ready so other effects can
    // animate the camera (e.g., to the user's GPS fix) without re-entering
    // getMapAsync.
    var mapboxMap by remember { mutableStateOf<MapboxMap?>(null) }

    // Offline data plane: staging is kicked off at process start by
    // [com.example.emergency.EmergencyApp], so by the time the map screen
    // mounts the copy is usually already done. We observe the shared state
    // here instead of starting the work ourselves. While staging is still
    // running, the map UI stays interactive - only the tile server / route
    // engine wait for paths to become available.
    val bootstrapStatus by OfflineBootstrap.state.collectAsState()
    val offlinePaths: OfflineAssets.Paths? =
        (bootstrapStatus as? OfflineBootstrap.Status.Ready)?.paths
    var tileServerStartError by remember { mutableStateOf<String?>(null) }

    // Tile server lives only while the composable is on screen. Re-keying on
    // [offlinePaths] means it spins up the moment staging completes, even if
    // the user was already on the map screen. The server is only constructed
    // when the bundled skeleton mbtiles actually exists on disk - the
    // skeleton is opt-in (multi-hour build, see scripts/build-pack/skeleton-build.sh)
    // so during dev iteration the map still renders, just without basemap
    // tiles (style.json's background color shows through).
    // Pick the right tiles file: prefer an installed region pack
    // (covers the user's active area in detail z0-14); fall back to the
    // bundled global skeleton (z0-6 worldwide) when no pack is installed
    // and the skeleton was actually built. Re-key on installedPacks so
    // the tile server swaps the moment a pack download finishes.
    //
    // TODO multi-pack: when more than one pack is installed, this
    // currently uses just the first one (alphabetical by id). A future
    // change should pick the pack whose bbox contains the camera centre,
    // or compose multiple mbtiles behind a single tile URL.
    val tileServer = remember(offlinePaths, installedPacks) {
        val packTiles = installedPacks
            .map { it.tilesFile }
            .firstOrNull { it.exists() }
        val mbtiles = packTiles
            ?: offlinePaths?.skeletonMbtiles?.takeIf { it.exists() }
        mbtiles?.let { MbtilesServer(it) }
    }
    DisposableEffect(tileServer) {
        val server = tileServer
        if (server != null) {
            try {
                server.start()
                tileServerStartError = null
            } catch (e: Exception) {
                Log.e(TAG, "MBTiles server failed to start", e)
                tileServerStartError = "Tile server start failed: ${e.message}"
            }
        }
        onDispose { server?.runCatching { stop() } }
    }

    LaunchedEffect(Unit) {
        getUserLocation(context)?.let {
            userLocation = if (it.isInNL()) it else DAM_SQUARE
            Log.d(
                TAG,
                "GPS fix ${it.latitude},${it.longitude} -> using " +
                    "${userLocation.latitude},${userLocation.longitude}" +
                    if (!it.isInNL()) " (out of NL - clamped to Dam Square)" else "",
            )
        } ?: Log.d(TAG, "No GPS - falling back to Dam Square")
    }

    val mapView = remember {
        val options = MapboxMapOptions.createFromAttributes(context).textureMode(true)
        MapView(context, options).apply {
            id = View.generateViewId()
            onCreate(null)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView, tileServer) {
        // Always load a style so the user-location dot, route, and POI
        // overlays render even when the skeleton mbtiles isn't built yet.
        // When the tile server is up we use the full OpenMapTiles vector
        // style; when not we fall back to a background-only style so the
        // overlays still have a canvas.
        Log.d(TAG, "getMapAsync requested (tile server=${tileServer?.tileUrlTemplate ?: "<none>"})")
        mapView.getMapAsync { map ->
            Log.d(TAG, "MapboxMap ready; setting style")
            mapboxMap = map
            // Hide the MapLibre logo (their library branding, removable
            // under MapLibre's BSD license). Keep the attribution button -
            // OSM's ODbL data license requires attribution to OpenStreetMap
            // contributors, and the (i) icon is the smallest, most standard
            // way to provide it.
            map.uiSettings.apply {
                isLogoEnabled = false
                // Hidden in the map UI to keep the canvas clean. NOTE:
                // OSM's ODbL data license requires "(c) OpenStreetMap
                // contributors" credit somewhere visible - add it to a
                // future Settings/About screen so the app stays compliant.
                isAttributionEnabled = false
            }
            // Open at city-level zoom on whatever location we currently have
            // (Dam Square fallback). The LaunchedEffect below will animate to
            // the real GPS fix as soon as it resolves.
            map.cameraPosition = CameraPosition.Builder()
                .target(userLocation)
                .zoom(14.0)
                .build()
            val styleJson = tileServer
                ?.let { buildOfflineStyle(context, it.tileUrlTemplate) }
                ?: FALLBACK_BACKGROUND_STYLE
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                Log.d(TAG, "Style loaded; layers=${style.layers.size}, sources=${style.sources.size}")
                try {
                    addPoiLayer(context, style)
                    routeSource = addRouteLayer(style)
                    userLocationSource = addUserLocationLayer(style)
                    selectedDestSource = addSelectedDestinationLayer(style)
                    Log.d(TAG, "POI + route + user-location + selected-dest layers attached")
                } catch (e: Exception) {
                    Log.e(TAG, "Layer setup failed", e)
                }
            }
            map.addOnMapClickListener { latLng ->
                val pt = map.projection.toScreenLocation(latLng)
                val touchRect = RectF(pt.x - 30, pt.y - 30, pt.x + 30, pt.y + 30)

                // Cluster tap first: query the cluster circles. If hit,
                // animate to the zoom level where this cluster breaks apart
                // (MapLibre's getClusterExpansionZoom gives the precise
                // value; fall back to current+2 if it isn't available).
                val clusterHits = map.queryRenderedFeatures(touchRect, "clusters-layer")
                if (clusterHits.isNotEmpty()) {
                    val cluster = clusterHits[0]
                    val coord = cluster.geometry() as? Point
                    if (coord != null) {
                        val source = map.style?.getSourceAs<GeoJsonSource>("pois-source")
                        val expansionZoom = runCatching {
                            source?.getClusterExpansionZoom(cluster)?.toDouble()
                        }.getOrNull() ?: (map.cameraPosition.zoom + 2.0)
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(coord.latitude(), coord.longitude()),
                                expansionZoom.coerceIn(2.0, 18.0),
                            ),
                            400,
                        )
                    }
                    return@addOnMapClickListener true
                }

                // Otherwise individual POI tap.
                val hits = map.queryRenderedFeatures(touchRect, "pois-layer")
                if (hits.isNotEmpty()) {
                    val f = hits[0]
                    val rawName = f.getStringProperty("name")
                    val category = f.getStringProperty("category") ?: "place"
                    // OSM POIs without a `name` tag (most ATMs, public toilets,
                    // water fountains, AEDs) used to fall back to the literal
                    // string "POI" which reads as a placeholder. Use the
                    // category as the display label instead so the user sees
                    // "ATM" / "Toilet" / "AED" rather than "POI".
                    val name = if (!rawName.isNullOrBlank()) rawName
                        else displayLabelForCategory(category)
                    val coord = f.geometry() as Point
                    selectedPoi = Poi(name, category, coord.latitude(), coord.longitude())
                    true
                } else false
            }
        }
    }

    // Animate the map to the current GPS fix once both the map is ready and
    // a user location has resolved. The PDOK basemap only covers NL, so if
    // the GPS reports somewhere else we stay on Dam Square - otherwise the
    // user sees a black void of un-tiled ocean.
    LaunchedEffect(mapboxMap, userLocation, initialDestination) {
        val map = mapboxMap ?: return@LaunchedEffect
        val target = if (userLocation.isInNL()) userLocation else DAM_SQUARE
        if (target !== userLocation) {
            Log.d(TAG, "GPS ${userLocation.latitude},${userLocation.longitude} outside NL - using Dam Square")
        }
        val update = if (initialDestination != null) {
            val dest = LatLng(initialDestination.lat, initialDestination.lon)
            val bounds = LatLngBounds.Builder().include(target).include(dest).build()
            // 180px padding leaves room for the top mode selector and the
            // bottom route info card without cropping either endpoint.
            CameraUpdateFactory.newLatLngBounds(bounds, 180)
        } else {
            CameraUpdateFactory.newLatLngZoom(target, 15.0)
        }
        map.animateCamera(update, 800)
    }

    LaunchedEffect(userLocationSource, userLocation) {
        val src = userLocationSource ?: return@LaunchedEffect
        val pt = Point.fromLngLat(userLocation.longitude, userLocation.latitude)
        src.setGeoJson(Feature.fromGeometry(pt))
    }

    // Mirror the selected destination into a dedicated source so the route
    // endpoint always shows a marker - including LLM-supplied destinations
    // that aren't part of the bundled POI dataset.
    LaunchedEffect(selectedDestSource, selectedPoi) {
        val src = selectedDestSource ?: return@LaunchedEffect
        val poi = selectedPoi
        if (poi != null) {
            val props = com.google.gson.JsonObject().apply {
                addProperty("category", poi.category)
                addProperty("name", poi.name)
            }
            val feature = Feature.fromGeometry(
                Point.fromLngLat(poi.lon, poi.lat),
                props,
            )
            src.setGeoJson(feature)
        } else {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    // Fetch route whenever (selectedPoi, mode, userLocation, offlinePaths,
    // installed pack set) changes. Re-keying on offlinePaths means the first
    // route after staging completes runs immediately; re-keying on the pack
    // list means a fresh install retriggers routing automatically.
    LaunchedEffect(selectedPoi, mode, userLocation, offlinePaths, installedPacks, catalog) {
        val poi = selectedPoi ?: run {
            routeResult = null
            routeOutcome = null
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return@LaunchedEffect
        }
        val paths = offlinePaths ?: run {
            // Staging not done yet - UI shows "Calculating route..." via
            // [routeLoading] so the user knows we'll route once data lands.
            routeLoading = true
            return@LaunchedEffect
        }
        routeLoading = true
        val outcome = OfflineRouter.route(
            from = userLocation,
            to = LatLng(poi.lat, poi.lon),
            profileName = mode.brouterProfile,
            profilesDir = paths.profilesDir,
            installedPacks = installedPacks,
            catalog = catalog.packs,
            activeRoot = activeRoot,
        )
        routeLoading = false
        routeOutcome = outcome
        if (outcome is RouteOutcome.Success && outcome.result.polyline.size > 1) {
            routeResult = RouteResult(
                polyline = outcome.result.polyline,
                distanceM = outcome.result.distanceM,
                durationS = outcome.result.durationS,
            )
            val pts = outcome.result.polyline.map { Point.fromLngLat(it.longitude, it.latitude) }
            routeSource?.setGeoJson(LineString.fromLngLats(pts))
        } else {
            routeResult = null
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            Log.w(TAG, "Routing did not produce a polyline: $outcome")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        ModeSelector(
            current = mode,
            onSelect = { mode = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
        )

        AnimatedVisibility(
            visible = selectedPoi != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            RouteInfoCard(
                poi = selectedPoi,
                route = routeResult,
                outcome = routeOutcome,
                mode = mode,
                loading = routeLoading,
                onDismiss = { selectedPoi = null },
                onOpenRegions = onOpenRegions,
                onStart = {
                    routeOutcome?.successOrNull()?.let { result ->
                        onStartNavigation(result, mode.navProfile)
                    }
                },
                modifier = Modifier
                    .padding(16.dp)
                    .padding(bottom = 24.dp),
            )
        }

        // Small non-blocking progress pill, only visible while the bootstrap
        // is mid-copy (or has errored out). The map stays fully interactive -
        // mode selector, GPS dot, POI taps all work; tiles just render once
        // the local server comes up.
        StagingPill(
            status = bootstrapStatus,
            tileServerError = tileServerStartError,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp),
        )

        // First-launch nudge: if no map packs are installed, surface a
        // prominent banner pointing the user at the picker. The cloud icon
        // in the top bar is easy to miss for first-time users; this banner
        // makes the "you need to download a region first" step explicit.
        // Auto-hides as soon as any pack is installed.
        AnimatedVisibility(
            visible = installedPacks.isEmpty() &&
                bootstrapStatus !is OfflineBootstrap.Status.Staging,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 110.dp, start = 16.dp, end = 16.dp),
        ) {
            NoPacksBanner(onOpenRegions = onOpenRegions)
        }
    }
}

@Composable
private fun NoPacksBanner(onOpenRegions: () -> Unit) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(EmergencyShapes.hero)
            .background(colors.text)
            .clickable(onClick = onOpenRegions)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = colors.bg,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Pick a region to use offline",
                style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = colors.bg,
            )
            Text(
                text = "Tap to download maps + routing for your area",
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.bg.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Slim non-blocking progress pill. Renders at the top of the map while the
 * offline bundle is still being staged (or if something failed). Hidden once
 * staging is [OfflineBootstrap.Status.Ready].
 */
@Composable
private fun StagingPill(
    status: OfflineBootstrap.Status,
    tileServerError: String?,
    modifier: Modifier = Modifier,
) {
    val errorText = (status as? OfflineBootstrap.Status.Failed)?.message
        ?: tileServerError
    val staging = status as? OfflineBootstrap.Status.Staging

    AnimatedVisibility(
        visible = errorText != null || staging != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(
                containerColor = if (errorText != null) Color(0xFFB71C1C) else Color(0xFF111111),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (errorText == null) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    val pct = staging?.let {
                        if (it.total > 0) (it.done * 100 / it.total) else 0
                    } ?: 0
                    Text(
                        "Preparing offline maps... $pct%",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        "Offline data unavailable",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    current: Mode,
    onSelect: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        modifier = modifier
            .clip(EmergencyShapes.full)
            .background(colors.surface)
            .border(1.dp, colors.line, EmergencyShapes.full)
            .padding(4.dp),
    ) {
        Mode.entries.forEach { m ->
            val selected = m == current
            val fg = if (selected) colors.accentInk else colors.text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(EmergencyShapes.full)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    m.icon,
                    contentDescription = m.label,
                    tint = fg,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = m.label,
                    style = typography.listItem,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun RouteInfoCard(
    poi: Poi?,
    route: RouteResult?,
    outcome: RouteOutcome?,
    mode: Mode,
    loading: Boolean,
    onDismiss: () -> Unit,
    onOpenRegions: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    poi ?: return
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val showGetMaps = outcome is RouteOutcome.OutsideDownloadedRegion
    val showStart = route != null && outcome is RouteOutcome.Success

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EmergencyShapes.hero)
            .background(colors.surface)
            .border(1.dp, colors.line, EmergencyShapes.hero),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.panel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIcon(poi.category),
                    contentDescription = poi.category,
                    tint = categoryColor(poi.category),
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name,
                    style = typography.listItem,
                    color = colors.text,
                    maxLines = 2,
                )
                Spacer(Modifier.size(4.dp))
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.textDim,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Calculating route...",
                            style = typography.helper,
                            color = colors.textDim,
                        )
                    }
                    route != null -> Text(
                        text = "${formatDistance(route.distanceM)}   -   ${formatDuration(route.durationS)} ${mode.label.lowercase()}",
                        style = typography.helper,
                        color = colors.textDim,
                    )
                    outcome != null -> Text(
                        text = outcome.userMessage(),
                        style = typography.helper,
                        color = if (outcome is RouteOutcome.OutsideDownloadedRegion)
                            colors.textDim else colors.danger,
                        maxLines = 3,
                    )
                    else -> Text(
                        text = "Routing failed",
                        style = typography.helper,
                        color = colors.danger,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = colors.textFaint,
                )
            }
        }
        if (showGetMaps) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .clip(EmergencyShapes.full)
                    .background(colors.text)
                    .clickable(onClick = onOpenRegions)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Open map regions",
                    style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.bg,
                )
            }
        }
        if (showStart) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .clip(EmergencyShapes.full)
                    .background(colors.text)
                    .clickable(onClick = onStart)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Start navigation",
                    style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.bg,
                )
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000) else "%.0f m".format(meters)

private fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60).toInt()
    return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
}

// --- Map layers --------------------------------------------------------------

// Adds the POI source + layers to the style. POIs are loaded from
// app/src/main/assets/pois-nl.geojson and aggregated client-side via MapLibre
// clustering - without that, rendering dense pins at low zoom would stutter.
private fun addPoiLayer(context: Context, style: Style) {
    POI_CATEGORIES.forEach { name ->
        val resId = context.resources.getIdentifier("ic_poi_$name", "drawable", context.packageName)
        if (resId != 0) {
            drawableToBitmap(context, resId)?.let { bmp ->
                style.addImage("$name-icon", bmp)
            }
        }
    }

    val options = GeoJsonOptions()
        .withCluster(true)
        // Cluster up to z18 so overlapping POIs (e.g. 3 pharmacies in one
        // block) keep showing a tappable count even at street level. Tap
        // the badge -> getClusterExpansionZoom animates to where they
        // spread apart.
        .withClusterMaxZoom(18)
        // 80 px (was 60) merges POIs more aggressively at every zoom so
        // the user always sees counted groupings instead of a soup of
        // overlapping pins.
        .withClusterRadius(80)
    val source = GeoJsonSource("pois-source", options)
    style.addSource(source)

    // The bundled GeoJSON is ~32 MB; reading it as a Kotlin String would
    // allocate ~128 MB on the Java heap (UTF-16 + StringBuilder doubling) and
    // OOM mid-launch. Instead we stream it to internal storage with a bounded
    // buffer and hand MapLibre a file:// URI so the parse happens natively.
    //
    // We copy once per install (existence check). When the bundled asset is
    // updated, the next reinstall replaces filesDir so the copy refreshes
    // automatically; for in-place dev iteration, clear app data.
    Thread {
        try {
            val outFile = java.io.File(context.filesDir, "pois-nl.geojson")
            val versionFile = java.io.File(context.filesDir, "pois-nl.version")
            val cachedVersion = versionFile.takeIf { it.exists() }
                ?.runCatching { readText().trim().toInt() }?.getOrNull() ?: -1
            val needsCopy = !outFile.exists() || cachedVersion != POI_BUNDLE_VERSION
            if (needsCopy) {
                Log.d(
                    TAG,
                    "Copying pois-nl.geojson from assets (cached=$cachedVersion, " +
                        "current=$POI_BUNDLE_VERSION)...",
                )
                context.assets.open("pois-nl.geojson").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                versionFile.writeText(POI_BUNDLE_VERSION.toString())
                Log.d(TAG, "Copied pois-nl.geojson (${outFile.length() / 1024} KB) to ${outFile.absolutePath}")
            } else {
                Log.d(TAG, "Reusing existing pois-nl.geojson (${outFile.length() / 1024} KB)")
            }
            // MapLibre expects a triple-slash file URI; File.toURI() yields
            // file:/path on some JVMs which the native loader rejects.
            val uri = "file://${outFile.absolutePath}"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Log.d(TAG, "Setting POI source URI: $uri")
                source.setUri(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage pois-nl.geojson", e)
        }
    }.start()

    val categoryColorExpr = Expression.match(
        Expression.get("category"),
        Expression.literal("#9E9E9E"),
        Expression.stop("hospital",            "#E53935"),
        Expression.stop("doctor",              "#EC407A"),
        Expression.stop("first_aid",           "#D32F2F"),
        Expression.stop("aed",                 "#FB8C00"),
        Expression.stop("pharmacy",            "#43A047"),
        Expression.stop("police",              "#1E40AF"),
        Expression.stop("fire",                "#B71C1C"),
        Expression.stop("water",               "#29B6F6"),
        Expression.stop("toilet",              "#6D4C41"),
        Expression.stop("bunker",              "#424242"),
        Expression.stop("fuel",                "#F9A825"),
        Expression.stop("supermarket",         "#7CB342"),
        Expression.stop("atm",                 "#00ACC1"),
        Expression.stop("phone",               "#8E24AA"),
    )

    val unclustered = Expression.not(Expression.has("point_count"))
    val clustered = Expression.has("point_count")

    // Cluster bubble: blue circle that grows with the count. Bumped the
    // base radius (was 16) so single-digit clusters stay visibly tappable
    // when zoomed out across a country, and stops at higher counts so
    // urban hot-spots clearly stand out from rural ones.
    val clusterCircle = CircleLayer("clusters-layer", "pois-source").withProperties(
        PropertyFactory.circleColor("#1E88E5"),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(3f),
        PropertyFactory.circleOpacity(0.95f),
        // Bigger across the board so the badges read as "tappable group"
        // even at country zoom. Was 20/24/30/36/42, now 26/32/40/48/56.
        PropertyFactory.circleRadius(
            Expression.step(
                Expression.toNumber(Expression.get("point_count")),
                Expression.literal(26f),
                Expression.stop(20, 32),
                Expression.stop(100, 40),
                Expression.stop(500, 48),
                Expression.stop(2000, 56),
            )
        ),
    )
    clusterCircle.setFilter(clustered)
    style.addLayer(clusterCircle)

    val clusterCount = SymbolLayer("clusters-count-layer", "pois-source").withProperties(
        PropertyFactory.textField("{point_count_abbreviated}"),
        PropertyFactory.textSize(16f),
        PropertyFactory.textColor("#FFFFFF"),
        PropertyFactory.textAllowOverlap(true),
        PropertyFactory.textIgnorePlacement(true),
    )
    clusterCount.setFilter(clustered)
    style.addLayer(clusterCount)

    // Individual POI: colored circle, only when not part of a cluster.
    // Radius grows with zoom so isolated POIs at city/district zoom are
    // still readable. Below 11 we draw nothing - dense areas will be
    // showing cluster badges at those zooms anyway.
    val poiCircle = CircleLayer("pois-layer", "pois-source").withProperties(
        PropertyFactory.circleRadius(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(11, 6f),
                Expression.stop(13, 10f),
                Expression.stop(15, 13f),
                Expression.stop(17, 15f),
                Expression.stop(19, 17f),
            )
        ),
        PropertyFactory.circleStrokeWidth(2.5f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleColor(categoryColorExpr),
    )
    poiCircle.setFilter(unclustered)
    style.addLayer(poiCircle)

    val poiIcon = SymbolLayer("pois-icons-layer", "pois-source").withProperties(
        PropertyFactory.iconImage(
            Expression.concat(Expression.get("category"), Expression.literal("-icon"))
        ),
        // Icons sized to fit inside the circle above at each zoom step.
        PropertyFactory.iconSize(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(11, 0.12f),
                Expression.stop(13, 0.18f),
                Expression.stop(15, 0.24f),
                Expression.stop(17, 0.28f),
                Expression.stop(19, 0.32f),
            )
        ),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )
    poiIcon.setFilter(unclustered)
    style.addLayer(poiIcon)
}

private fun addRouteLayer(style: Style): GeoJsonSource {
    val source = GeoJsonSource("route-source")
    style.addSource(source)
    style.addLayerBelow(
        LineLayer("route-layer", "route-source").withProperties(
            PropertyFactory.lineColor("#1E88E5"),
            PropertyFactory.lineWidth(5.5f),
            PropertyFactory.lineOpacity(0.9f),
        ),
        "pois-layer",
    )
    return source
}

// Two stacked circles: a translucent halo so the dot stays visible over busy
// basemap colors, and the solid blue puck on top with a white ring (matches
// the standard Maps you-are-here treatment).
private fun addUserLocationLayer(style: Style): GeoJsonSource {
    val source = GeoJsonSource("user-location-source")
    style.addSource(source)
    style.addLayer(
        CircleLayer("user-location-halo", "user-location-source").withProperties(
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleRadius(18f),
        ),
    )
    style.addLayer(
        CircleLayer("user-location-dot", "user-location-source").withProperties(
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    return source
}

// Marker for the currently selected destination. Uses the same `category` icon
// expression as the POI layer so the visual matches a tapped pin, but renders
// from its own source - so destinations supplied via `initialDestination`
// (e.g. from the chat tool) are always visible, even when they aren't part of
// the bundled POIs.
private fun addSelectedDestinationLayer(style: Style): GeoJsonSource {
    val source = GeoJsonSource("selected-dest-source")
    style.addSource(source)
    style.addLayer(
        CircleLayer("selected-dest-halo", "selected-dest-source").withProperties(
            PropertyFactory.circleColor("#C0392B"),
            PropertyFactory.circleOpacity(0.22f),
            PropertyFactory.circleRadius(20f),
        ),
    )
    style.addLayer(
        CircleLayer("selected-dest-dot", "selected-dest-source").withProperties(
            PropertyFactory.circleColor("#C0392B"),
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        SymbolLayer("selected-dest-icon", "selected-dest-source").withProperties(
            PropertyFactory.iconImage(
                Expression.concat(Expression.get("category"), Expression.literal("-icon"))
            ),
            PropertyFactory.iconSize(0.22f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
    return source
}

// `BitmapFactory.decodeResource` returns null for vector drawables, so we
// rasterize via Drawable.draw() ourselves. Falls back to a 96px square when
// the drawable has no intrinsic size (raw shapes).
private fun drawableToBitmap(context: Context, resId: Int): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, resId) ?: return null
    if (drawable is BitmapDrawable) return drawable.bitmap
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(Canvas(bmp))
    return bmp
}

// --- GPS ---------------------------------------------------------------------

@SuppressLint("MissingPermission")
internal suspend fun getUserLocation(context: Context): LatLng? {
    val granted = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    return suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { loc ->
                cont.resume(loc?.let { LatLng(it.latitude, it.longitude) })
            }
            .addOnFailureListener { cont.resume(null) }
    }
}

// --- Offline basemap style ---------------------------------------------------

// MapLibre 10.x doesn't ship an mbtiles:// scheme handler, so the offline
// tile pack is served by [MbtilesServer] over loopback HTTP. The port is
// OS-assigned at runtime, so we read the bundled OpenMapTiles vector style
// from assets and patch the placeholder tile URL with the live server URL
// before handing it to MapLibre.
private const val STYLE_ASSET_PATH = "bundled/style.json"
private const val TILE_URL_PLACEHOLDER = "{TILE_URL_TEMPLATE}"

private fun buildOfflineStyle(context: Context, tileUrlTemplate: String): String {
    val template = context.assets.open(STYLE_ASSET_PATH).bufferedReader().use { it.readText() }
    return template.replace(TILE_URL_PLACEHOLDER, tileUrlTemplate)
}

// Background-only fallback used while the bundled skeleton mbtiles is
// missing (e.g. during dev iteration before scripts/build-pack/skeleton-build.sh
// has been run). MapLibre still needs *some* style to render the user-location
// dot, route polyline and POI overlays; this gives those layers a canvas
// without trying to load tiles from a non-existent server.
private const val FALLBACK_BACKGROUND_STYLE = """
{
  "version": 8,
  "sources": {},
  "layers": [
    {"id": "background", "type": "background", "paint": {"background-color": "#f3f1ec"}}
  ]
}
"""
