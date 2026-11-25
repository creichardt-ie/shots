// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.core.model.ShotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shot state machine, replayed against synthetic weight traces.
 *
 * Every expectation here was produced by running the same trace through the
 * original Python state machine (archived outside the project), so these lock
 * the behaviour rather than anyone's reading of it.
 *
 * Several tests are named for the incident they exist to prevent. This is the
 * code path that cuts mains power to a live espresso machine; a regression
 * here is not a cosmetic bug.
 */
class ShotStateMachineTest {

    private val thresholds = Thresholds(
        startThresholdGrams = 0.2,
        maxStartWeightGrams = 10.0,
        maxFlowRateGps = 15.0,
        flowDetectionThreshold = 0.05,
        finishStabilitySeconds = 1.0,
        ignoreStabilityFirstSeconds = 7.0,
        minSaveDurationSeconds = 5.0,
        brewByWeightEnabled = true,
        // A learned offset from real use, not the 4.0 default.
        brewOffsetGrams = 6.2,
    )

    private class Run(val ctx: ShotContext, val actions: List<Pair<Double, Action>>)

    private fun run(
        trace: List<Pair<Double, Double>>,
        armed: Boolean = true,
        target: Double = 36.0,
        cfg: Thresholds = thresholds,
    ): Run {
        val ctx = ShotContext().apply { this.armed = armed }
        val fired = mutableListOf<Pair<Double, Action>>()
        for ((ts, w) in trace) {
            step(ctx, Sample(ts, w), cfg, target).forEach { fired.add(ts to it) }
        }
        return Run(ctx, fired)
    }

    /** Steady ramp: one sample every 100 ms, [gainPerSample] grams each. */
    private fun ramp(
        from: Double,
        samples: Int,
        gainPerSample: Double,
        startTs: Double = 0.1,
    ): List<Pair<Double, Double>> = (1..samples).map { i ->
        (startTs + i * 0.1) to (from + i * gainPerSample)
    }

    // --- Arming ----------------------------------------------------------

    @Test
    fun `an unarmed scale never starts a shot`() {
        // The whole point of the arm gate: a cup set down, or a portafilter
        // knocked against the scale, must not start a shot or fire a stop.
        val r = run(
            listOf(0.0 to 0.0, 0.1 to 0.5, 0.2 to 5.0, 0.3 to 12.0, 1.0 to 20.0),
            armed = false,
        )
        assertEquals(ShotState.IDLE, r.ctx.state)
        assertTrue(r.actions.isEmpty())
    }

    @Test
    fun `an armed scale starts on first flow and asks for the machine time`() {
        val r = run(listOf(0.0 to 0.0, 0.1 to 0.5, 0.2 to 1.2))
        assertEquals(ShotState.BREWING, r.ctx.state)
        assertEquals(listOf(0.1 to Action.FETCH_MACHINE_START), r.actions)
    }

    @Test
    fun `a 190g cup landing on an armed scale does not start a shot`() {
        // The 2026-08-30 failure. Auto-arm made arming routine, so an armed
        // scale plus a cup landing entered BREWING already past target-offset
        // and fired a BBW stop - power-cycling the machine - on the next
        // sample. maxStartWeightGrams is the ceiling that stops it.
        val r = run(
            listOf(0.0 to 0.0, 0.1 to 189.7, 0.2 to 189.7, 0.3 to 189.8),
        )
        assertEquals(ShotState.IDLE, r.ctx.state)
        assertTrue(r.actions.isEmpty())
        // Crucially the arm survives: the user still gets their next shot.
        assertTrue(r.ctx.armed)
    }

    // --- Brew by weight --------------------------------------------------

    @Test
    fun `brew by weight fires exactly once at target minus offset`() {
        // target 36.0 - offset 6.2 = stop at the first sample >= 29.8.
        val r = run(listOf(0.0 to 0.0, 0.1 to 0.3) + ramp(0.3, 180, 0.22))
        val stops = r.actions.filter { it.second == Action.SEND_STOP }
        assertEquals(1, stops.size)
        assertTrue(r.ctx.stopSent)
        assertEquals(ShotState.STOPPING, r.ctx.state)
        val stopWeight = r.ctx.weightAtStop!!
        assertTrue("stopped at $stopWeight, expected just past 29.8", stopWeight in 29.8..30.1)
        assertEquals(6.2, r.ctx.offsetUsedGrams!!, 1e-9)
    }

