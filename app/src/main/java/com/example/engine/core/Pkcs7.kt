package com.example.engine.core

import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * Builds the minimal PKCS#7 `SignedData` container that a JAR/APK v1 signature (META-INF signature block) requires.
 *
 * The broken behaviour this replaces wrote the raw RSA signature bytes into `CERT.RSA`; every verifier
 * rejects that because the file must be a DER encoded `ContentInfo` that carries the signer certificate.
 *
 * A JAR signature is defined as a **detached** signature over the bytes of the `.SF` file, so
 * `encapContentInfo` holds the `data` OID with no content and the digest is stored implicitly through the
 * authenticated data (the `.SF` file itself).
 */
object Pkcs7 {

    private const val OID_DATA = "1.2.840.113549.1.7.1"
    private const val OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
    private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val OID_SHA256 = "2.16.840.1.101.3.4.2.1"
    private const val OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1"

    /**
     * Wraps [signature] over [signedData] (the `.SF` bytes) into a PKCS#7 `ContentInfo`.
     */
    fun signedData(
        signedData: ByteArray,
        certificate: X509Certificate,
        privateKey: PrivateKey,
        signatureAlgorithm: String = "SHA256withRSA"
    ): ByteArray {
        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(privateKey)
            update(signedData)
            sign()
        }

        // `digestAlgorithm` is a plain digest (SHA-256); `digestEncryptionAlgorithm` carries the signature
        // algorithm OID. Both verifiers (java.util.jar and Android's apksig) map that OID to a
        // `Signature` implementation, so the sha256WithRSA OID is mandatory here.
        val digestAlgorithm = Der.sequence(Der.oid(OID_SHA256), Der.nullValue())
        val encryptionAlgorithm = Der.sequence(Der.oid(OID_SHA256_WITH_RSA), Der.nullValue())

        val issuerAndSerial = Der.sequence(
            certificate.issuerX500Principal.encoded,
            Der.integer(certificate.serialNumber)
        )

        val signerInfo = Der.sequence(
            Der.integer(1),
            issuerAndSerial,
            digestAlgorithm,
            encryptionAlgorithm,
            Der.octetString(signature)
        )

        val body = Der.sequence(
            Der.integer(1), // version
            Der.set(digestAlgorithm), // digestAlgorithms
            Der.sequence(Der.oid(OID_DATA)), // encapContentInfo of type data, detached
            Der.implicitConstructed(0, certificate.encoded), // certificates [0] IMPLICIT SET
            Der.set(signerInfo) // signerInfos
        )

        return Der.sequence(
            Der.oid(OID_SIGNED_DATA),
            Der.explicit(0, body)
        )
    }

    /** Parses a PKCS#7 `ContentInfo` and returns the embedded certificates. */
    fun certificates(encoded: ByteArray): List<X509Certificate> {
        val contentInfo = DerReader(encoded)
        val outer = contentInfo.readElement()
        require(outer.tag == Der.TAG_SEQUENCE) { "PKCS#7 must start with a SEQUENCE" }

        val outerReader = DerReader(encoded, outer.contentStart, outer.end)
        outerReader.readElement() // contentType
        val explicit = outerReader.readElement()

        val bodyReader = DerReader(encoded, explicit.contentStart, explicit.end)
        val signedData = bodyReader.readElement()
        val signedDataReader = DerReader(encoded, signedData.contentStart, signedData.end)
        signedDataReader.readElement() // version
        signedDataReader.readElement() // digestAlgorithms
        signedDataReader.readElement() // encapContentInfo

        val certificates = mutableListOf<X509Certificate>()
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        while (signedDataReader.offset < signedData.end) {
            val element = signedDataReader.readElement()
            if (element.tag == 0xA0) {
                var inner = DerReader(encoded, element.contentStart, element.end)
                while (inner.offset < element.end) {
                    val certificateElement = inner.readElement()
                    val der = encoded.copyOfRange(certificateElement.start, certificateElement.end)
                    certificates.add(factory.generateCertificate(der.inputStream()) as X509Certificate)
                }
            }
        }
        return certificates
    }
}
