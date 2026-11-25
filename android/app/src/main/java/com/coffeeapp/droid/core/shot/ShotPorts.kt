// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.ble.WeightSample
import kotlinx.coroutines.flow.SharedFlow

/**
 * What ShotService needs from the scale. ScaleAdapter implements it; the
 * tests feed a recorded trace through a fake.
 */
interface ScaleSource {
    val samples: SharedFlow<WeightSample>
    suspend fun tare()
}

/** Where a finished shot is written. ShotRepository implements it. Returns the row id. */
interface ShotSink {
    suspend fun saveShot(
        startTime: Double,
        duration: Double,
        yieldGrams: Double,
        target: Double,
        machineDuration: Double? = null,
        offsetGrams: Double? = null,
        beanId: Int? = null,
        grindSetting: Double? = null,
        weightAtStop: Double? = null,
        peakFlowGps: Double? = null,
        grindRec: String? = null,
        flowAtStopGps: Double? = null,
    ): Long
}
