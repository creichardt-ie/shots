// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.core.model.ShotState
import kotlin.math.abs

/**
 * Pure shot state machine.
 *
 * Ported statement for statement from the retired Pi app's shot_state.py;
 * ShotStateMachineTest carries its expected values. Kept free of Android,
 * coroutines and IO so it can be replayed against a recorded weight trace with no scale, no cloud
 * and no database. Side effects are returned as [Action] values the caller
 * dispatches.
 *
 * This is the file where a mistake cuts power to a live espresso machine, so
 * it is a statement-for-statement port rather than a re-interpretation.
 */

enum class Action {
    FETCH_MACHINE_START,
    SEND_STOP,
    FINALIZE,
    ABANDON,
}

/**
 * A single sample may not change by more than this before the rate check even
 * looks at it. Flow at the measured 3.7 g/s peak delivers ~0.2 g per sample;
 * a vessel landing delivers all of itself. Paired with maxFlowRateGps so that
 * a stalled BLE link (one late sample carrying several seconds of real flow)
 * is not mistaken for a step.
 */
private const val IMPLAUSIBLE_JUMP_GRAMS = 5.0

data class Sample(val ts: Double, val weight: Double)

data class Thresholds(
    val startThresholdGrams: Double,
    val maxStartWeightGrams: Double,
    val maxFlowRateGps: Double,
    val flowDetectionThreshold: Double,
    val finishStabilitySeconds: Double,
    val ignoreStabilityFirstSeconds: Double,
    val minSaveDurationSeconds: Double,
    val brewByWeightEnabled: Boolean,
    val brewOffsetGrams: Double,
    /**
     * Extra grams to stop early because the stop will go through the cloud
     * (flow x cloud delay); 0 when the BLE link is up. Kept apart from the
     * offset, which is the BLE drip and is learned from BLE stops only.
     */
    val cloudLeadGrams: Double = 0.0,
    /**
     * Below this yield, with no stop sent, a pause is not the end of the shot:
     * a fine grind can trickle a gram or two, pause past the grace period and
     * only then pour (2026-11-17: finished at 7.1 s / 1.9 g, then poured 37 g
     * with nobody watching). 0 disables the rule.
     */
    val minSaveYieldGrams: Double = 0.0,
    /**
     * How long such a low-yield shot must sit still before it is given up -
     * long enough for a slow start, short enough that a phantom start (a bump
     * past the start threshold) does not hold the machine link at HIGH.
     */
    val lowYieldFinishSeconds: Double = 15.0,
)

/** Mutable data for the currently-active shot. Reset between shots. */
class ShotContext {
    var state: ShotState = ShotState.IDLE
    var armed: Boolean = false
    var startTime: Double? = null
    var lastActiveTime: Double? = null
    var lastWeight: Double = 0.0
    var currentWeight: Double = 0.0
    var stopSent: Boolean = false
    var weightAtStop: Double? = null
    var offsetUsedGrams: Double? = null
    var cloudLeadUsedGrams: Double = 0.0
    /** The stop reached the machine over BLE; only then is its drip learned. */
    var stopViaBle: Boolean = false
    /** Every stop attempt failed; the machine was stopped by hand, if at all. */
    var stopFailed: Boolean = false
    var machineStartTime: Double? = null

    /**
     * Pump-on from the machine's BLE shot counter, wall seconds; null when BLE
     * gave none (link down, old firmware). Unlike [machineStartTime] never the
     * cloud's: this drives the Brew page timer, and the cloud's answer lands
     * seconds into the shot, which would make the timer jump (2.12.0).
     */
    var pumpOnTime: Double? = null

    /**
     * The machine reports itself brewing, fresh, over the BLE shot counter
     * (2.11.0). Set by the caller before every [step]; while true a pause in
     * the weight is not the end of the shot - the pump is still on, the
     * coffee just has not reached the cup yet (2026-11-17: a slow start
     * "finished" at 7.1 s / 1.9 g and then poured 37 g unwatched).
     */
    var machineBrewing: Boolean = false

    /** [step] wanted to finish but [machineBrewing] held it; for one log line. */
    var finishHeldByMachine: Boolean = false
    var saved: Boolean = false
    var lastSampleTs: Double = 0.0

    fun elapsed(now: Double): Double {
        val start = startTime ?: return 0.0
        val lastActive = lastActiveTime
        if (state == ShotState.FINALIZED && lastActive != null) {
            return maxOf(0.0, lastActive - start)
        }
        return maxOf(0.0, now - start)
    }
}

/**
 * Advance the state machine by one weight sample.
 *
 * Mutates [ctx] in place; returns the actions the caller should dispatch.
 * IO lives in the caller, not here.
 */
