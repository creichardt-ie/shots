// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.shot

import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.ble.MicraShotCounter
import com.coffeeapp.droid.ble.MicraProtocol.CommandStatus
import com.coffeeapp.droid.ble.WeightSample
import com.coffeeapp.droid.core.DefaultConfig
import com.coffeeapp.droid.core.config.AppConfig
import com.coffeeapp.droid.core.machine.BleMachine
import com.coffeeapp.droid.core.machine.CloudMachine
import com.coffeeapp.droid.core.machine.MachineController
import com.coffeeapp.droid.core.model.MachineSnapshot
import com.coffeeapp.droid.core.model.ShotSnapshot
import com.coffeeapp.droid.core.model.ShotState
import com.coffeeapp.droid.core.model.TimerSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestrator around the pure shot logic: the policies that live here
 * rather than in [step] - the stop retries, a scale lost mid-shot, RESET
 * mid-shot, the cancellable steam power-on, deferred auto-arming, and offset
 * learning - driven by a weight trace under virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runTest's virtual clock
class ShotServiceTest {

    private class FakeScale : ScaleSource {
        val flow = MutableSharedFlow<WeightSample>(extraBufferCapacity = 1024)
        var tares = 0
        override val samples: SharedFlow<WeightSample> = flow
        override suspend fun tare() { tares++ }
    }

    private class FakeCloud : CloudMachine {
        val calls = mutableListOf<String>()
        var failPower = false
        override suspend fun refresh() = MachineSnapshot(lastSyncOk = true)
        override suspend fun getBrewStartTs(): Double? = null
        override suspend fun setPower(on: Boolean) {
            calls += "power $on"
            if (failPower) throw IllegalStateException("offline")
        }
        override suspend fun setCoffeeTemp(temperature: Double) {}
        override suspend fun setSteamLevel(level: String) {}
        override suspend fun setSteamEnabled(enabled: Boolean) {}
    }

    /** A link that is up and acknowledges every command at once. */
    private class FakeBle : BleMachine {
        val calls = mutableListOf<String>()
        var counter = false
        override val shotCounterActive: Boolean get() = counter
        override val isConnected = true
        override val readings: StateFlow<MicraBleClient.Readings?> = MutableStateFlow(null)
        private val ok = CommandStatus("x", "ok", "success")
        override suspend fun setPower(on: Boolean): CommandStatus { calls += "power $on"; return ok }
        override suspend fun standbyFast(): Boolean { calls += "standby fast"; return true }
        override suspend fun setCoffeeTarget(celsius: Double) = ok
        override suspend fun setSteamTarget(celsius: Int) = ok
        override suspend fun setSteamEnabled(enabled: Boolean) = ok
    }

    private class FakeSink : ShotSink {
        val saved = mutableListOf<Double>()
        val durations = mutableListOf<Double>()
        val machineDurations = mutableListOf<Double?>()
        override suspend fun saveShot(
            startTime: Double, duration: Double, yieldGrams: Double, target: Double,
            machineDuration: Double?, offsetGrams: Double?, beanId: Int?, grindSetting: Double?,
            weightAtStop: Double?, peakFlowGps: Double?, grindRec: String?, flowAtStopGps: Double?,
        ): Long {
            saved += yieldGrams
            durations += duration
            machineDurations += machineDuration
            return saved.size.toLong()
        }
    }

    private class Rig(test: TestScope, config: AppConfig = AppConfig(), linkUp: Boolean = false) {
        val scale = FakeScale()
        val cloud = FakeCloud()
        val ble = if (linkUp) FakeBle() else null
        val sink = FakeSink()
        val learned = mutableListOf<Double>()
        val dosing = mutableListOf<String>()
        var snap = ShotSnapshot()
        private val scheduler = test.testScheduler
        val clock = { BASE + scheduler.currentTime / 1000.0 }
        val service = ShotService(
            scale = scale,
            machine = MachineController(cloud, ble, log = {}),
            repo = sink,
            config = config,
            scope = test.backgroundScope,
            onSnapshot = { snap = it },
            onOffsetLearned = { learned += it },
            onDosingVessel = { dosing += it },
            clock = clock,
        ).also { it.start() }
        var weight = 0.0

