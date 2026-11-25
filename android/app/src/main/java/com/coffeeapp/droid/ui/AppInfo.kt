// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

import android.content.Context
import android.os.Build

/** Shown on the launch screen and in Configuration -> About. */
const val APP_NAME = "Shots"

/**
 * Subtitle under [APP_NAME].
 *
 * Carries what the five-letter name cannot. Kept separate from the launcher
 * label in strings.xml, which stays short so it is never truncated under the
 * icon.
 */
const val APP_TAGLINE = "espresso by the gram"

/** Author credit. */
const val APP_AUTHOR = "Christian Reichardt"

/** How long the launch screen stays up. */
const val SPLASH_MILLIS = 4_000L

/**
 * "2.7.2 (build 12)", or "unknown" if the package cannot be read.
 *
 * The same pair FileLog puts in its startup banner, so a screenshot of About
 * and a log file can be matched to the same build.
 */
fun appVersionLabel(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    "${info.versionName} (build $code)"
} catch (e: Exception) {
    "unknown"
}
