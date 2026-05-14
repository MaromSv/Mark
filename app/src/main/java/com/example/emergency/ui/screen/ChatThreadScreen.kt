package com.example.emergency.ui.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.emergency.ui.state.ChatMessage
import com.example.emergency.ui.state.ToolCallInfo
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.emergency.ui.screen.chat.AbcCheckCard
import com.example.emergency.ui.screen.chat.ChatInputBar
import com.example.emergency.ui.screen.chat.CprWalkthroughCard
import com.example.emergency.ui.screen.chat.MapToolCard
import com.example.emergency.ui.screen.map.MapDestination
import org.json.JSONObject
import com.example.emergency.ui.screen.common.SubScreenTopBar
import com.example.emergency.ui.state.ChatRole
import com.example.emergency.ui.state.ChatThreadUiState
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme

@Composable
fun ChatThreadScreen(
    state: ChatThreadUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onMic: () -> Unit = {},
    onCamera: () -> Unit = {},
    onGallery: () -> Unit = {},
    pendingImages: List<String> = emptyList(),
    onRemoveImage: (String) -> Unit = {},
    onOpenTool: (ToolCallInfo) -> Unit = {},
    onNewChat: () -> Unit = {},
    showDownloadModelButton: Boolean = false,
    onDownloadModel: (() -> Unit)? = null,
    isDownloadingModel: Boolean = false,
    downloadProgress: Int = 0,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    var composer by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val totalItems = state.messages.size + if (state.isAssistantTyping) 1 else 0
    LaunchedEffect(totalItems) {
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        SubScreenTopBar(
            title = state.title,
            onBack = onBack,
            trailing = {
                IconButton(onClick = onNewChat) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New chat",
                        tint = colors.text,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )

        if (state.messages.isEmpty() && !state.isAssistantTyping) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Start a new conversation below.",
                        style = typography.body,
                        color = colors.textFaint,
                    )
                    if (showDownloadModelButton && onDownloadModel != null) {
                        androidx.compose.material3.Button(
                            onClick = onDownloadModel,
                            modifier = Modifier.padding(top = 24.dp)
                        ) {
                            Text("Download Model Weights")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(items = state.messages, key = { it.id }) { message ->
                    when (message.role) {
                        ChatRole.USER -> {
                            if (message.imagePaths.isNotEmpty()) {
                                UserMessageBubbleWithImages(message = message)
                            } else {
                                UserMessageBubble(text = message.text)
                            }
                        }
                        ChatRole.ASSISTANT -> {
                            if (message.text.isNotEmpty()) {
                                AssistantMessageBubble(text = message.text)
                            }
                        }
                        ChatRole.TOOL -> {
                            val toolCall = message.toolCall!!
                            when {
                                toolCall.toolName == "cpr_instructions" && toolCall.status == "success" ->
                                    CprWalkthroughCard(onClick = { onOpenTool(toolCall) })
                                toolCall.toolName == "abc_check" && toolCall.status == "success" ->
                                    AbcCheckCard(onClick = { onOpenTool(toolCall) })
                                (toolCall.toolName == "find_nearest" || toolCall.toolName == "route_to") &&
                                    toolCall.status == "success" -> {
                                    // Prefer rawResult so route_to's longer JSON
                                    // (with first_steps + distance) parses cleanly
                                    // even when the chat-display copy was truncated.
                                    val payload = toolCall.rawResult.ifBlank { toolCall.result }
                                    val parsed = parseFindNearestCard(payload)
                                    if (parsed != null) {
                                        MapToolCard(
                                            title = parsed.first,
                                            destination = parsed.second,
                                            onClick = { onOpenTool(toolCall) },
                                        )
                                    } else {
                                        ToolCallBubble(toolCall = toolCall)
                                    }
                                }
                                else -> ToolCallBubble(toolCall = toolCall)
                            }
                        }
                    }
                }
                if (state.isAssistantTyping) {
                    item(key = "typing-indicator") { AssistantTypingBubble() }
                }
                if (isDownloadingModel) {
                    item(key = "download-progress") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (downloadProgress < 0) "Downloading model..." else "Downloading model... $downloadProgress%",
                                style = EmergencyTheme.typography.body,
                                color = EmergencyTheme.colors.textFaint,
                            )
                            if (downloadProgress < 0) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(0.75f).height(8.dp)
                                )
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth(0.75f).height(8.dp)
                                )
                            }
                        }
                    }
                } else if (showDownloadModelButton && onDownloadModel != null) {
                    item(key = "download-button") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            androidx.compose.material3.Button(
                                onClick = onDownloadModel,
                            ) {
                                Text("Download Model Weights")
                            }
                        }
                    }
                }
            }
        }

        ChatInputBar(
            text = composer,
            onTextChange = { composer = it },
            onSend = {
                if (composer.isNotEmpty() || pendingImages.isNotEmpty()) {
                    onSend(composer)
                    composer = ""
                }
            },
            onMic = onMic,
            onCamera = onCamera,
            onGallery = onGallery,
            pendingImages = pendingImages,
            onRemoveImage = onRemoveImage,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun UserMessageBubble(text: String) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(EmergencyShapes.card)
                .background(colors.accent)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = typography.body,
                color = colors.accentInk,
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(text: String) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(EmergencyShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.line, EmergencyShapes.card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text.replace("**", ""),
                style = typography.body,
                color = colors.text,
            )
        }
    }
}

