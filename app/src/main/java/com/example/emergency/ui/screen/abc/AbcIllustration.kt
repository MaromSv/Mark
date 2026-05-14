package com.example.emergency.ui.screen.abc

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.example.emergency.ui.state.AbcIllustrationKind
import com.example.emergency.ui.theme.EmergencyTheme

private const val SOURCE_W = 280f
private const val SOURCE_H = 170f

@Composable
fun AbcIllustration(kind: AbcIllustrationKind, modifier: Modifier = Modifier) {
    val colors = EmergencyTheme.colors
    val accent = EmergencyTheme.toolPalettes.abcCheck.fg

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(SOURCE_W / SOURCE_H),
    ) {
        when (kind) {
            AbcIllustrationKind.AIRWAY -> AirwayCanvas(colors.text, colors.textDim, accent)
            AbcIllustrationKind.BREATHING -> BreathingCanvas(colors.text, colors.textDim, accent)
            AbcIllustrationKind.CIRCULATION -> CirculationCanvas(colors.text, colors.textDim, accent)
        }
    }
}

@Composable
private fun AirwayCanvas(stroke: Color, dim: Color, accent: Color) {
    val transition = rememberInfiniteTransition(label = "airway")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val sx = size.width / SOURCE_W
        val sy = size.height / SOURCE_H

        val tilt = tiltCurve(phase)
        val angleDeg = -12f * tilt
        val handOffset = Offset(-3f * sx * tilt, -4f * sy * tilt)
        val chinOffset = Offset(2f * sx * tilt, -6f * sy * tilt)
        val airflowAlpha = airflowAlpha(phase)

        line(dim, 20f, 145f, 260f, 145f, sx, sy, width = 1f, dash = floatArrayOf(3f, 4f))

        rotate(degrees = angleDeg, pivot = Offset(110f * sx, 100f * sy)) {
            val head = Path().apply {
                moveTo(70f * sx, 110f * sy)
                quadraticTo(70f * sx, 60f * sy, 110f * sx, 60f * sy)
                quadraticTo(145f * sx, 60f * sy, 148f * sx, 92f * sy)
                lineTo(152f * sx, 102f * sy)
                quadraticTo(152f * sx, 108f * sy, 144f * sx, 108f * sy)
                lineTo(138f * sx, 108f * sy)
                quadraticTo(138f * sx, 122f * sy, 124f * sx, 122f * sy)
                lineTo(88f * sx, 122f * sy)
                quadraticTo(70f * sx, 122f * sy, 70f * sx, 110f * sy)
                close()
            }
            drawPath(head, color = stroke, style = strokeOf(1.6f))

            line(stroke, 130f, 112f, 142f, 110f, sx, sy)
            val nose = Path().apply {
                moveTo(148f * sx, 92f * sy)
                quadraticTo(156f * sx, 96f * sy, 152f * sx, 102f * sy)
            }
            drawPath(nose, color = stroke, style = strokeOf(1.6f))
            drawCircle(stroke, radius = 1.6f * sx, center = Offset(118f * sx, 88f * sy))

            val hairline = Path().apply {
                moveTo(78f * sx, 78f * sy)
                quadraticTo(90f * sx, 64f * sy, 110f * sx, 60f * sy)
            }
            drawPath(hairline, color = dim, style = strokeOf(1.2f))

            line(stroke, 90f, 122f, 90f, 145f, sx, sy)
            line(stroke, 124f, 122f, 124f, 145f, sx, sy)

            val airway = Path().apply {
                moveTo(130f * sx, 112f * sy)
                quadraticTo(110f * sx, 120f * sy, 100f * sx, 138f * sy)
            }
            drawPath(
                airway,
                color = accent.copy(alpha = airflowAlpha),
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * sx, 3f * sx), 0f),
                ),
            )
        }

        translate(handOffset.x, handOffset.y) {
            val hand = Path().apply {
                moveTo(60f * sx, 56f * sy)
                lineTo(95f * sx, 60f * sy)
                quadraticTo(105f * sx, 62f * sy, 102f * sx, 70f * sy)
                lineTo(88f * sx, 72f * sy)
            }
            drawPath(hand, color = accent, style = strokeOf(1.8f))
            line(accent, 88f, 72f, 80f, 76f, sx, sy, width = 1.8f)
            line(accent, 90f, 70f, 84f, 78f, sx, sy, width = 1.8f)
        }

        translate(chinOffset.x, chinOffset.y) {
            line(accent, 150f, 132f, 162f, 132f, sx, sy, width = 1.8f)
            line(accent, 150f, 138f, 162f, 138f, sx, sy, width = 1.8f)
            val chin = Path().apply {
                moveTo(162f * sx, 132f * sy)
                lineTo(175f * sx, 130f * sy)
                lineTo(180f * sx, 138f * sy)
            }
            drawPath(chin, color = accent, style = strokeOf(1.8f))
        }
    }
}

