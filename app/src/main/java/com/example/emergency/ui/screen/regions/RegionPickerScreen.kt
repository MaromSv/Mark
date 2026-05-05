package com.example.emergency.ui.screen.regions

import android.content.Context
import android.util.Log
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emergency.offline.download.DownloadState
import com.example.emergency.offline.download.PackDownloader
import com.example.emergency.offline.pack.BoundingBox
import com.example.emergency.offline.pack.CatalogEntry
import com.example.emergency.offline.pack.CatalogProvider
import com.example.emergency.offline.pack.DensityGrid
import com.example.emergency.offline.pack.RegionPack
import com.example.emergency.offline.pack.RegionResolver
import com.example.emergency.offline.pack.RegionStore
import com.example.emergency.ui.screen.common.SubScreenTopBar
import com.example.emergency.ui.screen.map.getUserLocation
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme

/**
 * Region picker + storage manager (plan section 8 step 5). Single screen, four
 * tabs:
 *   * **Recommended** - top GPS-covering catalog entries via
 *     [RegionResolver].
 *   * **Browse** - flat list of every available pack from the catalog.
 *     The richer continent -> country -> state tree from the plan is
 *     deferred until we have more than ~four countries to show.
 *   * **Custom** - numeric W/S/E/N inputs + live size estimate from the
 *     bundled [DensityGrid]. The plan's draggable-rectangle map is heavy
 *     MapLibre work; the picker still hits the section 3 <= 800 MB cap and the
 *     +/-15 % size promise via the form.
 *   * **Storage** - installed packs with delete-with-confirm, total
 *     bytes used.
 *
 * State sources (process-wide singletons):
 *   * [CatalogProvider] - what's available to download.
 *   * [RegionStore]     - what's installed right now.
 *   * [PackDownloader]  - per-pack live progress.
 *
 * The picker doesn't own any persistent state of its own - everything
 * comes from those flows so background downloads stay live across
 * navigation.
 */
@Composable
fun RegionPickerScreen(
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val catalogProvider = remember { CatalogProvider.get(context) }
    val store = remember { RegionStore.get(context) }
    val downloader = remember { PackDownloader.get(context) }

    val catalog by catalogProvider.catalog.collectAsState()
    val installed by store.state.collectAsState()
    val downloads by downloader.state.collectAsState()
    val densityGrid = rememberDensityGrid(context)

    var userLat by remember { mutableStateOf<Double?>(null) }
    var userLon by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        getUserLocation(context)?.let {
            userLat = it.latitude
            userLon = it.longitude
        }
    }

    var selected by remember { mutableStateOf(Tab.Recommended) }
    val colors = EmergencyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        SubScreenTopBar(title = "Map regions", onBack = onBack)
        TabStrip(
            selected = selected,
            onSelect = { selected = it },
        )
        Box(modifier = Modifier.weight(1f)) {
            when (selected) {
                Tab.Recommended -> RecommendedTab(
                    catalog = catalog.packs,
                    installed = installed,
                    downloads = downloads,
                    userLat = userLat,
                    userLon = userLon,
                    onDownload = downloader::download,
                    onCancel = downloader::cancel,
                )
                Tab.Browse -> BrowseTab(
                    catalog = catalog.packs,
                    installed = installed,
                    downloads = downloads,
                    onDownload = downloader::download,
                    onCancel = downloader::cancel,
                )
                Tab.Custom -> CustomTab(
                    grid = densityGrid,
                    initialBbox = userLat?.let { lat ->
                        userLon?.let { lon -> bboxAround(lat, lon, halfDeg = 0.25) }
                    } ?: BoundingBox(4.6, 52.20, 5.10, 52.55),
                )
                Tab.Storage -> StorageTab(
                    installed = installed,
                    onDelete = downloader::delete,
                )
            }
        }
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

private enum class Tab(val label: String) {
    Recommended("Recommended"),
    Browse("Browse"),
    Custom("Custom"),
    Storage("Storage"),
}

