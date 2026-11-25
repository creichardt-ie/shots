// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.
@file:Suppress("DEPRECATION")

package com.coffeeapp.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.coffeeapp.droid.ble.MicraProtocol.Boiler
import com.coffeeapp.droid.ble.MicraProtocol.CommandStatus
import com.coffeeapp.droid.ble.MicraProtocol.MachineMode
import com.coffeeapp.droid.ble.MicraProtocol.ReadSetting
import com.coffeeapp.droid.core.log.FileLog as Log
import com.coffeeapp.droid.core.machine.BleMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android GATT client for the La Marzocco Micra — the replacement for
 * pylamarzocco's LaMarzoccoBluetoothClient.
 *
 * Used the way the official app uses the machine: in short sessions with
 * light traffic. [setDemand] says what the link is for right now:
 *  - [Demand.SHOT] (a shot armed or running): connected, connection priority
 *    HIGH so the brew-by-weight stop lands in ~50 ms, polled every
 *    [POLL_INTERVAL_MS].
 *  - [Demand.PREP] (beans being weighed, a shot likely within minutes):
 *    connected at BALANCED, polled every [SLOW_POLL_INTERVAL_MS], so a wedged
 *    machine shows on the dot in time to power-cycle it before the shot, and
 *    the shot finds the link already up. Retries with [backoffMs] like SHOT.
 *  - [Demand.NONE]: released cleanly [RELEASE_AFTER_MS] later, and no
 *    reconnecting until demand returns. A session bought by [reconnectNow]
 *    runs at NONE: BALANCED, polled every [SLOW_POLL_INTERVAL_MS].
 * A supervisor loop connects, polls `machineMode` and `boilers`, and on any
 * drop reconnects with [backoffMs]. [reconnectNow] skips the wait.
 *
 * Why: the Micra's BLE wedged (stopped advertising and answering until
 * power-cycled) four times in two days, each time after one of our sessions
 * ended - twice after links held for hours at HIGH priority with a 2 s poll
 * (~7000 GATT ops an hour), once after a *clean* disconnect. The official
 * app, which never holds or polls like that, has never wedged it. Each
 * session logs its length and op count so a recurrence can be tied to what
 * preceded it. A fifth wedge (2026-10-19) followed one light 2-minute session
 * and ~23 h of silence, so a 15-minute background advertising probe timed
 * when it went dormant (2.3.0-2.6.0; hangs 6-8 on 2026-10-22). Since 2.7.0
 * the client probes only while the app is on screen
 * ([FOREGROUND_PROBE_INTERVAL_MS], scan only), for the status-bar dot.
 *
 * Same two rules as ScaleGattClient, for the same reasons:
 *  1. **One GATT operation at a time.** Every exchange (write, then read back)
 *     runs under [opLock]. Each exchange runs as its own job in [scope], so a
 *     caller that stops waiting — the brew-by-weight stop gives BLE ~300 ms —
 *     abandons only its await. The exchange still finishes and releases the
 *     lock; cancelling it mid-flight would leave Android with an outstanding
 *     operation and silently drop the next one.
 *  2. **Characteristics resolved across all services**: the Micra's service
 *     UUID is not published.
 *
 * Callbacks arrive on a binder thread and only complete deferreds; all work
 * runs on [scope], the runtime's single-threaded dispatcher.
 *
 * **Shot counter (2.11.0).** Firmware with the BLE shot timer pushes brewing
 * events and telemetry on [MicraProtocol.SHOT_COUNTER_CHAR]. Each session
 * subscribes after auth (pylamarzocco 2.5 keeps a permanent link for it; we
 * only listen while a session is up anyway, which covers PREP and the shot),
 * drops the replay of the machine's last event that a subscribe triggers, and
 * publishes derived states on [brewUpdates]. A subscribe that times out
 * leaves the GATT queue wedged, so it drops the link and turns the shot
 * counter off until the app restarts - control is worth more than events.
 *
 * The token comes from [tokenProvider] on every connect attempt, so a token
 * synced from the cloud after a BadToken is picked up without a rebuild.
 * The token is never logged.
 */
