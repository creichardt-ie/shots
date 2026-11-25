// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.config

import kotlinx.serialization.Serializable

/**
 * Typed, round-trippable configuration.
 *
 * The defaults are deliberate — the tuned values (min_rise_rate_gps = 20,
 * max_flow_rate_gps = 15, the auto-tare bands) were measured on real hardware
 * and several encode a specific past failure. Changing one silently changes
 * behaviour the comments below are the record of.
 *
 * Secrets (cloud username, password, BLE token) are NOT in this object. They
 * live in EncryptedSharedPreferences, so this whole structure stays safe to
 * log or screenshot.
 */

@Serializable
data class ScaleConfig(
    val macAddress: String = "",
    /**
     * The scale's brand, [com.coffeeapp.droid.ble.ScaleModel.id] (2.16.0):
     * "acaia" (default, so existing installs are unchanged) or "bookoo".
     * The Acaia-only fields below are ignored for other brands.
     */
    val model: String = "acaia",
    /** True for Pyxis/Lunar-2021 (and Umbra); false for the original Lunar. */
    val isNewStyle: Boolean = true,
    /**
     * Optional GATT characteristic overrides for scales whose UUIDs differ
     * from the defaults. The Acaia Umbra speaks the standard new-style
     * protocol but exposes it on fe41 (write) / fe42 (notify), so it needs
     * BOTH set. Blank = use the isNewStyle defaults.
     */
    val notifyCharUuid: String = "",
    val writeCharUuid: String = "",
    /**
     * Drop the BLE link after this many minutes of no meaningful weight change
     * so the scale hits its own auto-off. 0 disables. Waking is manual.
     */
    val idleDisconnectMinutes: Double = 20.0,
    /**
     * How much the weight must change to count as activity and reset the idle
     * timer. Deliberately coarse, and NOT the fine flowDetectionThreshold: a
     * resting scale jitters ~0.1 g, so a shared threshold re-armed the timer
     * on every tick and the scale never slept.
     */
    val idleActivityThresholdGrams: Double = 10.0,
)

@Serializable
data class MachineConfig(
    val serial: String = "",
    val powerRefreshDelaySeconds: Double = 17.0,
    val coffeeTempOptions: List<Int> = listOf(90, 91, 92, 93, 94, 95, 96),
    /**
     * Hold a BLE link to the machine and route commands over it, with the
     * cloud as fallback (MachineController). Off by default: the link takes
     * the Micra's only BLE slot, which locks the official app out.
     */
    val localControl: Boolean = false,
    /** The machine's BLE MAC, picked by a scan in Settings. Blank = none. */
    val bleAddress: String = "",
)

@Serializable
data class ShotLogic(
    val startThresholdGrams: Double = 0.2,
    val finishStabilitySeconds: Double = 1.0,
    val minSaveDurationSeconds: Double = 5.0,
    /** A yield floor too: duration alone let a 5.2 s phantom reach the journal. */
    val minSaveYieldGrams: Double = 5.0,
    val ignoreStabilityFirstSeconds: Double = 7.0,
    val flowDetectionThreshold: Double = 0.05,
    /**
     * A shot starts near zero and climbs. A reading already this heavy when
     * the state machine would enter BREWING is a vessel landing, not espresso
     * - the lightest configured vessel is over 100 g.
     */
    val maxStartWeightGrams: Double = 10.0,
    /**
     * Ceiling on plausible flow, used to reject a sudden step (a cup being
     * placed, a knock) as espresso. 120 recorded shots peak at 3.7 g/s, so
     * this is ~4x the fastest ever measured and still two orders of magnitude
     * below a cup landing (~500 g/s).
     */
    val maxFlowRateGps: Double = 15.0,
)

@Serializable
data class BrewByWeight(
    val enabled: Boolean = true,
    val offsetGrams: Double = 4.0,
    val learningAlpha: Double = 0.2,
    val powerOnDelaySeconds: Double = 7.0,
    val maxPlausibleDripGrams: Double = 15.0,
    /**
     * Outlier filter for offset learning: skip a shot whose drip deviates from
     * the current offset by more than this fraction. 0 disables the filter;
     * the hard cap above still applies.
     */
    val learningMaxDeviationRatio: Double = 0.5,
    /**
     * How much later a stop sent through the cloud reaches the machine than
     * one sent over BLE. The machine keeps brewing at full flow until then,
     * so with the link down the stop fires this many seconds of the current
     * flow earlier. Measured 2026-10-31: 40 cloud stops since #140 poured
     * ~0.6 s of flow (~1.7 g at 2.8 g/s) more than a BLE stop at the same
     * flow; the learned offset is the BLE drip only.
     */
    val cloudStopDelaySeconds: Double = 0.6,
    /**
     * Grind-dial verdict window (total extraction seconds). Pure UI guidance -
     * never affects shot control.
     */
    val grindTargetFastSeconds: Double = 28.0,
    val grindTargetSlowSeconds: Double = 32.0,
)

/**
 * A container the scale can recognise by weight.
 *
 * [grams] is the bare (empty) weight. [arm] marks a vessel you brew into, so
 * placing it is also an arming gesture. The contents range defines the band in
 * which the vessel reads as loaded - placed with beans or grounds already in
 * it - which is shown as a net weight and deliberately NOT tared.
 */
