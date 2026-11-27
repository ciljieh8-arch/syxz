package com.assetdesk.plugins

import android.graphics.Bitmap
import android.graphics.Color
import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.UnityObjectInfo
import java.io.ByteArrayOutputStream
import kotlin.math.min

object TexturePlugin : AssetPlugin {
  override val id: String = "texture2d"
  override val displayName: String = "Texture2D"
  override val supportedClassIds: Set<Int> = setOf(28)
  override val targetSummary: String = "Texture2D (ClassID 28)"
  override val description: String =
    "Decodes Texture2D resolution, format, mip count, previews RGBA/RGB/DXT1/DXT5 bitmaps, exports PNG, and validates PNG replacement."

  private const val MAX_PREVIEW_PIXELS = 4096 * 4096

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    return try {
      val parsed = parseTextureMetadata(rawBytes, byteOrder, formatVersion, decodedData)
        ?: return PluginInspectionResult.PluginError(displayName, "Unable to parse Texture2D structure (unexpected layout or missing TypeTree).")

      val formatName = textureFormatName(parsed.textureFormat)
      val canDecodePixels = parsed.imageData.isNotEmpty() &&
        parsed.width in 1..4096 &&
        parsed.height in 1..4096 &&
        parsed.width.toLong() * parsed.height.toLong() <= MAX_PREVIEW_PIXELS &&
        isPixelFormatDecodable(parsed.textureFormat)

      val bitmap = if (canDecodePixels) {
        decodeToBitmap(parsed.imageData, parsed.width, parsed.height, parsed.textureFormat)
      } else null

      val note = when {
        parsed.imageData.isEmpty() && !parsed.streamPath.isNullOrBlank() ->
          "Pixel payload is stored externally in '${parsed.streamPath}' (offset ${parsed.streamOffset}, ${parsed.streamSize} B)."
        bitmap == null && parsed.imageData.isNotEmpty() ->
          "Preview/PNG export not supported for compressed GPU format $formatName (raw dump/export remains available)."
        else -> null
      }

      val canImport = parsed.imageData.isNotEmpty() && parsed.textureFormat in setOf(3, 4, 5, 14) // RGB24, RGBA32, ARGB32, BGRA32

      PluginInspectionResult.TexturePreview(
        assetName = parsed.name.ifBlank { obj.assetName },
        width = parsed.width,
        height = parsed.height,
        formatName = formatName,
        formatCode = parsed.textureFormat,
        mipCount = parsed.mipCount,
        imageDataSize = parsed.imageData.size,
        streamPath = parsed.streamPath,
        decodedBitmap = bitmap,
        canExportPng = bitmap != null,
        canImportPng = canImport,
        note = note,
      )
    } catch (t: Throwable) {
      PluginInspectionResult.PluginError(displayName, "Texture2D parse failed: ${t.message}")
    }
  }

  fun exportPngBytes(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
  }

  /**
   * Validates replacement bitmap dimensions and converts pixels into the original Texture2D uncompressed format.
   */
  fun buildModifiedRawBytes(
    originalRawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
    replacementBitmap: Bitmap,
  ): Result<ByteArray> {
    val parsed = parseTextureMetadata(originalRawBytes, byteOrder, formatVersion, decodedData)
      ?: return Result.failure(IllegalArgumentException("Cannot parse target Texture2D header."))

    if (replacementBitmap.width != parsed.width || replacementBitmap.height != parsed.height) {
      return Result.failure(
        IllegalArgumentException(
          "Dimension mismatch: replacement is ${replacementBitmap.width}x${replacementBitmap.height}, expected ${parsed.width}x${parsed.height}.",
        ),
      )
    }

    val encodedPixels = encodeBitmapToFormat(replacementBitmap, parsed.textureFormat)
      ?: return Result.failure(
        IllegalArgumentException(
          "Direct PNG import supports uncompressed RGBA32/ARGB32/RGB24/BGRA32 formats (target is ${textureFormatName(parsed.textureFormat)}).",
        ),
      )

    if (encodedPixels.size != parsed.imageData.size && parsed.mipCount > 1) {
      return Result.failure(
        IllegalArgumentException(
          "Target texture contains ${parsed.mipCount} mipmaps (${parsed.imageData.size} B); single-mip replacement size (${encodedPixels.size} B) differs.",
        ),
      )
    }

    // Splice updated pixel buffer cleanly at the exact imageData offset
    val imageDataOffset = parsed.imageDataOffsetInRaw
    if (imageDataOffset < 4 || imageDataOffset + 4 > originalRawBytes.size) {
      return Result.failure(IllegalStateException("Unable to locate image data length prefix inside Texture2D."))
    }

    val writer = BinaryWriter(byteOrder)
    writer.writeBytes(originalRawBytes, 0, imageDataOffset)
    writer.writeInt32(encodedPixels.size)
    writer.writeBytes(encodedPixels)
    writer.align(4)
    val tailOffset = parsed.imageDataEndOffsetInRaw
    if (tailOffset < originalRawBytes.size) {
      writer.writeBytes(originalRawBytes, tailOffset, originalRawBytes.size - tailOffset)
    }
    return Result.success(writer.toByteArray())
  }

  private data class ParsedTexture(
    val name: String,
    val width: Int,
    val height: Int,
    val completeImageSize: Int,
    val textureFormat: Int,
    val mipCount: Int,
    val imageData: ByteArray,
    val imageDataOffsetInRaw: Int,
    val imageDataEndOffsetInRaw: Int,
    val streamPath: String?,
    val streamOffset: Long,
    val streamSize: Long,
  )

  private fun parseTextureMetadata(
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): ParsedTexture? {
    val root = decodedData?.rootNode
    if (root != null) {
      val name = root.findString("m_Name") ?: ""
      val width = root.findInt("m_Width")
      val height = root.findInt("m_Height")
      val format = root.findInt("m_TextureFormat")
      val mipCount = root.findInt("m_MipCount") ?: if (root.findField("m_MipMap")?.valueText == "true") 2 else 1
      val completeSize = root.findInt("m_CompleteImageSize") ?: 0
      val imageNode = root.findField("image data")
      val streamNode = root.findField("m_StreamData")
      val streamPath = streamNode?.findString("path")?.ifBlank { null }
      val streamOffset = streamNode?.findLong("offset") ?: 0L
      val streamSize = streamNode?.findLong("size") ?: 0L

      if (width != null && height != null && format != null && imageNode != null) {
        val lenOffset = imageNode.byteOffset.toInt()
        val endOffset = (imageNode.byteOffset + imageNode.byteLength).toInt()
        val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
        reader.seek(lenOffset.toLong())
        val imgLen = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
        val pixels = reader.readBytes(imgLen)
        return ParsedTexture(
          name = name,
          width = width,
          height = height,
          completeImageSize = completeSize,
          textureFormat = format,
          mipCount = mipCount,
          imageData = pixels,
          imageDataOffsetInRaw = lenOffset,
          imageDataEndOffsetInRaw = endOffset,
          streamPath = streamPath,
          streamOffset = streamOffset,
          streamSize = streamSize,
        )
      }
    }

    // Fallback binary layout parser (Unity 5.x - 2019 standard Texture2D without TypeTree)
    return runCatching {
      val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
      val name = reader.readLengthPrefixedString(alignAfter = true)
      if (formatVersion >= 17) {
        // m_ForcedFallbackFormat (int32), m_DownscaleFallback (bool + align)
        reader.readInt32()
        reader.readBoolean()
        reader.align(4)
      }
      val width = reader.readInt32()
      val height = reader.readInt32()
      val completeImageSize = reader.readInt32()
      val textureFormat = reader.readInt32()
      val mipCount = reader.readInt32()
      if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return@runCatching null
      // Skip m_IsReadable (bool), m_ReadAllowed/StreamingMipmaps flags (align 4), m_ImageCount (int32),
      // m_TextureDimension (int32), GLTextureSettings (5 ints = 20B), m_LightmapFormat (int32), m_ColorSpace (int32)
      reader.readBoolean()
      reader.align(4)
      reader.readInt32() // m_ImageCount
      reader.readInt32() // m_TextureDimension
      reader.skip(20L)   // m_TextureSettings
      reader.readInt32() // m_LightmapFormat
      reader.readInt32() // m_ColorSpace
      val dataLenOffset = reader.position.toInt()
      val dataLen = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
      val pixels = reader.readBytes(dataLen)
      reader.align(4)
      val dataEndOffset = reader.position.toInt()

      var streamPath: String? = null
      var streamOffset = 0L
      var streamSize = 0L
      if (reader.remaining >= 12L) {
        streamOffset = reader.readUInt32()
        streamSize = reader.readUInt32()
        streamPath = runCatching { reader.readLengthPrefixedString(alignAfter = true) }.getOrNull()?.ifBlank { null }
      }

      ParsedTexture(
        name = name,
        width = width,
        height = height,
        completeImageSize = completeImageSize,
        textureFormat = textureFormat,
        mipCount = mipCount,
        imageData = pixels,
        imageDataOffsetInRaw = dataLenOffset,
        imageDataEndOffsetInRaw = dataEndOffset,
        streamPath = streamPath,
        streamOffset = streamOffset,
        streamSize = streamSize,
      )
    }.getOrNull()
  }

  private fun isPixelFormatDecodable(format: Int): Boolean =
    format in setOf(1, 2, 3, 4, 5, 7, 10, 12, 13, 14)

  private fun decodeToBitmap(
    data: ByteArray,
    width: Int,
    height: Int,
    format: Int,
  ): Bitmap? {
    val pixels = IntArray(width * height)
    when (format) {
      1 -> { // Alpha8
        if (data.size < width * height) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width
          val dstRow = y * width
          for (x in 0 until width) {
            val a = data[srcRow + x].toInt() and 0xFF
            pixels[dstRow + x] = Color.argb(a, 255, 255, 255)
          }
        }
      }
      2 -> { // ARGB4444
        if (data.size < width * height * 2) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 2
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 2
            val v = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            val b = (v and 0xF) * 17
            val g = ((v ushr 4) and 0xF) * 17
            val r = ((v ushr 8) and 0xF) * 17
            val a = ((v ushr 12) and 0xF) * 17
            pixels[dstRow + x] = Color.argb(a, r, g, b)
          }
        }
      }
      3 -> { // RGB24
        if (data.size < width * height * 3) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 3
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 3
            val r = data[i].toInt() and 0xFF
            val g = data[i + 1].toInt() and 0xFF
            val b = data[i + 2].toInt() and 0xFF
            pixels[dstRow + x] = Color.rgb(r, g, b)
          }
        }
      }
      4 -> { // RGBA32
        if (data.size < width * height * 4) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 4
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 4
            val r = data[i].toInt() and 0xFF
            val g = data[i + 1].toInt() and 0xFF
            val b = data[i + 2].toInt() and 0xFF
            val a = data[i + 3].toInt() and 0xFF
            pixels[dstRow + x] = Color.argb(a, r, g, b)
          }
        }
      }
      5 -> { // ARGB32
        if (data.size < width * height * 4) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 4
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 4
            val a = data[i].toInt() and 0xFF
            val r = data[i + 1].toInt() and 0xFF
            val g = data[i + 2].toInt() and 0xFF
            val b = data[i + 3].toInt() and 0xFF
            pixels[dstRow + x] = Color.argb(a, r, g, b)
          }
        }
      }
      7 -> { // RGB565
        if (data.size < width * height * 2) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 2
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 2
            val v = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            val r = ((v ushr 11) and 0x1F) * 255 / 31
            val g = ((v ushr 5) and 0x3F) * 255 / 63
            val b = (v and 0x1F) * 255 / 31
            pixels[dstRow + x] = Color.rgb(r, g, b)
          }
        }
      }
      10 -> decodeDxt1(data, width, height, pixels) ?: return null
      12 -> decodeDxt5(data, width, height, pixels) ?: return null
      13 -> { // RGBA4444
        if (data.size < width * height * 2) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 2
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 2
            val v = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            val a = (v and 0xF) * 17
            val b = ((v ushr 4) and 0xF) * 17
            val g = ((v ushr 8) and 0xF) * 17
            val r = ((v ushr 12) and 0xF) * 17
            pixels[dstRow + x] = Color.argb(a, r, g, b)
          }
        }
      }
      14 -> { // BGRA32
        if (data.size < width * height * 4) return null
        for (y in 0 until height) {
          val srcRow = (height - 1 - y) * width * 4
          val dstRow = y * width
          for (x in 0 until width) {
            val i = srcRow + x * 4
            val b = data[i].toInt() and 0xFF
            val g = data[i + 1].toInt() and 0xFF
            val r = data[i + 2].toInt() and 0xFF
            val a = data[i + 3].toInt() and 0xFF
            pixels[dstRow + x] = Color.argb(a, r, g, b)
          }
        }
      }
      else -> return null
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun decodeDxt1(data: ByteArray, width: Int, height: Int, out: IntArray): Unit? {
    val bx = (width + 3) / 4
    val by = (height + 3) / 4
    if (data.size < bx * by * 8) return null
    var offset = 0
    for (blockY in 0 until by) {
      for (blockX in 0 until bx) {
        val c0 = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        val c1 = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)
        val palette = buildDxtPalette(c0, c1, hasExplicitAlpha = false)
        val indices = (data[offset + 4].toInt() and 0xFF) or
          ((data[offset + 5].toInt() and 0xFF) shl 8) or
          ((data[offset + 6].toInt() and 0xFF) shl 16) or
          ((data[offset + 7].toInt() and 0xFF) shl 24)
        offset += 8

        for (py in 0 until 4) {
          val y = height - 1 - (blockY * 4 + py)
          if (y !in 0 until height) continue
          for (px in 0 until 4) {
            val x = blockX * 4 + px
            if (x >= width) continue
            val code = (indices ushr ((py * 4 + px) * 2)) and 0x03
            out[y * width + x] = palette[code]
          }
        }
      }
    }
    return Unit
  }

  private fun decodeDxt5(data: ByteArray, width: Int, height: Int, out: IntArray): Unit? {
    val bx = (width + 3) / 4
    val by = (height + 3) / 4
    if (data.size < bx * by * 16) return null
    var offset = 0
    for (blockY in 0 until by) {
      for (blockX in 0 until bx) {
        val a0 = data[offset].toInt() and 0xFF
        val a1 = data[offset + 1].toInt() and 0xFF
        val alphas = IntArray(8)
        alphas[0] = a0
        alphas[1] = a1
        if (a0 > a1) {
          for (i in 1..6) alphas[i + 1] = ((7 - i) * a0 + i * a1) / 7
        } else {
          for (i in 1..4) alphas[i + 1] = ((5 - i) * a0 + i * a1) / 5
          alphas[6] = 0
          alphas[7] = 255
        }
        var alphaBits = 0L
        for (i in 0 until 6) {
          alphaBits = alphaBits or ((data[offset + 2 + i].toLong() and 0xFFL) shl (i * 8))
        }
        val c0 = (data[offset + 8].toInt() and 0xFF) or ((data[offset + 9].toInt() and 0xFF) shl 8)
        val c1 = (data[offset + 10].toInt() and 0xFF) or ((data[offset + 11].toInt() and 0xFF) shl 8)
        val palette = buildDxtPalette(c0, c1, hasExplicitAlpha = true)
        val colorIndices = (data[offset + 12].toInt() and 0xFF) or
          ((data[offset + 13].toInt() and 0xFF) shl 8) or
          ((data[offset + 14].toInt() and 0xFF) shl 16) or
          ((data[offset + 15].toInt() and 0xFF) shl 24)
        offset += 16

        for (py in 0 until 4) {
          val y = height - 1 - (blockY * 4 + py)
          if (y !in 0 until height) continue
          for (px in 0 until 4) {
            val x = blockX * 4 + px
            if (x >= width) continue
            val pIdx = py * 4 + px
            val aIdx = ((alphaBits ushr (pIdx * 3)) and 0x07L).toInt()
            val cIdx = (colorIndices ushr (pIdx * 2)) and 0x03
            val rgb = palette[cIdx] and 0x00FFFFFF
            out[y * width + x] = (alphas[aIdx] shl 24) or rgb
          }
        }
      }
    }
    return Unit
  }

  private fun buildDxtPalette(c0: Int, c1: Int, hasExplicitAlpha: Boolean): IntArray {
    val r0 = ((c0 ushr 11) and 0x1F) * 255 / 31
    val g0 = ((c0 ushr 5) and 0x3F) * 255 / 63
    val b0 = (c0 and 0x1F) * 255 / 31
    val r1 = ((c1 ushr 11) and 0x1F) * 255 / 31
    val g1 = ((c1 ushr 5) and 0x3F) * 255 / 63
    val b1 = (c1 and 0x1F) * 255 / 31

    return if (hasExplicitAlpha || c0 > c1) {
      intArrayOf(
        Color.rgb(r0, g0, b0),
        Color.rgb(r1, g1, b1),
        Color.rgb((2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3),
        Color.rgb((r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3),
      )
    } else {
      intArrayOf(
        Color.rgb(r0, g0, b0),
        Color.rgb(r1, g1, b1),
        Color.rgb((r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2),
        Color.TRANSPARENT,
      )
    }
  }

  private fun encodeBitmapToFormat(bitmap: Bitmap, format: Int): ByteArray? {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    return when (format) {
      3 -> { // RGB24
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) {
          val srcRow = (h - 1 - y) * w
          val dstRow = y * w * 3
          for (x in 0 until w) {
            val c = pixels[srcRow + x]
            out[dstRow + x * 3] = Color.red(c).toByte()
            out[dstRow + x * 3 + 1] = Color.green(c).toByte()
            out[dstRow + x * 3 + 2] = Color.blue(c).toByte()
          }
        }
        out
      }
      4 -> { // RGBA32
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
          val srcRow = (h - 1 - y) * w
          val dstRow = y * w * 4
          for (x in 0 until w) {
            val c = pixels[srcRow + x]
            out[dstRow + x * 4] = Color.red(c).toByte()
            out[dstRow + x * 4 + 1] = Color.green(c).toByte()
            out[dstRow + x * 4 + 2] = Color.blue(c).toByte()
            out[dstRow + x * 4 + 3] = Color.alpha(c).toByte()
          }
        }
        out
      }
      5 -> { // ARGB32
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
          val srcRow = (h - 1 - y) * w
          val dstRow = y * w * 4
          for (x in 0 until w) {
            val c = pixels[srcRow + x]
            out[dstRow + x * 4] = Color.alpha(c).toByte()
            out[dstRow + x * 4 + 1] = Color.red(c).toByte()
            out[dstRow + x * 4 + 2] = Color.green(c).toByte()
            out[dstRow + x * 4 + 3] = Color.blue(c).toByte()
          }
        }
        out
      }
      14 -> { // BGRA32
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
          val srcRow = (h - 1 - y) * w
          val dstRow = y * w * 4
          for (x in 0 until w) {
            val c = pixels[srcRow + x]
            out[dstRow + x * 4] = Color.blue(c).toByte()
            out[dstRow + x * 4 + 1] = Color.green(c).toByte()
            out[dstRow + x * 4 + 2] = Color.red(c).toByte()
            out[dstRow + x * 4 + 3] = Color.alpha(c).toByte()
          }
        }
        out
      }
      else -> null
    }
  }

  fun textureFormatName(format: Int): String =
    when (format) {
      1 -> "Alpha8"
      2 -> "ARGB4444"
      3 -> "RGB24"
      4 -> "RGBA32"
      5 -> "ARGB32"
      7 -> "RGB565"
      9 -> "R16"
      10 -> "DXT1"
      12 -> "DXT5"
      13 -> "RGBA4444"
      14 -> "BGRA32"
      25 -> "BC7"
      34 -> "ETC_RGB4"
      45 -> "ETC2_RGB"
      47 -> "ETC2_RGBA8"
      48 -> "ASTC_4x4"
      50 -> "ASTC_6x6"
      52 -> "ASTC_8x8"
      else -> "Format_$format"
    }
}
