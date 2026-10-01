package com.example.model

import java.security.MessageDigest

/**
 * Options that need code *inside* the clone, not only a rewritten manifest (phase 3).
 *
 * They are handed to the runtime patch that App Cloner injects as an extra dex file: the patch reads the
 * ones and zeros from its own manifest meta-data, so the dex stays identical for every clone.
 *
 * The passcode is never stored as text - only its SHA-256 hash travels with the clone.
 */
data class RuntimeOptions(
    /** SHA-256 of the passcode, empty when the clone is not locked. */
    val passcodeHash: String = "",
    /** Sets `FLAG_SECURE` on every screen of the clone: screenshots and the recents preview are blocked. */
    val blockScreenshots: Boolean = false,
    /** Removes everything the clone stored as soon as the user leaves it. */
    val incognitoWipe: Boolean = false,
    /** Ends the clone when the screen is turned off (for apps that should not stay resident). */
    val exitOnScreenOff: Boolean = false
) {

    val lockEnabled: Boolean get() = passcodeHash.length >= MIN_HASH_LENGTH

    val isEmpty: Boolean
        get() = !lockEnabled && !blockScreenshots && !incognitoWipe && !exitOnScreenOff

    /** The configuration string the injected patch reads from its meta-data. */
    fun configString(): String = buildString {
        append("lock=").append(if (lockEnabled) passcodeHash else "")
        append(";shots=").append(if (blockScreenshots) "1" else "0")
        append(";wipe=").append(if (incognitoWipe) "1" else "0")
        append(";screenoff=").append(if (exitOnScreenOff) "1" else "0")
    }

    /** Human readable list for the clone details screen. */
    fun summary(): String {
        val items = ArrayList<String>()
        if (lockEnabled) items.add("passcode lock")
        if (blockScreenshots) items.add("screenshots blocked")
        if (incognitoWipe) items.add("incognito (data wiped on exit)")
        if (exitOnScreenOff) items.add("ends when screen turns off")
        return if (items.isEmpty()) "none" else items.joinToString(", ")
    }

    /**
     * Checks a passcode the user typed against the stored hash. The same algorithm runs inside the clone.
     */
    fun matches(passcode: String): Boolean = lockEnabled && passcodeHash == hash(passcode)

    companion object {

        const val MIN_PASSCODE_LENGTH = 4
        private const val MIN_HASH_LENGTH = 32
        private const val RESET_SALT = "appcloner-reset:"

        /** SHA-256 as lowercase hex - exactly what the injected patch computes. */
        fun hash(passcode: String): String = sha256(passcode)

        /**
         * One time code that unlocks a clone whose passcode was forgotten. It is derived from the clone's
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
