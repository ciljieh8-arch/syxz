package com.assetdesk.binary

sealed class BinaryReadException(
  message: String,
  val offset: Long,
  cause: Throwable? = null,
) : Exception(message, cause)

class UnexpectedEofException(
  offset: Long,
  val requestedBytes: Long,
  val availableBytes: Long,
) : BinaryReadException(
  message = "Unexpected EOF at offset 0x${offset.toString(16)}: requested $requestedBytes bytes, only $availableBytes available.",
  offset = offset,
)

class BoundsExceededException(
  offset: Long,
  val totalLength: Long,
  detail: String = "Target offset $offset is outside valid range [0, $totalLength].",
) : BinaryReadException(message = detail, offset = offset)

class InvalidAlignmentException(
  offset: Long,
  val alignment: Int,
) : BinaryReadException(
  message = "Invalid alignment $alignment at offset 0x${offset.toString(16)}.",
  offset = offset,
)

class AllocationLimitExceededException(
  offset: Long,
  val requestedBytes: Long,
  val maxAllowedBytes: Long,
) : BinaryReadException(
  message = "Requested allocation of $requestedBytes bytes at offset 0x${offset.toString(16)} exceeds safety limit of $maxAllowedBytes bytes.",
  offset = offset,
)
