// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from the BOOKOO OpenSource protocol (MIT), Copyright (c) 2024 BooKoo.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import java.util.UUID

/**
 * Bookoo Themis Ultra BLE protocol (2.16.0), from the maker's published spec,
 * `BooKooCode/OpenSource/bookoo_ultra_scale/protocols.md` (MIT). The two open
 * drivers (aiobookoo, Beanconqueror) are GPL-3, so nothing here comes from
 * their code; they were read only for facts the spec leaves out (the sign
 * byte values, the advertised name).
 *
 * Pure: no Android, no IO. Everything that touches a BluetoothGatt lives in
 * [ScaleGattClient].
 *
 * Weight notification, 20 bytes, on [WEIGHT_CHAR] (0-based):
 *
 *     0-1    03 0B          product, type = weight
 *     2-4    milliseconds   scale timer, unsigned 24-bit big-endian
 *     5      unit           01 g, 02 oz (the weight is always in grams)
 *     6      weight sign    2D '-' negative, otherwise positive
 *     7-9    weight x 100   unsigned 24-bit big-endian
 *     10     flow sign
 *     11-12  flow x 100     g/s, unsigned 16-bit
 *     13     battery %
 *     14-15  standby        minutes x 10, unsigned 16-bit
 *     16     buzzer level
 *     17     flow smoothing 0 off, 1 on
 *     18     00
 *     19     checksum       XOR of bytes 0-18
 *
 * Commands, 6 bytes on [COMMAND_CHAR]: `03 0A cmd d1 d2 xor`, where xor is
 * the XOR of the five bytes before it. The full 24-bit weight and timer are
 * read on purpose: a driver reading two bytes wraps above 655.35 g and 65.5 s.
 */
object BookooProtocol {
    /** All on the Bluetooth base UUID (spec: 16-bit short forms). */
    val SERVICE: UUID = UUID.fromString("00000ffe-0000-1000-8000-00805f9b34fb")
    val WEIGHT_CHAR: UUID = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHAR: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")

    const val PRODUCT: Int = 0x03
    const val TYPE_WEIGHT: Int = 0x0B
    const val TYPE_COMMAND: Int = 0x0A
    const val WEIGHT_PACKET_SIZE = 20
    private const val MINUS: Int = 0x2D

    /** XOR of every byte in [from, until). */
    fun checksum(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var x = 0
        for (i in from until until) x = x xor (bytes[i].toInt() and 0xFF)
        return x
    }

    /** `03 0A cmd d1 d2 xor`. */
    fun command(cmd: Int, d1: Int = 0, d2: Int = 0): ByteArray {
        val b = byteArrayOf(PRODUCT.toByte(), TYPE_COMMAND.toByte(), cmd.toByte(), d1.toByte(), d2.toByte(), 0)
        b[5] = checksum(b, 0, 5).toByte()
        return b
    }

    /** `03 0A 01 00 00 08`; the spec and both open drivers agree on these bytes. */
    val TARE: ByteArray = command(0x01)

    /** Reset the auto-off countdown (firmware 4.0.1+): `03 0A 25 00 00 2C`. Not sent yet. */
    val KEEP_ALIVE: ByteArray = command(0x25)

    data class Reading(
        val grams: Float,
        val flowGps: Float,
        val battery: Int,
        val timerMs: Int,
        val unit: Int,
        val standbyMinutes: Float,
    )

    sealed interface Decoded {
        data class Weight(val reading: Reading) : Decoded

        /** Another message type (03 0D auto-mode event, 03 0F powder weight, ...): not used. */
        data class Other(val product: Int, val type: Int) : Decoded

        data class Malformed(val reason: String) : Decoded
    }

    /** One message. Several may share a notification; see [split]. */
    fun decode(p: ByteArray): Decoded {
        if (p.size < 2) return Decoded.Malformed("${p.size} bytes")
        val product = u8(p, 0)
        val type = u8(p, 1)
        if (product != PRODUCT || type != TYPE_WEIGHT) return Decoded.Other(product, type)
        if (p.size != WEIGHT_PACKET_SIZE) return Decoded.Malformed("weight packet of ${p.size} bytes")
        val sum = checksum(p, 0, WEIGHT_PACKET_SIZE - 1)
        if (sum != u8(p, WEIGHT_PACKET_SIZE - 1)) {
            return Decoded.Malformed("checksum %02x, expected %02x".format(u8(p, 19), sum))
        }
        val weight = u24(p, 7) / 100f * (if (u8(p, 6) == MINUS) -1 else 1)
        val flow = u16(p, 11) / 100f * (if (u8(p, 10) == MINUS) -1 else 1)
        return Decoded.Weight(
            Reading(
                grams = weight,
                flowGps = flow,
                battery = u8(p, 13),
                timerMs = u24(p, 2),
                unit = u8(p, 5),
                standbyMinutes = u16(p, 14) / 10f,
            ),
        )
    }

    /**
     * A notification cut into messages: weight packets are 20 bytes, so a
     * longer notification that starts with one is taken 20 at a time. Anything
     * else is passed whole (the spec documents no other lengths we use).
     */
    fun split(packet: ByteArray): List<ByteArray> {
        if (packet.size <= WEIGHT_PACKET_SIZE ||
            u8(packet, 0) != PRODUCT || u8(packet, 1) != TYPE_WEIGHT ||
            packet.size % WEIGHT_PACKET_SIZE != 0
        ) {
            return listOf(packet)
        }
        return (0 until packet.size step WEIGHT_PACKET_SIZE).map { packet.copyOfRange(it, it + WEIGHT_PACKET_SIZE) }
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF
    private fun u16(p: ByteArray, i: Int) = (u8(p, i) shl 8) or u8(p, i + 1)
    private fun u24(p: ByteArray, i: Int) = (u8(p, i) shl 16) or (u8(p, i + 1) shl 8) or u8(p, i + 2)
}

/**
 * The Bookoo side of [ScaleProtocol]: subscribe and listen. The spec asks for
 * no identification and no heartbeat; the app's idle disconnect lets the
 * scale reach its own auto-off as it does for the Acaia.
 */
class BookooScaleProtocol : ScaleProtocol {
    override val brand = "Bookoo"
    override val writeCharUuid: UUID = BookooProtocol.COMMAND_CHAR
    override val notifyCharUuid: UUID = BookooProtocol.WEIGHT_CHAR

    // The spec does not say; acknowledged writes are the safer first try,
    // and only tare is ever sent. The client logs what the scale offers.
    override val preferWriteWithoutResponse = false

    override fun handshake(): List<ByteArray> = emptyList()
    override val heartbeatIntervalMs = 0L
    override fun heartbeat(): List<ByteArray> = emptyList()
    override fun tare(): ByteArray = BookooProtocol.TARE

    // Stateless: every notification stands alone.
    override fun decode(packet: ByteArray): List<ScaleDecode> =
        BookooProtocol.split(packet).map { msg ->
            when (val d = BookooProtocol.decode(msg)) {
                is BookooProtocol.Decoded.Weight -> ScaleDecode.Update(weight = d.reading.grams, battery = d.reading.battery)
                is BookooProtocol.Decoded.Malformed -> ScaleDecode.Malformed(d.reason)
                is BookooProtocol.Decoded.Other -> ScaleDecode.Ignored("message %02X %02X".format(d.product, d.type))
            }
        }

    override fun reset() = Unit
}
