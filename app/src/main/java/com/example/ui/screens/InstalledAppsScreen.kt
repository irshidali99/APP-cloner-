package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.ui.AppFilter
import com.example.ui.MainViewModel
import com.example.ui.components.AppIconView
import com.example.ui.components.BundleBadge
import com.example.ui.components.CloneableBadge
import com.example.ui.components.ClonedBadge
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SystemAppBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    viewModel: MainViewModel,
    onNavigateToCloneSetup: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier
) {
    val apps by viewModel.filteredInstalledApps.collectAsStateWithLifecycle()
    val allApps by viewModel.rawInstalledApps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val clonesList by viewModel.clonesList.collectAsStateWithLifecycle()

    var showCompatibilityDialogForApp by remember { mutableStateOf<InstalledApp?>(null) }

    val clonedCountTotal = remember(clonesList, allApps) {
        val clonedPkgs = clonesList.map { it.sourcePackage }.toSet()
        allApps.count { clonedPkgs.contains(it.packageName) }
    }
    val cloneableCountTotal = remember(allApps) {
        allApps.count { it.isCloneable }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "App Cloner",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Duplicate apps into independent launcher instances",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadInstalledApps() },
                        modifier = Modifier.testTag("refresh_apps_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh installed apps"
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
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search installed applications…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("search_input")
            )

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == AppFilter.ALL,
                    onClick = { viewModel.selectedFilter.value = AppFilter.ALL },
                    label = { Text("All (${allApps.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("filter_chip_all")
                )

                FilterChip(
                    selected = selectedFilter == AppFilter.CLONEABLE,
                    onClick = { viewModel.selectedFilter.value = AppFilter.CLONEABLE },
                    label = { Text("Cloneable ($cloneableCountTotal)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("filter_chip_cloneable")
                )

                FilterChip(
                    selected = selectedFilter == AppFilter.CLONED,
                    onClick = { viewModel.selectedFilter.value = AppFilter.CLONED },
                    label = { Text("Cloned ($clonedCountTotal)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("filter_chip_cloned")
                )
            }

            // Apps List or Loading or Empty State
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scanning installed applications...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (apps.isEmpty()) {
                if (searchQuery.isNotEmpty()) {
                    EmptyStateView(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = "No Applications Found",
                        description = "No apps match \"$searchQuery\". Try checking for typos or clear your search query.",
                        actionButtonText = "Clear Search",
                        onActionClick = { viewModel.searchQuery.value = "" }
                    )
                } else if (selectedFilter == AppFilter.CLONED) {
                    EmptyStateView(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = "No Cloned Apps Yet",
                        description = "You haven't cloned any installed applications yet. Choose a cloneable app from the list to create your first clone.",
                        actionButtonText = "View Cloneable Apps",
                        onActionClick = { viewModel.selectedFilter.value = AppFilter.CLONEABLE }
                    )
                } else {
                    EmptyStateView(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = "No Compatible Apps Available",
                        description = "No apps matched the selected filter on your device. Note that system-protected apps and Split APKs cannot be cloned.",
                        actionButtonText = "Show All Apps",
                        onActionClick = { viewModel.selectedFilter.value = AppFilter.ALL }
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        InstalledAppCard(
                            app = app,
                            onCloneClick = {
                                viewModel.prepareCloneSetup(app)
                                onNavigateToCloneSetup(app)
                            },
                            onInfoClick = {
                                showCompatibilityDialogForApp = app
                            }
                        )
                    }
                }
            }
        }
    }

    // Compatibility Explanation Dialog
    showCompatibilityDialogForApp?.let { app ->
        val report = remember(app) { CompatibilityReport.evaluate(app) }
        AlertDialog(
            onDismissRequest = { showCompatibilityDialogForApp = null },
            icon = {
                Icon(
                    imageVector = if (report.isSupported) Icons.Default.ContentCopy else Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = if (report.isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "${app.label} Compatibility",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Format: ${report.formatDescription}",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = report.signatureRestrictionsNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = report.technicalDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Recommendation: ${report.recommendedAction}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (report.isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                if (report.isSupported) {
                    Button(
                        onClick = {
                            val target = app
                            showCompatibilityDialogForApp = null
                            viewModel.prepareCloneSetup(target)
                            onNavigateToCloneSetup(target)
                        }
                    ) {
                        Text("Configure Clone")
                    }
                } else {
                    TextButton(onClick = { showCompatibilityDialogForApp = null }) {
                        Text("Got it")
                    }
                }
            },
            dismissButton = {
                if (report.isSupported) {
                    TextButton(onClick = { showCompatibilityDialogForApp = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
fun InstalledAppCard(
    app: InstalledApp,
    onCloneClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (app.isCloneable) {
                    onCloneClick()
                } else {
                    onInfoClick()
                }
            }
            .testTag("app_item_${app.packageName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconView(
                bitmap = app.iconBitmap,
                contentDescription = app.label
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (app.isCloneable && app.isSplitApk) {
                        BundleBadge(parts = app.splitSourceDirs.size + 1)
                    } else if (app.isCloneable) {
                        CloneableBadge()
                    } else if (app.isSystemApp) {
                        SystemAppBadge()
                    }

                    if (app.clonedCount > 0) {
                        ClonedBadge(count = app.clonedCount)
                    }

                    Text(
                        text = "v${app.versionName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (app.isCloneable) {
                Button(
                    onClick = onCloneClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("clone_button_${app.packageName}")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Clone", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onInfoClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("info_button_${app.packageName}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Compatibility details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