@Composable
private fun TabStrip(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    val colors = EmergencyTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .clip(EmergencyShapes.full)
                    .background(if (active) colors.text else Color.Transparent)
                    .border(1.dp, if (active) colors.text else colors.line, EmergencyShapes.full)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = tab.label,
                    style = EmergencyTheme.typography.listItem.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (active) colors.bg else colors.text,
                )
            }
        }
    }
}

// --- Tabs --------------------------------------------------------------------

@Composable
private fun RecommendedTab(
    catalog: List<CatalogEntry>,
    installed: List<RegionPack>,
    downloads: Map<String, DownloadState>,
    userLat: Double?,
    userLon: Double?,
    onDownload: (CatalogEntry) -> Unit,
    onCancel: (String) -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    if (userLat == null || userLon == null) {
        EmptyState(
            icon = Icons.Outlined.LocationOn,
            title = "Waiting for GPS",
            body = "Grant location permission to get pack suggestions for where you are. " +
                "You can always switch to Browse and pick a region by hand.",
        )
        return
    }

    val recommended = RegionResolver.coveringPacks(
        catalog, userLat, userLon, maxResults = RegionResolver.DEFAULT_MAX_RESULTS,
    )
    if (recommended.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.LocationOn,
            title = "No packs cover your location",
            body = "We don't have a region pack for ${"%.3f".format(userLat)}, " +
                "${"%.3f".format(userLon)} yet. Try Browse to pick a nearby country, " +
                "or Custom to draw a bbox.",
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValuesAll(top = 12.dp, bottom = 24.dp, horizontal = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = "BEST MATCHES FOR ${"%.2f".format(userLat)}, ${"%.2f".format(userLon)}",
                style = typography.eyebrow,
                color = colors.textDim,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(recommended, key = { it.id }) { entry ->
            CatalogRow(
                entry = entry,
                installed = installed.any { it.id == entry.id },
                downloadState = downloads[entry.id] ?: DownloadState.Idle,
                onDownload = { onDownload(entry) },
                onCancel = { onCancel(entry.id) },
            )
        }
    }
}

@Composable
private fun BrowseTab(
    catalog: List<CatalogEntry>,
    installed: List<RegionPack>,
    downloads: Map<String, DownloadState>,
    onDownload: (CatalogEntry) -> Unit,
    onCancel: (String) -> Unit,
) {
    if (catalog.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.CloudDownload,
            title = "Catalog empty",
            body = "No packs are listed in bundled/catalog.json. Check the build pipeline output.",
        )
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValuesAll(top = 12.dp, bottom = 24.dp, horizontal = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(catalog, key = { it.id }) { entry ->
            CatalogRow(
                entry = entry,
                installed = installed.any { it.id == entry.id },
                downloadState = downloads[entry.id] ?: DownloadState.Idle,
                onDownload = { onDownload(entry) },
                onCancel = { onCancel(entry.id) },
            )
        }
    }
}

@Composable
private fun CustomTab(
    grid: DensityGrid?,
    initialBbox: BoundingBox,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    var west by remember { mutableStateOf(initialBbox.west.toString()) }
    var south by remember { mutableStateOf(initialBbox.south.toString()) }
    var east by remember { mutableStateOf(initialBbox.east.toString()) }
    var north by remember { mutableStateOf(initialBbox.north.toString()) }

    val parsed = runCatching {
        BoundingBox(
            west = west.toDouble(),
            south = south.toDouble(),
            east = east.toDouble(),
            north = north.toDouble(),
        )
    }.getOrNull()

    val sizeBytes = if (parsed != null && grid != null) grid.estimateBytes(parsed) else -1L
    val sizeMb = if (sizeBytes >= 0) sizeBytes / 1024 / 1024 else -1L
    val overSoftWarn = sizeMb > SOFT_WARN_MB
    val overHardCap = sizeMb > HARD_CAP_MB

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "DRAW A BOUNDING BOX",
            style = typography.eyebrow,
            color = colors.textDim,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Coordinates in WGS84 degrees. The on-device density grid " +
                "estimates pack size to +/-15 %% - tight urban bboxes can run larger.",
            style = typography.helper.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = colors.textDim,
        )
        Spacer(Modifier.height(12.dp))
        BboxField("West (lon)", west) { west = it }
        BboxField("South (lat)", south) { south = it }
        BboxField("East (lon)", east) { east = it }
        BboxField("North (lat)", north) { north = it }

        Spacer(Modifier.height(16.dp))
        SizeBanner(
            sizeMb = sizeMb,
            overSoftWarn = overSoftWarn,
            overHardCap = overHardCap,
            parseOk = parsed != null,
            gridReady = grid != null,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Custom packs are not yet downloadable - the build pipeline " +
                "publishes country/metro packs only. The estimator above is wired " +
                "so the picker UI hits its size-cap behaviour today; downloadable " +
                "custom bboxes land alongside the on-demand build worker (plan section 10).",
            style = typography.helper.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )
    }
}

