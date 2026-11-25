// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from aioacaia (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

/**
 * Acaia BLE wire protocol — frame codec.
 *
 * Encoding ported from the aioacaia Python driver (helpers.py), which in turn
 * derives from pyacaia; decoding from aioacaia 0.2.3 (parser.py and the frame
 * loop in scale.py), which added checksum validation, every frame in a
 * packet, resync after a bad frame and the record-chain walk.
 *
 * Pure: no Android, no IO, no coroutines, so the whole protocol is
 * unit-testable without a scale. Everything that touches a
 * BluetoothGatt lives in ScaleGattClient.
 *
 * Frame layout:
 *     EF DD <command> <length> <payload…> <cksum1> <cksum2>
 * where <length> counts itself and the payload but not the checksum; cksum1
 * sums the even-indexed bytes from <length> on and cksum2 the odd ones, both
 * truncated to a byte. Event frames (command 12) start the payload with a
 * message type.
 *
 * Kotlin Byte is signed, so every byte read here is masked with 0xFF before
 * arithmetic. Getting that wrong fails silently: a 190 g cup reads as a
 * negative weight rather than throwing.
 */
object AcaiaProtocol {

    const val HEADER1 = 0xEF
    const val HEADER2 = 0xDD

    /** New-style scales: Pyxis, Lunar 2021, Umbra. */
    const val DEFAULT_CHAR_ID = "49535343-8841-43f4-a8d4-ecbe34729bb3"
    const val NOTIFY_CHAR_ID = "49535343-1e4d-4bd9-ba61-23c647249616"

    /**
     * The Umbra: the new-style protocol on vendor characteristics. Tried when
     * the standard pair is absent and no override is configured.
     */
    const val UMBRA_WRITE_CHAR_ID = "0000fe41-8e22-4541-9d4c-21edae82ed19"
    const val UMBRA_NOTIFY_CHAR_ID = "0000fe42-8e22-4541-9d4c-21edae82ed19"

    /** Original Lunar: one characteristic serves both read and write. */
    const val OLD_STYLE_CHAR_ID = "00002a80-0000-1000-8000-00805f9b34fb"

    /** New-style scales want a heartbeat every second; old ones every five. */
    const val HEARTBEAT_INTERVAL_NEW_MS = 1_000L
    const val HEARTBEAT_INTERVAL_OLD_MS = 5_000L

    // --- Encoding -------------------------------------------------------

    /** Wrap a payload in the EF DD framing plus the two-byte checksum. */
    fun encode(msgType: Int, payload: IntArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = HEADER1.toByte()
        out[1] = HEADER2.toByte()
        out[2] = msgType.toByte()
        var cksum1 = 0
        var cksum2 = 0
        for (i in payload.indices) {
            val v = payload[i] and 0xFF
            out[3 + i] = v.toByte()
            if (i % 2 == 0) cksum1 += v else cksum2 += v
        }
        out[payload.size + 3] = (cksum1 and 0xFF).toByte()
        out[payload.size + 4] = (cksum2 and 0xFF).toByte()
        return out
    }

    /**
     * The identify/auth frame. New-style scales expect the ASCII digits
     * "012345678901234"; old ones fifteen hyphens. Sent once at connect and
     * again on every heartbeat for new-style scales — that repetition is what
     * keeps a Pyxis-family scale streaming.
     */
    fun encodeId(isNewStyle: Boolean): ByteArray {
        val payload = if (isNewStyle) {
            intArrayOf(
                0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
                0x38, 0x39, 0x30, 0x31, 0x32, 0x33, 0x34,
            )
        } else {
            IntArray(15) { 0x2D }
        }
        return encode(11, payload)
    }

    /**
     * Subscribe to the event stream. The payload is a list of (event, arg)
     * pairs — weight, battery, timer, key, setting — prefixed by its own
     * length. Without this the scale connects and then stays silent.
     */
    fun encodeNotificationRequest(): ByteArray {
        val payload = intArrayOf(
            0, 1, // weight
            1, 2, // battery
            2, 5, // timer (heartbeats between timer messages)
            3, 4, // key + setting
        )
        val framed = IntArray(payload.size + 1)
        framed[0] = payload.size + 1
        payload.copyInto(framed, destinationOffset = 1)
        return encode(12, framed)
    }

