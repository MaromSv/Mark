package com.example.emergency.offline.routing

import android.util.Log
import com.mapbox.mapboxsdk.geometry.LatLng
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pulls turn-by-turn instructions out of a BRouter
 * `btools.router.OsmTrack`. Plan section 8 step 7.
 *
 * BRouter's voice-hint API surface has shifted between versions (private
 * fields -> getters; renames; new commands), so this parser is **purely
 * reflective** - it never imports `btools.*`. That keeps the build
 * independent of which BRouter jar version is on the classpath, and
 * means a future BRouter rev that adds a new command merely shows up as
 * `TurnCommand.Unknown` instead of breaking the build.
 *
 * Returns an empty list on any structural surprise (missing field,
 * missing method, type mismatch). Routing keeps working - the user just
 * doesn't get turn instructions, which the navigation UI handles
 * gracefully (Step 7.5 falls back to "follow the polyline").
 */
object VoiceHintParser {

    private const val TAG = "VoiceHintParser"

    /**
     * Walks the voice-hint list on [track] and produces one [TurnStep]
     * per maneuver. [polyline] must be the same node sequence the
     * router returned, so `indexInPolyline` lookups line up.
     *
     * Distance-to-next is read from BRouter when it exposes a numeric
     * field; otherwise we sum haversine edges between this hint's
     * `indexInTrack` and the next hint's, which gives the same answer.
     */
    fun parse(track: Any?, polyline: List<LatLng>): List<TurnStep> {
        if (track == null) return emptyList()
        if (polyline.size < 2) return emptyList()
        val hints = try {
            extractHintList(track) ?: return emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read voiceHints from track", t)
            return emptyList()
        }
        if (hints.isEmpty()) return emptyList()

        val steps = mutableListOf<TurnStep>()
        for ((i, hint) in hints.withIndex()) {
            val cmdString = readCommandString(hint) ?: continue
            val command = TurnCommand.parse(cmdString)
            val idx = readIndexInTrack(hint).coerceIn(0, polyline.lastIndex)
            val location = polyline[idx]
            val streetName = readStreetName(hint)
            val distanceToNext = readDistanceToNext(hint)
                ?: distanceBetweenIndices(
                    polyline,
                    fromIdx = idx,
                    toIdx = if (i + 1 < hints.size) {
                        readIndexInTrack(hints[i + 1]).coerceIn(0, polyline.lastIndex)
                    } else {
                        polyline.lastIndex
                    },
                )
            steps += TurnStep(
                location = location,
                command = command,
                distanceToNextMeters = distanceToNext,
                streetName = streetName,
                indexInPolyline = idx,
            )
        }
        return steps
    }

    // --- Reflective accessors ------------------------------------------------

    /**
     * BRouter's `OsmTrack.voiceHints` is a `VoiceHintList` (which has a
     * public `list: ArrayList<VoiceHint>` field). We accept either a
     * direct `List<*>` or a wrapper with a `list` / `getList()` member.
     */
    private fun extractHintList(track: Any): List<Any>? {
        val raw = readMember(track, listOf("voiceHints", "getVoiceHints")) ?: return null
        val wrappedList = when (raw) {
            is List<*> -> raw
            else -> readMember(raw, listOf("list", "getList")) as? List<*>
                ?: return null
        }
        return wrappedList.filterNotNull()
    }

    private fun readCommandString(hint: Any): String? {
        // Prefer the string getter (returns "TL"/"RNDB3"/etc).
        readMember(hint, listOf("getCommandString", "commandString"))
            ?.let { return it.toString() }
        // Fall back to the numeric command + roundaboutExit pair.
        val cmdInt = (readMember(hint, listOf("getCommand", "cmd", "commandNumber")) as? Number)
            ?.toInt() ?: return null
        val rb = (readMember(hint, listOf("getRoundaboutExit", "roundaboutExit")) as? Number)?.toInt()
        return COMMAND_INT_TO_STRING[cmdInt]?.let { code ->
            if (code.startsWith("RND") && rb != null) "$code$rb" else code
        }
    }

    private fun readIndexInTrack(hint: Any): Int =
        (readMember(hint, listOf("indexInTrack", "getIndexInTrack")) as? Number)?.toInt() ?: 0

    private fun readDistanceToNext(hint: Any): Double? =
        (readMember(hint, listOf(
            "distanceToNext", "getDistanceToNext",
            "distanceToNextHint", "distanceToHere",
        )) as? Number)?.toDouble()

    private fun readStreetName(hint: Any): String? {
        // BRouter attaches incoming/outgoing way info as `oldWay` / `goodWay`,
        // each a `MessageData` with `streetname` (and sometimes `linkdescription`).
        val way = readMember(hint, listOf("goodWay", "getGoodWay"))
            ?: readMember(hint, listOf("oldWay", "getOldWay"))
            ?: return null
        val name = (readMember(way, listOf("streetname", "getStreetname", "name", "getName")) as? String)
            ?.trim()
        return name?.takeIf { it.isNotEmpty() }
    }

    /**
     * Try a sequence of candidate field/method names and return the first
     * match. Field access wins over getters because BRouter exposes a lot
     * of public final fields directly.
     */
    private fun readMember(receiver: Any, names: List<String>): Any? {
        val cls = receiver.javaClass
        for (name in names) {
            // Try field
            try {
                val f = cls.getField(name)
                return f.get(receiver)
            } catch (_: NoSuchFieldException) {
                // fall through to method lookup
            }
            try {
                val m = cls.getMethod(name)
                return m.invoke(receiver)
            } catch (_: NoSuchMethodException) {
                // try next candidate
            }
        }
        return null
    }

    // BRouter command int -> string code, roughly stable across 1.7.x.
    // Used only when the string getter is missing in older jars.
    private val COMMAND_INT_TO_STRING = mapOf(
        1 to "C",
        2 to "TLU",
        3 to "TSHL",
        4 to "TL",
        5 to "TSLL",
        6 to "KL",
        7 to "BL",
        8 to "KR",
        9 to "TSLR",
        10 to "TR",
        11 to "TSHR",
        12 to "TRU",
        13 to "RNDB",
        14 to "RNLB",
        15 to "EXIT",
        16 to "OFFR",
        17 to "END",
    )

    // --- Geo helpers ---------------------------------------------------------

    /**
     * Sum of haversine edge lengths between two indices on [polyline].
     * Returns 0.0 when the indices are equal or out of range - used as
     * the distance-to-next fallback when BRouter doesn't carry the field.
     */
    private fun distanceBetweenIndices(polyline: List<LatLng>, fromIdx: Int, toIdx: Int): Double {
        if (fromIdx == toIdx) return 0.0
        val lo = minOf(fromIdx, toIdx).coerceAtLeast(0)
        val hi = maxOf(fromIdx, toIdx).coerceAtMost(polyline.lastIndex)
        var total = 0.0
        for (i in lo until hi) {
            total += haversineMeters(polyline[i], polyline[i + 1])
        }
        return total
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = (b.latitude - a.latitude) * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
