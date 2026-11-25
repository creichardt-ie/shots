// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.backup

import kotlinx.coroutines.sync.withLock
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.coffeeapp.droid.core.db.CoffeeDatabase
import com.coffeeapp.droid.core.log.FileLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mirrors the history database and the current log into Documents/CoffeeApp.
 *
 * Both live in app-private storage - Room in databases/, FileLog in
 * filesDir/logs - which the system deletes with the package. An uninstall is
 * not a rare event here: Android Studio does one whenever it cannot patch over
 * an existing install, and that is how the shot history was lost once already.
 * Anything under getExternalFilesDir would go the same way, so the copies have
 * to land in shared storage, which outlives the app.
 *
 * This is a one-way mirror, not a second home. Room needs a real file path and
 * cannot open a content:// URI, and FileLog rotates by renaming paths, which
 * has no equivalent over MediaStore - so both keep writing where they do now
 * and this copies out behind them. Restore is the existing importer, which
 * de-duplicates by timestamp and so can be run against a stale copy safely.
 *
 * Credentials are deliberately not mirrored: the MasterKey wrapping them lives
 * in the device keystore, so a copy would restore as undecryptable bytes.
 *
 * The database goes out as one dated file per day (coffee_history_<date>.db),
 * the newest [DB_GENERATIONS] kept. Each copy is a new item, pending until it
 * is complete, and only then replaces the day's previous one - an interrupted
 * copy can never leave the only backup truncated, which overwriting a single
 * file in place could.
 */
object DocumentsBackup {

    private const val TAG = "DocumentsBackup"

    /** Subfolder of the shared Documents dir. */
    const val FOLDER = "CoffeeApp"

    // Not const: Environment.DIRECTORY_DOCUMENTS is a non-final Java field.
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER/"
    private const val DB_NAME = "coffee_history.db"

    /** Dated database copies kept in the folder. */
    private const val DB_GENERATIONS = 3

    /** Matches FileLog's own retention, so the mirror ages like the source. */
    private const val RETENTION_DAYS = 365L

    data class Result(val ok: Boolean, val message: String)

    /**
     * Copy the database and current log out. Never throws: a failed backup
     * must not take down a shot save or app startup with it.
     *
     * [reason] is logged, so the log says what prompted each copy.
     */
    /**
     * One backup at a time. They are started from launch, every saved shot,
     * an import and the button, and two at once shared the snapshot file
     * (one deleting the other's mid-copy) and could write the same dated
     * name twice, leaving a "(1)" copy that pushed a real one out of the
     * newest three.
     */
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun run(context: Context, database: CoffeeDatabase, reason: String): Result =
        lock.withLock { runLocked(context, database, reason) }

