package com.assetdesk.model

import com.assetdesk.binary.ByteOrderMode

data class TypeTreeNode(
  val version: Int,
  val level: Int,
  val typeFlags: Int,
  val typeName: String,
  val name: String,
  val byteSize: Int,
  val index: Int = 0,
  val metaFlag: Int = 0,
  val children: List<TypeTreeNode> = emptyList(),
) {
  val isAligned: Boolean
    get() = (metaFlag and ALIGN_BYTES_FLAG) != 0

  val isArray: Boolean
    get() = (typeFlags and 1) != 0

  companion object {
    const val ALIGN_BYTES_FLAG = 0x4000
  }
}

data class SerializedTypeInfo(
  val classId: Int,
  val isStrippedType: Boolean = false,
  val scriptTypeIndex: Short = -1,
  val scriptIdHash: ByteArray? = null,
  val typeHash: ByteArray? = null,
  val rootNode: TypeTreeNode? = null,
  val typeDependencies: IntArray = IntArray(0),
)

data class ExternalFileRef(
  val tempEmpty: String = "",
  val guid: String = "",
  val type: Int = 0,
  val pathName: String,
)

data class PPtrReference(
  val fileId: Int,
  val pathId: Long,
  val targetTypeHint: String = "Object",
) {
  val isNull: Boolean
    get() = pathId == 0L

  val isInternal: Boolean
    get() = fileId == 0
}

enum class ObjectStatus(val label: String) {
  VALID("Valid"),
  MODIFIED("Modified"),
  READ_ONLY("Read-Only"),
  OUT_OF_BOUNDS("Out of Bounds"),
}

data class UnityObjectInfo(
  val pathId: Long,
  val fileId: Int = 0,
  val byteStart: Long,
  val byteSize: Long,
  val typeId: Int,
  val classId: Int,
  val className: String,
  val assetName: String,
  val hasExplicitName: Boolean,
  val containerPath: String? = null,
  val isDestroyed: Boolean = false,
  val isStripped: Boolean = false,
  val status: ObjectStatus = ObjectStatus.VALID,
  val modifiedRawBytes: ByteArray? = null,
) {
  val effectiveSize: Long
    get() = modifiedRawBytes?.size?.toLong() ?: byteSize

  val isModified: Boolean
    get() = modifiedRawBytes != null
}

enum class TypeTreeSource(val label: String) {
  EMBEDDED("Embedded TypeTree"),
  BUILTIN_FALLBACK("Built-in Fallback Schema"),
  UNAVAILABLE("No TypeTree"),
}

data class AssetFieldNode(
  val typeName: String,
  val fieldName: String,
  val level: Int,
  val byteOffset: Long,
  val byteLength: Long,
  val valueText: String,
  val isArray: Boolean = false,
  val isAligned: Boolean = false,
  val pptr: PPtrReference? = null,
  val children: List<AssetFieldNode> = emptyList(),
)

data class DecodedAssetData(
  val pathId: Long,
  val className: String,
  val typeTreeSource: TypeTreeSource,
  val byteOrder: ByteOrderMode,
  val rootNode: AssetFieldNode?,
  val pptrReferences: List<PPtrReference>,
  val bytesConsumed: Long,
  val expectedBytes: Long,
  val decodeWarning: String? = null,
) {
  val isSizeExact: Boolean
    get() = bytesConsumed == expectedBytes
}

enum class AssetSortField(val label: String) {
  PATH_ID("Path ID"),
  NAME("Name"),
  TYPE("Type"),
  SIZE("Size"),
}
