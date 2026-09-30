package com.example.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Support for apps that keep their large game assets in `.obb` expansion files.
 *
 * Android stores them as `/sdcard/Android/obb/<package>/main.<version>.<package>.obb`. A clone is a new
 * package, so it needs the same files under its own package name - otherwise a cloned game starts and
 * immediately stops because its assets are missing (the same reason a bundle clone needs all its splits).
 *
 * Reading and writing that directory needs "All files access" (MANAGE_EXTERNAL_STORAGE) on Android 11+,
 * so this helper reports what is possible and where the files are.
 */
object ObbSupport {

    private const val OBB_ROOT = "Android/obb"

    /** True when this app may read and write the shared obb directory. */
    fun canAccessObb(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Settings screen where the user grants "All files access". */
    fun manageAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }

    private fun directoryOf(packageName: String): File =
        File(Environment.getExternalStorageDirectory(), "$OBB_ROOT/$packageName")

    /** The obb files of [packageName], best effort: an empty list when they cannot be read. */
    fun obbFiles(packageName: String): List<File> {
        val directory = directoryOf(packageName)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.isFile }?.toList().orEmpty()
    }

    /** True when the installed app has expansion files. */
    fun hasObb(packageName: String): Boolean = obbFiles(packageName).isNotEmpty()

    /** Total size of the expansion files. */
    fun obbSize(packageName: String): Long = obbFiles(packageName).sumOf { it.length() }

    /**
     * Copies the expansion files of the original app into the clone's own obb directory.
     *
     * @return how many files were copied, or `-1` when the shared storage permission is missing.
     */
    fun copyObbToClone(sourcePackage: String, clonePackage: String): Int {
        if (!canAccessObbFiles()) return -1
        val source = directoryOf(sourcePackage)
        if (!source.isDirectory) return 0
        val target = directoryOf(clonePackage).apply { mkdirs() }
        if (!target.isDirectory) return -1
        var copied = 0
        source.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            // The file name contains the original package, so it is renamed for the clone.
            val newName = file.name.replace(sourcePackage, clonePackage)
            val destination = File(target, newName)
            runCatching {
                file.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }
        }
        return copied
    }

    private fun canAccessObbFiles(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    } catch (error: Exception) {
        // The platform call is missing on very old releases, where the legacy permission is enough.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    }
}
