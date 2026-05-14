package com.example.emergency.ui.screen.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.emergency.offline.MbtilesServer
import com.example.emergency.offline.OfflineAssets
import com.example.emergency.offline.OfflineBootstrap
import com.example.emergency.offline.OfflineRouter
import com.example.emergency.offline.navigation.NavigationEngine
import com.example.emergency.offline.navigation.NavigationProfile
import com.example.emergency.offline.navigation.NavigationState
import com.example.emergency.offline.navigation.PolylineTurnSynthesizer
import com.example.emergency.offline.pack.RegionPack
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.offline.routing.StepFormatter
import com.example.emergency.offline.routing.TurnCommand
import com.example.emergency.offline.routing.TurnStep
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.WellKnownTileServer
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "NavigationScreen"

/**
 * Active navigation experience (plan section 8 step 7.5). Hosts a MapLibre view
 * with the route polyline + a snapping user-puck, plus overlays for the
 * maneuver banner, ETA, off-route banner, and a recenter button.
 *
 * TTS is intentionally not wired here - per the user's instructions on
 * 2026-05-02. The [com.example.emergency.offline.navigation.ManeuverScheduler]
 * still drives the visual banner cadence (e.g. swap from "In 200 m, ..."
 * to "Turn now") so the user experience is the Google-Maps shape, just
 * silent.
 *
 * GPS subscription uses FusedLocationProviderClient at PRIORITY_HIGH_ACCURACY,
 * 1-second updates. The screen owns the subscription lifecycle - entering
 * the screen kicks it off, leaving stops it cleanly.
 */
