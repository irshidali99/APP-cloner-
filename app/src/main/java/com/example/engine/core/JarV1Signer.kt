package com.example.engine.core

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * JAR (APK signature scheme v1) signer.
 *
 * Produces the three `META-INF` files that make up a v1 signature:
 *  - `MANIFEST.MF` - SHA-256 digest of every entry,
 *  - `CERT.SF` - digest of the manifest and of every manifest section, signed indirectly,
 *  - `CERT.RSA` - PKCS#7 container with the signature over `CERT.SF` and the signer certificate.
 *
 * Manifests are written exactly like `java.util.jar.Manifest` writes them (line folding at 72 bytes, CRLF
 * endings) and the section digests are computed over the manifest section bytes, which is what Java's and
 * Android's verifiers recompute.
 */
class JarV1Signer(
    private val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    private val signatureBlockName: String = "CERT"
) {

    /** Result of signing: the three `META-INF` blobs keyed by their entry name. */
    data class SignedFiles(val files: Map<String, ByteArray>) {
        operator fun get(name: String): ByteArray? = files[name]
    }

    /**
     * @param entries entry name -> SHA-256 digest of the *uncompressed* entry content.
     * @param excluded names that are not part of the signed content (the signature files themselves).
     */
    fun sign(entries: Map<String, ByteArray>, excluded: Set<String> = emptySet()): SignedFiles {
        val digest = MessageDigest.getInstance("SHA-256")

        // ---- MANIFEST.MF ---------------------------------------------------------------
        val manifestEntries = entries.filterKeys { it !in excluded }.toSortedMap()
        val manifest = StringBuilder()
        appendLine(manifest, "Manifest-Version", "1.0")
        appendLine(manifest, "Created-By", "App Cloner")
        manifest.append("\r\n")

        val sections = LinkedHashMap<String, ByteArray>()
        for ((name, entryDigest) in manifestEntries) {
            val section = StringBuilder()
            appendLine(section, "Name", name)
            appendLine(section, "SHA-256-Digest", base64(entryDigest))
            val sectionBytes = section.toString().toByteArray(Charsets.UTF_8)
            sections[name] = sectionBytes
            manifest.append(String(sectionBytes, Charsets.UTF_8))
            manifest.append("\r\n")
        }
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)

        // ---- CERT.SF -------------------------------------------------------------------
        val sf = StringBuilder()
        appendLine(sf, "Signature-Version", "1.0")
        appendLine(sf, "Created-By", "App Cloner")
        digest.reset()
        appendLine(sf, "SHA-256-Digest-Manifest", base64(digest.digest(manifestBytes)))
        sf.append("\r\n")

        // The per entry digest is taken over the manifest section itself, without the blank line that
        // separates sections - verified against java.util.jar (the verifier Android uses for v1).
        for ((name, sectionBytes) in sections) {
            digest.reset()
            val sectionDigest = digest.digest(sectionBytes)
            val section = StringBuilder()
            appendLine(section, "Name", name)
            appendLine(section, "SHA-256-Digest", base64(sectionDigest))
            sf.append(section)
            sf.append("\r\n")
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)

        // ---- CERT.RSA ------------------------------------------------------------------
        val rsaBytes = Pkcs7.signedData(sfBytes, certificate, privateKey)

        return SignedFiles(
            linkedMapOf(
                "META-INF/MANIFEST.MF" to manifestBytes,
                "META-INF/$signatureBlockName.SF" to sfBytes,
                "META-INF/$signatureBlockName.RSA" to rsaBytes
            )
        )
    }

    /** Computes the per entry SHA-256 digests the signer needs. */
    fun digests(content: (String) -> ByteArray, entryNames: List<String>): Map<String, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val result = LinkedHashMap<String, ByteArray>()
        for (name in entryNames) {
            digest.reset()
            result[name] = digest.digest(content(name))
        }
        return result
    }

    private fun appendLine(builder: StringBuilder, name: String, value: String) {
        val raw = "$name: $value".toByteArray(Charsets.UTF_8)
        // JAR line folding: 72 bytes per physical line, continuation lines start with a single space.
        var position = 0
        var first = true
        while (position < raw.size) {
            val take = if (first) minOf(72, raw.size) else minOf(71, raw.size - position)
            if (!first) builder.append(' ')
            builder.append(String(raw, position, take, Charsets.UTF_8))
            builder.append("\r\n")
            position += take
            first = false
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
