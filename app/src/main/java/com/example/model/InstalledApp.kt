package com.example.model

import android.graphics.Bitmap

/**
 * Represents an application installed on the user's device.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val sourceDir: String,
    val splitSourceDirs: List<String> = emptyList(),
    val isSplitApk: Boolean = splitSourceDirs.isNotEmpty(),
    val targetSdkVersion: Int = 0,
    val apkSizeBytes: Long = 0L,
    val isCloneable: Boolean = !isSystemApp && splitSourceDirs.isEmpty(),
    val compatibilityReason: String = if (isSystemApp) {
        "System application (Protected)"
    } else if (splitSourceDirs.isNotEmpty()) {
        "Split APK format (App Bundle / Dynamic Features not supported for arbitrary single-file repackaging)"
    } else {
        "Standalone APK (Supported)"
    },
    val clonedCount: Int = 0,
    val iconBitmap: Bitmap? = null
)
