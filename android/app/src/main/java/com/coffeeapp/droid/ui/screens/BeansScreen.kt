// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.coffeeapp.droid.ui.parseDecimal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.core.model.Bean
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.SuccessGreen
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.TextSecondary

/**
 * The bean journal.
 *
 * Exactly one bean is current, and it is stamped onto every shot that is
 * saved - so the radio button is the point of this screen, not the list. The
 * grind setting travels with the bean because that is the pairing you actually
 * want back when a bag comes round again.
 */
@Composable
fun BeansScreen() {
    // Remembered: a new Room Flow on every recomposition restarted the query each time.
    val beansFlow = remember { CoffeeRuntime.listBeans() }
    // Null until Room answers, so "No beans yet" does not flash on every open.
    val loaded by beansFlow.collectAsStateWithLifecycle(initialValue = null)
    val beans = loaded.orEmpty()

    var newName by rememberSaveable { mutableStateOf("") }
    var newGrind by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Bean") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = newGrind,
                onValueChange = { newGrind = it },
                label = { Text("Grind") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // Blank grind means "not set yet" (0); anything else must parse -
        // "8,5" used to be stored as 0 without a word.
        val grindValue = if (newGrind.isBlank()) 0.0 else parseDecimal(newGrind)
        Button(
            onClick = {
                val name = newName.trim()
                if (name.isNotEmpty() && grindValue != null) {
                    CoffeeRuntime.addBean(name, grindValue)
                    newName = ""
                    newGrind = ""
                }
            },
            enabled = newName.isNotBlank() && grindValue != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("ADD BEAN") }

        Spacer(Modifier.height(12.dp))

        if (loaded != null && beans.isEmpty()) {
            Text(
                "No beans yet. Add one above and select it to stamp it onto your shots.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(beans, key = { it.id }) { bean -> BeanCard(bean) }
        }
    }
}

@Composable
private fun BeanCard(bean: Bean) {
    // The bin sits immediately right of the two thumb buttons, which are
    // tapped routinely to rate a bean - the highest mis-tap exposure in the
    // app, and the one deletion with consequences beyond the row itself.
    var confirming by remember(bean.id) { mutableStateOf(false) }
    var editingGrind by remember(bean.id) { mutableStateOf(false) }
    var grindText by remember(bean.id) { mutableStateOf(formatTrim(bean.grindSetting)) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this bean?") },
            text = {
                Text(
                    "\"${bean.name}\" will be removed from the journal.\n\n" +
                        "Shots pulled with it are kept, and keep their grind " +
                        "setting, but they will no longer show a bean name.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    CoffeeRuntime.deleteBean(bean.id)
                }) {
                    Text("DELETE", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("CANCEL") }
            },
        )
    }

    if (editingGrind) {
        AlertDialog(
            onDismissRequest = { editingGrind = false },
            title = { Text("Grind for ${bean.name}") },
            text = {
                OutlinedTextField(
                    value = grindText,
                    onValueChange = { grindText = it },
                    label = { Text("Grind") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        parseDecimal(grindText)?.let {
                            CoffeeRuntime.updateBean(bean.id, bean.name, it, bean.tasty)
                            editingGrind = false
                        }
                    }),
                )
            },
            confirmButton = {
                // Only a parsable value is written; anything else leaves the
                // stored setting alone rather than zeroing it.
                TextButton(
                    enabled = parseDecimal(grindText) != null,
                    onClick = {
                        parseDecimal(grindText)?.let {
                            CoffeeRuntime.updateBean(bean.id, bean.name, it, bean.tasty)
                        }
                        editingGrind = false
                    },
                ) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { editingGrind = false }) { Text("CANCEL") }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selecting a bean is the primary action here, so it gets the
            // leading control rather than a menu buried behind a long press.
            // Named, so TalkBack reads "<bean>, selected" rather than a
            // nameless "Radio button, not selected".
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .selectable(
                        selected = bean.isCurrent,
                        role = Role.RadioButton,
                        onClick = { CoffeeRuntime.setActiveBean(bean.id) },
                    )
                    .semantics { contentDescription = bean.name },
            ) {
                RadioButton(selected = bean.isCurrent, onClick = null)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    bean.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = if (bean.isCurrent) FontWeight.Bold else FontWeight.Normal,
                )
                // Tappable: a grinder gets nudged between bags, and the
                // setting recorded here is what a shot is judged against, so
                // it has to be correctable without deleting and re-adding the
                // bean. The pencil is what says so - a bare line of grey text
                // reads as a readout.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 48 dp tall: it was ~20, the size of its 12 sp text, and
                    // the only way to correct a grind setting.
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Edit grind", role = Role.Button) {
                            grindText = formatTrim(bean.grindSetting)
                            editingGrind = true
                        },
                ) {
                    Text(
                        "grind ${formatTrim(bean.grindSetting)}  ",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Change grind setting",
                        tint = TextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            // tasty is tri-state: liked, disliked, or unrated. Tapping the
            // active thumb clears it back to unrated.
            IconButton(onClick = {
                val next = if (bean.tasty == true) null else true
                CoffeeRuntime.updateBean(bean.id, bean.name, bean.grindSetting, next)
            }) {
                Icon(
                    Icons.Filled.ThumbUp,
                    contentDescription = if (bean.tasty == true) "Rated good (tap to clear)" else "Rate good",
                    tint = if (bean.tasty == true) SuccessGreen else TextSecondary,
                )
            }
            IconButton(onClick = {
                val next = if (bean.tasty == false) null else false
                CoffeeRuntime.updateBean(bean.id, bean.name, bean.grindSetting, next)
            }) {
                Icon(
                    Icons.Filled.ThumbDown,
                    contentDescription = if (bean.tasty == false) "Rated bad (tap to clear)" else "Rate bad",
                    tint = if (bean.tasty == false) Accent else TextSecondary,
                )
            }
            IconButton(onClick = { confirming = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete bean", tint = Accent)
            }
        }
    }
}
