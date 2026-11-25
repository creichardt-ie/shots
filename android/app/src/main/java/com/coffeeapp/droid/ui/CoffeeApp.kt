// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coffeeapp.droid.core.log.FileLog
import com.coffeeapp.droid.core.model.MachineSnapshot
import com.coffeeapp.droid.core.model.ScaleSnapshot
import com.coffeeapp.droid.core.model.statusOnly
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.coffeeapp.droid.BluetoothPermissions
import com.coffeeapp.droid.core.CoffeeService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.ui.screens.BeansScreen
import com.coffeeapp.droid.ui.screens.BrewScreen
import com.coffeeapp.droid.ui.screens.HelpScreen
import com.coffeeapp.droid.ui.screens.HistoryScreen
import com.coffeeapp.droid.ui.screens.MachineScreen
import com.coffeeapp.droid.ui.screens.ScaleScreen
import com.coffeeapp.droid.ui.screens.SettingsScreen
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.BluetoothBlue
import com.coffeeapp.droid.ui.theme.ErrorRed
import com.coffeeapp.droid.ui.theme.OnAccent
import com.coffeeapp.droid.ui.theme.SuccessGreen
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.TextSecondary
import com.coffeeapp.droid.ui.theme.coffeeSwitchColors
import com.coffeeapp.droid.ui.theme.WarningAmber

/**
 * How stale a vessel navigation request may be before it is ignored.
 *
 * Placing a vessel and then opening a screen by hand a minute later should not
 * yank you elsewhere - the point is to follow the gesture you just made, not
 * to replay one you have moved on from.
 */