    val TARE: ByteArray = encode(4, intArrayOf(0))
    val HEARTBEAT: ByteArray = encode(0, intArrayOf(2, 0))
    val GET_SETTINGS: ByteArray = encode(6, IntArray(16) { 0 })

    // --- Decoding (aioacaia 0.2.3 parser.py) ----------------------------

    /** Outcome of decoding one frame. */
    sealed interface DecodeResult {
        /** A complete, checksummed frame, plus whatever bytes trailed it. */
        data class Decoded(val message: AcaiaMessage?, val remaining: ByteArray) : DecodeResult

        /** Fewer than six bytes from the header on: wait for the rest. */
        data class TooShort(val bytes: ByteArray) : DecodeResult

        /** Header found but the frame claims more bytes than arrived. */
        data class TooLong(val bytes: ByteArray) : DecodeResult

        /** A complete frame that is wrong: bad checksum, unknown type, short payload. */
        data class Malformed(val bytes: ByteArray, val reason: String) : DecodeResult
    }

    private const val EVENT_COMMAND = 12
    private const val SETTINGS_COMMAND = 8
    private const val MIN_FRAME_LENGTH = 6
    private const val FRAME_OVERHEAD = 5

    private const val TYPE_WEIGHT = 5
    private const val TYPE_TIMER = 7
    private const val TYPE_BUTTON = 8
    private const val TYPE_HEARTBEAT = 11

    /** Record tags that carry no message of their own. */
    private const val BATTERY_TAG = 6
    private const val ACK_TAG = 11

    /** Width of each record body in a [tag][body] chain. */
    private val RECORD_WIDTHS = mapOf(TYPE_WEIGHT to 6, BATTERY_TAG to 1, TYPE_TIMER to 3, ACK_TAG to 2)

    /** Button key codes, with the timer state the press implies. */
    private val BUTTON_TYPES: Map<Int, Pair<String, Boolean?>> = mapOf(
        0 to ("tare" to null),
        8 to ("start" to true),
        9 to ("reset" to null),
        10 to ("stop" to false),
    )

    private class MessageError(val reason: String) : Exception(reason)

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun findHeader(data: ByteArray, from: Int = 0): Int {
        for (i in from until data.size - 1) {
            if (u(data[i]) == HEADER1 && u(data[i + 1]) == HEADER2) return i
        }
        return -1
    }

    /** Decode the first frame in [data] (aioacaia `decode`). */
    fun decode(data: ByteArray): DecodeResult {
        val start = findHeader(data)
        if (start < 0 || data.size - start < MIN_FRAME_LENGTH) return DecodeResult.TooShort(data)

        val msgEnd = start + u(data[start + 3]) + FRAME_OVERHEAD
        if (msgEnd > data.size) return DecodeResult.TooLong(data)

        val frame = data.copyOfRange(start, msgEnd)
        val remaining = data.copyOfRange(msgEnd, data.size)
        if (!checksumOk(data, start, msgEnd)) {
            return DecodeResult.Malformed(frame, "checksum does not match")
        }
        return try {
            when (u(data[start + 2])) {
                EVENT_COMMAND -> {
                    if (u(data[start + 3]) < 2) throw MessageError("event payload is too short")
                    val msgType = u(data[start + 4])
                    val payload = data.copyOfRange(start + 5, msgEnd - 2)
                    DecodeResult.Decoded(parseMessage(msgType, payload), remaining)
                }
                // Settings are sliced from the length byte, so battery is index 1.
                SETTINGS_COMMAND -> DecodeResult.Decoded(
                    parseSettings(data.copyOfRange(start + 3, msgEnd - 2)),
                    remaining,
                )
                // A known-but-uninteresting notification (command acks, etc).
                else -> DecodeResult.Decoded(null, remaining)
            }
        } catch (e: MessageError) {
            DecodeResult.Malformed(frame, e.reason)
        }
    }

