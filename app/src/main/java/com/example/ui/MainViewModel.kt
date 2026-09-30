package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppClonerApplication
import com.example.model.BatchItem
import com.example.model.BatchProgress
import com.example.model.BatchState
import com.example.model.CloneConfig
import com.example.model.CloneMods
import com.example.model.ClonePreset
import com.example.model.CloneRecord
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.model.PipelineProgress
import com.example.model.PipelineStage
import com.example.model.SettingsData
import com.example.model.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    // ------------------------------------------------------------------ expansion files (games) --------

    /** True when the clone has expansion files that still have to be placed for its package name. */
    fun needsObbCopy(record: CloneRecord): Boolean =
        record.obbFiles.isNotBlank() && com.example.engine.ObbSupport.obbFiles(record.clonePackageId).isEmpty()

    /** True when this app may read and write the shared obb directory. */
    fun canAccessObb(): Boolean = com.example.engine.ObbSupport.canAccessObb(getApplication())

    /** Settings screen where "All files access" is granted. */
    fun obbPermissionIntent(): Intent = com.example.engine.ObbSupport.manageAccessIntent(getApplication())

    /** Copies the expansion files of the original app to the clone's package directory. */
    fun copyObb(record: CloneRecord, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                com.example.engine.ObbSupport.copyObbToClone(record.sourcePackage, record.clonePackageId)
            }
            onResult(copied)
        }
    }

    /** Splits Android reports for an installed clone (empty for single APK clones or when not installed). */
    fun installedSplitNames(packageId: String): List<String> =
        packageInspector.installedSplitNames(packageId)

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

    /** Clone mods selected on the setup screen; applied to the manifest while the clone is built. */
    val cloneMods = MutableStateFlow(CloneMods())

    fun updateMods(transform: (CloneMods) -> CloneMods) {
        cloneMods.value = transform(cloneMods.value)
    }

    fun togglePermissionGroup(group: String) {
        updateMods { mods ->
            val groups = mods.removePermissionGroups.toMutableSet()
            if (!groups.add(group)) groups.remove(group)
            mods.copy(removePermissionGroups = groups)
        }
    }

    /** Saved clone setups (presets). */
    val presets: StateFlow<List<ClonePreset>> = cloneRepository.allPresets.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Name pattern used when a preset is saved, and the index of the clone currently being set up. */
    private var setupCloneIndex: Int = 1
    private var setupSourceLabel: String = ""

    // Batch cloning state
    private val _batchSelection = MutableStateFlow<Set<String>>(emptySet())
    val batchSelection = _batchSelection.asStateFlow()

    private val _batchProgress = MutableStateFlow(BatchProgress())
    val batchProgress = _batchProgress.asStateFlow()

    private var batchJob: Job? = null

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
            // Take both the saved history and what is really installed into account, otherwise a deleted
            // record would hand out a package name that is still occupied by an installed clone.
            val usedIndexes = (
                clonesForApp.mapNotNull { record ->
                    CloneConfig.cloneIndexSuffix(record.clonePackageId, installedApp.packageName)
                } + packageInspector.installedCloneIndexes(installedApp.packageName)
                ).toSet()
            // Verify the candidate name with the package manager as well: an installed clone whose record was
            // deleted must never be handed out as a "new" package name again.
            val nextCloneIndex = CloneConfig.nextFreeCloneIndex(
                usedIndexes = usedIndexes,
                isInstalled = { candidate -> packageInspector.isPackageInstalled(candidate) },
                sourcePackage = installedApp.packageName
            )
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

            setupCloneIndex = nextCloneIndex
            setupSourceLabel = installedApp.label
            activePresetName.value = null
            cloneDisplayName.value = defaultName
            clonePackageId.value = defaultPkg
            badgeNumber.value = nextCloneIndex
            badgeColor.value = 0xFF4F46E5L
            rotationDegrees.value = 0f
            invertColors.value = false
            cloneMods.value = CloneMods()
        }
    }

    // ------------------------------------------------------------------ presets --------

    /**
     * Applies a preset to the app that is currently being set up.
     *
     * The clone number is resolved for this app first, so the preset's `{app}` / `{n}` placeholders produce
     * a name that really is free on the device.
     */
    fun applyPreset(preset: ClonePreset) {
        val app = selectedAppForSetup.value ?: return
        viewModelScope.launch {
            val index = freeCloneIndexFor(app)
            cloneDisplayName.value = preset.cloneName(app.label, index)
            clonePackageId.value = CloneConfig.generateDefaultPackageId(app.packageName, index)
            badgeNumber.value = index
            badgeColor.value = preset.badgeColor
            rotationDegrees.value = if (preset.rotateIcon) ((index - 1) % 8) * 12f else 0f
            invertColors.value = preset.invertColors
            cloneMods.value = preset.toMods(
                versionName = if (preset.versionNameSuffix.isBlank()) {
                    null
                } else {
                    app.versionName + preset.versionNameSuffix
                }
            )
            activePresetName.value = preset.name
        }
    }

    /** Name of the preset that was applied last, shown on the setup screen. */
    val activePresetName = MutableStateFlow<String?>(null)

    /** Saves the settings currently shown on the setup screen as a reusable preset. */
    fun saveCurrentSettingsAsPreset(name: String) {
        val app = selectedAppForSetup.value
        val cleanName = name.trim().ifEmpty { "Preset ${presets.value.size + 1}" }
        val pattern = ClonePreset.derivePattern(
            cloneName = cloneDisplayName.value,
            sourceLabel = app?.label ?: setupSourceLabel,
            cloneIndex = setupCloneIndex
        )
        val preset = ClonePreset(name = cleanName).withSettings(
            namePattern = pattern,
            badgeColor = badgeColor.value,
            rotateIcon = rotationDegrees.value != 0f,
            invertColors = invertColors.value,
            mods = cloneMods.value,
            versionNameSuffix = ""
        )
        viewModelScope.launch { cloneRepository.savePreset(preset) }
        activePresetName.value = cleanName
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch { cloneRepository.deletePreset(id) }
    }

    /** The next free clone number for [app], taking installed packages and the history into account. */
    private suspend fun freeCloneIndexFor(app: InstalledApp): Int {
        val records = cloneRepository.getClonesForSource(app.packageName).first()
        val used = (
            records.mapNotNull { CloneConfig.cloneIndexSuffix(it.clonePackageId, app.packageName) } +
                packageInspector.installedCloneIndexes(app.packageName)
            ).toSet()
        return CloneConfig.nextFreeCloneIndex(
            usedIndexes = used,
            isInstalled = { candidate -> packageInspector.isPackageInstalled(candidate) },
            sourcePackage = app.packageName
        )
    }

    // ------------------------------------------------------------------ batch cloning --------

    fun toggleBatchSelection(packageName: String) {
        val selection = _batchSelection.value.toMutableSet()
        if (!selection.add(packageName)) selection.remove(packageName)
        _batchSelection.value = selection
    }

    fun setBatchSelection(packageNames: Set<String>) {
        _batchSelection.value = packageNames
    }

    /**
     * Clones every selected app one after another with the settings that are currently configured.
     *
     * Cloning is the slow part and Android asks for confirmation for every installation, so a batch first
     * builds all clones and then hands them to the installer in a queue (see [InstallGatewayActivity]).
     */
    fun startBatch(apps: List<InstalledApp>) {
        if (apps.isEmpty() || batchJob?.isActive == true) return
        val mods = cloneMods.value
        val colorValue = badgeColor.value
        val rotation = rotationDegrees.value
        val invert = invertColors.value
        val autoNumber = settings.value.autoNumber

        _batchProgress.value = BatchProgress(
            items = apps.map { BatchItem(packageName = it.packageName, label = it.label) },
            isRunning = true
        )

        batchJob = viewModelScope.launch {
            for (app in apps) {
                if (!isActive) break
                updateBatchItem(app.packageName) { item -> item.copy(state = BatchState.CLONING) }
                _batchProgress.value = _batchProgress.value.copy(currentLabel = app.label)

                val compatibility = cloneApkBuilder.checkCompatibility(app)
                if (!compatibility.isSupported) {
                    updateBatchItem(app.packageName) { item ->
                        item.copy(state = BatchState.FAILED, message = compatibility.formatDescription)
                    }
                    continue
                }

                val index = freeCloneIndexFor(app)
                val cloneName = CloneConfig.generateDefaultCloneName(app.label, index, autoNumber)
                val presetPattern = presets.value.firstOrNull { it.name == activePresetName.value }
                    ?.let { preset -> preset.cloneName(app.label, index) }
                val finalName = presetPattern ?: cloneName
                val config = CloneConfig(
                    sourcePackage = app.packageName,
                    sourceAppName = app.label,
                    cloneName = finalName,
                    clonePackageId = CloneConfig.generateDefaultPackageId(app.packageName, index),
                    badgeNumber = index,
                    badgeColor = colorValue,
                    rotationDegrees = rotation,
                    invertColors = invert,
                    mods = mods
                )

                val result = cloneApkBuilder.executeClonePipeline(
                    context = getApplication(),
                    sourceApp = app,
                    config = config
                ) { }
                result.onSuccess { outcome ->
                    val record = CloneRecord(
                        cloneName = config.cloneName,
                        clonePackageId = config.clonePackageId,
                        sourceAppName = app.label,
                        sourcePackage = app.packageName,
                        sourceVersion = app.versionName,
                        apkFilePath = outcome.apkFile.absolutePath,
                        apkSizeBytes = outcome.apkFile.length(),
                        installStatus = CloneRecord.STATUS_APK_READY,
                        badgeNumber = config.badgeNumber,
                        badgeColor = config.badgeColor,
                        rotationDegrees = config.rotationDegrees,
                        invertColors = config.invertColors,
                        isVerified = outcome.isVerified,
                        signatureScheme = signatureSchemeOf(outcome),
                        certificateFingerprint = outcome.certificateFingerprint,
                        splitNames = outcome.splitNames.joinToString(", "),
                        modsSummary = outcome.report.appliedMods.joinToString(", "),
                        obbFiles = outcome.obbFiles.joinToString(", ") { it.name }
                    )
                    cloneRepository.saveClone(record)
                    updateBatchItem(app.packageName) { item ->
                        item.copy(
                            state = BatchState.READY,
                            cloneName = config.cloneName,
                            clonePackageId = config.clonePackageId,
                            apkPath = outcome.apkFile.absolutePath,
                            message = if (outcome.isBundle) {
                                "bundle: base + ${outcome.bundleParts.size - 1} split(s)"
                            } else {
                                "single APK"
                            }
                        )
                    }
                }.onFailure { error ->
                    updateBatchItem(app.packageName) { item ->
                        item.copy(
                            state = BatchState.FAILED,
                            message = error.message ?: error::class.java.simpleName
                        )
                    }
                }
            }
            _batchProgress.value = _batchProgress.value.copy(isRunning = false, currentLabel = "")
            refreshStorageUsage()
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
        val items = _batchProgress.value.items.map { item ->
            if (item.isFinished) item else item.copy(state = BatchState.SKIPPED, message = "cancelled")
        }
        _batchProgress.value = _batchProgress.value.copy(items = items, isRunning = false, currentLabel = "")
    }

    fun clearBatch() {
        _batchProgress.value = BatchProgress()
    }

    private fun updateBatchItem(packageName: String, transform: (BatchItem) -> BatchItem) {
        val items = _batchProgress.value.items.map { item ->
            if (item.packageName == packageName) transform(item) else item
        }
        _batchProgress.value = _batchProgress.value.copy(items = items)
    }

    /**
     * True when an installed clone has no launcher entry of its own. A clone with a hidden launcher icon
     * can only be started from this app, so offering a pinned home screen shortcut keeps it reachable.
     */
    fun cloneNeedsShortcut(record: CloneRecord): Boolean {
        if (!record.isInstalled) return false
        if (!record.modsSummary.contains("launcher icon hidden")) return false
        val manager = getApplication<Application>().packageManager
        val launcher = runCatching { manager.getLaunchIntentForPackage(record.clonePackageId) }.getOrNull()
        return launcher == null
    }

    /** Asks the launcher to pin a shortcut for an installed clone. */
    fun pinCloneShortcut(record: CloneRecord): Boolean = runCatching {
        com.example.installer.CloneShortcut.requestPin(
            getApplication(),
            record.clonePackageId,
            record.cloneName
        )
    }.getOrDefault(false)

    /** Queue of clones to install one after another. */
    fun installQueueOfReadyClones(): List<com.example.installer.InstallQueueEntry> =
        _batchProgress.value.installable.map { item ->
            com.example.installer.InstallQueueEntry(
                cloneName = item.cloneName,
                apkPath = item.apkPath,
                sourcePackage = item.packageName
            )
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
            invertColors = invertColors.value,
            mods = cloneMods.value
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
                    certificateFingerprint = outcome.certificateFingerprint,
                    splitNames = outcome.splitNames.joinToString(", "),
                    modsSummary = outcome.report.appliedMods.joinToString(", "),
                    obbFiles = outcome.obbFiles.joinToString(", ") { it.name }
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

    fun updateMaxClones(max: Int) {
        viewModelScope.launch {
            preferencesRepository.setMaxClones(max)
        }
    }

    /** True when the configured clone limit is reached, so no new clone should be started. */
    val cloneLimitReached: StateFlow<Boolean> = combine(clonesList, settings) { clones, current ->
        current.maxClones > 0 && clones.size >= current.maxClones
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    fun onResume() {
        viewModelScope.launch {
            cloneRepository.reconcileWithPackageManager()
            refreshStorageUsage()
        }
    }
}
