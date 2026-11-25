// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The two Android Bluetooth permission models, in one place.
 *
 * Android 12 (API 31) replaced the location-gated model with dedicated
 * BLUETOOTH_SCAN / BLUETOOTH_CONNECT permissions. Because the manifest
 * declares BLUETOOTH_SCAN with neverForLocation and we connect to a known
 * MAC, modern devices never have to ask for location at all — the prompt on
 * Android 12+ is just "allow nearby devices".
 */
object BluetoothPermissions {

    /** Everything that must be granted before the scale can be used. */
    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pre-12, a BLE scan is a location capability whether or not you
            // want a location.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Not needed for Bluetooth itself, but the foreground service that
            // keeps a shot alive must be able to post its notification.
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /**
     * The subset that actually blocks hardware use.
     *
     * POST_NOTIFICATIONS being denied costs the foreground notification, not
     * the scale, so it should not gate the connection attempt.
     */
    fun blockingDenied(context: Context): List<String> = required()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
