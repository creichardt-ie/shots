// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import com.coffeeapp.droid.ble.MicraProtocol.MachineMode
import com.coffeeapp.droid.ble.MicraProtocol.ReadSetting
import com.coffeeapp.droid.ble.MicraProtocol.SmartStandbyMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-value tests for the Micra BLE protocol.
 *
 * Every expected hex string was produced by driving pylamarzocco's real
 * client against a fake Bleak client (the generator, `tools/ble_golden.py`,
 * is archived outside the project). Never edit the hex here by hand.
 */
class MicraProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun reply(s: String) = s.toByteArray(Charsets.UTF_8)

    // --- Encoding: golden bytes ---------------------------------------------

    @Test
    fun `auth is the bare token without NUL`() {
        assertEquals("544f4b454e2d616263313233", hex(MicraProtocol.encodeAuth("TOKEN-abc123")))
    }

    @Test
    fun `power on matches pylamarzocco`() {
        assertEquals(
            "7b226e616d65223a224d616368696e654368616e67654d6f6465222c22706172616d65746572223a7b226d6f6465223a2242726577696e674d6f6465227d7d00",
            hex(MicraProtocol.encodePower(true)),
        )
    }

    @Test
    fun `power off matches pylamarzocco`() {
        assertEquals(
            "7b226e616d65223a224d616368696e654368616e67654d6f6465222c22706172616d65746572223a7b226d6f6465223a225374616e644279227d7d00",
            hex(MicraProtocol.encodePower(false)),
        )
    }

    @Test
    fun `steam on matches pylamarzocco`() {
        assertEquals(
            "7b226e616d65223a2253657474696e67426f696c6572456e61626c65222c22706172616d65746572223a7b226964656e746966696572223a22537465616d426f696c6572222c227374617465223a747275657d7d00",
            hex(MicraProtocol.encodeSteamEnabled(true)),
        )
    }

    @Test
    fun `steam off matches pylamarzocco`() {
        assertEquals(
            "7b226e616d65223a2253657474696e67426f696c6572456e61626c65222c22706172616d65746572223a7b226964656e746966696572223a22537465616d426f696c6572222c227374617465223a66616c73657d7d00",
            hex(MicraProtocol.encodeSteamEnabled(false)),
        )
    }

    @Test
    fun `coffee 93_5 encodes as a Python float`() {
        assertEquals(
            "7b226e616d65223a2253657474696e67426f696c6572546172676574222c22706172616d65746572223a7b226964656e746966696572223a22436f66666565426f696c657231222c2276616c7565223a39332e357d7d00",
            hex(MicraProtocol.encodeCoffeeTarget(93.5)),
        )
    }

    @Test
    fun `coffee 93_0 keeps its decimal point like a Python float`() {
        // Python's set_temp(93.0) sends "93.0", not "93" — pin that.
        assertEquals(
            "7b226e616d65223a2253657474696e67426f696c6572546172676574222c22706172616d65746572223a7b226964656e746966696572223a22436f66666565426f696c657231222c2276616c7565223a39332e307d7d00",
            hex(MicraProtocol.encodeCoffeeTarget(93.0)),
        )
    }

    @Test
    fun `steam 128 encodes as an int`() {
        assertEquals(
            "7b226e616d65223a2253657474696e67426f696c6572546172676574222c22706172616d65746572223a7b226964656e746966696572223a22537465616d426f696c6572222c2276616c7565223a3132387d7d00",
            hex(MicraProtocol.encodeSteamTarget(128)),
        )
    }

    @Test
    fun `smart standby matches pylamarzocco`() {
        assertEquals(
            "7b226e616d65223a2253657474696e67536d6172745374616e646279222c22706172616d65746572223a7b226d696e75746573223a33302c226d6f6465223a224c61737442726577696e67222c22656e61626c6564223a747275657d7d00",
            hex(MicraProtocol.encodeSmartStandby(true, SmartStandbyMode.LAST_BREWING, 30)),
        )
    }

    @Test
    fun `read requests are NUL-terminated setting names`() {
        assertEquals("6d616368696e654d6f646500", hex(MicraProtocol.encodeRead(ReadSetting.MACHINE_MODE)))
        assertEquals("626f696c65727300", hex(MicraProtocol.encodeRead(ReadSetting.BOILERS)))
        assertEquals("74616e6b53746174757300", hex(MicraProtocol.encodeRead(ReadSetting.TANK_STATUS)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `coffee target refuses NaN`() {
        MicraProtocol.encodeCoffeeTarget(Double.NaN)
    }

    // --- Parsing ------------------------------------------------------------

    @Test
    fun `machine mode parses every mode`() {
        assertEquals(MachineMode.BREWING, MicraProtocol.parseMachineMode(reply("\"BrewingMode\"")))
        assertEquals(MachineMode.STANDBY, MicraProtocol.parseMachineMode(reply("\"StandBy\"")))
        assertEquals(MachineMode.ECO, MicraProtocol.parseMachineMode(reply("\"EcoMode\"")))
        assertNull(MicraProtocol.parseMachineMode(reply("\"Sideways\"")))
        assertNull(MicraProtocol.parseMachineMode(reply("BrewingMode"))) // unquoted: not JSON
    }

    @Test
    fun `boilers parse the golden reply`() {
        val boilers = MicraProtocol.parseBoilers(reply(
            """[{"id":"SteamBoiler","isEnabled":true,"target":128,"current":127},""" +
                """{"id":"CoffeeBoiler1","isEnabled":true,"target":94,"current":93}]""",
        ))!!
        assertEquals(
            listOf(
                MicraProtocol.Boiler("SteamBoiler", true, 128.0, 127.0),
                MicraProtocol.Boiler("CoffeeBoiler1", true, 94.0, 93.0),
            ),
            boilers,
        )
    }

    @Test
    fun `boilers accept fractional temperatures`() {
        val b = MicraProtocol.parseBoilers(reply(
            """[{"id":"CoffeeBoiler1","isEnabled":false,"target":93.5,"current":92.8}]""",
        ))!!.single()
        assertEquals(93.5, b.target, 0.0)
        assertEquals(92.8, b.current, 0.0)
        assertFalse(b.isEnabled)
    }

    @Test
    fun `boilers reject a malformed entry`() {
        assertNull(MicraProtocol.parseBoilers(reply("""[{"id":"SteamBoiler","isEnabled":true}]""")))
        assertNull(MicraProtocol.parseBoilers(reply("""{"id":"SteamBoiler"}""")))
        assertNull(MicraProtocol.parseBoilers(reply("""[{"id":"SteamBoiler","isEnabled":true,"target":"128","current":127}]""")))
    }

    @Test
    fun `tank status parses booleans, bare or quoted`() {
        assertEquals(true, MicraProtocol.parseTankStatus(reply("true")))
        assertEquals(false, MicraProtocol.parseTankStatus(reply("false")))
        // pylamarzocco 2.5.6 _parse_bool: the machine may send it as a string.
        assertEquals(true, MicraProtocol.parseTankStatus(reply("\"true\"")))
    }

    @Test
    fun `command status parses the golden reply`() {
        val s = MicraProtocol.parseCommandStatus(reply("""{"id":"x","message":"ok","status":"success"}"""))!!
        assertEquals(MicraProtocol.CommandStatus("x", "ok", "success"), s)
        assertTrue(s.isSuccess)
        assertFalse(MicraProtocol.CommandStatus("x", "busy", "error").isSuccess)
    }

    @Test
    fun `trailing NULs do not read as a bad token`() {
        assertEquals(MachineMode.STANDBY, MicraProtocol.parseMachineMode(reply("\"StandBy\"\u0000\u0000")))
    }

    // --- Bad-token garbage --------------------------------------------------

    @Test
    fun `garbage replies are not real answers`() {
        val garbage = listOf(
            ByteArray(0),
            byteArrayOf(0, 0, 0),
            byteArrayOf(0xFF.toByte(), 0x13, 0x80.toByte(), 0x7F),
            reply("machineMode"), // our own request echoed back
            reply("{\"id\":"),
        )
        for (g in garbage) {
            assertFalse("garbage ${hex(g)}", MicraProtocol.isValidReply(g))
            assertNull(MicraProtocol.parseMachineMode(g))
            assertNull(MicraProtocol.parseBoilers(g))
            assertNull(MicraProtocol.parseCommandStatus(g))
        }
        assertTrue(MicraProtocol.isValidReply(reply("\"BrewingMode\"")))
    }

    // --- Steam level mapping ------------------------------------------------

    @Test
    fun `steam levels round-trip through celsius`() {
        for (level in listOf("Level1", "Level2", "Level3")) {
            val c = MicraProtocol.steamLevelToCelsius(level)!!
            assertEquals(level, MicraProtocol.steamCelsiusToLevel(c.toDouble()))
        }
        assertEquals(128, MicraProtocol.steamLevelToCelsius("Level2"))
        assertNull(MicraProtocol.steamLevelToCelsius("Level4"))
    }

    @Test
    fun `odd steam targets snap to the nearest level`() {
        assertEquals("Level1", MicraProtocol.steamCelsiusToLevel(120.0))
        assertEquals("Level2", MicraProtocol.steamCelsiusToLevel(128.6))
        assertEquals("Level3", MicraProtocol.steamCelsiusToLevel(135.0))
    }

    // --- pylamarzocco 2.5.6 additions ------------------------------------

    @Test
    fun `a reply is cut at its first NUL`() {
        // Anything after the terminator is not part of the answer.
        val r = reply("\"BrewingMode\"") + byteArrayOf(0) + reply("junk")
        assertEquals(MicraProtocol.MachineMode.BREWING, MicraProtocol.parseMachineMode(r))
    }

    @Test
    fun `the shot counter and new names are known`() {
        assertEquals("0e0b7847-e12b-09a8-b04b-8e0922a9abab", MicraProtocol.SHOT_COUNTER_CHAR)
        assertTrue("LINEA" in MicraProtocol.MACHINE_NAME_PREFIXES)
        assertArrayEquals(reply("isPlumbedIn") + byteArrayOf(0), MicraProtocol.encodeRead(MicraProtocol.ReadSetting.IS_PLUMBED_IN))
    }
}