        /** Advance [dt] seconds, then deliver one sample at [w]. */
        fun feed(w: Double, dt: Double = 0.1, connected: Boolean = true, asleep: Boolean = false) {
            scheduler.advanceTimeBy((dt * 1000).toLong())
            weight = w
            scale.flow.tryEmit(WeightSample(clock(), w.toFloat(), null, connected, asleep))
            scheduler.runCurrent()
        }

        /** Rise from the current weight to [to] at 2 g/s, one sample every 0.1 s. */
        fun pourTo(to: Double) {
            while (weight < to) feed(minOf(to, weight + 0.2))
        }

        /** Hold the current weight for [seconds]. */
        fun hold(seconds: Double) {
            repeat((seconds * 10).toInt()) { feed(weight) }
        }

        fun wait(seconds: Double) {
            scheduler.advanceTimeBy((seconds * 1000).toLong())
            scheduler.runCurrent()
        }

        /** A shot-counter state from the machine, received now. */
        fun brew(state: MicraShotCounter.BrewState, timer: Double? = null, startAgoS: Double? = null, reason: String? = null) {
            val now = (clock() * 1000).toLong()
            service.onMachineBrew(
                MicraShotCounter.BrewUpdate(
                    state = state,
                    timerSeconds = timer,
                    finalShotSeconds = if (state == MicraShotCounter.BrewState.BREWING_STOPPED) timer else null,
                    flushSeconds = null,
                    doseIndex = "ContinuousDose",
                    stopType = if (reason != null) "Continuous" else null,
                    stopReason = reason,
                    receivedAtMs = now,
                    brewingStartMs = startAgoS?.let { now - (it * 1000).toLong() },
                ),
            )
        }

        fun armAndStart() {
            feed(0.0)
            service.setArmed(true)
            feed(0.4)
            assertEquals(ShotState.BREWING, snap.state)
        }
    }

    // --- The stop ----------------------------------------------------------

    @Test
    fun `a stop that keeps failing is retried, then raises the alert, and skips the power-on`() = runTest {
        val rig = Rig(this)
        rig.cloud.failPower = true
        rig.armAndStart()
        rig.pourTo(32.2) // target 36 - offset 4
        assertEquals(ShotState.STOPPING, rig.snap.state)
        rig.wait(2.0)
        assertEquals(listOf("power false", "power false", "power false"), rig.cloud.calls)
        assertEquals("Stop failed - stop the machine by hand", rig.snap.alert)
        rig.wait(10.0)
        assertFalse("no steam power-on after a failed stop", "power true" in rig.cloud.calls)
    }

    @Test
    fun `the stop-failed alert clears once the scale sees the flow stop`() = runTest {
        val rig = Rig(this)
        rig.cloud.failPower = true
        rig.armAndStart()
        rig.pourTo(32.2)
        rig.wait(2.0) // three failed attempts
        assertEquals("Stop failed - stop the machine by hand", rig.snap.alert)
        rig.pourTo(38.0) // still pouring until stopped by hand
        rig.hold(2.0) // the flow stops: FINALIZE
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals("", rig.snap.alert)
        assertTrue(rig.learned.isEmpty())
    }

