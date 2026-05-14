package com.example.emergency.ui.nav

import android.net.Uri
import android.util.Log
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.emergency.agent.ToolManager
import com.example.emergency.llm.GemmaBackend
import com.example.emergency.llm.GemmaLlm
import com.example.emergency.llm.GemmaLoadOptions
import com.example.emergency.ui.state.ToolCallInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.emergency.ui.screen.AbcCheckScreen
import com.example.emergency.ui.screen.ChatThreadScreen
import com.example.emergency.ui.screen.ConversationsScreen
import com.example.emergency.ui.screen.DataPacksScreen
import com.example.emergency.ui.screen.FirstAidScreen
import com.example.emergency.ui.screen.GetOutScreen
import com.example.emergency.ui.screen.HomeShell
import com.example.emergency.ui.screen.MapScreen
import com.example.emergency.ui.screen.map.MapDestination
import com.example.emergency.ui.screen.PersonalInfoScreen
import com.example.emergency.ui.screen.SettingsScreen
import com.example.emergency.ui.screen.cpr.CprWalkthroughScreen
import com.example.emergency.offline.navigation.PendingNavigation
import com.example.emergency.ui.screen.navigation.NavigationScreen
import com.example.emergency.ui.screen.regions.RegionPickerScreen
import com.example.emergency.ui.state.ChatMessage
import com.example.emergency.ui.state.ChatRole
import com.example.emergency.ui.state.ChatThreadUiState
import com.example.emergency.ui.state.SampleAbcCheckUiState
import com.example.emergency.ui.state.SampleConversationsUiState
import com.example.emergency.ui.state.SampleDataPacksUiState
import com.example.emergency.ui.state.SampleDrawerUiState
import com.example.emergency.ui.state.SampleFirstAidUiState
import com.example.emergency.ui.state.SampleGetOutUiState
import com.example.emergency.ui.state.SampleHomeUiState
import com.example.emergency.ui.state.SampleMapUiState
import com.example.emergency.ui.state.SamplePersonalInfoUiState
import com.example.emergency.ui.state.SampleSettingsUiState
enum class ModelStatus { IDLE, LOADING, READY, ERROR }


