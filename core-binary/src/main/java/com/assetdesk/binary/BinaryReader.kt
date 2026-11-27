package com.assetdesk.binary

import java.io.Closeable
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Buffered, bounds-checked, endian-aware binary reader for Unity serialized formats.
 */
class BinaryReader(
  private val source: SeekableByteSource,
  var byteOrder: ByteOrderMode = ByteOrderMode.LITTLE_ENDIAN,
  bufferSize: Int = DEFAULT_BUFFER_SIZE,
  private val maxSingleAllocationBytes: Int = SeekableByteSource.DEFAULT_MAX_ALLOCATION_BYTES,
) : Closeable {

  init {
    require(bufferSize >= 64) { "bufferSize must be at least 64 bytes" }
    require(maxSingleAllocationBytes >= 1024) { "maxSingleAllocationBytes must be >= 1024" }
  }

  private val buffer = ByteArray(bufferSize)
  private var bufferStartOffset: Long = 0L
  private var bufferValidLength: Int = 0
  private var currentPos: Long = 0L

  val length: Long
    get() = source.length

  val position: Long
    get() = currentPos

  val remaining: Long
    get() = (length - currentPos).coerceAtLeast(0L)

  inline fun <T> withByteOrder(order: ByteOrderMode, block: BinaryReader.() -> T): T {
    val previous = byteOrder
    byteOrder = order
    return try {
      block()
    } finally {
      byteOrder = previous
    }
  }

  fun seek(offset: Long) {
    if (offset < 0L || offset > length) {
      throw BoundsExceededException(offset, length)
    }
    currentPos = offset
  }

  fun skip(count: Long) {
    if (count < 0L) {
      throw BoundsExceededException(currentPos + count, length, "Negative skip count: $count")
    }
    val target = currentPos + count
    if (target < 0L || target > length) {
      throw UnexpectedEofException(currentPos, count, remaining)
    }
    currentPos = target
  }

  fun align(alignment: Int) {
    if (alignment <= 0) {
      throw InvalidAlignmentException(currentPos, alignment)
    }
    val remainder = (currentPos % alignment).toInt()
    if (remainder != 0) {
      val pad = (alignment - remainder).toLong()
      if (currentPos + pad > length) {
        throw UnexpectedEofException(currentPos, pad, remaining)
      }
      currentPos += pad
    }
  }

  fun readUInt8(): Int {
    ensureAvailable(1L)
    val b = readByteFromBuffer(currentPos).toInt() and 0xFF
    currentPos += 1L
    return b
  }

  fun readInt8(): Byte {
    ensureAvailable(1L)
    val b = readByteFromBuffer(currentPos)
    currentPos += 1L
    return b
  }

  fun readBoolean(): Boolean = readUInt8() != 0

  fun readInt16(): Short {
    ensureAvailable(2L)
    val b0 = readByteFromBuffer(currentPos).toInt() and 0xFF
    val b1 = readByteFromBuffer(currentPos + 1L).toInt() and 0xFF
    currentPos += 2L
    val value =
      when (byteOrder) {
        ByteOrderMode.BIG_ENDIAN -> (b0 shl 8) or b1
        ByteOrderMode.LITTLE_ENDIAN -> b0 or (b1 shl 8)
      }
    return value.toShort()
  }

  fun readUInt16(): Int = readInt16().toInt() and 0xFFFF

  fun readInt32(): Int {
    ensureAvailable(4L)
    val b0 = readByteFromBuffer(currentPos).toInt() and 0xFF
    val b1 = readByteFromBuffer(currentPos + 1L).toInt() and 0xFF
    val b2 = readByteFromBuffer(currentPos + 2L).toInt() and 0xFF
    val b3 = readByteFromBuffer(currentPos + 3L).toInt() and 0xFF
    currentPos += 4L
    return when (byteOrder) {
      ByteOrderMode.BIG_ENDIAN -> (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
      ByteOrderMode.LITTLE_ENDIAN -> b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
  }

  fun readUInt32(): Long = readInt32().toLong() and 0xFFFF_FFFFL

  fun readInt64(): Long {
    ensureAvailable(8L)
    var result = 0L
    when (byteOrder) {
      ByteOrderMode.BIG_ENDIAN -> {
        for (i in 0 until 8) {
          val b = readByteFromBuffer(currentPos + i).toLong() and 0xFFL
          result = (result shl 8) or b
        }
      }
      ByteOrderMode.LITTLE_ENDIAN -> {
        for (i in 0 until 8) {
          val b = readByteFromBuffer(currentPos + i).toLong() and 0xFFL
          result = result or (b shl (i * 8))
        }
      }
    }
    currentPos += 8L
    return result
  }

  fun readUInt64(): ULong = readInt64().toULong()

  fun readFloat32(): Float = Float.fromBits(readInt32())

  fun readFloat64(): Double = Double.fromBits(readInt64())

  fun readBytes(count: Int): ByteArray {
    if (count < 0) {
      throw BoundsExceededException(currentPos, length, "Negative byte count: $count")
    }
    if (count > maxSingleAllocationBytes) {
      throw AllocationLimitExceededException(
        offset = currentPos,
        requestedBytes = count.toLong(),
        maxAllowedBytes = maxSingleAllocationBytes.toLong(),
      )
    }
    ensureAvailable(count.toLong())
    if (count == 0) return ByteArray(0)

    val out = ByteArray(count)
    var written = 0
    while (written < count) {
      if (!isOffsetBuffered(currentPos)) {
        fillBuffer(currentPos)
      }
      val offsetInBuffer = (currentPos - bufferStartOffset).toInt()
      val availableInBuffer = bufferValidLength - offsetInBuffer
      val chunk = min(count - written, availableInBuffer)
      System.arraycopy(buffer, offsetInBuffer, out, written, chunk)
      written += chunk
      currentPos += chunk.toLong()
    }
    return out
  }

  fun readBytesSafe(count: Long): ByteArray {
    if (count < 0L) {
      throw BoundsExceededException(currentPos, length, "Negative byte count: $count")
    }
    if (count > Int.MAX_VALUE || count > maxSingleAllocationBytes.toLong()) {
      throw AllocationLimitExceededException(
        offset = currentPos,
        requestedBytes = count,
        maxAllowedBytes = maxSingleAllocationBytes.toLong(),
      )
    }
    return readBytes(count.toInt())
  }

  fun peekBytes(count: Int): ByteArray {
    val saved = currentPos
    return try {
      readBytes(count)
    } finally {
      currentPos = saved
    }
  }

  fun readFixedAscii(count: Int): String =
    String(readBytes(count), StandardCharsets.US_ASCII)

  fun readNullTerminatedString(maxBytes: Int = 4096): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val limit = min(maxBytes, maxSingleAllocationBytes)
    val startOffset = currentPos
    val bytes = ArrayList<Byte>(min(limit, 64))
    while (true) {
      if (currentPos >= length) {
        throw UnexpectedEofException(currentPos, 1L, 0L)
      }
      val b = readInt8()
      if (b.toInt() == 0) {
        break
      }
      if (bytes.size >= limit) {
        throw AllocationLimitExceededException(
          offset = startOffset,
          requestedBytes = (bytes.size + 1).toLong(),
          maxAllowedBytes = limit.toLong(),
        )
      }
      bytes.add(b)
    }
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
  }

  fun readLengthPrefixedString(alignAfter: Boolean = true): String {
    val startOffset = currentPos
    val byteLength = readInt32()
    if (byteLength < 0) {
      throw BoundsExceededException(
        offset = startOffset,
        totalLength = length,
        detail = "Negative string length $byteLength at offset 0x${startOffset.toString(16)}",
      )
    }
    val data = readBytes(byteLength)
    if (alignAfter) {
      align(4)
    }
    return String(data, StandardCharsets.UTF_8)
  }

  fun sliceFrom(offset: Long, sliceLength: Long): SeekableByteSource =
    BoundedByteSource(source, offset, sliceLength)

  override fun close() {
    source.close()
  }

  private fun ensureAvailable(needed: Long) {
    if (currentPos + needed > length) {
      throw UnexpectedEofException(
        offset = currentPos,
        requestedBytes = needed,
        availableBytes = remaining,
      )
    }
  }

  private fun isOffsetBuffered(offset: Long): Boolean =
    offset >= bufferStartOffset && offset < bufferStartOffset + bufferValidLength

  private fun readByteFromBuffer(offset: Long): Byte {
    if (!isOffsetBuffered(offset)) {
      fillBuffer(offset)
    }
    return buffer[(offset - bufferStartOffset).toInt()]
  }

  private fun fillBuffer(offset: Long) {
    val read = source.readAt(offset, buffer, 0, buffer.size)
    if (read <= 0) {
      throw UnexpectedEofException(offset, 1L, (length - offset).coerceAtLeast(0L))
    }
    bufferStartOffset = offset
    bufferValidLength = read
  }

  companion object {
    const val DEFAULT_BUFFER_SIZE: Int = 64 * 1024
  }
}