@Composable
private fun BreathingCanvas(stroke: Color, dim: Color, accent: Color) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val countdown by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10_000, easing = LinearEasing), RepeatMode.Restart),
        label = "countdown",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val sx = size.width / SOURCE_W
        val sy = size.height / SOURCE_H

        val rise = chestRise(phase)
        val arrowAlpha = breathArrowAlpha(phase)
        val arrowDy = (1f - rise) * 4f * sy

        line(dim, 20f, 145f, 260f, 145f, sx, sy, width = 1f, dash = floatArrayOf(3f, 4f))

        drawCircle(
            color = stroke,
            radius = 14f * sx,
            center = Offset(50f * sx, 100f * sy),
            style = strokeOf(1.6f),
        )
        line(stroke, 80f, 116f, 120f, 130f, sx, sy)
        line(stroke, 80f, 96f, 120f, 90f, sx, sy)
        line(stroke, 200f, 105f, 250f, 102f, sx, sy)
        line(stroke, 200f, 115f, 250f, 118f, sx, sy)

        val cy = 105f * sy
        val scaleY = 1f + 0.18f * rise
        fun yChest(y: Float): Float = cy + (y * sy - cy) * scaleY
        val chestPath = Path().apply {
            moveTo(64f * sx, yChest(95f))
            quadraticTo(80f * sx, yChest(88f), 110f * sx, yChest(88f))
            lineTo(200f * sx, yChest(92f))
            quadraticTo(218f * sx, yChest(92f), 218f * sx, yChest(100f))
            lineTo(218f * sx, yChest(112f))
            quadraticTo(218f * sx, yChest(118f), 200f * sx, yChest(118f))
            lineTo(110f * sx, yChest(118f))
            quadraticTo(80f * sx, yChest(118f), 64f * sx, yChest(110f))
            close()
        }
        drawPath(chestPath, color = stroke, style = strokeOf(1.6f))

        translate(top = arrowDy) {
            arrow(accent, 130f, sx, sy, alpha = arrowAlpha)
            arrow(accent, 150f, sx, sy, alpha = arrowAlpha)
            arrow(accent, 170f, sx, sy, alpha = arrowAlpha)
        }

        val timerCx = 238f * sx
        val timerCy = 32f * sy
        val rTimer = 18f * sx
        drawCircle(dim, radius = rTimer, center = Offset(timerCx, timerCy), style = strokeOf(1.4f))
        val sweepLen = (1f - countdown) * 360f
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = sweepLen,
            useCenter = false,
            topLeft = Offset(timerCx - rTimer, timerCy - rTimer),
            size = Size(rTimer * 2f, rTimer * 2f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun CirculationCanvas(stroke: Color, dim: Color, accent: Color) {
    val transition = rememberInfiniteTransition(label = "circulation")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(857, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val sx = size.width / SOURCE_W
        val sy = size.height / SOURCE_H

        val head = Path().apply {
            moveTo(70f * sx, 90f * sy)
            quadraticTo(70f * sx, 50f * sy, 110f * sx, 50f * sy)
            quadraticTo(145f * sx, 50f * sy, 148f * sx, 82f * sy)
            lineTo(152f * sx, 92f * sy)
            quadraticTo(152f * sx, 98f * sy, 144f * sx, 98f * sy)
            lineTo(138f * sx, 98f * sy)
            quadraticTo(138f * sx, 112f * sy, 124f * sx, 112f * sy)
            lineTo(100f * sx, 112f * sy)
        }
        drawPath(head, color = stroke, style = strokeOf(1.6f))
        drawCircle(stroke, radius = 1.6f * sx, center = Offset(118f * sx, 78f * sy))

        val hairline = Path().apply {
            moveTo(78f * sx, 68f * sy)
            quadraticTo(90f * sx, 54f * sy, 110f * sx, 50f * sy)
        }
        drawPath(hairline, color = dim, style = strokeOf(1.2f))

        line(stroke, 100f, 112f, 92f, 145f, sx, sy)
        line(stroke, 124f, 112f, 134f, 145f, sx, sy)

        val carotid = Path().apply {
            moveTo(108f * sx, 118f * sy)
            quadraticTo(112f * sx, 128f * sy, 110f * sx, 140f * sy)
        }
        drawPath(
            carotid,
            color = accent,
            style = Stroke(
                width = 1.4.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * sx, 3f * sx), 0f),
            ),
        )

        line(accent, 88f, 118f, 105f, 122f, sx, sy, width = 1.8f)
        line(accent, 86f, 124f, 105f, 128f, sx, sy, width = 1.8f)
        line(accent, 70f, 116f, 88f, 118f, sx, sy, width = 1.8f)
        line(accent, 70f, 122f, 86f, 124f, sx, sy, width = 1.8f)

        val pulseCenter = Offset(110f * sx, 125f * sy)
        val coreScale = pulseCoreScale(phase)
        val coreAlpha = pulseCoreAlpha(phase)
        drawCircle(
            color = accent.copy(alpha = coreAlpha),
            radius = 4f * sx * coreScale,
            center = pulseCenter,
        )
        val ringR = 4f * sx + (18f - 4f) * sx * phase
        val ringAlpha = (0.7f * (1f - phase)).coerceAtLeast(0f)
        drawCircle(
            color = accent.copy(alpha = ringAlpha),
            radius = ringR,
            center = pulseCenter,
            style = strokeOf(1.5f),
        )

        translate(left = 170f * sx, top = 70f * sy) {
            val ecg = Path().apply {
                moveTo(0f, 30f * sy)
                lineTo(18f * sx, 30f * sy)
                lineTo(22f * sx, 14f * sy)
                lineTo(28f * sx, 46f * sy)
                lineTo(34f * sx, 8f * sy)
                lineTo(40f * sx, 30f * sy)
                lineTo(90f * sx, 30f * sy)
            }
            drawPath(
                ecg,
                color = accent,
                style = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            val baseline = Path().apply {
                moveTo(0f, 60f * sy)
                lineTo(90f * sx, 60f * sy)
            }
            drawPath(
                baseline,
                color = dim,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * sx, 3f * sx), 0f),
                ),
            )
        }
    }
}

