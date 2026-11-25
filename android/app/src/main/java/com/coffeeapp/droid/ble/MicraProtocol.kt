// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.ble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * La Marzocco Micra BLE wire protocol — encoders and parsers.
 *
 * Ported from pylamarzocco 2.2.4 `clients/_bluetooth.py`, updated to 2.5.6
 * (shot-counter characteristic, `isPlumbedIn`, the `LINEA` name prefix,
 * string booleans, and replies cut at their first NUL). Pure: no Android, no
 * IO, no coroutines, so the protocol is unit-testable without a machine.
 * Everything that touches a BluetoothGatt lives in MicraBleClient.
 *
 * Every exchange is a write-with-response followed, where there is an answer,
 * by a read of the same characteristic:
 *  - settings: write `"<setting>\0"` to [READ_CHAR], read the JSON answer back;
 *  - commands: write compact JSON + `\0` to [WRITE_CHAR], read the status back;
 *  - auth: write the token to [AUTH_CHAR] as UTF-8 with **no** trailing NUL.
 *
 * The auth write always succeeds. A wrong token only shows up afterwards, as
 * reads that do not parse — so every parser here returns null rather than
 * throwing, and the client treats a null `machineMode` as a bad token.
 *
 * A complete port on purpose: a few members (smart standby, tank status,
 * [isValidReply]) are not used by the app yet, only pinned by
 * MicraProtocolTest against pylamarzocco, so they are ready and known-good if
 * auto-standby or a water-tank warning is ever wanted.
 */
object MicraProtocol {

    const val READ_CHAR = "0a0b7847-e12b-09a8-b04b-8e0922a9abab"
    const val WRITE_CHAR = "0b0b7847-e12b-09a8-b04b-8e0922a9abab"

    /** Present only while the machine is in pairing (configuration) mode. */
    const val TOKEN_CHAR = "0c0b7847-e12b-09a8-b04b-8e0922a9abab"
    const val AUTH_CHAR = "0d0b7847-e12b-09a8-b04b-8e0922a9abab"

    /**
     * Brewing events and telemetry the machine pushes once subscribed
     * (pylamarzocco 2.5.0); only on gateway firmware with the BLE shot timer.
     * See MicraShotCounter.
     */
    const val SHOT_COUNTER_CHAR = "0e0b7847-e12b-09a8-b04b-8e0922a9abab"

    val MACHINE_NAME_PREFIXES = listOf("MICRA", "MINI", "LINEA", "GS3")

    /** The `id`/`identifier` values the machine uses for its boilers. */
    const val COFFEE_BOILER = "CoffeeBoiler1"
    const val STEAM_BOILER = "SteamBoiler"

    enum class ReadSetting(val wire: String) {
        MACHINE_MODE("machineMode"),
        BOILERS("boilers"),
        TANK_STATUS("tankStatus"),
        MACHINE_CAPABILITIES("machineCapabilities"),
        SMART_STANDBY("smartStandBy"),
        IS_PLUMBED_IN("isPlumbedIn"),
    }

    enum class MachineMode(val wire: String) {
        BREWING("BrewingMode"),
        STANDBY("StandBy"),
        ECO("EcoMode");

        companion object {
            fun fromWire(s: String): MachineMode? = entries.firstOrNull { it.wire == s }
        }
    }

    enum class SmartStandbyMode(val wire: String) {
        LAST_BREWING("LastBrewing"),
        POWER_ON("PowerOn"),
    }

    /** One entry of the `boilers` answer. Temperatures in °C. */
    data class Boiler(
        val id: String,
        val isEnabled: Boolean,
        val target: Double,
        val current: Double,
    )

    /** The answer read back from [WRITE_CHAR] after a command. */
    data class CommandStatus(val id: String, val message: String, val status: String) {
        val isSuccess: Boolean get() = status == "success"
    }

    // --- Encoding -------------------------------------------------------

    /** Token bytes for [AUTH_CHAR]. Deliberately no NUL, unlike every other write. */
    fun encodeAuth(token: String): ByteArray = token.toByteArray(Charsets.UTF_8)

    /** Request for [READ_CHAR]; read the same characteristic for the answer. */
    fun encodeRead(setting: ReadSetting): ByteArray = nulTerminated(setting.wire)

    fun encodePower(on: Boolean): ByteArray = command("MachineChangeMode") {
        put("mode", if (on) MachineMode.BREWING.wire else MachineMode.STANDBY.wire)
    }

    fun encodeSteamEnabled(enabled: Boolean): ByteArray = command("SettingBoilerEnable") {
        put("identifier", STEAM_BOILER)
        put("state", enabled)
    }

    /**
     * Coffee target as a Python float would print it: `93.5`, `93.0`. Kotlin's
     * Double.toString agrees with Python's repr for one-decimal values, which
     * is all the coffee setting ever sends.
     */
    fun encodeCoffeeTarget(celsius: Double): ByteArray {
        require(celsius.isFinite()) { "Invalid coffee temperature: $celsius" }
        return command("SettingBoilerTarget") {
            put("identifier", COFFEE_BOILER)
            put("value", celsius)
        }
    }

