package com.example.engine.core

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
    val iconPng: ByteArray? = null
)

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
    val warnings: List<String>
)

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
        sectionDigestMode: JarV1Signer.SectionDigestMode = JarV1Signer.SectionDigestMode.WITHOUT_TRAILING_BLANK_LINE
    ): CloneReport {
        val warnings = ArrayList<String>()
        val archive = ZipArchive(request.sourceApk)
        try {
            val manifestEntry = archive.findEntry(MANIFEST)
                ?: throw IllegalArgumentException("source APK has no AndroidManifest.xml")
            val resourceEntry = archive.findEntry(RESOURCES)
                ?: throw IllegalArgumentException("source APK has no resources.arsc")

            // ---- resources.arsc ------------------------------------------------------------------
            val resourceBytes = archive.readEntry(resourceEntry)
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
            val rewrittenResources = arsc.withPackageName(request.newPackage)

            // ---- AndroidManifest.xml -------------------------------------------------------------
            val manifestBytes = archive.readEntry(manifestEntry)
            val editor = AxmlEditor.parse(manifestBytes)
            val rewriteReport = ManifestRewriter.rewrite(editor, request.newPackage, request.newLabel)
            if (rewriteReport.foreignAuthorities.isNotEmpty()) {
                warnings.add(
                    "providers declaring foreign authorities were left unchanged: " +
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
            val resourceName = iconAttributeValue?.let { arsc.findResource(it) }
            if (request.iconPng != null) {
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

                    entry.name == RESOURCES -> {
                        // must be stored uncompressed and 4 byte aligned since API 30
                        writer.writeEntry(
                            RESOURCES, rewrittenResources, alignment = 4, compress = false,
                            dosTime = entry.dosTime, dosDate = entry.dosDate
                        )
                        entryDigests[RESOURCES] = digestOf(digest, rewrittenResources)
                        entriesWritten++
                    }

                    request.iconPng != null && resourceName != null &&
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

            // ---- v1 signature --------------------------------------------------------------------
            val signer = JarV1Signer(certificate, privateKey, sectionDigestMode = sectionDigestMode)
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
                resourceTableRewritten = true,
                iconEntriesReplaced = iconTargets,
                foreignAuthorities = rewriteReport.foreignAuthorities,
                warnings = warnings
            )
        } finally {
            archive.close()
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
