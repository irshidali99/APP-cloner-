package com.example.model

/**
 * Apps that are known not to work as a clone, and why.
 *
 * A clone is a real, separately signed app, so any app that checks its own certificate, uses Play
 * Integrity / key attestation / reCAPTCHA or Google Play sign-in cannot run as one. This list collects the
 * cases users hit most often; it mirrors what the commercial App Cloner lists as "cannot be cloned".
 */
object KnownIssues {

    /** [reason] is shown to the user, [advice] tells them what to do instead. */
    data class Issue(val appName: String, val reason: String, val advice: String)

    private val SIGNATURE_CHECKS = Issue(
        appName = "",
        reason = "This app checks the signature of its own APK while starting. A clone has to be signed " +
            "with a new certificate, so the check fails and the app closes right after its first screen.",
        advice = "Use the app's own multi account feature, your phone's Dual Apps / Dual Messenger / " +
            "Secure Folder, or a second app from the same developer (for example the Business variant)."
    )

    private val PLAY_SERVICES = Issue(
        appName = "",
        reason = "This app depends on Google Play services and the original signing certificate for " +
            "sign-in. A clone cannot receive that certificate, so login and cloud sync usually fail.",
        advice = "Clone a different app, or use your phone's Dual Apps / Dual Messenger which keeps the " +
            "original signature."
    )

    private val INTEGRITY_CHECKS = Issue(
        appName = "",
        reason = "This app uses Play Integrity / key attestation / anti-cheat checks. A re-signed clone " +
            "cannot pass them.",
        advice = "Keep the original app untouched - use a second device, a work profile or the app's own " +
            "multi account support."
    )

    private val BLOCKED = Issue(
        appName = "",
        reason = "This app crashes or misbehaves after being re-signed (known problem, reported by other " +
            "users as well).",
        advice = "Try the clone anyway if you want, but the original app stays the reliable option."
    )

    private val MAP: Map<String, Issue> = mapOf(
        "com.whatsapp" to SIGNATURE_CHECKS,
        "com.whatsapp.w4b" to SIGNATURE_CHECKS,
        "org.telegram.messenger" to SIGNATURE_CHECKS,
        "com.instagram.android" to SIGNATURE_CHECKS,
        "com.facebook.katana" to SIGNATURE_CHECKS,
        "com.facebook.orca" to SIGNATURE_CHECKS,
        "com.snapchat.android" to SIGNATURE_CHECKS,
        "com.zhiliaoapp.musically" to SIGNATURE_CHECKS,
        "com.tencent.mm" to SIGNATURE_CHECKS,
        "com.viber.voip" to SIGNATURE_CHECKS,
        "com.skype.raider" to SIGNATURE_CHECKS,
        "com.trello" to BLOCKED,
        "org.xbmc.kodi" to BLOCKED,
        "com.evernote" to BLOCKED,
        "com.contextlogic.wish" to BLOCKED,
        "com.ebay.mobile" to BLOCKED,
        "com.aliexpress.buyer" to BLOCKED,
        "com.grabtaxi.passenger" to BLOCKED,
        "com.lazada.android" to BLOCKED,
        "com.paytm" to INTEGRITY_CHECKS,
        "com.nianticlabs.pokemongo" to INTEGRITY_CHECKS,
        "com.google.android.youtube" to PLAY_SERVICES,
        "com.google.android.apps.docs" to PLAY_SERVICES,
        "com.microsoft.office.outlook" to PLAY_SERVICES,
        "com.onedrive.sync" to PLAY_SERVICES,
        "com.google.android.gms" to PLAY_SERVICES,
        "com.android.vending" to PLAY_SERVICES
    )

    /** The known issue for [packageName], or `null` when the app is not on the list. */
    fun forPackage(packageName: String): Issue? = MAP[packageName]?.let { issue ->
        issue.copy(appName = packageName)
    }

    /** Human readable problem report for the user, used in the setup screen and in the report. */
    fun describe(packageName: String, label: String): String? = forPackage(packageName)?.let { issue ->
        "$label: ${issue.reason}\n\nWhat to do instead: ${issue.advice}"
    }

    val entryCount: Int get() = MAP.size
}
