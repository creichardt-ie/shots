// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core

import android.content.Context
import com.coffeeapp.droid.core.log.FileLog as Log
import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.ble.MicraProtocol
import com.coffeeapp.droid.ble.ScaleAdapter
import com.coffeeapp.droid.core.cloud.CloudClient
import com.coffeeapp.droid.core.config.AppConfig
import com.coffeeapp.droid.core.config.ConfigStore
import com.coffeeapp.droid.core.config.SecretsStore
import com.coffeeapp.droid.core.config.summarizeConfigChanges
import com.coffeeapp.droid.core.backup.DocumentsBackup
import com.coffeeapp.droid.core.db.CoffeeDatabase
import com.coffeeapp.droid.core.db.DatabaseImporter
import com.coffeeapp.droid.core.db.ShotRepository
import com.coffeeapp.droid.core.db.ShotWithBean
import com.coffeeapp.droid.core.machine.ControlPath
import com.coffeeapp.droid.core.machine.MachineController
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.core.model.Bean
import com.coffeeapp.droid.core.model.MachineSnapshot
import com.coffeeapp.droid.core.model.ScaleSnapshot
import com.coffeeapp.droid.core.model.ShotSnapshot
import com.coffeeapp.droid.core.model.ShotState
import com.coffeeapp.droid.core.shot.ShotService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Process-wide runtime singleton.
 *
 * Every hardware call is funnelled into [scope], backed by a single-threaded
 * executor. The invariant it buys: ShotService, ScaleAdapter and the placement detector are all touched by
 * exactly one thread and need no synchronisation of their own.
 *
 * Compose collects [snapshot] on the main thread. That StateFlow is the only
 * boundary; nothing in the UI ever reaches past it to the hardware.
 */
object CoffeeRuntime {

    private const val TAG = "CoffeeRuntime"

    /**
     * Sub-threshold offset changes (4.00 -> 4.02) are not worth a disk write
     * every shot. They still update the in-memory config so the next shot uses
     * the latest value; they just are not flushed until the drift accumulates.
     */
    private const val OFFSET_PERSIST_DELTA = 0.05

    private const val SLOW_CLOUD_REFRESH_MS = 10 * 60 * 1000L

    /** A refresh this recent is good enough when the app comes on screen. */
    private const val CLOUD_REFRESH_MIN_GAP_MS = 60_000L

    /** How long a BLE power toggle stays pending while the poll catches up. */
    private const val BLE_POWER_CONFIRM_MS = 6_000L

    /** Same idea for re-learned vessel tare weights. */
    private const val VESSEL_PERSIST_DELTA = 0.2

    /**
     * How long a dosing vessel landing keeps the machine link wanted (PREP)
     * when no shot follows. Weighing to shot took 3-5 min on 2026-10-23.
     */
    private const val LINK_PREP_HOLD_MS = 10 * 60 * 1000L

    /**
     * How long an arm in IDLE wants the link at SHOT (HIGH, 2 s poll) before
     * dropping to PREP (BALANCED, 10 s poll). An arm left standing - prep
     * running long, a shot abandoned - otherwise held the hot link until the
     * scale idled out, ~22 min. The link stays up either way, and BREWING
     * raises SHOT again within one poll slice.
     */
    private const val ARM_HOT_MS = 3 * 60 * 1000L

