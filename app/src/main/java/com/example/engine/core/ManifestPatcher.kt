package com.example.engine.core

import com.example.model.CloneMods
import com.example.model.ClonePermissionGroups

/**
 * Applies the user's clone mods to a parsed `AndroidManifest.xml`.
 *
 * Everything here is plain manifest rewriting, which is exactly what a normally installed app would
 * declare for itself - no code is injected and the original app is never touched:
 *
 *  - the clone is kept out of the recent apps list / off the launcher,
 *  - it can prefer the SD card, refuse backups and refuse unencrypted traffic,
 *  - its orientation can be locked, multi window / picture in picture / kiosk mode declared,
 *  - its version (name, code, min and target SDK) can be overridden,
 *  - permissions can be dropped so the cloned app can never ask for them.
 *
 * Identity mods (version, SDK levels) are applied to *every* part of an app bundle, because Android
 * refuses an installation whose splits disagree about the version. Behaviour mods are applied to the base
 * APK only: splits have no launcher entry, no application element of their own and rarely permissions.
 */
object ManifestPatcher {

    /** `android:installLocation` values. */
    private const val INSTALL_PREFER_EXTERNAL = 2

    /** `android:screenOrientation` values. */
    private const val ORIENTATION_PORTRAIT = 1

    /** `android:lockTaskMode` values. */
    private const val LOCK_TASK_IF_WHITELISTED = 1

    /** AXML stores `true` as -1 (0xFFFFFFFF) and `false` as 0. */
    private const val BOOLEAN_TRUE = -1
    private const val BOOLEAN_FALSE = 0

    /**
     * @param baseOnly `false` while a split is being rewritten: only the identity mods are applied then.
     * @return one description per mod that was really applied, for the clone report.
     */
    fun apply(editor: AxmlEditor, mods: CloneMods, baseOnly: Boolean): List<String> {
        val applied = ArrayList<String>()
        if (mods.isEmpty) return applied

        val elements = editor.startElements()
        val manifest = elements.firstOrNull { editor.elementName(it) == "manifest" }
        val usesSdk = elements.firstOrNull { editor.elementName(it) == "uses-sdk" }
        val application = elements.firstOrNull { editor.elementName(it) == "application" }

        // ------------------------------------------------------------------ identity (every part)
        if (manifest != null) {
            mods.versionCode?.let { code ->
                setInt(editor, manifest, "versionCode", android.R.attr.versionCode, ResValueType.INT_DEC, code.toInt())
                applied.add("version code ${code}")
            }
            mods.versionName?.let { name ->
                setString(editor, manifest, "versionName", android.R.attr.versionName, name)
                applied.add("version name \"$name\"")
            }
        }
        if (usesSdk != null) {
            mods.minSdk?.let { sdk ->
                setInt(editor, usesSdk, "minSdkVersion", android.R.attr.minSdkVersion, ResValueType.INT_DEC, sdk)
                applied.add("min SDK $sdk")
            }
            mods.targetSdk?.let { sdk ->
                setInt(editor, usesSdk, "targetSdkVersion", android.R.attr.targetSdkVersion, ResValueType.INT_DEC, sdk)
                applied.add("target SDK $sdk")
            }
        }

        if (baseOnly) return applied

        // ------------------------------------------------------------------ storage & privacy (base only)
        if (manifest != null && mods.installToSdCard) {
            setInt(
                editor, manifest, "installLocation", android.R.attr.installLocation,
                ResValueType.INT_DEC, INSTALL_PREFER_EXTERNAL
            )
            applied.add("prefers SD card")
        }
        if (application != null) {
            if (mods.disableBackup) {
                setInt(editor, application, "allowBackup", android.R.attr.allowBackup, ResValueType.INT_BOOLEAN, BOOLEAN_FALSE)
                applied.add("backup disabled")
            }
            if (mods.disableCleartextTraffic) {
                setInt(
                    editor, application, "usesCleartextTraffic", android.R.attr.usesCleartextTraffic,
                    ResValueType.INT_BOOLEAN, BOOLEAN_FALSE
                )
                applied.add("unencrypted traffic blocked")
            }
            if (mods.multiWindow) {
                setInt(
                    editor, application, "resizeableActivity", android.R.attr.resizeableActivity,
                    ResValueType.INT_BOOLEAN, BOOLEAN_TRUE
                )
                applied.add("multi window enabled")
            }
            if (mods.pictureInPicture) {
                setInt(
                    editor, application, "supportsPictureInPicture", android.R.attr.supportsPictureInPicture,
                    ResValueType.INT_BOOLEAN, BOOLEAN_TRUE
                )
                applied.add("picture in picture enabled")
            }
        }

        // ------------------------------------------------------------------ activities (base only)
        var excluded = 0
        var locked = 0
        var kiosk = 0
        for (element in elements) {
            val name = editor.elementName(element)
            if (name != "activity" && name != "activity-alias") continue
            if (mods.excludeFromRecents) {
                setInt(
                    editor, element, "excludeFromRecents", android.R.attr.excludeFromRecents,
                    ResValueType.INT_BOOLEAN, BOOLEAN_TRUE
                )
                excluded++
            }
            if (mods.lockRotation) {
                setInt(
                    editor, element, "screenOrientation", android.R.attr.screenOrientation,
                    ResValueType.INT_DEC, ORIENTATION_PORTRAIT
                )
                locked++
            }
            if (mods.kioskMode) {
                setInt(
                    editor, element, "lockTaskMode", android.R.attr.lockTaskMode,
                    ResValueType.INT_DEC, LOCK_TASK_IF_WHITELISTED
                )
                kiosk++
            }
        }
        if (excluded > 0) applied.add("hidden from recent apps ($excluded)")
        if (locked > 0) applied.add("portrait locked ($locked)")
        if (kiosk > 0) applied.add("kiosk mode ($kiosk)")

        // ------------------------------------------------------------------ permissions (base only)
        if (mods.removePermissionGroups.isNotEmpty()) {
            var removed = 0
            for (element in elements) {
                val name = editor.elementName(element)
                if (name != "uses-permission" && name != "uses-permission-sdk-23") continue
                val permission = editor.findAttribute(element, ManifestRules.ANDROID_NAMESPACE, "name")
                    ?.let { editor.string(it.rawValue) }
                    .orEmpty()
                if (permission.isNotEmpty() &&
                    ClonePermissionGroups.matchesAny(permission, mods.removePermissionGroups)
                ) {
                    editor.removeElement(element)
                    removed++
                }
            }
            if (removed > 0) {
                applied.add(
                    "$removed permission(s) removed (" +
                        mods.removePermissionGroups.joinToString { ClonePermissionGroups.label(it) } + ")"
                )
            }
        }

        // ------------------------------------------------------------------ launcher icon (base only)
        if (mods.hideLauncherIcon) {
            val launcherFilters = elements.filter { element ->
                editor.elementName(element) == "intent-filter" && isLauncherFilter(editor, element)
            }
            launcherFilters.forEach { editor.removeElement(it) }
            if (launcherFilters.isNotEmpty()) {
                applied.add("launcher icon hidden")
            }
        }

        return applied
    }