    @Test
    fun `brew by weight does not fire when disabled`() {
        val r = run(
            listOf(0.0 to 0.0, 0.1 to 0.3) + ramp(0.3, 180, 0.22),
            cfg = thresholds.copy(brewByWeightEnabled = false),
        )
        assertTrue(r.actions.none { it.second == Action.SEND_STOP })
        assertFalse(r.ctx.stopSent)
        assertNull(r.ctx.weightAtStop)
    }

    @Test
    fun `brew by weight does not fire against a zero target`() {
        // A target of 0 would otherwise satisfy weight >= 0 - offset on the
        // very first sample and stop the machine instantly.
        val r = run(listOf(0.0 to 0.0, 0.1 to 0.3) + ramp(0.3, 60, 0.22), target = 0.0)
        assertTrue(r.actions.none { it.second == Action.SEND_STOP })
    }

    // --- Implausible steps -----------------------------------------------

    @Test
    fun `a cup landing during the grace period abandons the shot but keeps the arm`() {
        // A step no brew rate can produce. Inside the opening grace period the
        // shot is written off entirely - it never was one. Nothing was sent to
        // the machine, so the arm is deliberately kept.
        val r = run(
            listOf(0.0 to 0.0, 0.1 to 0.5, 0.2 to 1.0, 0.3 to 190.0, 0.4 to 190.1),
        )
        assertEquals(
            listOf(0.3 to Action.ABANDON),
            r.actions.filter { it.second == Action.ABANDON },
        )
        assertEquals(ShotState.IDLE, r.ctx.state)
        assertTrue(r.ctx.armed)
        assertNull(r.ctx.startTime)
    }

    @Test
    fun `a vessel lifted late in a shot costs one sample, not the shot`() {
        // The -140.1 g incident: a stale arm plus the milk pitcher coming off
        // the scale once logged a 5-second shot with a negative yield. Past
        // the grace period the implausible sample is dropped and the shot
        // keeps the weight it really reached, so that is what gets saved.
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) +
            ramp(0.5, 120, 0.3) +
            listOf(12.3 to -103.9, 12.4 to -103.9)
        val r = run(trace)

