package com.example.model

/** What a batch clone run is doing with one of the selected apps. */
enum class BatchState {
    /** Waiting in the queue. */
    PENDING,

    /** Currently being cloned. */
    CLONING,

    /** Clone built and saved, ready to install. */
    READY,

    /** Could not be cloned (system app, unsupported, error). */
    FAILED,

    /** Skipped because the batch was cancelled. */
    SKIPPED
}

/** One app of a batch clone run. */
data class BatchItem(
    val packageName: String,
    val label: String,
    val state: BatchState = BatchState.PENDING,
    val clonePackageId: String = "",
    val cloneName: String = "",
    val apkPath: String = "",
    val message: String = ""
) {
    val isFinished: Boolean get() = state == BatchState.READY || state == BatchState.FAILED ||
        state == BatchState.SKIPPED
}

/** Overall progress of a batch run, used by the batch screen. */
data class BatchProgress(
    val items: List<BatchItem> = emptyList(),
    val isRunning: Boolean = false,
    val currentLabel: String = ""
) {
    val total: Int get() = items.size
    val done: Int get() = items.count { it.state == BatchState.READY }
    val failed: Int get() = items.count { it.state == BatchState.FAILED }
    val finished: Boolean get() = items.isNotEmpty() && items.all { it.isFinished }

    /** Clones of this run that are built and can be installed, in queue order. */
    val installable: List<BatchItem> get() = items.filter { it.state == BatchState.READY }
}
