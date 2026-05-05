package com.example.emergency.ui.screen.cpr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.emergency.ui.state.CprIllustrationKind
import com.example.emergency.ui.theme.EmergencyTheme

private const val SOURCE_W = 280f
private const val SOURCE_H = 170f

@Composable
fun CprIllustration(kind: CprIllustrationKind, modifier: Modifier = Modifier) {
    val colors = EmergencyTheme.colors
    val strokePrimary = colors.text
    val strokeDim = colors.textDim
    val accent = colors.danger

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(SOURCE_W / SOURCE_H),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = size.width / SOURCE_W
            val sy = size.height / SOURCE_H
            when (kind) {
                CprIllustrationKind.SAFETY -> drawSafety(strokePrimary, strokeDim, accent, sx, sy)
                CprIllustrationKind.RESPONSE -> drawResponse(strokePrimary, strokeDim, sx, sy)
                CprIllustrationKind.CALL -> drawCall(strokePrimary, strokeDim, accent, sx, sy)
                CprIllustrationKind.HANDS -> drawHands(strokePrimary, strokeDim, accent, sx, sy)
                CprIllustrationKind.RECOVERY -> drawRecovery(strokePrimary, strokeDim, sx, sy)
                CprIllustrationKind.METRONOME -> Unit
            }
        }
    }
}

private fun DrawScope.strokeOf(width: Float): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.dashedStroke(width: Float, on: Float, off: Float): Stroke =
    Stroke(
        width = width,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off), 0f),
    )

private fun DrawScope.line(
    color: Color,
    x1: Float, y1: Float, x2: Float, y2: Float,
    sx: Float, sy: Float,
    width: Float = 1.6f,
) {
    drawLine(
        color = color,
        start = Offset(x1 * sx, y1 * sy),
        end = Offset(x2 * sx, y2 * sy),
        strokeWidth = width.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.dashedLine(
    color: Color,
    x1: Float, y1: Float, x2: Float, y2: Float,
    sx: Float, sy: Float,
    width: Float = 1f,
) {
    drawLine(
        color = color,
        start = Offset(x1 * sx, y1 * sy),
        end = Offset(x2 * sx, y2 * sy),
        strokeWidth = width.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f.dp.toPx(), 4f.dp.toPx()), 0f),
    )
}

private fun DrawScope.circleStroke(
    color: Color,
    cx: Float, cy: Float, r: Float,
    sx: Float, sy: Float,
    width: Float = 1.6f,
) {
    drawCircle(
        color = color,
        center = Offset(cx * sx, cy * sy),
        radius = r * minOf(sx, sy),
        style = strokeOf(width.dp.toPx()),
    )
}

private fun DrawScope.circleFill(
    color: Color,
    cx: Float, cy: Float, r: Float,
    sx: Float, sy: Float,
) {
    drawCircle(
        color = color,
        center = Offset(cx * sx, cy * sy),
        radius = r * minOf(sx, sy),
    )
}

private fun pathFromPoints(
    points: List<Pair<Float, Float>>,
    sx: Float, sy: Float,
    close: Boolean = false,
): Path = Path().apply {
    points.forEachIndexed { i, (x, y) ->
        if (i == 0) moveTo(x * sx, y * sy) else lineTo(x * sx, y * sy)
    }
    if (close) close()
}

private fun DrawScope.drawSafety(
    stroke: Color,
    dim: Color,
    accent: Color,
    sx: Float,
    sy: Float,
) {
    dashedLine(dim, 20f, 130f, 260f, 130f, sx, sy)

    // standing rescuer
    circleStroke(stroke, 60f, 60f, 9f, sx, sy)
    line(stroke, 60f, 69f, 60f, 100f, sx, sy)
    line(stroke, 60f, 78f, 48f, 92f, sx, sy)
    line(stroke, 60f, 78f, 72f, 92f, sx, sy)
    line(stroke, 60f, 100f, 52f, 130f, sx, sy)
    line(stroke, 60f, 100f, 68f, 130f, sx, sy)

    // victim lying down
    circleStroke(stroke, 170f, 120f, 8f, sx, sy)
    line(stroke, 178f, 120f, 230f, 124f, sx, sy)
    line(stroke, 195f, 122f, 200f, 110f, sx, sy)
    line(stroke, 215f, 124f, 220f, 112f, sx, sy)
    line(stroke, 195f, 124f, 200f, 138f, sx, sy)
    line(stroke, 215f, 126f, 220f, 138f, sx, sy)

    // scan arc
    val arcSize = Size(120f * sx, 120f * sy)
    drawArc(
        color = dim,
        startAngle = 180f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(30f * sx, -10f * sy),
        size = arcSize,
        style = dashedStroke(1.2f.dp.toPx(), 2f.dp.toPx(), 3f.dp.toPx()),
    )

    // hazard triangle (translated to 130, 30)
    translate(left = 130f * sx, top = 30f * sy) {
        val tri = pathFromPoints(
            listOf(12f to 0f, 24f to 22f, 0f to 22f),
            sx, sy, close = true,
        )
        drawPath(tri, accent, style = strokeOf(1.5f.dp.toPx()))
        line(accent, 12f, 8f, 12f, 15f, sx, sy, width = 1.5f)
        circleFill(accent, 12f, 18.5f, 0.9f, sx, sy)
    }
}