@Composable
fun NavigationScreen(
    initialRoute: OfflineRouter.Result,
    profile: NavigationProfile,
    destinationName: String? = null,
    destinationCategory: String? = null,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current

    remember {
        Mapbox.getInstance(context, null, WellKnownTileServer.MapLibre).also {
            Mapbox.setConnected(true)
        }
    }

    // The engine is per-screen - when the user backs out, the next
    // navigation session starts fresh. Re-keying on initialRoute means
    // a freshly-rerouted polyline kicks off a new engine if the caller
    // re-enters this composable with a different route.
    val engine = remember(initialRoute) {
        Log.d(
            TAG,
            "NavigationScreen: route polyline=${initialRoute.polyline.size} pts, " +
                "steps=${initialRoute.steps.size}, distance=${initialRoute.distanceM}m, " +
                "duration=${initialRoute.durationS}s, profile=$profile",
        )
        NavigationEngine(initialRoute, profile)
    }
    val state by engine.state.collectAsState()

    // Auto-start: jump straight from Preview -> Navigating when the screen
    // mounts. The Preview state was the route-card on the InteractiveMap;
    // by the time the user is here, they already saw it.
    LaunchedEffect(engine) { engine.start() }

    // Live GPS feed -> engine ticks. requestLocationUpdates is gated on
    // ACCESS_FINE_LOCATION which the host activity already requested at
    // first launch (see AppNavHost.locationPermissionLauncher).
    LaunchedEffect(engine, context) {
        var tickCount = 0
        locationFlow(context).collect { loc ->
            tickCount++
            // Log every tick for the first 5, then every 10th, so we can
            // confirm GPS is actually flowing without spamming logcat.
            if (tickCount <= 5 || tickCount % 10 == 0) {
                Log.d(TAG, "tick #$tickCount: ${loc.latitude},${loc.longitude} speed=${loc.speed}")
            }
            engine.tick(
                rawFix = LatLng(loc.latitude, loc.longitude),
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                gpsHeadingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            )
        }
    }

    // Offline tile + style plumbing - mirrors InteractiveMap so navigation
    // sees the same basemap the user picked their destination on. Prefer
    // an installed region pack (z0-14 detail); fall back to the bundled
    // skeleton (z0-6 worldwide) only if no pack is installed.
    val bootstrapStatus by OfflineBootstrap.state.collectAsState()
    val offlinePaths: OfflineAssets.Paths? =
        (bootstrapStatus as? OfflineBootstrap.Status.Ready)?.paths
    val regionStore = remember { RegionStore.get(context) }
    val installedPacks: List<RegionPack> by regionStore.state.collectAsState()
    val tileServer = remember(offlinePaths, installedPacks) {
        val packTiles = installedPacks
            .map { it.tilesFile }
            .firstOrNull { it.exists() }
        val mbtiles = packTiles
            ?: offlinePaths?.skeletonMbtiles?.takeIf { it.exists() }
        mbtiles?.let { MbtilesServer(it) }
    }
    DisposableEffect(tileServer) {
        val s = tileServer
        if (s != null) {
            try { s.start() } catch (e: Exception) { Log.e(TAG, "tile server failed", e) }
        }
        onDispose { s?.runCatching { stop() } }
    }

    // MapLibre lifecycle.
    val mapView = remember {
        val opts = MapboxMapOptions.createFromAttributes(context).textureMode(true)
        MapView(context, opts).apply {
            id = View.generateViewId()
            onCreate(null)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
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

    // Map sources - once style is ready we wire the route + user-puck.
    var mapboxMap by remember { mutableStateOf<MapboxMap?>(null) }
    var routeSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var userSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var userPanned by remember { mutableStateOf(false) }

    LaunchedEffect(mapView, tileServer) {
        mapView.getMapAsync { map ->
            mapboxMap = map
            // Match InteractiveMap: hide MapLibre logo/attribution so
            // the navigation canvas stays clean. OSM ODbL credit lives
            // on the Settings screen.
            map.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
            }
            map.cameraPosition = CameraPosition.Builder()
                .target(initialRoute.polyline.first())
                .zoom(15.0)
                .build()
            val styleJson = tileServer
                ?.let { buildVectorStyle(context, it.tileUrlTemplate) }
                ?: NAV_FALLBACK_STYLE
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                routeSource = addRouteLayer(style, initialRoute.polyline)
                userSource = addUserLayer(style)
                addDestinationLayer(
                    context = context,
                    style = style,
                    destination = initialRoute.polyline.last(),
                    name = destinationName,
                    category = destinationCategory,
                )
            }
            map.addOnCameraMoveStartedListener { reason ->
                // 1 = USER_GESTURE - signals we should show the recenter
                // affordance; programmatic camera moves keep follow-mode.
                if (reason == 1) userPanned = true
            }
        }
    }

    // Push the current snapped position into the user-source.
    LaunchedEffect(userSource, state) {
        val src = userSource ?: return@LaunchedEffect
        val nav = state as? NavigationState.Navigating
        val snapped = nav?.progress?.snappedPoint ?: initialRoute.polyline.first()
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(snapped.longitude, snapped.latitude)))
    }

    // Refresh the polyline if the engine swapped in a rerouted track.
    LaunchedEffect(routeSource, state) {
        val src = routeSource ?: return@LaunchedEffect
        val poly = state.route.polyline
        val pts = poly.map { Point.fromLngLat(it.longitude, it.latitude) }
        src.setGeoJson(LineString.fromLngLats(pts))
    }

    // Camera follow - applied each tick unless the user has panned.
    LaunchedEffect(mapboxMap, state, userPanned) {
        val map = mapboxMap ?: return@LaunchedEffect
        if (userPanned) return@LaunchedEffect
        val frame = (state as? NavigationState.Navigating)?.cameraFrame
            ?: (state as? NavigationState.Rerouting)?.cameraFrame
            ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(frame.target)
                    .zoom(frame.zoom)
                    .bearing(frame.bearing)
                    .tilt(frame.tilt)
                    .build(),
            ),
            300,
        )
    }

    // --- UI ----------------------------------------------------------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyTheme.colors.bg),
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Top: maneuver banner + off-route banner + dismiss button.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverBanner(state = state, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(EmergencyTheme.colors.surface)
                        .border(1.dp, EmergencyTheme.colors.line, CircleShape)
                        .size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "End navigation",
                        tint = EmergencyTheme.colors.text,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = state is NavigationState.Rerouting ||
                    (state as? NavigationState.Navigating)?.offRouteEvent != null ||
                    state is NavigationState.RerouteFailed,
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Spacer(Modifier.size(8.dp))
                OffRouteBanner(state = state)
            }
        }

        // Bottom-right: recenter button (visible only when user panned).
        AnimatedVisibility(
            visible = userPanned,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 110.dp),
        ) {
            RecenterButton(onClick = { userPanned = false })
        }

        // Bottom: ETA / arrived / preview card.
        EtaCard(
            state = state,
            destinationName = destinationName,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        )
    }
}

