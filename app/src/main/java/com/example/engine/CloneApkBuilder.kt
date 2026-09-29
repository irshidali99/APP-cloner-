package com.example.engine

import android.content.Context
import com.example.engine.core.ApkSignatureSchemeV2
import com.example.engine.core.ApkTransformer
import com.example.engine.core.CloneBundleRequest
import com.example.engine.core.CloneReport
import com.example.engine.core.CloneRequest
import com.example.engine.core.ArscEditor
import com.example.engine.core.ZipArchive
import com.example.model.CloneConfig
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.model.PipelineProgress
import com.example.model.PipelineStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a standalone clone of an installed application.
 *
 * Works for single APKs **and** for apps that Android shipped as an app bundle (`base.apk` + splits).
 * For bundles every part is re-targeted and re-signed, written into one directory next to a `parts.txt`,
 * and installed together as a multi APK session - the same way the Play Store installs split apps.
 *
 * Pipeline:
 *  1. inspect the source (readable, has a manifest, has a resource table),
 *  2. validate compatibility (system app, package name that fits the resource table),
 *  3. render the custom launcher icon and rewrite manifest + resource table of every part,
 *  4. sign each part (JAR v1 during the rewrite, APK Signature Scheme v2 afterwards),
 *  5. verify every part before it is offered for installation.
 *
 * All copying is streamed; the generated APKs never have to fit into memory.
 */
