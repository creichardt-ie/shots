// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.core.config.Vessel
import kotlin.math.abs

/**
 * Pure vessel-placement detector and vessel matching.
 *
 * Ported statement for statement from the retired Pi app's placement.py;
 * PlacementDetectorTest carries its expected values. No IO, no framework
 * deps, so it can be replayed against a recorded weight trace without a scale.
 *
 * The detector reports STEP CHANGES BETWEEN TWO SETTLED PLATEAUS. Two
 * independent properties keep espresso flow from ever looking like a vessel
 * landing on the scale:
 *
 *  - a settle hold: the reading must sit inside a jitter band for
 *    stableSeconds before it counts as a plateau at all;
 *  - a transition rate: every event carries the time the edge took and the
 *    g/s it averaged, so the caller can reject slow edges. A placed cup moves
 *    at hundreds of g/s (190 g in a fraction of a second); espresso runs at
 *    ~2 g/s for 25 s. No plausible brew rate comes within an order of
 *    magnitude of a placement, in any shot state.
 *
 * The rate FLOOR is policy and lives in the caller (ShotService), the same way
 * ShotStateMachine returns Actions and lets the service decide what to do.
 */

/** A settled-to-settled step change in the weight stream. */
data class PlacementEvent(
    val ts: Double,
    /** Signed change from the previous plateau. */
    val delta: Double,
    /** Absolute reading of the new plateau. */
    val settled: Double,
    /** How long the transition between plateaus took. */
    val riseSeconds: Double,
) {
    /**
     * Average speed of the transition, in grams per second.
     *
     * A step that lands inside a single sample has no measurable duration; it
     * is as fast as this scale can report, so it reads as infinite rather than
     * as a divide-by-zero to defend against.
     */
    val rateGps: Double
        get() = if (riseSeconds <= 0.0) Double.POSITIVE_INFINITY else abs(delta) / riseSeconds
}

data class VesselMatch(
    val name: String,
    val vesselGrams: Double,
    /**
     * EMPTY: the bare vessel; safe to tare.
     * CONTENTS: the vessel plus beans/grounds; must NOT be tared, or we would
     * zero the very reading the user put it there to take.
     */
    val kind: Kind,
    val contentsGrams: Double,
    val arm: Boolean,
    val switchToPage: String,
) {
    enum class Kind { EMPTY, CONTENTS }
}

