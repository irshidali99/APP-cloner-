package com.example

import com.example.model.BatchItem
import com.example.model.BatchProgress
import com.example.model.BatchState
import com.example.model.CloneConfig
import com.example.model.CloneMods
import com.example.model.ClonePreset
import com.example.model.RuntimeOptions
import com.example.model.CloneRecord
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.model.KnownIssues
import com.example.model.LockMode
import com.example.model.PipelineProgress
import com.example.model.PipelineStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppClonerLogicTest {

    @Test
    fun testValidCloneConfigValidation() {
        val validConfig = CloneConfig(
            sourcePackage = "com.sample.calculator",
            sourceAppName = "Calculator",
            cloneName = "Calculator 2",
            clonePackageId = "com.sample.calculator.clone1"
        )
        val result = validConfig.validate()
        assertTrue(result.isValid)
        assertEquals(null, result.error)
    }

    @Test
    fun testEmptyNameValidationFails() {
        val invalidConfig = CloneConfig(
            sourcePackage = "com.sample.calculator",
            sourceAppName = "Calculator",
            cloneName = "   ",
            clonePackageId = "com.sample.calculator.clone1"
        )
        val result = invalidConfig.validate()
        assertFalse(result.isValid)
        assertNotNull(result.error)
    }

    @Test
    fun testSamePackageIdValidationFails() {
        val invalidConfig = CloneConfig(
            sourcePackage = "com.sample.calculator",
            sourceAppName = "Calculator",
            cloneName = "Calculator Clone",
            clonePackageId = "com.sample.calculator"
        )
        val result = invalidConfig.validate()
        assertFalse(result.isValid)
        assertTrue(result.error?.contains("cannot be identical") == true)
    }

    @Test
    fun testInvalidPackageIdFormatFails() {
        val invalidConfig = CloneConfig(
            sourcePackage = "com.sample.calculator",
            sourceAppName = "Calculator",
            cloneName = "Calculator Clone",
            clonePackageId = "invalid_single_word"
        )
        val result = invalidConfig.validate()
        assertFalse(result.isValid)
        assertTrue(result.error?.contains("dot-separated") == true)
    }

    @Test
    fun testDefaultNameAndPackageGeneration() {
        val generatedNameAuto = CloneConfig.generateDefaultCloneName("Notes", 2, true)
        assertEquals("Notes (Clone 2)", generatedNameAuto)

        val generatedNameSimple = CloneConfig.generateDefaultCloneName("Notes", 2, false)
        assertEquals("Notes Clone", generatedNameSimple)

        val generatedPkg = CloneConfig.generateDefaultPackageId("org.example.notes", 1)
        assertEquals("org.example.notes.clone1", generatedPkg)
    }

    @Test
    fun testCompatibilityReportForSystemApp() {
        val systemApp = InstalledApp(
            packageName = "com.android.settings",
            label = "Settings",
            versionName = "14",
            versionCode = 34L,
            isSystemApp = true,
            sourceDir = "/system/priv-app/Settings.apk"
        )
        val report = CompatibilityReport.evaluate(systemApp)
        assertFalse(report.isSupported)
        assertTrue(report.systemAppProtected)
        assertFalse(report.splitApkDetected)
    }

    @Test
    fun testCompatibilityReportForSplitApk() {
        val splitApp = InstalledApp(
            packageName = "com.popular.streaming",
            label = "Streaming App",
            versionName = "5.2.1",
            versionCode = 5021L,
            isSystemApp = false,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = listOf("/data/app/split_config.arm64_v8a.apk", "/data/app/split_config.xxhdpi.apk")
        )
        val report = CompatibilityReport.evaluate(splitApp)
        // App bundles are supported: every part is cloned and installed as one bundle.
        assertTrue(report.isSupported)
        assertTrue(report.splitApkDetected)
        assertFalse(report.systemAppProtected)
        assertTrue(report.formatDescription.contains("bundle"))
        assertTrue(report.signatureRestrictionsNote.contains("splits"))
    }

    @Test
    fun testCompatibilityReportForStandaloneApp() {
        val standaloneApp = InstalledApp(
            packageName = "com.simplemobiletools.calendar",
            label = "Simple Calendar",
            versionName = "1.0",
            versionCode = 100L,
            isSystemApp = false,
            sourceDir = "/data/app/calendar.apk",
            splitSourceDirs = emptyList()
        )
        val report = CompatibilityReport.evaluate(standaloneApp)
        assertTrue(report.isSupported)
        assertFalse(report.splitApkDetected)
        assertFalse(report.systemAppProtected)
    }

    @Test
    fun testCloneRecordStatusFlags() {
        val installedRecord = CloneRecord(
            cloneName = "Test Clone",
            clonePackageId = "com.test.clone",
            sourceAppName = "Test",
            sourcePackage = "com.test",
            sourceVersion = "1.0",
            apkFilePath = "/data/user/0/app/files/clones/test.apk",
            apkSizeBytes = 2048L,
            installStatus = CloneRecord.STATUS_INSTALLED
        )
        assertTrue(installedRecord.isInstalled)

        val readyRecord = installedRecord.copy(installStatus = CloneRecord.STATUS_APK_READY)
        assertFalse(readyRecord.isInstalled)
    }

    @Test
    fun testPipelineStageProgression() {
        var progress = PipelineProgress(stage = PipelineStage.INSPECT_SOURCE, progressFraction = 0.2f)
        assertEquals(PipelineStage.INSPECT_SOURCE, progress.stage)
        assertFalse(progress.isComplete)
        assertFalse(progress.isFailed)

        progress = progress.copy(
            stage = PipelineStage.COMPLETED,
            progressFraction = 1.0f,
            isComplete = true
        )
        assertTrue(progress.isComplete)
        assertEquals(1.0f, progress.progressFraction, 0.001f)
    }

    @Test
    fun testWhatsAppIsReportedAsSelfVerifying() {
        val whatsApp = InstalledApp(
            packageName = "com.whatsapp",
            label = "WhatsApp",
            versionName = "2.24.1",
            versionCode = 1L,
            isSystemApp = false,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = listOf("/data/app/split_config.arm64_v8a.apk"),
            isSplitApk = true,
            targetSdkVersion = 34,
            apkSizeBytes = 1024L,
            isCloneable = true,
            clonedCount = 0
        )
        val report = CompatibilityReport.evaluate(whatsApp)
        assertTrue(report.selfVerifyingApp)
        assertNotNull(report.alternativeRecommendation)
        assertTrue(report.alternativeRecommendation!!.contains("Add account"))

        val ordinary = whatsApp.copy(packageName = "org.fdroid.fdroid", label = "F-Droid")
        assertFalse(CompatibilityReport.evaluate(ordinary).selfVerifyingApp)
        assertFalse(CompatibilityReport.isSelfVerifying("org.fdroid.fdroid"))
        assertTrue(CompatibilityReport.isSelfVerifying("com.whatsapp.w4b"))
    }

    @Test
    fun testCloneIndexSuffixOnlyMatchesClonesOfTheSource() {
        assertEquals(1, CloneConfig.cloneIndexSuffix("com.app.demo.clone1", "com.app.demo"))
        assertEquals(12, CloneConfig.cloneIndexSuffix("com.app.demo.clone12", "com.app.demo"))
        assertNull(CloneConfig.cloneIndexSuffix("com.app.demo", "com.app.demo"))
        assertNull(CloneConfig.cloneIndexSuffix("com.app.demo.clone", "com.app.demo"))
        assertNull(CloneConfig.cloneIndexSuffix("com.app.other.clone1", "com.app.demo"))
    }

    @Test
    fun testNextFreeCloneIndexSkipsEveryTakenName() {
        // History knows about clone 1, an installed package that has no record occupies clone 2.
        val installed = setOf("com.app.demo.clone2")
        val index = CloneConfig.nextFreeCloneIndex(
            usedIndexes = setOf(1),
            isInstalled = { candidate -> candidate in installed },
            sourcePackage = "com.app.demo"
        )
        assertEquals(3, index)
        assertEquals("com.app.demo.clone3", CloneConfig.generateDefaultPackageId("com.app.demo", index))
    }

    @Test
    fun testNextFreeCloneIndexChecksTheCandidateEvenWhenNothingIsKnown() {
        // Nothing in the history, but the package manager reports that "clone1" is installed.
        val index = CloneConfig.nextFreeCloneIndex(
            usedIndexes = emptySet(),
            isInstalled = { candidate -> candidate == "com.app.demo.clone1" },
            sourcePackage = "com.app.demo"
        )
        assertEquals(2, index)
    }

    @Test
    fun testNextFreeCloneIndexGivesTheFirstIndexOnAFreshInstall() {
        val index = CloneConfig.nextFreeCloneIndex(
            usedIndexes = emptySet(),
            isInstalled = { false },
            sourcePackage = "com.app.demo"
        )
        assertEquals(1, index)
    }

    @Test
    fun testKnownIssuesDatabaseExplainsProblematicApps() {
        val whatsApp = KnownIssues.describe("com.whatsapp", "WhatsApp")
        assertNotNull(whatsApp)
        assertTrue(whatsApp!!.contains("WhatsApp"))
        assertTrue(whatsApp.contains("signature"))

        val snapchat = KnownIssues.describe("com.snapchat.android", "Snapchat")
        assertNotNull(snapchat)
        assertNull(KnownIssues.describe("org.fdroid.fdroid", "F-Droid"))
        assertTrue(KnownIssues.entryCount > 10)
    }

    @Test
    fun testCompatibilityReportCarriesTheKnownIssue() {
        val viber = InstalledApp(
            packageName = "com.viber.voip",
            label = "Viber",
            versionName = "1.0",
            versionCode = 1L,
            isSystemApp = false,
            sourceDir = "/data/app/base.apk"
        )
        val report = CompatibilityReport.evaluate(viber)
        assertNotNull(report.knownIssue)
        assertTrue(report.knownIssue!!.contains("Viber"))
    }

    // ------------------------------------------------------------------ presets

    @Test
    fun testPresetFillsTheNamePattern() {
        val preset = ClonePreset(name = "Stealth", namePattern = "{app} (Clone {n})", hideLauncherIcon = true)
        assertEquals("WhatsApp (Clone 2)", preset.cloneName("WhatsApp", 2))
        assertEquals("no icon", preset.summary())
    }

    @Test
    fun testPresetPatternWithoutPlaceholdersStaysAsItIs() {
        val preset = ClonePreset(name = "Plain", namePattern = "Work copy")
        assertEquals("Work copy", preset.cloneName("Telegram", 5))
    }

    @Test
    fun testPresetDerivesAPatternFromAConcreteName() {
        val pattern = ClonePreset.derivePattern("Telegram (Clone 3)", "Telegram", 3)
        assertEquals("{app} (Clone {n})", pattern)
        // A number that is part of the app name must not become the clone index.
        val other = ClonePreset.derivePattern("WhatsApp 2 Business", "WhatsApp 2 Business", 1)
        assertEquals("{app}", other)
    }

    @Test
    fun testPresetRoundTripsTheCloneMods() {
        val mods = CloneMods(
            hideLauncherIcon = true,
            excludeFromRecents = true,
            lockRotation = true,
            largeHeap = true,
            extractNativeLibs = true,
            removePermissionGroups = setOf("camera", "location")
        )
        val preset = ClonePreset(name = "Privacy").withSettings(
            namePattern = "{app} {n}",
            badgeColor = 0xFF112233L,
            rotateIcon = false,
            invertColors = true,
            mods = mods,
            versionNameSuffix = "-clone"
        )
        val restored = preset.toMods(versionName = "1.0-clone")
        assertEquals(mods.hideLauncherIcon, restored.hideLauncherIcon)
        assertEquals(mods.excludeFromRecents, restored.excludeFromRecents)
        assertEquals(mods.lockRotation, restored.lockRotation)
        assertEquals(mods.extractNativeLibs, restored.extractNativeLibs)
        assertEquals(mods.largeHeap, restored.largeHeap)
        assertEquals(setOf("camera", "location"), restored.removePermissionGroups)
        assertEquals("1.0-clone", restored.versionName)
    }

    // ------------------------------------------------------------------ runtime features (phase 3)

    @Test
    fun testRuntimePasscodeIsStoredAsSha256() {
        // The injected patch computes the very same digest; if this ever changes, locks stop opening.
        assertEquals(
            "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4",
            RuntimeOptions.hash("1234")
        )
        val options = RuntimeOptions(
            lockMode = LockMode.PASSCODE,
            passcodeHash = RuntimeOptions.hash("my pass")
        )
        assertTrue(options.lockEnabled)
        assertTrue(options.matches("my pass"))
        assertFalse(options.matches("my pass "))
        // A hash without a lock mode is not a lock, and neither is a lock without a hash.
        assertFalse(RuntimeOptions(passcodeHash = RuntimeOptions.hash("1234")).lockEnabled)
        assertFalse(RuntimeOptions(lockMode = LockMode.PASSCODE).lockEnabled)
    }

    @Test
    fun testRuntimePatternIsHashedInDrawingOrder() {
        val pattern = listOf(0, 1, 4, 7)
        val options = RuntimeOptions(
            lockMode = LockMode.PATTERN,
            patternHash = RuntimeOptions.hashPattern(pattern)
        )
        assertTrue(options.lockEnabled)
        assertTrue(options.matchesPattern("0,1,4,7"))
        // The same dots in a different order are a different pattern.
        assertFalse(options.matchesPattern("7,4,1,0"))
        assertFalse(options.matches("0,1,4,7"))
        assertEquals(RuntimeOptions.hash("0,1,4,7"), RuntimeOptions.hashPattern(pattern))
    }

    @Test
    fun testRuntimeConfigStringIsReadableByThePatch() {
        val options = RuntimeOptions(
            lockMode = LockMode.PASSCODE,
            passcodeHash = RuntimeOptions.hash("1234"),
            blockScreenshots = true,
            incognitoWipe = false,
            exitOnScreenOff = true,
            forceDarkMode = true,
            confirmExit = true,
            shakeToExit = false,
            floatingBackButton = true,
            appLanguage = "ur"
        )
        assertEquals(
            "mode=passcode;lock=03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4" +
                ";pattern=;shots=1;wipe=0;screenoff=1;dark=1;confirm=1;shake=0;fab=1;lang=ur",
            options.configString()
        )
        val empty = RuntimeOptions()
        assertTrue(empty.isEmpty)
        // The keys have to stay exactly like this: the injected patch parses them by name.
        assertEquals(
            "mode=none;lock=;pattern=;shots=0;wipe=0;screenoff=0;dark=0;confirm=0;shake=0;fab=0;lang=",
            empty.configString()
        )
    }

    @Test
    fun testRuntimeLockModeKeysMatchThePatch() {
        assertEquals("none", LockMode.NONE.key)
        assertEquals("passcode", LockMode.PASSCODE.key)
        assertEquals("pattern", LockMode.PATTERN.key)
        assertEquals("calc", LockMode.CALCULATOR.key)
        assertEquals(LockMode.CALCULATOR, LockMode.fromKey("calc"))
        assertEquals(LockMode.NONE, LockMode.fromKey("something-else"))
        // The calculator disguise is a passcode lock: it unlocks with the passcode hash.
        val calculator = RuntimeOptions(
            lockMode = LockMode.CALCULATOR,
            passcodeHash = RuntimeOptions.hash("1234")
        )
        assertTrue(calculator.lockEnabled)
        assertTrue(calculator.matches("1234"))
    }

    @Test
    fun testRuntimeResetCodeDependsOnTheClonePackageOnly() {
        val code = RuntimeOptions.resetCode("com.whatsapp.clone1")
        assertEquals("25B40423", code)
        assertEquals(8, code.length)
        // A different clone of the same app gets a different code.
        assertNotEquals(code, RuntimeOptions.resetCode("com.whatsapp.clone2"))
    }

    @Test
    fun testRuntimeSummaryListsEverySelectedFeature() {
        val options = RuntimeOptions(
            lockMode = LockMode.PASSCODE,
            passcodeHash = RuntimeOptions.hash("1234"),
            blockScreenshots = true,
            incognitoWipe = true
        )
        assertEquals(
            "passcode lock, screenshots blocked, incognito (data wiped on exit)",
            options.summary()
        )
        assertEquals(
            "pattern lock, forced dark mode, confirm exit, shake to exit",
            RuntimeOptions(
                lockMode = LockMode.PATTERN,
                patternHash = RuntimeOptions.hashPattern(listOf(0, 1, 2, 5)),
                forceDarkMode = true,
                confirmExit = true,
                shakeToExit = true
            ).summary()
        )
        assertEquals("none", RuntimeOptions().summary())
    }

    // ------------------------------------------------------------------ batch cloning

    @Test
    fun testBatchProgressCountsReadyAndFailedItems() {
        val progress = BatchProgress(
            items = listOf(
                BatchItem("com.a", "A", state = BatchState.READY, apkPath = "/tmp/a.apk"),
                BatchItem("com.b", "B", state = BatchState.FAILED, message = "system app"),
                BatchItem("com.c", "C", state = BatchState.SKIPPED)
            )
        )
        assertEquals(3, progress.total)
        assertEquals(1, progress.done)
        assertEquals(1, progress.failed)
        assertTrue(progress.finished)
        assertEquals(listOf("com.a"), progress.installable.map { it.packageName })
    }

    @Test
    fun testBatchProgressIsNotFinishedWhileAnItemIsQueued() {
        val progress = BatchProgress(
            items = listOf(
                BatchItem("com.a", "A", state = BatchState.READY),
                BatchItem("com.b", "B", state = BatchState.PENDING)
            ),
            isRunning = true
        )
        assertFalse(progress.finished)
        assertTrue(progress.items.last().state == BatchState.PENDING)
    }
}