        // The reading it kept is the pre-lift weight, not the negative one.
        assertTrue(
            "kept ${r.ctx.currentWeight}, expected the pre-lift weight",
            r.ctx.currentWeight > 30.0,
        )
        assertTrue(r.ctx.state == ShotState.STOPPING || r.ctx.state == ShotState.FINALIZED)
    }

    @Test
    fun `a slow large gain is flow, not a step`() {
        // The guard is a conjunction: over 5 g AND faster than maxFlowRateGps.
        // A 6 g gain spread over a full second is 6 g/s - implausible as a
        // single jump, entirely plausible as a stalled-link catch-up - and
        // must not abandon a real shot.
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5, 1.1 to 6.5, 1.2 to 6.8)
        val r = run(trace)
        assertTrue(r.actions.none { it.second == Action.ABANDON })
        assertEquals(ShotState.BREWING, r.ctx.state)
    }

    // --- Finishing -------------------------------------------------------

    @Test
    fun `a shot finalizes once the weight settles past the grace period`() {
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) +
            ramp(0.5, 71, 0.5) +
            (1..30).map { (7.3 + it * 0.1) to 36.0 }
        val r = run(trace)
        assertEquals(1, r.actions.count { it.second == Action.FINALIZE })
        assertEquals(ShotState.FINALIZED, r.ctx.state)
    }

    @Test
    fun `a settled weight inside the grace period does not finalize`() {
        // ignoreStabilityFirstSeconds exists because the first seconds of a
        // shot are flat: the machine is up to pressure but nothing has fallen
        // into the cup yet.
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) + (1..40).map { (0.1 + it * 0.1) to 0.5 }
        val r = run(trace)
        assertTrue(r.actions.none { it.second == Action.FINALIZE })
        assertEquals(ShotState.BREWING, r.ctx.state)
    }

    @Test
    fun `finalized is terminal`() {
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) +
            ramp(0.5, 71, 0.5) +
            (1..30).map { (7.3 + it * 0.1) to 36.0 }
        val r = run(trace)
        assertEquals(ShotState.FINALIZED, r.ctx.state)

        // Anything after FINALIZE - the cup being lifted, the pitcher landing
        // - must produce nothing at all until the user presses RESET.
        val after = step(r.ctx, Sample(20.0, -150.0), thresholds, 36.0)
        assertTrue(after.isEmpty())
        assertEquals(ShotState.FINALIZED, r.ctx.state)
    }

    @Test
    fun `elapsed freezes at the last flow once finalized`() {
        // A finished shot sits in FINALIZED until RESET, and its displayed
        // time must stop climbing while it waits.
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) +
            ramp(0.5, 71, 0.5) +
            (1..30).map { (7.3 + it * 0.1) to 36.0 }
        val r = run(trace)
        val atFinalize = r.ctx.elapsed(10.3)
        val muchLater = r.ctx.elapsed(600.0)
        assertEquals(atFinalize, muchLater, 1e-9)
    }

    // --- Slow starts (2.10.2) - not from the Python; the Pi never had this rule

    private val withYieldFloor = thresholds.copy(minSaveYieldGrams = 5.0)

    /**
     * 2026-11-17 17:12: a 1.9 g trickle over the first 6 s, a pause past the
     * 7 s grace period, then the real pour. The app finished the shot at
     * 7.1 s / 1.9 g and the cup filled to 37 g with no stop sent.
     */
    private val slowStart: List<Pair<Double, Double>> =
        listOf(0.0 to 0.2) +
            (1..60).map { (it * 0.1) to (0.2 + it * 1.7 / 60) } +
            (1..40).map { (6.0 + it * 0.1) to 1.9 } +
            (1..180).map { (10.0 + it * 0.1) to (1.9 + it * 0.2) }

    @Test
    fun `without the yield floor a slow start finishes in its pause`() {
        val r = run(slowStart)
        assertEquals(ShotState.FINALIZED, r.ctx.state)
        assertTrue(r.actions.none { it.second == Action.SEND_STOP })
        assertEquals(7.1, r.actions.single { it.second == Action.FINALIZE }.first, 0.15)
    }

    @Test
    fun `a slow start pausing below the yield floor keeps brewing and stops on weight`() {
        val r = run(slowStart, cfg = withYieldFloor)
        assertTrue(r.actions.none { it.second == Action.FINALIZE })
        val stop = r.actions.single { it.second == Action.SEND_STOP }
        assertEquals(29.8, r.ctx.weightAtStop!!, 0.2)
        assertTrue(stop.first > 23.0)
    }

    @Test
    fun `a phantom start below the yield floor gives up after the long wait`() {
        val trace = listOf(0.0 to 0.3) + (1..200).map { (it * 0.1) to 0.3 }
        val r = run(trace, cfg = withYieldFloor)
        val finish = r.actions.single { it.second == Action.FINALIZE }
        assertEquals(15.0, finish.first, 0.11)
    }

    @Test
    fun `a shot past the yield floor still finishes on the short pause`() {
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) +
            ramp(0.5, 71, 0.5) +
            (1..30).map { (7.3 + it * 0.1) to 36.0 }
        val r = run(trace, cfg = withYieldFloor)
        assertEquals(ShotState.FINALIZED, r.ctx.state)
        assertTrue(r.actions.single { it.second == Action.FINALIZE }.first < 9.0)
    }

    // --- Machine brewing (2.11.0) - not from the Python; the Pi had no BLE

    @Test
    fun `a pause while the machine reports brewing does not finish the shot`() {
        val trace = listOf(0.0 to 0.0, 0.1 to 0.5) +
            ramp(0.5, 71, 0.5) +
            (1..30).map { (7.3 + it * 0.1) to 36.0 }
        val ctx = ShotContext().apply { armed = true; machineBrewing = true }
        val fired = trace.flatMap { (ts, w) -> step(ctx, Sample(ts, w), thresholds, 36.0) }
        assertTrue(fired.none { it == Action.FINALIZE })
        assertTrue(ctx.finishHeldByMachine)
        // The machine stops reporting: the next settled sample finishes it.
        ctx.machineBrewing = false
        assertEquals(listOf(Action.FINALIZE), step(ctx, Sample(10.4, 36.0), thresholds, 36.0))
    }
}