/** Turns a weight stream into place/remove events. Pure, no IO. */
class PlacementDetector(
    stabilityBand: Double = 0.5,
    stableSeconds: Double = 1.2,
) {
    private var band: Double = maxOf(0.01, stabilityBand)
    private var stableSecs: Double = maxOf(0.05, stableSeconds)

    private var baseline: Double? = null
    private var ref: Double? = null
    private var refSince: Double = 0.0
    private var settled: Boolean = false
    private var departTs: Double? = null
    private var awaitingTareUntil: Double? = null

    fun configure(stabilityBand: Double, stableSeconds: Double) {
        band = maxOf(0.01, stabilityBand)
        stableSecs = maxOf(0.05, stableSeconds)
    }

    /**
     * Forget the baseline. The next plateau re-seeds it without an event.
     *
     * Called whenever we stop observing (shot running, auto-tare disabled) so
     * that changes which happened while we were not watching - the cup being
     * lifted after a shot, most obviously - cannot surface later as a phantom
     * step against a stale baseline.
     */
    fun reset() {
        baseline = null
        ref = null
        settled = false
        departTs = null
        awaitingTareUntil = null
    }

    /**
     * How long the reading has been off a settled plateau. 0 when settled.
     *
     * Purely diagnostic: it lets the caller notice (and log) the case where
     * weight clearly moved but never produced an event, which is otherwise
     * indistinguishable in the log from nothing having happened at all.
     */
    fun transitSeconds(now: Double): Double {
        val depart = departTs
        if (settled || depart == null) return 0.0
        return maxOf(0.0, now - depart)
    }

    /**
     * Ignore samples until the tare lands, then adopt the zero as baseline.
     *
     * Used right after issuing a tare: the scale keeps reporting the old
     * weight for a BLE round-trip and then drops to zero, and that drop is a
     * textbook "vessel removed" edge.
     *
     * What this must NOT do is go blind for a fixed window and re-seed on
     * whatever plateau follows. The natural gesture after pressing RESET is to
     * put the cup down immediately, and a placement made inside such a window
     * silently became the new baseline instead of an event - the cup landed,
     * nothing was detected, and auto-arm never fired. Watching for the zero
     * instead ends the blind period as soon as the tare actually lands
     * (~0.3 s on this hardware, not the full cooldown), and the placement
     * after it is a clean step from a known baseline.
     */
    fun expectTare(now: Double, timeoutSeconds: Double) {
        reset()
        awaitingTareUntil = now + maxOf(0.0, timeoutSeconds)
    }

    fun feed(ts: Double, weight: Double): PlacementEvent? {
        val awaiting = awaitingTareUntil
        if (awaiting != null) {
            if (abs(weight) <= band) {
                // The tare landed. Adopt it as a settled plateau outright: we
                // know the physical state it describes, so there is nothing
                // the settle hold could establish that we do not already know.
                awaitingTareUntil = null
                baseline = weight
                ref = weight
                refSince = ts
                settled = true
                departTs = null
                return null
            }
            if (ts < awaiting) return null
            // It never arrived (a failed write, or a hand on the scale). Fall
            // back to the old behaviour: re-seed silently on the next plateau.
            awaitingTareUntil = null
        }

        // Outside the jitter band, the reading is in transit. Note when it
        // first left a settled plateau; that is what makes riseSeconds the
        // duration of the whole transition and not just its last leg.
        val currentRef = ref
        if (currentRef == null || abs(weight - currentRef) > band) {
            if (settled) departTs = ts
            settled = false
            ref = weight
            refSince = ts
            return null
        }

        if (settled || (ts - refSince) < stableSecs) return null

        settled = true
        val previous = baseline
        baseline = weight
        if (previous == null) return null // first plateau after a reset: seed only

        val delta = weight - previous
        if (abs(delta) <= band) return null

        val depart = departTs ?: refSince
        return PlacementEvent(
            ts = ts,
            delta = delta,
            settled = weight,
            riseSeconds = maxOf(0.0, refSince - depart),
        )
    }
}

/**
 * Identify a positive step change as a known vessel, empty or loaded.
 *
 * Empty is checked first and wins: the empty band (grams +/- tolerance) and
 * the loaded band (grams + minContents ...) can abut, and "bare vessel, tare
 * it" is the safe reading of an ambiguous weight - taring an empty vessel
 * costs nothing, while treating a bare vessel as loaded would invent a
 * contents weight out of measurement slop.
 *
 * An arm vessel is matched EMPTY ONLY. A brew cup is something you place empty
 * and catch a shot in, never something you weigh contents in, and its loaded
 * band would otherwise be tens of grams wide - wide enough to swallow any
 * unfamiliar mug and report a confident, wrong net weight. Better to return no
 * match: an unrecognised vessel is tared by hand, which is what would have
 * happened anyway.
 */
/**
 * A vessel matched loaded as well as empty: not an arm vessel, and with a
 * loaded band. In practice the dosing cup - the milk pitcher is empty-only.
 */
fun Vessel.weighsDose(): Boolean = !arm && maxContentsGrams > 0

