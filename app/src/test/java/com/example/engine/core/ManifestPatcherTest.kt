package com.example.engine.core

import com.example.model.CloneMods
import com.example.model.RuntimeOptions
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

    private fun patchWith(mods: CloneMods, isBasePart: Boolean = true): AxmlEditor =
        try {
            val editor = AxmlEditor.parse(TestFixtures.manifestBytes())
            ManifestPatcher.apply(editor, mods, isBasePart)
            // Round tripping through the serialiser proves the offsets written are parseable again.
            AxmlEditor.parse(editor.toByteArray())
        } catch (error: Throwable) {
            throw AssertionError("patching $mods threw $error", error)
        }

    /** Same as [patchWith] but the caller gets the callback form for readable failure messages. */
    private fun patch(mods: CloneMods, isBasePart: Boolean = true): AxmlEditor = patchWith(mods, isBasePart)

    private fun element(editor: AxmlEditor, name: String): AxmlNode.StartElement? =
        editor.startElements().firstOrNull { editor.elementName(it) == name }

    private fun value(editor: AxmlEditor, element: AxmlNode.StartElement?, attribute: String): Int? =
        element?.let { editor.findAttribute(it, ManifestRules.ANDROID_NAMESPACE, attribute) }?.valueData

    /** Every element with all of its attributes, used in failure messages. */
    private fun dump(editor: AxmlEditor): String = buildString {
        for (element in editor.startElements()) {
            append("  <").append(editor.elementName(element)).append('>')
            for (attribute in element.attributes) {
                append(' ').append(editor.attributeName(attribute) ?: "?")
                append('=').append(attribute.valueData)
                append('(').append(attribute.valueType).append(')')
            }
            append('\n')
        }
    }

    private fun check(name: String, mods: CloneMods, block: (AxmlEditor) -> Unit) {
        val editor = try {
            patch(mods)
        } catch (error: Throwable) {
            throw AssertionError("[$name] patching $mods threw $error", error)
        }
        try {
            block(editor)
        } catch (error: Throwable) {
            throw AssertionError("[$name] failed for $mods: $error\nmanifest:\n${dump(editor)}", error)
        }
    }

    @Test
    fun versionAndSdkOverridesAreWritten() {
        val mods = CloneMods(versionName = "9.9-clone", versionCode = 999, minSdk = 24, targetSdk = 33)
        check("version", mods) { editor ->
            val manifest = element(editor, "manifest")
            val usesSdk = element(editor, "uses-sdk")
            assertNotNull("manifest element missing", manifest)
            assertEquals(
                "9.9-clone",
                editor.findAttribute(manifest!!, ManifestRules.ANDROID_NAMESPACE, "versionName")?.let { editor.string(it.rawValue) }
            )
            assertEquals(999, value(editor, manifest, "versionCode"))
            assertEquals(24, value(editor, usesSdk, "minSdkVersion"))
            assertEquals(33, value(editor, usesSdk, "targetSdkVersion"))
        }
    }

    @Test
    fun identityModsAreWrittenIntoEveryPart() {
        // Splits need the same version as the base, otherwise Android refuses the installation.
        val editor = patchWith(CloneMods(versionName = "2.0-clone"), isBasePart = false)
        val manifest = element(editor, "manifest")
        assertEquals("2.0-clone", editor.findAttribute(manifest!!, ManifestRules.ANDROID_NAMESPACE, "versionName")?.let { editor.string(it.rawValue) })
    }

    @Test
    fun behaviourModsAreAddedToTheApplicationElement() {
        val mods = CloneMods(
            disableBackup = true,
            disableCleartextTraffic = true,
            multiWindow = true,
            pictureInPicture = true,
            installToSdCard = true
        )
        check("behaviour", mods) { editor ->
            val application = element(editor, "application")
            val manifest = element(editor, "manifest")
            assertNotNull("application element missing", application)
            assertEquals(0, value(editor, application, "allowBackup")) // false is stored as 0
            assertEquals(0, value(editor, application, "usesCleartextTraffic"))
            assertEquals(-1, value(editor, application, "resizeableActivity")) // true is stored as -1
            assertEquals(-1, value(editor, application, "supportsPictureInPicture"))
            assertEquals(2, value(editor, manifest, "installLocation"))
        }
    }

    @Test
    fun activityModsAreAddedToEveryActivity() {
        val mods = CloneMods(excludeFromRecents = true, lockRotation = true, kioskMode = true)
        check("activity", mods) { editor ->
            val activity = element(editor, "activity")
            assertNotNull("activity element missing", activity)
            assertEquals(-1, value(editor, activity, "excludeFromRecents"))
            assertEquals(1, value(editor, activity, "screenOrientation")) // portrait
            assertEquals(1, value(editor, activity, "lockTaskMode")) // if_whitelisted
        }
    }

    @Test
    fun selectedPermissionGroupsAreRemoved() {
        val mods = CloneMods(removePermissionGroups = setOf("camera"))
        check("permissions", mods) { editor ->
            val permissions = editor.startElements()
                .filter { editor.elementName(it) == "uses-permission" }
                .mapNotNull { editor.findAttribute(it, ManifestRules.ANDROID_NAMESPACE, "name") }
                .mapNotNull { editor.string(it.rawValue) }

            assertTrue("camera permission is still declared: $permissions", !permissions.contains(TestFixtures.DANGEROUS_PERMISSION))
            // A permission that was not selected stays untouched.
            assertTrue("unselected permission was removed: $permissions", permissions.contains(TestFixtures.PERMISSION_NAME))
        }
    }

    @Test
    fun hidingTheLauncherIconRemovesTheLauncherIntentFilter() {
        val editor = patchWith(CloneMods(hideLauncherIcon = true))
        val filters = editor.startElements().filter { editor.elementName(it) == "intent-filter" }
        // Only the launcher entry has to go: the widget receiver keeps its own intent filter.
        val launcherFilters = filters.filter { filter -> isLauncherFilter(editor, filter) }
        assertTrue("launcher filter was not removed: ${launcherFilters.size} left", launcherFilters.isEmpty())
        assertTrue("the widget filter was removed as well", filters.isNotEmpty())
        // The activity itself stays, only its launcher entry is gone.
        assertNotNull("the activity disappeared", element(editor, "activity"))
    }

    /** Same rule the engine uses: a filter that declares MAIN plus the LAUNCHER category. */
    private fun isLauncherFilter(editor: AxmlEditor, filter: AxmlNode.StartElement): Boolean =
        actionsOf(editor, filter).let { values ->
            values.contains("android.intent.action.MAIN") &&
                values.contains("android.intent.category.LAUNCHER")
        }

    /** Collects the `android:name` of every `action` / `category` below [element]. */
    private fun actionsOf(editor: AxmlEditor, element: AxmlNode.StartElement): List<String> = buildList {
        val nodes = editor.nodes
        val start = nodes.indexOf(element)
        if (start < 0) return@buildList
        var depth = 0
        var index = start
        while (index < nodes.size) {
            when (val node = nodes[index]) {
                is AxmlNode.StartElement -> {
                    depth++
                    if (node !== element) {
                        val name = editor.elementName(node)
                        if (name == "action" || name == "category") {
                            editor.findAttribute(node, ManifestRules.ANDROID_NAMESPACE, "name")
                                ?.let { editor.string(it.rawValue) }
                                ?.let { add(it) }
                        }
                    }
                }
                is AxmlNode.EndElement -> {
                    depth--
                    if (depth == 0) return@buildList
                }
                else -> Unit
            }
            index++
        }
    }

    @Test
    fun anEmptyModListLeavesTheManifestAlone() {
        val editor = patchWith(CloneMods())
        val manifest = element(editor, "manifest")
        assertNull(editor.findAttribute(manifest!!, ManifestRules.ANDROID_NAMESPACE, "versionName"))
        assertNull(value(editor, element(editor, "application"), "allowBackup"))
        assertNotNull(element(editor, "uses-sdk"))
    }

    @Test
    fun widgetProvidersAreRemoved() {
        val editor = patchWith(CloneMods(removeWidgets = true))
        val receivers = editor.startElements().filter { editor.elementName(it) == "receiver" }
        assertTrue("widget receiver was not removed: ${receivers.size} left", receivers.isEmpty())
    }

    @Test
    fun extraApplicationAndActivityModsAreWritten() {
        val mods = CloneMods(noHistory = true, largeHeap = true, testOnly = true)
        check("extras", mods) { editor ->
            val application = element(editor, "application")
            val activity = element(editor, "activity")
            assertEquals(-1, value(editor, application, "largeHeap"))
            assertEquals(-1, value(editor, application, "testOnly"))
            assertEquals(-1, value(editor, activity, "noHistory"))
        }
    }

    @Test
    fun extractNativeLibsIsWrittenOnTheApplication() {
        val editor = patchWith(CloneMods(extractNativeLibs = true))
        assertEquals(-1, value(editor, element(editor, "application"), "extractNativeLibs"))
    }

    @Test
    fun runtimeRegistrationAddsTheBootstrapProviderAndTheLockScreen() {
        val runtime = RuntimeOptions(
            lockMode = com.example.model.LockMode.PASSCODE,
            passcodeHash = RuntimeOptions.hash("1234"),
            blockScreenshots = true
        )
        val editor = AxmlEditor.parse(TestFixtures.manifestBytes())
        val applied = RuntimeRegistration.apply(editor, runtime, "com.example.clone.smoke")
        // Round trip: the platform has to be able to parse what the engine writes.
        val reparsed = AxmlEditor.parse(editor.toByteArray())

        // The fixture has a provider of its own, so the injected one is picked by its class name.
        val provider = reparsed.startElements().firstOrNull { element ->
            reparsed.elementName(element) == "provider" &&
                reparsed.findAttribute(element, ManifestRules.ANDROID_NAMESPACE, "name")
                    ?.let { reparsed.string(it.rawValue) } == RuntimeRegistration.PROVIDER_CLASS
        }
        assertNotNull("the bootstrap provider was not added: ${dump(reparsed)}", provider)
        assertEquals(
            RuntimeRegistration.PROVIDER_CLASS,
            reparsed.findAttribute(provider!!, ManifestRules.ANDROID_NAMESPACE, "name")
                ?.let { reparsed.string(it.rawValue) }
        )
        assertEquals(
            "com.example.clone.smoke.appcloner.runtime",
            reparsed.findAttribute(provider, ManifestRules.ANDROID_NAMESPACE, "authorities")
                ?.let { reparsed.string(it.rawValue) }
        )
        assertEquals(
            "the provider must stay private to the clone",
            0,
            reparsed.findAttribute(provider, ManifestRules.ANDROID_NAMESPACE, "exported")?.valueData
        )

        val metaData = reparsed.startElements().firstOrNull { element ->
            reparsed.elementName(element) == "meta-data" &&
                reparsed.findAttribute(element, ManifestRules.ANDROID_NAMESPACE, "name")
                    ?.let { reparsed.string(it.rawValue) } == RuntimeRegistration.CONFIG_META
        }
        assertNotNull("the configuration meta-data was not added: ${dump(reparsed)}", metaData)
        assertEquals(
            RuntimeRegistration.CONFIG_META,
            reparsed.findAttribute(metaData!!, ManifestRules.ANDROID_NAMESPACE, "name")
                ?.let { reparsed.string(it.rawValue) }
        )
        assertEquals(
            runtime.configString(),
            reparsed.findAttribute(metaData, ManifestRules.ANDROID_NAMESPACE, "value")
                ?.let { reparsed.string(it.rawValue) }
        )

        val lockScreen = reparsed.startElements().firstOrNull { element ->
            reparsed.elementName(element) == "activity" &&
                reparsed.findAttribute(element, ManifestRules.ANDROID_NAMESPACE, "name")
                    ?.let { reparsed.string(it.rawValue) } == RuntimeRegistration.LOCK_ACTIVITY_CLASS
        }
        assertNotNull("the passcode screen was not registered: ${dump(reparsed)}", lockScreen)
        assertTrue("the report does not mention the lock: $applied", applied.any { it.contains("passcode") })
        assertTrue("the report does not mention the provider: $applied", applied.any { it.contains("provider") })
    }

    @Test
    fun withoutRuntimeOptionsNoExtraComponentsAreAdded() {
        val editor = AxmlEditor.parse(TestFixtures.manifestBytes())
        val before = editor.startElements().size
        val applied = RuntimeRegistration.apply(editor, RuntimeOptions(), "com.example.clone.smoke")
        assertTrue("nothing should be registered: $applied", applied.isEmpty())
        assertTrue("the manifest changed", editor.toByteArray().isNotEmpty())
        assertEquals(before, AxmlEditor.parse(editor.toByteArray()).startElements().size)
    }

    @Test
    fun modsAreReportedForTheCloneDetails() {
        val editor = AxmlEditor.parse(TestFixtures.manifestBytes())
        val applied = ManifestPatcher.apply(
            editor,
            CloneMods(hideLauncherIcon = true, excludeFromRecents = true, removePermissionGroups = setOf("camera")),
            isBasePart = true
        )
        assertTrue("missing launcher report: $applied", applied.any { it.contains("launcher icon hidden") })
        assertTrue("missing recents report: $applied", applied.any { it.contains("recent apps") })
        assertTrue("missing permission report: $applied", applied.any { it.contains("permission") })
    }
}
