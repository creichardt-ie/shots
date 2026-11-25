// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ble

import android.content.Context
import com.coffeeapp.droid.core.log.FileLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One reading from the scale.
 *
 * [ts] is wall-clock seconds because every
 * threshold downstream (rise_seconds, stable_seconds, finish_stability_seconds)
 * is expressed in seconds. Keeping the unit identical is what lets the ported
 * state machine and placement detector stay numerically unchanged.
 */
data class WeightSample(
    val ts: Double,
    val weight: Float,
    val battery: Int?,
    val connected: Boolean,
    val asleep: Boolean = false,
)

/**
 * Owns the scale client and turns its callbacks into a sample stream.
 *
 * All subscriber fan-out stays single-threaded: [scope] is backed by the
 * runtime's single-threaded dispatcher. Callbacks arrive on a binder thread
 * and are hopped onto the scope before anything shared is touched.
 *
 * Subscribers get drop-oldest buffering: a slow collector falls behind without
 * stalling the notification callback or the other collectors.
 */
class ScaleAdapter(
    context: Context,
    private val mac: String,
    /** Which protocol to speak (2.16.0); the rest of this class is brand-neutral. */
    model: ScaleModel = ScaleModel.ACAIA,
    isNewStyle: Boolean = true,
    /** Seconds of no meaningful weight change before the link is dropped. 0 disables. */
    private val idleTimeoutSeconds: Double = 0.0,
    /**
     * How much the weight must change to count as activity. Deliberately
     * coarse (10 g) and separate from the fine flow_detection_threshold:
     * a resting scale flickers well above 0.05 g, so a shared threshold
     * re-armed the timer on every jitter tick and the scale never slept.
     */
    private val activityThreshold: Float = 10.0f,
    notifyChar: String = "",
    writeChar: String = "",
    private val scope: CoroutineScope,
) : com.coffeeapp.droid.core.shot.ScaleSource {

    private companion object {
        const val TAG = "ScaleAdapter"
    }

    private val _samples = MutableSharedFlow<WeightSample>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The sample stream. Both ShotService and the snapshot pump collect it. */
    override val samples: SharedFlow<WeightSample> = _samples.asSharedFlow()

    private val client = ScaleGattClient(
        context = context,
        mac = mac,
        protocol = when (model) {
            ScaleModel.ACAIA -> AcaiaScaleProtocol(isNewStyle, notifyChar, writeChar)
            ScaleModel.BOOKOO -> BookooScaleProtocol()
        },
        scope = scope,
        onNotify = ::onScaleData,
        onDropped = ::onDropped,
    )

    /**
     * Whether a dropped link is worth reconnecting by itself: true while a
     * shot is armed or running. Otherwise waking stays manual, as it always
     * was - the scale switching itself off is the common case, and chasing it
     * would spend a minute of connect attempts after every session.
     */
    var wantLink: () -> Boolean = { false }

    /**
     * True while a shot is live: the idle clock is held at zero. Activity is
     * counted in [activityThreshold] steps, so a shot started late in a long
     * wait could otherwise be put to sleep in pre-infusion, before the cup
     * had gained enough to reset the clock - ending the shot as "scale lost"
     * with no BBW stop.
     */
    var keepAwake: () -> Boolean = { false }

    private var autoReconnectJob: Job? = null

    /** The last battery level logged, so a line is written only on a real change. */
    private var lastBatteryLogged: Int? = null

    /** Serialises connect/disconnect so a wake cannot race the idle watcher. */
    private val linkMutex = Mutex()

    @Volatile
    private var stopped: Boolean = false

    @Volatile
    private var asleep: Boolean = false

    private var lastActivityAt: Long = System.currentTimeMillis()
    private var lastActivityWeight: Float? = null
    private var idleJob: Job? = null

    val connected: Boolean get() = client.connected

    // --- Lifecycle -------------------------------------------------------

    suspend fun start() {
        stopped = false
        asleep = false
        lastActivityAt = System.currentTimeMillis()
        // Under the link lock: a WAKE or TARE pressed while this first connect
        // is still retrying (up to ~80 s with the scale off) used to run a
        // second connect on the same client, each closing the other's GATT.
        linkMutex.withLock {
            try {
                connectWithRecovery(force = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A missing or unreachable scale must not bring down the runtime.
                Log.w(TAG, "Scale connect failed; starting disconnected", e)
            }
        }

        if (idleTimeoutSeconds > 0) {
            idleJob = scope.launch { idleWatcher() }
        }
    }

    suspend fun stop() {
        // Set first: a final notification can fire during teardown and must
        // not be published.
        stopped = true
        idleJob?.cancel()
        idleJob = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        try {
            client.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "Scale disconnect raised during stop", e)
        }
    }

    // --- Commands --------------------------------------------------------

    /**
     * Zero the scale, waking it first if the link is down.
     *
     * A user pressing tare is an explicit interaction, so waking here does not
     * violate the otherwise-manual wake policy — and a tare issued against a
     * sleeping scale would otherwise vanish silently.
     */
    override suspend fun tare() {
        if (!client.connected) {
            if (!reconnect("tare")) {
                Log.w(TAG, "Tare requested but the scale could not be woken; skipping")
                return
            }
        }
        client.tare()
    }

    /**
     * Manual wake / reconnect.
     *
     * Clears the sleep state and resets the idle clock so a freshly woken
     * scale does not immediately drop again, then forces past the client
     * reconnect cooldown — this is a deliberate user action, not a flapping
     * link, and honouring the cooldown here is what used to make the wake
     * button do nothing at all.
     */
    suspend fun reconnect(source: String = "wake button"): Boolean = linkMutex.withLock {
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Scale wake requested (source=$source, asleep=$asleep, connected=${client.connected})")
        asleep = false
        lastActivityAt = System.currentTimeMillis()
        lastActivityWeight = null

        if (client.connected) {
            try {
                client.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Disconnect raised during reconnect", e)
            }
        }

        val ok = try {
            connectWithRecovery(force = true)
        } catch (e: Exception) {
            Log.w(TAG, "Scale reconnect failed", e)
            false
        }
        if (ok) {
            Log.i(TAG, "Scale reconnected/woken (mac=$mac) in ${System.currentTimeMillis() - t0} ms")
            lastBatteryLogged = null
        } else {
            Log.w(TAG, "Scale reconnect failed after ${System.currentTimeMillis() - t0} ms")
        }
        ok
    }

    /**
     * The link dropped on the scale's side. Reconnect by ourselves only while
     * a shot is armed or running ([wantLink]); up to three tries, backing off.
     */
    private fun onDropped() {
        if (stopped || asleep) return
        if (!wantLink()) {
            Log.i(TAG, "Scale dropped; no shot armed, so waking stays manual")
            return
        }
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob = scope.launch {
            for ((i, waitMs) in longArrayOf(2_000, 10_000, 30_000).withIndex()) {
                delay(waitMs)
                if (stopped || asleep || client.connected || !wantLink()) return@launch
                Log.i(TAG, "Scale dropped with a shot armed; reconnect try ${i + 1}/3")
                if (reconnect("auto after drop")) return@launch
            }
            Log.w(TAG, "Scale did not come back after 3 tries; wake it by hand")
        }
    }

    /**
     * Connect, retrying once on failure.
     *
     * Android's stack does not pin a device Connected across an unclean drop
     * (BlueZ on Linux needed a ghost-connection purge here), so the recovery
     * is just the retry. If a device does get wedged, the remedy on
     * Android is toggling Bluetooth, which is a user action, not ours.
     */
    private suspend fun connectWithRecovery(force: Boolean): Boolean {
        if (client.connect(force = force)) return true
        Log.i(TAG, "First connect attempt failed; retrying")
        return client.connect(force = true)
    }

    // --- Callback bridge -------------------------------------------------

    /**
     * Called by the GATT client on a binder thread for every packet.
     *
     * Snapshots the client state immediately (so the reading published is the
     * one that arrived) and hops onto the scope, where all fan-out and all
     * idle-timer bookkeeping happen single-threaded.
     */
    private fun onScaleData() {
        if (stopped) return
        val sample = WeightSample(
            ts = System.currentTimeMillis() / 1000.0,
            weight = client.weight ?: 0.0f,
            battery = client.battery,
            connected = client.connected,
            asleep = asleep,
        )
        scope.launch { publish(sample) }
    }

    private fun publish(sample: WeightSample) {
        logBattery(sample.battery)
        // Drive the idle-disconnect timer off meaningful weight changes only.
        // Skipped while asleep so the synthetic sleep sample below cannot
        // reset the very clock that produced it.
        if (idleTimeoutSeconds > 0 && !asleep) {
            val w = sample.weight
            val last = lastActivityWeight
            if (last == null || kotlin.math.abs(w - last) > activityThreshold) {
                lastActivityWeight = w
                lastActivityAt = System.currentTimeMillis()
            }
        }
        _samples.tryEmit(sample)
    }

    /**
     * First reading after a connect, then a move of more than 10 points from
     * the last line, or a first drop below 15 % (WARN). The Bookoo reports
     * in 10 % steps and flips between neighbours (70/80 every ~2 s,
     * 2026-11-25), so a 10-point bucket boundary is not a change (2.17.1).
     */
    private fun logBattery(level: Int?) {
        if (level == null) return
        val last = lastBatteryLogged
        val crossedLow = level < 15 && (last == null || last >= 15)
        if (last != null && kotlin.math.abs(level - last) <= 10 && !crossedLow) return
        lastBatteryLogged = level
        if (level < 15) Log.w(TAG, "Scale battery $level%") else Log.i(TAG, "Scale battery $level%")
    }

    // --- Idle disconnect -------------------------------------------------

    /**
     * Drops the link after [idleTimeoutSeconds] without a meaningful weight
     * change, so the scale reaches its own auto-off and saves battery.
     *
     * A live shot holds the clock at zero ([keepAwake]); weight steps alone
     * only reset it once 10 g has poured.
     */
    private suspend fun idleWatcher() {
        val checkIntervalMs = (minOf(15.0, maxOf(1.0, idleTimeoutSeconds / 4.0)) * 1000).toLong()
        while (scope.isActive) {
            try {
                delay(checkIntervalMs)
                if (stopped || asleep) continue
                if (!client.connected) continue
                if (keepAwake()) {
                    lastActivityAt = System.currentTimeMillis()
                    continue
                }
                val idleFor = (System.currentTimeMillis() - lastActivityAt) / 1000.0
                if (idleFor >= idleTimeoutSeconds) sleepNow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scale idle watcher iteration failed", e)
            }
        }
    }

    /** Disconnect on idle so the scale auto-powers-off. Waking is manual. */
    private suspend fun sleepNow() = linkMutex.withLock {
        Log.i(
            TAG,
            "Scale idle for >=${idleTimeoutSeconds.toInt()}s; dropping the BLE link to " +
                "save battery (wake manually via the reconnect button).",
        )
        try {
            client.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "Disconnect raised during idle sleep", e)
        }
        asleep = true
        // Publish one final sample so the UI shows the sleep straight away —
        // no further notifications arrive until the scale is woken.
        publish(
            WeightSample(
                ts = System.currentTimeMillis() / 1000.0,
                weight = 0.0f,
                battery = null,
                connected = false,
                asleep = true,
            ),
        )
    }
}
