package com.example.emergency.ui.screen.map

import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.emergency.offline.MbtilesServer
import com.example.emergency.offline.OfflineAssets
import com.example.emergency.offline.OfflineBootstrap
import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.pack.CatalogProvider
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.offline.routing.RouteOutcome
import java.io.File
import com.mapbox.geojson.Feature
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
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource

private const val MINI_TAG = "LiveMiniMap"

/**
 * A small embedded MapLibre view for chat cards and the home preview. Renders
 * the PDOK basemap, a blue you-here dot, and (if [destination] is supplied) a
 * dashed walking route + a destination marker. All gestures are disabled - it
 * is meant to look like a live thumbnail, not an interactive surface.
 *
 * The route is fetched from BRouter (walking profile) the same way the full
 * [InteractiveMap] does it. The camera fits both endpoints when a destination
 * is set; otherwise it stays centered on the user's GPS fix at city zoom.
 */
@Composable
fun LiveMiniMap(
    modifier: Modifier = Modifier,
    destination: MapDestination? = null,
) {
    val context = LocalContext.current
    remember {
        Mapbox.getInstance(context, null, WellKnownTileServer.MapLibre).also {
            Mapbox.setConnected(true)
        }
    }

    var userLocation by remember { mutableStateOf(DAM_SQUARE) }
    var mapboxMap by remember { mutableStateOf<MapboxMap?>(null) }
    var routeSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var userSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var destSource by remember { mutableStateOf<GeoJsonSource?>(null) }

    // Observe offline bootstrap for tile server + routing paths
    val bootstrapStatus by OfflineBootstrap.state.collectAsState()
    val offlinePaths: OfflineAssets.Paths? =
        (bootstrapStatus as? OfflineBootstrap.Status.Ready)?.paths
    val regionStore = remember { RegionStore.get(context) }
    val catalogProvider = remember { CatalogProvider.get(context) }
    val installedPacks by regionStore.state.collectAsState()
    val catalog by catalogProvider.catalog.collectAsState()
    val activeRoot = remember { File(context.filesDir, "regions/_active") }

    // Same tile-source priority as InteractiveMap: installed pack first,
    // bundled skeleton as fallback. Re-key on installedPacks so the
    // mini-map updates the moment a pack download finishes elsewhere.
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
            try { server.start() } catch (e: Exception) {
                Log.e(MINI_TAG, "MBTiles server failed to start", e)
            }
        }
        onDispose { server?.runCatching { stop() } }
    }

    LaunchedEffect(Unit) {
        getUserLocation(context)?.let {
            userLocation = if (it.isInNL()) it else DAM_SQUARE
        }
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
        mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
                setAllGesturesEnabled(false)
            }
            map.cameraPosition = CameraPosition.Builder()
                .target(userLocation)
                .zoom(13.5)
                .build()
            val styleJson = tileServer
                ?.let { buildMiniOfflineStyle(context, it.tileUrlTemplate) }
                ?: MINI_FALLBACK_BACKGROUND_STYLE
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                try {
                    routeSource = addMiniRouteLayer(style)
                    destSource = addMiniDestinationLayer(style)
                    userSource = addMiniUserLocationLayer(style)
                } catch (e: Exception) {
                    Log.e(MINI_TAG, "Layer setup failed", e)
                }
            }
        }
    }

    LaunchedEffect(userSource, userLocation) {
        val src = userSource ?: return@LaunchedEffect
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(userLocation.longitude, userLocation.latitude)))
    }

    LaunchedEffect(destSource, destination) {
        val src = destSource ?: return@LaunchedEffect
        if (destination != null) {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
        } else {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(0.0, 0.0)))
        }
    }

    // Frame the camera around either the user+dest pair (route case) or the
    // user alone (home preview case).
    LaunchedEffect(mapboxMap, userLocation, destination) {
        val map = mapboxMap ?: return@LaunchedEffect
        val target = if (userLocation.isInNL()) userLocation else DAM_SQUARE
        val update = if (destination != null) {
            val dest = LatLng(destination.lat, destination.lon)
            val bounds = LatLngBounds.Builder().include(target).include(dest).build()
            CameraUpdateFactory.newLatLngBounds(bounds, 60)
        } else {
            CameraUpdateFactory.newLatLngZoom(target, 14.5)
        }
        map.animateCamera(update, 400)
    }

    // Pull a route from BRouter when we have a destination and draw it as a
    // dashed line. We use the walking profile because the chat card is a
    // preview, not a planning tool - the user can pick a different mode after
    // tapping through to the full map.
    LaunchedEffect(routeSource, userLocation, destination, offlinePaths, installedPacks, catalog) {
        val src = routeSource ?: return@LaunchedEffect
        val paths = offlinePaths ?: return@LaunchedEffect
        if (destination == null) {
            src.setGeoJson(LineString.fromLngLats(emptyList()))
            return@LaunchedEffect
        }
        // Mini-map silently hides the polyline on every non-Success outcome -
        // the chat bubble that hosts it has its own (terser) "tap to open
        // map for details" affordance, so we don't need to surface the
        // typed reason here.
        val outcome = OfflineRouter.route(
            from = userLocation,
            to = LatLng(destination.lat, destination.lon),
            profileName = "walk",
            profilesDir = paths.profilesDir,
            installedPacks = installedPacks,
            catalog = catalog.packs,
            activeRoot = activeRoot,
        )
        if (outcome is RouteOutcome.Success && outcome.result.polyline.size > 1) {
            val pts = outcome.result.polyline.map { pt -> Point.fromLngLat(pt.longitude, pt.latitude) }
            src.setGeoJson(LineString.fromLngLats(pts))
        } else {
            src.setGeoJson(LineString.fromLngLats(emptyList()))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}

private fun addMiniUserLocationLayer(style: Style): GeoJsonSource {
    val source = GeoJsonSource("mini-user-location-source")
    style.addSource(source)
    style.addLayer(
        CircleLayer("mini-user-location-halo", "mini-user-location-source").withProperties(
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleRadius(14f),
        ),
    )
    style.addLayer(
        CircleLayer("mini-user-location-dot", "mini-user-location-source").withProperties(
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleRadius(5.5f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    return source
}

private fun addMiniDestinationLayer(style: Style): GeoJsonSource {
    val source = GeoJsonSource("mini-dest-source")
    style.addSource(source)
    style.addLayer(
        CircleLayer("mini-dest-halo", "mini-dest-source").withProperties(
            PropertyFactory.circleColor("#C0392B"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleRadius(13f),
        ),
    )
    style.addLayer(
        CircleLayer("mini-dest-dot", "mini-dest-source").withProperties(
            PropertyFactory.circleColor("#C0392B"),
            PropertyFactory.circleRadius(5.5f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    return source
}

private fun addMiniRouteLayer(style: Style): GeoJsonSource {
    val source = GeoJsonSource("mini-route-source")
    style.addSource(source)
    style.addLayer(
        LineLayer("mini-route-layer", "mini-route-source").withProperties(
            PropertyFactory.lineColor("#1E88E5"),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    return source
}

// The mini-map shares the bundled OpenMapTiles vector style so it stays
// in sync with the full map (same colour palette, same road hierarchy).
// Placeholder substitution mirrors InteractiveMap.buildOfflineStyle.
private const val MINI_STYLE_ASSET_PATH = "bundled/style.json"
private const val MINI_TILE_URL_PLACEHOLDER = "{TILE_URL_TEMPLATE}"

private fun buildMiniOfflineStyle(context: android.content.Context, tileUrlTemplate: String): String {
    val template = context.assets.open(MINI_STYLE_ASSET_PATH).bufferedReader().use { it.readText() }
    return template.replace(MINI_TILE_URL_PLACEHOLDER, tileUrlTemplate)
}

// Same fallback as InteractiveMap - used when the bundled skeleton mbtiles
// hasn't been built yet so the route + dot still have a canvas to render on.
private const val MINI_FALLBACK_BACKGROUND_STYLE = """
{
  "version": 8,
  "sources": {},
  "layers": [
    {"id": "background", "type": "background", "paint": {"background-color": "#f3f1ec"}}
  ]
}
"""
