package com.assetdesk.unity

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.Lz4Codec
import com.assetdesk.binary.LzmaCodec
import com.assetdesk.binary.SeekableByteSource
import java.io.ByteArrayOutputStream
import kotlin.math.min

data class BundleStorageBlock(
  val uncompressedSize: Long,
  val compressedSize: Long,
  val flags: Int,
) {
  val compressionType: Int
    get() = flags and 0x3F

  val compressionName: String
    get() = when (compressionType) {
      0 -> "None"
      1 -> "LZMA"
      2 -> "LZ4"
      3 -> "LZ4HC"
      4 -> "LZHAM"
      else -> "Unknown($compressionType)"
    }
}

data class BundleDirectoryEntry(
  val path: String,
  val offset: Long,
  val size: Long,
  val flags: Int,
  val status: String = "Ready",
) {
  val isSerializedFileFlag: Boolean
    get() = (flags and 0x04) != 0
}

data class ParsedAssetBundle(
  val fileName: String,
  val header: HeaderParseResult.AssetBundleHeaderInfo,
  val blocks: List<BundleStorageBlock>,
  val directoryEntries: List<BundleDirectoryEntry>,
  val blocksDataStartOffset: Long,
  val legacyUncompressedStream: ByteArray? = null,
)

/**
 * Parses UnityFS, UnityRaw, and UnityWeb AssetBundle directories, decompresses blocks,
 * and extracts internal SerializedFiles or streaming resource files safely.
 */
object AssetBundleParser {

  private const val MAX_ENTRY_EXTRACT_BYTES = 64 * 1024 * 1024L

