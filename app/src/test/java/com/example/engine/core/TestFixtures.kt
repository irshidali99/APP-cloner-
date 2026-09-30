package com.example.engine.core

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds synthetic but format-accurate APK files (binary manifest + resource table + zip container) so the
 * cloning engine can be tested on the JVM. No Android device or SDK is required.
 *
 * The structures mirror what `aapt2` emits: a resource map chunk covering the attribute-name prefix of the
 * string pool, sorted attributes, a `RES_TABLE_PACKAGE_TYPE` with type/key pools and a `RES_TABLE_TYPE_TYPE`
 * per resource type.
 */
object TestFixtures {

    const val ORIGINAL_PACKAGE = "com.original.game"
    const val ORIGINAL_LABEL = "Original Game"
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    const val PROVIDER_AUTHORITY = "com.original.game.provider"
    const val ICON_PATH = "res/mipmap-hdpi-v4/ic_launcher.png"

    /** The kind of permission modern build tools add to every app; it must move with the package. */
    const val PERMISSION_NAME = "$ORIGINAL_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    /** A dangerous platform permission, used to test permission removal. */
    const val DANGEROUS_PERMISSION = "android.permission.CAMERA"

    private const val ATTR_NAME = 0x01010003
    private const val ATTR_LABEL = 0x01010001
    private const val ATTR_ICON = 0x01010002
    private const val ATTR_EXPORTED = 0x01010010
    private const val ATTR_AUTHORITIES = 0x01010018
    private const val ATTR_SHARED_USER_ID = 0x0101000b

    private const val TYPE_STRING = 0x03
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_INT_DEC = 0x10

    /** String pool of the fixture manifest, attribute names first so the resource map stays valid. */
    private val manifestStrings = listOf(
        "name", "label", "icon", "exported", "authorities", "package",
        "android", ANDROID_NAMESPACE,
        "manifest", "application", "provider", "activity",
        ORIGINAL_PACKAGE, ".DataProvider", PROVIDER_AUTHORITY, ".MainActivity", ORIGINAL_LABEL,
        "permission", "uses-permission", "sharedUserId", PERMISSION_NAME,
        "uses-sdk", "versionCode", "versionName", "minSdkVersion", "targetSdkVersion",
        "intent-filter", "action", "category", "priority",
        "android.intent.action.MAIN", "android.intent.category.LAUNCHER", DANGEROUS_PERMISSION
    )

    private fun stringIndex(value: String): Int = manifestStrings.indexOf(value)

    private fun stringsFor(splitName: String?): List<String> =
        if (splitName == null) manifestStrings else manifestStrings + listOf("split", splitName)

    /** A binary `AndroidManifest.xml` equivalent to a small real-world app. */
    fun manifest(file: File) = file.writeBytes(manifestBytes())

