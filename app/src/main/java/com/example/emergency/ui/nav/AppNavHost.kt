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
                            gemma.load(
                                GemmaLoadOptions(
                                    modelPath = modelPath,
                                    backend = GemmaBackend.GPU,
                                    systemInstruction = buildSystemPrompt(toolManager)
                                )
                            )
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
                                // Generate a follow-up response from the tool result
                                val followUpPrompt = "Tool result for ${toolCall.toolName}:\n${result.data}\n\nDo NOT call any tools. Give the user a brief, numbered answer (max 6 steps, <=20 words each). No XML tags. No preamble."

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
                onStartNavigation = { result, profile ->
                    PendingNavigation.current = PendingNavigation.Handoff(result, profile)
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
You are Mark, an emergency medical assistant. Your job is to call the correct tool and then return a brief, numbered answer for a layperson.

${toolManager.getToolDescriptions()}

**How to choose a tool - follow this decision tree in order:**

1. User mentions NOT BREATHING, no pulse, cardiac arrest, or explicitly asks for CPR -> call `cpr_instructions`.
2. User says someone is unresponsive/collapsed/passed out/fainted/won't wake up BUT has NOT confirmed they are not breathing -> call `abc_check` first so they can assess Airway-Breathing-Circulation.
3. After abc_check, if the user reports the person is NOT breathing -> THEN call `cpr_instructions`.
4. Medical question (wound, burn, bleeding, fracture, poisoning, choking, etc.) -> call `search_medical_database` with the specific condition as query.
5. User asks **where** the nearest hospital/pharmacy/AED/etc. **is** (no movement implied) -> call `find_nearest` with the matching category.
   Supported categories: hospital, doctor, first_aid, aed, pharmacy, police, fire, shelter, water, toilet, metro, parking_underground, bunker, fuel, supermarket, atm, phone, school, community, worship.
6. User asks to **go to / get to / take me to / route to** a place - anything that implies movement - -> call `route_to`. The destination param accepts either coords ('52.374,4.890') or one of the find_nearest categories. Optional profile param: walk (default), bike, drive.
7. User asks for their current GPS coordinates -> call `get_location`. (It no longer fakes turn-by-turn directions - use route_to for that.)

**Output rules:**
- Your FIRST response must be the `<tool_call>` block - nothing before it, nothing after it.
- After the tool returns, reply with a numbered list (max 6 short steps, <=20 words each). No preamble, no medical jargon.

**Examples:**

User: "Someone collapsed, she's not breathing!"
Assistant:
<tool_call>
cpr_instructions
<tool_call>

User: "Someone collapsed and isn't responding - what do I check first?"
Assistant:
<tool_call>
abc_check
<tool_call>

User (after abc_check): "She's not breathing"
Assistant:
<tool_call>
cpr_instructions
<tool_call>

User: "Where is the nearest pharmacy?"
Assistant:
<tool_call>
find_nearest
category=pharmacy
<tool_call>

User: "I need to find an AED"
Assistant:
<tool_call>
find_nearest
category=aed
<tool_call>

User: "Where is the closest police station?"
Assistant:
<tool_call>
find_nearest
category=police
<tool_call>

User: "Find me a hospital"
Assistant:
<tool_call>
find_nearest
category=hospital
<tool_call>

User: "I need shelter"
Assistant:
<tool_call>
find_nearest
category=shelter
<tool_call>

User: "Take me to the nearest hospital"
Assistant:
<tool_call>
route_to
destination=hospital
<tool_call>

User: "Walk me to 52.374, 4.890"
Assistant:
<tool_call>
route_to
destination=52.374,4.890
profile=walk
<tool_call>

User: "Drive me to the nearest pharmacy"
Assistant:
<tool_call>
route_to
destination=pharmacy
profile=drive
<tool_call>

User: "[image of bleeding wound] how do I apply a tourniquet?"
Assistant:
<tool_call>
search_medical_database
query=tourniquet
<tool_call>
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
