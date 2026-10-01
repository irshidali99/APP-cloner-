package com.example.engine.core

import com.example.model.CloneMods
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/** A resource resolved from `resources.arsc` down to its type and entry name. */
data class ResourceName(
    val typeId: Int,
    val entryId: Int,
    val typeName: String,
    val entryName: String
) {
    /** Matches zip entries such as `res/mipmap-hdpi-v4/ic_launcher.png`. */
    fun matchesZipEntry(path: String): Boolean {
        if (!path.startsWith("res/")) return false
        val slash = path.indexOf('/', 4)
        if (slash < 0) return false
        val directory = path.substring(4, slash)
        val file = path.substring(slash + 1)
        val base = file.substringBeforeLast('.')
        if (base != entryName) return false
        return directory == typeName || directory.startsWith("$typeName-")
    }
}

/** What the clone should look like. */
data class CloneRequest(
    val sourceApk: File,
    val outputApk: File,
    val newPackage: String,
    val newLabel: String? = null,
    /**
     * Optional launcher icon to inject, already rendered by the platform layer (for example with a badge).
     * The resource it replaces is resolved from the manifest's `android:icon` attribute.
     */
    val iconPng: ByteArray? = null,
    /** Optional clone mods (launcher, privacy, storage, version, permissions). */
    val mods: CloneMods = CloneMods(),
    /**
     * Optional runtime patch (phase 3): an extra dex file with the injected code plus the manifest
     * components that make the platform load it.
     */
    val runtime: RuntimeInjection? = null
)

/**
 * A ready to inject runtime patch: [dexBytes] is written as [dexEntryName] (`classesN.dex`) and the
 * manifest gets a bootstrap provider plus the configuration all features read.
 *
 * The dex is compiled once by CI (`scripts/build-runtime-dex.sh`) and ships inside App Cloner, so cloning
 * on the phone never needs a compiler.
 */
class RuntimeInjection(
    val dexEntryName: String,
    val dexBytes: ByteArray,
    val options: com.example.model.RuntimeOptions
)

/** Which part of an app bundle an APK is. */
enum class CloneRole {
    /** The `base.apk`: carries the launcher entry, the label, the providers and the icon. */
    BASE,

    /** A configuration or feature split: only the package name has to change. */
    SPLIT
}

/** Everything the caller (and the user) should know about a finished transformation. */
data class CloneReport(
    val originalPackage: String,
    val newPackage: String,
    val originalLabel: String?,
    val newLabel: String?,
    val entriesWritten: Int,
    val manifestRewritten: Boolean,
    val resourceTableRewritten: Boolean,
    val iconEntriesReplaced: List<String>,
    val foreignAuthorities: List<String>,
    val warnings: List<String>,
    val role: CloneRole = CloneRole.BASE,
    /** Value of the `split` attribute for split APKs, `null` for the base. */
    val splitName: String? = null,
    /** Permissions owned by the original app that were re-targeted to the new package name. */
    val renamedPermissions: List<String> = emptyList(),
    /** Clone mods that were applied to this part, in human readable form. */
    val appliedMods: List<String> = emptyList(),
    /** Name of the injected runtime dex entry (`classes2.dex`), `null` when nothing was injected. */
    val runtimeDexEntry: String? = null
)

/** An app bundle to clone: the base APK plus every configuration/feature split. */
data class CloneBundleRequest(
    val baseApk: File,
    val splitApks: List<File>,
    val outputDirectory: File,
    val newPackage: String,
    val newLabel: String? = null,
    val iconPng: ByteArray? = null,
    val mods: CloneMods = CloneMods(),
    /** The runtime patch is injected into the base APK: a split holds no code. */
    val runtime: RuntimeInjection? = null
)

/** Result of cloning a whole bundle. */
data class CloneBundleReport(
    val base: CloneReport,
    val splits: List<CloneReport>,
    val baseFile: File,
    val splitFiles: List<File>,
    val warnings: List<String>
) {
    /** Every APK that has to be installed together, base first. */
    val apkFiles: List<File> get() = listOf(baseFile) + splitFiles

    val isBundle: Boolean get() = splitFiles.isNotEmpty()
}

/**
 * Turns an installed application's APK into an independent clone.
 *
 * This is the step the original implementation was missing completely: changing the package identity of an
 * app requires rewriting the compiled resources, not just re-zipping the archive.
 *  1. `AndroidManifest.xml` (binary XML) gets a new `package`, rewritten provider authorities, qualified
 *     component class names and an optional new label.
 *  2. `resources.arsc` gets the new package name in its package chunk header.
 *  3. Old signatures are dropped, `resources.arsc` is stored uncompressed and aligned, and the launcher icon
 *     can be replaced with a badged bitmap.
 *  4. v1 signature files are appended so the archive is a validly signed JAR before the v2 block is added.
 *
 * All copying is streamed; only the manifest, the resource table and the icon are held in memory.
 */