    private fun checksumOk(data: ByteArray, start: Int, msgEnd: Int): Boolean {
        var even = 0
        var odd = 0
        for (i in start + 3 until msgEnd - 2) {
            if ((i - start - 3) % 2 == 0) even += u(data[i]) else odd += u(data[i])
        }
        return u(data[msgEnd - 2]) == (even and 0xFF) && u(data[msgEnd - 1]) == (odd and 0xFF)
    }

    private fun parseMessage(msgType: Int, p: ByteArray): AcaiaMessage = when (msgType) {
        TYPE_WEIGHT -> parseWeight(p)
        TYPE_TIMER -> AcaiaMessage.Timer(decodeTime(p, 0))
        TYPE_HEARTBEAT -> parseHeartbeat(p)
        TYPE_BUTTON -> parseButton(p)
        else -> throw MessageError("unknown message type $msgType")
    }

    /** Fields collected from one [tag][body] record chain. */
    private class Records(val key: Int?, val weight: Float?, val time: Float?)

    /**
     * Walk a [tag][body] chain. An unknown tag makes everything behind it
     * unreadable, and a known tag cut short by the end of the frame is a
     * fragment - the walk stops at either rather than guessing at offsets.
     */
    private fun walkRecords(p: IntArray): Records {
        var key: Int? = null
        var weight: Float? = null
        var time: Float? = null
        var i = 0
        while (i < p.size) {
            val tag = p[i]
            if (tag == TYPE_BUTTON) {
                if (i + 2 > p.size) break
                key = p[i + 1]
                i += 2
                continue
            }
            val width = RECORD_WIDTHS[tag] ?: break
            if (i + 1 + width > p.size) break
            val body = ByteArray(width) { p[i + 1 + it].toByte() }
            when (tag) {
                TYPE_WEIGHT -> weight = decodeWeight(body, 0)
                TYPE_TIMER -> time = decodeTime(body, 0)
            }
            i += 1 + width
        }
        return Records(key, weight, time)
    }

    private fun ints(p: ByteArray, from: Int = 0): IntArray = IntArray(p.size - from) { u(p[from + it]) }

    private fun parseWeight(p: ByteArray): AcaiaMessage.Weight {
        val weight = decodeWeight(p, 0)
        val trailing = walkRecords(ints(p, 6))
        return AcaiaMessage.Weight(weight, trailing.time)
    }

    /**
     * Physical button presses: a key code, then the ordinary record chain -
     * the weight or timer captured at the moment of the press.
     */
    private fun parseButton(p: ByteArray): AcaiaMessage.Button {
        if (p.isEmpty()) throw MessageError("button payload is too short")
        val records = walkRecords(intArrayOf(TYPE_BUTTON) + ints(p))
        val (name, running) = BUTTON_TYPES[records.key] ?: return AcaiaMessage.Button("unknown")
        return AcaiaMessage.Button(name, weight = records.weight, time = records.time, timerRunning = running)
    }

    /**
     * A heartbeat reply: a 2-byte ack record, optionally wrapping one more
     * record (a button, weight or timer) identified by its tag at byte 2.
     */
    private fun parseHeartbeat(p: ByteArray): AcaiaMessage {
        if (p.size < 2) throw MessageError("heartbeat payload is too short")
        val packed = u(p[1])
        val ack = AcaiaMessage.Ack(ackId = u(p[0]), resultType = packed and 0x1F, resultValue = packed shr 5)
        if (p.size < 3) return ack
        val inner = p.copyOfRange(3, p.size)
        return when (u(p[2])) {
            TYPE_BUTTON -> parseButton(inner)
            TYPE_WEIGHT -> parseWeight(inner)
            TYPE_TIMER -> AcaiaMessage.Timer(decodeTime(inner, 0))
            else -> ack
        }
    }

