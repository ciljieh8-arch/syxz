package com.assetdesk.binary

import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * Random-access, streamable byte source that avoids loading large files entirely into RAM.
 */
interface SeekableByteSource : Closeable {
  val length: Long

  fun readAt(
    offset: Long,
    destination: ByteArray,
    destOffset: Int = 0,
    count: Int,
  ): Int

  fun readFullyAt(
    offset: Long,
    count: Int,
    maxAllowedBytes: Int = DEFAULT_MAX_ALLOCATION_BYTES,
  ): ByteArray {
    if (count < 0) {
      throw BoundsExceededException(offset, length, "Negative read length: $count")
    }
    if (count > maxAllowedBytes) {
      throw AllocationLimitExceededException(offset, count.toLong(), maxAllowedBytes.toLong())
    }
    if (offset < 0 || offset + count > length) {
      throw UnexpectedEofException(
        offset = offset,
        requestedBytes = count.toLong(),
        availableBytes = (length - offset).coerceAtLeast(0L),
      )
    }
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
      val read = readAt(offset + filled, out, filled, count - filled)
      if (read <= 0) {
        throw UnexpectedEofException(
          offset = offset + filled,
          requestedBytes = (count - filled).toLong(),
          availableBytes = 0L,
        )
      }
      filled += read
    }
    return out
  }

  companion object {
    const val DEFAULT_MAX_ALLOCATION_BYTES: Int = 16 * 1024 * 1024
  }
}

class ByteArrayByteSource(
  private val bytes: ByteArray,
) : SeekableByteSource {
  override val length: Long = bytes.size.toLong()

  override fun readAt(
    offset: Long,
    destination: ByteArray,
    destOffset: Int,
    count: Int,
  ): Int {
    if (count == 0) return 0
    if (offset < 0 || offset >= length) return -1
    val available = (length - offset).toInt()
    val toCopy = min(count, available)
    System.arraycopy(bytes, offset.toInt(), destination, destOffset, toCopy)
    return toCopy
  }

  override fun close() = Unit
}

class FileChannelByteSource(
  private val channel: FileChannel,
  private val underlyingCloseable: Closeable? = null,
) : SeekableByteSource {
  override val length: Long = channel.size()

  override fun readAt(
    offset: Long,
    destination: ByteArray,
    destOffset: Int,
    count: Int,
  ): Int {
    if (count == 0) return 0
    if (offset < 0 || offset >= length) return -1
    val buffer = ByteBuffer.wrap(destination, destOffset, count)
    var totalRead = 0
    var currentPos = offset
    while (totalRead < count && currentPos < length) {
      val read = channel.read(buffer, currentPos)
      if (read <= 0) break
      totalRead += read
      currentPos += read
    }
    return if (totalRead == 0) -1 else totalRead
  }

  override fun close() {
    var firstError: Throwable? = null
    try {
      channel.close()
    } catch (t: Throwable) {
      firstError = t
    }
    try {
      underlyingCloseable?.close()
    } catch (t: Throwable) {
      if (firstError == null) firstError = t
    }
    if (firstError != null) throw firstError
  }

  companion object {
    fun fromRandomAccessFile(raf: RandomAccessFile): FileChannelByteSource =
      FileChannelByteSource(raf.channel, raf)
  }
}

class BoundedByteSource(
  private val parent: SeekableByteSource,
  private val startOffset: Long,
  override val length: Long,
) : SeekableByteSource {
  init {
    require(startOffset >= 0) { "startOffset must be >= 0" }
    require(length >= 0) { "length must be >= 0" }
    require(startOffset + length <= parent.length) {
      "Bounded slice [$startOffset, ${startOffset + length}) exceeds parent length ${parent.length}"
    }
  }

  override fun readAt(
    offset: Long,
    destination: ByteArray,
    destOffset: Int,
    count: Int,
  ): Int {
    if (count == 0) return 0
    if (offset < 0 || offset >= length) return -1
    val clamped = min(count.toLong(), length - offset).toInt()
    return parent.readAt(startOffset + offset, destination, destOffset, clamped)
  }

  override fun close() = Unit
}
