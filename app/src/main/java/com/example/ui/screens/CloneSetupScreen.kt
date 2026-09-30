package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CloneConfig
import com.example.model.ClonePermissionGroups
import com.example.model.InstalledApp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIconView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneSetupScreen(
    viewModel: MainViewModel,
    installedApp: InstalledApp,
    onBackClick: () -> Unit,
    onStartCloning: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cloneName by viewModel.cloneDisplayName.collectAsStateWithLifecycle()
    val packageId by viewModel.clonePackageId.collectAsStateWithLifecycle()
    val badgeNum by viewModel.badgeNumber.collectAsStateWithLifecycle()
    val badgeColorValue by viewModel.badgeColor.collectAsStateWithLifecycle()
    val rotation by viewModel.rotationDegrees.collectAsStateWithLifecycle()
    val invert by viewModel.invertColors.collectAsStateWithLifecycle()

    val validationResult by remember(cloneName, packageId, installedApp) {
        derivedStateOf {
            val config = CloneConfig(
                sourcePackage = installedApp.packageName,
                sourceAppName = installedApp.label,
                cloneName = cloneName,
                clonePackageId = packageId,
                badgeNumber = badgeNum,
                badgeColor = badgeColorValue,
                rotationDegrees = rotation,
                invertColors = invert
            )
            config.validate()
        }
    }

    val paletteColors = remember {
        listOf(
            0xFF4F46E5L to "Indigo",
            0xFF0EA5E9L to "Cyan",
            0xFF10B981L to "Emerald",
            0xFFF59E0BL to "Amber",
            0xFFEF4444L to "Rose",
            0xFF8B5CF6L to "Violet",
            0xFF64748BL to "Slate"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Clone Setup",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.startCloning(onStartCloning)
                        },
                        enabled = validationResult.isValid,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_cloning_button")
                    ) {
                        Text(
                            text = "Start Cloning Process",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Source App Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIconView(
                        bitmap = installedApp.iconBitmap,
                        contentDescription = installedApp.label,
                        modifier = Modifier.size(56.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = installedApp.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = installedApp.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Original app remains untouched",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Apps that verify their own signature (WhatsApp and friends) install but close right after
            // their first screen. Say so before the user spends time on a clone that cannot work, and show
            // the official alternatives.
            val compatibility by viewModel.compatibilityReport.collectAsStateWithLifecycle()
            compatibility?.takeIf { it.selfVerifyingApp }?.let { report ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("self_verifying_warning")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFB45309),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "This app cannot run as a clone",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF7C2D12)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = report.alternativeRecommendation ?: report.recommendedAction,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF7C2D12)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You can still clone it to test, but expect it to close after the first " +
                                "screen.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9A3412)
                        )
                    }
                }
            }

            // ------------------------------------------------ Presets (saved clone setups)
            val presets by viewModel.presets.collectAsStateWithLifecycle()
            val activePreset by viewModel.activePresetName.collectAsStateWithLifecycle()
            var showSavePresetDialog by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("presets_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Presets",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { showSavePresetDialog = true },
                            modifier = Modifier.testTag("save_preset_button")
                        ) {
                            Text("Save current")
                        }
                    }
                    Text(
                        text = if (presets.isEmpty()) {
                            "Save the settings below as a preset and apply them to any app with one tap " +
                                "(name pattern {app} and {n} are filled in automatically)."
                        } else {
                            "Applied preset: " + (activePreset ?: "none") +
                                ". Clones made with a preset keep its name pattern and mods."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (presets.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (preset in presets) {
                                InputChip(
                                    selected = preset.name == activePreset,
                                    onClick = { viewModel.applyPreset(preset) },
                                    label = { Text(preset.name, fontSize = 12.sp) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete ${preset.name}",
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.deletePreset(preset.id) }
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = presets.firstOrNull { it.name == activePreset }?.summary()
                                ?: presets.first().summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (showSavePresetDialog) {
                var presetName by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showSavePresetDialog = false },
                    title = { Text("Save preset") },
                    text = {
                        Column {
                            Text(
                                text = "The current name, icon styling and clone mods are saved and can be " +
                                    "applied to other apps later.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = presetName,
                                onValueChange = { presetName = it },
                                label = { Text("Preset name") },
                                singleLine = true,
                                modifier = Modifier.testTag("preset_name_field")
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.saveCurrentSettingsAsPreset(presetName)
                                showSavePresetDialog = false
                            },
                            modifier = Modifier.testTag("preset_save_confirm")
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ------------------------------------------------ Clone mods (manifest level features)
            val mods by viewModel.cloneMods.collectAsStateWithLifecycle()
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clone_mods_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Clone mods",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Applied to the clone's manifest while it is built. The original app stays " +
                            "untouched; these settings need no root and no code changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ModSwitch(
                        title = "Hide launcher icon",
                        subtitle = "Stealth: open the clone from App Cloner, no icon on the home screen",
                        checked = mods.hideLauncherIcon
                    ) { value -> viewModel.updateMods { it.copy(hideLauncherIcon = value) } }
                    ModSwitch(
                        title = "Hide from recent apps",
                        subtitle = "Keeps the clone out of the recents list",
                        checked = mods.excludeFromRecents
                    ) { value -> viewModel.updateMods { it.copy(excludeFromRecents = value) } }
                    ModSwitch(
                        title = "Install to SD card",
                        subtitle = "Prefers external storage as install location",
                        checked = mods.installToSdCard
                    ) { value -> viewModel.updateMods { it.copy(installToSdCard = value) } }
                    ModSwitch(
                        title = "Disable backup",
                        subtitle = "No cloud backup or device transfer for the clone",
                        checked = mods.disableBackup
                    ) { value -> viewModel.updateMods { it.copy(disableBackup = value) } }
                    ModSwitch(
                        title = "Block unencrypted traffic",
                        subtitle = "Refuses plain http connections",
                        checked = mods.disableCleartextTraffic
                    ) { value -> viewModel.updateMods { it.copy(disableCleartextTraffic = value) } }
                    ModSwitch(
                        title = "Lock rotation (portrait)",
                        subtitle = "Every activity stays in portrait",
                        checked = mods.lockRotation
                    ) { value -> viewModel.updateMods { it.copy(lockRotation = value) } }
                    ModSwitch(
                        title = "Multi window",
                        subtitle = "Allows split screen and free-form windows",
                        checked = mods.multiWindow
                    ) { value -> viewModel.updateMods { it.copy(multiWindow = value) } }
                    ModSwitch(
                        title = "Picture in picture",
                        subtitle = "Declares PiP support for the clone",
                        checked = mods.pictureInPicture
                    ) { value -> viewModel.updateMods { it.copy(pictureInPicture = value) } }
                    ModSwitch(
                        title = "Kiosk mode",
                        subtitle = "Lock task mode (needs device owner to be enforced)",
                        checked = mods.kioskMode
                    ) { value -> viewModel.updateMods { it.copy(kioskMode = value) } }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Remove permissions from the clone",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (group in ClonePermissionGroups.GROUPS.keys) {
                            val selected = group in mods.removePermissionGroups
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.togglePermissionGroup(group) },
                                label = { Text(ClonePermissionGroups.label(group), fontSize = 12.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "A removed permission can never be requested by the clone; the original app " +
                            "keeps its own permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = mods.versionName.orEmpty(),
                            onValueChange = { value ->
                                viewModel.updateMods { it.copy(versionName = value.ifBlank { null }) }
                            },
                            label = { Text("Version name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = mods.versionCode?.toString().orEmpty(),
                            onValueChange = { value ->
                                viewModel.updateMods {
                                    it.copy(versionCode = value.filter { c -> c.isDigit() }.ifBlank { null }?.toLongOrNull())
                                }
                            },
                            label = { Text("Version code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Icon Customization Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Icon Customization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Customize the launcher icon to distinguish this clone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Icon Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIconView(
                            bitmap = installedApp.iconBitmap,
                            contentDescription = "Clone Preview",
                            badgeNumber = badgeNum,
                            badgeColor = Color(badgeColorValue),
                            rotationDegrees = rotation,
                            invertColors = invert,
                            modifier = Modifier.size(72.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Badge Number Selection
                    Text(
                        text = "Badge Number",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = badgeNum == null,
                            onClick = { viewModel.badgeNumber.value = null },
                            label = { Text("None") },
                            modifier = Modifier.testTag("badge_chip_none")
                        )
                        (1..9).forEach { num ->
                            FilterChip(
                                selected = badgeNum == num,
                                onClick = { viewModel.badgeNumber.value = num },
                                label = { Text(num.toString()) },
                                modifier = Modifier.testTag("badge_chip_$num")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Badge Color Palette
                    Text(
                        text = "Badge Color",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        paletteColors.forEach { (colorHex, name) ->
                            val isSelected = badgeColorValue == colorHex
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.badgeColor.value = colorHex }
                                    .testTag("color_picker_$name")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Icon Rotation
                    Text(
                        text = "Icon Rotation",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0f to "0°", 90f to "90°", 180f to "180°", 270f to "270°").forEach { (deg, label) ->
                            FilterChip(
                                selected = rotation == deg,
                                onClick = { viewModel.rotationDegrees.value = deg },
                                label = { Text(label) },
                                modifier = Modifier.testTag("rotation_chip_$label")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Invert Colors Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Invert Icon Colors",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "High contrast negative effect for quick recognition",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = invert,
                            onCheckedChange = { viewModel.invertColors.value = it },
                            modifier = Modifier.testTag("invert_colors_switch")
                        )
                    }
                }
            }

            // Naming and Package ID Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Clone Identity",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Clone Name
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { viewModel.cloneDisplayName.value = it },
                        label = { Text("Clone Display Name") },
                        supportingText = { Text("${cloneName.length}/50 characters") },
                        isError = cloneName.isBlank(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clone_name_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Clone Package ID
                    OutlinedTextField(
                        value = packageId,
                        onValueChange = { viewModel.clonePackageId.value = it },
                        label = { Text("Unique Package ID") },
                        supportingText = {
                            if (!validationResult.isValid && validationResult.error != null) {
                                Text(
                                    text = validationResult.error!!,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("Independent identifier required by Android package manager")
                            }
                        },
                        isError = !validationResult.isValid,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clone_package_id_input")
                    )
                }
            }

            // Legal & Security Disclaimer Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Security & Data Notice",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• Isolated Storage: Android's sandbox model prevents cloning private app data, passwords, or credentials. The clone will launch in a clean, isolated state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Self-Signed Key: The clone is cryptographically signed with a secure local key. Google Play services or developer-verified DRM checks may not function inside the clone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** One labelled switch of the clone mods card. */
@Composable
private fun ModSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
