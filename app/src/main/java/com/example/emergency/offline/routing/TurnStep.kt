package com.example.emergency.offline.routing

import com.mapbox.mapboxsdk.geometry.LatLng

/**
 * One maneuver in a routing result. Plan section 8 step 7 shape:
 * `{location, command, distanceToNextMeters, streetName, indexInPolyline}`.
 *
 *   * [location] - where on the map the user actually performs the action.
 *   * [command] - the maneuver category (turn, keep, roundabout exit...).
 *   * [distanceToNextMeters] - distance from this maneuver to the next, or
 *     to the destination if this is the last step. The active-navigation
 *     UI subtracts the user's progress from this to show "In 200 m, ...";
 *     pre-rendered step lists use it directly as "after the turn, walk
 *     200 m before the next instruction".
 *   * [streetName] - name of the road the user moves onto after this
 *     maneuver. May be null if OSM has no `name` for the way (common in
 *     residential closes / unpaved tracks); the formatter falls back to
 *     "the road" in that case.
 *   * [indexInPolyline] - index into [com.example.emergency.offline.OfflineRouter.Result.polyline].
 *     Lets navigation code (Step 7.5) snap GPS to the polyline and figure
 *     out which TurnStep is currently active.
 */
data class TurnStep(
    val location: LatLng,
    val command: TurnCommand,
    val distanceToNextMeters: Double,
    val streetName: String?,
    val indexInPolyline: Int,
)

/**
 * Maneuver category. Mirrors BRouter's voice-hint command alphabet (see
 * `btools.router.VoiceHint.getCommandString()`):
 *
 * | BRouter code | TurnCommand          | Meaning                             |
 * |--------------|----------------------|-------------------------------------|
 * | C            | CONTINUE             | go straight, no decision             |
 * | TLU          | U_TURN_LEFT          | u-turn (left-handed)                 |
 * | TSHL         | TURN_SHARP_LEFT      | sharp left (>120 deg)                   |
 * | TL           | TURN_LEFT            | regular left (~90 deg)                  |
 * | TSLL         | TURN_SLIGHT_LEFT     | slight left (~30 deg)                   |
 * | TSTR         | STRAIGHT             | go straight at junction               |
 * | TSLR         | TURN_SLIGHT_RIGHT    | slight right                         |
 * | TR           | TURN_RIGHT           | regular right                        |
 * | TSHR         | TURN_SHARP_RIGHT     | sharp right                          |
 * | TRU          | U_TURN_RIGHT         | u-turn (right-handed)                |
 * | KL           | KEEP_LEFT            | bear left at fork                    |
 * | KR           | KEEP_RIGHT           | bear right at fork                   |
 * | RNDBn        | ROUNDABOUT(exit=n)   | take the n-th exit                   |
 * | RNLBn        | ROUNDABOUT_LH(exit=n)| left-handed roundabout (UK/IE/AU)    |
 * | BL           | BEELINE              | off-graph segment (e.g. through park) |
 * | OFFR         | OFF_ROUTE            | snap-to-route hint (rare in offline) |
 * | EXIT         | EXIT                 | motorway / ramp exit                  |
 * | END          | ARRIVE               | route end                            |
 *
 * Unknown codes coming out of a future BRouter version map to [UNKNOWN]
 * so the picker / nav UI never shows a raw "TLU" string to the user.
 */
sealed class TurnCommand {
    data object Continue : TurnCommand()
    data object Straight : TurnCommand()
    data object TurnLeft : TurnCommand()
    data object TurnSlightLeft : TurnCommand()
    data object TurnSharpLeft : TurnCommand()
    data object TurnRight : TurnCommand()
    data object TurnSlightRight : TurnCommand()
    data object TurnSharpRight : TurnCommand()
    data object KeepLeft : TurnCommand()
    data object KeepRight : TurnCommand()
    data object UTurnLeft : TurnCommand()
    data object UTurnRight : TurnCommand()
    data class Roundabout(val exit: Int, val leftHanded: Boolean = false) : TurnCommand()
    data object Beeline : TurnCommand()
    data object OffRoute : TurnCommand()
    data object Exit : TurnCommand()
    data object Arrive : TurnCommand()
    data class Unknown(val code: String) : TurnCommand()

    companion object {
        /**
         * Parses BRouter's `getCommandString()` output. The roundabout
         * codes carry the exit number as a numeric suffix ("RNDB3" -> take
         * 3rd exit). Unknown codes return [Unknown] so the formatter can
         * decide whether to surface "(unknown maneuver)" or skip.
         */
        fun parse(raw: String): TurnCommand {
            // Strip trailing whitespace and normalise case (BRouter emits
            // upper, but we don't want to break on a stray lowercase).
            val s = raw.trim()
            if (s.isEmpty()) return Continue
            // RNDBn / RNLBn - variable suffix.
            if (s.startsWith("RNDB")) {
                val exit = s.substring(4).toIntOrNull() ?: 1
                return Roundabout(exit = exit, leftHanded = false)
            }
            if (s.startsWith("RNLB")) {
                val exit = s.substring(4).toIntOrNull() ?: 1
                return Roundabout(exit = exit, leftHanded = true)
            }
            return when (s.uppercase()) {
                "C"     -> Continue
                "TSTR"  -> Straight
                "TL"    -> TurnLeft
                "TSLL"  -> TurnSlightLeft
                "TSHL"  -> TurnSharpLeft
                "TR"    -> TurnRight
                "TSLR"  -> TurnSlightRight
                "TSHR"  -> TurnSharpRight
                "KL"    -> KeepLeft
                "KR"    -> KeepRight
                "TLU"   -> UTurnLeft
                "TRU"   -> UTurnRight
                "BL"    -> Beeline
                "OFFR"  -> OffRoute
                "EXIT"  -> Exit
                "END"   -> Arrive
                else    -> Unknown(s)
            }
        }
    }
}