@Composable
private fun StorageTab(
    installed: List<RegionPack>,
    onDelete: (String) -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    if (installed.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Check,
            title = "No packs installed",
            body = "Install a pack from Recommended or Browse and it'll show up here.",
        )
        return
    }

    val totalMb = installed.sumOf { it.sizeBytes } / 1024 / 1024
    var pendingDelete by remember { mutableStateOf<RegionPack?>(null) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValuesAll(top = 12.dp, bottom = 24.dp, horizontal = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = "USING $totalMb MB across ${installed.size} pack${if (installed.size == 1) "" else "s"}",
                style = typography.eyebrow,
                color = colors.textDim,
            )
        }
        items(installed, key = { it.id }) { pack ->
            InstalledRow(pack = pack, onDelete = { pendingDelete = pack })
        }
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = {
                Text(
                    "Removes ${p.sizeBytes / 1024 / 1024} MB from this device. " +
                        "Routing and labels in this region will fall back to the " +
                        "Tier-0 skeleton until you re-download.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(p.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

// --- Row composables ---------------------------------------------------------

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = typography.listItem.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = colors.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${entry.type.name.lowercase().replaceFirstChar { it.uppercase() }}  -  " +
                        "${entry.sizeBytes / 1024 / 1024} MB  -  v${entry.version}",
                    style = typography.helper.copy(fontSize = 12.sp),
                    color = colors.textDim,
                )
            }
            Spacer(Modifier.size(10.dp))
            ActionButton(
                installed = installed,
                state = downloadState,
                onDownload = onDownload,
                onCancel = onCancel,
            )
        }
        if (downloadState is DownloadState.Downloading && downloadState.bytesTotal > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (downloadState.bytesDone.toFloat() / downloadState.bytesTotal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.line,
            )
        } else if (downloadState is DownloadState.Verifying || downloadState is DownloadState.Installing) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.line,
            )
        } else if (downloadState is DownloadState.Failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Failed: ${downloadState.reason}",
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun ActionButton(
    installed: Boolean,
    state: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val semantic = EmergencyTheme.semantic

    val (label, onClick, bg, fg, indeterminate) = when {
        installed || state == DownloadState.Installed ->
            ButtonSpec("Installed", null, semantic.safeBg, semantic.safeInk, false)
        state is DownloadState.Downloading ->
            ButtonSpec(
                if (state.bytesTotal > 0)
                    "${(100L * state.bytesDone / state.bytesTotal).toInt()}%"
                else "...",
                onCancel, colors.surface, colors.text, false,
            )
        state is DownloadState.Verifying ->
            ButtonSpec("Verify", null, colors.surface, colors.text, true)
        state is DownloadState.Installing ->
            ButtonSpec("Install", null, colors.surface, colors.text, true)
        state is DownloadState.Queued ->
            ButtonSpec("Queued", onCancel, colors.surface, colors.text, false)
        state is DownloadState.Paused ->
            ButtonSpec("Resume", onDownload, colors.surface, colors.text, false)
        state is DownloadState.Failed ->
            ButtonSpec("Retry", onDownload, colors.surface, colors.danger, false)
        else ->
            ButtonSpec("Get", onDownload, colors.text, colors.bg, false)
    }

    Box(
        modifier = Modifier
            .clip(EmergencyShapes.full)
            .background(bg)
            .border(1.dp, colors.line, EmergencyShapes.full)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        if (indeterminate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = fg,
                )
                Spacer(Modifier.size(6.dp))
                Text(label, style = typography.helper.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = fg)
            }
        } else {
            Text(label, style = typography.helper.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = fg)
        }
    }
}

