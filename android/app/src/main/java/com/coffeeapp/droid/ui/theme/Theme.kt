// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.coffeeapp.droid.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coffeeapp.droid.core.CoffeeRuntime

/**
 * Theme: a black panel with La Marzocco red, read from across a kitchen,
 * expressed as a Material 3 scheme so the components come out native.
 *
 * The app is dark-only on purpose. It is looked at in a kitchen, often in low
 * light, and a white screen behind a shot timer is genuinely unpleasant; a
 * light scheme would also make the red unreadable at the weights it is used at.
 */

val SuccessGreen = Color(0xFF28A745)
val WarningAmber = Color(0xFFF0AD4E)
val ErrorRed = Color(0xFFDC3545)

/** The machine's Bluetooth link indicator in the status bar. */
val BluetoothBlue = Color(0xFF0082FC)

val PanelBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF111111)
val SurfaceRaised = Color(0xFF1C1C1C)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF888888)
/**
 * The quietest text. #767676 is the darkest grey with 4.5:1 contrast on
 * black (WCAG AA); the old #555555 was 2.6:1, too faint for hints like
 * "Press READY to arm the scale".
 */
val TextDim = Color(0xFF767676)

/** Behind the logo, matching the launcher icon's background. */
val IconCream = Color(0xFFF2EBDD)

private fun coffeeColorScheme(scheme: AccentScheme) = darkColorScheme(
    primary = scheme.accent,
    onPrimary = scheme.onAccent,
    secondary = SuccessGreen,
    onSecondary = Color.Black,
    background = PanelBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.White,
    outline = Color(0xFF333333),
)

/**
 * Big numeric readouts use a monospace face.
 *
 * Not decoration: proportional digits change width as they change value, so a
 * live weight or a running timer visibly jitters left and right while you are
 * trying to read it. Tabular figures hold still.
 */
val ReadoutLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 76.sp,
)

@Composable
fun CoffeeTheme(content: @Composable () -> Unit) {
    // Collected, not read once: the accent is a setting, and configFlow is
    // what makes a change to it repaint rather than wait for the next launch.
    val config by CoffeeRuntime.configFlow.collectAsStateWithLifecycle()
    val scheme = AccentScheme.fromId(config.defaults.colorScheme)

    CompositionLocalProvider(LocalAccentScheme provides scheme) {
        MaterialTheme(
            colorScheme = coffeeColorScheme(scheme),
            typography = Typography(),
            content = content,
        )
    }
}
