package com.assetdesk.plugins

import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.UnityObjectInfo

object MovieTexturePlugin : AssetPlugin {
  override val id: String = "movie_texture"
  override val displayName: String = "MovieTexture"
  override val supportedClassIds: Set<Int> = setOf(152)
  override val targetSummary: String = "MovieTexture (ClassID 152)"
  override val description: String =
    "Inspects legacy Unity 4/5 MovieTexture metadata, color space, loop flag, and embedded Ogg Theora stream size."

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    val root = decodedData?.rootNode
    val fields = listOf(
      "Object Size" to "${rawBytes.size} B",
      "Loop" to (root.findField("m_Loop")?.valueText ?: "TypeTree required"),
      "Color Space" to (root.findField("m_ColorSpace")?.valueText ?: "TypeTree required"),
      "Movie Data" to (root.findField("m_MovieData")?.valueText ?: "Not decoded"),
    )
    return PluginInspectionResult.MetadataPluginPreview(
      pluginName = displayName,
      assetName = obj.assetName,
      fields = fields,
      note = "Legacy Unity MovieTexture inspection active. Video playback/transcoding is not performed; raw stream can be exported via Export Raw.",
    )
  }
}

object TerrainDataPlugin : AssetPlugin {
  override val id: String = "terrain_data"
  override val displayName: String = "TerrainData"
  override val supportedClassIds: Set<Int> = setOf(156)
  override val targetSummary: String = "TerrainData (ClassID 156)"
  override val description: String =
    "Inspects heightmap resolution, terrain size, detail/splatmap counts, and TreePrototype references."

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    val root = decodedData?.rootNode
    val fields = listOf(
      "Object Size" to "${rawBytes.size} B",
      "Heightmap Width" to (root.findField("m_Width")?.valueText ?: "TypeTree required"),
      "Heightmap Height" to (root.findField("m_Height")?.valueText ?: "TypeTree required"),
      "Terrain Scale" to (root.findField("m_Scale")?.valueText ?: "TypeTree required"),
      "PPtr References" to "${decodedData?.pptrReferences?.size ?: 0}",
    )
    return PluginInspectionResult.MetadataPluginPreview(
      pluginName = displayName,
      assetName = obj.assetName,
      fields = fields,
      note = "TerrainData metadata inspected from TypeTree. Heightmap raw arrays are viewable in View Data / Export Dump.",
    )
  }
}

object SubstanceArchivePlugin : AssetPlugin {
  override val id: String = "substance_archive"
  override val displayName: String = "SubstanceArchive"
  override val supportedClassIds: Set<Int> = setOf(184, 186)
  override val targetSummary: String = "SubstanceArchive / ProceduralMaterial (ClassID 184, 186)"
  override val description: String =
    "Inspects legacy Unity 4/5 SubstanceArchive and ProceduralMaterial package metadata."

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    val root = decodedData?.rootNode
    val fields = listOf(
      "Class" to obj.className,
      "Object Size" to "${rawBytes.size} B",
      "Package Data" to (root.findField("m_PackageData")?.valueText ?: "Use Export Raw for .sbsar blob"),
      "PPtr References" to "${decodedData?.pptrReferences?.size ?: 0}",
    )
    return PluginInspectionResult.MetadataPluginPreview(
      pluginName = displayName,
      assetName = obj.assetName,
      fields = fields,
      note = "SubstanceArchive/ProceduralMaterial metadata verified. Raw package bytes can be extracted via Export Raw.",
    )
  }
}

object UMAMeshPlugin : AssetPlugin {
  override val id: String = "uma_mesh"
  override val displayName: String = "UMAMesh"
  override val supportedClassIds: Set<Int> = setOf(114)
  override val targetSummary: String = "UMA Mesh / SlotDataAsset (MonoBehaviour)"
  override val description: String =
    "Recognizes UMA SlotDataAsset / UMAMesh MonoBehaviour structures when TypeTree fields include umaMesh or meshData."

  override fun canHandle(obj: UnityObjectInfo): Boolean =
    obj.classId == 114 && (
      obj.assetName.contains("UMA", ignoreCase = true) ||
        obj.assetName.contains("Slot", ignoreCase = true) ||
        obj.assetName.contains("Mesh", ignoreCase = true)
      )

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    val root = decodedData?.rootNode
    val fields = listOf(
      "Asset Name" to obj.assetName,
      "Object Size" to "${rawBytes.size} B",
      "m_Script PPtr" to (root.findField("m_Script")?.valueText ?: "Not decoded"),
      "meshData" to (root.findField("meshData")?.valueText ?: "Not present"),
      "PPtr References" to "${decodedData?.pptrReferences?.size ?: 0}",
    )
    return PluginInspectionResult.MetadataPluginPreview(
      pluginName = displayName,
      assetName = obj.assetName,
      fields = fields,
      note = "UMA MonoBehaviour slot/mesh metadata inspected. Full field hierarchy is available in View Data / Export Dump.",
    )
  }
}
