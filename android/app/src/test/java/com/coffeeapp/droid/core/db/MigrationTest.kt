// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A v1 database (as the app wrote it before flow_at_stop_gps) opens through
 * [CoffeeDatabase.MIGRATION_1_2] with its rows intact. Room checks the
 * migrated schema against the entities on open, so a migration that does not
 * match the entity fails here instead of crashing on the phone - there is
 * deliberately no destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @Test
    fun `v1 history migrates to v2 with every row kept`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration_test.db").apply { parentFile?.mkdirs(); delete() }

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `shots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` TEXT NOT NULL, `duration_seconds` REAL, `duration_machine` REAL, " +
                    "`yield_grams` REAL, `target_grams` REAL, `ratio` REAL, `offset_grams` REAL, " +
                    "`bean_id` INTEGER, `grind_setting` REAL, `weight_at_stop` REAL, " +
                    "`peak_flow_gps` REAL, `grind_rec` TEXT)",
            )
            db.execSQL("CREATE INDEX `idx_shots_timestamp` ON `shots` (`timestamp`)")
            db.execSQL(
                "CREATE TABLE `beans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `grind_setting` REAL, `tasty` INTEGER, `is_current` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO shots (timestamp, duration_seconds, yield_grams, target_grams) " +
                    "VALUES ('2026-10-20 09:52:43', 27.1, 36.1, 36.0)",
            )
            db.execSQL("INSERT INTO beans (name, grind_setting, is_current) VALUES ('Kenya AA', 8.5, 1)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, CoffeeDatabase::class.java, file.name)
            .addMigrations(CoffeeDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf("2026-10-20 09:52:43"), room.shotDao().allTimestamps())
            assertEquals("Kenya AA", room.beanDao().currentBean()?.name)
            room.openHelper.readableDatabase.query("SELECT flow_at_stop_gps FROM shots").use {
                it.moveToFirst()
                assertNull(if (it.isNull(0)) null else it.getDouble(0))
            }
            assertEquals(2, room.openHelper.readableDatabase.version)
        } finally {
            room.close()
        }
    }
}