    /**
     * The lowest target the +/- buttons reach and the type-in dialog
     * accepts. At 0 the BBW stop is off (`targetYield > 0`) while the page
     * still promises one; just above it, the stop fires on the first drops.
     */
    const val MIN_TARGET_YIELD_GRAMS = 5.0

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "runtime-loop").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    /**
     * Last line of defence: an exception nothing else caught is logged
     * rather than handed to the thread's default handler, which kills the
     * process - mid-shot that took the scale link and the BBW stop with it.
     */
    private val uncaught = CoroutineExceptionHandler { ctx, e ->
        Log.e(TAG, "Uncaught exception in runtime job ${ctx[CoroutineName]?.name ?: ""}", e)
    }

    /** The single-threaded scope every hardware call runs on. */
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("runtime") + uncaught)

    /**
     * Run [block] on the runtime thread as a job of the runtime scope, and
     * wait for it. Two things at once: the work runs on the one thread that
     * owns the hardware and the snapshot, and a caller whose screen goes away
     * (a vessel placement navigating, a banner hiding itself on connect)
     * only stops waiting - the tare or wake itself is not cancelled halfway.
     */
    private suspend fun <T> onRuntime(block: suspend () -> T): T = scope.async { block() }.await()

    private val _snapshot = MutableStateFlow(AppSnapshot())
    val snapshot: StateFlow<AppSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var started = false

    /** Serialises bootstrap and shutdown. */
    private val lifecycle = kotlinx.coroutines.sync.Mutex()

    private lateinit var appContext: Context
    private lateinit var configStore: ConfigStore
    private lateinit var secretsStore: SecretsStore
    private lateinit var database: CoffeeDatabase
    private lateinit var repo: ShotRepository

    /**
     * The live config, observable so Compose can recompose on it.
     *
     * `config` keeps its name and its assignments; it is now a view onto the
     * flow rather than a field of its own. That matters because a screen that
     * reads a plain field has nothing to recompose on: the brew-by-weight
     * switch only appeared to work because scale samples redrew the screen
     * 10-20x a second, and stopped working the moment the scale slept.
     *
     * Replaces an @Volatile field; MutableStateFlow already publishes writes
     * across threads, which is what that annotation was there for.
     */
    private val _config = MutableStateFlow(AppConfig())
    val configFlow: StateFlow<AppConfig> = _config.asStateFlow()

    private var config: AppConfig
        get() = _config.value
        set(value) { _config.value = value }

    private var scale: ScaleAdapter? = null
    private var cloud: CloudClient? = null

    /** The local link to the Micra; null while local control is off. */
    private var micra: MicraBleClient? = null

    /** Every machine call goes through here; it picks BLE or cloud. */
    private var machine: MachineController? = null
    private var micraPumpJob: kotlinx.coroutines.Job? = null

    /** True from a dosing vessel landing until a shot starts or the hold runs out. */
    private val linkPrep = MutableStateFlow(false)
    private var linkPrepJob: kotlinx.coroutines.Job? = null

    /** True for [ARM_HOT_MS] after an arm in IDLE; see [MachineController.linkDemand]. */
    private val armHot = MutableStateFlow(true)
    private var armHotJob: kotlinx.coroutines.Job? = null

    /**
     * The hardware is up (between [start] and [shutdown]). The notification's
     * Quit ends it; the activity listens and closes itself rather than sit on
     * screen over a runtime that no longer answers.
     */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * The Scale page's AUTO switch: vessel detection on for this run of the
     * app. Deliberately not in [AppConfig] - never saved, so it is on again
     * at every launch and a forgotten "off" cannot outlive the process.
     */
    private val _autoDetect = MutableStateFlow(true)
    val autoDetect: StateFlow<Boolean> = _autoDetect.asStateFlow()

    fun setAutoDetect(on: Boolean) = launchLogged("setAutoDetect") {
        _autoDetect.value = on
        shotService?.setAutoPaused(!on)
    }

    private val _bleState = MutableStateFlow(MicraBleClient.State.OFF)

    /** The machine link's state; OFF while local control is off. Survives rebuilds. */
    val bleState: StateFlow<MicraBleClient.State> = _bleState.asStateFlow()

    /** The app is on screen (started): the machine link probes every minute. */
    private val appVisible = MutableStateFlow(false)

    /** From CoffeeApp's LifecycleStartEffect. */
    fun setAppVisible(visible: Boolean) {
        if (appVisible.value == visible) return
        appVisible.value = visible
        Log.i(TAG, if (visible) "App on screen" else "App off screen")
    }

    /** When the cloud was last refreshed (ms), for the on-screen refresh cadence. */
    @Volatile private var lastCloudRefreshAt = 0L

    /**
     * Since when the current cloud problem has stood, kept in prefs so an
     * outage that outlives the app (the 11-01..11-17 one outlived dozens of
     * launches) still says how old it is. Read on first use.
     */
    private val cloudStatusPrefs by lazy {
        appContext.getSharedPreferences("cloud_status", Context.MODE_PRIVATE)
    }

    /** The problem and its start, folded into each machine snapshot. */
    private fun trackCloudProblem(merged: MachineSnapshot, previous: MachineSnapshot): MachineSnapshot {
        val problem = merged.cloudProblem
        val storedName = cloudStatusPrefs.getString("problem", null)
        val storedSince = cloudStatusPrefs.getLong("since", 0L).takeIf { it > 0 }
        if (problem == null) {
            if (storedName != null) {
                val lasted = storedSince?.let { formatDuration(System.currentTimeMillis() - it) } ?: "?"
                Log.i(TAG, "Cloud OK again after $lasted of $storedName")
                cloudStatusPrefs.edit().remove("problem").remove("since").apply()
            }
            return merged.copy(cloudProblemSince = null)
        }
        val since = when {
            previous.cloudProblem == problem && previous.cloudProblemSince != null -> previous.cloudProblemSince
            storedName == problem.name && storedSince != null -> storedSince
            else -> System.currentTimeMillis()
        }
        if (storedName != problem.name || storedSince != since) {
            cloudStatusPrefs.edit().putString("problem", problem.name).putLong("since", since).apply()
            Log.w(TAG, "Cloud problem: $problem (${problem.label} - ${problem.advice})")
        }
        return merged.copy(cloudProblemSince = since)
    }

    private fun formatDuration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> "${m}min"
            m < 48 * 60 -> "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
            else -> "${m / (24 * 60)} days"
        }
    }
    private var slowCloudJob: kotlinx.coroutines.Job? = null
    private var shotService: ShotService? = null
    private var scalePumpJob: kotlinx.coroutines.Job? = null

    private var activeBean: Bean? = null
    private var lastPersistedOffset: Double = 0.0
    private val lastPersistedVessels = mutableMapOf<String, Double>()

    val secrets: SecretsStore get() = secretsStore
    /** Snapshot read, for callers outside a composition. */
    fun config(): AppConfig = config

    /**
     * Whether to show the launch screen.
     *
     * Reads the stored config rather than [config]: the first frame is
     * composed before bootstrap() has loaded anything, so the in-memory copy
     * is still defaults at that point - and the default says show it, which
     * would ignore the user turning it off on every cold start.
     */
    suspend fun splashEnabled(): Boolean =
        if (::configStore.isInitialized) configStore.load().defaults.showSplashScreen else true

    // --- Bootstrap --------------------------------------------------------

    @Volatile
    private var initialised = false

    /**
     * Set up the stores. Cheap, touches no hardware, safe before permissions.
     *
     * Called from Application.onCreate so that anything reading config or
     * secrets during the first composition finds them ready. Separated from
     * [start] deliberately: on a fresh install the Bluetooth permission has
     * not been granted yet, and bootstrapping hardware here would fail to
     * connect once and then never retry.
     */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        appContext = context.applicationContext
        configStore = ConfigStore(appContext)
        secretsStore = SecretsStore(appContext)
        // The encrypted store opens lazily; open it now, off the main thread.
        scope.launch(Dispatchers.IO) { secretsStore.warmUp() }
        // The database is not hardware: history, the bean journal and the
        // importer must all work even if Bluetooth permission was refused.
        database = CoffeeDatabase.build(appContext)
        repo = ShotRepository(database)
        // Publish the stored config before bootstrap() runs. bootstrap waits on
        // the Bluetooth permission, and the theme and the launch screen are
        // both composed long before that - without this they would show the
        // defaults first and snap to the user's settings later.
        scope.launch {
            configStore.loadOrNull()?.let {
                _config.value = it
                publishStoredShotValues(it)
            }
            // The bean is a database read; it need not wait for the scale either.
            refreshActiveBean()
        }
        watchBluetoothAdapter()
        // Mirror out at launch, so a wipe costs at most the shots taken since.
        // Fire-and-forget on purpose: nothing downstream waits on a backup.
        scope.launch { DocumentsBackup.run(appContext, database, "startup") }
    }

    /**
     * Log Bluetooth being switched off and on. Otherwise a scale or machine
     * that "just stopped" with the phone's Bluetooth off leaves only
     * scattered "Bluetooth unavailable" lines to infer it from.
     */
    private fun watchBluetoothAdapter() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
                when (state) {
                    android.bluetooth.BluetoothAdapter.STATE_ON -> Log.i(TAG, "Bluetooth adapter ON")
                    android.bluetooth.BluetoothAdapter.STATE_OFF -> Log.i(TAG, "Bluetooth adapter OFF")
                }
            }
        }
        try {
            appContext.registerReceiver(
                receiver,
                android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            )
        } catch (e: Exception) {
            Log.d(TAG, "Could not watch the Bluetooth adapter", e)
        }
    }

    /**
     * Put the stored offset and target on the Brew page before the shot
     * service exists. bootstrap() only builds the service once the scale
     * connect resolves - up to ~80 s with the scale off - and until then the
     * page showed ShotSnapshot's defaults: "learned offset 4.0 g" against a
     * stored 3.25, which read as the setting having been lost. Skipped once
     * the service is up: from then on it publishes these itself.
     */
    private fun publishStoredShotValues(c: AppConfig) {
        if (shotService != null) return
        _snapshot.update { snap -> snap.copy(
            shot = snap.shot.copy(
                currentOffsetGrams = c.brewByWeight.offsetGrams,
                targetYield = c.defaults.targetYieldGrams,
            ),
        ) }
    }

    /**
     * Bring up the hardware. Called once the Bluetooth permission is granted.
     *
     * Idempotent: the foreground service may be created more than once.
     */
    fun start(context: Context) {
        init(context)
        if (started) return
        started = true
        _running.value = true
        scope.launch { lifecycle.withLock { bootstrap() } }
    }

    private suspend fun bootstrap() {
        val t0 = System.currentTimeMillis()

        // First run seeds from DefaultConfig so the app is usable immediately;
        // after that the stored config is authoritative.
        val stored = configStore.loadOrNull()
        config = if (stored == null) {
            Log.i(TAG, "No stored config; seeding from defaults")
            DefaultConfig.SEED.also { configStore.save(it) }
        } else {
            stored
        }
        // Again here for a first run, where init() found nothing stored.
        publishStoredShotValues(config)
        lastPersistedOffset = config.brewByWeight.offsetGrams
        config.autoTare.vessels.forEach { lastPersistedVessels[it.name] = it.grams }

        Log.i(
            TAG,
            "Bootstrapping: scale(model=${config.scale.model}, newStyle=${config.scale.isNewStyle}, " +
                "idleDisconnect=${config.scale.idleDisconnectMinutes}min) | " +
                "machine serial=${config.machine.serial.ifBlank { "N/A" }} | " +
                if (config.machine.localControl) "local control on, BLE ${config.machine.bleAddress}"
                else "local control off",
        )

        // The cloud goes first, and on its own coroutine.
        //
        // adapter.start() suspends until the BLE connect resolves, which is
        // ~1s with the scale awake but 80s of retries when it is asleep or out
        // of range - and every launch that happened to, the Micra page sat
        // empty for that whole time because this launch was the statement
        // after it. The Micra has no reason to wait for the scale. Launching
        // here works because the connect genuinely suspends rather than
        // blocking the runtime thread.
        val cloudClient = CloudClient(config.machine.serial, secretsStore)
        cloud = cloudClient
        val controller = startMachineLink(cloudClient)
        // Publish whether there is anything to talk to BEFORE the first
        // refresh lands, or a configured install shows NOT SET UP for the
        // second it takes to answer.
        _snapshot.update { snap -> snap.copy(
            machine = snap.machine.copy(configured = cloudConfigured()),
        ) }
        scope.launch { initialCloudRefresh() }
        // Refresh the cloud while someone is looking, and not otherwise
        // (both local-control settings). Since 2.6.0 BLE is up only during a
        // shot, so between shots the cloud is the only source - and the 24/7
        // 10-minute loop it replaces ran mostly for nobody.
        slowCloudJob = scope.launch { cloudRefreshWhileVisible() }

        val adapter = buildScaleAdapter()
        scale = adapter
        adapter.start()

        val service = ShotService(
            scale = adapter,
            machine = controller,
            repo = repo,
            config = config,
            scope = scope,
            onSnapshot = ::updateShotSnapshot,
            onOffsetLearned = ::handleOffsetLearned,
            onVesselLearned = ::handleVesselLearned,
            onShotSaved = ::handleShotSaved,
            onDosingVessel = ::handleDosingVessel,
        )
        shotService = service
        service.setAutoPaused(!_autoDetect.value)
        service.start()

        // Seed the target yield from the configured default.
        service.setTargetYield(config.defaults.targetYieldGrams)

        refreshActiveBean()

        scalePumpJob = scope.launch { scaleSnapshotPump(adapter) }

        Log.i(
            TAG,
            "Bootstrap complete in ${(System.currentTimeMillis() - t0) / 1000.0}s " +
                "(mostly the scale connect; the cloud refresh ran alongside it)",
        )
    }

    /**
     * Mirror the history out again after each saved shot.
     *
     * The one moment the database has changed in a way that would hurt to
     * lose. Cheap enough to do every time: one checkpoint and a copy of a file
     * measured in hundreds of kilobytes, off the hardware thread.
     */
    private fun handleShotSaved() {
        scope.launch { DocumentsBackup.run(appContext, database, "shot saved") }
    }

    /**
     * Beans are being weighed, so a shot is minutes away: want the machine
     * link now (PREP), so a wedged machine shows on the dot in time to
     * power-cycle it. Each placement restarts the hold.
     */
    private fun handleDosingVessel(name: String) {
        linkPrepJob?.cancel()
        if (!linkPrep.value) {
            Log.i(TAG, "$name placed: machine link wanted for up to ${LINK_PREP_HOLD_MS / 60_000} min ahead of a shot")
        }
        linkPrep.value = true
        linkPrepJob = scope.launch {
            delay(LINK_PREP_HOLD_MS)
            Log.i(TAG, "No shot within ${LINK_PREP_HOLD_MS / 60_000} min of the $name; machine link no longer wanted")
            linkPrep.value = false
        }
    }

    /** A shot started: SHOT demand covers the link from here, and the release follows the shot. */
    private fun endLinkPrep() {
        if (!linkPrep.value) return
        linkPrepJob?.cancel()
        linkPrepJob = null
        linkPrep.value = false
    }

    /**
     * The BLE client, or null while local control is off or no machine has
     * been picked - in which case every call routes to the cloud with
     * "BLE not configured". The token is read on each connect attempt, so a
     * cloud sync or a manual entry lands without a rebuild.
     */
    private fun buildMicraClient(): MicraBleClient? {
        val m = config.machine
        if (!m.localControl) return null
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(m.bleAddress)) {
            Log.w(TAG, "Local control is on but the machine address '${m.bleAddress}' is not valid")
            return null
        }
        return MicraBleClient(appContext, m.bleAddress, { secretsStore.bleToken }, scope)
    }

    /**
     * Build the BLE client (if any) and the controller over it and [cloudClient],
     * start holding the link, and pump its polls into the snapshot.
     */
    private fun startMachineLink(cloudClient: CloudClient): MachineController {
        val client = buildMicraClient()
        micra = client
        val controller = MachineController(
            cloud = cloudClient,
            ble = client,
            log = { Log.i("MachineController", it) },
        )
        machine = controller
        if (client != null) {
            client.start()
            micraPumpJob = scope.launch {
                launch {
                    combine(_snapshot, linkPrep, armHot, MachineController::linkDemand)
                        .distinctUntilChanged()
                        .collect(client::setDemand)
                }
                launch { appVisible.collect(client::setAppVisible) }
                // Machine brewing events (BLE shot counter) to the shot logic.
                launch { client.brewUpdates.collect { shotService?.onMachineBrew(it) } }
                launch {
                    client.seen.collect {
                        _snapshot.update { snap -> snap.copy(
                            machine = snap.machine.copy(bleSeen = it),
                        ) }
                    }
                }
                launch {
                    var previous: MicraBleClient.State? = null
                    client.state.collect {
                        if (previous != null && previous != it) {
                            Log.i(
                                TAG,
                                "Machine link state $previous -> $it " +
                                    "(demand ${MachineController.linkDemand(_snapshot.value, linkPrep.value, armHot.value)}, " +
                                    "seen=${client.seen.value})",
                            )
                        }
                        previous = it
                        // A rejected token: the cloud carries the current one.
                        if (it == MicraBleClient.State.BAD_TOKEN) scope.launch { refreshCloudIfStale(0L) }
                        _bleState.value = it
                        _snapshot.update { snap -> snap.copy(
                            machine = MachineController.withLink(snap.machine, it),
                        ) }
                    }
                }
                client.readings.filterNotNull().collect(::applyBleReadings)
            }
        }
        return controller
    }

    private suspend fun stopMachineLink() {
        micraPumpJob?.cancel()
        micraPumpJob = null
        micra?.stop()
        micra = null
        _bleState.value = MicraBleClient.State.OFF
        _snapshot.update { snap -> snap.copy(
            machine = MachineController.withLink(snap.machine, MicraBleClient.State.OFF)
                .copy(bleSeen = false),
        ) }
    }

    /**
     * While the app is on screen: refresh at once (unless one landed in the
     * last minute), then every [SLOW_CLOUD_REFRESH_MS]. Off screen, nothing.
     * The machine is switched off between uses now, so a page that refreshed
     * only every 10 min showed it off for up to 10 min after switching it on.
     */
    private suspend fun cloudRefreshWhileVisible() {
        appVisible.collectLatest { visible ->
            if (!visible) return@collectLatest
            while (true) {
                refreshCloudIfStale(CLOUD_REFRESH_MIN_GAP_MS)
                delay(SLOW_CLOUD_REFRESH_MS)
            }
        }
    }

    /** Refresh unless the last one is younger than [minGapMs]. For screens opening. */
    suspend fun refreshCloudIfStale(minGapMs: Long = CLOUD_REFRESH_MIN_GAP_MS) = onRuntime {
        if (!cloudConfigured()) return@onRuntime
        if (System.currentTimeMillis() - lastCloudRefreshAt < minGapMs) return@onRuntime
        try {
            updateMachineSnapshot(machine?.refreshCloud() ?: return@onRuntime)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud refresh failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun buildScaleAdapter() = ScaleAdapter(
        context = appContext,
        mac = config.scale.macAddress,
        model = com.coffeeapp.droid.ble.ScaleModel.fromId(config.scale.model),
        isNewStyle = config.scale.isNewStyle,
        idleTimeoutSeconds = config.scale.idleDisconnectMinutes * 60.0,
        activityThreshold = config.scale.idleActivityThresholdGrams.toFloat(),
        notifyChar = config.scale.notifyCharUuid,
        writeChar = config.scale.writeCharUuid,
        scope = scope,
    ).also { adapter ->
        // A dropped scale reconnects by itself only while a shot needs it.
        adapter.wantLink = { _snapshot.value.shot.armed || shotService?.shotLive == true }
        // And never idles out under one: the idle clock counts 10 g steps,
        // so a shot started late in a long wait could be put to sleep in
        // pre-infusion, before it had poured enough to reset the clock.
        adapter.keepAwake = { shotService?.shotLive == true }
    }

    /** Keeps the scale section of the snapshot current even when idle. */
    private suspend fun scaleSnapshotPump(adapter: ScaleAdapter) {
        adapter.samples.collect { sample ->
            _snapshot.update { snap -> snap.copy(
                scale = ScaleSnapshot(
                    connected = sample.connected,
                    // Zero unless the link is up. The runtime outlives the UI -
                    // the foreground service keeps it alive - so reopening the
                    // app after the scale dropped or was switched off would
                    // otherwise redisplay the last weight it ever sent, which
                    // reads as a live reading and is not one. The idle-sleep
                    // path already published a synthetic 0.0 for this reason;
                    // this covers every other way the link can end.
                    weight = if (sample.connected) sample.weight.toDouble() else 0.0,
                    battery = sample.battery,
                    asleep = sample.asleep,
                ),
            ) }
        }
    }

    private suspend fun initialCloudRefresh() {
        if (!cloudConfigured()) {
            Log.i(TAG, "Cloud not configured (account or serial missing); skipping the refresh")
            return
        }
        try {
            updateMachineSnapshot(machine?.refreshCloud() ?: return)
        } catch (e: Exception) {
            Log.e(TAG, "Initial cloud refresh failed", e)
        }
    }

    // --- Snapshot updates -------------------------------------------------

    private fun updateShotSnapshot(s: ShotSnapshot) {
        if (s.state == ShotState.BREWING) endLinkPrep()
        val before = _snapshot.value.shot
        val armedIdle = s.armed && s.state == ShotState.IDLE
        if (armedIdle && !(before.armed && before.state == ShotState.IDLE)) startArmHot()
        _snapshot.update { snap -> snap.copy(shot = s) }
    }

    /** A new arm in IDLE: the link at SHOT for [ARM_HOT_MS], then PREP. */
    private fun startArmHot() {
        armHotJob?.cancel()
        armHot.value = true
        armHotJob = scope.launch {
            delay(ARM_HOT_MS)
            val shot = _snapshot.value.shot
            if (shot.armed && shot.state == ShotState.IDLE) {
                Log.i(TAG, "Armed ${ARM_HOT_MS / 60_000} min with no shot; machine link down to PREP")
            }
            armHot.value = false
        }
    }

    /** An account and a serial both present - the cloud cannot be reached without either. */
    private fun cloudConfigured(): Boolean =
        secretsStore.hasCredentials && config.machine.serial.isNotBlank()

    private fun updateMachineSnapshot(m: MachineSnapshot) {
        lastCloudRefreshAt = System.currentTimeMillis()
        val previous = _snapshot.value.machine
        // A failed refresh keeps the last good values (MachineController.
        // mergeCloud), and the latest BLE poll goes back on top: the cloud lags
        // the machine by seconds, and must not overwrite a fresher local read.
        val merged = trackCloudProblem(MachineController.compose(previous, m, micra?.readings?.value), previous)
        // Preserve any in-flight power toggle so the UI keeps showing it.
        // Recomputed rather than latched true: credentials can be cleared
        // as well as entered, and that should read as NOT SET UP again rather
        // than as a machine that has stopped answering.
        _snapshot.update { snap -> snap.copy(
            machine = merged.copy(
                pendingPower = previous.pendingPower,
                pendingCommand = previous.pendingCommand,
                commandError = previous.commandError,
                configured = cloudConfigured(),
            ),
        ) }
    }

    /** A BLE poll landed: layer it over whatever the cloud last said. */
    private fun applyBleReadings(r: MicraBleClient.Readings) {
        _snapshot.update { snap -> snap.copy(
            machine = MachineController.applyBle(snap.machine, r),
        ) }
    }

    private fun setPendingPower(pending: Boolean?) {
        _snapshot.update { snap -> snap.copy(
            machine = snap.machine.copy(pendingPower = pending),
        ) }
    }

    // --- Commands ---------------------------------------------------------

    /**
     * Zero the scale, waking it first if the link is down.
     *
     * Suspending for the same reason as [reconnectScaleNow]: against a
     * sleeping scale this tare waits on a full reconnect before it can zero
     * anything.
     */
    suspend fun tareNow() = onRuntime {
        val service = shotService
        val s = _snapshot.value
        if (service != null && service.shotLive) {
            // A tare mid-shot drops every later sample as implausible, so the
            // BBW stop never fires. The buttons are disabled then; this is
            // the backstop.
            Log.w(TAG, "Manual tare refused during a shot (at %.1fg)".format(java.util.Locale.US, s.scale.weight))
            return@onRuntime
        }
        Log.i(
            TAG,
            "Manual tare (state=%s, weight %.1fg, connected=%s)".format(
                java.util.Locale.US, s.shot.state, s.scale.weight, s.scale.connected,
            ),
        )
        service?.onManualTare()
        scale?.tare()
    }

    /**
     * Manual wake. There is deliberately no auto-wake from an idle sleep.
     *
     * Suspends rather than firing and forgetting: waking a sleeping scale runs
     * three connect attempts and has taken over two minutes in the logs, and a
     * button that looks inert for that long gets pressed again and again, each
     * press queueing more work.
     */
    suspend fun reconnectScaleNow(): Boolean = onRuntime {
        Log.i(TAG, "Scale wake requested by hand (asleep=${_snapshot.value.scale.asleep})")
        scale?.reconnect() ?: false
    }

    fun resetShot() = launchLogged("resetShot") { shotService?.reset() }

    fun armShot() = launchLogged("armShot") { shotService?.setArmed(true) }

    fun disarmShot() = launchLogged("disarmShot") { shotService?.setArmed(false) }

    fun setTargetYield(grams: Double) = launchLogged("setTargetYield") { applyTargetYield(grams) }

    /** The +/- buttons: relative to the live target, so a held button cannot lag behind. */
    fun stepTargetYield(deltaGrams: Double) = launchLogged("stepTargetYield") {
        applyTargetYield(_snapshot.value.shot.targetYield + deltaGrams)
    }

    private var targetSaveJob: kotlinx.coroutines.Job? = null

    /**
     * Set the target and remember it as the default, so a restart comes back
     * to the last target instead of 36 g. The save waits for a second of
     * quiet: a held +/- button steps every 60 ms.
     */
    private fun applyTargetYield(grams: Double) {
        val rounded = Math.round(maxOf(MIN_TARGET_YIELD_GRAMS, grams) * 10.0) / 10.0
        val service = shotService
        if (service != null) {
            service.setTargetYield(rounded)
        } else {
            _snapshot.update { snap -> snap.copy(shot = snap.shot.copy(targetYield = rounded)) }
        }
        config = config.copy(defaults = config.defaults.copy(targetYieldGrams = rounded))
        targetSaveJob?.cancel()
        targetSaveJob = scope.launch {
            delay(1_000)
            configStore.save(config)
            Log.i(TAG, "Target yield $rounded g saved as the default")
        }
    }

    fun setBrewByWeight(enabled: Boolean) = launchLogged("setBrewByWeight") {
        Log.i(TAG, "Brew-by-weight ${if (enabled) "enabled" else "disabled"}")
        // On the runtime thread, like every other config write: from Main it
        // raced handleOffsetLearned at FINALIZE and one of the two was lost.
        config = config.copy(brewByWeight = config.brewByWeight.copy(enabled = enabled))
        shotService?.setBrewByWeight(enabled)
        configStore.save(config)
    }

    /**
     * Refresh on demand and suspend until it lands.
     *
     * Suspends rather than firing and forgetting, the way backupNow does: the
     * automatic refreshes are background work nobody watches, but this one is
     * pressed by someone looking at the screen waiting for it to change.
     * CloudClient.refresh never throws, so a failure arrives as a snapshot
     * with lastSyncOk=false rather than an exception.
     */
    suspend fun refreshCloudNow() = onRuntime {
        if (!cloudConfigured()) return@onRuntime
        updateMachineSnapshot(machine?.refreshCloud() ?: return@onRuntime)
    }

    private var commandErrorJob: kotlinx.coroutines.Job? = null

    /** How long a failed command's message stays up. */
    private const val COMMAND_ERROR_SHOW_MS = 10_000L

    /** Say why a machine command failed, for [COMMAND_ERROR_SHOW_MS]. */
    private fun showCommandError(what: String, e: Exception) {
        val why = when (com.coffeeapp.droid.core.cloud.CloudProblem.classify(e)) {
            com.coffeeapp.droid.core.cloud.CloudProblem.NETWORK -> "the cloud is unreachable"
            com.coffeeapp.droid.core.cloud.CloudProblem.OTHER -> e.message?.take(80) ?: e.javaClass.simpleName
            else -> com.coffeeapp.droid.core.cloud.CloudProblem.classify(e).label.lowercase()
        }
        val message = "Could not $what: $why"
        Log.w(TAG, "$message (${e.javaClass.simpleName}: ${e.message})")
        _snapshot.update { snap -> snap.copy(machine = snap.machine.copy(commandError = message)) }
        commandErrorJob?.cancel()
        commandErrorJob = scope.launch {
            delay(COMMAND_ERROR_SHOW_MS)
            _snapshot.update { snap ->
                if (snap.machine.commandError == message) {
                    snap.copy(machine = snap.machine.copy(commandError = null))
                } else {
                    snap
                }
            }
        }
    }

    /**
     * A temperature or steam command: marked [pending] on the snapshot while
     * it runs, and a failure shown rather than only logged - with the cloud
     * down the tapped option simply never moved, and got tapped again.
     */
    private fun machineCommand(name: String, pending: String, what: String, block: suspend () -> Unit) =
        scope.launch {
            _snapshot.update { snap ->
                snap.copy(machine = snap.machine.copy(pendingCommand = pending, commandError = null))
            }
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Background command $name failed", e)
                showCommandError(what, e)
            } finally {
                _snapshot.update { snap ->
                    if (snap.machine.pendingCommand == pending) {
                        snap.copy(machine = snap.machine.copy(pendingCommand = null))
                    } else {
                        snap
                    }
                }
            }
        }

    fun setCoffeeTemp(temperature: Double) = machineCommand(
        "setCoffeeTemp", "coffee:${Math.round(temperature)}", "set ${Math.round(temperature)}°C",
    ) {
        val routed = machine?.setCoffeeTemp(temperature) ?: return@machineCommand
        // Optimistic local update so the UI reflects the change immediately;
        // the authoritative read runs behind it - the next 2 s poll over BLE,
        // a delayed cloud refresh otherwise.
        _snapshot.update { snap -> snap.copy(
            machine = snap.machine.copy(coffeeTemp = Math.round(temperature * 10.0) / 10.0),
        ) }
        if (routed.path == ControlPath.CLOUD) refreshAfterChange()
    }

    fun setSteamEnabled(enabled: Boolean) = machineCommand(
        "setSteamEnabled", "steamOn:$enabled", "switch the steam boiler ${if (enabled) "on" else "off"}",
    ) {
        val routed = machine?.setSteamEnabled(enabled) ?: return@machineCommand
        _snapshot.update { snap -> snap.copy(
            machine = snap.machine.copy(steamEnabled = enabled),
        ) }
        if (routed.path == ControlPath.CLOUD) refreshAfterChange()
    }

    /** [level] is "Level1", "Level2" or "Level3". */
    fun setSteamLevel(level: String) = machineCommand(
        "setSteamLevel", "steamLevel:$level", "set steam ${level.replace("Level", "level ")}",
    ) {
        val routed = machine?.setSteamLevel(level) ?: return@machineCommand
        _snapshot.update { snap -> snap.copy(
            machine = snap.machine.copy(steamLevel = level),
        ) }
        if (routed.path == ControlPath.CLOUD) refreshAfterChange()
    }

    /**
     * Read a setting back once the cloud has caught up with the command.
     *
     * The delay is not politeness: the dashboard lags a command by seconds -
     * the same lag togglePower waits out - so refreshing immediately reads the
     * pre-command value and overwrites the optimistic update above it, making
     * the chip visibly jump back to where it was. The optimistic value is
     * already on screen, so nobody is watching this wait.
     */
    private suspend fun refreshAfterChange() {
        try {
            delay((config.machine.powerRefreshDelaySeconds * 1000).toLong())
            updateMachineSnapshot(machine?.refreshCloud() ?: return)
        } catch (e: Exception) {
            Log.e(TAG, "Background refresh after change failed", e)
        }
    }

    fun togglePower(on: Boolean) = launchLogged("togglePower") {
        Log.i(TAG, "Power toggled by hand -> ${if (on) "on" else "standby"} (shown on=${_snapshot.value.machine.powerOn})")
        setPendingPower(on)
        // A power-off by hand must not be undone by the steam power-on still
        // queued from the last stop.
        if (!on) shotService?.cancelPendingPowerOn("machine switched off by hand")
            try {
                val controller = machine ?: return@launchLogged
                val routed = controller.setPower(on)
                if (routed.path == ControlPath.BLE) {
                    // No 17 s cloud wait: the poll shows the new mode within
                    // seconds. Not the very next read, though - a read straight
                    // after a command returns the old mode (Apollo2's logs) -
                    // so stay pending until a poll agrees, or give up.
                    withTimeoutOrNull(BLE_POWER_CONFIRM_MS) {
                        controller.bleReadings?.first {
                            it != null &&
                                (it.mode == MicraProtocol.MachineMode.BREWING) == on
                        }
                    }
                } else {
                    delay((config.machine.powerRefreshDelaySeconds * 1000).toLong())
                    updateMachineSnapshot(controller.refreshCloud())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shown, not only logged: the switch quietly snapping back
                // was the only sign the machine had not moved.
                showCommandError("switch the machine ${if (on) "on" else "to standby"}", e)
            } finally {
                setPendingPower(null)
            }
    }

    /** Scales seen in an 8 s scan, as (name, address, brand), for Settings to pick from. */
    suspend fun scanForScales(): List<Triple<String, String, com.coffeeapp.droid.ble.ScaleModel>> =
        onRuntime { com.coffeeapp.droid.ble.ScaleGattClient.scan(appContext) }
            .also { Log.i(TAG, "Scale scan found ${it.ifEmpty { "nothing" }}") }

    /** Machines seen in an 8 s scan, as (name, address), for Settings to pick from. */
    suspend fun scanForMachines(): List<Pair<String, String>> =
        onRuntime { MicraBleClient.scan(appContext) }
            .also { Log.i(TAG, "Machine scan found ${it.ifEmpty { "nothing" }}") }

    /**
     * Skip the reconnect backoff - the user is waiting on it. On the runtime
     * thread: setDemand writes the same client fields there, and a press from
     * Main racing an arm could leave the release clock running under a shot.
     */
    fun reconnectMachineNow() = launchLogged("reconnectMachineNow") {
        Log.i(TAG, "RECONNECT NOW pressed (machine link ${_bleState.value})")
        micra?.reconnectNow()
    }

    /** A BLE token typed in Settings, for when the cloud does not supply one. */
    fun setBleToken(token: String) {
        secretsStore.bleToken = token.trim()
        Log.i(TAG, "BLE token entered manually")
        reconnectMachineNow()
    }

    // --- Configuration ----------------------------------------------------

    fun updateConfig(newConfig: AppConfig) = launchLogged("updateConfig") {
        val old = config
        val changes = summarizeConfigChanges(old, newConfig)
        configStore.save(newConfig)
        config = newConfig
        shotService?.setConfig(newConfig)

        if (changes.isNotEmpty()) {
            Log.i(TAG, "Configuration updated (${changes.size} field(s)): ${changes.joinToString("; ")}")
        } else {
            Log.i(TAG, "Configuration saved with no field changes")
        }

        // Every scale field except the cosmetic device name. Note autoTare is deliberately NOT
        // here: it is a live kill-switch and must apply on the next weight
        // sample with no BLE reconnect.
        val s = old.scale
        val n = newConfig.scale
        val needsScaleRestart = s.macAddress != n.macAddress ||
            s.model != n.model ||
            s.isNewStyle != n.isNewStyle ||
            s.notifyCharUuid != n.notifyCharUuid ||
            s.writeCharUuid != n.writeCharUuid ||
            s.idleDisconnectMinutes != n.idleDisconnectMinutes ||
            s.idleActivityThresholdGrams != n.idleActivityThresholdGrams
        val needsCloudRestart = old.machine.serial != newConfig.machine.serial
        val needsLinkRestart = old.machine.localControl != newConfig.machine.localControl ||
            old.machine.bleAddress != newConfig.machine.bleAddress

        if (needsScaleRestart || needsCloudRestart || needsLinkRestart) {
            Log.i(
                TAG,
                "Config change requires a hardware restart " +
                    "(scale=$needsScaleRestart, cloud=$needsCloudRestart, link=$needsLinkRestart)",
            )
            restartHardware(needsScaleRestart, needsCloudRestart, needsLinkRestart)
        }
    }

    /** Credentials changed: rebuild the cloud client so it re-authenticates. */
    fun credentialsChanged() = launchLogged("credentialsChanged") {
        Log.i(TAG, "Cloud credentials changed; rebuilding the cloud client")
        restartHardware(restartScale = false, restartCloud = true)
        refreshAfterChange()
    }

    private suspend fun restartHardware(
        restartScale: Boolean,
        restartCloud: Boolean,
        restartLink: Boolean = false,
    ) {
        if (restartScale) {
            Log.i(TAG, "Rebuilding the scale adapter after a config change")
            // Cancel the old pump first so it stops collecting from the dead
            // adapter, then tear the adapter down.
            scalePumpJob?.cancel()
            scalePumpJob = null
            scale?.stop()

            val adapter = buildScaleAdapter()
            scale = adapter
            adapter.start()
            scalePumpJob = scope.launch { scaleSnapshotPump(adapter) }
        }

        // The controller wraps both transports, so either change rebuilds it.
        if (restartCloud || restartLink) {
            stopMachineLink()
            if (restartCloud) {
                Log.i(TAG, "Rebuilding the cloud client after a config change")
                cloud?.close()
                cloud = CloudClient(config.machine.serial, secretsStore)
            }
            Log.i(TAG, "Rebuilding the machine link (localControl=${config.machine.localControl})")
            cloud?.let(::startMachineLink)
        }

        shotService?.rebind(
            newScale = if (restartScale) scale else null,
            newMachine = if (restartCloud || restartLink) machine else null,
        )
        Log.i(TAG, "Hardware restart complete")
    }

    // --- Database passthrough ---------------------------------------------

    fun recentShots(limit: Int = 100): Flow<List<ShotWithBean>> =
        if (::repo.isInitialized) repo.recentShots(limit) else flowOf(emptyList())

    fun shotCount(): Flow<Int> =
        if (::repo.isInitialized) repo.shotCount() else flowOf(0)

    fun listBeans(): Flow<List<Bean>> =
        if (::repo.isInitialized) repo.listBeans() else flowOf(emptyList())

    fun deleteShots(ids: List<Int>) = launchLogged("deleteShots") {
        repo.deleteShots(ids)
        Log.i(TAG, "Deleted ${ids.size} shot(s): ids ${ids.joinToString(",")}")
    }

    /**
     * Import a coffee_history.db: a backup from Documents/CoffeeApp, or an
     * older database file.
     *
     * Suspends and returns the outcome rather than firing and forgetting,
     * because this is the one operation the user is actively waiting on and a
     * silent result would be useless. Safe to run more than once - see
     * [DatabaseImporter].
     */
    suspend fun importDatabase(context: Context, uri: android.net.Uri): DatabaseImporter.Result {
        if (!::repo.isInitialized) {
            return DatabaseImporter.Result(error = "The app is still starting up; try again.")
        }
        // In the runtime scope, so collapsing the Restore section (which
        // disposes its coroutine scope) cannot cancel an import halfway.
        return onRuntime {
            val result = DatabaseImporter(repo, database).importFrom(context.applicationContext, uri)
            if (result.ok) {
                // An imported journal may carry a current bean, and the shot
                // service needs to know about it before the next shot is saved.
                refreshActiveBean()
                // And the restored history belongs in the mirror now, not at
                // the next shot or launch.
                scope.launch { DocumentsBackup.run(appContext, database, "import") }
            }
            result
        }
    }

    /**
     * Copy the history and log out on demand.
     *
     * Suspends and returns the outcome rather than firing and forgetting, the
     * way importDatabase does: the automatic copies are background work nobody
     * watches, but this one is pressed by someone waiting for an answer -
     * usually just before letting Studio reinstall the app.
     */
    suspend fun backupNow(): DocumentsBackup.Result {
        if (!::database.isInitialized) {
            return DocumentsBackup.Result(false, "The app is still starting up; try again.")
        }
        return onRuntime { DocumentsBackup.run(appContext, database, "manual") }
    }

    fun addBean(name: String, grind: Double, tasty: Boolean? = null) =
        launchLogged("addBean") {
            val id = repo.addBean(name, grind, tasty)
            Log.i(TAG, "Bean added #$id '${name.trim()}' grind $grind")
        }

    fun updateBean(id: Int, name: String, grind: Double?, tasty: Boolean?) =
        launchLogged("updateBean") {
            repo.updateBean(id, name, grind, tasty)
            Log.i(TAG, "Bean #$id updated: '${name.trim()}' grind $grind tasty $tasty")
            if (activeBean?.id == id) refreshActiveBean()
        }

    fun deleteBean(id: Int) = launchLogged("deleteBean") {
        repo.deleteBean(id)
        Log.i(TAG, "Bean #$id deleted")
        if (activeBean?.id == id) refreshActiveBean()
    }

    fun setActiveBean(id: Int?) = launchLogged("setActiveBean") {
        repo.setCurrentBean(id)
        refreshActiveBean()
        Log.i(TAG, "Active bean -> ${activeBean?.let { "#${it.id} '${it.name}' (grind ${it.grindSetting})" } ?: "none"}")
    }

    /** Re-read the current bean and fan it out to the snapshot + shot service. */
    private suspend fun refreshActiveBean() {
        val bean = repo.currentBean()
        activeBean = bean
        _snapshot.update { snap -> snap.copy(
            activeBeanName = bean?.name ?: "",
            activeBeanGrind = bean?.grindSetting,
        ) }
        shotService?.setActiveBean(bean?.id, bean?.grindSetting)
    }

    // --- Learning callbacks -----------------------------------------------

    private fun handleOffsetLearned(newOffset: Double) {
        config = config.copy(brewByWeight = config.brewByWeight.copy(offsetGrams = newOffset))
        // Keep the service view of the config in lockstep so the next shot is
        // not relying on finalize having pre-updated the cache.
        shotService?.setConfig(config)
        if (abs(newOffset - lastPersistedOffset) < OFFSET_PERSIST_DELTA) {
            Log.d(TAG, "Offset $newOffset kept in memory (moved < $OFFSET_PERSIST_DELTA since the last save)")
            return
        }
        launchLogged("persistOffset") {
            configStore.save(config)
            lastPersistedOffset = newOffset
            Log.i(TAG, "Offset $newOffset persisted")
        }
    }

    private fun handleVesselLearned(name: String, newGrams: Double) {
        val at = config.autoTare
        val updated = at.vessels.map { if (it.name == name) it.copy(grams = newGrams) else it }
        if (updated == at.vessels) return
        config = config.copy(autoTare = at.copy(vessels = updated))
        shotService?.setConfig(config)

        val last = lastPersistedVessels[name]
        if (last != null && abs(newGrams - last) < VESSEL_PERSIST_DELTA) return
        launchLogged("persistVessel") {
            configStore.save(config)
            lastPersistedVessels[name] = newGrams
        }
    }

    // --- Plumbing ---------------------------------------------------------

    /**
     * Run on the runtime dispatcher, logging anything that escapes.
     *
     * Without this an exception inside a fire-and-forget command would be
     * swallowed by the scope and vanish.
     */
    private fun launchLogged(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Background command $name failed", e)
            }
        }
    }

    fun shutdown() {
        started = false
        _running.value = false
        scope.launch {
            // Under the same lock as bootstrap: a service re-created while
            // this teardown is suspended waits for it to finish, instead of
            // building a second set of hardware beside one going away.
            lifecycle.withLock {
                try {
                    // Quit mid-shot: RESET's path stops the machine first.
                    // stop() alone only cancels jobs, and the BBW stop went
                    // with them while the machine poured on.
                    shotService?.let { if (it.shotLive) it.reset() }
                    shotService?.cancelPendingPowerOn("app quit")
                    shotService?.stop()
                    linkPrepJob?.cancel()
                    linkPrep.value = false
                    armHotJob?.cancel()
                    scalePumpJob?.cancel()
                    scalePumpJob = null
                    slowCloudJob?.cancel()
                    slowCloudJob = null
                    scale?.stop()
                    stopMachineLink()
                    cloud?.close()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Shutdown had errors", e)
                } finally {
                    shotService = null
                    scale = null
                    cloud = null
                    machine = null
                    // Nothing feeds the snapshot now. Left as it was, a
                    // reopened screen showed a connected scale and a frozen
                    // weight; keep only what is stored (target, offset).
                    _snapshot.update { snap -> snap.copy(
                        scale = ScaleSnapshot(),
                        shot = ShotSnapshot(
                            targetYield = snap.shot.targetYield,
                            currentOffsetGrams = snap.shot.currentOffsetGrams,
                        ),
                    ) }
                    Log.i(TAG, "Runtime shutdown complete")
                }
            }
        }
    }
}
