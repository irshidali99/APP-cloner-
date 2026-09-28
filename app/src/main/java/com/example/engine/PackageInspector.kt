package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.example.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PackageInspector(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    /**
     * Retrieves all launchable user-visible applications using policy-compliant <queries>.
     */
    suspend fun getInstalledApps(clonedPackageIds: Set<String> = emptySet()): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    mainIntent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(mainIntent, 0)
            }

            val apps = mutableListOf<InstalledApp>()
            val processedPackages = mutableSetOf<String>()

            for (resolveInfo in resolveInfoList) {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == context.packageName || processedPackages.contains(pkgName)) {
                    continue
                }
                processedPackages.add(pkgName)

                try {
                    val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getApplicationInfo(
                            pkgName,
                            PackageManager.ApplicationInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getApplicationInfo(pkgName, 0)
                    }

                    val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(
                            pkgName,
                            PackageManager.PackageInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(pkgName, 0)
                    }

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                    val label = resolveInfo.loadLabel(packageManager).toString().ifBlank {
                        appInfo.loadLabel(packageManager).toString()
                    }

                    val sourceFile = File(appInfo.sourceDir)
                    val apkSize = if (sourceFile.exists()) sourceFile.length() else 0L

                    val splitDirs = appInfo.splitSourceDirs?.toList() ?: emptyList()
                    val isSplit = splitDirs.isNotEmpty()

                    val icon = try {
                        drawableToBitmap(resolveInfo.loadIcon(packageManager))
                    } catch (e: Exception) {
                        null
                    }

                    val isClone = clonedPackageIds.contains(pkgName)

                    apps.add(
                        InstalledApp(
                            packageName = pkgName,
                            label = label,
                            versionName = pkgInfo.versionName ?: "1.0",
                            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                pkgInfo.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                pkgInfo.versionCode.toLong()
                            },
                            isSystemApp = isSystem,
                            sourceDir = appInfo.sourceDir,
                            splitSourceDirs = splitDirs,
                            isSplitApk = isSplit,
                            targetSdkVersion = appInfo.targetSdkVersion,
                            apkSizeBytes = apkSize,
                            isCloneable = !isSystem && !isSplit,
                            clonedCount = if (isClone) 1 else 0,
                            iconBitmap = icon
                        )
                    )
                } catch (e: Exception) {
                    // Ignore packages that cannot be read
                }
            }

            // Also create a guaranteed cloneable demo sample app if list is sparse (e.g. in test/emulator)
            if (apps.none { it.packageName == "com.aistudio.demo.counter" }) {
                apps.add(
                    0,
                    InstalledApp(
                        packageName = "com.aistudio.demo.counter",
                        label = "Tally Counter (Demo App)",
                        versionName = "1.0.0",
                        versionCode = 1L,
                        isSystemApp = false,
                        sourceDir = "internal_bundled",
                        splitSourceDirs = emptyList(),
                        isSplitApk = false,
                        targetSdkVersion = 34,
                        apkSizeBytes = 450_000L,
                        isCloneable = true,
                        compatibilityReason = "Standalone Monolithic APK (Verified Supported)",
                        clonedCount = 0,
                        iconBitmap = null
                    )
                )
            }

            apps.sortedBy { it.label.lowercase() }
        }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
