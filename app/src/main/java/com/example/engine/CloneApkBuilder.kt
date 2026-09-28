package com.example.engine

import android.content.Context
import com.example.engine.core.ApkSignatureSchemeV2
import com.example.engine.core.ApkTransformer
import com.example.engine.core.ArscEditor
import com.example.engine.core.CloneReport
import com.example.engine.core.CloneRequest
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
 * Pipeline:
 *  1. inspect the source APK (readable, has a manifest and a resource table),
 *  2. validate compatibility (system app, split APK, package name that fits the resource table),
 *  3. render the custom launcher icon and rewrite manifest + resource table,
 *  4. sign with the persistent local identity (JAR v1 during the rewrite, APK Signature Scheme v2 after),
 *  5. verify the output before it is offered for installation.
 *
 * All work is streamed; the generated APK never has to fit into memory.
 */
class CloneApkBuilder(
    private val packageInspector: PackageInspector,
    private val keystore: CloneKeystore
) : CloneEngine {

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
            emit(
                PipelineStage.INSPECT_SOURCE, 0.08f,
                "Reading ${sourceApp.label} (${sourceApp.packageName})"
            )
            log("Source: ${sourceApp.label} ${sourceApp.versionName} (${sourceApp.packageName})")
            log("Source APK: ${sourceApp.sourceDir}")

            val sourceApk = File(sourceApp.sourceDir)
            if (!sourceApk.isFile || !sourceApk.canRead()) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.08f,
                    "The source APK cannot be read on this device (${sourceApp.sourceDir})."
                )
            }

            val archive = ZipArchive(sourceApk)
            val hasManifest: Boolean
            val hasResourceTable: Boolean
            val largestEntry: Long
            val totalSize: Long
            try {
                hasManifest = archive.findEntry(ApkTransformer.MANIFEST) != null
                hasResourceTable = archive.findEntry(ApkTransformer.RESOURCES) != null
                largestEntry = archive.entries.maxOfOrNull { it.uncompressedSize } ?: 0L
                totalSize = sourceApk.length()
            } finally {
                archive.close()
            }

            log("Source APK size: ${totalSize / 1024} KB")

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
            if (totalSize > MAX_SUPPORTED_BYTES || largestEntry > MAX_SUPPORTED_BYTES) {
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

            log("Compatibility verified: standalone package, package name fits ($capacity characters available).")

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
                "Rewriting AndroidManifest.xml and resources.arsc"
            )

            val identity = keystore.identity()
            val unsigned = File(workspace, "clone-unsigned.apk")
            val report: CloneReport = ApkTransformer.transform(
                request = CloneRequest(
                    sourceApk = sourceApk,
                    outputApk = unsigned,
                    newPackage = config.clonePackageId,
                    newLabel = config.cloneName,
                    iconPng = iconPng
                ),
                certificate = identity.certificate,
                privateKey = identity.privateKey
            )

            log("Manifest rewritten: package ${report.originalPackage} -> ${report.newPackage}")
            log("Resource table re-targeted (${report.entriesWritten} entries copied).")
            if (report.iconEntriesReplaced.isNotEmpty()) {
                log("Launcher icon replaced: ${report.iconEntriesReplaced.joinToString()}")
            }
            report.warnings.forEach { log("[WARN] $it") }
            if (report.foreignAuthorities.isNotEmpty()) {
                log("Foreign provider authorities left untouched: ${report.foreignAuthorities.joinToString()}")
            }

            // ---------------------------------------------------------------- 4. sign
            emit(PipelineStage.BUILD_SIGN, 0.68f, "Applying APK Signature Scheme v2")
            ApkSignatureSchemeV2.signInPlace(unsigned, identity.certificate, identity.privateKey)
            log("Signed with ${identity.certificate.subjectX500Principal.name} (v1 + v2).")
            log("Certificate SHA-256: ${identity.fingerprint}")

            // ---------------------------------------------------------------- 5. verify
            emit(PipelineStage.VERIFY_OUTPUT, 0.88f, "Verifying package identity and signature")

            val signature = ApkSignatureSchemeV2.verify(unsigned)
            val identityCheck = ApkTransformer.readPackageIdentity(unsigned)
            val manifestPackage = identityCheck.first
            val resourcePackage = identityCheck.second

            if (!signature.isValid) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.88f,
                    "Signature verification failed: ${signature.detail}"
                )
            }
            if (manifestPackage != config.clonePackageId || resourcePackage != config.clonePackageId) {
                return@withContext fail(
                    PipelineStage.FAILED, 0.88f,
                    "Package identity mismatch: manifest=$manifestPackage, resource table=$resourcePackage."
                )
            }

            val outputDirectory = File(context.filesDir, "clones").apply { mkdirs() }
            val sanitized = config.cloneName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val outputFile = File(outputDirectory, "${sanitized}-${System.currentTimeMillis()}.apk")
            unsigned.copyTo(outputFile, overwrite = true)
            unsigned.delete()

            val outcome = CloneOutcome(
                apkFile = outputFile,
                report = report,
                signature = signature,
                manifestPackage = manifestPackage,
                resourcePackage = resourcePackage,
                certificateFingerprint = identity.fingerprint,
                durationMillis = System.currentTimeMillis() - startedAt
            )

            log("Verified: v2 signature ${signature.detail}.")
            log("Output: ${outputFile.name} (${outputFile.length() / 1024} KB)")

            emit(
                PipelineStage.COMPLETED, 1f,
                "Clone ready for installation", complete = true, outcome = outcome
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
    }
}
