// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.machine

import kotlin.math.abs

/**
 * Whether a boiler is at its target, judged from the live BLE reading.
 *
 * The cloud's own "Ready since" is refreshed only every 10 minutes while BLE
 * carries the state, so after a level change it kept saying Ready about a
 * boiler still heating to the new target. The live reading answers it
 * directly.
 *
 * Steam only. The coffee target is a brew temperature at the group, and the
 * boiler is held above it to cover the loss on the way: 97 for 94, 94 for
 * 90. A plain comparison would call a settled coffee boiler "settling"
 * forever; until the offset is pinned down (+3 and +4 so far), coffee
 * readiness stays the cloud's.
 */
enum class Readiness { HEATING, READY, SETTLING }

object BoilerReadiness {

    /**
     * Steam readings are coarse and move in jumps, mostly ~3 degrees: on
     * 2026-10-17 nearly all were 125, 128 or 131, with the odd 129 or 132.
     * At a 126 target a cycling heater flips between 125 and 128, so the band
     * must take both - 2 does, while 128 against 131 still reads as heating.
     */
    const val TOLERANCE_C = 2.0

    /**
     * HEATING below the target, SETTLING above it (usable - hotter steam is
     * not a problem - but still drifting down), READY within [TOLERANCE_C].
     * Null without a reading, so the caller falls back to the cloud.
     */
    fun of(targetC: Double?, currentC: Double?): Readiness? {
        if (targetC == null || currentC == null) return null
        val delta = currentC - targetC
        return when {
            abs(delta) <= TOLERANCE_C -> Readiness.READY
            delta < 0 -> Readiness.HEATING
            else -> Readiness.SETTLING
        }
    }
}
