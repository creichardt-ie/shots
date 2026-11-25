// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from aioacaia (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.
@file:Suppress("DEPRECATION")

package com.coffeeapp.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.coffeeapp.droid.core.log.FileLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android GATT client for a scale, any brand (2.16.0; until then
 * AcaiaScaleClient). What differs per brand is a [ScaleProtocol]; this class
 * does what every scale needs and Android makes hard:
 *
 *  1. **One operation at a time.** Android permits exactly one outstanding
 *     GATT operation per connection and silently drops the rest - no error,
 *     no callback, the write just never happens. Every write therefore goes
 *     through [opQueue], which serialises them and waits for each completion
 *     callback before starting the next. This mirrors aioacaia process_queue,
 *     including the 100 ms spacing between writes.
 *
 *  2. **Characteristics resolved across all services.** The Umbra exposes the
 *     Acaia protocol on fe41/fe42 under a vendor service, so the usual
 *     getService(uuid).getCharacteristic(uuid) lookup cannot find them
 *     without knowing that service. [findCharacteristic] searches every
 *     discovered service instead, which works for every brand.
 *
 * Callbacks arrive on a binder thread. State is written under @Volatile and
 * [onNotify] fires on that thread; ScaleAdapter is responsible for hopping
 * onto the runtime dispatcher before touching anything shared.
 */
