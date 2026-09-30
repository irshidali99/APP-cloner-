package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.installer.InstallGatewayActivity
import com.example.model.BatchItem
import com.example.model.BatchState
import com.example.ui.MainViewModel
import com.example.ui.components.AppIconView
import com.example.ui.components.StatusBadge

/**
 * Batch cloning: pick several apps, clone them all with the current settings, then install the built
 * clones one after another (each installation asks for Android's own confirmation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchCloneScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val apps by viewModel.rawInstalledApps.collectAsStateWithLifecycle()
    val selection by viewModel.batchSelection.collectAsStateWithLifecycle()
    val progress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val clones by viewModel.clonesList.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val selectable = remember(apps, query) {
        apps.filter { app ->
            app.isCloneable && (query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch clone", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("batch_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (progress.items.isEmpty()) {
                Text(
                    text = "Select the apps to clone. Every clone uses the settings from the last setup " +
                        "screen (name, icon, clone mods). Cloning runs one app after another; afterwards you " +
                        "can install all clones in one go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch_search_field")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.setBatchSelection(selectable.map { it.packageName }.toSet()) },
                        label = { Text("Select all (${selectable.size})") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.setBatchSelection(emptySet()) },
                        label = { Text("Clear") }
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = selectable, key = { it.packageName }) { app ->
                        val checked = app.packageName in selection
                        val cloneCount = clones.count { it.sourcePackage == app.packageName }
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleBatchSelection(app.packageName) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    viewModel.toggleBatchSelection(app.packageName)
                                })
                                AppIconView(
                                    bitmap = app.iconBitmap,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = app.packageName + if (cloneCount > 0) " - $cloneCount clone(s)" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { viewModel.startBatch(selectable.filter { it.packageName in selection }) },
                    enabled = selection.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("batch_start_button")
                ) {
                    Icon(Icons.Default.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start batch (${selection.size})", fontWeight = FontWeight.Bold)
                }
            } else {
                // ------------------------------------------------------------------ run in progress / result
                val done = progress.done
                val total = progress.total
                Text(
                    text = if (progress.isRunning) {
                        "Cloning ${progress.currentLabel}... ($done of $total ready)"
                    } else {
                        "Batch finished: $done of $total clone(s) ready, ${progress.failed} failed"
                    },
                    fontWeight = FontWeight.Medium
                )
                if (progress.isRunning) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = progress.items, key = { it.packageName }) { item -> BatchRow(item) }
                }

                if (progress.isRunning) {
                    OutlinedButton(
                        onClick = { viewModel.cancelBatch() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("batch_cancel_button")
                    ) {
                        Text("Cancel batch")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val queue = viewModel.installQueueOfReadyClones()
                                val first = queue.firstOrNull() ?: return@Button
                                val rest = queue.drop(1)
                                val intent: Intent = InstallGatewayActivity.intentForQueue(context, first, rest)
                                context.startActivity(intent)
                            },
                            enabled = progress.installable.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("batch_install_all_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Install all (${progress.installable.size})")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.clearBatch()
                                viewModel.setBatchSelection(emptySet())
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("batch_clear_button")
                        ) {
                            Text("New batch")
                        }
                    }
                }
            }
        }
    }
}

/** One row of the batch result list. */
@Composable
private fun BatchRow(item: BatchItem) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = item.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.cloneName.isNotEmpty()) {
                    Text(
                        text = item.cloneName + if (item.message.isNotEmpty()) " - ${item.message}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (item.message.isNotEmpty()) {
                    Text(
                        text = item.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            when (item.state) {
                BatchState.PENDING -> StatusBadge(
                    text = "queued",
                    textColor = Color(0xFF64748B),
                    backgroundColor = Color(0xFFF1F5F9)
                )
                BatchState.CLONING -> StatusBadge(
                    text = "cloning",
                    textColor = Color(0xFF4F46E5),
                    backgroundColor = Color(0xFFEEF2FF)
                )
                BatchState.READY -> StatusBadge(
                    text = "ready",
                    textColor = Color(0xFF059669),
                    backgroundColor = Color(0xFFECFDF5)
                )
                BatchState.FAILED -> StatusBadge(
                    text = "failed",
                    textColor = Color(0xFFB91C1C),
                    backgroundColor = Color(0xFFFEF2F2)
                )
                BatchState.SKIPPED -> StatusBadge(
                    text = "skipped",
                    textColor = Color(0xFF64748B),
                    backgroundColor = Color(0xFFF1F5F9)
                )
            }
        }
    }
}
