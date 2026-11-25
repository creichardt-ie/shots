// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Shot and bean queries.
 *
 * Room returns a Flow, so the history screen
 * updates itself when a shot is saved rather than polling for it.
 */
@Dao
interface ShotDao {

    @Insert
    suspend fun insert(shot: ShotEntity): Long

    /** Newest first, with the bean name joined in. */
    @Query(
        """
        SELECT s.id, s.timestamp, s.duration_seconds, s.duration_machine,
               s.yield_grams, s.target_grams, s.ratio, s.offset_grams,
               b.name AS bean_name, s.grind_setting,
               s.weight_at_stop, s.peak_flow_gps, s.grind_rec
        FROM shots s LEFT JOIN beans b ON s.bean_id = b.id
        ORDER BY s.timestamp DESC LIMIT :limit
        """,
    )
    fun recentShots(limit: Int = 100): Flow<List<ShotWithBean>>

    @Query("SELECT COUNT(*) FROM shots")
    fun countShots(): Flow<Int>

    @Query("DELETE FROM shots WHERE id IN (:ids)")
    suspend fun delete(ids: List<Int>)

    /**
     * Every stored timestamp, used to make importing idempotent.
     *
     * Each shot is one row with a whole-second timestamp, and two shots a second apart is not a thing that happens, so
     * the timestamp is a sound natural key for "have I already got this one".
     */
    @Query("SELECT timestamp FROM shots")
    suspend fun allTimestamps(): List<String>
}

@Dao
interface BeanDao {

    @Query("SELECT * FROM beans ORDER BY name COLLATE NOCASE")
    fun listBeans(): Flow<List<BeanEntity>>

    @Query("SELECT * FROM beans ORDER BY name COLLATE NOCASE")
    suspend fun listBeansOnce(): List<BeanEntity>

    @Query("SELECT * FROM beans WHERE is_current = 1 LIMIT 1")
    suspend fun currentBean(): BeanEntity?

    @Insert
    suspend fun insert(bean: BeanEntity): Long

    @Query(
        """
        UPDATE beans SET name = :name, grind_setting = :grindSetting, tasty = :tasty
        WHERE id = :id
        """,
    )
    suspend fun update(id: Int, name: String, grindSetting: Double?, tasty: Int?)

    @Query("UPDATE beans SET is_current = 0")
    suspend fun clearCurrent()

    @Query("UPDATE beans SET is_current = 1 WHERE id = :id")
    suspend fun markCurrent(id: Int)

    /**
     * Past shots keep their bean_id (now dangling), so the recentShots join
     * shows a blank bean for those rows rather than losing the shot.
     * Intentional: deleting a bean should not rewrite shot history.
     */
    @Query("DELETE FROM beans WHERE id = :id")
    suspend fun delete(id: Int)
}
