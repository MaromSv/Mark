package com.example.emergency.ui.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emergency.ui.screen.common.SubScreenTopBar
import com.example.emergency.ui.state.DataPack
import com.example.emergency.ui.state.DataPackStatus
import com.example.emergency.ui.state.DataPacksUiState
import com.example.emergency.ui.state.PackContent
import com.example.emergency.ui.theme.DefaultPackPalette
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme
import com.example.emergency.ui.theme.JetBrainsMonoFamily
import com.example.emergency.ui.theme.PackPalette
import com.example.emergency.ui.theme.PackPalettes

private enum class PackFilter { Browse, Installed }

@Composable
fun DataPacksScreen(
    state: DataPacksUiState,
    onBack: () -> Unit = {},
    onPackAction: (DataPack) -> Unit = {},
) {
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    val selected = selectedPackId?.let { id -> state.packs.firstOrNull { it.id == id } }

    if (selected != null) {
        DataPackDetail(
            pack = selected,
            onBack = { selectedPackId = null },
            onAction = { onPackAction(selected) },
        )
    } else {
        DataPacksList(
            state = state,
            onBack = onBack,
            onSelect = { pack -> selectedPackId = pack.id },
        )
    }
}

@Composable
private fun DataPacksList(
    state: DataPacksUiState,
    onBack: () -> Unit,
    onSelect: (DataPack) -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    var filter by remember { mutableStateOf(PackFilter.Browse) }

    val visible = when (filter) {
        PackFilter.Browse -> state.packs
        PackFilter.Installed -> state.packs.filter { it.status == DataPackStatus.INSTALLED }
    }
    val recommended = visible.firstOrNull { it.status == DataPackStatus.RECOMMENDED }
    val others = visible.filter { it.status != DataPackStatus.RECOMMENDED }

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
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = "More",
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        ) {
            FilterChip(label = "Browse", active = filter == PackFilter.Browse) {
                filter = PackFilter.Browse
            }
            FilterChip(label = "Installed", active = filter == PackFilter.Installed) {
                filter = PackFilter.Installed
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            if (recommended != null && filter == PackFilter.Browse) {
                RecommendedHero(pack = recommended, onClick = { onSelect(recommended) })
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = if (filter == PackFilter.Installed) "INSTALLED" else "MORE PACKS",
                style = typography.eyebrow,
                color = colors.textDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                others.forEach { pack ->
                    PackRow(pack = pack, onClick = { onSelect(pack) })
                }
            }
        }

        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val bg = if (active) colors.text else Color.Transparent
    val fg = if (active) colors.bg else colors.text
    val borderColor = if (active) colors.text else colors.line

    Box(
        modifier = Modifier
            .clip(EmergencyShapes.full)
            .background(bg)
            .border(1.dp, borderColor, EmergencyShapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = typography.listItem.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = fg,
        )
    }
}

@Composable
private fun RecommendedHero(
    pack: DataPack,
    onClick: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val ink = colors.bg
    val inkDim = colors.bg.copy(alpha = 0.7f)
    val inkFaint = colors.bg.copy(alpha = 0.6f)
    val badgeText = (pack.badge ?: "Recommended for today").uppercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.text)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Text(
            text = badgeText,
            style = typography.monoMicro.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = inkFaint,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = pack.name,
            style = typography.greeting.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
            color = ink,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = listOfNotNull(pack.issuer, pack.whenLabel).joinToString(" \u00B7 "),
            style = typography.helper.copy(fontSize = 13.sp),
            color = inkDim,
        )
        if (!pack.summary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = pack.summary,
                style = typography.body.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = ink.copy(alpha = 0.85f),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(EmergencyShapes.full)
                    .background(ink)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "Get for ${pack.priceLabel ?: "Free"}",
                    style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.text,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = pack.sizeLabel,
                style = typography.monoMicro.copy(fontSize = 12.sp),
                color = inkFaint,
            )
        }
    }
}

@Composable
private fun PackRow(
    pack: DataPack,
    onClick: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val palette = pack.paletteId?.let { PackPalettes[it] } ?: DefaultPackPalette

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        PackThumb(palette = palette, size = 52)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pack.name,
                style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                pack.issuer?.let {
                    Text(
                        text = it,
                        style = typography.helper.copy(fontSize = 12.sp),
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    DotSeparator()
                }
                Text(
                    text = pack.sizeLabel,
                    style = typography.monoMicro.copy(fontSize = 12.sp),
                    color = colors.textDim,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        PackStatusPill(status = pack.status, priceLabel = pack.priceLabel)
    }
}

@Composable
private fun PackStatusPill(status: DataPackStatus, priceLabel: String?) {
    val colors = EmergencyTheme.colors
    val semantic = EmergencyTheme.semantic
    val typography = EmergencyTheme.typography

    when (status) {
        DataPackStatus.INSTALLED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(EmergencyShapes.full)
                    .background(semantic.safeBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = semantic.safeInk,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "Installed",
                    style = typography.helper.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = semantic.safeInk,
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .clip(EmergencyShapes.full)
                    .background(colors.surface)
                    .border(1.dp, colors.line, EmergencyShapes.full)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = priceLabel ?: "Free",
                    style = typography.helper.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = colors.text,
                )
            }
        }
    }
}