@Serializable
data class Vessel(
    val name: String = "",
    val grams: Double = 0.0,
    val arm: Boolean = false,
    val minContentsGrams: Double = 3.0,
    val maxContentsGrams: Double = 30.0,
    /**
     * Screen to jump to when this vessel lands: "scale", "brewing", or "" for
     * none. The brew cup asks for brewing (it was just tared and armed, the
     * shot is next); the milk pitcher for scale (the shot is over and the live
     * weight is what steaming wants).
     */
    val switchToPage: String = "",
)

/**
 * Recognise a vessel by weight when it lands on the scale.
 *
 * Defaults to disabled: the feature can tare the scale and arm a shot on its
 * own, so a code deploy must be inert until it is switched on deliberately.
 */
@Serializable
data class AutoTare(
    val enabled: Boolean = false,
    /** Arm the shot when an arm vessel is auto-tared. Separate from enabled. */
    val autoArm: Boolean = false,
    val toleranceGrams: Double = 5.0,
    /** How long a reading must hold steady to count as a plateau. */
    val stableSeconds: Double = 1.2,
    /**
     * Minimum average rate (g/s) of the transition between two plateaus. This
     * is the guard that makes espresso flow structurally unable to look like a
     * vessel being placed, and it guards on the quantity that actually
     * separates them: a shot delivers ~2 g/s (peak ~5), while a 190 g cup set
     * down moves at hundreds of g/s even when placed slowly.
     *
     * This replaced a flat cap on the transition DURATION (0.5 s). Time is a
     * poor proxy: a real placement measured on hardware used 0.35 s of that
     * budget and lifting the same cup took 0.51 s and was rejected, so any
     * slightly deliberate placement fell off a cliff aimed at something else.
     */
    val minRiseRateGps: Double = 20.0,
    /** Jitter band a resting scale is allowed to wander inside. */
    val stabilityBandGrams: Double = 0.5,
    /** Blind window after issuing a tare, covering the BLE round-trip. */
    val tareCooldownSeconds: Double = 2.0,
    /** EWMA rate for re-learning a bare weight from each empty placement. */
    val learningAlpha: Double = 0.1,
    val vessels: List<Vessel> = emptyList(),
)

@Serializable
data class Defaults(
    val targetYieldGrams: Double = 36.0,
    /** Show the launch screen at startup. Off skips it entirely. */
    val showSplashScreen: Boolean = true,
    /** Accent colour id; see AccentScheme. Unknown values fall back to the default. */
    val colorScheme: String = "micra_red",
    /**
     * The picture on the Micra page: [MachinePicture.id]. The La Marzocco
     * photo is theirs, not ours (THIRD-PARTY-NOTICES.md section 4), so it
     * can be swapped for the user's own photo or dropped. Unknown values
     * fall back to the default.
     */
    val machinePicture: String = MachinePicture.LA_MARZOCCO.id,
)

/** The choices for [Defaults.machinePicture]. */
enum class MachinePicture(val id: String, val label: String) {
    LA_MARZOCCO("lamarzocco", "La Marzocco photo"),
    OWN("own", "My photo"),
    NONE("none", "None"),
    ;

    companion object {
        fun fromId(id: String): MachinePicture = entries.firstOrNull { it.id == id } ?: LA_MARZOCCO
    }
}

@Serializable
data class AppConfig(
    val scale: ScaleConfig = ScaleConfig(),
    val machine: MachineConfig = MachineConfig(),
    val shotLogic: ShotLogic = ShotLogic(),
    val brewByWeight: BrewByWeight = BrewByWeight(),
    val autoTare: AutoTare = AutoTare(),
    val defaults: Defaults = Defaults(),
)

/**
 * What SAVE on the Configuration screen should write: the user's edits
 * ([draft] against [opened], the config when the screen opened) laid over
 * [live], the config as it is now.
 *
 * The runtime keeps changing a few values while the screen is open - the BBW
 * offset learned at every shot, a vessel's weight learned at every bare
 * placement, the target yield and BBW switch set from the Brew screen. A
 * plain "write the draft" put the values from when the screen opened back.
 * Those fields take the live value unless the user edited them here.
 */
fun mergeEdits(opened: AppConfig, draft: AppConfig, live: AppConfig): AppConfig {
    val bbw = draft.brewByWeight.copy(
        offsetGrams = if (draft.brewByWeight.offsetGrams == opened.brewByWeight.offsetGrams) {
            live.brewByWeight.offsetGrams
        } else {
            draft.brewByWeight.offsetGrams
        },
        enabled = if (draft.brewByWeight.enabled == opened.brewByWeight.enabled) {
            live.brewByWeight.enabled
        } else {
            draft.brewByWeight.enabled
        },
    )
    val openedVessels = opened.autoTare.vessels.associateBy { it.name }
    val liveVessels = live.autoTare.vessels.associateBy { it.name }
    val vessels = draft.autoTare.vessels.map { v ->
        val o = openedVessels[v.name]
        val l = liveVessels[v.name]
        if (o != null && l != null && v.grams == o.grams) v.copy(grams = l.grams) else v
    }
    val target = if (draft.defaults.targetYieldGrams == opened.defaults.targetYieldGrams) {
        live.defaults.targetYieldGrams
    } else {
        draft.defaults.targetYieldGrams
    }
    return draft.copy(
        brewByWeight = bbw,
        autoTare = draft.autoTare.copy(vessels = vessels),
        defaults = draft.defaults.copy(targetYieldGrams = target),
    )
}
