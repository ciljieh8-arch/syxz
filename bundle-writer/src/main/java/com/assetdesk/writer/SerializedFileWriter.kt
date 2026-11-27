package com.assetdesk.writer

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.SeekableByteSource
import com.assetdesk.model.SerializedTypeInfo
import com.assetdesk.model.TypeTreeNode
import com.assetdesk.unity.ParsedSerializedFile
import com.assetdesk.unity.SerializedFileParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Safely serializes modified Little-Endian Unity SerializedFiles (v9 .. v22+),
 * aligns object payloads, recalculates offsets/sizes, and re-parses the output for integrity verification.
 */
object SerializedFileWriter {

  fun writeAndVerifyToTempFile(
    originalSource: SeekableByteSource,
    parsedFile: ParsedSerializedFile,
    tempDirectory: File,
  ): Result<File> {
    if (parsedFile.header.isReadOnly || parsedFile.header.endianness == ByteOrderMode.BIG_ENDIAN) {
      return Result.failure(
        UnsupportedOperationException(
          parsedFile.header.readOnlyReason ?: "Big-Endian SerializedFile writing is disabled to prevent file corruption.",
        ),
      )
    }

    val version = parsedFile.header.formatVersion
    if (version < 9 || version > 25) {
      return Result.failure(
        UnsupportedOperationException("SerializedFile format v$version is outside safe writer support (v9..v25)."),
      )
    }

    val tempFile = File.createTempFile("assetdesk_sf_write_", ".assets.tmp", tempDirectory)
    return try {
      val serializedBytes = serializeToByteArray(originalSource, parsedFile)
      FileOutputStream(tempFile).use { fos ->
        fos.write(serializedBytes)
        fos.flush()
      }

      // Mandatory round-trip validation using SerializedFileParser before any replacement
      val raf = RandomAccessFile(tempFile, "r")
      com.assetdesk.binary.FileChannelByteSource.fromRandomAccessFile(raf).use { verifySource ->
        val reParsed = SerializedFileParser.parse(verifySource, parsedFile.fileName)
        if (reParsed.isFailure) {
          tempFile.delete()
          return Result.failure(
            IllegalStateException(
              "Write verification failed; output aborted: ${reParsed.exceptionOrNull()?.message}",
              reParsed.exceptionOrNull(),
            ),
          )
        }
        val verified = reParsed.getOrThrow()
        if (verified.objects.size != parsedFile.objects.size) {
          tempFile.delete()
          return Result.failure(
            IllegalStateException(
              "Write verification mismatch: expected ${parsedFile.objects.size} objects, re-parsed ${verified.objects.size}.",
            ),
          )
        }
      }
      Result.success(tempFile)
    } catch (t: Throwable) {
      tempFile.delete()
      Result.failure(t)
    }
  }

