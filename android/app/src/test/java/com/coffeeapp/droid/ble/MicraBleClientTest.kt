// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ble

import com.coffeeapp.droid.ble.MicraBleClient.Discovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of MicraBleClient that decide things without touching GATT:
 * what a discovered characteristic set means, and the reconnect schedule.
 */
class MicraBleClientTest {

    private val read = MicraProtocol.READ_CHAR
    private val write = MicraProtocol.WRITE_CHAR
    private val token = MicraProtocol.TOKEN_CHAR
    private val auth = MicraProtocol.AUTH_CHAR

    @Test
    fun `normal machine is ready`() {
        assertEquals(Discovery.READY, MicraBleClient.classify(setOf(read, write, auth)))
    }

    @Test
    fun `token without auth is configuration mode`() {
        assertEquals(Discovery.CONFIG_MODE, MicraBleClient.classify(setOf(read, write, token)))
        assertEquals(Discovery.CONFIG_MODE, MicraBleClient.classify(setOf(token)))
    }

    @Test
    fun `token alongside auth is still ready`() {
        assertEquals(Discovery.READY, MicraBleClient.classify(setOf(read, write, token, auth)))
    }

    @Test
    fun `missing characteristics are not ready`() {
        assertEquals(Discovery.MISSING, MicraBleClient.classify(setOf(read, auth)))
        assertEquals(Discovery.MISSING, MicraBleClient.classify(emptySet()))
    }

    @Test
    fun `classification ignores UUID case`() {
        assertEquals(
            Discovery.READY,
            MicraBleClient.classify(setOf(read.uppercase(), write.uppercase(), auth.uppercase())),
        )
    }

    @Test
    fun `backoff grows and caps at a minute`() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L),
            (1..6).map(MicraBleClient::backoffMs))
        assertEquals(2_000L, MicraBleClient.backoffMs(0))
        assertEquals(60_000L, MicraBleClient.backoffMs(1_000))
    }

    @Test
    fun `release is due only after the full delay unwanted`() {
        val t = 1_000_000L
        val delay = MicraBleClient.RELEASE_AFTER_MS
        assertFalse(MicraBleClient.releaseDue(null, t + 10 * delay))
        assertFalse(MicraBleClient.releaseDue(t, t))
        assertFalse(MicraBleClient.releaseDue(t, t + delay - 1))
        assertTrue(MicraBleClient.releaseDue(t, t + delay))
    }

    @Test
    fun `a failed connect is retried only while something needs the link`() {
        val none = MicraBleClient.Demand.NONE
        assertTrue(MicraBleClient.skipRetry(1, none, manual = false))
        assertFalse(MicraBleClient.skipRetry(0, none, manual = false))
        assertFalse(MicraBleClient.skipRetry(1, none, manual = true))
        assertFalse(MicraBleClient.skipRetry(3, MicraBleClient.Demand.SHOT, manual = false))
        // PREP retries: a power-cycle while the beans are weighed should come back by itself.
        assertFalse(MicraBleClient.skipRetry(3, MicraBleClient.Demand.PREP, manual = false))
    }

    @Test
    fun `only a shot gets the fast poll and HIGH priority`() {
        assertEquals(2_000L, MicraBleClient.pollIntervalMs(MicraBleClient.Demand.SHOT))
        assertEquals(10_000L, MicraBleClient.pollIntervalMs(MicraBleClient.Demand.NONE))
        assertEquals(10_000L, MicraBleClient.pollIntervalMs(MicraBleClient.Demand.PREP))
        assertEquals(
            android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
            MicraBleClient.priorityFor(MicraBleClient.Demand.PREP),
        )
        assertEquals(
            android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH,
            MicraBleClient.priorityFor(MicraBleClient.Demand.SHOT),
        )
        assertEquals(
            android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
            MicraBleClient.priorityFor(MicraBleClient.Demand.NONE),
        )
    }

    @Test
    fun `probe ages read as seconds, minutes, then hours`() {
        assertEquals("0s", MicraBleClient.formatAge(-5))
        assertEquals("45s", MicraBleClient.formatAge(45_999))
        assertEquals("12m", MicraBleClient.formatAge(12 * 60_000L + 30_000))
        assertEquals("3h05m", MicraBleClient.formatAge((3 * 60 + 5) * 60_000L))
        assertEquals("23h00m", MicraBleClient.formatAge(23 * 3_600_000L))
    }

    @Test
    fun `machine names match pylamarzocco prefixes`() {
        assertTrue(MicraBleClient.isMachineName("MICRA_ABC123"))
        assertTrue(MicraBleClient.isMachineName("GS3_XYZ"))
        assertFalse(MicraBleClient.isMachineName("PYXIS-1234"))
        assertFalse(MicraBleClient.isMachineName(null))
    }
}
