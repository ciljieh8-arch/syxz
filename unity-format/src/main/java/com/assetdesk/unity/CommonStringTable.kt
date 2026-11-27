package com.assetdesk.unity

/**
 * Standard Unity Engine TypeTree common string table indexed by offset when bit 31 (0x80000000) is set.
 */
object CommonStringTable {

  private val entries: Map<Int, String> = mapOf(
    0 to "AABB",
    5 to "AnimationClip",
    19 to "AnimationCurve",
    34 to "AnimationState",
    49 to "Array",
    55 to "Base",
    60 to "BitField",
    69 to "bitset",
    76 to "bool",
    81 to "char",
    86 to "ColorRGBA",
    96 to "Component",
    106 to "data",
    111 to "deque",
    117 to "double",
    124 to "dynamic_array",
    138 to "FastPropertyName",
    155 to "first",
    161 to "float",
    167 to "Font",
    172 to "GameObject",
    183 to "Generic Mono",
    196 to "GradientNEW",
    208 to "GUID",
    213 to "GUIStyle",
    222 to "int",
    226 to "list",
    231 to "long long",
    241 to "map",
    245 to "Matrix4x4f",
    256 to "MdFour",
    263 to "MonoBehaviour",
    277 to "MonoScript",
    288 to "m_ByteSize",
    299 to "m_Curve",
    307 to "m_EditorClassIdentifier",
    331 to "m_EditorHideFlags",
    349 to "m_Enabled",
    359 to "m_ExtensionPtr",
    374 to "m_GameObject",
    387 to "m_Index",
    395 to "m_IsArray",
    405 to "m_IsStatic",
    416 to "m_MetaFlag",
    427 to "m_Name",
    434 to "m_ObjectHideFlags",
    452 to "m_PrefabInternal",
    469 to "m_PrefabParentObject",
    490 to "m_Script",
    499 to "m_StaticEditorFlags",
    519 to "m_Type",
    526 to "m_Version",
    536 to "Object",
    543 to "pair",
    548 to "PPtr<Component>",
    564 to "PPtr<GameObject>",
    581 to "PPtr<Material>",
    596 to "PPtr<MonoBehaviour>",
    616 to "PPtr<MonoScript>",
    633 to "PPtr<Object>",
    646 to "PPtr<Prefab>",
    659 to "PPtr<Sprite>",
    672 to "PPtr<TextAsset>",
    688 to "PPtr<Texture>",
    702 to "PPtr<Texture2D>",
    718 to "PPtr<Transform>",
    734 to "Prefab",
    741 to "Quaternionf",
    753 to "Rectf",
    759 to "RectInt",
    767 to "RectOffset",
    778 to "second",
    785 to "set",
    789 to "short",
    795 to "size",
    800 to "SInt16",
    807 to "SInt32",
    814 to "SInt64",
    821 to "SInt8",
    827 to "staticvector",
    840 to "string",
    847 to "TextAsset",
    857 to "TextMesh",
    866 to "Texture",
    874 to "Texture2D",
    884 to "Transform",
    894 to "TypelessData",
    907 to "UInt16",
    914 to "UInt32",
    921 to "UInt64",
    928 to "UInt8",
    934 to "unsigned int",
    947 to "unsigned long long",
    966 to "unsigned short",
    981 to "vector",
    988 to "Vector2f",
    997 to "Vector3f",
    1006 to "Vector4f",
    1015 to "m_ScriptingClassIdentifier",
    1042 to "Gradient",
    1051 to "Type*",
    1057 to "int2_storage",
    1070 to "int3_storage",
    1083 to "BoundsInt",
    1093 to "m_CorrespondingSourceObject",
    1121 to "m_PrefabInstance",
    1138 to "m_PrefabAsset",
    1152 to "FileSize",
    1161 to "Hash128",
  )

  fun readStringFromOffset(
    stringBuffer: ByteArray,
    rawOffset: Int,
  ): String {
    val isCommon = (rawOffset and Int.MIN_VALUE) != 0
    val offset = rawOffset and 0x7FFF_FFFF
    if (isCommon) {
      return entries[offset] ?: "CommonStr_$offset"
    }
    if (offset < 0 || offset >= stringBuffer.size) {
      return "StrOffset_$offset"
    }
    var end = offset
    while (end < stringBuffer.size && stringBuffer[end].toInt() != 0) {
      end++
    }
    return String(stringBuffer, offset, end - offset, Charsets.UTF_8)
  }
}
