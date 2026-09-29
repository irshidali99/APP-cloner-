package com.example.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.AppClonerApplication
import com.example.model.CloneRecord
import kotlinx.coroutines.launch

/**
 * Receives the outcome of a package installation session (used for app bundle clones, which have to be
 * installed as a multi APK session).
 *
 * Two things happen here:
 *  - the system asks the installer to confirm the install (`STATUS_PENDING_USER_ACTION`), which has to be
 *    handed off to the system installer UI, and
 *  - the final status is stored on the matching clone record so the UI can show it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        Log.i(TAG, "install result: status=$status package=$packageName message=$statusMessage")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The confirmation is shown by InstallGatewayActivity, which is visible while the session runs.
            // Starting an activity from here would be blocked in the background on Android 10+ (and the
            // installation would appear to vanish), so this receiver only records final statuses.
            Log.i(TAG, "user confirmation requested; handled by the install gateway activity")
            return
        }

        val application = context.applicationContext as? AppClonerApplication ?: return
        val repository = application.cloneRepository
        application.applicationScope.launch {
            val record = packageName?.let { runCatching { repository.findByPackageId(it) }.getOrNull() }

            if (record != null) {
                val newStatus = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> CloneRecord.STATUS_INSTALLED
                    else -> CloneRecord.STATUS_APK_READY
                }
                repository.updateClone(record.copy(installStatus = newStatus))
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
        private const val TAG = "InstallResult"
        const val ACTION_INSTALL_RESULT = "com.example.appcloner.INSTALL_RESULT"
        const val EXTRA_PART_COUNT = "part_count"
    }
}
