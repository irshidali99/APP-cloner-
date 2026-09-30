package com.example.engine.core

import com.example.model.CloneMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the manifest level clone mods: every option has to end up in the rewritten manifest, and the
 * options that must not touch a split have to stay away from it.
 */
class ManifestPatcherTest {

    private fun patch(mods: CloneMods, baseOnly: Boolean = true): AxmlEditor {
        val editor = AxmlEditor.parse(TestFixtures.manifestBytes())
        ManifestPatcher.apply(editor, mods, baseOnly)
        // Round tripping through the serialiser proves the offsets written are parseable again.
        return AxmlEditor.parse(editor.toByteArray())
    }

    private fun element(editor: AxmlEditor, name: String): AxmlNode.StartElement? =
        editor.startElements().firstOrNull { editor.elementName(it) == name }

    private fun value(editor: AxmlEditor, element: AxmlNode.StartElement?, attribute: String): Int? =
        element?.let { editor.findAttribute(it, ManifestRules.ANDROID_NAMESPACE, attribute) }?.valueData

    @Test
    fun versionAndSdkOverridesAreWritten() {
        val editor = patch(CloneMods(versionName = "9.9-clone", versionCode = 999, minSdk = 24, targetSdk = 33))
        val manifest = element(editor, "manifest")
        val usesSdk = element(editor, "uses-sdk")

        assertEquals("9.9-clone", editor.findAttribute(manifest!!, ManifestRules.ANDROID_NAMESPACE, "versionName")?.let { editor.string(it.rawValue) })
        assertEquals(999, value(editor, manifest, "versionCode"))
        assertEquals(24, value(editor, usesSdk, "minSdkVersion"))
        assertEquals(33, value(editor, usesSdk, "targetSdkVersion"))
    }

    @Test
    fun identityModsAreWrittenIntoEveryPart() {
        // Splits need the same version as the base, otherwise Android refuses the installation.
        val editor = patch(CloneMods(versionName = "2.0-clone"), baseOnly = false)
        val manifest = element(editor, "manifest")
        assertEquals("2.0-clone", editor.findAttribute(manifest!!, ManifestRules.ANDROID_NAMESPACE, "versionName")?.let { editor.string(it.rawValue) })
    }

    @Test
    fun behaviourModsAreAddedToTheApplicationElement() {
        val editor = patch(
            CloneMods(
                disableBackup = true,
                disableCleartextTraffic = true,
                multiWindow = true,
                pictureInPicture = true,
                installToSdCard = true
            )
        )
        val application = element(editor, "application")
        val manifest = element(editor, "manifest")

        assertEquals(0, value(editor, application, "allowBackup")) // false is stored as 0
        assertEquals(0, value(editor, application, "usesCleartextTraffic"))
        assertEquals(-1, value(editor, application, "resizeableActivity")) // true is stored as -1
        assertEquals(-1, value(editor, application, "supportsPictureInPicture"))
        assertEquals(2, value(editor, manifest, "installLocation"))
    }

    @Test
    fun activityModsAreAddedToEveryActivity() {
        val editor = patch(CloneMods(excludeFromRecents = true, lockRotation = true, kioskMode = true))
        val activity = element(editor, "activity")

        assertEquals(-1, value(editor, activity, "excludeFromRecents"))
        assertEquals(1, value(editor, activity, "screenOrientation")) // portrait
        assertEquals(1, value(editor, activity, "lockTaskMode")) // if_whitelisted
    }

    @Test
    fun selectedPermissionGroupsAreRemoved() {
        val editor = patch(CloneMods(removePermissionGroups = setOf("camera")))
        val permissions = editor.startElements()
            .filter { editor.elementName(it) == "uses-permission" }
            .mapNotNull { editor.findAttribute(it, ManifestRules.ANDROID_NAMESPACE, "name") }
            .mapNotNull { editor.string(it.rawValue) }

        assertFalse(permissions.contains(TestFixtures.DANGEROUS_PERMISSION))
        // A permission that was not selected stays untouched.
        assertTrue(permissions.contains(TestFixtures.PERMISSION_NAME))
    }

    @Test
    fun hidingTheLauncherIconRemovesTheLauncherIntentFilter() {
        val editor = patch(CloneMods(hideLauncherIcon = true))
        assertTrue(
            editor.startElements().none { editor.elementName(it) == "intent-filter" }
        )
        // The activity itself stays, only its launcher entry is gone.
        assertNotNull(element(editor, "activity"))
    }

    @Test
    fun anEmptyModListLeavesTheManifestAlone() {
        val editor = patch(CloneMods())
        val manifest = element(editor, "manifest")
        assertNull(editor.findAttribute(manifest!!, ManifestRules.ANDROID_NAMESPACE, "versionName"))
        assertNull(value(editor, element(editor, "application"), "allowBackup"))
        assertNotNull(element(editor, "uses-sdk"))
    }

    @Test
    fun modsAreReportedForTheCloneDetails() {
        val editor = AxmlEditor.parse(TestFixtures.manifestBytes())
        val applied = ManifestPatcher.apply(
            editor,
            CloneMods(hideLauncherIcon = true, excludeFromRecents = true, removePermissionGroups = setOf("camera")),
            baseOnly = true
        )
        assertTrue(applied.any { it.contains("launcher icon hidden") })
        assertTrue(applied.any { it.contains("recent apps") })
        assertTrue(applied.any { it.contains("permission") })
    }
}
