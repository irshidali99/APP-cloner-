package com.example.engine.core

/**
 * One attribute of an AXML start element. Every index refers to the [StringPool] of the document.
 *
 * For a plain string attribute (like `android:name=".MainActivity"`) [rawValue], [valueData] hold the same
 * string index and [valueType] is `RES_VALUE_TYPE_STRING`. For a compiled resource reference
 * (`android:icon="@mipmap/ic_launcher"`) [valueType] is `RES_VALUE_TYPE_REFERENCE` and [valueData] holds
 * the packed resource id (`0xPPTTEEEE`).
 */
class AxmlAttribute(
    var namespace: Int,
    var name: Int,
    var rawValue: Int,
    var valueType: Int,
    var valueData: Int
)

/** A node of the AXML document, kept in document order. */
sealed class AxmlNode {
    var line: Int = -1
    var comment: Int = NO_INDEX

    class StartNamespace(var prefix: Int, var uri: Int) : AxmlNode()

    class EndNamespace(var prefix: Int, var uri: Int) : AxmlNode()

    class StartElement(
        var namespace: Int,
        var name: Int,
        var attributeIdIndex: Int,
        var classIndex: Int,
        var styleIndex: Int,
        val attributes: MutableList<AxmlAttribute> = ArrayList()
    ) : AxmlNode()

    class EndElement(var namespace: Int, var name: Int) : AxmlNode()

    class CData(var data: Int) : AxmlNode()
}

/**
 * Parser and serializer for Android's *binary* XML (`AndroidManifest.xml` inside an APK).
 *
 * The editor keeps the original string pool order, so the resource-id map chunk (which maps
 * `pool[index] -> attribute resource id` for the first N entries) stays valid. New strings are appended
 * at the end of the pool which keeps every existing index intact - that is what makes in-place
 * modification of values such as the `package` attribute safe.
 */
