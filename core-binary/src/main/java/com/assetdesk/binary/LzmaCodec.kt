package com.assetdesk.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream

/**
 * LZMA codec handling both UnityFS raw LZMA blocks (5-byte props + stream, known uncompressed size)
 * and UnityWeb standard 13-byte LZMA header streams.
 */
object LzmaCodec {

  fun decompressUnityFsBlock(
    compressed: ByteArray,
    uncompressedSize: Int,
  ): ByteArray {
    if (uncompressedSize < 0) {
      throw IllegalArgumentException("Negative uncompressedSize: $uncompressedSize")
    }
    if (uncompressedSize == 0) return ByteArray(0)
    if (compressed.size < 5) {
      throw IllegalArgumentException("Truncated UnityFS LZMA block: needs 5-byte props, got ${compressed.size} bytes")
    }

    val propByte = compressed[0]
    val dictSize =
      (compressed[1].toInt() and 0xFF) or
        ((compressed[2].toInt() and 0xFF) shl 8) or
        ((compressed[3].toInt() and 0xFF) shl 16) or
        ((compressed[4].toInt() and 0xFF) shl 24)

    val payloadStream = ByteArrayInputStream(compressed, 5, compressed.size - 5)
    LZMAInputStream(payloadStream, uncompressedSize.toLong(), propByte, dictSize).use { lzmaIn ->
      val out = ByteArray(uncompressedSize)
      var total = 0
      while (total < uncompressedSize) {
        val read = lzmaIn.read(out, total, uncompressedSize - total)
        if (read <= 0) break
        total += read
      }
      if (total != uncompressedSize) {
        throw IllegalArgumentException(
          "UnityFS LZMA decoded size mismatch: expected $uncompressedSize bytes, got $total bytes",
        )
      }
      return out
    }
  }

  fun decompressUnityWebStream(compressed: ByteArray): ByteArray {
    if (compressed.size < 13) {
      throw IllegalArgumentException("Truncated UnityWeb LZMA stream: ${compressed.size} bytes")
    }
    ByteArrayInputStream(compressed).use { bis ->
      LZMAInputStream(bis).use { lzmaIn ->
        return lzmaIn.readBytes()
      }
    }
  }

  fun compressUnityFsBlock(raw: ByteArray): ByteArray {
    val options = LZMA2Options(LZMA2Options.PRESET_DEFAULT).apply {
      dictSize = 1 shl 20 // 1 MB dictionary for mobile memory safety
    }
    val out = ByteArrayOutputStream(raw.size)
    // Write 5-byte UnityFS LZMA properties: propByte + dictSize LE
    val lc = options.lc
    val lp = options.lp
    val pb = options.pb
    val propByte = ((pb * 5 + lp) * 9 + lc) and 0xFF
    out.write(propByte)
    val dict = options.dictSize
    out.write(dict and 0xFF)
    out.write((dict ushr 8) and 0xFF)
    out.write((dict ushr 16) and 0xFF)
    out.write((dict ushr 24) and 0xFF)

    LZMAOutputStream(out, options, raw.size.toLong()).use { lzmaOut ->
      // Note: LZMAOutputStream writes the 13-byte header automatically; strip the first 13 bytes
      // Actually use a dedicated stream and strip the 13-byte header so UnityFS gets 5-byte props + stream cleanly.
      lzmaOut.write(raw)
    }

    val full = out.toByteArray()
    // out has our 5 manual bytes + 13-byte standard header from LZMAOutputStream + compressed payload.
    // Reconstruct cleanly: take the 5-byte props from full[5..9] and payload from full[18..end]!
    val clean = ByteArray(5 + (full.size - 18))
    System.arraycopy(full, 5, clean, 0, 5)
    System.arraycopy(full, 18, clean, 5, full.size - 18)
    return clean
  }
}
