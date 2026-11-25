// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid

import android.app.Application
import com.coffeeapp.droid.core.CoffeeRuntime
import com.coffeeapp.droid.core.log.FileLog

/**
 * Application entry point.
 *
 * Deliberately thin. It initialises logging, the config
 * and secrets stores only; hardware comes up in CoffeeService, once the
 * Bluetooth permission exists. Splitting the two matters on a fresh install:
 * connecting before the permission is granted would fail once and never retry.
 */
class CoffeeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // First, so the startup banner and everything the runtime logs during
        // init actually lands in the file.
        FileLog.init(this)
        FileLog.installCrashHandler()
        CoffeeRuntime.init(this)
    }
}
