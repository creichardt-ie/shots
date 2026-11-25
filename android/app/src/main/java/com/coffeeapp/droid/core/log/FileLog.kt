// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * File logging: one file per process start and per day, 5 MB x 3 rotation,
 * 365-day prune (2.11.1; was 7 - a phone used for nothing else has room
 * for years of 20-60 KB days, and outages were traced back further than a
 * week).
 *
 * Logcat alone is not enough. It is a ring buffer that
 * a chatty app can overrun in minutes, it is wiped on reboot, and reading it
 * needs a USB cable. That is the wrong trade for this app in particular:
 * CLAUDE.md is explicit that the auto-tare narration must stay at INFO because
 * the guard values are tuned from those lines, and "auto-arm did not fire,
 * why?" is a question you ask hours later, standing at the machine, not
 * tethered to a laptop.
 *
 * So every line goes to BOTH logcat (for live `adb logcat`) and a file.
 *
 * Deliberately API-compatible with android.util.Log, so call sites are
 * unchanged and each file simply aliases the import:
 *
 *     import com.coffeeapp.droid.core.log.FileLog as Log
 *
 * Writes happen on a single background thread: logging must never block the
 * runtime dispatcher, and it must never block BLE binder callbacks either.
 * Each line is flushed. A crash drains the queue and writes its own trace
 * synchronously before the process dies ([installCrashHandler]).
 *
 * Lines carry the date and milliseconds: the foreground service keeps one
 * process alive for days, and "13:19:10" alone could be any of them.
 */
object FileLog {

    private const val TAG = "FileLog"

    /** 5 MB per file, three backups. */
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val BACKUP_COUNT = 3

