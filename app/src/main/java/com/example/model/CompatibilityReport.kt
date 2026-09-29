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
                    isSupported = true,
                    formatDescription = "App bundle (${app.splitSourceDirs.size + 1} parts)",
                    splitApkDetected = true,
                    systemAppProtected = false,
                    signatureRestrictionsNote = "Apps installed as base + configuration splits are cloned as a whole: every part keeps its split name and gets the new package name and the same certificate, so Android installs them together as one app. All parts must be installed in a single step - installing only the base would leave the clone without its native libraries or density resources.",
                    technicalDetails = "The bundle contains the base APK plus ${app.splitSourceDirs.size} split APK(s) (ABI, density, language and feature splits). Each part is re-targeted in its manifest and resource table, re-signed with the local certificate and installed through one multi APK session.",
                    recommendedAction = "Proceed with clone configuration and use the in-app installer to install the whole bundle."
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
