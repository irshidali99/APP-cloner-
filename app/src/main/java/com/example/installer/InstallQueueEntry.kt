package com.example.installer

/** One clone waiting in the install queue of [InstallGatewayActivity]. */
data class InstallQueueEntry(
    val cloneName: String,
    val apkPath: String,
    /** Package of the app the clone was made from (used to find its expansion files). */
    val sourcePackage: String = ""
)
