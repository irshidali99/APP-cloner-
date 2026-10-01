package com.example.engine.core

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A parsed ZIP central directory entry. */
class ZipEntryRecord(
    val name: String,
    val method: Int,
    val flags: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val dosTime: Int,
    val dosDate: Int,
    val extra: ByteArray
) {
    val isStored: Boolean get() = method == ZipFormat.METHOD_STORED
}

/**
 * Minimal ZIP reader/writer tailored to APK handling.
 *
 * `java.util.zip` cannot (a) copy already compressed entry payloads verbatim, (b) align entries on a byte
 * boundary or (c) insert the APK signing block, so the engine needs its own implementation. Everything is
 * streamed with fixed size buffers; only the small metadata entries are ever held in memory.
 */
object ZipFormat {
    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8
    const val FLAG_DATA_DESCRIPTOR = 0x0008
    const val FLAG_UTF8 = 0x0800

    const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
    const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    const val EOCD_SIGNATURE = 0x06054b50L
    const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L

    const val APK_SIGNATURE_SCHEME_V2_ID = 0x7109871a
    const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"

    /** Extra field id used by `zipalign` for padding; unknown ids are ignored by every consumer. */
    const val PADDING_EXTRA_ID = 0xD935

    const val COPY_BUFFER = 64 * 1024
}

/**
 * Random access reader for the parts of a ZIP file an APK signer cares about: the central directory, the
 * end of central directory record and the raw (still compressed) payload of each entry.
 */
class ZipArchive(val file: File) : AutoCloseable {

    private val ra = RandomAccessFile(file, "r")

    val entries: List<ZipEntryRecord>
    val centralDirectoryOffset: Long
    val centralDirectorySize: Long
    val endOfCentralDirectoryOffset: Long

    init {
        val length = ra.length()
        require(length > 22) { "not a zip file: ${file.name}" }

        val eocd = findEndOfCentralDirectory(length)
        endOfCentralDirectoryOffset = eocd
        ra.seek(eocd + 12)
        var cdSize = readU32()
        var cdOffset = readU32()

        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
            val zip64 = findZip64EndOfCentralDirectory(eocd)
            ra.seek(zip64 + 40)
            cdSize = readU64()
            cdOffset = readU64()
        }