  fun serializeToByteArray(
    originalSource: SeekableByteSource,
    parsedFile: ParsedSerializedFile,
  ): ByteArray {
    if (parsedFile.header.isReadOnly || parsedFile.header.endianness == ByteOrderMode.BIG_ENDIAN) {
      throw UnsupportedOperationException("Big-Endian SerializedFile writing is disabled.")
    }

    val version = parsedFile.header.formatVersion
    val endian = parsedFile.header.endianness

    // 1. Assemble object payloads sequentially with 8-byte alignment
    val dataStream = ByteArrayOutputStream()
    val relativeStarts = LongArray(parsedFile.objects.size)
    val objectSizes = LongArray(parsedFile.objects.size)

    var dataCursor = 0L
    for ((idx, obj) in parsedFile.objects.withIndex()) {
      val pad = ((8L - (dataCursor % 8L)) % 8L).toInt()
      if (pad > 0) {
        dataStream.write(ByteArray(pad))
        dataCursor += pad
      }
      val payload = obj.modifiedRawBytes
        ?: SerializedFileParser.readObjectRawBytes(originalSource, obj)
      relativeStarts[idx] = dataCursor
      objectSizes[idx] = payload.size.toLong()
      dataStream.write(payload)
      dataCursor += payload.size.toLong()
    }
    val dataBytes = dataStream.toByteArray()

    // 2. Write metadata section
    val metaWriter = BinaryWriter(endian)
    if (version >= 7) {
      metaWriter.writeNullTerminatedString(parsedFile.unityVersion)
    }
    if (version >= 8) {
      metaWriter.writeInt32(parsedFile.targetPlatform)
    }
    if (version >= 13) {
      metaWriter.writeBoolean(parsedFile.enableTypeTree)
    }

    metaWriter.writeInt32(parsedFile.types.size)
    for (type in parsedFile.types) {
      writeSerializedType(metaWriter, type, version, parsedFile.enableTypeTree)
    }

    if (version in 7..13) {
      metaWriter.writeInt32(0) // bigIdEnabled
    }

    metaWriter.writeInt32(parsedFile.objects.size)
    for ((idx, obj) in parsedFile.objects.withIndex()) {
      if (version >= 14) {
        metaWriter.align(4)
        metaWriter.writeInt64(obj.pathId)
      } else {
        metaWriter.writeInt32(obj.pathId.toInt())
      }

      if (version >= 22) {
        metaWriter.writeInt64(relativeStarts[idx])
      } else {
        metaWriter.writeUInt32(relativeStarts[idx])
      }
      metaWriter.writeUInt32(objectSizes[idx])
      metaWriter.writeInt32(obj.typeId)

      if (version < 16) {
        metaWriter.writeInt16(obj.classId.toShort())
        metaWriter.writeUInt16(if (obj.isDestroyed) 1 else 0)
        if (version == 15) {
          metaWriter.writeUInt8(if (obj.isStripped) 1 else 0)
        }
      } else if (version == 16) {
        metaWriter.writeUInt16(if (obj.isDestroyed) 1 else 0)
        metaWriter.writeUInt8(if (obj.isStripped) 1 else 0)
      }
    }

    if (version >= 11) {
      metaWriter.writeInt32(parsedFile.scriptTypes.size)
      for (script in parsedFile.scriptTypes) {
        metaWriter.writeInt32(script.localSerializedFileIndex)
        if (version >= 14) {
          metaWriter.align(4)
          metaWriter.writeInt64(script.localIdentifierInFile)
        } else {
          metaWriter.writeInt32(script.localIdentifierInFile.toInt())
        }
      }
    }

    metaWriter.writeInt32(parsedFile.externals.size)
    for (ext in parsedFile.externals) {
      if (version >= 6) {
        metaWriter.writeNullTerminatedString(ext.tempEmpty)
      }
      if (version >= 5) {
        metaWriter.writeBytes(parseHex16(ext.guid))
        metaWriter.writeInt32(ext.type)
      }
      metaWriter.writeNullTerminatedString(ext.pathName)
    }

    if (version >= 20) {
      metaWriter.writeInt32(0) // refTypeCount
    }

    if (version >= 5 && parsedFile.userInformation.isNotEmpty()) {
      metaWriter.writeNullTerminatedString(parsedFile.userInformation)
    }

    val metadataBytes = metaWriter.toByteArray()
    val headerSize = if (version >= 22) 48L else 20L
    val unalignedDataOffset = headerSize + metadataBytes.size.toLong()
    val dataOffset = ((unalignedDataOffset + 15L) / 16L) * 16L
    val headerPadding = (dataOffset - unalignedDataOffset).toInt()
    val totalFileSize = dataOffset + dataBytes.size.toLong()

    // 3. Write Big-Endian header + metadata + padding + data
    val finalWriter = BinaryWriter(ByteOrderMode.BIG_ENDIAN, totalFileSize.toInt())
    if (version >= 22) {
      finalWriter.writeInt32(0)
      finalWriter.writeInt32(0)
      finalWriter.writeInt32(version)
      finalWriter.writeInt32(0)
      finalWriter.writeUInt8(0) // Little Endian
      finalWriter.writeZeroBytes(3L)
      finalWriter.writeUInt32(metadataBytes.size.toLong())
      finalWriter.writeInt64(totalFileSize)
      finalWriter.writeInt64(dataOffset)
      finalWriter.writeInt64(0L)
    } else {
      finalWriter.writeUInt32(metadataBytes.size.toLong())
      finalWriter.writeUInt32(totalFileSize)
      finalWriter.writeInt32(version)
      finalWriter.writeUInt32(dataOffset)
      finalWriter.writeUInt8(0) // Little Endian
      finalWriter.writeZeroBytes(3L)
    }

    finalWriter.writeBytes(metadataBytes)
    if (headerPadding > 0) {
      finalWriter.writeZeroBytes(headerPadding.toLong())
    }
    finalWriter.writeBytes(dataBytes)

    return finalWriter.toByteArray()
  }

