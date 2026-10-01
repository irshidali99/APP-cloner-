package com.example.model

import java.security.MessageDigest

/** How a locked clone asks for its secret. */
enum class LockMode(val key: String, val label: String) {
    NONE("none", "No lock"),
    PASSCODE("passcode", "Passcode"),
    PATTERN("pattern", "Pattern"),
    CALCULATOR("calc", "Calculator disguise");

    companion object {
        fun fromKey(key: String?): LockMode = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * Options that need code *inside* the clone, not only a rewritten manifest (phase 3).
 *
 * They are handed to the runtime patch that App Cloner injects as an extra dex file: the patch reads the
 * ones and zeros from its own manifest meta-data, so the dex stays identical for every clone.
 *
 * The secret is never stored as text - only its SHA-256 hash travels with the clone.
 */
data class RuntimeOptions(
    /** Which lock screen the clone shows, if any. */
    val lockMode: LockMode = LockMode.NONE,
    /** SHA-256 of the passcode (used by [LockMode.PASSCODE] and [LockMode.CALCULATOR]). */
    val passcodeHash: String = "",
    /** SHA-256 of the drawn pattern (used by [LockMode.PATTERN]). */
    val patternHash: String = "",
    /** Sets `FLAG_SECURE` on every screen of the clone: screenshots and the recents preview are blocked. */
    val blockScreenshots: Boolean = false,
    /** Removes everything the clone stored as soon as the user leaves it. */
    val incognitoWipe: Boolean = false,
    /** Ends the clone when the screen is turned off (for apps that should not stay resident). */
    val exitOnScreenOff: Boolean = false,
    /** Forces the clone into dark mode (Android 12+). */
    val forceDarkMode: Boolean = false,
    /** The first back press only asks, the second one leaves the clone. */
    val confirmExit: Boolean = false,
    /** Ends the clone when the phone is shaken. */
    val shakeToExit: Boolean = false,
    /** Shows a small back button inside the clone (useful on large screens). */
    val floatingBackButton: Boolean = false,
    /** Display language of the clone as a BCP-47 tag (Android 13+), empty = system language. */
    val appLanguage: String = "",
    /**
     * Quiet time: during this window the clone's own notifications are cancelled. Both are `HH:MM`
     * (empty = off), and the window may cross midnight (22:00 - 07:00).
     */
    val quietStart: String = "",
    val quietEnd: String = "",
    /** Comma separated words: a clone notification containing one of them is cancelled. */
    val notificationFilter: String = ""
) {

    /** The words of [notificationFilter], cleaned up. */
    fun filterWords(): List<String> = notificationFilter
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** True when a usable quiet window is configured. */
    val quietTimeEnabled: Boolean
        get() = CLOCK.matches(quietStart.trim()) && CLOCK.matches(quietEnd.trim()) &&
            quietStart.trim() != quietEnd.trim()

    /** True when the clone's notifications have to be watched at all (needs user granted access). */
    val notificationFeatures: Boolean get() = quietTimeEnabled || filterWords().isNotEmpty()

    val lockEnabled: Boolean
        get() = when (lockMode) {
            LockMode.NONE -> false
            LockMode.PATTERN -> patternHash.length >= MIN_HASH_LENGTH
            LockMode.PASSCODE, LockMode.CALCULATOR -> passcodeHash.length >= MIN_HASH_LENGTH
        }

    val isEmpty: Boolean
        get() = !lockEnabled && !blockScreenshots && !incognitoWipe && !exitOnScreenOff &&
            !forceDarkMode && !confirmExit && !shakeToExit && !floatingBackButton && appLanguage.isBlank() &&
            !notificationFeatures

    /** The configuration string the injected patch reads from its meta-data. */
    fun configString(): String = buildString {
        append("mode=").append(if (lockEnabled) lockMode.key else LockMode.NONE.key)
        append(";lock=").append(passcodeHash)
        append(";pattern=").append(patternHash)
        append(";shots=").append(if (blockScreenshots) "1" else "0")
        append(";wipe=").append(if (incognitoWipe) "1" else "0")
        append(";screenoff=").append(if (exitOnScreenOff) "1" else "0")
        append(";dark=").append(if (forceDarkMode) "1" else "0")
        append(";confirm=").append(if (confirmExit) "1" else "0")
        append(";shake=").append(if (shakeToExit) "1" else "0")
        append(";fab=").append(if (floatingBackButton) "1" else "0")
        append(";lang=").append(appLanguage)
        append(";quiet=").append(if (quietTimeEnabled) "${quietStart.trim()}-${quietEnd.trim()}" else "")
        append(";nfilter=").append(
            // The patch splits on '|', the setup screen takes commas; ';' and '=' would break the pairs.
            filterWords()
                .map { word -> word.replace(";", " ").replace("=", " ").replace("|", " ") }
                .joinToString("|")
        )
    }

    /** Human readable list for the clone details screen. */
    fun summary(): String {
        val items = ArrayList<String>()
        when {
            !lockEnabled -> Unit
            lockMode == LockMode.PATTERN -> items.add("pattern lock")
            lockMode == LockMode.CALCULATOR -> items.add("passcode lock (calculator disguise)")
            else -> items.add("passcode lock")
        }
        if (blockScreenshots) items.add("screenshots blocked")
        if (incognitoWipe) items.add("incognito (data wiped on exit)")
        if (exitOnScreenOff) items.add("ends when screen turns off")
        if (forceDarkMode) items.add("forced dark mode")
        if (confirmExit) items.add("confirm exit")
        if (shakeToExit) items.add("shake to exit")
        if (floatingBackButton) items.add("floating back button")
        if (appLanguage.isNotBlank()) items.add("language $appLanguage")
        if (quietTimeEnabled) items.add("quiet time ${quietStart.trim()}-${quietEnd.trim()}")
        if (filterWords().isNotEmpty()) items.add("notification filter (${filterWords().size} word(s))")
        return if (items.isEmpty()) "none" else items.joinToString(", ")
    }

    /** Checks a passcode the user typed against the stored hash. */
    fun matches(passcode: String): Boolean = lockEnabled && passcodeHash == hash(passcode)

    /** Checks a drawn pattern ("0,1,4,7") against the stored hash. */
    fun matchesPattern(sequence: String): Boolean = lockEnabled && patternHash == hash(sequence)

    companion object {

        const val MIN_PASSCODE_LENGTH = 4
        /** `HH:MM`, 24 hour clock, as typed on the setup screen. */
        private val CLOCK = Regex("^([01]?[0-9]|2[0-3]):([0-5]?[0-9])$")

        /** Normalises "9:5" to "09:05" so the injected patch always sees the same shape. */
        fun normaliseClock(value: String): String? {
            val trimmed = value.trim()
            if (!CLOCK.matches(trimmed)) return null
            val parts = trimmed.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            return (if (hour < 10) "0$hour" else "$hour") + ":" + (if (minute < 10) "0$minute" else "$minute")
        }
        const val MIN_PATTERN_DOTS = 4
        private const val MIN_HASH_LENGTH = 32
        private const val RESET_SALT = "appcloner-reset:"

        /** SHA-256 as lowercase hex - exactly what the injected patch computes. */
        fun hash(secret: String): String = sha256(secret)

        /** Hash of a pattern: the dots are hashed in the order they were drawn. */
        fun hashPattern(dots: List<Int>): String = sha256(dots.joinToString(","))

        /**
         * One time code that unlocks a clone whose secret was forgotten. It is derived from the clone's
         * package name, so App Cloner can show it without storing anything inside the clone.
         */
        fun resetCode(clonePackageId: String): String =
            sha256(RESET_SALT + clonePackageId).take(8).uppercase()

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                for (byte in digest) {
                    val hex = byte.toInt() and 0xff
                    if (hex < 0x10) append('0')
                    append(hex.toString(16))
                }
            }
        }
    }
}