private fun DrawScope.drawResponse(
    stroke: Color,
    dim: Color,
    sx: Float,
    sy: Float,
) {
    // victim
    circleStroke(stroke, 80f, 100f, 14f, sx, sy)
    line(stroke, 94f, 100f, 220f, 105f, sx, sy)
    line(stroke, 130f, 105f, 135f, 80f, sx, sy)
    line(stroke, 165f, 105f, 170f, 80f, sx, sy)
    line(stroke, 200f, 105f, 215f, 75f, sx, sy)
    line(stroke, 215f, 105f, 230f, 80f, sx, sy)

    // hand tapping shoulder (curved fingers)
    val hand = Path().apply {
        moveTo(110f * sx, 60f * sy)
        quadraticBezierTo(120f * sx, 70f * sy, 130f * sx, 78f * sy)
        moveTo(130f * sx, 78f * sy)
        quadraticBezierTo(140f * sx, 72f * sy, 142f * sx, 64f * sy)
        moveTo(130f * sx, 78f * sy)
        quadraticBezierTo(138f * sx, 80f * sy, 142f * sx, 76f * sy)
        moveTo(130f * sx, 78f * sy)
        quadraticBezierTo(136f * sx, 86f * sy, 140f * sx, 86f * sy)
    }
    drawPath(hand, stroke, style = strokeOf(1.6f.dp.toPx()))

    // shout waves
    val waves = Path().apply {
        moveTo(50f * sx, 80f * sy)
        quadraticBezierTo(35f * sx, 90f * sy, 50f * sx, 105f * sy)
        moveTo(42f * sx, 76f * sy)
        quadraticBezierTo(22f * sx, 90f * sy, 42f * sx, 110f * sy)
    }
    drawPath(waves, dim, style = strokeOf(1.2f.dp.toPx()))

    // impact dots
    circleFill(stroke, 115f, 90f, 2f, sx, sy)
    drawCircle(
        color = stroke.copy(alpha = 0.5f),
        center = Offset(105f * sx, 86f * sy),
        radius = 1.5f * minOf(sx, sy),
    )

    drawCanvasText("\"Are you okay?\"", 20f * sx, 40f * sy, 11f, stroke, bold = false)
}

