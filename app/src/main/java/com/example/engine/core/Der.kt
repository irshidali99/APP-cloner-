package com.example.engine.core

/**
 * Minimal DER (ASN.1) encoder/decoder.
 *
 * Needed because Android ships no API to create an X.509 certificate or a PKCS#7 container, which is
 * exactly what APK signing requires. Keeping it in the shared core makes it testable on the JVM.
 */
object Der {

    const val TAG_BOOLEAN = 0x01
    const val TAG_INTEGER = 0x02
    const val TAG_BIT_STRING = 0x03
    const val TAG_OCTET_STRING = 0x04
    const val TAG_NULL = 0x05
    const val TAG_OID = 0x06
    const val TAG_UTF8_STRING = 0x0C
    const val TAG_SEQUENCE = 0x30
    const val TAG_SET = 0x31
    const val TAG_UTC_TIME = 0x17
    const val TAG_GENERALIZED_TIME = 0x18
    const val TAG_IA5_STRING = 0x16

    fun encode(tag: Int, content: ByteArray): ByteArray {
        val writer = LeWriter(content.size + 6)
        writer.u8(tag)
        writeLength(writer, content.size)
        writer.bytes(content)
        return writer.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = encode(TAG_SEQUENCE, concat(parts))

    fun set(vararg parts: ByteArray): ByteArray = encode(TAG_SET, concat(parts))

    fun setOf(elements: List<ByteArray>): ByteArray = encode(TAG_SET, concat(elements.toTypedArray()))

    fun integer(value: Long): ByteArray {
        var length = 1
        var probe = value
        while (probe shr (length * 8 - 1) != 0L && probe shr (length * 8 - 1) != -1L) length++
        val content = ByteArray(length)
        for (index in 0 until length) {
            content[length - 1 - index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
        return encode(TAG_INTEGER, content)
    }

    /** Encodes a `BigInteger` (RSA modulus and exponent are handed out in that form). */
    fun integer(value: java.math.BigInteger): ByteArray = integer(value.toByteArray())

    /** Encodes a non negative big integer, adding a leading zero byte when the top bit is set. */
    fun integer(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start++
        val trimmed = value.copyOfRange(start, value.size)
        val content = if (trimmed[0].toInt() and 0x80 != 0) {
            ByteArray(trimmed.size + 1).also { trimmed.copyInto(it, 1) }
        } else {
            trimmed
        }
        return encode(TAG_INTEGER, content)
    }

    fun boolean(value: Boolean): ByteArray = encode(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    fun nullValue(): ByteArray = encode(TAG_NULL, ByteArray(0))

    fun octetString(content: ByteArray): ByteArray = encode(TAG_OCTET_STRING, content)

    fun bitString(content: ByteArray, unusedBits: Int = 0): ByteArray {
        val writer = LeWriter(content.size + 2)
        writer.u8(unusedBits)
        writer.bytes(content)
        return encode(TAG_BIT_STRING, writer.toByteArray())
    }

    fun utf8String(value: String): ByteArray = encode(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    fun ia5String(value: String): ByteArray = encode(TAG_IA5_STRING, value.toByteArray(Charsets.US_ASCII))

    /** `YYMMDDHHMMSSZ` for years below 2050, `YYYYMMDDHHMMSSZ` afterwards. */
    fun time(epochMillis: Long): ByteArray {
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val text = format.format(java.util.Date(epochMillis))
        val year = text.substring(0, 4).toInt()
        return if (year < 2050) {
            encode(TAG_UTC_TIME, (text.substring(2) + "Z").toByteArray(Charsets.US_ASCII))
        } else {
            encode(TAG_GENERALIZED_TIME, (text + "Z").toByteArray(Charsets.US_ASCII))
        }
    }

    /** Encodes a dotted object identifier such as `1.2.840.113549.1.1.11`. */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "an OID needs at least two components" }
        val writer = LeWriter(parts.size + 2)
        writer.u8((parts[0] * 40 + parts[1]).toInt())
        for (index in 2 until parts.size) {
            var value = parts[index]
            val stack = ArrayDeque<Int>()
            stack.addFirst((value and 0x7F).toInt())
            value = value shr 7
            while (value > 0) {
                stack.addFirst(((value and 0x7F) or 0x80).toInt())
                value = value shr 7
            }
            stack.forEach { writer.u8(it) }
        }
        return encode(TAG_OID, writer.toByteArray())
    }

    /** `[n] EXPLICIT` wrapper, used by X.509 version and extensions. */
    fun explicit(tagNumber: Int, content: ByteArray): ByteArray = encode(0xA0 or tagNumber, content)

    /**
     * `[n] IMPLICIT` wrapper for a *constructed* value (a SET/SEQUENCE). The tag byte must carry the
     * constructed bit, otherwise parsers such as asn1crypto reject the container.
     */
    fun implicitConstructed(tagNumber: Int, content: ByteArray): ByteArray = encode(0xA0 or tagNumber, content)

    fun concat(parts: Array<out ByteArray>): ByteArray {
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

    fun concat(parts: List<ByteArray>): ByteArray = concat(parts.toTypedArray())

    /**
     * DER lengths are big-endian (unlike everything else in the Android binary formats), so the bytes are
     * written explicitly instead of going through [LeWriter.u16]/[LeWriter.u32].
     */
    private fun writeLength(writer: LeWriter, length: Int) {
        when {
            length < 0x80 -> writer.u8(length)
            length <= 0xFF -> {
                writer.u8(0x81)
                writer.u8(length)
            }
            length <= 0xFFFF -> {
                writer.u8(0x82)
                writer.u8((length ushr 8) and 0xFF)
                writer.u8(length and 0xFF)
            }
            else -> {
                writer.u8(0x84)
                writer.u8((length ushr 24) and 0xFF)
                writer.u8((length ushr 16) and 0xFF)
                writer.u8((length ushr 8) and 0xFF)
                writer.u8(length and 0xFF)
            }
        }
    }
}

/** Streaming reader for DER structures, used when verifying signed blocks. */
class DerReader(private val data: ByteArray, var offset: Int = 0, private val end: Int = data.size) {

    data class Element(
        val tag: Int,
        val start: Int,
        val contentStart: Int,
        val contentLength: Int
    ) {
        val end: Int get() = contentStart + contentLength
        val rawSize: Int get() = end - start
    }

    fun readElement(): Element {
        require(offset < end) { "unexpected end of DER data" }
        val start = offset
        val tag = data.u8(offset++)
        var length = data.u8(offset++)
        if (length and 0x80 != 0) {
            val byteCount = length and 0x7F
            length = 0
            repeat(byteCount) { length = (length shl 8) or data.u8(offset++) }
        }
        val element = Element(tag, start, offset, length)
        offset = element.end
        return element
    }

    /** Raw bytes of an element including its tag and length. */
    fun raw(element: Element): ByteArray = data.copyOfRange(element.start, element.end)

    fun bytes(element: Element): ByteArray = data.copyOfRange(element.contentStart, element.end)

    fun reader(element: Element): DerReader = DerReader(data, element.contentStart, element.end)
}