    /** True when the intent filter contains both `MAIN` and `LAUNCHER`, which makes an app launchable. */
    private fun isLauncherFilter(editor: AxmlEditor, filter: AxmlNode.StartElement): Boolean {
        val nodes = editor.nodes
        val start = nodes.indexOf(filter)
        if (start < 0) return false
        var depth = 0
        var hasMain = false
        var hasLauncher = false
        var index = start
        while (index < nodes.size) {
            when (val node = nodes[index]) {
                is AxmlNode.StartElement -> {
                    depth++
                    if (node !== filter) {
                        val name = editor.elementName(node)
                        val value = editor.findAttribute(node, ManifestRules.ANDROID_NAMESPACE, "name")
                            ?.let { editor.string(it.rawValue) }
                        if (name == "action" && value == "android.intent.action.MAIN") hasMain = true
                        if (name == "category" && value == "android.intent.category.LAUNCHER") hasLauncher = true
                    }
                }
                is AxmlNode.EndElement -> {
                    depth--
                    if (depth == 0) return hasMain && hasLauncher
                }
                else -> Unit
            }
            index++
        }
        return hasMain && hasLauncher
    }

    private fun setInt(
        editor: AxmlEditor,
        element: AxmlNode.StartElement,
        name: String,
        resourceId: Int,
        valueType: Int,
        value: Int
    ) {
        val existing = editor.findAttribute(element, ManifestRules.ANDROID_NAMESPACE, name)
        if (existing != null) {
            editor.setIntAttribute(existing, valueType, value)
        } else {
            editor.addAttribute(
                element = element,
                namespaceUri = ManifestRules.ANDROID_NAMESPACE,
                name = name,
                resourceId = resourceId,
                valueType = valueType,
                valueData = value
            )
        }
    }

    private fun setString(
        editor: AxmlEditor,
        element: AxmlNode.StartElement,
        name: String,
        resourceId: Int,
        value: String
    ) {
        val existing = editor.findAttribute(element, ManifestRules.ANDROID_NAMESPACE, name)
        if (existing != null) {
            editor.setStringAttribute(existing, value)
        } else {
            val index = editor.intern(value)
            editor.addAttribute(
                element = element,
                namespaceUri = ManifestRules.ANDROID_NAMESPACE,
                name = name,
                resourceId = resourceId,
                valueType = ResValueType.STRING,
                valueData = index
            )
        }
    }
}
