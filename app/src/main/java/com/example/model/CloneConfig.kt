package com.example.model

/**
 * Configuration options selected by the user for creating an app clone.
 */
data class CloneConfig(
    val sourcePackage: String,
    val sourceAppName: String,
    val cloneName: String,
    val clonePackageId: String,
    val badgeNumber: Int? = null,
    val badgeColor: Long = 0xFF4F46E5L,
    val rotationDegrees: Float = 0f,
    val invertColors: Boolean = false
) {
    fun validate(): ValidationResult {
        val trimmedName = cloneName.trim()
        if (trimmedName.isEmpty()) {
            return ValidationResult(isValid = false, error = "Clone display name cannot be empty.")
        }
        if (trimmedName.length > 50) {
            return ValidationResult(isValid = false, error = "Clone display name is too long (maximum 50 characters).")
        }

        val trimmedPackage = clonePackageId.trim()
        if (trimmedPackage.isEmpty()) {
            return ValidationResult(isValid = false, error = "Package identifier cannot be empty.")
        }
        if (!trimmedPackage.matches(PACKAGE_NAME_REGEX)) {
            return ValidationResult(
                isValid = false,
                error = "Package ID must contain at least two dot-separated segments with lowercase letters, numbers, or underscores (e.g., com.example.clone)."
            )
        }
        if (trimmedPackage == sourcePackage) {
            return ValidationResult(
                isValid = false,
                error = "Clone package ID cannot be identical to the original application package."
            )
        }

        return ValidationResult(isValid = true, error = null)
    }

    companion object {
        private val PACKAGE_NAME_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

        fun generateDefaultPackageId(sourcePackage: String, cloneIndex: Int): String {
            return "$sourcePackage.clone$cloneIndex"
        }

        fun generateDefaultCloneName(sourceAppName: String, cloneIndex: Int, autoNumber: Boolean): String {
            return if (autoNumber) {
                "$sourceAppName (Clone $cloneIndex)"
            } else {
                "$sourceAppName Clone"
            }
        }
    }
}

data class ValidationResult(
    val isValid: Boolean,
    val error: String?
)
