// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import com.coffeeapp.droid.core.config.MachinePicture
import com.coffeeapp.droid.core.MachinePhoto
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.coffeeapp.droid.ui.theme.OnAccent
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coffeeapp.droid.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.ble.MicraProtocol
import com.coffeeapp.droid.core.machine.BoilerReadiness
import com.coffeeapp.droid.core.machine.ControlPath
import com.coffeeapp.droid.core.machine.Readiness
import com.coffeeapp.droid.core.model.MachineSnapshot
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.ui.theme.ErrorRed
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.SuccessGreen
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.SurfaceRaised
import com.coffeeapp.droid.ui.theme.TextDim
import com.coffeeapp.droid.ui.theme.TextSecondary
import com.coffeeapp.droid.ui.theme.WarningAmber
import com.coffeeapp.droid.ui.theme.coffeeSwitchColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A row of options that always fits on one line.
 *
 * This replaces a FilterChip row. Material sizes a chip to its label plus its
 * own padding and enforces a 48dp minimum touch size, so seven of them are
 * wider than a phone card at any font size - the seventh, 96, wrapped onto a
 * second line. Dividing the width evenly puts every option on one line, and
 * lines them up as a bonus, at the cost of cells that are narrow but still a
 * comfortable 40dp tall.
 *
 * Selected and unselected follow AccentPicker: filled and white against
 * raised-grey and secondary.
 */
