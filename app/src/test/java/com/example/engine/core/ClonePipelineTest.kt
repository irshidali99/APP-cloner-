package com.example.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.jar.JarFile

/**
 * End to end tests for the cloning engine.
 *
 * They run as plain JVM tests (no Android framework, no device) because the whole engine is pure Kotlin
 * plus the JDK: a synthetic APK is built in [TestFixtures], transformed, signed and then verified with
 * independent verifiers (`java.util.jar` for v1 and the engine's own v2 verifier).
 */
class ClonePipelineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val newPackage = "com.original.game.clone1"
    private val newLabel = "Original Game (Clone 1)"

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun sourceApk(): File = TestFixtures.apk(File(temporary.root, "original.apk"))

    // ------------------------------------------------------------------ fixtures

    @Test
    fun `fixture manifest parses`() {
        val apk = sourceApk()
        val archive = ZipArchive(apk)
        try {
            val editor = AxmlEditor.parse(
                archive.readEntry(archive.findEntry(ApkTransformer.MANIFEST)!!)
            )
            val root = editor.startElements().first { editor.elementName(it) == "manifest" }
            val packageAttribute = editor.findAttribute(root, null, "package")!!
            assertEquals(TestFixtures.ORIGINAL_PACKAGE, editor.string(packageAttribute.rawValue))
            assertTrue(editor.startElements().any { editor.elementName(it) == "provider" })
        } finally {
            archive.close()
        }
    }

    @Test
    fun `fixture resource table parses and resolves the launcher icon`() {
        val archive = ZipArchive(sourceApk())
        try {
            val arsc = ArscEditor.parse(
                archive.readEntry(archive.findEntry(ApkTransformer.RESOURCES)!!)
            )
            assertEquals(TestFixtures.ORIGINAL_PACKAGE, arsc.primaryPackage?.nameUtf16)
            assertTrue("package name capacity", arsc.packageNameCapacity() >= 26)

            val icon = arsc.findResource(0x7f010000)
            assertNotNull("icon resource must resolve", icon)
            assertEquals("mipmap", icon!!.typeName)
            assertEquals("ic_launcher", icon.entryName)
            assertTrue(icon.matchesZipEntry(TestFixtures.ICON_PATH))
        } finally {
            archive.close()
        }
    }

    @Test
    fun `generated certificate is a valid self signed X509`() {
        val pair = keyPair()
        val certificate = X509SelfSigned.create(pair, "App Cloner Test")
        assertTrue(certificate.subjectX500Principal.name.contains("App Cloner Test"))
        certificate.verify(pair.public) // throws when the signature does not match
    }

    // ------------------------------------------------------------------ transform

    @Test
    fun `transform rewrites both package identities and the launcher icon`() {
        val pair = keyPair()
        val certificate = X509SelfSigned.create(pair, "App Cloner Test")
        val clone = File(temporary.root, "clone.apk")

        val report = ApkTransformer.transform(
            CloneRequest(
                sourceApk = sourceApk(),
                outputApk = clone,
                newPackage = newPackage,
                newLabel = newLabel,
                iconPng = ByteArray(700) { 0x33 }
            ),
            certificate,
            pair.private
        )

        assertTrue(report.manifestRewritten)
        assertTrue(report.resourceTableRewritten)
        assertEquals(listOf(TestFixtures.ICON_PATH), report.iconEntriesReplaced)

        val identity = ApkTransformer.readPackageIdentity(clone)
        assertEquals(newPackage, identity.first)
        assertEquals(newPackage, identity.second)

        val archive = ZipArchive(clone)
        try {
            // the old signature must be gone, ours must be there
            assertTrue(archive.entries.none { it.name == "META-INF/OLD.SF" })
            assertTrue(archive.entries.none { it.name == "META-INF/OLD.RSA" })
            assertNotNull(archive.findEntry("META-INF/MANIFEST.MF"))
            assertNotNull(archive.findEntry("META-INF/CERT.SF"))
            assertNotNull(archive.findEntry("META-INF/CERT.RSA"))

            // alignment rules that Android's resource loader relies on
            val resourceTable = archive.findEntry(ApkTransformer.RESOURCES)!!
            assertTrue("resources.arsc must be stored", resourceTable.isStored)
            assertEquals(0L, archive.dataOffsetOf(resourceTable) % 4)
            assertFalse("deflated entries stay deflated", archive.findEntry("classes.dex")!!.isStored)

            // manifest details: package, authorities and label
            val editor = AxmlEditor.parse(archive.readEntry(archive.findEntry(ApkTransformer.MANIFEST)!!))
            val root = editor.startElements().first { editor.elementName(it) == "manifest" }
            val packageAttribute = editor.findAttribute(root, null, "package")!!
            assertEquals(newPackage, editor.string(packageAttribute.valueData))

            val provider = editor.startElements().first { editor.elementName(it) == "provider" }
            val authorities = editor.findAttribute(provider, TestFixtures.ANDROID_NAMESPACE, "authorities")!!
            assertEquals("$newPackage.provider", editor.string(authorities.rawValue))
            val providerName = editor.findAttribute(provider, TestFixtures.ANDROID_NAMESPACE, "name")!!
            assertEquals("com.original.game.DataProvider", editor.string(providerName.rawValue))

            val application = editor.startElements().first { editor.elementName(it) == "application" }
            val label = editor.findAttribute(application, TestFixtures.ANDROID_NAMESPACE, "label")!!
            assertEquals(newLabel, editor.string(label.rawValue))

            // the injected icon really replaced the launcher bitmap
            val iconBytes = archive.readEntry(archive.findEntry(TestFixtures.ICON_PATH)!!)
            assertEquals(700, iconBytes.size)
        } finally {
            archive.close()
        }
    }

    @Test
    fun `java util jar accepts the generated v1 signature`() {
        val pair = keyPair()
        val clone = File(temporary.root, "v1.apk")
        ApkTransformer.transform(
            CloneRequest(sourceApk(), clone, newPackage, newLabel),
            X509SelfSigned.create(pair, "App Cloner Test"),
            pair.private
        )

        // The JVM verifier throws SecurityException on a broken digest or a missing certificate.
        val jar = JarFile(clone, true)
        try {
            var signedEntries = 0
            for (entry in jar.entries()) {
                jar.getInputStream(entry).use { it.readBytes() }
                if (!entry.name.startsWith("META-INF/")) {
                    assertFalse(
                        "entry ${entry.name} has no certificate",
                        entry.certificates.isNullOrEmpty()
                    )
                    signedEntries++
                }
            }
            assertTrue(signedEntries > 0)
        } finally {
            jar.close()
        }
        assertTrue(ApkSignatureSchemeV2.hasV1Signature(clone))
    }

    // ------------------------------------------------------------------ v2 + tamper

    @Test
    fun `v2 signing verifies and reports both schemes`() {
        val pair = keyPair()
        val clone = File(temporary.root, "v2.apk")
        ApkTransformer.transform(
            CloneRequest(sourceApk(), clone, newPackage, newLabel),
            X509SelfSigned.create(pair, "App Cloner Test"),
            pair.private
        )

        ApkSignatureSchemeV2.signInPlace(clone, X509SelfSigned.create(pair, "App Cloner Test"), pair.private)

        val result = ApkSignatureSchemeV2.verify(clone)
        assertTrue(result.detail, result.isValid)
        assertEquals(2, result.schemeVersion)
        assertEquals(listOf("v1", "v2"), result.schemes)
        assertEquals(newPackage, ApkTransformer.readPackageIdentity(clone).first)
    }

    @Test
    fun `tampering after signing is detected`() {
        val pair = keyPair()
        val clone = File(temporary.root, "signed.apk")
        ApkTransformer.transform(
            CloneRequest(sourceApk(), clone, newPackage, newLabel),
            X509SelfSigned.create(pair, "App Cloner Test"),
            pair.private
        )
        ApkSignatureSchemeV2.signInPlace(clone, X509SelfSigned.create(pair, "App Cloner Test"), pair.private)
        assertTrue(ApkSignatureSchemeV2.verify(clone).isValid)

        val bytes = clone.readBytes()
        bytes[bytes.indexOfFirst { it == 0.toByte() }] = 0x42
        clone.writeBytes(bytes)

        val tampered = ApkSignatureSchemeV2.verify(clone)
        assertFalse(tampered.isValid)
        assertTrue(tampered.detail.contains("digest mismatch"))
    }

    @Test
    fun `package name that does not fit the resource table is rejected clearly`() {
        val pair = keyPair()
        val tooLong = "a".repeat(200) + ".b"
        val failure = runCatching {
            ApkTransformer.transform(
                CloneRequest(sourceApk(), File(temporary.root, "long.apk"), tooLong),
                X509SelfSigned.create(pair, "App Cloner Test"),
                pair.private
            )
        }
        assertTrue("transform must fail", failure.isFailure)
        assertTrue(
            failure.exceptionOrNull()!!.message!!,
            failure.exceptionOrNull()!!.message!!.contains("does not fit")
        )
    }
}