// --- Composables ------------------------------------------------------------

@Composable
private fun ManeuverBanner(state: NavigationState, modifier: Modifier = Modifier) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val nav = state as? NavigationState.Navigating
    val progress = nav?.progress
    val steps = state.route.steps
    // When the engine hasn't ticked yet (no GPS fix yet, fresh start, or
    // tests on emulator without location) progress is null. Fall back to
    // the route's first two steps so the banner still tells the user
    // "Turn left in X m / Then right" instead of an empty "Loading..."
    val currentIdx = progress?.currentStepIndex?.takeIf { it >= 0 }
    val currentStep = currentIdx?.let { steps.getOrNull(it) } ?: steps.firstOrNull()
    val thenStep = if (currentIdx != null) steps.getOrNull(currentIdx + 1) else steps.getOrNull(1)
    val distanceToCurrent = progress?.distanceToNextStepMeters
        ?: currentStep?.distanceToNextMeters
        ?: 0.0
    val arrived = state is NavigationState.Arrived
    // BRouter sometimes returns a route with no voice hints (single-segment
    // beelines, parser miss). Without this branch the banner sticks on
    // "Loading route..." forever even though the route + GPS are live.
    val noManeuvers = steps.isEmpty()
    val remainingForBanner = progress?.remainingMeters ?: state.route.distanceM
    // When BRouter gave us no voice hints, synthesise the next turn from
    // the polyline geometry so the user still sees "Turn left in 200 m"
    // instead of just the straight-line distance to the destination.
    val syntheticNext = if (noManeuvers && progress != null && !arrived) {
        PolylineTurnSynthesizer.nextTurn(
            polyline = state.route.polyline,
            snappedPoint = progress.snappedPoint,
            snappedSegmentIndex = progress.snappedSegmentIndex,
        )
    } else null
    val syntheticThen = syntheticNext?.let {
        PolylineTurnSynthesizer.turnAfter(state.route.polyline, it)
    }

    Column(
        modifier = modifier
            .clip(EmergencyShapes.hero)
            .background(colors.text)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Primary line: BIG icon + "Turn left in 200 m" + street name.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    arrived -> Icons.Filled.Flag
                    currentStep != null -> turnIcon(currentStep.command)
                    syntheticNext != null -> turnIcon(syntheticNext.command)
                    noManeuvers -> Icons.Filled.Straight
                    else -> Icons.Filled.Place
                },
                contentDescription = null,
                tint = colors.bg,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        arrived -> "You have arrived"
                        currentStep != null -> primaryManeuverLine(currentStep, distanceToCurrent)
                        syntheticNext != null -> primaryFromSynthetic(syntheticNext)
                        noManeuvers -> "Continue to destination - ${StepFormatter.formatDistance(remainingForBanner)}"
                        else -> "Loading route..."
                    },
                    style = typography.listItem.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.bg,
                    maxLines = 2,
                )
                if (!arrived && currentStep?.streetName?.isNotBlank() == true) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = "onto ${currentStep.streetName}",
                        style = typography.helper.copy(fontSize = 13.sp),
                        color = colors.bg.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }
        }

        // Secondary line: "Then right onto Spuistraat" — only when there's a turn after.
        // Pulls from BRouter steps when present, otherwise from the synthesized fallback.
        val thenSyntheticCommand = syntheticThen
            ?.takeIf { it.command !is TurnCommand.Continue && it.command !is TurnCommand.Arrive }
        val showThen = !arrived && (
            (thenStep != null && thenStep.command !is TurnCommand.Continue) ||
                thenSyntheticCommand != null
            )
        if (showThen) {
            Spacer(Modifier.size(10.dp))
            HorizontalDivider(color = colors.bg.copy(alpha = 0.18f), thickness = 1.dp)
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cmd = thenStep?.command ?: thenSyntheticCommand!!.command
                Icon(
                    imageVector = turnIcon(cmd),
                    contentDescription = null,
                    tint = colors.bg.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "Then " + (thenStep?.let { secondaryManeuverLine(it) }
                        ?: primaryVerb(cmd).replaceFirstChar { it.lowercase() }),
                    style = typography.helper.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.bg.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Same shape as [primaryManeuverLine] but for a synthesised turn (no
 * street name, command pulled from polyline geometry instead of BRouter
 * voice hints).
 */
private fun primaryFromSynthetic(turn: PolylineTurnSynthesizer.SyntheticTurn): String {
    if (turn.command == TurnCommand.Arrive) {
        return "Arrive in ${StepFormatter.formatDistance(turn.distanceMeters)}"
    }
    val verb = primaryVerb(turn.command)
    val distLabel = StepFormatter.formatDistance(turn.distanceMeters)
    return if (turn.distanceMeters < 25.0) "$verb now" else "$verb in $distLabel"
}

/** Primary banner phrasing: "Turn left in 200 m" / "Turn left now". */
private fun primaryManeuverLine(step: TurnStep, distanceM: Double): String {
    val verb = primaryVerb(step.command)
    if (step.command is TurnCommand.Arrive) return "You have arrived"
    val distLabel = StepFormatter.formatDistance(distanceM)
    return if (distanceM < 25.0) "$verb now" else "$verb in $distLabel"
}

/** Secondary line ("Then right"): just the verb, no distance. */
private fun secondaryManeuverLine(step: TurnStep): String {
    val verb = primaryVerb(step.command).replaceFirstChar { it.lowercase() }
    return if (!step.streetName.isNullOrBlank()) "$verb onto ${step.streetName}" else verb
}

private fun primaryVerb(command: TurnCommand): String = when (command) {
    TurnCommand.Continue            -> "Continue"
    TurnCommand.Straight            -> "Go straight"
    TurnCommand.TurnLeft            -> "Turn left"
    TurnCommand.TurnSlightLeft      -> "Bear left"
    TurnCommand.TurnSharpLeft       -> "Sharp left"
    TurnCommand.TurnRight           -> "Turn right"
    TurnCommand.TurnSlightRight     -> "Bear right"
    TurnCommand.TurnSharpRight      -> "Sharp right"
    TurnCommand.KeepLeft            -> "Keep left"
    TurnCommand.KeepRight           -> "Keep right"
    TurnCommand.UTurnLeft           -> "Make a U-turn"
    TurnCommand.UTurnRight          -> "Make a U-turn"
    TurnCommand.Beeline             -> "Follow the path"
    TurnCommand.Exit                -> "Take the exit"
    TurnCommand.Arrive              -> "Arrive"
    TurnCommand.OffRoute            -> "Return to route"
    is TurnCommand.Roundabout       -> "At the roundabout"
    is TurnCommand.Unknown          -> "Follow the road"
}

private fun turnIcon(command: TurnCommand): ImageVector = when (command) {
    TurnCommand.Continue, TurnCommand.Straight   -> Icons.Filled.Straight
    TurnCommand.TurnLeft                         -> Icons.Filled.TurnLeft
    TurnCommand.TurnSlightLeft                   -> Icons.Filled.TurnSlightLeft
    TurnCommand.TurnSharpLeft                    -> Icons.Filled.TurnSharpLeft
    TurnCommand.TurnRight                        -> Icons.Filled.TurnRight
    TurnCommand.TurnSlightRight                  -> Icons.Filled.TurnSlightRight
    TurnCommand.TurnSharpRight                   -> Icons.Filled.TurnSharpRight
    TurnCommand.KeepLeft                         -> Icons.Filled.ForkLeft
    TurnCommand.KeepRight                        -> Icons.Filled.ForkRight
    TurnCommand.UTurnLeft                        -> Icons.Filled.UTurnLeft
    TurnCommand.UTurnRight                       -> Icons.Filled.UTurnRight
    is TurnCommand.Roundabout                    -> if (command.leftHanded) Icons.Filled.RoundaboutLeft else Icons.Filled.RoundaboutRight
    TurnCommand.Exit                             -> Icons.Filled.TurnSlightRight
    TurnCommand.Arrive                           -> Icons.Filled.Flag
    TurnCommand.Beeline                          -> Icons.Filled.Straight
    TurnCommand.OffRoute                         -> Icons.Filled.Place
    is TurnCommand.Unknown                       -> Icons.Filled.Straight
}

@Composable
private fun OffRouteBanner(state: NavigationState) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val pair: Pair<String, Boolean>? = when (state) {
        is NavigationState.Rerouting -> "Off route - recalculating..." to false
        is NavigationState.RerouteFailed -> "Off route, can't recalculate: ${state.reason}" to true
        is NavigationState.Navigating -> state.offRouteEvent?.let {
            "Off route - ${it.deviationM.toInt()} m off" to false
        }
        else -> null
    }
    val (msg, dangerous) = pair ?: return
    val bg = if (dangerous) colors.dangerSoft else EmergencyTheme.semantic.noteWarningBg
    val ink = if (dangerous) colors.danger else EmergencyTheme.semantic.noteWarningInk
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = msg,
            style = typography.helper.copy(fontSize = 13.sp),
            color = ink,
        )
    }
}

