// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ble

import java.util.UUID

/**
 * What differs between scale brands (2.16.0): where to write and listen, what
 * to send after subscribing and as a keep-alive, the tare command, and how a
 * notification decodes. Everything else - one GATT operation at a time, the
 * write queue, connect retries, the reconnect cooldown, drop detection - is
 * the same for every scale and lives in [ScaleGattClient].
 *
 * Pure: no Android, so each protocol is unit-testable without a scale.
 */
interface ScaleProtocol {
    /** For log lines: "Acaia", "Bookoo". */
    val brand: String

    val writeCharUuid: UUID
    val notifyCharUuid: UUID

    /**
     * Further (write, notify) pairs to try, in order, when [writeCharUuid] /
     * [notifyCharUuid] are not on the scale. Lets one brand cover models that
     * expose the same protocol on different characteristics without the user
     * having to know which one they own.
     */
    val alternateChars: List<Pair<UUID, UUID>> get() = emptyList()

    /**
     * The write type this scale is known to want. The client falls back to
     * the other one if the characteristic does not offer it, and logs that.
     */
    val preferWriteWithoutResponse: Boolean

    /** Frames written once, right after notifications are on. */
    fun handshake(): List<ByteArray>

    /** Keep-alive period; 0 means the scale needs none. */
    val heartbeatIntervalMs: Long

    /** Frames written every [heartbeatIntervalMs]. */
    fun heartbeat(): List<ByteArray>

    fun tare(): ByteArray

    /**
     * Decode one notification. Each [ScaleDecode.Update] becomes one weight
     * sample, so the sample rate is the scale's own message rate. May be
     * called on a binder thread while [reset] runs elsewhere: implementations
     * holding state must lock it.
     */
    fun decode(packet: ByteArray): List<ScaleDecode>

    /** Forget partial data between connections. */
    fun reset()
}

sealed interface ScaleDecode {
    /** A message from the scale; null fields leave the last value unchanged. */
    data class Update(val weight: Float? = null, val battery: Int? = null) : ScaleDecode

    /** Dropped as corrupt (bad checksum or layout); counted per connection. */
    data class Malformed(val reason: String) : ScaleDecode

    /** A valid message the app does not use; logged once per kind per connection. */
    data class Ignored(val what: String) : ScaleDecode
}

/** The scale brands the app speaks to; `ScaleConfig.model` holds the id. */
enum class ScaleModel(val id: String, val label: String) {
    ACAIA("acaia", "Acaia"),
    BOOKOO("bookoo", "Bookoo"),
    ;

    /** Whether an advertised name looks like this brand's scale. */
    fun matchesName(name: String): Boolean {
        val n = name.lowercase()
        return when (this) {
            // Acaia names its scales after the model.
            ACAIA -> ACAIA_NAMES.any { n.startsWith(it) }
            // Not documented by Bookoo; the open drivers expect a name starting
            // "BOOKOO" (aiobookoo) or containing "bookoo_sc" (Beanconqueror).
            BOOKOO -> "bookoo" in n
        }
    }

    companion object {
        // aioacaia's prefixes (ACAIA, PYXIS, LUNAR, PROCH) plus the Umbra and
        // Pearl names. Only used to label scan results.
        private val ACAIA_NAMES = listOf("acaia", "lunar", "pearl", "pyxis", "umbra", "proch")

        fun fromId(id: String): ScaleModel = entries.firstOrNull { it.id == id } ?: ACAIA

        /** The brand an advertised name belongs to, or null for anything else. */
        fun forName(name: String?): ScaleModel? = name?.let { n -> entries.firstOrNull { it.matchesName(n) } }
    }
}
