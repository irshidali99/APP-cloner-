package com.example.engine.core

/**
 * Low level helpers for Android's binary resource formats (`resources.arsc`, binary `AndroidManifest.xml`).
 *
 * All structures are little-endian and every one of them starts with a [ResChunk] header:
 * `uint16 type`, `uint16 headerSize`, `uint32 size`.
 *
 * This file deliberately has **no Android framework dependencies** so the whole cloning engine can be
 * unit tested on the JVM.
 */

/** Chunk type identifiers. */
internal object ResType {
    const val STRING_POOL = 0x0001
    const val TABLE = 0x0002
    const val XML = 0x0003
    const val XML_START_NAMESPACE = 0x0100
    const val XML_END_NAMESPACE = 0x0101
    const val XML_START_ELEMENT = 0x0102
    const val XML_END_ELEMENT = 0x0103
    const val XML_CDATA = 0x0104
    const val XML_RESOURCE_MAP = 0x0180
    const val TABLE_PACKAGE = 0x0200
    const val TABLE_TYPE = 0x0201
    const val TABLE_TYPE_SPEC = 0x0202
    const val TABLE_LIBRARY = 0x0203
}

/** `Res_value.dataType` values. */
internal object ResValueType {
    const val NULL = 0x00
    const val REFERENCE = 0x01
    const val ATTRIBUTE = 0x02
    const val STRING = 0x03
    const val FLOAT = 0x04
    const val DIMENSION = 0x05
    const val FRACTION = 0x06
    const val INT_DEC = 0x10
    const val INT_HEX = 0x11
    const val INT_BOOLEAN = 0x12
    const val INT_COLOR_ARGB8 = 0x1c
    const val INT_COLOR_RGB8 = 0x1d
    const val INT_COLOR_ARGB4 = 0x1e
    const val INT_COLOR_RGB4 = 0x1f
}

internal object StringPoolFlags {
    const val SORTED = 0x0001
    const val UTF8 = 0x0100
}

/** Standard chunk header size (`type` + `headerSize` + `size`). */
internal const val CHUNK_HEADER_SIZE = 8

/** Index value meaning "no string / no entry". */
internal const val NO_INDEX = -1

// ---------------------------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------------------------

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

internal fun ByteArray.u32(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)

/** Reads a chunk header at [offset]. */
internal fun ByteArray.chunkType(offset: Int): Int = u16(offset)

internal fun ByteArray.chunkHeaderSize(offset: Int): Int = u16(offset + 2)

internal fun ByteArray.chunkSize(offset: Int): Int = u32(offset + 4)

// ---------------------------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------------------------

/** Small little-endian writer used to rebuild chunks. */
internal class LeWriter(initialCapacity: Int = 256) {
    private val out = java.io.ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun u8(value: Int) {
        out.write(value and 0xFF)
    }

    fun u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Int) {
        u8(value)
        u8(value ushr 8)
        u8(value ushr 16)
        u8(value ushr 24)
    }

    fun bytes(value: ByteArray) {
        out.write(value, 0, value.size)
    }

    fun zeros(count: Int) {
        repeat(count) { out.write(0) }
    }

    /** Pads with zeros so that [size] becomes a multiple of [alignment]. */
    fun padToAlignment(alignment: Int) {
        if (alignment <= 1) return
        val remainder = size % alignment
        if (remainder != 0) zeros(alignment - remainder)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Rounds [value] up to the next multiple of [alignment]. */
internal fun alignUp(value: Int, alignment: Int): Int =
    if (alignment <= 1) value else ((value + alignment - 1) / alignment) * alignment
