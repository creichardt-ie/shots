// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coffeeapp.droid.R
import androidx.compose.foundation.shape.RoundedCornerShape
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.IconCream
import com.coffeeapp.droid.ui.theme.TextSecondary

/**
 * The launch screen.
 *
 * Up for [SPLASH_MILLIS] and skippable with a tap, because four seconds is a
 * long time when the scale is already on the counter and a shot is waiting.
 * Turned off entirely from Configuration -> About.
 *
 * It opens with the launcher icon because Android 12+ always draws its own
 * splash from that icon first and cannot be told not to. Repeating it here
 * turns two screens - an icon, then unrelated text - into one that resolves:
 * the icon stays put and the name joins it. The backgrounds already match
 * (windowBackground is black, so is PanelBlack), so the seam is invisible.
 *
 * The adaptive icon's foreground is drawn rather than the mipmap itself: the
 * full icon would put its #111111 square on the black and show its edges.
 */
@Composable
fun SplashScreen(onSkip: () -> Unit) {
    val context = LocalContext.current
    // No ripple: this is a whole-screen skip target, not a button.
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = interaction, indication = null, onClick = onSkip),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            // On its cream, as on the launcher: the artwork is dark brown and
            // would be invisible dropped straight onto the black splash.
            //
            // R.drawable.logo, not the launcher foreground - that one carries
            // 45% padding so the wide artwork survives the adaptive icon's
            // circular mask, which here just made the scale look small inside
            // its own tile. This copy is trimmed to the artwork.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(IconCream)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.width(150.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            // The wordmark is an image, not type: the brush face the sample
            // is set in cannot be bundled, and no OFL script measured close
            // enough - Kaushan Script was nearest and still lighter and more
            // slanted. Extracted with a soft alpha ramp so the brush edges
            // stay smooth and the paper texture is left behind, then tinted,
            // so it still follows the accent.
            Image(
                painter = painterResource(R.drawable.wordmark),
                contentDescription = APP_NAME,
                colorFilter = ColorFilter.tint(Accent),
                modifier = Modifier.width(130.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                APP_TAGLINE,
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "by $APP_AUTHOR",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            appVersionLabel(context),
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Clear of 3-button navigation, which edge-to-edge draws over.
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        )
    }
}
