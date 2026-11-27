package com.assetdesk.export

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.ObjectStatus
import com.assetdesk.model.UnityClassRegistry
import com.assetdesk.unity.SerializedFileParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportAndPackageTest {

  @Test
  fun `AssetDumpCodec round-trips TextAsset dump export and edited dump import`() {
    val writer = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    writer.writeLengthPrefixedString("credits", true)
    writer.writeLengthPrefixedString("Original\nText", true)
    val originalBytes = writer.toByteArray()

    val schema = UnityClassRegistry.getFallbackTypeTree(49)!!
    val dump = AssetDumpCodec.exportDump(schema, originalBytes, ByteOrderMode.LITTLE_ENDIAN).getOrThrow()
    assertTrue(dump.contains("string m_Name = \"credits\""))
    assertTrue(dump.contains("string m_Script = \"Original\\nText\""))

    val editedDump = dump.replace("Original\\nText", "Patched\\nValue")
    val reserialized = AssetDumpCodec.importDump(editedDump, schema, ByteOrderMode.LITTLE_ENDIAN).getOrThrow()

    val reDumped = AssetDumpCodec.exportDump(schema, reserialized, ByteOrderMode.LITTLE_ENDIAN).getOrThrow()
    assertTrue(reDumped.contains("Patched\\nValue"))
  }

  @Test
  fun `InstallerPackageManager creates, previews, validates, and applies change packages`() {
    val sfBytes = buildMinimalSerializedFile("hero_text", "Alpha")
    val parsed = SerializedFileParser.parse(ByteArrayByteSource(sfBytes), "sharedassets0.assets").getOrThrow()

    val modifiedPayload = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN).apply {
      writeLengthPrefixedString("hero_text", true)
      writeLengthPrefixedString("Omega", true)
    }.toByteArray()

    val modifiedSf = parsed.copy(
      objects = parsed.objects.map { it.copy(modifiedRawBytes = modifiedPayload, status = ObjectStatus.MODIFIED) },
    )

    val pkgBytes = InstallerPackageManager.createPackageBytes("Hero Text Patch", modifiedSf).getOrThrow()
    val preview = InstallerPackageManager.inspectPackage(pkgBytes).getOrThrow()
    assertEquals("Hero Text Patch", preview.packageName)
    assertEquals(1, preview.changes.size)
    assertEquals(101L, preview.changes[0].pathId)

    val validation = InstallerPackageManager.validateAgainstTarget(preview, parsed)
    assertTrue(validation.isCompatible)
    assertEquals(1, validation.matchedObjectCount)

    val applied = InstallerPackageManager.applyPackage(preview, parsed).getOrThrow()
    assertArrayEquals(modifiedPayload, applied.objects[0].modifiedRawBytes)

    // Mismatched format version must be rejected
    val wrongVersionTarget = parsed.copy(header = parsed.header.copy(formatVersion = 19))
    val badValidation = InstallerPackageManager.validateAgainstTarget(preview, wrongVersionTarget)
    assertFalse(badValidation.isCompatible)
  }

  private fun buildMinimalSerializedFile(name: String, body: String): ByteArray {
    val objWriter = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    objWriter.writeLengthPrefixedString(name, true)
    objWriter.writeLengthPrefixedString(body, true)
    val objBytes = objWriter.toByteArray()

    val meta = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
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
    meta.writeInt64(101L)
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
    out.writeUInt8(0)
    out.writeZeroBytes(3L)
    out.writeBytes(metaBytes)
    if (pad > 0) out.writeZeroBytes(pad.toLong())
    out.writeBytes(objBytes)
    return out.toByteArray()
  }
}
