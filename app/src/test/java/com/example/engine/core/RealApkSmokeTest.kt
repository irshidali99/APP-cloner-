package com.example.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator

/**
 * Runs the cloning engine against a **real world APK** (downloaded by CI) instead of the synthetic fixture.
 *
 * The test is skipped unless `CLONE_SMOKE_APK` points at an APK file. When `CLONE_SMOKE_SPLITS` lists split
 * APKs, the whole bundle is cloned instead. Everything is written next to a `smoke-report.txt` so a build log
 * or an artifact shows exactly what happened:
 *
 * - how many entries were rewritten,
 * - whether the launcher icon was replaced,
 * - whether the package identity matches in manifest and resource table,
 * - whether the v2 signature verifies.
 */
class RealApkSmokeTest {

    private val newPackage = "com.example.clone.smoke"

    @Test
    fun `clones a real world apk when one is configured`() {
        val sourcePath = System.getenv("CLONE_SMOKE_APK") ?: return
        val source = File(sourcePath)
        if (!source.isFile || source.length() == 0L) {
            println("SMOKE: no usable APK at $sourcePath, skipping")
            return
        }

        val outputDirectory = File(System.getenv("CLONE_SMOKE_OUT_DIR") ?: "build/smoke").apply { mkdirs() }
        val splitPaths = System.getenv("CLONE_SMOKE_SPLITS")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.isFile }

        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = X509SelfSigned.create(pair, "App Cloner Smoke")
        val lines = ArrayList<String>()

        fun report(line: String) {
            println("SMOKE: $line")
            lines.add(line)
        }

        report("source=${source.name} size=${source.length()} splits=${splitPaths.size}")

        val parts: List<File>
        val baseReport: CloneReport

        if (splitPaths.isEmpty()) {
            val output = File(outputDirectory, "clone-${source.name}")
            baseReport = ApkTransformer.transform(
                request = CloneRequest(
                    sourceApk = source,
                    outputApk = output,
                    newPackage = newPackage,
                    newLabel = "Smoke Clone",
                    iconPng = ByteArray(600) { 0x55 }
                ),
                certificate = certificate,
                privateKey = pair.private
            )
            parts = listOf(output)
        } else {
            val raw = ApkTransformer.transformBundle(
                request = CloneBundleRequest(
                    baseApk = source,
                    splitApks = splitPaths,
                    outputDirectory = outputDirectory,
                    newPackage = newPackage,
                    newLabel = "Smoke Clone",
                    iconPng = ByteArray(600) { 0x55 }
                ),
                certificate = certificate,
                privateKey = pair.private
            )
            baseReport = raw.base
            parts = raw.apkFiles
            report("bundle parts=${raw.apkFiles.size} splitNames=${raw.splits.mapNotNull { it.splitName }}")
            raw.warnings.take(5).forEach { report("warning: $it") }
        }

        report("base entries=${baseReport.entriesWritten} icons=${baseReport.iconEntriesReplaced.size} " +
            "resourceTable=${baseReport.resourceTableRewritten}")

        for (part in parts) {
            ApkSignatureSchemeV2.signInPlace(part, certificate, pair.private)
            val verification = ApkSignatureSchemeV2.verify(part)
            val identity = ApkTransformer.readPackageIdentity(part)
            val splitName = ApkTransformer.readSplitName(part)
            report(
                "part=${part.name} manifest=${identity.first} arsc=${identity.second} " +
                    "split=${splitName ?: "-"} v1=${ApkSignatureSchemeV2.hasV1Signature(part)} " +
                    "v2=${verification.isValid} schemes=${verification.schemes} detail=${verification.detail}"
            )

            assertEquals("manifest package of ${part.name}", newPackage, identity.first)
            assertTrue("v2 signature of ${part.name}: ${verification.detail}", verification.isValid)
        }

        report("cloneReady=${parts.size} apk(s), total=${parts.sumOf { it.length() }} bytes")
        File(outputDirectory, "smoke-report.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