private fun DrawScope.drawCall(
    stroke: Color,
    dim: Color,
    accent: Color,
    sx: Float,
    sy: Float,
) {
    // phone outer body
    drawRoundedRectStroke(stroke, 60f, 30f, 50f, 100f, 8f, sx, sy, 1.6f)
    // screen inset
    drawRoundedRectStroke(dim, 65f, 40f, 40f, 70f, 2f, sx, sy, 1f)
    // home button
    circleStroke(dim, 85f, 120f, 3f, sx, sy, width = 1f)
    // 112 label
    drawCanvasText("112", 85f * sx, 76f * sy, 20f, accent, bold = true, centered = true)

    // speaker waves
    val waves = Path().apply {
        moveTo(130f * sx, 60f * sy)
        quadraticBezierTo(145f * sx, 80f * sy, 130f * sx, 100f * sy)
        moveTo(145f * sx, 50f * sy)
        quadraticBezierTo(170f * sx, 80f * sy, 145f * sx, 110f * sy)
        moveTo(160f * sx, 40f * sy)
        quadraticBezierTo(195f * sx, 80f * sy, 160f * sx, 120f * sy)
    }
    drawPath(waves, accent, style = strokeOf(1.4f.dp.toPx()))

    // AED block
    translate(left = 210f * sx, top = 50f * sy) {
        drawRoundedRectStroke(stroke, 0f, 0f, 50f, 60f, 4f, sx, sy, 1.6f)
        // ECG zigzag
        val ecg = pathFromPoints(
            listOf(16f to 30f, 22f to 22f, 28f to 38f, 34f to 30f),
            sx, sy,
        )
        drawPath(ecg, accent, style = strokeOf(1.6f.dp.toPx()))
        // bottom slot
        drawLine(
            color = dim,
            start = Offset(8f * sx, 50f * sy),
            end = Offset(42f * sx, 50f * sy),
            strokeWidth = 1f.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCanvasText("AED", 25f * sx, 74f * sy, 9f, dim, bold = false, centered = true)
    }
}

private fun DrawScope.drawHands(
    stroke: Color,
    dim: Color,
    accent: Color,
    sx: Float,
    sy: Float,
) {
    // head
    circleStroke(stroke, 140f, 32f, 14f, sx, sy)

    // torso (from above)
    val torso = Path().apply {
        moveTo(100f * sx, 56f * sy)
        quadraticBezierTo(100f * sx, 50f * sy, 110f * sx, 50f * sy)
        lineTo(170f * sx, 50f * sy)
        quadraticBezierTo(180f * sx, 50f * sy, 180f * sx, 56f * sy)
        lineTo(188f * sx, 145f * sy)
        quadraticBezierTo(188f * sx, 152f * sy, 180f * sx, 152f * sy)
        lineTo(100f * sx, 152f * sy)
        quadraticBezierTo(92f * sx, 152f * sy, 92f * sx, 145f * sy)
        close()
    }
    drawPath(torso, stroke, style = strokeOf(1.6f.dp.toPx()))

    // sternum + nipple guide lines
    dashedLine(dim, 140f, 56f, 140f, 145f, sx, sy)
    dashedLine(dim, 105f, 92f, 175f, 92f, sx, sy)

    // hand position circle (heel of hand)
    val center = Offset(140f * sx, 100f * sy)
    val outerRx = 18f * sx
    val outerRy = 22f * sy
    drawOval(
        color = accent.copy(alpha = 0.18f),
        topLeft = Offset(center.x - outerRx, center.y - outerRy),
        size = Size(outerRx * 2, outerRy * 2),
    )
    drawOval(
        color = accent,
        topLeft = Offset(center.x - outerRx, center.y - outerRy),
        size = Size(outerRx * 2, outerRy * 2),
        style = strokeOf(1.5f.dp.toPx()),
    )
    val innerRx = 9f * sx
    val innerRy = 11f * sy
    drawOval(
        color = accent.copy(alpha = 0.4f),
        topLeft = Offset(center.x - innerRx, center.y - innerRy),
        size = Size(innerRx * 2, innerRy * 2),
    )
    drawCanvasText("x", 140f * sx, 103f * sy, 12f, accent, bold = true, centered = true)

    // label leader line
    drawLine(
        color = dim,
        start = Offset(218f * sx, 100f * sy),
        end = Offset(162f * sx, 100f * sy),
        strokeWidth = 1f.dp.toPx(),
    )
    drawCanvasText("centre of", 220f * sx, 100f * sy, 10f, dim, bold = false)
    drawCanvasText("chest", 220f * sx, 113f * sy, 10f, dim, bold = false)
}

private fun DrawScope.drawRecovery(
    stroke: Color,
    dim: Color,
    sx: Float,
    sy: Float,
) {
    // ground
    dashedLine(dim, 20f, 130f, 260f, 130f, sx, sy)

    // head
    circleStroke(stroke, 60f, 80f, 12f, sx, sy)

    // body curve along ground
    val body = Path().apply {
        moveTo(72f * sx, 80f * sy)
        quadraticBezierTo(110f * sx, 75f * sy, 140f * sx, 90f * sy)
        quadraticBezierTo(180f * sx, 105f * sy, 220f * sx, 95f * sy)
    }
    drawPath(body, stroke, style = strokeOf(1.6f.dp.toPx()))

    // upper arm folded
    val arm = Path().apply {
        moveTo(80f * sx, 80f * sy)
        quadraticBezierTo(90f * sx, 60f * sy, 75f * sx, 55f * sy)
    }
    drawPath(arm, stroke, style = strokeOf(1.6f.dp.toPx()))

    // upper leg bent
    val upperLeg = Path().apply {
        moveTo(165f * sx, 95f * sy)
        quadraticBezierTo(175f * sx, 65f * sy, 195f * sx, 70f * sy)
    }
    drawPath(upperLeg, stroke, style = strokeOf(1.6f.dp.toPx()))

    // lower leg
    line(stroke, 195f, 95f, 230f, 100f, sx, sy)

    // breathing arcs
    val breath = Path().apply {
        moveTo(50f * sx, 60f * sy)
        quadraticBezierTo(55f * sx, 50f * sy, 60f * sx, 60f * sy)
    }
    drawPath(breath, dim, style = strokeOf(1.2f.dp.toPx()))
    val breath2 = Path().apply {
        moveTo(40f * sx, 50f * sy)
        quadraticBezierTo(50f * sx, 35f * sy, 60f * sx, 50f * sy)
    }
    drawPath(breath2, dim.copy(alpha = 0.6f), style = strokeOf(1.2f.dp.toPx()))

    drawCanvasText("stable  -  monitor", 148f * sx, 40f * sy, 10f, dim, bold = false)
}

private fun DrawScope.drawRoundedRectStroke(
    color: Color,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius: Float,
    sx: Float,
    sy: Float,
    width: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x * sx, y * sy),
        size = Size(w * sx, h * sy),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * sx, radius * sy),
        style = strokeOf(width.dp.toPx()),
    )
}

private fun DrawScope.drawCanvasText(
    text: String,
    x: Float,
    y: Float,
    sizeSp: Float,
    color: Color,
    bold: Boolean,
    centered: Boolean = false,
) {
    drawIntoCanvasText(text, x, y, sizeSp, color, bold, centered)
}

private fun DrawScope.drawIntoCanvasText(
    text: String,
    x: Float,
    y: Float,
    sizeSp: Float,
    color: Color,
    bold: Boolean,
    centered: Boolean,
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        textSize = sizeSp.dp.toPx()
        textAlign = if (centered) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
        typeface = if (bold) {
            android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        } else {
            android.graphics.Typeface.DEFAULT
        }
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
