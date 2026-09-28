package com.example.model

/**
 * Objective technical compatibility evaluation for an app.
 */
data class CompatibilityReport(
    val isSupported: Boolean,
    val formatDescription: String,
    val splitApkDetected: Boolean,
    val systemAppProtected: Boolean,
    val signatureRestrictionsNote: String,
    val technicalDetails: String,
    val recommendedAction: String
) {
    companion object {
        fun evaluate(app: InstalledApp): CompatibilityReport {
            if (app.isSystemApp) {
                return CompatibilityReport(
                    isSupported = false,
                    formatDescription = "System-Protected Application",
                    splitApkDetected = false,
                    systemAppProtected = true,
                    signatureRestrictionsNote = "System apps are cryptographically bound to device platform keys. Independent cloning is blocked by Android security policy.",
                    technicalDetails = "The package resides on a protected system image partition. Extracting or re-signing this APK as a separate launcher application will trigger system signature verification errors.",
                    recommendedAction = "Select a standalone user-installed application."
                )
            }

            if (app.isSplitApk) {
                return CompatibilityReport(
                    isSupported = false,
                    formatDescription = "Split APKs / Dynamic App Bundle (${app.splitSourceDirs.size + 1} parts)",
                    splitApkDetected = true,
                    systemAppProtected = false,
                    signatureRestrictionsNote = "Split APKs contain modular feature splits (base.apk + split_config.*.apk). Merging arbitrary split APKs into a valid single-APK requires source rebuild and violates Android signing guarantees.",
                    technicalDetails = "Modern Play Store apps use Android App Bundles (AAB) split across ABI, screen density, and localized language slices. Repackaging splits arbitrarily leads to missing resource ID tables or runtime classloading crashes.",
                    recommendedAction = "Only monolithic, standalone APK packages can be reliably cloned into independent packages."
                )
            }

            return CompatibilityReport(
                isSupported = true,
                formatDescription = "Standalone Monolithic APK",
                splitApkDetected = false,
                systemAppProtected = false,
                signatureRestrictionsNote = "The clone will be signed with a secure local self-signed key. It will not share Google Play services, original account logins, or in-app purchase verification with the original developer's signature.",
                technicalDetails = "Single APK container detected. Package manifest and resources can be safely packaged in app-private workspace without split resource conflicts.",
                recommendedAction = "Proceed with clone configuration."
            )
        }
    }
}
