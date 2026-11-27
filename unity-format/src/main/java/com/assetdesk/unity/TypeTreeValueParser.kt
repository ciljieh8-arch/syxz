package com.assetdesk.unity

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.AssetFieldNode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.PPtrReference
import com.assetdesk.model.TypeTreeNode
import com.assetdesk.model.TypeTreeSource
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

/**
 * Decodes raw Unity object bytes against a TypeTreeNode hierarchy into a structured AssetFieldNode tree.
 */
object TypeTreeValueParser {

  private const val MAX_DISPLAY_ARRAY_ELEMENTS = 64

  fun decode(
    pathId: Long,
    className: String,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    rootTypeNode: TypeTreeNode?,
    typeTreeSource: TypeTreeSource,
  ): DecodedAssetData {
    if (rootTypeNode == null) {
      return DecodedAssetData(
        pathId = pathId,
        className = className,
        typeTreeSource = TypeTreeSource.UNAVAILABLE,
        byteOrder = byteOrder,
        rootNode = null,
        pptrReferences = emptyList(),
        bytesConsumed = 0L,
        expectedBytes = rawBytes.size.toLong(),
        decodeWarning = "No embedded TypeTree or fallback schema available for $className.",
      )
    }

    val pptrs = mutableListOf<PPtrReference>()
    val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
    var warning: String? = null

    val rootField = try {
      readNode(reader, rootTypeNode, pptrs)
    } catch (t: Throwable) {
      warning = "Partial decode stopped at offset 0x${reader.position.toString(16)}: ${t.message}"
      null
    }

    if (warning == null && reader.position != rawBytes.size.toLong()) {
      warning = "Decoded ${reader.position} of ${rawBytes.size} bytes (remaining ${rawBytes.size - reader.position} bytes)."
    }

    return DecodedAssetData(
      pathId = pathId,
      className = className,
      typeTreeSource = typeTreeSource,
      byteOrder = byteOrder,
      rootNode = rootField,
      pptrReferences = pptrs,
      bytesConsumed = reader.position,
      expectedBytes = rawBytes.size.toLong(),
      decodeWarning = warning,
    )
  }

