// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from aioacaia (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

/**
 * Decoded messages from the scale (aioacaia 0.2.3 `messages.py`).
 *
 * aioacaia uses typed dataclasses per message since 0.2.0; a sealed
 * interface says the same thing in Kotlin.
 */
sealed interface AcaiaMessage {

    /**
     * A weight reading, in the scale current unit (grams unless changed).
     * [time] is the scale's own timer, which 2021+ scales append to every
     * weight reading as a trailing record; null when the reading has none.
     */
    data class Weight(val grams: Float, val time: Float? = null) : AcaiaMessage

    /** The scale built-in timer, in seconds. Unused by the shot logic. */
    data class Timer(val seconds: Float) : AcaiaMessage

    /**
     * A button pressed on the scale itself: `tare`, `start`, `stop`, `reset`
     * or `unknown`. [weight] and [time] carry the values captured at the press
     * when the frame includes them.
     */
    data class Button(
        val name: String,
        val weight: Float? = null,
        val time: Float? = null,
        val timerRunning: Boolean? = null,
    ) : AcaiaMessage

    /**
     * Periodic device status. This is where the battery level comes from.
     * [timerRunning] is the top bit of the battery byte (aioacaia 0.2.0:
     * verified on a 2021+ Lunar; it was read as a charging flag before).
     */
    data class ScaleSettings(
        val batteryLevel: Int,
        val grams: Boolean,
        val autoOffMinutes: Int,
        val beepOn: Boolean,
        val timerRunning: Boolean = false,
    ) : AcaiaMessage

    /**
     * A bare command acknowledgement or keep-alive. New-style scales send one
     * every second in reply to the heartbeat; it carries no state change, so
     * the client does not count it as a sample.
     */
    data class Ack(val ackId: Int, val resultType: Int, val resultValue: Int) : AcaiaMessage {
        companion object {
            /** result_value of a heartbeat answered by a live scale. */
            const val ALIVE_SUCCESS = 7
        }
    }

    /** A well-formed frame we do not act on. */
    data object Unknown : AcaiaMessage
}

/**
 * Mirrors aioacaia AcaiaDeviceState. Kept for the protocol tests; the client
 * now keeps only the battery level. Originally surfaced to
 * the UI through ScaleSnapshot.
 */
data class AcaiaDeviceState(
    val batteryLevel: Int,
    val grams: Boolean = true,
    val beeps: Boolean = true,
    val autoOffMinutes: Int = 0,
)