    private fun parseSettings(p: ByteArray): AcaiaMessage.ScaleSettings {
        if (p.size < 7) throw MessageError("settings payload is too short")
        return AcaiaMessage.ScaleSettings(
            batteryLevel = u(p[1]) and 0x7F,
            grams = u(p[2]) != 5,
            autoOffMinutes = u(p[4]) * 5,
            beepOn = u(p[6]) == 1,
            timerRunning = (u(p[1]) and 0x80) != 0,
        )
    }

    /**
     * Weight is a little-endian u16 scaled by a decimal exponent, with the
     * sign carried as a flag bit rather than as two's complement. An unknown
     * unit byte makes the frame malformed: a garbage reading must never reach
     * the shot state machine.
     */
    internal fun decodeWeight(p: ByteArray, offset: Int): Float {
        if (p.size - offset < 6) throw MessageError("weight payload is too short")
        var value = ((u(p[offset + 1]) shl 8) + u(p[offset])).toFloat()
        value /= when (u(p[offset + 4])) {
            1 -> 10f
            2 -> 100f
            3 -> 1000f
            4 -> 10000f
            else -> throw MessageError("unknown weight unit ${u(p[offset + 4])}")
        }
        if ((u(p[offset + 5]) and 0x02) == 0x02) value = -value
        return value
    }

    internal fun decodeTime(p: ByteArray, offset: Int): Float {
        if (p.size - offset < 3) throw MessageError("timer payload is too short")
        return u(p[offset]) * 60f + u(p[offset + 1]) + u(p[offset + 2]) / 10f
    }

    // --- Frame stream (aioacaia 0.2.3 scale.py _extract_messages) --------

    /**
     * Turns notifications into frames. A packet can carry several frames, or
     * part of one (some scales split a frame across notifications at any
     * byte), and a corrupt length must not pin good frames behind it. Holds
     * the unfinished tail between packets; not thread-safe - one per link,
     * fed from one thread.
     */
    class FrameReader {
        private var pending: ByteArray? = null

        /** Bytes held back for the next packet; for tests. */
        val held: Int get() = pending?.size ?: 0

        fun reset() {
            pending = null
        }

        /** Every complete frame in [data] (with what was held back), decoded or malformed. */
        fun feed(data: ByteArray): List<DecodeResult> {
            val out = mutableListOf<DecodeResult>()
            var buf = (pending ?: ByteArray(0)) + data
            pending = null
            while (buf.isNotEmpty()) {
                val start = findHeader(buf)
                if (start < 0) {
                    // A lone EF at the end may be the first half of a header.
                    if (u(buf.last()) == HEADER1) pending = byteArrayOf(HEADER1.toByte())
                    return out
                }
                if (start > 0) buf = buf.copyOfRange(start, buf.size)
                when (val r = decode(buf)) {
                    is DecodeResult.TooShort -> {
                        pending = buf
                        return out
                    }
                    is DecodeResult.TooLong -> {
                        buf = skipToNextFrame(buf) ?: return out
                    }
                    is DecodeResult.Malformed -> {
                        out.add(r)
                        buf = buf.copyOfRange(2, buf.size)
                    }
                    is DecodeResult.Decoded -> {
                        out.add(r)
                        buf = r.remaining
                    }
                }
            }
            return out
        }

        /**
         * Past an over-long frame to the next header that decodes. None: the
         * frame may just be unfinished, so it is held for the next packet -
         * which is also what keeps header bytes inside a fragmented payload
         * from being mistaken for a new frame.
         */
        private fun skipToNextFrame(buf: ByteArray): ByteArray? {
            var next = findHeader(buf, 2)
            while (next >= 0) {
                if (decode(buf.copyOfRange(next, buf.size)) is DecodeResult.Decoded) break
                next = findHeader(buf, next + 2)
            }
            if (next < 0) {
                pending = buf
                return null
            }
            return buf.copyOfRange(next, buf.size)
        }
    }
}

/**
 * The Acaia side of [ScaleProtocol] (2.16.0): exactly what AcaiaScaleClient
 * sent and decoded before the client became brand-neutral - identify and
 * notification request after subscribing, the new-style 1 s keep-alive of
 * identify + heartbeat + settings poll (the settings answer is the only
 * source of the battery level), writes without response.
 */
