package com.example.model

data class SettingsData(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultSuffix: String = "Clone",
    val autoNumber: Boolean = true,
    val confirmDelete: Boolean = true,
    val storageUsageBytes: Long = 0L,
    val totalClonesCount: Int = 0,
    /** Maximum number of clones that may exist; 0 means unlimited. */
    val maxClones: Int = 0
)

enum class ThemeMode(val title: String) {
    SYSTEM("System Default"),
    LIGHT("Light Theme"),
    DARK("Dark Theme")
}
