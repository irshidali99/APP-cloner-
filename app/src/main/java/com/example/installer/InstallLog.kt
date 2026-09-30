package com.example.installer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers what the Android package installer answered for the last installation attempt of a clone.
 *
 * The installer's own message (for example `INSTALL_FAILED_DUPLICATE_PERMISSION`) is the only reliable way
 * to tell *why* an installation was refused, and on many devices that message lives in a dialog the user
 * cannot copy from. Keeping it here means the clone details screen can show it and copy it out.
 */
object InstallLog {

    private const val PREFS = "install_log"
    private const val FIELD_SEPARATOR = "\u0001"
    private const val LIST_SEPARATOR = "\u0002"
    private const val LAST_ATTEMPT_KEY = "last_attempt"

    data class Entry(
        val cloneName: String,
        val packageName: String,
        val success: Boolean,
        val status: Int,
        val message: String,
        val expectedSplits: List<String>,
        val installedSplits: List<String>,
        val timestamp: Long
    ) {
        /** Multi line, copy friendly summary of the attempt. */
        fun describe(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
            return buildString {
                append("Clone: ").append(cloneName).append('\n')
                append("Package: ").append(packageName).append('\n')
                append("Result: ").append(if (success) "SUCCESS" else "FAILED").append(" (status ").append(status).append(")\n")
                append("Installer message: ").append(message.ifBlank { "-" }).append('\n')
                append("Expected splits (").append(expectedSplits.size).append("): ")
                append(expectedSplits.joinToString(", ").ifBlank { "none" }).append('\n')
                append("Installed splits (").append(installedSplits.size).append("): ")
                append(installedSplits.joinToString(", ").ifBlank { "none" }).append('\n')
                append("Time: ").append(time)
            }
        }
    }

    fun record(
        context: Context,
        cloneName: String,
        packageName: String,
        success: Boolean,
        status: Int,
        message: String?,
        expectedSplits: List<String>,
        installedSplits: List<String>
    ) {
        val entry = Entry(
            cloneName = cloneName,
            packageName = packageName,
            success = success,
            status = status,
            message = message.orEmpty(),
            expectedSplits = expectedSplits,
            installedSplits = installedSplits,
            timestamp = System.currentTimeMillis()
        )
        val encoded = encode(entry)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(keyFor(packageName), encoded)
            .putString(LAST_ATTEMPT_KEY, encoded)
            .apply()
    }

    /** Last attempt for [packageName], or the last attempt of any clone when there is none for that name. */
    fun read(context: Context, packageName: String): Entry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(keyFor(packageName), null) ?: prefs.getString(LAST_ATTEMPT_KEY, null)
        return encoded?.let { decode(it) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun keyFor(packageName: String) = "clone:$packageName"

    private fun encode(entry: Entry): String = listOf(
        entry.cloneName,
        entry.packageName,
        entry.success.toString(),
        entry.status.toString(),
        entry.message.replace(FIELD_SEPARATOR, " ").replace('\n', ' '),
        entry.expectedSplits.joinToString(LIST_SEPARATOR),
        entry.installedSplits.joinToString(LIST_SEPARATOR),
        entry.timestamp.toString()
    ).joinToString(FIELD_SEPARATOR)

    private fun decode(encoded: String): Entry? {
        val parts = encoded.split(FIELD_SEPARATOR)
        if (parts.size < 8) return null
        fun list(value: String): List<String> =
            value.split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        return runCatching {
            Entry(
                cloneName = parts[0],
                packageName = parts[1],
                success = parts[2].toBoolean(),
                status = parts[3].toInt(),
                message = parts[4],
                expectedSplits = list(parts[5]),
                installedSplits = list(parts[6]),
                timestamp = parts[7].toLong()
            )
        }.getOrNull()
    }
}