  fun parse(
    source: SeekableByteSource,
    fileName: String,
  ): Result<ParsedAssetBundle> {
    return try {
      val headerResult = UnityHeaderParser.parse(source, fileName)
      if (headerResult !is HeaderParseResult.AssetBundleHeaderInfo) {
        val reason = when (headerResult) {
          is HeaderParseResult.CorruptedFileInfo -> headerResult.errorMessage
          is HeaderParseResult.UnsupportedFormatInfo -> headerResult.reason
          else -> "File is not a Unity AssetBundle."
        }
        return Result.failure(IllegalArgumentException(reason))
      }

      when (headerResult.kind) {
        UnityFileKind.ASSET_BUNDLE_FS -> parseUnityFsBundle(source, fileName, headerResult)
        UnityFileKind.ASSET_BUNDLE_RAW,
        UnityFileKind.ASSET_BUNDLE_WEB -> parseLegacyBundle(source, fileName, headerResult)
        else -> Result.failure(IllegalArgumentException("Unsupported AssetBundle kind: ${headerResult.kind}"))
      }
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  fun extractEntryBytes(
    source: SeekableByteSource,
    bundle: ParsedAssetBundle,
    entry: BundleDirectoryEntry,
    maxAllowedBytes: Long = MAX_ENTRY_EXTRACT_BYTES,
  ): Result<ByteArray> {
    return try {
      if (entry.size < 0L || entry.size > maxAllowedBytes) {
        return Result.failure(
          IllegalStateException(
            "Internal bundle entry '${entry.path}' size (${entry.size} B) exceeds safety limit (${maxAllowedBytes} B).",
          ),
        )
      }
      if (entry.size == 0L) return Result.success(ByteArray(0))

      bundle.legacyUncompressedStream?.let { legacy ->
        val end = entry.offset + entry.size
        if (entry.offset < 0L || end > legacy.size.toLong()) {
          return Result.failure(IllegalArgumentException("Legacy bundle entry '${entry.path}' out of bounds."))
        }
        return Result.success(legacy.copyOfRange(entry.offset.toInt(), end.toInt()))
      }

      val entryStart = entry.offset
      val entryEnd = entry.offset + entry.size
      val out = ByteArray(entry.size.toInt())

      var uncompressedCursor = 0L
      var compressedCursor = bundle.blocksDataStartOffset

      for ((blockIndex, block) in bundle.blocks.withIndex()) {
        val blockStart = uncompressedCursor
        val blockEnd = blockStart + block.uncompressedSize

        if (blockEnd > entryStart && blockStart < entryEnd) {
          if (compressedCursor + block.compressedSize > source.length) {
            return Result.failure(
              IllegalStateException("Bundle block #$blockIndex exceeds file bounds."),
            )
          }
          val compressedBytes = source.readFullyAt(compressedCursor, block.compressedSize.toInt())
          val rawBlock = decompressBlock(
            compressed = compressedBytes,
            uncompressedSize = block.uncompressedSize.toInt(),
            compressionType = block.compressionType,
          )
          val copyStartInBlock = (entryStart - blockStart).coerceAtLeast(0L).toInt()
          val copyEndInBlock = (entryEnd - blockStart).coerceAtMost(block.uncompressedSize).toInt()
          val copyLen = copyEndInBlock - copyStartInBlock
          val destOffset = (blockStart + copyStartInBlock - entryStart).toInt()
          System.arraycopy(rawBlock, copyStartInBlock, out, destOffset, copyLen)
        }

        uncompressedCursor = blockEnd
        compressedCursor += block.compressedSize
        if (uncompressedCursor >= entryEnd) break
      }

      Result.success(out)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  private fun parseUnityFsBundle(
    source: SeekableByteSource,
    fileName: String,
    header: HeaderParseResult.AssetBundleHeaderInfo,
  ): Result<ParsedAssetBundle> {
    val reader = BinaryReader(source, ByteOrderMode.BIG_ENDIAN)
    reader.readNullTerminatedString(32) // UnityFS
    reader.readInt32() // formatVersion
    reader.readNullTerminatedString(64) // playerVersion
    reader.readNullTerminatedString(64) // engineRevision
    reader.readInt64() // size
    val compressedBlocksInfoSize = reader.readInt32()
    val uncompressedBlocksInfoSize = reader.readInt32()
    val flags = reader.readInt32()

    if (header.formatVersion >= 7) {
      reader.align(16)
    }

    val headerEndOffset = reader.position
    val blocksInfoAtEnd = (flags and 0x80) != 0
    val blocksInfoOffset = if (blocksInfoAtEnd) {
      header.declaredFileSize - compressedBlocksInfoSize.toLong()
    } else {
      headerEndOffset
    }

    if (blocksInfoOffset < 0L || blocksInfoOffset + compressedBlocksInfoSize > source.length) {
      return Result.failure(IllegalArgumentException("UnityFS blocksInfo offset out of file bounds."))
    }

    val compressedBlocksInfo = source.readFullyAt(blocksInfoOffset, compressedBlocksInfoSize)
    val rawBlocksInfo = decompressBlock(
      compressed = compressedBlocksInfo,
      uncompressedSize = uncompressedBlocksInfoSize,
      compressionType = flags and 0x3F,
    )

    val infoReader = BinaryReader(ByteArrayByteSource(rawBlocksInfo), ByteOrderMode.BIG_ENDIAN)
    infoReader.readBytes(16) // uncompressedDataHash
    val blockCount = infoReader.readInt32()
    if (blockCount < 0 || blockCount > 1_000_000) {
      return Result.failure(IllegalArgumentException("Invalid UnityFS block count: $blockCount"))
    }
    val blocks = ArrayList<BundleStorageBlock>(min(blockCount, 1024))
    for (i in 0 until blockCount) {
      val uncompressedSize = infoReader.readUInt32()
      val compressedSize = infoReader.readUInt32()
      val blockFlags = infoReader.readUInt16()
      blocks.add(BundleStorageBlock(uncompressedSize, compressedSize, blockFlags))
    }

    val directoryCount = infoReader.readInt32()
    if (directoryCount < 0 || directoryCount > 500_000) {
      return Result.failure(IllegalArgumentException("Invalid UnityFS directory count: $directoryCount"))
    }
    val totalUncompressedSize = blocks.sumOf { it.uncompressedSize }
    val directoryEntries = ArrayList<BundleDirectoryEntry>(min(directoryCount, 1024))
    for (i in 0 until directoryCount) {
      val offset = infoReader.readInt64()
      val size = infoReader.readInt64()
      val entryFlags = infoReader.readInt32()
      val path = infoReader.readNullTerminatedString(1024)
      val status = if (offset >= 0L && offset + size <= totalUncompressedSize) "Ready" else "Out of Bounds"
      directoryEntries.add(BundleDirectoryEntry(path, offset, size, entryFlags, status))
    }

    val blocksDataStart = if (blocksInfoAtEnd) headerEndOffset else (blocksInfoOffset + compressedBlocksInfoSize)

    return Result.success(
      ParsedAssetBundle(
        fileName = fileName,
        header = header,
        blocks = blocks,
        directoryEntries = directoryEntries,
        blocksDataStartOffset = blocksDataStart,
      ),
    )
  }

  private fun parseLegacyBundle(
    source: SeekableByteSource,
    fileName: String,
    header: HeaderParseResult.AssetBundleHeaderInfo,
  ): Result<ParsedAssetBundle> {
    val headerSize = header.headerSize ?: return Result.failure(IllegalArgumentException("Missing legacy bundle headerSize"))
    val dataLength = (source.length - headerSize).coerceAtLeast(0L)
    if (dataLength > MAX_ENTRY_EXTRACT_BYTES) {
      return Result.failure(IllegalStateException("Legacy bundle stream (${dataLength} B) exceeds memory safety limit."))
    }
    val streamBytes = source.readFullyAt(headerSize.toLong(), dataLength.toInt())
    val decompressedStream = if (header.kind == UnityFileKind.ASSET_BUNDLE_WEB) {
      LzmaCodec.decompressUnityWebStream(streamBytes)
    } else {
      streamBytes
    }

    val dirReader = BinaryReader(ByteArrayByteSource(decompressedStream), ByteOrderMode.BIG_ENDIAN)
    val fileCount = dirReader.readInt32()
    if (fileCount < 0 || fileCount > 100_000) {
      return Result.failure(IllegalArgumentException("Invalid legacy bundle fileCount: $fileCount"))
    }
    val entries = ArrayList<BundleDirectoryEntry>(min(fileCount, 512))
    for (i in 0 until fileCount) {
      val name = dirReader.readNullTerminatedString(1024)
      val offset = dirReader.readUInt32()
      val size = dirReader.readUInt32()
      val status = if (offset + size <= decompressedStream.size) "Ready" else "Out of Bounds"
      entries.add(BundleDirectoryEntry(path = name, offset = offset, size = size, flags = 0x04, status = status))
    }

    val singleBlock = BundleStorageBlock(
      uncompressedSize = decompressedStream.size.toLong(),
      compressedSize = dataLength,
      flags = if (header.kind == UnityFileKind.ASSET_BUNDLE_WEB) 1 else 0,
    )

    return Result.success(
      ParsedAssetBundle(
        fileName = fileName,
        header = header,
        blocks = listOf(singleBlock),
        directoryEntries = entries,
        blocksDataStartOffset = headerSize.toLong(),
        legacyUncompressedStream = decompressedStream,
      ),
    )
  }

  private fun decompressBlock(
    compressed: ByteArray,
    uncompressedSize: Int,
    compressionType: Int,
  ): ByteArray =
    when (compressionType) {
      0 -> {
        if (compressed.size != uncompressedSize) {
          throw IllegalArgumentException(
            "Uncompressed block size mismatch: expected $uncompressedSize bytes, got ${compressed.size} bytes.",
          )
        }
        compressed
      }
      1 -> LzmaCodec.decompressUnityFsBlock(compressed, uncompressedSize)
      2, 3 -> Lz4Codec.decompressBlock(compressed, uncompressedSize)
      else -> throw UnsupportedOperationException("Unsupported UnityFS compression codec ID: $compressionType")
    }
}
