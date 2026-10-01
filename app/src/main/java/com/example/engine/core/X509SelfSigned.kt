package com.example.engine.core

import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * Creates the self signed X.509 certificate that an APK signature needs.
 *
 * The previous implementation signed with a bare RSA key and never produced a certificate at all, which
 * makes an APK unverifiable on every Android version. This builder emits a standards compliant
 * `Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }` with a
 * `subjectPublicKeyInfo`, a validity window and the two extensions Android expects on a signing identity.
 */
object X509SelfSigned {

    private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"

    /**
     * Builds and signs a self signed certificate for [keyPair].
     *
     * @param commonName value of the certificate subject, e.g. `"App Cloner"`.
     * @param validityYears how long the certificate stays valid; Android rejects signatures from keys that
     *        cannot be renewed, so a long window is used by default.
     */
    fun create(
        keyPair: KeyPair,
        commonName: String,
        validityYears: Int = 30,
        serial: BigInteger = BigInteger.valueOf(System.currentTimeMillis())
    ): X509Certificate {
        val publicKey = keyPair.public as RSAPublicKey
        val privateKey = keyPair.private as RSAPrivateKey

        val now = System.currentTimeMillis()
        val notBefore = now - 24L * 60 * 60 * 1000
        val notAfter = notBefore + validityYears.toLong() * 365 * 24 * 60 * 60 * 1000

        val commonNameValue = commonName.removePrefix("CN=")
        val signatureAlgorithm = Der.sequence(Der.oid(OID_SHA256_WITH_RSA), Der.nullValue())
        val name = Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonNameValue))))
        val validity = Der.sequence(Der.time(notBefore), Der.time(notAfter))
        val subjectPublicKeyInfo = publicKeyInfo(publicKey)

        val extensions = Der.explicit(
            3,
            Der.sequence(
                extension(OID_BASIC_CONSTRAINTS, critical = true, value = Der.sequence()),
                extension(
                    OID_KEY_USAGE,
                    critical = true,
                    value = Der.bitString(byteArrayOf(0x80.toByte()), unusedBits = 7)
                )
            )
        )

        val tbsCertificate = Der.sequence(
            Der.explicit(0, Der.integer(2)), // version v3
            Der.integer(serial),
            signatureAlgorithm,
            name, // issuer
            validity,
            name, // subject (self signed)
            subjectPublicKeyInfo,
            extensions
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbsCertificate)
            sign()
        }

        val certificate = Der.sequence(
            tbsCertificate,
            signatureAlgorithm,
            Der.bitString(signature, unusedBits = 0)
        )

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.inputStream()) as X509Certificate
    }

    /** `SubjectPublicKeyInfo ::= SEQUENCE { algorithm, subjectPublicKey }` for an RSA key. */
    fun publicKeyInfo(publicKey: RSAPublicKey): ByteArray = Der.sequence(
        Der.sequence(Der.oid(OID_RSA_ENCRYPTION), Der.nullValue()),
        Der.bitString(
            Der.sequence(
                Der.integer(publicKey.modulus),
                Der.integer(publicKey.publicExponent)
            )
        )
    )

    /** Public key bytes exactly as they must be embedded in the APK signature scheme blocks. */
    fun publicKeyBytes(publicKey: RSAPublicKey): ByteArray = publicKeyInfo(publicKey)

    /** `Extension ::= SEQUENCE { extnID, critical, extnValue }`. */
    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray = Der.sequence(
        Der.oid(oid),
        Der.boolean(critical),
        Der.octetString(value)
    )

    /** Convenience: the certificate encoded as DER. */
    fun certificateBytes(certificate: X509Certificate): ByteArray = certificate.encoded

    @Suppress("unused")
    internal fun describe(certificate: X509Certificate): String =
        "CN=${certificate.subjectX500Principal.name} valid ${certificate.notBefore}..${certificate.notAfter}"

    internal fun privateKeyOf(keyPair: KeyPair): PrivateKey = keyPair.private
}
