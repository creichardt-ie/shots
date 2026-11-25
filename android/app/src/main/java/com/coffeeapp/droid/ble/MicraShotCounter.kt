// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The Micra's Bluetooth shot counter: brewing events the machine pushes on
 * [MicraProtocol.SHOT_COUNTER_CHAR] once subscribed (gateway firmware with
 * "BLE shot timer", 7.4.2 onward). Ported from pylamarzocco 2.5.6
 * (`models/_bluetooth.py` BluetoothBrewingData / BluetoothMachineTelemetry,
 * `clients/_bluetooth.py` _BrewingTracker). Pure: no Android, no IO.
 *
 * Each notification is one small JSON object carrying one or a few keys, e.g.
 * `{"BrewingStartedGroup1DoseIndex":"ContinuousDose"}`, then about once a
 * second `{"BrewingUpdateGroup1Time":3.34,"BrewingUpdateGroup1Ev":true,...}`,
 * then `{"BrewingStoppedGroup1StopType":"Continuous","BrewingStoppedGroup1
 * StopReason":"Manual",...,"BrewingStoppedGroup1Time":12.727}`. Times are
 * seconds of pump-on (inferred: they rise ~1.07 per wall second). Most
 * notifications are telemetry - boiler temperatures every 15-20 s.
 *
 * On subscribe the machine first replays its last notification, which can
 * be hours old; the client drops it.
 */
object MicraShotCounter {

    enum class BrewState { BREWING, BREWING_STOPPED, FLUSHED, BACKFLUSHING }

    /** The brewing keys of one notification, or several merged; all optional. */
    data class BrewingData(
        val startedDoseIndex: String? = null,
        val ev: Boolean? = null,
        val backflush: Boolean? = null,
        val brewingTime: Double? = null,
        val flushStoppedTime: Double? = null,
        val flushStoppedDoseIndex: String? = null,
        val brewingStoppedTime: Double? = null,
        val brewingStoppedDoseIndex: String? = null,
        val stopType: String? = null,
        val stopReason: String? = null,
    ) {
        val isEmpty: Boolean get() = this == BrewingData()

        /** A copy overridden by every field [other] sets (pylamarzocco `merge`). */
        fun merge(other: BrewingData) = BrewingData(
            startedDoseIndex = other.startedDoseIndex ?: startedDoseIndex,
            ev = other.ev ?: ev,
            backflush = other.backflush ?: backflush,
            brewingTime = other.brewingTime ?: brewingTime,
            flushStoppedTime = other.flushStoppedTime ?: flushStoppedTime,
            flushStoppedDoseIndex = other.flushStoppedDoseIndex ?: flushStoppedDoseIndex,
            brewingStoppedTime = other.brewingStoppedTime ?: brewingStoppedTime,
            brewingStoppedDoseIndex = other.brewingStoppedDoseIndex ?: brewingStoppedDoseIndex,
            stopType = other.stopType ?: stopType,
            stopReason = other.stopReason ?: stopReason,
        )

        /** pylamarzocco `derive_state`, same priority order. */
        fun deriveState(receivedAtMs: Long): BrewUpdate? {
            val state = when {
                brewingStoppedTime != null || brewingStoppedDoseIndex != null ||
                    stopType != null || stopReason != null || ev == false -> BrewState.BREWING_STOPPED
                flushStoppedTime != null || flushStoppedDoseIndex != null -> BrewState.FLUSHED
                backflush == true -> BrewState.BACKFLUSHING
                startedDoseIndex != null || (ev == true && brewingTime != null) -> BrewState.BREWING
                else -> return null
            }
            val timer = if (state == BrewState.BREWING) brewingTime ?: 0.0 else null
            return BrewUpdate(
                state = state,
                timerSeconds = timer,
                finalShotSeconds = brewingStoppedTime,
                flushSeconds = flushStoppedTime,
                doseIndex = startedDoseIndex ?: brewingStoppedDoseIndex ?: flushStoppedDoseIndex,
                stopType = stopType,
                stopReason = stopReason,
                receivedAtMs = receivedAtMs,
                brewingStartMs = timer?.let { receivedAtMs - (it * 1000).toLong() },
            )
        }
    }

