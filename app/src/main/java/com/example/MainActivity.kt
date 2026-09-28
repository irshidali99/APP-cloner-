package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CloneRecord
import com.example.model.InstalledApp
import com.example.model.ThemeMode
import com.example.ui.MainViewModel
import com.example.ui.screens.CloneDetailsScreen
import com.example.ui.screens.CloneReadySuccessScreen
import com.example.ui.screens.CloneSetupScreen
import com.example.ui.screens.CloningProgressScreen
import com.example.ui.screens.InstalledAppsScreen
import com.example.ui.screens.MyClonesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AppClonerTheme

enum class MainDestination(val title: String) {
    INSTALLED_APPS("Apps"),
    MY_CLONES("My Clones"),
    SETTINGS("Settings"),
    CLONE_SETUP("Setup"),
    CLONING_PROGRESS("Progress"),
    CLONE_READY("Success"),
    CLONE_DETAILS("Details")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            AppClonerTheme(darkTheme = darkTheme) {
                AppClonerApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}

@Composable
fun AppClonerApp(viewModel: MainViewModel) {
    var currentDestination by remember { mutableStateOf(MainDestination.INSTALLED_APPS) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var selectedClone by remember { mutableStateOf<CloneRecord?>(null) }
    val clones by viewModel.clonesList.collectAsStateWithLifecycle()

    // Back button handling
    BackHandler(enabled = currentDestination != MainDestination.INSTALLED_APPS) {
        when (currentDestination) {
            MainDestination.CLONE_SETUP -> currentDestination = MainDestination.INSTALLED_APPS
            MainDestination.CLONING_PROGRESS -> {
                viewModel.cancelCloning()
                currentDestination = MainDestination.INSTALLED_APPS
            }
            MainDestination.CLONE_READY -> currentDestination = MainDestination.MY_CLONES
            MainDestination.CLONE_DETAILS -> currentDestination = MainDestination.MY_CLONES
            MainDestination.MY_CLONES, MainDestination.SETTINGS -> currentDestination = MainDestination.INSTALLED_APPS
            else -> {}
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            bottomBar = {
                if (!isWideScreen && (currentDestination == MainDestination.INSTALLED_APPS ||
                            currentDestination == MainDestination.MY_CLONES ||
                            currentDestination == MainDestination.SETTINGS)) {
                    NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                        NavigationBarItem(
                            selected = currentDestination == MainDestination.INSTALLED_APPS,
                            onClick = { currentDestination = MainDestination.INSTALLED_APPS },
                            icon = {
                                Icon(
                                    imageVector = if (currentDestination == MainDestination.INSTALLED_APPS) Icons.Default.Apps else Icons.Outlined.Apps,
                                    contentDescription = "Apps"
                                )
                            },
                            label = { Text("Apps") },
                            modifier = Modifier.testTag("nav_item_apps")
                        )

                        NavigationBarItem(
                            selected = currentDestination == MainDestination.MY_CLONES,
                            onClick = { currentDestination = MainDestination.MY_CLONES },
                            icon = {
                                BadgedBox(badge = {
                                    if (clones.isNotEmpty()) {
                                        Badge { Text(clones.size.toString()) }
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (currentDestination == MainDestination.MY_CLONES) Icons.Default.ContentCopy else Icons.Outlined.ContentCopy,
                                        contentDescription = "My Clones"
                                    )
                                }
                            },
                            label = { Text("My Clones") },
                            modifier = Modifier.testTag("nav_item_clones")
                        )

                        NavigationBarItem(
                            selected = currentDestination == MainDestination.SETTINGS,
                            onClick = { currentDestination = MainDestination.SETTINGS },
                            icon = {
                                Icon(
                                    imageVector = if (currentDestination == MainDestination.SETTINGS) Icons.Default.Settings else Icons.Outlined.Settings,
                                    contentDescription = "Settings"
                                )
                            },
                            label = { Text("Settings") },
                            modifier = Modifier.testTag("nav_item_settings")
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Wide Screen Navigation Rail
                if (isWideScreen && (currentDestination == MainDestination.INSTALLED_APPS ||
                            currentDestination == MainDestination.MY_CLONES ||
                            currentDestination == MainDestination.SETTINGS)) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("nav_rail")
                    ) {
                        NavigationRailItem(
                            selected = currentDestination == MainDestination.INSTALLED_APPS,
                            onClick = { currentDestination = MainDestination.INSTALLED_APPS },
                            icon = {
                                Icon(
                                    imageVector = if (currentDestination == MainDestination.INSTALLED_APPS) Icons.Default.Apps else Icons.Outlined.Apps,
                                    contentDescription = "Apps"
                                )
                            },
                            label = { Text("Apps") }
                        )

                        NavigationRailItem(
                            selected = currentDestination == MainDestination.MY_CLONES,
                            onClick = { currentDestination = MainDestination.MY_CLONES },
                            icon = {
                                BadgedBox(badge = {
                                    if (clones.isNotEmpty()) {
                                        Badge { Text(clones.size.toString()) }
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (currentDestination == MainDestination.MY_CLONES) Icons.Default.ContentCopy else Icons.Outlined.ContentCopy,
                                        contentDescription = "My Clones"
                                    )
                                }
                            },
                            label = { Text("My Clones") }
                        )

                        NavigationRailItem(
                            selected = currentDestination == MainDestination.SETTINGS,
                            onClick = { currentDestination = MainDestination.SETTINGS },
                            icon = {
                                Icon(
                                    imageVector = if (currentDestination == MainDestination.SETTINGS) Icons.Default.Settings else Icons.Outlined.Settings,
                                    contentDescription = "Settings"
                                )
                            },
                            label = { Text("Settings") }
                        )
                    }
                }

                when (currentDestination) {
                    MainDestination.INSTALLED_APPS -> {
                        InstalledAppsScreen(
                            viewModel = viewModel,
                            onNavigateToCloneSetup = { app ->
                                selectedApp = app
                                currentDestination = MainDestination.CLONE_SETUP
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MainDestination.CLONE_SETUP -> {
                        val app = selectedApp ?: viewModel.selectedAppForSetup.collectAsStateWithLifecycle().value
                        if (app != null) {
                            CloneSetupScreen(
                                viewModel = viewModel,
                                installedApp = app,
                                onBackClick = { currentDestination = MainDestination.INSTALLED_APPS },
                                onStartCloning = { currentDestination = MainDestination.CLONING_PROGRESS },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            currentDestination = MainDestination.INSTALLED_APPS
                        }
                    }

                    MainDestination.CLONING_PROGRESS -> {
                        CloningProgressScreen(
                            viewModel = viewModel,
                            onCloneSuccess = {
                                currentDestination = MainDestination.CLONE_READY
                            },
                            onCancel = {
                                currentDestination = MainDestination.INSTALLED_APPS
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MainDestination.CLONE_READY -> {
                        CloneReadySuccessScreen(
                            viewModel = viewModel,
                            onNavigateToMyClones = { currentDestination = MainDestination.MY_CLONES },
                            onNavigateToHome = { currentDestination = MainDestination.INSTALLED_APPS },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MainDestination.MY_CLONES -> {
                        MyClonesScreen(
                            viewModel = viewModel,
                            onNavigateToApps = { currentDestination = MainDestination.INSTALLED_APPS },
                            onSelectCloneDetail = { record ->
                                selectedClone = record
                                currentDestination = MainDestination.CLONE_DETAILS
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MainDestination.CLONE_DETAILS -> {
                        val cloneRecord = selectedClone
                        if (cloneRecord != null) {
                            CloneDetailsScreen(
                                record = cloneRecord,
                                viewModel = viewModel,
                                onBackClick = { currentDestination = MainDestination.MY_CLONES },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            currentDestination = MainDestination.MY_CLONES
                        }
                    }

                    MainDestination.SETTINGS -> {
                        SettingsScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
