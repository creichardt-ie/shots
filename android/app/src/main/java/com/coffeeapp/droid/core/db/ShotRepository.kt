// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coffeeapp.droid.core.model.Bean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

@Database(entities = [ShotEntity::class, BeanEntity::class], version = 2, exportSchema = true)
abstract class CoffeeDatabase : RoomDatabase() {
    abstract fun shotDao(): ShotDao
    abstract fun beanDao(): BeanDao

    companion object {
        /**
         * v2 adds shots.flow_at_stop_gps. Nullable with no default, which is
         * exactly what the entity declares, so Room's schema check passes and
         * every existing row keeps its values with the new column null.
         *
         * There is deliberately no fallbackToDestructiveMigration: a missing
         * migration must crash, not silently wipe the shot history.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                com.coffeeapp.droid.core.log.FileLog.i(
                    "CoffeeDatabase",
                    "Migrating 1->2: adding shots.flow_at_stop_gps",
                )
                db.execSQL("ALTER TABLE shots ADD COLUMN flow_at_stop_gps REAL")
            }
        }

        fun build(context: Context): CoffeeDatabase = Room.databaseBuilder(
            context.applicationContext,
            CoffeeDatabase::class.java,
            "coffee_history.db",
        ).addMigrations(MIGRATION_1_2)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Which schema, and how much history, every launch.
                    try {
                        fun count(table: String) =
                            db.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else -1 }
                        com.coffeeapp.droid.core.log.FileLog.i(
                            "CoffeeDatabase",
                            "Opened coffee_history.db v${db.version} " +
                                "(${count("shots")} shots, ${count("beans")} beans)",
                        )
                    } catch (e: Exception) {
                        com.coffeeapp.droid.core.log.FileLog.d("CoffeeDatabase", "Open line failed", e)
                    }
                }
            })
            .build()
    }
}

/**
 * Persistence for shots and the bean journal.
 *
 * Room handles its own threading: every method is a suspend function on a
 * Room suspend DAO, which is main-safe, so callers (ShotService on the
 * runtime dispatcher included) need no dispatcher hop.
 */
class ShotRepository(private val db: CoffeeDatabase) : com.coffeeapp.droid.core.shot.ShotSink {

    private companion object {
        /** Same format as older databases, so imports de-duplicate by timestamp. */
        const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }

    private fun formatTimestamp(epochSeconds: Double): String =
        SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date((epochSeconds * 1000).toLong()))

    /** Two-decimal rounding for storage. */
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0

    override suspend fun saveShot(
        startTime: Double,
        duration: Double,
        yieldGrams: Double,
        target: Double,
        machineDuration: Double?,
        offsetGrams: Double?,
        beanId: Int?,
        grindSetting: Double?,
        weightAtStop: Double?,
        peakFlowGps: Double?,
        grindRec: String?,
        flowAtStopGps: Double?,
    ): Long =
        db.shotDao().insert(
            ShotEntity(
                timestamp = formatTimestamp(startTime),
                durationSeconds = round2(duration),
                durationMachine = machineDuration?.let { round2(it) },
                yieldGrams = round2(yieldGrams),
                targetGrams = target,
                ratio = if (target > 0) round2(yieldGrams / target) else 0.0,
                offsetGrams = offsetGrams,
                beanId = beanId,
                grindSetting = grindSetting,
                weightAtStop = weightAtStop?.let { round2(it) },
                peakFlowGps = peakFlowGps,
                grindRec = grindRec,
                flowAtStopGps = flowAtStopGps?.let { round2(it) },
            ),
        )

    fun recentShots(limit: Int = 100): Flow<List<ShotWithBean>> = db.shotDao().recentShots(limit)

    fun shotCount(): Flow<Int> = db.shotDao().countShots()

    suspend fun deleteShots(ids: List<Int>) {
        if (ids.isEmpty()) return
        db.shotDao().delete(ids)
    }

    // ------------------------------------------------------------- beans

    fun listBeans(): Flow<List<Bean>> =
        db.beanDao().listBeans().map { rows -> rows.map { it.toBean() } }

    suspend fun listBeansOnce(): List<Bean> = db.beanDao().listBeansOnce().map { it.toBean() }

    suspend fun currentBean(): Bean? = db.beanDao().currentBean()?.toBean()

    suspend fun addBean(name: String, grindSetting: Double, tasty: Boolean? = null): Int =
        db.beanDao().insert(
            BeanEntity(
                name = name.trim(),
                grindSetting = grindSetting,
                tasty = tasty.toDb(),
                isCurrent = 0,
            ),
        ).toInt()

    /**
     * Update the given fields.
     *
     * [tasty] is always written, never skipped: null is ambiguous (it means
     * both "unrated" and "leave alone"), so callers pass the bean full current
     * value rather than relying on a partial update.
     */
    suspend fun updateBean(
        beanId: Int,
        name: String,
        grindSetting: Double?,
        tasty: Boolean?,
    ) {
        db.beanDao().update(beanId, name.trim(), grindSetting, tasty.toDb())
    }

    /** Mark one bean current, clearing the rest. null clears all. */
    suspend fun setCurrentBean(beanId: Int?) {
        // One transaction: a failure between the two used to leave no
        // current bean, and shots were then saved without one.
        db.withTransaction {
            db.beanDao().clearCurrent()
            if (beanId != null) db.beanDao().markCurrent(beanId)
        }
    }

    suspend fun deleteBean(beanId: Int) = db.beanDao().delete(beanId)
}

private fun Boolean?.toDb(): Int? = this?.let { if (it) 1 else 0 }

private fun BeanEntity.toBean() = Bean(
    id = id,
    name = name,
    grindSetting = grindSetting ?: 0.0,
    tasty = tasty?.let { it != 0 },
    isCurrent = isCurrent != 0,
)
