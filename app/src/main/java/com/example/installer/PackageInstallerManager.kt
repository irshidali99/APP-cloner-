package com.example.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
     * Creates an Android Sharesheet Intent to securely share the cloned APK file.
     */
    fun createShareApkIntent(apkFile: File, cloneName: String): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "$cloneName APK")
            putExtra(Intent.EXTRA_TEXT, "Here is the standalone cloned APK package for $cloneName.")
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
}
