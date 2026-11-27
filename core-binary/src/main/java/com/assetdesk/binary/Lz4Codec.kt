package com.assetdesk.binary

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Pure-Kotlin implementation of raw LZ4 block decompression and compression
 * compatible with UnityFS LZ4 / LZ4HC storage blocks.
 */
object Lz4Codec {

  private const val MIN_MATCH = 4
  private const val MAX_DISTANCE = 65535
  private const val HASH_LOG = 14
  private const val HASH_SIZE = 1 shl HASH_LOG

  fun decompressBlock(
    compressed: ByteArray,
    uncompressedSize: Int,
  ): ByteArray {
    if (uncompressedSize < 0) {
      throw IllegalArgumentException("Negative uncompressedSize: $uncompressedSize")
    }
    if (uncompressedSize == 0) return ByteArray(0)

    val output = ByteArray(uncompressedSize)
    var srcPos = 0
    var dstPos = 0

    while (srcPos < compressed.size) {
      val token = compressed[srcPos++].toInt() and 0xFF
      var literalLength = token ushr 4
      if (literalLength == 15) {
        var extra: Int
        do {
          if (srcPos >= compressed.size) {
            throw IllegalArgumentException("Truncated LZ4 literal length at src=$srcPos")
          }
          extra = compressed[srcPos++].toInt() and 0xFF
          literalLength += extra
        } while (extra == 255)
      }

      if (srcPos + literalLength > compressed.size) {
        throw IllegalArgumentException(
          "LZ4 literal length $literalLength exceeds input bounds (${compressed.size - srcPos} available)",
        )
      }
      if (dstPos + literalLength > uncompressedSize) {
        throw IllegalArgumentException(
          "LZ4 literal overflow: dst=$dstPos + $literalLength > $uncompressedSize",
        )
      }
      System.arraycopy(compressed, srcPos, output, dstPos, literalLength)
      srcPos += literalLength
      dstPos += literalLength

      if (srcPos >= compressed.size) {
        break
      }

      if (srcPos + 2 > compressed.size) {
        throw IllegalArgumentException("Truncated LZ4 match offset at src=$srcPos")
      }
      val offset = (compressed[srcPos].toInt() and 0xFF) or
        ((compressed[srcPos + 1].toInt() and 0xFF) shl 8)
      srcPos += 2

      if (offset <= 0 || offset > dstPos) {
        throw IllegalArgumentException("Invalid LZ4 match offset $offset at dst=$dstPos")
      }

      var matchLength = (token and 0x0F) + MIN_MATCH
      if ((token and 0x0F) == 15) {
        var extra: Int
        do {
          if (srcPos >= compressed.size) {
            throw IllegalArgumentException("Truncated LZ4 match length at src=$srcPos")
          }
          extra = compressed[srcPos++].toInt() and 0xFF
          matchLength += extra
        } while (extra == 255)
      }

      if (dstPos + matchLength > uncompressedSize) {
        throw IllegalArgumentException(
          "LZ4 match overflow: dst=$dstPos + $matchLength > $uncompressedSize",
        )
      }

      var matchSrc = dstPos - offset
      for (i in 0 until matchLength) {
        output[dstPos++] = output[matchSrc++]
      }
    }

    if (dstPos != uncompressedSize) {
      throw IllegalArgumentException(
        "LZ4 decoded size mismatch: expected $uncompressedSize bytes, got $dstPos bytes",
      )
    }
    return output
  }

  fun compressBlock(input: ByteArray): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    if (input.size < 13) {
      return writeLiteralsOnly(input)
    }

    val out = ByteArrayOutputStream(input.size + (input.size / 255) + 16)
    val hashTable = IntArray(HASH_SIZE) { -1 }
    var anchor = 0
    var pos = 0
    val matchLimit = input.size - 5
    val mfLimit = input.size - 12

    while (pos <= mfLimit) {
      val seq = readInt32LE(input, pos)
      val hash = ((seq * -1640531535) ushr (32 - HASH_LOG)) and (HASH_SIZE - 1)
      val candidate = hashTable[hash]
      hashTable[hash] = pos

      if (candidate >= 0 && pos - candidate <= MAX_DISTANCE && readInt32LE(input, candidate) == seq) {
        var matchLen = MIN_MATCH
        while (pos + matchLen <= matchLimit && input[candidate + matchLen] == input[pos + matchLen]) {
          matchLen++
        }

        val literalLen = pos - anchor
        val matchCode = matchLen - MIN_MATCH
        val tokenLit = min(literalLen, 15)
        val tokenMatch = min(matchCode, 15)
        out.write((tokenLit shl 4) or tokenMatch)
        if (literalLen >= 15) {
          writeExtendedLength(out, literalLen - 15)
        }
        if (literalLen > 0) {
          out.write(input, anchor, literalLen)
        }

        val offset = pos - candidate
        out.write(offset and 0xFF)
        out.write((offset ushr 8) and 0xFF)

        if (matchCode >= 15) {
          writeExtendedLength(out, matchCode - 15)
        }

        pos += matchLen
        anchor = pos
        if (pos <= mfLimit) {
          val nextSeq = readInt32LE(input, pos - 2)
          val nextHash = ((nextSeq * -1640531535) ushr (32 - HASH_LOG)) and (HASH_SIZE - 1)
          hashTable[nextHash] = pos - 2
        }
      } else {
        pos++
      }
    }

    val remainingLiterals = input.size - anchor
    val tokenLit = min(remainingLiterals, 15)
    out.write(tokenLit shl 4)
    if (remainingLiterals >= 15) {
      writeExtendedLength(out, remainingLiterals - 15)
    }
    if (remainingLiterals > 0) {
      out.write(input, anchor, remainingLiterals)
    }
    return out.toByteArray()
  }

  private fun writeLiteralsOnly(input: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(input.size + 4)
    val tokenLit = min(input.size, 15)
    out.write(tokenLit shl 4)
    if (input.size >= 15) {
      writeExtendedLength(out, input.size - 15)
    }
    out.write(input)
    return out.toByteArray()
  }

  private fun writeExtendedLength(out: ByteArrayOutputStream, extra: Int) {
    var rem = extra
    while (rem >= 255) {
      out.write(255)
      rem -= 255
    }
    out.write(rem)
  }

  private fun readInt32LE(data: ByteArray, index: Int): Int =
    (data[index].toInt() and 0xFF) or
      ((data[index + 1].toInt() and 0xFF) shl 8) or
      ((data[index + 2].toInt() and 0xFF) shl 16) or
      ((data[index + 3].toInt() and 0xFF) shl 24)
}