class AxmlEditor private constructor(
    val pool: StringPool,
    var resourceIds: IntArray,
    val nodes: MutableList<AxmlNode>
) {
    /** Resolves a pool index to its string, `null` for "no index". */
    fun string(index: Int): String? = pool.get(index)

    /** Returns the index of [value], appending it to the pool when needed. */
    fun intern(value: String): Int = pool.intern(value)

    /** Resource id of an attribute *name* string, or 0 when the document has no resource map entry. */
    fun attributeResourceId(nameIndex: Int): Int =
        if (nameIndex in resourceIds.indices) resourceIds[nameIndex] else 0

    fun startElements(): List<AxmlNode.StartElement> = nodes.filterIsInstance<AxmlNode.StartElement>()

    fun elementName(element: AxmlNode.StartElement): String? = string(element.name)

    /** Name of an attribute, resolved through the pool. */
    fun attributeName(attribute: AxmlAttribute): String? = string(attribute.name)

    /** Namespace URI of an attribute, resolved through the pool. */
    fun attributeNamespace(attribute: AxmlAttribute): String? = string(attribute.namespace)

    /**
     * Finds an attribute by namespace URI and name. Passing a `null` namespace matches attributes that
     * have no namespace at all (such as the manifest's own `package` attribute).
     */
    fun findAttribute(
        element: AxmlNode.StartElement,
        namespaceUri: String?,
        name: String
    ): AxmlAttribute? {
        val namespaceIndex = if (namespaceUri == null) NO_INDEX else pool.indexOf(namespaceUri)
        return element.attributes.firstOrNull { attribute ->
            string(attribute.name) == name &&
                (attribute.namespace == namespaceIndex ||
                    (namespaceIndex == NO_INDEX && attribute.namespace == NO_INDEX))
        }
    }

    /** Replaces an attribute value with a plain string. */
    fun setStringAttribute(attribute: AxmlAttribute, value: String) {
        val index = intern(value)
        attribute.rawValue = index
        attribute.valueType = ResValueType.STRING
        attribute.valueData = index
    }

    /** Replaces an attribute value with an integer (plain int, enum, flags or boolean). */
    fun setIntAttribute(attribute: AxmlAttribute, valueType: Int, value: Int) {
        attribute.rawValue = value
        attribute.valueType = valueType
        attribute.valueData = value
    }

    /** Resource id of an attribute, resolved through its name index. */
    fun attributeResourceId(attribute: AxmlAttribute): Int = attributeResourceId(attribute.name)

    /**
     * Adds an attribute the original manifest does not have yet.
     *
     * The attribute name is appended to the string pool and the resource map is extended: the platform
     * looks framework attributes up by *resource id*, not by name, so a missing map entry would make the
     * attribute silently ignored. Attributes are inserted sorted by resource id, the order `aapt2` emits.
     */
    fun addAttribute(
        element: AxmlNode.StartElement,
        namespaceUri: String?,
        name: String,
        resourceId: Int,
        valueType: Int,
        valueData: Int,
        rawValue: Int = valueData
    ): AxmlAttribute {
        val nameIndex = intern(name)
        ensureResourceId(nameIndex, resourceId)
        val namespaceIndex = if (namespaceUri == null) NO_INDEX else intern(namespaceUri)
        val attribute = AxmlAttribute(namespaceIndex, nameIndex, rawValue, valueType, valueData)
        val position = element.attributes.indexOfFirst { existing ->
            attributeResourceId(existing) > resourceId
        }
        if (position < 0) element.attributes.add(attribute) else element.attributes.add(position, attribute)
        return attribute
    }

    /** Removes an element together with everything nested inside it. */
    fun removeElement(element: AxmlNode.StartElement) {
        val start = nodes.indexOf(element)
        if (start < 0) return
        var depth = 0
        var index = start
        while (index < nodes.size) {
            when (nodes[index]) {
                is AxmlNode.StartElement -> depth++
                is AxmlNode.EndElement -> {
                    depth--
                    if (depth == 0) {
                        repeat(index - start + 1) { nodes.removeAt(start) }
                        return
                    }
                }
                else -> Unit
            }
            index++
        }
    }

    /** Serialises the document back into a binary XML file. */
    fun toByteArray(): ByteArray {
        val writer = LeWriter(estimateSize())
        writer.u16(ResType.XML)
        writer.u16(CHUNK_HEADER_SIZE)
        writer.u32(0) // patched at the end

        writer.bytes(pool.build())

        if (resourceIds.isNotEmpty()) {
            writer.u16(ResType.XML_RESOURCE_MAP)
            writer.u16(CHUNK_HEADER_SIZE)
            writer.u32(CHUNK_HEADER_SIZE + resourceIds.size * 4)
            for (id in resourceIds) writer.u32(id)
        }

        for (node in nodes) writeNode(writer, node)

        val bytes = writer.toByteArray()
        writeU32(bytes, 4, bytes.size)
        return bytes
    }

    private fun estimateSize(): Int {
        var size = 64 + pool.stringCount * 24
        for (node in nodes) {
            size += when (node) {
                is AxmlNode.StartElement -> 36 + node.attributes.size * 20
                is AxmlNode.StartNamespace, is AxmlNode.EndNamespace, is AxmlNode.EndElement -> 24
                is AxmlNode.CData -> 20
            }
        }
        return size
    }

    private fun writeNode(writer: LeWriter, node: AxmlNode) {
        when (node) {
            is AxmlNode.StartNamespace -> {
                writer.u16(ResType.XML_START_NAMESPACE)
                writer.u16(16)
                writer.u32(24)
                writer.u32(node.line)
                writer.u32(node.comment)
                writer.u32(node.prefix)
                writer.u32(node.uri)
            }

            is AxmlNode.EndNamespace -> {
                writer.u16(ResType.XML_END_NAMESPACE)
                writer.u16(16)
                writer.u32(24)
                writer.u32(node.line)
                writer.u32(node.comment)
                writer.u32(node.prefix)
                writer.u32(node.uri)
            }

            is AxmlNode.StartElement -> {
                val attributeCount = node.attributes.size
                writer.u16(ResType.XML_START_ELEMENT)
                writer.u16(16)
                writer.u32(36 + attributeCount * 20)
                writer.u32(node.line)
                writer.u32(node.comment)
                writer.u32(node.namespace)
                writer.u32(node.name)
                writer.u16(20) // attributeStart, relative to the extension
                writer.u16(20) // attributeSize
                writer.u16(attributeCount)
                writer.u16(node.attributeIdIndex)
                writer.u16(node.classIndex)
                writer.u16(node.styleIndex)
                for (attribute in node.attributes) {
                    writer.u32(attribute.namespace)
                    writer.u32(attribute.name)
                    writer.u32(attribute.rawValue)
                    writer.u16(8) // Res_value.size
                    writer.u8(0) // Res_value.res0
                    writer.u8(attribute.valueType)
                    writer.u32(attribute.valueData)
                }
            }

            is AxmlNode.EndElement -> {
                writer.u16(ResType.XML_END_ELEMENT)
                writer.u16(16)
                writer.u32(24)
                writer.u32(node.line)
                writer.u32(node.comment)
                writer.u32(node.namespace)
                writer.u32(node.name)
            }

            is AxmlNode.CData -> {
                writer.u16(ResType.XML_CDATA)
                writer.u16(16)
                writer.u32(20)
                writer.u32(node.line)
                writer.u32(node.comment)
                writer.u32(node.data)
            }
        }
    }

    companion object {
        /** Parses a binary XML document (the contents of `AndroidManifest.xml` inside an APK). */
        fun parse(data: ByteArray): AxmlEditor {
            require(data.size >= CHUNK_HEADER_SIZE) { "file is too small to be a binary XML document" }
            require(data.u16(0) == ResType.XML) { "not a binary XML document" }

            val fileSize = minOf(data.u32(4).takeIf { it > 0 } ?: data.size, data.size)
            var pool: StringPool? = null
            var resourceIds = IntArray(0)
            val nodes = ArrayList<AxmlNode>()

            var position = data.chunkHeaderSize(0)
            while (position + CHUNK_HEADER_SIZE <= fileSize) {
                val type = data.u16(position)
                val headerSize = data.chunkHeaderSize(position)
                val size = data.chunkSize(position)
                if (size < CHUNK_HEADER_SIZE || position + size > data.size) break

                when (type) {
                    ResType.STRING_POOL -> pool = StringPool.parse(data, position)

                    ResType.XML_RESOURCE_MAP -> {
                        val count = (size - headerSize) / 4
                        resourceIds = IntArray(maxOf(count, 0)) { data.u32(position + headerSize + it * 4) }
                    }

                    ResType.XML_START_NAMESPACE -> nodes.add(
                        AxmlNode.StartNamespace(data.u32(position + 16), data.u32(position + 20))
                            .also { it.readNodeHeader(data, position) }
                    )

                    ResType.XML_END_NAMESPACE -> nodes.add(
                        AxmlNode.EndNamespace(data.u32(position + 16), data.u32(position + 20))
                            .also { it.readNodeHeader(data, position) }
                    )

                    ResType.XML_START_ELEMENT -> nodes.add(readStartElement(data, position))

                    ResType.XML_END_ELEMENT -> nodes.add(
                        AxmlNode.EndElement(data.u32(position + 16), data.u32(position + 20))
                            .also { it.readNodeHeader(data, position) }
                    )

                    ResType.XML_CDATA -> nodes.add(
                        AxmlNode.CData(data.u32(position + 16))
                            .also { it.readNodeHeader(data, position) }
                    )
                }
                position += size
            }

            val stringPool = pool ?: throw IllegalArgumentException("binary XML document has no string pool")
            return AxmlEditor(stringPool, resourceIds, nodes)
        }

        private fun readStartElement(data: ByteArray, position: Int): AxmlNode.StartElement {
            val element = AxmlNode.StartElement(
                namespace = data.u32(position + 16),
                name = data.u32(position + 20),
                attributeIdIndex = data.u16(position + 30),
                classIndex = data.u16(position + 32),
                styleIndex = data.u16(position + 34)
            )
            element.readNodeHeader(data, position)

            val attributeStart = data.u16(position + 24)
            val attributeSize = data.u16(position + 26)
            val attributeCount = data.u16(position + 28)
            val stride = if (attributeSize >= 20) attributeSize else 20
            var attributePosition = position + 16 + attributeStart
            for (index in 0 until attributeCount) {
                if (attributePosition + 20 > data.size) break
                element.attributes.add(
                    AxmlAttribute(
                        namespace = data.u32(attributePosition),
                        name = data.u32(attributePosition + 4),
                        rawValue = data.u32(attributePosition + 8),
                        valueType = data.u8(attributePosition + 15),
                        valueData = data.u32(attributePosition + 16)
                    )
                )
                attributePosition += stride
            }
            return element
        }

        private fun AxmlNode.readNodeHeader(data: ByteArray, position: Int) {
            line = data.u32(position + 8)
            comment = data.u32(position + 12)
        }

        private fun writeU32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }
}