private data class ButtonSpec(
    val label: String,
    val onClick: (() -> Unit)?,
    val bg: Color,
    val fg: Color,
    val indeterminate: Boolean,
)

@Composable
private fun InstalledRow(pack: RegionPack, onDelete: () -> Unit) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pack.name,
                style = typography.listItem.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${pack.sizeBytes / 1024 / 1024} MB  -  installed ${shortDate(pack.installedAt)}",
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.textDim,
            )
        }
        Box(
            modifier = Modifier
                .clip(EmergencyShapes.full)
                .clickable(onClick = onDelete)
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = colors.danger,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SizeBanner(
    sizeMb: Long,
    overSoftWarn: Boolean,
    overHardCap: Boolean,
    parseOk: Boolean,
    gridReady: Boolean,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val semantic = EmergencyTheme.semantic

    val bg = when {
        overHardCap -> colors.dangerSoft
        overSoftWarn -> semantic.noteWarningBg
        else -> colors.panel
    }
    val ink = when {
        overHardCap -> colors.danger
        overSoftWarn -> semantic.noteWarningInk
        else -> colors.text
    }
    val message = when {
        !gridReady -> "Density grid not loaded - size estimate unavailable."
        !parseOk -> "Enter four numeric WGS84 coordinates to estimate size."
        overHardCap -> "~=$sizeMb MB - over the $HARD_CAP_MB MB cap. Trim the bbox to download."
        overSoftWarn -> "~=$sizeMb MB - large pack. Consider trimming below $SOFT_WARN_MB MB."
        sizeMb <= 0 -> "~=<1 MB - tiny bbox; estimator may underreport. Actual download may be larger."
        else -> "~=$sizeMb MB - fits the $HARD_CAP_MB MB cap. Estimate is +/-15 %%."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = message,
            style = typography.helper.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = ink,
        )
    }
}

@Composable
private fun BboxField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = typography.sectionTitle.copy(fontSize = 16.sp),
            color = colors.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = typography.helper.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )
    }
}

// --- Helpers -----------------------------------------------------------------

private const val SOFT_WARN_MB = 250L
private const val HARD_CAP_MB = 800L

@Composable
private fun rememberDensityGrid(context: Context): DensityGrid? {
    return remember {
        try {
            context.assets.open("bundled/density-grid.bin").use {
                DensityGrid.load(it)
            }
        } catch (e: Exception) {
            Log.e("RegionPicker", "density-grid.bin load failed", e)
            null
        }
    }
}

private fun bboxAround(lat: Double, lon: Double, halfDeg: Double): BoundingBox {
    val w = (lon - halfDeg).coerceIn(-180.0, 180.0)
    val e = (lon + halfDeg).coerceIn(-180.0, 180.0)
    val s = (lat - halfDeg).coerceIn(-90.0, 90.0)
    val n = (lat + halfDeg).coerceIn(-90.0, 90.0)
    // Make sure the box stays non-degenerate even if the user is right
    // at the antimeridian / pole.
    val safeW = if (w < e) w else e - 0.001
    val safeS = if (s < n) s else n - 0.001
    return BoundingBox(safeW, safeS, e, n)
}

private fun shortDate(epochMs: Long): String {
    val days = (System.currentTimeMillis() - epochMs) / (24L * 3600 * 1000)
    return when {
        days <= 0 -> "today"
        days == 1L -> "yesterday"
        days < 30 -> "$days days ago"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(epochMs))
    }
}

@Suppress("FunctionName")
private fun PaddingValuesAll(
    top: androidx.compose.ui.unit.Dp,
    bottom: androidx.compose.ui.unit.Dp,
    horizontal: androidx.compose.ui.unit.Dp,
) = androidx.compose.foundation.layout.PaddingValues(
    start = horizontal,
    end = horizontal,
    top = top,
    bottom = bottom,
)
