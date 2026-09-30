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
                            isCloneable = !isSystem,
                            clonedCount = if (isClone) 1 else 0,
                            iconBitmap = icon
                        )
                    )
                } catch (e: Exception) {
                    // Ignore packages that cannot be read
                }
            }

            // Only applications that are really installed are listed; there is no simulated sample app.

            apps.sortedBy { it.label.lowercase() }
        }

    /** True when a package with that name is installed and visible to this app. */
    fun isPackageInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (notInstalled: Exception) {
        false
    }

    /**
     * Package names of every app that has a launcher entry.
     *
     * Used in addition to `getInstalledPackages`, because since Android 11 the package manager hides apps
     * that do not match the `<queries>` of this app. A launcher query is declared in the manifest, so this
     * list is complete for the apps a clone can come from or turn into - and every clone has a launcher
     * entry.
     */
    fun launchablePackageNames(): Set<String> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(mainIntent, 0)
            }
        } catch (error: Exception) {
            emptyList()
        }
        return resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    /**
     * Clone indexes that are currently installed for [sourcePackage].
     *
     * The clone history in the database is not enough to pick a free package name: a record can be deleted
     * (or never saved) while the clone is still installed, and Android rejects a second package with the same
     * name.
     */
    fun installedCloneIndexes(sourcePackage: String): Set<Int> {
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        } catch (error: Exception) {
            emptyList()
        }
        // Two independent sources: the package manager list and the launcher query. Either one alone can be
        // incomplete because of package visibility filtering on Android 11+.
        val names = LinkedHashSet<String>()
        packages.forEach { info -> names.add(info.packageName) }
        names.addAll(launchablePackageNames())
        return names.mapNotNull { name ->
            com.example.model.CloneConfig.cloneIndexSuffix(name, sourcePackage)
        }.toSet()
    }

    /** Split APKs that are actually installed for [packageName] (empty for single APK apps). */
    fun installedSplitNames(packageName: String): List<String> {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.splitNames?.toList().orEmpty()
        } catch (notFound: Exception) {
            emptyList()
        }
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
