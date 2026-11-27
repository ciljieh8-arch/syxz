package com.assetdesk.writer

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.FileChannelByteSource
import com.assetdesk.binary.Lz4Codec
import com.assetdesk.binary.LzmaCodec
import com.assetdesk.binary.SeekableByteSource
import com.assetdesk.unity.AssetBundleParser
import com.assetdesk.unity.BundleDirectoryEntry
import com.assetdesk.unity.ParsedAssetBundle
import com.assetdesk.unity.UnityFileKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

enum class BundleCompressionMode(val flagCode: Int, val label: String) {
  NONE(0, "Uncompressed"),
  LZ4(2, "LZ4"),
  LZ4HC(3, "LZ4HC"),
  LZMA(1, "LZMA"),
}

/**
 * Rebuilds UnityFS AssetBundles with updated internal entries, recompresses blocks,
 * writes safely to a temp file, and re-parses via AssetBundleParser to prevent corruption.
 */
object AssetBundleWriter {

  fun rebuildUnityFsToTempFile(
    originalSource: SeekableByteSource,
    originalBundle: ParsedAssetBundle,
    replacedEntries: Map<String, ByteArray>,
    compressionMode: BundleCompressionMode = BundleCompressionMode.LZ4HC,
    tempDirectory: File,
  ): Result<File> {
    if (originalBundle.header.kind != UnityFileKind.ASSET_BUNDLE_FS) {
      return Result.failure(
        UnsupportedOperationException(
          "Bundle rebuild currently supports UnityFS only (loaded bundle is ${originalBundle.header.signature}).",
        ),
      )
    }

    val tempFile = File.createTempFile("assetdesk_bundle_write_", ".bundle.tmp", tempDirectory)
    return try {
      val rebuiltBytes = rebuildUnityFsBytes(originalSource, originalBundle, replacedEntries, compressionMode)
      FileOutputStream(tempFile).use { fos ->
        fos.write(rebuiltBytes)
        fos.flush()
      }

      val raf = RandomAccessFile(tempFile, "r")
      FileChannelByteSource.fromRandomAccessFile(raf).use { verifySource ->
        val reParsed = AssetBundleParser.parse(verifySource, originalBundle.fileName)
        if (reParsed.isFailure) {
          tempFile.delete()
          return Result.failure(
            IllegalStateException(
              "Bundle write verification failed: ${reParsed.exceptionOrNull()?.message}",
              reParsed.exceptionOrNull(),
            ),
          )
        }
        val verified = reParsed.getOrThrow()
        if (verified.directoryEntries.size != originalBundle.directoryEntries.size) {
          tempFile.delete()
          return Result.failure(
            IllegalStateException("Bundle verification entry count mismatch."),
          )
        }
      }
      Result.success(tempFile)
    } catch (t: Throwable) {
      tempFile.delete()
      Result.failure(t)
    }
  }