  private fun readNode(
    reader: BinaryReader,
    node: TypeTreeNode,
    pptrs: MutableList<PPtrReference>,
  ): AssetFieldNode {
    val startOffset = reader.position
    val type = node.typeName

    val result = when {
      type == "string" -> {
        val arrayNode = node.children.firstOrNull { it.isArray || it.typeName == "Array" }
        val strLen = reader.readInt32().coerceAtLeast(0)
        val clampedLen = min(strLen.toLong(), reader.remaining).toInt()
        val bytes = reader.readBytes(clampedLen)
        reader.align(4)
        val preview = decodeStringPreview(bytes)
        AssetFieldNode(
          typeName = "string",
          fieldName = node.name,
          level = node.level,
          byteOffset = startOffset,
          byteLength = reader.position - startOffset,
          valueText = "\"$preview\"" + if (bytes.size > 120) " (${bytes.size} bytes)" else "",
          isAligned = node.isAligned || (arrayNode?.isAligned == true),
        )
      }

      type == "TypelessData" -> {
        val count = reader.readInt32().coerceAtLeast(0)
        val clamped = min(count.toLong(), reader.remaining)
        val previewBytes = reader.readBytes(min(clamped, 16L).toInt())
        if (clamped > previewBytes.size) {
          reader.skip(clamped - previewBytes.size)
        }
        if (node.isAligned) reader.align(4)
        val hex = previewBytes.joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
        AssetFieldNode(
          typeName = "TypelessData",
          fieldName = node.name,
          level = node.level,
          byteOffset = startOffset,
          byteLength = reader.position - startOffset,
          valueText = "byte[$count] { $hex${if (count > 16) " ..." else ""} }",
          isArray = true,
          isAligned = node.isAligned,
        )
      }

      node.isArray || type == "Array" -> {
        val sizeNode = node.children.getOrNull(0)
        val elemNode = node.children.getOrNull(1)
        val count = reader.readInt32()
        val children = mutableListOf<AssetFieldNode>()
        if (count > 0 && elemNode != null) {
          val fixedElemSize = elemNode.byteSize
          if (fixedElemSize > 0 && elemNode.children.isEmpty() && count > MAX_DISPLAY_ARRAY_ELEMENTS) {
            val previewCount = min(count, 16)
            for (i in 0 until previewCount) {
              val item = readNode(reader, elemNode.copy(name = "[$i]"), pptrs)
              children.add(item)
            }
            val skipBytes = (count - previewCount).toLong() * fixedElemSize.toLong()
            reader.skip(min(skipBytes, reader.remaining))
          } else {
            val displayLimit = min(count, MAX_DISPLAY_ARRAY_ELEMENTS)
            for (i in 0 until count) {
              if (reader.remaining <= 0L) break
              val item = readNode(reader, elemNode.copy(name = "[$i]"), pptrs)
              if (i < displayLimit) {
                children.add(item)
              }
            }
          }
        }
        if (node.isAligned || sizeNode?.isAligned == true) {
          reader.align(4)
        }
        AssetFieldNode(
          typeName = "Array",
          fieldName = node.name,
          level = node.level,
          byteOffset = startOffset,
          byteLength = reader.position - startOffset,
          valueText = "size = $count",
          isArray = true,
          isAligned = node.isAligned,
          children = children,
        )
      }

      type.startsWith("PPtr<") && node.children.size >= 2 -> {
        val fileIdNode = node.children[0]
        val pathIdNode = node.children[1]
        val fileId = reader.readInt32()
        val pathId = if (pathIdNode.byteSize == 4 || pathIdNode.typeName == "int" || pathIdNode.typeName == "SInt32") {
          reader.readInt32().toLong()
        } else {
          reader.readInt64()
        }
        if (node.isAligned) reader.align(4)
        val hint = type.removePrefix("PPtr<").removeSuffix(">").removePrefix("$")
        val ref = PPtrReference(fileId = fileId, pathId = pathId, targetTypeHint = hint)
        if (!ref.isNull) {
          pptrs.add(ref)
        }
        AssetFieldNode(
          typeName = type,
          fieldName = node.name,
          level = node.level,
          byteOffset = startOffset,
          byteLength = reader.position - startOffset,
          valueText = if (ref.isNull) "Null (0, 0)" else "FileID=$fileId, PathID=$pathId",
          isAligned = node.isAligned,
          pptr = ref,
          children = listOf(
            AssetFieldNode(fileIdNode.typeName, fileIdNode.name, fileIdNode.level, startOffset, 4L, fileId.toString()),
            AssetFieldNode(pathIdNode.typeName, pathIdNode.name, pathIdNode.level, startOffset + 4L, (reader.position - startOffset - 4L).coerceAtLeast(4L), pathId.toString()),
          ),
        )
      }

      node.children.isEmpty() -> {
        val valueText = readPrimitiveValue(reader, type)
        if (node.isAligned) reader.align(4)
        AssetFieldNode(
          typeName = type,
          fieldName = node.name,
          level = node.level,
          byteOffset = startOffset,
          byteLength = reader.position - startOffset,
          valueText = valueText,
          isAligned = node.isAligned,
        )
      }

      else -> {
        val children = node.children.map { child -> readNode(reader, child, pptrs) }
        if (node.isAligned) reader.align(4)
        val summary = buildStructSummary(type, children)
        AssetFieldNode(
          typeName = type,
          fieldName = node.name,
          level = node.level,
          byteOffset = startOffset,
          byteLength = reader.position - startOffset,
          valueText = summary,
          isAligned = node.isAligned,
          children = children,
        )
      }
    }

    return result
  }

  private fun readPrimitiveValue(reader: BinaryReader, type: String): String =
    when (type) {
      "bool" -> reader.readBoolean().toString()
      "SInt8", "char" -> reader.readInt8().toString()
      "UInt8", "unsigned char" -> reader.readUInt8().toString()
      "SInt16", "short" -> reader.readInt16().toString()
      "UInt16", "unsigned short" -> reader.readUInt16().toString()
      "SInt32", "int" -> reader.readInt32().toString()
      "UInt32", "unsigned int" -> reader.readUInt32().toString()
      "SInt64", "long long" -> reader.readInt64().toString()
      "UInt64", "unsigned long long", "FileSize" -> reader.readUInt64().toString()
      "float" -> String.format(Locale.ROOT, "%.5g", reader.readFloat32())
      "double" -> String.format(Locale.ROOT, "%.8g", reader.readFloat64())
      else -> {
        if (reader.remaining >= 4L) {
          "0x${reader.readUInt32().toString(16)}"
        } else if (reader.remaining > 0L) {
          val rem = reader.readBytes(reader.remaining.toInt())
          rem.joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
        } else {
          "<empty>"
        }
      }
    }

  private fun buildStructSummary(type: String, children: List<AssetFieldNode>): String {
    if (children.isEmpty()) return "{}"
    if (type in setOf("Vector2f", "Vector3f", "Vector4f", "Quaternionf", "ColorRGBA")) {
      return children.joinToString(", ", prefix = "(", postfix = ")") { "${it.fieldName}=${it.valueText}" }
    }
    if (type == "vector" && children.size == 1 && children[0].isArray) {
      return children[0].valueText
    }
    return "{ ${children.size} fields }"
  }

  private fun decodeStringPreview(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val slice = if (bytes.size > 120) bytes.copyOf(120) else bytes
    val raw = String(slice, StandardCharsets.UTF_8)
    return raw.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
  }
}
