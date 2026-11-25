// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Credentials and the La Marzocco installation key.
 *
 * Kept out of [ConfigStore] so the config stays safe to screenshot or share,
 * and encrypted at rest via the Android keystore.
 *
 * Nothing in this class should ever be logged. The config diff in ConfigStore
 * deliberately has no branch that can print these values.
 */
class SecretsStore(context: Context) {

    private companion object {
        const val FILE_NAME = "coffee_secrets"

        const val KEY_USERNAME = "lm_username"
        const val KEY_PASSWORD = "lm_password"
        const val KEY_BLE_TOKEN = "lm_ble_token"

        // The LM installation key: an EC P-256 private key plus the derived
        // secret, registered once with the cloud and reused thereafter.
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_INSTALLATION_PRIVATE = "installation_private_key"
        const val KEY_INSTALLATION_PUBLIC = "installation_public_key"
        const val KEY_INSTALLATION_SECRET = "installation_secret"
    }

    /**
     * Opened on first use, not in Application.onCreate: building the master
     * key and the encrypted file is Keystore and Tink work, 50-200 ms cold,
     * that held back the first frame. CoffeeRuntime.init warms it off the
     * main thread, so Settings rarely finds it cold.
     */
    private val prefs: SharedPreferences by lazy { openOrReset(context.applicationContext) }

    /** Open the store now, from a background thread. */
    fun warmUp() {
        prefs
    }

    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Open the store, or - when the keystore key no longer matches the file
     * (after some OS updates, keystore resets and device restores) - wipe both
     * and start empty. This runs in Application.onCreate, so the alternative
     * was a crash on every launch until the app's data was cleared, which
     * would also have taken the shot history. Credentials are re-entered and
     * the installation key re-registered; the values are never logged.
     */
    private fun openOrReset(context: Context): SharedPreferences = try {
        open(context)
    } catch (e: Exception) {
        com.coffeeapp.droid.core.log.FileLog.e(
            "SecretsStore",
            "Encrypted credentials could not be opened (${e.javaClass.simpleName}); " +
                "resetting them - re-enter the La Marzocco account",
        )
        context.deleteSharedPreferences(FILE_NAME)
        try {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (_: Exception) {
            // Nothing to delete, or the keystore refused; the retry decides.
        }
        open(context)
    }

    // --- Cloud credentials -----------------------------------------------

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty().also(com.coffeeapp.droid.core.log.FileLog::redact)
        set(value) {
            com.coffeeapp.droid.core.log.FileLog.redact(value)
            prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

    /**
     * The Micra's BLE auth token. Synced from the cloud settings response on
     * every refresh (CloudClient.syncBleToken) and read by MicraBleClient on
     * each connect attempt. Never logged.
     */
    var bleToken: String
        get() = prefs.getString(KEY_BLE_TOKEN, "").orEmpty().also(com.coffeeapp.droid.core.log.FileLog::redact)
        set(value) {
            com.coffeeapp.droid.core.log.FileLog.redact(value)
            prefs.edit().putString(KEY_BLE_TOKEN, value).apply()
        }

    val hasCredentials: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    // --- Installation key -------------------------------------------------

    /**
     * The stored key material, or null on first run.
     *
     * All three parts are written together so a half-registered key can never
     * be read back: an installation id without its private key would fail
     * every signed request with no obvious cause.
     */
    fun loadInstallationKey(): StoredInstallationKey? {
        val id = prefs.getString(KEY_INSTALLATION_ID, null) ?: return null
        val priv = prefs.getString(KEY_INSTALLATION_PRIVATE, null) ?: return null
        val pub = prefs.getString(KEY_INSTALLATION_PUBLIC, null) ?: return null
        val secret = prefs.getString(KEY_INSTALLATION_SECRET, null) ?: return null
        return StoredInstallationKey(id, priv, pub, secret)
    }

    fun saveInstallationKey(key: StoredInstallationKey) {
        prefs.edit()
            .putString(KEY_INSTALLATION_ID, key.installationId)
            .putString(KEY_INSTALLATION_PRIVATE, key.privateKeyDerB64)
            .putString(KEY_INSTALLATION_PUBLIC, key.publicKeyDerB64)
            .putString(KEY_INSTALLATION_SECRET, key.secretB64)
            .apply()
    }
}

/**
 * Serialised form of the LM installation key.
 *
 * pylamarzocco stores only the private key and derives the public half from
 * it via the cryptography library. The JCA has no public API for that
 * derivation, and hand-rolling the curve arithmetic to avoid a
 * BouncyCastle dependency is not a trade worth making for one startup path -
 * so the public key is simply persisted alongside. This build registers its
 * own client, so no key is ever imported.
 */
data class StoredInstallationKey(
    val installationId: String,
    /** PKCS8 DER, base64. */
    val privateKeyDerB64: String,
    /** X.509 SubjectPublicKeyInfo DER, base64. */
    val publicKeyDerB64: String,
    /** The 32-byte derived secret, base64. */
    val secretB64: String,
)
