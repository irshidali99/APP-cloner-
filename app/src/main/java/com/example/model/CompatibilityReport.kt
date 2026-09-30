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
    val recommendedAction: String,
    /**
     * True for apps that check their own signature or install source at runtime. A re-signed clone cannot
     * pass that check, so such a clone installs but closes right after its first screen.
     */
    val selfVerifyingApp: Boolean = false,
    /** What to use instead when [selfVerifyingApp] is true. */
    val alternativeRecommendation: String? = null
) {
    companion object {

        /**
         * Apps that verify their own signature or integrity while starting.
         *
         * WhatsApp checks the APK signature of its own package (and the install source) and stops itself
         * when the app was re-signed - the app closes one or two seconds after its first screen. That check
         * cannot be satisfied by a clone: only Meta owns the original signing key, and disabling the check
         * would mean tampering with the app's protection (and puts the user's account at risk of being
         * banned). Every other kind of app is unaffected.
         */
        private val SELF_VERIFYING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )

        /** True when [packageName] is a known app that refuses to run after being re-signed. */
        fun isSelfVerifying(packageName: String): Boolean = packageName in SELF_VERIFYING_PACKAGES

        /** Explanation shown in the app for a self-verifying app. */
        fun selfVerifyingNotice(appLabel: String): String =
            "$appLabel checks its own APK signature when it starts. A clone is signed with a new " +
                "certificate (the original developer's key is private), so the check fails and the app " +
                "closes itself right after its first screen. This is deliberate protection on their side; " +
                "breaking it is not something this app does.\n\n" +
                "You do not need a clone for this:\n" +
                "• " + "WhatsApp itself: Settings → tap your name → \"Add account\" (two accounts in one " +
                "official app, needs a second phone number).\n" +
                "• Your phone's built-in Dual Apps / Dual Messenger / Parallel Apps keeps the app signed " +
                "by its developer, so it runs normally.\n" +
                "• WhatsApp Business can be installed next to normal WhatsApp, with its own number."

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

            val selfVerifying = isSelfVerifying(app.packageName)
            val alternativeNotice = if (selfVerifying) selfVerifyingNotice(app.label) else null

            if (app.isSplitApk) {
                return CompatibilityReport(
                    isSupported = true,
                    formatDescription = if (selfVerifying) {
                        "App bundle (${app.splitSourceDirs.size + 1} parts) - verifies its own signature"
                    } else {
                        "App bundle (${app.splitSourceDirs.size + 1} parts)"
                    },
                    splitApkDetected = true,
                    systemAppProtected = false,
                    signatureRestrictionsNote = "Apps installed as base + configuration splits are cloned as a whole: every part keeps its split name and gets the new package name and the same certificate, so Android installs them together as one app. All parts must be installed in a single step - installing only the base would leave the clone without its native libraries or density resources.",
                    technicalDetails = "The bundle contains the base APK plus ${app.splitSourceDirs.size} split APK(s) (ABI, density, language and feature splits). Each part is re-targeted in its manifest and resource table, re-signed with the local certificate and installed through one multi APK session.",
                    recommendedAction = if (selfVerifying) {
                        "A clone of this app will install but close right after its first screen. Use the " +
                            "official alternatives listed below instead."
                    } else {
                        "Proceed with clone configuration and use the in-app installer to install the whole bundle."
                    },
                    selfVerifyingApp = selfVerifying,
                    alternativeRecommendation = alternativeNotice
                )
            }

            return CompatibilityReport(
                isSupported = true,
                formatDescription = if (selfVerifying) {
                    "Standalone APK - verifies its own signature"
                } else {
                    "Standalone Monolithic APK"
                },
                splitApkDetected = false,
                systemAppProtected = false,
                signatureRestrictionsNote = "The clone will be signed with a secure local self-signed key. It will not share Google Play services, original account logins, or in-app purchase verification with the original developer's signature.",
                technicalDetails = "Single APK container detected. Package manifest and resources can be safely packaged in app-private workspace without split resource conflicts.",
                recommendedAction = if (selfVerifying) {
                    "A clone of this app will install but close right after its first screen. Use the " +
                        "official alternatives listed below instead."
                } else {
                    "Proceed with clone configuration."
                },
                selfVerifyingApp = selfVerifying,
                alternativeRecommendation = alternativeNotice
            )
        }
    }
}
