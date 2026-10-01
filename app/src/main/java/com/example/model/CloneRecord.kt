package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local record of a created clone saved in the Room database.
 */
@Entity(tableName = "clone_records")
data class CloneRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cloneName: String,
    val clonePackageId: String,
    val sourceAppName: String,
    val sourcePackage: String,
    val sourceVersion: String,
    val apkFilePath: String,
    val apkSizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val installStatus: String = STATUS_APK_READY,
    val badgeNumber: Int? = null,
    val badgeColor: Long = 0xFF4F46E5L,
    val rotationDegrees: Float = 0f,
    val invertColors: Boolean = false,
    /** True when the generated APK's signature and package identity were verified after signing. */
    val isVerified: Boolean = false,
    /** Signature schemes found in the generated APK, e.g. "v1+v2". */
    val signatureScheme: String = "",
    /** SHA-256 fingerprint of the certificate that signed this clone. */
    val certificateFingerprint: String = "",
    /** Split names of a bundle clone (empty for single APK clones). */
    val splitNames: String = "",
    /** Human readable list of the clone mods that were applied to this clone. */
    val modsSummary: String = "",
    /** Expansion (.obb) files of the original app, comma separated (empty when there are none). */
    val obbFiles: String = "",
    /** Runtime features (phase 3) injected into this clone, in human readable form. */
    val runtimeSummary: String = "",
    /** Reset code that unlocks this clone when its passcode is forgotten (empty when it has no lock). */
    val runtimeResetCode: String = ""
) {
    val isInstalled: Boolean
        get() = installStatus == STATUS_INSTALLED

    companion object {
        const val STATUS_INSTALLED = "INSTALLED"
        const val STATUS_APK_READY = "APK_READY"
        const val STATUS_UNINSTALLED = "UNINSTALLED"
        const val STATUS_FAILED = "FAILED"
    }
}