    @Test
    fun `a good stop powers the machine back on after the delay`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(32.2)
        rig.wait(0.5)
        assertEquals(listOf("power false"), rig.cloud.calls)
        rig.wait(7.0)
        assertEquals(listOf("power false", "power true"), rig.cloud.calls)
        assertEquals("", rig.snap.alert)
    }

    @Test
    fun `RESET cancels the steam power-on queued after a stop`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(32.2)
        rig.wait(1.0)
        rig.service.reset()
        rig.wait(10.0)
        assertFalse("power true" in rig.cloud.calls)
    }

    @Test
    fun `RESET mid-shot stops the machine before dropping the shot`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(10.0)
        rig.service.reset()
        rig.wait(0.5)
        assertEquals(listOf("power false"), rig.cloud.calls)
        assertEquals(ShotState.IDLE, rig.snap.state)
        assertTrue(rig.sink.saved.isEmpty())
    }

    // --- A scale lost mid-shot ----------------------------------------------

    @Test
    fun `a scale dropping mid-shot finishes and saves the shot without learning from it`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(20.0) // ~10 s in, past the 7 s grace period
        rig.feed(0.0, connected = false)
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals(1, rig.sink.saved.size)
        assertEquals(20.0, rig.sink.saved.single(), 0.01)
        assertEquals("Scale lost mid-shot - stop the machine by hand", rig.snap.alert)
        assertTrue(rig.learned.isEmpty())
    }

    @Test
    fun `a cup lifted before the stop finishes the shot with an alert, and sends nothing`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(20.0) // ~10 s in, short of the 32 g stop
        rig.feed(-180.0) // cup off: every sample from here is dropped
        rig.hold(3.0)
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals("Shot ended without a stop - check the machine", rig.snap.alert)
        assertTrue("no stop from dropped samples", rig.cloud.calls.isEmpty())
    }

    @Test
    fun `a cup lifted after the machine reported its stop raises no alert`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(20.0)
        rig.brew(MicraShotCounter.BrewState.BREWING_STOPPED, timer = 25.0, reason = "Manual")
        rig.feed(-180.0)
        rig.hold(3.0)
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals("", rig.snap.alert)
    }

    @Test
    fun `a shot that finishes on readings raises no alert`() = runTest {
        val rig = Rig(this, AppConfig().let { it.copy(defaults = it.defaults.copy(targetYieldGrams = 60.0)) })
        rig.armAndStart()
        rig.pourTo(20.0)
        rig.hold(3.0) // stopped by hand, cup still on
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals("", rig.snap.alert)
    }

    @Test
    fun `a scale dropping inside the grace period drops the shot`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(3.0) // ~1.3 s in
        rig.feed(0.0, connected = false)
        rig.wait(0.5)
        assertEquals(ShotState.IDLE, rig.snap.state)
        assertTrue(rig.sink.saved.isEmpty())
    }

    @Test
    fun `a scale that goes silent mid-shot is caught by the watchdog`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        rig.pourTo(20.0)
        rig.wait(12.0) // no samples at all
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals(1, rig.sink.saved.size)
    }

    @Test
    fun `the sleep sample is not a reading`() = runTest {
        val rig = Rig(this)
        rig.feed(5.0)
        rig.service.setArmed(true)
        // weight 0, asleep: fed on it used to look like a start-threshold read
        rig.feed(0.0, connected = false, asleep = true)
        assertEquals(ShotState.IDLE, rig.snap.state)
        // Skipped, not fed: the shot context still holds the last real reading.
        assertEquals(5.0, rig.snap.weight, 0.0)
    }

    // --- Learning -----------------------------------------------------------

    @Test
    fun `the offset is learned from the drip after a stop`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.armAndStart()
        rig.pourTo(32.2)
        rig.pourTo(35.2) // 3 g of drip
        rig.hold(2.0) // settles: FINALIZE
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals(1, rig.sink.saved.size)
        // The stop fires on the first sample at or past 32.0 g, so the drip
        // is 35.2 - 32.0 = 3.2 g: 0.2 * 3.2 + 0.8 * 4.0
        assertEquals(listOf(3.84), rig.learned)
    }

    @Test
    fun `a stop through the cloud leaves the offset alone`() = runTest {
        val rig = Rig(this) // no link: the stop goes to the cloud
        rig.armAndStart()
        rig.pourTo(31.0)
        assertEquals(listOf("power false"), rig.cloud.calls)
        rig.pourTo(34.0) // "drip" that is mostly the cloud's delay
        rig.hold(2.0)
        rig.wait(0.5)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals(1, rig.sink.saved.size)
        assertTrue(rig.learned.isEmpty())
    }

    // --- Cloud lead -----------------------------------------------------------

    @Test
    fun `with the link up the stop fires at target minus offset`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.armAndStart()
        rig.pourTo(31.8)
        assertEquals(ShotState.BREWING, rig.snap.state)
        rig.pourTo(32.0) // 36 - 4
        assertEquals(ShotState.STOPPING, rig.snap.state)
        assertEquals(listOf("standby fast"), rig.ble!!.calls)
    }

    @Test
    fun `with the link down the stop fires flow x cloud delay earlier`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        // 2 g/s x 0.6 s = 1.2 g early: 36 - 4 - 1.2 = 30.8
        rig.pourTo(30.6)
        assertEquals(ShotState.BREWING, rig.snap.state)
        rig.pourTo(31.0)
        assertEquals(ShotState.STOPPING, rig.snap.state)
        assertEquals(listOf("power false"), rig.cloud.calls)
    }

    @Test
    fun `an implausible drip leaves the offset alone`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.armAndStart()
        rig.pourTo(32.2)
        rig.pourTo(48.2) // 16 g "drip": over the 15 g hard cap
        rig.hold(2.0)
        rig.wait(0.5)
        assertTrue(rig.learned.isEmpty())
    }

    // --- Auto-tare and deferred arming ---------------------------------------

    @Test
    fun `auto-arm waits for the tare to land, not for the tare call to return`() = runTest {
        val config = DefaultConfig.SEED.copy(
            autoTare = DefaultConfig.SEED.autoTare.copy(enabled = true, autoArm = true),
        )
        val rig = Rig(this, config)
        rig.hold(2.0) // empty scale, settled
        rig.feed(189.7) // the coffee cup lands
        rig.hold(2.0) // settles: matched, tare requested
        rig.wait(0.1)
        assertEquals(1, rig.scale.tares)
        assertFalse("not armed while the scale still reads the cup", rig.snap.armed)
        rig.feed(0.0) // the tare shows up in the stream
        assertTrue(rig.snap.armed)
        assertEquals(ShotState.IDLE, rig.snap.state)
    }

    @Test
    fun `only a dosing vessel asks for the machine link early`() = runTest {
        val config = DefaultConfig.SEED.copy(
            autoTare = DefaultConfig.SEED.autoTare.copy(enabled = true, autoArm = true),
        )
        val rig = Rig(this, config)
        rig.hold(2.0)
        rig.feed(140.2) // the milk pitcher: empty-only, no dose
        rig.hold(2.0)
        assertEquals(emptyList<String>(), rig.dosing)
        rig.feed(0.0) // its tare lands
        rig.hold(2.0)
        rig.feed(-140.2) // lifted (2.13.1: until then a rise is its contents)
        rig.hold(2.0)
        rig.feed(-140.2 + 113.8 + 18.0) // the dosing cup with beans
        rig.hold(2.0)
        assertEquals(listOf("Dosing cup"), rig.dosing)
    }

    @Test
    fun `the sleep sample does not complete a pending auto-arm`() = runTest {
        val config = DefaultConfig.SEED.copy(
            autoTare = DefaultConfig.SEED.autoTare.copy(enabled = true, autoArm = true),
        )
        val rig = Rig(this, config)
        rig.hold(2.0)
        rig.feed(189.7)
        rig.hold(2.0)
        rig.wait(0.1)
        rig.feed(0.0, connected = false, asleep = true)
        assertFalse(rig.snap.armed)
    }

    @Test
    fun `a pitcher landing disarms a stale arm`() = runTest {
        val config = DefaultConfig.SEED.copy(
            autoTare = DefaultConfig.SEED.autoTare.copy(enabled = true, autoArm = true),
        )
        val rig = Rig(this, config)
        rig.hold(2.0)
        rig.service.setArmed(true)
        // Pitcher lands on an armed, tared scale: 140.2 g is past the 10 g
        // start ceiling, so no shot - and the arm goes.
        rig.feed(140.2)
        rig.hold(2.0)
        assertFalse(rig.snap.armed)
        assertNull(rig.snap.finalYield)
    }

    // --- A vessel on the scale (2.13.1): rises are its contents ---------------

    private fun autoArmConfig() = DefaultConfig.SEED.copy(
        autoTare = DefaultConfig.SEED.autoTare.copy(enabled = true, autoArm = true),
    )

    /** Pour from the current weight to [to] at [gps], one sample every 0.1 s. */
    private fun Rig.pour(to: Double, gps: Double) {
        val step = gps / 10.0
        while (weight < to) feed(minOf(to, weight + step))
    }

    /** The pitcher lands on an empty scale, is matched, and its tare lands. */
    private fun Rig.pitcherOnAndTared() {
        hold(2.0)
        feed(140.3)
        hold(2.0)
        wait(0.1)
        assertEquals(1, scale.tares)
        feed(0.0) // the tare shows up
        hold(2.0)
    }

    @Test
    fun `milk poured into the pitcher is not read as the coffee cup (11-19 1508)`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.pitcherOnAndTared()
        rig.pour(190.6, gps = 35.0) // 190.6 g: the Coffee cup is 189.5 bare
        rig.hold(2.0)
        rig.wait(0.5)
        assertEquals("no second tare", 1, rig.scale.tares)
        assertFalse("not armed by milk", rig.snap.armed)
    }

    @Test
    fun `milk matching the dosing cup does not bring the machine link up (11-17 1426)`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.pitcherOnAndTared()
        rig.pour(114.5, gps = 35.0) // the Dosing cup is 113.8 bare
        rig.hold(2.0)
        assertEquals(1, rig.scale.tares)
        assertEquals(emptyList<String>(), rig.dosing)
    }

    @Test
    fun `once the pitcher is lifted, the coffee cup is matched and arms again`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.pitcherOnAndTared()
        rig.pour(190.6, gps = 35.0)
        rig.hold(2.0)
        rig.feed(-140.3) // pitcher and milk lifted
        rig.hold(2.0)
        rig.feed(-140.3 + 189.5) // the cup lands
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals(2, rig.scale.tares)
        rig.feed(0.0)
        assertTrue(rig.snap.armed)
    }

    @Test
    fun `a slow real cup after the dosing cup is lifted still arms (11-18 1006)`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.hold(2.0)
        rig.feed(132.8) // dosing cup with 19 g of beans: contents, no tare
        rig.hold(2.0)
        assertEquals(listOf("Dosing cup"), rig.dosing)
        rig.feed(0.0) // lifted
        rig.hold(2.0)
        rig.pour(189.6, gps = 50.0) // the cup set down slowly: 50 g/s
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals(1, rig.scale.tares)
        rig.feed(0.0)
        assertTrue(rig.snap.armed)
    }

    @Test
    fun `a tare on an empty scale reading minus the cup does not hold off the next cup (11-10 1011)`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.hold(2.0)
        rig.feed(-113.9) // the empty scale, zeroed with the cup on
        rig.hold(2.0)
        rig.feed(0.0) // TARE: one instant +113.9 g step onto zero
        rig.hold(2.0)
        rig.feed(132.4) // the dosing cup with beans
        rig.hold(2.0)
        assertEquals("Dosing cup", rig.snap.netLabel)
    }

    @Test
    fun `TARE after the coffee cup is lifted does not arm (2_13_2)`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.hold(2.0)
        rig.feed(-189.5) // the empty scale, zeroed with the cup on
        rig.hold(2.0)
        rig.feed(0.0) // TARE: one instant +189.5 g step onto zero
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals("no auto-tare", 0, rig.scale.tares)
        assertFalse("not armed", rig.snap.armed)
    }

    @Test
    fun `TARE after the dosing cup is lifted does not bring the link up (2_13_2)`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.hold(2.0)
        rig.feed(-113.7)
        rig.hold(2.0)
        rig.feed(0.0)
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals(0, rig.scale.tares)
        assertEquals(emptyList<String>(), rig.dosing)
    }

    @Test
    fun `the coffee cup set back down onto zero still arms`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.hold(2.0)
        rig.feed(-189.5) // cup lifted from a scale tared with it on
        rig.hold(2.0)
        rig.pour(0.0, gps = 400.0) // set back down over several samples
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals(1, rig.scale.tares)
        rig.feed(0.0)
        assertTrue(rig.snap.armed)
    }

    @Test
    fun `TARE pressed ends the vessel-on-the-scale rule`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.pitcherOnAndTared()
        rig.service.onManualTare()
        rig.pour(189.5, gps = 400.0)
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals(2, rig.scale.tares)
    }

    @Test
    fun `a vessel left on the scale stops holding off matches after 10 minutes`() = runTest {
        val rig = Rig(this, autoArmConfig())
        rig.pitcherOnAndTared()
        rig.hold(601.0)
        rig.pour(189.5, gps = 400.0)
        rig.hold(2.0)
        rig.wait(0.1)
        assertEquals(2, rig.scale.tares)
    }

    private companion object {
        /** Epoch-like seconds, so timestamps look like the real ones. */
        const val BASE = 1_790_000_000.0
    }

    // --- Machine shot counter (2.11.0) ------------------------------------

    @Test
    fun `the machine's pump start becomes the shot's machine time`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.feed(0.0)
        rig.service.setArmed(true)
        // The pump came on 6 s before the first drops.
        rig.brew(MicraShotCounter.BrewState.BREWING, timer = 6.0, startAgoS = 6.0)
        rig.feed(0.4)
        assertEquals(ShotState.BREWING, rig.snap.state)
        rig.pourTo(36.0)
        rig.brew(MicraShotCounter.BrewState.BREWING_STOPPED, timer = 24.0, reason = "Manual")
        rig.hold(3.0)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        val machine = rig.sink.machineDurations.single()!!
        val scale = rig.sink.durations.single()
        assertEquals(scale + 6.0, machine, 0.3)
    }

    // --- Brew page timer (2.12.0) -------------------------------------------

    @Test
    fun `the timer runs from pump-on, before the first drops`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.feed(0.0)
        rig.service.setArmed(true)
        rig.feed(0.0)
        assertEquals(TimerSource.NONE, rig.snap.timerSource)
        // The pump came on 2 s ago; nothing in the cup yet.
        rig.brew(MicraShotCounter.BrewState.BREWING, timer = 2.0, startAgoS = 2.0)
        rig.feed(0.0)
        assertEquals(ShotState.IDLE, rig.snap.state)
        assertEquals(TimerSource.PUMP, rig.snap.timerSource)
        assertEquals(2.1, rig.snap.elapsedSeconds, 0.05)
        // First drops 3 s later: the timer carries on, it does not restart.
        repeat(3) {
            rig.brew(MicraShotCounter.BrewState.BREWING, timer = 2.1 + it, startAgoS = 2.1 + it)
            rig.hold(1.0)
        }
        rig.feed(0.4)
        assertEquals(ShotState.BREWING, rig.snap.state)
        assertEquals(TimerSource.PUMP, rig.snap.timerSource)
        assertEquals(5.2, rig.snap.elapsedSeconds, 0.05)
        rig.pourTo(36.0)
        rig.brew(MicraShotCounter.BrewState.BREWING_STOPPED, timer = 24.0, reason = "Manual")
        rig.hold(3.0)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        // Finished, it reads the saved machine time.
        assertEquals(TimerSource.PUMP, rig.snap.timerSource)
        assertEquals(rig.sink.machineDurations.single()!!, rig.snap.elapsedSeconds, 0.05)
    }

    @Test
    fun `without BLE the timer falls back to the first drops`() = runTest {
        val rig = Rig(this)
        rig.armAndStart()
        assertEquals(TimerSource.SCALE, rig.snap.timerSource)
        rig.pourTo(36.0)
        rig.hold(3.0)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
        assertEquals(TimerSource.SCALE, rig.snap.timerSource)
        assertEquals(rig.sink.durations.single(), rig.snap.elapsedSeconds, 0.05)
    }

    @Test
    fun `a pump timer goes back to zero when the machine stops reporting`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.feed(0.0)
        rig.service.setArmed(true)
        rig.brew(MicraShotCounter.BrewState.BREWING, timer = 1.0, startAgoS = 1.0)
        rig.feed(0.0)
        assertEquals(TimerSource.PUMP, rig.snap.timerSource)
        // The link goes quiet: past the 3 s freshness the pump is not assumed on.
        rig.hold(4.0)
        assertEquals(TimerSource.NONE, rig.snap.timerSource)
        assertEquals(0.0, rig.snap.elapsedSeconds, 0.0)
    }

    @Test
    fun `a slow start does not finish while the machine is still brewing`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.armAndStart()
        // A trickle to 6 g (past the 5 g floor of 2.10.2), then a 4 s pause past
        // the grace period, with the machine ticking every second.
        repeat(60) { i ->
            rig.feed(0.4 + (i + 1) * 5.6 / 60)
            if (i % 10 == 0) rig.brew(MicraShotCounter.BrewState.BREWING, timer = i / 10.0 + 5)
        }
        repeat(4) {
            rig.brew(MicraShotCounter.BrewState.BREWING, timer = 11.0 + it)
            rig.hold(1.0)
        }
        assertEquals(ShotState.BREWING, rig.snap.state)
        rig.pourTo(36.0)
        assertTrue(rig.ble.calls.contains("standby fast"))
    }

    @Test
    fun `without fresh machine ticks the scale decides the finish again`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.armAndStart()
        rig.pourTo(10.0)
        rig.brew(MicraShotCounter.BrewState.BREWING, timer = 10.0)
        // Ticks stop (link gone quiet); the weight settles past 10 g.
        rig.hold(5.0)
        assertEquals(ShotState.FINALIZED, rig.snap.state)
    }

    @Test
    fun `a stop the machine confirms is not sent again`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.armAndStart()
        rig.pourTo(33.0)
        assertEquals(1, rig.ble.calls.count { it == "standby fast" })
        rig.brew(MicraShotCounter.BrewState.BREWING_STOPPED, timer = 25.0, reason = "Manual")
        rig.hold(6.0)
        assertEquals(1, rig.ble.calls.count { it == "standby fast" })
        assertEquals("", rig.snap.alert)
    }

    @Test
    fun `a machine still brewing after the stop gets it again, then the alert`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.ble!!.counter = true
        rig.armAndStart()
        rig.pourTo(33.0)
        assertEquals(1, rig.ble.calls.count { it == "standby fast" })
        // The machine keeps ticking and the cup keeps filling.
        repeat(60) {
            rig.feed(rig.weight + 0.2)
            if (it % 10 == 0) rig.brew(MicraShotCounter.BrewState.BREWING, timer = 25.0 + it / 10.0)
        }
        assertEquals(2, rig.ble.calls.count { it == "standby fast" })
        assertEquals("Stop failed - stop the machine by hand", rig.snap.alert)
    }

    @Test
    fun `no shot counter, no stop confirmation`() = runTest {
        val rig = Rig(this, linkUp = true)
        rig.armAndStart()
        rig.pourTo(33.0)
        repeat(60) { rig.feed(rig.weight + 0.05) }
        assertEquals(1, rig.ble!!.calls.count { it == "standby fast" })
    }
}
