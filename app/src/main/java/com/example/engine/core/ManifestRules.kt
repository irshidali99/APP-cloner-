package com.example.engine.core

/**
 * Pure string rules used while re-targeting a manifest to a new package name.
 *
 * Kept free of any XML/byte handling so every rule is directly unit testable.
 */
object ManifestRules {

    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /** Returns `value` re-targeted from [from] to [to], or `null` when it does not belong to [from]. */
    fun rewritePackagePrefix(value: String, from: String, to: String): String? = when {
        value.isEmpty() -> null
        value == from -> to
        value.startsWith("$from.") -> to + value.substring(from.length)
        else -> null
    }

    /**
     * Re-targets a `;` separated authority list (`com.example.app.fileprovider`).
     * Elements that do not belong to [from] are left untouched.
     */
    fun rewriteAuthorities(value: String, from: String, to: String): String =
        value.split(';').joinToString(";") { part ->
            val trimmed = part.trim()
            rewritePackagePrefix(trimmed, from, to) ?: part
        }

    /**
     * Expands a component class name so that it keeps pointing at the *original* class after the package
     * attribute changed.
     *
     * Android resolves component names relative to the manifest `package`:
     *  - `".MainActivity"` -> `com.original.MainActivity`
     *  - `"MainActivity"` (no dot) -> `com.original.MainActivity`
     *  - `"com.original.MainActivity"` -> already absolute, returned unchanged
     */
    fun qualifyClassName(value: String, originalPackage: String): String = when {
        value.isEmpty() -> value
        value.startsWith(".") -> originalPackage + value
        !value.contains('.') -> "$originalPackage.$value"
        else -> value
    }

    /** True when [value] is a class name that has to be expanded before the package is renamed. */
    fun needsQualification(value: String): Boolean =
        value.startsWith(".") || (value.isNotEmpty() && !value.contains('.'))

    /** Elements whose `android:name` attribute holds a Java class name. */
    val CLASS_NAME_ELEMENTS = setOf(
        "application",
        "activity",
        "activity-alias",
        "service",
        "receiver",
        "provider",
        "instrumentation"
    )

    /** Elements that declare a permission owned by this application. */
    val DECLARED_PERMISSION_ELEMENTS = setOf(
        "permission",
        "permission-group",
        "permission-tree"
    )

    /** Elements that consume a permission. */
    val CONSUMED_PERMISSION_ELEMENTS = setOf(
        "uses-permission",
        "uses-permission-sdk-23"
    )

    /** Attributes holding a Java class name. */
    val CLASS_NAME_ATTRIBUTES = setOf(
        "name", // on the elements listed in CLASS_NAME_ELEMENTS
        "targetActivity",
        "backupAgent",
        "manageSpaceActivity",
        "appComponentFactory"
    )

    /** Attributes whose value may be derived from the application package. */
    val PACKAGE_PREFIX_ATTRIBUTES = setOf(
        "taskAffinity",
        "process",
        "sharedUserId"
    )
}
