package com.assetdesk.plugins

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.UnityObjectInfo
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextAssetPlugin : AssetPlugin {
  override val id: String = "text_asset"
  override val displayName: String = "TextAsset"
  override val supportedClassIds: Set<Int> = setOf(49)
  override val targetSummary: String = "TextAsset (ClassID 49)"
  override val description: String =
    "Detects UTF-8/UTF-16/ASCII text encoding, previews content, exports .txt/.bytes, and imports replacement text while preserving binary fallback."

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    return try {
      val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
      val name = reader.readLengthPrefixedString(alignAfter = true)
      val payload = reader.readBytes(reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt()))
      val detection = detectTextPayload(payload)

      PluginInspectionResult.TextAssetPreview(
        assetName = name.ifBlank { obj.assetName },
        encodingName = detection.encodingName,
        isBinaryFallback = detection.isBinary,
        textContent = detection.previewText,
        payloadBytes = payload,
        canExportText = true,
        canImportText = !detection.isBinary,
      )
    } catch (t: Throwable) {
      PluginInspectionResult.PluginError(displayName, "Failed to decode TextAsset: ${t.message}")
    }
  }

  fun buildModifiedRawBytes(
    originalRawBytes: ByteArray,
    newText: String,
    byteOrder: ByteOrderMode,
  ): ByteArray {
    val reader = BinaryReader(ByteArrayByteSource(originalRawBytes), byteOrder)
    val originalName = reader.readLengthPrefixedString(alignAfter = true)
    val writer = BinaryWriter(byteOrder)
    writer.writeLengthPrefixedString(originalName, alignAfter = true)
    writer.writeLengthPrefixedString(newText, alignAfter = true)
    return writer.toByteArray()
  }

  private data class TextDetection(
    val encodingName: String,
    val isBinary: Boolean,
    val previewText: String,
  )

  private fun detectTextPayload(bytes: ByteArray): TextDetection {
    if (bytes.isEmpty()) {
      return TextDetection("UTF-8 (Empty)", isBinary = false, previewText = "")
    }
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
      val text = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
      return TextDetection("UTF-8 (BOM)", isBinary = false, previewText = text)
    }
    if (bytes.size >= 2 && bytes.size % 2 == 0 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
      val decoded = decodeStrict(bytes, 2, StandardCharsets.UTF_16LE)
      if (decoded != null && isTextLike(decoded)) {
        return TextDetection("UTF-16 LE", isBinary = false, previewText = decoded)
      }
    }
    if (bytes.size >= 2 && bytes.size % 2 == 0 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
      val decoded = decodeStrict(bytes, 2, StandardCharsets.UTF_16BE)
      if (decoded != null && isTextLike(decoded)) {
        return TextDetection("UTF-16 BE", isBinary = false, previewText = decoded)
      }
    }

    val utf8Result = decodeStrict(bytes, 0, StandardCharsets.UTF_8)
    if (utf8Result != null && isTextLike(utf8Result)) {
      val asciiOnly = bytes.all { (it.toInt() and 0x80) == 0 }
      return TextDetection(
        encodingName = if (asciiOnly) "ASCII / UTF-8" else "UTF-8",
        isBinary = false,
        previewText = utf8Result,
      )
    }

    return TextDetection(
      encodingName = "Unknown / Binary (.bytes)",
      isBinary = true,
      previewText = "[Binary payload: ${bytes.size} bytes — original bytes preserved]",
    )
  }

  private fun decodeStrict(
    bytes: ByteArray,
    offset: Int,
    charset: java.nio.charset.Charset,
  ): String? = try {
    val decoder = charset.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
  } catch (_: CharacterCodingException) {
    null
  }

  private fun isTextLike(text: String): Boolean =
    text.none { ch -> ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' }
}