@Composable
private fun EtaCard(
    state: NavigationState,
    destinationName: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val nav = state as? NavigationState.Navigating
    val rerouting = state as? NavigationState.Rerouting
    val arrived = state is NavigationState.Arrived
    val progress = nav?.progress ?: rerouting?.progress
    val total = state.route.distanceM
    // Distance to next turn - prefer BRouter's voice-hint distance when
    // we have one, otherwise synth from the polyline geometry. Same
    // logic the maneuver banner uses; computed here so the bottom card
    // can show "in 250 m" alongside the destination name without
    // duplicating the work.
    val nextTurnM: Double? = if (nav != null && progress != null && !arrived) {
        val steps = state.route.steps
        val idx = progress.currentStepIndex
        val brouterDist = if (idx >= 0 && idx <= steps.lastIndex) {
            progress.distanceToNextStepMeters
        } else null
        brouterDist ?: PolylineTurnSynthesizer.nextTurn(
            polyline = state.route.polyline,
            snappedPoint = progress.snappedPoint,
            snappedSegmentIndex = progress.snappedSegmentIndex,
        )?.distanceMeters
    } else null
    val remainingM = progress?.remainingMeters ?: total
    val remainingLabel = if (remainingM >= 1000) "%.1f km".format(remainingM / 1000)
        else "%.0f m".format(remainingM)
    val durationS = state.route.durationS
    val ratio = if (total > 0) (remainingM / total).coerceIn(0.0, 1.0) else 1.0
    val remainingMin = (durationS / 60.0 * ratio).toInt().coerceAtLeast(0)
    val timeLabel = when {
        remainingMin < 1 -> "<1 min"
        remainingMin < 60 -> "$remainingMin min"
        else -> "${remainingMin / 60} h ${remainingMin % 60} min"
    }
    // Re-anchor the arrival clock against wall-clock time every 15 s so
    // the ETA pushes forward when the user isn't moving (without this,
    // standing still keeps the clock frozen and the "arrive at 14:32"
    // pin drifts into the past).
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val etaClock = remember(remainingMin, nowMillis) {
        val arrival = Date(nowMillis + remainingMin * 60_000L)
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrival)
    }

    Box(
        modifier = modifier
            .clip(EmergencyShapes.hero)
            .background(colors.surface)
            .border(1.dp, colors.line, EmergencyShapes.hero)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (arrived) {
            Text(
                text = if (!destinationName.isNullOrBlank()) "Arrived at $destinationName" else "Arrived",
                style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        } else if (rerouting != null) {
            Text(
                text = "Recalculating... - $remainingLabel left",
                style = typography.helper.copy(fontSize = 13.sp),
                color = colors.textDim,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header line: "Elkerliek Ziekenhuis · Turn in 250 m"
                if (!destinationName.isNullOrBlank() || nextTurnM != null) {
                    val nameText = destinationName?.takeIf { it.isNotBlank() }
                    val turnText = nextTurnM?.let { "Turn in ${StepFormatter.formatDistance(it)}" }
                    val combined = listOfNotNull(nameText, turnText).joinToString("  -  ")
                    Text(
                        text = combined,
                        style = typography.helper.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = colors.text,
                        maxLines = 1,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EtaStat(big = etaClock, small = "arrive")
                    EtaDot()
                    EtaStat(big = timeLabel, small = "left")
                    EtaDot()
                    EtaStat(big = remainingLabel, small = "to go")
                }
            }
        }
    }
}

@Composable
private fun EtaStat(big: String, small: String) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = big,
            style = typography.listItem.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = colors.text,
        )
        Text(
            text = small,
            style = typography.helper.copy(fontSize = 11.sp),
            color = colors.textDim,
        )
    }
}

