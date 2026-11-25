// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.config

import android.content.Context
import com.coffeeapp.droid.core.log.FileLog as Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.Locale

private val Context.configDataStore by preferencesDataStore(name = "app_config")

/**
 * Persistence for AppConfig.
 *
 * DataStore writes atomically, so a kill mid-flush cannot corrupt the
 * config, which is most of why it is used here rather than a plain file.
 *
 * Secrets are deliberately NOT in this store - see [SecretsStore] - so this
 * whole blob stays safe to dump into a log or a bug report.
 *
 * Unknown keys are ignored on read. That is what lets a config
 * written by a newer build still load on an older one instead of throwing.
 */
class ConfigStore(private val context: Context) {

    private companion object {
        const val TAG = "ConfigStore"
        val CONFIG_KEY: Preferences.Key<String> = stringPreferencesKey("config_json")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Emits the current config, and again on every save. */
    val configFlow: Flow<AppConfig> = context.configDataStore.data.map { prefs ->
        decode(prefs[CONFIG_KEY])
    }

    suspend fun load(): AppConfig = configFlow.first()

    /**
     * The stored config, or null when nothing has ever been saved.
     *
     * The caller needs the difference. Inferring "first run" from a property
     * of the config itself - a blank scale MAC - misfires the moment a user
     * legitimately clears that field, and the seeding path then overwrites
     * every other setting they had saved.
     */
    suspend fun loadOrNull(): AppConfig? =
        context.configDataStore.data.first()[CONFIG_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.let { decode(it) }

    suspend fun save(config: AppConfig) {
        context.configDataStore.edit { prefs ->
            prefs[CONFIG_KEY] = json.encodeToString(AppConfig.serializer(), config)
        }
    }

    private fun decode(raw: String?): AppConfig {
        if (raw.isNullOrBlank()) return AppConfig()
        return try {
            json.decodeFromString(AppConfig.serializer(), raw)
        } catch (e: Exception) {
            // A corrupt or incompatible blob must not brick the app, nor be
            // lost: the first learned value saved after this overwrites it.
            // Kept as a file first, so it can be read back by hand, and the
            // app runs on the seed (vessels, scale) rather than bare defaults.
            keepUnreadable(raw)
            Log.e(TAG, "Stored config could not be parsed; running on the seed config", e)
            com.coffeeapp.droid.core.DefaultConfig.SEED
        }
    }

    /** The unparseable blob, once per content, in files/config_unreadable_<hash>.json. */
    private fun keepUnreadable(raw: String) {
        try {
            val file = java.io.File(context.filesDir, "config_unreadable_${Integer.toHexString(raw.hashCode())}.json")
            if (!file.exists()) {
                file.writeText(raw)
                Log.e(TAG, "Unreadable config kept in ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not keep the unreadable config", e)
        }
    }
}

/**
 * Per-field diff between two configs, for an audit trail in the log.
 *
 * Port of summarize_config_changes. Vessel changes are summarised by name,
 * weight and flags rather than dumped whole: the full objects would swamp the
 * line, and a flags-only edit used to log as an identical before/after pair.
 */
/**
 * name -> value from a flat data class's toString(), "X(a=1, b=two)". Only
 * for sections whose fields are numbers, booleans and simple strings.
 */
private fun fieldsOf(dataClass: Any): Map<String, String> =
    dataClass.toString().substringAfter('(').removeSuffix(")")
        .split(", ")
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

fun summarizeConfigChanges(old: AppConfig, new: AppConfig): List<String> {
    val changes = mutableListOf<String>()

    fun <T> diff(section: String, field: String, a: T, b: T) {
        if (a != b) changes.add("$section.$field $a->$b")
    }

    diff("scale", "macAddress", old.scale.macAddress, new.scale.macAddress)
    diff("scale", "model", old.scale.model, new.scale.model)
    diff("scale", "isNewStyle", old.scale.isNewStyle, new.scale.isNewStyle)
    diff("scale", "notifyCharUuid", old.scale.notifyCharUuid, new.scale.notifyCharUuid)
    diff("scale", "writeCharUuid", old.scale.writeCharUuid, new.scale.writeCharUuid)
    diff("scale", "idleDisconnectMinutes", old.scale.idleDisconnectMinutes, new.scale.idleDisconnectMinutes)
    diff(
        "scale", "idleActivityThresholdGrams",
        old.scale.idleActivityThresholdGrams, new.scale.idleActivityThresholdGrams,
    )

    diff("machine", "serial", old.machine.serial, new.machine.serial)
    diff("machine", "localControl", old.machine.localControl, new.machine.localControl)
    diff("machine", "bleAddress", old.machine.bleAddress, new.machine.bleAddress)
    diff(
        "machine", "powerRefreshDelaySeconds",
        old.machine.powerRefreshDelaySeconds, new.machine.powerRefreshDelaySeconds,
    )
    diff("machine", "coffeeTempOptions", old.machine.coffeeTempOptions, new.machine.coffeeTempOptions)

    // Field by field: a whole-section dump made the one changed value a
    // needle in a line of twenty.
    fun sectionDiff(section: String, a: Any, b: Any) {
        if (a == b) return
        val fa = fieldsOf(a)
        val fb = fieldsOf(b)
        for ((k, v) in fa) if (v != fb[k]) changes.add("$section.$k $v->${fb[k]}")
    }
    sectionDiff("shotLogic", old.shotLogic, new.shotLogic)
    sectionDiff("brewByWeight", old.brewByWeight, new.brewByWeight)
    sectionDiff("defaults", old.defaults, new.defaults)

    val oldAt = old.autoTare
    val newAt = new.autoTare
    diff("autoTare", "enabled", oldAt.enabled, newAt.enabled)
    diff("autoTare", "autoArm", oldAt.autoArm, newAt.autoArm)
    diff("autoTare", "toleranceGrams", oldAt.toleranceGrams, newAt.toleranceGrams)
    diff("autoTare", "stableSeconds", oldAt.stableSeconds, newAt.stableSeconds)
    diff("autoTare", "minRiseRateGps", oldAt.minRiseRateGps, newAt.minRiseRateGps)
    diff("autoTare", "stabilityBandGrams", oldAt.stabilityBandGrams, newAt.stabilityBandGrams)
    diff("autoTare", "tareCooldownSeconds", oldAt.tareCooldownSeconds, newAt.tareCooldownSeconds)
    diff("autoTare", "learningAlpha", oldAt.learningAlpha, newAt.learningAlpha)

    if (oldAt.vessels != newAt.vessels) {
        fun fmt(vs: List<Vessel>) = vs.joinToString(", ") { v ->
            buildString {
                append(String.format(Locale.US, "%s %.1fg", v.name, v.grams))
                if (v.arm) append(" arm")
                if (v.switchToPage.isNotEmpty()) append(" ->${v.switchToPage}")
            }
        }.ifEmpty { "none" }
        changes.add("autoTare.vessels [${fmt(oldAt.vessels)}]->[${fmt(newAt.vessels)}]")
    }

    return changes
}