    /**
     * One brewing state, as derived after a notification. [brewingStartMs]
     * (wall clock, while BREWING) is when the pump came on: the earliest
     * receipt time minus timer seen this brew, since notifications can only
     * arrive late.
     */
    data class BrewUpdate(
        val state: BrewState,
        val timerSeconds: Double?,
        val finalShotSeconds: Double?,
        val flushSeconds: Double?,
        val doseIndex: String?,
        val stopType: String?,
        val stopReason: String?,
        val receivedAtMs: Long,
        val brewingStartMs: Long?,
    )

    /** Live machine values riding on the same characteristic; usually one per notification. */
    data class Telemetry(
        val coffeeBoilerTemp: Double? = null,
        val steamBoilerTemp: Double? = null,
        val machineMode: String? = null,
        val sleep: String? = null,
        /** `MachineStatistics`, sent as a JSON string; counters unverified upstream. */
        val statistics: JsonObject? = null,
    ) {
        val isEmpty: Boolean get() = this == Telemetry()
    }

    /** One notification, split into its two halves; keys we do not know are left out. */
    data class Notification(val brewing: BrewingData, val telemetry: Telemetry, val keys: Set<String>)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse one notification, NUL padding stripped (pylamarzocco
     * `json.loads(bytes(data).strip(b"\x00"))`). Null if it is not a JSON
     * object.
     */
    fun parse(raw: ByteArray): Notification? {
        val text = raw.toString(Charsets.UTF_8).trim('\u0000', ' ', '\n', '\r', '\t')
        if (text.isEmpty()) return null
        val o = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun num(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        fun bool(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val brewing = BrewingData(
            startedDoseIndex = str("BrewingStartedGroup1DoseIndex"),
            ev = bool("BrewingUpdateGroup1Ev"),
            backflush = bool("BrewingUpdateGroup1Backflush"),
            brewingTime = num("BrewingUpdateGroup1Time"),
            flushStoppedTime = num("FlushStoppedGroup1Time"),
            flushStoppedDoseIndex = str("FlushStoppedGroup1DoseIndex"),
            brewingStoppedTime = num("BrewingStoppedGroup1Time"),
            brewingStoppedDoseIndex = str("BrewingStoppedGroup1DoseIndex"),
            stopType = str("BrewingStoppedGroup1StopType"),
            stopReason = str("BrewingStoppedGroup1StopReason"),
        )
        val stats = str("MachineStatistics")?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (_: Exception) {
                null
            }
        }
        val telemetry = Telemetry(
            coffeeBoilerTemp = num("CoffeeBoiler1UpdateTemperature"),
            steamBoilerTemp = num("SteamBoilerUpdateTemperature"),
            machineMode = str("MachineMode"),
            sleep = str("Sleep"),
            statistics = stats,
        )
        return Notification(brewing, telemetry, o.keys)
    }

    /**
     * Folds the brewing halves of successive notifications into a state
     * (pylamarzocco `_BrewingTracker`): a start resets it, a stop or flush
     * ends it, and the start time keeps the earliest estimate.
     */
    class Tracker {
        private var data = BrewingData()
        private var startMs: Long? = null

        fun reset() {
            data = BrewingData()
            startMs = null
        }

        fun process(message: BrewingData, receivedAtMs: Long): BrewUpdate? {
            if (message.startedDoseIndex != null) reset()
            data = data.merge(message)
            val update = data.deriveState(receivedAtMs) ?: return null
            return when (update.state) {
                BrewState.FLUSHED, BrewState.BREWING_STOPPED -> {
                    // The stop carries no start; keep the one this brew had.
                    val start = startMs
                    reset()
                    update.copy(brewingStartMs = start)
                }
                else -> {
                    val est = update.brewingStartMs
                    if (est != null && (startMs == null || est < startMs!!)) startMs = est
                    update.copy(brewingStartMs = startMs)
                }
            }
        }
    }
}
