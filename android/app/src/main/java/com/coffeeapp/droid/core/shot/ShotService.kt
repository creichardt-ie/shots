// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.core.log.FileLog as Log
import com.coffeeapp.droid.ble.WeightSample
import com.coffeeapp.droid.core.machine.ControlPath
import com.coffeeapp.droid.ble.MicraShotCounter
import com.coffeeapp.droid.core.machine.MachineController
import com.coffeeapp.droid.core.config.AppConfig
import com.coffeeapp.droid.core.model.ShotSnapshot
import com.coffeeapp.droid.core.model.ShotState
import com.coffeeapp.droid.core.model.TimerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Orchestrator: feeds weight samples into the state machine and performs IO.
 *
 * The state machine and placement detector are pure; the IO - machine calls (through MachineController), database saves,
 * the post-stop power-on sequence and snapshot publishing - lives here.
 *
 * Everything runs on the runtime single-threaded dispatcher. That is load-
 * bearing: none of the mutable state below is synchronised, because nothing
 * else is supposed to touch it.
 */
class ShotService(
    private var scale: ScaleSource,
    private var machine: MachineController,
    private val repo: ShotSink,
    private var config: AppConfig,
    private val scope: CoroutineScope,
    private val onSnapshot: (ShotSnapshot) -> Unit,
    private val onOffsetLearned: (Double) -> Unit,
    private val onVesselLearned: ((String, Double) -> Unit)? = null,
    private val onShotSaved: (() -> Unit)? = null,
    /** A dosing vessel landed, bare or with beans; gets the vessel name. */
    private val onDosingVessel: ((String) -> Unit)? = null,
    /** Wall-clock seconds; the tests pass virtual time. */
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {

    private companion object {
        const val TAG = "ShotService"

        /**
         * Live flow rate is computed over a trailing window rather than
         * sample-to-sample: the scale jitters ~0.1 g, so a single-sample
         * derivative is ~1 g/s of pure noise - the same order as real flow.
         */
        const val FLOW_WINDOW_SECONDS = 1.0
        const val FLOW_MIN_SPAN_SECONDS = 0.4

        /**
         * How long past the settle hold a transition may run before it is
         * worth a log line saying the weight moved but never plateaued.
         */
        const val TRANSIT_LOG_SLACK_SECONDS = 2.0

        /**
         * A gap this long between samples means the stream stopped: the scale
         * slept on its idle timer, or the link dropped. Samples arrive at
         * 10-20 Hz, so anything near this is not a hiccup.
         */
        const val STREAM_GAP_SECONDS = 5.0

        /**
         * A live shot with no sample for this long has lost its scale. Twice
         * the stream-gap threshold, so a slow BLE hiccup is not mistaken for it.
         */
        const val SHOT_SILENCE_SECONDS = 10.0

        /**
         * The stop is retried this many times, [STOP_RETRY_DELAY_MS] apart,
         * before the shot is flagged as unstopped. The first attempt is the
         * normal path, unchanged; the retries only run after it threw.
         */
        const val STOP_ATTEMPTS = 3
        const val STOP_RETRY_DELAY_MS = 500L

        /**
         * Ceiling on the cloud lead: 0.6 s at ~6.7 g/s, twice the fastest
         * shot on record, so a flow-window glitch cannot stop a shot early.
         */
        const val MAX_CLOUD_LEAD_GRAMS = 4.0

        /**
         * A shot-counter state older than this is not evidence the machine is
         * brewing: updates come ~1.07 s apart while it is, so three missed
         * means the link or the counter went quiet.
         */
        const val BREW_EVENT_FRESH_MS = 3_000L

        /**
         * How long after a stop the machine may keep reporting itself brewing
         * before the stop counts as not having landed. A cloud stop reaches
         * the machine ~0.6 s after a BLE one; this leaves room for both.
         */
        const val STOP_CONFIRM_MS = 2_500L

        const val ALERT_STOP_FAILED = "Stop failed - stop the machine by hand"
        const val ALERT_SCALE_LOST = "Scale lost mid-shot - stop the machine by hand"
        const val ALERT_ENDED_NO_STOP = "Shot ended without a stop - check the machine"

        /** An [Occupant] lapses after this long, so a missed lift cannot stick. */
        const val OCCUPIED_MAX_SECONDS = 600.0
    }

    // --- Auto-tare state --------------------------------------------------

    private val placement = PlacementDetector(
        stabilityBand = config.autoTare.stabilityBandGrams,
        stableSeconds = config.autoTare.stableSeconds,
    )
    private var netOffset: Double? = null
    private var netLabel: String = ""
    private var netVesselGrams: Double? = null
    private var autoTareHint: String = ""

    /**
     * The Scale page's AUTO switch (2.17.0): vessel detection off for this
     * run of the app only. Never saved, so every launch starts with it on;
     * the saved [AppConfig.autoTare] enabled still applies underneath.
     */
    private var autoPaused = false

    /** Auto-arm is deferred until the tare has visibly landed. */
    private var pendingArm: String? = null
    private var pendingArmDeadline: Double = 0.0

    /**
     * A vessel auto-tare recognised and that is still on the scale (2.13.1).
     *
     * Nothing lands on top of a vessel, so while one is on the scale a rise
     * is its contents. Without this, milk poured into the tared pitcher read
     * as a new vessel whenever the amount matched one: 190 g as the Coffee
     * cup (tared away and ARMED, 11-17 and 11-19), 114.5 g as the Dosing cup
     * (11-17). Pours run at 35-78 g/s, over the 20 g/s placement floor, and a
     * real cup can land at 50 g/s (11-18), so no rate floor separates them.
     *
     * [emptyAt] is what the scale reads with the vessel off: minus its weight
     * after its tare, or the pre-placement baseline for a vessel not tared
     * (one placed with contents). Policy only; PlacementDetector is untouched.
     */
    private data class Occupant(val name: String, val grams: Double, val emptyAt: Double, val since: Double)

    private var occupant: Occupant? = null

    /** One log line per stuck transit, not per sample. */
    private var transitLogged: Boolean = false

    /** One warning per stint of "armed, but too loaded to ever start". */
    private var armedButLoadedLogged: Boolean = false

    private var lastPlacementSampleTs: Double? = null

    private var navSeq: Int = 0
    private var navTarget: String = ""
    private var navTs: Double = 0.0

    // --- Shot state -------------------------------------------------------

    private var ctx = ShotContext()
    private var targetYield: Double = config.defaults.targetYieldGrams
    private var currentOffset: Double = config.brewByWeight.offsetGrams
    private var bbwEnabled: Boolean = config.brewByWeight.enabled

    private val flowWindow = ArrayDeque<Pair<Double, Double>>()
    private var flowRate: Double = 0.0
    private var peakFlow: Double = 0.0

    /**
     * [flowRate] when the BBW stop fired, for the shot record. Taken at
     * dispatch, so its window ends one sample (~0.1 s) before the stop sample
     * - updateFlow runs after the state machine - which a 1 s window absorbs.
     */
    private var flowAtStop: Double? = null

    /** The yield captured at FINALIZE; null until a shot ends. See [ShotSnapshot.finalYield]. */
    private var finalYield: Double? = null
    private var grindHint: String = ""

    private var activeBeanId: Int? = null
    private var activeGrind: Double? = null

    private var runJob: Job? = null
    private var fetchJob: Job? = null

    /** The steam power-on queued after a stop; cancelled by RESET or a manual power-off. */
    private var powerOnJob: Job? = null

    /** Watches a live shot for a scale that has gone silent. */
    private var watchdogJob: Job? = null

    /** The last sample published a pre-drops pump timer; see [shotTimer]. */
    private var pumpTimerShown = false

    /** One "finish held by the machine" line per shot. */
    private var finishHoldLogged = false

    /** Wall time (s) of the last real sample, for the watchdog and gap logging. */
    private var lastSampleTs: Double? = null

    /** See [ShotSnapshot.alert]. */
    private var alert: String = ""

    /**
     * The last live sample was dropped as implausible (a cup lifted, a
     * knock). A shot that finishes on such samples saw no end of flow - the
     * readings froze - so with BBW on and no stop sent, the machine may
     * still be pouring.
     */
    private var lastSampleRejected = false

    /** A shot ended because its scale went away: saved, but no offset learning. */
    private var scaleLostShot: ShotContext? = null

    /** The latest brewing state from the machine's BLE shot counter; null until one arrives. */
    private var lastBrew: MicraShotCounter.BrewUpdate? = null

    /** The last stop or flush the machine reported, for the saved-shot line. */
    private var lastBrewEnd: MicraShotCounter.BrewUpdate? = null

    /** Wall-clock millis; the tests pass virtual time. */
    private fun nowMs(): Long = (clock() * 1000).toLong()

    /** The machine says it is brewing, and said so recently. */
    private fun machineBrewingNow(): Boolean {
        val b = lastBrew ?: return false
        return b.state == MicraShotCounter.BrewState.BREWING && nowMs() - b.receivedAtMs <= BREW_EVENT_FRESH_MS
    }

    /**
     * A brewing state from the machine's BLE shot counter (2.11.0). Gives the
     * shot its machine start without the cloud, and lets [step] tell a pause
     * in the cup from the end of the shot.
     */
    fun onMachineBrew(u: MicraShotCounter.BrewUpdate) {
        lastBrew = u
        when (u.state) {
            MicraShotCounter.BrewState.BREWING -> {
                val shot = ctx
                if (shotLive && shot.machineStartTime == null) takeBleMachineStart(shot, u)
            }
            MicraShotCounter.BrewState.BREWING_STOPPED, MicraShotCounter.BrewState.FLUSHED -> lastBrewEnd = u
            MicraShotCounter.BrewState.BACKFLUSHING -> Unit
        }
    }

    /** Stamp [shot] with the pump-on time the machine reported, if it has one. */
    private fun takeBleMachineStart(shot: ShotContext, u: MicraShotCounter.BrewUpdate) {
        val startMs = u.brewingStartMs ?: return
        val start = startMs / 1000.0
        shot.machineStartTime = start
        shot.pumpOnTime = start
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Machine shot start from the BLE shot counter: pump on %.1fs before the scale saw the first drops",
                (shot.startTime ?: start) - start,
            ),
        )
    }

    // --- Setters driven by the UI -----------------------------------------

    fun setTargetYield(grams: Double) {
        targetYield = grams
        publishSnapshot()
    }

    fun setBrewByWeight(enabled: Boolean) {
        bbwEnabled = enabled
    }

    fun setActiveBean(beanId: Int?, grind: Double?) {
        activeBeanId = beanId
        activeGrind = grind
    }

    fun setConfig(newConfig: AppConfig) {
        config = newConfig
        currentOffset = newConfig.brewByWeight.offsetGrams
        bbwEnabled = newConfig.brewByWeight.enabled
        val at = newConfig.autoTare
        placement.configure(at.stabilityBandGrams, at.stableSeconds)
        if (!at.enabled) stopAutoTare("auto-tare switched off")
    }

    /**
     * The AUTO switch. Off stops vessel detection on the next sample (no
     * tare, arm, vessel learning or PREP link from a placement); an arm
     * already made stays, and manual TARE and ARM work as before. On again
     * starts from a fresh baseline, since [stopAutoTare] reset the detector.
     */
    fun setAutoPaused(paused: Boolean) {
        if (paused == autoPaused) return
        autoPaused = paused
        Log.i(TAG, "Auto-tare ${if (paused) "paused" else "resumed"} from the Scale page (AUTO ${if (paused) "off" else "on"})")
        if (paused) stopAutoTare("AUTO switched off")
    }

    /** Live kill-switch: takes effect on the next sample, with no scale reconnect. */
    private fun stopAutoTare(why: String) {
        placement.reset()
        transitLogged = false
        pendingArm = null
        endOccupant(why)
        if (netOffset != null || autoTareHint.isNotEmpty()) {
            clearNet()
            autoTareHint = ""
            publishSnapshot()
        }
    }

    /**
     * Point the service at new hardware after a credentials/MAC change.
     *
     * A new scale means a new sample stream, so the run loop is cancelled and
     * restarted to re-subscribe; otherwise we would silently keep collecting
     * from the dead adapter forever.
     */
    suspend fun rebind(newScale: ScaleSource? = null, newMachine: MachineController? = null) {
        if (newScale != null) {
            runJob?.cancel()
            runJob = null
            scale = newScale
            // New adapter, new sample stream: the old baseline describes a
            // scale we are no longer listening to.
            placement.reset()
            clearNet()
            pendingArm = null
            endOccupant("new scale")
            start()
        }
        if (newMachine != null) machine = newMachine
    }

    /**
     * The Brew page timer (2.12.0): from pump-on when the BLE shot counter
     * gave it, else from the first drops on the scale, as before. Pump-on is
     * typically ~5 s before the first drops (pre-infusion), so armed and
     * waiting, the timer starts with the pump. The end is the scale's last
     * flow either way, so a finished shot reads the saved machine time.
     *
     * The fallback is the scale, not the cloud's brew start: that one is
     * polled after the first drops and would jump the running timer.
     */
    private fun shotTimer(now: Double): Pair<Double, TimerSource> {
        val shot = ctx
        val pumpOn = shot.pumpOnTime
        return when (shot.state) {
            ShotState.IDLE -> {
                val startMs = lastBrew?.brewingStartMs
                if (shot.armed && startMs != null && machineBrewingNow()) {
                    max(0.0, now - startMs / 1000.0) to TimerSource.PUMP
                } else {
                    0.0 to TimerSource.NONE
                }
            }
            ShotState.FINALIZED -> if (pumpOn != null) {
                max(0.0, (shot.lastActiveTime ?: now) - pumpOn) to TimerSource.PUMP
            } else {
                shot.elapsed(now) to TimerSource.SCALE
            }
            ShotState.BREWING, ShotState.STOPPING -> if (pumpOn != null) {
                max(0.0, now - pumpOn) to TimerSource.PUMP
            } else {
                shot.elapsed(now) to TimerSource.SCALE
            }
        }
    }

    fun snapshot(): ShotSnapshot {
        val now = clock()
        val (timer, timerSource) = shotTimer(now)
        return ShotSnapshot(
            state = ctx.state,
            elapsedSeconds = timer,
            timerSource = timerSource,
            weight = ctx.currentWeight,
            finalYield = finalYield,
            targetYield = targetYield,
            stopSent = ctx.stopSent,
            saved = ctx.saved,
            currentOffsetGrams = currentOffset,
            armed = ctx.armed,
            flowRateGps = flowRate,
            grindHint = grindHint,
            netOffset = netOffset,
            netLabel = netLabel,
            netVesselGrams = netVesselGrams,
            autoTareHint = autoTareHint,
            navSeq = navSeq,
            navTarget = navTarget,
            navTs = navTs,
            alert = alert,
        )
    }

    /** Whether a shot is live (BREWING or STOPPING) - TARE is refused then. */
    val shotLive: Boolean
        get() = ctx.state == ShotState.BREWING || ctx.state == ShotState.STOPPING

    /** [source] says who armed or disarmed: "button", a vessel name, "reset". */
    fun setArmed(armed: Boolean, source: String = "button", weight: Double = ctx.currentWeight) {
        // Arming only gates the IDLE -> BREWING transition; a no-op once a
        // shot is already in flight (the safety guard is spent).
        ctx.armed = armed
        // An explicit disarm outranks an auto-arm still waiting on the tare.
        if (!armed) pendingArm = null
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Shot %s (source=%s) at %.1fg",
                if (armed) "armed (waiting for shot)" else "disarmed",
                source,
                weight,
            ),
        )
        publishSnapshot()
    }

    /**
     * Drop the steam power-on queued after a stop, if any. Called on RESET
     * and on a power-off by hand: neither should be undone 7 s later.
     */
    fun cancelPendingPowerOn(why: String) {
        val job = powerOnJob ?: return
        if (!job.isActive) return
        job.cancel()
        powerOnJob = null
        Log.i(TAG, "Steam power-on after the stop skipped: $why")
    }

    // --- Lifecycle --------------------------------------------------------

    fun start() {
        runJob = scope.launch {
            // Capture the adapter we subscribed to so a rebind mid-flight
            // cannot leave us collecting from two streams.
            val subscribed = scale
            subscribed.samples.collect { sample ->
                try {
                    onTick(sample)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Shot service tick failed", e)
                }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        fetchJob?.cancel()
        fetchJob = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * RESET: drop the shot and tare the scale.
     *
     * Mid-shot it stops the machine first (the UI asks for confirmation):
     * dropping a live shot without a stop left the machine pouring.
     */
    suspend fun reset() {
        val shot = ctx
        val live = shotLive
        if (live) {
            Log.w(
                TAG,
                String.format(
                    Locale.US,
                    "RESET during %s at %.1fg after %.1fs (stop %s)",
                    shot.state, shot.currentWeight,
                    shot.elapsed(clock()),
                    if (shot.stopSent) "already sent" else "not sent - sending it now",
                ),
            )
        } else {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "RESET by user (state=%s, armed=%s, weight %.1fg)",
                    shot.state, shot.armed, shot.currentWeight,
                ),
            )
        }
        cancelPendingPowerOn("RESET")
        if (live && !shot.stopSent) {
            try {
                val routed = machine.stopBrew()
                Log.i(TAG, "Stop on RESET sent via=${routed.path}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stop on RESET failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        fetchJob?.cancel()
        fetchJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        alert = ""
        resetShotCtx()
        clearNet()
        autoTareHint = ""
        pendingArm = null
        // RESET starts over; its tare is swallowed by expectTare below, so
        // it would never show as a lift.
        endOccupant("RESET")
        // This path tares too, so it needs the same blind window as an
        // auto-tare - otherwise the drop to zero reads as a vessel removal.
        placement.expectTare(
            clock(),
            config.autoTare.tareCooldownSeconds,
        )
        try {
            scale.tare()
        } catch (e: Exception) {
            Log.d(TAG, "Tare during reset failed", e)
        }
        publishSnapshot()
    }

    // --- Internals --------------------------------------------------------

    /** Drop the finished/abandoned shot and its telemetry. No IO. */
    private fun resetShotCtx() {
        ctx = ShotContext()
        flowWindow.clear()
        flowRate = 0.0
        peakFlow = 0.0
        flowAtStop = null
        finalYield = null
    }

    private fun thresholds(): Thresholds {
        val sl = config.shotLogic
        return Thresholds(
            startThresholdGrams = sl.startThresholdGrams,
            maxStartWeightGrams = sl.maxStartWeightGrams,
            maxFlowRateGps = sl.maxFlowRateGps,
            flowDetectionThreshold = sl.flowDetectionThreshold,
            finishStabilitySeconds = sl.finishStabilitySeconds,
            ignoreStabilityFirstSeconds = sl.ignoreStabilityFirstSeconds,
            minSaveDurationSeconds = sl.minSaveDurationSeconds,
            minSaveYieldGrams = sl.minSaveYieldGrams,
            brewByWeightEnabled = bbwEnabled,
            brewOffsetGrams = currentOffset,
            cloudLeadGrams = cloudLead(),
        )
    }

    /** Grams to stop early because the stop would go through the cloud. */
    private fun cloudLead(): Double =
        if (machine.stopGoesToCloud) {
            min(MAX_CLOUD_LEAD_GRAMS, flowRate * config.brewByWeight.cloudStopDelaySeconds)
        } else {
            0.0
        }

    private fun onTick(raw: WeightSample) {
        // The adapter marks a dropped link and an idle sleep with one
        // synthetic sample (weight 0). It is not a reading: fed on, it armed a
        // pending auto-arm (0 g is under the start threshold), zeroed a
        // finished shot's weight and reached the placement detector.
        if (!raw.connected || raw.asleep) {
            if (shotLive) onShotScaleLost(if (raw.asleep) "went to sleep" else "disconnected")
            return
        }

        val sample = Sample(ts = raw.ts, weight = raw.weight.toDouble())
        logStreamGap(sample)

        // Before step(), so the sample that arms is also the sample step()
        // sees: it only arms on a reading at/below the start threshold, so the
        // arm can never be spent by a stale pre-tare weight.
        resolvePendingArm(sample)

        val before = ctx.state
        ctx.machineBrewing = machineBrewingNow()
        val actions = step(ctx, sample, thresholds(), targetYield)
        // step() sets currentWeight to the sample, except for one it drops
        // as implausible, which keeps the last good weight. Read before the
        // actions run: FINALIZE needs it.
        lastSampleRejected = (before == ShotState.BREWING || before == ShotState.STOPPING) &&
            ctx.currentWeight != sample.weight
        actions.forEach { dispatch(it) }

        if (before == ShotState.IDLE && ctx.state == ShotState.BREWING) {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Shot started at %.1fg | target %.1fg, BBW %s, stop at %.1fg (offset %.2fg), machine link %s",
                    sample.weight, targetYield,
                    if (bbwEnabled) "on" else "off",
                    targetYield - currentOffset, currentOffset,
                    machine.linkState,
                ),
            )
            // Fresh shot: drop the previous verdict and stale flow telemetry.
            grindHint = ""
            autoTareHint = ""
            alert = ""
            scaleLostShot = null
            finishHoldLogged = false
            lastBrewEnd = null
            flowWindow.clear()
            peakFlow = 0.0
            flowAtStop = null
            startWatchdog(ctx)
            // The pump comes on seconds before the first drops, so the
            // machine has usually said so already.
            val brew = lastBrew
            if (brew != null && machineBrewingNow()) takeBleMachineStart(ctx, brew)
        }

        if (ctx.finishHeldByMachine) {
            ctx.finishHeldByMachine = false
            if (!finishHoldLogged) {
                finishHoldLogged = true
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Shot not finished at %.1fg after %.1fs: the weight paused but the machine reports " +
                            "itself still brewing",
                        ctx.currentWeight, ctx.elapsed(clock()),
                    ),
                )
            }
        }

        // Armed with something heavy still on the scale: the start ceiling
        // will refuse to read anything as a shot until it is tared. Say so, or
        // the arm looks live from the UI while being a guaranteed no-op.
        if (ctx.state == ShotState.IDLE &&
            ctx.armed &&
            sample.weight > config.shotLogic.maxStartWeightGrams
        ) {
            if (!armedButLoadedLogged) {
                armedButLoadedLogged = true
                Log.w(
                    TAG,
                    String.format(
                        Locale.US,
                        "Armed, but the scale reads %.1fg (ceiling %.1fg): a shot cannot " +
                            "start until it is tared",
                        sample.weight, config.shotLogic.maxStartWeightGrams,
                    ),
                )
            }
        } else {
            armedButLoadedLogged = false
        }

        updateFlow(sample)

        // Publish on transition or during an active shot. Idle weight jitter
        // should not churn the UI. Armed with the pump on, the timer runs
        // before the first drops; one more publish when it stops resets it.
        val pumpTimer = ctx.state == ShotState.IDLE && ctx.armed && machineBrewingNow()
        if (before != ctx.state ||
            ctx.state == ShotState.BREWING ||
            ctx.state == ShotState.STOPPING ||
            pumpTimer || pumpTimerShown
        ) {
            publishSnapshot()
        }
        pumpTimerShown = pumpTimer

        // After step(), so shot detection is never delayed by placement work
        // and so the IDLE check below sees the state this sample produced.
        autoTareTick(sample)
    }

    /**
     * Log a gap in the weight stream whatever auto-tare is doing: the
     * auto-tare re-seed line only exists while auto-tare is on, and a gap
     * during a shot is the one that matters most.
     */
    private fun logStreamGap(sample: Sample) {
        val previous = lastSampleTs
        lastSampleTs = sample.ts
        if (previous == null) return
        val gap = sample.ts - previous
        if (gap <= STREAM_GAP_SECONDS) return
        val line = String.format(
            Locale.US,
            "Weight stream gap %.1fs during %s (now %.1fg)",
            gap, ctx.state, sample.weight,
        )
        if (shotLive) Log.w(TAG, line) else Log.i(TAG, line)
    }

    /** Poll a live shot for a scale that stopped sending without saying so. */
    private fun startWatchdog(shot: ShotContext) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (shot === ctx && shotLive) {
                delay(1_000)
                val last = lastSampleTs ?: continue
                val silent = clock() - last
                if (shot === ctx && shotLive && silent > SHOT_SILENCE_SECONDS) {
                    onShotScaleLost(String.format(Locale.US, "silent for %.0fs", silent))
                    return@launch
                }
            }
        }
    }

    /**
     * The scale went away during a live shot. The state machine only moves on
     * samples, so without this the shot sat in BREWING for good - frozen on
     * screen, unsaved, and holding the machine link at HIGH until RESET.
     *
     * Policy, not shot logic: [step] is untouched. Inside the opening grace
     * period the shot is dropped, as an implausible step would drop it;
     * later it is finished at the last good weight and saved, without
     * learning an offset from it. The stop cannot fire without a weight, so
     * the alert tells the person at the machine to stop it.
     */
    private fun onShotScaleLost(how: String) {
        val shot = ctx
        if (!shotLive) return
        val start = shot.startTime ?: return
        val last = lastSampleTs ?: start
        val elapsed = last - start
        Log.w(
            TAG,
            String.format(
                Locale.US,
                "Scale %s during %s at %.1fg after %.1fs; %s",
                how, shot.state, shot.currentWeight, elapsed,
                if (shot.stopSent) "the stop was already sent" else "the BBW stop cannot fire",
            ),
        )
        watchdogJob?.cancel()
        watchdogJob = null
        if (!shot.stopSent && bbwEnabled) alert = ALERT_SCALE_LOST
        if (!shot.stopSent && elapsed <= config.shotLogic.ignoreStabilityFirstSeconds) {
            fetchJob?.cancel()
            fetchJob = null
            resetShotCtx()
            Log.w(TAG, "Shot dropped: the scale went inside the opening grace period")
            publishSnapshot()
            return
        }
        scaleLostShot = shot
        if (shot.lastActiveTime == null) shot.lastActiveTime = last
        shot.state = ShotState.FINALIZED
        dispatch(Action.FINALIZE)
        publishSnapshot()
    }

    // --- Auto-tare --------------------------------------------------------

    private fun autoTareTick(sample: Sample) {
        val at = config.autoTare
        val previousTs = lastPlacementSampleTs
        lastPlacementSampleTs = sample.ts
        if (!at.enabled || autoPaused) return

        // A gap means we were not watching - the scale slept, or the link
        // dropped. Whatever happened meanwhile is invisible, so the baseline
        // is stale for the same reason it is during a shot, and the transit
        // timer would otherwise report the whole sleep as one continuous
        // "still moving" (an hour, in one log).
        if (previousTs != null && sample.ts - previousTs > STREAM_GAP_SECONDS) {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-tare: %.0fs gap in the weight stream (scale asleep or link " +
                        "dropped); re-seeding the baseline",
                    sample.ts - previousTs,
                ),
            )
            placement.reset()
            transitLogged = false
            // Unwatched: the vessel may have come off meanwhile.
            endOccupant("the weight stream had a gap")
        }

        // Never during a live shot: BREWING and - especially - STOPPING, which
        // ends in a dead-flat drip plateau that is exactly the shape of a
        // settled placement. FINALIZED IS allowed: the shot is over and the
        // row is written, and the state sticks there until RESET, so gating it
        // out left auto-tare dead for everything that follows a shot (lifting
        // the cup, putting the milk pitcher on).
        if (ctx.state != ShotState.IDLE && ctx.state != ShotState.FINALIZED) {
            placement.reset()
            transitLogged = false
            if (netOffset != null) {
                clearNet()
                publishSnapshot()
            }
            return
        }

        val event = placement.feed(sample.ts, sample.weight)
        if (event == null) {
            // Weight can move a lot and still never produce an event. Without
            // this line that case looks exactly like "nothing was ever put on
            // the scale" in the log, which is the hardest failure to diagnose.
            logTransit(sample)
            return
        }
        transitLogged = false
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Auto-tare: step %+.1fg -> settled at %.1fg (%s over %.2fs, floor %.0f g/s)",
                event.delta, event.settled, fmtRate(event.rateGps),
                event.riseSeconds, at.minRiseRateGps,
            ),
        )

        // Before the rate floor: a lift is a lift however slowly it is done.
        occupant?.let { o ->
            val emptyBound = o.emptyAt + at.toleranceGrams
            when {
                // Back to the empty-scale reading - after this step, or
                // already before it (a cup lifted straight after a shot,
                // while the detector was still re-seeding, leaves no lift
                // step: 11-10 10:14) - or one drop of at least the vessel's
                // own weight (a tare that never landed leaves the readings
                // wrong; a manual TARE's drop counts here too).
                event.settled <= emptyBound ||
                    event.settled - event.delta <= emptyBound ||
                    event.delta <= -(o.grams - at.toleranceGrams) ->
                    endOccupant(String.format(Locale.US, "lifted (%+.1fg, now %.1fg)", event.delta, event.settled))
                sample.ts - o.since > OCCUPIED_MAX_SECONDS ->
                    endOccupant("on the scale for over ${(OCCUPIED_MAX_SECONDS / 60).toInt()} min")
            }
        }

        // Rate floor. An unarmed shot never leaves IDLE, so weight can climb
        // 0 to 36 g with the detector live; this is what makes coffee flowing
        // at a few g/s structurally unable to be read as a vessel landing - a
        // cup lands two orders of magnitude faster than any brew rate.
        if (event.rateGps < at.minRiseRateGps) {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-tare: ignoring %+.1fg step - %s over %.2fs, floor is %.0f g/s%s",
                    event.delta, fmtRate(event.rateGps), event.riseSeconds, at.minRiseRateGps,
                    if (event.delta > 0) {
                        " | " + describeCandidates(event.delta, at.vessels, at.toleranceGrams)
                    } else {
                        ""
                    },
                ),
            )
            return
        }

        if (event.delta < 0) {
            if (netOffset != null) {
                Log.i(TAG, "Auto-tare: $netLabel lifted; clearing net readout")
                clearNet()
                publishSnapshot()
            }
            return
        }

        // TARE on an empty scale that still reads minus the last vessel: the
        // scale jumps onto zero in one sample, by exactly that vessel's
        // weight, so it always matched it (8 times 11-12..11-20, re-taring
        // and re-learning the Dosing cup). With the Coffee cup it would arm
        // a shot on an empty scale. Nothing landed: match nothing (2.13.2).
        if (looksLikeTare(event, at.toleranceGrams)) {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-tare: +%.1fg in one sample onto zero - a tare, not a vessel; no tare, no arm",
                    event.delta,
                ),
            )
            if (netOffset != null) {
                clearNet()
                publishSnapshot()
            }
            return
        }

        // A vessel is on the scale, so this rise went into it: no match, so
        // no tare, no arm, no early machine link.
        occupant?.let { o ->
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-tare: +%.1fg with the %s on the scale - its contents, not a vessel; " +
                        "no tare, no arm | %s",
                    event.delta, o.name,
                    describeCandidates(event.delta, at.vessels, at.toleranceGrams),
                ),
            )
            return
        }

        val m = matchVessel(event.delta, at.vessels, at.toleranceGrams)
        if (m == null) {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-tare: +%.1fg matches no configured vessel; ignoring | %s",
                    event.delta,
                    describeCandidates(event.delta, at.vessels, at.toleranceGrams),
                ),
            )
            return
        }

        // A vessel that weighs a dose (one with a loaded band) means a shot
        // is a few minutes off: the runtime brings the machine link up early.
        if (at.vessels.any { it.name == m.name && it.weighsDose() }) onDosingVessel?.invoke(m.name)

        if (m.kind == VesselMatch.Kind.CONTENTS) {
            // Deliberately no tare: taring here would zero the vessel AND its
            // contents, destroying the reading the user put it there to take.
            // The offset is absolute-minus-contents, so the displayed net stays
            // correct whether or not the vessel was tared empty beforehand, and
            // keeps tracking live as more beans are poured in.
            netOffset = event.settled - m.contentsGrams
            netLabel = m.name
            netVesselGrams = m.vesselGrams
            autoTareHint = "${m.name} + contents - showing net weight"
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-tare: %s placed with contents (%.1fg net, vessel %.1fg)",
                    m.name, m.contentsGrams, m.vesselGrams,
                ),
            )
            requestPage(m)
            disarmForOtherVessel(m)
            // Not tared: the empty scale reads what it did before it landed.
            startOccupant(m.name, m.vesselGrams, event.settled - event.delta, sample.ts)
            publishSnapshot()
            return
        }

        // Bare vessel: physically tare it, and arm if it is one we brew into.
        val willArm = at.autoArm && m.arm
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Auto-tare: +%.1fg matched %s (bare %.1fg) - taring, %s",
                event.delta, m.name, m.vesselGrams,
                when {
                    willArm -> "arming once the tare lands"
                    m.arm -> "no arm (autoArm is off)"
                    else -> "not an arming vessel"
                },
            ),
        )
        clearNet()
        requestPage(m)
        disarmForOtherVessel(m)
        placement.expectTare(sample.ts, at.tareCooldownSeconds)
        // Tared: from here the empty scale reads minus the vessel.
        startOccupant(m.name, event.delta, -event.delta, sample.ts)
        scope.launch { autoTare(m, willArm) }
        learnVessel(m.name, event.delta)
    }

    /**
     * TARE pressed in the app. It starts the "vessel on the scale" rule over:
     * a lift merged into the tare's own step leaves no lift to see (11-17
     * 14:20: cup lifted and TARE pressed together, one -18 g step), and the
     * rule then held off the dosing cup that came next.
     */
    fun onManualTare() {
        endOccupant("TARE pressed")
    }

    private fun startOccupant(name: String, grams: Double, emptyAt: Double, ts: Double) {
        occupant = Occupant(name, grams, emptyAt, ts)
        Log.i(TAG, "Auto-tare: $name on the scale - rises read as its contents until it is lifted")
    }

    /**
     * A step that is a tare, not a placement: in one sample and onto zero.
     * TARE on an empty scale reading -113.9 g makes exactly this +113.9 g
     * step, and it has always matched the Dosing cup (six times 11-10..11-15,
     * the pitcher on 11-19). Since 2.13.2 it matches nothing: no tare, no
     * arm, no vessel learning, no early link, and no [Occupant] to hold off
     * the real dosing cup that follows. A vessel set back down takes several
     * samples (0.3 s+ in every logged placement), so it still matches.
     */
    private fun looksLikeTare(event: PlacementEvent, toleranceGrams: Double): Boolean =
        event.riseSeconds <= 0.0 && abs(event.settled) <= toleranceGrams

    private fun endOccupant(why: String) {
        val o = occupant ?: return
        occupant = null
        Log.i(TAG, "Auto-tare: ${o.name} no longer on the scale ($why) - vessels match again")
    }

    /**
     * A vessel that is not a brew cup landed, so the cup is off the scale.
     *
     * An arm outlives the placement that created it - nothing auto-disarms on
     * a timer, deliberately - but another vessel landing is positive evidence
     * that the cup has gone, and a stale arm is what turned the milk pitcher
     * being lifted into a logged 5-second shot yielding -140.1 g.
     */
    private fun disarmForOtherVessel(m: VesselMatch) {
        if (m.arm || !ctx.armed) return
        Log.i(TAG, "Auto-tare: ${m.name} placed - disarming (the brew cup is off the scale)")
        setArmed(false, "${m.name} placed") // publishes
    }

    /**
     * Ask the UI to switch to this vessel screen.
     *
     * Only a request: the runtime has no view of what anyone is looking at.
     * Whoever is on screen decides whether the change is news to them.
     */
    private fun requestPage(m: VesselMatch) {
        if (m.switchToPage.isEmpty()) return
        navSeq += 1
        navTarget = m.switchToPage
        navTs = clock()
        Log.i(TAG, "Auto-tare: ${m.name} placed - requesting the ${m.switchToPage} screen")
    }

    /** Log once when the weight moves but refuses to settle into a plateau. */
    private fun logTransit(sample: Sample) {
        val at = config.autoTare
        val moving = placement.transitSeconds(sample.ts)
        if (moving <= 0.0) {
            transitLogged = false
            return
        }
        if (transitLogged || moving < at.stableSeconds + TRANSIT_LOG_SLACK_SECONDS) return
        transitLogged = true
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Auto-tare: weight has been moving for %.1fs without settling (now %.1fg); " +
                    "no placement event - needs %.2fs inside +/-%.2fg",
                moving, sample.weight, at.stableSeconds, at.stabilityBandGrams,
            ),
        )
    }

    private suspend fun autoTare(m: VesselMatch, arm: Boolean) {
        try {
            scale.tare()
        } catch (e: Exception) {
            Log.w(TAG, "Auto-tare of ${m.name} failed", e)
            return
        }
        var hint = "Auto-tared: ${m.name}"
        // Re-check the state: the tare is a suspend point, and a shot could in
        // principle have started while we were parked on the BLE round-trip.
        if (arm && (ctx.state == ShotState.IDLE || ctx.state == ShotState.FINALIZED)) {
            // Deferred, not immediate - see resolvePendingArm.
            pendingArm = m.name
            pendingArmDeadline = clock() +
                max(1.0, config.autoTare.tareCooldownSeconds)
            hint += " - arming..."
        }
        autoTareHint = hint
        Log.i(TAG, "Auto-tare: $hint")
        publishSnapshot()
    }

    /**
     * Arm only once the tare has actually shown up in the weight stream.
     *
     * tare() returns when the BLE write is accepted, not when the scale has
     * zeroed - the next samples still report the full vessel weight. Arming on
     * the write return therefore armed the state machine while the scale still
     * read ~190 g, and the very next sample tripped IDLE -> BREWING: a phantom
     * shot whose timer started the moment the cup landed, and which (being far
     * past target - offset) would fire a spurious BBW stop and machine
     * power-cycle on the sample after that.
     *
     * So the arm is held until a sample comes back at or below the start
     * threshold. If the tare never lands we deliberately fail closed - no arm,
     * and the hint says so - rather than arm against a loaded scale.
     */
    private fun resolvePendingArm(sample: Sample) {
        val name = pendingArm ?: return

        if (ctx.state != ShotState.IDLE && ctx.state != ShotState.FINALIZED) {
            pendingArm = null
            return
        }

        if (abs(sample.weight) <= config.shotLogic.startThresholdGrams) {
            pendingArm = null
            // A finished shot is still sitting in the context; arming without
            // clearing it would be a no-op (step() short-circuits FINALIZED).
            if (ctx.state == ShotState.FINALIZED) resetShotCtx()
            // The sample's weight: ctx has not seen this sample yet, so its
            // weight is the pre-tare one (logged "armed at 75.6g", 10-23).
            setArmed(true, "auto-tare $name", sample.weight) // publishes
            autoTareHint = "Auto-tared: $name - armed"
            Log.i(TAG, "Auto-tare: $autoTareHint")
            publishSnapshot()
            return
        }

        if (sample.ts >= pendingArmDeadline) {
            pendingArm = null
            autoTareHint = "Auto-tared: $name - not armed (scale did not zero)"
            Log.w(
                TAG,
                String.format(
                    Locale.US,
                    "Auto-arm for %s abandoned: scale still reads %.1fg after the tare " +
                        "cooldown; arm by hand",
                    name, sample.weight,
                ),
            )
            publishSnapshot()
        }
    }

    /**
     * Nudge a vessel stored bare weight toward what we just measured.
     *
     * The match already bounds the difference by the tolerance, so a single
     * placement can only move the value a little; a wet or seasoned vessel
     * converges over a few uses instead of quietly skewing every net reading.
     */
    private fun learnVessel(name: String, observed: Double) {
        val at = config.autoTare
        val callback = onVesselLearned ?: return
        if (at.learningAlpha <= 0) return

        val v = at.vessels.firstOrNull { it.name == name } ?: return
        val newGrams = round2(at.learningAlpha * observed + (1 - at.learningAlpha) * v.grams)
        if (abs(newGrams - v.grams) < 0.01) return

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Auto-tare: vessel %s tare weight %.2f->%.2fg (measured %.2fg)",
                name, v.grams, newGrams, observed,
            ),
        )
        try {
            callback(name, newGrams)
        } catch (e: Exception) {
            Log.w(TAG, "onVesselLearned callback raised", e)
        }
    }

    private fun clearNet() {
        netOffset = null
        netLabel = ""
        netVesselGrams = null
    }

    /**
     * Trailing-window flow-rate estimate (g/s) for the UI.
     *
     * Only meaningful while a shot is live; reset to 0 otherwise so the screen
     * does not show a phantom rate at rest. Feeds control in one place only:
     * the cloud lead, which brings a stop forward when the link is down.
     */
    private fun updateFlow(sample: Sample) {
        if (ctx.state != ShotState.BREWING && ctx.state != ShotState.STOPPING) {
            if (flowWindow.isNotEmpty()) flowWindow.clear()
            flowRate = 0.0
            return
        }

        flowWindow.addLast(sample.ts to sample.weight)
        val cutoff = sample.ts - FLOW_WINDOW_SECONDS
        while (flowWindow.size > 2 && flowWindow.first().first < cutoff) {
            flowWindow.removeFirst()
        }

        val span = flowWindow.last().first - flowWindow.first().first
        if (span >= FLOW_MIN_SPAN_SECONDS) {
            // Negative rate (drip-back / scale settle) clamps to 0 - espresso
            // only flows one way; a brief dip should not read as -0.3 g/s.
            flowRate = max(
                0.0,
                (flowWindow.last().second - flowWindow.first().second) / span,
            )
            if (flowRate > peakFlow) peakFlow = flowRate
        }
    }

    private fun dispatch(action: Action) {
        when (action) {
            Action.ABANDON -> {
                // A step no brew rate can produce - a vessel landing on an
                // armed scale, or a knock. Nothing was sent to the machine;
                // the shot is dropped and the arm is kept.
                Log.w(
                    TAG,
                    String.format(
                        Locale.US,
                        "Shot abandoned: weight jumped to %.1fg - that fast a step is a " +
                            "placement, not espresso. No stop was sent%s",
                        ctx.currentWeight,
                        if (ctx.armed) "; still armed" else "",
                    ),
                )
                val armed = ctx.armed
                val weight = ctx.currentWeight
                fetchJob?.cancel()
                fetchJob = null
                watchdogJob?.cancel()
                watchdogJob = null
                resetShotCtx()
                ctx.armed = armed
                ctx.currentWeight = weight
                autoTareHint = "Ignored a sudden weight jump - not a shot"
                publishSnapshot()
            }

            Action.FETCH_MACHINE_START -> {
                // Cancel any previous poller first. Overwriting the handle
                // orphaned it, and an orphan still holds the cloud lock -
                // which is what the cancel on SEND_STOP exists to avoid.
                fetchJob?.cancel()
                val target = ctx
                fetchJob = scope.launch { fetchMachineStart(target) }
            }

            Action.SEND_STOP -> {
                // The stop command is time-critical - every extra second of
                // flow is overshoot. A machine-start poll is a cloud request,
                // and if the stop falls back to the cloud it should not share
                // the network with one. Cancel it first. By the time we hit the
                // stop threshold (15-30s in) the cloud has long since reported
                // Brewing, so the start time is almost always already captured;
                // in the rare fast-shot case where it is not, a timely stop
                // beats a cosmetic machine time.
                fetchJob?.cancel()
                fetchJob = null
                flowAtStop = flowRate
                val shot = ctx
                scope.launch { sendStopSequence(shot) }
            }

            Action.FINALIZE -> {
                // Capture the yield NOW, at the moment the shot ended, rather
                // than letting the finalize task read a live field: once the
                // state is FINALIZED the context tracks the scale again, so a
                // cup lifted in the meantime would be what gets written down.
                // The context too: RESET or an auto-arm can replace it while
                // the save is suspended, and the save must finish this shot.
                val shot = ctx
                val finalWeight = shot.currentWeight
                finalYield = finalWeight
                watchdogJob?.cancel()
                watchdogJob = null
                // Finished on readings means the scale saw the flow stop, so
                // the machine is stopped - by hand, if the stop failed - and
                // "stop the machine by hand" is done. A scale-lost shot saw
                // nothing of the kind; its alert stays until RESET.
                if (alert.isNotEmpty() && shot !== scaleLostShot) {
                    Log.i(TAG, "Alert cleared: the scale saw the flow stop (\"$alert\")")
                    alert = ""
                }
                // Finished on dropped samples, not on the flow stopping: the
                // cup came off before the stop, so nothing stopped the
                // machine. Evidence only - no stop is sent from this (the
                // stop fires from scale readings alone); the person is told.
                // A BrewingStopped from the shot counter since the start
                // ([lastBrewEnd], cleared at shot start) says it did stop.
                if (bbwEnabled && !shot.stopSent && lastSampleRejected && shot !== scaleLostShot &&
                    lastBrewEnd == null
                ) {
                    alert = ALERT_ENDED_NO_STOP
                    Log.w(
                        TAG,
                        String.format(
                            Locale.US,
                            "Shot finished on dropped samples at %.1fg with no stop sent (cup lifted?): alert raised",
                            finalWeight,
                        ),
                    )
                }
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Shot finished: %.1fs, %.1fg%s",
                        max(0.0, (shot.lastActiveTime ?: 0.0) - (shot.startTime ?: 0.0)),
                        finalWeight,
                        shot.weightAtStop?.let {
                            String.format(Locale.US, " (stop at %.1fg, drip %.1fg)", it, max(0.0, finalWeight - it))
                        } ?: " (no stop sent)",
                    ),
                )
                scope.launch { finalize(shot, finalWeight) }
            }
        }
    }

    private fun publishSnapshot() {
        try {
            onSnapshot(snapshot())
        } catch (e: Exception) {
            Log.e(TAG, "onSnapshot callback raised", e)
        }
    }

    /**
     * Poll the cloud for the machine own brew start time.
     *
     * The event-driven scale detects first flow a beat before the cloud flips
     * to Brewing, so a single fetch at shot start usually misses it. Guards:
     * exit the instant we capture it; a hard cap on attempts so a never-
     * Brewing machine cannot loop; and stop once the shot leaves BREWING, so a
     * poll can never contend with the BBW stop for the cloud lock.
     */
    /**
     * Poll the machine for the pump-on time of [target].
     *
     * Takes the context rather than reading the ctx field. ABANDON and RESET
     * both install a fresh ShotContext without waiting for this poll, so a
     * late reply used to land on whatever context existed when it returned -
     * and since IDLE -> BREWING reuses that context, the next real shot
     * inherited a machine start time from an abandoned one and was saved with
     * a nonsense duration_machine and a grind verdict judged on it.
     */
    private suspend fun fetchMachineStart(target: ShotContext) {
        val client = machine
        val maxAttempts = 6
        val pollIntervalMs = 1_500L

        for (attempt in 1..maxAttempts) {
            if (target.machineStartTime != null) {
                if (attempt == 1) Log.d(TAG, "Machine shot start already from BLE; no cloud fetch")
                return
            }
            if (target !== ctx || target.state != ShotState.BREWING) {
                Log.i(
                    TAG,
                    "Machine shot start NOT captured before the shot left BREWING " +
                        "(state=${ctx.state} after ${attempt - 1} attempt(s)) - saving with " +
                        "N/A machine time",
                )
                return
            }
            val ts = try {
                client.getBrewStartTs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch machine shot start", e)
                null
            }
            if (ts != null) {
                // Re-checked: the await above is another chance to be replaced,
                // or for the BLE shot counter to have answered first.
                if (target !== ctx || target.machineStartTime != null) return
                target.machineStartTime = ts
                Log.i(TAG, "Machine shot start captured (ts=$ts) after $attempt attempt(s)")
                return
            }
            if (attempt < maxAttempts) delay(pollIntervalMs)
        }
        Log.w(
            TAG,
            "Machine shot start NOT captured after $maxAttempts attempts - this shot will " +
                "save with N/A machine time",
        )
    }

    /**
     * Stop the machine, then power it back on for steaming.
     *
     * The first attempt is the normal path, unchanged. If it throws (the BLE
     * leg failed or was down and the cloud call then failed too) it is retried
     * [STOP_ATTEMPTS] times - a lost stop means the machine pours on - and if
     * every attempt fails the shot raises an alert instead of a lone ERROR
     * line nobody reads at the machine.
     */
    private suspend fun sendStopSequence(shot: ShotContext) {
        // Pin the controller for the whole stop/sleep/power-on sequence so a
        // rebind cannot substitute a different one between suspends.
        val client = machine
        // Instrumentation: stopBrew() suspends on the round-trip, and scale
        // ticks keep updating currentWeight on this same dispatcher while we
        // are parked on it. Snapshotting weight either side splits the drip
        // into the transport-attributable leg and the irreducible
        // post-power-cut leg. Round-trip is a LOWER BOUND on overshoot - the
        // return means the command was accepted, not that the machine actually
        // cut. via= is what device check 2 compares.
        val t0 = System.currentTimeMillis()
        val weightBefore = shot.currentWeight
        var routed: com.coffeeapp.droid.core.machine.Routed? = null
        var lastError: Exception? = null
        for (attempt in 1..STOP_ATTEMPTS) {
            try {
                routed = client.stopBrew()
                shot.stopViaBle = routed.path == ControlPath.BLE
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(
                    TAG,
                    "BBW stop attempt $attempt/$STOP_ATTEMPTS failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                if (attempt < STOP_ATTEMPTS) delay(STOP_RETRY_DELAY_MS)
            }
        }
        val latency = (System.currentTimeMillis() - t0) / 1000.0
        if (routed == null) {
            shot.stopFailed = true
            Log.e(
                TAG,
                String.format(
                    Locale.US,
                    "BBW stop FAILED at %.1fg after %d attempts in %.2fs (last: %s) - the machine " +
                        "may still be pouring",
                    shot.weightAtStop ?: 0.0, STOP_ATTEMPTS, latency,
                    lastError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown",
                ),
            )
            // A shot that finished while the retries ran was stopped by hand
            // already: the scale saw the flow stop.
            if (shot.state == ShotState.FINALIZED && shot !== scaleLostShot) {
                Log.i(TAG, "No stop alert: the shot already finished, so the machine was stopped by hand")
            } else {
                alert = ALERT_STOP_FAILED
            }
            publishSnapshot()
            // No steam power-on: the machine was never put in standby.
            return
        }
        val weightAfter = shot.currentWeight
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "BBW stop sent at %.1fg (flow %.2f g/s, target %.1f, offset %.2f, cloud lead %.2f) via=%s | " +
                    "round-trip %.2fs | +%.1fg flowed during call%s",
                shot.weightAtStop ?: 0.0,
                flowAtStop ?: 0.0,
                targetYield,
                shot.offsetUsedGrams ?: currentOffset,
                shot.cloudLeadUsedGrams,
                if (routed.path == ControlPath.BLE) "BLE" else "cloud",
                latency,
                max(0.0, weightAfter - weightBefore),
                routed.fallbackReason?.let { " | fallback: $it" } ?: "",
            ),
        )

        if (client.shotCounterActive) {
            val stopAtMs = nowMs()
            scope.launch { confirmStop(shot, client, stopAtMs) }
        }

        // Its own job, so RESET or a power-off by hand can cancel it.
        powerOnJob = scope.launch {
            val delaySeconds = config.brewByWeight.powerOnDelaySeconds
            delay((delaySeconds * 1000).toLong())
            try {
                val tOn = System.currentTimeMillis()
                val on = client.setPower(true)
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Machine powered back on for steaming after %.1fs via=%s in %.2fs%s",
                        delaySeconds, on.path, (System.currentTimeMillis() - tOn) / 1000.0,
                        on.fallbackReason?.let { " (fallback: $it)" } ?: "",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Steam power-on after the stop failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Did the stop land? With the BLE shot counter up the machine says so: a
     * BrewingStopped soon after, or no more brewing ticks. Ticks still coming
     * [STOP_CONFIRM_MS] after the stop mean it is still pouring - the stop is
     * sent once more, and if that does not take either, the alert goes up.
     * Without fresh events nothing is concluded.
     */
    private suspend fun confirmStop(
        shot: ShotContext,
        client: com.coffeeapp.droid.core.machine.MachineController,
        stopAtMs: Long,
    ) {
        for (round in 1..2) {
            val sentAt = nowMs()
            delay(STOP_CONFIRM_MS)
            // RESET dropped the shot (and stopped the machine itself if it was live).
            if (shot !== ctx) return
            val end = lastBrewEnd
            if (end != null && end.receivedAtMs >= stopAtMs - 1_000) {
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Stop confirmed by the machine %d ms after it was sent: pump %ss, %s/%s",
                        end.receivedAtMs - stopAtMs,
                        end.finalShotSeconds?.let { String.format(Locale.US, "%.2f", it) } ?: "?",
                        end.stopType ?: "?", end.stopReason ?: "?",
                    ),
                )
                return
            }
            val b = lastBrew
            val stillBrewing = b != null && b.state == MicraShotCounter.BrewState.BREWING &&
                b.receivedAtMs > sentAt + 1_000 && nowMs() - b.receivedAtMs <= BREW_EVENT_FRESH_MS
            if (!stillBrewing) {
                Log.i(TAG, "Stop not confirmed by an event, but the machine stopped reporting brewing")
                return
            }
            if (round == 1) {
                Log.w(
                    TAG,
                    String.format(
                        Locale.US,
                        "Machine still brewing %.1fs after the stop (timer %ss); sending the stop again",
                        (nowMs() - stopAtMs) / 1000.0, b?.timerSeconds ?: "?",
                    ),
                )
                try {
                    val routed = client.stopBrew()
                    Log.i(TAG, "Second stop sent via=${routed.path}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Second stop failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            } else {
                Log.e(TAG, "Machine STILL brewing after two stops - raising the stop alert")
                shot.stopFailed = true
                alert = ALERT_STOP_FAILED
                publishSnapshot()
            }
        }
    }

    private suspend fun finalize(shot: ShotContext, finalWeight: Double) {
        val startTime = shot.startTime ?: return
        val lastActive = shot.lastActiveTime ?: return
        val duration = max(0.0, lastActive - startTime)
        val minDuration = config.shotLogic.minSaveDurationSeconds
        val minYield = config.shotLogic.minSaveYieldGrams

        val machineDuration = shot.machineStartTime?.let { round2(max(0.0, lastActive - it)) }

        if (duration > minDuration && finalWeight >= minYield) {
            // Grind verdict, computed up front so the token persists with the
            // row. Judged on machine time (true pump-on) when captured, else
            // scale time. UI readout + journal note; never control.
            val bbw = config.brewByWeight
            // Judged on the same whole second the note prints, and floored
            // rather than rounded: the window's bounds are inclusive, so a
            // 26-30s target means anything up to the 31st second is on target.
            // A 30.56s shot is a 30s shot, not a slow one.
            //
            // Comparing the raw value while printing a %.0f one is what made a
            // 30.04s shot read "Slow (30s) - grind coarser" - a verdict
            // contradicting the evidence beside it. Flooring both makes them
            // agree by construction.
            val judgeTime = floor(machineDuration ?: duration)
            val (grindRec, hint) = when {
                judgeTime < bbw.grindTargetFastSeconds ->
                    "finer" to String.format(Locale.US, "Fast (%.0fs) - grind finer", judgeTime)
                judgeTime > bbw.grindTargetSlowSeconds ->
                    "coarser" to String.format(Locale.US, "Slow (%.0fs) - grind coarser", judgeTime)
                else ->
                    "good" to String.format(Locale.US, "Dialed in (%.0fs)", judgeTime)
            }

            try {
                val rowId = repo.saveShot(
                    startTime = startTime,
                    duration = duration,
                    yieldGrams = finalWeight,
                    target = targetYield,
                    machineDuration = machineDuration,
                    offsetGrams = shot.offsetUsedGrams,
                    beanId = activeBeanId,
                    grindSetting = activeGrind,
                    weightAtStop = shot.weightAtStop,
                    peakFlowGps = if (peakFlow > 0) round2(peakFlow) else null,
                    grindRec = grindRec,
                    flowAtStopGps = if (shot.stopSent) flowAtStop else null,
                )
                shot.saved = true
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Shot saved #%d: %.1fs scale / %s machine, %.1fg (target %.1f, 1:%.2f), " +
                            "bean %s grind %s, peak %s g/s, flow@stop %s g/s%s",
                        rowId,
                        duration,
                        machineDuration?.let { String.format(Locale.US, "%.1fs", it) } ?: "N/A",
                        finalWeight,
                        targetYield,
                        if (targetYield > 0) finalWeight / targetYield else 0.0,
                        activeBeanId?.let { "#$it" } ?: "none",
                        activeGrind?.toString() ?: "-",
                        if (peakFlow > 0) String.format(Locale.US, "%.2f", peakFlow) else "-",
                        if (shot.stopSent) flowAtStop?.let { String.format(Locale.US, "%.2f", it) } ?: "-" else "-",
                        if (shot === scaleLostShot) " | scale lost before the end" else "",
                    ) + (lastBrewEnd?.let { e ->
                        String.format(
                            Locale.US,
                            " | machine pump %ss (%s)",
                            (e.finalShotSeconds ?: e.flushSeconds)?.let { String.format(Locale.US, "%.2f", it) } ?: "?",
                            e.stopReason ?: e.state.name.lowercase(),
                        )
                    } ?: ""),
                )
                // After the row lands, not before: a backup of a shot that
                // failed to save would be a copy of the same missing data.
                onShotSaved?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shot.saved = false
                Log.e(TAG, "Failed to save shot", e)
            }

            if (shot.saved) {
                grindHint = hint
                Log.i(TAG, "Grind verdict: $grindHint")
            }

            if (shot === scaleLostShot) {
                Log.i(TAG, "BBW: offset not learned - the scale went before the shot ended")
            } else {
                learnOffset(shot, finalWeight)
            }
        } else {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Shot discarded: %.1fs / %.1fg (needs > %.1fs and >= %.1fg) - not saved",
                    duration, finalWeight, minDuration, minYield,
                ),
            )
        }
        publishSnapshot()
    }

    /**
     * Learn from the observed drip after the stop.
     *
     * Two guards stop a single weird shot corrupting a converged offset:
     * a hard cap for gross errors such as a failed stop command, and an
     * outlier band for a drip too far from the current offset (a one-off slow
     * cloud stop, channeling). The band is skipped when there is no baseline
     * yet, so learning can still bootstrap from zero.
     */
    private fun learnOffset(shot: ShotContext, finalWeight: Double) {
        val weightAtStop = shot.weightAtStop
        if (!shot.stopSent || weightAtStop == null) return

        val drip = max(0.0, finalWeight - weightAtStop)
        val bbw = config.brewByWeight

        // A cloud stop pours on until the cloud reaches the machine, so its
        // "drip" is mostly brewing, not drip. The offset is the BLE drip;
        // learning from this would drag it up for every BLE shot after.
        // Logged as the delay it implies, which is what cloudStopDelaySeconds
        // is tuned from.
        if (shot.stopFailed) {
            Log.i(TAG, String.format(Locale.US, "BBW: the stop failed - offset not learned (drip %.1fg)", drip))
            return
        }
        if (!shot.stopViaBle) {
            val flow = flowAtStop
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BBW: stop went through the cloud - offset not learned | stop %.1fg drip %.1fg, " +
                        "offset %.2f, lead %.2fg, implied cloud delay %s (configured %.2fs)",
                    weightAtStop, drip, currentOffset, shot.cloudLeadUsedGrams,
                    if (flow != null && flow > 0) String.format(Locale.US, "%.2fs", (drip - currentOffset) / flow) else "-",
                    bbw.cloudStopDelaySeconds,
                ),
            )
            return
        }
        val devRatio = bbw.learningMaxDeviationRatio

        when {
            drip > bbw.maxPlausibleDripGrams -> Log.w(
                TAG,
                String.format(
                    Locale.US,
                    "BBW: drip of %.1fg over hard cap %.1fg; offset unchanged",
                    drip, bbw.maxPlausibleDripGrams,
                ),
            )

            devRatio > 0 && currentOffset > 0 &&
                abs(drip - currentOffset) > devRatio * currentOffset -> Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BBW: unusual shot - drip %.1fg deviates >%.0f%% from offset %.2fg; " +
                        "offset unchanged",
                    drip, devRatio * 100, currentOffset,
                ),
            )

            else -> {
                val alpha = bbw.learningAlpha
                val newOffset = round2(alpha * drip + (1 - alpha) * currentOffset)
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "BBW: stop %.1fg drip %.1fg offset %.2f->%.2f",
                        weightAtStop, drip, currentOffset, newOffset,
                    ),
                )
                currentOffset = newOffset
                try {
                    onOffsetLearned(newOffset)
                } catch (e: Exception) {
                    Log.w(TAG, "onOffsetLearned callback raised", e)
                }
            }
        }
    }

    /** Rate for the log; a single-sample step has no duration to divide by. */
    private fun fmtRate(rate: Double): String =
        if (rate.isInfinite()) "instant" else String.format(Locale.US, "%.0f g/s", rate)

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
