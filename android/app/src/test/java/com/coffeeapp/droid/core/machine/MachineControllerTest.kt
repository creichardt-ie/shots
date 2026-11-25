// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.machine

import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.ble.MicraBleClient.Demand
import com.coffeeapp.droid.ble.MicraProtocol
import com.coffeeapp.droid.ble.MicraProtocol.Boiler
import com.coffeeapp.droid.ble.MicraProtocol.CommandStatus
import com.coffeeapp.droid.ble.MicraProtocol.MachineMode
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.core.model.MachineSnapshot
import com.coffeeapp.droid.core.model.ScaleSnapshot
import com.coffeeapp.droid.core.model.ShotSnapshot
import com.coffeeapp.droid.core.model.ShotState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules from BLE_PLAN.md step 3, against fake transports. Virtual
 * time (runTest) makes "never waits" a measurable claim: currentTime is the
 * milliseconds a call spent suspended.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runTest's virtual clock (currentTime)
class MachineControllerTest {

    private val ok = CommandStatus("x", "ok", "success")

    private class FakeCloud : CloudMachine {
        val calls = mutableListOf<String>()
        var snapshot = MachineSnapshot(lastSyncOk = true)
        var startTs: Double? = 1234.5
        var failPower: Exception? = null
        override suspend fun refresh(): MachineSnapshot { calls += "refresh"; return snapshot }
        override suspend fun getBrewStartTs(): Double? { calls += "brewStart"; return startTs }
        override suspend fun setPower(on: Boolean) {
            calls += "power $on"
            failPower?.let { throw it }
        }
        override suspend fun setCoffeeTemp(temperature: Double) { calls += "coffee $temperature" }
        override suspend fun setSteamLevel(level: String) { calls += "steam $level" }
        override suspend fun setSteamEnabled(enabled: Boolean) { calls += "steamOn $enabled" }
    }

    private class FakeBle(
        override var isConnected: Boolean = true,
        var reply: CommandStatus? = CommandStatus("x", "ok", "success"),
        var latencyMs: Long = 50,
        var throws: Exception? = null,
    ) : BleMachine {
        val calls = mutableListOf<String>()
        override val readings: StateFlow<MicraBleClient.Readings?> = MutableStateFlow(null)
        private suspend fun op(name: String): CommandStatus? {
            calls += name
            delay(latencyMs)
            throws?.let { throw it }
            return reply
        }
        override suspend fun setPower(on: Boolean) = op("power $on")
        override suspend fun standbyFast() = op("standby fast")?.isSuccess == true
        override suspend fun setCoffeeTarget(celsius: Double) = op("coffee $celsius")
        override suspend fun setSteamTarget(celsius: Int) = op("steam $celsius")
        override suspend fun setSteamEnabled(enabled: Boolean) = op("steamOn $enabled")
    }

    private val logs = mutableListOf<String>()
    private fun controller(cloud: CloudMachine, ble: BleMachine?) =
        MachineController(cloud, ble, log = { logs += it })

    // --- Stop ---------------------------------------------------------------

    @Test
    fun `a stop is foreseen to go to the cloud only when the link is down`() {
        assertTrue(controller(FakeCloud(), null).stopGoesToCloud)
        assertTrue(controller(FakeCloud(), FakeBle(isConnected = false)).stopGoesToCloud)
        assertFalse(controller(FakeCloud(), FakeBle()).stopGoesToCloud)
    }

    @Test
    fun `stop goes over BLE when connected`() = runTest {
        val cloud = FakeCloud()
        val ble = FakeBle()
        val r = controller(cloud, ble).stopBrew()
        assertEquals(Routed(ControlPath.BLE), r)
        // The fast write-only path, never the full command with a status read.
        assertEquals(listOf("standby fast"), ble.calls)
        assertTrue(cloud.calls.isEmpty())
        assertTrue(logs.single().startsWith("BBW stop via BLE"))
    }

    @Test
    fun `stop falls back to cloud when the BLE command fails`() = runTest {
        val cloud = FakeCloud()
        val r = controller(cloud, FakeBle(reply = null)).stopBrew()
        assertEquals(ControlPath.CLOUD, r.path)
        assertTrue(r.fallbackReason!!.startsWith("BLE command failed"))
        assertEquals(listOf("power false"), cloud.calls)
    }

