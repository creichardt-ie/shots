// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core

import com.coffeeapp.droid.core.config.AppConfig
import com.coffeeapp.droid.core.config.AutoTare
import com.coffeeapp.droid.core.config.MachineConfig
import com.coffeeapp.droid.core.config.ScaleConfig
import com.coffeeapp.droid.core.config.Vessel

/**
 * First-run seed values: scale behaviour and an example vessel set.
 *
 * Used ONLY when the config store is empty. After that the Settings screen
 * owns the values and this is never consulted again - editing anything here
 * has no effect on an install that has already run once.
 *
 * No device identity is seeded: the scale is picked by a scan and the machine
 * serial typed in under Configuration. Until then the scale stays
 * disconnected and the cloud unconfigured.
 *
 * The tuned numbers that matter (min_rise_rate_gps, max_flow_rate_gps, the
 * shot-logic thresholds) live in the AppConfig defaults, not here. The vessel
 * weights are the author's cups, as an example: weigh your own and edit them
 * under Configuration -> Auto-tare before switching it on.
 */
object DefaultConfig {

    val SEED = AppConfig(
        scale = ScaleConfig(
            macAddress = "",
            isNewStyle = true,
            // Blank: the standard new-style characteristics, with the Umbra's
            // fe41/fe42 tried when those are absent (AcaiaScaleProtocol).
            idleDisconnectMinutes = 20.0,
            idleActivityThresholdGrams = 10.0,
        ),
        machine = MachineConfig(
            serial = "",
            powerRefreshDelaySeconds = 17.0,
        ),
        autoTare = AutoTare(
            // Ships disabled: this feature can tare the scale
            // and arm a shot on its own, so it must be switched on knowingly.
            enabled = false,
            autoArm = false,
            toleranceGrams = 2.5,
            vessels = listOf(
                Vessel("Dosing cup", 113.8, arm = false, minContentsGrams = 8.0, maxContentsGrams = 20.0),
                Vessel(
                    "Milk pitcher", 140.2, arm = false,
                    minContentsGrams = 0.0,
                    // 0 opts out of loaded matching entirely, so this cannot
                    // shadow the dosing cup band.
                    maxContentsGrams = 0.0,
                    switchToPage = "scale",
                ),
                Vessel(
                    "Coffee cup", 189.7, arm = true,
                    minContentsGrams = 8.0, maxContentsGrams = 60.0,
                    switchToPage = "brewing",
                ),
            ),
        ),
    )
}
