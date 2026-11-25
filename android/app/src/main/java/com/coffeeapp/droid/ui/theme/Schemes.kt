// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.theme

import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Selectable accent colours.
 *
 * Only the accent moves. The background stays black - the app is read in a
 * kitchen, often in low light, and a bright panel behind a shot timer is
 * genuinely unpleasant - and the status colours stay fixed too: green means
 * connected and dialed in, amber means adjust, red means error. Recolouring
 * those to match a theme would trade meaning for decoration.
 *
 * [onAccent] is not cosmetic. Three of these are light enough that white text
 * on them is unreadable, so each scheme carries the content colour that
 * belongs on it rather than assuming white.
 */
enum class AccentScheme(
    val id: String,
    val label: String,
    val accent: Color,
    val onAccent: Color,
) {
    /** La Marzocco red. The default. */
    MICRA_RED("micra_red", "Micra Red", Color(0xFFE31837), Color.White),

    /** Sampled from the Linea Micra Celeste photo in the drawables. */
    CELESTE("celeste", "Celeste", Color(0xFFA9C6D2), Color.Black),

    /**
     * The yellow La Marzocco paints machines in.
     *
     * Sampled from lamarzocco.com's own linea-mini-r-yellow product shot, the
     * same way CELESTE was taken from the Micra photo: #FFDC3C is the flat-lit
     * body colour, the cluster around it running #FFDA39-#FFDB3B. Published
     * brand-palette values found elsewhere contradicted each other, so the
     * paint in a photograph is the more trustworthy source.
     */
    LM_YELLOW("lm_yellow", "La Marzocco Yellow", Color(0xFFFFDC3C), Color.Black),

    COPPER("copper", "Copper", Color(0xFFC87F45), Color.Black),

    /** The colour of a good crema. */
    CREMA("crema", "Crema", Color(0xFFE3C08A), Color.Black),
    ;

    companion object {
        val DEFAULT = MICRA_RED

        /** Tolerant of an unknown or empty id, so a bad config cannot blank the UI. */
        fun fromId(id: String?): AccentScheme =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

val LocalAccentScheme = staticCompositionLocalOf { AccentScheme.DEFAULT }

/**
 * Colours for every Switch in the app.
 *
 * Both halves are stated. Material's unchecked defaults are surfaceVariant
 * (#1C1C1C) behind an outline (#333333) thumb, which on this app's black and
 * #111111 surfaces is three near-blacks: the control reads as one grey blob
 * with no visible knob.
 *
 * The checked thumb is white on every accent - chosen on the device, over an
 * [OnAccent] thumb that went black on the three light schemes. The accent
 * border is what keeps the pill defined where the thumb and track are close
 * in value.
 */
@Composable
fun coffeeSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Accent,
    checkedBorderColor = Accent,
    uncheckedThumbColor = TextSecondary,
    uncheckedTrackColor = Color.Transparent,
    uncheckedBorderColor = TextSecondary,
)

/**
 * The current accent.
 *
 * Replaces the `MicraRed` constant at call sites: a top-level val cannot
 * change with a setting, and every screen imported it directly.
 */
val Accent: Color
    @Composable get() = LocalAccentScheme.current.accent

/** The content colour that belongs on [Accent]. */
val OnAccent: Color
    @Composable get() = LocalAccentScheme.current.onAccent
