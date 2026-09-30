package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.CloneRepository
import com.example.data.PreferencesRepository
import com.example.engine.CloneApkBuilder
import com.example.engine.CloneKeystore
import com.example.engine.PackageInspector
import com.example.installer.PackageInstallerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppClonerApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this) }
    val cloneRepository by lazy { CloneRepository(this, database.cloneRecordDao(), database.clonePresetDao()) }
    val preferencesRepository by lazy { PreferencesRepository(this) }
    val packageInspector by lazy { PackageInspector(this) }
    val cloneKeystore by lazy { CloneKeystore(this) }
    val cloneApkBuilder by lazy { CloneApkBuilder(packageInspector, cloneKeystore) }
    val installerManager by lazy { PackageInstallerManager(this) }

    override fun onCreate() {
        super.onCreate()
        // Reconcile saved clone records with actual device package installations on startup
        applicationScope.launch {
            try {
                cloneRepository.reconcileWithPackageManager()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
