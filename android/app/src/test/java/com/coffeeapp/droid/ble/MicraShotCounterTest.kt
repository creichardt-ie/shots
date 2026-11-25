// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import com.coffeeapp.droid.ble.MicraShotCounter.BrewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shot-counter parser and tracker, replayed against pylamarzocco 2.5.6's
 * own recording of a Linea Micra (`tests/fixtures/machine/
 * shot_counter_2026-10-05.txt`; its heating coefficients are made up, the
 * rest is as recorded). The states asserted are what pylamarzocco's
 * _BrewingTracker derives from the same lines.
 */
class MicraShotCounterTest {

    /** The recording, minus the subscribe replays (the client drops those). */
    private val recording = """
07:47:47.412 {"SteamBoilerUpdateTemperature":112}
07:49:22.144 {"SteamBoilerHeatingCoeff":"[{\"temperature\":18,\"coefficent\":0.1957}]"}
07:49:22.179 {"SteamBoilerUpdateTemperature":130}
07:50:02.612 {"BrewingStartedGroup1DoseIndex":"ContinuousDose"}
07:50:02.682 {"BrewingUpdateGroup1Time":0.089,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:50:03.742 {"BrewingUpdateGroup1Time":1.159,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:50:04.525 {"FlushStoppedGroup1DoseIndex":"ContinuousDose","FlushStoppedGroup1Time":1.891}
07:50:09.360 {"BrewingStartedGroup1DoseIndex":"ContinuousDose"}
07:50:09.507 {"BrewingUpdateGroup1Time":0.107,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:50:11.215 {"BrewingUpdateGroup1Time":1.177,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:50:11.218 {"FlushStoppedGroup1DoseIndex":"ContinuousDose","FlushStoppedGroup1Time":1.374}
07:50:17.940 {"MachineStatistics":"{\"groups\":[{\"doses\":[{\"DoseA\":2123}],\"clean\":0}],\"tot_doses\":2123,\"pump_ontime\":41289}"}
07:53:27.093 {"CoffeeBoiler1UpdateTemperature":95}
07:57:49.729 {"BrewingStartedGroup1DoseIndex":"ContinuousDose"}
07:57:49.831 {"BrewingUpdateGroup1Time":0.108,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:50.894 {"BrewingUpdateGroup1Time":1.178,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:51.983 {"BrewingUpdateGroup1Time":2.251,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:53.054 {"BrewingUpdateGroup1Time":3.324,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:54.132 {"BrewingUpdateGroup1Time":4.394,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:55.192 {"BrewingUpdateGroup1Time":5.479,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:56.282 {"BrewingUpdateGroup1Time":6.556,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:57.352 {"BrewingUpdateGroup1Time":7.631,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:58.433 {"BrewingUpdateGroup1Time":8.703,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:57:59.493 {"BrewingUpdateGroup1Time":9.773,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:58:00.583 {"BrewingUpdateGroup1Time":10.846,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:58:01.659 {"BrewingUpdateGroup1Time":11.897,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:58:02.461 {"BrewingStoppedGroup1StopType":"Continuous","BrewingStoppedGroup1StopReason":"Manual","BrewingStoppedGroup1DoseIndex":"ContinuousDose","BrewingStoppedGroup1Time":12.727}
07:59:22.007 {"BrewingStartedGroup1DoseIndex":"ContinuousDose"}
07:59:22.140 {"BrewingUpdateGroup1Time":0.118,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:59:30.726 {"BrewingUpdateGroup1Time":8.716,"BrewingUpdateGroup1Ev":true,"BrewingUpdateGroup1Backflush":false}
07:59:31.412 {"FlushStoppedGroup1DoseIndex":"ContinuousDose","FlushStoppedGroup1Time":9.314}
07:59:38.589 {"CoffeeBoiler1UpdateTemperature":92}
07:59:42.983 {"MachineMode":"StandBy"}
07:59:42.983 {"Sleep":"command"}
""".trim().lines()

    private fun ms(clock: String): Long {
        val (h, m, s) = clock.split(":")
        return ((h.toLong() * 60 + m.toLong()) * 60 * 1000) + (s.toDouble() * 1000).toLong()
    }

    private data class Fed(val atMs: Long, val n: MicraShotCounter.Notification, val update: MicraShotCounter.BrewUpdate?)

