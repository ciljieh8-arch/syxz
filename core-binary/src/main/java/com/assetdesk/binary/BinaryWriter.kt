package com.assetdesk.binary

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Endian-aware binary writer supporting alignment and stream flushing.
 */
class BinaryWriter(
  var byteOrder: ByteOrderMode = ByteOrderMode.LITTLE_ENDIAN,
  initialCapacity: Int = 1024,
) {
  private val buffer = ByteArrayOutputStream(initialCapacity)
  private var currentPos: Long = 0L

  val position: Long
    get() = currentPos

  inline fun <T> withByteOrder(order: ByteOrderMode, block: BinaryWriter.() -> T): T {
    val previous = byteOrder
    byteOrder = order
    return try {
      block()
    } finally {
      byteOrder = previous
    }
  }

  fun writeUInt8(value: Int) {
    buffer.write(value and 0xFF)
    currentPos += 1L
  }

  fun writeInt8(value: Byte) {
    buffer.write(value.toInt() and 0xFF)
    currentPos += 1L
  }

  fun writeBoolean(value: Boolean) {
    writeUInt8(if (value) 1 else 0)
  }

  fun writeInt16(value: Short) {
    val v = value.toInt()
    when (byteOrder) {
      ByteOrderMode.BIG_ENDIAN -> {
        buffer.write((v ushr 8) and 0xFF)
        buffer.write(v and 0xFF)
      }
      ByteOrderMode.LITTLE_ENDIAN -> {
        buffer.write(v and 0xFF)
        buffer.write((v ushr 8) and 0xFF)
      }
    }
    currentPos += 2L
  }

  fun writeUInt16(value: Int) = writeInt16(value.toShort())

  fun writeInt32(value: Int) {
    when (byteOrder) {
      ByteOrderMode.BIG_ENDIAN -> {
        buffer.write((value ushr 24) and 0xFF)
        buffer.write((value ushr 16) and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
      }
      ByteOrderMode.LITTLE_ENDIAN -> {
        buffer.write(value and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
        buffer.write((value ushr 16) and 0xFF)
        buffer.write((value ushr 24) and 0xFF)
      }
    }
    currentPos += 4L
  }

  fun writeUInt32(value: Long) = writeInt32(value.toInt())

  fun writeInt64(value: Long) {
    when (byteOrder) {
      ByteOrderMode.BIG_ENDIAN -> {
        for (shift in 56 downTo 0 step 8) {
          buffer.write(((value ushr shift) and 0xFFL).toInt())
        }
      }
      ByteOrderMode.LITTLE_ENDIAN -> {
        for (shift in 0..56 step 8) {
          buffer.write(((value ushr shift) and 0xFFL).toInt())
        }
      }
    }
    currentPos += 8L
  }

  fun writeFloat32(value: Float) = writeInt32(value.toRawBits())

  fun writeFloat64(value: Double) = writeInt64(value.toRawBits())

  fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
    if (length == 0) return
    buffer.write(bytes, offset, length)
    currentPos += length.toLong()
  }

  fun writeZeroBytes(count: Long) {
    require(count >= 0L) { "Zero padding count must be >= 0" }
    if (count == 0L) return
    val chunk = ByteArray(minOf(count, 4096L).toInt())
    var remaining = count
    while (remaining > 0L) {
      val toWrite = minOf(remaining, chunk.size.toLong()).toInt()
      buffer.write(chunk, 0, toWrite)
      remaining -= toWrite
      currentPos += toWrite
    }
  }

  fun align(alignment: Int) {
    require(alignment > 0) { "Alignment must be positive" }
    val rem = (currentPos % alignment).toInt()
    if (rem != 0) {
      writeZeroBytes((alignment - rem).toLong())
    }
  }

  fun writeNullTerminatedString(text: String) {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    writeBytes(bytes)
    writeUInt8(0)
  }

  fun writeLengthPrefixedString(text: String, alignAfter: Boolean = true) {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    writeInt32(bytes.size)
    writeBytes(bytes)
    if (alignAfter) {
      align(4)
    }
  }

  fun toByteArray(): ByteArray = buffer.toByteArray()

  fun writeTo(output: OutputStream) {
    buffer.writeTo(output)
  }
}
