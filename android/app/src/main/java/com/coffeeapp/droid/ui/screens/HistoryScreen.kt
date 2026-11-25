// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.core.db.ShotWithBean
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.SuccessGreen
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.TextDim
import com.coffeeapp.droid.ui.theme.TextSecondary
import com.coffeeapp.droid.ui.theme.WarningAmber
import java.util.Locale

/**
 * Shot history.
 *
 * A table on a phone is unreadable, so each shot is a card, leading with the two numbers that actually get
 * compared between shots - time and yield - and carrying the grind verdict as
 * a coloured tag.
 *
 * The list is a Room Flow, so a shot saved while this screen is open appears
 * on its own.
 */
@Composable
fun HistoryScreen() {
    // A page of 100 at a time, with a count, so older shots are reachable
    // and it is plain that there are more. The flows are remembered: a new
    // Room Flow per recomposition restarted the query every time.
    var limit by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    val shotsFlow = remember(limit) { CoffeeRuntime.recentShots(limit) }
    // Null until Room answers: an empty initial value flashed "No shots
    // recorded yet" on every open, which after a restore read as data loss.
    val loaded by shotsFlow.collectAsStateWithLifecycle(initialValue = null)
    val countFlow = remember { CoffeeRuntime.shotCount() }
    val total by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val shots = loaded ?: return

    if (shots.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No shots recorded yet", color = TextSecondary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        items(shots, key = { it.id }) { shot ->
            ShotCard(shot)
        }
        item(key = "footer") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Showing ${shots.size} of $total shots",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                if (shots.size < total) {
                    TextButton(onClick = { limit += PAGE_SIZE }) { Text("SHOW $PAGE_SIZE MORE") }
                }
            }
        }
    }
}

private const val PAGE_SIZE = 100

@Composable
private fun ShotCard(shot: ShotWithBean) {
    // The bin sits inside a scrolling list of cards, a thumb's width from the
    // card you were reading. A stray tap used to delete a shot outright, with
    // no undo and nothing but the Documents backup to recover it from.
    var confirming by remember(shot.id) { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this shot?") },
            // Names the shot rather than asking in the abstract, so a
            // mis-tapped row is obvious before it is confirmed.
            text = {
                Text(
                    String.format(
                        Locale.US,
                        "%s - %.1f g%s\n\nThis cannot be undone.",
                        shot.timestamp,
                        shot.yieldGrams ?: 0.0,
                        shot.beanName?.let { " - $it" } ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    CoffeeRuntime.deleteShots(listOf(shot.id))
                }) {
                    Text("DELETE", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("CANCEL") }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // Machine time is the true pump-on figure; prefer it and
                    // say which one is being shown, since they differ by a
                    // second or two and comparing the wrong pair is misleading.
                    val machine = shot.durationMachine
                    val seconds = machine ?: shot.durationSeconds ?: 0.0
                    Text(
                        String.format(Locale.US, "%.1fs", seconds),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (machine != null) "  machine" else "  scale",
                        color = TextDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(
                        String.format(Locale.US, "   %.1f g", shot.yieldGrams ?: 0.0),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row {
                    Text(shot.timestamp, color = TextSecondary, fontSize = 12.sp)
                    shot.ratio?.let {
                        Text(String.format(Locale.US, "  ·  1:%.1f", it), color = TextSecondary, fontSize = 12.sp)
                    }
                    shot.peakFlowGps?.let {
                        Text(String.format(Locale.US, "  ·  peak %.1f g/s", it), color = TextSecondary, fontSize = 12.sp)
                    }
                }

                Row {
                    shot.beanName?.let {
                        Text(it, color = TextSecondary, fontSize = 12.sp)
                    }
                    shot.grindSetting?.let {
                        Text("  grind ${formatTrim(it)}", color = TextDim, fontSize = 12.sp)
                    }
                }

                shot.grindRec?.let { rec ->
                    val (label, color) = when (rec) {
                        "finer" -> "grind finer" to WarningAmber
                        "coarser" -> "grind coarser" to WarningAmber
                        else -> "dialed in" to SuccessGreen
                    }
                    Text(label, color = color, fontSize = 12.sp)
                }
            }

            IconButton(onClick = { confirming = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete shot", tint = Accent)
            }
        }
    }
}