@Composable
private fun EtaDot() {
    Box(
        modifier = Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(EmergencyTheme.colors.line),
    )
}

@Composable
private fun RecenterButton(onClick: () -> Unit) {
    val colors = EmergencyTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.text)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MyLocation,
            contentDescription = "Recenter on me",
            tint = colors.bg,
            modifier = Modifier.size(20.dp),
        )
    }
}

// --- MapLibre helpers -------------------------------------------------------

private fun addRouteLayer(style: Style, polyline: List<LatLng>): GeoJsonSource {
    val src = GeoJsonSource("nav-route-source")
    style.addSource(src)
    val pts = polyline.map { Point.fromLngLat(it.longitude, it.latitude) }
    src.setGeoJson(LineString.fromLngLats(pts))
    style.addLayer(
        LineLayer("nav-route-layer", "nav-route-source").withProperties(
            PropertyFactory.lineColor("#1E88E5"),
            PropertyFactory.lineWidth(7.0f),
            PropertyFactory.lineOpacity(0.9f),
        ),
    )
    return src
}

// Red halo + dot at the route's last point, plus optional category icon
// inside the dot and a name label beneath. Icon is registered as an SDF
// image so it scales cleanly; the name label is rendered to a bitmap via
// Android Canvas (the bundled style.json ships without glyphs, so we
// can't use textField). Static (no source returned) - reroutes keep the
// same endpoint.
private fun addDestinationLayer(
    context: Context,
    style: Style,
    destination: LatLng,
    name: String?,
    category: String?,
) {
    val src = GeoJsonSource("nav-dest-source")
    val props = com.google.gson.JsonObject().apply {
        if (!category.isNullOrBlank()) addProperty("category", category)
        if (!name.isNullOrBlank()) addProperty("name", name)
    }
    src.setGeoJson(
        Feature.fromGeometry(
            Point.fromLngLat(destination.longitude, destination.latitude),
            props,
        ),
    )
    style.addSource(src)
    style.addLayer(
        CircleLayer("nav-dest-halo", "nav-dest-source").withProperties(
            PropertyFactory.circleColor("#C0392B"),
            PropertyFactory.circleOpacity(0.22f),
            PropertyFactory.circleRadius(22f),
        ),
    )
    style.addLayer(
        CircleLayer("nav-dest-dot", "nav-dest-source").withProperties(
            PropertyFactory.circleColor("#C0392B"),
            PropertyFactory.circleRadius(13f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )

    // Register the category icon as an SDF image so we can tint it white
    // (matches the look on InteractiveMap's POI layer).
    if (!category.isNullOrBlank()) {
        val resId = context.resources
            .getIdentifier("ic_poi_$category", "drawable", context.packageName)
        if (resId != 0) {
            val bmp = drawableToBitmap(context, resId)
            if (bmp != null) {
                runCatching { style.addImage("$category-icon", bmp, true) }
                style.addLayer(
                    SymbolLayer("nav-dest-icon", "nav-dest-source").withProperties(
                        PropertyFactory.iconImage("$category-icon"),
                        PropertyFactory.iconColor("#FFFFFF"),
                        PropertyFactory.iconSize(0.22f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
            }
        }
    }

    // Name label: render to a bitmap and use as iconImage on a separate
    // symbol layer offset below the dot. One-off so we just generate it
    // here; it never changes once nav has started.
    if (!name.isNullOrBlank()) {
        val density = context.resources.displayMetrics.density
        val labelBmp = makeNameLabel(name, density)
        val imageId = "nav-dest-label"
        runCatching { style.addImage(imageId, labelBmp) }
        style.addLayer(
            SymbolLayer("nav-dest-name", "nav-dest-source").withProperties(
                PropertyFactory.iconImage(imageId),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("top"),
                PropertyFactory.iconOffset(arrayOf(0f, 16f)),
            ),
        )
    }
}

/**
 * Renders [name] as dark-text-on-light-pill so it stays readable against
 * any basemap colour. Used as the destination name label image since the
 * bundled style ships without glyphs.
 */
private fun makeNameLabel(name: String, density: Float): android.graphics.Bitmap {
    val paddingPx = (8 * density)
    val textPx = (12 * density)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#111111")
        textAlign = android.graphics.Paint.Align.LEFT
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textSize = textPx
    }
    val trimmed = if (name.length > 36) name.take(34) + "..." else name
    val textWidth = paint.measureText(trimmed)
    val w = (textWidth + paddingPx * 2).toInt().coerceAtLeast(80)
    val h = (textPx + paddingPx * 2).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 255, 255, 255)
    }
    val radius = h / 2f
    canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, bgPaint)
    val baseline = h / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(trimmed, paddingPx, baseline, paint)
    return bmp
}

// Same rasterise-via-Canvas trick as InteractiveMap.drawableToBitmap;
// duplicated here so this file stays self-contained without exposing the
// helper across module boundaries.
private fun drawableToBitmap(context: Context, resId: Int): android.graphics.Bitmap? {
    val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId) ?: return null
    if (drawable is android.graphics.drawable.BitmapDrawable) return drawable.bitmap
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(android.graphics.Canvas(bmp))
    return bmp
}