@SuppressLint("MissingPermission")
class ScaleGattClient(
    private val context: Context,
    private val mac: String,
    private val protocol: ScaleProtocol,
    private val scope: CoroutineScope,
    private val onNotify: () -> Unit,
    /** A remote drop of an established link (not our own disconnect). */
    private val onDropped: (() -> Unit)? = null,
) {

    companion object {
        private const val TAG = "ScaleGattClient"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        private const val OP_TIMEOUT_MS = 5_000L

        /** aioacaia refuses to reconnect within 15 s of a drop; so do we. */
        private const val RECONNECT_COOLDOWN_MS = 15_000L

        /** bleak-retry-connector used max_attempts=3. Status 133 is common. */
        private const val MAX_CONNECT_ATTEMPTS = 3

        /** Matches the asyncio.sleep(0.1) between writes in process_queue. */
        private const val WRITE_SPACING_MS = 100L

        /** Raw notifications logged per connection (DEBUG). */
        private const val RAW_LOG_LIMIT = 50

        /** When the first reading-rate line is logged after a connect. */
        private const val RATE_LOG_AFTER_MS = 60_000L

        /**
         * Scan for [timeoutMs] and return every scale seen, as (name, address,
         * brand), for picking the scale in Settings.
         */
        suspend fun scan(context: Context, timeoutMs: Long = 8_000L): List<Triple<String, String, ScaleModel>> {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
                Log.w(TAG, "Scale scan skipped: Bluetooth unavailable or off")
                return emptyList()
            }
            val found = linkedMapOf<String, Pair<String, ScaleModel>>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: result.device.name
                    val model = ScaleModel.forName(name) ?: return
                    synchronized(found) { found[result.device.address] = name!! to model }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Scale scan failed: error $errorCode")
                }
            }
            scanner.startScan(callback)
            try {
                delay(timeoutMs)
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
            return synchronized(found) { found.map { (addr, v) -> Triple(v.first, addr, v.second) } }
        }
    }

    // --- Observable state (written from the binder thread) ---------------

    @Volatile
    var connected: Boolean = false
        private set

    /** Latest weight in grams, or null before the first reading. */
    @Volatile
    var weight: Float? = null
        private set

    /** Latest battery level in percent, or null until the scale reports one. */
    @Volatile
    var battery: Int? = null
        private set

    @Volatile
    private var lastDisconnectAt: Long = 0L

    // --- Internals -------------------------------------------------------

    // All of these are written from the binder thread (GATT callbacks) and
    // read from the runtime dispatcher, so every one of them needs @Volatile.
    // Without it the queue worker can read a stale null gatt and drop writes.
    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyChar: BluetoothGattCharacteristic? = null

    /** The write type this connection uses; chosen from what the characteristic offers. */
    @Volatile
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    @Volatile
    private var connectDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var servicesDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingOp: CompletableDeferred<Boolean>? = null

    private val opQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var queueJob: Job? = null
    private var heartbeatJob: Job? = null
    private var rateJob: Job? = null

    /** Messages dropped as malformed (bad checksum etc.) this connection; logged on teardown. */
    private val malformedFrames = AtomicInteger(0)

    /** Readings this connection, for the rate log; and when it began. */
    private val readings = AtomicInteger(0)

    @Volatile
    private var connectedAt: Long = 0L

    /** Raw notifications logged this connection. */
    private val rawLogged = AtomicInteger(0)

    /** Ignored message kinds already logged this connection. */
    private val ignoredSeen: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Status of the last connection-state callback, for the attempt log line. */
    @Volatile
    private var lastConnectStatus: Int? = null

    // --- Connection ------------------------------------------------------

    /**
     * Connect, discover, subscribe, send the protocol's handshake and start
     * its heartbeat.
     *
     * @param force bypasses the post-disconnect cooldown. aioacaia has no
     *   bypass, which is why waking the scale from an idle sleep used
     *   to silently do nothing until the app was restarted - the wake landed
     *   inside the cooldown and returned early. The explicit wake path passes
     *   force=true; automatic paths do not, so a flapping link still backs off.
     */
    suspend fun connect(force: Boolean = false): Boolean {
        if (connected) return true

        val sinceDisconnect = System.currentTimeMillis() - lastDisconnectAt
        if (!force && lastDisconnectAt > 0L && sinceDisconnect < RECONNECT_COOLDOWN_MS) {
            Log.d(TAG, "Skipping connect: only ${sinceDisconnect}ms since last disconnect")
            return false
        }

        val adapter = bluetoothAdapter() ?: run {
            Log.w(TAG, "No Bluetooth adapter available")
            return false
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off")
            return false
        }

        if (mac.isBlank()) {
            Log.i(TAG, "No scale chosen yet (Configuration -> Scale -> scan)")
            return false
        }
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid scale MAC address: $mac", e)
            return false
        }

        val t0 = System.currentTimeMillis()
        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            teardownGatt()
            val cd = CompletableDeferred<Boolean>()
            connectDeferred = cd
            servicesDeferred = CompletableDeferred()
            lastConnectStatus = null

            gatt = device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)

            val linkUp = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { cd.await() }
            if (linkUp == true && discoverAndSubscribe()) {
                val name = runCatching { device.name }.getOrNull() ?: "no name"
                Log.i(
                    TAG,
                    "Connected to ${protocol.brand} scale $mac (\"$name\") in " +
                        "${System.currentTimeMillis() - t0} ms (attempt ${attempt + 1})",
                )
                return true
            }
            val why = when {
                linkUp == null -> "no CONNECTED within $CONNECT_TIMEOUT_MS ms"
                linkUp == false -> "status ${lastConnectStatus ?: "unknown"}"
                else -> "discovery or subscribe failed"
            }
            Log.i(TAG, "Connect attempt ${attempt + 1}/$MAX_CONNECT_ATTEMPTS failed: $why")
            teardownGatt()
            // Android needs a beat between a failed connect and the next one,
            // or the second attempt fails with the same status 133.
            delay(500L * (attempt + 1))
        }
        return false
    }

    private suspend fun discoverAndSubscribe(): Boolean {
        val g = gatt ?: return false

        if (!g.discoverServices()) {
            Log.w(TAG, "discoverServices() refused to start")
            return false
        }
        val discovered = withTimeoutOrNull(DISCOVER_TIMEOUT_MS) {
            servicesDeferred?.await()
        } ?: false
        if (!discovered) {
            Log.w(TAG, "Service discovery timed out")
            return false
        }

        writeChar = findCharacteristic(g, protocol.writeCharUuid)
        notifyChar = findCharacteristic(g, protocol.notifyCharUuid)
        if (writeChar == null || notifyChar == null) {
            for ((w, n) in protocol.alternateChars) {
                val altWrite = findCharacteristic(g, w) ?: continue
                val altNotify = findCharacteristic(g, n) ?: continue
                Log.i(TAG, "${protocol.brand} standard characteristics absent; using write=$w notify=$n")
                writeChar = altWrite
                notifyChar = altNotify
                break
            }
        }
        if (writeChar == null || notifyChar == null) {
            Log.e(
                TAG,
                "${protocol.brand} characteristics not found (write=${protocol.writeCharUuid} " +
                    "found=${writeChar != null}, notify=${protocol.notifyCharUuid} found=${notifyChar != null}). " +
                    "Check the scale model in Configuration (and, for Acaia, is_new_style and the UUID overrides).",
            )
            return false
        }
        writeType = chooseWriteType(writeChar!!)

        protocol.reset()
        readings.set(0)
        rawLogged.set(0)
        ignoredSeen.clear()
        connectedAt = System.currentTimeMillis()
        connected = true
        startQueueWorker()

        if (!enableNotifications(g, notifyChar!!)) {
            Log.e(TAG, "Failed to subscribe to notifications")
            connected = false
            return false
        }

        protocol.handshake().forEach(::enqueue)
        startHeartbeat()
        startRateLog()
        return true
    }

    /**
     * The protocol's preferred write type if the characteristic offers it,
     * else the other. Logged when it is not the preferred one, which is what
     * to look for if commands such as tare seem to go nowhere.
     */
    private fun chooseWriteType(ch: BluetoothGattCharacteristic): Int {
        val props = ch.properties
        val noResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val withResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = when {
            protocol.preferWriteWithoutResponse && noResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            !protocol.preferWriteWithoutResponse && withResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            noResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            withResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // Neither advertised: keep the preference and let the write say.
            protocol.preferWriteWithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val preferred = (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == protocol.preferWriteWithoutResponse
        val label = if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "without response" else "with response"
        if (preferred) {
            Log.d(TAG, "${protocol.brand} writes $label (properties 0x${props.toString(16)})")
        } else {
            Log.i(TAG, "${protocol.brand} write characteristic does not offer the preferred type; writing $label (properties 0x${props.toString(16)})")
        }
        return type
    }

    /**
     * Search every discovered service for the characteristic.
     *
     * The Umbra fe41/fe42 characteristics sit under a vendor service, so the
     * conventional two-step lookup would need that service UUID as well.
     * Scanning all services keeps one code path for every scale.
     */
    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private suspend fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!g.setCharacteristicNotification(characteristic, true)) return false
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: run {
            Log.w(TAG, "Characteristic has no CCCD; notifications may not start")
            return true
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingOp = deferred
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        if (!started) {
            pendingOp = null
            return false
        }
        return withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: false
    }

    // --- Write queue -----------------------------------------------------

    /** Queue a frame for writing. Never blocks; the worker drains in order. */
    private fun enqueue(payload: ByteArray) {
        opQueue.trySend(payload)
    }

    /**
     * Serialises writes. Android allows one outstanding GATT operation, so
     * each write waits for its completion callback before the next starts -
     * firing them concurrently means all but one are dropped without error.
     */
    private fun startQueueWorker() {
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            while (isActive) {
                val payload = opQueue.receive()
                if (!connected) continue
                val g = gatt ?: continue
                val ch = writeChar ?: continue

                val deferred = CompletableDeferred<Boolean>()
                pendingOp = deferred
                val started = writeCharacteristic(g, ch, payload)
                if (!started) {
                    pendingOp = null
                    Log.w(TAG, "Write refused to start")
                    continue
                }
                val ok = withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: false
                if (!ok) Log.w(TAG, "Write did not complete cleanly")
                delay(WRITE_SPACING_MS)
            }
        }
    }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean {
        val type = writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = type
            ch.value = payload
            g.writeCharacteristic(ch)
        }
    }

    // --- Heartbeat and rate ------------------------------------------------

    /** The protocol's keep-alive, if it has one (Acaia does; Bookoo needs none). */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        val interval = protocol.heartbeatIntervalMs
        if (interval <= 0) return
        heartbeatJob = scope.launch {
            while (isActive && connected) {
                protocol.heartbeat().forEach(::enqueue)
                delay(interval)
            }
        }
    }

    /**
     * Readings per second, once a minute after the connect and again at the
     * disconnect. The shot logic's per-sample guards were tuned at the Umbra's
     * 10-20 Hz; a scale reporting slower needs those checked.
     */
    private fun startRateLog() {
        rateJob?.cancel()
        rateJob = scope.launch {
            delay(RATE_LOG_AFTER_MS)
            if (connected) Log.i(TAG, "${protocol.brand} scale: ${rateLine()} in the first minute")
        }
    }

    private fun rateLine(): String {
        val n = readings.get()
        val secs = (System.currentTimeMillis() - connectedAt) / 1000.0
        return String.format(java.util.Locale.US, "%d readings in %.0f s (%.1f/s)", n, secs, if (secs > 0) n / secs else 0.0)
    }

    // --- Commands --------------------------------------------------------

    /** Zero the scale. Returns once the write is queued, not once it lands. */
    fun tare() {
        enqueue(protocol.tare())
    }

    suspend fun disconnect() {
        connected = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        queueJob?.cancel()
        queueJob = null
        // Drain anything still queued so a later reconnect does not replay
        // stale frames against a fresh link.
        while (opQueue.tryReceive().isSuccess) { /* discard */ }
        try {
            gatt?.disconnect()
        } catch (e: SecurityException) {
            Log.d(TAG, "disconnect() denied", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "disconnect() raised", e)
        }
        teardownGatt()
    }

    private fun teardownGatt() {
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            Log.d(TAG, "close() denied", e)
        } catch (e: Exception) {
            Log.d(TAG, "close() raised", e)
        }
        val hadLink = gatt != null && connectedAt > 0L
        gatt = null
        writeChar = null
        notifyChar = null
        rateJob?.cancel()
        rateJob = null
        protocol.reset()
        if (hadLink && readings.get() > 0) {
            Log.i(TAG, "${protocol.brand} scale connection: ${rateLine()}")
            readings.set(0)
        }
        connectedAt = 0L
        val bad = malformedFrames.getAndSet(0)
        if (bad > 0) Log.i(TAG, "Dropped $bad malformed scale message(s) on that connection (bad checksum or layout)")
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // --- GATT callbacks (binder thread) ----------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    lastConnectStatus = status
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectDeferred?.complete(true)
                    } else {
                        Log.w(TAG, "Connected with error status $status")
                        connectDeferred?.complete(false)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lastConnectStatus = status
                    val wasConnected = connected
                    connected = false
                    lastDisconnectAt = System.currentTimeMillis()
                    connectDeferred?.complete(false)
                    servicesDeferred?.complete(false)
                    pendingOp?.complete(false)
                    heartbeatJob?.cancel()
                    queueJob?.cancel()
                    // Drain, as the clean disconnect path does. Without it a
                    // TARE enqueued as the link dropped sat in the channel and
                    // was written to the scale by the worker a reconnect
                    // started later - zeroing it at an arbitrary moment.
                    while (opQueue.tryReceive().isSuccess) { /* discard */ }
                    if (wasConnected) {
                        Log.i(TAG, "Scale disconnected (status $status)")
                        // Tell the adapter immediately so the UI can show the
                        // drop instead of freezing on the last good reading.
                        onNotify()
                        // Close the dead client now rather than at the next
                        // connect, and let the adapter decide on a reconnect.
                        // Only a remote drop reaches here: disconnect() clears
                        // connected first.
                        scope.launch {
                            if (gatt === g) teardownGatt()
                            onDropped?.invoke()
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingOp = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "${protocol.brand} write failed: status $status")
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingOp = null
        }

        // API 33+ delivers the value as a parameter.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handlePacket(value)
        }

        // API 26-32 reads it off the characteristic.
        @Deprecated("Superseded by the three-argument overload on API 33+")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handlePacket(ch.value ?: return)
        }
    }

    // --- Notification handling -------------------------------------------

    /**
     * Decode one notification and update state. ScaleAdapter builds a
     * WeightSample per update, so the sample rate is the scale's own message
     * rate.
     */
    private fun handlePacket(raw: ByteArray) {
        for (result in protocol.decode(raw)) {
            when (result) {
                is ScaleDecode.Update -> {
                    result.weight?.let { weight = it }
                    result.battery?.let { battery = it }
                    readings.incrementAndGet()
                    onNotify()
                }
                is ScaleDecode.Malformed -> {
                    malformedFrames.incrementAndGet()
                    Log.d(TAG, "Malformed scale message dropped: ${result.reason}")
                }
                is ScaleDecode.Ignored -> {
                    if (ignoredSeen.add(result.what)) {
                        Log.i(TAG, "${protocol.brand} scale sent ${result.what} (not used; logged once per connection)")
                    }
                }
            }
        }
        // The first notifications of each connection, raw, for checking a
        // new scale's bytes against its spec; not every one (10-20 a second).
        if (rawLogged.incrementAndGet() <= RAW_LOG_LIMIT) {
            Log.d(TAG, "${protocol.brand} raw: ${raw.joinToString(" ") { "%02x".format(it) }}")
        }
    }
}
