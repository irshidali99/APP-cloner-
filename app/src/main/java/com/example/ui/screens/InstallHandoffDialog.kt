package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.installer.InstallGatewayActivity
import com.example.installer.PackageInstallerManager
import java.io.File

@Composable
fun InstallHandoffDialog(
    context: Context,
    apkFile: File,
    cloneName: String,
    packageInstallerManager: PackageInstallerManager,
    onDismiss: () -> Unit
) {
    var hasPermission by remember {
        mutableStateOf(packageInstallerManager.canRequestPackageInstalls())
    }
    val parts = remember(apkFile) { packageInstallerManager.partsOf(apkFile) }
    var installError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (hasPermission) Icons.Default.Download else Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = if (hasPermission) "System Installation Handoff" else "Install Permission Required",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasPermission) {
                    Text(
                        text = if (parts.size > 1) {
                            "App Cloner will install $cloneName as one bundle: the base APK plus " +
                                "${parts.size - 1} split APK(s), confirmed by Android in a single step."
                        } else {
                            "App Cloner will now hand off the package for $cloneName to the Android System Package Installer."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Android requires you to explicitly tap 'Install' on the system dialog.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• The clone will appear as an independent app on your home screen once installation finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (parts.size > 1) {
                        Text(
                            text = "• Split apps must be installed together; Android rejects a base APK " +
                                "without its configuration splits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    installError?.let { message ->
                        Text(
                            text = "Install could not be started: $message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        text = "Android requires explicit permission for App Cloner to install standalone APK packages.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Tap 'Open Settings' below, enable 'Allow from this source', and return here to complete installation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (hasPermission) {
                Button(
                    onClick = {
                        if (!apkFile.exists()) {
                            installError = "the generated APK is no longer on the device"
                            return@Button
                        }
                        onDismiss()
                        // A visible activity drives the installer session: Android hands the confirmation
                        // dialog back to the app, and starting it from the background is blocked on
                        // Android 10+, which is why the install appeared to vanish.
                        runCatching {
                            context.startActivity(
                                InstallGatewayActivity.intent(context, apkFile, cloneName)
                            )
                        }.onFailure { error ->
                            installError = error.message ?: error::class.java.simpleName
                        }
                    },
                    modifier = Modifier.testTag("proceed_install_button")
                ) {
                    Text(if (parts.size > 1) "Install bundle" else "Install now")
                }
            } else {
                Button(
                    onClick = {
                        val settingsIntent = packageInstallerManager.createManageUnknownSourcesIntent()
                        context.startActivity(settingsIntent)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("open_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Settings")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_handoff_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
