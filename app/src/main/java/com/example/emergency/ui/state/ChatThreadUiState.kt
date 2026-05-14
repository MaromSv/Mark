package com.example.emergency.ui.state

enum class ChatRole { USER, ASSISTANT, TOOL }

data class ToolCallInfo(
    val toolName: String,
    val status: String, // "calling", "success", "error"
    /** Truncated to ~200 chars for display in the chat bubble. */
    val result: String = "",
    /** Full untruncated tool data - used by chat->map handoffs that need to
     *  parse JSON (find_nearest, route_to). Empty for tools that don't
     *  carry structured data. */
    val rawResult: String = "",
)

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val timestampLabel: String,
    val imagePaths: List<String> = emptyList(),
    val toolCall: ToolCallInfo? = null,
)

data class ChatThreadUiState(
    val title: String,
    val messages: List<ChatMessage>,
    val isAssistantTyping: Boolean,
)

val EmptyChatThreadUiState = ChatThreadUiState(
    title = "Conversation",
    messages = emptyList(),
    isAssistantTyping = false,
)

val SampleChatThreadUiState = ChatThreadUiState(
    title = "Conversation",
    messages = listOf(
        ChatMessage(
            id = "u0",
            role = ChatRole.USER,
            text = "Someone fell on the bridge \u2014 I think they're hurt.",
            timestampLabel = "12:04",
        ),
        ChatMessage(
            id = "a0",
            role = ChatRole.ASSISTANT,
            text = "I hear you. Where on the body, and how bad is the bleeding? I can walk you through first aid.",
            timestampLabel = "12:04",
        ),
    ),
    isAssistantTyping = false,
)
