// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings SAVE writes the user's edits over the live config, so values the
 * runtime learned while the screen was open are not put back.
 */
class MergeEditsTest {

    private val cup = Vessel("Coffee cup", 189.7, arm = true)
    private val opened = AppConfig(
        brewByWeight = BrewByWeight(offsetGrams = 3.5),
        autoTare = AutoTare(vessels = listOf(cup)),
        defaults = Defaults(targetYieldGrams = 36.0),
    )

    /** A shot and a placement happened while the screen was open. */
    private val live = opened.copy(
        brewByWeight = opened.brewByWeight.copy(offsetGrams = 3.2),
        autoTare = opened.autoTare.copy(vessels = listOf(cup.copy(grams = 189.5))),
        defaults = opened.defaults.copy(targetYieldGrams = 38.0),
    )

    @Test
    fun `untouched learned values come from the live config`() {
        val draft = opened.copy(shotLogic = opened.shotLogic.copy(minSaveYieldGrams = 6.0))
        val out = mergeEdits(opened, draft, live)
        assertEquals(3.2, out.brewByWeight.offsetGrams, 0.0)
        assertEquals(189.5, out.autoTare.vessels.single().grams, 0.0)
        assertEquals(38.0, out.defaults.targetYieldGrams, 0.0)
        // ...and the edit itself survives.
        assertEquals(6.0, out.shotLogic.minSaveYieldGrams, 0.0)
    }

    @Test
    fun `an edited learned value wins`() {
        val draft = opened.copy(
            brewByWeight = opened.brewByWeight.copy(offsetGrams = 5.0),
            autoTare = opened.autoTare.copy(vessels = listOf(cup.copy(grams = 200.0))),
        )
        val out = mergeEdits(opened, draft, live)
        assertEquals(5.0, out.brewByWeight.offsetGrams, 0.0)
        assertEquals(200.0, out.autoTare.vessels.single().grams, 0.0)
    }

    @Test
    fun `a vessel added in the draft is kept as typed`() {
        val pitcher = Vessel("Milk pitcher", 140.2)
        val draft = opened.copy(autoTare = opened.autoTare.copy(vessels = listOf(cup, pitcher)))
        val out = mergeEdits(opened, draft, live)
        assertEquals(listOf(189.5, 140.2), out.autoTare.vessels.map { it.grams })
    }
}
