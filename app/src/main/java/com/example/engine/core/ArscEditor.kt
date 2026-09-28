package com.example.engine.core

/**
 * Editor for `resources.arsc`, the compiled resource table.
 *
 * The table is a tree of chunks:
 * ```
 * RES_TABLE_TYPE
 *  ├─ RES_STRING_POOL_TYPE                     (global value pool, indexed by *.value.string)
 *  └─ RES_TABLE_PACKAGE_TYPE  (one per package: 0x7f)
 *      ├─ (optional) RES_TABLE_TYPE_SPEC_TYPE  (only when allowNewResourceTypes = true)
 *      ├─ RES_TABLE_TYPE_TYPE  (one per type/configuration, holds the entries)
 *      └─ (optional) RES_TABLE_LIBRARY_TYPE
 * ```
 *
 * Renaming the package means replacing the package name string inside the package chunk header. The chunk
 * header stores the string *inline* (a UTF-16 array with its own 1-2 byte varint length prefix) and is
 * padded to a four byte boundary, so the replacement is only possible when the new name fits into the
 * existing header. [packageNameCapacity] reports how many UTF-16 code units are available.
 *
 * Entries are copied verbatim, which keeps every compiled resource id (`0xPPTTEEEE`) valid - rewriting
 * resource references that carry the package byte is therefore *not* required.
 */
