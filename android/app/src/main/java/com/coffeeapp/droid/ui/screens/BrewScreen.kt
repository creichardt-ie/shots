// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.core.model.ShotState
import com.coffeeapp.droid.core.model.TimerSource
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.OnAccent
import com.coffeeapp.droid.ui.theme.ReadoutLarge
import com.coffeeapp.droid.ui.theme.SuccessGreen
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.TextDim
import com.coffeeapp.droid.ui.theme.TextSecondary
import com.coffeeapp.droid.ui.theme.coffeeSwitchColors
import com.coffeeapp.droid.ui.theme.WarningAmber
import com.coffeeapp.droid.core.cloud.CloudProblem
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import com.coffeeapp.droid.ui.AutoSizeReadout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.coffeeapp.droid.ui.theme.ErrorRed
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The brew-by-weight screen - the one that is actually looked at during a shot.
 *
 * A pure observer: it reads the snapshot and draws, without polling.
 * ShotService publishes a snapshot per weight sample while a shot is live,
 * and Compose recomposes on that. The refresh rate has no bearing at all on
 * shot detection or BBW timing.
 */
@Composable
fun BrewScreen(snapshot: AppSnapshot) {
    val shot = snapshot.shot
    val config by CoffeeRuntime.configFlow.collectAsStateWithLifecycle()

    // The target lives in the runtime, not here: it must survive leaving the
    // screen, the shot service is what acts on it, and the stored value can
    // arrive after the first frame. Read from the snapshot every time - a
    // local copy kept showing the value from first composition.
    val target = shot.targetYield.toFloat()

    val scope = rememberCoroutineScope()
    var taring by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }

    val shotActive = shot.state == ShotState.BREWING || shot.state == ShotState.STOPPING
    val finished = shot.state == ShotState.FINALIZED

    // While a shot runs the weight comes from the shot context (which freezes
    // an implausible reading); at rest it is the live scale.
    val displayWeight = if (shotActive || finished) shot.weight else snapshot.scale.weight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        // Centred, so the slack sits half above and half below instead of
        // pooling under the buttons. Safe alongside the scroll: fillMaxSize
        // is outside it, so the column is measured with a minimum height of
        // one viewport and a maximum of infinity - it centres when the
        // content fits and simply grows and scrolls when it does not.
        verticalArrangement = Arrangement.Center,
    ) {
        // Active bean, read-only. Managed on the Beans screen.
        if (snapshot.activeBeanName.isNotEmpty()) {
            val grind = snapshot.activeBeanGrind?.let {
                " · grind ${formatTrim(it)}"
            } ?: ""
            Text(
                "${snapshot.activeBeanName}$grind",
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }

        // Auto-tare narration - a readout, not a control, so the screen stays
        // observer-only.
        if (shot.autoTareHint.isNotEmpty()) {
            Text(shot.autoTareHint, color = TextSecondary, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))

        // --- Timer and weight ------------------------------------------
        Row(modifier = Modifier.fillMaxWidth()) {
            // From pump-on when BLE gave it; "SCALE TIME" marks the fallback
            // (from the first drops), so the two are never read as one.
            Readout(
                label = if (shot.timerSource == TimerSource.SCALE) "SCALE TIME" else "TIME",
                value = String.format(Locale.US, "%.1f", shot.elapsedSeconds),
                unit = "s",
                color = if (shotActive || shot.timerSource == TimerSource.PUMP && !finished) Accent else TextDim,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "WEIGHT",
                value = String.format(Locale.US, "%.1f", displayWeight),
                unit = "g",
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        val progress = if (target > 0) {
            min(max(displayWeight.toFloat() / target, 0f), 1f)
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (shot.stopSent) SuccessGreen else Accent,
            trackColor = SurfaceDark,
            // Material 3 now draws a dot at the end of the track (the "stop
            // indicator") and a gap between the fill and the track. Here the
            // bar is a plain fill towards the target yield; the dot read as a
            // stray pixel beside the weight.
            gapSize = 0.dp,
            drawStopIndicator = {},
        )

        Spacer(Modifier.height(10.dp))
        StatusLine(shot.state, shot.armed, shot.saved, shot.grindHint)
        if (shot.alert.isNotEmpty()) {
            Text(
                shot.alert,
                color = ErrorRed,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            // Shown with the link up too: BLE carries the shot, but the cloud
            // is the stop's fallback and the source of machine time.
            CloudProblem.warning(
                snapshot.machine.cloudProblem,
                snapshot.machine.cloudProblemSince,
                System.currentTimeMillis(),
            )?.let {
                Text(
                    it,
                    color = WarningAmber,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        // Live flow rate, only while a shot is actually pulling.
        AnimatedVisibility(visible = shotActive && shot.flowRateGps > 0) {
            Text(
                String.format(Locale.US, "Flow %.1f g/s", shot.flowRateGps),
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- Arm gate ---------------------------------------------------
        // The state machine will not start a shot until this is pressed, so an
        // accidental bump cannot start a false shot or fire a premature stop.
        if (shot.state == ShotState.IDLE) {
            if (!shot.armed) {
                Button(
                    onClick = { CoffeeRuntime.armShot() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    // contentColor too, not just the fill: three of the five
                    // accents are light enough that the default white label
                    // would be unreadable on them.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent,
                    ),
                ) {
                    Text("READY", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 2:1, not 3:1. At a quarter of the row the button was
                    // narrower than its own label once the default content
                    // padding was taken off, so DISARM wrapped its last letter
                    // onto a second line. The smaller status text is what buys
                    // the width back without shrinking the banner's box.
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SuccessGreen.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "ARMED — waiting for shot",
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { CoffeeRuntime.disarmShot() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("DISARM", fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // --- Tare / Reset ------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    taring = true
                    scope.launch {
                        CoffeeRuntime.tareNow()
                        taring = false
                    }
                },
                // Not mid-shot: a tare then made every later sample an
                // implausible step, so the BBW stop never fired.
                enabled = !taring && !shotActive,
                modifier = Modifier.weight(1f).height(50.dp),
            ) {
                if (taring) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("TARE")
                }
            }
            OutlinedButton(
                // Mid-shot RESET drops the shot, so it asks first - and then
                // stops the machine, rather than leaving it pouring.
                onClick = { if (shotActive) confirmingReset = true else CoffeeRuntime.resetShot() },
                modifier = Modifier.weight(1f).height(50.dp),
            ) { Text("RESET") }
        }

        if (confirmingReset) {
            AlertDialog(
                onDismissRequest = { confirmingReset = false },
                title = { Text("Stop this shot?") },
                text = { Text("The machine is stopped and the shot is dropped, not saved.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingReset = false
                        CoffeeRuntime.resetShot()
                    }) { Text("STOP AND RESET") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingReset = false }) { Text("KEEP BREWING") }
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- Target yield -------------------------------------------------
        Text("TARGET YIELD", color = TextSecondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatButton("−", "Lower the target") {
                CoffeeRuntime.stepTargetYield(-0.5)
            }

            // Tappable: 0.5g steps are right for nudging a dialled-in target
            // and hopeless for changing it outright - 36 to 20 is 32 presses.
            Text(
                String.format(Locale.US, "  %.1f g  ", target),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Type the target yield", role = Role.Button) { editingTarget = true }
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )

            RepeatButton("+", "Raise the target") {
                CoffeeRuntime.stepTargetYield(0.5)
            }

            Text("  tap to type", color = TextDim, fontSize = 11.sp)
        }

        if (editingTarget) {
            TargetDialog(
                initial = target,
                onDismiss = { editingTarget = false },
                onConfirm = {
                    CoffeeRuntime.setTargetYield(it.toDouble())
                    editingTarget = false
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Brew by weight -----------------------------------------------
        // The whole row toggles, so TalkBack reads "Brew by weight" with the
        // state rather than a nameless switch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = config.brewByWeight.enabled,
                    role = Role.Switch,
                    onValueChange = { CoffeeRuntime.setBrewByWeight(it) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Brew by weight", color = Color.White, fontSize = 15.sp)
                Text(
                    if (config.brewByWeight.enabled) {
                        String.format(
                            Locale.US,
                            "Stops the machine at ~%.1f g · learned offset %.1f g",
                            target - shot.currentOffsetGrams, shot.currentOffsetGrams,
                        )
                    } else {
                        "The machine will not be stopped automatically"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = config.brewByWeight.enabled,
                onCheckedChange = null,
                colors = coffeeSwitchColors(),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A step button that repeats while it is held.
 *
 * A tap is one step; holding past [HOLD_BEFORE_REPEAT_MS] runs steps every
 * [REPEAT_INTERVAL_MS] until release. The [repeated] latch swallows the click
 * that Compose delivers on release after a hold, which would otherwise add one
 * more step than the user asked for.
 */
@Composable
private fun RepeatButton(label: String, description: String, onStep: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var repeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        // Cleared on press, not only on the click that ends a hold: releasing
        // with the finger dragged off the button delivers no click, and a
        // latch left set would swallow the next tap.
        repeated = false
        delay(HOLD_BEFORE_REPEAT_MS)
        while (pressed) {
            repeated = true
            onStep()
            delay(REPEAT_INTERVAL_MS)
        }
    }

    OutlinedButton(
        onClick = { if (repeated) repeated = false else onStep() },
        interactionSource = interaction,
        modifier = Modifier.semantics { contentDescription = description },
    ) { Text(label) }
}

private const val HOLD_BEFORE_REPEAT_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/** Type a target outright, for the jumps the step buttons are wrong for. */
@Composable
private fun TargetDialog(initial: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var text by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.1f", initial)) }
    val parsed = com.coffeeapp.droid.ui.parseDecimal(text)?.toFloat()
    // The same floor as the +/- buttons (CoffeeRuntime.applyTargetYield):
    // below it the BBW stop is off or fires on the first drops.
    val min = CoffeeRuntime.MIN_TARGET_YIELD_GRAMS.toFloat()
    val ok = parsed != null && parsed >= min

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target yield") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Grams") },
                singleLine = true,
                isError = !ok,
                supportingText = if (!ok) {
                    { Text("At least ${formatTrim(min.toDouble())} g") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) parsed?.let(onConfirm) }),
            )
        },
        confirmButton = {
            // Only a sane number is accepted; anything else leaves the target
            // where it was rather than arming a shot against zero.
            TextButton(
                enabled = ok,
                onClick = { parsed?.let(onConfirm) },
            ) { Text("SET") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

@Composable
private fun Readout(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            // At most 68 dp, a touch under the shared 76: two readouts share
            // the row here, where the Scale page has its weight alone. Sized
            // on the phone against a typical shot (27.7 s, 36.1 g). In dp, not
            // sp, so a large system font cannot overflow the row - and shrunk
            // to fit, so a five-digit value (a mug before the tare) is not
            // clipped mid-digit. The unit keeps its slot either way.
            val size = with(LocalDensity.current) { 68.dp.toSp() }
            AutoSizeReadout(
                value,
                style = ReadoutLarge.copy(color = color),
                maxSize = size,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                unit,
                color = TextDim,
                fontSize = with(LocalDensity.current) { 22.dp.toSp() },
                maxLines = 1,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(state: ShotState, armed: Boolean, saved: Boolean, grindHint: String) {
    val (text, color) = when {
        state == ShotState.FINALIZED && saved -> "SHOT SAVED" to SuccessGreen
        state == ShotState.FINALIZED -> "Finished, not saved (too short or too light)" to TextSecondary
        state == ShotState.STOPPING -> "Stop sent — dripping…" to Accent
        state == ShotState.BREWING -> "Extracting…" to TextSecondary
        armed -> "Waiting for shot…" to TextDim
        else -> "Press READY to arm the scale" to TextDim
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = color,
            fontSize = 15.sp,
            fontWeight = if (state == ShotState.FINALIZED && saved) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (grindHint.isNotEmpty() && state == ShotState.FINALIZED) {
            Text(
                grindHint,
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 8.0 -> "8", 8.5 -> "8.5" - grind settings read badly with a trailing zero. */
internal fun formatTrim(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
