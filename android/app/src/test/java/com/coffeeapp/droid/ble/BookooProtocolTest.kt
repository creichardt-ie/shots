// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from the BOOKOO OpenSource protocol (MIT), Copyright (c) 2024 BooKoo.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bookoo Themis Ultra protocol against its published spec. No real
 * packets exist yet (the scale arrives 2026-11-26), so the weight packets are
 * built field by field from the spec's layout; the first captures from the
 * scale should be added here as fixtures.
 */
class BookooProtocolTest {

    private fun hex(s: String): ByteArray = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** A weight packet laid out as the Ultra spec describes, with its XOR checksum. */
    private fun weightPacket(
        grams: Double,
        flow: Double = 0.0,
        battery: Int = 80,
        timerMs: Int = 0,
        unit: Int = 1,
        standbyMinTimes10: Int = 100,
    ): ByteArray {
        val w = Math.round(kotlin.math.abs(grams) * 100).toInt()
        val f = Math.round(kotlin.math.abs(flow) * 100).toInt()
        val b = IntArray(20)
        b[0] = 0x03; b[1] = 0x0B
        b[2] = (timerMs shr 16) and 0xFF; b[3] = (timerMs shr 8) and 0xFF; b[4] = timerMs and 0xFF
        b[5] = unit
        b[6] = if (grams < 0) 0x2D else 0x2B
        b[7] = (w shr 16) and 0xFF; b[8] = (w shr 8) and 0xFF; b[9] = w and 0xFF
        b[10] = if (flow < 0) 0x2D else 0x2B
        b[11] = (f shr 8) and 0xFF; b[12] = f and 0xFF
        b[13] = battery
        b[14] = (standbyMinTimes10 shr 8) and 0xFF; b[15] = standbyMinTimes10 and 0xFF
        b[16] = 1; b[17] = 0; b[18] = 0
        var x = 0
        for (i in 0 until 19) x = x xor b[i]
        b[19] = x
        return ByteArray(20) { b[it].toByte() }
    }

    private fun reading(p: ByteArray) = (BookooProtocol.decode(p) as BookooProtocol.Decoded.Weight).reading

    @Test
    fun `commands carry the spec's bytes and XOR checksum`() {
        // Tare: given verbatim in the spec, and the same in both open drivers.
        assertArrayEquals(hex("03 0A 01 00 00 08"), BookooProtocol.TARE)
        // Reset auto-off countdown, from the spec's layout and checksum rule.
        assertArrayEquals(hex("03 0A 25 00 00 2C"), BookooProtocol.KEEP_ALIVE)
        // Auto-off 5 minutes: 03 0A 03 00 05 (05 ^ 0A).
        assertArrayEquals(hex("03 0A 03 00 05 0F"), BookooProtocol.command(0x03, 0x00, 0x05))
    }

    @Test
    fun `a weight packet decodes every field`() {
        val r = reading(weightPacket(grams = 18.52, flow = 2.35, battery = 87, timerMs = 12_345, standbyMinTimes10 = 150))
        assertEquals(18.52f, r.grams, 0.001f)
        assertEquals(2.35f, r.flowGps, 0.001f)
        assertEquals(87, r.battery)
        assertEquals(12_345, r.timerMs)
        assertEquals(1, r.unit)
        assertEquals(15.0f, r.standbyMinutes, 0.001f)
    }

    @Test
    fun `negative weight after a tare and lift`() {
        assertEquals(-113.8f, reading(weightPacket(grams = -113.8)).grams, 0.001f)
    }

    @Test
    fun `weight and timer use all 24 bits`() {
        // A two-byte read would wrap above 655.35 g and 65.5 s.
        val r = reading(weightPacket(grams = 1234.56, timerMs = 90_000))
        assertEquals(1234.56f, r.grams, 0.01f)
        assertEquals(90_000, r.timerMs)
    }

    @Test
    fun `a bad checksum or wrong length is malformed`() {
        val p = weightPacket(grams = 36.0)
        p[19] = (p[19].toInt() xor 0x01).toByte()
        assertTrue(BookooProtocol.decode(p) is BookooProtocol.Decoded.Malformed)
        assertTrue(BookooProtocol.decode(weightPacket(grams = 36.0).copyOf(19)) is BookooProtocol.Decoded.Malformed)
    }

    @Test
    fun `other message types are reported, not decoded as weight`() {
        // 03 0D is the Ultra's automatic-mode event.
        val d = BookooProtocol.decode(hex("03 0D 01 00 00 0F"))
        assertEquals(BookooProtocol.Decoded.Other(0x03, 0x0D), d)
        val out = BookooScaleProtocol().decode(hex("03 0D 01 00 00 0F"))
        assertEquals(listOf(ScaleDecode.Ignored("message 03 0D")), out)
    }

    @Test
    fun `the scale protocol turns each packet into one update`() {
        val proto = BookooScaleProtocol()
        assertEquals(listOf(ScaleDecode.Update(weight = 36.1f, battery = 64)), proto.decode(weightPacket(grams = 36.1, battery = 64)))
        // Two weight packets in one notification are two samples.
        val two = weightPacket(grams = 1.0) + weightPacket(grams = 1.2)
        assertEquals(2, proto.decode(two).count { it is ScaleDecode.Update })
        assertArrayEquals(BookooProtocol.TARE, proto.tare())
        assertEquals(0L, proto.heartbeatIntervalMs)
        assertTrue(proto.handshake().isEmpty())
    }

    @Test
    fun `scale names map to brands`() {
        assertEquals(ScaleModel.BOOKOO, ScaleModel.forName("BOOKOO_SC 12"))
        assertEquals(ScaleModel.BOOKOO, ScaleModel.forName("bookoo_sc"))
        assertEquals(ScaleModel.ACAIA, ScaleModel.forName("UMBRA123"))
        assertEquals(ScaleModel.ACAIA, ScaleModel.forName("LUNAR-ABC"))
        assertNull(ScaleModel.forName("LINEA_MICRA"))
        assertNull(ScaleModel.forName(null))
        assertEquals(ScaleModel.ACAIA, ScaleModel.fromId("unknown"))
        assertEquals(ScaleModel.BOOKOO, ScaleModel.fromId("bookoo"))
    }
}