    /** Steam target as an int (`128`), matching what the levels map to. */
    fun encodeSteamTarget(celsius: Int): ByteArray = command("SettingBoilerTarget") {
        put("identifier", STEAM_BOILER)
        put("value", celsius)
    }

    fun encodeSmartStandby(enabled: Boolean, mode: SmartStandbyMode, minutes: Int): ByteArray =
        command("SettingSmartStandby") {
            put("minutes", minutes)
            put("mode", mode.wire)
            put("enabled", enabled)
        }

    // Key order is part of the golden bytes: buildJsonObject keeps insertion
    // order, and Json.toString is compact like separators=(",", ":").
    private fun command(
        name: String,
        parameter: JsonObjectBuilder.() -> Unit,
    ): ByteArray {
        val obj = buildJsonObject {
            put("name", name)
            put("parameter", buildJsonObject(parameter))
        }
        return nulTerminated(obj.toString())
    }

    private fun nulTerminated(s: String): ByteArray = (s + "\u0000").toByteArray(Charsets.UTF_8)

    // --- Parsing --------------------------------------------------------

    /**
     * The reply as JSON, or null if it is not JSON — which is what a bad token
     * produces. Cut at the first NUL, as pylamarzocco 2.5 does ("responses
     * are NUL-terminated, ignore anything after it"), then trailing
     * whitespace trimmed: a padded reply must not read as a bad token.
     */
    fun parseJson(reply: ByteArray): JsonElement? {
        val text = reply.toString(Charsets.UTF_8).substringBefore('\u0000').trimEnd(' ', '\n', '\r', '\t')
        if (text.isEmpty()) return null
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return null
        }
        return element.takeIf { isStrict(it) }
    }

    /**
     * parseToJsonElement reads any unquoted token as a literal, so garbage like
     * `machineMode` (our own request echoed back) would parse. Python's
     * json.loads refuses it; do the same.
     */
    private fun isStrict(e: JsonElement): Boolean = when (e) {
        is JsonObject -> e.values.all(::isStrict)
        is JsonArray -> e.all(::isStrict)
        is JsonPrimitive -> e.isString || e === JsonNull ||
            e.content == "true" || e.content == "false" || e.content.toDoubleOrNull() != null
    }

    /** True if [reply] is a real answer rather than bad-token garbage. */
    fun isValidReply(reply: ByteArray): Boolean = parseJson(reply) != null

    fun parseMachineMode(reply: ByteArray): MachineMode? {
        val p = parseJson(reply) as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return MachineMode.fromWire(p.content)
    }

    /**
     * pylamarzocco types target/current as int; Apollo2 parses floats. Accept
     * both. Null if the reply is not an array or any entry is malformed.
     */
    fun parseBoilers(reply: ByteArray): List<Boiler>? {
        val arr = parseJson(reply) as? JsonArray ?: return null
        return arr.map { el ->
            val o = el as? JsonObject ?: return null
            Boiler(
                id = o.string("id") ?: return null,
                isEnabled = (o["isEnabled"] as? JsonPrimitive)?.booleanOrNull ?: return null,
                target = o.number("target") ?: return null,
                current = o.number("current") ?: return null,
            )
        }
    }

    /**
     * A JSON bool or a string (pylamarzocco 2.5 `_parse_bool`: "the machine
     * may send as JSON bool or string"). Null for anything else.
     */
    fun parseBool(reply: ByteArray): Boolean? {
        val p = parseJson(reply) as? JsonPrimitive ?: return null
        return when (p.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun parseTankStatus(reply: ByteArray): Boolean? = parseBool(reply)

    fun parseCommandStatus(reply: ByteArray): CommandStatus? {
        val o = parseJson(reply) as? JsonObject ?: return null
        return CommandStatus(
            id = o.string("id") ?: return null,
            message = o.string("message") ?: return null,
            status = o.string("status") ?: return null,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    // --- Steam level ↔ temperature --------------------------------------

    /**
     * The cloud sets steam as `Level1/2/3`; BLE sets a temperature. Apollo2's
     * mapping, confirmed on our Micra 2026-10-17: each level set through the
     * cloud read back over BLE as exactly these targets.
     */
    val STEAM_LEVEL_CELSIUS = linkedMapOf(
        "Level1" to 126,
        "Level2" to 128,
        "Level3" to 131,
    )

    fun steamLevelToCelsius(level: String): Int? = STEAM_LEVEL_CELSIUS[level]

    /** Nearest level to a BLE steam target, so an odd target still displays. */
    fun steamCelsiusToLevel(celsius: Double): String =
        STEAM_LEVEL_CELSIUS.minBy { (_, c) -> abs(c - celsius) }.key
}