object ApkTransformer {

    const val MANIFEST = "AndroidManifest.xml"
    const val RESOURCES = "resources.arsc"

    private const val ANDROID_ICON = "icon"
    private const val MAX_ARSC_BYTES = 64 * 1024 * 1024

    fun transform(
        request: CloneRequest,
        certificate: java.security.cert.X509Certificate,
        privateKey: java.security.PrivateKey,
        role: CloneRole = CloneRole.BASE
    ): CloneReport {
        val warnings = ArrayList<String>()
        val archive = ZipArchive(request.sourceApk)
        try {
            val manifestEntry = archive.findEntry(MANIFEST)
                ?: throw IllegalArgumentException("source APK has no AndroidManifest.xml")
            val resourceEntry = archive.findEntry(RESOURCES)
            if (resourceEntry == null && role == CloneRole.BASE) {
                throw IllegalArgumentException("source APK has no resources.arsc")
            }
            if (resourceEntry == null) {
                warnings.add("split '${request.sourceApk.name}' has no resource table, only its manifest was rewritten")
            }

            // ---- resources.arsc ------------------------------------------------------------------
            val resourceBytes = resourceEntry?.let { archive.readEntry(it) }
            var rewrittenResources: ByteArray? = null
            if (resourceBytes != null) {
                require(resourceBytes.size <= MAX_ARSC_BYTES) { "resources.arsc is unexpectedly large" }
                val arsc = ArscEditor.parse(resourceBytes)
                val packageChunk = arsc.primaryPackage
                    ?: throw IllegalArgumentException("resource table has no package chunk")
                if (!packageChunk.canHold(request.newPackage)) {
                    throw IllegalArgumentException(
                        "package name '${request.newPackage}' does not fit into the resource table " +
                            "(maximum ${packageChunk.nameCharCapacity} characters). Choose a shorter name."
                    )
                }
                rewrittenResources = arsc.withPackageName(request.newPackage)
            }

            // ---- AndroidManifest.xml -------------------------------------------------------------
            val manifestBytes = archive.readEntry(manifestEntry)
            val editor = AxmlEditor.parse(manifestBytes)
            val rewriteReport = ManifestRewriter.rewrite(
                editor = editor,
                newPackage = request.newPackage,
                cloneLabel = if (role == CloneRole.BASE) request.newLabel else null,
                retargetComponents = role == CloneRole.BASE
            )
            // Clone mods are applied after the identity rewrite: they only add or change manifest
            // attributes, so the two steps cannot interfere with each other.
            val appliedMods = ManifestPatcher.apply(
                editor = editor,
                mods = request.mods,
                isBasePart = role == CloneRole.BASE
            ).toMutableList()

            // The runtime patch registers its own components (bootstrap provider, lock screen).
            if (role == CloneRole.BASE && request.runtime != null) {
                appliedMods += RuntimeRegistration.apply(
                    editor = editor,
                    options = request.runtime.options,
                    clonePackage = request.newPackage
                )
            }

            if (rewriteReport.foreignAuthorities.isNotEmpty()) {
                warnings.add(
                    "provider authorities outside the package were moved into the clone's namespace " +
                        "(an authority is unique per device): " +
                        rewriteReport.foreignAuthorities.joinToString()
                )
            }

            // ---- launcher icon -------------------------------------------------------------------
            val iconTargets = ArrayList<String>()
            val iconAttributeValue = run {
                val iconAttribute = editor.startElements()
                    .firstOrNull { editor.elementName(it) == "application" }
                    ?.let { application ->
                        editor.findAttribute(application, ManifestRules.ANDROID_NAMESPACE, ANDROID_ICON)
                    }
                iconAttribute?.takeIf { it.valueType == ResValueType.REFERENCE }?.valueData
            }
            val resourceName = if (role == CloneRole.BASE && resourceBytes != null) {
                iconAttributeValue?.let { ArscEditor.parse(resourceBytes).findResource(it) }
            } else {
                null
            }
            if (request.iconPng != null && role == CloneRole.BASE) {
                if (resourceName == null) {
                    warnings.add("launcher icon could not be resolved, the badge was not applied")
                } else if (resourceName.typeName !in setOf("mipmap", "drawable")) {
                    warnings.add("launcher icon type '${resourceName.typeName}' is not a bitmap, badge skipped")
                }
            }

            val newManifestBytes = editor.toByteArray()

            // ---- write the archive ---------------------------------------------------------------
            val outputStream = FileOutputStream(request.outputApk)
            val writer = CloneZipWriter(outputStream)
            val digest = MessageDigest.getInstance("SHA-256")
            val entryDigests = LinkedHashMap<String, ByteArray>()
            var entriesWritten = 0

            val sourceTimestamp = archive.entries.maxByOrNull { it.dosTime } ?: archive.entries.first()
            writer.setDefaultTimestamp(sourceTimestamp.dosTime, sourceTimestamp.dosDate)

            for (entry in archive.entries) {
                if (entry.name.startsWith("META-INF/")) continue // old signatures are dropped

                when {
                    entry.name == MANIFEST -> {
                        writer.writeEntry(
                            MANIFEST, newManifestBytes, alignment = 4, compress = true,
                            dosTime = entry.dosTime, dosDate = entry.dosDate
                        )
                        entryDigests[MANIFEST] = digestOf(digest, newManifestBytes)
                        entriesWritten++
                    }

                    entry.name == RESOURCES && rewrittenResources != null -> {
                        // must be stored uncompressed and 4 byte aligned since API 30
                        writer.writeEntry(
                            RESOURCES, rewrittenResources, alignment = 4, compress = false,
                            dosTime = entry.dosTime, dosDate = entry.dosDate
                        )
                        entryDigests[RESOURCES] = digestOf(digest, rewrittenResources)
                        entriesWritten++
                    }

                    request.iconPng != null && role == CloneRole.BASE && resourceName != null &&
                        !entry.name.endsWith(".xml", ignoreCase = true) &&
                        resourceName.matchesZipEntry(entry.name) -> {
                        writer.writeEntry(
                            entry.name, request.iconPng, alignment = 4, compress = false,
                            dosTime = entry.dosTime, dosDate = entry.dosDate
                        )
                        entryDigests[entry.name] = digestOf(digest, request.iconPng)
                        iconTargets.add(entry.name)
                        entriesWritten++
                    }

                    else -> {
                        digest.reset()
                        writer.copyEntry(archive, entry, alignmentFor(entry), digest)
                        entryDigests[entry.name] = digest.digest()
                        entriesWritten++
                    }
                }
            }

            // ---- injected runtime patch ----------------------------------------------------------
            var runtimeDexEntry: String? = null
            val injection = request.runtime
            if (injection != null && role == CloneRole.BASE && injection.dexBytes.isNotEmpty()) {
                writer.writeEntry(
                    injection.dexEntryName, injection.dexBytes, alignment = 4, compress = true,
                    dosTime = sourceTimestamp.dosTime, dosDate = sourceTimestamp.dosDate
                )
                entryDigests[injection.dexEntryName] = digestOf(digest, injection.dexBytes)
                runtimeDexEntry = injection.dexEntryName
                entriesWritten++
            }

            // ---- v1 signature --------------------------------------------------------------------
            val signer = JarV1Signer(certificate, privateKey)
            val signed = signer.sign(entryDigests, excluded = emptySet())
            for ((name, bytes) in signed.files) {
                writer.writeStoredEntry(name, bytes, alignment = 4)
            }

            writer.finish()
            outputStream.close()

            return CloneReport(
                originalPackage = rewriteReport.originalPackage,
                newPackage = request.newPackage,
                originalLabel = null,
                newLabel = rewriteReport.applicationLabelChanged,
                entriesWritten = entriesWritten,
                manifestRewritten = true,
                resourceTableRewritten = rewrittenResources != null,
                iconEntriesReplaced = iconTargets,
                foreignAuthorities = rewriteReport.foreignAuthorities,
                warnings = warnings,
                role = role,
                splitName = readSplitName(editor),
                renamedPermissions = rewriteReport.renamedPermissions,
                appliedMods = appliedMods,
                runtimeDexEntry = runtimeDexEntry
            )
        } finally {
            archive.close()
        }
    }

