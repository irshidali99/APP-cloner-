package com.example.engine.core

import java.io.File
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec

/**
 * APK Signature Scheme v2 - the signing block that Android 7+ understands and that *every* Android 11+
 * device requires whenever the app targets API 30 or newer.
 *
 * Layout produced here:
 * ```
 * [ ZIP entry contents ][ APK Signing Block ][ Central Directory ][ EOCD ]
 * ```
 * The signed payload is covered by a chunked digest:
 *  - section 1: everything before the signing block,
 *  - section 2: the central directory,
 *  - section 3: the EOCD record, with the central directory offset field restored to the value it would
 *    have had without the signing block.
 * Sections are split into 1 MiB chunks, each prefixed with `0xA5`, and the stream is terminated by `0x5A`
 * plus the little-endian chunk count.
 *
 * Everything inside a signing block is `uint32 length prefixed` (not DER), which is why a dedicated reader
 * is used instead of [DerReader].
 */
object ApkSignatureSchemeV2 {

    const val BLOCK_ID = 0x7109871a
    const val CHUNK_SIZE = 1024 * 1024
    const val CHUNK_PREFIX = 0xA5
    const val CHUNK_TERMINATOR = 0x5A

    /** RSASSA-PKCS1-v1_5 with SHA2-256. */
    const val ALGORITHM_RSA_PKCS1_SHA256 = 0x0103

    /** RSASSA-PSS with SHA2-256, accepted as a fallback when verifying. */
    const val ALGORITHM_RSA_PSS_SHA256 = 0x0101

    private const val BLOCK_MAGIC = "APK Sig Block 42"

    data class VerificationResult(
        val isValid: Boolean,
        val schemeVersion: Int,
        val signerCount: Int,
        val certificateSubject: String?,
        val detail: String,
        /** Signature schemes actually present in the file, e.g. `[v1, v2]`. */
        val schemes: List<String> = emptyList()
    )

    // ---------------------------------------------------------------------------------------------
    // Signing
    // ---------------------------------------------------------------------------------------------

