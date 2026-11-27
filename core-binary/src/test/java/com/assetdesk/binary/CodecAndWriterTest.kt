package com.assetdesk.binary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CodecAndWriterTest {

  @Test
  fun `BinaryWriter round-trips with BinaryReader across BE and LE and alignment`() {
    val writer = BinaryWriter(ByteOrderMode.BIG_ENDIAN)
    writer.writeNullTerminatedString("UnityFS")
    writer.writeInt32(6)
    writer.withByteOrder(ByteOrderMode.LITTLE_ENDIAN) {
      writeLengthPrefixedString("Texture2D", alignAfter = true)
      writeInt64(0x1122334455667788L)
      writeFloat32(3.5f)
      align(8)
      writeUInt8(0xAA)
    }

    val bytes = writer.toByteArray()
    BinaryReader(ByteArrayByteSource(bytes), ByteOrderMode.BIG_ENDIAN).use { reader ->
      assertEquals("UnityFS", reader.readNullTerminatedString())
      assertEquals(6, reader.readInt32())
      reader.withByteOrder(ByteOrderMode.LITTLE_ENDIAN) {
        assertEquals("Texture2D", readLengthPrefixedString(alignAfter = true))
        assertEquals(0x1122334455667788L, readInt64())
        assertEquals(3.5f, readFloat32(), 0.0001f)
        align(8)
        assertEquals(0xAA, readUInt8())
      }
    }
  }

  @Test
  fun `Lz4Codec round-trips repetitive and mixed binary data and rejects invalid lengths`() {
    val payload = (
      "AssetDesk_Unity_Block_Header_".repeat(40) +
        "UniqueSegment_0123456789ABCDEF" +
        "MeshVertexBuffer".repeat(25)
      ).toByteArray()

    val compressed = Lz4Codec.compressBlock(payload)
    val decompressed = Lz4Codec.decompressBlock(compressed, payload.size)
    assertArrayEquals(payload, decompressed)

    assertThrows(IllegalArgumentException::class.java) {
      Lz4Codec.decompressBlock(compressed, payload.size + 10)
    }
    assertThrows(IllegalArgumentException::class.java) {
      Lz4Codec.decompressBlock(byteArrayOf(0x10, 0x01), 10)
    }
  }

  @Test
  fun `LzmaCodec round-trips UnityFS block without 13-byte header mismatch`() {
    val payload = ("UnityFS_TypeTree_Metadata_".repeat(60)).toByteArray()
    val compressed = LzmaCodec.compressUnityFsBlock(payload)
    val decompressed = LzmaCodec.decompressUnityFsBlock(compressed, payload.size)
    assertArrayEquals(payload, decompressed)
  }
}
