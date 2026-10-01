package com.example.model

/**
 * Pipeline stages for clone creation.
 */
enum class PipelineStage(
    val title: String,
    val description: String,
    val targetPercentage: Float
) {
    INSPECT_SOURCE(
        title = "Inspecting Source Package",
        description = "Reading APK metadata, manifest components, and architecture assets.",
        targetPercentage = 0.20f
    ),
    VALIDATE_COMPATIBILITY(
        title = "Validating Compatibility",
        description = "Checking split APK status, signature protection, and anti-tamper constraints.",
        targetPercentage = 0.40f
    ),
    PREPARE_PACKAGE(
        title = "Preparing Clone Workspace",
        description = "Allocating app-private sandbox and writing package structures.",
        targetPercentage = 0.65f
    ),
    BUILD_SIGN(
        title = "Signing Clone Package",
        description = "Generating local certificate and cryptographically signing the APK.",
        targetPercentage = 0.85f
    ),
    VERIFY_OUTPUT(
        title = "Verifying Package Integrity",
        description = "Validating ZIP alignment, certificate validity, and install readiness.",
        targetPercentage = 1.0f
    ),
    COMPLETED(
        title = "Clone Generation Complete",
        description = "The standalone clone APK is ready for system installation.",
        targetPercentage = 1.0f
    ),
    FAILED(
        title = "Cloning Terminated",
        description = "Operation stopped due to compatibility or security constraints.",
        targetPercentage = 0f
    )
}

data class PipelineProgress(
    val stage: PipelineStage = PipelineStage.INSPECT_SOURCE,
    val progressFraction: Float = 0f,
    val detailMessage: String = "",
    val logHistory: List<String> = emptyList(),
    val isComplete: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val outputApkPath: String? = null,
    val outputPackageId: String? = null,
    val outputCloneName: String? = null
)