private fun addUserLayer(style: Style): GeoJsonSource {
    val src = GeoJsonSource("nav-user-source")
    style.addSource(src)
    style.addLayer(
        CircleLayer("nav-user-halo", "nav-user-source").withProperties(
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleRadius(22f),
        ),
    )
    style.addLayer(
        CircleLayer("nav-user-dot", "nav-user-source").withProperties(
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3.0f),
        ),
    )
    return src
}

private const val STYLE_ASSET_PATH = "bundled/style.json"
private const val TILE_URL_PLACEHOLDER = "{TILE_URL_TEMPLATE}"

private fun buildVectorStyle(context: Context, tileUrlTemplate: String): String =
    context.assets.open(STYLE_ASSET_PATH).bufferedReader().use { it.readText() }
        .replace(TILE_URL_PLACEHOLDER, tileUrlTemplate)

private const val NAV_FALLBACK_STYLE = """
{
  "version": 8,
  "sources": {},
  "layers": [
    {"id": "background", "type": "background", "paint": {"background-color": "#f3f1ec"}}
  ]
}
"""

// --- GPS Flow ---------------------------------------------------------------

/**
 * High-accuracy 1-second location updates as a Flow. Honours the
 * existing ACCESS_FINE_LOCATION grant (host activity requested it on
 * first launch); silently completes if the permission is missing - the
 * banner / ETA stay on the initial values.
 */
@SuppressLint("MissingPermission")
private fun locationFlow(context: Context): Flow<Location> = callbackFlow {
    val granted = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        Log.w(TAG, "ACCESS_FINE_LOCATION not granted - nav will not tick")
        close()
        return@callbackFlow
    }
    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMinUpdateIntervalMillis(500L)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { trySend(it) }
        }
    }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    awaitClose { client.removeLocationUpdates(callback) }
}
