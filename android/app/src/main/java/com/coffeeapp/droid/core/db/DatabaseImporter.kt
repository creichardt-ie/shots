// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.coffeeapp.droid.core.log.FileLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports a coffee_history.db: the app's own backup from Documents/CoffeeApp,
 * or an older database file (the retired Pi app's, created by plain sqlite3).
 *
 * Deliberately NOT done with Room prepackaged-database support. Room validates
 * a prepackaged file against its own identity hash and schema version, and an
 * older file has neither - it was created by plain sqlite3 with user_version
 * 0. Reading the rows out with the framework SQLite API and inserting them
 * through Room sidesteps that entirely, and buys three things a file copy
 * could not:
 *
 *  - **Two schemas, one importer.** The database has grown columns over
 *    time (bean_id, grind_setting, weight_at_stop, peak_flow_gps, grind_rec)
 *    and gained a beans table. An older file has eight columns and no beans
 *    table at all. Every column here is read by name and tolerated as absent.
 *  - **Bean identity is remapped, not assumed.** Bean row ids will collide
 *    with whatever is already on the phone, so imported beans are matched or
 *    inserted first and each shot bean_id is rewritten to the new id. Copying
 *    the file would have silently re-pointed shots at the wrong beans.
 *  - **Idempotence.** Importing the same file twice adds nothing, so a
 *    re-import of a newer copy tops up rather than duplicates.
 */
class DatabaseImporter(private val repo: ShotRepository, private val db: CoffeeDatabase) {

    private companion object {
        const val TAG = "DatabaseImporter"
    }

    data class Result(
        val shotsImported: Int = 0,
        val shotsSkipped: Int = 0,
        val beansImported: Int = 0,
        val beansSkipped: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null

        fun summary(): String = when {
            error != null -> error
            shotsImported == 0 && beansImported == 0 ->
                "Nothing new to import - all $shotsSkipped shot(s) are already here."
            else -> buildString {
                append("Imported $shotsImported shot(s)")
                if (beansImported > 0) append(" and $beansImported bean(s)")
                if (shotsSkipped > 0) append("; skipped $shotsSkipped already present")
                append(".")
            }
        }
    }

    /**
     * Copy the picked file somewhere SQLite can open it, then import.
     *
     * A content:// URI is not a file path, and SQLite needs one, so the
     * document is streamed to the cache first. The copy is always deleted.
     */
    suspend fun importFrom(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        // Blocking streams and the framework SQLite API: off every thread that
        // matters, the main one and the runtime one alike.
        val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.db")
        val name = uri.lastPathSegment ?: uri.toString()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result(error = "Could not open the selected file.")
            Log.i(TAG, "Import started: $name (${temp.length() / 1024} KB)")
            importFile(temp)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Import of $name failed", e)
            Result(error = "Import failed: ${e.message}")
        } finally {
            temp.delete()
        }
    }

    private suspend fun importFile(file: File): Result {
        val source = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Not a readable SQLite database", e)
            return Result(error = "That file is not a readable database.")
        }

        source.use { src ->
            if (!hasTable(src, "shots")) {
                return Result(error = "No shots table - is this a CoffeeApp database?")
            }

            // One transaction: all or nothing, and far faster than a commit
            // per row. Beans first: shots reference them, and the id map is
            // needed before any shot can be written.
            val (beanResult, shotResult) = db.withTransaction {
                val beans = importBeans(src)
                beans to importShots(src, beans.first)
            }
            val (_, beansImported, beansSkipped) = beanResult
            val (shotsImported, shotsSkipped) = shotResult

            Log.i(
                TAG,
                "Import complete: $shotsImported shots (+$shotsSkipped skipped), " +
                    "$beansImported beans (+$beansSkipped skipped)",
            )
            return Result(shotsImported, shotsSkipped, beansImported, beansSkipped)
        }
    }

    // --- Beans ------------------------------------------------------------

    /** Returns (oldBeanId -> newBeanId, imported, skipped). */
    private suspend fun importBeans(src: SQLiteDatabase): Triple<Map<Int, Int>, Int, Int> {
        val idMap = mutableMapOf<Int, Int>()
        // An older database predates the bean journal entirely.
        if (!hasTable(src, "beans")) return Triple(idMap, 0, 0)

        val existing = repo.listBeansOnce()
            .associate { it.name.lowercase().trim() to it.id }
            .toMutableMap()
        var imported = 0
        var skipped = 0

        src.rawQuery("SELECT * FROM beans", null).use { c ->
            while (c.moveToNext()) {
                val oldId = c.optInt("id") ?: continue
                val name = c.optString("name")?.trim().orEmpty()
                if (name.isEmpty()) continue

                val match = existing[name.lowercase()]
                if (match != null) {
                    // Already have this bean by name; point the shots at it
                    // rather than creating a second copy.
                    idMap[oldId] = match
                    skipped++
                    continue
                }

                val newId = repo.addBean(
                    name = name,
                    grindSetting = c.optDouble("grind_setting") ?: 0.0,
                    tasty = c.optInt("tasty")?.let { it != 0 },
                )
                idMap[oldId] = newId
                // So a second same-name bean in the source maps here too.
                existing[name.lowercase()] = newId
                imported++
            }
        }
        return Triple(idMap, imported, skipped)
    }

    // --- Shots ------------------------------------------------------------

    /** Returns (imported, skipped). */
    private suspend fun importShots(
        src: SQLiteDatabase,
        beanIdMap: Map<Int, Int>,
    ): Pair<Int, Int> {
        val existingTimestamps = db.shotDao().allTimestamps().toHashSet()
        var imported = 0
        var skipped = 0

        src.rawQuery("SELECT * FROM shots ORDER BY timestamp ASC", null).use { c ->
            while (c.moveToNext()) {
                val timestamp = c.optString("timestamp") ?: continue
                if (!existingTimestamps.add(timestamp)) {
                    skipped++
                    continue
                }

                db.shotDao().insert(
                    ShotEntity(
                        timestamp = timestamp,
                        durationSeconds = c.optDouble("duration_seconds"),
                        durationMachine = c.optDouble("duration_machine"),
                        yieldGrams = c.optDouble("yield_grams"),
                        targetGrams = c.optDouble("target_grams"),
                        ratio = c.optDouble("ratio"),
                        offsetGrams = c.optDouble("offset_grams"),
                        // Remapped, never carried across as-is.
                        beanId = c.optInt("bean_id")?.let { beanIdMap[it] },
                        grindSetting = c.optDouble("grind_setting"),
                        weightAtStop = c.optDouble("weight_at_stop"),
                        peakFlowGps = c.optDouble("peak_flow_gps"),
                        grindRec = c.optString("grind_rec"),
                        // Only in v2 backups; optDouble reads a missing column as null.
                        flowAtStopGps = c.optDouble("flow_at_stop_gps"),
                    ),
                )
                imported++
            }
        }
        return Pair(imported, skipped)
    }

    // --- Defensive column access ------------------------------------------
    //
    // Every read goes through these, so a column the source database has never
    // heard of comes back null instead of throwing. That is what lets one
    // importer read both the 8-column original and the current 13-column
    // schema.

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(name),
        ).use { it.moveToFirst() }

    private fun Cursor.optDouble(column: String): Double? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getDouble(i)
    }

    private fun Cursor.optInt(column: String): Int? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }

    private fun Cursor.optString(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }
}