fun matchVessel(delta: Double, vessels: List<Vessel>, tolerance: Double): VesselMatch? {
    if (delta <= 0) return null

    for (v in vessels) {
        if (v.grams > 0 && abs(delta - v.grams) <= tolerance) {
            return VesselMatch(
                name = v.name,
                vesselGrams = v.grams,
                kind = VesselMatch.Kind.EMPTY,
                contentsGrams = 0.0,
                arm = v.arm,
                switchToPage = v.switchToPage,
            )
        }
    }

    for (v in vessels) {
        // maxContentsGrams <= 0 opts a vessel out of loaded matching entirely
        // - use it for anything you only ever place empty (a milk pitcher), so
        // its loaded band cannot shadow another vessel.
        if (v.grams <= 0 || v.arm || v.maxContentsGrams <= 0) continue
        val low = v.grams + v.minContentsGrams
        val high = v.grams + v.maxContentsGrams + tolerance
        if (delta in low..high) {
            return VesselMatch(
                name = v.name,
                vesselGrams = v.grams,
                kind = VesselMatch.Kind.CONTENTS,
                contentsGrams = delta - v.grams,
                // A loaded vessel is never an arming gesture - you are
                // weighing, not about to catch a shot.
                arm = false,
                switchToPage = v.switchToPage,
            )
        }
    }
    return null
}

/**
 * One-line "why did this not match" summary of a step against every vessel.
 *
 * Written for the log: a placement that misses by 4 g and one that misses by
 * 80 g are the same "no match" to the caller, but they mean completely
 * different things (re-learn the vessel vs. an unknown object).
 */
fun describeCandidates(delta: Double, vessels: List<Vessel>, tolerance: Double): String {
    if (vessels.isEmpty()) return "no vessels configured"
    val parts = mutableListOf<String>()
    for (v in vessels) {
        if (v.grams <= 0 || v.name.isEmpty()) continue
        val sb = StringBuilder()
        sb.append(
            String.format(
                "%s bare %.1fg (off %+.1fg, tol +/-%.1fg)",
                v.name, v.grams, delta - v.grams, tolerance,
            ),
        )
        if (v.weighsDose()) {
            val low = v.grams + v.minContentsGrams
            val high = v.grams + v.maxContentsGrams + tolerance
            sb.append(String.format(" loaded %.1f-%.1fg", low, high))
        } else {
            sb.append(" empty-only")
        }
        parts.add(sb.toString())
    }
    return if (parts.isEmpty()) "no usable vessels configured" else parts.joinToString("; ")
}

/**
 * Human-readable warnings for vessels whose match bands collide.
 *
 * A collision does not produce a no-op, it produces a plausible-looking wrong
 * number (the net contents weight is derived by subtracting the MATCHED
 * vessel), so it is worth surfacing at config-save time rather than leaving it
 * to be debugged from a bad dose reading.
 */
fun findBandOverlaps(vessels: List<Vessel>, tolerance: Double): List<String> {
    // Vessels matched empty-only (an arm vessel, or one with no loaded band)
    // occupy just the tolerance window, not a loaded band.
    fun band(v: Vessel): Pair<Double, Double> {
        val emptyOnly = !v.weighsDose()
        val high = v.grams + (if (emptyOnly) 0.0 else v.maxContentsGrams)
        return Pair(v.grams - tolerance, high + tolerance)
    }

    val warnings = mutableListOf<String>()
    val usable = vessels.filter { it.grams > 0 && it.name.isNotEmpty() }

    for (i in usable.indices) {
        val a = usable[i]
        val (aLow, aHigh) = band(a)
        for (j in (i + 1) until usable.size) {
            val b = usable[j]
            val (bLow, bHigh) = band(b)
            if (aLow <= bHigh && bLow <= aHigh) {
                warnings.add(
                    String.format(
                        "\"%s\" (%.1f-%.1f g) overlaps \"%s\" (%.1f-%.1f g) - a placement " +
                            "in the overlap will be attributed to whichever is listed first.",
                        a.name, aLow, aHigh, b.name, bLow, bHigh,
                    ),
                )
            }
        }
    }

    for (v in usable) {
        if (v.arm || v.maxContentsGrams <= 0) continue
        if (v.minContentsGrams <= tolerance) {
            warnings.add(
                String.format(
                    "\"%s\": min contents (%.1f g) is not above the match tolerance " +
                        "(%.1f g), so a bare vessel and a barely-loaded one are " +
                        "indistinguishable; it will be treated as empty and tared.",
                    v.name, v.minContentsGrams, tolerance,
                ),
            )
        }
    }
    return warnings
}