    private fun replay(): List<Fed> {
        val tracker = MicraShotCounter.Tracker()
        return recording.map { line ->
            val at = ms(line.substringBefore(' '))
            val n = MicraShotCounter.parse(line.substringAfter(' ').toByteArray())!!
            Fed(at, n, if (n.brewing.isEmpty) null else tracker.process(n.brewing, at))
        }
    }

    @Test
    fun `every line of the recording parses`() {
        assertEquals(recording.size, replay().size)
    }

    @Test
    fun `the shot brews, then stops with the machine's own time and reason`() {
        val fed = replay()
        val shot = fed.filter { it.atMs in ms("07:57:49.000")..ms("07:58:03.000") }.mapNotNull { it.update }
        assertTrue(shot.dropLast(1).all { it.state == BrewState.BREWING })
        val stop = shot.last()
        assertEquals(BrewState.BREWING_STOPPED, stop.state)
        assertEquals(12.727, stop.finalShotSeconds!!, 1e-9)
        assertEquals("Manual", stop.stopReason)
        assertEquals("Continuous", stop.stopType)
    }

    @Test
    fun `the pump start is the earliest estimate and survives into the stop`() {
        val fed = replay()
        val shot = fed.filter { it.atMs in ms("07:57:49.000")..ms("07:58:03.000") }.mapNotNull { it.update }
        // The start notification has no timer: its own arrival is the first guess.
        val first = shot.first()
        assertEquals(ms("07:57:49.729"), first.brewingStartMs)
        // Later ticks arrive late by varying amounts and can only pull it earlier.
        val starts = shot.dropLast(1).map { it.brewingStartMs!! }
        assertTrue(starts.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(starts.last() in ms("07:57:49.700")..ms("07:57:49.729"))
        assertEquals(starts.last(), shot.last().brewingStartMs)
    }

    @Test
    fun `short pulls end as flushes`() {
        val flushes = replay().mapNotNull { it.update }.filter { it.state == BrewState.FLUSHED }
        assertEquals(listOf(1.891, 1.374, 9.314), flushes.map { it.flushSeconds })
    }

    @Test
    fun `telemetry never touches the brewing state`() {
        val fed = replay()
        val temps = fed.filter { it.n.telemetry.steamBoilerTemp != null || it.n.telemetry.coffeeBoilerTemp != null }
        assertTrue(temps.isNotEmpty())
        assertTrue(temps.all { it.n.brewing.isEmpty && it.update == null })
        assertEquals(95.0, fed.first { it.n.telemetry.coffeeBoilerTemp != null }.n.telemetry.coffeeBoilerTemp!!, 1e-9)
        assertEquals("StandBy", fed.first { it.n.telemetry.machineMode != null }.n.telemetry.machineMode)
        assertEquals("command", fed.first { it.n.telemetry.sleep != null }.n.telemetry.sleep)
    }

    @Test
    fun `machine statistics arrive as a json string and are parsed`() {
        val stats = replay().firstNotNullOf { it.n.telemetry.statistics }
        assertEquals("2123", stats["tot_doses"].toString())
    }

    @Test
    fun `keys nobody knows are neither brewing nor telemetry`() {
        val coeff = replay().first { "SteamBoilerHeatingCoeff" in it.n.keys }
        assertTrue(coeff.n.brewing.isEmpty)
        assertTrue(coeff.n.telemetry.isEmpty)
    }

    @Test
    fun `nul padding is stripped and garbage is refused`() {
        val padded = "{\"BrewingStartedGroup1DoseIndex\":\"DoseA\"}".toByteArray() + byteArrayOf(0, 0)
        assertEquals("DoseA", MicraShotCounter.parse(padded)!!.brewing.startedDoseIndex)
        assertNull(MicraShotCounter.parse("not json".toByteArray()))
        assertNull(MicraShotCounter.parse(ByteArray(0)))
    }

    @Test
    fun `ev false alone reads as stopped`() {
        val t = MicraShotCounter.Tracker()
        t.process(MicraShotCounter.BrewingData(startedDoseIndex = "DoseA"), 1_000)
        val u = t.process(MicraShotCounter.BrewingData(ev = false), 2_000)
        assertNotNull(u)
        assertEquals(BrewState.BREWING_STOPPED, u!!.state)
    }
}
