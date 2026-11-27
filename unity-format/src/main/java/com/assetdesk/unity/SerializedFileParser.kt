package com.assetdesk.unity

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.SeekableByteSource
import com.assetdesk.model.ExternalFileRef
import com.assetdesk.model.ObjectStatus
import com.assetdesk.model.SerializedTypeInfo
import com.assetdesk.model.TypeTreeNode
import com.assetdesk.model.TypeTreeSource
import com.assetdesk.model.UnityClassRegistry
import com.assetdesk.model.UnityObjectInfo
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

data class ScriptTypeRef(
  val localSerializedFileIndex: Int,
  val localIdentifierInFile: Long,
)

data class ParsedSerializedFile(
  val fileName: String,
  val header: HeaderParseResult.SerializedFileHeaderInfo,
  val unityVersion: String,
  val targetPlatform: Int,
  val enableTypeTree: Boolean,
  val types: List<SerializedTypeInfo>,
  val objects: List<UnityObjectInfo>,
  val scriptTypes: List<ScriptTypeRef>,
  val externals: List<ExternalFileRef>,
  val userInformation: String,
) {
  fun findTypeForObject(obj: UnityObjectInfo): SerializedTypeInfo? =
    if (header.formatVersion >= 17) {
      types.getOrNull(obj.typeId)
    } else {
      types.firstOrNull { it.classId == obj.typeId || it.classId == obj.classId }
    }

  fun findTypeTreeForObject(obj: UnityObjectInfo): Pair<TypeTreeNode?, TypeTreeSource> {
    val embedded = findTypeForObject(obj)?.rootNode
    if (embedded != null) {
      return embedded to TypeTreeSource.EMBEDDED
    }
    val fallback = UnityClassRegistry.getFallbackTypeTree(obj.classId)
    if (fallback != null) {
      return fallback to TypeTreeSource.BUILTIN_FALLBACK
    }
    return null to TypeTreeSource.UNAVAILABLE
  }
}

/**
 * Parses full Unity SerializedFile metadata, TypeTrees, Object Table (PathID / ByteStart / ByteSize / ClassID),
 * ScriptTypes, and External file dependencies for Unity 3.4+ (v9 .. v22+).
 */
object SerializedFileParser {

  private const val MAX_REASONABLE_COUNT = 2_000_000