private const val MAX_NAV_REQUEST_AGE_SECONDS = 10.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoffeeApp() {
    val navController = rememberNavController()
    val snapshot by CoffeeRuntime.snapshot.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    FollowVesselNavigation(navController, currentRoute, snapshot.shot.navSeq, snapshot.shot.navTarget, snapshot.shot.navTs)

    // With the app on screen the machine link listens for advertising every
    // minute (never connects), so the status-bar dot says whether the
    // machine is there before a shot needs it. Off screen nothing probes.
    val context = LocalContext.current
    LifecycleStartEffect(Unit) {
        CoffeeRuntime.setAppVisible(true)
        // And bring the hardware back if it is down. The activity is
        // singleTask and outlives the service: after the notification's Quit
        // (or a refused sticky restart) a reopened app found no service, since
        // onCreate - the only other start - does not run again. Idempotent.
        if (BluetoothPermissions.blockingDenied(context).isEmpty()) CoffeeService.start(context)
        onStopOrDispose { CoffeeRuntime.setAppVisible(false) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
                // Slices, not the whole snapshot: the weight changes 10-20
                // times a second and the status row shows none of it.
                title = { StatusRow(snapshot.scale.statusOnly(), snapshot.machine) },
                actions = {
                    // Scale page only: the weighing screen is where a vessel
                    // match surprises (a pitcher with milk left reading as the
                    // Coffee cup), so its off switch sits there.
                    if (currentRoute == Destination.Scale.route) AutoSwitch()
                    // Help opens the guide at the page on screen; in Help the
                    // same button closes it. The cog is hidden there: Help is
                    // also opened from Configuration, and the cog would stack
                    // a second Configuration on top of the first.
                    val inHelp = currentRoute == HELP_ROUTE
                    IconButton(
                        onClick = {
                            if (inHelp) {
                                navController.popBackStack()
                            } else {
                                val section = helpAnchorFor(currentRoute) ?: "top"
                                FileLog.i("CoffeeApp", "Help opened at $section")
                                navController.navigate(helpRoute(section)) { launchSingleTop = true }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (inHelp) Icons.Filled.Close else Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = if (inHelp) "Close help" else "Help",
                            tint = TextSecondary,
                        )
                    }

                    // Contextual: the same button opens Configuration and
                    // closes it again. Without this the cog was a one-way
                    // door - tapping it while already in Configuration just
                    // pushed another copy onto the back stack, so getting out
                    // took as many back presses as taps.
                    val inSettings = currentRoute == SETTINGS_ROUTE
                    // Closing goes through the back dispatcher rather than
                    // popping directly, so the unsaved-changes guard the
                    // Configuration screen installs as a BackHandler catches
                    // this button too. Without that the cog and the system
                    // back gesture behave differently on the same screen.
                    val backDispatcher =
                        LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    if (!inHelp) IconButton(
                        onClick = {
                            if (inSettings) {
                                if (backDispatcher != null) {
                                    backDispatcher.onBackPressed()
                                } else {
                                    navController.popBackStack()
                                }
                            } else {
                                navController.navigate(SETTINGS_ROUTE) {
                                    // Never stack duplicates of a screen that
                                    // is only ever reached from this button.
                                    launchSingleTop = true
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (inSettings) Icons.Filled.Close else Icons.Filled.Settings,
                            contentDescription = if (inSettings) "Close configuration" else "Configuration",
                            tint = TextSecondary,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                Destination.bottomBar.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            if (currentRoute != destination.route) {
                                navController.navigate(destination.route) {
                                    // Single-top with a popUpTo so the back
                                    // stack does not grow with every tab tap.
                                    // The real start destination, not Brew: since Scale
                                    // became the start, popping to Brew popped nothing
                                    // and every tab tap grew the back stack.
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        // The label below already names it; a description too
                        // made TalkBack read "Brew, Brew".
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Dropped-link banner. Deliberately global rather than per-screen:
            // a scale that has silently dropped makes every screen wrong, and
            // the shot logic will simply never fire.
            ConnectionBanner(
                connected = snapshot.scale.connected,
                asleep = snapshot.scale.asleep,
            )
            // A machine command that failed (power, temperature, steam), on
            // every page: the power switch lives in the bar above all of them.
            snapshot.machine.commandError?.let {
                Text(
                    it,
                    color = ErrorRed,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ErrorRed.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            NavHost(
                navController = navController,
                // Scale, not Brew: the app is opened next to the machine with
                // something about to go on the scale, and placing a vessel
                // navigates from here on its own when it recognises one.
                startDestination = Destination.Scale.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destination.Brew.route) { BrewScreen(snapshot) }
                composable(Destination.Scale.route) { ScaleScreen(snapshot) }
                composable(Destination.Beans.route) { BeansScreen() }
                composable(Destination.History.route) { HistoryScreen() }
                composable(Destination.Machine.route) { MachineScreen(snapshot.machine) }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(
                        onDone = { navController.popBackStack() },
                        onHelp = {
                            FileLog.i("CoffeeApp", "Help opened from Configuration")
                            navController.navigate(helpRoute(SETTINGS_HELP_ANCHOR)) { launchSingleTop = true }
                        },
                    )
                }
                composable(HELP_ROUTE) { entry -> HelpScreen(entry.arguments?.getString("section")) }
            }
        }
    }
}

/**
 * Vessel-driven screen switching.
 *
 * ShotService publishes a navigation REQUEST - a counter, a
 * target and the time it was made - whenever a vessel configured with
 * switchToPage lands. It has no idea what is on screen; this is the whole of
 * that decision.
 *
 * The rule that only the two live screens may be navigated away from is kept
 * deliberately: it exists so a placement cannot interrupt someone editing the
 * Configuration screen: a stray navigation would discard half-typed form
 * state.
 */
@Composable
private fun FollowVesselNavigation(
    navController: NavHostController,
    currentRoute: String?,
    navSeq: Int,
    navTarget: String,
    navTs: Double,
) {
    var lastSeenSeq by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(navSeq) {
        // First composition only records where the counter stands: a screen
        // opened after a placement must not bounce straight out of itself.
        if (lastSeenSeq < 0 || navSeq == lastSeenSeq) {
            lastSeenSeq = navSeq
            return@LaunchedEffect
        }
        lastSeenSeq = navSeq

        val target = Destination.fromRoute(navTarget) ?: return@LaunchedEffect
        if (target != Destination.Brew && target != Destination.Scale) return@LaunchedEffect
        if (currentRoute == target.route) return@LaunchedEffect
        // Only the live screens hand over; never interrupt a form.
        if (currentRoute != Destination.Brew.route && currentRoute != Destination.Scale.route) {
            FileLog.d("CoffeeApp", "Vessel navigation to ${target.route} skipped: on $currentRoute")
            return@LaunchedEffect
        }
        if (System.currentTimeMillis() / 1000.0 - navTs > MAX_NAV_REQUEST_AGE_SECONDS) {
            FileLog.d("CoffeeApp", "Vessel navigation to ${target.route} skipped: request too old")
            return@LaunchedEffect
        }
        FileLog.i("CoffeeApp", "Vessel navigation to ${target.route} (seq $navSeq)")
        navController.navigate(target.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** Scale status, machine power and the machine's Bluetooth dot. */
@Composable
private fun StatusRow(scale: ScaleSnapshot, machine: MachineSnapshot) {

    // Three states, not two: asleep is a deliberate battery saving, not a
    // fault, and showing it as a disconnection would send people hunting for
    // a problem that is not there.
    val (scaleLabel, scaleColor) = when {
        scale.asleep -> "Sleeping" to WarningAmber
        scale.connected -> {
            val battery = scale.battery?.let { " $it%" } ?: ""
            "Scale$battery" to SuccessGreen
        }
        else -> "No scale" to ErrorRed
    }

    val pending = machine.pendingPower
    val displayOn = pending ?: machine.powerOn

    // The Machine page's rule: usable over BLE, or with a cloud that
    // answered. Gating on the cloud alone disabled the switch with the
    // link up and the cloud down, and for good on a BLE-only setup.
    val reachable = machine.controlPath == com.coffeeapp.droid.core.machine.ControlPath.BLE ||
        (machine.configured && machine.lastSyncOk)

    // Dim, not red, when nothing answers. powerOn defaults to false, so
    // colouring that state red would state "the machine is off" on no
    // evidence - the same reason the switch beside it is disabled.
    val micraColor = when {
        !reachable -> TextSecondary
        displayOn -> SuccessGreen
        else -> ErrorRed
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(scaleColor),
        )
        // The texts give way (ellipsis) before the switch does: at a large
        // font they took the whole title slot and the switch - the app's only
        // power control - was measured at zero width.
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Text(
            text = " $scaleLabel",
            color = scaleColor,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        Text(
            text = " · Micra",
            color = micraColor,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
        )
        }
        Switch(
            checked = displayOn,
            onCheckedChange = { CoffeeRuntime.togglePower(it) },
            // Disabled while a toggle is in flight: confirming takes ~17 s
            // through the cloud and up to 6 s over BLE, and a second tap
            // would queue a contradictory command.
            enabled = pending == null && reachable,
            colors = coffeeSwitchColors(),
            // Scaled rather than resized: Material's Switch has a fixed
            // 52x32dp track, and next to 13sp status text it read as the
            // heaviest thing in the bar. scale() shrinks what is drawn while
            // the layout slot - and so the touch target - stays full size,
            // which matters for a control that powers the machine on and off.
            modifier = Modifier
                .padding(start = 6.dp)
                .scale(0.65f)
                .semantics { contentDescription = "Micra power" },
        )
        BluetoothDot(machine.bleState, machine.bleSeen)
    }
}

/**
 * AUTO: vessel detection on/off for this run of the app (on at every
 * launch). Disabled, and shown off, while auto-tare is off in Configuration,
 * which is the saved setting underneath it.
 */
@Composable
private fun AutoSwitch() {
    val on by CoffeeRuntime.autoDetect.collectAsStateWithLifecycle()
    val config by CoffeeRuntime.configFlow.collectAsStateWithLifecycle()
    val saved = config.autoTare.enabled
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "AUTO",
            color = if (saved && on) SuccessGreen else TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
        )
        Switch(
            checked = saved && on,
            onCheckedChange = { CoffeeRuntime.setAutoDetect(it) },
            enabled = saved,
            colors = coffeeSwitchColors(),
            // Same scaled-down switch as Micra power, full-size touch target.
            modifier = Modifier
                .scale(0.65f)
                .semantics { contentDescription = "Auto-tare vessel detection" },
        )
    }
}

/**
 * The machine's Bluetooth: filled while the link is up, a ring while it is
 * released but the machine was advertising at the last look, nothing when it
 * was not seen or local control is off. Only an indicator - commands route
 * on the link state, never on this.
 */
@Composable
private fun BluetoothDot(state: MicraBleClient.State, seen: Boolean) {
    // Colour alone says nothing to TalkBack, so each dot says what it means.
    val shape = Modifier.size(8.dp).clip(CircleShape)
    when {
        state == MicraBleClient.State.CONNECTED ->
            Box(shape.background(BluetoothBlue).semantics { contentDescription = "Micra Bluetooth connected" })
        seen -> Box(
            shape.border(1.5.dp, BluetoothBlue, CircleShape)
                .semantics { contentDescription = "Micra seen over Bluetooth" },
        )
    }
}

@Composable
private fun ConnectionBanner(connected: Boolean, asleep: Boolean) {
    if (connected) return

    val scope = rememberCoroutineScope()
    // A wake runs three connect attempts and has taken over two minutes in the
    // logs. Without a busy state the button looks dead for that whole time and
    // gets pressed repeatedly, each press queueing another reconnect.
    var waking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val (message, action, color) = if (asleep) {
        Triple("Scale asleep to save battery", "WAKE", WarningAmber)
    } else {
        Triple("Scale disconnected", "RECONNECT", ErrorRed)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (failed && !waking) "Could not reach the scale" else message,
            color = color,
            fontSize = 13.sp,
        )
        TextButton(
            enabled = !waking,
            onClick = {
                waking = true
                failed = false
                scope.launch {
                    val ok = CoffeeRuntime.reconnectScaleNow()
                    waking = false
                    // A success removes the banner entirely, so this only ever
                    // shows on the failure it is there to report.
                    failed = !ok
                }
            },
        ) {
            if (waking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = color,
                )
                Text("  WAKING…", color = color, fontSize = 13.sp)
            } else {
                Text(if (failed) "RETRY" else action, color = color, fontSize = 13.sp)
            }
        }
    }
}
