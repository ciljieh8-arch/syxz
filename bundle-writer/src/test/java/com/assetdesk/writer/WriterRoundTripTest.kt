package com.assetdesk.writer

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.Lz4Codec
import com.assetdesk.binary.LzmaCodec
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

  @Test
  fun `AssetBundleWriter preserves original compression and keeps trailing objects intact when earlier object shrinks`() {
    val firstObj = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN).apply {
      writeLengthPrefixedString("shader_a", true)
      writeLengthPrefixedString("X".repeat(400), true)
    }.toByteArray()
    val lastObj = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN).apply {
      writeLengthPrefixedString("shader_last", true)
      writeLengthPrefixedString("TrailingPayloadPreserved_999", true)
    }.toByteArray()

    val innerSf = buildMultiObjectSerializedFile(
      listOf(
        1001L to firstObj,
        9026648381118478326L to lastObj,
      ),
    )

    for (compressionCode in listOf(0, 1, 2, 3)) {
      val bundleBytes = buildSimpleUnityFs("CAB-multi", innerSf, blockCompression = compressionCode)
      val bundleSource = ByteArrayByteSource(bundleBytes)
      val parsedBundle = AssetBundleParser.parse(bundleSource, "multi_$compressionCode.bundle").getOrThrow()
      val detectedMode = AssetBundleWriter.detectPreferredCompressionMode(parsedBundle)
      assertEquals(compressionCode, detectedMode.flagCode)

      val originalEntry = AssetBundleParser.extractEntryBytes(
        bundleSource,
        parsedBundle,
        parsedBundle.directoryEntries.first(),
      ).getOrThrow()
      val parsedSf = SerializedFileParser.parse(ByteArrayByteSource(originalEntry), "CAB-multi").getOrThrow()

      // Shrink the first object significantly (like editing a dump) and rebuild using original entry as source
      val shrunkFirstObj = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN).apply {
        writeLengthPrefixedString("shader_a", true)
        writeLengthPrefixedString("Short", true)
      }.toByteArray()
      val updatedSf = parsedSf.copy(
        objects = parsedSf.objects.map { obj ->
          if (obj.pathId == 1001L) obj.copy(modifiedRawBytes = shrunkFirstObj, status = ObjectStatus.MODIFIED)
          else obj
        },
      )

      val serializedEntry = SerializedFileWriter.serializeToByteArray(
        ByteArrayByteSource(originalEntry),
        updatedSf,
      )
      val rebuiltFile = AssetBundleWriter.rebuildUnityFsToTempFile(
        originalSource = bundleSource,
        originalBundle = parsedBundle,
        replacedEntries = mapOf("CAB-multi" to serializedEntry),
        compressionMode = detectedMode,
        tempDirectory = tempFolder.root,
      ).getOrThrow()

      val rebuiltBytes = rebuiltFile.readBytes()
      val reBundle = AssetBundleParser.parse(ByteArrayByteSource(rebuiltBytes), "out.bundle").getOrThrow()
      assertEquals(compressionCode, reBundle.blocks.first().compressionType)
      val extracted = AssetBundleParser.extractEntryBytes(
        ByteArrayByteSource(rebuiltBytes),
        reBundle,
        reBundle.directoryEntries.first(),
      ).getOrThrow()
      val reSf = SerializedFileParser.parse(ByteArrayByteSource(extracted), "CAB-multi").getOrThrow()
      assertEquals(2, reSf.objects.size)
      assertEquals(shrunkFirstObj.size.toLong(), reSf.objects[0].byteSize)
      assertEquals(lastObj.size.toLong(), reSf.objects[1].byteSize)
      assertEquals("shader_last", reSf.objects[1].assetName)
    }
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

  private fun buildMultiObjectSerializedFile(
    items: List<Pair<Long, ByteArray>>,
    endian: ByteOrderMode = ByteOrderMode.LITTLE_ENDIAN,
  ): ByteArray {
    val dataOut = java.io.ByteArrayOutputStream()
    val starts = LongArray(items.size)
    var cursor = 0L
    items.forEachIndexed { idx, (_, bytes) ->
      val pad = ((8L - (cursor % 8L)) % 8L).toInt()
      if (pad > 0) {
        dataOut.write(ByteArray(pad))
        cursor += pad
      }
      starts[idx] = cursor
      dataOut.write(bytes)
      cursor += bytes.size
    }
    val dataBytes = dataOut.toByteArray()

    val meta = BinaryWriter(endian)
    meta.writeNullTerminatedString("2018.4.36f1")
    meta.writeInt32(13)
    meta.writeBoolean(false)
    meta.writeInt32(1)
    meta.writeInt32(49)
    meta.writeBoolean(false)
    meta.writeInt16(-1)
    meta.writeBytes(ByteArray(16))

    meta.writeInt32(items.size)
    items.forEachIndexed { idx, (pathId, bytes) ->
      meta.align(4)
      meta.writeInt64(pathId)
      meta.writeUInt32(starts[idx])
      meta.writeUInt32(bytes.size.toLong())
      meta.writeInt32(0)
    }
    meta.writeInt32(0)
    meta.writeInt32(0)
    val metaBytes = meta.toByteArray()

    val dataOffset = ((20L + metaBytes.size + 15L) / 16L) * 16L
    val pad = (dataOffset - 20L - metaBytes.size).toInt()
    val totalSize = dataOffset + dataBytes.size

    val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN, totalSize.toInt())
    out.writeUInt32(metaBytes.size.toLong())
    out.writeUInt32(totalSize)
    out.writeInt32(17)
    out.writeUInt32(dataOffset)
    out.writeUInt8(if (endian == ByteOrderMode.BIG_ENDIAN) 1 else 0)
    out.writeZeroBytes(3L)
    out.writeBytes(metaBytes)
    if (pad > 0) out.writeZeroBytes(pad.toLong())
    out.writeBytes(dataBytes)
    return out.toByteArray()
  }

  private fun buildSimpleUnityFs(
    entryName: String,
    payload: ByteArray,
    blockCompression: Int = 3,
  ): ByteArray {
    val compPayload = when (blockCompression) {
      0 -> payload
      1 -> LzmaCodec.compressUnityFsBlock(payload)
      else -> Lz4Codec.compressBlock(payload)
    }
    val info = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    info.writeZeroBytes(16L)
    info.writeInt32(1)
    info.writeUInt32(payload.size.toLong())
    info.writeUInt32(compPayload.size.toLong())
    info.writeUInt16(blockCompression)
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
