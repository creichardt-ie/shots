// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE fields were added to an existing stored blob. DefaultConfig seeds
 * the store only once, so an install from before them must decode with the
 * defaults (local control off) rather than fail or need a migration.
 */
class MachineConfigTest {

    // Same settings as ConfigStore's private instance.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a stored machine section from before BLE decodes with local control off`() {
        val old = """{"machine":{"serial":"MR000000","powerRefreshDelaySeconds":17.0}}"""
        val cfg = json.decodeFromString(AppConfig.serializer(), old)
        assertEquals("MR000000", cfg.machine.serial)
        assertFalse(cfg.machine.localControl)
        assertEquals("", cfg.machine.bleAddress)
    }

    @Test
    fun `BLE fields round-trip`() {
        val cfg = AppConfig(machine = MachineConfig(localControl = true, bleAddress = "AA:BB:CC:DD:EE:FF"))
        val back = json.decodeFromString(AppConfig.serializer(), json.encodeToString(AppConfig.serializer(), cfg))
        assertEquals(cfg.machine, back.machine)
    }

    @Test
    fun `a config from before the machine picture keeps the La Marzocco photo`() {
        val old = """{"defaults":{"targetYieldGrams":36.0,"colorScheme":"crema"}}"""
        val cfg = json.decodeFromString(AppConfig.serializer(), old)
        assertEquals("crema", cfg.defaults.colorScheme)
        assertEquals(MachinePicture.LA_MARZOCCO, MachinePicture.fromId(cfg.defaults.machinePicture))
        // An id this build does not know falls back the same way.
        assertEquals(MachinePicture.LA_MARZOCCO, MachinePicture.fromId("something-new"))
        assertEquals(MachinePicture.OWN, MachinePicture.fromId("own"))
        // And the change is logged like every other setting.
        val changed = cfg.copy(defaults = cfg.defaults.copy(machinePicture = "none"))
        assertTrue(summarizeConfigChanges(cfg, changed).contains("defaults.machinePicture lamarzocco->none"))
    }

    @Test
    fun `a config from before the scale choice stays on the Acaia`() {
        val old = """{"scale":{"macAddress":"AA:BB:CC:DD:EE:01","isNewStyle":true,"idleDisconnectMinutes":10.0}}"""
        val cfg = json.decodeFromString(AppConfig.serializer(), old)
        assertEquals("acaia", cfg.scale.model)
        assertEquals("AA:BB:CC:DD:EE:01", cfg.scale.macAddress)
        val changed = cfg.copy(scale = cfg.scale.copy(model = "bookoo"))
        assertTrue(summarizeConfigChanges(cfg, changed).contains("scale.model acaia->bookoo"))
    }

    @Test
    fun `changing a BLE field is logged`() {
        val a = AppConfig()
        val b = a.copy(machine = a.machine.copy(localControl = true, bleAddress = "AA:BB:CC:DD:EE:FF"))
        val changes = summarizeConfigChanges(a, b)
        assertTrue(changes.contains("machine.localControl false->true"))
        assertTrue(changes.contains("machine.bleAddress ->AA:BB:CC:DD:EE:FF"))
    }
}
