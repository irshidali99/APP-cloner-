package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.InstalledApp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.installer.InstallLog
import com.example.model.CloneRecord
import com.example.model.CompatibilityReport
import com.example.ui.MainViewModel
import com.example.ui.components.AppIconView
import com.example.ui.components.StatusBadge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneDetailsScreen(
    record: CloneRecord,
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showInstallDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val allApps by viewModel.rawInstalledApps.collectAsStateWithLifecycle()
    val sourceApp = remember(record.sourcePackage, allApps) {
        allApps.firstOrNull { it.packageName == record.sourcePackage }
    }
    val file = remember(record.apkFilePath) { File(record.apkFilePath) }
    val sizeMb = remember(record.apkSizeBytes) {
        String.format(Locale.US, "%.1f MB", record.apkSizeBytes / (1024f * 1024f))
    }
    val dateString = remember(record.createdAt) {
        SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.US).format(Date(record.createdAt))
    }
    // What the Android installer answered for the last attempt. Declared here so both the header and the
    // action section (diagnostics copy) can use it.
    val lastAttempt = remember(record.id, record.installStatus) {
        InstallLog.read(context, record.clonePackageId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Clone Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("details_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIconView(
                        bitmap = sourceApp?.iconBitmap,
                        contentDescription = record.cloneName,
                        badgeNumber = record.badgeNumber,
                        badgeColor = Color(record.badgeColor),
                        rotationDegrees = record.rotationDegrees,
                        invertColors = record.invertColors,
                        modifier = Modifier.size(64.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = record.cloneName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = record.clonePackageId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        when (record.installStatus) {
                            CloneRecord.STATUS_INSTALLED -> {
                                StatusBadge(
                                    text = "Installed on Device",
                                    textColor = Color(0xFF059669),
                                    backgroundColor = Color(0xFFECFDF5)
                                )
                            }
                            CloneRecord.STATUS_APK_READY -> {
                                StatusBadge(
                                    text = "APK Ready to Install",
                                    textColor = Color(0xFF4F46E5),
                                    backgroundColor = Color(0xFFEEF2FF)
                                )
                            }
                            else -> {
                                StatusBadge(
                                    text = "Uninstalled",
                                    textColor = Color(0xFF64748B),
                                    backgroundColor = Color(0xFFF1F5F9)
                                )
                            }
                        }
                        if (record.isVerified) {
                            Spacer(modifier = Modifier.height(6.dp))
                            StatusBadge(
                                text = "Signature verified" +
                                    if (record.signatureScheme.isNotEmpty()) " (${record.signatureScheme})" else "",
                                textColor = Color(0xFF059669),
                                backgroundColor = Color(0xFFECFDF5)
                            )
                        }

                        // Diagnostics: an app that closes right after its first screen is almost always
                        // missing parts of its bundle, so show what was cloned versus what is installed.
                        val expectedSplits = record.splitNames
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        if (expectedSplits.isNotEmpty()) {
                            val installedSplits = if (record.isInstalled) {
                                viewModel.installedSplitNames(record.clonePackageId)
                            } else {
                                emptyList()
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            StatusBadge(
                                text = if (!record.isInstalled) {
                                    "Bundle: base + ${expectedSplits.size} split(s)"
                                } else if (installedSplits.size >= expectedSplits.size) {
                                    "All ${expectedSplits.size + 1} parts installed"
                                } else {
                                    "Incomplete: ${installedSplits.size} of ${expectedSplits.size} splits installed"
                                },
                                textColor = if (record.isInstalled &&
                                    installedSplits.size < expectedSplits.size
                                ) Color(0xFFB45309) else Color(0xFF4F46E5),
                                backgroundColor = if (record.isInstalled &&
                                    installedSplits.size < expectedSplits.size
                                ) Color(0xFFFFF7ED) else Color(0xFFEEF2FF)
                            )
                        }
                        if (record.modsSummary.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Clone mods: ${record.modsSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (record.certificateFingerprint.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Certificate SHA-256: ${record.certificateFingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // What the Android installer answered last time. Without this the reason for a
                        // refused installation only exists in a dialog that cannot be copied.
                        if (lastAttempt != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Last install attempt: " +
                                    if (lastAttempt.success) "installed" else "not installed",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (lastAttempt.success) Color(0xFF059669) else Color(0xFFB45309)
                            )
                            Text(
                                text = lastAttempt.message.ifBlank { "no message from the installer" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Primary Actions Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val expectedPartSplits = record.splitNames
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (record.isInstalled && expectedPartSplits.isNotEmpty()) {
                        val installedNow = viewModel.installedSplitNames(record.clonePackageId)
                        if (installedNow.size < expectedPartSplits.size) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFFFF7ED),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "This clone is missing ${expectedPartSplits.size - installedNow.size} " +
                                        "of its ${expectedPartSplits.size} split APK(s). An app that is missing " +
                                        "parts closes right after its first screen. Tap \"Install Package\" " +
                                        "below to install the complete bundle again.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF7C2D12),
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    // Games keep their assets in expansion files; the clone needs them under its own name.
                    if (record.obbFiles.isNotBlank()) {
                        val needsCopy = viewModel.needsObbCopy(record)
                        val canAccess = viewModel.canAccessObb()
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("obb_card")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Game data (expansion files)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF0C4A6E)
                                )
                                Text(
                                    text = if (needsCopy) {
                                        "This app stores assets outside the APK: " + record.obbFiles +
                                            ". The clone needs its own copy, otherwise it closes right " +
                                            "after its first screen."
                                    } else {
                                        "Expansion files are in place for this clone: " + record.obbFiles
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF0C4A6E)
                                )
                                if (needsCopy) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                viewModel.copyObb(record) { copied ->
                                                    Toast.makeText(
                                                        context,
                                                        when {
                                                            copied > 0 -> "Copied $copied file(s)"
                                                            copied == 0 -> "No source files found"
                                                            else -> "Grant \"All files access\" first"
                                                        },
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            },
                                            modifier = Modifier.testTag("obb_copy_button")
                                        ) {
                                            Text(if (canAccess) "Copy game data" else "Try copy")
                                        }
                                        OutlinedButton(
                                            onClick = { context.startActivity(viewModel.obbPermissionIntent()) },
                                            modifier = Modifier.testTag("obb_permission_button")
                                        ) {
                                            Text("Allow all files access")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (CompatibilityReport.isSelfVerifying(record.sourcePackage)) {
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("self_verifying_details_notice")
                        ) {
                            Text(
                                text = "Why this clone closes after its first screen:\n\n" +
                                    CompatibilityReport.selfVerifyingNotice(record.sourceAppName),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF7C2D12),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (record.isInstalled) {
                        Button(
                            onClick = {
                                val launchIntent = viewModel.installerManager.getLaunchIntent(record.clonePackageId)
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("details_open_button")
                        ) {
                            Icon(imageVector = Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Cloned App")
                        }
                    }

                    Button(
                        onClick = { showInstallDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (record.isInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("details_install_button")
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (record.isInstalled) "Reinstall Package" else "Install Package")
                    }

                    OutlinedButton(
                        onClick = {
                            if (file.exists()) {
                                val shareIntent = viewModel.installerManager.createShareApkIntent(file, record.cloneName)
                                context.startActivity(shareIntent)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("details_share_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share Standalone APK")
                    }

                    if (record.isInstalled) {
                        OutlinedButton(
                            onClick = {
                                val uninstallIntent = viewModel.installerManager.createUninstallIntent(record.clonePackageId)
                                context.startActivity(uninstallIntent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("details_uninstall_button")
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uninstall Clone from Device")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val expected = record.splitNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            val installed = if (record.isInstalled) {
                                viewModel.installedSplitNames(record.clonePackageId)
                            } else {
                                emptyList()
                            }
                            val report = buildString {
                                append("App Cloner diagnostics\n")
                                append("Clone: ").append(record.cloneName).append('\n')
                                append("Package: ").append(record.clonePackageId).append('\n')
                                append("Source app: ").append(record.sourceAppName)
                                append(" (").append(record.sourcePackage).append(")\n")
                                append("Status: ").append(record.installStatus).append('\n')
                                append("Signature: ").append(record.signatureScheme)
                                append(if (record.isVerified) " verified" else " unverified").append('\n')
                                append("Certificate SHA-256: ").append(record.certificateFingerprint).append('\n')
                                append("Expected splits: ")
                                append(expected.joinToString(", ").ifBlank { "none" }).append('\n')
                                append("Installed splits: ")
                                append(installed.joinToString(", ").ifBlank { "none" }).append('\n')
                                append("Android: SDK ").append(android.os.Build.VERSION.SDK_INT)
                                append(" (").append(android.os.Build.MANUFACTURER).append(' ')
                                append(android.os.Build.MODEL).append(")\n")
                                lastAttempt?.let { attempt ->
                                    append('\n').append(attempt.describe())
                                }
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("App Cloner diagnostics", report))
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("details_copy_diagnostics_button")
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Diagnostics")
                    }

                    OutlinedButton(
                        onClick = {
                            // Puts the diagnostics on the clipboard and opens a pre-filled issue, so a
                            // report can be sent without copying anything by hand.
                            val report = buildString {
                                append("App: ").append(record.sourceAppName)
                                append(" (").append(record.sourcePackage).append(")\n")
                                append("Clone: ").append(record.cloneName).append('\n')
                                append("Package: ").append(record.clonePackageId).append('\n')
                                append("Android: SDK ").append(android.os.Build.VERSION.SDK_INT)
                                append(" (").append(android.os.Build.MANUFACTURER).append(' ')
                                append(android.os.Build.MODEL).append(")\n")
                                append("Mods: ").append(record.modsSummary.ifBlank { "none" }).append('\n')
                                append("Splits: ").append(record.splitNames.ifBlank { "none" }).append('\n')
                                lastAttempt?.let { attempt ->
                                    append('\n').append(attempt.describe())
                                }
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("App Cloner report", report))
                            Toast.makeText(context, "Diagnostics copied - paste them into the report", Toast.LENGTH_LONG).show()
                            val url = "https://github.com/irshidali99/APP-cloner-/issues/new?title=" +
                                java.net.URLEncoder.encode(
                                    "Clone issue: " + record.sourceAppName + " (" + record.clonePackageId + ")",
                                    "UTF-8"
                                )
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("details_report_issue_button")
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Report App Issue")
                    }

                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("details_delete_record_button")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Stored APK & Record")
                    }
                }
            }

            // Package Details Table
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Technical Specification",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    DetailRow("Source App", record.sourceAppName)
                    DetailRow("Source Package", record.sourcePackage)
                    DetailRow("Source Version", record.sourceVersion)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DetailRow("Clone Package ID", record.clonePackageId)
                    DetailRow("APK File Size", sizeMb)
                    DetailRow("Generated Date", dateString)
                    DetailRow("File Exists on Disk", if (file.exists()) "Yes" else "No")
                    DetailRow("Storage Location", record.apkFilePath)
                }
            }
        }
    }

    if (showInstallDialog) {
        InstallHandoffDialog(
            context = context,
            apkFile = file,
            cloneName = record.cloneName,
            packageInstallerManager = viewModel.installerManager,
            onDismiss = { showInstallDialog = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Clone Record?") },
            text = {
                Text("This permanently deletes the stored APK file and removes this clone from the database.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteClone(record, deletePhysicalApk = true)
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
