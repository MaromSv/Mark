package com.example.emergency.offline.routing

/**
 * Renders a [TurnStep] as the kind of short line a maneuver banner shows
 * ("In 200 m, turn left onto Hoofdstraat") or a TTS engine speaks.
 *
 * Pure functions so the formatter is the same for the live-banner UI
 * (Step 7.5), the LLM tool reply (Step 8), and unit tests.
 *
 * Style choices, all of them deliberate:
 *   * Distance bucketing matches Google Maps: under 100 m show "50 m",
 *     under a kilometre show "400 m" (rounded to 50 m for spoken cadence),
 *     otherwise "1.2 km" with one decimal.
 *   * "Onto" is used only when the new street has a name - falling back
 *     to "the road" reads more honestly than dropping the connector.
 *   * Roundabouts always spell out the exit number - "the 1st exit", not
 *     "exit 1" - because "exit 3" sounds like a motorway sign.
 */
object StepFormatter {

    /**
     * Renders a single instruction. [distanceUntilHereM] is the distance
     * from the user's current position (or the previous maneuver) to
     * [step]. Pass `0.0` for the very first instruction the user sees so
     * we drop the "In X m," lead-in.
     *
     * For a fully pre-rendered step list (no live GPS), call this with
     * the *previous* step's [TurnStep.distanceToNextMeters] for each row;
     * the first row uses 0.0.
     */
    fun formatStep(step: TurnStep, distanceUntilHereM: Double): String {
        val maneuver = maneuverPhrase(step)
        if (step.command is TurnCommand.Arrive) {
            return "You have arrived"
        }
        return if (distanceUntilHereM < ANNOUNCE_FLOOR_METERS) {
            // No "In X m," lead-in for sub-floor distances or the first
            // instruction on the route - just speak the maneuver.
            maneuver.replaceFirstChar { it.uppercase() }
        } else {
            "In ${formatDistance(distanceUntilHereM)}, $maneuver"
        }
    }

    /**
     * Distance bucketing used by [formatStep].
     *   * `< 100 m` -> rounded to 10 m, "50 m"
     *   * `< 1 000 m` -> rounded to 50 m, "400 m"
     *   * `>= 1 000 m` -> one-decimal km, "1.2 km"
     */
    fun formatDistance(meters: Double): String = when {
        meters < 100.0 -> "${(meters / 10).toInt() * 10} m"
        meters < 1000.0 -> "${(meters / 50).toInt() * 50} m"
        else -> "%.1f km".format(meters / 1000.0)
    }

    /**
     * Pre-renders the whole route as a numbered list of strings. Useful
     * for the LLM tool reply (Step 8) and for snapshot tests.
     *
     * The first entry's distance comes from the start, not from a
     * preceding maneuver - its `distanceUntilHereM` is its own
     * `distanceToNextMeters` field interpreted as "distance from start".
     * BRouter usually emits a leading "C" (continue) hint at index 0
     * with the right value, but if it doesn't we fall back to 0.
     */
    fun formatAll(steps: List<TurnStep>): List<String> {
        if (steps.isEmpty()) return emptyList()
        val out = ArrayList<String>(steps.size)
        for ((i, step) in steps.withIndex()) {
            val distanceUntilHere = if (i == 0) step.distanceToNextMeters
                else steps[i - 1].distanceToNextMeters
            out += formatStep(step, distanceUntilHere)
        }
        return out
    }

    // --- Internals -----------------------------------------------------------

    /**
     * The "verb + object" half of an instruction (without distance lead).
     */
    private fun maneuverPhrase(step: TurnStep): String {
        val onto = ontoPhrase(step.streetName)
        return when (val c = step.command) {
            TurnCommand.Continue -> "continue$onto"
            TurnCommand.Straight -> "go straight$onto"
            TurnCommand.TurnLeft -> "turn left$onto"
            TurnCommand.TurnSlightLeft -> "bear slightly left$onto"
            TurnCommand.TurnSharpLeft -> "make a sharp left$onto"
            TurnCommand.TurnRight -> "turn right$onto"
            TurnCommand.TurnSlightRight -> "bear slightly right$onto"
            TurnCommand.TurnSharpRight -> "make a sharp right$onto"
            TurnCommand.KeepLeft -> "keep left$onto"
            TurnCommand.KeepRight -> "keep right$onto"
            TurnCommand.UTurnLeft -> "make a U-turn (left)$onto"
            TurnCommand.UTurnRight -> "make a U-turn (right)$onto"
            TurnCommand.Beeline -> "follow the path$onto"
            TurnCommand.Exit -> "take the exit$onto"
            TurnCommand.Arrive -> "arrive at your destination"
            TurnCommand.OffRoute -> "return to the route"
            is TurnCommand.Roundabout -> roundaboutPhrase(c, step.streetName)
            is TurnCommand.Unknown -> "follow the road$onto"
        }
    }

    private fun roundaboutPhrase(c: TurnCommand.Roundabout, streetName: String?): String {
        val ord = ordinalEnglish(c.exit)
        val onto = ontoPhrase(streetName)
        val direction = if (c.leftHanded) " (left-handed)" else ""
        return "at the roundabout$direction take the $ord exit$onto"
    }

    private fun ontoPhrase(streetName: String?): String =
        if (streetName.isNullOrBlank()) "" else " onto $streetName"

    private fun ordinalEnglish(n: Int): String = when {
        n in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }

    /**
     * Below this threshold we drop the "In X m," lead-in. 25 m matches
     * roughly one car-length of warning, which matches what Google Maps
     * does when announcing the imminent maneuver.
     */
    private const val ANNOUNCE_FLOOR_METERS = 25.0
}