    @Test
    fun `stop falls back to cloud when the BLE leg throws`() = runTest {
        val cloud = FakeCloud()
        val r = controller(cloud, FakeBle(throws = IllegalStateException("gatt"))).stopBrew()
        assertEquals(ControlPath.CLOUD, r.path)
        assertTrue(r.fallbackReason!!, r.fallbackReason!!.startsWith("BLE threw IllegalStateException"))
        assertEquals(listOf("power false"), cloud.calls)
    }

    @Test
    fun `a command falls back to cloud when the machine reports an error`() = runTest {
        val cloud = FakeCloud()
        val r = controller(cloud, FakeBle(reply = CommandStatus("x", "busy", "error"))).setPower(false)
        assertEquals(ControlPath.CLOUD, r.path)
        assertEquals("BLE status error (busy)", r.fallbackReason)
        assertEquals(listOf("power false"), cloud.calls)
    }

    @Test
    fun `slow BLE stop hands over to cloud at the timeout`() = runTest {
        val cloud = FakeCloud()
        val r = controller(cloud, FakeBle(latencyMs = 5_000)).stopBrew()
        assertEquals(ControlPath.CLOUD, r.path)
        assertEquals("BLE timed out after 300 ms", r.fallbackReason)
        assertEquals(listOf("power false"), cloud.calls)
        // Handed over at the timeout, not after the slow op finished.
        assertEquals(MachineController.STOP_BLE_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `stop never waits on a disconnected link`() = runTest {
        val cloud = FakeCloud()
        val ble = FakeBle(isConnected = false, latencyMs = 60_000)
        val r = controller(cloud, ble).stopBrew()
        assertEquals(Routed(ControlPath.CLOUD, "BLE not connected"), r)
        assertTrue("BLE must not even be tried", ble.calls.isEmpty())
        assertEquals(0L, currentTime)
        assertEquals(listOf("power false"), cloud.calls)
    }

    @Test
    fun `no BLE client means cloud with a reason`() = runTest {
        val cloud = FakeCloud()
        assertEquals(Routed(ControlPath.CLOUD, "BLE not configured"), controller(cloud, null).stopBrew())
        assertEquals("BBW stop via cloud (BLE not configured)", logs.first())
        assertTrue(logs.last().startsWith("BBW stop via cloud done in"))
        assertEquals(2, logs.size)
    }

    @Test
    fun `a failing cloud stop is logged and rethrown`() = runTest {
        val cloud = FakeCloud().apply { failPower = IllegalStateException("offline") }
        val thrown = runCatching { controller(cloud, FakeBle(isConnected = false)).stopBrew() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(logs.last(), logs.last().startsWith("BBW stop via cloud failed after"))
        assertTrue(logs.last().contains("offline"))
    }

    // --- Other commands -----------------------------------------------------

    @Test
    fun `commands go over BLE when connected`() = runTest {
        val cloud = FakeCloud()
        val ble = FakeBle()
        val c = controller(cloud, ble)
        c.setPower(true)
        c.setCoffeeTemp(93.46)
        c.setSteamLevel("Level3")
        c.setSteamEnabled(false)
        assertEquals(listOf("power true", "coffee 93.5", "steam 131", "steamOn false"), ble.calls)
        assertTrue(cloud.calls.isEmpty())
    }

    @Test
    fun `commands go to cloud when disconnected`() = runTest {
        val cloud = FakeCloud()
        val c = controller(cloud, FakeBle(isConnected = false))
        c.setPower(false)
        c.setCoffeeTemp(94.0)
        c.setSteamLevel("Level1")
        c.setSteamEnabled(true)
        assertEquals(listOf("power false", "coffee 94.0", "steam Level1", "steamOn true"), cloud.calls)
    }

    @Test
    fun `a slow non-urgent command gets longer than the stop before falling back`() = runTest {
        val cloud = FakeCloud()
        val r = controller(cloud, FakeBle(latencyMs = 1_000)).setPower(true)
        assertEquals(Routed(ControlPath.BLE), r)
        assertTrue(cloud.calls.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown steam level is refused before any transport`() = runTest {
        controller(FakeCloud(), FakeBle()).setSteamLevel("Level9")
    }

    // --- Cloud-only calls ---------------------------------------------------

    @Test
    fun `brew start is always cloud, even with BLE connected`() = runTest {
        val cloud = FakeCloud()
        val ble = FakeBle()
        assertEquals(1234.5, controller(cloud, ble).getBrewStartTs()!!, 0.0)
        assertEquals(listOf("brewStart"), cloud.calls)
        assertTrue(ble.calls.isEmpty())
    }

    // --- Snapshot merging ---------------------------------------------------

    private val good = MachineSnapshot(
        status = "PoweredOn", powerOn = true, name = "Micra", coffeeTemp = 94.0,
        coffeeBoilerStatus = "Ready", steamLevel = "Level2", connected = true,
        gatewayVersion = "v5", lastSyncOk = true,
    )

    private fun readings(mode: MachineMode, coffee: Double = 93.0, steam: Double = 131.0) =
        MicraBleClient.Readings(
            mode,
            listOf(
                Boiler(MicraProtocol.STEAM_BOILER, true, steam, steam - 1),
                Boiler(MicraProtocol.COFFEE_BOILER, true, coffee, coffee - 0.5),
            ),
            atMillis = 1L,
        )

    @Test
    fun `failed cloud refresh keeps last good values`() {
        val failed = MachineSnapshot(lastSyncOk = false, lastError = "timeout")
        val merged = MachineController.mergeCloud(good, failed)
        assertEquals(good.copy(lastSyncOk = false, lastError = "timeout"), merged)
    }

    @Test
    fun `successful cloud refresh replaces the snapshot`() {
        val fresh = good.copy(coffeeTemp = 95.0)
        assertEquals(fresh, MachineController.mergeCloud(good, fresh))
    }

    @Test
    fun `BLE readings override targets and power, keep cloud-only fields`() {
        val out = MachineController.applyBle(good, readings(MachineMode.BREWING, coffee = 93.0, steam = 131.0))
        assertEquals(93.0, out.coffeeTemp, 0.0)
        assertEquals("Level3", out.steamLevel)
        assertTrue(out.powerOn)
        assertEquals("PoweredOn", out.status)
        assertEquals("Micra", out.name)
        assertEquals("Ready", out.coffeeBoilerStatus)
        assertEquals("v5", out.gatewayVersion)
    }

    @Test
    fun `BLE standby turns power off and says so`() {
        val out = MachineController.applyBle(good, readings(MachineMode.STANDBY))
        assertEquals(false, out.powerOn)
        assertEquals("StandBy", out.status)
    }

    @Test
    fun `BLE keeps the cloud's Brewing status while power agrees`() {
        val out = MachineController.applyBle(good.copy(status = "Brewing"), readings(MachineMode.BREWING))
        assertEquals("Brewing", out.status)
    }

    @Test
    fun `compose keeps last good values and still layers BLE on a failed refresh`() {
        val failed = MachineSnapshot(lastSyncOk = false, lastError = "timeout")
        val out = MachineController.compose(good, failed, readings(MachineMode.BREWING, coffee = 92.0))
        assertEquals(92.0, out.coffeeTemp, 0.0)
        assertEquals("Micra", out.name)
        assertEquals(false, out.lastSyncOk)
        assertEquals("timeout", out.lastError)
    }

    @Test
    fun `BLE readings carry live temperatures and steam enabled`() {
        val r = MicraBleClient.Readings(
            MachineMode.BREWING,
            listOf(
                Boiler(MicraProtocol.STEAM_BOILER, false, 128.0, 40.0),
                Boiler(MicraProtocol.COFFEE_BOILER, true, 94.0, 97.0),
            ),
            atMillis = 1L,
        )
        val out = MachineController.applyBle(good.copy(steamEnabled = true), r)
        assertEquals(97.0, out.coffeeTempCurrent!!, 0.0)
        assertEquals(40.0, out.steamTempCurrent!!, 0.0)
        assertEquals(false, out.steamEnabled)
    }

    @Test
    fun `a link that is not up drops live temperatures and routes to cloud`() {
        val live = MachineController.withLink(
            MachineController.applyBle(good, readings(MachineMode.BREWING)),
            MicraBleClient.State.CONNECTED,
        )
        assertEquals(ControlPath.BLE, live.controlPath)
        assertTrue(live.coffeeTempCurrent != null)

        val lost = MachineController.withLink(live, MicraBleClient.State.UNREACHABLE)
        assertEquals(ControlPath.CLOUD, lost.controlPath)
        assertEquals(MicraBleClient.State.UNREACHABLE, lost.bleState)
        assertNull(lost.coffeeTempCurrent)
        assertNull(lost.steamTempCurrent)
        // Targets are still the last known, not wiped.
        assertEquals(live.coffeeTemp, lost.coffeeTemp, 0.0)
    }

    @Test
    fun `a cloud refresh keeps the link state it knows nothing about`() {
        val previous = good.copy(
            bleState = MicraBleClient.State.CONNECTED,
            controlPath = ControlPath.BLE,
            bleSeen = true,
        )
        val out = MachineController.compose(previous, good, readings(MachineMode.BREWING))
        assertEquals(MicraBleClient.State.CONNECTED, out.bleState)
        assertTrue(out.bleSeen)
        assertEquals(ControlPath.BLE, out.controlPath)
        assertTrue(out.coffeeTempCurrent != null)
    }

    @Test
    fun `no BLE readings leave the cloud snapshot untouched`() {
        assertEquals(good, MachineController.applyBle(good, null))
        assertNull(MachineController.compose(good, good, null).lastError)
    }

    @Test
    fun `link demand follows the shot only`() {
        val idle = AppSnapshot()
        val awake = ScaleSnapshot(connected = true)
        fun demand(s: AppSnapshot = idle) = MachineController.linkDemand(s)

        assertEquals(Demand.NONE, demand())
        // An awake scale alone no longer holds the link.
        assertEquals(Demand.NONE, demand(s = idle.copy(scale = awake)))

        assertEquals(Demand.SHOT, demand(s = idle.copy(scale = awake, shot = ShotSnapshot(armed = true))))
        // Armed on a sleeping or absent scale cannot see a shot start.
        assertEquals(Demand.NONE, demand(s = idle.copy(shot = ShotSnapshot(armed = true))))
        assertEquals(
            Demand.NONE,
            demand(s = idle.copy(scale = ScaleSnapshot(connected = true, asleep = true), shot = ShotSnapshot(armed = true))),
        )
        // A running shot wins over everything, scale state included.
        assertEquals(Demand.SHOT, demand(s = idle.copy(shot = ShotSnapshot(state = ShotState.BREWING))))
        assertEquals(Demand.SHOT, demand(s = idle.copy(shot = ShotSnapshot(state = ShotState.STOPPING))))
        // FINALIZED lasts until RESET; it must not pin the link.
        assertEquals(Demand.NONE, demand(s = idle.copy(scale = awake, shot = ShotSnapshot(state = ShotState.FINALIZED))))
        // Nor may the arm a real shot leaves behind: after every espresso the
        // snapshot is FINALIZED with armed still true (until the pitcher or
        // RESET disarms it), and counting that held the link at HIGH.
        assertEquals(
            Demand.NONE,
            demand(s = idle.copy(scale = awake, shot = ShotSnapshot(state = ShotState.FINALIZED, armed = true))),
        )
    }

    @Test
    fun `beans on the scale want the link at PREP until a shot outranks it`() {
        val awake = ScaleSnapshot(connected = true)
        fun demand(s: AppSnapshot) = MachineController.linkDemand(s, prep = true)
        assertEquals(Demand.PREP, demand(AppSnapshot()))
        assertEquals(Demand.PREP, demand(AppSnapshot(shot = ShotSnapshot(state = ShotState.FINALIZED, armed = true))))
        assertEquals(Demand.SHOT, demand(AppSnapshot(scale = awake, shot = ShotSnapshot(armed = true))))
        assertEquals(Demand.SHOT, demand(AppSnapshot(shot = ShotSnapshot(state = ShotState.BREWING))))
    }

    @Test
    fun `an arm left standing drops the link to PREP, a shot raises it again`() {
        val awake = ScaleSnapshot(connected = true)
        val armed = AppSnapshot(scale = awake, shot = ShotSnapshot(armed = true))
        assertEquals(Demand.SHOT, MachineController.linkDemand(armed, armHot = true))
        assertEquals(Demand.PREP, MachineController.linkDemand(armed, armHot = false))
        assertEquals(
            Demand.SHOT,
            MachineController.linkDemand(armed.copy(shot = ShotSnapshot(state = ShotState.BREWING, armed = true)), armHot = false),
        )
        // Asleep: no arm demand at all, hot or not.
        assertEquals(
            Demand.NONE,
            MachineController.linkDemand(armed.copy(scale = awake.copy(asleep = true)), armHot = false),
        )
    }
}
