package com.assetdesk.binary

import java.nio.ByteOrder

enum class ByteOrderMode {
  BIG_ENDIAN,
  LITTLE_ENDIAN;

  fun toJavaByteOrder(): ByteOrder =
    when (this) {
      BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
      LITTLE_ENDIAN -> ByteOrder.LITTLE_ENDIAN
    }

  companion object {
    fun fromUnityFlag(flag: Int): ByteOrderMode? =
      when (flag) {
        0 -> LITTLE_ENDIAN
        1 -> BIG_ENDIAN
        else -> null
      }
  }
}
