package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CloneRecord
import com.example.model.InstalledApp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIconView
import com.example.ui.components.EmptyStateView
import com.example.ui.components.StatusBadge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyClonesScreen(
    viewModel: MainViewModel,
    onNavigateToApps: () -> Unit,
    onSelectCloneDetail: (CloneRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clones by viewModel.clonesList.collectAsStateWithLifecycle()
    val allApps by viewModel.rawInstalledApps.collectAsStateWithLifecycle()
    val storageUsed by viewModel.storageUsed.collectAsStateWithLifecycle()

    var cloneToInstall by remember { mutableStateOf<CloneRecord?>(null) }
    var cloneToDelete by remember { mutableStateOf<CloneRecord?>(null) }

    val storageUsedMb = remember(storageUsed) {
        String.format(Locale.US, "%.1f MB", storageUsed / (1024f * 1024f))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "My Clones",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${clones.size} clones managed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.onResume() },
                        modifier = Modifier.testTag("refresh_clones_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh clone status"
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
        ) {
            // Storage & Summary Banner
            if (clones.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Clone Storage Usage",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = storageUsedMb,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${clones.count { it.isInstalled }} Installed • ${clones.count { !it.isInstalled }} APK Stored",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.cleanTempFiles { freed ->
                                    // Feedback
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("clean_cache_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clean Cache", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Clones List or Empty State
            if (clones.isEmpty()) {
                EmptyStateView(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = "No Clones Created Yet",
                    description = "Duplicate any supported standalone app to use multiple accounts or customize icons.",
                    actionButtonText = "Select App to Clone",
                    onActionClick = onNavigateToApps,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(clones, key = { it.id }) { record ->
                        CloneRecordCard(
                            record = record,
                            apps = allApps,
                            onOpenClick = {
                                val launchIntent = viewModel.installerManager.getLaunchIntent(record.clonePackageId)
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                }
                            },
                            onInstallClick = {
                                cloneToInstall = record
                            },
                            onShareClick = {
                                val file = File(record.apkFilePath)
                                if (file.exists()) {
                                    val shareIntent = viewModel.installerManager.createShareApkIntent(file, record.cloneName)
                                    context.startActivity(shareIntent)
                                }
                            },
                            onCardClick = {
                                onSelectCloneDetail(record)
                            },
                            onDeleteClick = {
                                cloneToDelete = record
                            }
                        )
                    }
                }
            }
        }
    }

    // Install Handoff Dialog
    cloneToInstall?.let { record ->
        InstallHandoffDialog(
            context = context,
            apkFile = File(record.apkFilePath),
            cloneName = record.cloneName,
            packageInstallerManager = viewModel.installerManager,
            onDismiss = { cloneToInstall = null }
        )
    }

    // Delete Confirmation Dialog
    cloneToDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { cloneToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = "Delete ${record.cloneName}?")
            },
            text = {
                Text(
                    text = "This will remove the clone record from App Cloner and delete the stored APK file (${record.apkSizeBytes / (1024 * 1024)} MB). If the clone is currently installed on your home screen, you will need to uninstall it separately via system settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = record
                        cloneToDelete = null
                        viewModel.deleteClone(toDelete, deletePhysicalApk = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text("Delete APK & Record")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { cloneToDelete = null },
                    modifier = Modifier.testTag("cancel_delete_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CloneRecordCard(
    record: CloneRecord,
    apps: List<InstalledApp> = emptyList(),
    onOpenClick: () -> Unit,
    onInstallClick: () -> Unit,
    onShareClick: () -> Unit,
    onCardClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateString = remember(record.createdAt) {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(record.createdAt))
    }
    val sizeMb = remember(record.apkSizeBytes) {
        String.format(Locale.US, "%.1f MB", record.apkSizeBytes / (1024f * 1024f))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
            .testTag("clone_record_${record.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sourceApp = remember(record.sourcePackage, apps) {
                    apps.firstOrNull { it.packageName == record.sourcePackage }
                }

                AppIconView(
                    bitmap = sourceApp?.iconBitmap,
                    contentDescription = record.cloneName,
                    badgeNumber = record.badgeNumber,
                    badgeColor = Color(record.badgeColor),
                    rotationDegrees = record.rotationDegrees,
                    invertColors = record.invertColors,
                    modifier = Modifier.size(52.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.cloneName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "Source: ${record.sourceAppName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = record.clonePackageId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                when (record.installStatus) {
                    CloneRecord.STATUS_INSTALLED -> {
                        StatusBadge(
                            text = "Installed",
                            textColor = Color(0xFF059669),
                            backgroundColor = Color(0xFFECFDF5)
                        )
                    }
                    CloneRecord.STATUS_APK_READY -> {
                        StatusBadge(
                            text = "APK Ready",
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
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$sizeMb • Created $dateString",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (record.isInstalled) {
                        Button(
                            onClick = onOpenClick,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("open_clone_${record.id}")
                        ) {
                            Icon(imageVector = Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = onInstallClick,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("install_clone_${record.id}")
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Install", fontSize = 12.sp)
                        }
                    }

                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("share_clone_${record.id}")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("delete_clone_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