    /**
     * Clones an app that is shipped as an app bundle: the base APK plus every split.
     *
     * Every part gets the new package name injected into its manifest and its resource table, every part is
     * signed with the same certificate, and the parts are written next to each other so the installer can
     * hand them to Android as one multi APK session (exactly how the Play Store installs split apps).
     */
    fun transformBundle(
        request: CloneBundleRequest,
        certificate: java.security.cert.X509Certificate,
        privateKey: java.security.PrivateKey
    ): CloneBundleReport {
        val warnings = ArrayList<String>()
        require(request.newPackage.isNotBlank()) { "new package name must not be blank" }
        request.outputDirectory.mkdirs()

        val baseOutput = File(request.outputDirectory, request.baseApk.name)
        val baseReport = transform(
            request = CloneRequest(
                sourceApk = request.baseApk,
                outputApk = baseOutput,
                newPackage = request.newPackage,
                newLabel = request.newLabel,
                iconPng = request.iconPng,
                mods = request.mods,
                runtime = request.runtime
            ),
            certificate = certificate,
            privateKey = privateKey,
            role = CloneRole.BASE
        )
        warnings.addAll(baseReport.warnings)

        val splitReports = ArrayList<CloneReport>()
        val splitFiles = ArrayList<File>()
        for (split in request.splitApks) {
            if (split.name == request.baseApk.name) {
                warnings.add("split '${split.name}' has the same file name as the base and was skipped")
                continue
            }
            val report = try {
                transform(
                    request = CloneRequest(
                        sourceApk = split,
                        outputApk = File(request.outputDirectory, split.name),
                        newPackage = request.newPackage,
                        newLabel = null,
                        iconPng = null,
                        mods = request.mods
                    ),
                    certificate = certificate,
                    privateKey = privateKey,
                    role = CloneRole.SPLIT
                )
            } catch (error: Exception) {
                // A split that cannot be re-targeted must not silently disappear: without it the clone is
                // incomplete, so the whole bundle fails with a message naming the split.
                throw IllegalStateException(
                    "split '${split.name}' could not be cloned: ${error.message ?: error::class.simpleName}",
                    error
                )
            }
            splitReports.add(report)
            splitFiles.add(File(request.outputDirectory, split.name))
            warnings.addAll(report.warnings)
        }

        return CloneBundleReport(
            base = baseReport,
            splits = splitReports,
            baseFile = baseOutput,
            splitFiles = splitFiles,
            warnings = warnings
        )
    }

