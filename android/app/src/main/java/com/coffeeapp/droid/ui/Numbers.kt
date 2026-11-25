// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

/**
 * Parse a number typed into a decimal field, accepting a comma as well as a
 * point. `toDoubleOrNull` takes only ".", and a German keyboard offers ","
 * on the decimal pad: "8,5" was refused as "Not a number" in Settings and
 * silently stored as grind 0 in the bean journal.
 */
fun parseDecimal(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
