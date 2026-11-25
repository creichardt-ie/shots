// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema. Table and column names match the retired Pi app's SQLite
 * schema, so older databases import by column name (see [DatabaseImporter];
 * a foreign file cannot simply be opened by Room). Column names are therefore
 * snake_case even though the Kotlin properties are camelCase.
 */

@Entity(
    tableName = "shots",
    indices = [Index(value = ["timestamp"], name = "idx_shots_timestamp")],
)
data class ShotEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,

    /** "yyyy-MM-dd HH:mm:ss", local time. */
    @ColumnInfo(name = "timestamp")
    val timestamp: String,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Double?,

    /** True pump-on time from the cloud; null when it was never captured. */
    @ColumnInfo(name = "duration_machine")
    val durationMachine: Double?,

    @ColumnInfo(name = "yield_grams")
    val yieldGrams: Double?,

    @ColumnInfo(name = "target_grams")
    val targetGrams: Double?,

    @ColumnInfo(name = "ratio")
    val ratio: Double?,

    /** The BBW offset in force for this shot. */
    @ColumnInfo(name = "offset_grams")
    val offsetGrams: Double?,

    @ColumnInfo(name = "bean_id")
    val beanId: Int?,

    @ColumnInfo(name = "grind_setting")
    val grindSetting: Double?,

    /** Weight when the stop was sent; drip = yield - this. */
    @ColumnInfo(name = "weight_at_stop")
    val weightAtStop: Double?,

    /** Max smoothed flow seen during the shot. */
    @ColumnInfo(name = "peak_flow_gps")
    val peakFlowGps: Double?,

    /** Grind verdict: "finer", "coarser" or "good". */
    @ColumnInfo(name = "grind_rec")
    val grindRec: String?,

    /**
     * Smoothed flow (1 s trailing window) when the BBW stop fired; null when
     * no stop was sent, and on every shot before schema v2. Collected to test
     * whether the drip after the stop follows the flow at that moment - peak
     * flow only half-explains it (r = 0.49 over 60 shots, 2026-10-17).
     */
    @ColumnInfo(name = "flow_at_stop_gps")
    val flowAtStopGps: Double? = null,
)

@Entity(tableName = "beans")
data class BeanEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "grind_setting")
    val grindSetting: Double?,

    /** 1 = liked, 0 = disliked, null = unrated. Stored as INTEGER, as in SQLite. */
    @ColumnInfo(name = "tasty")
    val tasty: Int?,

    @ColumnInfo(name = "is_current")
    val isCurrent: Int = 0,
)

/**
 * A shot joined to its bean name, for the history list.
 *
 * Mirrors the LEFT JOIN in recent_shots: a deleted bean leaves the shot in
 * place with a null name rather than dropping the row, because deleting a bean
 * should not rewrite shot history.
 */
data class ShotWithBean(
    val id: Int,
    val timestamp: String,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double?,
    @ColumnInfo(name = "duration_machine") val durationMachine: Double?,
    @ColumnInfo(name = "yield_grams") val yieldGrams: Double?,
    @ColumnInfo(name = "target_grams") val targetGrams: Double?,
    val ratio: Double?,
    @ColumnInfo(name = "offset_grams") val offsetGrams: Double?,
    @ColumnInfo(name = "bean_name") val beanName: String?,
    @ColumnInfo(name = "grind_setting") val grindSetting: Double?,
    @ColumnInfo(name = "weight_at_stop") val weightAtStop: Double?,
    @ColumnInfo(name = "peak_flow_gps") val peakFlowGps: Double?,
    @ColumnInfo(name = "grind_rec") val grindRec: String?,
)