    /** Reads the `split` attribute of an APK's manifest, `null` when the APK is not a split. */
    fun readSplitName(editor: AxmlEditor): String? {
        val root = editor.startElements().firstOrNull { editor.elementName(it) == "manifest" } ?: return null
        val attribute = editor.findAttribute(root, null, "split") ?: return null
        return editor.string(attribute.rawValue)?.takeIf { it.isNotBlank() }
    }

    /** Reads the `split` attribute from an APK file. */
    fun readSplitName(apk: File): String? {
        ZipArchive(apk).use { archive ->
            val manifestEntry = archive.findEntry(MANIFEST) ?: return null
            return readSplitName(AxmlEditor.parse(archive.readEntry(manifestEntry)))
        }
    }

    /** Alignment required for an entry that is copied verbatim. */
    private fun alignmentFor(entry: ZipEntryRecord): Int = when {
        entry.name == RESOURCES -> 4
        !entry.isStored -> 1 // compressed data needs no alignment
        entry.name.endsWith(".so") -> 16 * 1024 // native libraries must be page aligned when stored
        else -> 4
    }

    private fun digestOf(digest: MessageDigest, bytes: ByteArray): ByteArray {
        digest.reset()
        return digest.digest(bytes)
    }

    /** Reports the package name stored inside an APK's manifest and resource table. */
    fun readPackageIdentity(apk: File): Pair<String?, String?> {
        ZipArchive(apk).use { archive ->
            val manifestPackage = archive.findEntry(MANIFEST)?.let { entry ->
                val editor = AxmlEditor.parse(archive.readEntry(entry))
                val manifest = editor.startElements().firstOrNull { editor.elementName(it) == "manifest" }
                manifest?.let { editor.findAttribute(it, null, "package") }
                    ?.let { editor.string(it.rawValue) }
            }
            val tablePackage = archive.findEntry(RESOURCES)?.let { entry ->
                ArscEditor.parse(archive.readEntry(entry)).primaryPackage?.nameUtf16
            }
            return manifestPackage to tablePackage
        }
    }
}

/** Inflater based helper used while copying entries verbatim. */
internal object Inflaters {
    fun inflate(raw: ByteArray, uncompressedSize: Long): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(raw)
        val out = java.io.ByteArrayOutputStream(uncompressedSize.toInt().coerceAtLeast(16))
        val buffer = ByteArray(ZipFormat.COPY_BUFFER)
        while (!inflater.finished()) {
            val produced = inflater.inflate(buffer)
            if (produced == 0 && inflater.needsInput()) break
            out.write(buffer, 0, produced)
        }
        inflater.end()
        return out.toByteArray()
    }

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
