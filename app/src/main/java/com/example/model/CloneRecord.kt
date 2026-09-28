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
    val invertColors: Boolean = false
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