    /** Files older than this are deleted at startup and at each day roll. */
    const val RETENTION_DAYS = 365

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "file-log").apply { isDaemon = true }
    }

    private val lineFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var current: File? = null

    /** The file before [current] (the previous day's), for the backup mirror. */
    @Volatile
    private var previous: File? = null

    /** The day [current] belongs to; writing on a later day starts a new file. */
    @Volatile
    private var currentDay: String = ""

    private val processStart = System.currentTimeMillis()

    /**
     * The lowest level written to the file. DEBUG today; a noisy DEBUG source
     * can be cut from the file by raising it, without losing it from logcat.
     */
    @Volatile
    var minFileLevel: Int = Log.DEBUG

    /** Secret values registered by their owners; masked in every file line. */
    private val secrets = java.util.concurrent.CopyOnWriteArraySet<String>()

    /**
     * Mask [value] wherever it would appear in a log line. A safety net, not
     * the policy - nothing logs secrets on purpose - against an exception
     * message quoting a response that carried one. Short values are ignored,
     * so a masked "1" cannot blank out every number in the file.
     */
    fun redact(value: String?) {
        if (value != null && value.length >= 8) secrets.add(value)
    }

    /** The active log file, or null before [init]. Used by the share action. */
    val currentFile: File? get() = current

    /**
     * The current file plus whatever the backup mirror would otherwise lose:
     * the newest rotation backup and the previous day's file.
     */
    val recentFiles: List<File>
        get() {
            val cur = current ?: return emptyList()
            return listOfNotNull(
                cur,
                File("${cur.path}.1").takeIf { it.exists() },
                previous?.takeIf { it.exists() },
            )
        }

    /**
     * Create the log directory and this launch file, prune old ones, and
     * write the startup banner. Safe to call more than once.
     */
    fun init(context: Context) {
        if (current != null) return
        try {
            // Android/data/<package>/files/logs (2.16.1): no permission, written
            // line by line, and readable over adb on a release build, where
            // run-as on the private folder is refused. Deleted with the app like
            // the private folder was; DocumentsBackup still mirrors it to
            // Documents. Private folder only if shared storage is unavailable.
            val external = context.getExternalFilesDir("logs")?.takeIf { it.isDirectory || it.mkdirs() }
            val dir = external ?: File(context.filesDir, "logs").apply { mkdirs() }
            logDir = dir
            // A fresh timestamped file per process start. Seconds are included so a restart inside the same minute
            // cannot collide with the previous run.
            current = File(dir, newFileName())
            currentDay = dayFormat.format(Date())
            // The banner stays here, so the version is the file's first line.
            // The rest is binder calls and a stat per log file (hundreds, with
            // a year's retention) - off the main thread, before first frame.
            banner(context)
            writer.execute {
                try {
                    logPreviousExit(context)
                    if (external != null) moveOldLogs(File(context.filesDir, "logs"), dir)
                    prune(dir)
                } catch (e: Exception) {
                    Log.e(TAG, "Log housekeeping failed", e)
                }
            }
        } catch (e: Exception) {
            // Logging must never be the thing that breaks startup.
            Log.e(TAG, "Could not initialise file logging", e)
        }
    }

    // --- android.util.Log-compatible surface -------------------------------

    fun d(tag: String, msg: String) = write("DEBUG", tag, msg, null, Log.DEBUG)
    fun d(tag: String, msg: String, tr: Throwable?) = write("DEBUG", tag, msg, tr, Log.DEBUG)

    fun i(tag: String, msg: String) = write("INFO", tag, msg, null, Log.INFO)
    fun i(tag: String, msg: String, tr: Throwable?) = write("INFO", tag, msg, tr, Log.INFO)

    fun w(tag: String, msg: String) = write("WARNING", tag, msg, null, Log.WARN)
    fun w(tag: String, msg: String, tr: Throwable?) = write("WARNING", tag, msg, tr, Log.WARN)

    fun e(tag: String, msg: String) = write("ERROR", tag, msg, null, Log.ERROR)
    fun e(tag: String, msg: String, tr: Throwable?) = write("ERROR", tag, msg, tr, Log.ERROR)

    // --- Internals ---------------------------------------------------------

    private fun write(level: String, tag: String, msg: String, tr: Throwable?, priority: Int) {
        // Straight to logcat first, so live debugging is unaffected by
        // anything going wrong with the file.
        if (tr != null) Log.println(priority, tag, "$msg\n${stackTrace(tr)}")
        else Log.println(priority, tag, msg)

        if (current == null || priority < minFileLevel) return
        val line = formatLine(level, tag, msg, tr)

        try {
            writer.execute {
                try {
                    val file = rollIfNewDay() ?: return@execute
                    rotateIfNeeded(file)
                    file.appendText(line)
                } catch (e: Exception) {
                    Log.e(TAG, "Log write failed", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The crash handler has shut the writer down; the process is going.
        }
    }

    private fun formatLine(level: String, tag: String, msg: String, tr: Throwable?): String {
        val timestamp = synchronized(lineFormat) { lineFormat.format(Date()) }
        var line = buildString {
            // "date time.ms - LEVEL - Tag - message"
            append(timestamp).append(" - ").append(level).append(" - ")
            append(tag).append(" - ").append(msg).append('\n')
            if (tr != null) append(stackTrace(tr)).append('\n')
        }
        for (s in secrets) if (line.contains(s)) line = line.replace(s, "***")
        return line
    }

    private fun newFileName(): String =
        SimpleDateFormat("'applog_'ddMMM_HHmm_ss'.log'", Locale.US).format(Date()).lowercase(Locale.US)

    /**
     * On the writer thread: past local midnight, start a new file (and prune),
     * so one file never spans days and old ones go even if the process never
     * restarts. Returns the file to write to.
     */
    private fun rollIfNewDay(): File? {
        val file = current ?: return null
        val today = synchronized(dayFormat) { dayFormat.format(Date()) }
        if (today == currentDay) return file
        val dir = logDir ?: return file
        val next = File(dir, newFileName())
        val up = (System.currentTimeMillis() - processStart) / 3_600_000
        next.appendText(
            formatLine("INFO", TAG, "New day: continuing from ${file.name} (process up ${up}h)", null),
        )
        previous = file
        current = next
        currentDay = today
        pruneOnWriter(dir)
        return next
    }

    private fun stackTrace(tr: Throwable): String = StringWriter().also { sw ->
        PrintWriter(sw).use { tr.printStackTrace(it) }
    }.toString()

    /** Shift .log -> .log.1 -> .log.2 -> .log.3, dropping the oldest. */
    private fun rotateIfNeeded(file: File) {
        if (file.length() < MAX_BYTES) return
        for (i in BACKUP_COUNT downTo 1) {
            val src = if (i == 1) file else File("${file.path}.${i - 1}")
            val dst = File("${file.path}.$i")
            if (src.exists()) {
                dst.delete()
                src.renameTo(dst)
            }
        }
    }

    /**
     * Delete log files older than the retention window.
     *
     * Best-effort by modification time, covering both the per-launch files and
     * their rotation backups. Never throws: cleanup must not break startup.
     */
    /**
     * One-time move of the logs written to the private folder before 2.16.1,
     * so the year of history stays in one place. Copy then delete: the two
     * folders sit on different file systems, where a rename fails.
     */
    private fun moveOldLogs(from: File, to: File) {
        val old = from.listFiles()?.filter { it.isFile } ?: return
        if (old.isEmpty()) return
        var moved = 0
        for (f in old) {
            val target = File(to, f.name)
            try {
                if (!target.exists()) f.copyTo(target)
                f.delete()
                moved++
            } catch (e: Exception) {
                Log.w(TAG, "Could not move old log ${f.name}: ${e.message}")
            }
        }
        from.delete()
        i("FileLog", "Moved $moved older log file(s) to ${to.path}")
    }

    private fun prune(dir: File) {
        val removed = pruneFiles(dir)
        if (removed > 0) i(TAG, "Pruned $removed log file(s) older than $RETENTION_DAYS days")
    }

    /** Same, from the writer thread, where logging through [i] would re-enter. */
    private fun pruneOnWriter(dir: File) {
        val removed = pruneFiles(dir)
        if (removed > 0) Log.i(TAG, "Pruned $removed log file(s) older than $RETENTION_DAYS days")
    }

    private fun pruneFiles(dir: File): Int {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        var removed = 0
        try {
            dir.listFiles { f -> f.name.startsWith("applog_") }?.forEach { f ->
                if (f != current && f.lastModified() < cutoff && f.delete()) removed++
            }
        } catch (e: Exception) {
            Log.d(TAG, "Log prune failed", e)
        }
        return removed
    }

    /**
     * Write an uncaught exception to the file before the process dies, then
     * hand it on to the previous handler (which shows the crash and kills
     * the process). The queue is drained first so the lines leading up to
     * the crash - usually the useful ones - are not lost with it.
     */
    fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                writer.shutdown()
                writer.awaitTermination(500, TimeUnit.MILLISECONDS)
                val line = formatLine("ERROR", "Crash", "Uncaught exception on thread ${thread.name}", e)
                Log.e("Crash", "Uncaught exception on thread ${thread.name}", e)
                current?.appendText(line)
            } catch (_: Throwable) {
                // Nothing more can be done; never mask the original crash.
            }
            previousHandler?.uncaughtException(thread, e)
        }
    }

    /**
     * Why the previous process ended (Android 11+): a crash, an ANR, the
     * system reclaiming memory, the user swiping the app away. Otherwise a
     * mid-shot death leaves no trace at all.
     */
    private fun logPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val reason = when (last.reason) {
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                ApplicationExitInfo.REASON_OTHER -> "OTHER"
                else -> "reason ${last.reason}"
            }
            val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(last.timestamp))
            val line = "Previous process ended: $reason at $at" +
                (last.description?.let { " | $it" } ?: "")
            when (last.reason) {
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                -> w(TAG, line)
                else -> i(TAG, line)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read the previous exit reason", e)
        }
    }

    /**
     * One-time startup banner.
     *
     * The app version and version code identify the build, and the build pins
     * every library, so the first line shows exactly what was running.
     */
    private fun banner(context: Context) {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} (build $code)"
        } catch (e: Exception) {
            "unknown"
        }

        i(TAG, "=".repeat(64))
        i(TAG, "CoffeeApp $version starting at ${Date()}")
        i(
            TAG,
            "Device ${Build.MANUFACTURER} ${Build.MODEL} | Android " +
                "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        i(TAG, "Log file: ${current?.path}")
    }
}
