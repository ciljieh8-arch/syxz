package com.assetdesk.plugins

import android.graphics.Bitmap
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.AssetFieldNode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.UnityObjectInfo

sealed interface PluginInspectionResult {
  val pluginName: String

  data class TextAssetPreview(
    override val pluginName: String = "TextAsset",
    val assetName: String,
    val encodingName: String,
    val isBinaryFallback: Boolean,
    val textContent: String,
    val payloadBytes: ByteArray,
    val canExportText: Boolean,
    val canImportText: Boolean,
  ) : PluginInspectionResult

  data class TexturePreview(
    override val pluginName: String = "Texture2D",
    val assetName: String,
    val width: Int,
    val height: Int,
    val formatName: String,
    val formatCode: Int,
    val mipCount: Int,
    val imageDataSize: Int,
    val streamPath: String?,
    val decodedBitmap: Bitmap?,
    val canExportPng: Boolean,
    val canImportPng: Boolean,
    val note: String? = null,
  ) : PluginInspectionResult

  data class AudioClipPreview(
    override val pluginName: String = "AudioClip",
    val assetName: String,
    val channels: Int?,
    val frequencyHz: Int?,
    val bitsPerSample: Int?,
    val durationSec: Float?,
    val compressionFormatName: String,
    val externalResourcePath: String?,
    val externalOffset: Long?,
    val externalSize: Long?,
    val embeddedDataOffset: Int?,
    val embeddedDataSize: Int,
    val detectedContainer: String,
    val canExportAudio: Boolean,
    val exportExtension: String,
    val note: String,
  ) : PluginInspectionResult

  data class MeshPreview(
    override val pluginName: String = "Mesh",
    val assetName: String,
    val vertexCount: Int,
    val indexCount: Int,
    val submeshCount: Int,
    val indexFormatName: String,
    val boundsSummary: String,
    val channelsSummary: String,
    val canExportObj: Boolean,
    val objText: String? = null,
    val note: String,
  ) : PluginInspectionResult

  data class MetadataPluginPreview(
    override val pluginName: String,
    val assetName: String,
    val fields: List<Pair<String, String>>,
    val note: String,
  ) : PluginInspectionResult

  data class PluginError(
    override val pluginName: String,
    val message: String,
  ) : PluginInspectionResult
}

interface AssetPlugin {
  val id: String
  val displayName: String
  val supportedClassIds: Set<Int>
  val targetSummary: String
  val description: String

  fun canHandle(obj: UnityObjectInfo): Boolean = obj.classId in supportedClassIds

  fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult
}

internal fun AssetFieldNode?.findField(name: String): AssetFieldNode? {
  if (this == null) return null
  if (fieldName == name) return this
  for (child in children) {
    val found = child.findField(name)
    if (found != null) return found
  }
  return null
}

internal fun AssetFieldNode?.findInt(name: String): Int? =
  findField(name)?.valueText?.toIntOrNull()

internal fun AssetFieldNode?.findLong(name: String): Long? =
  findField(name)?.valueText?.toLongOrNull()

internal fun AssetFieldNode?.findFloat(name: String): Float? =
  findField(name)?.valueText?.toFloatOrNull()

internal fun AssetFieldNode?.findString(name: String): String? =
  findField(name)?.valueText?.removeSurrounding("\"")