        centralDirectoryOffset = cdOffset
        centralDirectorySize = cdSize
        entries = parseCentralDirectory(cdOffset, cdSize)
    }

    /** True when this file already carries an APK signing block before its central directory. */
    fun hasSigningBlock(): Boolean {
        if (centralDirectoryOffset < 32) return false
        ra.seek(centralDirectoryOffset - 16)
        return String(readBytes(16), Charsets.US_ASCII) == ZipFormat.APK_SIG_BLOCK_MAGIC
    }

    /** Absolute offset where the signing block starts, or -1 when there is none. */
    fun signingBlockOffset(): Long {
        if (!hasSigningBlock()) return -1
        ra.seek(centralDirectoryOffset - 24)
        val blockSize = readU64()
        return centralDirectoryOffset - blockSize - 8
    }

    /** Offset of the first payload byte of [entry] inside the file. */
    fun dataOffsetOf(entry: ZipEntryRecord): Long {
        ra.seek(entry.localHeaderOffset)
        require(readU32() == ZipFormat.LOCAL_HEADER_SIGNATURE) {
            "broken local header for ${entry.name}"
        }
        ra.seek(entry.localHeaderOffset + 26)
        val nameLength = readU16()
        val extraLength = readU16()
        return entry.localHeaderOffset + 30 + nameLength + extraLength
    }

    /** Streams the raw (compressed) payload of [entry] into [sink]. */
    fun copyRaw(entry: ZipEntryRecord, sink: OutputStream) {
        ra.seek(dataOffsetOf(entry))
        var remaining = entry.compressedSize
        val buffer = ByteArray(ZipFormat.COPY_BUFFER)
        while (remaining > 0) {
            val read = ra.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IllegalStateException("unexpected end of ${entry.name}")
            sink.write(buffer, 0, read)
            remaining -= read
        }
    }

    /**
     * Streams the raw payload of [entry] into [sink] while feeding the *uncompressed* content into
     * [digest]. Used for the v1 signature, which digests the decoded entry data, without buffering the
     * entry in memory.
     */
    fun copyRawWithDigest(entry: ZipEntryRecord, sink: OutputStream, digest: java.security.MessageDigest) {
        // Two separate buffers are essential: the inflater reads its input lazily while producing output,
        // so reusing one array for both corrupts the decompressed data.
        val input = ByteArray(ZipFormat.COPY_BUFFER)
        val output = ByteArray(ZipFormat.COPY_BUFFER)
        val inflater = if (entry.isStored) null else Inflater(true)
        try {
            ra.seek(dataOffsetOf(entry))
            var remaining = entry.compressedSize
            while (remaining > 0) {
                val read = ra.read(input, 0, minOf(input.size.toLong(), remaining).toInt())
                if (read <= 0) throw IllegalStateException("unexpected end of ${entry.name}")
                sink.write(input, 0, read)
                if (inflater == null) {
                    digest.update(input, 0, read)
                } else {
                    inflater.setInput(input, 0, read)
                    while (true) {
                        val produced = inflater.inflate(output)
                        if (produced > 0) {
                            digest.update(output, 0, produced)
                        } else if (inflater.needsInput() || inflater.finished()) {
                            break
                        }
                    }
                }
                remaining -= read
            }
            if (inflater != null) {
                while (!inflater.finished()) {
                    val produced = inflater.inflate(output)
                    if (produced > 0) {
                        digest.update(output, 0, produced)
                    } else if (inflater.needsInput()) {
                        break
                    }
                }
            }
        } finally {
            inflater?.end()
        }
    }

    /** Reads the raw (compressed) payload of [entry]. */
    fun rawBytes(entry: ZipEntryRecord): ByteArray {
        val out = java.io.ByteArrayOutputStream(entry.compressedSize.toInt().coerceAtLeast(16))
        copyRaw(entry, out)
        return out.toByteArray()
    }

    /** Reads and decompresses [entry] into memory. Only used for small, well known entries. */
    fun readEntry(entry: ZipEntryRecord): ByteArray {
        val raw = java.io.ByteArrayInputStream(rawBytes(entry))
        // APK entries use raw deflate streams (no zlib wrapper), hence `nowrap = true`
        val stream = if (entry.isStored) raw else java.util.zip.InflaterInputStream(raw, Inflater(true))
        val out = java.io.ByteArrayOutputStream(entry.uncompressedSize.toInt().coerceAtLeast(16))
        val buffer = ByteArray(ZipFormat.COPY_BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun findEntry(name: String): ZipEntryRecord? = entries.firstOrNull { it.name == name }

    override fun close() {
        ra.close()
    }

    // ---------------------------------------------------------------------------------------------

    private fun findEndOfCentralDirectory(length: Long): Long {
        val maxComment = 0xFFFF
        val scan = minOf(length, (maxComment + 22).toLong())
        val buffer = ByteArray(scan.toInt())
        ra.seek(length - scan)
        ra.readFully(buffer)
        for (index in buffer.size - 22 downTo 0) {
            if (buffer[index].toInt() and 0xFF != 0x50) continue
            if (u32(buffer, index) == ZipFormat.EOCD_SIGNATURE) return length - scan + index
        }
        throw IllegalArgumentException("end of central directory record not found")
    }

    private fun findZip64EndOfCentralDirectory(eocd: Long): Long {
        val locator = eocd - 20
        require(locator >= 0) { "zip64 locator not found" }
        ra.seek(locator)
        require(readU32() == ZipFormat.ZIP64_LOCATOR_SIGNATURE) { "zip64 locator not found" }
        ra.seek(locator + 8)
        return readU64()
    }

    private fun parseCentralDirectory(offset: Long, size: Long): List<ZipEntryRecord> {
        val result = ArrayList<ZipEntryRecord>()
        ra.seek(offset)
        var consumed = 0L
        while (consumed < size) {
            if (readU32() != ZipFormat.CENTRAL_HEADER_SIGNATURE) break
            ra.skipBytes(4) // version made by + version needed
            val flags = readU16()
            val method = readU16()
            val dosTime = readU16()
            val dosDate = readU16()
            val crc = readU32()
            var compressedSize = readU32()
            var uncompressedSize = readU32()
            val nameLength = readU16()
            val extraLength = readU16()
            val commentLength = readU16()
            ra.skipBytes(4) // disk number start + internal attributes
            ra.skipBytes(4) // external attributes
            var localHeaderOffset = readU32()
            val nameBytes = readBytes(nameLength)
            val extra = readBytes(extraLength)
            ra.skipBytes(commentLength)

            val name = if (flags and ZipFormat.FLAG_UTF8 != 0) {
                String(nameBytes, Charsets.UTF_8)
            } else {
                String(nameBytes, Charsets.ISO_8859_1)
            }

            // zip64: values that do not fit into 32 bit are moved into the extra field (id 0x0001)
            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL ||
                localHeaderOffset == 0xFFFFFFFFL
            ) {
                var position = 0
                while (position + 4 <= extra.size) {
                    val id = u16(extra, position)
                    val length = u16(extra, position + 2)
                    if (id == 0x0001) {
                        var cursor = position + 4
                        if (uncompressedSize == 0xFFFFFFFFL) {
                            uncompressedSize = u64(extra, cursor); cursor += 8
                        }
                        if (compressedSize == 0xFFFFFFFFL) {
                            compressedSize = u64(extra, cursor); cursor += 8
                        }
                        if (localHeaderOffset == 0xFFFFFFFFL) {
                            localHeaderOffset = u64(extra, cursor); cursor += 8
                        }
                        break
                    }
                    position += 4 + length
                }
            }

            result.add(
                ZipEntryRecord(
                    name = name,
                    method = method,
                    flags = flags,
                    crc32 = crc,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    dosTime = dosTime,
                    dosDate = dosDate,
                    extra = extra
                )
            )
            consumed += 46 + nameLength + extraLength + commentLength
        }
        return result
    }

    private fun readU16(): Int {
        val low = ra.read()
        val high = ra.read()
        return (low and 0xFF) or ((high and 0xFF) shl 8)
    }

    private fun readU32(): Long {
        var value = 0L
        for (index in 0 until 4) value = value or ((ra.read().toLong() and 0xFF) shl (8 * index))
        return value
    }

    private fun readU64(): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((ra.read().toLong() and 0xFF) shl (8 * index))
        return value
    }

    private fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        ra.readFully(bytes)
        return bytes
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) value = value or ((data[offset + index].toLong() and 0xFF) shl (8 * index))
        return value
    }

    private fun u64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((data[offset + index].toLong() and 0xFF) shl (8 * index))
        return value
    }
}

