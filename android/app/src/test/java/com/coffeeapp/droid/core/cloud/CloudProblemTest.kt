// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.Calendar

/** What a failed cloud call is called, and when it is worth a warning (2.11.0). */
class CloudProblemTest {

    @Test
    fun `the policy 403 is told apart from any other 403`() {
        // The body both outages answered with (pylamarzocco #166, HA #189601).
        val policy = RequestFailure("Request to /things/<serial>/dashboard failed: 403", 403, "USER_MUST_ACCEPT_POLICY")
        assertEquals(CloudProblem.POLICY, CloudProblem.classify(policy))
        assertEquals(CloudProblem.REFUSED, CloudProblem.classify(RequestFailure("x", 403, null)))
        assertEquals(CloudProblem.REFUSED, CloudProblem.classify(RequestFailure("x", 403, "SOMETHING_ELSE")))
    }

    @Test
    fun `sign-in, network and the rest`() {
        assertEquals(CloudProblem.SIGN_IN, CloudProblem.classify(AuthFailure("Invalid username or password")))
        assertEquals(CloudProblem.NETWORK, CloudProblem.classify(UnknownHostException("lion.lamarzocco.io")))
        assertEquals(CloudProblem.NETWORK, CloudProblem.classify(IllegalStateException("wrapped", IOException("reset"))))
        assertEquals(CloudProblem.OTHER, CloudProblem.classify(RequestFailure("x", 500)))
    }

    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 17, 18, 30, 0) }.timeInMillis

    @Test
    fun `the warning says what, since when, and what to do`() {
        val since = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 3, 9, 52, 0) }.timeInMillis
        assertEquals(
            "La Marzocco cloud needs the updated policy accepted since 3 Nov - open the La Marzocco app and accept it",
            CloudProblem.warning(CloudProblem.POLICY, since, now),
        )
        val today = now - 2 * 3600_000L
        assertTrue(CloudProblem.warning(CloudProblem.REFUSED, today, now)!!.contains("since 16:30"))
    }

    @Test
    fun `a network blip is not news, a long outage is`() {
        assertNull(CloudProblem.warning(null, null, now))
        assertNull(CloudProblem.warning(CloudProblem.NETWORK, now - 60_000, now))
        assertTrue(CloudProblem.warning(CloudProblem.NETWORK, now - CloudProblem.NETWORK_GRACE_MS, now) != null)
        // Refusals are shown at once: they never clear by waiting.
        assertTrue(CloudProblem.warning(CloudProblem.POLICY, now, now) != null)
    }
}
