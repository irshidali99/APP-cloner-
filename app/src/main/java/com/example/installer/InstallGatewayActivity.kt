package com.example.installer

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.engine.core.ApkTransformer
import java.io.File

/**
 * Runs a package installation in the foreground.
 *
 * Installing a split app (an app bundle) requires a `PackageInstaller` session, and Android hands the
 * user confirmation back to the app as `STATUS_PENDING_USER_ACTION`. Starting that confirmation from a
 * background broadcast receiver is blocked on Android 10+ and on several OEM skins, which made the
 * installation simply disappear without any message. Running the whole flow inside a visible activity:
 *
 *  - the confirmation is started from a foreground activity, which is always allowed,
 *  - every step is reported on screen, so a failure shows the installer's own status code and message
 *    instead of nothing happening,
 *  - the outcome is stored on the clone record and this activity finishes.
 */
class InstallGatewayActivity : Activity() {

    private var receiver: InstallStatusReceiver? = null
    private var statusView: TextView? = null
    private var finished = false
    private var cloneName: String = "clone"
    private var targetPackageName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Installing clone"

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        cloneName = intent.getStringExtra(EXTRA_CLONE_NAME) ?: "clone"
        // A batch hands over one clone at a time: after a successful install the next one starts, so the
        // user only has to confirm each system dialog.
        pendingNames = intent.getStringArrayListExtra(EXTRA_QUEUE_NAMES) ?: ArrayList()
        pendingPaths = intent.getStringArrayListExtra(EXTRA_QUEUE_PATHS) ?: ArrayList()
        pendingSources = intent.getStringArrayListExtra(EXTRA_QUEUE_SOURCES) ?: ArrayList()
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        val baseApk = apkPath?.let { File(it) }

        if (baseApk == null || !baseApk.isFile) {
            showFailure("The generated APK could not be found on this device.")
            return
        }

