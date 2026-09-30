package com.example.model

/**
 * Options that are applied to a clone while its APK is built ("clone mods").
 *
 * All of these work purely by rewriting the clone's `AndroidManifest.xml`, so they neither need root nor
 * change the app's code: the settings are what a normally installed app would declare for itself.
 */
data class CloneMods(
    /** Overrides the version name shown in Android's app info. */
    val versionName: String? = null,
    /** Overrides the internal version code (useful to keep a clone "older" than the original). */
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    /** Removes the launcher icon: the app can then only be started from App Cloner ("stealth"). */
    val hideLauncherIcon: Boolean = false,
    /** Keeps the clone out of the recent apps list. */
    val excludeFromRecents: Boolean = false,
    /** Prefers the SD card / external storage as install location. */
    val installToSdCard: Boolean = false,
    /** Turns off cloud backup and device transfer for the clone. */
    val disableBackup: Boolean = false,
    /** Refuses unencrypted (http) network traffic. */
    val disableCleartextTraffic: Boolean = false,
    /** Locks every activity to portrait orientation. */
    val lockRotation: Boolean = false,
    /** Allows split screen / free-form windows. */
    val multiWindow: Boolean = false,
    /** Declares picture-in-picture support. */
    val pictureInPicture: Boolean = false,
    /** Declares lock task (kiosk) mode for the clone's activities. */
    val kioskMode: Boolean = false,
    /** Permission groups that are removed from the clone's manifest. */
    val removePermissionGroups: Set<String> = emptySet()
) {

    /** True when no mod is selected, so the manifest can be left untouched. */
    val isEmpty: Boolean
        get() = versionName == null && versionCode == null && minSdk == null && targetSdk == null &&
            !hideLauncherIcon && !excludeFromRecents && !installToSdCard && !disableBackup &&
            !disableCleartextTraffic && !lockRotation && !multiWindow && !pictureInPicture &&
            !kioskMode && removePermissionGroups.isEmpty()
}

/**
 * The permission groups a clone can drop, mapped to the platform permissions they cover.
 *
 * Removing permissions only changes the manifest: the platform never grants them, so the cloned app cannot
 * ask for them either. It does not break the original app, which stays installed and untouched.
 */
object ClonePermissionGroups {

    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "contacts" to listOf("READ_CONTACTS", "WRITE_CONTACTS"),
        "calendar" to listOf("READ_CALENDAR", "WRITE_CALENDAR"),
        "sms" to listOf("READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH"),
        "call_log" to listOf("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS"),
        "phone" to listOf(
            "CALL_PHONE", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "ANSWER_PHONE_CALLS",
            "ADD_VOICEMAIL", "USE_SIP"
        ),
        "camera" to listOf("CAMERA"),
        "microphone" to listOf("RECORD_AUDIO"),
        "location" to listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"),
        "storage" to listOf(
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"
        ),
        "accounts" to listOf("GET_ACCOUNTS", "AUTHENTICATE_ACCOUNTS", "MANAGE_ACCOUNTS", "USE_CREDENTIALS"),
        "sensors" to listOf("BODY_SENSORS", "ACTIVITY_RECOGNITION", "BODY_SENSORS_BACKGROUND"),
        "nearby" to listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE")
    )

    /** Human readable label for a group key. */
    fun label(group: String): String = group.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { character -> character.uppercase() }
    }

    /** True when [permissionName] (for example `android.permission.CAMERA`) belongs to [group]. */
    fun belongsTo(permissionName: String, group: String): Boolean {
        val suffix = permissionName.substringAfterLast('.')
        return GROUPS[group]?.any { it == suffix } == true
    }

    /** True when [permissionName] belongs to any of [groups]. */
    fun matchesAny(permissionName: String, groups: Set<String>): Boolean =
        groups.any { belongsTo(permissionName, it) }
}
