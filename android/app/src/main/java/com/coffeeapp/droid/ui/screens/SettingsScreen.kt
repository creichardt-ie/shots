// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import com.coffeeapp.droid.core.config.ScaleConfig
import com.coffeeapp.droid.ble.ScaleModel
import com.coffeeapp.droid.core.config.MachinePicture
import com.coffeeapp.droid.core.MachinePhoto
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import kotlinx.serialization.json.Json
import com.coffeeapp.droid.core.config.mergeEdits
import com.coffeeapp.droid.ui.theme.OnAccent
import com.coffeeapp.droid.ui.parseDecimal
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.coffeeapp.droid.core.config.MachineConfig
import com.coffeeapp.droid.ble.MicraBleClient
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.core.backup.DocumentsBackup
import com.coffeeapp.droid.core.config.AppConfig
import com.coffeeapp.droid.core.config.Vessel
import com.coffeeapp.droid.core.log.FileLog
import com.coffeeapp.droid.core.shot.findBandOverlaps
import com.coffeeapp.droid.ui.APP_AUTHOR
import com.coffeeapp.droid.ui.APP_TAGLINE
import com.coffeeapp.droid.ui.APP_NAME
import com.coffeeapp.droid.ui.Destination
import com.coffeeapp.droid.ui.appVersionLabel
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.AccentScheme
import com.coffeeapp.droid.ui.theme.SurfaceRaised
import com.coffeeapp.droid.ui.theme.ErrorRed
import com.coffeeapp.droid.ui.theme.SuccessGreen
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.TextSecondary
import com.coffeeapp.droid.ui.theme.coffeeSwitchColors
import com.coffeeapp.droid.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The four grind-verdict windows on offer.
 *
 * label -> (fast, slow). A wider window is a more tolerant verdict; the
 * tightest flags almost any drift, the loosest only a real miss. Any other
 * pair can be typed in below - these are shortcuts, not the only options.
 */
private val GRIND_WINDOWS: List<Pair<String, Pair<Double, Double>>> = listOf(
    "29-31s" to (29.0 to 31.0),
    "28-32s" to (28.0 to 32.0),
    "27-33s" to (27.0 to 33.0),
    "26-34s" to (26.0 to 34.0),
)

/**
 * Configuration.
 *
 * Reached from the app bar rather than the bottom row, because it is opened
 * rarely and deliberately - and because a vessel placement must never navigate
 * away from it mid-edit (see FollowVesselNavigation).
 *
 * Collapsible sections: every field is exposed, but there
 * are around thirty of them and only one or two are ever wanted at a time.
 * All start closed, so the screen opens as a short index with each section
 * summarising its current values, and SAVE stays within reach.
 *
 * Edits accumulate in a local [AppConfig] draft and are written in one go by
 * SAVE, through CoffeeRuntime.updateConfig - which persists atomically and
 * rebuilds only the hardware whose settings actually changed. Nothing here
 * takes effect until SAVE, so a half-typed threshold can never reach the shot
 * state machine.
 */
