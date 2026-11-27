package com.assetdesk.export

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.TypeTreeNode
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

/**
 * UABE-compatible indented text dump exporter and importer validated against TypeTree schemas.
 */
object AssetDumpCodec {

  fun exportDump(
    rootType: TypeTreeNode,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
  ): Result<String> {
    return try {
      val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
      val sb = StringBuilder()
      writeDumpNode(reader, rootType, indent = 0, nameOverride = null, sb = sb)
      Result.success(sb.toString())
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  fun importDump(
    dumpText: String,
    rootType: TypeTreeNode,
    byteOrder: ByteOrderMode,
  ): Result<ByteArray> {
    return try {
      val lines = dumpText.lines()
        .mapIndexedNotNull { idx, raw ->
          val trimmed = raw.trimEnd()
          if (trimmed.isBlank()) null else parseDumpLine(idx + 1, trimmed)
        }
      if (lines.isEmpty()) {
        return Result.failure(IllegalArgumentException("Dump text is empty."))
      }

      val cursor = LineCursor(lines)
      val writer = BinaryWriter(byteOrder)
      serializeFromDump(cursor, rootType, writer, expectedIndent = lines[0].indent)
      Result.success(writer.toByteArray())
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  private fun writeDumpNode(
    reader: BinaryReader,
    node: TypeTreeNode,
    indent: Int,
    nameOverride: String?,
    sb: StringBuilder,
  ) {
    val prefix = " ".repeat(indent)
    val flagBit = if (node.isAligned) 1 else 0
    val displayName = nameOverride ?: node.name
    val type = node.typeName

    when {
      type == "string" -> {
        val len = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
        val bytes = reader.readBytes(len)
        reader.align(4)
        val escaped = escapeDumpString(String(bytes, StandardCharsets.UTF_8))
        sb.append("$prefix$flagBit string $displayName = \"$escaped\"\n")
      }

      type == "TypelessData" -> {
        val count = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
        val bytes = reader.readBytes(count)
        if (node.isAligned) reader.align(4)
        val hex = bytes.joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
        sb.append("$prefix$flagBit TypelessData $displayName = [$count] $hex\n")
      }

      node.isArray || type == "Array" -> {
        val sizeNode = node.children.getOrNull(0)
        val elemNode = node.children.getOrNull(1)
        val count = reader.readInt32().coerceAtLeast(0)
        sb.append("$prefix$flagBit Array $displayName\n")
        sb.append("${" ".repeat(indent + 1)}0 int size = $count\n")
        if (elemNode != null) {
          for (i in 0 until count) {
            if (reader.remaining <= 0L) break
            sb.append("${" ".repeat(indent + 1)}[$i]\n")
            writeDumpNode(reader, elemNode, indent + 2, elemNode.name, sb)
          }
        }
        if (node.isAligned || sizeNode?.isAligned == true) {
          reader.align(4)
        }
      }

      node.children.isEmpty() -> {
        val valueStr = readPrimitiveText(reader, type)
        if (node.isAligned) reader.align(4)
        sb.append("$prefix$flagBit $type $displayName = $valueStr\n")
      }

      else -> {
        sb.append("$prefix$flagBit $type $displayName\n")
        for (child in node.children) {
          writeDumpNode(reader, child, indent + 1, null, sb)
        }
        if (node.isAligned) reader.align(4)
      }
    }
  }

  private fun readPrimitiveText(reader: BinaryReader, type: String): String =
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
      "float" -> reader.readFloat32().toString()
      "double" -> reader.readFloat64().toString()
      else -> reader.readInt32().toString()
    }

  private fun serializeFromDump(
    cursor: LineCursor,
    node: TypeTreeNode,
    writer: BinaryWriter,
    expectedIndent: Int,
  ) {
    val line = cursor.next()
      ?: throw IllegalArgumentException("Unexpected end of dump while expecting ${node.typeName} ${node.name}.")

    val type = node.typeName
    when {
      type == "string" -> {
        val rawVal = line.value
          ?: throw IllegalArgumentException("Line ${line.lineNumber}: expected string value for ${node.name}")
        val unescaped = unescapeDumpString(rawVal.removeSurrounding("\""))
        writer.writeLengthPrefixedString(unescaped, alignAfter = true)
      }

      type == "TypelessData" -> {
        val rawVal = line.value ?: ""
        val afterBracket = rawVal.substringAfter(']').trim()
        val bytes = if (afterBracket.isBlank()) {
          ByteArray(0)
        } else {
          afterBracket.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
        }
        writer.writeInt32(bytes.size)
        writer.writeBytes(bytes)
        if (node.isAligned) writer.align(4)
      }

      node.isArray || type == "Array" -> {
        val sizeLine = cursor.next()
          ?: throw IllegalArgumentException("Line ${line.lineNumber}: missing Array size line.")
        val count = sizeLine.value?.toIntOrNull()
          ?: throw IllegalArgumentException("Line ${sizeLine.lineNumber}: invalid Array size '${sizeLine.value}'.")
        writer.writeInt32(count)
        val elemNode = node.children.getOrNull(1)
        if (count > 0 && elemNode != null) {
          for (i in 0 until count) {
            val peek = cursor.peek()
            if (peek != null && peek.isIndexMarker) {
              cursor.next()
            }
            serializeFromDump(cursor, elemNode, writer, expectedIndent + 2)
          }
        }
        if (node.isAligned || node.children.getOrNull(0)?.isAligned == true) {
          writer.align(4)
        }
      }

      node.children.isEmpty() -> {
        val rawVal = line.value
          ?: throw IllegalArgumentException("Line ${line.lineNumber}: missing value for $type ${node.name}")
        writePrimitiveText(writer, type, rawVal, line.lineNumber)
        if (node.isAligned) writer.align(4)
      }

      else -> {
        for (child in node.children) {
          serializeFromDump(cursor, child, writer, expectedIndent + 1)
        }
        if (node.isAligned) writer.align(4)
      }
    }
  }

  private fun writePrimitiveText(writer: BinaryWriter, type: String, raw: String, lineNumber: Int) {
    try {
      when (type) {
        "bool" -> writer.writeBoolean(raw.toBooleanStrict())
        "SInt8", "char" -> writer.writeInt8(raw.toByte())
        "UInt8", "unsigned char" -> writer.writeUInt8(raw.toInt())
        "SInt16", "short" -> writer.writeInt16(raw.toShort())
        "UInt16", "unsigned short" -> writer.writeUInt16(raw.toInt())
        "SInt32", "int" -> writer.writeInt32(raw.toInt())
        "UInt32", "unsigned int" -> writer.writeUInt32(raw.toLong())
        "SInt64", "long long" -> writer.writeInt64(raw.toLong())
        "UInt64", "unsigned long long", "FileSize" -> writer.writeInt64(raw.toULong().toLong())
        "float" -> writer.writeFloat32(raw.toFloat())
        "double" -> writer.writeFloat64(raw.toDouble())
        else -> writer.writeInt32(raw.toInt())
      }
    } catch (t: Throwable) {
      throw IllegalArgumentException("Line $lineNumber: invalid value '$raw' for type $type", t)
    }
  }

  private fun escapeDumpString(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

  private fun unescapeDumpString(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val ch = s[i]
      if (ch == '\\' && i + 1 < s.length) {
        when (s[i + 1]) {
          '\\' -> { sb.append('\\'); i += 2 }
          '"' -> { sb.append('"'); i += 2 }
          'n' -> { sb.append('\n'); i += 2 }
          'r' -> { sb.append('\r'); i += 2 }
          't' -> { sb.append('\t'); i += 2 }
          else -> { sb.append(ch); i++ }
        }
      } else {
        sb.append(ch)
        i++
      }
    }
    return sb.toString()
  }

  private data class DumpLine(
    val lineNumber: Int,
    val indent: Int,
    val isIndexMarker: Boolean,
    val headerText: String,
    val value: String?,
  )

  private class LineCursor(private val lines: List<DumpLine>) {
    private var idx = 0
    fun peek(): DumpLine? = lines.getOrNull(idx)
    fun next(): DumpLine? = if (idx < lines.size) lines[idx++] else null
  }

  private fun parseDumpLine(lineNumber: Int, raw: String): DumpLine {
    var indent = 0
    while (indent < raw.length && raw[indent] == ' ') indent++
    val body = raw.substring(indent)
    if (body.startsWith("[") && body.endsWith("]")) {
      return DumpLine(lineNumber, indent, isIndexMarker = true, headerText = body, value = null)
    }
    val eqIdx = body.indexOf(" = ")
    return if (eqIdx >= 0) {
      DumpLine(
        lineNumber = lineNumber,
        indent = indent,
        isIndexMarker = false,
        headerText = body.substring(0, eqIdx).trim(),
        value = body.substring(eqIdx + 3),
      )
    } else {
      DumpLine(
        lineNumber = lineNumber,
        indent = indent,
        isIndexMarker = false,
        headerText = body.trim(),
        value = null,
      )
    }
  }
}
