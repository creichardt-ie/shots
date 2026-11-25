// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.cloud

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Why the cloud is not answering, in terms the person at the machine can act
 * on (2.11.0). Two outages (2026-10-29 and 11-01..11-17) went unnoticed for
 * days: the only sign was a red word on the Machine page, hidden whenever
 * the BLE link was up, and "unreachable" was wrong anyway - the cloud was
 * answering, and refusing.
 */
enum class CloudProblem(val label: String, val advice: String) {
    /**
     * 403 `USER_MUST_ACCEPT_POLICY`: La Marzocco's updated privacy policy is
     * not accepted on the account (pylamarzocco 2.5.3, issue #166). Sign-in
     * still works; every machine call is refused. Both outages were this.
     */
    POLICY("La Marzocco cloud needs the updated policy accepted", "open the La Marzocco app and accept it"),

    /** Any other 403: signed in, but the account may not reach this machine. */
    REFUSED("La Marzocco cloud refuses access to the machine", "open the La Marzocco app and let it connect"),

    /** 401 at sign-in, or no credentials: the account itself is the problem. */
    SIGN_IN("La Marzocco sign-in failed", "check the account in Settings"),

    /** No answer at all: offline, DNS, timeout. */
    NETWORK("La Marzocco cloud unreachable", "check the phone's internet connection"),

    /** Anything else (5xx, malformed answers). */
    OTHER("La Marzocco cloud error", "it may clear by itself"),
    ;

    companion object {
        /** Network trouble is shown only once it has lasted this long; a blip is not news. */
        const val NETWORK_GRACE_MS = 30 * 60_000L

        const val POLICY_CODE = "USER_MUST_ACCEPT_POLICY"

        /** The problem behind a failed cloud call. */
        fun classify(e: Throwable): CloudProblem = when {
            e is RequestFailure && e.code == POLICY_CODE -> POLICY
            e is RequestFailure && e.status == 403 -> REFUSED
            e is AuthFailure -> SIGN_IN
            e is IOException || e.cause is IOException -> NETWORK
            e.javaClass.simpleName.contains("Timeout") -> NETWORK
            else -> OTHER
        }

        /**
         * The warning for the Brew screen and the notification, or null when
         * there is nothing to say: no problem, or network trouble younger than
         * [NETWORK_GRACE_MS].
         */
        fun warning(problem: CloudProblem?, sinceMs: Long?, nowMs: Long): String? {
            if (problem == null) return null
            if (problem == NETWORK && (sinceMs == null || nowMs - sinceMs < NETWORK_GRACE_MS)) return null
            val since = sinceMs?.let { " since ${formatSince(it, nowMs)}" } ?: ""
            return "${problem.label}$since - ${problem.advice}"
        }

        /** "14:25" today, "3 Nov" before that. */
        fun formatSince(sinceMs: Long, nowMs: Long): String {
            val a = Calendar.getInstance().apply { timeInMillis = sinceMs }
            val b = Calendar.getInstance().apply { timeInMillis = nowMs }
            val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            val fmt = if (sameDay) "HH:mm" else "d MMM"
            return SimpleDateFormat(fmt, Locale.UK).format(Date(sinceMs))
        }
    }
}