@Composable
private fun PackThumb(palette: PackPalette, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.light),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            drawCircle(
                color = palette.dark.copy(alpha = 0.7f),
                radius = w * 0.42f,
                center = Offset(w * 0.7f, h * 0.27f),
            )
            val curve = Path().apply {
                moveTo(0f, h * 0.74f)
                quadraticTo(w * 0.27f, h * 0.66f, w * 0.5f, h * 0.74f)
                quadraticTo(w * 0.78f, h * 0.82f, w, h * 0.69f)
            }
            drawPath(
                path = curve,
                color = palette.dark.copy(alpha = 0.5f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun DotSeparator() {
    val colors = EmergencyTheme.colors
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .size(2.dp)
            .background(colors.textFaint, EmergencyShapes.full),
    )
}

@Composable
private fun DataPackDetail(
    pack: DataPack,
    onBack: () -> Unit,
    onAction: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val palette = pack.paletteId?.let { PackPalettes[it] } ?: DefaultPackPalette
    val isInstalled = pack.status == DataPackStatus.INSTALLED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        SubScreenTopBar(
            title = "Data pack",
            onBack = onBack,
            trailing = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Outlined.IosShare,
                        contentDescription = "Share",
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                PackThumb(palette = palette, size = 72)
                Spacer(modifier = Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.name,
                        style = typography.greeting.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
                        color = colors.text,
                    )
                    pack.issuer?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = typography.helper.copy(fontSize = 13.sp),
                            color = colors.textDim,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            MetaStrip(pack = pack)

            if (!pack.summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = pack.summary,
                    style = typography.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
                    color = colors.text,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            if (pack.contents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "WHAT'S INCLUDED",
                    style = typography.eyebrow,
                    color = colors.textDim,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                ContentGrid(contents = pack.contents)
            }

            Spacer(modifier = Modifier.height(18.dp))
            TrustStrip(issuer = pack.issuer)
        }

        BottomCta(pack = pack, isInstalled = isInstalled, onClick = onAction)
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun MetaStrip(pack: DataPack) {
    val colors = EmergencyTheme.colors

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            MetaItem(label = "WHEN", value = pack.whenLabel ?: "-")
            MetaItem(label = "SIZE", value = pack.sizeLabel)
            MetaItem(label = "PRICE", value = pack.priceLabel ?: "Free")
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
    }
}

@Composable
private fun MetaItem(label: String, value: String) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Column {
        Text(
            text = label,
            style = typography.monoMicro.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = colors.textFaint,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = typography.listItem.copy(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
        )
    }
}

@Composable
private fun ContentGrid(contents: List<PackContent>) {
    val rows = contents.chunked(2)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { entry ->
                    Box(modifier = Modifier.weight(1f)) {
                        ContentChip(entry = entry)
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ContentChip(entry: PackContent) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = entry.count,
            style = typography.listItem.copy(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = entry.label,
            style = typography.helper.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = colors.textDim,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrustStrip(issuer: String?) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val name = issuer ?: "the issuer"

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            tint = colors.textDim,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Medium)) {
                    append("Verified by $name. ")
                }
                append("Cryptographically signed. Stays on your device - never sent anywhere.")
            },
            style = typography.helper.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )
    }
}

@Composable
private fun BottomCta(pack: DataPack, isInstalled: Boolean, onClick: () -> Unit) {
    val colors = EmergencyTheme.colors
    val semantic = EmergencyTheme.semantic
    val typography = EmergencyTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(if (isInstalled) colors.panel else colors.text)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = semantic.safeInk,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Installed \u00B7 ready offline",
                        style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = colors.text,
                    )
                }
            } else {
                val price = pack.priceLabel ?: "Free"
                val label = if (price == "Free") "Get \u00B7 ${pack.sizeLabel}" else "Buy $price \u00B7 ${pack.sizeLabel}"
                Text(
                    text = label,
                    style = typography.listItem.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = colors.bg,
                )
            }
        }
    }
}

