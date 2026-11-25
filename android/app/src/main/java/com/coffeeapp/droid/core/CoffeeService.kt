// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core

import com.coffeeapp.droid.core.log.FileLog as Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.coffeeapp.droid.MainActivity
import com.coffeeapp.droid.R
import com.coffeeapp.droid.core.cloud.CloudProblem
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.core.model.ShotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps the scale link and the shot logic alive.
 *
 * Not optional. On Android an app that loses foreground has its
 * BLE notifications throttled and can be killed outright — which, mid-shot,
 * would strand brew-by-weight with the machine still pouring and no stop
 * command coming. Running in the foreground with a connectedDevice type is
 * what makes "put the phone down while the shot pulls" safe.
 */
class CoffeeService : Service() {

    companion object {
        private const val TAG = "CoffeeService"
        private const val CHANNEL_ID = "scale_connection"
        private const val NOTIFICATION_ID = 1

        /**
         * Shortest gap between two notification updates.
         *
         * The snapshot arrives per weight sample, 10-20 a second. Posting at
         * that rate would be pointless work and a janky notification; twice a
         * second is faster than anyone reads a number off a lock screen.
         */
        private const val MIN_UPDATE_INTERVAL_MS = 500L

        /** The resting line, when there is no shot to report. */
        private const val IDLE_TEXT = "Watching the scale"

        /** The notification's Quit action: stop the service and the hardware. */
        private const val ACTION_QUIT = "com.coffeeapp.droid.QUIT"

        fun start(context: Context) {
            val intent = Intent(context, CoffeeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Foreground service created")
        createChannel()
        if (!startForegroundCompat()) {
            // Android 12+ can refuse a foreground start from the background
            // (a sticky restart, say). Without it the service would be killed
            // shortly anyway; stop cleanly and wait for the app to be opened.
            stopSelf()
            return
        }
        CoffeeRuntime.start(applicationContext)
        watchShot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            Log.i(TAG, "Quit chosen from the notification; stopping the service")
            stopSelf()
            return START_NOT_STICKY
        }
        // A null intent is the system restarting us after killing the process.
        if (intent == null) Log.i(TAG, "Foreground service restarted by the system (sticky)")
        // Restart if the system kills us: the scale link should come back on
        // its own rather than waiting for the user to reopen the app.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        scope.cancel()
        CoffeeRuntime.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Narrate the shot in the notification.
     *
     * The phone is usually face down or locked on the counter while a shot
     * pulls, and this notification is already on screen holding the process
     * up - so it is the one surface that can show the weight without picking
     * the phone up. distinctUntilChanged drops the repeats a resting scale
     * produces, and conflate plus the delay caps the rate.
     */
    private fun watchShot() {
        scope.launch {
            CoffeeRuntime.snapshot
                .map(::notificationText)
                .distinctUntilChanged()
                .conflate()
                .collect { text ->
                    notify(text)
                    delay(MIN_UPDATE_INTERVAL_MS)
                }
        }
    }

    private fun notificationText(snapshot: AppSnapshot): String {
        val shot = snapshot.shot
        // Something to act on outranks the readout: a stop that never reached
        // the machine is read off the lock screen or not at all.
        if (shot.alert.isNotEmpty()) return shot.alert
        return when (shot.state) {
            ShotState.BREWING, ShotState.STOPPING -> String.format(
                Locale.US,
                "%.1f g of %.1f g · %.0f s",
                shot.weight,
                shot.targetYield,
                shot.elapsedSeconds,
            )
            // The finished figures stay up until RESET, which is the state a
            // finished shot sits in - exactly when the number is wanted.
            ShotState.FINALIZED -> String.format(
                Locale.US,
                "%s · %.1f g in %.0f s",
                if (shot.saved) "Shot saved" else "Shot finished",
                shot.finalYield ?: shot.weight,
                shot.elapsedSeconds,
            )
            // Resting is when a cloud problem gets the line: that is when
            // someone can do something about it, and it is the usual state.
            ShotState.IDLE -> if (shot.armed) {
                "Armed — waiting for a shot"
            } else {
                CloudProblem.warning(
                    snapshot.machine.cloudProblem,
                    snapshot.machine.cloudProblemSince,
                    System.currentTimeMillis(),
                ) ?: IDLE_TEXT
            }
        }
    }

    private fun notify(text: String) {
        // Re-posting the same id updates the existing notification in place
        // rather than stacking a second one.
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scale_channel_name),
            // LOW: no sound, no heads-up. This notification exists to hold the
            // process, not to interrupt anyone mid-espresso.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        builder.setContentText(text).build()

    /**
     * Built once: during a shot the text changes twice a second, and each
     * update rebuilt the builder and both PendingIntents - two binder calls
     * to the activity manager - for a notification that only changes text.
     */
    private val builder: NotificationCompat.Builder by lazy {
        val tapToOpen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            // Silent on every update: this notification is a readout, and a
            // shot would otherwise buzz the phone ten times a second.
            .setOnlyAlertOnce(true)
            .setContentIntent(tapToOpen)
            // The only way out short of force-stopping the app: it ends the
            // scale and machine links and the service until the app is opened.
            .addAction(
                0,
                "Quit",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, CoffeeService::class.java).setAction(ACTION_QUIT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
    }

    /** False when Android refused the foreground start. */
    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification(IDLE_TEXT)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start refused (${e.javaClass.simpleName}); stopping the service", e)
            false
        }
    }
}