    /**
     * @param splitName when set, the manifest gets a `split` attribute and the APK is accepted as a
     *   configuration split of an app bundle.
     */
    fun manifestBytes(splitName: String? = null): ByteArray {
        val strings = stringsFor(splitName)
        fun index(value: String): Int = strings.indexOf(value).also {
            require(it >= 0) { "fixture string '$value' is missing" }
        }

        val writer = LeWriter(2048)
        val pool = buildStringPool(strings, utf8 = true)

        val resourceMap = intArrayOf(
            ATTR_NAME, ATTR_LABEL, ATTR_ICON, ATTR_EXPORTED, ATTR_AUTHORITIES, ATTR_SHARED_USER_ID
        )
        val resourceMapSize = CHUNK_HEADER_SIZE + resourceMap.size * 4

        // ---- nodes ---------------------------------------------------------------------------
        var bodySize = 0
        val nodes = ArrayList<ByteArray>()

        fun addNode(node: ByteArray) {
            nodes.add(node)
            bodySize += node.size
        }

        addNode(namespaceNode(start = true, "android", ANDROID_NAMESPACE))
        val manifestAttributes = ArrayList<ByteArray>()
        manifestAttributes.add(attribute(null, "package", TYPE_STRING, index(ORIGINAL_PACKAGE), strings))
        // a shared user id derived from the package, exactly like many real apps declare it
        manifestAttributes.add(
            attribute(ANDROID_NAMESPACE, "sharedUserId", TYPE_STRING, index(ORIGINAL_PACKAGE), strings)
        )
        if (splitName != null) {
            // the split attribute lives in the extended pool of this manifest
            manifestAttributes.add(attribute(null, "split", TYPE_STRING, index(splitName), strings))
        }
        addNode(startElement("manifest", manifestAttributes))
        addNode(
            startElement(
                "permission",
                listOf(
                    attribute(ANDROID_NAMESPACE, "name", TYPE_STRING, index(PERMISSION_NAME), strings)
                )
            )
        )
        addNode(endElement("permission"))
        addNode(
            startElement(
                "uses-sdk",
                listOf(
                    attribute(ANDROID_NAMESPACE, "minSdkVersion", TYPE_INT_DEC, 21),
                    attribute(ANDROID_NAMESPACE, "targetSdkVersion", TYPE_INT_DEC, 34)
                )
            )
        )
        addNode(endElement("uses-sdk"))
        addNode(
            startElement(
                "uses-permission",
                listOf(
                    attribute(ANDROID_NAMESPACE, "name", TYPE_STRING, index(PERMISSION_NAME), strings)
                )
            )
        )
        addNode(endElement("uses-permission"))
        addNode(
            startElement(
                "application",
                listOf(
                    attribute(ANDROID_NAMESPACE, "label", TYPE_STRING, index(ORIGINAL_LABEL)),
                    attribute(ANDROID_NAMESPACE, "icon", TYPE_REFERENCE, 0x7f010000)
                )
            )
        )
        addNode(
            startElement(
                "provider",
                listOf(
                    attribute(ANDROID_NAMESPACE, "name", TYPE_STRING, index(".DataProvider")),
                    attribute(ANDROID_NAMESPACE, "authorities", TYPE_STRING, index(PROVIDER_AUTHORITY)),
                    attribute(ANDROID_NAMESPACE, "exported", TYPE_INT_BOOLEAN, 0)
                )
            )
        )
        addNode(endElement("provider"))
        addNode(
            startElement(
                "activity",
                listOf(attribute(ANDROID_NAMESPACE, "name", TYPE_STRING, index(".MainActivity")))
            )
        )
        addNode(
            startElement(
                "intent-filter",
                listOf(
                    attribute(ANDROID_NAMESPACE, "label", TYPE_STRING, index(ORIGINAL_LABEL)),
                    attribute(ANDROID_NAMESPACE, "priority", TYPE_INT_DEC, 0)
                )
            )
        )
        addNode(
            startElement(
                "action",
                listOf(
                    attribute(ANDROID_NAMESPACE, "name", TYPE_STRING, index("android.intent.action.MAIN"))
                )
            )
        )
        addNode(endElement("action"))
        addNode(
            startElement(
                "category",
                listOf(
                    attribute(ANDROID_NAMESPACE, "name", TYPE_STRING, index("android.intent.category.LAUNCHER"))
                )
            )
        )
        addNode(endElement("category"))
        addNode(endElement("intent-filter"))
        addNode(endElement("activity"))
        addNode(endElement("application"))
        addNode(endElement("manifest"))
        addNode(namespaceNode(start = false, "android", ANDROID_NAMESPACE))

        val total = CHUNK_HEADER_SIZE + pool.size + resourceMapSize + bodySize
        writer.u16(ResType.XML)
        writer.u16(CHUNK_HEADER_SIZE)
        writer.u32(total)
        writer.bytes(pool)

        writer.u16(ResType.XML_RESOURCE_MAP)
        writer.u16(CHUNK_HEADER_SIZE)
        writer.u32(resourceMapSize)
        for (id in resourceMap) writer.u32(id)

        for (node in nodes) writer.bytes(node)
        return writer.toByteArray()
    }

    /** A `resources.arsc` with one `mipmap` resource (the launcher icon) and one string. */
    fun resourceTable(file: File) = file.writeBytes(resourceTableBytes())

