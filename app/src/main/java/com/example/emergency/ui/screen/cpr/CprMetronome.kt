package com.example.emergency.ui.screen.cpr

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val BPM = 110
// 60_000 ms / 110 bpm ~= 545 ms per beat
private const val BEAT_MS = 545L

@Composable
fun CprMetronome(modifier: Modifier = Modifier) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    var isRunning by remember { mutableStateOf(false) }
    var compressions by remember { mutableStateOf(0) }
    var cycles by remember { mutableStateOf(0) }

    // Audible click on every beat
    val toneGenerator = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose { toneGenerator?.release() }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (isActive) {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            delay(BEAT_MS)
            val next = compressions + 1
            if (next >= 30) {
                compressions = 0
                cycles += 1
            } else {
                compressions = next
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Metronome \u2014 110 bpm",
            style = typography.eyebrow,
            color = colors.textDim,
        )

        PulseRing(
            isRunning = isRunning,
            compressions = compressions,
        )

        CounterRow(
            compressions = compressions,
            cycles = cycles,
        )

        DepthIndicator(isRunning = isRunning)

        StartPauseButton(
            isRunning = isRunning,
            onToggle = {
                if (isRunning) {
                    isRunning = false
                } else {
                    compressions = 0
                    cycles = 0
                    isRunning = true
                }
            },
        )
    }
}

@Composable
private fun PulseRing(
    isRunning: Boolean,
    compressions: Int,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    val transition = rememberInfiniteTransition(label = "cpr-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BEAT_MS.toInt()),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cpr-pulse-progress",
    )

    val effectivePulse = if (isRunning) pulse else 0f
    val haloScale = 0.55f + effectivePulse * 0.45f
    val haloAlpha = if (isRunning) (1f - effectivePulse).coerceAtLeast(0f) * 0.85f else 0f
    val centerScale = if (isRunning) 0.92f + (1f - effectivePulse) * 0.08f else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(150.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                color = colors.line,
                radius = radius - 1f,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = colors.dangerSoft.copy(alpha = haloAlpha),
                radius = radius * haloScale,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size((64f * centerScale).dp)
                .clip(CircleShape)
                .background(colors.danger),
        ) {
            Text(
                text = compressions.toString().padStart(2, '0'),
                style = typography.monoMicro.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.accentInk,
            )
        }
    }
}

@Composable
private fun CounterRow(
    compressions: Int,
    cycles: Int,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CounterCell(
            value = compressions.toString().padStart(2, '0'),
            suffix = "/30",
            label = "compressions",
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(colors.line),
        )
        CounterCell(
            value = BPM.toString(),
            suffix = null,
            label = "bpm target",
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(colors.line),
        )
        CounterCell(
            value = cycles.toString(),
            suffix = null,
            label = "cycles",
        )
    }
}

@Composable
private fun CounterCell(
    value: String,
    suffix: String?,
    label: String,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = typography.monoMicro.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.text,
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    style = typography.monoMicro.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.textFaint,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            style = typography.eyebrow.copy(fontSize = 9.sp, letterSpacing = 0.7.sp),
            color = colors.textDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DepthIndicator(isRunning: Boolean) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val semantic = EmergencyTheme.semantic

    val transition = rememberInfiniteTransition(label = "cpr-depth")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BEAT_MS.toInt()),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cpr-depth-bob",
    )

    Column(
        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0 cm",
                style = typography.monoMicro.copy(fontSize = 10.sp),
                color = colors.textDim,
            )
            Text(
                text = "5\u20136 cm target",
                style = typography.monoMicro.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
            )
            Text(
                text = "8 cm",
                style = typography.monoMicro.copy(fontSize = 10.sp),
                color = colors.textDim,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.panel2),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val bandStart = w * 0.625f
                val bandWidth = w * 0.125f
                drawRoundRect(
                    color = semantic.statusOk,
                    topLeft = androidx.compose.ui.geometry.Offset(bandStart, 0f),
                    size = androidx.compose.ui.geometry.Size(bandWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                )
                val markerCenter = w * 0.70f
                val markerRadius = 7.dp.toPx()
                val yOffset = if (isRunning) (bob - 0.5f) * 4.dp.toPx() else 0f
                drawCircle(
                    color = colors.surface,
                    radius = markerRadius,
                    center = androidx.compose.ui.geometry.Offset(markerCenter, h / 2f + yOffset),
                )
                drawCircle(
                    color = colors.text,
                    radius = markerRadius - 2.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(markerCenter, h / 2f + yOffset),
                )
            }
        }
    }
}

@Composable
private fun StartPauseButton(
    isRunning: Boolean,
    onToggle: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val bg = if (isRunning) colors.panel else colors.accent
    val ink = if (isRunning) colors.text else colors.accentInk
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .height(52.dp)
            .clip(EmergencyShapes.full)
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp),
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (isRunning) "Pause metronome" else "Start metronome",
            style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = ink,
        )
    }
}
