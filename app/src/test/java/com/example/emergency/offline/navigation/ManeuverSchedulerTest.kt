package com.example.emergency.offline.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverSchedulerTest {

    @Test
    fun walkingFiresEightyMeterWarningAndImminent() {
        val s = ManeuverScheduler(NavigationProfile.Walking)
        // 200m -> above the ladder; nothing fires.
        assertNull(s.tick(currentStepIndex = 0, distanceToStepM = 200.0))
        // 90m -> still above 80m threshold; nothing.
        assertNull(s.tick(0, 90.0))
        // 70m -> crosses 80m; fires At(80m).
        val first = s.tick(0, 70.0)
        assertNotNull(first)
        assertEquals(ManeuverScheduler.Trigger.At(80.0), first!!.trigger)
        // 40m -> above NOW threshold (25m); nothing fires (80m already used).
        assertNull(s.tick(0, 40.0))
        // 20m -> crosses NOW.
        val second = s.tick(0, 20.0)
        assertNotNull(second)
        assertEquals(ManeuverScheduler.Trigger.Now, second!!.trigger)
        // Re-tick at NOW distance: nothing - already fired.
        assertNull(s.tick(0, 5.0))
    }

    @Test
    fun drivingFiresFourTriggersInOrder() {
        val s = ManeuverScheduler(NavigationProfile.Driving)
        assertEquals(
            ManeuverScheduler.Trigger.At(500.0),
            s.tick(0, 480.0)!!.trigger,
        )
        assertEquals(
            ManeuverScheduler.Trigger.At(200.0),
            s.tick(0, 180.0)!!.trigger,
        )
        assertEquals(
            ManeuverScheduler.Trigger.At(50.0),
            s.tick(0, 40.0)!!.trigger,
        )
        assertEquals(
            ManeuverScheduler.Trigger.Now,
            s.tick(0, 10.0)!!.trigger,
        )
        assertNull(s.tick(0, 5.0))
    }

    @Test
    fun stepAdvanceResetsLadder() {
        val s = ManeuverScheduler(NavigationProfile.Walking)
        s.tick(0, 70.0)  // fires At(80)
        s.tick(0, 20.0)  // fires Now
        // Step 1 starts fresh.
        val firstOnNewStep = s.tick(currentStepIndex = 1, distanceToStepM = 70.0)
        assertNotNull(firstOnNewStep)
        assertEquals(ManeuverScheduler.Trigger.At(80.0), firstOnNewStep!!.trigger)
    }

    @Test
    fun reportsInnermostTriggerOnSparseTicks() {
        val s = ManeuverScheduler(NavigationProfile.Driving)
        // Single tick where the user blew past 500/200/50/Now.
        val fired = s.tick(0, 10.0)
        assertNotNull(fired)
        assertEquals(ManeuverScheduler.Trigger.Now, fired!!.trigger)
    }

    @Test
    fun negativeStepIndexNoOps() {
        val s = ManeuverScheduler(NavigationProfile.Walking)
        assertNull(s.tick(currentStepIndex = -1, distanceToStepM = 50.0))
    }

    @Test
    fun resetClearsState() {
        val s = ManeuverScheduler(NavigationProfile.Walking)
        s.tick(0, 70.0) // fires
        s.reset()
        val fired = s.tick(0, 70.0)
        assertNotNull(fired)
    }
}
