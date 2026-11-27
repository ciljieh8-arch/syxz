package com.assetdesk.writer

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.Lz4Codec
import com.assetdesk.model.ObjectStatus
import com.assetdesk.unity.AssetBundleParser
import com.assetdesk.unity.SerializedFileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WriterRoundTripTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `SerializedFileWriter saves modified object to temp file and re-parses cleanly`() {
    val originalSf = buildSerializedFile("entry_a", "OriginalText")
    val source = ByteArrayByteSource(originalSf)
    val parsed = SerializedFileParser.parse(source, "sharedassets0.assets").getOrThrow()

    val newPayload = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN).apply {
      writeLengthPrefixedString("entry_a", true)
      writeLengthPrefixedString("ModifiedLongerTextPayload_12345", true)
    }.toByteArray()

    val modified = parsed.copy(
      objects = parsed.objects.map { it.copy(modifiedRawBytes = newPayload, status = ObjectStatus.MODIFIED) },
    )

    val tempRes = SerializedFileWriter.writeAndVerifyToTempFile(source, modified, tempFolder.root)
    assertTrue(tempRes.isSuccess)
    val tempFile = tempRes.getOrThrow()

    val reopenedBytes = tempFile.readBytes()
    val reParsed = SerializedFileParser.parse(ByteArrayByteSource(reopenedBytes), "sharedassets0.assets").getOrThrow()
    assertEquals(1, reParsed.objects.size)
    assertEquals(newPayload.size.toLong(), reParsed.objects[0].byteSize)

    val readBack = SerializedFileParser.readObjectRawBytes(ByteArrayByteSource(reopenedBytes), reParsed.objects[0])
    assertEquals(newPayload.size, readBack.size)
  }

  @Test
  fun `SerializedFileWriter refuses Big Endian file to prevent corruption`() {
    val beSf = buildSerializedFile("be_entry", "Data", endian = ByteOrderMode.BIG_ENDIAN)
    val source = ByteArrayByteSource(beSf)
    val parsed = SerializedFileParser.parse(source, "console.assets").getOrThrow()
    val writeRes = SerializedFileWriter.writeAndVerifyToTempFile(source, parsed, tempFolder.root)
    assertTrue(writeRes.isFailure)
  }

  @Test
  fun `AssetBundleWriter rebuilds UnityFS bundle with modified internal SerializedFile and verifies re-parse`() {
    val innerSf = buildSerializedFile("bundle_item", "BeforeRebuild")
    val bundleBytes = buildSimpleUnityFs("CAB-abc", innerSf)
    val bundleSource = ByteArrayByteSource(bundleBytes)
    val parsedBundle = AssetBundleParser.parse(bundleSource, "mod.bundle").getOrThrow()

    val parsedSf = SerializedFileParser.parse(ByteArrayByteSource(innerSf), "CAB-abc").getOrThrow()
    val newObj = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN).apply {
      writeLengthPrefixedString("bundle_item", true)
      writeLengthPrefixedString("AfterBundleRebuild!", true)
    }.toByteArray()
    val modSfBytes = SerializedFileWriter.serializeToByteArray(
      ByteArrayByteSource(innerSf),
      parsedSf.copy(objects = parsedSf.objects.map { it.copy(modifiedRawBytes = newObj) }),
    )

    val rebuiltTemp = AssetBundleWriter.rebuildUnityFsToTempFile(
      originalSource = bundleSource,
      originalBundle = parsedBundle,
      replacedEntries = mapOf("CAB-abc" to modSfBytes),
      compressionMode = BundleCompressionMode.LZ4HC,
      tempDirectory = tempFolder.root,
    ).getOrThrow()

    val rebuiltBytes = rebuiltTemp.readBytes()
    val reBundle = AssetBundleParser.parse(ByteArrayByteSource(rebuiltBytes), "mod.bundle").getOrThrow()
    assertEquals(1, reBundle.directoryEntries.size)
    val extracted = AssetBundleParser.extractEntryBytes(
      ByteArrayByteSource(rebuiltBytes),
      reBundle,
      reBundle.directoryEntries[0],
    ).getOrThrow()
    val reSf = SerializedFileParser.parse(ByteArrayByteSource(extracted), "CAB-abc").getOrThrow()
    assertEquals("bundle_item", reSf.objects[0].assetName)
    assertEquals(newObj.size.toLong(), reSf.objects[0].byteSize)
  }

  private fun buildSerializedFile(
    name: String,
    body: String,
    endian: ByteOrderMode = ByteOrderMode.LITTLE_ENDIAN,
  ): ByteArray {
    val objWriter = BinaryWriter(endian)
    objWriter.writeLengthPrefixedString(name, true)
    objWriter.writeLengthPrefixedString(body, true)
    val objBytes = objWriter.toByteArray()

    val meta = BinaryWriter(endian)
    meta.writeNullTerminatedString("2018.4.36f1")
    meta.writeInt32(13)
    meta.writeBoolean(false)
    meta.writeInt32(1)
    meta.writeInt32(49)
    meta.writeBoolean(false)
    meta.writeInt16(-1)
    meta.writeBytes(ByteArray(16))

    meta.writeInt32(1)
    meta.align(4)
    meta.writeInt64(555L)
    meta.writeUInt32(0L)
    meta.writeUInt32(objBytes.size.toLong())
    meta.writeInt32(0)
    meta.writeInt32(0)
    meta.writeInt32(0)
    val metaBytes = meta.toByteArray()

    val dataOffset = ((20L + metaBytes.size + 15L) / 16L) * 16L
    val pad = (dataOffset - 20L - metaBytes.size).toInt()
    val totalSize = dataOffset + objBytes.size

    val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN, totalSize.toInt())
    out.writeUInt32(metaBytes.size.toLong())
    out.writeUInt32(totalSize)
    out.writeInt32(17)
    out.writeUInt32(dataOffset)
    out.writeUInt8(if (endian == ByteOrderMode.BIG_ENDIAN) 1 else 0)
    out.writeZeroBytes(3L)
    out.writeBytes(metaBytes)
    if (pad > 0) out.writeZeroBytes(pad.toLong())
    out.writeBytes(objBytes)
    return out.toByteArray()
  }

  private fun buildSimpleUnityFs(entryName: String, payload: ByteArray): ByteArray {
    val compPayload = Lz4Codec.compressBlock(payload)
    val info = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    info.writeZeroBytes(16L)
    info.writeInt32(1)
    info.writeUInt32(payload.size.toLong())
    info.writeUInt32(compPayload.size.toLong())
    info.writeUInt16(3)
    info.writeInt32(1)
    info.writeInt64(0L)
    info.writeInt64(payload.size.toLong())
    info.writeInt32(0x04)
    info.writeNullTerminatedString(entryName)
    val rawInfo = info.toByteArray()
    val compInfo = Lz4Codec.compressBlock(rawInfo)

    val prefix = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    prefix.writeNullTerminatedString("UnityFS")
    prefix.writeInt32(6)
    prefix.writeNullTerminatedString("5.x.x")
    prefix.writeNullTerminatedString("2018.4.36f1")
    val total = prefix.position + 20L + compInfo.size + compPayload.size

    val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN, total.toInt())
    out.writeBytes(prefix.toByteArray())
    out.writeInt64(total)
    out.writeInt32(compInfo.size)
    out.writeInt32(rawInfo.size)
    out.writeInt32(0x42)
    out.writeBytes(compInfo)
    out.writeBytes(compPayload)
    return out.toByteArray()
  }
}
