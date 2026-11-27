package com.assetdesk.binary

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BinaryReaderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `reads big endian and little endian primitives accurately`() {
    val bytes = byteArrayOf(
      0x12, 0x34, 0x56, 0x78, // BE: 0x12345678, LE: 0x78563412
      0x01, 0x02,             // BE: 0x0102, LE: 0x0201
      0x7F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01 // 64-bit value
    )
    BinaryReader(ByteArrayByteSource(bytes), ByteOrderMode.BIG_ENDIAN).use { reader ->
      assertEquals(0x12345678, reader.readInt32())
      assertEquals(0x0102, reader.readUInt16())
      assertEquals(0x7F00000000000001L, reader.readInt64())
      assertEquals(0L, reader.remaining)

      reader.seek(0L)
      reader.withByteOrder(ByteOrderMode.LITTLE_ENDIAN) {
        assertEquals(0x78563412, readInt32())
        assertEquals(0x0201, readUInt16())
      }
      assertEquals(ByteOrderMode.BIG_ENDIAN, reader.byteOrder)
    }
  }

  @Test
  fun `align pads position to boundary and rejects invalid alignment`() {
    val bytes = ByteArray(16) { it.toByte() }
    BinaryReader(ByteArrayByteSource(bytes)).use { reader ->
      reader.readUInt8() // pos = 1
      reader.align(4) // pos = 4
      assertEquals(4L, reader.position)
      assertEquals(4, reader.readUInt8()) // pos = 5
      reader.align(8) // pos = 8
      assertEquals(8L, reader.position)
      reader.align(8) // already aligned
      assertEquals(8L, reader.position)

      assertThrows(InvalidAlignmentException::class.java) {
        reader.align(0)
      }
    }
  }

  @Test
  fun `reads null terminated and length prefixed strings with alignment`() {
    val payload = byteArrayOf(
      'U'.code.toByte(), 'n'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 0x00,
      0x03, 0x00, 0x00, 0x00, // LE length = 3
      'F'.code.toByte(), 'O'.code.toByte(), 'O'.code.toByte(),
      0x00 // 1 byte padding to 4-byte boundary (6 + 4 + 3 = 13 -> align(4) = 16 -> wait 6+4+3=13, next mult of 4 is 16 -> 3 padding bytes)
    ) + byteArrayOf(0x00, 0x00, 0x42)

    BinaryReader(ByteArrayByteSource(payload), ByteOrderMode.LITTLE_ENDIAN).use { reader ->
      assertEquals("Unity", reader.readNullTerminatedString())
      assertEquals(6L, reader.position)
      assertEquals("FOO", reader.readLengthPrefixedString(alignAfter = true))
      assertEquals(16L, reader.position)
      assertEquals(0x42, reader.readUInt8())
    }
  }

  @Test
  fun `bounds checks catch EOF, negative sizes, and oversized allocations`() {
    val bytes = byteArrayOf(0x01, 0x02, 0x03)
    BinaryReader(
      source = ByteArrayByteSource(bytes),
      bufferSize = 64,
      maxSingleAllocationBytes = 1024,
    ).use { reader ->
      assertThrows(UnexpectedEofException::class.java) {
        reader.readInt32()
      }
      assertThrows(BoundsExceededException::class.java) {
        reader.seek(10L)
      }
      assertThrows(BoundsExceededException::class.java) {
        reader.seek(-1L)
      }
      assertThrows(AllocationLimitExceededException::class.java) {
        reader.readBytesSafe(50_000_000L)
      }
      assertThrows(UnexpectedEofException::class.java) {
        reader.readNullTerminatedString()
      }
    }
  }

  @Test
  fun `FileChannelByteSource reads across small buffer boundaries without loading full file`() {
    val file: File = tempFolder.newFile("stream_test.bin")
    val expected = ByteArray(2048) { (it * 7 and 0xFF).toByte() }
    file.writeBytes(expected)

    val raf = RandomAccessFile(file, "r")
    BinaryReader(
      source = FileChannelByteSource.fromRandomAccessFile(raf),
      bufferSize = 128,
    ).use { reader ->
      assertEquals(2048L, reader.length)
      val chunk1 = reader.readBytes(300)
      assertArrayEquals(expected.copyOfRange(0, 300), chunk1)
      reader.seek(1500L)
      val chunk2 = reader.readBytes(548)
      assertArrayEquals(expected.copyOfRange(1500, 2048), chunk2)
    }
  }
}