        val manager = PackageInstallerManager(this)
        val parts = manager.partsOf(baseApk)
        queueTotal = intent.getIntExtra(EXTRA_QUEUE_TOTAL, 0)
        statusView = TextView(this).apply {
            text = buildString {
                append("Preparing to install $cloneName\n")
                if (queueTotal > 1) {
                    append("Batch: clone ${queueTotal - pendingNames.size} of $queueTotal\n")
                }
                append("${parts.size} APK part(s)\n\n")
                append("Google Play Protect may warn about this app because the clone is signed with a new\n")
                append("certificate instead of the original developer's key. That warning is expected for clones:\n")
                append("tap \"More details\" and then \"Install anyway\" to continue.")
            }
            setPadding(48, 96, 48, 48)
        }
        val progress = ProgressBar(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
            addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            FrameLayout(this).apply {
                addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER })
            }
        )

        // The receiver lives only while this activity is visible; the confirmation can therefore always be
        // started from a foreground context.
        receiver = InstallStatusReceiver().also {
            val filter = IntentFilter(InstallResultReceiver.ACTION_INSTALL_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(it, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(it, filter)
            }
        }

        // A clone with the same package name may already be installed (for example from an earlier
        // attempt). Android then answers with "App not installed as package conflicts with an existing
        // package"; saying so up front and offering to uninstall is far more useful.
        val targetPackage = runCatching { ApkTransformer.readPackageIdentity(baseApk).first }.getOrNull()
        targetPackageName = targetPackage.orEmpty()
        if (targetPackage != null && isInstalled(targetPackage)) {
            InstallLog.record(
                context = this,
                cloneName = cloneName,
                packageName = targetPackage,
                success = false,
                status = PackageInstaller.STATUS_FAILURE_CONFLICT,
                message = "A clone with this package name is already installed on the device.",
                expectedSplits = PackageInstallerManager(this).splitNamesOf(baseApk),
                installedSplits = PackageInstallerManager(this).installedSplitNames(targetPackage)
            )
            promptUninstall(targetPackage)
            return
        }

        expectedSplitNames = PackageInstallerManager(this).splitNamesOf(baseApk)

        // A part that belongs to another package would make the installer fail with a message that says
        // nothing about the real problem, so the parts are checked here first.
        val inconsistent = parts.filter { part ->
            val identity = runCatching { ApkTransformer.readPackageIdentity(part) }.getOrNull()
            identity == null || identity.first.isNullOrBlank() ||
                (targetPackage != null && identity.first != targetPackage)
        }
        if (inconsistent.isNotEmpty()) {
            InstallLog.record(
                context = this,
                cloneName = cloneName,
                packageName = targetPackageName,
                success = false,
                status = PackageInstaller.STATUS_FAILURE_INVALID,
                message = "These parts do not belong to the clone: " +
                    inconsistent.joinToString { it.name },
                expectedSplits = expectedSplitNames,
                installedSplits = emptyList()
            )
            showFailure(
                "The clone folder is not consistent: " + inconsistent.joinToString { it.name } +
                    " do not carry the package name " + (targetPackage ?: "?") + ".\n\n" +
                    "Clone the app again so all parts belong together."
            )
            return
        }

        startSession(parts, cloneName, targetPackage)
    }

    private var expectedSplitNames: List<String> = emptyList()
    private var queueTotal: Int = 0
    private var sourcePackage: String = ""
    private var pendingSources: ArrayList<String> = ArrayList()
    private var pendingNames: ArrayList<String> = ArrayList()
    private var pendingPaths: ArrayList<String> = ArrayList()

    private fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (notFound: PackageManager.NameNotFoundException) {
        false
    }

    /** Offers to remove the clone that is already installed so the new APK can take its place. */
    private fun promptUninstall(packageName: String) {
        AlertDialog.Builder(this)
            .setTitle("Already installed")
            .setMessage(
                "A clone with the package name\n\n$packageName\n\nis already installed on this device.\n\n" +
                    "Android refuses to install a second copy with the same package name. Uninstall the old " +
                    "clone first, then tap Install again."
            )
            .setPositiveButton("Uninstall old clone") { _, _ ->
                runCatching {
                    startActivity(PackageInstallerManager(this).createUninstallIntent(packageName))
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun startSession(parts: List<File>, cloneName: String, appPackageName: String?) {
        val resultIntent = Intent(InstallResultReceiver.ACTION_INSTALL_RESULT).apply {
            setPackage(packageName)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The installer fills in the confirmation intent and the status extras.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        // A fresh request code per session: reusing one cached PendingIntent across installs is a known way
        // to lose the installer's answer (and then nothing appears to happen).
        val requestCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, resultIntent, flags)

        // Cleaning up sessions left over from earlier attempts avoids "session already active" failures.
        runCatching {
            val installer = packageManager.packageInstaller
            installer.mySessions.forEach { session ->
                if (session.isActive) installer.abandonSession(session.sessionId)
            }
        }

        val sessionId = PackageInstallerManager(this)
            .createSession(parts, pendingIntent.intentSender, appPackageName)
        if (sessionId < 0) {
            showFailure(PackageInstallerManager.lastError ?: "The installer rejected the session.")
            return
        }
        updateStatus("Waiting for the system installer confirmation\n$cloneName (${parts.size} part(s))")
        // The installer calls back immediately in the normal case; if it never does, the user should get a
        // hint instead of an empty screen.
        statusView?.postDelayed({
            if (!finished) {
                updateStatus(
                    "The system installer has not answered yet.\n" +
                        "If no dialog appeared, check Settings > Apps > App Cloner > Install unknown apps."
                )
            }
        }, 15_000)
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusView?.text = message }
    }

    internal fun onPendingUserAction(confirmation: Intent?) {
        if (confirmation == null) {
            showFailure("The system installer did not provide a confirmation dialog.")
            return
        }
        updateStatus("Confirm the installation in the system dialog")
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(confirmation) }
            .onFailure { showFailure("The system installer dialog could not be opened: ${it.message}") }
    }

    internal fun onSessionFinished(status: Int, message: String?, packageName: String?) {
        if (finished) return
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                finished = true
                val expectedSplits = expectedSplitNames
                val installedSplits = packageName?.let {
                    PackageInstallerManager(this).installedSplitNames(it)
                }.orEmpty()
                val missing = expectedSplits.filterNot { it in installedSplits }
                Log.i(TAG, "installed=${packageName ?: "?"} expectedSplits=$expectedSplits installedSplits=$installedSplits")
                InstallLog.record(
                    context = this,
                    cloneName = cloneName,
                    packageName = packageName ?: targetPackageName,
                    success = missing.isEmpty(),
                    status = status,
                    message = if (missing.isEmpty()) {
                        "Installed with ${installedSplits.size + 1} part(s)."
                    } else {
                        "Installed, but these parts are missing: ${missing.joinToString()}"
                    },
                    expectedSplits = expectedSplits,
                    installedSplits = installedSplits
                )
                if (missing.isNotEmpty()) {
                    showFailure(
                        "The app installed, but these parts are missing: ${missing.joinToString()}.\n\n" +
                            "A clone that is missing parts closes right after its first screen.\n\n" +
                            "Tap \"Install now\" again and keep the whole bundle together - do not install " +
                            "or share the base APK by itself."
                    )
                } else {
                    // A cloned game needs the expansion files of the original under its own package name.
                    var obbNote = ""
                    val installedPackage = packageName ?: targetPackageName
                    if (installedPackage.isNotEmpty()) {
                        val hasObb = com.example.engine.ObbSupport.hasObb(installedPackage)
                        if (!hasObb && sourcePackage.isNotEmpty()) {
                            val sourceObb = com.example.engine.ObbSupport.hasObb(sourcePackage)
                            if (sourceObb) {
                                val copied = com.example.engine.ObbSupport.copyObbToClone(
                                    sourcePackage, installedPackage
                                )
                                obbNote = when {
                                    copied > 0 -> "\n\nExpansion files copied: $copied"
                                    copied == 0 -> "\n\nNo expansion files found to copy."
                                    else -> "\n\nExpansion files could not be copied: " +
                                        "grant App Cloner \"All files access\" and use \"Copy game data\" " +
                                        "on the clone's details screen."
                                }
                            }
                        }
                    }

                    val nextName = pendingNames.firstOrNull()
                    val nextPath = pendingPaths.firstOrNull()
                    if (nextName != null && nextPath != null) {
                        pendingNames.removeAt(0)
                        pendingPaths.removeAt(0)
                        Toast.makeText(this, "Installed $cloneName, next: $nextName", Toast.LENGTH_SHORT).show()
                        val next = Intent(this, InstallGatewayActivity::class.java).apply {
                            putExtra(EXTRA_APK_PATH, nextPath)
                            putExtra(EXTRA_CLONE_NAME, nextName)
                            putExtra(EXTRA_SOURCE_PACKAGE, nextSource)
                            putStringArrayListExtra(EXTRA_QUEUE_NAMES, pendingNames)
                            putStringArrayListExtra(EXTRA_QUEUE_PATHS, pendingPaths)
                            putStringArrayListExtra(EXTRA_QUEUE_SOURCES, pendingSources)
                            putExtra(EXTRA_QUEUE_TOTAL, queueTotal)
                        }
                        startActivity(next)
                    } else {
                        Toast.makeText(
                            this,
                            "Clone installed${if (packageName != null) " ($packageName)" else ""}" +
                                obbNote.replace("\n\n", " - "),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    setResult(RESULT_OK)
                    finish()
                }
            }
            else -> {
                val readable = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The installation was cancelled."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked this installation (device policy or 'install unknown apps')."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "The package conflicts with an app that is already installed."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The clone is not compatible with this device or Android version."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the generated APK as invalid."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space."
                    else -> "The installation failed (status $status)."
                }
                InstallLog.record(
                    context = this,
                    cloneName = cloneName,
                    packageName = packageName ?: targetPackageName,
                    success = false,
                    status = status,
                    message = message,
                    expectedSplits = expectedSplitNames,
                    installedSplits = packageName?.let {
                        PackageInstallerManager(this).installedSplitNames(it)
                    }.orEmpty()
                )
                showFailure("$readable\n\n${message ?: "no further details from the installer"}")
            }
        }
    }

    private fun showFailure(reason: String) {
        finished = true
        Log.e(TAG, "install failed: $reason")
        updateStatus(reason)
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("Installation failed")
                .setMessage(reason)
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }.onFailure { finish() }
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    /** Receives the installer callbacks while this activity is visible. */
    private inner class InstallStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            Log.i(TAG, "session status=$status package=$packageName message=$message")
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                onPendingUserAction(confirmationIntent(intent))
            } else {
                onSessionFinished(status, message, packageName)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "InstallGateway"
        private const val SESSION_REQUEST_CODE = 4711

        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_CLONE_NAME = "clone_name"
        const val EXTRA_QUEUE_NAMES = "queue_names"
        const val EXTRA_QUEUE_PATHS = "queue_paths"
        const val EXTRA_QUEUE_TOTAL = "queue_total"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val EXTRA_QUEUE_SOURCES = "queue_sources"

        /** Intent that runs the whole install flow in this activity. */
        fun intent(
            context: Context,
            apkFile: File,
            cloneName: String,
            sourcePackage: String = ""
        ): Intent =
            Intent(context, InstallGatewayActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
                putExtra(EXTRA_CLONE_NAME, cloneName)
                putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            }

        /**
         * Intent that installs a whole batch: [first] is installed now, everything in [queue] follows after
         * each successful installation, so the user confirms one system dialog after another.
         */
        fun intentForQueue(
            context: Context,
            first: InstallQueueEntry,
            queue: List<InstallQueueEntry>
        ): Intent {
            val names = ArrayList(queue.map { it.cloneName })
            val paths = ArrayList(queue.map { it.apkPath })
            val sources = ArrayList(queue.map { it.sourcePackage })
            return Intent(context, InstallGatewayActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, first.apkPath)
                putExtra(EXTRA_CLONE_NAME, first.cloneName)
                putExtra(EXTRA_SOURCE_PACKAGE, first.sourcePackage)
                putStringArrayListExtra(EXTRA_QUEUE_NAMES, names)
                putStringArrayListExtra(EXTRA_QUEUE_PATHS, paths)
                putStringArrayListExtra(EXTRA_QUEUE_SOURCES, sources)
                putExtra(EXTRA_QUEUE_TOTAL, queue.size + 1)
            }
        }
    }
}
