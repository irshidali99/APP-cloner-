package com.example.engine

import android.content.Context
import com.example.engine.core.ApkSignatureSchemeV2
import com.example.engine.core.CloneReport
import com.example.model.CloneConfig
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.model.PipelineProgress
import java.io.File

/**
 * Everything a successful cloning run produced, including the evidence that it really is a valid clone.
 */
data class CloneOutcome(
    val apkFile: File,
    val report: CloneReport,
    val signature: ApkSignatureSchemeV2.VerificationResult,
    val manifestPackage: String?,
    val resourcePackage: String?,
    val certificateFingerprint: String,
    val durationMillis: Long,
    /** Every APK of the clone; more than one entry for apps that were shipped as an app bundle. */
    val bundleParts: List<File> = listOf(apkFile),
    /** Names of the configuration/feature splits that were cloned. */
    val splitNames: List<String> = emptyList(),
    /** Signature schemes verified on the parts, e.g. `v1+v2`. */
    val signatureSchemes: String = "",
    /** Expansion (.obb) files of the original app - a clone of a game needs them under its own package. */
    val obbFiles: List<File> = emptyList()
) {
    /** True when the source app keeps game assets in expansion files. */
    val hasObb: Boolean get() = obbFiles.isNotEmpty()
    val isBundle: Boolean get() = bundleParts.size > 1

    /** True only when the signature verifies *and* both package identities match the requested one. */
    val isVerified: Boolean
        get() = signature.isValid &&
            manifestPackage == report.newPackage &&
            resourcePackage == report.newPackage

    /** Human readable one-liner used in the success screen and in the clone details. */
    fun summary(): String = buildString {
        append(if (isVerified) "Verified clone" else "Unverified output")
        append(" - ").append(report.originalPackage).append(" -> ").append(report.newPackage)
        if (isBundle) {
            append(", bundle with ").append(bundleParts.size).append(" parts")
        }
        if (report.iconEntriesReplaced.isNotEmpty()) {
            append(", icon replaced (").append(report.iconEntriesReplaced.size).append(" file(s))")
        }
    }
}

/**
 * Isolated interface for APK analysis, compatibility checking and cloning execution.
 */
interface CloneEngine {

    /**
     * Evaluates whether an installed application can be cloned into an independent APK on this device.
     */
    fun checkCompatibility(app: InstalledApp): CompatibilityReport

    /**
     * Runs the cloning pipeline: rewrite the package identity, apply the custom icon, sign the result and
     * verify it before it is handed to the installer.
     */
    suspend fun executeClonePipeline(
        context: Context,
        sourceApp: InstalledApp,
        config: CloneConfig,
        onProgress: (PipelineProgress) -> Unit
    ): Result<CloneOutcome>
}
