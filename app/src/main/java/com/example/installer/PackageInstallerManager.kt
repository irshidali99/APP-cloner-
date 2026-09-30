package com.example.installer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.content.IntentSender
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class PackageInstallerManager(private val context: Context) {

    /**
     * Checks if this application currently has permission to request package installation.
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Creates an Intent to navigate the user to system settings to grant unknown source install permission.
     */
    fun createManageUnknownSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    /**
     * Every APK that belongs to a clone.
     *
     * Clones built from an app bundle live in their own directory with a `parts.txt` listing the base and all
     * splits, so the whole bundle is found from the base APK alone. Older single APK clones simply return
     * their own file.
     */
    fun partsOf(apkFile: File): List<File> {
        val directory = apkFile.parentFile ?: return listOf(apkFile)
        val partsFile = File(directory, PARTS_FILE)
        if (partsFile.isFile) {
            val listed = partsFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { File(directory, it) }
                .filter { it.isFile }
            if (listed.isNotEmpty() && listed.any { it.name == apkFile.name }) return listed
        }
        return listOf(apkFile)
    }

    /**
     * Split names of a cloned bundle, read from each split's manifest (`config.arm64_v8a`), which is the name
     * Android reports for an installed split - file names would not match.
     */
    fun splitNamesOf(apkFile: File): List<String> = partsOf(apkFile)
        .filter { it.name != apkFile.name }
        .mapNotNull { runCatching { com.example.engine.core.ApkTransformer.readSplitName(it) }.getOrNull() }

    /** Split names of an installed package, used to verify a bundle install. */
    fun installedSplitNames(packageName: String): List<String> = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        info.splitNames?.toList().orEmpty()
    } catch (notFound: Exception) {
        emptyList()
    }

    /**
     * Creates an Intent to invoke the Android System Package Installer for a generated APK.
     * Uses content URI from FileProvider with FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun createInstallIntent(apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    /**
     * Writes every APK of a clone into one package installer session and commits it.
     *
     * Android installs a split app only when all of its parts are handed over inside a single session -
     * that is exactly how the Play Store installs app bundles.
     *
     * @param statusSender where the installer reports back to (see [InstallGatewayActivity], which keeps a
     *   foreground receiver so the confirmation dialog can always be shown).
     * @return the session id, or `-1` when the session could not be created.
     */
    @SuppressLint("MissingPermission")
    fun createSession(apkFiles: List<File>, statusSender: IntentSender, appPackageName: String? = null): Int {
        require(apkFiles.isNotEmpty()) { "no APK to install" }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // Naming the target package tells the installer which app the session belongs to, which is what
            // a split installation expects (the base APK alone does not identify the bundle).
            if (!appPackageName.isNullOrBlank()) {
                params.setAppPackageName(appPackageName)
            }
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                for (apk in apkFiles) {
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        apk.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }
                session.commit(statusSender)
            }
            lastError = null
            Log.i(TAG, "install session $sessionId created with ${apkFiles.size} part(s)")
            sessionId
        } catch (error: Exception) {
            lastError = error.message ?: error::class.java.simpleName
            Log.e(TAG, "install session failed", error)
            -1
        }
    }

    /**
     * Creates an Android Sharesheet Intent to securely share the cloned APK file.
     */
    fun createShareApkIntent(apkFile: File, cloneName: String): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val parts = partsOf(apkFile)

        if (parts.size > 1) {
            // Sharing only the base APK would produce a clone that closes right after its first screen, so
            // every part is attached and the text says why.
            val uris = ArrayList<Uri>()
            for (part in parts) {
                uris.add(FileProvider.getUriForFile(context, authority, part))
            }
            val multipleIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/vnd.android.package-archive"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "$cloneName APK bundle")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "App bundle clone \"$cloneName\": ${parts.size} APKs (base + splits). " +
                        "All parts belong together - install them with an installer that handles split APKs " +
                        "(for example App Cloner's own Install button or 'adb install-multiple')."
                )
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            return Intent.createChooser(multipleIntent, "Share $cloneName APK bundle")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "$cloneName APK")
            putExtra(
                Intent.EXTRA_TEXT,
                if (parts.size > 1) {
                    "Base APK of the cloned bundle $cloneName. The clone also has " +
                        "${parts.size - 1} split APK(s); all parts are in the clone folder of App Cloner."
                } else {
                    "Here is the standalone cloned APK package for $cloneName."
                }
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        return Intent.createChooser(shareIntent, "Share $cloneName APK")
    }

    /**
     * Creates an Intent to launch the installed clone application.
     */
    fun getLaunchIntent(packageId: String): Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageId)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an Intent to trigger the system-confirmed uninstall flow.
     */
    fun createUninstallIntent(packageId: String): Intent {
        return Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageId")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    companion object {
        private const val TAG = "PackageInstaller"
        const val PARTS_FILE = "parts.txt"

        /** Message of the last failed installer call, shown to the user instead of failing silently. */
        @Volatile
        var lastError: String? = null
            private set
    }
}
