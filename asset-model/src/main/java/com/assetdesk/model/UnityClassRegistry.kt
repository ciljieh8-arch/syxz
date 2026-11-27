package com.assetdesk.model

/**
 * Standard Unity Engine Class ID mapping and minimal built-in fallback schemas
 * used only when a SerializedFile strips embedded TypeTree metadata (enableTypeTree = false).
 */
object UnityClassRegistry {

  private val classNamesById = mapOf(
    1 to "GameObject",
    2 to "Component",
    4 to "Transform",
    12 to "ParticleAnimator",
    20 to "Camera",
    21 to "Material",
    23 to "MeshRenderer",
    25 to "Renderer",
    27 to "Texture",
    28 to "Texture2D",
    33 to "MeshFilter",
    43 to "Mesh",
    48 to "Shader",
    49 to "TextAsset",
    50 to "Rigidbody2D",
    54 to "Rigidbody",
    64 to "MeshCollider",
    65 to "BoxCollider",
    74 to "AnimationClip",
    81 to "AudioListener",
    82 to "AudioSource",
    83 to "AudioClip",
    84 to "RenderTexture",
    89 to "Cubemap",
    90 to "Avatar",
    91 to "AnimatorController",
    95 to "Animator",
    104 to "RenderSettings",
    108 to "Light",
    111 to "Animation",
    114 to "MonoBehaviour",
    115 to "MonoScript",
    124 to "Behaviour",
    128 to "Font",
    134 to "PhysicMaterial",
    135 to "SphereCollider",
    136 to "CapsuleCollider",
    137 to "SkinnedMeshRenderer",
    142 to "AssetBundle",
    150 to "PreloadData",
    152 to "MovieTexture",
    156 to "TerrainData",
    184 to "ProceduralMaterial",
    185 to "ProceduralTexture",
    186 to "SubstanceArchive",
    198 to "ParticleSystem",
    199 to "ParticleSystemRenderer",
    212 to "SpriteRenderer",
    213 to "Sprite",
    221 to "AnimatorOverrideController",
    222 to "CanvasRenderer",
    223 to "Canvas",
    224 to "RectTransform",
    225 to "CanvasGroup",
    226 to "BillboardAsset",
    258 to "LightProbes",
    328 to "VideoPlayer",
    329 to "VideoClip",
    1001 to "PrefabInstance",
    1002 to "EditorExtensionImpl",
    1003 to "AssetImporter",
    1004 to "AssetDatabase",
    1005 to "Mesh3DSImporter",
    1006 to "TextureImporter",
    1007 to "ShaderImporter",
    1020 to "AudioImporter",
    1040 to "ModelImporter",
    1105 to "HumanTemplate",
    1110 to "SpeedTreeImporter",
    1113 to "LightmapParameters",
  )

  fun getClassName(classId: Int): String =
    classNamesById[classId] ?: "ClassID_$classId"

  fun isNamedObjectClass(classId: Int): Boolean =
    classId in setOf(
      1, 21, 27, 28, 43, 48, 49, 74, 83, 84, 89, 90, 91, 114, 115, 128,
      142, 152, 156, 184, 185, 186, 213, 221, 329,
    )

  /**
   * Minimal verified fallback TypeTrees for common Unity assets when SerializedFile omits TypeTrees.
   */
  fun getFallbackTypeTree(classId: Int): TypeTreeNode? =
    when (classId) {
      49 -> buildTextAssetTree()
      115 -> buildMonoScriptFallbackTree()
      else -> if (isNamedObjectClass(classId)) buildNamedHeaderTree(getClassName(classId)) else null
    }

  private fun buildNamedHeaderTree(className: String): TypeTreeNode =
    TypeTreeNode(
      version = 1,
      level = 0,
      typeFlags = 0,
      typeName = className,
      name = "Base",
      byteSize = -1,
      children = listOf(
        TypeTreeNode(
          version = 1,
          level = 1,
          typeFlags = 0,
          typeName = "string",
          name = "m_Name",
          byteSize = -1,
          metaFlag = TypeTreeNode.ALIGN_BYTES_FLAG,
          children = listOf(
            TypeTreeNode(
              version = 1,
              level = 2,
              typeFlags = 1,
              typeName = "Array",
              name = "Array",
              byteSize = -1,
              metaFlag = TypeTreeNode.ALIGN_BYTES_FLAG,
              children = listOf(
                TypeTreeNode(1, 3, 0, "int", "size", 4),
                TypeTreeNode(1, 3, 0, "char", "data", 1),
              ),
            ),
          ),
        ),
      ),
    )

  private fun buildTextAssetTree(): TypeTreeNode =
    TypeTreeNode(
      version = 1,
      level = 0,
      typeFlags = 0,
      typeName = "TextAsset",
      name = "Base",
      byteSize = -1,
      children = listOf(
        stringField("m_Name", level = 1),
        stringField("m_Script", level = 1),
      ),
    )

  private fun buildMonoScriptFallbackTree(): TypeTreeNode =
    TypeTreeNode(
      version = 1,
      level = 0,
      typeFlags = 0,
      typeName = "MonoScript",
      name = "Base",
      byteSize = -1,
      children = listOf(
        stringField("m_Name", level = 1),
        TypeTreeNode(1, 1, 0, "int", "m_ExecutionOrder", 4),
        TypeTreeNode(1, 1, 0, "Hash128", "m_PropertiesHash", 16),
        stringField("m_ClassName", level = 1),
        stringField("m_Namespace", level = 1),
        stringField("m_AssemblyName", level = 1),
      ),
    )

  private fun stringField(name: String, level: Int): TypeTreeNode =
    TypeTreeNode(
      version = 1,
      level = level,
      typeFlags = 0,
      typeName = "string",
      name = name,
      byteSize = -1,
      metaFlag = TypeTreeNode.ALIGN_BYTES_FLAG,
      children = listOf(
        TypeTreeNode(
          version = 1,
          level = level + 1,
          typeFlags = 1,
          typeName = "Array",
          name = "Array",
          byteSize = -1,
          metaFlag = TypeTreeNode.ALIGN_BYTES_FLAG,
          children = listOf(
            TypeTreeNode(1, level + 2, 0, "int", "size", 4),
            TypeTreeNode(1, level + 2, 0, "char", "data", 1),
          ),
        ),
      ),
    )
}
