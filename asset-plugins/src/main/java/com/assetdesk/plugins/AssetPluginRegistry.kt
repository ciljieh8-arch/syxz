package com.assetdesk.plugins

import com.assetdesk.model.UnityObjectInfo

object AssetPluginRegistry {

  val allPlugins: List<AssetPlugin> = listOf(
    TexturePlugin,
    AudioClipPlugin,
    MeshPlugin,
    TextAssetPlugin,
    MovieTexturePlugin,
    TerrainDataPlugin,
    SubstanceArchivePlugin,
    UMAMeshPlugin,
  )

  fun findPluginFor(obj: UnityObjectInfo): AssetPlugin? =
    allPlugins.firstOrNull { it.canHandle(obj) }
}
