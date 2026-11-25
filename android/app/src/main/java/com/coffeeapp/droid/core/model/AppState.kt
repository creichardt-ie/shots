// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.model

import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.core.machine.ControlPath

/**
 * Immutable snapshot types shared between the runtime and the UI.
 *
 * Data classes updated with copy(), so the UI can hold a reference without
 * worrying that it mutates underneath.
 */

enum class ShotState {
    IDLE,
    BREWING,
    STOPPING,
    FINALIZED,
}

/** A coffee bean in the journal. [tasty]: true = up, false = down, null = unrated. */
data class Bean(
    val id: Int,
    val name: String,
    val grindSetting: Double = 0.0,
    val tasty: Boolean? = null,
    val isCurrent: Boolean = false,
)

data class ScaleSnapshot(
    val connected: Boolean = false,
    val weight: Double = 0.0,
    val battery: Int? = null,
    /**
     * True when the adapter has dropped the BLE link on idle to let the scale
     * power down. Distinct from a connection error: wake it from the UI.
     */
    val asleep: Boolean = false,
)

data class MachineSnapshot(
    val status: String = "Unknown",
    val powerOn: Boolean = false,
    val pendingPower: Boolean? = null,
    val name: String = "",
    val coffeeTemp: Double = 0.0,
    /**
     * BoilerStatus as the cloud spells it: "Ready", "HeatingUp", "StandBy",
     * "NoWater" or "Off". Blank when the widget was missing.
     *
     * The dashboard carries no live boiler temperature - only the target - so
     * this and [coffeeReadySince] are the whole of what the cloud knows about
     * whether the machine is actually up to heat.
     */
    val coffeeBoilerStatus: String = "",
    /** Epoch millis at which the coffee boiler reached temperature, or null. */
    val coffeeReadySince: Long? = null,
    val steamLevel: String = "N/A",
    /** As [coffeeBoilerStatus], for the steam boiler. */
    val steamBoilerStatus: String = "",
    /** As [coffeeReadySince], for the steam boiler. */
    val steamReadySince: Long? = null,
    val connected: Boolean = false,
    val gatewayVersion: String = "N/A",
    val lastSyncOk: Boolean = false,
    /**
     * Whether there is anything to talk to the cloud with.
     *
     * False until an account and a serial exist, which is a different thing
     * from a refresh that failed: on a fresh install every cloud field is
     * empty for want of configuration, not because anything is broken, and
     * saying CLOUD UNREACHABLE there sends people hunting a fault they do
     * not have.
     */
    val configured: Boolean = false,
    val lastError: String? = null,
    /** Why the last cloud refresh failed; null after a good one (2.11.0). */
    val cloudProblem: com.coffeeapp.droid.core.cloud.CloudProblem? = null,
    /**
     * Since when [cloudProblem] has stood, epoch millis - kept across app
     * restarts, so a weeks-long outage does not read as minutes old.
     */
    val cloudProblemSince: Long? = null,
    /**
     * Live boiler temperatures. Only BLE reports them - the cloud dashboard
     * carries targets alone - so they are null whenever the link is down.
     */
    val coffeeTempCurrent: Double? = null,
    val steamTempCurrent: Double? = null,
    /** Whether the steam boiler is switched on. Null until someone has said. */
    val steamEnabled: Boolean? = null,
    /** Where a command would go right now. */
    val controlPath: ControlPath = ControlPath.CLOUD,
    /** The BLE link; OFF while local control is off. */
    val bleState: MicraBleClient.State = MicraBleClient.State.OFF,
    /**
     * Whether the machine was advertising (or answered) at the last look over
     * BLE - a probe, a pre-connect scan or a connect. False while local
     * control is off. Between probes it can be up to one probe interval old.
     */
    val bleSeen: Boolean = false,
    /**
     * A temperature or steam command in flight, as "coffee:94",
     * "steamLevel:Level2" or "steamOn:true"; null when none. A cloud round
     * trip takes seconds, and the tapped option showed nothing meanwhile.
     */
    val pendingCommand: String? = null,
    /** Why the last machine command failed, shown for a few seconds; null otherwise. */
    val commandError: String? = null,
)

/** Where the Brew page timer counts from (2.12.0). */
enum class TimerSource {
    /** No shot and no pump: the timer reads 0. */
    NONE,

    /** Pump-on, from the machine's BLE shot counter. */
    PUMP,

    /** The first drops on the scale: the fallback when BLE gave no pump-on. */
    SCALE,
}

data class ShotSnapshot(
    val state: ShotState = ShotState.IDLE,
    val elapsedSeconds: Double = 0.0,
    val timerSource: TimerSource = TimerSource.NONE,
    val weight: Double = 0.0,

    /**
     * The yield as it stood when the shot ended; null until then. [weight]
     * goes back to tracking the live scale in FINALIZED, so anything quoting
     * the finished shot must read this - the notification once reported a
     * milk pitcher's -49.1 g as the shot.
     */
    val finalYield: Double? = null,
    val targetYield: Double = 36.0,
    val stopSent: Boolean = false,
    val saved: Boolean = false,
    val currentOffsetGrams: Double = 4.0,
    val armed: Boolean = false,

    /** Smoothed extraction flow rate (g/s) while a shot is live; 0 when idle. */
    val flowRateGps: Double = 0.0,

    /**
     * Post-shot grind verdict (e.g. "Dialed in (29s)"); empty until a shot is
     * saved, cleared when the next shot starts.
     */
    val grindHint: String = "",

    /**
     * Auto-tare net readout: set while a recognised vessel with contents
     * (beans or grounds) is sitting on the scale, null otherwise. The UI
     * subtracts netOffset from the LIVE scale reading, so the number tracks at
     * the scale own sample rate without the shot service having to publish a
     * snapshot per tick while idle.
     */
    val netOffset: Double? = null,
    val netLabel: String = "",
    val netVesselGrams: Double? = null,

    /**
     * Last auto-tare action, e.g. "Auto-tared: Coffee cup - armed". Empty when
     * the feature is off or nothing has been recognised yet.
     */
    val autoTareHint: String = "",

    /**
     * Page-switch request raised when a vessel with switchToPage lands.
     * A counter rather than a flag: nothing has to consume and clear it, and
     * every observer reacts exactly once. [navTs] lets the UI ignore a request
     * it has moved on from.
     */
    val navSeq: Int = 0,
    val navTarget: String = "",
    val navTs: Double = 0.0,

    /**
     * Something the person at the machine must act on - a stop that never
     * reached the machine, a scale lost mid-shot. Empty when all is well;
     * cleared by RESET, by the next shot starting, and by the shot finishing
     * on scale readings (the flow stopped, so the machine did) - but not for
     * a scale-lost shot, where nobody saw the flow stop.
     */
    val alert: String = "",
)

/** The scale without its weight, for views that do not show it (so they skip the 10-20 Hz updates). */
fun ScaleSnapshot.statusOnly(): ScaleSnapshot = copy(weight = 0.0)

data class AppSnapshot(
    val scale: ScaleSnapshot = ScaleSnapshot(),
    val machine: MachineSnapshot = MachineSnapshot(),
    val shot: ShotSnapshot = ShotSnapshot(),
    /** Active bean shown read-only on the Brew screen; empty when none. */
    val activeBeanName: String = "",
    val activeBeanGrind: Double? = null,
)