  fun rebuildUnityFsBytes(
    originalSource: SeekableByteSource,
    originalBundle: ParsedAssetBundle,
    replacedEntries: Map<String, ByteArray>,
    compressionMode: BundleCompressionMode,
  ): ByteArray {
    // 1. Assemble uncompressed stream from updated + preserved directory entries
    val rawStream = ByteArrayOutputStream()
    val newDirectory = ArrayList<BundleDirectoryEntry>(originalBundle.directoryEntries.size)
    var cursor = 0L

    for (entry in originalBundle.directoryEntries) {
      val bytes = replacedEntries[entry.path]
        ?: AssetBundleParser.extractEntryBytes(originalSource, originalBundle, entry).getOrThrow()
      newDirectory.add(
        entry.copy(
          offset = cursor,
          size = bytes.size.toLong(),
        ),
      )
      rawStream.write(bytes)
      cursor += bytes.size.toLong()
    }

    val rawData = rawStream.toByteArray()
    val blockSize = 128 * 1024
    val compressedBlocks = ArrayList<ByteArray>()
    val uncompressedSizes = ArrayList<Int>()

    if (rawData.isEmpty()) {
      compressedBlocks.add(ByteArray(0))
      uncompressedSizes.add(0)
    } else if (compressionMode == BundleCompressionMode.LZMA) {
      compressedBlocks.add(LzmaCodec.compressUnityFsBlock(rawData))
      uncompressedSizes.add(rawData.size)
    } else {
      var offset = 0
      while (offset < rawData.size) {
        val len = minOf(blockSize, rawData.size - offset)
        val chunk = rawData.copyOfRange(offset, offset + len)
        val encoded = when (compressionMode) {
          BundleCompressionMode.NONE -> chunk
          BundleCompressionMode.LZ4, BundleCompressionMode.LZ4HC -> Lz4Codec.compressBlock(chunk)
          BundleCompressionMode.LZMA -> LzmaCodec.compressUnityFsBlock(chunk)
        }
        compressedBlocks.add(encoded)
        uncompressedSizes.add(len)
        offset += len
      }
    }

    // 2. Build blocksInfo
    val infoWriter = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    infoWriter.writeZeroBytes(16L) // uncompressedDataHash
    infoWriter.writeInt32(compressedBlocks.size)
    for (i in compressedBlocks.indices) {
      infoWriter.writeUInt32(uncompressedSizes[i].toLong())
      infoWriter.writeUInt32(compressedBlocks[i].size.toLong())
      infoWriter.writeUInt16(compressionMode.flagCode)
    }
    infoWriter.writeInt32(newDirectory.size)
    for (entry in newDirectory) {
      infoWriter.writeInt64(entry.offset)
      infoWriter.writeInt64(entry.size)
      infoWriter.writeInt32(entry.flags)
      infoWriter.writeNullTerminatedString(entry.path)
    }

    val rawBlocksInfo = infoWriter.toByteArray()
    val infoCompression = if (compressionMode == BundleCompressionMode.NONE) 0 else 2 // LZ4 for blocksInfo
    val compressedBlocksInfo = if (infoCompression == 0) {
      rawBlocksInfo
    } else {
      Lz4Codec.compressBlock(rawBlocksInfo)
    }

    val formatVersion = originalBundle.header.formatVersion
    val flags = (0x40 or (infoCompression and 0x3F)) // blocksInfo before data, standard directory flag

    // Compute exact total file size
    val preHeaderWriter = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    preHeaderWriter.writeNullTerminatedString("UnityFS")
    preHeaderWriter.writeInt32(formatVersion)
    preHeaderWriter.writeNullTerminatedString(originalBundle.header.unityPlayerVersion)
    preHeaderWriter.writeNullTerminatedString(originalBundle.header.unityEngineRevision)
    val fixedHeaderPrefixSize = preHeaderWriter.position
    val headerRestSize = 8L + 4L + 4L + 4L
    val unalignedHeaderTotal = fixedHeaderPrefixSize + headerRestSize
    val alignedHeaderTotal = if (formatVersion >= 7) ((unalignedHeaderTotal + 15L) / 16L) * 16L else unalignedHeaderTotal
    val totalDataSize = compressedBlocks.sumOf { it.size.toLong() }
    val totalBundleSize = alignedHeaderTotal + compressedBlocksInfo.size.toLong() + totalDataSize

    val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN, totalBundleSize.toInt())
    out.writeNullTerminatedString("UnityFS")
    out.writeInt32(formatVersion)
    out.writeNullTerminatedString(originalBundle.header.unityPlayerVersion)
    out.writeNullTerminatedString(originalBundle.header.unityEngineRevision)
    out.writeInt64(totalBundleSize)
    out.writeInt32(compressedBlocksInfo.size)
    out.writeInt32(rawBlocksInfo.size)
    out.writeInt32(flags)
    if (formatVersion >= 7) {
      out.align(16)
    }
    out.writeBytes(compressedBlocksInfo)
    for (block in compressedBlocks) {
      out.writeBytes(block)
    }
    return out.toByteArray()
  }
}