@Composable
private fun AssistantTypingBubble() {
    val colors = EmergencyTheme.colors
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(EmergencyShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.line, EmergencyShapes.card)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            for (idx in 0..2) {
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600, delayMillis = idx * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$idx",
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(colors.textDim.copy(alpha = alpha)),
                )
            }
        }
    }
}

@Composable
private fun UserMessageBubbleWithImages(message: ChatMessage) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(EmergencyShapes.card)
                .background(colors.accent)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            message.imagePaths.forEach { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            if (message.text.isNotEmpty()) {
                Text(
                    text = message.text,
                    style = typography.body,
                    color = colors.accentInk,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallBubble(toolCall: ToolCallInfo) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(EmergencyShapes.card)
                .background(
                    when (toolCall.status) {
                        "success" -> colors.surface
                        "error" -> colors.surface.copy(red = 0.3f)
                        else -> colors.surface
                    }
                )
                .border(
                    1.dp,
                    when (toolCall.status) {
                        "success" -> colors.accent.copy(alpha = 0.5f)
                        "error" -> colors.line.copy(red = 0.5f)
                        else -> colors.line
                    },
                    EmergencyShapes.card
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when (toolCall.status) {
                                    "success" -> colors.accent
                                    "error" -> colors.line.copy(red = 0.8f)
                                    else -> colors.textDim
                                }
                            )
                    )
                    Text(
                        text = when (toolCall.status) {
                            "calling" -> "Calling ${toolCall.toolName}..."
                            "success" -> "Used ${toolCall.toolName}"
                            "error" -> "Error: ${toolCall.toolName}"
                            else -> toolCall.toolName
                        },
                        style = typography.body.copy(fontSize = 13.sp),
                        color = colors.textDim,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (toolCall.result.isNotEmpty()) {
                    Text(
                        text = toolCall.result,
                        style = typography.body.copy(fontSize = 12.sp),
                        color = colors.textFaint,
                    )
                }
            }
        }
    }
}

private fun parseFindNearestCard(raw: String): Pair<String, MapDestination>? {
    val trimmed = raw.trim().removeSuffix("...")
    if (!trimmed.startsWith("{")) return null
    return runCatching {
        val obj = JSONObject(trimmed)
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@runCatching null
        val category = obj.optString("category").takeIf { it.isNotBlank() } ?: return@runCatching null
        val lat = obj.optDouble("lat", Double.NaN).takeIf { !it.isNaN() } ?: return@runCatching null
        val lon = obj.optDouble("lon", Double.NaN).takeIf { !it.isNaN() } ?: return@runCatching null
        val pretty = categoryLabel(category)
        val title = if (!name.equals(pretty, ignoreCase = true)) "$pretty \u00B7 $name" else name
        title to MapDestination(name, category, lat, lon)
    }.getOrNull()
}

private fun categoryLabel(category: String): String = when (category) {
    "first_aid" -> "Medical post"
    "aed" -> "AED"
    "atm" -> "ATM"
    "parking_underground" -> "Parking"
    else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
