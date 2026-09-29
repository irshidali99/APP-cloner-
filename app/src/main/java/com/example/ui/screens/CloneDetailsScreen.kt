package com.example.ui.screens

import android.content.Context
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
import com.example.model.CloneRecord
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
                        if (record.certificateFingerprint.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Certificate SHA-256: ${record.certificateFingerprint}",
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
