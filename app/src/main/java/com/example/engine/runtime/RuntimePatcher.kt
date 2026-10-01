package com.example.engine.runtime

import android.content.Context
import com.example.engine.core.RuntimeInjection
import com.example.engine.core.ZipArchive
import com.example.model.RuntimeOptions
import java.io.File

/**
 * Hands the prebuilt runtime patch dex to the APK transformer.
 *
 * The dex is compiled by CI from `runtime-src/` and shipped as `assets/runtime/patch.dex`, so a clone
 * never needs a compiler on the phone. Because the same dex is used for every clone, everything that
 * differs between clones (passcode hash, feature flags) travels in the clone's manifest meta-data.
 */
object RuntimePatcher {

    const val DEX_ASSET = "runtime/patch.dex"

    /** The patch dex shipped with this build, or `null` when the asset is missing. */
    fun readDex(context: Context): ByteArray? = runCatching {
        context.assets.open(DEX_ASSET).use { stream -> stream.readBytes() }
    }.getOrNull()

    /** A dex file starts with `dex\n` and a 112 byte header. */
    fun isDex(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size > 112 && bytes.decodeToString(0, 4) == "dex\n"

    /**
     * Name of the dex entry to add: an app that is already split into several dex files keeps them, the
     * patch takes the next free `classesN.dex` (the platform loads every one of them).
     */
    fun nextDexEntryName(apk: File): String {
        val used = runCatching {
            ZipArchive(apk).use { archive -> archive.entries.map { entry -> entry.name }.toSet() }
        }.getOrDefault(emptySet())
        var index = 2
        while (used.contains("classes$index.dex")) index++
        return "classes$index.dex"
    }

    /**
     * Builds the injection for [sourceApk], or `null` when the user did not ask for runtime features or
     * this build has no patch dex.
     */
    fun prepare(context: Context, sourceApk: File, options: RuntimeOptions): RuntimeInjection? {
        if (options.isEmpty) return null
        val dex = readDex(context)
        if (!isDex(dex)) return null
        return RuntimeInjection(
            dexEntryName = nextDexEntryName(sourceApk),
            dexBytes = dex!!,
            options = options
        )
    }

    /** True when this build can inject runtime features at all. */
    fun isAvailable(context: Context): Boolean = isDex(readDex(context))
}
