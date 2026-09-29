package com.example.data

import android.content.Context
import android.content.pm.PackageManager
import com.example.model.CloneRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class CloneRepository(
    private val context: Context,
    private val cloneRecordDao: CloneRecordDao
) {
    val allClones: Flow<List<CloneRecord>> = cloneRecordDao.getAllClones()
    val cloneCount: Flow<Int> = cloneRecordDao.getCloneCount()

    fun getCloneById(id: Long): Flow<CloneRecord?> = cloneRecordDao.getCloneById(id)

    fun getClonesForSource(sourcePackage: String): Flow<List<CloneRecord>> =
        cloneRecordDao.getClonesForSource(sourcePackage)

    /** Finds the record of a clone by the package id it installs as. */
    suspend fun findByPackageId(packageId: String): CloneRecord? = withContext(Dispatchers.IO) {
        cloneRecordDao.getCloneByPackageId(packageId)
    }

    suspend fun saveClone(record: CloneRecord): Long = withContext(Dispatchers.IO) {
        cloneRecordDao.insertClone(record)
    }

    suspend fun updateClone(record: CloneRecord) = withContext(Dispatchers.IO) {
        cloneRecordDao.updateClone(record)
    }

    suspend fun deleteClone(record: CloneRecord, deletePhysicalApk: Boolean = true) =
        withContext(Dispatchers.IO) {
            if (deletePhysicalApk && record.apkFilePath.isNotBlank()) {
                val file = File(record.apkFilePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            cloneRecordDao.deleteClone(record)
        }

    suspend fun deleteById(id: Long, deletePhysicalApk: Boolean = true) =
        withContext(Dispatchers.IO) {
            val record = cloneRecordDao.getCloneById(id).first()
            if (record != null) {
                deleteClone(record, deletePhysicalApk)
            } else {
                cloneRecordDao.deleteById(id)
            }
        }

    /**
     * Reconciles all stored clone records against actual Android PackageManager status.
     * If an app is detected as installed on device, status is set to INSTALLED.
     * If uninstalled, checks if APK file still exists to set APK_READY or UNINSTALLED.
     */
    suspend fun reconcileWithPackageManager() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val records = cloneRecordDao.getAllClones().first()
        for (record in records) {
            val isPkgInstalled = try {
                pm.getPackageInfo(record.clonePackageId, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }

            val newStatus = if (isPkgInstalled) {
                CloneRecord.STATUS_INSTALLED
            } else {
                val file = File(record.apkFilePath)
                if (file.exists() && file.length() > 0) {
                    CloneRecord.STATUS_APK_READY
                } else {
                    CloneRecord.STATUS_UNINSTALLED
                }
            }

            if (newStatus != record.installStatus) {
                cloneRecordDao.updateInstallStatus(record.clonePackageId, newStatus)
            }
        }
    }

    suspend fun getStorageUsedByClones(): Long = withContext(Dispatchers.IO) {
        val clonesDir = File(context.filesDir, "clones")
        if (!clonesDir.exists()) return@withContext 0L
        clonesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun cleanTemporaryFiles(): Long = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "clones")
        var freed = 0L
        if (cacheDir.exists()) {
            cacheDir.walkTopDown().forEach {
                if (it.isFile) {
                    freed += it.length()
                    it.delete()
                }
            }
        }
        freed
    }
}
