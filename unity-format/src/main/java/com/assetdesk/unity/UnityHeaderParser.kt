package com.assetdesk.unity

import com.assetdesk.binary.BinaryReadException
import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.SeekableByteSource
import java.util.Locale
import kotlin.math.min

/**
 * Parses and validates Unity AssetBundle (UnityFS, UnityRaw, UnityWeb),
 * SerializedFile (.assets, level*, globalgamemanagers), and companion streaming resource headers.
 */
object UnityHeaderParser {

  private const val MIN_SERIALIZED_HEADER_BYTES = 20L
  private const val MIN_SERIALIZED_V22_HEADER_BYTES = 48L
  private const val MAX_SUPPORTED_SERIALIZED_VERSION = 35
  private const val MIN_SUPPORTED_SERIALIZED_VERSION = 9 // Unity 3.4+
  private const val MAX_REASONABLE_BLOCKS_INFO_SIZE = 256 * 1024 * 1024

  fun parse(source: SeekableByteSource, fileName: String): HeaderParseResult {
    val actualLength = source.length
    if (actualLength <= 0L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.CORRUPTED,
        errorMessage = "File is empty (0 bytes).",
        actualFileSize = 0L,
        firstBytesHex = "",
      )
    }

    val previewCount = min(actualLength, 32L).toInt()
    val reader = BinaryReader(source, ByteOrderMode.BIG_ENDIAN)
    val previewBytes = reader.peekBytes(previewCount)
    val firstBytesHex = formatHexPreview(previewBytes.take(16).toByteArray())
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    return try {
      when {
        startsWithAsciiNul(previewBytes, "UnityFS") ->
          parseUnityFs(reader, actualLength, firstBytesHex)
        startsWithAsciiNul(previewBytes, "UnityRaw") ->
          parseUnityLegacyBundle(
            reader = reader,
            kind = UnityFileKind.ASSET_BUNDLE_RAW,
            expectedSignature = "UnityRaw",
            actualLength = actualLength,
            firstBytesHex = firstBytesHex,
          )
        startsWithAsciiNul(previewBytes, "UnityWeb") ->
          parseUnityLegacyBundle(
            reader = reader,
            kind = UnityFileKind.ASSET_BUNDLE_WEB,
            expectedSignature = "UnityWeb",
            actualLength = actualLength,
            firstBytesHex = firstBytesHex,
          )
        startsWithAsciiNul(previewBytes, "UnityArchive") ->
          HeaderParseResult.UnsupportedFormatInfo(
            detectedLabel = "UnityArchive",
            reason = "UnityArchive container format is not supported.",
            actualFileSize = actualLength,
            firstBytesHex = firstBytesHex,
          )
        else -> {
          detectKnownNonUnitySignature(previewBytes)?.let { nonUnityLabel ->
            return HeaderParseResult.UnsupportedFormatInfo(
              detectedLabel = nonUnityLabel,
              reason = "File matches $nonUnityLabel signature, not a Unity SerializedFile or AssetBundle.",
              actualFileSize = actualLength,
              firstBytesHex = firstBytesHex,
            )
          }

          if (extension == "ress" || extension == "resource") {
            return HeaderParseResult.CompanionResourceInfo(
              fileName = fileName,
              extension = ".$extension",
              actualFileSize = actualLength,
              firstBytesHex = firstBytesHex,
              note = "External binary streaming resource (.${extension}) referenced by SerializedFile assets (AudioClip/Texture2D/Mesh). Contains no standalone header.",
            )
          }

          parseSerializedFileOrFallback(reader, actualLength, firstBytesHex, extension)
        }
      }
    } catch (e: BinaryReadException) {
      HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.CORRUPTED,
        errorMessage = e.message ?: "Binary bounds validation failed while parsing header.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
  }

