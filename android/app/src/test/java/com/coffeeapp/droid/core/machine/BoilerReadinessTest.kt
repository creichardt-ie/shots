// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.machine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Values are the steam readings logged on the machine, 2026-10-17. */
class BoilerReadinessTest {

    @Test
    fun `raising the level reads as heating, not ready`() {
        // Level3 set: target 131 while the boiler still sat at 128.
        assertEquals(Readiness.HEATING, BoilerReadiness.of(131.0, 128.0))
    }

    @Test
    fun `steady readings around the target are ready`() {
        assertEquals(Readiness.READY, BoilerReadiness.of(128.0, 127.0))
        assertEquals(Readiness.READY, BoilerReadiness.of(128.0, 129.0))
        assertEquals(Readiness.READY, BoilerReadiness.of(131.0, 131.0))
    }

    @Test
    fun `a heater cycling around 126 stays ready across the 3 degree step`() {
        // Level1: the reading flipped between 125 and 128 every minute or two.
        assertEquals(Readiness.READY, BoilerReadiness.of(126.0, 125.0))
        assertEquals(Readiness.READY, BoilerReadiness.of(126.0, 128.0))
    }

    @Test
    fun `lowering the level reads as settling`() {
        // Level1 set: target 126 with the boiler still at 131.
        assertEquals(Readiness.SETTLING, BoilerReadiness.of(126.0, 131.0))
    }

    @Test
    fun `no reading or no target defers to the cloud`() {
        assertNull(BoilerReadiness.of(128.0, null))
        assertNull(BoilerReadiness.of(null, 128.0))
    }
}
