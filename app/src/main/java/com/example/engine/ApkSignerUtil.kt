package com.example.engine

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles cryptographic key generation and JAR (v1) APK signing.
 */
object ApkSignerUtil {

    private const val KEY_ALIAS = "app_cloner_key"
    private const val KEY_PASSWORD = "app_cloner_pass"
    private const val KEYSTORE_NAME = "app_cloner.bks"

    /**
     * Signs an unsigned APK file with a local private key and writes output signed APK.
     */
    fun signApk(context: Context, inputApk: File, outputApk: File): Boolean {
        return try {
            val keyPair = getOrCreateKeyPair(context)
            signZip(inputApk, outputApk, keyPair.private, keyPair.public as java.security.PublicKey)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getOrCreateKeyPair(context: Context): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        return keyGen.generateKeyPair()
    }

    private fun signZip(
        inputFile: File,
        outputFile: File,
        privateKey: PrivateKey,
        publicKey: java.security.PublicKey
    ) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Created-By")] = "App Cloner Engine"

        val fileEntries = mutableMapOf<String, ByteArray>()
        val sha256 = MessageDigest.getInstance("SHA-256")

        ZipInputStream(FileInputStream(inputFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!name.startsWith("META-INF/")) {
                    val bytes = zis.readBytes()
                    fileEntries[name] = bytes

                    sha256.reset()
                    val digest = sha256.digest(bytes)
                    val digestBase64 = Base64.encodeToString(digest, Base64.NO_WRAP)

                    val attr = Attributes()
                    attr[Attributes.Name("SHA-256-Digest")] = digestBase64
                    manifest.entries[name] = attr
                }
                entry = zis.nextEntry
            }
        }

        val manifestBytes = ByteArrayOutputStream().apply {
            manifest.write(this)
        }.toByteArray()

        val sfAttributes = Attributes()
        sfAttributes[Attributes.Name("Signature-Version")] = "1.0"
        sfAttributes[Attributes.Name("Created-By")] = "App Cloner Engine"
        sha256.reset()
        sfAttributes[Attributes.Name("SHA-256-Digest-Manifest")] =
            Base64.encodeToString(sha256.digest(manifestBytes), Base64.NO_WRAP)

        val sfManifest = Manifest()
        sfManifest.mainAttributes.putAll(sfAttributes)
        for ((name, attr) in manifest.entries) {
            val sectionAttr = Attributes()
            sectionAttr[Attributes.Name("SHA-256-Digest")] = attr.getValue("SHA-256-Digest")
            sfManifest.entries[name] = sectionAttr
        }

        val sfBytes = ByteArrayOutputStream().apply {
            sfManifest.write(this)
        }.toByteArray()

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(sfBytes)
        val signatureBytes = sig.sign()

        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
            zos.write(sfBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
            zos.write(signatureBytes)
            zos.closeEntry()

            for ((name, bytes) in fileEntries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}
