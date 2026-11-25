// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from aioacaia (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-value tests for the Acaia wire protocol.
 *
 * The encoding expectations were computed from the aioacaia Python
 * implementation. The decoding cases are aioacaia 0.2.3's own fixtures
 * (tests/fixtures/messages.py, several of them verbatim captures from a
 * Pearl and a 2021+ Lunar) with the values its test_parser.py asserts, all
 * hard-coded, so these lock the port to aioacaia.
 * Nothing in this file needs a scale, an Android device, or a connection.
 */
class AcaiaProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // --- Encoding -------------------------------------------------------

    @Test
    fun `tare frame matches the driver`() {
        // encode(4, [0]) -> EF DD 04 00 | cksum 00 00
        assertArrayEquals(bytes(0xEF, 0xDD, 0x04, 0x00, 0x00, 0x00), AcaiaProtocol.TARE)
    }

    @Test
    fun `heartbeat frame matches the driver`() {
        // encode(0, [2, 0]) -> cksum1 = 2 (even index), cksum2 = 0 (odd index)
        assertArrayEquals(
            bytes(0xEF, 0xDD, 0x00, 0x02, 0x00, 0x02, 0x00),
            AcaiaProtocol.HEARTBEAT,
        )
    }

    @Test
    fun `checksum splits payload across even and odd indices`() {
        // Even indices sum into cksum1, odd into cksum2 — not a single sum.
        val frame = AcaiaProtocol.encode(1, intArrayOf(0x10, 0x01, 0x20, 0x02))
        assertEquals(0x30.toByte(), frame[frame.size - 2]) // 0x10 + 0x20
        assertEquals(0x03.toByte(), frame[frame.size - 1]) // 0x01 + 0x02
    }

    @Test
    fun `checksum truncates to one byte`() {
        // Two payload bytes at even indices summing past 0xFF must wrap.
        val frame = AcaiaProtocol.encode(1, intArrayOf(0xFF, 0x00, 0x02, 0x00))
        assertEquals(0x01.toByte(), frame[frame.size - 2]) // 0xFF + 0x02 = 0x101
    }

    @Test
    fun `new style id frame is the ascii digit sequence`() {
        val frame = AcaiaProtocol.encodeId(isNewStyle = true)
        assertEquals(20, frame.size) // 5 framing + 15 payload
        assertEquals(0x0B.toByte(), frame[2]) // msg type 11
        assertEquals(0x30.toByte(), frame[3]) // first digit '0'
        assertEquals(0x34.toByte(), frame[17]) // last digit '4'
        assertEquals(0x9A.toByte(), frame[18]) // cksum1 = 410 & 0xFF
        assertEquals(0x6D.toByte(), frame[19]) // cksum2 = 365 & 0xFF
    }

    @Test
    fun `old style id frame is fifteen hyphens`() {
        val frame = AcaiaProtocol.encodeId(isNewStyle = false)
        assertEquals(20, frame.size)
        for (i in 3..17) assertEquals(0x2D.toByte(), frame[i])
    }

    @Test
    fun `notification request carries its own length prefix`() {
        // encode(12, [9, 0,1, 1,2, 2,5, 3,4])
        val frame = AcaiaProtocol.encodeNotificationRequest()
        assertArrayEquals(
            bytes(0xEF, 0xDD, 0x0C, 0x09, 0x00, 0x01, 0x01, 0x02, 0x02, 0x05, 0x03, 0x04, 0x15, 0x06),
            frame,
        )
    }

    // --- Decoding: aioacaia 0.2.3 fixtures ------------------------------

    private fun hex(s: String): ByteArray {
        val c = s.replace(" ", "")
        return ByteArray(c.length / 2) { c.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** Frame [payload] under [command] with its length byte and split checksum (fixtures `_frame`). */
    private fun rawFrame(command: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf((payload.size + 1).toByte()) + payload
        var even = 0
        var odd = 0
        body.forEachIndexed { i, b -> if (i % 2 == 0) even += b.toInt() and 0xFF else odd += b.toInt() and 0xFF }
        return bytes(0xEF, 0xDD, command) + body + bytes(even and 0xFF, odd and 0xFF)
    }

    /** An event frame (command 12) of [msgType] (fixtures `frame`). */
    private fun frame(msgType: Int, payload: ByteArray) = rawFrame(0x0C, byteArrayOf(msgType.toByte()) + payload)

    private val weight = bytes(0xDF, 0x06, 0x00, 0x00, 0x01, 0x00) // 175.9 g
    private val time = bytes(0x01, 0x1E, 0x05) // 90.5 s
    private val timeField = time + bytes(0x05)

    /** Real capture; 175.9 g with the timer at 0.2 s. */
    private val realWeight = hex("efdd0c0c05df060000010007000002f30d")
    private val settingsGrams = hex("efdd08095d020201000101000d60")

    private fun message(raw: ByteArray): AcaiaMessage? =
        (AcaiaProtocol.decode(raw) as AcaiaProtocol.DecodeResult.Decoded).message

    private fun weightOf(raw: ByteArray) = message(raw) as AcaiaMessage.Weight

    private fun corrupted(raw: ByteArray) =
        raw.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }

    @Test
    fun `decodes weights at every unit`() {
        assertEquals(175.9f, weightOf(realWeight).grams, 0.001f)
        assertEquals(-175.9f, weightOf(frame(5, bytes(0xDF, 0x06, 0x00, 0x00, 0x01, 0x02))).grams, 0.001f)
        assertEquals(17.59f, weightOf(frame(5, bytes(0xDF, 0x06, 0x00, 0x00, 0x02, 0x00))).grams, 0.0001f)
        assertEquals(1.759f, weightOf(frame(5, bytes(0xDF, 0x06, 0x00, 0x00, 0x03, 0x00))).grams, 0.0001f)
        assertEquals(0.1759f, weightOf(frame(5, bytes(0xDF, 0x06, 0x00, 0x00, 0x04, 0x00))).grams, 0.00001f)
    }

    @Test
    fun `decodes a high byte above 0x7F without sign extension`() {
        // 50000 at unit 2 = 500.00 g. 0xC3 is negative as a signed Kotlin
        // Byte - the masking bug that would make a heavy vessel read negative.
        assertEquals(500.0f, weightOf(frame(5, bytes(0x50, 0xC3, 0x00, 0x00, 0x02, 0x00))).grams, 0.001f)
    }

    @Test
    fun `decodes the coffee cup weight`() {
        // 189.7 g, the arm vessel auto-tare matching depends on.
        assertEquals(189.7f, weightOf(frame(5, bytes(0x69, 0x07, 0x00, 0x00, 0x01, 0x00))).grams, 0.001f)
    }

    @Test
    fun `an unknown weight unit makes the frame malformed`() {
        val r = AcaiaProtocol.decode(frame(5, bytes(0xDF, 0x06, 0x00, 0x00, 0x00, 0x00)))
        assertTrue((r as AcaiaProtocol.DecodeResult.Malformed).reason.contains("unit"))
    }

    @Test
    fun `a weight reports the scale timer that trails it`() {
        assertEquals(0.2f, weightOf(realWeight).time!!, 0.001f)
        val lunar = weightOf(hex("efdd0c0c05000000000100070008011c06"))
        assertEquals(0.0f, lunar.grams, 0.001f)
        assertEquals(8.1f, lunar.time!!, 0.001f)
        assertNull(weightOf(frame(5, bytes(0xDF, 0x06, 0x00, 0x00, 0x01, 0x02))).time)
        // A timer record cut short by the end of the frame is a fragment.
        assertNull(weightOf(frame(5, weight + bytes(0x07, 0x00))).time)
    }

    @Test
    fun `decodes a timer`() {
        assertEquals(90.5f, (message(frame(7, time)) as AcaiaMessage.Timer).seconds, 0.001f)
    }

    @Test
    fun `a bare heartbeat is an ack`() {
        assertEquals(AcaiaMessage.Ack(0, 0, AcaiaMessage.Ack.ALIVE_SUCCESS), message(frame(11, bytes(0x00, 0xE0))))
    }

    @Test
    fun `a heartbeat decodes the record it wraps`() {
        assertEquals(175.9f, weightOf(frame(11, bytes(0x00, 0x00, 0x05) + weight)).grams, 0.001f)
        assertEquals(90.5f, (message(frame(11, bytes(0x00, 0x00, 0x07) + time)) as AcaiaMessage.Timer).seconds, 0.001f)
        val both = weightOf(frame(11, bytes(0x00, 0x00, 0x05) + weight + bytes(0x07) + time))
        assertEquals(175.9f, both.grams, 0.001f)
        assertEquals(90.5f, both.time!!, 0.001f)
    }

    private fun assertButton(raw: ByteArray, name: String, running: Boolean?, t: Float?, w: Float?) {
        val b = message(raw) as AcaiaMessage.Button
        assertEquals(name, b.name)
        assertEquals(running, b.timerRunning)
        if (t == null) assertNull(b.time) else assertEquals(t, b.time!!, 0.001f)
        if (w == null) assertNull(b.weight) else assertEquals(w, b.weight!!, 0.001f)
    }

    @Test
    fun `decodes every button layout`() {
        assertButton(frame(8, bytes(0, 5) + weight), "tare", null, null, 175.9f)
        assertButton(frame(8, bytes(8, 5) + weight), "start", true, null, 175.9f)
        assertButton(frame(8, bytes(8)), "start", true, null, null)
        assertButton(frame(8, bytes(10, 7) + timeField + weight), "stop", false, 90.5f, 175.9f)
        assertButton(frame(8, bytes(10, 7) + time), "stop", false, 90.5f, null)
        assertButton(frame(8, bytes(10, 13)), "stop", false, null, null)
        assertButton(frame(8, bytes(9, 7) + timeField + weight), "reset", null, 90.5f, 175.9f)
        assertButton(frame(8, bytes(9, 7) + time), "reset", null, 90.5f, null)
        assertButton(frame(8, bytes(9, 12)), "reset", null, null, null)
        assertButton(frame(8, bytes(10, 5) + weight), "stop", false, null, 175.9f)
        assertButton(frame(8, bytes(9, 5) + weight), "reset", null, null, 175.9f)
        assertButton(frame(8, bytes(7, 7)), "unknown", null, null, null)
    }

    @Test
    fun `decodes buttons captured from real scales`() {
        // Pearl: a stop with weight then time. Reading the weight record as a
        // time once turned 55.1 g into "2342 s".
        assertButton(frame(8, hex("0a05270200000101 07000907")), "stop", false, 9.7f, 55.1f)
        assertButton(frame(8, hex("080b00e005250200000101")), "start", true, null, 54.9f)
        assertButton(frame(8, hex("090b00e005000000000101")), "reset", null, null, 0.0f)
        assertButton(frame(11, hex("00e0080805000000000101")), "start", true, null, 0.0f)
        // 2021+ scale: start and reset are a bare key code.
        assertButton(frame(8, hex("08")), "start", true, null, null)
        assertButton(frame(8, hex("0a0500000000010107000a08")), "stop", false, 10.8f, 0.0f)
        assertButton(frame(8, hex("09")), "reset", null, null, null)
    }

    @Test
    fun `decodes settings and the timer-running bit`() {
        val s = message(settingsGrams) as AcaiaMessage.ScaleSettings
        assertEquals(93, s.batteryLevel)
        assertTrue(s.grams)
        assertEquals(5, s.autoOffMinutes)
        assertTrue(s.beepOn)
        val ounces = message(rawFrame(0x08, hex("5d05020100010100"))) as AcaiaMessage.ScaleSettings
        assertFalse(ounces.grams)
        val stopped = message(hex("efdd08094a020101000101010e4c")) as AcaiaMessage.ScaleSettings
        val running = message(hex("efdd0809ca020101000101010ecc")) as AcaiaMessage.ScaleSettings
        assertEquals(74, stopped.batteryLevel)
        assertEquals(74, running.batteryLevel)
        assertFalse(stopped.timerRunning)
        assertTrue(running.timerRunning)
    }

    // --- Framing ---------------------------------------------------------

    @Test
    fun `a bad checksum is malformed`() {
        assertTrue(AcaiaProtocol.decode(corrupted(realWeight)) is AcaiaProtocol.DecodeResult.Malformed)
    }

    @Test
    fun `unknown types and short payloads are malformed`() {
        assertTrue(AcaiaProtocol.decode(frame(99, bytes(0, 0))) is AcaiaProtocol.DecodeResult.Malformed)
        assertTrue(AcaiaProtocol.decode(frame(5, bytes(0))) is AcaiaProtocol.DecodeResult.Malformed)
    }

    @Test
    fun `too short and too long`() {
        assertTrue(AcaiaProtocol.decode(hex("efdd0c")) is AcaiaProtocol.DecodeResult.TooShort)
        assertTrue(AcaiaProtocol.decode(hex("efdd0c200500")) is AcaiaProtocol.DecodeResult.TooLong)
    }

    @Test
    fun `an unrecognised command yields no message`() {
        assertNull(message(rawFrame(0x05, bytes(0x00))))
    }

    @Test
    fun `skips leading garbage and returns what trails the frame`() {
        assertEquals(175.9f, weightOf(bytes(0x00, 0x01, 0x02) + realWeight).grams, 0.001f)
        val r = AcaiaProtocol.decode(realWeight + settingsGrams) as AcaiaProtocol.DecodeResult.Decoded
        assertArrayEquals(settingsGrams, r.remaining)
    }

    @Test
    fun `a split header decodes once joined`() {
        assertEquals(175.9f, weightOf(hex("efdd0c") + hex("0c05df060000010007000002f30d")).grams, 0.001f)
    }

    // --- FrameReader (aioacaia scale.py) ----------------------------------

    private fun weights(results: List<AcaiaProtocol.DecodeResult>) =
        results.mapNotNull { ((it as? AcaiaProtocol.DecodeResult.Decoded)?.message as? AcaiaMessage.Weight)?.grams }

    @Test
    fun `reassembles a frame split at any byte`() {
        for (split in 1 until realWeight.size) {
            val reader = AcaiaProtocol.FrameReader()
            assertTrue("split $split", weights(reader.feed(realWeight.copyOfRange(0, split))).isEmpty())
            assertEquals("split $split", listOf(175.9f), weights(reader.feed(realWeight.copyOfRange(split, realWeight.size))))
        }
    }

    @Test
    fun `every frame in one packet is decoded`() {
        // The old decoder read the first frame and dropped the rest.
        val out = AcaiaProtocol.FrameReader().feed(realWeight + settingsGrams)
        assertEquals(2, out.size)
        assertEquals(listOf(175.9f), weights(out))
        assertEquals(93, ((out[1] as AcaiaProtocol.DecodeResult.Decoded).message as AcaiaMessage.ScaleSettings).batteryLevel)
    }

    @Test
    fun `a corrupt length does not pin a good frame behind it`() {
        val reader = AcaiaProtocol.FrameReader()
        assertEquals(listOf(175.9f), weights(reader.feed(hex("efdd0c200500") + realWeight)))
        assertEquals(0, reader.held)
    }

    @Test
    fun `a header inside a fragmented payload is not a new frame`() {
        val raw = frame(5, bytes(0xEF, 0xDD, 0x00, 0x00, 0x01, 0x00))
        val reader = AcaiaProtocol.FrameReader()
        reader.feed(raw.copyOfRange(0, 7))
        assertEquals(listOf(5681.5f), weights(reader.feed(raw.copyOfRange(7, raw.size))))
    }

    @Test
    fun `a bad frame is reported and the next one still decodes`() {
        val out = AcaiaProtocol.FrameReader().feed(corrupted(realWeight) + realWeight)
        assertTrue(out.first() is AcaiaProtocol.DecodeResult.Malformed)
        assertEquals(listOf(175.9f), weights(out))
    }

    /**
     * 2.16.0 moved the Acaia specifics behind ScaleProtocol. This pins that the
     * shared client still sends what AcaiaScaleClient sent - identify and
     * notification request, then identify + heartbeat + settings every 1 s,
     * writes without response - and decodes a real frame the same way.
     */
    @Test
    fun `the Acaia scale protocol sends and decodes what the old client did`() {
        val p = AcaiaScaleProtocol(isNewStyle = true)
        assertEquals(java.util.UUID.fromString(AcaiaProtocol.DEFAULT_CHAR_ID), p.writeCharUuid)
        assertEquals(java.util.UUID.fromString(AcaiaProtocol.NOTIFY_CHAR_ID), p.notifyCharUuid)
        assertTrue(p.preferWriteWithoutResponse)
        assertEquals(
            listOf(AcaiaProtocol.encodeId(true), AcaiaProtocol.encodeNotificationRequest()).map { it.toList() },
            p.handshake().map { it.toList() },
        )
        assertEquals(1_000L, p.heartbeatIntervalMs)
        assertEquals(
            listOf(AcaiaProtocol.encodeId(true), AcaiaProtocol.HEARTBEAT, AcaiaProtocol.GET_SETTINGS).map { it.toList() },
            p.heartbeat().map { it.toList() },
        )
        assertEquals(AcaiaProtocol.TARE.toList(), p.tare().toList())
        assertEquals(listOf(ScaleDecode.Update(weight = 175.9f)), p.decode(realWeight))

        val old = AcaiaScaleProtocol(isNewStyle = false)
        assertEquals(java.util.UUID.fromString(AcaiaProtocol.OLD_STYLE_CHAR_ID), old.writeCharUuid)
        assertEquals(java.util.UUID.fromString(AcaiaProtocol.OLD_STYLE_CHAR_ID), old.notifyCharUuid)
        assertEquals(5_000L, old.heartbeatIntervalMs)
        assertEquals(listOf(AcaiaProtocol.HEARTBEAT.toList()), old.heartbeat().map { it.toList() })
    }

    @Test
    fun `a new-style scale without overrides falls back to the Umbra characteristics`() {
        val umbra = java.util.UUID.fromString("0000fe41-8e22-4541-9d4c-21edae82ed19") to
            java.util.UUID.fromString("0000fe42-8e22-4541-9d4c-21edae82ed19")
        val p = AcaiaScaleProtocol(isNewStyle = true)
        assertEquals(java.util.UUID.fromString(AcaiaProtocol.DEFAULT_CHAR_ID), p.writeCharUuid)
        assertEquals(java.util.UUID.fromString(AcaiaProtocol.NOTIFY_CHAR_ID), p.notifyCharUuid)
        assertEquals(listOf(umbra), p.alternateChars)
        // An old Lunar has no Umbra variant, and explicit overrides are final.
        assertTrue(AcaiaScaleProtocol(isNewStyle = false).alternateChars.isEmpty())
        assertTrue(
            AcaiaScaleProtocol(isNewStyle = true, AcaiaProtocol.UMBRA_NOTIFY_CHAR_ID, AcaiaProtocol.UMBRA_WRITE_CHAR_ID)
                .alternateChars.isEmpty(),
        )
    }
}
