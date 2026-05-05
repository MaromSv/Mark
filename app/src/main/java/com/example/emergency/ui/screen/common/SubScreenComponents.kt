package com.example.emergency.ui.screen.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme

// Hardcoded brand colors - never affected by theme
private val MarkNavy = Color(0xFF1E293B)
private val MarkRed = Color(0xFFEF4444)

@Composable
fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = EmergencyTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .drawBehind {
                drawLine(
                    color = colors.line,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 4.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = colors.text,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MarkNavy, fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                    append("mar")
                }
                withStyle(SpanStyle(color = MarkRed, fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                    append("k")
                }
            },
            modifier = Modifier.align(Alignment.Center),
        )
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                trailing()
            }
        }
    }
}

@Composable
fun ScreenSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Text(
        text = text,
        style = typography.eyebrow,
        color = colors.textFaint,
        modifier = modifier,
    )
}

@Composable
fun GroupedListContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = EmergencyTheme.colors
    Column(
        verticalArrangement = Arrangement.Top,
        modifier = modifier
            .fillMaxWidth()
            .clip(EmergencyShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.line, EmergencyShapes.card),
    ) {
        content()
    }
}

@Composable
fun GroupedListDivider() {
    val colors = EmergencyTheme.colors
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(colors.line),
    )
}
