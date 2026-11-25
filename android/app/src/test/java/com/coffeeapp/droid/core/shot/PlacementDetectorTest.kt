// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.core.config.Vessel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement detector and vessel matching, replayed against weight traces.
 *
 * Expectations came from running the identical traces through the original
 * Python detector (archived outside the project). The vessel definitions are
 * the real ones in use, so the matching tests exercise the actual bands.
 */
class PlacementDetectorTest {

    private val dosingCup = Vessel("Dosing cup", 113.8, false, 8.0, 20.0, "")
    private val milkPitcher = Vessel("Milk pitcher", 140.2, false, 0.0, 0.0, "scale")
    private val coffeeCup = Vessel("Coffee cup", 189.7, true, 8.0, 60.0, "brewing")
    private val vessels = listOf(dosingCup, milkPitcher, coffeeCup)
    private val tolerance = 2.5

    private fun PlacementDetector.feedAll(
        trace: List<Pair<Double, Double>>,
    ): List<PlacementEvent> = trace.mapNotNull { (ts, w) -> feed(ts, w) }

    /** A settled plateau at [weight], long enough to seed the baseline. */
    private fun plateau(weight: Double, from: Double = 0.0, count: Int = 20) =
        (0 until count).map { (from + it * 0.1) to weight }

    // --- Detection -------------------------------------------------------

    @Test
    fun `the first plateau after a reset seeds the baseline without an event`() {
        // Otherwise every startup, and every re-seed after a shot, would fire
        // a phantom placement for weight that was already sitting there.
        val d = PlacementDetector(0.5, 1.2)
        assertTrue(d.feedAll(plateau(0.0)).isEmpty())
    }

    @Test
    fun `a cup placed quickly is a clean step`() {
        val d = PlacementDetector(0.5, 1.2)
        val trace = plateau(0.0, count = 21) +
            listOf(2.1 to 60.0, 2.2 to 150.0, 2.3 to 189.7) +
            listOf(2.4 to 189.7, 3.0 to 189.7, 3.6 to 189.7, 4.0 to 189.7)

        val events = d.feedAll(trace)
        assertEquals(1, events.size)
        val e = events.first()
        assertEquals(189.7, e.delta, 0.01)
        assertEquals(189.7, e.settled, 0.01)
        assertEquals(0.2, e.riseSeconds, 0.01)
        assertTrue("rate was ${e.rateGps}", e.rateGps > 900.0)
    }

    @Test
    fun `espresso flow can never look like a vessel landing`() {
        // THE safety property. An unarmed shot never leaves IDLE, so the
        // detector is live while weight climbs 0 to 36 g. What separates the
        // two gestures is rate, not size: this whole extraction averages
        // ~2 g/s against a floor of 20, two orders of magnitude below the
        // ~950 g/s of the placement above.
        val d = PlacementDetector(0.5, 1.2)
        val ramp = (1..180).map { (2.0 + it * 0.1) to (it * 0.2) }
        val settle = (1..30).map { (20.0 + it * 0.1) to 36.0 }
        val trace = plateau(0.0) + ramp + settle

        val events = d.feedAll(trace)
        // A single event does surface once the pour settles - and it is the
        // rate floor in ShotService, not the detector, that rejects it.
        assertEquals(1, events.size)
        val e = events.first()
        assertEquals(36.0, e.delta, 0.1)
        assertTrue(
            "flow read as ${e.rateGps} g/s, which is above the 20 g/s floor",
            e.rateGps < 20.0,
        )
    }

    @Test
    fun `a slow deliberate placement still clears the rate floor`() {
        // The v1.6.0 regression. The old guard capped the transition DURATION
        // at 0.5 s; measured on hardware, placing the cup took 0.35 s but
        // lifting it took 0.51 s and was rejected - a cliff aimed at
        // something else entirely. Rate is the quantity that actually
        // separates a placement from a pour.
        val d = PlacementDetector(0.5, 1.2)
        val trace = plateau(0.0) +
            listOf(2.1 to 40.0, 2.2 to 90.0, 2.3 to 140.0, 2.4 to 175.0, 2.5 to 188.0, 2.61 to 189.7) +
            listOf(2.7 to 189.7, 3.3 to 189.7, 3.9 to 189.7, 4.2 to 189.7)

        val events = d.feedAll(trace)
        assertEquals(1, events.size)
        val e = events.first()
        assertEquals(0.51, e.riseSeconds, 0.01)
        assertTrue("rate was ${e.rateGps}", e.rateGps > 20.0)
    }

    @Test
    fun `a tare is absorbed and the cup placed straight after is still a clean step`() {
        // Why expectTare watches for the zero instead of going blind for a
        // fixed window: the natural gesture after RESET is to put the cup
        // down immediately, and a placement inside a blind window silently
        // became the new baseline - the cup landed, nothing was detected, and
        // auto-arm never fired.
        val d = PlacementDetector(0.5, 1.2)
        d.feedAll(listOf(0.0 to 189.7, 0.5 to 189.7, 1.0 to 189.7, 1.5 to 189.7, 2.0 to 189.7))
        d.expectTare(2.0, 2.0)

        val trace = listOf(2.1 to 189.7, 2.2 to 189.7, 2.3 to 0.0) + // the tare lands
            listOf(2.4 to 0.0, 2.5 to 80.0, 2.6 to 189.7) + // cup placed at once
            listOf(2.7 to 189.7, 3.3 to 189.7, 3.9 to 189.7, 4.2 to 189.7)

        val events = d.feedAll(trace)
        assertEquals(1, events.size)
        // The tare drop itself is NOT reported as a vessel removal...
        assertTrue(events.first().delta > 0)
        // ...and the placement after it is a full-size step from a known zero.
        assertEquals(189.7, events.first().delta, 0.01)
    }

