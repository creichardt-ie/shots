// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.core.cloud

import com.coffeeapp.droid.core.model.MachineSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure reading of the La Marzocco dashboard and settings responses.
 *
 * Split out of CloudClient so it can be tested against recorded JSON with no
 * network: the wire format is easy to get subtly wrong (`brewingStartTime`
 * is epoch MILLISECONDS on the wire, not pylamarzocco's re-serialised ISO
 * string), and a mistake there compiles, runs and silently never captures a
 * machine start time.
 */
object CloudParsing {

    fun widgetOutput(dashboard: JsonObject, code: String): JsonObject? =
        dashboard["widgets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["code"]?.jsonPrimitive?.content == code }
            ?.get("output")?.jsonObject

    /**
     * readyStartTime off a boiler widget, in epoch millis.
     *
     * Absent when the boiler is not at temperature, and JSON null rather than a
     * missing key when the machine has never reached it - "null".toLongOrNull()
     * folds both to null, the same trick the brew-start parse relies on.
     */
    fun readyStartTime(boiler: JsonObject?): Long? =
        boiler?.get("readyStartTime")?.jsonPrimitive?.content?.toLongOrNull()

    /** (machine status, brew start in epoch SECONDS or null) from a dashboard. */
    fun brewStart(dashboard: JsonObject): Pair<String?, Double?> {
        val status = widgetOutput(dashboard, "CMMachineStatus")
        val machineStatus = status?.get("status")?.jsonPrimitive?.content
        // The wire type is epoch milliseconds, NOT the ISO string that
        // pylamarzocco shows after re-serialising its model.
        val startMillis = status?.get("brewingStartTime")?.jsonPrimitive?.content?.toDoubleOrNull()
        return machineStatus to startMillis?.let { it / 1000.0 }
    }

    fun snapshotFrom(dashboard: JsonObject, settings: JsonObject?): MachineSnapshot {
        val status = widgetOutput(dashboard, "CMMachineStatus")
        val coffee = widgetOutput(dashboard, "CMCoffeeBoiler")
        val steam = widgetOutput(dashboard, "CMSteamBoilerLevel")

        val statusName = status?.get("status")?.jsonPrimitive?.content ?: "Unknown"

        // Anything unrecognised is treated as off. The power toggle issues a
        // real cloud command, so showing "ON" for an unknown status would be
        // actively misleading.
        val offStates = setOf("standby", "off", "unknown", "")

        val gatewayVersion = settings?.get("actualFirmwares")?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "Gateway" }
            ?.get("buildVersion")?.jsonPrimitive?.content
            ?: "N/A"

        return MachineSnapshot(
            status = statusName,
            powerOn = statusName.lowercase() !in offStates,
            name = dashboard["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "Linea Micra",
            coffeeTemp = coffee?.get("targetTemperature")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: 0.0,
            coffeeBoilerStatus = coffee?.get("status")?.jsonPrimitive?.content ?: "",
            coffeeReadySince = readyStartTime(coffee),
            steamLevel = steam?.get("targetLevel")?.jsonPrimitive?.content ?: "N/A",
            steamBoilerStatus = steam?.get("status")?.jsonPrimitive?.content ?: "",
            steamEnabled = steam?.get("enabled")?.jsonPrimitive?.booleanOrNull,
            steamReadySince = readyStartTime(steam),
            connected = dashboard["connected"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            gatewayVersion = gatewayVersion,
            lastSyncOk = true,
            lastError = null,
        )
    }
}