/**
 * Streaming ZIP writer used to assemble the cloned APK.
 *
 * Entries can be copied verbatim from a [ZipArchive] (no recompression, constant memory) or written from a
 * byte array for the entries the clone has to modify. Every entry can be aligned, which is required for
 * `resources.arsc` and for uncompressed native libraries.
 */
class CloneZipWriter(private val output: OutputStream) {

    private class Record(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val dosTime: Int,
        val dosDate: Int,
        val extra: ByteArray
    )

    private val records = ArrayList<Record>()
    private var offset = 0L
    private var defaultTime = 0
    private var defaultDate = 0x0021 // 1980-01-01

    fun setDefaultTimestamp(dosTime: Int, dosDate: Int) {
        defaultTime = dosTime
        defaultDate = dosDate
    }

    /**
     * Copies [entry] verbatim (compressed payload included), pads its header so the data is aligned and
     * optionally feeds the decoded content into [digest].
     */
    fun copyEntry(
        archive: ZipArchive,
        entry: ZipEntryRecord,
        alignment: Int = 1,
        digest: java.security.MessageDigest? = null
    ) {
        val nameBytes = entryNameBytes(entry.name, entry.flags)
        val padding = paddingFor(offset, nameBytes.size, alignment)
        val extra = buildExtra(padding)
        val header = localHeader(
            method = entry.method,
            flags = entry.flags and ZipFormat.FLAG_DATA_DESCRIPTOR.inv(),
            crc = entry.crc32,
            compressedSize = entry.compressedSize,
            uncompressedSize = entry.uncompressedSize,
            dosTime = entry.dosTime,
            dosDate = entry.dosDate,
            nameBytes = nameBytes,
            extra = extra
        )
        output.write(header)
        if (digest == null) {
            archive.copyRaw(entry, output)
        } else {
            archive.copyRawWithDigest(entry, output, digest)
        }
        val localHeaderOffset = offset
        offset += header.size + entry.compressedSize
        records.add(
            Record(
                entry.name, entry.method, entry.flags and ZipFormat.FLAG_DATA_DESCRIPTOR.inv(),
                entry.crc32, entry.compressedSize, entry.uncompressedSize, localHeaderOffset,
                entry.dosTime, entry.dosDate, extra
            )
        )
    }