class CloneApkBuilder(
    private val packageInspector: PackageInspector,
    private val keystore: CloneKeystore
) : CloneEngine {

    /** A part of the clone that will be installed as one bundle. */
    data class BundlePart(val file: File, val isSplit: Boolean, val splitName: String?)

    override fun checkCompatibility(app: InstalledApp): CompatibilityReport =
        CompatibilityReport.evaluate(app)

    override suspend fun executeClonePipeline(
        context: Context,
        sourceApp: InstalledApp,
        config: CloneConfig,
        onProgress: (PipelineProgress) -> Unit
    ): Result<CloneOutcome> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun log(message: String) {
            logs.add("[${clock.format(Date())}] $message")
        }

        fun emit(
            stage: PipelineStage,
            fraction: Float,
            detail: String,
            complete: Boolean = false,
            failed: Boolean = false,
            error: String? = null,
            outcome: CloneOutcome? = null
        ) {
            onProgress(
                PipelineProgress(
                    stage = stage,
                    progressFraction = fraction,
                    detailMessage = detail,
                    logHistory = logs.toList(),
                    isComplete = complete,
                    isFailed = failed,
                    errorMessage = error,
                    outputApkPath = outcome?.apkFile?.absolutePath,
                    outputPackageId = outcome?.report?.newPackage,
                    outputCloneName = outcome?.report?.newLabel
                )
            )
        }

        fun fail(stage: PipelineStage, fraction: Float, message: String): Result<CloneOutcome> {
            log("[ERROR] $message")
            emit(stage, fraction, message, failed = true, error = message)
            return Result.failure(IllegalStateException(message))
        }

        val workspace = File(context.cacheDir, "clone-workspace").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            // ---------------------------------------------------------------- 1. inspect
            val isBundleSource = sourceApp.splitSourceDirs.isNotEmpty()
            emit(
                PipelineStage.INSPECT_SOURCE, 0.08f,
                "Reading ${sourceApp.label} (${sourceApp.packageName})" +
                    if (isBundleSource) " - ${sourceApp.splitSourceDirs.size + 1} parts" else ""
            )
            log("Source: ${sourceApp.label} ${sourceApp.versionName} (${sourceApp.packageName})")
            log("Source APK: ${sourceApp.sourceDir}")
            if (isBundleSource) {
                log("App bundle detected: base + ${sourceApp.splitSourceDirs.size} split(s).")
            }

            val sourceApk = File(sourceApp.sourceDir)
            if (!sourceApk.isFile || !sourceApk.canRead()) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.08f,
                    "The source APK cannot be read on this device (${sourceApp.sourceDir})."
                )
            }

            // Splits that this app cannot read (rare, e.g. removed by an optimiser) are reported, because a
            // clone without them can be incomplete.
            val readableSplits = ArrayList<File>()
            val missingSplits = ArrayList<String>()
            for (path in sourceApp.splitSourceDirs) {
                val split = File(path)
                if (split.isFile && split.canRead()) readableSplits.add(split) else missingSplits.add(split.name)
            }
            if (missingSplits.isNotEmpty()) {
                log("[WARN] unreadable splits: ${missingSplits.joinToString()}")
            }

            val totalBytes = sourceApk.length() + readableSplits.sumOf { it.length() }
            log("Total source size: ${totalBytes / 1024} KB")

            val archive = ZipArchive(sourceApk)
            val hasManifest: Boolean
            val hasResourceTable: Boolean
            val largestEntry: Long
            try {
                hasManifest = archive.findEntry(ApkTransformer.MANIFEST) != null
                hasResourceTable = archive.findEntry(ApkTransformer.RESOURCES) != null
                largestEntry = archive.entries.maxOfOrNull { it.uncompressedSize } ?: 0L
            } finally {
                archive.close()
            }

            // ---------------------------------------------------------------- 2. validate
            emit(PipelineStage.VALIDATE_COMPATIBILITY, 0.22f, "Validating package and signature constraints")

            val compatibility = checkCompatibility(sourceApp)
            if (!compatibility.isSupported) {
                log("[ERROR] ${compatibility.formatDescription}")
                log("[REASON] ${compatibility.signatureRestrictionsNote}")
                return@withContext fail(
                    PipelineStage.FAILED, 0.22f,
                    "${compatibility.formatDescription}: ${compatibility.signatureRestrictionsNote}"
                )
            }
            if (!hasManifest || !hasResourceTable) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.22f,
                    "The source APK has no AndroidManifest.xml or resources.arsc and cannot be re-targeted."
                )
            }
            if (totalBytes > MAX_SUPPORTED_BYTES || largestEntry > MAX_SUPPORTED_BYTES) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.22f,
                    "APKs above ${MAX_SUPPORTED_BYTES / (1024 * 1024)} MB are not supported by the current writer."
                )
            }

            val validation = config.validate()
            if (!validation.isValid) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.22f,
                    validation.error ?: "The clone configuration is invalid."
                )
            }

            val capacity = runCatching {
                ZipArchive(sourceApk).use { source ->
                    ArscEditor.parse(source.readEntry(source.findEntry(ApkTransformer.RESOURCES)!!))
                        .packageNameCapacity()
                }
            }.getOrElse { error ->
                return@withContext fail(
                    PipelineStage.FAILED, 0.22f,
                    "The resource table could not be read (${error.message ?: "unknown error"})."
                )
            }
            if (config.clonePackageId.length > capacity) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.22f,
                    "This app's resource table only fits $capacity characters for a package name, " +
                        "but '${config.clonePackageId}' has ${config.clonePackageId.length}."
                )
            }

            log("Compatibility verified: $capacity characters available for the package name.")

            // ---------------------------------------------------------------- 3. rewrite
            val iconPng = sourceApp.iconBitmap?.let { bitmap ->
                log("Rendering clone icon (badge ${config.badgeNumber ?: "none"}).")
                BadgeRenderer.render(
                    original = bitmap,
                    badgeNumber = config.badgeNumber,
                    badgeColor = config.badgeColor,
                    rotationDegrees = config.rotationDegrees,
                    invertColors = config.invertColors
                )
            }
            if (iconPng == null) {
                log("[WARN] No launcher icon available, keeping the original artwork.")
            }

            emit(
                PipelineStage.PREPARE_PACKAGE, 0.45f,
                if (isBundleSource) {
                    "Rewriting base.apk and ${readableSplits.size} split(s)"
                } else {
                    "Rewriting AndroidManifest.xml and resources.arsc"
                }
            )

            val identity = keystore.identity()
            val unsignedDirectory = File(workspace, "unsigned").apply { mkdirs() }

            val report: CloneReport
            val parts: List<BundlePart>

            if (isBundleSource) {
                val result = ApkTransformer.transformBundle(
                    request = CloneBundleRequest(
                        baseApk = sourceApk,
                        splitApks = readableSplits,
                        outputDirectory = unsignedDirectory,
                        newPackage = config.clonePackageId,
                        newLabel = config.cloneName,
                        iconPng = iconPng
                    ),
                    certificate = identity.certificate,
                    privateKey = identity.privateKey
                )
                report = result.base
                parts = listOf(BundlePart(result.baseFile, isSplit = false, splitName = null)) +
                    result.splitFiles.map {
                        BundlePart(it, isSplit = true, splitName = ApkTransformer.readSplitName(it))
                    }
                result.warnings.forEach { log("[WARN] $it") }
            } else {
                val output = File(unsignedDirectory, sourceApk.name)
                report = ApkTransformer.transform(
                    request = CloneRequest(
                        sourceApk = sourceApk,
                        outputApk = output,
                        newPackage = config.clonePackageId,
                        newLabel = config.cloneName,
                        iconPng = iconPng
                    ),
                    certificate = identity.certificate,
                    privateKey = identity.privateKey
                )
                parts = listOf(BundlePart(output, isSplit = false, splitName = null))
                report.warnings.forEach { log("[WARN] $it") }
            }

            log("Manifest rewritten: package ${report.originalPackage} -> ${report.newPackage}")
            log("Resource table re-targeted (${report.entriesWritten} entries copied).")
            if (report.renamedPermissions.isNotEmpty()) {
                log("App-owned permissions re-targeted: ${report.renamedPermissions.joinToString()}")
            }
            if (report.iconEntriesReplaced.isNotEmpty()) {
                log("Launcher icon replaced: ${report.iconEntriesReplaced.joinToString()}")
            }
            parts.filter { it.isSplit }.forEach { part ->
                log("Split kept: ${part.file.name}${part.splitName?.let { " ($it)" } ?: ""}")
            }
            if (report.foreignAuthorities.isNotEmpty()) {
                log("Foreign provider authorities left untouched: ${report.foreignAuthorities.joinToString()}")
            }

            // ---------------------------------------------------------------- 4. sign
            emit(PipelineStage.BUILD_SIGN, 0.68f, "Applying APK Signature Scheme v2 to ${parts.size} part(s)")
            for (part in parts) {
                ApkSignatureSchemeV2.signInPlace(part.file, identity.certificate, identity.privateKey)
            }
            log("Signed with ${identity.certificate.subjectX500Principal.name} (v1 + v2).")
            log("Certificate SHA-256: ${identity.fingerprint}")

            // ---------------------------------------------------------------- 5. verify
            emit(PipelineStage.VERIFY_OUTPUT, 0.88f, "Verifying package identity and signature of every part")

            val failures = ArrayList<String>()
            var schemes = ""
            for (part in parts) {
                val signature = ApkSignatureSchemeV2.verify(part.file)
                schemes = signature.schemes.joinToString("+")
                if (!signature.isValid) {
                    failures.add("${part.file.name}: ${signature.detail}")
                }
                val identityCheck = ApkTransformer.readPackageIdentity(part.file)
                if (identityCheck.first != config.clonePackageId) {
                    failures.add("${part.file.name}: manifest package is ${identityCheck.first}")
                }
                val resourcePackage = identityCheck.second
                if (part.file.name == sourceApk.name && resourcePackage == null) {
                    failures.add("${part.file.name}: the clone lost its resource table")
                } else if (resourcePackage != null && resourcePackage != config.clonePackageId) {
                    failures.add("${part.file.name}: resource table package is $resourcePackage")
                }
                if (part.isSplit && part.splitName != ApkTransformer.readSplitName(part.file)) {
                    failures.add("${part.file.name}: split name changed")
                }
            }
            if (failures.isNotEmpty()) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.88f,
                    "Verification failed - " + failures.joinToString("; ")
                )
            }

            // ---------------------------------------------------------------- output
            val clonesDirectory = File(context.filesDir, "clones").apply { mkdirs() }
            val sanitized = config.cloneName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val outputDirectory = File(clonesDirectory, "$sanitized-${System.currentTimeMillis()}")
            outputDirectory.mkdirs()

            val outputParts = ArrayList<File>()
            for (part in parts) {
                val destination = File(outputDirectory, part.file.name)
                part.file.copyTo(destination, overwrite = true)
                outputParts.add(destination)
            }
            // parts.txt tells the installer that these APKs belong together as one bundle.
            File(outputDirectory, PARTS_FILE).writeText(outputParts.joinToString("\n") { it.name })

            val baseFile = outputParts.first()
            val baseSignature = ApkSignatureSchemeV2.verify(baseFile)
            val baseIdentity = ApkTransformer.readPackageIdentity(baseFile)

            val outcome = CloneOutcome(
                apkFile = baseFile,
                report = report,
                signature = baseSignature,
                manifestPackage = baseIdentity.first,
                resourcePackage = baseIdentity.second,
                certificateFingerprint = identity.fingerprint,
                durationMillis = System.currentTimeMillis() - startedAt,
                bundleParts = outputParts,
                splitNames = parts.filter { it.isSplit }.mapNotNull { it.splitName },
                signatureSchemes = schemes
            )

            unsignedDirectory.deleteRecursively()

            log("Verified: signature $schemes valid for all ${outputParts.size} part(s).")
            log("Output: ${outputDirectory.name} (${outputParts.sumOf { it.length() } / 1024} KB)")

            emit(
                PipelineStage.COMPLETED, 1f,
                if (outcome.isBundle) {
                    "Clone ready: base + ${outcome.bundleParts.size - 1} split(s)"
                } else {
                    "Clone ready for installation"
                },
                complete = true, outcome = outcome
            )
            Result.success(outcome)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "Unknown cloning error"
            log("[EXCEPTION] $message")
            emit(PipelineStage.FAILED, 0f, message, failed = true, error = message)
            Result.failure(error)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private companion object {
        /** The writer emits 32 bit zip fields, so anything close to the 4 GiB limit is refused up front. */
        const val MAX_SUPPORTED_BYTES = 3_500_000_000L

        /** Lists the APK parts of a clone so the installer knows they belong to one bundle. */
        const val PARTS_FILE = "parts.txt"
    }
}
