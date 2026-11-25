// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.machine

import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.ble.MicraProtocol
import com.coffeeapp.droid.ble.MicraProtocol.CommandStatus
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.core.model.MachineSnapshot
import com.coffeeapp.droid.core.model.ShotState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** The cloud side of machine control. CloudClient implements it. */
interface CloudMachine {
    suspend fun refresh(): MachineSnapshot
    suspend fun getBrewStartTs(): Double?
    suspend fun setPower(on: Boolean)
    suspend fun setCoffeeTemp(temperature: Double)
    suspend fun setSteamLevel(level: String)
    suspend fun setSteamEnabled(enabled: Boolean)
}

/** The local side. MicraBleClient implements it. Null status = the op failed. */
interface BleMachine {
    val isConnected: Boolean
    val readings: StateFlow<MicraBleClient.Readings?>
    suspend fun setPower(on: Boolean): CommandStatus?

    /** StandBy, done at the write ack with no status read. True = acknowledged. */
    suspend fun standbyFast(): Boolean
    suspend fun setCoffeeTarget(celsius: Double): CommandStatus?
    suspend fun setSteamTarget(celsius: Int): CommandStatus?
    suspend fun setSteamEnabled(enabled: Boolean): CommandStatus?

    /** Subscribed to the machine's BLE shot counter (2.11.0); false where there is none. */
    val shotCounterActive: Boolean get() = false
}

enum class ControlPath { BLE, CLOUD }

/**
 * Which way a call went, and why it did not go the other way.
 * [fallbackReason] is null when BLE carried it.
 */
data class Routed(val path: ControlPath, val fallbackReason: String? = null)

/**
 * Routes machine calls between the local BLE link and the cloud
 * (BLE_PLAN.md step 3):
 *
 * | Call                                   | Route                                            |
 * |----------------------------------------|--------------------------------------------------|
 * | [stopBrew]                             | BLE if connected; failure or [STOP_BLE_TIMEOUT_MS] → cloud at once |
 * | [setPower], temps, steam level, on/off | BLE if connected (failure → cloud), else cloud   |
 * | [refreshCloud]                         | cloud; BLE readings are layered on by [compose]  |
 * | [getBrewStartTs]                       | cloud; ShotService prefers the BLE shot counter (2.11.0) when it has one |
 *
 * **The stop never waits on a reconnect.** A disconnected link is skipped
 * outright, and a connected one gets [STOP_BLE_TIMEOUT_MS] before the cloud
 * is called. If the BLE op lands late anyway the machine gets StandBy twice,
 * which is harmless; overshoot in the cup is not.
 *
 * Cloud failures propagate as exceptions, exactly as when callers held a
 * CloudClient. Every call logs its path, and the reason for any fallback,
 * through [log] (INFO in the app; a list in the tests — FileLog needs Android).
 */
