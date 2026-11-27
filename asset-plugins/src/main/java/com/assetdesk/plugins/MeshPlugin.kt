package com.assetdesk.plugins

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.UnityObjectInfo
import java.util.Locale

object MeshPlugin : AssetPlugin {
  override val id: String = "mesh"
  override val displayName: String = "Mesh"
  override val supportedClassIds: Set<Int> = setOf(43)
  override val targetSummary: String = "Mesh (ClassID 43)"
  override val description: String =
    "Inspects vertex counts, submeshes, index format, bounding box, and exports Wavefront .obj when position/index buffers are directly readable."

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    return try {
      val root = decodedData?.rootNode
      val name = root.findString("m_Name") ?: runCatching {
        BinaryReader(ByteArrayByteSource(rawBytes), byteOrder).readLengthPrefixedString(true)
      }.getOrDefault(obj.assetName)

      val vertexDataNode = root.findField("m_VertexData")
      val vertexCount = vertexDataNode?.findInt("m_VertexCount") ?: 0
      val submeshArray = root.findField("m_SubMeshes")?.findField("Array")
      val submeshCount = submeshArray?.children?.size ?: 0
      val indexFormatCode = root.findInt("m_IndexFormat") ?: 0
      val indexFormatName = if (indexFormatCode == 1) "UInt32 (32-bit)" else "UInt16 (16-bit)"

      val indexBufferNode = root.findField("m_IndexBuffer")
      val indexByteLen = if (indexBufferNode != null) {
        val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
        reader.seek(indexBufferNode.byteOffset)
        reader.readInt32().coerceAtLeast(0)
      } else 0

      val bytesPerIndex = if (indexFormatCode == 1) 4 else 2
      val indexCount = indexByteLen / bytesPerIndex

      val aabbNode = root.findField("m_LocalAABB")
      val center = aabbNode?.findField("m_Center")?.valueText
      val extent = aabbNode?.findField("m_Extent")?.valueText
      val boundsSummary = if (center != null && extent != null) "center=$center, extent=$extent" else "Unavailable"

      val channelsNode = vertexDataNode?.findField("m_Channels")?.findField("Array")
      val channelsSummary = if (channelsNode != null) "${channelsNode.children.size} channel descriptors" else "Unavailable"

      val objText = tryBuildObj(rawBytes, byteOrder, decodedData, name.ifBlank { obj.assetName })
      val canExportObj = objText != null
      val note = if (canExportObj) {
        "Embedded Float32 vertex positions and triangle index buffer verified for Wavefront .obj export."
      } else {
        "OBJ export requires embedded Float32 vertex positions (Channel 0), readable submesh/index buffer, and TypeTree metadata."
      }

      PluginInspectionResult.MeshPreview(
        assetName = name.ifBlank { obj.assetName },
        vertexCount = vertexCount,
        indexCount = indexCount,
        submeshCount = submeshCount,
        indexFormatName = indexFormatName,
        boundsSummary = boundsSummary,
        channelsSummary = channelsSummary,
        canExportObj = canExportObj,
        objText = objText,
        note = note,
      )
    } catch (t: Throwable) {
      PluginInspectionResult.PluginError(displayName, "Mesh inspection failed: ${t.message}")
    }
  }

  private fun tryBuildObj(
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    decodedData: DecodedAssetData?,
    meshName: String,
  ): String? {
    val root = decodedData?.rootNode ?: return null
    val vertexDataNode = root.findField("m_VertexData") ?: return null
    val vertexCount = vertexDataNode.findInt("m_VertexCount") ?: return null
    if (vertexCount <= 0 || vertexCount > 200_000) return null

    val dataSizeNode = vertexDataNode.findField("m_DataSize") ?: return null
    val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
    reader.seek(dataSizeNode.byteOffset)
    val vBytesLen = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
    if (vBytesLen < vertexCount * 12) return null
    val vertexBuffer = reader.readBytes(vBytesLen)

    // Determine stride for Stream 0 (Channel 0 = position: format=0 Float32, dimension=3)
    val channels = vertexDataNode.findField("m_Channels")?.findField("Array")?.children.orEmpty()
    val ch0 = channels.firstOrNull() ?: return null
    val ch0Format = ch0.findInt("format") ?: 0
    val ch0Dim = ch0.findInt("dimension") ?: 3
    if (ch0Format != 0 || ch0Dim < 3) return null

    // Compute Stream 0 stride by summing active channels on stream 0, or fall back to vBytesLen / vertexCount
    val stream0Stride = channels
      .filter { (it.findInt("stream") ?: 0) == 0 && (it.findInt("dimension") ?: 0) > 0 }
      .sumOf { ch ->
        val dim = ch.findInt("dimension") ?: 0
        val fmt = ch.findInt("format") ?: 0
        val compSize = when (fmt) {
          0 -> 4 // Float32
          1 -> 2 // Float16
          2, 3 -> 1 // UNorm8 / SNorm8
          else -> 4
        }
        dim * compSize
      }
      .takeIf { it >= 12 && it * vertexCount <= vBytesLen } ?: (vBytesLen / vertexCount)

    val indexBufferNode = root.findField("m_IndexBuffer") ?: return null
    reader.seek(indexBufferNode.byteOffset)
    val idxLen = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
    val indexBytes = reader.readBytes(idxLen)
    val indexFormat = root.findInt("m_IndexFormat") ?: 0
    val bytesPerIndex = if (indexFormat == 1) 4 else 2
    val indexCount = idxLen / bytesPerIndex
    if (indexCount < 3) return null

    val sb = StringBuilder()
    sb.append("# Exported by AssetDesk Mobile\n")
    sb.append("o ").append(meshName.replace(' ', '_')).append('\n')

    val vReader = BinaryReader(ByteArrayByteSource(vertexBuffer), byteOrder)
    val ch0Offset = ch0.findInt("offset") ?: 0
    for (v in 0 until vertexCount) {
      vReader.seek(v.toLong() * stream0Stride + ch0Offset)
      val x = vReader.readFloat32()
      val y = vReader.readFloat32()
      val z = vReader.readFloat32()
      sb.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f\n", x, y, z))
    }

    val iReader = BinaryReader(ByteArrayByteSource(indexBytes), byteOrder)
    val triCount = indexCount / 3
    for (t in 0 until triCount) {
      val a = (if (bytesPerIndex == 4) iReader.readInt32() else iReader.readUInt16()) + 1
      val b = (if (bytesPerIndex == 4) iReader.readInt32() else iReader.readUInt16()) + 1
      val c = (if (bytesPerIndex == 4) iReader.readInt32() else iReader.readUInt16()) + 1
      sb.append("f ").append(a).append(' ').append(b).append(' ').append(c).append('\n')
    }
    return sb.toString()
  }
}
