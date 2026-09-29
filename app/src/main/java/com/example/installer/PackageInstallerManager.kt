package com.example.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
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
     * Installs a clone that consists of several APKs (an app bundle) as one multi APK session.
     *
     * Android only accepts split apps when every part is written into a single session - that is exactly how
     * the Play Store installs bundles. The system shows its own confirmation dialog and reports back through
     * [InstallResultReceiver].
     */
    fun startBundleInstall(apkFiles: List<File>): Result<Unit> {
        require(apkFiles.isNotEmpty()) { "no APK to install" }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                for (apk in apkFiles) {
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        apk.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }

                val resultIntent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = InstallResultReceiver.ACTION_INSTALL_RESULT
                    putExtra(InstallResultReceiver.EXTRA_PART_COUNT, apkFiles.size)
                }
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
            Log.i(TAG, "bundle install session created with ${apkFiles.size} parts")
            Result.success(Unit)
        } catch (error: Exception) {
            Log.e(TAG, "bundle install failed", error)
            runCatching {
                // Abandon the session so a retry does not run into stale state.
                val installer = context.packageManager.packageInstaller
                installer.mySessions.forEach { if (it.isActive) installer.abandonSession(it.sessionId) }
            }
            Result.failure(error)
        }
    }

    /**
     * Creates an Android Sharesheet Intent to securely share the cloned APK file.
     */
    fun createShareApkIntent(apkFile: File, cloneName: String): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val parts = partsOf(apkFile)

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

    private companion object {
        const val TAG = "PackageInstaller"
        const val PARTS_FILE = "parts.txt"
    }
}
