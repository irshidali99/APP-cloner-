package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved clone setup that can be applied to any app in one tap.
 *
 * A preset stores everything the setup screen asks for: the name pattern, the icon styling and all the
 * clone mods. Patterns support `{app}` (the source app's label) and `{n}` (the clone number), so the same
 * preset works for apps that already have clones.
 */
@Entity(tableName = "clone_presets")
data class ClonePreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** Name pattern, for example `{app} (Clone {n})`. */
    val namePattern: String = DEFAULT_NAME_PATTERN,
    val badgeColor: Long = 0xFF4F46E5L,
    /** Rotates the clone's icon a little per clone number so clones are told apart at a glance. */
    val rotateIcon: Boolean = true,
    val invertColors: Boolean = false,
    // ---- clone mods -------------------------------------------------------------
    val hideLauncherIcon: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val installToSdCard: Boolean = false,
    val disableBackup: Boolean = false,
    val disableCleartextTraffic: Boolean = false,
    val lockRotation: Boolean = false,
    val multiWindow: Boolean = false,
    val pictureInPicture: Boolean = false,
    val kioskMode: Boolean = false,
    /** Comma separated permission group keys (see [ClonePermissionGroups]). */
    val removePermissionGroups: String = "",
    /** Appended to the clone's version name, for example `-clone`. */
    val versionNameSuffix: String = "",
    val removeWidgets: Boolean = false,
    val noHistory: Boolean = false,
    val largeHeap: Boolean = false,
    val testOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {

    /** The mods of this preset. */
    fun toMods(versionName: String? = null): CloneMods = CloneMods(
        versionName = versionName,
        hideLauncherIcon = hideLauncherIcon,
        excludeFromRecents = excludeFromRecents,
        installToSdCard = installToSdCard,
        disableBackup = disableBackup,
        disableCleartextTraffic = disableCleartextTraffic,
        lockRotation = lockRotation,
        multiWindow = multiWindow,
        pictureInPicture = pictureInPicture,
        kioskMode = kioskMode,
        removePermissionGroups = permissionGroups(),
        removeWidgets = removeWidgets,
        noHistory = noHistory,
        largeHeap = largeHeap,
        testOnly = testOnly
    )

    fun permissionGroups(): Set<String> =
        removePermissionGroups.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Fills the name pattern for one clone. */
    fun cloneName(sourceLabel: String, cloneIndex: Int): String {
        val pattern = namePattern.ifBlank { DEFAULT_NAME_PATTERN }
        return pattern
            .replace(PLACEHOLDER_APP, sourceLabel)
            .replace(PLACEHOLDER_INDEX, cloneIndex.toString())
            .trim()
    }

    /** A copy of this preset with the settings the setup screen currently shows. */
    fun withSettings(
        namePattern: String,
        badgeColor: Long,
        rotateIcon: Boolean,
        invertColors: Boolean,
        mods: CloneMods,
        versionNameSuffix: String
    ): ClonePreset = copy(
        namePattern = namePattern,
        badgeColor = badgeColor,
        rotateIcon = rotateIcon,
        invertColors = invertColors,
        hideLauncherIcon = mods.hideLauncherIcon,
        excludeFromRecents = mods.excludeFromRecents,
        installToSdCard = mods.installToSdCard,
        disableBackup = mods.disableBackup,
        disableCleartextTraffic = mods.disableCleartextTraffic,
        lockRotation = mods.lockRotation,
        multiWindow = mods.multiWindow,
        pictureInPicture = mods.pictureInPicture,
        kioskMode = mods.kioskMode,
        removePermissionGroups = mods.removePermissionGroups.sorted().joinToString(","),
        removeWidgets = mods.removeWidgets,
        noHistory = mods.noHistory,
        largeHeap = mods.largeHeap,
        testOnly = mods.testOnly,
        versionNameSuffix = versionNameSuffix
    )

    /** One line description for the preset list. */
    fun summary(): String {
        val mods = ArrayList<String>()
        if (hideLauncherIcon) mods.add("no icon")
        if (excludeFromRecents) mods.add("hidden from recents")
        if (installToSdCard) mods.add("SD card")
        if (disableBackup) mods.add("no backup")
        if (disableCleartextTraffic) mods.add("no http")
        if (lockRotation) mods.add("portrait")
        if (multiWindow) mods.add("multi window")
        if (pictureInPicture) mods.add("PiP")
        if (kioskMode) mods.add("kiosk")
        if (removeWidgets) mods.add("no widgets")
        if (noHistory) mods.add("no history")
        if (largeHeap) mods.add("large heap")
        if (testOnly) mods.add("test only")
        val groups = permissionGroups()
        if (groups.isNotEmpty()) mods.add("no ${groups.sorted().joinToString("/")}")
        return if (mods.isEmpty()) "no mods" else mods.joinToString(", ")
    }

    companion object {
        /**
         * Builds a name pattern out of a concrete clone name, so "WhatsApp (Clone 3)" becomes
         * "{app} (Clone {n})" and the preset can be reused for other apps.
         */
        fun derivePattern(cloneName: String, sourceLabel: String, cloneIndex: Int): String {
            var pattern = cloneName.trim()
            if (pattern.isEmpty()) return DEFAULT_NAME_PATTERN
            if (sourceLabel.isNotEmpty() && pattern.contains(sourceLabel)) {
                pattern = pattern.replace(sourceLabel, PLACEHOLDER_APP)
            }
            if (cloneIndex > 0) {
                pattern = pattern.replace(Regex("(?<![0-9])" + cloneIndex + "(?![0-9])"), PLACEHOLDER_INDEX)
            }
            return pattern.ifBlank { DEFAULT_NAME_PATTERN }
        }

        const val DEFAULT_NAME_PATTERN = "{app} (Clone {n})"
        const val PLACEHOLDER_APP = "{app}"
        const val PLACEHOLDER_INDEX = "{n}"
    }
}
