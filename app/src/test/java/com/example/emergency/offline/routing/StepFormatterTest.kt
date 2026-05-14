package com.example.emergency.offline.routing

import com.mapbox.mapboxsdk.geometry.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

class StepFormatterTest {

    private fun step(command: TurnCommand, street: String? = null, distNext: Double = 0.0) =
        TurnStep(
            location = LatLng(52.0, 5.0),
            command = command,
            distanceToNextMeters = distNext,
            streetName = street,
            indexInPolyline = 0,
        )

    // --- formatDistance buckets ----------------------------------------------

    @Test
    fun distanceBucketsRoundCorrectly() {
        // <100m -> 10m bucket
        assertEquals("0 m", StepFormatter.formatDistance(0.0))
        assertEquals("50 m", StepFormatter.formatDistance(50.0))
        assertEquals("90 m", StepFormatter.formatDistance(99.9))
        // <1000m -> 50m bucket
        assertEquals("400 m", StepFormatter.formatDistance(420.0))
        assertEquals("950 m", StepFormatter.formatDistance(950.0))
        // >=1000m -> 0.1km
        assertEquals("1.0 km", StepFormatter.formatDistance(1000.0))
        assertEquals("1.2 km", StepFormatter.formatDistance(1234.0))
        assertEquals("12.3 km", StepFormatter.formatDistance(12_345.0))
    }

    // --- per-command rendering -----------------------------------------------

    @Test
    fun turnLeftWithStreetNameAndDistance() {
        val s = step(TurnCommand.TurnLeft, street = "Hoofdstraat")
        assertEquals(
            "In 200 m, turn left onto Hoofdstraat",
            StepFormatter.formatStep(s, distanceUntilHereM = 200.0),
        )
    }

    @Test
    fun turnLeftWithoutStreetNameDropsConnector() {
        val s = step(TurnCommand.TurnLeft, street = null)
        assertEquals(
            "In 50 m, turn left",
            StepFormatter.formatStep(s, distanceUntilHereM = 50.0),
        )
    }

    @Test
    fun nearImminentManeuverDropsLeadIn() {
        val s = step(TurnCommand.TurnRight, street = "Damrak")
        assertEquals(
            "Turn right onto Damrak",
            StepFormatter.formatStep(s, distanceUntilHereM = 5.0),
        )
    }

    @Test
    fun roundaboutSpellsOutOrdinal() {
        val s = step(TurnCommand.Roundabout(exit = 3), street = "Stadsring")
        assertEquals(
            "In 100 m, at the roundabout take the 3rd exit onto Stadsring",
            StepFormatter.formatStep(s, distanceUntilHereM = 100.0),
        )
    }

    @Test
    fun leftHandedRoundaboutAnnotated() {
        val s = step(TurnCommand.Roundabout(exit = 2, leftHanded = true))
        assertEquals(
            "In 200 m, at the roundabout (left-handed) take the 2nd exit",
            StepFormatter.formatStep(s, distanceUntilHereM = 200.0),
        )
    }

    @Test
    fun arrivalAlwaysReadsArrived() {
        val s = step(TurnCommand.Arrive)
        assertEquals("You have arrived", StepFormatter.formatStep(s, 0.0))
        assertEquals("You have arrived", StepFormatter.formatStep(s, 500.0))
    }

    @Test
    fun ordinalEnglishHandlesTeens() {
        val eleven = step(TurnCommand.Roundabout(exit = 11))
        val twelve = step(TurnCommand.Roundabout(exit = 12))
        val twentyFirst = step(TurnCommand.Roundabout(exit = 21))
        assertEquals(
            "In 100 m, at the roundabout take the 11th exit",
            StepFormatter.formatStep(eleven, 100.0),
        )
        assertEquals(
            "In 100 m, at the roundabout take the 12th exit",
            StepFormatter.formatStep(twelve, 100.0),
        )
        assertEquals(
            "In 100 m, at the roundabout take the 21st exit",
            StepFormatter.formatStep(twentyFirst, 100.0),
        )
    }

    @Test
    fun unknownCommandRendersGeneric() {
        val s = step(TurnCommand.Unknown("XYZ"), street = "Damrak")
        assertEquals(
            "In 50 m, follow the road onto Damrak",
            StepFormatter.formatStep(s, distanceUntilHereM = 50.0),
        )
    }

    // --- formatAll over a synthetic route ------------------------------------

    @Test
    fun formatAllChainsDistancesCorrectly() {
        val steps = listOf(
            step(TurnCommand.Continue, street = "Stationsplein", distNext = 80.0),
            step(TurnCommand.TurnLeft, street = "Damrak", distNext = 320.0),
            step(TurnCommand.TurnRight, street = "Dam", distNext = 0.0),
            step(TurnCommand.Arrive),
        )
        val rendered = StepFormatter.formatAll(steps)
        assertEquals(
            listOf(
                "In 80 m, continue onto Stationsplein",
                "In 80 m, turn left onto Damrak",
                "In 300 m, turn right onto Dam",
                "You have arrived",
            ),
            rendered,
        )
    }
}
