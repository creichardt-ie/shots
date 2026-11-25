// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A big one-line number that shrinks to fit rather than being clipped.
 *
 * The readouts are sized for a typical value ("27.7", "36.1"), and a fixed
 * size cut longer ones mid-digit: a mug weighed before the tare ("312.4"), a
 * milk pitcher on the Scale page ("650.3 g"), or any value at a large system
 * font. Stepped down to [minSize], never up past [maxSize].
 */
@Composable
fun AutoSizeReadout(
    text: String,
    style: TextStyle,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 24.sp,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 2.sp),
    )
}