    fun resourceTableBytes(): ByteArray {
        val valuePool = buildStringPool(listOf(ICON_PATH, "Original Game"), utf8 = true)
        val typePool = buildStringPool(listOf("mipmap", "string"), utf8 = true)
        val keyPool = buildStringPool(listOf("ic_launcher", "app_name"), utf8 = true)

        val packageHeader = LeWriter(288)
        packageHeader.u16(ResType.TABLE_PACKAGE)
        packageHeader.u16(288)
        val packageSizePosition = packageHeader.size
        packageHeader.u32(0) // size, patched below
        packageHeader.u32(0x7f) // package id
        // name[128]: 1 byte varint length + UTF-16 characters padded with zeros
        val nameBytes = ORIGINAL_PACKAGE.toByteArray(Charsets.UTF_16LE)
        packageHeader.u8(ORIGINAL_PACKAGE.length)
        packageHeader.bytes(nameBytes)
        packageHeader.zeros(256 - 1 - nameBytes.size)
        packageHeader.u32(288) // typeStrings offset (patched to the real offset below)
        packageHeader.u32(2) // lastPublicType
        packageHeader.u32(288 + typePool.size) // keyStrings offset
        packageHeader.u32(2) // lastPublicKey
        packageHeader.u32(0) // typeIdOffset (present since headerSize is 288)

        val packageBody = LeWriter(1024)
        packageBody.bytes(typePool)
        packageBody.bytes(keyPool)
        packageBody.bytes(typeChunk(typeId = 1, entryKey = 0, valueType = TYPE_STRING, value = 0))
        packageBody.bytes(typeChunk(typeId = 2, entryKey = 1, valueType = TYPE_STRING, value = 1))

        val packageBytes = concat(packageHeader.toByteArray(), packageBody.toByteArray())
        // patch the chunk size of the package
        writeU32(packageBytes, packageSizePosition, packageBytes.size.toLong())

        val tableSize = 12 + valuePool.size + packageBytes.size
        val tableHeader = LeWriter(12)
        tableHeader.u16(ResType.TABLE)
        tableHeader.u16(12)
        tableHeader.u32(tableSize)
        tableHeader.u32(1) // package count

        val table = concat(tableHeader.toByteArray(), valuePool, packageBytes)
        // valuePool starts right after the 12 byte table header
        return table
    }

    private fun typeChunk(typeId: Int, entryKey: Int, valueType: Int, value: Int): ByteArray {
        val entryCount = 1
        val configSize = 64
        val entryBytes = LeWriter(16)
        entryBytes.u16(8) // ResTable_entry.size
        entryBytes.u16(0) // flags
        entryBytes.u32(entryKey) // key index into the key pool
        entryBytes.u16(8) // Res_value.size
        entryBytes.u8(0) // res0
        entryBytes.u8(valueType)
        entryBytes.u32(value)
        val entry = entryBytes.toByteArray()

        val headerSize = 20 + configSize
        val entriesStart = headerSize + entryCount * 4
        val chunkSize = entriesStart + entry.size

        val writer = LeWriter(chunkSize)
        writer.u16(ResType.TABLE_TYPE)
        writer.u16(headerSize)
        writer.u32(chunkSize)
        writer.u8(typeId)
        writer.u8(0) // flags
        writer.u16(0) // reserved
        writer.u32(entryCount)
        writer.u32(entriesStart)
        writer.u32(configSize) // ResTable_config.size
        writer.zeros(configSize - 4)
        writer.u32(0) // offset of entry 0
        writer.bytes(entry)
        return writer.toByteArray()
    }