  private fun parseUnityFs(
    reader: BinaryReader,
    actualLength: Long,
    firstBytesHex: String,
  ): HeaderParseResult {
    reader.seek(0L)
    reader.byteOrder = ByteOrderMode.BIG_ENDIAN
    val signature = reader.readNullTerminatedString(maxBytes = 32)
    if (reader.remaining < 4L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.ASSET_BUNDLE_FS,
        errorMessage = "Truncated UnityFS header before format version.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    val formatVersion = reader.readInt32()
    if (formatVersion !in 1..20) {
      return HeaderParseResult.UnsupportedFormatInfo(
        detectedLabel = "UnityFS v$formatVersion",
        reason = "UnityFS format version $formatVersion is outside supported range (v1..v20).",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val playerVersion = reader.readNullTerminatedString(maxBytes = 64)
    val engineRevision = reader.readNullTerminatedString(maxBytes = 64)

    if (reader.remaining < 20L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.ASSET_BUNDLE_FS,
        errorMessage = "Truncated UnityFS header: missing bundle size or block info fields.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val declaredSize = reader.readInt64()
    val compressedBlocksInfoSize = reader.readInt32()
    val uncompressedBlocksInfoSize = reader.readInt32()
    val flags = reader.readInt32()

    if (declaredSize <= 0L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.ASSET_BUNDLE_FS,
        errorMessage = "Invalid UnityFS declared size: $declaredSize bytes.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    if (declaredSize > actualLength) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.ASSET_BUNDLE_FS,
        errorMessage = "Truncated UnityFS bundle: header declares $declaredSize bytes, but file is $actualLength bytes.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    if (compressedBlocksInfoSize < 0 || compressedBlocksInfoSize.toLong() > actualLength) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.ASSET_BUNDLE_FS,
        errorMessage = "Invalid UnityFS compressed blocks info size ($compressedBlocksInfoSize bytes) for file length $actualLength.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    if (uncompressedBlocksInfoSize < 0 || uncompressedBlocksInfoSize > MAX_REASONABLE_BLOCKS_INFO_SIZE) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.ASSET_BUNDLE_FS,
        errorMessage = "Nonsensical UnityFS uncompressed blocks info size ($uncompressedBlocksInfoSize bytes).",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val compressionCode = flags and 0x3F
    val compressionHint = decodeUnityFsCompression(compressionCode)
    val blocksAtEnd = (flags and 0x80) != 0
    val warnings = buildList {
      if (declaredSize < actualLength) {
        add("Trailing data detected: actual file is ${actualLength - declaredSize} bytes larger than declared bundle size.")
      }
      if (compressionCode > 4) {
        add("Unrecognized UnityFS compression flag ($compressionCode); decompression may fail.")
      }
    }

    val isWritableCodec = compressionCode in 0..3

    return HeaderParseResult.AssetBundleHeaderInfo(
      kind = UnityFileKind.ASSET_BUNDLE_FS,
      signature = signature,
      formatVersion = formatVersion,
      unityPlayerVersion = playerVersion.ifBlank { "Unknown" },
      unityEngineRevision = engineRevision.ifBlank { "Unknown" },
      declaredFileSize = declaredSize,
      actualFileSize = actualLength,
      compressedBlocksInfoSize = compressedBlocksInfoSize,
      uncompressedBlocksInfoSize = uncompressedBlocksInfoSize,
      flags = flags,
      compressionHint = compressionHint,
      blocksInfoLocationHint = if (blocksAtEnd) "End of bundle" else "Immediately after header",
      isReadOnly = !isWritableCodec,
      readOnlyReason = if (isWritableCodec) {
        null
      } else {
        "UnityFS compression codec ($compressionHint) is read-only; rebuilding supports None, LZMA, LZ4, and LZ4HC."
      },
      warnings = warnings,
      firstBytesHex = firstBytesHex,
    )
  }

  private fun parseUnityLegacyBundle(
    reader: BinaryReader,
    kind: UnityFileKind,
    expectedSignature: String,
    actualLength: Long,
    firstBytesHex: String,
  ): HeaderParseResult {
    reader.seek(0L)
    reader.byteOrder = ByteOrderMode.BIG_ENDIAN
    val signature = reader.readNullTerminatedString(maxBytes = 32)
    if (reader.remaining < 4L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = kind,
        errorMessage = "Truncated $expectedSignature header before stream version.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    val streamVersion = reader.readInt32()
    if (streamVersion !in 1..6) {
      return HeaderParseResult.UnsupportedFormatInfo(
        detectedLabel = "$expectedSignature v$streamVersion",
        reason = "$expectedSignature stream version $streamVersion is unsupported.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val unityVersion = reader.readNullTerminatedString(maxBytes = 64)
    val unityRevision = reader.readNullTerminatedString(maxBytes = 64)

    if (reader.remaining < 16L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = kind,
        errorMessage = "Truncated $expectedSignature header before stream metadata fields.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    reader.readUInt32() // minimumStreamedBytes
    val headerSize = reader.readInt32()
    reader.readInt32() // numberOfLevelsToDownloadBeforeStreaming
    val levelCount = reader.readInt32()

    if (headerSize <= 0 || headerSize.toLong() > actualLength) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = kind,
        errorMessage = "Invalid $expectedSignature headerSize ($headerSize) for file length $actualLength.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    if (levelCount < 0 || levelCount > 10_000) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = kind,
        errorMessage = "Nonsensical $expectedSignature levelCount ($levelCount).",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    val levelTableBytes = levelCount.toLong() * 8L
    if (reader.remaining < levelTableBytes) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = kind,
        errorMessage = "Truncated $expectedSignature level offset table (needs $levelTableBytes bytes, ${reader.remaining} available).",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }
    reader.skip(levelTableBytes)

    var completeFileSize = actualLength
    if (streamVersion >= 2 && reader.remaining >= 4L) {
      completeFileSize = reader.readUInt32()
      if (completeFileSize > actualLength) {
        return HeaderParseResult.CorruptedFileInfo(
          suspectedKind = kind,
          errorMessage = "Truncated $expectedSignature bundle: header declares $completeFileSize bytes, but file is $actualLength bytes.",
          actualFileSize = actualLength,
          firstBytesHex = firstBytesHex,
        )
      }
    }

    val compressionHint =
      if (kind == UnityFileKind.ASSET_BUNDLE_WEB) "LZMA (UnityWeb)" else "Uncompressed (UnityRaw)"

    return HeaderParseResult.AssetBundleHeaderInfo(
      kind = kind,
      signature = signature,
      formatVersion = streamVersion,
      unityPlayerVersion = unityVersion.ifBlank { "Unknown" },
      unityEngineRevision = unityRevision.ifBlank { "Unknown" },
      declaredFileSize = completeFileSize,
      actualFileSize = actualLength,
      compressionHint = compressionHint,
      headerSize = headerSize,
      levelCount = levelCount,
      isReadOnly = true,
      readOnlyReason = "Legacy $signature AssetBundles are opened in read-only inspection mode; only UnityFS supports rebuilding.",
      firstBytesHex = firstBytesHex,
    )
  }

  private fun parseSerializedFileOrFallback(
    reader: BinaryReader,
    actualLength: Long,
    firstBytesHex: String,
    extension: String,
  ): HeaderParseResult {
    if (actualLength < MIN_SERIALIZED_HEADER_BYTES) {
      return HeaderParseResult.UnsupportedFormatInfo(
        detectedLabel = if (extension.isNotEmpty()) ".$extension binary" else "Small Binary",
        reason = "File is only $actualLength bytes, below minimum 20-byte Unity SerializedFile header.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    reader.seek(0L)
    reader.byteOrder = ByteOrderMode.BIG_ENDIAN

    val legacyMetadataSize = reader.readUInt32()
    val legacyFileSize = reader.readUInt32()
    val formatVersionLong = reader.readUInt32()
    val legacyDataOffset = reader.readUInt32()

    if (formatVersionLong == 0L || formatVersionLong > MAX_SUPPORTED_SERIALIZED_VERSION.toLong()) {
      return HeaderParseResult.UnsupportedFormatInfo(
        detectedLabel = "Unrecognized Binary",
        reason = "Header does not match Unity AssetBundle magic or valid SerializedFile format version (parsed version=$formatVersionLong).",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val formatVersion = formatVersionLong.toInt()
    if (formatVersion < MIN_SUPPORTED_SERIALIZED_VERSION) {
      return HeaderParseResult.UnsupportedFormatInfo(
        detectedLabel = "Legacy SerializedFile v$formatVersion",
        reason = "Unity SerializedFile format v$formatVersion (< Unity 3.4) is outside the initial v9+ target.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val endianByte = reader.readUInt8()
    val reserved1 = reader.readUInt8()
    val reserved2 = reader.readUInt8()
    val reserved3 = reader.readUInt8()

    val endianness = ByteOrderMode.fromUnityFlag(endianByte)
      ?: return if (legacyFileSize == actualLength) {
        HeaderParseResult.CorruptedFileInfo(
          suspectedKind = UnityFileKind.SERIALIZED_FILE,
          errorMessage = "Invalid endianness flag ($endianByte) in SerializedFile v$formatVersion header.",
          actualFileSize = actualLength,
          firstBytesHex = firstBytesHex,
        )
      } else {
        HeaderParseResult.UnsupportedFormatInfo(
          detectedLabel = "Unrecognized Binary",
          reason = "Header bytes do not match a valid Unity SerializedFile (invalid endianness byte $endianByte).",
          actualFileSize = actualLength,
          firstBytesHex = firstBytesHex,
        )
      }

    val isLargeFile = formatVersion >= 22
    val metadataSize: Long
    val declaredFileSize: Long
    val dataOffset: Long

    if (isLargeFile) {
      if (actualLength < MIN_SERIALIZED_V22_HEADER_BYTES) {
        return HeaderParseResult.CorruptedFileInfo(
          suspectedKind = UnityFileKind.SERIALIZED_FILE,
          errorMessage = "Truncated SerializedFile v$formatVersion header: requires at least 48 bytes, found $actualLength bytes.",
          actualFileSize = actualLength,
          firstBytesHex = firstBytesHex,
        )
      }
      metadataSize = reader.readUInt32()
      declaredFileSize = reader.readInt64()
      dataOffset = reader.readInt64()
      reader.readInt64() // unknown64
    } else {
      metadataSize = legacyMetadataSize
      declaredFileSize = legacyFileSize
      dataOffset = legacyDataOffset
    }

    if (declaredFileSize <= 0L) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.SERIALIZED_FILE,
        errorMessage = "Invalid declared SerializedFile size ($declaredFileSize bytes).",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    if (declaredFileSize > actualLength) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.SERIALIZED_FILE,
        errorMessage = "Truncated SerializedFile v$formatVersion: header declares $declaredFileSize bytes, but file is $actualLength bytes.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val minHeaderSize = if (isLargeFile) MIN_SERIALIZED_V22_HEADER_BYTES else MIN_SERIALIZED_HEADER_BYTES
    if (metadataSize <= 0L || metadataSize >= declaredFileSize) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.SERIALIZED_FILE,
        errorMessage = "Nonsensical SerializedFile metadataSize ($metadataSize bytes) for declared fileSize $declaredFileSize.",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    if (dataOffset < minHeaderSize || dataOffset > declaredFileSize) {
      return HeaderParseResult.CorruptedFileInfo(
        suspectedKind = UnityFileKind.SERIALIZED_FILE,
        errorMessage = "Invalid SerializedFile dataOffset ($dataOffset) outside range [$minHeaderSize, $declaredFileSize].",
        actualFileSize = actualLength,
        firstBytesHex = firstBytesHex,
      )
    }

    val warnings = buildList {
      if (reserved1 != 0 || reserved2 != 0 || reserved3 != 0) {
        add("Non-zero reserved header bytes detected ($reserved1, $reserved2, $reserved3).")
      }
      if (declaredFileSize < actualLength) {
        add("File has ${actualLength - declaredFileSize} trailing bytes beyond declared fileSize.")
      }
    }

    val isBigEndian = endianness == ByteOrderMode.BIG_ENDIAN
    val readOnlyReason =
      if (isBigEndian) {
        "Big-Endian SerializedFile is marked read-only to prevent unsafe writes."
      } else {
        null
      }

    return HeaderParseResult.SerializedFileHeaderInfo(
      formatVersion = formatVersion,
      unityVersionHint = mapSerializedVersionToUnityHint(formatVersion),
      metadataSize = metadataSize,
      declaredFileSize = declaredFileSize,
      actualFileSize = actualLength,
      dataOffset = dataOffset,
      endianness = endianness,
      isLargeFileFormat = isLargeFile,
      isReadOnly = isBigEndian,
      readOnlyReason = readOnlyReason,
      warnings = warnings,
      firstBytesHex = firstBytesHex,
    )
  }

  private fun mapSerializedVersionToUnityHint(version: Int): String =
    when (version) {
      9 -> "Unity 3.4 – 4.x (Format v9)"
      in 10..13 -> "Unity 4.x – 5.0 alpha (Format v$version)"
      14 -> "Unity 5.0 (Format v14)"
      15 -> "Unity 5.1 – 5.4 (Format v15)"
      16 -> "Unity 5.5 (Format v16)"
      17 -> "Unity 5.6 – 2018.2 (Format v17)"
      18 -> "Unity 2018.3 (Format v18)"
      19 -> "Unity 2018.4 – 2019.1 (Format v19)"
      20 -> "Unity 2019.2 (Format v20)"
      21 -> "Unity 2019.3 – 2020.1 (Format v21)"
      22 -> "Unity 2020.2+ (Format v22)"
      else -> "Unity Modern (Format v$version)"
    }

  private fun decodeUnityFsCompression(code: Int): String =
    when (code) {
      0 -> "None (Uncompressed)"
      1 -> "LZMA"
      2 -> "LZ4"
      3 -> "LZ4HC"
      4 -> "LZHAM"
      else -> "Unknown ($code)"
    }

  private fun startsWithAsciiNul(bytes: ByteArray, magic: String): Boolean {
    val target = magic.toByteArray(Charsets.US_ASCII)
    if (bytes.size <= target.size) return false
    for (i in target.indices) {
      if (bytes[i] != target[i]) return false
    }
    return bytes[target.size].toInt() == 0
  }

  private fun detectKnownNonUnitySignature(bytes: ByteArray): String? {
    if (bytes.size >= 4) {
      if (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) {
        return "PNG Image"
      }
      if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) {
        return "ZIP / APK Archive"
      }
      if (bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
        return "PDF Document"
      }
      if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()) {
        return "Ogg Audio"
      }
      if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
        return "RIFF / WAV Media"
      }
      if (bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
        return "ELF Executable"
      }
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
      return "JPEG Image"
    }
    if (bytes.size >= 5) {
      val head5 = String(bytes, 0, 5, Charsets.US_ASCII)
      if (head5 == "<?xml" || head5.startsWith("%YAML")) {
        return "Text / YAML Asset"
      }
    }
    return null
  }

  private fun formatHexPreview(bytes: ByteArray): String =
    bytes.joinToString(" ") { b -> String.format(Locale.ROOT, "%02X", b.toInt() and 0xFF) }
}
