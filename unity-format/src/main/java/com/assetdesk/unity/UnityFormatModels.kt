package com.assetdesk.unity

import com.assetdesk.binary.ByteOrderMode

enum class UnityFileKind(val displayName: String) {
  SERIALIZED_FILE("Unity SerializedFile"),
  ASSET_BUNDLE_FS("UnityFS AssetBundle"),
  ASSET_BUNDLE_RAW("UnityRaw AssetBundle"),
  ASSET_BUNDLE_WEB("UnityWeb AssetBundle"),
  STREAMING_RESOURCE("Unity Streaming Resource"),
  UNSUPPORTED("Unsupported Format"),
  CORRUPTED("Corrupted Unity File"),
}

enum class ReadabilityStatus(val label: String) {
  SUPPORTED_READ_WRITE("Read / Write"),
  READ_ONLY("Read-Only"),
  COMPANION_RESOURCE("Companion Data"),
  UNSUPPORTED("Unsupported"),
  CORRUPTED("Corrupted"),
}

sealed interface HeaderParseResult {
  val kind: UnityFileKind
  val readability: ReadabilityStatus
  val actualFileSize: Long
  val firstBytesHex: String

  data class SerializedFileHeaderInfo(
    val formatVersion: Int,
    val unityVersionHint: String,
    val metadataSize: Long,
    val declaredFileSize: Long,
    override val actualFileSize: Long,
    val dataOffset: Long,
    val endianness: ByteOrderMode,
    val isLargeFileFormat: Boolean,
    val isReadOnly: Boolean,
    val readOnlyReason: String?,
    val warnings: List<String>,
    override val firstBytesHex: String,
  ) : HeaderParseResult {
    override val kind: UnityFileKind = UnityFileKind.SERIALIZED_FILE
    override val readability: ReadabilityStatus =
      if (isReadOnly) ReadabilityStatus.READ_ONLY else ReadabilityStatus.SUPPORTED_READ_WRITE
  }

  data class AssetBundleHeaderInfo(
    override val kind: UnityFileKind,
    val signature: String,
    val formatVersion: Int,
    val unityPlayerVersion: String,
    val unityEngineRevision: String,
    val declaredFileSize: Long,
    override val actualFileSize: Long,
    val compressedBlocksInfoSize: Int? = null,
    val uncompressedBlocksInfoSize: Int? = null,
    val flags: Int? = null,
    val compressionHint: String,
    val blocksInfoLocationHint: String? = null,
    val headerSize: Int? = null,
    val levelCount: Int? = null,
    val isReadOnly: Boolean = true,
    val readOnlyReason: String? = "AssetBundle editing requires Milestone 4+ block parser & writer.",
    val warnings: List<String> = emptyList(),
    override val firstBytesHex: String,
  ) : HeaderParseResult {
    override val readability: ReadabilityStatus =
      if (isReadOnly) ReadabilityStatus.READ_ONLY else ReadabilityStatus.SUPPORTED_READ_WRITE
  }

  data class CompanionResourceInfo(
    val fileName: String,
    val extension: String,
    override val actualFileSize: Long,
    override val firstBytesHex: String,
    val note: String,
  ) : HeaderParseResult {
    override val kind: UnityFileKind = UnityFileKind.STREAMING_RESOURCE
    override val readability: ReadabilityStatus = ReadabilityStatus.COMPANION_RESOURCE
  }

  data class UnsupportedFormatInfo(
    val detectedLabel: String,
    val reason: String,
    override val actualFileSize: Long,
    override val firstBytesHex: String,
  ) : HeaderParseResult {
    override val kind: UnityFileKind = UnityFileKind.UNSUPPORTED
    override val readability: ReadabilityStatus = ReadabilityStatus.UNSUPPORTED
  }

  data class CorruptedFileInfo(
    val suspectedKind: UnityFileKind,
    val errorMessage: String,
    override val actualFileSize: Long,
    override val firstBytesHex: String,
  ) : HeaderParseResult {
    override val kind: UnityFileKind = UnityFileKind.CORRUPTED
    override val readability: ReadabilityStatus = ReadabilityStatus.CORRUPTED
  }
}