@Composable
private fun SegmentedOptions(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    pendingIndex: Int = -1,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            // Tapped, not yet confirmed: outlined in the accent until the
            // command returns (seconds, through the cloud).
            val isPending = index == pendingIndex && !isSelected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent else SurfaceRaised)
                    .then(
                        if (isPending) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier,
                    )
                    // A radio group to TalkBack, with the choice it holds.
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                    ) { onSelect(index) },
            ) {
                Text(
                    label,
                    color = when {
                        // OnAccent, not white: three accents are light.
                        isSelected -> OnAccent
                        enabled -> TextSecondary
                        // Dimmed rather than hidden: the options stay readable
                        // while the cloud is down, they just cannot be pressed.
                        else -> TextDim
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Accent only when the boiler is actually at temperature.
 *
 * The big number is the target, not a live reading - the live one only comes
 * over BLE, and shows beside it - so painting it in Accent while the machine
 * sits in standby says
 * "the boiler is at 94 C" about a machine that is stone cold. Dimming keeps
 * the target legible without claiming it was reached. A blank status means
 * the cloud did not say, so it keeps the accent rather than guessing.
 *
 * Composable because Accent is: the accent follows the colour scheme setting.
 */
@Composable
private fun readingColor(status: String): Color =
    if (status.isBlank() || status == "Ready") Accent else TextSecondary

/**
 * The line under a boiler reading.
 *
 * The cloud exposes no live boiler temperature - the dashboard widget carries
 * the target and nothing else - but it does say whether the boiler has reached
 * it, and since when. Over BLE the live temperature is shown as well, by
 * [liveLine]; this status line still comes from the cloud.
 *
 * Returns null for a status the cloud did not send, so the line disappears
 * instead of printing a placeholder.
 */
private fun boilerLine(status: String, readySince: Long?): String? = when (status) {
    "Ready" -> when {
        readySince == null -> "Ready"
        // Guard the tense rather than assume: the field is documented as when
        // the ready state began, but a machine on a schedule could as easily
        // report when it will begin.
        readySince > System.currentTimeMillis() -> "Ready at ${clockTime(readySince)}"
        else -> "Ready since ${clockTime(readySince)}"
    }
    "HeatingUp" -> "Heating up"
    "NoWater" -> "No water"
    "StandBy" -> "Standby"
    "Off" -> "Off"
    else -> null
}

/** Epoch millis as a short local wall-clock time. */
private fun clockTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** "Now 128°C" (or "[label] 128°C") from a BLE reading, or null with no link. */
private fun liveLine(current: Double?, label: String = "Now"): String? =
    current?.let { String.format(Locale.US, "%s %.0f°C", label, it) }

/**
 * Joined with " · " so the live temperature and the cloud's ready status
 * share a line; null when neither has anything to say.
 */
private fun boilerLines(vararg parts: String?): String? =
    parts.filterNotNull().joinToString(" · ").ifBlank { null }

/**
 * Which way commands go right now. Green BLE when the local link carries
 * them; grey CLOUD when it does not (the reason, if any, is in [linkMessage]).
 */
@Composable
private fun PathBadge(overBle: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (overBle) SuccessGreen else SurfaceRaised)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            if (overBle) "BLE" else "CLOUD",
            color = if (overBle) Color.Black else TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Why commands are going to the cloud although local control is on, or null
 * when there is nothing to say. BadToken and ConfigMode need the user to do
 * something; Unreachable only needs patience.
 */
private fun linkMessage(state: MicraBleClient.State, seen: Boolean): Pair<String, Color>? = when (state) {
    MicraBleClient.State.BAD_TOKEN ->
        "Bluetooth token rejected - using the cloud. Check it under the cog, " +
            "Machine Bluetooth." to ErrorRed
    MicraBleClient.State.CONFIG_MODE ->
        "The machine is in Bluetooth configuration mode - restart it to use " +
            "local control. Using the cloud meanwhile." to WarningAmber
    MicraBleClient.State.UNREACHABLE ->
        "Bluetooth link down, reconnecting - using the cloud meanwhile." to TextSecondary
    MicraBleClient.State.CONNECTING ->
        "Connecting over Bluetooth…" to TextSecondary
    // The normal state between shots since 2.6.0: only a shot connects.
    MicraBleClient.State.RELEASED ->
        if (seen) {
            "Bluetooth idle - the machine is advertising and connects when a shot is armed." to TextSecondary
        } else {
            "Bluetooth idle - the machine is not advertising (off, or its Bluetooth has hung). " +
                "Shots use the cloud." to WarningAmber
        }
    MicraBleClient.State.CONNECTED, MicraBleClient.State.OFF -> null
}

/**
 * The steam boiler's status, in the cloud's vocabulary so [readingColor] and
 * [boilerLine] work unchanged. Over BLE it is decided from the live reading
 * against the level's target - the cloud's "Ready" lags a level change by up
 * to its 10-minute refresh. The cloud still wins on NoWater (BLE is not
 * polling the tank) and is the whole answer with no link.
 */
private fun steamStatus(m: MachineSnapshot): String {
    if (m.steamEnabled == false) return "Off"
    if (m.steamBoilerStatus == "NoWater") return "NoWater"
    val target = MicraProtocol.steamLevelToCelsius(m.steamLevel)?.toDouble()
    return when (BoilerReadiness.of(target, m.steamTempCurrent)) {
        null -> m.steamBoilerStatus
        Readiness.HEATING -> "HeatingUp"
        Readiness.READY, Readiness.SETTLING -> "Ready"
    }
}

/**
 * "Now 128°C · Heating to 131°C". The cloud's "Ready since" is used only
 * without a live reading: after a change its time is from before it.
 */
private fun steamLine(m: MachineSnapshot, status: String): String? {
    val now = liveLine(m.steamTempCurrent)
    val target = MicraProtocol.steamLevelToCelsius(m.steamLevel)
    val readiness = BoilerReadiness.of(target?.toDouble(), m.steamTempCurrent)
    val detail = when {
        status == "Off" || status == "NoWater" || readiness == null ->
            boilerLine(status, m.steamReadySince)
        readiness == Readiness.HEATING -> "Heating to $target°C"
        readiness == Readiness.SETTLING -> "Ready, settling to $target°C"
        else -> "Ready"
    }
    return boilerLines(now, detail)
}

/** "Level1" -> "Level 1". Anything else - "N/A" included - passes through. */
private fun steamLabel(raw: String): String =
    if (raw.startsWith("Level") && raw.length == 6) "Level ${raw.last()}" else raw

/**
 * Linea Micra dashboard.
 *
 * Temperature and steam level are chip rows rather than dropdowns: there are
 * seven and three options respectively, both fixed, and each one is a real
 * cloud command - a chip shows the current value and takes one tap, where a
 * dropdown hides it behind two.
 */
@Composable
fun MachineScreen(machine: MachineSnapshot) {
    val config by CoffeeRuntime.configFlow.collectAsStateWithLifecycle()
    // Since 2.6.0 the cloud is the only source between shots, so opening the
    // page refreshes it unless that happened in the last minute.
    LaunchedEffect(Unit) { CoffeeRuntime.refreshCloudIfStale() }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    val overBle = machine.controlPath == ControlPath.BLE
    // A connected BLE link can carry every control here even when the cloud
    // cannot be reached; without one, the cloud has to be there.
    val controlsEnabled = overBle || (machine.configured && machine.lastSyncOk)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Title and machine photo share a row: the image identifies which
        // machine this is talking to at a glance.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    machine.name.ifBlank { "Linea Micra" },
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Serial ${config.machine.serial.ifBlank { "N/A" }}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(8.dp))

                // Connection state first: every control below is a cloud
                // round-trip and they are all meaningless if it is offline.
                val (stateLabel, stateColor) = when {
                    // The machine is answering us directly; the cloud's view
                    // of it is beside the point.
                    overBle -> "CONNECTED" to SuccessGreen
                    // Amber, not red: nothing is broken, it is just not filled in.
                    !machine.configured -> "NOT SET UP" to WarningAmber
                    !machine.lastSyncOk -> when (machine.cloudProblem) {
                        com.coffeeapp.droid.core.cloud.CloudProblem.POLICY -> "CLOUD: POLICY NOT ACCEPTED"
                        com.coffeeapp.droid.core.cloud.CloudProblem.REFUSED -> "CLOUD REFUSES ACCESS"
                        com.coffeeapp.droid.core.cloud.CloudProblem.SIGN_IN -> "CLOUD SIGN-IN FAILED"
                        else -> "CLOUD UNREACHABLE"
                    } to ErrorRed
                    machine.connected -> "CONNECTED" to SuccessGreen
                    else -> "OFFLINE" to TextSecondary
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stateLabel, color = stateColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    // Only with local control on: a cloud-only install has one
                    // path and nothing to tell apart.
                    if (config.machine.localControl) {
                        Spacer(Modifier.width(8.dp))
                        PathBadge(overBle)
                    }
                }
            }

            MachinePictureBox(MachinePicture.fromId(config.defaults.machinePicture))
        }

        if (!machine.configured) {
            Text(
                "Add your La Marzocco account and machine serial under the cog, " +
                    "and this page comes to life. Nothing below works until then.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }

        val cloudProblem = machine.cloudProblem
        if (cloudProblem != null && !machine.lastSyncOk) {
            // What it is and what to do, link up or not (2.11.0): two
            // policy outages hid behind CONNECTED over BLE for days.
            val since = machine.cloudProblemSince?.let {
                " since " + com.coffeeapp.droid.core.cloud.CloudProblem.formatSince(it, System.currentTimeMillis())
            } ?: ""
            Text(
                "${cloudProblem.label}$since - ${cloudProblem.advice}",
                color = if (cloudProblem == com.coffeeapp.droid.core.cloud.CloudProblem.NETWORK) WarningAmber else ErrorRed,
                fontSize = 12.sp,
            )
        }
        machine.lastError?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp)
        }

        if (config.machine.localControl) {
            linkMessage(machine.bleState, machine.bleSeen)?.let { (text, color) ->
                Text(text, color = color, fontSize = 12.sp)
            }
            // Live boiler readings come only over BLE, and BLE is up only
            // during a shot - this buys a two-minute session on demand.
            if (machine.bleState == MicraBleClient.State.RELEASED ||
                machine.bleState == MicraBleClient.State.UNREACHABLE
            ) {
                TextButton(onClick = { CoffeeRuntime.reconnectMachineNow() }) {
                    Text("CONNECT FOR 2 MIN (LIVE READINGS)", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Coffee boiler ------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("COFFEE BREW TARGET", color = TextSecondary, fontSize = 11.sp)
                Text(
                    String.format(Locale.US, "%.0f°C", machine.coffeeTemp),
                    color = readingColor(machine.coffeeBoilerStatus),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                // "Boiler", not "Now": the target is the brew temperature at
                // the group, and the boiler is held a few degrees above it
                // (97 for 94, 94 for 90) to cover the loss on the way - so
                // "Now 97°C" under a 94 target read as running hot when it is
                // as intended. Readiness stays the cloud's until that offset
                // is pinned down (+3 and +4 so far).
                boilerLines(
                    liveLine(machine.coffeeTempCurrent, label = "Boiler"),
                    boilerLine(machine.coffeeBoilerStatus, machine.coffeeReadySince),
                )?.let {
                    Text(
                        it,
                        // No water is the one status worth interrupting for -
                        // it is why a shot will not pour.
                        color = if (machine.coffeeBoilerStatus == "NoWater") ErrorRed else TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                val temps = config.machine.coffeeTempOptions
                SegmentedOptions(
                    labels = temps.map { "$it" },
                    // Rounded, not truncated: the reading above is formatted
                    // with %.0f, so a target of 95.6 would print "96" while
                    // toInt() selected 95.
                    selectedIndex = temps.indexOf(Math.round(machine.coffeeTemp).toInt()),
                    enabled = controlsEnabled && machine.pendingCommand?.startsWith("coffee:") != true,
                    pendingIndex = machine.pendingCommand?.removePrefix("coffee:")?.toIntOrNull()
                        ?.let { temps.indexOf(it) } ?: -1,
                    onSelect = { CoffeeRuntime.setCoffeeTemp(temps[it].toDouble()) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Steam boiler --------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("STEAM TARGET", color = TextSecondary, fontSize = 11.sp)
                val steamStatus = steamStatus(machine)
                Text(
                    steamLabel(machine.steamLevel),
                    color = readingColor(steamStatus),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                steamLine(machine, steamStatus)?.let {
                    Text(
                        it,
                        color = if (steamStatus == "NoWater") ErrorRed else TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                val levels = listOf("Level1", "Level2", "Level3")
                SegmentedOptions(
                    labels = levels.map(::steamLabel),
                    selectedIndex = levels.indexOf(machine.steamLevel),
                    enabled = controlsEnabled && machine.steamEnabled != false &&
                        machine.pendingCommand?.startsWith("steamLevel:") != true,
                    pendingIndex = machine.pendingCommand?.removePrefix("steamLevel:")
                        ?.let { levels.indexOf(it) } ?: -1,
                    onSelect = { CoffeeRuntime.setSteamLevel(levels[it]) },
                )
                Spacer(Modifier.height(8.dp))
                // Not before we know which way it is: a blind toggle could
                // switch off a boiler someone is about to use. Nor while a
                // switch is still on its way.
                val steamToggleEnabled = controlsEnabled && machine.steamEnabled != null &&
                    machine.pendingCommand?.startsWith("steamOn:") != true
                // The whole row toggles, so TalkBack names the switch.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = machine.steamEnabled == true,
                        enabled = steamToggleEnabled,
                        role = Role.Switch,
                        onValueChange = { CoffeeRuntime.setSteamEnabled(it) },
                    ),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Steam boiler", color = Color.White, fontSize = 15.sp)
                        Text(
                            when (machine.steamEnabled) {
                                true -> "On"
                                false -> "Off - coffee only, saves power"
                                null -> "Unknown until the machine reports it"
                            },
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    if (machine.pendingCommand?.startsWith("steamOn:") == true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Switch(
                        checked = machine.steamEnabled == true,
                        onCheckedChange = null,
                        enabled = steamToggleEnabled,
                        colors = coffeeSwitchColors(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The steam boiler setting on the machine: Level 1 is the " +
                        "lowest and Level 3 the highest.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                refreshing = true
                scope.launch {
                    CoffeeRuntime.refreshCloudNow()
                    refreshing = false
                }
            },
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("REFRESHING…")
            } else {
                Text("REFRESH")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Gateway ${machine.gatewayVersion}",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The picture beside the machine's name (2.15.0): La Marzocco's product
 * photo, the user's own (`MachinePhoto`), or nothing. An own photo that is
 * missing or unreadable shows nothing rather than La Marzocco's, so the
 * choice of not showing their image holds.
 */
@Composable
private fun MachinePictureBox(choice: MachinePicture) {
    when (choice) {
        MachinePicture.NONE -> Unit
        MachinePicture.LA_MARZOCCO -> Image(
            painter = painterResource(R.drawable.lineamicra_celeste),
            contentDescription = "La Marzocco Linea Micra",
            // Fit, not Crop: this is a product shot of a specific machine
            // in a specific colour, and cropping it would cut the machine
            // out of its own photo.
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(140.dp).height(115.dp),
        )
        MachinePicture.OWN -> {
            val context = LocalContext.current
            val version by MachinePhoto.version.collectAsStateWithLifecycle()
            // Decoded once per pick, not on every recomposition (the page
            // recomposes with each machine poll).
            val bitmap = remember(version) { MachinePhoto.load(context)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Your machine",
                    // Crop into a rounded frame: a phone photo has a
                    // background, unlike the cut-out product shot.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(140.dp)
                        .height(115.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

