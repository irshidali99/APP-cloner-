package com.example.engine.core

/**
 * Reader/writer for Android's `ResStringPool` chunk, the string storage used by both `resources.arsc`
 * and binary XML files.
 *
 * Two encodings exist and both are supported:
 *  - UTF-16: `uint16 charCount` (with a `0x8000` continuation flag for very long strings), then UTF-16LE data.
 *  - UTF-8 : a 1-2 byte varint character count, a 1-2 byte varint byte count, then UTF-8 data.
 * Both are NUL terminated.
 *
 * [build] always emits a well formed pool chunk padded to a 4 byte boundary. The SORTED flag is dropped
 * because callers mutate the string list; the UTF-8 flag of the source pool is preserved so the output
 * stays as close to the original as possible.
 */
class StringPool(
    val strings: MutableList<String>,
    val utf8: Boolean,
    val sorted: Boolean
) {
    /** Index of [value] or -1 when absent. */
    fun indexOf(value: String): Int = strings.indexOf(value)

    /** Returns the index of [value], appending it when it is not present yet. */
    fun intern(value: String): Int {
        val existing = indexOf(value)
        if (existing >= 0) return existing
        strings.add(value)
        return strings.size - 1
    }

    fun get(index: Int): String? = if (index < 0 || index >= strings.size) null else strings[index]

    /** Total number of strings currently held. */
    val stringCount: Int get() = strings.size

    /** Serialises the pool into a complete `RES_STRING_POOL_TYPE` chunk. */
    fun build(): ByteArray {
        val encoded = strings.map { encode(it, utf8) }
        val count = encoded.size
        val stringsStart = HEADER_SIZE + count * 4
        var dataSize = 0
        for (item in encoded) dataSize += item.size
        val totalSize = alignUp(stringsStart + dataSize, 4)

        val writer = LeWriter(totalSize)
        writer.u16(ResType.STRING_POOL)
        writer.u16(HEADER_SIZE)
        writer.u32(totalSize)
        writer.u32(count)
        writer.u32(0) // styleCount - styles are not used by manifests and are dropped
        writer.u32(if (utf8) StringPoolFlags.UTF8 else 0)
        writer.u32(stringsStart)
        writer.u32(0) // stylesStart

        var offset = 0
        for (item in encoded) {
            writer.u32(offset)
            offset += item.size
        }
        for (item in encoded) writer.bytes(item)
        writer.padToAlignment(4)
        return writer.toByteArray()
    }

    companion object {
        /** `type`(2) + `headerSize`(2) + `size`(4) + count/style/flags/stringsStart/stylesStart (5 * 4). */
        const val HEADER_SIZE = 28

        /** Parses the pool chunk that starts at [offset] inside [data]. */
        fun parse(data: ByteArray, offset: Int): StringPool {
            require(data.u16(offset) == ResType.STRING_POOL) {
                "expected a string pool chunk at offset $offset"
            }
            val headerSize = data.chunkHeaderSize(offset)
            val stringCount = data.u32(offset + 8)
            val flags = data.u32(offset + 16)
            val stringsStart = data.u32(offset + 20)

            val utf8 = (flags and StringPoolFlags.UTF8) != 0
            val sorted = (flags and StringPoolFlags.SORTED) != 0

            val offsetsBase = offset + headerSize
            val dataBase = offset + stringsStart

            val strings = ArrayList<String>(maxOf(stringCount, 0))
            for (index in 0 until stringCount) {
                val relative = data.u32(offsetsBase + index * 4)
                strings.add(if (relative == -1) "" else decode(data, dataBase + relative, utf8))
            }
            return StringPool(strings, utf8, sorted)
        }

        private fun decode(data: ByteArray, start: Int, utf8: Boolean): String {
            var position = start
            if (utf8) {
                val charCount = readVarint8(data, position)
                position = charCount.next
                val byteCount = readVarint8(data, position)
                position = byteCount.next
                if (byteCount.value <= 0) return ""
                return String(data, position, byteCount.value, Charsets.UTF_8)
            }

            var length = data.u16(position)
            position += 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7FFF) shl 16) or data.u16(position)
                position += 2
            }
            if (length <= 0) return ""
            return String(data, position, length * 2, Charsets.UTF_16LE)
        }

        private class Varint(val value: Int, val next: Int)

        private fun readVarint8(data: ByteArray, position: Int): Varint {
            val first = data.u8(position)
            return if (first and 0x80 != 0) {
                Varint(((first and 0x7F) shl 8) or data.u8(position + 1), position + 2)
            } else {
                Varint(first, position + 1)
            }
        }

        private fun encode(value: String, utf8: Boolean): ByteArray {
            if (utf8) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                val writer = LeWriter(bytes.size + 8)
                writeVarint8(writer, value.length)
                writeVarint8(writer, bytes.size)
                writer.bytes(bytes)
                writer.u8(0)
                return writer.toByteArray()
            }

            val bytes = value.toByteArray(Charsets.UTF_16LE)
            val writer = LeWriter(bytes.size + 6)
            val chars = value.length
            if (chars > 0x7FFF) {
                writer.u16(0x8000 or (chars ushr 16))
                writer.u16(chars and 0xFFFF)
            } else {
                writer.u16(chars)
            }
            writer.bytes(bytes)
            writer.u16(0)
            return writer.toByteArray()
        }

        private fun writeVarint8(writer: LeWriter, value: Int) {
            if (value > 0xFF) {
                writer.u8((value ushr 8) or 0x80)
                writer.u8(value and 0xFF)
            } else {
                writer.u8(value)
            }
        }
    }
}
