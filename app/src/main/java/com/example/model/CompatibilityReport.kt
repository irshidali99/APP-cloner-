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
    val alternativeRecommendation: String? = null,
    /** Explanation from the known issue database, `null` for apps without a known problem. */
    val knownIssue: String? = null
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

        /**
         * Explanation shown in the app for a self-verifying app, including why container apps behave
         * differently.
         */
        fun selfVerifyingNotice(appLabel: String): String =
            "$appLabel checks the signature of its own APK while starting. A clone is a real, separate " +
                "app and therefore signed with a new certificate (the developer's key is private), so the " +
                "check fails and the app closes itself right after its first screen. Apps that only look " +
                "at their own package name work fine as clones; apps that check their signature do not.\n\n" +
                "What does work instead:\n" +
                "1. Built-in multi account: in $appLabel open Settings, tap your name and choose the " +
                "\"Add account\" option (two accounts in one official app, needs a second phone number).\n" +
                "2. Your phone's own Dual Apps / Dual Messenger / Parallel Apps / Secure Folder: it keeps " +
                "the app signed by its developer, so it runs normally.\n" +
                "3. WhatsApp Business can be installed next to the normal app with its own number.\n\n" +
                "Why a container app (Parallel Space, Clone Master, Dual Space) can run it: those apps do " +
                "not build a new clone - they let the original, unchanged APK run inside their own process, " +
                "so the original signature is still there. That is a different kind of product: the app " +
                "only lives inside the container, it is not a normal installed app, and everything " +
                "disappears with the container. This app creates true independent copies, which is exactly " +
                "why it cannot run an app that verifies its own signature."

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
            val knownIssue = KnownIssues.describe(app.packageName, app.label)

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
                    alternativeRecommendation = alternativeNotice,
                    knownIssue = knownIssue
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
                alternativeRecommendation = alternativeNotice,
                knownIssue = knownIssue
            )
        }
    }
}
