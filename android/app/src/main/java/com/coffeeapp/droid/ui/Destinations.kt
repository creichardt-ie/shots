// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation destinations.
 *
 * Five is the practical limit for a bottom bar. Configuration is opened
 * rarely and deliberately, so it lives in the app bar and the five daily
 * screens keep the bottom row.
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** The section of the bundled user guide that describes this page. */
    val helpAnchor: String,
) {
    Brew("brewing", "Brew", Icons.Filled.Coffee, "brew-h"),
    Scale("scale", "Scale", Icons.Filled.Scale, "scale-h"),
    Beans("beans", "Beans", Icons.Filled.Grain, "beans-h"),
    History("history", "History", Icons.Filled.History, "history-h"),
    Machine("machine", "Micra", Icons.Filled.Tune, "micra-h"),
    ;

    companion object {
        /** The bottom bar, in order. */
        val bottomBar = listOf(Brew, Scale, Beans, History, Machine)

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}

/** Configuration is reached from the app bar, not the bottom bar. */
const val SETTINGS_ROUTE = "settings"

/** The guide's section for Configuration. */
const val SETTINGS_HELP_ANCHOR = "config-h"

/** A section of the user guide, as Help shows it: one at a time, by tab. */
data class HelpSection(val label: String, val anchor: String)

/** Help's tabs, in the guide's order. The anchors are element ids in APP_GUIDE.html. */
val HELP_SECTIONS = listOf(
    HelpSection("Overview", "flow-h"),
    HelpSection("Status bar", "bar-h"),
    HelpSection("Scale", "scale-h"),
    HelpSection("Brew", "brew-h"),
    HelpSection("Beans", "beans-h"),
    HelpSection("History", "history-h"),
    HelpSection("Micra", "micra-h"),
    HelpSection("Configuration", SETTINGS_HELP_ANCHOR),
)

/** Help (the bundled user guide), opened at a section of it. */
const val HELP_ROUTE = "help/{section}"

fun helpRoute(section: String): String = "help/$section"

/** The guide section for the page on screen; null opens the guide at the top. */
fun helpAnchorFor(route: String?): String? = when (route) {
    SETTINGS_ROUTE -> SETTINGS_HELP_ANCHOR
    else -> Destination.fromRoute(route)?.helpAnchor
}
