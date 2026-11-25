// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "?" in the top bar opens the bundled guide at the page on screen. A
 * renamed section in `docs/APP_GUIDE.html` would leave it opening at the top
 * with nothing to say why, so every anchor the app uses must exist there.
 */
class HelpAnchorsTest {

    // Unit tests run with the app module as the working directory.
    private val guide = File("../../docs/APP_GUIDE.html").readText()

    @Test
    fun everyPageAndConfigurationHasItsSectionInTheGuide() {
        val anchors = Destination.entries.map { it.helpAnchor } + SETTINGS_HELP_ANCHOR +
            HELP_SECTIONS.map { it.anchor }
        for (anchor in anchors) {
            assertTrue("APP_GUIDE.html has no id=\"$anchor\"", "id=\"$anchor\"" in guide)
        }
        assertEquals("brew-h", helpAnchorFor(Destination.Brew.route))
        assertEquals(SETTINGS_HELP_ANCHOR, helpAnchorFor(SETTINGS_ROUTE))
        assertEquals(null, helpAnchorFor("nowhere"))
        // Every page the "?" can open on has a tab, or Help would open the overview.
        val tabs = HELP_SECTIONS.map { it.anchor }.toSet()
        for (anchor in Destination.entries.map { it.helpAnchor } + SETTINGS_HELP_ANCHOR) {
            assertTrue("no Help tab for $anchor", anchor in tabs)
        }
    }
}
