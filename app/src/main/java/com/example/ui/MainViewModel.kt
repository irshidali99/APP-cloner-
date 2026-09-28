package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppClonerApplication
import com.example.model.CloneConfig
import com.example.model.CloneRecord
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.model.PipelineProgress
import com.example.model.PipelineStage
import com.example.model.SettingsData
import com.example.model.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class AppFilter {
    ALL,
    CLONEABLE,
    CLONED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AppClonerApplication
    private val cloneRepository = app.cloneRepository
    private val preferencesRepository = app.preferencesRepository
    private val packageInspector = app.packageInspector
    private val cloneApkBuilder = app.cloneApkBuilder
    val installerManager = app.installerManager

    /** "v1+v2" style description of what the generated APK actually carries. */
    private fun signatureSchemeOf(outcome: com.example.engine.CloneOutcome): String =
        outcome.signature.schemes.joinToString("+")

    private val _rawInstalledApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val rawInstalledApps = _rawInstalledApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps = _isLoadingApps.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val selectedFilter = MutableStateFlow(AppFilter.ALL)

    val clonesList: StateFlow<List<CloneRecord>> = cloneRepository.allClones.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val settings: StateFlow<SettingsData> = preferencesRepository.settingsData.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsData()
    )

    // Filtered installed apps
    val filteredInstalledApps: StateFlow<List<InstalledApp>> = combine(
        _rawInstalledApps,
        searchQuery,
        selectedFilter,
        clonesList
    ) { apps, query, filter, clones ->
        val clonedSourcePackages = clones.map { it.sourcePackage }.toSet()
        val queryTrimmed = query.trim().lowercase()

        apps.map { app ->
            val count = clones.count { it.sourcePackage == app.packageName }
            app.copy(clonedCount = count)
        }.filter { app ->
            val matchesQuery = queryTrimmed.isEmpty() ||
                    app.label.lowercase().contains(queryTrimmed) ||
                    app.packageName.lowercase().contains(queryTrimmed)

            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.CLONEABLE -> app.isCloneable
                AppFilter.CLONED -> clonedSourcePackages.contains(app.packageName)
            }

            matchesQuery && matchesFilter
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Current app selected for clone setup
    val selectedAppForSetup = MutableStateFlow<InstalledApp?>(null)
    val compatibilityReport = MutableStateFlow<CompatibilityReport?>(null)

    // Clone configuration form state
    val cloneDisplayName = MutableStateFlow("")
    val clonePackageId = MutableStateFlow("")
    val badgeNumber = MutableStateFlow<Int?>(1)
    val badgeColor = MutableStateFlow(0xFF4F46E5L)
    val rotationDegrees = MutableStateFlow(0f)
    val invertColors = MutableStateFlow(false)

    // Cloning execution state
    private var cloningJob: Job? = null
    private val _cloningProgress = MutableStateFlow(PipelineProgress())
    val cloningProgress = _cloningProgress.asStateFlow()

    private val _lastGeneratedCloneRecord = MutableStateFlow<CloneRecord?>(null)
    val lastGeneratedCloneRecord = _lastGeneratedCloneRecord.asStateFlow()

    // Selected clone for detail sheet/screen
    val selectedCloneDetail = MutableStateFlow<CloneRecord?>(null)

    // Storage usage
    private val _storageUsed = MutableStateFlow(0L)
    val storageUsed = _storageUsed.asStateFlow()

    init {
        loadInstalledApps()
        refreshStorageUsage()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                cloneRepository.reconcileWithPackageManager()
                val currentClones = cloneRepository.allClones.first()
                val clonedPkgIds = currentClones.map { it.clonePackageId }.toSet()
                val apps = packageInspector.getInstalledApps(clonedPkgIds)
                _rawInstalledApps.value = apps
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun prepareCloneSetup(installedApp: InstalledApp) {
        selectedAppForSetup.value = installedApp
        compatibilityReport.value = cloneApkBuilder.checkCompatibility(installedApp)

        viewModelScope.launch {
            val clonesForApp = cloneRepository.getClonesForSource(installedApp.packageName).first()
            val nextCloneIndex = clonesForApp.size + 1
            val currentSettings = settings.value

            val defaultName = CloneConfig.generateDefaultCloneName(
                installedApp.label,
                nextCloneIndex,
                currentSettings.autoNumber
            )
            val defaultPkg = CloneConfig.generateDefaultPackageId(
                installedApp.packageName,
                nextCloneIndex
            )

            cloneDisplayName.value = defaultName
            clonePackageId.value = defaultPkg
            badgeNumber.value = nextCloneIndex
            badgeColor.value = 0xFF4F46E5L
            rotationDegrees.value = 0f
            invertColors.value = false
        }
    }

    fun startCloning(onStarted: () -> Unit) {
        val app = selectedAppForSetup.value ?: return
        val config = CloneConfig(
            sourcePackage = app.packageName,
            sourceAppName = app.label,
            cloneName = cloneDisplayName.value.trim(),
            clonePackageId = clonePackageId.value.trim(),
            badgeNumber = badgeNumber.value,
            badgeColor = badgeColor.value,
            rotationDegrees = rotationDegrees.value,
            invertColors = invertColors.value
        )

        val validation = config.validate()
        if (!validation.isValid) {
            _cloningProgress.value = PipelineProgress(
                stage = PipelineStage.FAILED,
                progressFraction = 0f,
                detailMessage = validation.error ?: "Validation error",
                isFailed = true,
                errorMessage = validation.error
            )
            onStarted()
            return
        }

        onStarted()
        cloningJob?.cancel()
        cloningJob = viewModelScope.launch {
            _cloningProgress.value = PipelineProgress(
                stage = PipelineStage.INSPECT_SOURCE,
                progressFraction = 0.05f,
                detailMessage = "Starting cloning pipeline for ${config.cloneName}..."
            )

            val result = cloneApkBuilder.executeClonePipeline(
                context = getApplication(),
                sourceApp = app,
                config = config
            ) { progress ->
                _cloningProgress.value = progress
            }

            result.onSuccess { outcome ->
                val apkFile = outcome.apkFile
                val newRecord = CloneRecord(
                    cloneName = config.cloneName,
                    clonePackageId = config.clonePackageId,
                    sourceAppName = app.label,
                    sourcePackage = app.packageName,
                    sourceVersion = app.versionName,
                    apkFilePath = apkFile.absolutePath,
                    apkSizeBytes = apkFile.length(),
                    installStatus = CloneRecord.STATUS_APK_READY,
                    badgeNumber = config.badgeNumber,
                    badgeColor = config.badgeColor,
                    rotationDegrees = config.rotationDegrees,
                    invertColors = config.invertColors,
                    isVerified = outcome.isVerified,
                    signatureScheme = signatureSchemeOf(outcome),
                    certificateFingerprint = outcome.certificateFingerprint
                )
                val id = cloneRepository.saveClone(newRecord)
                val savedRecord = newRecord.copy(id = id)
                _lastGeneratedCloneRecord.value = savedRecord
                refreshStorageUsage()
            }.onFailure {
                // The pipeline already reported the failure through the progress state.
            }
        }
    }

    fun cancelCloning() {
        cloningJob?.cancel()
        _cloningProgress.value = PipelineProgress(
            stage = PipelineStage.FAILED,
            progressFraction = 0f,
            detailMessage = "Operation cancelled by user.",
            isFailed = true,
            errorMessage = "Cloning cancelled by user."
        )
    }

    fun deleteClone(record: CloneRecord, deletePhysicalApk: Boolean = true) {
        viewModelScope.launch {
            cloneRepository.deleteClone(record, deletePhysicalApk)
            refreshStorageUsage()
        }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch {
            _storageUsed.value = cloneRepository.getStorageUsedByClones()
        }
    }

    fun cleanTempFiles(onCleaned: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = cloneRepository.cleanTemporaryFiles()
            refreshStorageUsage()
            onCleaned(freed)
        }
    }

    fun updateTheme(themeMode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(themeMode)
        }
    }

    fun updateDefaultSuffix(suffix: String) {
        viewModelScope.launch {
            preferencesRepository.setDefaultSuffix(suffix)
        }
    }

    fun updateAutoNumber(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoNumber(enabled)
        }
    }

    fun updateConfirmDelete(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setConfirmDelete(enabled)
        }
    }

    fun onResume() {
        viewModelScope.launch {
            cloneRepository.reconcileWithPackageManager()
            refreshStorageUsage()
        }
    }
}
