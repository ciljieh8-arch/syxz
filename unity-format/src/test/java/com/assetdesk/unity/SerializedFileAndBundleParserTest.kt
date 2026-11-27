package com.assetdesk.unity

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.binary.Lz4Codec
import com.assetdesk.model.TypeTreeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedFileAndBundleParserTest {

  @Test
  fun `parses SerializedFile v17 metadata, TypeTree, Object Table, Externals, and View Data`() {
    val sfBytes = buildRealSerializedFileFixture(
      version = 17,
      endian = ByteOrderMode.LITTLE_ENDIAN,
      textAssetName = "config_dialog",
      textAssetBody = "Hello AssetDesk",
    )

    val parsedResult = SerializedFileParser.parse(ByteArrayByteSource(sfBytes), "sharedassets0.assets")
    assertTrue(parsedResult.isSuccess)
    val sf = parsedResult.getOrThrow()

    assertEquals("2018.4.36f1", sf.unityVersion)
    assertTrue(sf.enableTypeTree)
    assertEquals(1, sf.types.size)
    assertEquals(49, sf.types[0].classId)
    assertEquals(1, sf.objects.size)
    assertEquals(1, sf.externals.size)
    assertEquals("resources.assets", sf.externals[0].pathName)

    val obj = sf.objects[0]
    assertEquals(101L, obj.pathId)
    assertEquals("TextAsset", obj.className)
    assertEquals("config_dialog", obj.assetName)
    assertTrue(obj.hasExplicitName)

    val rawBytes = SerializedFileParser.readObjectRawBytes(ByteArrayByteSource(sfBytes), obj)
    val (typeTree, sourceKind) = sf.findTypeTreeForObject(obj)
    assertEquals(TypeTreeSource.EMBEDDED, sourceKind)
    assertNotNull(typeTree)

    val decoded = TypeTreeValueParser.decode(
      pathId = obj.pathId,
      className = obj.className,
      rawBytes = rawBytes,
      byteOrder = sf.header.endianness,
      rootTypeNode = typeTree,
      typeTreeSource = sourceKind,
    )
    assertTrue(decoded.isSizeExact)
    assertNotNull(decoded.rootNode)
    assertEquals(2, decoded.rootNode!!.children.size)
    assertEquals("\"config_dialog\"", decoded.rootNode!!.children[0].valueText)
    assertEquals("\"Hello AssetDesk\"", decoded.rootNode!!.children[1].valueText)
  }

  @Test
  fun `parses UnityFS LZ4HC bundle, lists internal files, and opens SerializedFile inside bundle`() {
    val innerSfBytes = buildRealSerializedFileFixture(
      version = 17,
      endian = ByteOrderMode.LITTLE_ENDIAN,
      textAssetName = "bundle_manifest",
      textAssetBody = "version=2",
    )

    val bundleBytes = buildRealUnityFsBundle(
      entryName = "CAB-test1234",
      entryPayload = innerSfBytes,
      useLz4 = true,
    )

    val bundleSource = ByteArrayByteSource(bundleBytes)
    val parsedBundleRes = AssetBundleParser.parse(bundleSource, "pack.bundle")
    assertTrue(parsedBundleRes.isSuccess)
    val bundle = parsedBundleRes.getOrThrow()

    assertEquals(1, bundle.directoryEntries.size)
    assertEquals("CAB-test1234", bundle.directoryEntries[0].path)
    assertEquals(innerSfBytes.size.toLong(), bundle.directoryEntries[0].size)

    val extracted = AssetBundleParser.extractEntryBytes(bundleSource, bundle, bundle.directoryEntries[0])
    assertTrue(extracted.isSuccess)
    val sfRes = SerializedFileParser.parse(ByteArrayByteSource(extracted.getOrThrow()), "CAB-test1234")
    assertTrue(sfRes.isSuccess)
    val sf = sfRes.getOrThrow()
    assertEquals("bundle_manifest", sf.objects[0].assetName)
  }

  @Test
  fun `rejects corrupted LZ4 bundle block cleanly without crashing`() {
    val innerSfBytes = buildRealSerializedFileFixture(17, ByteOrderMode.LITTLE_ENDIAN, "a", "b")
    val validBundle = buildRealUnityFsBundle("CAB-bad", innerSfBytes, useLz4 = true)
    // Corrupt last few bytes of compressed data payload
    val corrupted = validBundle.copyOf()
    corrupted[corrupted.size - 2] = 0xFF.toByte()
    corrupted[corrupted.size - 1] = 0xFF.toByte()

    val source = ByteArrayByteSource(corrupted)
    val parsed = AssetBundleParser.parse(source, "corrupt.bundle").getOrThrow()
    // Corrupt the LZ4 block token byte at the start of compressed block data
    corrupted[parsed.blocksDataStartOffset.toInt()] = 0xFF.toByte()
    val corruptedSource = ByteArrayByteSource(corrupted)
    val extract = AssetBundleParser.extractEntryBytes(corruptedSource, parsed, parsed.directoryEntries[0])
    assertFalse(extract.isSuccess)
  }

  companion object {
    fun buildRealSerializedFileFixture(
      version: Int,
      endian: ByteOrderMode,
      textAssetName: String,
      textAssetBody: String,
    ): ByteArray {
      // Object payload: TextAsset (m_Name + m_Script)
      val objWriter = BinaryWriter(endian)
      objWriter.writeLengthPrefixedString(textAssetName, alignAfter = true)
      objWriter.writeLengthPrefixedString(textAssetBody, alignAfter = true)
      val objBytes = objWriter.toByteArray()

      // Build TypeTree string buffer for TextAsset
      val strBuf = java.io.ByteArrayOutputStream()
      fun intern(s: String): Int {
        val off = strBuf.size()
        strBuf.write(s.toByteArray(Charsets.UTF_8))
        strBuf.write(0)
        return off
      }
      val tTextAsset = intern("TextAsset")
      val nBase = intern("Base")
      val tString = intern("string")
      val nName = intern("m_Name")
      val tArray = intern("Array")
      val nArray = intern("Array")
      val tInt = intern("int")
      val nSize = intern("size")
      val tChar = intern("char")
      val nData = intern("data")
      val nScript = intern("m_Script")
      val strBytes = strBuf.toByteArray()

      val meta = BinaryWriter(endian)
      meta.writeNullTerminatedString("2018.4.36f1")
      meta.writeInt32(13) // Android
      meta.writeBoolean(true) // enableTypeTree
      meta.writeInt32(1) // typeCount
      // Type 0: TextAsset (49)
      meta.writeInt32(49)
      meta.writeBoolean(false)
      meta.writeInt16(-1)
      meta.writeBytes(ByteArray(16)) // typeHash

      // TypeTree nodes (9 nodes)
      meta.writeInt32(9)
      meta.writeInt32(strBytes.size)
      fun writeNode(lvl: Int, flags: Int, typeOff: Int, nameOff: Int, byteSize: Int, idx: Int, metaFlag: Int) {
        meta.writeUInt16(1)
        meta.writeUInt8(lvl)
        meta.writeUInt8(flags)
        meta.writeInt32(typeOff)
        meta.writeInt32(nameOff)
        meta.writeInt32(byteSize)
        meta.writeInt32(idx)
        meta.writeInt32(metaFlag)
      }
      writeNode(0, 0, tTextAsset, nBase, -1, 0, 0)
      writeNode(1, 0, tString, nName, -1, 1, 0x4000)
      writeNode(2, 1, tArray, nArray, -1, 2, 0x4000)
      writeNode(3, 0, tInt, nSize, 4, 3, 0)
      writeNode(3, 0, tChar, nData, 1, 4, 0)
      writeNode(1, 0, tString, nScript, -1, 5, 0x4000)
      writeNode(2, 1, tArray, nArray, -1, 6, 0x4000)
      writeNode(3, 0, tInt, nSize, 4, 7, 0)
      writeNode(3, 0, tChar, nData, 1, 8, 0)
      meta.writeBytes(strBytes)

      // Objects (1)
      meta.writeInt32(1)
      meta.align(4)
      meta.writeInt64(101L) // pathId
      meta.writeUInt32(0L)  // byteStart relative to dataOffset
      meta.writeUInt32(objBytes.size.toLong())
      meta.writeInt32(0)    // typeId

      // ScriptTypes (0)
      meta.writeInt32(0)

      // Externals (1)
      meta.writeInt32(1)
      meta.writeNullTerminatedString("")
      meta.writeBytes(ByteArray(16))
      meta.writeInt32(0)
      meta.writeNullTerminatedString("resources.assets")

      val metaBytes = meta.toByteArray()
      val dataOffset = ((20L + metaBytes.size + 15L) / 16L) * 16L
      val pad = (dataOffset - 20L - metaBytes.size).toInt()
      val totalSize = dataOffset + objBytes.size

      val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN, totalSize.toInt())
      out.writeUInt32(metaBytes.size.toLong())
      out.writeUInt32(totalSize)
      out.writeInt32(version)
      out.writeUInt32(dataOffset)
      out.writeUInt8(if (endian == ByteOrderMode.BIG_ENDIAN) 1 else 0)
      out.writeZeroBytes(3L)
      out.writeBytes(metaBytes)
      if (pad > 0) out.writeZeroBytes(pad.toLong())
      out.writeBytes(objBytes)
      return out.toByteArray()
    }

    fun buildRealUnityFsBundle(
      entryName: String,
      entryPayload: ByteArray,
      useLz4: Boolean,
    ): ByteArray {
      val blockCompType = if (useLz4) 3 else 0
      val compressedPayload = if (useLz4) Lz4Codec.compressBlock(entryPayload) else entryPayload

      val infoWriter = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
      infoWriter.writeZeroBytes(16L)
      infoWriter.writeInt32(1) // blockCount
      infoWriter.writeUInt32(entryPayload.size.toLong())
      infoWriter.writeUInt32(compressedPayload.size.toLong())
      infoWriter.writeUInt16(blockCompType)
      infoWriter.writeInt32(1) // directoryCount
      infoWriter.writeInt64(0L)
      infoWriter.writeInt64(entryPayload.size.toLong())
      infoWriter.writeInt32(0x04)
      infoWriter.writeNullTerminatedString(entryName)

      val rawInfo = infoWriter.toByteArray()
      val compInfo = if (useLz4) Lz4Codec.compressBlock(rawInfo) else rawInfo
      val flags = 0x40 or (if (useLz4) 2 else 0)

      val headerPrefix = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
      headerPrefix.writeNullTerminatedString("UnityFS")
      headerPrefix.writeInt32(6)
      headerPrefix.writeNullTerminatedString("5.x.x")
      headerPrefix.writeNullTerminatedString("2018.4.36f1")
      val headerSize = headerPrefix.position + 8L + 4L + 4L + 4L
      val totalSize = headerSize + compInfo.size + compressedPayload.size

      val out = BinaryWriter(ByteOrderMode.BIG_ENDIAN, totalSize.toInt())
      out.writeBytes(headerPrefix.toByteArray())
      out.writeInt64(totalSize)
      out.writeInt32(compInfo.size)
      out.writeInt32(rawInfo.size)
      out.writeInt32(flags)
      out.writeBytes(compInfo)
      out.writeBytes(compressedPayload)
      return out.toByteArray()
    }
  }
}
