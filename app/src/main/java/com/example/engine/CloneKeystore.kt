package com.example.engine

import android.content.Context
import android.util.Base64
import com.example.engine.core.X509SelfSigned
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Persistent signing identity for generated clones.
 *
 * Every clone is signed with a locally generated self signed certificate. The identity is created once and
 * then reused, which matters a lot: Android only accepts an update when the new APK is signed with the same
 * certificate, so a fresh key per clone would make clones impossible to update.
 *
 * The store lives in the application's private directory. An earlier version of this project shipped a real
 * upload keystore in the repository, so this class deliberately never uses bundled key material.
 */
class CloneKeystore(private val context: Context) {

    /** The certificate and private key used to sign clones. */
    data class SigningIdentity(
        val certificate: X509Certificate,
        val privateKey: PrivateKey,
        /** SHA-256 fingerprint of the certificate, shown in the UI. */
        val fingerprint: String,
        val createdAt: Long
    )

    private val storeFile = File(context.filesDir, STORE_NAME)
    private val passwordFile = File(context.filesDir, PASSWORD_NAME)

    @Volatile
    private var cached: SigningIdentity? = null

    /** Loads the existing identity or creates and persists a new one. */
    fun identity(): SigningIdentity {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrCreate().also { cached = it }
        }
    }

    /** Deletes the identity; existing clones can no longer be updated afterwards. */
    fun reset() {
        synchronized(this) {
            cached = null
            storeFile.delete()
            passwordFile.delete()
        }
    }

    /** SHA-256 fingerprint of a certificate in the usual `AA:BB:...` form. */
    fun fingerprintOf(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun loadOrCreate(): SigningIdentity {
        val password = readOrCreatePassword().toCharArray()
        for (type in KEYSTORE_TYPES) {
            val existing = load(type, password)
            if (existing != null) return existing
        }
        return create(password)
    }

    private fun load(type: String, password: CharArray): SigningIdentity? {
        if (!storeFile.exists()) return null
        return try {
            val keyStore = KeyStore.getInstance(type)
            storeFile.inputStream().use { keyStore.load(it, password) }
            val key = keyStore.getKey(ALIAS, password) as? PrivateKey ?: return null
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
            SigningIdentity(
                certificate = certificate,
                privateKey = key,
                fingerprint = fingerprintOf(certificate),
                createdAt = storeFile.lastModified()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun create(password: CharArray): SigningIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            .generateKeyPair()

        val certificate = X509SelfSigned.create(
            keyPair = keyPair,
            commonName = "App Cloner",
            validityYears = 30
        )

        var stored = false
        for (type in KEYSTORE_TYPES) {
            try {
                val keyStore = KeyStore.getInstance(type)
                keyStore.load(null, password)
                keyStore.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))
                storeFile.outputStream().use { keyStore.store(it, password) }
                stored = true
                break
            } catch (e: Exception) {
                // try the next keystore type
            }
        }
        if (!stored) {
            throw IllegalStateException("no writable keystore type available on this device")
        }

        return SigningIdentity(
            certificate = certificate,
            privateKey = keyPair.private,
            fingerprint = fingerprintOf(certificate),
            createdAt = System.currentTimeMillis()
        )
    }

    private fun readOrCreatePassword(): String {
        if (passwordFile.exists()) {
            val existing = passwordFile.readText().trim()
            if (existing.isNotEmpty()) return existing
        }
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = Base64.encodeToString(random, Base64.NO_WRAP)
        passwordFile.writeText(password)
        return password
    }

    private companion object {
        const val ALIAS = "clone-signing"
        const val STORE_NAME = "clone-signing.p12"
        const val PASSWORD_NAME = "clone-signing.pin"

        /** PKCS#12 where the platform supports it, BouncyCastle's BKS on older releases. */
        val KEYSTORE_TYPES = arrayOf("PKCS12", "BKS")
    }
}
