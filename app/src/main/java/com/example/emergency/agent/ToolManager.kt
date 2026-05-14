package com.example.emergency.agent

import android.content.Context
import com.example.emergency.agent.tools.FindNearestTool
import com.example.emergency.agent.tools.MedicalRagTool
import com.example.emergency.agent.tools.RouteTool

/**
 * Manages available tools and handles tool execution.
 */
class ToolManager(context: Context) {
    
    private val tools: Map<String, Tool>
    
    init {
        // Chat-agent tools:
        //   * search_medical_database — all medical Qs, with clarification
        //     handled in the system prompt.
        //   * find_nearest — "where is the nearest X" (drops a pin only,
        //     no route polyline).
        //   * route_to — turn-by-turn routing. Accepts coords or POI
        //     categories directly.
        // CPR and ABC are home-screen buttons, not tool calls.
        // GpsLocationTool removed — route_to + find_nearest cover the cases
        // it used to handle.
        val toolList = listOf(
            MedicalRagTool(context).getTool(),
            FindNearestTool(context).getTool(),
            RouteTool(context).getTool(),
        )
        
        tools = toolList.associateBy { it.name }
    }
    
    /**
     * Returns the list of available tools and the tool-call format for the system prompt.
     */
    fun getToolDescriptions(): String {
        return buildString {
            appendLine("Available tools:")
            tools.values.forEach { tool ->
                appendLine("- **${tool.name}**: ${tool.description}")
            }
            appendLine()
            appendLine("Tool call format (must match exactly):")
            appendLine("<tool_call>")
            appendLine("tool_name")
            appendLine("param=value")
            appendLine("</tool_call>")
        }
    }
    
    /**
     * Parses tool calls from the assistant's response.
     * Handles malformed tags the LLM sometimes produces such as:
     *   <tool_call>...</ tool_call>
     *   <tool_call>...</call>
     *   <tool_tool_call>...</tool_call>
     *   <tool_call>...</tool_ call>
     * We use a generous regex that captures the body between any
     * "tool" opening tag and any closing tag that starts with "</".
     */
    fun parseToolCalls(response: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        // Generous open tag: <tool_call>, <tool_tool_call>, < tool_call >, etc.
        // Generous close tag: </tool_call>, </call>, </tool_tool_call>, etc.
        val regex = """<\s*(?:tool_)*tool_call\s*>(.*?)<\s*/\s*(?:tool_)*(?:tool_)?call\s*>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        regex.findAll(response).forEach { match ->
            val content = match.groupValues[1].trim()
            val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

            if (lines.isNotEmpty()) {
                // The first line is the tool name - strip any leftover XML-like chars
                val toolName = lines[0].replace(Regex("[<>]"), "").trim()
                if (toolName.isNotEmpty() && toolName in tools) {
                    val paramLines = lines.drop(1)
                    val params = mutableMapOf<String, String>()
                    val orphans = mutableListOf<String>()

                    for (line in paramLines) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            params[parts[0].trim()] = parts[1].trim()
                        } else {
                            // Orphan value (no key=value format)
                            orphans.add(line)
                        }
                    }

                    // If no "query" param but there are orphan lines, treat
                    // the first one as the query (common model mistake).
                    if ("query" !in params && orphans.isNotEmpty()) {
                        params["query"] = orphans.joinToString(" ")
                    }
                    // Same recovery for find_nearest's "category".
                    if (toolName == "find_nearest" && "category" !in params && orphans.isNotEmpty()) {
                        params["category"] = orphans.first().lowercase()
                    }
                    // Same recovery for route_to's "destination".
                    if (toolName == "route_to" && "destination" !in params && orphans.isNotEmpty()) {
                        params["destination"] = orphans.first()
                    }

                    toolCalls.add(ToolCall(toolName, params))
                }
            }
        }

        return toolCalls
    }
    
    /**
     * Executes a tool call and returns the result.
     */
    suspend fun executeTool(toolCall: ToolCall): ToolResult {
        val tool = tools[toolCall.toolName]
            ?: return ToolResult(
                success = false,
                data = "",
                error = "Unknown tool: ${toolCall.toolName}"
            )
        
        return try {
            tool.execute(toolCall.params)
        } catch (e: Exception) {
            ToolResult(
                success = false,
                data = "",
                error = "Tool execution error: ${e.message}"
            )
        }
    }
    
    /**
     * Removes tool call blocks from the response text (matches the same
     * generous pattern as [parseToolCalls]).
     */
    fun removeToolCallBlocks(response: String): String {
        return response
            .replace(
                """<\s*(?:tool_)*tool_call\s*>.*?<\s*/\s*(?:tool_)*(?:tool_)?call\s*>"""
                    .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
                ""
            )
            .trim()
    }
}
