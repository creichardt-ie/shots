// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.core.cloud

import com.coffeeapp.droid.core.config.StoredInstallationKey
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * La Marzocco request signing.
 *
 * Port of pylamarzocco/util/_authentication.py. Every request to the customer
 * API carries a proof and an ECDSA signature derived from an installation key
 * registered once per client, so this has to be byte-exact: the cloud rejects
 * anything else with a bare 401 and no useful detail.
 *
 * The key is a P-256 keypair plus a 32-byte secret derived from the public
 * key. It is generated on first run, registered with /auth/init, and then
 * persisted - re-registering on every launch would create a new client
 * against the account each time.
 *
 * Two deliberate choices:
 *
 *  - java.util.Base64, not android.util.Base64, so the proof algorithm is
 *    testable as a plain JVM unit test. (Available from API 26, our minSdk.)
 *  - the key is NOT held in the Android hardware keystore. The private key
 *    must be exportable as PKCS8 DER so it can be persisted and round-tripped;
 *    a keystore key cannot be extracted by design. It is stored encrypted at
 *    rest in EncryptedSharedPreferences instead.
 */
class InstallationKey private constructor(
    val installationId: String,
    val privateKey: PrivateKey,
    val publicKeyDer: ByteArray,
    /** 32 bytes, derived from the installation id and the public key. */
    val secret: ByteArray,
) {

    companion object {
        private const val CURVE = "secp256r1"
        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /** Unpadded-line base64, matching Python b64encode output exactly. */
        internal fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

        internal fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

        internal fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        /** Generate a fresh key. The caller must register it with /auth/init. */
        fun generate(
            installationId: String = UUID.randomUUID().toString().lowercase(),
        ): InstallationKey {
            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            generator.initialize(ECGenParameterSpec(CURVE), SecureRandom())
            val pair = generator.generateKeyPair()
            val pubDer = pair.public.encoded // X.509 SubjectPublicKeyInfo DER
            return InstallationKey(
                installationId = installationId,
                privateKey = pair.private,
                publicKeyDer = pubDer,
                secret = deriveSecret(installationId, pubDer),
            )
        }

        /** Rebuild from persisted material. */
        fun fromStored(stored: StoredInstallationKey): InstallationKey {
            val privateKey = KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePrivate(PKCS8EncodedKeySpec(unb64(stored.privateKeyDerB64)))
            return InstallationKey(
                installationId = stored.installationId,
                privateKey = privateKey,
                publicKeyDer = unb64(stored.publicKeyDerB64),
                secret = unb64(stored.secretB64),
            )
        }

        /**
         * secret = sha256("<id>.<b64(pubDer)>.<b64(sha256(id))>")
         *
         * The inner hash is over the raw DER bytes, not over their base64
         * text - a detail that is easy to get subtly wrong and impossible to
         * diagnose from the server response.
         */
        internal fun deriveSecret(installationId: String, publicKeyDer: ByteArray): ByteArray {
            val pubB64 = b64(publicKeyDer)
            val idHashB64 = b64(sha256(installationId.toByteArray(Charsets.UTF_8)))
            return sha256("$installationId.$pubB64.$idHashB64".toByteArray(Charsets.UTF_8))
        }

        /**
         * La Marzocco custom proof algorithm.
         *
         * Walks the UTF-8 bytes of [baseString], and for each one mutates a
         * working copy of the 32-byte secret in place: XOR the input byte with
         * the working byte it indexes, rotate that left by a shift taken from
         * the NEXT working byte, and write it back. The proof is the base64
         * SHA-256 of the final state.
         *
         * Two things to preserve exactly: the rotate is over 8 bits, so a
         * shift of 0 must be a no-op rather than a shift by 8 (which in Kotlin
         * would be an unshifted value ORed with itself, and in C would be
         * undefined); and the walk is over UTF-8 bytes, not characters.
         */
        fun generateRequestProof(baseString: String, secret32: ByteArray): String {
            require(secret32.size == 32) { "secret must be 32 bytes" }
            val work = secret32.copyOf()

            for (b in baseString.toByteArray(Charsets.UTF_8)) {
                val byteVal = b.toInt() and 0xFF
                val idx = byteVal % 32
                val shiftIdx = (idx + 1) % 32
                val shiftAmount = work[shiftIdx].toInt() and 7

                val xorResult = byteVal xor (work[idx].toInt() and 0xFF)
                val rotated = if (shiftAmount == 0) {
                    xorResult and 0xFF
                } else {
                    ((xorResult shl shiftAmount) or (xorResult ushr (8 - shiftAmount))) and 0xFF
                }
                work[idx] = rotated.toByte()
            }
            return b64(sha256(work))
        }
    }

    /** base64 of the public key in DER SubjectPublicKeyInfo form. */
    val publicKeyB64: String get() = b64(publicKeyDer)

    /** "<installationId>.<b64(sha256(publicKeyDer))>" - the registration input. */
    val baseString: String get() = "$installationId.${b64(sha256(publicKeyDer))}"

    /** Proof sent as X-Request-Proof when registering the client. */
    fun registrationProof(): String = generateRequestProof(baseString, secret)

    /**
     * Per-request headers for an authenticated call.
     *
     * The signature covers "<id>.<nonce>.<timestamp>.<proof>", so the proof is
     * computed first and then folded into the signed payload.
     *
     * [nonce] and [timestampMillis] are parameters only so a test can pin
     * them; production calls always take the defaults.
     */
    fun requestHeaders(
        nonce: String = UUID.randomUUID().toString().lowercase(),
        timestampMillis: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val timestamp = timestampMillis.toString()
        val proofInput = "$installationId.$nonce.$timestamp"
        val proof = generateRequestProof(proofInput, secret)
        val signatureData = "$proofInput.$proof"

        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(signatureData.toByteArray(Charsets.UTF_8))
            sign()
        }

        return mapOf(
            "X-App-Installation-Id" to installationId,
            "X-Timestamp" to timestamp,
            "X-Nonce" to nonce,
            "X-Request-Signature" to b64(signature),
        )
    }

    /** Serialise for [com.coffeeapp.droid.core.config.SecretsStore]. */
    fun toStored(): StoredInstallationKey = StoredInstallationKey(
        installationId = installationId,
        privateKeyDerB64 = b64(privateKey.encoded),
        publicKeyDerB64 = b64(publicKeyDer),
        secretB64 = b64(secret),
    )
}