@SuppressLint("MissingPermission")
class MicraBleClient(
    private val context: Context,
    private val address: String,
    private val tokenProvider: () -> String,
    private val scope: CoroutineScope,
) : BleMachine {

    /** RELEASED: let go on purpose because nothing wanted it; reconnects on demand. */
    enum class State { OFF, CONNECTING, CONNECTED, BAD_TOKEN, CONFIG_MODE, UNREACHABLE, RELEASED }

    /** What the link is needed for right now; see the class doc. */
    enum class Demand { NONE, PREP, SHOT }

    /** One poll's worth of machine state. [atMillis] is when it was read. */
    data class Readings(
        val mode: MachineMode,
        val boilers: List<Boiler>,
        val atMillis: Long,
    ) {
        val coffee: Boiler? get() = boilers.firstOrNull { it.id == MicraProtocol.COFFEE_BOILER }
        val steam: Boiler? get() = boilers.firstOrNull { it.id == MicraProtocol.STEAM_BOILER }
    }

    /** What the characteristics found after discovery say about the machine. */
    enum class Discovery { READY, CONFIG_MODE, MISSING }

    companion object {
        private const val TAG = "MicraBleClient"
        private const val NUL = '\u0000'

        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Longest wait for an advertisement before connecting anyway. */
        const val PRE_CONNECT_SCAN_MS = 5_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L
        const val MTU_TIMEOUT_MS = 3_000L
        const val OP_TIMEOUT_MS = 5_000L
        /** Poll interval during a shot. */
        const val POLL_INTERVAL_MS = 2_000L

        /** Poll interval outside a shot (a RECONNECT NOW session). */
        const val SLOW_POLL_INTERVAL_MS = 10_000L

        /** How finely a poll wait notices a demand change. */
        private const val WAIT_SLICE_MS = 250L

        fun pollIntervalMs(demand: Demand): Long =
            if (demand == Demand.SHOT) POLL_INTERVAL_MS else SLOW_POLL_INTERVAL_MS

        /** HIGH only where the stop's latency matters; the default otherwise. */
        fun priorityFor(demand: Demand): Int =
            if (demand == Demand.SHOT) BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else BluetoothGatt.CONNECTION_PRIORITY_BALANCED

        /** Commands are ~90 bytes and `boilers` ~130; the default 23 would split both. */
        const val REQUESTED_MTU = 247

        /** Client Characteristic Configuration descriptor, for subscribing. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * A notification this soon after subscribing is the machine replaying
         * its last one (possibly hours old) and is dropped. Later than this
         * it is real: a brew start must not be thrown away for want of a
         * replay that never came.
         */
        const val REPLAY_WINDOW_MS = 2_000L

        /** Reads that fail in a row before the link is treated as dead. */
        const val MAX_POLL_FAILURES = 3

        /**
         * How long the link stays up after demand ends. Covers the steam
         * power-on 7 s after a shot and a quick switch to another app;
         * pylamarzocco itself lets go after 30 s.
         */
        const val RELEASE_AFTER_MS = 2 * 60_000L

        /** While released, how often to re-check in case a wake was missed. */
        private const val RELEASED_RECHECK_MS = 30_000L

        /** How long a probe listens; sightings have taken up to ~1.7 s. */
        const val ADVERT_PROBE_SCAN_MS = 10_000L

        /**
         * Probe interval while the app is on screen, so the status-bar dot
         * says whether the machine is there before a shot needs it. A probe
         * ends at the first sighting (usually well under a second).
         */
        const val FOREGROUND_PROBE_INTERVAL_MS = 60_000L

        /** A probe this soon after any scan start is skipped (scan budget). */
        const val MIN_PROBE_GAP_MS = 20_000L

        /** Android's scan-start limit: five per thirty seconds per app. */
        private const val MAX_SCAN_STARTS_PER_WINDOW = 5
        private const val SCAN_WINDOW_MS = 30_000L

        /** GATT status with the common names spelled out, for the log. */
        fun gattStatusName(status: Int?): String = when (status) {
            null -> "unknown"
            0 -> "0 (SUCCESS)"
            8 -> "8 (CONN_TIMEOUT)"
            19 -> "19 (PEER_TERMINATED)"
            22 -> "22 (LOCAL_HOST_TERMINATED)"
            62 -> "62 (FAIL_ESTABLISH)"
            133 -> "133 (GATT_ERROR)"
            else -> status.toString()
        }

        fun scanErrorName(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "$code (ALREADY_STARTED)"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "$code (APPLICATION_REGISTRATION_FAILED)"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "$code (INTERNAL_ERROR)"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "$code (FEATURE_UNSUPPORTED)"
            else -> code.toString()
        }

        /** "45s", "12m", "3h05m" - for the probe lines. */
        fun formatAge(ms: Long): String {
            val s = ms.coerceAtLeast(0) / 1000
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m"
                else -> "${s / 3600}h${(s % 3600 / 60).toString().padStart(2, '0')}m"
            }
        }

        /** True once the link has gone unwanted for [RELEASE_AFTER_MS]. Null = wanted. */
        fun releaseDue(unwantedSince: Long?, now: Long): Boolean =
            unwantedSince != null && now - unwantedSince >= RELEASE_AFTER_MS

        /**
         * Release instead of retrying: a connect has failed and nothing needs
         * the link. A RECONNECT NOW session still retries for its full
         * [RELEASE_AFTER_MS] - the machine may be booting after a power-cycle.
         */
        fun skipRetry(failures: Int, demand: Demand, manual: Boolean): Boolean =
            failures > 0 && demand == Demand.NONE && !manual

        private val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)

        /** Wait before reconnect attempt [failures] (1-based), capped at a minute. */
        fun backoffMs(failures: Int): Long =
            BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.size - 1)]

        /**
         * `0c` (token) present with `0d` (auth) absent is configuration mode:
         * the machine needs a restart before it will accept commands.
         */
        fun classify(present: Set<String>): Discovery {
            val p = present.map { it.lowercase() }.toSet()
            val core = listOf(MicraProtocol.READ_CHAR, MicraProtocol.WRITE_CHAR, MicraProtocol.AUTH_CHAR)
            return when {
                MicraProtocol.TOKEN_CHAR in p && MicraProtocol.AUTH_CHAR !in p -> Discovery.CONFIG_MODE
                core.all { it in p } -> Discovery.READY
                else -> Discovery.MISSING
            }
        }

        fun isMachineName(name: String?): Boolean =
            name != null && MicraProtocol.MACHINE_NAME_PREFIXES.any { name.startsWith(it) }

        /**
         * Scan for [timeoutMs] and return every La Marzocco machine seen, as
         * (name, address), for picking the machine in Settings.
         */
        suspend fun scan(context: Context, timeoutMs: Long = 8_000L): List<Pair<String, String>> {
            val adapter = adapter(context)
            val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
                Log.w(TAG, "Scan skipped: Bluetooth unavailable or off")
                return emptyList()
            }
            val found = linkedMapOf<String, String>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: result.device.name
                    if (isMachineName(name)) {
                        synchronized(found) { found[result.device.address] = name!! }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Scan failed: error $errorCode")
                }
            }
            scanner.startScan(callback)
            try {
                delay(timeoutMs)
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
            return synchronized(found) { found.map { (addr, name) -> name to addr } }
        }

        private fun adapter(context: Context): BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _readings = MutableStateFlow<Readings?>(null)

    /** Latest poll; null until the first one, and cleared when the link drops. */
    override val readings: StateFlow<Readings?> = _readings.asStateFlow()

    override val isConnected: Boolean get() = _state.value == State.CONNECTED

    private val _seen = MutableStateFlow(false)

    /**
     * Whether the machine was advertising or answering at the last look: a
     * probe, a pre-connect scan or a connect. Only the status-bar dot reads
     * it; nothing routes on it.
     */
    val seen: StateFlow<Boolean> = _seen.asStateFlow()

    private val _brewUpdates = MutableSharedFlow<MicraShotCounter.BrewUpdate>(extraBufferCapacity = 32)

    /**
     * Brewing states derived from the shot counter while a session is up.
     * Nothing is emitted when the link drops; consumers judge freshness by
     * [MicraShotCounter.BrewUpdate.receivedAtMs] (updates come ~1 s apart
     * while brewing).
     */
    val brewUpdates: SharedFlow<MicraShotCounter.BrewUpdate> = _brewUpdates.asSharedFlow()

    private val _shotCounterActive = MutableStateFlow(false)

    /** Subscribed to the shot counter on the current link. */
    val shotCounterState: StateFlow<Boolean> = _shotCounterActive.asStateFlow()

    override val shotCounterActive: Boolean get() = _shotCounterActive.value

    // --- GATT internals (written from the binder thread) ------------------

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var readChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var authChar: BluetoothGattCharacteristic? = null
    @Volatile private var shotCounterChar: BluetoothGattCharacteristic? = null
    @Volatile private var descriptorDeferred: CompletableDeferred<Boolean>? = null

    // Shot counter state; the tracker is touched only on [scope] (single-threaded).
    private val brewTracker = MicraShotCounter.Tracker()
    @Volatile private var replayPending = false
    @Volatile private var subscribedAt = 0L
    private var brewStartLogged = false
    private val shotCounterNotifications = AtomicInteger(0)

    /** Set when a subscribe wedged the link: no more subscribing until restart. */
    @Volatile private var shotCounterDisabled = false

    /** One line per process naming what the machine offers. */
    @Volatile private var characteristicsLogged = false

    /** The tank is read once per session, outside shots. */
    private var tankReadThisSession = false

    @Volatile private var connectDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var mtuDeferred: CompletableDeferred<Int>? = null
    @Volatile private var writeDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var readDeferred: CompletableDeferred<ByteArray?>? = null

    /** Completed by the binder thread when an established link drops. */
    @Volatile private var linkLost: CompletableDeferred<Unit>? = null

    /** Status of the last connection-state callback during a connect; null = none came. */
    @Volatile private var lastConnectStatus: Int? = null

    /** Status of the last onServicesDiscovered. */
    @Volatile private var lastDiscoverStatus: Int? = null

    private val opLock = Mutex()

    /** Priority ops waiting for [opLock]; polls step aside while non-zero. */
    private val priorityWaiting = AtomicInteger(0)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var supervisor: Job? = null

    // --- Lifecycle --------------------------------------------------------

    /** Start holding the link. Idempotent. */
    fun start() {
        if (supervisor?.isActive == true) return
        Log.i(TAG, "Starting machine link to $address")
        supervisor = scope.launch { superviseLink() }
    }

    /** Drop the link and stop reconnecting. */
    suspend fun stop() {
        supervisor?.cancel()
        supervisor = null
        teardown()
        _readings.value = null
        _state.value = State.OFF
        _seen.value = false
        Log.i(TAG, "Machine link stopped")
    }

    /**
     * Skip the remaining backoff — for an explicit user action. With no
     * demand it buys one [RELEASE_AFTER_MS] session rather than nothing.
     */
    fun reconnectNow() {
        if (_demand.value == Demand.NONE) {
            unwantedSince = System.currentTimeMillis()
            manualSession = true
        }
        kick.trySend(Unit)
    }

    /** The current no-demand session came from [reconnectNow]; see [skipRetry]. */
    @Volatile private var manualSession = false

    private val _demand = MutableStateFlow(Demand.NONE)

    /**
     * When demand last went to NONE; null while there is demand. Starts long
     * ago, so a fresh start with no demand does not connect at all.
     */
    @Volatile private var unwantedSince: Long? = 0L

    /**
     * What the link is needed for (class doc). Changes are logged at INFO -
     * a few per shot. Demand returning cuts short a release or a backoff;
     * a change of level takes effect within [WAIT_SLICE_MS].
     */
    fun setDemand(demand: Demand) {
        val prev = _demand.value
        if (prev == demand) return
        _demand.value = demand
        manualSession = false
        if (demand == Demand.NONE) {
            unwantedSince = System.currentTimeMillis()
            Log.i(TAG, "Machine link demand $prev -> NONE; releasing in ${RELEASE_AFTER_MS / 1000}s unless needed")
        } else {
            unwantedSince = null
            Log.i(TAG, "Machine link demand $prev -> $demand (state ${_state.value})")
            // A shot arming during a PREP backoff tries again at once; a kick
            // left over while connected would only cut a later backoff short.
            val connected = _state.value == State.CONNECTED
            if (prev == Demand.NONE || (demand == Demand.SHOT && !connected)) kick.trySend(Unit)
        }
    }

    /**
     * Never while there is demand, whatever [unwantedSince] says: a stale
     * value (a RECONNECT NOW racing an arm) must not release the link under
     * a shot.
     */
    private fun releaseDue() =
        _demand.value == Demand.NONE && releaseDue(unwantedSince, System.currentTimeMillis())

    /** The app is on screen: probe every [FOREGROUND_PROBE_INTERVAL_MS]. */
    @Volatile private var appVisible = false

    /** Set when the app comes on screen; the released loop probes at once. */
    @Volatile private var probeRequested = false

    /**
     * The app came on screen or left it. Coming on screen probes straight
     * away - scan only, never a connect - so the dot is current when someone
     * is about to use the machine. The kick only goes to a released link; a
     * link in backoff must not be hurried by this.
     */
    fun setAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        if (visible) {
            probeRequested = true
            if (_state.value == State.RELEASED) kick.trySend(Unit)
        }
    }

    // Per-session counters, for the line logged when a session ends.
    private var sessionStartedAt = 0L
    private val sessionOps = AtomicInteger(0)

    /** When the last session ended; 0 = none since start. For the probe lines. */
    private var sessionEndedAt = 0L

    private fun logSessionEnd(how: String) {
        sessionEndedAt = System.currentTimeMillis()
        val secs = (sessionEndedAt - sessionStartedAt) / 1000
        val sc = if (shotCounterNotifications.get() > 0) {
            ", ${shotCounterNotifications.get()} shot-counter notifications"
        } else {
            ""
        }
        Log.i(TAG, "Machine session ended ($how) after ${secs / 60}m${secs % 60}s, ${sessionOps.get()} GATT ops$sc")
    }

    private suspend fun superviseLink() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            // One bad iteration must not end the supervisor, or take the
            // process with it: connectGatt and friends throw when the
            // Bluetooth stack restarts or a permission is revoked.
            try {
                failures = superviseOnce(failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Machine link supervisor error; tearing down and backing off", e)
                _readings.value = null
                runCatching { teardown() }
                if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) {
                    _state.value = State.UNREACHABLE
                }
                failures++
                withTimeoutOrNull(backoffMs(failures)) { kick.receive() }
            }
        }
    }

    /** One pass of the supervisor loop; returns the updated failure count. */
    private suspend fun superviseOnce(failuresIn: Int): Int {
        var failures = failuresIn
        run {
            if (releaseDue()) {
                // Not retrying while released is half the point: overnight
                // retries against a wedged machine went on for eight hours.
                _state.value = State.RELEASED
                var nextProbeAt = 0L
                while (releaseDue()) {
                    val now = System.currentTimeMillis()
                    // Off screen nothing probes (2.7.0): the dot is only
                    // looked at with the app open, and coming on screen
                    // kicks a probe at once.
                    if (appVisible && (now >= nextProbeAt || probeRequested)) {
                        probeRequested = false
                        probeAdvertising()
                        nextProbeAt = System.currentTimeMillis() + FOREGROUND_PROBE_INTERVAL_MS
                        continue
                    }
                    val wait = if (appVisible) minOf(RELEASED_RECHECK_MS, nextProbeAt - now) else RELEASED_RECHECK_MS
                    withTimeoutOrNull(wait) { kick.receive() }
                }
                failures = 0
                Log.i(TAG, "Machine link reconnecting after release")
            }
            if (skipRetry(failures, _demand.value, manualSession)) {
                // A shot that ended mid-backoff left retries nobody is waiting
                // for (2026-10-23: four more 15 s connects against a machine
                // that was not advertising). Release now, not after the grace.
                Log.i(TAG, "Machine link not retried: no demand after failure $failures; releasing")
                unwantedSince = 0L
                return failures
            }
            _state.value = State.CONNECTING
            sessionOps.set(0)
            val result = connectOnce()
            _state.value = result
            if (result == State.CONNECTED) {
                failures = 0
                sessionStartedAt = System.currentTimeMillis()
                // A kick sent while connecting (a demand change, RECONNECT NOW)
                // is spent; left in the conflated channel it would later cut
                // one backoff to zero for no reason.
                kick.tryReceive()
                val released = pollUntilLost()
                _readings.value = null
                if (released) {
                    // Under opLock so no command is mid-exchange, and out of
                    // CONNECTED first so anything arriving routes to the cloud.
                    opLock.withLock {
                        _state.value = State.RELEASED
                        teardown()
                    }
                    logSessionEnd("released, no demand for ${RELEASE_AFTER_MS / 1000}s")
                    return failures
                }
                logSessionEnd("link lost")
                teardown()
                _state.value = State.UNREACHABLE
            } else {
                teardown()
            }
            failures++
            val wait = backoffMs(failures)
            val what = if (result == State.CONNECTED) "lost" else result.toString()
            Log.i(TAG, "Machine link $what; next attempt in ${wait / 1000}s (failure $failures)")
            // Either the backoff runs out or reconnectNow() cuts it short.
            withTimeoutOrNull(wait) { kick.receive() }
        }
        return failures
    }

    /** One full connect: link, MTU, discovery, auth, verify. Returns the resulting state. */
    private suspend fun connectOnce(): State {
        val adapter = adapter(context)
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or off")
            return State.UNREACHABLE
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid machine address: $address", e)
            return State.UNREACHABLE
        }
        val token = tokenProvider()
        if (token.isBlank()) {
            Log.w(TAG, "No BLE token stored; cannot authenticate")
            return State.BAD_TOKEN
        }

        teardown()
        val t0 = System.currentTimeMillis()
        // A direct connect to an address Android has not seen in a recent scan
        // can fail at once with status 133: measured 2026-10-18, four 133s in a
        // row after a machine restart, then up first try right after a scan.
        // Not seen is logged but not fatal - Android silently throttles a
        // sixth scan start within 30 s, and the connect may still work.
        val sighted = sightMachine(adapter, purpose = "Pre-connect")
        val tScan = System.currentTimeMillis()
        if (sighted == null) {
            // Drop the ring now, not after the 15 s connect: on 10-23 it stayed
            // up 14 s into a shot whose link never came (hang 9).
            setSeen(false, "pre-connect scan")
            Log.i(TAG, "Machine not seen advertising in ${PRE_CONNECT_SCAN_MS} ms; connecting anyway")
        } else {
            setSeen(true, "pre-connect scan")
            Log.d(TAG, "Machine seen advertising after ${sighted.afterMs} ms (RSSI ${sighted.rssi})")
        }
        val cd = CompletableDeferred<Boolean>()
        connectDeferred = cd
        servicesDeferred = CompletableDeferred()
        lastConnectStatus = null
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val g = gatt ?: return State.UNREACHABLE

        val linked = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { cd.await() }
        val tLink = System.currentTimeMillis()
        if (linked != true) {
            // A sighting without a link still counts as seen: the machine is
            // there, the connect is what failed. A miss was recorded above.
            val why = when (val st = lastConnectStatus) {
                null -> "no callback in $CONNECT_TIMEOUT_MS ms"
                else -> "status ${gattStatusName(st)} after ${tLink - tScan} ms"
            }
            Log.i(TAG, "Machine link did not come up: $why")
            return State.UNREACHABLE
        }
        Log.i(TAG, "GATT connect: CONNECTED after ${tLink - tScan} ms")

        val md = CompletableDeferred<Int>()
        mtuDeferred = md
        if (g.requestMtu(REQUESTED_MTU)) {
            val mtu = withTimeoutOrNull(MTU_TIMEOUT_MS) { md.await() }
            when {
                mtu == null -> Log.w(TAG, "MTU request timed out after $MTU_TIMEOUT_MS ms (asked $REQUESTED_MTU)")
                mtu < 0 -> Log.w(TAG, "MTU change failed (asked $REQUESTED_MTU)")
                else -> Log.i(TAG, "MTU $mtu (asked $REQUESTED_MTU)")
            }
        } else {
            Log.w(TAG, "MTU request refused to start")
        }
        val tMtu = System.currentTimeMillis()
        // A write + read-back measured ~500 ms on the default interval, too
        // slow to beat the cloud on a stop - so HIGH during a shot, and the
        // default otherwise (pollUntilLost follows demand from here on).
        applyPriority(g, _demand.value)

        if (!g.discoverServices()) {
            Log.w(TAG, "Service discovery refused to start")
            return State.UNREACHABLE
        }
        when (withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { servicesDeferred?.await() }) {
            null -> {
                Log.w(TAG, "Service discovery timed out after $DISCOVER_TIMEOUT_MS ms")
                return State.UNREACHABLE
            }
            false -> {
                Log.w(TAG, "Service discovery failed: status ${gattStatusName(lastDiscoverStatus)}")
                return State.UNREACHABLE
            }
            true -> Unit
        }
        val tDiscover = System.currentTimeMillis()

        val chars = g.services.flatMap { it.characteristics }
        val present = chars.map { it.uuid.toString() }.toSet()
        when (classify(present)) {
            Discovery.CONFIG_MODE -> {
                Log.w(TAG, "Machine is in configuration mode (token char present, auth absent); restart it")
                return State.CONFIG_MODE
            }
            Discovery.MISSING -> {
                Log.e(TAG, "Machine characteristics not found; saw ${present.sorted()}")
                return State.UNREACHABLE
            }
            Discovery.READY -> Unit
        }
        fun find(uuid: String) = chars.first { it.uuid == UUID.fromString(uuid) }
        readChar = find(MicraProtocol.READ_CHAR)
        writeChar = find(MicraProtocol.WRITE_CHAR)
        authChar = find(MicraProtocol.AUTH_CHAR)

        // Arm the drop detector before the first op, so a drop during auth
        // or verification is seen too.
        linkLost = CompletableDeferred()

        // The auth write "succeeds" whatever the token; only the verify read
        // below can tell a good token from a bad one.
        if (!writeAwait(authChar!!, MicraProtocol.encodeAuth(token))) {
            Log.w(TAG, "Auth write failed")
            return State.UNREACHABLE
        }
        val tAuth = System.currentTimeMillis()
        val modeReply = exchange(readChar!!, MicraProtocol.encodeRead(ReadSetting.MACHINE_MODE))
            ?: run {
                Log.w(TAG, "Verify read failed")
                return State.UNREACHABLE
            }
        val mode = MicraProtocol.parseMachineMode(modeReply) ?: run {
            Log.w(TAG, "Verify read returned no machine mode (${modeReply.size} bytes); token rejected")
            // Machine output only, never the auth payload: what did it say?
            Log.d(TAG, "Verify reply starts ${modeReply.take(16).joinToString("") { "%02x".format(it) }}")
            return State.BAD_TOKEN
        }
        if (subscribeShotCounter(g, chars) == Subscribe.WEDGED) return State.UNREACHABLE
        tankReadThisSession = false
        val tEnd = System.currentTimeMillis()
        setSeen(true, "connect")
        Log.d(
            TAG,
            "Connect phases: scan ${tScan - t0}, link ${tLink - tScan}, mtu ${tMtu - tLink}, " +
                "discover ${tDiscover - tMtu}, auth ${tAuth - tDiscover}, verify+subscribe ${tEnd - tAuth} ms",
        )
        Log.i(TAG, "Machine link up in ${tEnd - t0} ms: mode=${mode.wire}")
        return State.CONNECTED
    }

    private enum class Subscribe { ACTIVE, ABSENT, SKIPPED, FAILED, WEDGED }

    /**
     * Subscribe to the shot counter if the machine offers it. Runs inside
     * connectOnce, before CONNECTED, so no other op can be in flight.
     * WEDGED (the descriptor write timed out) means the link must go.
     */
    private suspend fun subscribeShotCounter(g: BluetoothGatt, chars: List<BluetoothGattCharacteristic>): Subscribe {
        shotCounterNotifications.set(0)
        brewStartLogged = false
        brewTracker.reset()
        if (!characteristicsLogged) {
            characteristicsLogged = true
            Log.i(TAG, "Machine characteristics: ${chars.map { it.uuid.toString().lowercase() }.toSortedSet()}")
        }
        val ch = chars.firstOrNull { it.uuid == UUID.fromString(MicraProtocol.SHOT_COUNTER_CHAR) }
        if (ch == null) {
            Log.i(TAG, "Shot counter not offered by this firmware; machine shot time stays cloud-only")
            return Subscribe.ABSENT
        }
        if (shotCounterDisabled) {
            Log.i(TAG, "Shot counter not subscribed: turned off after a subscribe wedged the link")
            return Subscribe.SKIPPED
        }
        val props = ch.properties
        val value = when {
            props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> {
                Log.w(TAG, "Shot counter characteristic neither notifies nor indicates (properties $props)")
                return Subscribe.FAILED
            }
        }
        val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
            Log.w(TAG, "Shot counter characteristic has no CCCD; not subscribed")
            return Subscribe.FAILED
        }
        if (!g.setCharacteristicNotification(ch, true)) {
            Log.w(TAG, "Shot counter: setCharacteristicNotification refused")
            return Subscribe.FAILED
        }
        // Before the write: the replay can arrive ahead of its callback.
        shotCounterChar = ch
        replayPending = true
        subscribedAt = System.currentTimeMillis()
        val d = CompletableDeferred<Boolean>()
        descriptorDeferred = d
        sessionOps.incrementAndGet()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        if (!started) {
            descriptorDeferred = null
            shotCounterChar = null
            Log.w(TAG, "Shot counter subscribe refused to start")
            return Subscribe.FAILED
        }
        return when (withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() }) {
            null -> {
                shotCounterChar = null
                shotCounterDisabled = true
                Log.w(TAG, "Shot counter subscribe timed out; dropping the link and not subscribing again until restart")
                Subscribe.WEDGED
            }
            false -> {
                shotCounterChar = null
                Log.w(TAG, "Shot counter subscribe failed (descriptor write status error)")
                Subscribe.FAILED
            }
            true -> {
                _shotCounterActive.value = true
                val how = if (value === BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) "indicate" else "notify"
                Log.i(TAG, "Shot counter subscribed ($how)")
                Subscribe.ACTIVE
            }
        }
    }

    /** A shot-counter notification, on [scope]. [atMs] is when it arrived. */
    private fun onShotCounter(raw: ByteArray, atMs: Long) {
        shotCounterNotifications.incrementAndGet()
        // Machine output only - no token, no account data - so it is logged as is.
        val text = raw.toString(Charsets.UTF_8).substringBefore(NUL).trim()
        if (replayPending) {
            replayPending = false
            if (atMs - subscribedAt < REPLAY_WINDOW_MS) {
                Log.d(TAG, "Shot counter: ignoring the replay of the last event: $text")
                return
            }
        }
        val n = MicraShotCounter.parse(raw) ?: run {
            Log.w(TAG, "Shot counter: unparseable notification (${raw.size} bytes)")
            return
        }
        Log.d(TAG, "Shot counter: $text")
        if (!n.brewing.isEmpty) {
            val u = brewTracker.process(n.brewing, atMs)
            if (u != null) {
                logBrew(u)
                _brewUpdates.tryEmit(u)
            }
        }
        val t = n.telemetry
        t.machineMode?.let { Log.i(TAG, "Shot counter: machine mode $it") }
        t.sleep?.let { Log.i(TAG, "Shot counter: sleep ($it)") }
        t.statistics?.let { Log.i(TAG, "Machine statistics: $it") }
    }

    /** INFO for the start and the end of a brew; the ~1 s timer ticks are in the DEBUG line. */
    private fun logBrew(u: MicraShotCounter.BrewUpdate) {
        when (u.state) {
            MicraShotCounter.BrewState.BREWING -> if (!brewStartLogged) {
                brewStartLogged = true
                Log.i(TAG, "Machine brewing (dose ${u.doseIndex ?: "?"}, timer ${u.timerSeconds}s)")
            }
            MicraShotCounter.BrewState.BREWING_STOPPED -> {
                brewStartLogged = false
                Log.i(
                    TAG,
                    "Machine brew stopped after ${u.finalShotSeconds ?: "?"}s " +
                        "(type ${u.stopType ?: "?"}, reason ${u.stopReason ?: "?"}, dose ${u.doseIndex ?: "?"})",
                )
            }
            MicraShotCounter.BrewState.FLUSHED -> {
                brewStartLogged = false
                Log.i(TAG, "Machine flush ended after ${u.flushSeconds ?: "?"}s")
            }
            MicraShotCounter.BrewState.BACKFLUSHING -> if (!brewStartLogged) {
                brewStartLogged = true
                Log.i(TAG, "Machine backflushing")
            }
        }
    }

    /** Record whether the machine was heard, logging every change with its cause. */
    private fun setSeen(seen: Boolean, cause: String) {
        if (_seen.value == seen) return
        _seen.value = seen
        Log.i(TAG, "Machine advertising: ${if (seen) "not seen -> seen" else "seen -> not seen"} ($cause)")
    }

    /**
     * Scan for this machine's address until it is seen or
     * [PRE_CONNECT_SCAN_MS] passes; ms to the sighting, or null. Filtered by
     * address, which also keeps it allowed with the screen off - an arm on a
     * locked phone must still connect. Low latency: this sits in front of
     * the connect a shot is waiting on, and it stops at the first result.
     */
    private suspend fun sightMachine(
        adapter: BluetoothAdapter,
        timeoutMs: Long = PRE_CONNECT_SCAN_MS,
        purpose: String,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
        giveUp: () -> Boolean = { false },
    ): Sighting? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val seen = CompletableDeferred<Sighting>()
        val t0 = System.currentTimeMillis()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen.complete(Sighting(System.currentTimeMillis() - t0, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "$purpose scan failed: error ${scanErrorName(errorCode)}")
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        val recent = noteScanStart(t0)
        if (recent > MAX_SCAN_STARTS_PER_WINDOW) {
            // Android hands back nothing - no results, no error - after the
            // fifth scan start in 30 s; a miss from here on means nothing.
            Log.w(TAG, "$purpose scan start #$recent in 30 s - Android may return nothing; result unreliable")
        } else {
            Log.d(TAG, "$purpose scan start, $recent in the last 30 s")
        }
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            Log.w(TAG, "$purpose scan could not start", e)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                // Sliced so [giveUp] is noticed; a sighting still ends it at once.
                while (!seen.isCompleted && !giveUp()) withTimeoutOrNull(WAIT_SLICE_MS) { seen.await() }
                if (seen.isCompleted) seen.await() else null
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
                .onFailure { Log.d(TAG, "$purpose stopScan raised ${it.javaClass.simpleName}") }
        }
    }

    /** Start times of our recent scans, for Android's 5-starts-per-30 s limit. */
    private val scanStarts = ArrayDeque<Long>()

    /** Record a scan start at [now]; returns how many fall in the last 30 s, this one included. */
    private fun noteScanStart(now: Long): Int = synchronized(scanStarts) {
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > SCAN_WINDOW_MS) scanStarts.removeFirst()
        scanStarts.addLast(now)
        scanStarts.size
    }

    /** Ms since our last scan start, or Long.MAX_VALUE if none. */
    private fun msSinceLastScan(now: Long): Long = synchronized(scanStarts) {
        scanStarts.lastOrNull()?.let { now - it } ?: Long.MAX_VALUE
    }

    private data class Sighting(val afterMs: Long, val rssi: Int)

    /**
     * Listen (never connect) for the machine's advertising while released,
     * and log what was heard with the time since our last session. Gives way
     * at once if the link is wanted again. A machine connected to the
     * official app does not advertise either, so a miss then is expected.
     */
    private suspend fun probeAdvertising() {
        val adapter = adapter(context)
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "Advertising probe skipped: Bluetooth unavailable or off")
            return
        }
        val since = if (sessionEndedAt == 0L) "no session since start"
            else "${formatAge(System.currentTimeMillis() - sessionEndedAt)} after the last session"
        // A fresh scan within seconds of the last one adds nothing and spends
        // Android's scan budget; a throttled scan would then read as NOT seen.
        if (msSinceLastScan(System.currentTimeMillis()) < MIN_PROBE_GAP_MS) {
            Log.d(TAG, "Advertising probe skipped: scanned moments ago (answer unknown, dot unchanged)")
            return
        }
        // BALANCED, not LOW_LATENCY: nobody is waiting on this one, and with
        // the machine switched off it listens the full 10 s every minute.
        val s = sightMachine(
            adapter,
            ADVERT_PROBE_SCAN_MS,
            purpose = "Advertising probe",
            scanMode = ScanSettings.SCAN_MODE_BALANCED,
        ) { !releaseDue() }
        // On screen a probe runs every minute; INFO only when the answer
        // changes, so the log keeps one line per transition, not per minute.
        val wasSeen = _seen.value
        fun log(seen: Boolean, msg: String) =
            if (appVisible && seen == wasSeen) Log.d(TAG, msg) else Log.i(TAG, msg)
        when {
            s != null -> {
                _seen.value = true
                log(true, "Advertising probe: seen after ${s.afterMs} ms, RSSI ${s.rssi} ($since)")
            }
            !releaseDue() -> Log.d(TAG, "Advertising probe cut short: link wanted again")
            else -> {
                _seen.value = false
                log(false, "Advertising probe: machine NOT seen in ${ADVERT_PROBE_SCAN_MS / 1000}s ($since)")
            }
        }
    }

    /**
     * Poll until the link drops, reads keep failing, or the release is due.
     * True only for the release: the link was fine and is let go on purpose.
     */
    private suspend fun pollUntilLost(): Boolean {
        var failures = 0
        var last: Readings? = null
        while (currentCoroutineContext().isActive && linkLost?.isCompleted == false) {
            if (releaseDue()) return true
            val demand = _demand.value
            if (priorityFor(demand) != appliedPriority) {
                // Not a queued GATT op, but kept off any in-flight exchange anyway.
                opLock.withLock { gatt?.let { applyPriority(it, demand) } }
            }
            when (val r = pollOnce()) {
                is PollResult.Failed -> {
                    failures++
                    Log.w(TAG, "Machine poll failed ($failures/$MAX_POLL_FAILURES): ${r.why}")
                    if (failures >= MAX_POLL_FAILURES) return false
                }
                // Stepped aside for a stop; not the link's fault.
                PollResult.Skipped -> Log.d(TAG, "Machine poll skipped for a priority command")
                is PollResult.Ok -> {
                    failures = 0
                    logIfChanged(last, r.readings, r.timing)
                    last = r.readings
                    _readings.value = r.readings
                }
            }
            waitForNextPoll(demand)
        }
        return false
    }

    /**
     * Sleep one poll interval for [seen], waking at once if the link drops
     * and within a slice if demand changes - so arming a shot during a 10 s
     * idle wait gets HIGH and the 2 s poll straight away.
     */
    private suspend fun waitForNextPoll(seen: Demand) {
        val lost = linkLost ?: return
        val until = System.currentTimeMillis() + pollIntervalMs(seen)
        while (System.currentTimeMillis() < until && _demand.value == seen) {
            if (withTimeoutOrNull(WAIT_SLICE_MS) { lost.await() } != null) return
        }
    }

    /** The priority last requested on this link; -1 before the first request. */
    @Volatile private var appliedPriority = -1

    private fun applyPriority(g: BluetoothGatt, demand: Demand) {
        val p = priorityFor(demand)
        val name = if (p == BluetoothGatt.CONNECTION_PRIORITY_HIGH) "HIGH" else "BALANCED"
        val ok = g.requestConnectionPriority(p)
        // Recorded even if refused, so a refusal is not retried every poll.
        appliedPriority = p
        Log.i(TAG, "Connection priority $name ${if (ok) "requested" else "refused"} (demand $demand)")
    }

    private sealed interface PollResult {
        class Failed(val why: String) : PollResult
        data object Skipped : PollResult
        class Ok(val readings: Readings, val timing: String) : PollResult
    }

    /** Sentinel from [pollRead]: a priority op was waiting, so the read stepped aside. */
    private val skipped = ByteArray(0)

    private suspend fun pollOnce(): PollResult {
        val timing = StringBuilder()
        val modeReply = pollRead(ReadSetting.MACHINE_MODE, timing)
            ?: return PollResult.Failed("machineMode write/read failed or timed out")
        if (modeReply === skipped) return PollResult.Skipped
        val mode = MicraProtocol.parseMachineMode(modeReply)
            ?: return PollResult.Failed("machineMode reply unparseable (${modeReply.size} bytes)")
        val boilersReply = pollRead(ReadSetting.BOILERS, timing)
            ?: return PollResult.Failed("boilers write/read failed or timed out")
        if (boilersReply === skipped) return PollResult.Skipped
        val boilers = MicraProtocol.parseBoilers(boilersReply)
            ?: return PollResult.Failed("boilers reply unparseable (${boilersReply.size} bytes)")
        // Once a session and never during a shot: one extra exchange the
        // stop must not queue behind. Logged only; the cloud's boiler status
        // already says NoWater on screen.
        if (!tankReadThisSession && _demand.value != Demand.SHOT) {
            tankReadThisSession = true
            val tank = pollRead(ReadSetting.TANK_STATUS, StringBuilder())
            when {
                tank == null -> return PollResult.Failed("tankStatus write/read failed or timed out")
                tank === skipped -> tankReadThisSession = false
                else -> Log.i(
                    TAG,
                    "Water tank status: ${MicraProtocol.parseTankStatus(tank) ?: "unreadable"} " +
                        "(raw ${tank.toString(Charsets.UTF_8).substringBefore(NUL).take(40)})",
                )
            }
        }
        return PollResult.Ok(Readings(mode, boilers, System.currentTimeMillis()), timing.toString())
    }

    /**
     * A poll's setting read. It yields to a waiting
     * priority op, both before starting and between its write and its read
     * (skipping the read is safe: the next request overwrites the answer).
     * Appends "w=..ms r=..ms" to [timing] - the per-op latency the stop
     * timeout should be set from.
     */
    private suspend fun pollRead(setting: ReadSetting, timing: StringBuilder): ByteArray? = runOp {
        val ch = readChar ?: return@runOp null
        if (priorityWaiting.get() > 0) return@runOp skipped
        val t0 = System.currentTimeMillis()
        if (!writeAwait(ch, MicraProtocol.encodeRead(setting))) return@runOp null
        val t1 = System.currentTimeMillis()
        if (priorityWaiting.get() > 0) return@runOp skipped
        val reply = readAwait(ch)
        timing.append(" ${setting.wire} w=${t1 - t0}ms r=${System.currentTimeMillis() - t1}ms")
        reply
    }

    /**
     * INFO only when something moved: the mode, a target, or enabled. Current
     * temperatures drift every poll and go to DEBUG, or a 2 s poll would
     * crowd the auto-tare narration out of the 5 MB log.
     */
    private fun logIfChanged(prev: Readings?, now: Readings, timing: String) {
        fun fmt(b: Boiler?) = b?.let { "${it.target}/${it.current}${if (it.isEnabled) "" else " off"}" } ?: "-"
        val line = "mode=${now.mode.wire} coffee=${fmt(now.coffee)} steam=${fmt(now.steam)}"
        val moved = prev == null || prev.mode != now.mode ||
            prev.boilers.map { it.id to it.target to it.isEnabled } !=
            now.boilers.map { it.id to it.target to it.isEnabled }
        if (moved) Log.i(TAG, "Machine: $line |$timing") else Log.d(TAG, "Machine: $line |$timing")
    }

    // --- Public operations ------------------------------------------------

    /**
     * The brew-by-weight stop: StandBy as a priority write, done when the
     * write is acknowledged - no status read-back, which would double the
     * time. A write-with-response ack means the machine received it. Jumps
     * ahead of polls (see [pollRead]); an exchange already in flight still
     * finishes first, since Android cannot abort one.
     *
     * Logs its own time from inside the op, so a caller that gave up at its
     * timeout still leaves the real number in the log.
     */
    override suspend fun standbyFast(): Boolean {
        if (!isConnected) return false
        val t0 = System.currentTimeMillis()
        priorityWaiting.incrementAndGet()
        val job = scope.async {
            try {
                opLock.withLock {
                    val tLock = System.currentTimeMillis()
                    val ch = writeChar
                    val ok = isConnected && ch != null &&
                        writeAwait(ch, MicraProtocol.encodePower(false))
                    Log.i(
                        TAG,
                        "BLE fast standby: write ${if (ok) "acknowledged" else "failed"} " +
                            "in ${System.currentTimeMillis() - t0} ms " +
                            "(${tLock - t0} ms waiting for the link)",
                    )
                    ok
                }
            } finally {
                priorityWaiting.decrementAndGet()
            }
        }
        return job.await()
    }

    override suspend fun setPower(on: Boolean): CommandStatus? = command("power ${if (on) "on" else "standby"}", MicraProtocol.encodePower(on))

    override suspend fun setSteamEnabled(enabled: Boolean): CommandStatus? =
        command("steam ${if (enabled) "on" else "off"}", MicraProtocol.encodeSteamEnabled(enabled))

    override suspend fun setCoffeeTarget(celsius: Double): CommandStatus? =
        command("coffee $celsius", MicraProtocol.encodeCoffeeTarget(celsius))

    override suspend fun setSteamTarget(celsius: Int): CommandStatus? =
        command("steam $celsius", MicraProtocol.encodeSteamTarget(celsius))

    /**
     * Write a command and read its status back. Null if not connected, the
     * write failed, or the status did not parse. Times are logged at INFO:
     * they are the numbers device check 2 compares against the cloud.
     */
    private suspend fun command(label: String, payload: ByteArray): CommandStatus? {
        val t0 = System.currentTimeMillis()
        val reply = runOp { exchange(writeChar ?: return@runOp null, payload) }
        val status = reply?.let(MicraProtocol::parseCommandStatus)
        val ms = System.currentTimeMillis() - t0
        when {
            reply == null -> Log.w(TAG, "BLE $label failed after $ms ms")
            status == null -> Log.w(TAG, "BLE $label: unparseable status after $ms ms")
            else -> Log.i(TAG, "BLE $label: ${status.status} (${status.message}) in $ms ms")
        }
        return status
    }

    /**
     * Run [block] under [opLock] as a job of its own (see the class doc): a
     * caller that is cancelled or times out stops waiting, but the GATT op
     * completes and releases the lock. Only while connected.
     */
    private suspend fun runOp(block: suspend () -> ByteArray?): ByteArray? {
        if (!isConnected) return null
        val job = scope.async {
            opLock.withLock { if (isConnected) block() else null }
        }
        return job.await()
    }

    // --- GATT primitives (callers hold opLock, or are connectOnce) --------

    /** Write with response to [ch], then read [ch] back. */
    private suspend fun exchange(ch: BluetoothGattCharacteristic, payload: ByteArray): ByteArray? {
        if (!writeAwait(ch, payload)) return null
        return readAwait(ch)
    }

    private suspend fun writeAwait(ch: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val g = gatt ?: return false
        sessionOps.incrementAndGet()
        val d = CompletableDeferred<Boolean>()
        writeDeferred = d
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = type
            ch.value = payload
            g.writeCharacteristic(ch)
        }
        if (!started) {
            writeDeferred = null
            abandonLink("Write to ${ch.uuid} refused to start")
            return false
        }
        return withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() } ?: run {
            abandonLink("Write to ${ch.uuid} timed out")
            false
        }
    }

    private suspend fun readAwait(ch: BluetoothGattCharacteristic): ByteArray? {
        val g = gatt ?: return null
        sessionOps.incrementAndGet()
        val d = CompletableDeferred<ByteArray?>()
        readDeferred = d
        if (!g.readCharacteristic(ch)) {
            readDeferred = null
            abandonLink("Read of ${ch.uuid} refused to start")
            return null
        }
        // Wrapped so a timeout (link wedged) stays distinct from a read that
        // completed with an error status (link fine, this read failed).
        val outcome = withTimeoutOrNull(OP_TIMEOUT_MS) { ReadOutcome(d.await()) }
        if (outcome == null) {
            abandonLink("Read of ${ch.uuid} timed out")
            return null
        }
        return outcome.value
    }

    private class ReadOutcome(val value: ByteArray?)

    /**
     * An operation that timed out or would not start leaves Android's
     * one-at-a-time GATT queue wedged: the unanswered op is still outstanding,
     * so every later op is refused. Measured 2026-10-17: a steam command the
     * machine never acknowledged left polls refused for 12 s and the link
     * down for a minute. Drop the link at once instead - the supervisor
     * reconnects on a fresh queue, and going non-CONNECTED first sends any
     * command arriving meanwhile straight to the cloud.
     */
    private fun abandonLink(why: String) {
        if (linkLost?.isCompleted != false) {
            Log.w(TAG, why)
            return
        }
        Log.w(TAG, "$why; dropping the link to clear the GATT queue")
        if (_state.value == State.CONNECTED) _state.value = State.UNREACHABLE
        linkLost?.complete(Unit)
    }

    private fun teardown() {
        appliedPriority = -1
        linkLost?.complete(Unit)
        val g = gatt
        gatt = null
        readChar = null
        writeChar = null
        authChar = null
        shotCounterChar = null
        replayPending = false
        _shotCounterActive.value = false
        takeWrite()?.complete(false)
        takeRead()?.complete(null)
        takeDescriptor()?.complete(false)
        try {
            g?.disconnect()
            g?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "GATT close raised", e)
        }
    }

    // --- GATT callbacks (binder thread) -----------------------------------

    private val callback = object : BluetoothGattCallback() {

        /**
         * A callback from a GATT client we have already replaced or closed.
         * Null [gatt] means connectGatt has not returned yet, which is the
         * current client's own early callback, not a stale one.
         */
        private fun stale(g: BluetoothGatt): Boolean {
            val current = gatt
            return current != null && g !== current
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (stale(g)) {
                Log.d(TAG, "Ignoring connection state $newState (status $status) from a replaced GATT client")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    lastConnectStatus = status
                    connectDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lastConnectStatus = status
                    if (_state.value == State.CONNECTED) {
                        val up = (System.currentTimeMillis() - sessionStartedAt) / 1000
                        Log.w(
                            TAG,
                            "Machine GATT dropped while connected (status ${gattStatusName(status)}) " +
                                "after ${up / 60}m${up % 60}s",
                        )
                    } else {
                        Log.i(TAG, "Machine GATT disconnected (status ${gattStatusName(status)})")
                    }
                    connectDeferred?.complete(false)
                    servicesDeferred?.complete(false)
                    mtuDeferred?.complete(-1)
                    takeWrite()?.complete(false)
                    takeRead()?.complete(null)
                    takeDescriptor()?.complete(false)
                    linkLost?.complete(Unit)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (stale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "MTU change failed: status ${gattStatusName(status)}")
            mtuDeferred?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -1)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (stale(g)) return
            lastDiscoverStatus = status
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (stale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Write to ${ch.uuid} status $status")
            // Take, then complete: completing first resumes the waiter, which
            // can install the next op's deferred before a later "= null" here
            // wipes it - and that op then times out and drops the link.
            takeWrite()?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ delivers the value as a parameter.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (stale(g)) return
            completeRead(ch, if (status == BluetoothGatt.GATT_SUCCESS) value else null, status)
        }

        // API 26–32 reads it off the characteristic.
        @Deprecated("Superseded by the four-argument overload on API 33+")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (stale(g)) return
            completeRead(ch, if (status == BluetoothGatt.GATT_SUCCESS) ch.value else null, status)
        }

        private fun completeRead(ch: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
            if (value == null) Log.w(TAG, "Read of ${ch.uuid} status $status")
            takeRead()?.complete(value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (stale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write ${descriptor.uuid} status ${gattStatusName(status)}")
            }
            takeDescriptor()?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ delivers the value as a parameter.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (stale(g)) return
            shotCounterNotified(ch, value.copyOf())
        }

        // API 26-32 reads it off the characteristic.
        @Deprecated("Superseded by the three-argument overload on API 33+")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (stale(g)) return
            shotCounterNotified(ch, ch.value?.copyOf() ?: return)
        }

        /** Stamp the arrival here, on the binder thread; handle it on [scope]. */
        private fun shotCounterNotified(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid != UUID.fromString(MicraProtocol.SHOT_COUNTER_CHAR)) return
            val at = System.currentTimeMillis()
            scope.launch { onShotCounter(value, at) }
        }
    }

    /** Clear and return the pending write's deferred, so only one party completes it. */
    @Synchronized private fun takeWrite(): CompletableDeferred<Boolean>? =
        writeDeferred.also { writeDeferred = null }

    @Synchronized private fun takeRead(): CompletableDeferred<ByteArray?>? =
        readDeferred.also { readDeferred = null }

    @Synchronized private fun takeDescriptor(): CompletableDeferred<Boolean>? =
        descriptorDeferred.also { descriptorDeferred = null }
}
