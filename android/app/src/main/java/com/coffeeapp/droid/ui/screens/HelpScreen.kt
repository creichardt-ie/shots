// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.coffeeapp.droid.ui.HELP_SECTIONS
import com.coffeeapp.droid.ui.theme.Accent
import com.coffeeapp.droid.ui.theme.SurfaceDark
import com.coffeeapp.droid.ui.theme.TextSecondary

/** Where the build puts `docs/APP_GUIDE.html` (app/build.gradle.kts, bundleGuide). */
private const val GUIDE_URL = "file:///android_asset/APP_GUIDE.html"

/**
 * The user guide, bundled with the app and shown offline, one section at a
 * time: a tab per section, and the bundled copy's added CSS hides everything
 * but the section holding the URL's target. Scrolling therefore ends at the
 * end of the section instead of running on into the next page's.
 *
 * [section] is the tab to open on (an anchor from [HELP_SECTIONS]); anything
 * else opens the overview.
 *
 * Locked down because nothing in it needs more: no JavaScript, no network
 * (the bundled copy has no external references left, and this makes sure),
 * no following links away from the page.
 */
@Composable
fun HelpScreen(section: String?) {
    var selected by rememberSaveable {
        mutableStateOf(HELP_SECTIONS.indexOfFirst { it.anchor == section }.coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selected,
            containerColor = SurfaceDark,
            contentColor = Accent,
            edgePadding = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HELP_SECTIONS.forEachIndexed { i, s ->
                Tab(
                    selected = i == selected,
                    onClick = { selected = i },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextSecondary,
                    text = { Text(s.label, fontSize = 13.sp, maxLines = 1) },
                )
            }
        }

        val anchor = HELP_SECTIONS[selected].anchor
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    // The guide's own dark background arrives with the page;
                    // this stops a white flash while it loads.
                    setBackgroundColor(SurfaceDark.toArgb())
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false // assets are readable without it
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    }
                }
            },
            // A tab change only moves the fragment: same document, no reload,
            // and the CSS swaps the visible section.
            update = { view ->
                if (view.tag != anchor) {
                    view.tag = anchor
                    view.loadUrl("$GUIDE_URL#$anchor")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
