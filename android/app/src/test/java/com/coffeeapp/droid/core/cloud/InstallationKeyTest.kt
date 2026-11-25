// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.core.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import java.util.Base64

/**
 * La Marzocco request signing, pinned to the Python implementation.
 *
 * Every expected string was produced by running the same input through
 * pylamarzocco.util._authentication on this machine. That matters more here
 * than anywhere else in the port: the cloud answers a wrong proof with a bare
 * 401, so a subtle mistake in the bit-twiddling would surface as "your
 * password is wrong" rather than as anything pointing at this code.
 */
class InstallationKeyTest {

    /** The 32-byte secret 00 01 02 ... 1f, used for the deterministic cases. */
    private val secret = ByteArray(32) { it.toByte() }

    private fun proof(input: String) = InstallationKey.generateRequestProof(input, secret)

    // --- The custom proof algorithm --------------------------------------

    @Test
    fun `proof matches python for a short input`() {
        assertEquals("juztopMnB9ew1qMN157g7FDK8cUtPVhZAd4ZlWO/3M0=", proof("test"))
    }

    @Test
    fun `proof matches python for a single character`() {
        assertEquals("IqGIIcxATMVqX7mQGlZ9LSfhy11NSYeMGj33NqFK89c=", proof("a"))
    }

    @Test
    fun `proof of an empty string is the hash of the untouched secret`() {
        // No input bytes means no mutation, so this pins the base case: the
        // working copy must start as the secret verbatim.
        assertEquals("Yw3NKWbEM2aRElRIu7JbT/QSpJxzLbLIq8G4WBvXEN0=", proof(""))
    }

    @Test
    fun `proof matches python for a realistic request input`() {
        // The actual shape signed on every call: id.nonce.timestamp
        assertEquals(
            "+Y+Wlo1UI5ntFXquXeBSOesw+uxGwyDngTf7THWuG48=",
            proof("01234567-89ab-cdef-0123-456789abcdef.nonce-value.1700000000000"),
        )
    }

    @Test
    fun `proof matches python for a dotted installation string`() {
        assertEquals("QmrV7hqvdc3DznE269rOUn6BBrr6zpz+GDrlXqglCC0=", proof("installation.AAAA.BBBB"))
    }

    @Test
    fun `proof walks utf-8 bytes not characters`() {
        // "cafe" with an acute accent is 5 UTF-8 bytes from 4 characters. If
        // the walk were over chars, or over a non-UTF-8 encoding, this would
        // silently diverge only for non-ASCII input - which an email address
        // in the signed payload could easily contain.
        assertEquals("SsfPhNPYtOTe94nvEFPNfWfyNI4kDpDEo3b/8FR/ZSk=", proof("café"))
    }

    @Test
    fun `proof rejects a secret that is not 32 bytes`() {
        val e = runCatching { InstallationKey.generateRequestProof("x", ByteArray(16)) }
        assertTrue(e.isFailure)
    }

    // --- Secret derivation ------------------------------------------------

    @Test
    fun `derived secret matches python`() {
        val installationId = "01234567-89ab-cdef-0123-456789abcdef"
        val publicKeyDer = ByteArray(64) { it.toByte() }
        val derived = InstallationKey.deriveSecret(installationId, publicKeyDer)

        assertEquals("P3UHQmK2IBH0hPRU2WNATvrhgi3vfXLA4E2oPe6Da0g=", InstallationKey.b64(derived))
        assertEquals(32, derived.size)
    }

    @Test
    fun `registration proof matches python end to end`() {
        // Chains all three pieces: derive the secret, build the base string
        // (which hashes the RAW DER, not its base64), then proof the result.
        val installationId = "01234567-89ab-cdef-0123-456789abcdef"
        val publicKeyDer = ByteArray(64) { it.toByte() }
        val derived = InstallationKey.deriveSecret(installationId, publicKeyDer)
        val baseString =
            "$installationId.${InstallationKey.b64(InstallationKey.sha256(publicKeyDer))}"

        assertEquals(
            "01234567-89ab-cdef-0123-456789abcdef./eq5rPNxA2K9JljNyaKej5x1f8+YEWA6jER80dkVEQg=",
            baseString,
        )
        assertEquals(
            "RLqtp17PL3T9u/nUCI7+Ayr2GbsXe2QkfUiEwZJBh44=",
            InstallationKey.generateRequestProof(baseString, derived),
        )
    }

    // --- Key generation and round-tripping --------------------------------

    @Test
    fun `a generated key round-trips through storage`() {
        // The key is registered with the cloud exactly once, so losing it
        // across a restart would silently register a second client on every
        // launch.
        val original = InstallationKey.generate()
        val restored = InstallationKey.fromStored(original.toStored())

        assertEquals(original.installationId, restored.installationId)
        assertArrayEquals(original.publicKeyDer, restored.publicKeyDer)
        assertArrayEquals(original.secret, restored.secret)
        assertEquals(original.baseString, restored.baseString)
        assertEquals(original.registrationProof(), restored.registrationProof())
    }

    @Test
    fun `a generated key derives its own secret consistently`() {
        val key = InstallationKey.generate()
        assertArrayEquals(
            InstallationKey.deriveSecret(key.installationId, key.publicKeyDer),
            key.secret,
        )
    }

    @Test
    fun `each generated key is distinct`() {
        assertNotEquals(InstallationKey.generate().installationId, InstallationKey.generate().installationId)
    }

    // --- Request headers --------------------------------------------------

    @Test
    fun `request headers carry the signed nonce and timestamp`() {
        val key = InstallationKey.generate()
        val headers = key.requestHeaders(nonce = "fixed-nonce", timestampMillis = 1_700_000_000_000L)

        assertEquals(key.installationId, headers["X-App-Installation-Id"])
        assertEquals("fixed-nonce", headers["X-Nonce"])
        assertEquals("1700000000000", headers["X-Timestamp"])
        assertTrue(headers["X-Request-Signature"]!!.isNotBlank())
    }

    @Test
    fun `the signature verifies against the signed payload`() {
        // Proves the payload really is id.nonce.timestamp.proof - if the
        // signed string were assembled differently the cloud would reject
        // every call, and locally we would see nothing at all.
        val key = InstallationKey.generate()
        val nonce = "fixed-nonce"
        val timestamp = 1_700_000_000_000L
        val headers = key.requestHeaders(nonce, timestamp)

        val proofInput = "${key.installationId}.$nonce.$timestamp"
        val expectedPayload =
            "$proofInput.${InstallationKey.generateRequestProof(proofInput, key.secret)}"

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(
                java.security.KeyFactory.getInstance("EC").generatePublic(
                    java.security.spec.X509EncodedKeySpec(key.publicKeyDer),
                ),
            )
            update(expectedPayload.toByteArray(Charsets.UTF_8))
        }
        assertTrue(verifier.verify(Base64.getDecoder().decode(headers["X-Request-Signature"])))
    }

    @Test
    fun `each request gets a fresh signature`() {
        // ECDSA is randomised, so two signatures over the same payload differ.
        // Asserted so nobody "fixes" that later by caching the header map.
        val key = InstallationKey.generate()
        val a = key.requestHeaders(nonce = "n", timestampMillis = 1L)["X-Request-Signature"]
        val b = key.requestHeaders(nonce = "n", timestampMillis = 1L)["X-Request-Signature"]
        assertNotEquals(a, b)
    }
}
