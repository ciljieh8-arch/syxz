package com.assetdesk.unity

import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityHeaderParserTest {

  @Test
  fun `parses valid UnityFS bundle header`() {
    val bytes = buildUnityFsHeader(
      version = 6,
      playerVersion = "5.x.x",
      engineRevision = "2019.4.28f1",
      declaredSize = 256L,
      compressedBlocksInfo = 64,
      uncompressedBlocksInfo = 128,
      flags = 0x43, // LZ4HC (3) + blocks flag
      actualTotalBytes = 256,
    )
    val result = UnityHeaderParser.parse(ByteArrayByteSource(bytes), "characters.bundle")
    assertTrue(result is HeaderParseResult.AssetBundleHeaderInfo)
    val info = result as HeaderParseResult.AssetBundleHeaderInfo
    assertEquals(UnityFileKind.ASSET_BUNDLE_FS, info.kind)
    assertEquals("UnityFS", info.signature)
    assertEquals(6, info.formatVersion)
    assertEquals("5.x.x", info.unityPlayerVersion)
    assertEquals("2019.4.28f1", info.unityEngineRevision)
    assertEquals("LZ4HC", info.compressionHint)
    assertEquals(256L, info.declaredFileSize)
    assertFalse(info.isReadOnly)
    assertEquals(ReadabilityStatus.SUPPORTED_READ_WRITE, info.readability)

    val lzhamBytes = buildUnityFsHeader(
      version = 6,
      playerVersion = "5.x.x",
      engineRevision = "2019.4.28f1",
      declaredSize = 256L,
      compressedBlocksInfo = 64,
      uncompressedBlocksInfo = 128,
      flags = 0x44, // LZHAM (4)
      actualTotalBytes = 256,
    )
    val lzhamInfo = UnityHeaderParser.parse(ByteArrayByteSource(lzhamBytes), "lzham.bundle")
      as HeaderParseResult.AssetBundleHeaderInfo
    assertTrue(lzhamInfo.isReadOnly)
    assertEquals(ReadabilityStatus.READ_ONLY, lzhamInfo.readability)
  }

  @Test
  fun `detects truncated UnityFS bundle`() {
    val bytes = buildUnityFsHeader(
      version = 6,
      playerVersion = "5.x.x",
      engineRevision = "2020.3.1f1",
      declaredSize = 4096L,
      compressedBlocksInfo = 32,
      uncompressedBlocksInfo = 64,
      flags = 2,
      actualTotalBytes = 128,
    )
    val result = UnityHeaderParser.parse(ByteArrayByteSource(bytes), "broken.bundle")
    assertTrue(result is HeaderParseResult.CorruptedFileInfo)
    val corrupted = result as HeaderParseResult.CorruptedFileInfo
    assertTrue(corrupted.errorMessage.contains("Truncated UnityFS"))
  }

  @Test
  fun `parses UnityRaw and UnityWeb headers`() {
    val rawBytes = buildLegacyBundleHeader("UnityRaw", streamVersion = 3, levelCount = 1, completeSize = 128)
    val rawResult = UnityHeaderParser.parse(ByteArrayByteSource(rawBytes), "legacy_raw.unity3d")
    assertTrue(rawResult is HeaderParseResult.AssetBundleHeaderInfo)
    val rawInfo = rawResult as HeaderParseResult.AssetBundleHeaderInfo
    assertEquals(UnityFileKind.ASSET_BUNDLE_RAW, rawInfo.kind)
    assertEquals("Uncompressed (UnityRaw)", rawInfo.compressionHint)
    assertEquals(1, rawInfo.levelCount)

    val webBytes = buildLegacyBundleHeader("UnityWeb", streamVersion = 3, levelCount = 2, completeSize = 192)
    val webResult = UnityHeaderParser.parse(ByteArrayByteSource(webBytes), "legacy_web.unity3d")
    assertTrue(webResult is HeaderParseResult.AssetBundleHeaderInfo)
    val webInfo = webResult as HeaderParseResult.AssetBundleHeaderInfo
    assertEquals(UnityFileKind.ASSET_BUNDLE_WEB, webInfo.kind)
    assertEquals("LZMA (UnityWeb)", webInfo.compressionHint)
    assertEquals(2, webInfo.levelCount)
  }

  @Test
  fun `parses Little Endian SerializedFile v9 and v15 as read-write`() {
    val v9 = buildSerializedHeader(
      metadataSize = 100,
      fileSize = 512,
      version = 9,
      dataOffset = 128,
      endianFlag = 0,
      actualBytes = 512,
    )
    val res9 = UnityHeaderParser.parse(ByteArrayByteSource(v9), "sharedassets0.assets")
    assertTrue(res9 is HeaderParseResult.SerializedFileHeaderInfo)
    val info9 = res9 as HeaderParseResult.SerializedFileHeaderInfo
    assertEquals(9, info9.formatVersion)
    assertEquals(ByteOrderMode.LITTLE_ENDIAN, info9.endianness)
    assertFalse(info9.isReadOnly)
    assertEquals(ReadabilityStatus.SUPPORTED_READ_WRITE, info9.readability)

    val v15 = buildSerializedHeader(
      metadataSize = 240,
      fileSize = 1024,
      version = 15,
      dataOffset = 280,
      endianFlag = 0,
      actualBytes = 1024,
    )
    val res15 = UnityHeaderParser.parse(ByteArrayByteSource(v15), "resources.assets")
    val info15 = res15 as HeaderParseResult.SerializedFileHeaderInfo
    assertEquals(15, info15.formatVersion)
    assertTrue(info15.unityVersionHint.contains("Unity 5.1"))
  }

  @Test
  fun `marks Big Endian SerializedFile as read-only`() {
    val beBytes = buildSerializedHeader(
      metadataSize = 120,
      fileSize = 600,
      version = 17,
      dataOffset = 160,
      endianFlag = 1,
      actualBytes = 600,
    )
    val result = UnityHeaderParser.parse(ByteArrayByteSource(beBytes), "console.assets")
    assertTrue(result is HeaderParseResult.SerializedFileHeaderInfo)
    val info = result as HeaderParseResult.SerializedFileHeaderInfo
    assertEquals(ByteOrderMode.BIG_ENDIAN, info.endianness)
    assertTrue(info.isReadOnly)
    assertEquals(ReadabilityStatus.READ_ONLY, info.readability)
  }

  @Test
  fun `parses SerializedFile v22 large file 64-bit header`() {
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    dos.writeInt(0) // legacy metadataSize
    dos.writeInt(0) // legacy fileSize
    dos.writeInt(22) // version 22
    dos.writeInt(0) // legacy dataOffset
    dos.writeByte(0) // LE
    dos.writeByte(0)
    dos.writeByte(0)
    dos.writeByte(0)
    dos.writeInt(200) // metadataSize64 (uint32)
    dos.writeLong(1024L) // fileSize64
    dos.writeLong(256L) // dataOffset64
    dos.writeLong(0L) // unknown64
    val pad = ByteArray(1024 - out.size())
    out.write(pad)

    val result = UnityHeaderParser.parse(ByteArrayByteSource(out.toByteArray()), "level0")
    assertTrue(result is HeaderParseResult.SerializedFileHeaderInfo)
    val info = result as HeaderParseResult.SerializedFileHeaderInfo
    assertTrue(info.isLargeFileFormat)
    assertEquals(22, info.formatVersion)
    assertEquals(200L, info.metadataSize)
    assertEquals(1024L, info.declaredFileSize)
    assertEquals(256L, info.dataOffset)
  }

  @Test
  fun `rejects unsupported, corrupted, empty, and non-Unity files cleanly`() {
    // Empty file
    val emptyResult = UnityHeaderParser.parse(ByteArrayByteSource(ByteArray(0)), "empty.assets")
    assertTrue(emptyResult is HeaderParseResult.CorruptedFileInfo)

    // Pre-Unity 3.4 SerializedFile v7
    val v7 = buildSerializedHeader(50, 256, version = 7, dataOffset = 80, endianFlag = 0, actualBytes = 256)
    val res7 = UnityHeaderParser.parse(ByteArrayByteSource(v7), "legacy_v7.assets")
    assertTrue(res7 is HeaderParseResult.UnsupportedFormatInfo)

    // Truncated SerializedFile
    val truncated = buildSerializedHeader(100, 4096, version = 15, dataOffset = 140, endianFlag = 0, actualBytes = 64)
    val resTrunc = UnityHeaderParser.parse(ByteArrayByteSource(truncated), "trunc.assets")
    assertTrue(resTrunc is HeaderParseResult.CorruptedFileInfo)

    // Nonsensical metadataSize > fileSize
    val nonsense = buildSerializedHeader(5000, 512, version = 15, dataOffset = 100, endianFlag = 0, actualBytes = 512)
    val resNonsense = UnityHeaderParser.parse(ByteArrayByteSource(nonsense), "corrupt.assets")
    assertTrue(resNonsense is HeaderParseResult.CorruptedFileInfo)

    // PNG file renamed to .assets
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(32)
    val resPng = UnityHeaderParser.parse(ByteArrayByteSource(png), "fake.assets")
    assertTrue(resPng is HeaderParseResult.UnsupportedFormatInfo)
    assertEquals("PNG Image", (resPng as HeaderParseResult.UnsupportedFormatInfo).detectedLabel)

    // .resS companion resource
    val resS = ByteArray(128) { 0x5A }
    val resCompanion = UnityHeaderParser.parse(ByteArrayByteSource(resS), "sharedassets0.assets.resS")
    assertTrue(resCompanion is HeaderParseResult.CompanionResourceInfo)
  }

  private fun buildUnityFsHeader(
    version: Int,
    playerVersion: String,
    engineRevision: String,
    declaredSize: Long,
    compressedBlocksInfo: Int,
    uncompressedBlocksInfo: Int,
    flags: Int,
    actualTotalBytes: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    dos.write("UnityFS\u0000".toByteArray(Charsets.US_ASCII))
    dos.writeInt(version)
    dos.write((playerVersion + "\u0000").toByteArray(Charsets.UTF_8))
    dos.write((engineRevision + "\u0000").toByteArray(Charsets.UTF_8))
    dos.writeLong(declaredSize)
    dos.writeInt(compressedBlocksInfo)
    dos.writeInt(uncompressedBlocksInfo)
    dos.writeInt(flags)
    val remaining = (actualTotalBytes - out.size()).coerceAtLeast(0)
    if (remaining > 0) out.write(ByteArray(remaining))
    return out.toByteArray()
  }

  private fun buildLegacyBundleHeader(
    signature: String,
    streamVersion: Int,
    levelCount: Int,
    completeSize: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    dos.write((signature + "\u0000").toByteArray(Charsets.US_ASCII))
    dos.writeInt(streamVersion)
    dos.write("4.x.x\u0000".toByteArray(Charsets.UTF_8))
    dos.write("4.7.2f1\u0000".toByteArray(Charsets.UTF_8))
    dos.writeInt(completeSize) // minimumStreamedBytes
    dos.writeInt(64) // headerSize
    dos.writeInt(1) // numberOfLevelsToDownloadBeforeStreaming
    dos.writeInt(levelCount)
    repeat(levelCount) {
      dos.writeInt(32)
      dos.writeInt(32)
    }
    dos.writeInt(completeSize)
    val remaining = (completeSize - out.size()).coerceAtLeast(0)
    if (remaining > 0) out.write(ByteArray(remaining))
    return out.toByteArray()
  }

  private fun buildSerializedHeader(
    metadataSize: Int,
    fileSize: Int,
    version: Int,
    dataOffset: Int,
    endianFlag: Int,
    actualBytes: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    dos.writeInt(metadataSize)
    dos.writeInt(fileSize)
    dos.writeInt(version)
    dos.writeInt(dataOffset)
    dos.writeByte(endianFlag)
    dos.writeByte(0)
    dos.writeByte(0)
    dos.writeByte(0)
    val remaining = (actualBytes - out.size()).coerceAtLeast(0)
    if (remaining > 0) out.write(ByteArray(remaining))
    return out.toByteArray()
  }
}