  private fun writeSerializedType(
    writer: BinaryWriter,
    type: SerializedTypeInfo,
    version: Int,
    enableTypeTree: Boolean,
  ) {
    writer.writeInt32(type.classId)
    if (version >= 16) {
      writer.writeBoolean(type.isStrippedType)
    }
    if (version >= 17) {
      writer.writeInt16(type.scriptTypeIndex)
    }
    if (version >= 13) {
      val needsScriptHash = (version < 16 && type.classId < 0) || (version >= 16 && type.classId == 114)
      if (needsScriptHash) {
        writer.writeBytes(type.scriptIdHash ?: ByteArray(16))
      }
      writer.writeBytes(type.typeHash ?: ByteArray(16))
    }

    if (enableTypeTree) {
      val root = type.rootNode
      if (version >= 12 || version == 10) {
        writeTypeTreeBlob(writer, root, version)
      } else if (root != null) {
        writeLegacyTypeTreeNode(writer, root)
      }
      if (version >= 21) {
        writer.writeInt32(type.typeDependencies.size)
        for (dep in type.typeDependencies) {
          writer.writeInt32(dep)
        }
      }
    }
  }

  private fun writeTypeTreeBlob(
    writer: BinaryWriter,
    root: TypeTreeNode?,
    version: Int,
  ) {
    if (root == null) {
      writer.writeInt32(0)
      writer.writeInt32(0)
      return
    }
    val flat = mutableListOf<TypeTreeNode>()
    fun collect(node: TypeTreeNode) {
      flat.add(node)
      node.children.forEach { collect(it) }
    }
    collect(root)

    val stringBuffer = ByteArrayOutputStream()
    val stringOffsets = mutableMapOf<String, Int>()
    fun internString(text: String): Int {
      return stringOffsets.getOrPut(text) {
        val offset = stringBuffer.size()
        stringBuffer.write(text.toByteArray(Charsets.UTF_8))
        stringBuffer.write(0)
        offset
      }
    }

    val nodeOffsets = flat.map { n ->
      internString(n.typeName) to internString(n.name)
    }

    writer.writeInt32(flat.size)
    writer.writeInt32(stringBuffer.size())
    for ((idx, node) in flat.withIndex()) {
      val (typeOff, nameOff) = nodeOffsets[idx]
      writer.writeUInt16(node.version)
      writer.writeUInt8(node.level)
      writer.writeUInt8(node.typeFlags)
      writer.writeInt32(typeOff)
      writer.writeInt32(nameOff)
      writer.writeInt32(node.byteSize)
      writer.writeInt32(node.index)
      writer.writeInt32(node.metaFlag)
      if (version >= 19) {
        writer.writeInt64(0L)
      }
    }
    writer.writeBytes(stringBuffer.toByteArray())
  }

  private fun writeLegacyTypeTreeNode(writer: BinaryWriter, node: TypeTreeNode) {
    writer.writeNullTerminatedString(node.typeName)
    writer.writeNullTerminatedString(node.name)
    writer.writeInt32(node.byteSize)
    writer.writeInt32(node.index)
    writer.writeInt32(node.typeFlags)
    writer.writeInt32(node.version)
    writer.writeInt32(node.metaFlag)
    writer.writeInt32(node.children.size)
    node.children.forEach { writeLegacyTypeTreeNode(writer, it) }
  }

  private fun parseHex16(hex: String): ByteArray {
    if (hex.length != 32) return ByteArray(16)
    return ByteArray(16) { i ->
      hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }
}