class ArscEditor private constructor(
    val data: ByteArray,
    val valuePool: StringPool,
    val packages: List<PackageChunk>
) {
    /** A `RES_TABLE_PACKAGE_TYPE` chunk. */
    class PackageChunk(
        val offset: Int,
        val id: Int,
        val nameUtf16: String,
        val headerSize: Int
    ) {
        /**
         * The header reserves a fixed `name[128]` UTF-16 field, followed by padding up to `headerSize`.
         * The varint length prefix and the NUL terminator have to fit into that padding as well.
         */
        val nameCharCapacity: Int
            get() = minOf(128, (headerSize - CHUNK_HEADER_SIZE - 4 - 4 - 2) / 2)

        /** Excludes the trailing NUL terminator from the required space. */
        fun canHold(newName: String): Boolean = newName.length <= nameCharCapacity
    }

    /** Package id of the first resizable package, usually `0x7f`. */
    val primaryPackage: PackageChunk?
        get() = packages.firstOrNull { it.nameUtf16.isNotEmpty() }

    /**
     * Resolves a compiled resource id (`0xPPTTEEEE`, the form stored in `android:icon="@mipmap/ic_launcher"`)
     * to the type and entry name of that resource, which in turn maps to the file inside `res/`.
     */
    fun findResource(resourceId: Int): ResourceName? {
        val packageId = (resourceId ushr 24) and 0xFF
        val typeId = (resourceId ushr 16) and 0xFF
        val entryId = resourceId and 0xFFFF
        if (packageId == 0 || typeId == 0) return null

        val packageChunk = packages.firstOrNull { it.id == packageId } ?: return null
        val packageEnd = packageChunk.offset + data.chunkSize(packageChunk.offset)

        val headerSize = data.chunkHeaderSize(packageChunk.offset)
        val typeStringsOffset = data.u32(packageChunk.offset + headerSize - 20)
        val keyStringsOffset = data.u32(packageChunk.offset + headerSize - 12)
        val typeNames = StringPool.parse(data, packageChunk.offset + typeStringsOffset)
        val keyNames = StringPool.parse(data, packageChunk.offset + keyStringsOffset)
        val typeName = typeNames.get(typeId - 1) ?: return null

        var position = packageChunk.offset + headerSize
        while (position + CHUNK_HEADER_SIZE <= packageEnd) {
            val chunkType = data.u16(position)
            val chunkSize = data.chunkSize(position)
            if (chunkSize < CHUNK_HEADER_SIZE) break

            if (chunkType == ResType.TABLE_TYPE) {
                val id = data.u8(position + 8)
                val entryCount = data.u32(position + 12)
                val entriesStart = data.u32(position + 16)
                if (id == typeId && entryId < entryCount) {
                    val offsetPosition = position + data.chunkHeaderSize(position) + entryId * 4
                    if (offsetPosition + 4 <= packageEnd) {
                        val entryOffset = data.u32(offsetPosition)
                        // 0xFFFFFFFF marks an entry that does not exist for this configuration, in that
                        // case other type chunks (other density/config variants) are still consulted.
                        if (entryOffset != -1) {
                            val resolved = keyNames.get(entryId) ?: return null
                            return ResourceName(typeId, entryId, typeName, resolved)
                        }
                    }
                }
            }
            position += chunkSize
        }
        return null
    }

    /**
     * Number of UTF-16 code units the package name field can hold. Names longer than this cannot be written
     * without moving the following chunks, which would invalidate every offset stored inside the table.
     */
    fun packageNameCapacity(packageChunk: PackageChunk = primaryPackage
        ?: throw IllegalStateException("resource table has no package chunk")): Int = packageChunk.nameCharCapacity

    /**
     * Returns a copy of `resources.arsc` with the name of [packageChunk] replaced by [newName].
     * Throws when the new name does not fit into the existing header.
     */
    fun withPackageName(
        newName: String,
        packageChunk: PackageChunk = primaryPackage
            ?: throw IllegalStateException("resource table has no package chunk")
    ): ByteArray {
        require(newName.isNotEmpty()) { "new package name must not be empty" }
        require(!newName.contains('\u0000')) { "package name must not contain NUL" }
        require(packageChunk.canHold(newName)) {
            "package name '$newName' needs ${newName.length} chars but only " +
                "${packageChunk.nameCharCapacity} fit into the resource table header"
        }

        val output = data.copyOf()
        // Header layout: type/headerSize/size (8) + id (4) + name[128] (256) + typeStrings (4) +
        // lastPublicType (4) + keyStrings (4) + lastPublicKey (4) [+ typeIdOffset (4)].
        val nameStart = packageChunk.offset + CHUNK_HEADER_SIZE + 4
        val nameFieldSize = 256

        // Zero the whole field first so no trailing characters of the old name survive.
        java.util.Arrays.fill(output, nameStart, nameStart + nameFieldSize, 0)

        var position = nameStart
        val length = newName.length
        if (length > 0xFF) {
            output[position++] = ((length ushr 8) or 0x80).toByte()
            output[position++] = (length and 0xFF).toByte()
        } else {
            output[position++] = length.toByte()
        }
        val encoded = newName.toByteArray(Charsets.UTF_16LE)
        System.arraycopy(encoded, 0, output, position, encoded.size)

        // Keep the chunk size untouched: the header itself did not grow.
        return output
    }

    companion object {
        /** Parses a `resources.arsc` file. */
        fun parse(bytes: ByteArray): ArscEditor {
            require(bytes.size >= CHUNK_HEADER_SIZE) { "file is too small to be a resource table" }
            require(bytes.u16(0) == ResType.TABLE) { "not a resource table" }

            val tableSize = minOf(bytes.u32(4), bytes.size)
            val headerSize = bytes.chunkHeaderSize(0)

            // `ResTable_header` is only 12 bytes (chunk header + packageCount); the global value string
            // pool is the chunk that directly follows it.
            val valuePool = StringPool.parse(bytes, headerSize)

            val packages = ArrayList<PackageChunk>()
            var position = headerSize
            while (position + CHUNK_HEADER_SIZE <= tableSize) {
                val type = bytes.u16(position)
                val size = bytes.chunkSize(position)
                if (size < CHUNK_HEADER_SIZE || position + size > bytes.size) break

                if (type == ResType.TABLE_PACKAGE) {
                    val id = bytes.u32(position + 8)
                    val headerSize = bytes.chunkHeaderSize(position)
                    val name = readInlineUtf16(bytes, position + CHUNK_HEADER_SIZE + 4, 128)
                    packages.add(PackageChunk(position, id, name, headerSize))
                }
                position += size
            }

            return ArscEditor(bytes, valuePool, packages)
        }

        /** Reads the inline UTF-16 package name: 1-2 byte varint length, code units, NUL terminator. */
        private fun readInlineUtf16(bytes: ByteArray, start: Int, maxChars: Int): String {
            var position = start
            var length = bytes.u8(position)
            position += 1
            if (length and 0x80 != 0) {
                length = ((length and 0x7F) shl 8) or bytes.u8(position)
                position += 1
            }
            if (length <= 0 || length > maxChars) return ""
            return String(bytes, position, length * 2, Charsets.UTF_16LE)
        }
    }
}