    private suspend fun runLocked(context: Context, database: CoffeeDatabase, reason: String): Result =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val snapshot = File(context.cacheDir, "backup_snapshot.db")
            try {
                val db = context.getDatabasePath(DB_NAME)
                if (!db.exists()) {
                    return@withContext Result(false, "No database to back up yet")
                }
                makeSnapshot(database, db, snapshot)
                val dbName = datedDbName()
                writeDatabase(context, dbName, snapshot)
                val logs = Log.recentFiles.filter { it.exists() }
                logs.forEach { write(context, exportedLogName(it.name), "text/plain", it) }
                pruneLogs(context)
                pruneDatabases(context)
                Log.i(
                    TAG,
                    "Backed up $dbName (${snapshot.length() / 1024} KB) + ${logs.size} log file(s) " +
                        "in ${System.currentTimeMillis() - t0} ms ($reason)",
                )
                Result(true, "Saved $dbName to Documents/$FOLDER")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup to Documents/$FOLDER failed ($reason)", e)
                Result(false, e.message ?: "Backup failed")
            } finally {
                snapshot.delete()
            }
        }

    private fun datedDbName(): String =
        "coffee_history_" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".db"

    /**
     * A consistent copy of the database in one statement: VACUUM INTO writes
     * a complete, checkpointed database even while Room keeps writing. On
     * SQLite older than 3.27 (Android 10 and below) it falls back to a WAL
     * checkpoint and a file copy, which is consistent unless a write lands
     * mid-copy.
     */
    private fun makeSnapshot(database: CoffeeDatabase, db: File, snapshot: File) {
        snapshot.delete()
        try {
            database.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(snapshot.path))
            return
        } catch (e: Exception) {
            Log.d(TAG, "VACUUM INTO unavailable (${e.javaClass.simpleName}); checkpoint and copy instead")
        }
        checkpoint(database)
        db.copyTo(snapshot, overwrite = true)
    }

    /**
     * Fold the write-ahead log back into the database file first.
     *
     * Room runs in WAL mode, so shots saved since the last checkpoint are
     * still in coffee_history.db-wal. Copying the .db alone would produce a
     * backup that looks complete and quietly ends a few shots early - the
     * worst kind, because you only find out when you need it.
     */
    private fun checkpoint(database: CoffeeDatabase) {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
    }

    /**
     * applog_01sep_1322_59.log -> applog_01sep_1322_59.txt
     *
     * MediaStore enforces that the extension matches the MIME type and appends
     * one when it does not, which silently produced applog_*.log.txt. Naming
     * the copy for what it is beats letting the store correct us.
     */
    private fun exportedLogName(name: String) = name.replace(".log", "") + ".txt"

    /**
     * Write the database copy without ever truncating the day's existing one:
     * a new pending item first, then the old one goes, then the new one takes
     * its name and becomes visible.
     */
    private fun writeDatabase(context: Context, name: String, source: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            writeLegacy(context, name, source)
            return
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val previous = findExisting(context, name)
        val fresh = resolver.insert(
            collection,
            ContentValues().apply {
                // A different name while pending, so the store has no reason
                // to rename it "(1)" against the copy it is about to replace.
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.part")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: throw IllegalStateException("MediaStore would not create $name")
        try {
            resolver.openOutputStream(fresh, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open $name for writing")
        } catch (e: Exception) {
            resolver.delete(fresh, null, null)
            throw e
        }
        if (previous != null) resolver.delete(previous, null, null)
        resolver.update(
            fresh,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null,
        )
        // The store may still have renamed it (a same-name file another
        // install owns cannot be seen or replaced); say so.
        resolver.query(fresh, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.getString(0) != name) {
                Log.w(TAG, "Backup saved as ${c.getString(0)}, not $name (a file of that name is owned by another install)")
            }
        }
    }

    /**
     * Keep the newest [DB_GENERATIONS] dated copies, and drop the undated
     * coffee_history.db of older versions once a dated one exists, so Restore
     * does not offer two look-alikes. Only files this install owns are visible.
     */
    private fun pruneDatabases(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
                val dated = dir.listFiles { f -> f.name.startsWith("coffee_history_") && f.name.endsWith(".db") }
                    ?.sortedByDescending { it.name }.orEmpty()
                dated.drop(DB_GENERATIONS).forEach { it.delete() }
                if (dated.isNotEmpty()) File(dir, DB_NAME).delete()
                return
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val found = mutableListOf<Pair<String, Long>>()
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(RELATIVE_PATH),
                null,
            )?.use { c -> while (c.moveToNext()) found += c.getString(1) to c.getLong(0) }
            val dated = found.filter { it.first.startsWith("coffee_history_") && it.first.endsWith(".db") }
                .sortedByDescending { it.first }
            val drop = dated.drop(DB_GENERATIONS) +
                if (dated.isNotEmpty()) found.filter { it.first == DB_NAME } else emptyList()
            drop.forEach { (name, id) ->
                resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                Log.i(TAG, "Removed old backup $name")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Database backup prune failed", e)
        }
    }

    private fun write(context: Context, name: String, mime: String, source: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, name, mime, source)
        } else {
            writeLegacy(context, name, source)
        }
    }

    /**
     * Android 10+. No permission needed for our own files, and RELATIVE_PATH
     * creates the subfolder implicitly.
     *
     * IS_PENDING brackets the write so a copy interrupted halfway is never
     * visible as a finished backup - which matters when the previous good copy
     * is the thing being overwritten.
     */
    private fun writeViaMediaStore(context: Context, name: String, mime: String, source: File) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }

        val existing = findExisting(context, name)
        val uri = if (existing != null) {
            resolver.update(existing, pending, null, null)
            existing
        } else {
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IllegalStateException("MediaStore would not create $name")
        }

        // "wt" truncates, so a shorter file cannot leave a tail of the old one.
        resolver.openOutputStream(uri, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not open $name for writing")

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }

    private fun findExisting(context: Context, name: String): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(RELATIVE_PATH, name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    /**
     * Android 9 and below, where shared storage is still a filesystem.
     *
     * minSdk is 26, so this path is real but rarely taken. It needs the legacy
     * permission, which the manifest declares with maxSdkVersion="28"; without
     * the grant it fails loudly rather than writing nowhere.
     */
    private fun writeLegacy(context: Context, name: String, source: File) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw IllegalStateException("Storage permission not granted")

        @Suppress("DEPRECATION")
        val documents =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(documents, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Could not create ${dir.path}")
        }
        // Copy beside it, then rename over: an interrupted copy leaves the
        // previous file intact.
        val part = File(dir, "$name.part")
        source.copyTo(part, overwrite = true)
        if (!part.renameTo(File(dir, name))) {
            source.copyTo(File(dir, name), overwrite = true)
            part.delete()
        }
    }

    /**
     * Drop mirrored logs past the retention window.
     *
     * The database is one file that is overwritten in place, but logs arrive
     * one per launch and would otherwise pile up in a folder the user can see.
     * Never throws: losing the prune is not worth losing the backup.
     */
    private fun pruneLogs(context: Context) {
        var removed = 0
        val cutoffMillis = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val documents =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                File(documents, FOLDER)
                    .listFiles { f -> f.name.startsWith("applog_") }
                    ?.forEach { if (it.lastModified() < cutoffMillis) it.delete() }
                return
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val keep = Log.recentFiles.map { exportedLogName(it.name) }.toSet()
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(RELATIVE_PATH),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    // DATE_MODIFIED is seconds, not millis.
                    val modified = c.getLong(2) * 1000L
                    if (name.startsWith("applog_") && name !in keep && modified < cutoffMillis) {
                        resolver.delete(
                            ContentUris.withAppendedId(collection, c.getLong(0)),
                            null,
                            null,
                        )
                        removed++
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backup prune failed", e)
        }
        if (removed > 0) Log.i(TAG, "Removed $removed mirrored log(s) older than $RETENTION_DAYS days")
    }
}