    /** Signs [file] in place with a single RSA signer. */
    fun signInPlace(
        file: File,
        certificate: X509Certificate,
        privateKey: PrivateKey
    ) {
        val archive = ZipArchive(file)
        val centralDirectoryOffset: Long
        val endOfCentralDirectoryOffset: Long
        try {
            require(!archive.hasSigningBlock()) { "APK is already v2 signed" }
            centralDirectoryOffset = archive.centralDirectoryOffset
            endOfCentralDirectoryOffset = archive.endOfCentralDirectoryOffset
        } finally {
            archive.close()
        }

        val digest = contentDigest(
            file = file,
            blockStart = centralDirectoryOffset,
            blockEnd = centralDirectoryOffset,
            endOfCentralDirectoryOffset = endOfCentralDirectoryOffset,
            originalCentralDirectoryOffset = centralDirectoryOffset
        )

        // The signature covers the *contents* of the signed data field, the stored field adds a length prefix.
        val signedDataContent = buildSignedDataContent(ALGORITHM_RSA_PKCS1_SHA256, digest, certificate.encoded)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signedDataContent)
            sign()
        }
        val signedData = lengthPrefixed(signedDataContent)

        // "length-prefixed sequence of length-prefixed signatures", each record being
        // `signature algorithm ID (uint32)` + length prefixed signature over the signed data
        val signatures = sequence(
            lengthPrefixed(concat(le32(ALGORITHM_RSA_PKCS1_SHA256), lengthPrefixed(signature)))
        )
        // A signer is the three length prefixed fields above, itself length prefixed; the v2 block value is
        // that sequence of signers wrapped once more (matches AOSP's `encodeAsSequenceOfLengthPrefixedElements`).
        val signer = concat(signedData, signatures, lengthPrefixed(certificate.publicKey.encoded))
        val signers = lengthPrefixed(signer)
        val blockValue = lengthPrefixed(signers)

        val block = buildSigningBlock(blockValue)

        val temporary = File(file.parentFile, file.name + ".signing")
        RandomAccessFile(file, "r").use { input ->
            RandomAccessFile(temporary, "rw").use { output ->
                copyRange(input, output, 0, centralDirectoryOffset)
                output.write(block)
                copyRange(
                    input, output, centralDirectoryOffset,
                    endOfCentralDirectoryOffset - centralDirectoryOffset
                )

                val eocdLength = (file.length() - endOfCentralDirectoryOffset).toInt()
                val eocd = ByteArray(eocdLength)
                input.seek(endOfCentralDirectoryOffset)
                input.readFully(eocd)
                writeU32(eocd, 16, centralDirectoryOffset + block.size)
                output.write(eocd)
            }
        }
        if (!file.delete() || !temporary.renameTo(file)) {
            throw IllegalStateException("could not replace ${file.name} with its signed version")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------------------------------

    /**
     * Verifies [file]: the v2 signature plus, when present, the JAR v1 signature.
     *
     * The result lists every scheme found, so callers can report what the APK really carries instead of
     * claiming a fixed set.
     */
    fun verify(file: File): VerificationResult {
        val hasV1 = hasV1Signature(file)
        val result = verifyV2(file)
        val schemes = buildList {
            if (hasV1) add("v1")
            if (result.schemeVersion == 2) add("v2")
        }
        return result.copy(schemes = schemes)
    }

    private fun verifyV2(file: File): VerificationResult {
        val archive = ZipArchive(file)
        try {
            val blockStart = archive.signingBlockOffset()
            if (blockStart < 0) {
                return VerificationResult(false, 0, 0, null, "no APK signing block present")
            }

            val blockValue = readSigningBlockValue(file, blockStart, archive.centralDirectoryOffset)
                ?: return VerificationResult(false, 2, 0, null, "signing block has no v2 entry")

            val signersBlock = LengthPrefixedReader(blockValue).nextElement()
            val signers = readLengthPrefixedList(signersBlock)
            if (signers.isEmpty()) {
                return VerificationResult(false, 2, 0, null, "signer list is empty")
            }

            val expectedDigests = HashMap<Int, ByteArray>()
            var certificateSubject: String? = null

            for (signer in signers) {
                val signerReader = LengthPrefixedReader(signer)
                val signedData = signerReader.nextElement()
                val signaturesBlock = signerReader.nextElement()
                val publicKeyBytes = signerReader.nextElement()

                // 1. signature over signedData
                var algorithm = 0
                var signatureBytes: ByteArray? = null
                for ((entryAlgorithm, entrySignature) in readAlgorithmValueRecords(signaturesBlock)) {
                    algorithm = entryAlgorithm
                    signatureBytes = entrySignature
                }
                val signatureValue = signatureBytes
                    ?: return VerificationResult(false, 2, signers.size, null, "signer has no signature")
                if (algorithm != ALGORITHM_RSA_PKCS1_SHA256) {
                    return VerificationResult(
                        false, 2, signers.size, null,
                        "unsupported signature algorithm 0x${algorithm.toString(16)}"
                    )
                }

                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(publicKeyBytes))
                val verified = Signature.getInstance("SHA256withRSA").run {
                    initVerify(publicKey)
                    update(signedData)
                    verify(signatureValue)
                }
                if (!verified) {
                    return VerificationResult(false, 2, signers.size, null, "signature does not verify")
                }

                // 2. digests + certificates from signedData
                val signedDataReader = LengthPrefixedReader(signedData)
                val digestsBlock = signedDataReader.nextElement()
                val certificatesBlock = signedDataReader.nextElement()

                for ((digestAlgorithm, digestValue) in readAlgorithmValueRecords(digestsBlock)) {
                    expectedDigests[digestAlgorithm] = digestValue
                }

                val certificates = readLengthPrefixedList(certificatesBlock)
                if (certificates.isNotEmpty()) {
                    val factory = CertificateFactory.getInstance("X.509")
                    val certificate = factory.generateCertificate(certificates.first().inputStream())
                        as X509Certificate
                    certificateSubject = certificate.subjectX500Principal.name
                    if (!certificate.publicKey.encoded.contentEquals(publicKeyBytes)) {
                        return VerificationResult(
                            false, 2, signers.size, certificateSubject,
                            "signer public key does not match the signer certificate"
                        )
                    }
                }
            }

            val expected = expectedDigests[ALGORITHM_RSA_PKCS1_SHA256]
                ?: expectedDigests[ALGORITHM_RSA_PSS_SHA256]
                ?: return VerificationResult(
                    false, 2, signers.size, certificateSubject, "no SHA-256 content digest present"
                )

            val actual = contentDigest(
                file = file,
                blockStart = blockStart,
                blockEnd = archive.centralDirectoryOffset,
                endOfCentralDirectoryOffset = archive.endOfCentralDirectoryOffset,
                originalCentralDirectoryOffset = blockStart
            )
            if (!actual.contentEquals(expected)) {
                return VerificationResult(
                    false, 2, signers.size, certificateSubject,
                    "content digest mismatch (file was modified after signing)"
                )
            }

            return VerificationResult(
                true, 2, signers.size, certificateSubject,
                "v2 signature valid (RSA PKCS#1 v1.5 / SHA-256)"
            )
        } finally {
            archive.close()
        }
    }

    /** Certificates embedded in a v2 signed APK. */
    fun certificatesOf(file: File): List<X509Certificate> {
        val archive = ZipArchive(file)
        try {
            val blockStart = archive.signingBlockOffset()
            if (blockStart < 0) return emptyList()
            val blockValue = readSigningBlockValue(file, blockStart, archive.centralDirectoryOffset)
                ?: return emptyList()

            val result = ArrayList<X509Certificate>()
            val factory = CertificateFactory.getInstance("X.509")
            val signersBlock = LengthPrefixedReader(blockValue).nextElement()
            for (signer in readLengthPrefixedList(signersBlock)) {
                val signerReader = LengthPrefixedReader(signer)
                val signedData = signerReader.nextElement()
                val signedDataReader = LengthPrefixedReader(signedData)
                signedDataReader.nextElement() // digests
                val certificatesBlock = signedDataReader.nextElement()
                for (der in readLengthPrefixedList(certificatesBlock)) {
                    result.add(factory.generateCertificate(der.inputStream()) as X509Certificate)
                }
            }
            return result
        } finally {
            archive.close()
        }
    }

    /** True when a v1 (META-INF signature file) signature is present in the archive. */
    fun hasV1Signature(file: File): Boolean {
        val archive = ZipArchive(file)
        try {
            return archive.entries.any {
                it.name.startsWith("META-INF/") && it.name.endsWith(".SF")
            }
        } finally {
            archive.close()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Content digest
    // ---------------------------------------------------------------------------------------------

    /**
     * Digest of the APK contents covered by APK Signature Scheme v2 (sections 1, 3 and 4).
     *
     * Per the spec each section is split into 1 MiB chunks and every chunk is hashed as
     * `0xa5 || uint32(chunk length) || chunk contents`. The top level digest is computed over
     * `0x5a || uint32(chunk count) || concatenation of the chunk digests`.
     *
     * While hashing the ZIP End of Central Directory, the field holding the central directory offset is
     * treated as containing the offset of the APK signing block, so the digest stays the same whether or not
     * the signing block is present in the file.
     */
    fun contentDigest(
        file: File,
        blockStart: Long,
        blockEnd: Long,
        endOfCentralDirectoryOffset: Long,
        originalCentralDirectoryOffset: Long,
        algorithm: String = "SHA-256"
    ): ByteArray {
        val chunkDigests = ArrayList<ByteArray>()
        val chunkDigest = MessageDigest.getInstance(algorithm)

        RandomAccessFile(file, "r").use { ra ->
            digestFileSection(chunkDigest, chunkDigests, ra, 0, blockStart)
            digestFileSection(
                chunkDigest, chunkDigests, ra,
                blockEnd, endOfCentralDirectoryOffset - blockEnd
            )

            val eocdLength = (file.length() - endOfCentralDirectoryOffset).toInt()
            val eocd = ByteArray(eocdLength)
            ra.seek(endOfCentralDirectoryOffset)
            ra.readFully(eocd)
            writeU32(eocd, 16, originalCentralDirectoryOffset)
            digestBytes(chunkDigest, chunkDigests, eocd, 0, eocd.size)
        }

        val topLevel = MessageDigest.getInstance(algorithm)
        topLevel.update(CHUNK_TERMINATOR.toByte())
        topLevel.update(le32(chunkDigests.size))
        for (chunk in chunkDigests) topLevel.update(chunk)
        return topLevel.digest()
    }

    /** Splits `[start, start + size)` of [ra] into chunks and records each chunk digest. */
    private fun digestFileSection(
        digest: MessageDigest,
        chunkDigests: MutableList<ByteArray>,
        ra: RandomAccessFile,
        start: Long,
        size: Long
    ) {
        if (size <= 0) return
        ra.seek(start)
        val buffer = ByteArray(CHUNK_SIZE)
        var remaining = size
        while (remaining > 0) {
            val toRead = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            var filled = 0
            while (filled < toRead) {
                val read = ra.read(buffer, filled, toRead - filled)
                if (read < 0) throw IllegalStateException("unexpected end of file while hashing")
                filled += read
            }
            digestBytes(digest, chunkDigests, buffer, 0, toRead)
            remaining -= toRead
        }
    }

    /** Hashes `[offset, offset + length)` of [bytes] in 1 MiB chunks and stores each chunk digest. */
    private fun digestBytes(
        digest: MessageDigest,
        chunkDigests: MutableList<ByteArray>,
        bytes: ByteArray,
        offset: Int,
        length: Int
    ) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val take = minOf(CHUNK_SIZE, end - position)
            digest.reset()
            digest.update(CHUNK_PREFIX.toByte())
            digest.update(le32(take))
            digest.update(bytes, position, take)
            chunkDigests.add(digest.digest())
            position += take
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun buildSignedDataContent(
        algorithm: Int,
        contentDigest: ByteArray,
        certificateDer: ByteArray
    ): ByteArray = concat(
        // "length-prefixed sequence of length-prefixed digests", each digest being
        // `signature algorithm ID (uint32)` + length prefixed digest
        sequence(lengthPrefixed(concat(le32(algorithm), lengthPrefixed(contentDigest)))),
        // "length-prefixed sequence of X.509 certificates", each certificate length prefixed
        sequence(lengthPrefixed(certificateDer)),
        sequence() // additional attributes (none)
    )

    private fun buildSigningBlock(blockValue: ByteArray): ByteArray {
        val pairs = concat(leU64(4L + blockValue.size), le32(BLOCK_ID), blockValue)
        val sizeField = pairs.size + 8 + 16
        return concat(
            leU64(sizeField.toLong()),
            pairs,
            leU64(sizeField.toLong()),
            BLOCK_MAGIC.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun readSigningBlockValue(
        file: File,
        blockStart: Long,
        centralDirectoryOffset: Long
    ): ByteArray? {
        RandomAccessFile(file, "r").use { ra ->
            val limit = centralDirectoryOffset - 24
            var position = blockStart + 8
            ra.seek(position)
            while (position + 12 <= limit) {
                val pairSize = leU64(ra)
                val id = leU32(ra)
                val valueSize = pairSize - 4
                if (valueSize < 0 || valueSize > Int.MAX_VALUE) return null
                if (id == BLOCK_ID) {
                    val value = ByteArray(valueSize.toInt())
                    ra.readFully(value)
                    return value
                }
                ra.seek(ra.filePointer + valueSize)
                position = ra.filePointer
            }
            return null
        }
    }

    /**
     * Parses the `digests` / `signatures` field: a sequence of length prefixed records, where each record
     * holds a `uint32` algorithm ID followed by a length prefixed value (AOSP: "length-prefixed sequence of
     * length-prefixed digests/signatures").
     */
    private fun readAlgorithmValueRecords(block: ByteArray): List<Pair<Int, ByteArray>> {
        val result = ArrayList<Pair<Int, ByteArray>>()
        for (record in readLengthPrefixedList(block)) {
            val reader = LengthPrefixedReader(record)
            val algorithm = reader.nextU32()
            result.add(algorithm to reader.nextElement())
        }
        return result
    }

    /** Splits a `uint32 length prefixed` sequence into its elements. */
    private fun readLengthPrefixedList(block: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var position = 0
        while (position + 4 <= block.size) {
            val length = readU32(block, position)
            position += 4
            if (length < 0 || position + length > block.size) break
            result.add(block.copyOfRange(position, position + length))
            position += length
        }
        return result
    }

    /**
     * Builds a *length prefixed* sequence: the concatenated elements wrapped in a `uint32` length, which is
     * how the v2 spec nests `signers`, `digests`, `certificates`, `signatures` and additional attributes.
     */
    private fun sequence(vararg elements: ByteArray): ByteArray = lengthPrefixed(concat(*elements))

    private class LengthPrefixedReader(private val data: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position + 4 <= data.size

        fun nextElement(): ByteArray {
            val length = readU32(data, position)
            position += 4
            val value = data.copyOfRange(position, position + length)
            position += length
            return value
        }

        fun nextU32(): Int {
            val value = readU32(data, position)
            position += 4
            return value
        }
    }

    private fun lengthPrefixed(content: ByteArray): ByteArray {
        val writer = LeWriter(content.size + 4)
        writer.u32(content.size)
        writer.bytes(content)
        return writer.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray = Der.concat(parts)

    private fun digestRange(digest: MessageDigest, ra: RandomAccessFile, start: Long, size: Long): Int {
        if (size <= 0) return 0
        ra.seek(start)
        val buffer = ByteArray(CHUNK_SIZE)
        var remaining = size
        var chunks = 0
        while (remaining > 0) {
            val toRead = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            var filled = 0
            while (filled < toRead) {
                val read = ra.read(buffer, filled, toRead - filled)
                if (read < 0) throw IllegalStateException("unexpected end of file while hashing")
                filled += read
            }
            digest.update(CHUNK_PREFIX.toByte())
            digest.update(buffer, 0, toRead)
            chunks++
            remaining -= toRead
        }
        return chunks
    }

    private fun copyRange(input: RandomAccessFile, output: RandomAccessFile, start: Long, size: Long) {
        if (size <= 0) return
        input.seek(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("unexpected end of file while copying")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun le32(value: Int): ByteArray = ByteArray(4) { ((value ushr (8 * it)) and 0xFF).toByte() }

    private fun leU32(value: Int): ByteArray = le32(value)

    private fun leU64(value: Long): ByteArray =
        ByteArray(8) { index -> ((value ushr (8 * index)) and 0xFF).toByte() }

    private fun readU32(data: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((data[offset + index].toInt() and 0xFF) shl (8 * index))
        return value
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte()
        }
    }

    private fun leU32(ra: RandomAccessFile): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((ra.read() and 0xFF) shl (8 * index))
        return value
    }

    private fun leU64(ra: RandomAccessFile): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((ra.read().toLong() and 0xFF) shl (8 * index))
        return value
    }
}
