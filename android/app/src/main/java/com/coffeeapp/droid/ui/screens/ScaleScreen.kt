// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.coffeeapp.droid.ui.AutoSizeReadout
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.core.model.AppSnapshot
import com.coffeeapp.droid.ui.theme.ReadoutLarge
import com.coffeeapp.droid.ui.theme.TextDim
import com.coffeeapp.droid.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The plain scale: a big number and a tare button.
 *
 * Used for weighing a dose and for steaming milk, so the weight is the whole
 * screen. Where the brew screen shows the shot context weight, this one always
 * shows the live scale - it is a scale, not a shot readout.
 */
@Composable
fun ScaleScreen(snapshot: AppSnapshot) {
    val weight = snapshot.scale.weight
    val shot = snapshot.shot
    val scope = rememberCoroutineScope()
    // Against a sleeping scale a tare waits on a full reconnect first, which
    // is seconds to minutes - long enough that the button has to say so.
    var taring by remember { mutableStateOf(false) }

    // Scrolls, like Brew: at a large font the alert, the net line and the
    // spacer could push TARE below the screen with no way to reach it.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (shot.alert.isNotEmpty()) {
            Text(
                shot.alert,
                color = com.coffeeapp.droid.ui.theme.ErrorRed,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            // Shrinks to fit: a milk pitcher ("650.3") at 76 sp, or any
            // value at a large font, was clipped mid-digit.
            AutoSizeReadout(
                String.format(Locale.US, "%.1f", weight),
                style = ReadoutLarge.copy(color = Color.White),
                maxSize = ReadoutLarge.fontSize,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "g",
                color = TextDim,
                fontSize = 26.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        // Auto-tare net readout.
        //
        // The offset is subtracted from the LIVE reading rather than showing a
        // value published by the shot service, so the number tracks at the
        // scale own sample rate - pouring beans in ticks up in real time. The
        // vessel weight is shown alongside because the net is derived, not
        // measured: a stale vessel tare would otherwise be invisible.
        val netOffset = shot.netOffset
        if (netOffset != null) {
            val net = weight - netOffset
            val vessel = shot.netVesselGrams?.let {
                String.format(Locale.US, " (vessel %.1f g)", it)
            } ?: ""
            Spacer(Modifier.height(8.dp))
            Text(
                text = String.format(Locale.US, "%.1f g net · %s%s", net, shot.netLabel, vessel),
                color = TextSecondary,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = {
                taring = true
                scope.launch {
                    CoffeeRuntime.tareNow()
                    taring = false
                }
            },
            // Not mid-shot: a tare then stops the BBW stop from ever firing.
            enabled = !taring && shot.state != com.coffeeapp.droid.core.model.ShotState.BREWING &&
                shot.state != com.coffeeapp.droid.core.model.ShotState.STOPPING,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) {
            if (taring) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("TARING…", fontSize = 18.sp)
            } else {
                Text("TARE", fontSize = 18.sp)
            }
        }
    }
}
