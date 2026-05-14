package com.example.emergency.offline.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCommandTest {

    @Test
    fun parsesEverySimpleCode() {
        assertEquals(TurnCommand.Continue, TurnCommand.parse("C"))
        assertEquals(TurnCommand.Straight, TurnCommand.parse("TSTR"))
        assertEquals(TurnCommand.TurnLeft, TurnCommand.parse("TL"))
        assertEquals(TurnCommand.TurnSlightLeft, TurnCommand.parse("TSLL"))
        assertEquals(TurnCommand.TurnSharpLeft, TurnCommand.parse("TSHL"))
        assertEquals(TurnCommand.TurnRight, TurnCommand.parse("TR"))
        assertEquals(TurnCommand.TurnSlightRight, TurnCommand.parse("TSLR"))
        assertEquals(TurnCommand.TurnSharpRight, TurnCommand.parse("TSHR"))
        assertEquals(TurnCommand.KeepLeft, TurnCommand.parse("KL"))
        assertEquals(TurnCommand.KeepRight, TurnCommand.parse("KR"))
        assertEquals(TurnCommand.UTurnLeft, TurnCommand.parse("TLU"))
        assertEquals(TurnCommand.UTurnRight, TurnCommand.parse("TRU"))
        assertEquals(TurnCommand.Beeline, TurnCommand.parse("BL"))
        assertEquals(TurnCommand.OffRoute, TurnCommand.parse("OFFR"))
        assertEquals(TurnCommand.Exit, TurnCommand.parse("EXIT"))
        assertEquals(TurnCommand.Arrive, TurnCommand.parse("END"))
    }

    @Test
    fun parsesRoundaboutsWithExitNumbers() {
        assertEquals(TurnCommand.Roundabout(exit = 1), TurnCommand.parse("RNDB1"))
        assertEquals(TurnCommand.Roundabout(exit = 3), TurnCommand.parse("RNDB3"))
        assertEquals(TurnCommand.Roundabout(exit = 8, leftHanded = false), TurnCommand.parse("RNDB8"))
        assertEquals(TurnCommand.Roundabout(exit = 2, leftHanded = true), TurnCommand.parse("RNLB2"))
    }

    @Test
    fun unknownCodesAreCarriedNotDropped() {
        val result = TurnCommand.parse("XYZ")
        assertTrue("expected Unknown for XYZ", result is TurnCommand.Unknown)
        assertEquals("XYZ", (result as TurnCommand.Unknown).code)
    }

    @Test
    fun emptyOrWhitespaceFallsToContinue() {
        assertEquals(TurnCommand.Continue, TurnCommand.parse(""))
        assertEquals(TurnCommand.Continue, TurnCommand.parse("   "))
    }

    @Test
    fun roundaboutMissingExitDefaultsTo1() {
        assertEquals(TurnCommand.Roundabout(exit = 1), TurnCommand.parse("RNDB"))
    }
}