  fun parse(
    source: SeekableByteSource,
    fileName: String,
    containerMap: Map<Long, String> = emptyMap(),
  ): Result<ParsedSerializedFile> {
    return try {
      val headerResult = UnityHeaderParser.parse(source, fileName)
      if (headerResult !is HeaderParseResult.SerializedFileHeaderInfo) {
        val reason = when (headerResult) {
          is HeaderParseResult.CorruptedFileInfo -> headerResult.errorMessage
          is HeaderParseResult.UnsupportedFormatInfo -> headerResult.reason
          else -> "File is not a Unity SerializedFile."
        }
        return Result.failure(IllegalArgumentException(reason))
      }

      val headerOffset = if (headerResult.isLargeFileFormat) 48L else 20L
      val reader = BinaryReader(source, headerResult.endianness)
      reader.seek(headerOffset)

      val version = headerResult.formatVersion
      val unityVersion = if (version >= 7) reader.readNullTerminatedString(maxBytes = 128) else ""
      val targetPlatform = if (version >= 8) reader.readInt32() else -1
      val enableTypeTree = if (version >= 13) reader.readBoolean() else true

      val typeCount = reader.readInt32()
      validateCount(typeCount, "typeCount")
      val types = ArrayList<SerializedTypeInfo>(min(typeCount, 1024))
      for (i in 0 until typeCount) {
        types.add(readSerializedType(reader, version, enableTypeTree, isRefType = false))
      }

      var bigIdEnabled = 0
      if (version in 7..13) {
        bigIdEnabled = reader.readInt32()
      }

      val objectCount = reader.readInt32()
      validateCount(objectCount, "objectCount")
      val rawObjects = ArrayList<RawObjectRecord>(min(objectCount, 4096))

      for (i in 0 until objectCount) {
        val pathId = when {
          bigIdEnabled != 0 -> reader.readInt64()
          version >= 14 -> {
            reader.align(4)
            reader.readInt64()
          }
          else -> reader.readInt32().toLong()
        }

        val byteStartRelative = if (version >= 22) reader.readInt64() else reader.readUInt32()
        val byteSize = reader.readUInt32()
        val typeId = reader.readInt32()

        val classId: Int
        val isDestroyed: Boolean
        val isStripped: Boolean

        when {
          version < 16 -> {
            classId = reader.readInt16().toInt()
            isDestroyed = reader.readUInt16() != 0
            isStripped = if (version == 15) reader.readUInt8() != 0 else false
          }
          version == 16 -> {
            classId = types.getOrNull(typeId)?.classId ?: typeId
            isDestroyed = reader.readUInt16() != 0
            isStripped = reader.readUInt8() != 0
          }
          else -> {
            classId = types.getOrNull(typeId)?.classId ?: typeId
            isDestroyed = false
            isStripped = types.getOrNull(typeId)?.isStrippedType ?: false
          }
        }

        rawObjects.add(
          RawObjectRecord(
            pathId = pathId,
            byteStart = headerResult.dataOffset + byteStartRelative,
            byteSize = byteSize,
            typeId = typeId,
            classId = classId,
            isDestroyed = isDestroyed,
            isStripped = isStripped,
          ),
        )
      }

      val scriptTypes = if (version >= 11) {
        val scriptCount = reader.readInt32()
        validateCount(scriptCount, "scriptCount")
        val list = ArrayList<ScriptTypeRef>(min(scriptCount, 512))
        for (i in 0 until scriptCount) {
          val fileIdx = reader.readInt32()
          val identifier = if (version >= 14) {
            reader.align(4)
            reader.readInt64()
          } else {
            reader.readInt32().toLong()
          }
          list.add(ScriptTypeRef(fileIdx, identifier))
        }
        list
      } else {
        emptyList()
      }

      val externalsCount = reader.readInt32()
      validateCount(externalsCount, "externalsCount")
      val externals = ArrayList<ExternalFileRef>(min(externalsCount, 256))
      for (i in 0 until externalsCount) {
        val tempEmpty = if (version >= 6) reader.readNullTerminatedString(256) else ""
        val guid = if (version >= 5) {
          val gBytes = reader.readBytes(16)
          gBytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        } else ""
        val extType = if (version >= 5) reader.readInt32() else 0
        val pathName = reader.readNullTerminatedString(1024)
        externals.add(ExternalFileRef(tempEmpty, guid, extType, pathName))
      }

      if (version >= 20) {
        val refTypeCount = reader.readInt32()
        validateCount(refTypeCount, "refTypeCount")
        for (i in 0 until refTypeCount) {
          readSerializedType(reader, version, enableTypeTree, isRefType = true)
        }
      }

      val userInformation = if (version >= 5 && reader.remaining > 0L) {
        runCatching { reader.readNullTerminatedString(1024) }.getOrDefault("")
      } else ""

      // Build resolved UnityObjectInfo entries and extract asset names where safe & within bounds
      val resolvedObjects = rawObjects.map { raw ->
        val className = UnityClassRegistry.getClassName(raw.classId)
        val inBounds = raw.byteStart >= headerResult.dataOffset &&
          raw.byteStart + raw.byteSize <= source.length &&
          raw.byteSize <= Int.MAX_VALUE

        val status = when {
          !inBounds -> ObjectStatus.OUT_OF_BOUNDS
          headerResult.isReadOnly -> ObjectStatus.READ_ONLY
          else -> ObjectStatus.VALID
        }

        val extractedName = if (inBounds) {
          extractAssetNameIfAvailable(source, raw, types, headerResult.formatVersion, headerResult.endianness)
        } else null

        val hasExplicit = !extractedName.isNullOrBlank()
        val displayName = if (!extractedName.isNullOrBlank()) extractedName else "<Unnamed $className>"

        UnityObjectInfo(
          pathId = raw.pathId,
          fileId = 0,
          byteStart = raw.byteStart,
          byteSize = raw.byteSize,
          typeId = raw.typeId,
          classId = raw.classId,
          className = className,
          assetName = displayName,
          hasExplicitName = hasExplicit,
          containerPath = containerMap[raw.pathId],
          isDestroyed = raw.isDestroyed,
          isStripped = raw.isStripped,
          status = status,
        )
      }

      Result.success(
        ParsedSerializedFile(
          fileName = fileName,
          header = headerResult,
          unityVersion = unityVersion,
          targetPlatform = targetPlatform,
          enableTypeTree = enableTypeTree,
          types = types,
          objects = resolvedObjects,
          scriptTypes = scriptTypes,
          externals = externals,
          userInformation = userInformation,
        ),
      )
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  fun readObjectRawBytes(
    source: SeekableByteSource,
    obj: UnityObjectInfo,
    maxAllowedBytes: Int = SeekableByteSource.DEFAULT_MAX_ALLOCATION_BYTES,
  ): ByteArray {
    obj.modifiedRawBytes?.let { return it }
    if (obj.byteStart < 0L || obj.byteStart + obj.byteSize > source.length) {
      throw IllegalStateException(
        "Object PathID=${obj.pathId} bounds [${obj.byteStart}, ${obj.byteStart + obj.byteSize}) exceed file length ${source.length}.",
      )
    }
    return source.readFullyAt(obj.byteStart, obj.byteSize.toInt(), maxAllowedBytes)
  }

  private fun readSerializedType(
    reader: BinaryReader,
    version: Int,
    enableTypeTree: Boolean,
    isRefType: Boolean,
  ): SerializedTypeInfo {
    val classId = reader.readInt32()
    val isStrippedType = if (version >= 16) reader.readBoolean() else false
    val scriptTypeIndex = if (version >= 17) reader.readInt16() else (-1).toShort()

    val scriptIdHash = if (version >= 13) {
      val needsScriptHash = (isRefType && scriptTypeIndex >= 0) ||
        (version < 16 && classId < 0) ||
        (version >= 16 && classId == 114)
      if (needsScriptHash) reader.readBytes(16) else null
    } else null

    val typeHash = if (version >= 13) reader.readBytes(16) else null

    var rootNode: TypeTreeNode? = null
    var dependencies = IntArray(0)

    if (enableTypeTree) {
      rootNode = if (version >= 12 || version == 10) {
        readTypeTreeBlob(reader, version)
      } else {
        readLegacyTypeTreeNode(reader, level = 0)
      }

      if (version >= 21) {
        if (isRefType) {
          reader.readNullTerminatedString(512) // className
          reader.readNullTerminatedString(512) // namespace
          reader.readNullTerminatedString(512) // assemblyName
        } else {
          val depCount = reader.readInt32()
          validateCount(depCount, "typeDependencies")
          dependencies = IntArray(depCount) { reader.readInt32() }
        }
      }
    }

    return SerializedTypeInfo(
      classId = classId,
      isStrippedType = isStrippedType,
      scriptTypeIndex = scriptTypeIndex,
      scriptIdHash = scriptIdHash,
      typeHash = typeHash,
      rootNode = rootNode,
      typeDependencies = dependencies,
    )
  }

  private fun readTypeTreeBlob(reader: BinaryReader, version: Int): TypeTreeNode? {
    val nodeCount = reader.readInt32()
    val stringBufferSize = reader.readInt32()
    if (nodeCount <= 0) return null
    validateCount(nodeCount, "typeTreeNodeCount")
    validateCount(stringBufferSize, "typeTreeStringBufferSize")

    data class FlatNode(
      val version: Int,
      val level: Int,
      val typeFlags: Int,
      val typeStrOffset: Int,
      val nameStrOffset: Int,
      val byteSize: Int,
      val index: Int,
      val metaFlag: Int,
    )

    val flat = ArrayList<FlatNode>(min(nodeCount, 2048))
    for (i in 0 until nodeCount) {
      val nodeVersion = reader.readUInt16()
      val level = reader.readUInt8()
      val typeFlags = reader.readUInt8()
      val typeStrOffset = reader.readInt32()
      val nameStrOffset = reader.readInt32()
      val byteSize = reader.readInt32()
      val index = reader.readInt32()
      val metaFlag = reader.readInt32()
      if (version >= 19) {
        reader.readInt64() // refTypeHash
      }
      flat.add(FlatNode(nodeVersion, level, typeFlags, typeStrOffset, nameStrOffset, byteSize, index, metaFlag))
    }

    val stringBuffer = reader.readBytes(stringBufferSize)
    val nodes = flat.map { fn ->
      TypeTreeNode(
        version = fn.version,
        level = fn.level,
        typeFlags = fn.typeFlags,
        typeName = CommonStringTable.readStringFromOffset(stringBuffer, fn.typeStrOffset),
        name = CommonStringTable.readStringFromOffset(stringBuffer, fn.nameStrOffset),
        byteSize = fn.byteSize,
        index = fn.index,
        metaFlag = fn.metaFlag,
      )
    }

    return assembleTypeTree(nodes)
  }

  private fun assembleTypeTree(flatNodes: List<TypeTreeNode>): TypeTreeNode? {
    if (flatNodes.isEmpty()) return null
    val childBuckets = Array(flatNodes.size) { mutableListOf<Int>() }
    val stack = ArrayDeque<Int>()
    for (i in flatNodes.indices) {
      val level = flatNodes[i].level
      while (stack.isNotEmpty() && flatNodes[stack.last()].level >= level) {
        stack.removeLast()
      }
      if (stack.isNotEmpty()) {
        childBuckets[stack.last()].add(i)
      }
      stack.addLast(i)
    }

    fun build(idx: Int): TypeTreeNode {
      val node = flatNodes[idx]
      val kids = childBuckets[idx].map { build(it) }
      return node.copy(children = kids)
    }
    return build(0)
  }

  private fun readLegacyTypeTreeNode(reader: BinaryReader, level: Int): TypeTreeNode {
    val typeName = reader.readNullTerminatedString(256)
    val name = reader.readNullTerminatedString(256)
    val byteSize = reader.readInt32()
    val index = reader.readInt32()
    val typeFlags = reader.readInt32()
    val nodeVersion = reader.readInt32()
    val metaFlag = reader.readInt32()
    val childCount = reader.readInt32()
    validateCount(childCount, "legacyTypeTreeChildCount")
    val children = ArrayList<TypeTreeNode>(min(childCount, 256))
    for (i in 0 until childCount) {
      children.add(readLegacyTypeTreeNode(reader, level + 1))
    }
    return TypeTreeNode(
      version = nodeVersion,
      level = level,
      typeFlags = typeFlags,
      typeName = typeName,
      name = name,
      byteSize = byteSize,
      index = index,
      metaFlag = metaFlag,
      children = children,
    )
  }

  private fun extractAssetNameIfAvailable(
    source: SeekableByteSource,
    raw: RawObjectRecord,
    types: List<SerializedTypeInfo>,
    formatVersion: Int,
    byteOrder: ByteOrderMode,
  ): String? {
    val typeInfo = if (formatVersion >= 17) types.getOrNull(raw.typeId) else types.firstOrNull { it.classId == raw.typeId }
    val firstFieldName = typeInfo?.rootNode?.children?.firstOrNull()?.name
    val mightStartWithName = firstFieldName == "m_Name" ||
      (typeInfo?.rootNode == null && UnityClassRegistry.isNamedObjectClass(raw.classId))

    if (!mightStartWithName || raw.byteSize < 4L) return null

    return try {
      val reader = BinaryReader(source, byteOrder, bufferSize = 512)
      reader.seek(raw.byteStart)
      val len = reader.readInt32()
      if (len !in 1..256 || len.toLong() + 4L > raw.byteSize) {
        return null
      }
      val bytes = reader.readBytes(len)
      val str = String(bytes, StandardCharsets.UTF_8)
      if (str.any { it.code < 0x20 && it != '\t' }) null else str
    } catch (_: Throwable) {
      null
    }
  }

  private fun validateCount(count: Int, field: String) {
    if (count < 0 || count > MAX_REASONABLE_COUNT) {
      throw IllegalArgumentException("Invalid or nonsensical $field: $count")
    }
  }

  private data class RawObjectRecord(
    val pathId: Long,
    val byteStart: Long,
    val byteSize: Long,
    val typeId: Int,
    val classId: Int,
    val isDestroyed: Boolean,
    val isStripped: Boolean,
  )
}