class MachineController(
    private val cloud: CloudMachine,
    private val ble: BleMachine?,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** Cloud stop baseline is 0.22-0.27 s; a slower BLE stop has lost its point. */
        const val STOP_BLE_TIMEOUT_MS = 300L

        /** Non-urgent commands: generous, but a hung link must not hang the UI. */
        const val COMMAND_BLE_TIMEOUT_MS = 3_000L

        /**
         * Fold a cloud refresh into the previous snapshot. A failed refresh
         * carries no readings, so the last good values are kept and only the
         * failure is recorded - the Machine page must not claim 0 °C on no
         * evidence.
         */
        fun mergeCloud(previous: MachineSnapshot, fresh: MachineSnapshot): MachineSnapshot =
            if (fresh.lastSyncOk) fresh
            else previous.copy(lastSyncOk = false, lastError = fresh.lastError, cloudProblem = fresh.cloudProblem)

        /**
         * Layer a BLE poll over [base]. BLE is fresher than the cloud for what
         * it reports (mode, boiler targets); everything else - name, firmware,
         * boiler status, ready times - stays as the cloud last said.
         *
         * `status` is left alone unless BLE contradicts power: the cloud's
         * "Brewing" is information BLE does not have.
         */
        fun applyBle(base: MachineSnapshot, r: MicraBleClient.Readings?): MachineSnapshot {
            if (r == null) return base
            val on = r.mode == MicraProtocol.MachineMode.BREWING
            val status = when {
                on == base.powerOn -> base.status
                on -> "PoweredOn"
                else -> r.mode.wire
            }
            return base.copy(
                powerOn = on,
                status = status,
                coffeeTemp = r.coffee?.target ?: base.coffeeTemp,
                steamLevel = r.steam?.let { MicraProtocol.steamCelsiusToLevel(it.target) }
                    ?: base.steamLevel,
                coffeeTempCurrent = r.coffee?.current,
                steamTempCurrent = r.steam?.current,
                steamEnabled = r.steam?.isEnabled ?: base.steamEnabled,
            )
        }

        /**
         * Stamp the link state on [base]. A link that is not up drops the
         * live temperatures: they are BLE-only, and the last ones read would
         * otherwise sit on screen looking current.
         */
        fun withLink(base: MachineSnapshot, state: MicraBleClient.State): MachineSnapshot {
            val up = state == MicraBleClient.State.CONNECTED
            return base.copy(
                bleState = state,
                controlPath = if (up) ControlPath.BLE else ControlPath.CLOUD,
                coffeeTempCurrent = if (up) base.coffeeTempCurrent else null,
                steamTempCurrent = if (up) base.steamTempCurrent else null,
            )
        }

        /**
         * What the machine link is needed for right now
         * ([MicraBleClient.Demand]):
         *  - SHOT while a shot runs, or while one is armed in IDLE on an awake
         *    scale - arming is what gets the link up before the stop needs
         *    it. Armed with the scale asleep cannot detect a shot.
         *  - NONE otherwise. A finished shot is not a reason, armed or not:
         *    the arm outlives the shot (until the pitcher or RESET disarms
         *    it), and counting it in FINALIZED held the link at HIGH with a
         *    2 s poll for up to ~20 min after every espresso. The steam
         *    power-on 7 s after the stop is well inside
         *    [MicraBleClient.RELEASE_AFTER_MS].
         *  - PREP while [prep] holds: a dosing vessel landed recently (2.9.0;
         *    CoffeeRuntime times it out). Connecting while the beans are
         *    weighed shows a wedged machine minutes before the shot needs it
         *    - hang 9 (2026-10-23) began between the weighing and the shot.
         * Nothing else connects (2.6.0): with the app on screen the client
         * only listens for advertising, which is what the status-bar dot shows.
         */
        fun linkDemand(s: AppSnapshot, prep: Boolean = false, armHot: Boolean = true): MicraBleClient.Demand = when {
            s.shot.state == ShotState.BREWING || s.shot.state == ShotState.STOPPING ->
                MicraBleClient.Demand.SHOT
            // [armHot]: the first minutes after the arm (CoffeeRuntime.ARM_HOT_MS).
            // An arm left standing drops to PREP: still connected, but not
            // the HIGH-priority 2 s poll for the ~20 min until the scale idles.
            s.shot.state == ShotState.IDLE && s.shot.armed && s.scale.connected && !s.scale.asleep ->
                if (armHot) MicraBleClient.Demand.SHOT else MicraBleClient.Demand.PREP
            prep -> MicraBleClient.Demand.PREP
            else -> MicraBleClient.Demand.NONE
        }

        /** The machine snapshot to publish: cloud merged, then BLE on top. */
        fun compose(
            previous: MachineSnapshot,
            fresh: MachineSnapshot,
            ble: MicraBleClient.Readings?,
        ): MachineSnapshot = withLink(
            applyBle(mergeCloud(previous, fresh), ble).copy(bleSeen = previous.bleSeen),
            // The cloud knows nothing of the link; carry it across.
            previous.bleState,
        )
    }

    val bleReadings: StateFlow<MicraBleClient.Readings?>? get() = ble?.readings

    /** The BLE link in a word, for log lines. */
    val linkState: String
        get() = when {
            ble == null -> "not configured"
            ble.isConnected -> "connected"
            else -> "not connected"
        }

    /**
     * Whether a stop sent now would go straight to the cloud. A connected
     * link can still time out into the cloud; that case is not foreseen.
     */
    val stopGoesToCloud: Boolean
        get() = ble == null || !ble.isConnected

    /** The machine is pushing brewing events over BLE right now. */
    val shotCounterActive: Boolean
        get() = ble?.let { it.isConnected && it.shotCounterActive } ?: false

    // --- Routed calls -----------------------------------------------------

    /**
     * Over BLE this is [BleMachine.standbyFast]: the full write + status read
     * measured ~500 ms on the machine, past the cloud's 0.22-0.27 s. The write
     * ack alone says the machine has the command.
     */
    suspend fun stopBrew(): Routed = route(
        "BBW stop",
        STOP_BLE_TIMEOUT_MS,
        { if (standbyFast()) WRITE_ACKNOWLEDGED else null },
        { setPower(false) },
    )

    suspend fun setPower(on: Boolean): Routed = route(
        "Power ${if (on) "on" else "standby"}",
        COMMAND_BLE_TIMEOUT_MS,
        { setPower(on) },
        { setPower(on) },
    )

    suspend fun setCoffeeTemp(temperature: Double): Routed {
        val rounded = Math.round(temperature * 10.0) / 10.0
        return route(
            "Coffee target $rounded",
            COMMAND_BLE_TIMEOUT_MS,
            { setCoffeeTarget(rounded) },
            { setCoffeeTemp(rounded) },
        )
    }

    /**
     * [level] is "Level1/2/3". Over BLE it becomes a temperature through
     * [MicraProtocol.steamLevelToCelsius] (126/128/131, confirmed on the
     * machine).
     */
    suspend fun setSteamLevel(level: String): Routed {
        val celsius = MicraProtocol.steamLevelToCelsius(level)
        require(celsius != null) { "Invalid steam level: $level" }
        return route(
            "Steam $level",
            COMMAND_BLE_TIMEOUT_MS,
            { setSteamTarget(celsius) },
            { setSteamLevel(level) },
        )
    }

    suspend fun setSteamEnabled(enabled: Boolean): Routed = route(
        "Steam boiler ${if (enabled) "on" else "off"}",
        COMMAND_BLE_TIMEOUT_MS,
        { setSteamEnabled(enabled) },
        { setSteamEnabled(enabled) },
    )

    suspend fun refreshCloud(): MachineSnapshot = cloud.refresh()

    suspend fun getBrewStartTs(): Double? = cloud.getBrewStartTs()

    // --- Routing ------------------------------------------------------------

    private suspend fun route(
        label: String,
        bleTimeoutMs: Long,
        viaBle: suspend BleMachine.() -> CommandStatus?,
        viaCloud: suspend CloudMachine.() -> Unit,
    ): Routed {
        val b = ble
        val reason = when {
            b == null -> "BLE not configured"
            !b.isConnected -> "BLE not connected"
            else -> {
                val t0 = clock()
                // Wrapped so "timed out" and "returned null" stay distinguishable.
                // A throw from the BLE leg (a GATT stack exception) falls back
                // like any other failure: for a stop, the cloud is always the
                // right next step, and escaping here skipped it.
                val outcome = try {
                    withTimeoutOrNull(bleTimeoutMs) { Result(b.viaBle()) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result(null, e)
                }
                val ms = clock() - t0
                val status = outcome?.status
                when {
                    outcome == null -> "BLE timed out after $bleTimeoutMs ms"
                    outcome.error != null ->
                        "BLE threw ${outcome.error.javaClass.simpleName} after $ms ms: ${outcome.error.message}"
                    status == null -> "BLE command failed after $ms ms"
                    !status.isSuccess -> "BLE status ${status.status} (${status.message})"
                    else -> {
                        log("$label via BLE in $ms ms")
                        return Routed(ControlPath.BLE)
                    }
                }
            }
        }
        log("$label via cloud ($reason)")
        val t0 = clock()
        try {
            cloud.viaCloud()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$label via cloud failed after ${clock() - t0} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        log("$label via cloud done in ${clock() - t0} ms")
        return Routed(ControlPath.CLOUD, reason)
    }

    private class Result(val status: CommandStatus?, val error: Exception? = null)

    /** What a successful [BleMachine.standbyFast] reads as to [route]. */
    private val WRITE_ACKNOWLEDGED = CommandStatus("", "write acknowledged", "success")
}