    /** Writes a stored (uncompressed) entry from memory. */
    fun writeStoredEntry(
        name: String,
        data: ByteArray,
        alignment: Int = 1,
        flags: Int = ZipFormat.FLAG_UTF8,
        dosTime: Int = defaultTime,
        dosDate: Int = defaultDate
    ) {
        val crc = CRC32().apply { update(data) }.value
        val nameBytes = entryNameBytes(name, flags)
        val padding = paddingFor(offset, nameBytes.size, alignment)
        val extra = buildExtra(padding)
        val header = localHeader(
            method = ZipFormat.METHOD_STORED,
            flags = flags,
            crc = crc,
            compressedSize = data.size.toLong(),
            uncompressedSize = data.size.toLong(),
            dosTime = dosTime,
            dosDate = dosDate,
            nameBytes = nameBytes,
            extra = extra
        )
        output.write(header)
        output.write(data)
        val localHeaderOffset = offset
        offset += header.size + data.size
        records.add(
            Record(
                name, ZipFormat.METHOD_STORED, flags, crc, data.size.toLong(), data.size.toLong(),
                localHeaderOffset, dosTime, dosDate, extra
            )
        )
    }

    /** Deflates [data] when compression actually helps, otherwise stores it. */
    fun writeEntry(
        name: String,
        data: ByteArray,
        alignment: Int = 1,
        compress: Boolean = false,
        dosTime: Int = defaultTime,
        dosDate: Int = defaultDate
    ) {
        if (!compress) {
            writeStoredEntry(name, data, alignment, dosTime = dosTime, dosDate = dosDate)
            return
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(ZipFormat.COPY_BUFFER)
        val compressed = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        while (!deflater.finished()) {
            val produced = deflater.deflate(buffer)
            compressed.write(buffer, 0, produced)
        }
        deflater.end()
        val payload = compressed.toByteArray()
        if (payload.size >= data.size) {
            writeStoredEntry(name, data, alignment, dosTime = dosTime, dosDate = dosDate)
            return
        }

        val crc = CRC32().apply { update(data) }.value
        val nameBytes = entryNameBytes(name, ZipFormat.FLAG_UTF8)
        val padding = paddingFor(offset, nameBytes.size, alignment)
        val extra = buildExtra(padding)
        val header = localHeader(
            method = ZipFormat.METHOD_DEFLATED,
            flags = ZipFormat.FLAG_UTF8,
            crc = crc,
            compressedSize = payload.size.toLong(),
            uncompressedSize = data.size.toLong(),
            dosTime = dosTime,
            dosDate = dosDate,
            nameBytes = nameBytes,
            extra = extra
        )
        output.write(header)
        output.write(payload)
        val localHeaderOffset = offset
        offset += header.size + payload.size
        records.add(
            Record(
                name, ZipFormat.METHOD_DEFLATED, ZipFormat.FLAG_UTF8, crc, payload.size.toLong(),
                data.size.toLong(), localHeaderOffset, dosTime, dosDate, extra
            )
        )
    }

    /** Set of entry names written so far. */
    val entryNames: List<String> get() = records.map { it.name }

    /** Writes the central directory and the end of central directory record. */
    fun finish() {
        val directoryOffset = offset
        var directorySize = 0L
        for (record in records) {
            val nameBytes = entryNameBytes(record.name, record.flags)
            val writer = LeWriter(64 + nameBytes.size + record.extra.size)
            writer.u32(ZipFormat.CENTRAL_HEADER_SIGNATURE.toInt())
            writer.u16(20) // version made by
            writer.u16(20) // version needed
            writer.u16(record.flags)
            writer.u16(record.method)
            writer.u16(record.dosTime)
            writer.u16(record.dosDate)
            writer.u32(record.crc.toInt())
            writer.u32(record.compressedSize.toInt())
            writer.u32(record.uncompressedSize.toInt())
            writer.u16(nameBytes.size)
            writer.u16(record.extra.size)
            writer.u16(0) // comment length
            writer.u16(0) // disk number start
            writer.u16(0) // internal attributes
            writer.u32(0) // external attributes
            writer.u32(record.localHeaderOffset.toInt())
            writer.bytes(nameBytes)
            writer.bytes(record.extra)
            val bytes = writer.toByteArray()
            output.write(bytes)
            directorySize += bytes.size
        }

        val end = LeWriter(22)
        end.u32(ZipFormat.EOCD_SIGNATURE.toInt())
        end.u16(0) // this disk
        end.u16(0) // disk with central directory
        end.u16(records.size)
        end.u16(records.size)
        end.u32(directorySize.toInt())
        end.u32(directoryOffset.toInt())
        end.u16(0) // comment length
        output.write(end.toByteArray())
    }

    private fun entryNameBytes(name: String, flags: Int): ByteArray =
        if (flags and ZipFormat.FLAG_UTF8 != 0) name.toByteArray(Charsets.UTF_8)
        else name.toByteArray(Charsets.ISO_8859_1)

    private fun paddingFor(offset: Long, nameLength: Int, alignment: Int): Int {
        if (alignment <= 1) return 0
        val base = offset + 30 + nameLength
        val remainder = (base % alignment).toInt()
        if (remainder == 0) return 0
        var padding = alignment - remainder
        // an extra field needs a 4 byte header, so keep padding >= 0 and add a whole alignment block
        if (padding < 4) padding += alignment
        return padding
    }

    private fun buildExtra(padding: Int): ByteArray {
        if (padding < 4) return ByteArray(0)
        val extra = ByteArray(padding)
        extra[0] = (ZipFormat.PADDING_EXTRA_ID and 0xFF).toByte()
        extra[1] = ((ZipFormat.PADDING_EXTRA_ID ushr 8) and 0xFF).toByte()
        extra[2] = ((padding - 4) and 0xFF).toByte()
        extra[3] = (((padding - 4) ushr 8) and 0xFF).toByte()
        return extra
    }

    private fun localHeader(
        method: Int,
        flags: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        dosTime: Int,
        dosDate: Int,
        nameBytes: ByteArray,
        extra: ByteArray
    ): ByteArray {
        val writer = LeWriter(30 + nameBytes.size + extra.size)
        writer.u32(ZipFormat.LOCAL_HEADER_SIGNATURE.toInt())
        writer.u16(20) // version needed
        writer.u16(flags)
        writer.u16(method)
        writer.u16(dosTime)
        writer.u16(dosDate)
        writer.u32(crc.toInt())
        writer.u32(compressedSize.toInt())
        writer.u32(uncompressedSize.toInt())
        writer.u16(nameBytes.size)
        writer.u16(extra.size)
        writer.bytes(nameBytes)
        writer.bytes(extra)
        return writer.toByteArray()
    }
}
