package com.example.emergency.agent.tools

import com.example.emergency.agent.Tool
import com.example.emergency.agent.ToolResult

class AbcCheckTool {

    fun getTool(): Tool = Tool(
        name = "abc_check",
        description = "Guided ABC primary survey (Airway, Breathing, Circulation) for an unresponsive person.",
        execute = ::execute,
    )

    private suspend fun execute(params: Map<String, String>): ToolResult {
        val instructions = """
            ABC primary survey:

            A - Airway:
            - Tilt head back gently, lift chin with two fingers.
            - Only remove visible obstructions. Do not sweep blindly.

            B - Breathing:
            - Look, listen, feel for 10 seconds. No longer.
            - Gasping is not normal breathing. If unsure, treat as not breathing and start CPR.

            C - Circulation:
            - Skin colour: pale, blue, or grey is a warning sign.
            - Stop major bleeding with firm direct pressure.
            - Trained: feel carotid pulse - but do not delay CPR.

            If breathing is absent or in doubt, start CPR immediately and call 112.
        """.trimIndent()

        return ToolResult(success = true, data = instructions)
    }
}