@Composable
fun AppNavHost() {
    val context = LocalContext.current

    // Track if model download is in progress
    var isDownloadingModel by remember { mutableStateOf(false) }
    var showDownloadModelButton by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(-1) } // -1 = indeterminate
    var downloadId by remember { mutableStateOf<Long?>(null) }

    // Check if model file exists
    LaunchedEffect(Unit) {
        val modelPath = GemmaLlm.defaultModelPath(context)
        showDownloadModelButton = !File(modelPath).exists()
    }

    // Download callback
    fun onDownloadModel() {
        val modelUrl = "https://drive.usercontent.google.com/download?id=1Ckz7pdwTlx-_yC5Hg0pnHjM1V48ClVhE&export=download&confirm=t"
        val modelPath = GemmaLlm.defaultModelPath(context)
        isDownloadingModel = true
        showDownloadModelButton = false // Hide button while downloading
        downloadProgress = -1 // indeterminate until we know total size
        val id = com.example.emergency.util.ModelDownloadUtil.downloadModel(
            context,
            modelUrl,
            File(modelPath)
        )
        downloadId = id
    }

    val navController = rememberNavController()
    val threadMessages = remember { mutableStateListOf<ChatMessage>() }
    val pendingImages = remember { mutableStateListOf<String>() }
    var isAssistantTyping by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf(ModelStatus.IDLE) }
    var modelError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Poll DownloadManager for progress
    LaunchedEffect(isDownloadingModel, downloadId) {
        if (isDownloadingModel && downloadId != null) {
            val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            var downloading = true
            while (downloading) {
                val q = android.app.DownloadManager.Query().setFilterById(downloadId!!)
                val cursor = dm.query(q)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                    downloadProgress = if (bytesTotal > 0) (bytesDownloaded * 100L / bytesTotal).toInt() else -1
                    if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false
                        isDownloadingModel = false
                        showDownloadModelButton = false
                        downloadId = null
                        // Reset model status so it retries loading on next message
                        modelStatus = ModelStatus.IDLE
                    } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                        downloading = false
                        isDownloadingModel = false
                        showDownloadModelButton = true
                        downloadId = null
                    }
                }
                cursor?.close()
                kotlinx.coroutines.delay(500)
            }
        }
    }

    // Create LLM and ToolManager instances
    val gemma = remember { GemmaLlm(context) }
    val toolManager = remember { ToolManager(context) }

    // Image pickers
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }
    
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success && pendingCameraPath != null) {
            pendingImages.add(pendingCameraPath!!)
        }
        pendingCameraPath = null
    }
    
    val requestCameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingCameraPath != null) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(pendingCameraPath!!)
            )
            takePicture.launch(uri)
        } else {
            pendingCameraPath = null
        }
    }
    
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            val ext = context.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.lowercase()
                ?.let { if (it in setOf("jpeg", "jpg", "png", "webp")) it else "jpg" }
                ?: "jpg"
            val dest = File(context.cacheDir, "img_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            pendingImages.add(dest.absolutePath)
        }
    }
    
    // Request location permission on first launch
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("AppNavHost", "Location permissions granted: $permissions")
    }
    
    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Clean up when composable leaves composition
    DisposableEffect(Unit) {
        onDispose { gemma.unload() }
    }

    fun sendUserMessage(text: String) {
        val userIndex = threadMessages.size
        val images = pendingImages.toList()
        threadMessages.add(
            ChatMessage(
                id = "u$userIndex",
                role = ChatRole.USER,
                text = text,
                timestampLabel = "now",
                imagePaths = images,
            ),
        )
        pendingImages.clear()

        // Add placeholder assistant message
        val assistantIndex = threadMessages.size
        val assistantId = "a$assistantIndex"
        threadMessages.add(
            ChatMessage(
                id = assistantId,
                role = ChatRole.ASSISTANT,
                text = "",
                timestampLabel = "now",
            ),
        )

        scope.launch {
            isAssistantTyping = true
            try {
                // Load model if not loaded yet
                if (modelStatus == ModelStatus.IDLE) {
                    modelStatus = ModelStatus.LOADING
                    val idx = threadMessages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        threadMessages[idx] = threadMessages[idx].copy(
                            text = "Loading model..."
                        )
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val modelPath = GemmaLlm.defaultModelPath(context)
                            Log.d("AppNavHost", "Loading model from: $modelPath")
                            // Try GPU first (fast on real phones with OpenCL),
                            // fall back to CPU on emulators / devices without
                            // an OpenCL driver. CPU is ~5x slower but works
                            // everywhere.
                            try {
                                gemma.load(
                                    GemmaLoadOptions(
                                        modelPath = modelPath,
                                        backend = GemmaBackend.GPU,
                                        systemInstruction = buildSystemPrompt(toolManager),
                                    ),
                                )
                            } catch (gpuFail: Throwable) {
                                Log.w(
                                    "AppNavHost",
                                    "GPU backend unavailable (likely no OpenCL on this device); " +
                                        "falling back to CPU. ${gpuFail.message}",
                                )
                                gemma.load(
                                    GemmaLoadOptions(
                                        modelPath = modelPath,
                                        backend = GemmaBackend.CPU,
                                        systemInstruction = buildSystemPrompt(toolManager),
                                    ),
                                )
                            }
                        }
                        modelStatus = ModelStatus.READY
                        Log.d("AppNavHost", "Model loaded successfully")
                        // Clear loading message
                        val idx2 = threadMessages.indexOfFirst { it.id == assistantId }
                        if (idx2 >= 0) {
                            threadMessages[idx2] = threadMessages[idx2].copy(text = "")
                        }
                    } catch (e: Exception) {
                        if (e is com.example.emergency.llm.ModelFileMissingException) {
                            // Model file missing: show download button and friendly message
                            showDownloadModelButton = true
                            modelStatus = ModelStatus.ERROR
                            val idx2 = threadMessages.indexOfFirst { it.id == assistantId }
                            if (idx2 >= 0) {
                                threadMessages[idx2] = threadMessages[idx2].copy(
                                    text = "Model not downloaded. Please download to continue."
                                )
                            }
                        } else {
                            Log.e("AppNavHost", "Failed to load model", e)
                            modelError = e.message ?: "Unknown error"
                            modelStatus = ModelStatus.ERROR
                            val idx2 = threadMessages.indexOfFirst { it.id == assistantId }
                            if (idx2 >= 0) {
                                threadMessages[idx2] = threadMessages[idx2].copy(
                                    text = "Error loading model: ${e.message ?: "Unknown error"}"
                                )
                            }
                        }
                        isAssistantTyping = false
                        return@launch
                    }
                }
                
                // Generate response with tool calling support
                if (gemma.isLoaded) {
                    // Reset conversation so the model starts fresh from the
                    // system prompt - this prevents it from copying its own
                    // previous (sometimes malformed) tool-call XML. We then
                    // prepend a sanitised summary of the last few exchanges
                    // to the user prompt so the model retains context.
                    withContext(Dispatchers.IO) { gemma.resetConversation() }

                    // Build a clean context recap from recent messages.
                    val history = threadMessages
                        .dropLast(2) // drop current user msg + empty assistant placeholder
                        .takeLast(8) // last ~4 exchanges
                    val contextPrefix = if (history.isNotEmpty()) {
                        buildString {
                            appendLine("[Conversation history]")
                            for (msg in history) {
                                when (msg.role) {
                                    ChatRole.USER -> appendLine("User: ${msg.text}")
                                    ChatRole.ASSISTANT -> {
                                        val clean = toolManager.removeToolCallBlocks(msg.text).take(200)
                                        if (clean.isNotBlank()) appendLine("Assistant: $clean")
                                    }
                                    ChatRole.TOOL -> {
                                        val tc = msg.toolCall
                                        if (tc != null) appendLine("(Tool ${tc.toolName} -> ${tc.status})")
                                    }
                                }
                            }
                            appendLine("[End of history]\n")
                            appendLine("Now the user says:")
                        }
                    } else ""

                    val augmentedPrompt = contextPrefix + text

                    var fullResponse = ""
                    
                    // Stream the initial response. Keep the full text (including any
                    // <tool_call> XML the LLM emits) in fullResponse for the parser, but
                    // strip anything from the first '<' onward before showing it in the
                    // chat bubble so the user never sees raw XML.
                    withContext(Dispatchers.IO) {
                        gemma.generateStreamingWithImages(augmentedPrompt, images).collect { token ->
                            fullResponse += token
                            val ltIdx = fullResponse.indexOf('<')
                            val displayText = if (ltIdx >= 0) {
                                fullResponse.substring(0, ltIdx).trim()
                            } else {
                                fullResponse
                            }
                            val idx = threadMessages.indexOfFirst { it.id == assistantId }
                            if (idx >= 0) {
                                val current = threadMessages[idx]
                                threadMessages[idx] = current.copy(text = displayText)
                            }
                        }
                    }
                    
                    Log.d("AppNavHost", "Full LLM response: $fullResponse")
                    
                    // Check for tool calls in the response
                    val toolCalls = toolManager.parseToolCalls(fullResponse)
                    Log.d("AppNavHost", "Parsed tool calls: ${toolCalls.size} found")
                    toolCalls.forEachIndexed { i, call ->
                        Log.d("AppNavHost", "Tool call $i: ${call.toolName} with params: ${call.params}")
                    }
                    if (toolCalls.isNotEmpty()) {
                        // When a tool call is found, HIDE the original assistant
                        // bubble - the model often puts a preamble answer before
                        // the <tool_call> which would duplicate the follow-up.
                        val assistantIdx = threadMessages.indexOfFirst { it.id == assistantId }
                        val preambleText = toolManager.removeToolCallBlocks(fullResponse).trim()
                        if (assistantIdx >= 0) {
                            threadMessages[assistantIdx] = threadMessages[assistantIdx].copy(text = "")
                        }

                        // Execute only the FIRST tool call (ignore duplicates)
                        val toolCall = toolCalls.first()

                        // Add tool call message
                        val toolIndex = threadMessages.size
                        threadMessages.add(
                            ChatMessage(
                                id = "t$toolIndex",
                                role = ChatRole.TOOL,
                                text = "",
                                timestampLabel = "now",
                                toolCall = ToolCallInfo(
                                    toolName = toolCall.toolName,
                                    status = "calling"
                                )
                            )
                        )

                        // Execute tool
                        Log.d("AppNavHost", "Executing tool: ${toolCall.toolName}")
                        val result = withContext(Dispatchers.IO) {
                            toolManager.executeTool(toolCall)
                        }
                        Log.d("AppNavHost", "Tool ${toolCall.toolName} result: success=${result.success}, data=${result.data}, error=${result.error}")

                        // Update tool call message with result
                        val tidx = threadMessages.indexOfFirst { it.id == "t$toolIndex" }
                        if (tidx >= 0) {
                            threadMessages[tidx] = threadMessages[tidx].copy(
                                toolCall = ToolCallInfo(
                                    toolName = toolCall.toolName,
                                    status = if (result.success) "success" else "error",
                                    result = result.data.take(200) + if (result.data.length > 200) "..." else "",
                                    rawResult = result.data,
                                )
                            )
                        }

                        // CPR and ABC are fully handled by their walkthrough cards
                        if (toolCall.toolName != "cpr_instructions" && toolCall.toolName != "abc_check") {
                            if (result.success) {
                                // Build the follow-up prompt with full context: prior
                                // conversation, the user's actual question, and the
                                // tool's authoritative result. Without history the
                                // model answers in a vacuum and ignores follow-ups
                                // ("how tight?", "what about kids?") that depend on
                                // what was said earlier.
                                val followUpPrompt = buildString {
                                    if (history.isNotEmpty()) {
                                        appendLine("[Conversation history]")
                                        for (msg in history) {
                                            when (msg.role) {
                                                ChatRole.USER -> appendLine("User: ${msg.text}")
                                                ChatRole.ASSISTANT -> {
                                                    val clean = toolManager.removeToolCallBlocks(msg.text).take(300)
                                                    if (clean.isNotBlank()) appendLine("Assistant: $clean")
                                                }
                                                ChatRole.TOOL -> {
                                                    val tc = msg.toolCall
                                                    if (tc != null) {
                                                        appendLine("(Previous tool ${tc.toolName} returned:")
                                                        appendLine(tc.rawResult.ifBlank { tc.result }.take(600))
                                                        appendLine(")")
                                                    }
                                                }
                                            }
                                        }
                                        appendLine("[End of history]")
                                        appendLine()
                                    }
                                    appendLine("The user's latest message:")
                                    appendLine(text)
                                    appendLine()
                                    appendLine("You just called `${toolCall.toolName}` and got this result from the medical database:")
                                    appendLine("---")
                                    appendLine(result.data)
                                    appendLine("---")
                                    appendLine()
                                    append(
                                        "Now answer the user. Do NOT call any more tools - you already have the information. " +
                                        "Match the form of your answer to the form of the question: " +
                                        "if it's a request for a procedure, give clear numbered steps in plain language; " +
                                        "if it's a judgment or follow-up question, write naturally and reason about it. " +
                                        "Use the database result as authoritative protocol detail, but add your own reasoning and explanation - you are a capable medical model, not just a search frontend. " +
                                        "No XML tags. No preamble like \"Here's the answer.\""
                                    )
                                }

                                val newAssistantIndex = threadMessages.size
                                val newAssistantId = "a$newAssistantIndex"
                                threadMessages.add(
                                    ChatMessage(
                                        id = newAssistantId,
                                        role = ChatRole.ASSISTANT,
                                        text = "",
                                        timestampLabel = "now",
                                    )
                                )

                                var toolResponse = ""
                                withContext(Dispatchers.IO) {
                                    gemma.generateStreaming(followUpPrompt).collect { token ->
                                        toolResponse += token
                                        val clean = toolManager.removeToolCallBlocks(toolResponse)
                                        val ridx = threadMessages.indexOfFirst { it.id == newAssistantId }
                                        if (ridx >= 0) {
                                            threadMessages[ridx] = threadMessages[ridx].copy(text = clean)
                                        }
                                    }
                                }
                            } else if (preambleText.isNotBlank()) {
                                // Tool failed but the model gave a direct answer - show it
                                val aidx = threadMessages.indexOfFirst { it.id == assistantId }
                                if (aidx >= 0) {
                                    threadMessages[aidx] = threadMessages[aidx].copy(text = preambleText)
                                }
                            }
                        }
                    } else {
                        // No tool calls, just clean up the response
                        val idx = threadMessages.indexOfFirst { it.id == assistantId }
                        if (idx >= 0) {
                            threadMessages[idx] = threadMessages[idx].copy(
                                text = toolManager.removeToolCallBlocks(fullResponse)
                            )
                        }
                    }
                } else {
                    // Fallback if model not loaded
                    val idx = threadMessages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        threadMessages[idx] = threadMessages[idx].copy(
                            text = "Model not loaded. Please wait or check the model file."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("AppNavHost", "Generation error", e)
                val idx = threadMessages.indexOfFirst { it.id == assistantId }
                if (idx >= 0) {
                    threadMessages[idx] = threadMessages[idx].copy(
                        text = "Error: ${e.message ?: "Generation failed"}"
                    )
                }
            } finally {
                isAssistantTyping = false
            }
        }
    }

    fun onCamera() {
        val imageFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        pendingCameraPath = imageFile.absolutePath
        requestCameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    
    fun onGallery() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun onRemoveImage(path: String) {
        pendingImages.remove(path)
    }

    fun startNewChat() {
        threadMessages.clear()
        modelStatus = ModelStatus.IDLE
    }

    NavHost(
        navController = navController,
        startDestination = Route.Home.path,
    ) {
        composable(Route.Home.path) {
            HomeShell(
                homeState = SampleHomeUiState,
                drawerState = SampleDrawerUiState,
                onToolClick = { id ->
                    navController.navigate(id.toRoute().navigatePath)
                },
                onDrawerItemClick = { id ->
                    navController.navigate(id.toRoute().navigatePath)
                },
                onSend = { text ->
                    sendUserMessage(text)
                    navController.navigate(Route.ChatThread.path) {
                        launchSingleTop = true
                    }
                },
                onNewChatClick = { startNewChat() },
                onCamera = { onCamera() },
                onGallery = { onGallery() },
                pendingImages = pendingImages,
                onRemoveImage = { path -> onRemoveImage(path) },
            )
        }
        composable(Route.ChatThread.path) {
            ChatThreadScreen(
                state = ChatThreadUiState(
                    title = "Conversation",
                    messages = threadMessages,
                    isAssistantTyping = isAssistantTyping,
                ),
                onBack = { navController.popBackStack() },
                onNewChat = {
                    startNewChat()
                    navController.popBackStack(Route.Home.path, inclusive = false)
                },
                onSend = { text -> sendUserMessage(text) },
                onCamera = { onCamera() },
                onGallery = { onGallery() },
                pendingImages = pendingImages,
                onRemoveImage = { path -> onRemoveImage(path) },
                onOpenTool = { toolCall ->
                    when (toolCall.toolName) {
                        "cpr_instructions" -> navController.navigate(Route.CprWalkthrough.path)
                        "abc_check" -> navController.navigate(Route.AbcCheck.path)
                        // Both find_nearest and route_to return the same
                        // {name, category, lat, lon} JSON shape so the chat
                        // can hand them off to the map identically. Prefer
                        // rawResult so route_to's longer JSON (with first_steps)
                        // doesn't get cut at the 200-char chat-display limit.
                        "find_nearest", "route_to" -> {
                            val payload = toolCall.rawResult.ifBlank { toolCall.result }
                            val dest = parseFindNearestDestination(payload)
                            val target = if (dest != null) {
                                Route.Map.withDestination(dest.lat, dest.lon, dest.name, dest.category)
                            } else {
                                "map"
                            }
                            navController.navigate(target)
                        }
                    }
                },
                showDownloadModelButton = showDownloadModelButton,
                onDownloadModel = if (showDownloadModelButton) ::onDownloadModel else null,
                isDownloadingModel = isDownloadingModel,
                downloadProgress = downloadProgress,
            )
        }
        composable(Route.DataPacks.path) {
            DataPacksScreen(
                state = SampleDataPacksUiState,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.PersonalInfo.path) {
            PersonalInfoScreen(
                state = SamplePersonalInfoUiState,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.Conversations.path) {
            ConversationsScreen(
                state = SampleConversationsUiState,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Route.Map.path,
            arguments = listOf(
                navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("lon") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val lat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull()
            val lon = backStackEntry.arguments?.getString("lon")?.toDoubleOrNull()
            val name = backStackEntry.arguments?.getString("name")
            val category = backStackEntry.arguments?.getString("category")
            val dest = if (lat != null && lon != null && !name.isNullOrBlank() && !category.isNullOrBlank()) {
                MapDestination(name, category, lat, lon)
            } else null
            MapScreen(
                state = SampleMapUiState,
                onBack = { navController.popBackStack() },
                onOpenRegions = { navController.navigate(Route.Regions.path) },
                onStartNavigation = { result, profile, destination ->
                    PendingNavigation.current = PendingNavigation.Handoff(
                        route = result,
                        profile = profile,
                        destinationName = destination?.name,
                        destinationCategory = destination?.category,
                    )
                    navController.navigate(Route.Navigation.path)
                },
                initialDestination = dest,
            )
        }
        composable(Route.FirstAid.path) {
            FirstAidScreen(
                state = SampleFirstAidUiState,
                onBack = { navController.popBackStack() },
                onAbcCheckClick = { navController.navigate(Route.AbcCheck.path) },
            )
        }
        composable(Route.AbcCheck.path) {
            AbcCheckScreen(
                state = SampleAbcCheckUiState,
                onBack = { navController.popBackStack() },
                onStartCpr = {
                    navController.navigate(Route.CprWalkthrough.path) {
                        popUpTo(Route.AbcCheck.path) { inclusive = true }
                    }
                },
            )
        }
        composable(Route.CprWalkthrough.path) {
            CprWalkthroughScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.GetOut.path) {
            GetOutScreen(
                state = SampleGetOutUiState,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.Regions.path) {
            RegionPickerScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.Navigation.path) {
            // Read once on mount - PendingNavigation is single-slot and a
            // re-entry without a fresh route shouldn't loop on the same
            // (possibly stale) handoff. Re-keying remember on the handoff
            // means a brand-new "Start" tap pushes a fresh engine even if
            // the user backed out and tapped again.
            val handoff = remember { PendingNavigation.take() }
            if (handoff != null) {
                NavigationScreen(
                    initialRoute = handoff.route,
                    profile = handoff.profile,
                    destinationName = handoff.destinationName,
                    destinationCategory = handoff.destinationCategory,
                    onBack = { navController.popBackStack() },
                )
            } else {
                // Process restart or direct deeplink - bounce back to map.
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(Route.Settings.path) {
            SettingsScreen(
                state = SampleSettingsUiState,
                onBack = { navController.popBackStack() },
                onPersonalInfoClick = { navController.navigate(Route.PersonalInfo.path) },
            )
        }
    }
}

private fun buildSystemPrompt(toolManager: ToolManager): String {
    return """
You are Mark, an emergency medical assistant. You have three tools: `search_medical_database` for any medical situation, `find_nearest` for "where is the nearest X" (locate only), and `route_to` for navigation. CPR and the ABC (Airway-Breathing-Circulation) check live on dedicated home-screen buttons - don't try to "call" them.

${toolManager.getToolDescriptions()}

**How to handle medical situations (`search_medical_database`):**

You are a capable medical model. The database makes you *better* by giving you authoritative protocol detail when you need it - it does not replace your own reasoning. Use both.

**Do NOT call the tool when:**
- The user's question can be answered from a previous tool result already in this conversation. Reason over it instead of re-retrieving the same thing.
- They're asking a yes/no or common-sense judgment ("is a dirty sock OK to use?" -> "No - non-sterile cloth risks infection. Use a clean cloth or piece of clothing if no kit is available, and change it once you get proper supplies."). Answer directly with your own knowledge.
- They're asking you to describe or assess what's in an image. Use your vision - that's not retrieval.
- They're asking for a definition, a conversational follow-up, or to summarize what you already retrieved.

**DO call the tool when** you need protocol-level detail (specific dosages, step orders, contraindications, decision criteria) that you don't already have in context. Triage:

1. **Life-threatening AND clear** (e.g. "she's not breathing", "he's choking", "deep bleeding I can't stop", "anaphylaxis"): call immediately with a short focused query (e.g. query=cardiac arrest, query=choking, query=hemorrhage, query=anaphylaxis). No clarifying question - retrieve now.

2. **Specific complaint with enough detail** (e.g. "I burned my hand on the stove", "deep cut on my arm from a knife", "snake bite on my leg"): call directly.

3. **Vague or ambiguous symptom** (e.g. "I feel weird", "my chest hurts", "she's confused", "stomach pain"): ask exactly ONE short clarifying question - whichever detail most changes what protocol applies. Then on the next turn, call the tool. Examples:
   - "I can't breathe" -> "Did this start suddenly (e.g. after a bee sting or eating), or build up gradually (asthma, panic, illness)?"
   - "my chest hurts" -> "Is the pain sharp and worse when you breathe in, or a heavy/squeezing pressure?"
   - "she's confused" -> "Did this come on suddenly, and is she also weak on one side or slurring her speech?"
   - "stomach pain" -> "Where exactly is the pain - upper, lower-right, lower-left, or all over?"

Once you have the info, call the tool with a query reflecting the clarified situation.

**How to handle locations & navigation:**

- **"Where is the nearest X?" / "Find me an X"** (locate only, no movement implied) -> call `find_nearest` with one of the supported categories.
- **"Take me to / walk me to / drive me to / route to X"** (movement implied) -> call `route_to`. Destination accepts coords (`52.374,4.890`) or a category. Optional profile: walk (default) | bike | drive.

Supported categories (same for both tools): hospital, doctor, first_aid, aed, pharmacy, police, fire, shelter, water, toilet, metro, parking_underground, bunker, fuel, supermarket, atm, phone, school, community, worship.

**Output rules:**

- When you're calling a tool, the `<tool_call>` block is the WHOLE response - nothing before, nothing after.
- When asking a clarifying question, no tool call - just one short question (under 25 words).
- When a tool returned a step-by-step procedure (e.g. CPR, tourniquet application, choking maneuver, anaphylaxis response): present the steps clearly as a numbered list, plain language. Add a short lead sentence if it helps the user understand what they're about to do, and a brief warning or next-step at the end if the protocol calls for one. Don't pad, don't repeat what the database said verbatim if you can make it clearer.
- When answering a judgment, follow-up, image-description, or conversational question: write naturally. Reason. Explain *why*, not just *what*. Match the form of the answer to the form of the question - a yes/no gets a yes/no with a one-line reason, an image gets a description with your assessment, a "what should I do next" after a previous tool result gets a direct answer.
- Plain language does not mean shallow. You're a real medical model - use your knowledge. The database is an authority you cite when you need protocol detail, not a crutch that replaces your reasoning.
- Never refuse a medical question. If you're genuinely missing protocol-level detail, retrieve. Otherwise, answer.

**Examples:**

User: "She collapsed and isn't breathing!"
Assistant:
<tool_call>
search_medical_database
query=cardiac arrest
<tool_call>

User: "He's choking on a piece of bread"
Assistant:
<tool_call>
search_medical_database
query=choking
<tool_call>

User: "I burned my hand on the stove"
Assistant:
<tool_call>
search_medical_database
query=thermal burn
<tool_call>

User: "I can't breathe"
Assistant: Did this come on suddenly (e.g. after a sting, eating, or exposure), or build up gradually like asthma or illness?

User: "Sudden, after a bee stung me"
Assistant:
<tool_call>
search_medical_database
query=anaphylaxis bee sting
<tool_call>

User: "My chest hurts"
Assistant: Is the pain sharp and worse when you breathe in, or a heavy/squeezing pressure that doesn't move?

User: "Heavy pressure, also down my left arm"
Assistant:
<tool_call>
search_medical_database
query=chest pain heart attack
<tool_call>

User: "[image of bleeding wound] how do I apply a tourniquet?"
Assistant:
<tool_call>
search_medical_database
query=tourniquet hemorrhage
<tool_call>

User: "Where is the nearest pharmacy?"
Assistant:
<tool_call>
find_nearest
category=pharmacy
<tool_call>

User: "Find me an AED"
Assistant:
<tool_call>
find_nearest
category=aed
<tool_call>

User: "Take me to the nearest hospital"
Assistant:
<tool_call>
route_to
destination=hospital
<tool_call>

User: "Drive me to a pharmacy"
Assistant:
<tool_call>
route_to
destination=pharmacy
profile=drive
<tool_call>

User: "Walk me to 52.374, 4.890"
Assistant:
<tool_call>
route_to
destination=52.374,4.890
profile=walk
<tool_call>

**Examples where you answer directly (no tool call):**

User: "[image of a small cut on a forearm]"
Assistant: I can see a shallow linear cut on the forearm with light bleeding - it looks like a superficial laceration, not a deep wound. Rinse it under clean running water, apply gentle pressure with a clean cloth for a few minutes until bleeding stops, then cover with a clean dressing. If the bleeding doesn't slow within 10 minutes or you can see fat/muscle, that's a different situation - tell me and I'll pull the protocol.

User: "Should I use a dirty sock as a dressing?"
Assistant: No - non-sterile fabric can introduce infection into an open wound. A clean piece of clothing (a folded t-shirt, scarf, or anything freshly laundered) is much better. If that's also not available, use whatever is cleanest and change it as soon as you have proper supplies. The goal right now is pressure + barrier, not perfection.

User: (after a previous tool call returned tourniquet protocol) "How tight should I make it?"
Assistant: Tight enough that the bleeding stops and you can no longer feel a pulse below the tourniquet - it should be uncomfortable, even painful. A tourniquet that "looks tight" but doesn't stop the bleed is doing nothing useful. Note the time you applied it; that's information the paramedics will need.

User: "What does anaphylaxis mean?"
Assistant: It's a severe, full-body allergic reaction - the immune system overreacts to something (food, sting, medication), causing airway swelling, a drop in blood pressure, and sometimes loss of consciousness. It's life-threatening and needs epinephrine fast. If someone is having one right now and you have an EpiPen, tell me - I'll walk you through it.
    """.trimIndent()
}

private fun parseFindNearestDestination(raw: String): MapDestination? {
    val trimmed = raw.trim().removeSuffix("...")
    if (!trimmed.startsWith("{")) return null
    return runCatching {
        val obj = org.json.JSONObject(trimmed)
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@runCatching null
        val category = obj.optString("category").takeIf { it.isNotBlank() } ?: return@runCatching null
        val lat = obj.optDouble("lat", Double.NaN).takeIf { !it.isNaN() } ?: return@runCatching null
        val lon = obj.optDouble("lon", Double.NaN).takeIf { !it.isNaN() } ?: return@runCatching null
        MapDestination(name, category, lat, lon)
    }.getOrNull()
}