@Composable
fun SettingsScreen(onDone: () -> Unit, onHelp: () -> Unit) {
    val config = CoffeeRuntime.config()
    val secrets = CoffeeRuntime.secrets

    // One draft rather than a variable per field: numeric settings are nested
    // three deep, and copying the whole config keeps SAVE a single write with
    // no chance of a field being silently left behind.
    // Saveable: a trip to the official app (to accept the policy, or copy the
    // serial) can cost the process, and with it every edit made so far.
    var draft by rememberSaveable(stateSaver = AppConfigSaver) { mutableStateOf(config) }

    // The config as last opened or saved here. Learning keeps writing the
    // live config while this screen is open (the BBW offset at every shot, a
    // vessel's weight at every bare placement); comparing against the live
    // copy called the screen dirty when the user had changed nothing, and
    // saving the draft wrote those learned values back to stale ones. Moved
    // forward by SAVE: left at the opening value, the screen stayed dirty
    // after a save, and a second SAVE wrote an edit from the first one over
    // a value learned since.
    var opened by rememberSaveable(stateSaver = AppConfigSaver) { mutableStateOf(config) }

    var username by rememberSaveable { mutableStateOf(secrets.username) }
    var password by rememberSaveable { mutableStateOf(secrets.password) }
    var bleToken by rememberSaveable { mutableStateOf(secrets.bleToken) }
    var savedMessage by remember { mutableStateOf("") }

    // The stored credentials, read once: each getter is a decrypt, and the
    // dirty check below runs on every keystroke. Moved forward by SAVE.
    var stored by remember { mutableStateOf(Triple(secrets.username, secrets.password, secrets.bleToken)) }

    // Fields whose text is not a usable value (blank, unparseable, out of
    // range), by key. Such a field never reaches the draft, so SAVE would
    // silently write the old value while the field showed the new one.
    val invalid = remember { mutableStateMapOf<String, String>() }

    // The saved config, collected rather than read once: after SAVE the store
    // updates asynchronously, and a plain read has nothing to recompose on.
    val savedConfig by CoffeeRuntime.configFlow.collectAsStateWithLifecycle()
    val dirty = draft != opened ||
        username != stored.first ||
        password != stored.second ||
        bleToken != stored.third

    var confirmDiscard by remember { mutableStateOf(false) }

    // Nothing here takes effect until SAVE, so leaving by any route throws the
    // edits away silently. Both routes out land on this handler: the system
    // back gesture directly, and the toolbar's close button because it asks
    // the back dispatcher rather than popping the stack itself.
    BackHandler(enabled = dirty) { confirmDiscard = true }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = {
                Text(
                    "These edits have not been saved, so nothing you changed " +
                        "here has reached the scale or the machine yet.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("DISCARD", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("KEEP EDITING") }
            },
        )
    }

    // Independent rather than accordion: closing one to open another would be
    // irritating when cross-checking, say, the flow threshold against the
    // placement rate floor.
    val open = remember { mutableStateMapOf<String, Boolean>() }

    val sl = draft.shotLogic
    val bbw = draft.brewByWeight
    val at = draft.autoTare

    CompositionLocalProvider(LocalInvalidFields provides invalid) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge: keep the focused field above the keyboard.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // --- Account --------------------------------------------------------
        ExpandableSection(
            title = "La Marzocco account",
            summary = username.ifBlank { "not set" },
            open = open,
        ) {
            Text(
                "Stored encrypted on this device. Brew-by-weight cannot stop the " +
                    "machine without these.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.machine.serial,
                onValueChange = { draft = draft.copy(machine = draft.machine.copy(serial = it)) },
                label = { Text("Machine serial") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            NumberSetting(
                label = "Power refresh delay (s)",
                value = draft.machine.powerRefreshDelaySeconds,
                min = 0.0,
                max = 60.0,
                help = "How long to wait after a power toggle before re-reading the " +
                    "machine state. The cloud reports the old status for a while " +
                    "after the command, so refreshing too early just shows the " +
                    "previous value.",
                onValue = {
                    draft = draft.copy(machine = draft.machine.copy(powerRefreshDelaySeconds = it))
                },
            )
        }

        // --- Machine Bluetooth ------------------------------------------------
        ExpandableSection(
            title = "Machine Bluetooth",
            summary = if (draft.machine.localControl) {
                "local control · ${draft.machine.bleAddress.ifBlank { "no machine picked" }}"
            } else {
                "off - cloud only"
            },
            open = open,
        ) {
            MachineBluetoothSection(
                machine = draft.machine,
                savedMachine = savedConfig.machine,
                bleToken = bleToken,
                onBleToken = { bleToken = it },
                onChange = { draft = draft.copy(machine = it) },
            )
        }

        // --- Scale ----------------------------------------------------------
        ExpandableSection(
            title = "Scale",
            summary = "${ScaleModel.fromId(draft.scale.model).label} · " +
                "${draft.scale.macAddress.ifBlank { "no MAC" }} · " +
                "sleeps after ${trimNumber(draft.scale.idleDisconnectMinutes)} min",
            open = open,
        ) {
            ScalePicker(
                scale = draft.scale,
                onChange = { draft = draft.copy(scale = it) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.scale.macAddress,
                onValueChange = { draft = draft.copy(scale = draft.scale.copy(macAddress = it)) },
                label = { Text("Bluetooth MAC") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            NumberSetting(
                label = "Idle disconnect (minutes)",
                value = draft.scale.idleDisconnectMinutes,
                min = 0.0,
                help = "Minutes of no weight change before the link is dropped so the " +
                    "scale auto-powers-off. 0 = never. Waking is manual, from the " +
                    "banner. Saving a new value reconnects the scale.",
                onValue = {
                    draft = draft.copy(scale = draft.scale.copy(idleDisconnectMinutes = it))
                },
            )
            NumberSetting(
                label = "Idle activity threshold (g)",
                value = draft.scale.idleActivityThresholdGrams,
                min = 0.1,
                help = "How much the weight must change to count as activity. Coarse " +
                    "on purpose: a resting scale jitters ~0.1 g, and a finer value " +
                    "re-arms the timer on every tick so the scale never sleeps.",
                onValue = {
                    draft = draft.copy(scale = draft.scale.copy(idleActivityThresholdGrams = it))
                },
            )
        }

        // --- Shot detection ---------------------------------------------------
        ExpandableSection(
            title = "Shot detection",
            summary = "start ${trimNumber(sl.startThresholdGrams)} g · " +
                "finish ${trimNumber(sl.finishStabilitySeconds)} s",
            open = open,
        ) {
            NumberSetting(
                label = "Start threshold (g)",
                value = sl.startThresholdGrams,
                min = 0.05,
                help = "Weight that starts the timer, once armed.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(startThresholdGrams = it)) },
            )
            NumberSetting(
                label = "Flow detection threshold (g)",
                value = sl.flowDetectionThreshold,
                min = 0.01,
                help = "Sample-to-sample change that counts as still flowing.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(flowDetectionThreshold = it)) },
            )
            NumberSetting(
                label = "Finish stability (s)",
                value = sl.finishStabilitySeconds,
                min = 0.2,
                help = "How long the weight must hold steady before the shot is done.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(finishStabilitySeconds = it)) },
            )
            NumberSetting(
                label = "Ignore stability first (s)",
                value = sl.ignoreStabilityFirstSeconds,
                min = 0.0,
                help = "Opening grace period. The first seconds of a shot are flat - " +
                    "the machine is up to pressure but nothing has reached the cup yet.",
                onValue = {
                    draft = draft.copy(shotLogic = sl.copy(ignoreStabilityFirstSeconds = it))
                },
            )
            NumberSetting(
                label = "Min save duration (s)",
                value = sl.minSaveDurationSeconds,
                min = 0.0,
                help = "Shorter shots are discarded rather than written to history.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(minSaveDurationSeconds = it)) },
            )
            NumberSetting(
                label = "Min save yield (g)",
                value = sl.minSaveYieldGrams,
                min = 0.0,
                help = "And a yield floor, for the same reason: duration alone once " +
                    "let a 5.2 s phantom reach the journal.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(minSaveYieldGrams = it)) },
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "SAFETY GUARDS",
                color = WarningAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "These two are what stop a cup landing on an armed scale from being " +
                    "read as a shot and firing a brew-by-weight stop - which " +
                    "power-cycles the machine. The defaults have wide margins: the " +
                    "lightest vessel is over 100 g, and 120 recorded shots peak at " +
                    "3.7 g/s. Widen them only with a reason.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            NumberSetting(
                label = "Max start weight (g)",
                value = sl.maxStartWeightGrams,
                min = 1.0,
                max = 60.0,
                help = "A reading heavier than this when a shot would start is a " +
                    "vessel landing, not espresso.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(maxStartWeightGrams = it)) },
            )
            NumberSetting(
                label = "Max flow rate (g/s)",
                value = sl.maxFlowRateGps,
                min = 5.0,
                max = 100.0,
                help = "A single sample changing faster than this is a placement or a " +
                    "knock, so it is never fed to brew-by-weight or saved as the yield.",
                onValue = { draft = draft.copy(shotLogic = sl.copy(maxFlowRateGps = it)) },
            )
        }

        // --- Brew by weight ---------------------------------------------------
        ExpandableSection(
            title = "Brew by weight",
            summary = "offset ${trimNumber(bbw.offsetGrams)} g · " +
                "learning ${trimNumber(bbw.learningAlpha)}",
            open = open,
        ) {
            Text(
                "The machine is stopped when the scale reaches target minus the " +
                    "offset, and the offset is re-learned from the drip after every " +
                    "shot. The toggle itself lives on the Brew screen.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            NumberSetting(
                label = "Starting offset (g)",
                value = bbw.offsetGrams,
                min = 0.0,
                max = 30.0,
                help = "A seed, not a fixed value: it is overwritten by learning after " +
                    "each shot. Setting it here restarts the learning from this number.",
                onValue = { draft = draft.copy(brewByWeight = bbw.copy(offsetGrams = it)) },
            )
            NumberSetting(
                label = "Learning rate (0-1)",
                value = bbw.learningAlpha,
                min = 0.0,
                max = 1.0,
                help = "How far each shot pulls the offset toward the drip it measured. " +
                    "Higher adapts faster but chases noise; 0 freezes the offset.",
                onValue = { draft = draft.copy(brewByWeight = bbw.copy(learningAlpha = it)) },
            )
            NumberSetting(
                label = "Power-on delay (s)",
                value = bbw.powerOnDelaySeconds,
                min = 5.0,
                max = 10.0,
                help = "How long to wait after cutting power before switching the " +
                    "machine back on for steaming.",
                onValue = { draft = draft.copy(brewByWeight = bbw.copy(powerOnDelaySeconds = it)) },
            )
            NumberSetting(
                label = "Max plausible drip (g)",
                value = bbw.maxPlausibleDripGrams,
                min = 1.0,
                max = 50.0,
                help = "Hard cap on offset learning. A drip larger than this is a gross " +
                    "error - a failed stop command, say - so the offset is unchanged.",
                onValue = {
                    draft = draft.copy(brewByWeight = bbw.copy(maxPlausibleDripGrams = it))
                },
            )
            NumberSetting(
                label = "Learning outlier band (0-1)",
                value = bbw.learningMaxDeviationRatio,
                min = 0.0,
                max = 1.0,
                help = "Skips a shot whose drip deviates from the current offset by " +
                    "more than this fraction (0.5 = 50%), so one odd shot - a slow " +
                    "cloud stop, channeling - cannot pollute a converged offset. " +
                    "0 disables the band; the hard cap above still applies.",
                onValue = {
                    draft = draft.copy(brewByWeight = bbw.copy(learningMaxDeviationRatio = it))
                },
            )
        }

        // --- Grind target window ----------------------------------------------
        ExpandableSection(
            title = "Grind target window",
            summary = "${trimNumber(bbw.grindTargetFastSeconds)}-" +
                "${trimNumber(bbw.grindTargetSlowSeconds)}s",
            open = open,
        ) {
            Text(
                "The verdict shown after each shot. Faster than the window reads " +
                    "\"grind finer\", slower reads \"grind coarser\", in between is " +
                    "\"dialed in\". Judged on machine time (pump-on duration) when it " +
                    "was captured. Ideal for an 18g to 36g shot is around 30s.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            // Flow, not Row: four chips overflow a 360 dp phone or a larger font.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GRIND_WINDOWS.forEach { (label, window) ->
                    val (fast, slow) = window
                    FilterChip(
                        selected = bbw.grindTargetFastSeconds == fast &&
                            bbw.grindTargetSlowSeconds == slow,
                        onClick = {
                            draft = draft.copy(
                                brewByWeight = bbw.copy(
                                    grindTargetFastSeconds = fast,
                                    grindTargetSlowSeconds = slow,
                                ),
                            )
                        },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = OnAccent,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberSetting(
                    label = "Fast below (s)",
                    value = bbw.grindTargetFastSeconds,
                    min = 5.0,
                    max = 120.0,
                    modifier = Modifier.weight(1f),
                    onValue = {
                        draft = draft.copy(brewByWeight = bbw.copy(grindTargetFastSeconds = it))
                    },
                )
                NumberSetting(
                    label = "Slow above (s)",
                    value = bbw.grindTargetSlowSeconds,
                    min = 5.0,
                    max = 120.0,
                    modifier = Modifier.weight(1f),
                    onValue = {
                        draft = draft.copy(brewByWeight = bbw.copy(grindTargetSlowSeconds = it))
                    },
                )
            }
            // A window with the bounds the wrong way round has no "dialed in"
            // band at all - every shot would read as both too fast and too slow.
            if (bbw.grindTargetFastSeconds >= bbw.grindTargetSlowSeconds) {
                Text(
                    "\"Fast below\" must be less than \"slow above\", or no shot can " +
                        "ever read as dialed in.",
                    color = WarningAmber,
                    fontSize = 12.sp,
                )
            }
        }

        // --- Auto-tare ---------------------------------------------------------
        ExpandableSection(
            title = "Auto-tare",
            summary = (if (at.enabled) "on" else "off") +
                (if (at.enabled && at.autoArm) ", auto-arm" else "") +
                " · ${at.vessels.size} vessels",
            open = open,
        ) {
            Text(
                "Recognises a vessel by weight when it lands. A bare vessel is tared; " +
                    "one placed with beans in it is deliberately NOT tared - that " +
                    "would zero the very contents you put it there to weigh - and is " +
                    "shown as a net weight instead. Only ever active while idle.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))

            ToggleRow(
                title = "Enable auto-tare",
                subtitle = "Applies the moment you save - no reconnect - so it also " +
                    "works as a live kill-switch",
                checked = at.enabled,
                onChange = { draft = draft.copy(autoTare = at.copy(enabled = it)) },
            )
            ToggleRow(
                title = "Also arm the shot automatically",
                subtitle = "Arms as if you had pressed READY, once the tare has landed. " +
                    "Leave off to keep the arm gate manual.",
                checked = at.autoArm,
                enabled = at.enabled,
                onChange = { draft = draft.copy(autoTare = at.copy(autoArm = it)) },
            )

            Spacer(Modifier.height(8.dp))
            NumberSetting(
                label = "Match tolerance (g)",
                value = at.toleranceGrams,
                min = 0.5,
                enabled = at.enabled,
                help = "How close a step must be to a stored bare weight to count as " +
                    "that vessel.",
                onValue = { draft = draft.copy(autoTare = at.copy(toleranceGrams = it)) },
            )
            NumberSetting(
                label = "Stability hold (s)",
                value = at.stableSeconds,
                min = 0.2,
                enabled = at.enabled,
                help = "How long the reading must sit still to count as a plateau.",
                onValue = { draft = draft.copy(autoTare = at.copy(stableSeconds = it)) },
            )
            NumberSetting(
                label = "Min placement rate (g/s)",
                value = at.minRiseRateGps,
                min = 1.0,
                enabled = at.enabled,
                help = "The load-bearing guard. How fast a weight change must arrive " +
                    "to count as a placement - this is what makes espresso " +
                    "structurally unable to look like a vessel landing: a shot pours " +
                    "at ~2 g/s, a cup set down moves at hundreds. Lower it if " +
                    "deliberate placements are being missed.",
                onValue = { draft = draft.copy(autoTare = at.copy(minRiseRateGps = it)) },
            )
            NumberSetting(
                label = "Stability band (g)",
                value = at.stabilityBandGrams,
                min = 0.05,
                enabled = at.enabled,
                help = "The jitter band a resting scale is allowed to wander inside.",
                onValue = { draft = draft.copy(autoTare = at.copy(stabilityBandGrams = it)) },
            )
            NumberSetting(
                label = "Tare cooldown (s)",
                value = at.tareCooldownSeconds,
                min = 0.5,
                enabled = at.enabled,
                help = "How long to wait for a tare to land before giving up on " +
                    "arming. Fails closed: no arm, rather than arming a loaded scale.",
                onValue = { draft = draft.copy(autoTare = at.copy(tareCooldownSeconds = it)) },
            )
            NumberSetting(
                label = "Vessel learning rate (0-1)",
                value = at.learningAlpha,
                min = 0.0,
                max = 1.0,
                enabled = at.enabled,
                help = "How fast a stored bare weight is nudged toward each " +
                    "measurement, so a wet or seasoned vessel stays accurate. " +
                    "0 disables re-learning.",
                onValue = { draft = draft.copy(autoTare = at.copy(learningAlpha = it)) },
            )

            Spacer(Modifier.height(12.dp))
            Text("VESSELS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            at.vessels.forEach { vessel ->
                VesselCard(
                    vessel = vessel,
                    onChange = { updated ->
                        draft = draft.copy(
                            autoTare = at.copy(
                                vessels = at.vessels.map {
                                    if (it.name == vessel.name) updated else it
                                },
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
            }

            // Band-overlap warnings, surfaced before saving rather than left to
            // be debugged from a wrong dose reading later: a collision does not
            // produce a no-op, it produces a plausible-looking wrong number.
            findBandOverlaps(at.vessels, at.toleranceGrams).forEach { warning ->
                Text(
                    warning,
                    color = WarningAmber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // --- Data and diagnostics ----------------------------------------------
        ExpandableSection(
            title = "Back up now",
            summary = "Documents/${DocumentsBackup.FOLDER}",
            open = open,
        ) { BackupSection() }

        ExpandableSection(
            title = "Restore data",
            summary = "shot history and beans, from a backup",
            open = open,
        ) { RestoreSection() }

        ExpandableSection(
            title = "Diagnostics",
            summary = FileLog.currentFile?.name ?: "no log yet",
            open = open,
        ) { DiagnosticsSection() }

        ExpandableSection(
            title = "Appearance",
            summary = AccentScheme.fromId(draft.defaults.colorScheme).label + " · " +
                MachinePicture.fromId(draft.defaults.machinePicture).label,
            open = open,
        ) {
            AccentPicker(
                selected = AccentScheme.fromId(draft.defaults.colorScheme),
                onSelect = {
                    draft = draft.copy(defaults = draft.defaults.copy(colorScheme = it.id))
                },
            )
            Spacer(Modifier.height(16.dp))
            MachinePicturePicker(
                selected = MachinePicture.fromId(draft.defaults.machinePicture),
                onSelect = {
                    draft = draft.copy(defaults = draft.defaults.copy(machinePicture = it.id))
                },
            )
        }

        LinkRow(
            title = "Help",
            summary = "the user guide, opened at this page",
            onClick = onHelp,
        )

        ExpandableSection(
            title = "About",
            summary = appVersionLabel(LocalContext.current),
            open = open,
        ) {
            AboutSection(
                showSplash = draft.defaults.showSplashScreen,
                onShowSplash = {
                    draft = draft.copy(defaults = draft.defaults.copy(showSplashScreen = it))
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        if (savedMessage.isNotEmpty() && !dirty) {
            Text(savedMessage, color = SuccessGreen, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        if (invalid.isNotEmpty()) {
            Text(
                "Fix before saving: ${invalid.values.distinct().sorted().joinToString(", ")}",
                color = ErrorRed,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        } else if (dirty) {
            Text("Unsaved changes", color = WarningAmber, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                val credentialsChanged =
                    username != stored.first || password != stored.second
                if (credentialsChanged) {
                    secrets.username = username
                    secrets.password = password
                }
                if (bleToken != stored.third) CoffeeRuntime.setBleToken(bleToken)
                stored = Triple(username, password, bleToken)

                // Only what was edited here; learned values come from the
                // live config (see [opened]).
                val saved = mergeEdits(opened = opened, draft = draft, live = CoffeeRuntime.config()).copy(
                    machine = draft.machine.copy(
                        serial = draft.machine.serial.trim(),
                        bleAddress = draft.machine.bleAddress.trim().uppercase(),
                    ),
                    scale = draft.scale.copy(
                        macAddress = draft.scale.macAddress.trim().uppercase(),
                    ),
                )
                CoffeeRuntime.updateConfig(saved)
                // The new baseline is what was written, and the screen shows
                // it: learned values that came in meanwhile included.
                opened = saved
                draft = saved
                if (credentialsChanged) CoffeeRuntime.credentialsChanged()
                savedMessage = "Saved."
            },
            enabled = invalid.isEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("SAVE", fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { if (dirty) confirmDiscard = true else onDone() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("BACK") }
        Spacer(Modifier.height(32.dp))
    }
    }
}

/** See [SettingsScreen]'s `invalid`: field key -> label, for the SAVE line. */
private val LocalInvalidFields = compositionLocalOf<MutableMap<String, String>?> { null }

/** The draft through process death: the config is already @Serializable. */
private val AppConfigSaver = Saver<AppConfig, String>(
    save = { Json.encodeToString(AppConfig.serializer(), it) },
    restore = { runCatching { Json.decodeFromString(AppConfig.serializer(), it) }.getOrNull() },
)

/**
 * Report this field to [LocalInvalidFields] while [problem] is non-null, and
 * withdraw it when the field leaves the screen (a section collapsed drops
 * its typed text, so what remains in the draft is the valid old value).
 */
@Composable
private fun ReportValidity(key: String, label: String, problem: String?) {
    val invalid = LocalInvalidFields.current ?: return
    LaunchedEffect(key, problem) {
        if (problem != null) invalid[key] = label else invalid.remove(key)
    }
    DisposableEffect(key) { onDispose { invalid.remove(key) } }
}

/**
 * A collapsible settings section.
 *
 * The header doubles as the readout: while closed it shows the values the
 * section currently holds, so the whole screen can be scanned without opening
 * anything. Expansion state is keyed by [title] in a caller-owned map so it
 * survives recomposition when the draft changes.
 */
@Composable
private fun ExpandableSection(
    title: String,
    summary: String,
    open: MutableMap<String, Boolean>,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = open[title] == true

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open[title] = !expanded }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!expanded && summary.isNotEmpty()) {
                    Text(summary, color = TextSecondary, fontSize = 12.sp)
                }
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 12.dp)) { content() }
        }

        // A hairline rather than a Divider component, so this does not depend
        // on which Material 3 revision named it what.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF222222)),
        )
    }
}

/**
 * A row that opens another screen, drawn like an [ExpandableSection] header
 * so it sits in the list as one of them, with an arrow instead of a chevron.
 */
@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(summary, color = TextSecondary, fontSize = 12.sp)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                // The row's text names it; TalkBack reads that.
                contentDescription = null,
                tint = TextSecondary,
            )
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF222222)),
        )
    }
}

/**
 * A numeric setting with its explanation and bounds.
 *
 * Keeps the typed text locally so a field can be cleared or half-typed, and
 * only pushes a value up once it parses AND falls inside the bounds. That is
 * the important part: an out-of-range or unparseable entry is shown as an
 * error and simply never reaches the draft, so SAVE can only ever write values
 * the shot logic can cope with.
 */
@Composable
private fun NumberSetting(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    help: String = "",
    min: Double? = null,
    max: Double? = null,
    enabled: Boolean = true,
    key: String = label,
) {
    var text by remember(key) { mutableStateOf(trimNumber(value)) }
    var error by remember(key) { mutableStateOf("") }
    ReportValidity(key, label, error.ifEmpty { null })
    // Follow changes made elsewhere (a grind-window chip sets both fields),
    // but not the echo of this field's own typing: "8." parses to 8.0, which
    // is already the value, so the text is left as typed.
    LaunchedEffect(value) {
        if (parseDecimal(text) != value) {
            text = trimNumber(value)
            error = ""
        }
    }

    Column(modifier = modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                val parsed = parseDecimal(raw)
                error = when {
                    parsed == null -> if (raw.isBlank()) "Enter a value" else "Not a number"
                    min != null && parsed < min -> "Must be at least ${trimNumber(min)}"
                    max != null && parsed > max -> "Must be at most ${trimNumber(max)}"
                    else -> ""
                }
                if (parsed != null && error.isEmpty()) onValue(parsed)
            },
            label = { Text(label) },
            singleLine = true,
            isError = error.isNotEmpty(),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        val supporting = if (error.isNotEmpty()) error else help
        if (supporting.isNotEmpty()) {
            Text(
                supporting,
                color = if (error.isNotEmpty()) ErrorRed else TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/** 8.0 -> "8", 0.05 -> "0.05". Trailing zeroes read badly in a settings field. */
private fun trimNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else v.toString().trimEnd('0').trimEnd('.')

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    // The whole row is the switch: the label is tappable, and TalkBack reads
    // the title with the state instead of a nameless "Switch, on".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else TextSecondary, fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = coffeeSwitchColors(),
        )
    }
}

/**
 * Page targets a placement may ask for.
 *
 * Only the two live screens act on a navigation request - FollowVesselNavigation
 * drops anything else - so offering Beans, History or Micra here would be an
 * option that silently does nothing. "-" stores the empty string, which is a
 * deliberate no-op rather than a missing value: the dosing cup is weighed
 * wherever you happen to be standing.
 */
private val VESSEL_PAGES = listOf(
    "" to "—",
    Destination.Scale.route to Destination.Scale.label,
    Destination.Brew.route to Destination.Brew.label,
)

/**
 * One vessel's row.
 *
 * Page and weights are editable; these used to be a read-only summary line,
 * which read as settings but could only be changed by clearing app data
 * (DefaultConfig seeds the store once, and after the first save the stored
 * blob is the whole config).
 *
 * `arm` is deliberately NOT a control. It is not the "also arms the shot"
 * checkbox its name suggests: PlacementDetector excludes arm vessels from
 * loaded matching entirely, so ticking it on the dosing cup would quietly
 * stop it reading a dose - a consequence no honest label fits next to a
 * switch. It is a statement about what a vessel *is*, set once in config,
 * not a per-session preference.
 */
@Composable
private fun VesselCard(vessel: Vessel, onChange: (Vessel) -> Unit) {
    var grams by remember(vessel.name) { mutableStateOf(trimNumber(vessel.grams)) }
    // A learned weight landing while the screen is open moves the field, as
    // NumberSetting does - but not the echo of this field's own typing.
    LaunchedEffect(vessel.grams) {
        if (parseDecimal(grams) != vessel.grams) grams = trimNumber(vessel.grams)
    }
    val gramsError = parseDecimal(grams).let { g ->
        when {
            g == null -> if (grams.isBlank()) "Enter a value" else "Not a number"
            g < 1.0 -> "Must be at least 1"
            else -> null
        }
    }
    ReportValidity("vessel/${vessel.name}", "${vessel.name} weight", gramsError)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vessel.name, color = Color.White, fontSize = 15.sp)
                    if (vessel.arm) {
                        Text("arms the shot", color = TextSecondary, fontSize = 11.sp)
                    }
                }
                OutlinedTextField(
                    value = grams,
                    onValueChange = {
                        grams = it
                        parseDecimal(it)?.takeIf { g -> g >= 1.0 }?.let { g -> onChange(vessel.copy(grams = g)) }
                    },
                    label = { Text("bare g") },
                    singleLine = true,
                    isError = gramsError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.width(110.dp),
                )
            }
            if (gramsError != null) {
                Text(gramsError, color = ErrorRed, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "OPENS ON PLACEMENT",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VESSEL_PAGES.forEach { (route, label) ->
                    FilterChip(
                        selected = vessel.switchToPage == route,
                        onClick = { onChange(vessel.copy(switchToPage = route)) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = OnAccent,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            if (vessel.arm) {
                // Guarded by !v.arm at every use, so editable fields here would
                // be controls with nothing on the other end.
                Text(
                    "Matched empty only: a vessel you brew into is caught as it " +
                        "lands, never weighed with contents, so it has no loaded band.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberSetting(
                    label = "Loaded from (g)",
                    value = vessel.minContentsGrams,
                    min = 0.0,
                    key = "${vessel.name}/min",
                    modifier = Modifier.weight(1f),
                    onValue = { onChange(vessel.copy(minContentsGrams = it)) },
                )
                NumberSetting(
                    label = "Loaded to (g)",
                    value = vessel.maxContentsGrams,
                    min = 0.0,
                    key = "${vessel.name}/max",
                    modifier = Modifier.weight(1f),
                    help = "0 = matched empty only",
                    onValue = { onChange(vessel.copy(maxContentsGrams = it)) },
                )
            }
        }
    }
}

/**
 * Accent picker.
 *
 * A list of swatches rather than a dropdown or a row of chips: the thing being
 * chosen is a colour, so each option shows the colour at the size it will be
 * seen, on the black it will sit on. Takes effect on SAVE, like every other
 * setting here.
 */
@Composable
private fun AccentPicker(selected: AccentScheme, onSelect: (AccentScheme) -> Unit) {
    Text(
        "The accent only. The background stays black, and connected/dialed-in " +
            "green, adjust amber and error red keep their meaning in every scheme.",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    AccentScheme.entries.forEach { scheme ->
        val isSelected = scheme == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) SurfaceRaised else Color.Transparent)
                .clickable { onSelect(scheme) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(scheme.accent))
            Text(
                "  ${scheme.label}",
                color = if (isSelected) Color.White else TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Text("selected", color = scheme.accent, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The picture on the Micra page (2.15.0). La Marzocco's product photo is
 * theirs, so it can be replaced with the user's own or left out. Picking a
 * photo stores it at once (MachinePhoto); the choice itself waits for SAVE
 * like every other setting.
 */
@Composable
private fun MachinePicturePicker(selected: MachinePicture, onSelect: (MachinePicture) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPhoto by remember { mutableStateOf(MachinePhoto.exists(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    // The system photo picker: no storage permission, and only the one image
    // chosen is shared with the app.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = ""
        scope.launch {
            val ok = MachinePhoto.store(context, uri)
            busy = false
            hasPhoto = MachinePhoto.exists(context)
            message = if (ok) "Photo stored." else "That image could not be read."
            if (ok) onSelect(MachinePicture.OWN)
        }
    }

    Text("MACHINE PICTURE", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text(
        "Shown beside the machine's name on the Micra page. The La Marzocco photo " +
            "is theirs; use your own photo or none instead.",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    MachinePicture.entries.forEach { choice ->
        val isSelected = choice == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) SurfaceRaised else Color.Transparent)
                .clickable {
                    // My photo with nothing stored yet goes straight to the
                    // picker; the choice is made once a photo has arrived.
                    if (choice == MachinePicture.OWN && !hasPhoto) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        onSelect(choice)
                    }
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                choice.label,
                color = if (isSelected) Color.White else TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) Text("selected", color = Accent, fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("STORING…")
        } else {
            Text(if (hasPhoto) "CHOOSE ANOTHER PHOTO" else "CHOOSE PHOTO")
        }
    }
    if (message.isNotEmpty()) {
        Text(
            message,
            color = if (message.startsWith("Photo")) SuccessGreen else ErrorRed,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Who wrote this, which build it is, and the one preference that belongs
 * with that: whether the launch screen carrying the same words appears.
 */
@Composable
private fun AboutSection(showSplash: Boolean, onShowSplash: (Boolean) -> Unit) {
    val context = LocalContext.current

    Text(APP_NAME, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(APP_TAGLINE, color = TextSecondary, fontSize = 13.sp)
    Text("by $APP_AUTHOR", color = TextSecondary, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Version ${appVersionLabel(context)}",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))

    ToggleRow(
        title = "Show launch screen",
        subtitle = "Four seconds at startup. Tap it to skip.",
        checked = showSplash,
        onChange = onShowSplash,
    )
}

/**
 * Which scale the app talks to (2.16.0): the brand, which picks the protocol,
 * and a scan that fills in the address. Both wait for SAVE; saving a new
 * brand or address reconnects the scale.
 */
@Composable
private fun ScalePicker(scale: ScaleConfig, onChange: (ScaleConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Triple<String, String, ScaleModel>>?>(null) }
    val selected = ScaleModel.fromId(scale.model)

    Text(
        "The scale brand decides how the app talks to it. Scan to find the scale " +
            "and fill in its address, or type the address below.",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))
    ScaleModel.entries.forEach { model ->
        val isSelected = model == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) SurfaceRaised else Color.Transparent)
                .clickable { onChange(scale.copy(model = model.id)) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.label,
                color = if (isSelected) Color.White else TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) Text("selected", color = Accent, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            scanning = true
            found = null
            scope.launch {
                found = CoffeeRuntime.scanForScales()
                scanning = false
            }
        },
        enabled = !scanning,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("SCANNING…")
        } else {
            Text("SCAN FOR SCALE")
        }
    }
    found?.let { list ->
        Spacer(Modifier.height(6.dp))
        if (list.isEmpty()) {
            Text(
                "No scale found. Is it switched on, and not connected to this app " +
                    "or another one? A connected scale does not advertise.",
                color = WarningAmber,
                fontSize = 12.sp,
            )
        }
        list.forEach { (name, address, model) ->
            val picked = address.equals(scale.macAddress, ignoreCase = true)
            Text(
                "${if (picked) "● " else "○ "}$name  $address  (${model.label})",
                color = if (picked) Accent else Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    // Picking a scale sets its brand too.
                    .clickable { onChange(scale.copy(macAddress = address, model = model.id)) }
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/**
 * Local BLE control of the machine: pick it from a scan, switch local control
 * on, and watch the link come up.
 *
 * Picking and the switch are draft edits like everything else here and only
 * take effect on SAVE (which rebuilds the link). The status line and
 * RECONNECT NOW act on the link as saved, immediately.
 *
 * The token normally arrives by itself from the cloud's settings response;
 * the field is the fallback for when it does not. Masked, never logged.
 */
@Composable
private fun MachineBluetoothSection(
    machine: MachineConfig,
    savedMachine: MachineConfig,
    bleToken: String,
    onBleToken: (String) -> Unit,
    onChange: (MachineConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val linkState by CoffeeRuntime.bleState.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    Text(
        "Controls the machine over Bluetooth, with the cloud as fallback whenever " +
            "the link is down. The app connects only around a shot: from the " +
            "dosing cup landing (up to 10 minutes) or the arm, through the shot, " +
            "and for two minutes after. It holds the Micra's only Bluetooth slot " +
            "meanwhile, so the official La Marzocco app cannot connect then.",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    ToggleRow(
        title = "Local control",
        subtitle = if (machine.bleAddress.isBlank()) "Scan and pick the machine first." else machine.bleAddress,
        checked = machine.localControl,
        enabled = machine.bleAddress.isNotBlank(),
        onChange = { onChange(machine.copy(localControl = it)) },
    )
    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            scanning = true
            found = null
            scope.launch {
                found = CoffeeRuntime.scanForMachines()
                scanning = false
            }
        },
        enabled = !scanning,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("SCANNING…")
        } else {
            Text("SCAN FOR MACHINE")
        }
    }
    found?.let { list ->
        Spacer(Modifier.height(6.dp))
        if (list.isEmpty()) {
            Text(
                "No machine found. Is it on, and is the official app (or this " +
                    "app's own link) holding the connection?",
                color = WarningAmber,
                fontSize = 12.sp,
            )
        }
        list.forEach { (name, address) ->
            val picked = address.equals(machine.bleAddress, ignoreCase = true)
            Text(
                "${if (picked) "● " else "○ "}$name  $address",
                color = if (picked) Accent else Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onChange(machine.copy(bleAddress = address)) }
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(vertical = 10.dp),
            )
        }
    }

    if (savedMachine.localControl) {
        Spacer(Modifier.height(8.dp))
        val (label, color) = when (linkState) {
            MicraBleClient.State.CONNECTED -> "Connected" to SuccessGreen
            MicraBleClient.State.CONNECTING -> "Connecting…" to TextSecondary
            MicraBleClient.State.BAD_TOKEN ->
                "Token rejected. Refresh the cloud, or enter the token below." to ErrorRed
            MicraBleClient.State.CONFIG_MODE ->
                "The machine is in configuration mode. Restart it." to WarningAmber
            MicraBleClient.State.UNREACHABLE ->
                "Not reachable. Retrying; using the cloud meanwhile." to WarningAmber
            MicraBleClient.State.RELEASED ->
                "Idle. Connects when a shot is armed." to TextSecondary
            MicraBleClient.State.OFF -> "Off" to TextSecondary
        }
        Text("Link: $label", color = color, fontSize = 13.sp)
        // In RELEASED too, labelled for what it does there: idle is the
        // intended state, so a press opens one two-minute session (live
        // boiler readings, or a check that the link still works).
        if (linkState != MicraBleClient.State.CONNECTED && linkState != MicraBleClient.State.OFF) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { CoffeeRuntime.reconnectMachineNow() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (linkState == MicraBleClient.State.RELEASED) "CONNECT FOR 2 MIN" else "RECONNECT NOW")
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = bleToken,
        onValueChange = onBleToken,
        label = { Text("Bluetooth token") },
        supportingText = {
            Text(
                if (bleToken.isBlank()) {
                    "Normally filled in from the cloud. Enter it here if it never arrives."
                } else {
                    "Synced from the cloud. Only change it if the link reports a rejected token."
                },
            )
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Force a copy of the history and log into Documents.
 *
 * The mirror already runs at launch and after every saved shot, so this is not
 * how the backup normally happens - it is for the moment before something that
 * might wipe app data, where waiting for the next launch is exactly the thing
 * that will not happen.
 */
@Composable
private fun BackupSection() {
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    Text(
        "The shot history and current log are copied to " +
            "Documents/${DocumentsBackup.FOLDER} at every launch and after every " +
            "saved shot. They stay there if the app is uninstalled. Press this " +
            "before anything that might reinstall the app. Cloud credentials are " +
            "not included - the key that encrypts them cannot leave this device.",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            busy = true
            message = ""
            scope.launch {
                val result = CoffeeRuntime.backupNow()
                busy = false
                failed = !result.ok
                message = result.message
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("BACKING UP…")
        } else {
            Text("BACK UP NOW")
        }
    }

    if (message.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(message, color = if (failed) ErrorRed else SuccessGreen, fontSize = 13.sp)
    }
}

/**
 * Open or share the current log file.
 *
 * It matters more than it looks: the auto-tare narration is kept at INFO precisely so the
 * guard values can be tuned from it, and "why did auto-arm not fire?" is a
 * question asked at the machine, hours later - not while tethered to a laptop
 * reading logcat, which is a ring buffer that is wiped on reboot anyway.
 *
 * OPEN reads it here and now in whatever text viewer the phone already has;
 * SHARE gets it off the device. Both go through the same FileProvider grant,
 * so neither needs a storage permission.
 */
@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }

    val logFile = FileLog.currentFile
    val sizeKb = (logFile?.length() ?: 0L) / 1024

    /** A read grant for the log, or null if there is nothing to grant yet. */
    fun logUri(): Uri? {
        val file = FileLog.currentFile
        if (file == null || !file.exists()) {
            error = "No log file yet."
            return null
        }
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        } catch (e: Exception) {
            error = "Could not access the log file: ${e.message}"
            null
        }
    }

    Text(
        if (logFile == null) {
            "No log file yet."
        } else {
            "${logFile.name} · $sizeKb KB. A new file is started each launch and " +
                "each day; files older than ${FileLog.RETENTION_DAYS} days are deleted. The log contains the " +
                "scale and machine Bluetooth addresses and the machine serial (never " +
                "passwords or tokens), and is copied to Documents/${DocumentsBackup.FOLDER}."
        },
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = {
                error = ""
                val uri = logUri() ?: return@OutlinedButton
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/plain")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    // Plenty of phones ship without anything that claims
                    // text/plain. Say so plainly and point at the button that
                    // does work, rather than failing silently.
                    error = "No app on this phone can open a text file. " +
                        "Use SHARE LOG and send it to yourself instead."
                }
            },
            enabled = logFile != null,
            modifier = Modifier.weight(1f).height(48.dp),
        ) { Text("OPEN LOG") }

        OutlinedButton(
            onClick = {
                error = ""
                val uri = logUri() ?: return@OutlinedButton
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, FileLog.currentFile?.name.orEmpty())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(Intent.createChooser(send, "Share log"))
                } catch (e: Exception) {
                    error = "Could not share the log: ${e.message}"
                }
            },
            enabled = logFile != null,
            modifier = Modifier.weight(1f).height(48.dp),
        ) { Text("SHARE LOG") }
    }

    if (error.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(error, color = ErrorRed, fontSize = 13.sp)
    }
}

/**
 * Restore shots and beans from a coffee_history.db.
 *
 * Normally that file is the app's own mirror in Documents/CoffeeApp, which is
 * the copy that survives an uninstall; an older database file still works
 * too, since the importer reads both schemas.
 *
 * Repeatable by design: it merges rather than replaces, so picking the same
 * file twice tops up with whatever is new instead of duplicating history.
 */
@Composable
private fun RestoreSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = ""
        scope.launch {
            val result = CoffeeRuntime.importDatabase(context, uri)
            busy = false
            failed = !result.ok
            message = result.summary()
        }
    }

    Text(
        "Pick a database to merge into this app - normally the newest " +
            "coffee_history_<date>.db in Documents/${DocumentsBackup.FOLDER}, which " +
            "stays behind when the app is uninstalled. An older coffee_history.db " +
            "works too. Shots already on this phone are skipped, so this is safe to repeat.",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        // Deliberately unfiltered: a .db lands with wildly inconsistent MIME
        // types depending on how it reached the phone, and a strict filter
        // greys out the very file the user is trying to select.
        onClick = { picker.launch(arrayOf("*/*")) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("RESTORING…")
        } else {
            Text("CHOOSE BACKUP FILE")
        }
    }

    if (message.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = if (failed) ErrorRed else SuccessGreen,
            fontSize = 13.sp,
        )
    }
}