fun step(
    ctx: ShotContext,
    sample: Sample,
    cfg: Thresholds,
    targetYield: Double,
): List<Action> {
    ctx.currentWeight = sample.weight
    val actions = mutableListOf<Action>()

    if (ctx.state == ShotState.FINALIZED) return actions

    if (ctx.state == ShotState.IDLE) {
        // Requires an explicit arm: prevents an accidental cup/portafilter
        // placement or scale bump from starting a shot (and worse, triggering
        // the BBW stop command if the weight happens to exceed target-offset).
        //
        // The upper bound matters as much as the lower one now that auto-arm
        // exists: an armed scale sitting at zero with the cup already tared on
        // it is one placement away from reading 190 g, and without a ceiling
        // that lands in BREWING already past target - offset, firing a BBW
        // stop (and the machine power-cycle behind it) on the very next sample.
        if (ctx.armed &&
            sample.weight > cfg.startThresholdGrams &&
            sample.weight <= cfg.maxStartWeightGrams
        ) {
            ctx.state = ShotState.BREWING
            ctx.startTime = sample.ts
            ctx.lastActiveTime = sample.ts
            ctx.lastWeight = sample.weight
            ctx.lastSampleTs = sample.ts
            actions.add(Action.FETCH_MACHINE_START)
        }
        return actions
    }

    // BREWING or STOPPING
    val startTime = ctx.startTime ?: return actions
    val elapsed = sample.ts - startTime

    // Sudden step: a vessel was put on the scale or taken off it (or the scale
    // was knocked). Espresso only ever adds weight, and never this fast, so
    // this sample is not flow and must not be fed to brew-by-weight or saved
    // as the yield. The check is on magnitude, not direction: a DROP of this
    // size is a vessel being lifted, which is how a stale arm plus the milk
    // pitcher coming off the scale once logged a 5-second shot yielding
    // -140.1 g.
    //
    // Inside the opening grace period the whole shot is written off - it never
    // was one; later, only the sample is dropped, so lifting the cup during
    // the drip tail (or a knock mid-shot) costs one reading, not the shot.
    val jump = sample.weight - ctx.lastWeight
    val interval = sample.ts - ctx.lastSampleTs
    ctx.lastSampleTs = sample.ts
    val implausible = abs(jump) > IMPLAUSIBLE_JUMP_GRAMS &&
        interval > 0 &&
        abs(jump) / interval > cfg.maxFlowRateGps

    if (implausible) {
        // The reading is not to be trusted as this shot weight - finalize
        // saves it and derives the BBW drip from it.
        ctx.currentWeight = ctx.lastWeight
        if (!ctx.stopSent && elapsed <= cfg.ignoreStabilityFirstSeconds) {
            ctx.state = ShotState.IDLE
            ctx.startTime = null
            ctx.lastActiveTime = null
            ctx.lastWeight = sample.weight
            ctx.currentWeight = sample.weight
            // armed is deliberately left alone: the stop can no longer fire
            // off a step (that is what this guard is for), and the ceiling on
            // the start transition keeps a loaded scale out of BREWING until
            // it is tared, so disarming would only cost the user the next
            // real shot.
            actions.add(Action.ABANDON)
            return actions
        }
    }

    if (!implausible) {
        if (abs(sample.weight - ctx.lastWeight) > cfg.flowDetectionThreshold) {
            ctx.lastWeight = sample.weight
            ctx.lastActiveTime = sample.ts
        }

        // Brew-by-weight: fire a single stop command when we cross the
        // threshold.
        if (cfg.brewByWeightEnabled &&
            !ctx.stopSent &&
            targetYield > 0 &&
            sample.weight >= targetYield - cfg.brewOffsetGrams - cfg.cloudLeadGrams
        ) {
            ctx.stopSent = true
            ctx.weightAtStop = sample.weight
            ctx.offsetUsedGrams = cfg.brewOffsetGrams
            ctx.cloudLeadUsedGrams = cfg.cloudLeadGrams
            ctx.state = ShotState.STOPPING
            actions.add(Action.SEND_STOP)
        }
    }

    // The finish check runs either way. Dropped samples freeze lastActiveTime,
    // so a cup lifted during the drip tail settles the shot out and finalizes
    // it at the weight it really reached - rather than leaving it stuck in
    // STOPPING until someone presses RESET.
    val timeSinceFlow = sample.ts - (ctx.lastActiveTime ?: sample.ts)
    val finishAfter = if (!ctx.stopSent && ctx.currentWeight < cfg.minSaveYieldGrams) {
        maxOf(cfg.finishStabilitySeconds, cfg.lowYieldFinishSeconds)
    } else {
        cfg.finishStabilitySeconds
    }
    if (timeSinceFlow >= finishAfter &&
        elapsed > cfg.ignoreStabilityFirstSeconds
    ) {
        if (ctx.machineBrewing) {
            ctx.finishHeldByMachine = true
        } else {
            ctx.state = ShotState.FINALIZED
            actions.add(Action.FINALIZE)
        }
    }

    return actions
}
