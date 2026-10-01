package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.PipelineStage
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloningProgressScreen(
    viewModel: MainViewModel,
    onCloneSuccess: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by viewModel.cloningProgress.collectAsStateWithLifecycle()
    val animatedProgress by animateFloatAsState(
        targetValue = progress.progressFraction,
        label = "progress_animation"
    )

    LaunchedEffect(progress.isComplete) {
        if (progress.isComplete) {
            onCloneSuccess()
        }
    }

    val stages = listOf(
        PipelineStage.INSPECT_SOURCE,
        PipelineStage.VALIDATE_COMPATIBILITY,
        PipelineStage.PREPARE_PACKAGE,
        PipelineStage.BUILD_SIGN,
        PipelineStage.VERIFY_OUTPUT
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (progress.isFailed) "Cloning Stopped" else "Cloning Application",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Progress Meter Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (progress.isFailed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(96.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            strokeWidth = 8.dp,
                            color = if (progress.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (progress.isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = progress.stage.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (progress.isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = progress.detailMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress.isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        color = if (progress.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            // Stepper view of all 5 stages
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
                    Text(
                        text = "Pipeline Stages",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    stages.forEachIndexed { index, stage ->
                        val isPassed = animatedProgress >= stage.targetPercentage
                        val isCurrent = progress.stage == stage && !progress.isFailed

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isPassed -> MaterialTheme.colorScheme.primary
                                            isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPassed) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else if (isCurrent) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else {
                                    Text(
                                        text = (index + 1).toString(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stage.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isPassed || isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Real-time Diagnostic Terminal Log Viewer
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Diagnostic Log Terminal",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val listState = rememberLazyListState()
                    LaunchedEffect(progress.logHistory.size) {
                        if (progress.logHistory.isNotEmpty()) {
                            listState.animateScrollToItem(progress.logHistory.size - 1)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(progress.logHistory) { logLine ->
                            val color = when {
                                logLine.contains("[ERROR]") || logLine.contains("[EXCEPTION]") -> Color(0xFFEF4444)
                                logLine.contains("[WARN]") || logLine.contains("[REASON]") -> Color(0xFFF59E0B)
                                logLine.contains("Success") || logLine.contains("verified") -> Color(0xFF10B981)
                                else -> Color(0xFFE2E8F0)
                            }
                            Text(
                                text = logLine,
                                color = color,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Cancel / Dismiss button
            if (progress.isFailed) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("dismiss_error_button")
                ) {
                    Text("Return to Application List")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        viewModel.cancelCloning()
                        onCancel()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("cancel_cloning_button")
                ) {
                    Text("Cancel Cloning Operation")
                }
            }
        }
    }
}