    @Test
    fun `a lifted vessel reports a negative step`() {
        val d = PlacementDetector(0.5, 1.2)
        val trace = plateau(189.7, count = 21) +
            listOf(2.1 to 120.0, 2.2 to 0.0) +
            listOf(2.3 to 0.0, 2.9 to 0.0, 3.5 to 0.0, 3.9 to 0.0)

        val events = d.feedAll(trace)
        assertEquals(1, events.size)
        assertEquals(-189.7, events.first().delta, 0.01)
    }

    @Test
    fun `reset forgets the baseline so nothing surfaces against a stale one`() {
        // Called whenever we stop observing - during a shot, or when the
        // feature is switched off - so the cup being lifted meanwhile cannot
        // reappear later as a phantom step.
        val d = PlacementDetector(0.5, 1.2)
        d.feedAll(plateau(0.0))
        d.reset()
        val events = d.feedAll(plateau(189.7, from = 5.0))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `transitSeconds reports movement that never settles`() {
        val d = PlacementDetector(0.5, 1.2)
        d.feedAll(plateau(0.0))
        assertEquals(0.0, d.transitSeconds(2.0), 1e-9)

        d.feed(2.1, 50.0) // departs the plateau
        assertTrue(d.transitSeconds(4.1) > 1.9)
    }

    // --- Vessel matching -------------------------------------------------

    @Test
    fun `a bare coffee cup matches empty and arms`() {
        val m = matchVessel(189.7, vessels, tolerance)!!
        assertEquals("Coffee cup", m.name)
        assertEquals(VesselMatch.Kind.EMPTY, m.kind)
        assertTrue(m.arm)
        assertEquals("brewing", m.switchToPage)
    }

    @Test
    fun `a slightly wet cup still matches within tolerance`() {
        val m = matchVessel(191.0, vessels, tolerance)!!
        assertEquals("Coffee cup", m.name)
        assertEquals(VesselMatch.Kind.EMPTY, m.kind)
    }

    @Test
    fun `a dosing cup with a dose matches loaded and reports net contents`() {
        val m = matchVessel(131.8, vessels, tolerance)!!
        assertEquals("Dosing cup", m.name)
        assertEquals(VesselMatch.Kind.CONTENTS, m.kind)
        assertEquals(18.0, m.contentsGrams, 0.01)
        // A loaded vessel is never an arming gesture: you are weighing, not
        // about to catch a shot. And it must not be tared, or we would zero
        // the very dose the user put it there to read.
        assertTrue(!m.arm)
    }

    @Test
    fun `an arm vessel is matched empty-only so a strange mug is not mis-read`() {
        // 205 g falls inside the coffee cup loaded band (197.7 to 252.2), but
        // that band is tens of grams wide and would swallow any unfamiliar
        // mug and report a confident, wrong net weight. No match is the right
        // answer: an unrecognised vessel gets tared by hand.
        assertNull(matchVessel(205.0, vessels, tolerance))
    }

    @Test
    fun `a vessel opted out of loaded matching cannot shadow another`() {
        // The milk pitcher ships maxContentsGrams = 0 precisely so its loaded
        // band cannot swallow placements meant for the dosing cup.
        val m = matchVessel(140.2, vessels, tolerance)!!
        assertEquals("Milk pitcher", m.name)
        assertEquals(VesselMatch.Kind.EMPTY, m.kind)
        assertNull(matchVessel(160.0, vessels, tolerance))
    }

    @Test
    fun `an unknown weight and a negative step match nothing`() {
        assertNull(matchVessel(300.0, vessels, tolerance))
        assertNull(matchVessel(-5.0, vessels, tolerance))
    }

    // --- Band overlap warnings -------------------------------------------

    @Test
    fun `the shipped vessel set has no overlapping bands`() {
        assertTrue(findBandOverlaps(vessels, tolerance).isEmpty())
    }

    @Test
    fun `widening the dosing cup band collides with the milk pitcher`() {
        // With the 30 g default rather than the tuned 20 g, the dosing cup
        // loaded band (111.3 to 146.3) swallows the pitcher (137.7 to 142.7),
        // and a placement in the overlap yields a plausible-looking wrong
        // number rather than a no-op. This is what the save-time warning is
        // for.
        val widened = listOf(dosingCup.copy(maxContentsGrams = 30.0), milkPitcher, coffeeCup)
        val warnings = findBandOverlaps(widened, tolerance)
        assertEquals(1, warnings.size)
        assertTrue(warnings.first().contains("Dosing cup"))
        assertTrue(warnings.first().contains("Milk pitcher"))
    }

    @Test
    fun `a min contents below the tolerance is warned about`() {
        val sloppy = listOf(dosingCup.copy(minContentsGrams = 1.0))
        val warnings = findBandOverlaps(sloppy, tolerance)
        assertEquals(1, warnings.size)
        assertTrue(warnings.first().contains("indistinguishable"))
    }
}
