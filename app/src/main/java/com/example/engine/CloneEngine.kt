package com.example.engine

import android.content.Context
import com.example.model.CloneConfig
import com.example.model.CompatibilityReport
import com.example.model.InstalledApp
import com.example.model.PipelineProgress
import java.io.File

/**
 * Isolated interface for APK analysis, compatibility checking, and cloning execution.
 */
interface CloneEngine {
    /**
     * Evaluates whether an installed application can be safely cloned into an independent APK.
     */
    fun checkCompatibility(app: InstalledApp): CompatibilityReport

    /**
     * Runs the multi-stage clone creation pipeline.
     */
    suspend fun executeClonePipeline(
        context: Context,
        sourceApp: InstalledApp,
        config: CloneConfig,
        onProgress: (PipelineProgress) -> Unit
    ): Result<File>
}
