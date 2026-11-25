// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud's dashboard and settings JSON, as the REST API sends it (shape
 * from the customer API; serial and token made up). The trap it pins:
 * `brewingStartTime` is epoch MILLISECONDS on the wire.
 */
class CloudParsingTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val brewing = obj(
        """
        {"name":"Micra","connected":true,"widgets":[
          {"code":"CMMachineStatus","output":{"status":"Brewing","brewingStartTime":1792655982093}},
          {"code":"CMCoffeeBoiler","output":{"status":"Ready","targetTemperature":94.0,"readyStartTime":1792655000000}},
          {"code":"CMSteamBoilerLevel","output":{"status":"HeatingUp","enabled":true,"targetLevel":"Level2","readyStartTime":null}}
        ]}
        """,
    )

    private val settings = obj(
        """
        {"bleAuthToken":"x","actualFirmwares":[
          {"type":"Machine","buildVersion":"v1.17"},
          {"type":"Gateway","buildVersion":"v7.4.2"}
        ]}
        """,
    )

    @Test
    fun `brew start is read as epoch milliseconds and returned in seconds`() {
        val (status, start) = CloudParsing.brewStart(brewing)
        assertEquals("Brewing", status)
        assertEquals(1792655982.093, start!!, 1e-6)
    }

    @Test
    fun `an ISO string start time is not mistaken for one`() {
        val iso = obj("""{"widgets":[{"code":"CMMachineStatus","output":{"status":"Brewing","brewingStartTime":"2026-10-22T08:59:42Z"}}]}""")
        assertNull(CloudParsing.brewStart(iso).second)
    }

    @Test
    fun `a dashboard folds into a snapshot`() {
        val s = CloudParsing.snapshotFrom(brewing, settings)
        assertEquals("Brewing", s.status)
        assertTrue(s.powerOn)
        assertTrue(s.connected)
        assertEquals("Micra", s.name)
        assertEquals(94.0, s.coffeeTemp, 0.0)
        assertEquals("Ready", s.coffeeBoilerStatus)
        assertEquals(1792655000000L, s.coffeeReadySince)
        assertEquals("Level2", s.steamLevel)
        assertEquals(true, s.steamEnabled)
        // JSON null and a missing key both fold to null.
        assertNull(s.steamReadySince)
        assertEquals("v7.4.2", s.gatewayVersion)
        assertTrue(s.lastSyncOk)
    }

    @Test
    fun `standby, off and unknown statuses read as off`() {
        for (status in listOf("StandBy", "Off", "Unknown", "")) {
            val d = obj("""{"widgets":[{"code":"CMMachineStatus","output":{"status":"$status"}}]}""")
            assertFalse(status, CloudParsing.snapshotFrom(d, null).powerOn)
        }
    }

    @Test
    fun `a sparse dashboard still parses`() {
        val s = CloudParsing.snapshotFrom(obj("""{"widgets":[]}"""), null)
        assertEquals("Unknown", s.status)
        assertEquals("Linea Micra", s.name)
        assertEquals("N/A", s.steamLevel)
        assertEquals("N/A", s.gatewayVersion)
        assertFalse(s.connected)
    }
}