    /** Writes a complete, typical APK file for the fixture app. */
    fun apk(file: File): File {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.writeStored("AndroidManifest.xml", manifestBytes())
            zip.writeStored("resources.arsc", resourceTableBytes())
            zip.writeDeflated("classes.dex", ByteArray(2048) { (it % 97).toByte() })
            zip.writeStored(ICON_PATH, ByteArray(512) { 0x11 })
            zip.writeStored("res/mipmap-hdpi-v4/ic_launcher_round.png", ByteArray(256) { 0x22 })
            zip.writeStored("META-INF/OLD.SF", "old signature".toByteArray())
            zip.writeStored("META-INF/OLD.RSA", "old certificate".toByteArray())
        }
        return file
    }

    /** A typical configuration split of the fixture app (manifest + resource table + payload). */
    fun splitApk(file: File, splitName: String): File {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.writeStored("AndroidManifest.xml", manifestBytes(splitName = splitName))
            zip.writeStored("resources.arsc", resourceTableBytes())
            zip.writeDeflated("classes.dex", ByteArray(1024) { (it % 89).toByte() })
            zip.writeStored("assets/$splitName.bin", ByteArray(128) { 0x44 })
        }
        return file
    }

    // ---------------------------------------------------------------------------------------------

    private fun ZipOutputStream.writeStored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.writeDeflated(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun buildStringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val encoded = strings.map { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            val writer = LeWriter(bytes.size + 4)
            writer.u8(value.length)
            writer.u8(bytes.size)
            writer.bytes(bytes)
            writer.u8(0)
            writer.toByteArray()
        }
        val count = encoded.size
        val stringsStart = StringPool.HEADER_SIZE + count * 4
        var dataSize = 0
        for (item in encoded) dataSize += item.size
        val total = alignUp(stringsStart + dataSize, 4)

        val writer = LeWriter(total)
        writer.u16(ResType.STRING_POOL)
        writer.u16(StringPool.HEADER_SIZE)
        writer.u32(total)
        writer.u32(count)
        writer.u32(0)
        writer.u32(if (utf8) StringPoolFlags.UTF8 else 0)
        writer.u32(stringsStart)
        writer.u32(0)
        var offset = 0
        for (item in encoded) {
            writer.u32(offset)
            offset += item.size
        }
        for (item in encoded) writer.bytes(item)
        writer.padToAlignment(4)
        return writer.toByteArray()
    }

    private fun namespaceNode(start: Boolean, prefix: String, uri: String): ByteArray {
        val writer = LeWriter(24)
        writer.u16(if (start) ResType.XML_START_NAMESPACE else ResType.XML_END_NAMESPACE)
        writer.u16(16)
        writer.u32(24)
        writer.u32(-1) // line
        writer.u32(-1) // comment
        writer.u32(stringIndex(prefix))
        writer.u32(stringIndex(uri))
        return writer.toByteArray()
    }

    private fun attribute(
        namespace: String?,
        name: String,
        valueType: Int,
        value: Int,
        strings: List<String> = manifestStrings
    ): ByteArray {
        fun poolIndex(value: String): Int = strings.indexOf(value)
        val writer = LeWriter(20)
        writer.u32(if (namespace == null) -1 else poolIndex(namespace))
        writer.u32(poolIndex(name))
        writer.u32(if (valueType == TYPE_STRING) value else -1)
        writer.u16(8)
        writer.u8(0)
        writer.u8(valueType)
        writer.u32(value)
        return writer.toByteArray()
    }

    private fun startElement(name: String, attributes: List<ByteArray>): ByteArray {
        val size = 36 + attributes.size * 20
        val writer = LeWriter(size)
        writer.u16(ResType.XML_START_ELEMENT)
        writer.u16(16)
        writer.u32(size)
        writer.u32(-1)
        writer.u32(-1)
        writer.u32(-1) // namespace
        writer.u32(stringIndex(name))
        writer.u16(20)
        writer.u16(20)
        writer.u16(attributes.size)
        writer.u16(0) // id index
        writer.u16(0) // class index
        writer.u16(0) // style index
        for (item in attributes) writer.bytes(item)
        return writer.toByteArray()
    }

    private fun endElement(name: String): ByteArray {
        val writer = LeWriter(24)
        writer.u16(ResType.XML_END_ELEMENT)
        writer.u16(16)
        writer.u32(24)
        writer.u32(-1)
        writer.u32(-1)
        writer.u32(-1)
        writer.u32(stringIndex(name))
        return writer.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (part in parts) total += part.size
        val out = ByteArray(total)
        var position = 0
        for (part in parts) {
            part.copyInto(out, position)
            position += part.size
        }
        return out
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte()
        }
    }
}
