package com.example.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Guards the two things that make runtime features possible on a phone without a compiler:
 *
 *  - the patch dex is compiled by CI (`scripts/build-runtime-dex.sh`) and has to be shipped with the app,
 *  - the injected dex must never collide with a dex file the cloned app already has.
 */
class RuntimePatcherTest {

    private fun assetDex(): File? = listOf(
        File("src/main/assets/runtime/patch.dex"),
        File("app/src/main/assets/runtime/patch.dex")
    ).firstOrNull { it.isFile }

    @Test
    fun theShippedRuntimePatchIsARealDexFile() {
        val dex = assetDex()
        assertNotNull(
            "app/src/main/assets/runtime/patch.dex is missing - run scripts/build-runtime-dex.sh " +
                "(CI does it before every build)",
            dex
        )
        val bytes = dex!!.readBytes()
        assertEquals("the asset is not a dex file", "dex\n", bytes.decodeToString(0, 4))
        assertTrue("the dex is suspiciously small: ${bytes.size} bytes", bytes.size > 1000)
        // The classes the manifest registers have to be inside, otherwise the provider cannot start.
        val text = bytes.decodeToString()
        assertTrue("PatchProvider missing from the dex", text.contains("com/appcloner/runtime/PatchProvider"))
        assertTrue("LockActivity missing from the dex", text.contains("com/appcloner/runtime/LockActivity"))
        assertTrue("AppClonerPatch missing from the dex", text.contains("com/appcloner/runtime/AppClonerPatch"))
        assertTrue(
            "CloneNotificationListener missing from the dex",
            text.contains("com/appcloner/runtime/CloneNotificationListener")
        )
        assertTrue("PatternView missing from the dex", text.contains("com/appcloner/runtime/PatternView"))
    }

    @Test
    fun theInjectedDexNeverOverwritesAnExistingOne() {
        val apk = File.createTempFile("runtime-test", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex")); zip.write(ByteArray(16)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes2.dex")); zip.write(ByteArray(16)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("resources.arsc")); zip.write(ByteArray(16)); zip.closeEntry()
            }
            assertEquals("classes3.dex", RuntimePatcher.nextDexEntryName(apk))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun aSingleDexAppGetsClasses2() {
        val apk = File.createTempFile("runtime-test", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex")); zip.write(ByteArray(16)); zip.closeEntry()
            }
            assertEquals("classes2.dex", RuntimePatcher.nextDexEntryName(apk))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun aMissingAssetIsReportedAsSuch() {
        assertFalse(RuntimePatcher.isDex(null))
        assertFalse(RuntimePatcher.isDex(ByteArray(0)))
        assertFalse(RuntimePatcher.isDex("not a dex file at all".toByteArray()))
    }
}