private fun DrawScope.arrow(color: Color, x: Float, sx: Float, sy: Float, alpha: Float) {
    val c = color.copy(alpha = alpha)
    val style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val shaft = Path().apply {
        moveTo(x * sx, 70f * sy)
        lineTo(x * sx, 50f * sy)
    }
    drawPath(shaft, color = c, style = style)
    val head = Path().apply {
        moveTo((x - 6f) * sx, 56f * sy)
        lineTo(x * sx, 50f * sy)
        lineTo((x + 6f) * sx, 56f * sy)
    }
    drawPath(head, color = c, style = style)
}

private fun DrawScope.line(
    color: Color,
    x1: Float, y1: Float, x2: Float, y2: Float,
    sx: Float, sy: Float,
    width: Float = 1.6f,
    dash: FloatArray? = null,
) {
    drawLine(
        color = color,
        start = Offset(x1 * sx, y1 * sy),
        end = Offset(x2 * sx, y2 * sy),
        strokeWidth = width.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = dash?.let { PathEffect.dashPathEffect(it.map { v -> v * sx }.toFloatArray(), 0f) },
    )
}

private fun DrawScope.strokeOf(width: Float): Stroke =
    Stroke(width = width.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

// 0..1 phase -> 0..1 tilt (0-35% rest, 45-65% peak, 85-100% rest)
private fun tiltCurve(p: Float): Float = when {
    p < 0.35f -> p / 0.35f * 0f
    p < 0.45f -> (p - 0.35f) / 0.10f
    p < 0.65f -> 1f
    p < 0.85f -> 1f - (p - 0.65f) / 0.20f
    else -> 0f
}

private fun airflowAlpha(p: Float): Float = when {
    p < 0.35f -> 0.15f
    p < 0.50f -> 0.15f + (p - 0.35f) / 0.15f * (1f - 0.15f)
    p < 0.65f -> 1f
    p < 0.85f -> 1f - (p - 0.65f) / 0.20f * (1f - 0.15f)
    else -> 0.15f
}

private fun chestRise(p: Float): Float = when {
    p < 0.40f -> p / 0.40f
    p < 0.60f -> 1f
    p < 1.00f -> 1f - (p - 0.60f) / 0.40f
    else -> 0f
}

private fun breathArrowAlpha(p: Float): Float = chestRise(p).coerceIn(0.15f, 1f)

private fun pulseCoreScale(p: Float): Float = when {
    p < 0.30f -> 1f + p / 0.30f * 0.6f
    p < 0.70f -> 1.6f - (p - 0.30f) / 0.40f * 0.6f
    else -> 1f
}

private fun pulseCoreAlpha(p: Float): Float = when {
    p < 0.30f -> 0.95f + p / 0.30f * 0.05f
    p < 0.70f -> 1f - (p - 0.30f) / 0.40f * 0.30f
    else -> 0.70f + (p - 0.70f) / 0.30f * 0.25f
}
