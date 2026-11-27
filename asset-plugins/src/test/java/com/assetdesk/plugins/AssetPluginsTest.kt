package com.assetdesk.plugins

import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.AssetFieldNode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.TypeTreeSource
import com.assetdesk.model.UnityObjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetPluginsTest {

  @Test
  fun `TextAssetPlugin inspects UTF-8 text and preserves binary fallback for unknown bytes`() {
    val writer = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    writer.writeLengthPrefixedString("dialogue", true)
    writer.writeLengthPrefixedString("Line 1\nLine 2", true)
    val rawUtf8 = writer.toByteArray()

    val obj = UnityObjectInfo(1L, 0, 0L, rawUtf8.size.toLong(), 0, 49, "TextAsset", "dialogue", true)
    val preview = TextAssetPlugin.inspect(obj, rawUtf8, ByteOrderMode.LITTLE_ENDIAN, 17, null)
      as PluginInspectionResult.TextAssetPreview
    assertFalse(preview.isBinaryFallback)
    assertEquals("Line 1\nLine 2", preview.textContent)

    val modified = TextAssetPlugin.buildModifiedRawBytes(rawUtf8, "Updated Line", ByteOrderMode.LITTLE_ENDIAN)
    val previewMod = TextAssetPlugin.inspect(obj, modified, ByteOrderMode.LITTLE_ENDIAN, 17, null)
      as PluginInspectionResult.TextAssetPreview
    assertEquals("Updated Line", previewMod.textContent)

    // Binary payload with invalid UTF-8 bytes
    val binWriter = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    binWriter.writeLengthPrefixedString("blob", true)
    binWriter.writeInt32(4)
    binWriter.writeBytes(byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x00, 0xFF.toByte()))
    val binPreview = TextAssetPlugin.inspect(obj, binWriter.toByteArray(), ByteOrderMode.LITTLE_ENDIAN, 17, null)
      as PluginInspectionResult.TextAssetPreview
    assertTrue(binPreview.isBinaryFallback)
    assertFalse(binPreview.canImportText)
  }

  @Test
  fun `AudioClipPlugin recognizes embedded WAV and wraps raw PCM into valid RIFF WAV`() {
    val pcmBytes = byteArrayOf(0x00, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00, 0x40)
    val writer = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN)
    val lenOffset = 0L
    writer.writeInt32(pcmBytes.size)
    writer.writeBytes(pcmBytes)
    val rawAudio = writer.toByteArray()

    val decoded = DecodedAssetData(
      pathId = 10L,
      className = "AudioClip",
      typeTreeSource = TypeTreeSource.EMBEDDED,
      byteOrder = ByteOrderMode.LITTLE_ENDIAN,
      rootNode = AssetFieldNode(
        typeName = "AudioClip",
        fieldName = "Base",
        level = 0,
        byteOffset = 0L,
        byteLength = rawAudio.size.toLong(),
        valueText = "",
        children = listOf(
          AssetFieldNode("string", "m_Name", 1, 0L, 0L, "\"sfx_hit\""),
          AssetFieldNode("int", "m_Channels", 1, 0L, 4L, "1"),
          AssetFieldNode("int", "m_Frequency", 1, 0L, 4L, "22050"),
          AssetFieldNode("int", "m_BitsPerSample", 1, 0L, 4L, "16"),
          AssetFieldNode("int", "m_CompressionFormat", 1, 0L, 4L, "0"),
          AssetFieldNode("Array", "m_AudioData", 1, lenOffset, rawAudio.size.toLong(), "size = 8"),
        ),
      ),
      pptrReferences = emptyList(),
      bytesConsumed = rawAudio.size.toLong(),
      expectedBytes = rawAudio.size.toLong(),
    )

    val obj = UnityObjectInfo(10L, 0, 0L, rawAudio.size.toLong(), 0, 83, "AudioClip", "sfx_hit", true)
    val preview = AudioClipPlugin.inspect(obj, rawAudio, ByteOrderMode.LITTLE_ENDIAN, 17, decoded)
      as PluginInspectionResult.AudioClipPreview
    assertTrue(preview.canExportAudio)
    assertEquals("wav", preview.exportExtension)

    val exported = AudioClipPlugin.exportRecognizedAudio(rawAudio, ByteOrderMode.LITTLE_ENDIAN, decoded).getOrThrow()
    assertEquals("wav", exported.first)
    assertEquals("RIFF", String(exported.second, 0, 4, Charsets.US_ASCII))
    assertEquals(44 + pcmBytes.size, exported.second.size)
  }
}