class AcaiaScaleProtocol(
    private val isNewStyle: Boolean,
    notifyCharOverride: String = "",
    writeCharOverride: String = "",
) : ScaleProtocol {
    override val brand = "Acaia"

    /** Blank overrides keep the is_new_style defaults; an old Lunar uses one characteristic both ways. */
    override val writeCharUuid: java.util.UUID = java.util.UUID.fromString(
        writeCharOverride.ifBlank {
            if (isNewStyle) AcaiaProtocol.DEFAULT_CHAR_ID else AcaiaProtocol.OLD_STYLE_CHAR_ID
        },
    )
    override val notifyCharUuid: java.util.UUID = java.util.UUID.fromString(
        notifyCharOverride.ifBlank {
            if (isNewStyle) AcaiaProtocol.NOTIFY_CHAR_ID else AcaiaProtocol.OLD_STYLE_CHAR_ID
        },
    )

    /** A new-style scale without the standard pair is taken to be an Umbra; explicit overrides are never second-guessed. */
    override val alternateChars: List<Pair<java.util.UUID, java.util.UUID>> =
        if (isNewStyle && notifyCharOverride.isBlank() && writeCharOverride.isBlank()) {
            listOf(
                java.util.UUID.fromString(AcaiaProtocol.UMBRA_WRITE_CHAR_ID) to
                    java.util.UUID.fromString(AcaiaProtocol.UMBRA_NOTIFY_CHAR_ID),
            )
        } else {
            emptyList()
        }

    // The write characteristic is command-style; acknowledged writes would make
    // each one wait for a round-trip, which the 1 Hz heartbeat cannot absorb.
    override val preferWriteWithoutResponse = true

    // Identify, then ask for the event stream: without the notification
    // request the scale connects and reports nothing at all.
    override fun handshake() = listOf(AcaiaProtocol.encodeId(isNewStyle), AcaiaProtocol.encodeNotificationRequest())

    override val heartbeatIntervalMs =
        if (isNewStyle) AcaiaProtocol.HEARTBEAT_INTERVAL_NEW_MS else AcaiaProtocol.HEARTBEAT_INTERVAL_OLD_MS

    override fun heartbeat(): List<ByteArray> =
        if (isNewStyle) {
            listOf(AcaiaProtocol.encodeId(true), AcaiaProtocol.HEARTBEAT, AcaiaProtocol.GET_SETTINGS)
        } else {
            listOf(AcaiaProtocol.HEARTBEAT)
        }

    override fun tare(): ByteArray = AcaiaProtocol.TARE

    /** Frames span packets, so the reader keeps state; locked against [reset]. */
    private val frameReader = AcaiaProtocol.FrameReader()

    override fun decode(packet: ByteArray): List<ScaleDecode> {
        val results = synchronized(frameReader) { frameReader.feed(packet) }
        return results.mapNotNull { result ->
            when (result) {
                // A bare keep-alive (Ack) changes nothing but still counts as a
                // sample: the shot watchdog and the stream-gap re-seed read a
                // pause in samples as a lost scale, and a scale that reports
                // weight only when it changes would leave one at a steady weight.
                is AcaiaProtocol.DecodeResult.Decoded -> when (val m = result.message) {
                    is AcaiaMessage.Weight -> ScaleDecode.Update(weight = m.grams)
                    is AcaiaMessage.Button -> ScaleDecode.Update(weight = m.weight)
                    is AcaiaMessage.ScaleSettings -> ScaleDecode.Update(battery = m.batteryLevel)
                    else -> ScaleDecode.Update()
                }
                is AcaiaProtocol.DecodeResult.Malformed -> ScaleDecode.Malformed(result.reason)
                // FrameReader keeps these to itself.
                is AcaiaProtocol.DecodeResult.TooShort, is AcaiaProtocol.DecodeResult.TooLong -> null
            }
        }
    }

    override fun reset() {
        synchronized(frameReader) { frameReader.reset() }
    }
}
