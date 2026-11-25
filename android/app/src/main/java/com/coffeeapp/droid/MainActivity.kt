// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid

import com.coffeeapp.droid.core.log.FileLog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coffeeapp.droid.core.CoffeeService
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.ui.CoffeeApp
import com.coffeeapp.droid.ui.SPLASH_MILLIS
import com.coffeeapp.droid.ui.SplashScreen
import com.coffeeapp.droid.ui.theme.CoffeeTheme
import com.coffeeapp.droid.ui.theme.TextSecondary
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the hardware now when the permission is already there, not
        // after the 4 s launch screen: on a cold start the scale connect began
        // 4 s late. PermissionGate starts it too; starting twice is a no-op.
        if (BluetoothPermissions.blockingDenied(this).isEmpty()) CoffeeService.start(this)
        // The notification's Quit ends the runtime; the screen goes with it.
        // Left open it showed a scale and a machine nothing was talking to
        // any more, with buttons that silently did nothing.
        lifecycleScope.launch {
            var wasRunning = CoffeeRuntime.running.value
            CoffeeRuntime.running.collect { running ->
                if (wasRunning && !running) {
                    FileLog.i("MainActivity", "Runtime shut down (Quit); closing the screen")
                    finishAndRemoveTask()
                }
                wasRunning = running
            }
        }
        setContent {
            CoffeeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    Launch { PermissionGate { CoffeeApp() } }
                }
            }
        }
    }
}

/**
 * Shows the launch screen, then the app.
 *
 * Tri-state on purpose. Composing the app first and the splash a frame later
 * would flash the UI behind it; composing the splash first would flash it at
 * someone who turned it off. Undecided renders nothing, which lasts as long as
 * one DataStore read.
 */
@Composable
private fun Launch(content: @Composable () -> Unit) {
    var showSplash by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        showSplash = CoffeeRuntime.splashEnabled()
        if (showSplash == true) {
            delay(SPLASH_MILLIS)
            showSplash = false
        }
    }

    when (showSplash) {
        null -> Unit
        true -> SplashScreen(onSkip = { showSplash = false })
        else -> content()
    }
}

/** One line with every permission the app asks for and whether it is held. */
private fun logPermissions(context: android.content.Context, whenLabel: String) {
    val states = BluetoothPermissions.required().joinToString(" ") { p ->
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, p) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        "${p.substringAfterLast('.')}=${if (granted) "granted" else "denied"}"
    }
    FileLog.i("MainActivity", "Permissions $whenLabel: $states")
}

/**
 * Holds the app behind the Bluetooth permission, then starts the service.
 *
 * The foreground service is what keeps a shot alive with the screen off, and
 * it cannot connect to anything before the permission is granted - so this is
 * the one place where startup order matters.
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BluetoothPermissions.blockingDenied(context).isEmpty()) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Judged on the blocking subset: a denied notification permission
        // costs the ongoing notification, not the scale connection.
        granted = BluetoothPermissions.blockingDenied(context).isEmpty()
        asked = true
        logPermissions(context, "after request")
        if (granted) {
            CoffeeService.start(context)
        } else {
            FileLog.w("MainActivity", "Bluetooth permission denied; hardware not started")
        }
    }

    LaunchedEffect(Unit) {
        logPermissions(context, "at launch")
        if (granted) {
            CoffeeService.start(context)
        } else {
            launcher.launch(BluetoothPermissions.required())
        }
    }

    // Granted in Android Settings and come back: pick it up on return,
    // rather than waiting for a button press that re-asks for nothing.
    LifecycleResumeEffect(Unit) {
        if (!granted && BluetoothPermissions.blockingDenied(context).isEmpty()) {
            granted = true
            logPermissions(context, "on return")
            CoffeeService.start(context)
        }
        onPauseOrDispose { }
    }

    if (granted) {
        content()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Bluetooth permission is needed to talk to the scale.",
            color = TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        if (asked) {
            Spacer(Modifier.height(8.dp))
            Text(
                "If you denied it permanently, enable Nearby devices for this app " +
                    "in Android Settings.",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { launcher.launch(BluetoothPermissions.required()) }) {
            Text("GRANT PERMISSION")
        }
        // After a permanent denial the request returns at once with no
        // dialog, so the button above does nothing visible; this is the way.
        if (asked) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }) { Text("OPEN APP SETTINGS") }
        }
    }
}
