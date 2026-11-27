package com.assetdesk.plugins

import com.assetdesk.binary.BinaryReader
import com.assetdesk.binary.BinaryWriter
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.UnityObjectInfo

object AudioClipPlugin : AssetPlugin {
  override val id: String = "audio_clip"
  override val displayName: String = "AudioClip"
  override val supportedClassIds: Set<Int> = setOf(83)
  override val targetSummary: String = "AudioClip (ClassID 83)"
  override val description: String =
    "Inspects AudioClip channels, sample rate, compression format, and exports recognized embedded streams (WAV/Ogg/FSB5/PCM)."

  override fun inspect(
    obj: UnityObjectInfo,
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    formatVersion: Int,
    decodedData: DecodedAssetData?,
  ): PluginInspectionResult {
    return try {
      val root = decodedData?.rootNode
      val name = root.findString("m_Name") ?: runCatching {
        BinaryReader(ByteArrayByteSource(rawBytes), byteOrder).readLengthPrefixedString(true)
      }.getOrDefault(obj.assetName)

      val channels = root.findInt("m_Channels")
      val frequency = root.findInt("m_Frequency")
      val bitsPerSample = root.findInt("m_BitsPerSample")
      val duration = root.findFloat("m_Length")
      val compCode = root.findInt("m_CompressionFormat")
      val compName = compressionFormatName(compCode)

      val resNode = root.findField("m_Resource")
      val resPath = resNode?.findString("m_Source")?.ifBlank { null }
      val resOffset = resNode?.findLong("m_Offset")
      val resSize = resNode?.findLong("m_Size")

      val audioNode = root.findField("m_AudioData")
      var embeddedOffset: Int? = null
      var embeddedSize = 0
      var embeddedBytes = ByteArray(0)

      if (audioNode != null) {
        val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
        reader.seek(audioNode.byteOffset)
        val count = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
        embeddedOffset = reader.position.toInt()
        embeddedSize = count
        embeddedBytes = reader.readBytes(count)
      }

      val detectedContainer = detectAudioSignature(embeddedBytes, compCode)
      val canExport = embeddedBytes.isNotEmpty() && detectedContainer != "Unknown Binary"
      val ext = when (detectedContainer) {
        "WAV (RIFF)", "PCM (Wrapped WAV)" -> "wav"
        "Ogg Vorbis" -> "ogg"
        "FSB5 Bank" -> "fsb"
        "MP3 (ID3)" -> "mp3"
        else -> "bin"
      }

      val note = when {
        !resPath.isNullOrBlank() ->
          "Audio payload streams from '$resPath' (offset ${resOffset ?: 0}, size ${resSize ?: 0} B). Open companion .resS/.resource to extract raw stream."
        embeddedBytes.isEmpty() ->
          "No embedded m_AudioData found (requires TypeTree or companion .resS reference)."
        detectedContainer == "FSB5 Bank" ->
          "Embedded FMOD Sound Bank (FSB5) detected; raw .fsb container can be exported, direct playback is not claimed."
        else ->
          "Recognized $detectedContainer audio payload ready for export."
      }

      PluginInspectionResult.AudioClipPreview(
        assetName = name.ifBlank { obj.assetName },
        channels = channels,
        frequencyHz = frequency,
        bitsPerSample = bitsPerSample,
        durationSec = duration,
        compressionFormatName = compName,
        externalResourcePath = resPath,
        externalOffset = resOffset,
        externalSize = resSize,
        embeddedDataOffset = embeddedOffset,
        embeddedDataSize = embeddedSize,
        detectedContainer = detectedContainer,
        canExportAudio = canExport,
        exportExtension = ext,
        note = note,
      )
    } catch (t: Throwable) {
      PluginInspectionResult.PluginError(displayName, "AudioClip inspection failed: ${t.message}")
    }
  }

  fun exportRecognizedAudio(
    rawBytes: ByteArray,
    byteOrder: ByteOrderMode,
    decodedData: DecodedAssetData?,
  ): Result<Pair<String, ByteArray>> {
    val root = decodedData?.rootNode
    val audioNode = root.findField("m_AudioData")
      ?: return Result.failure(IllegalStateException("AudioClip has no embedded m_AudioData stream."))
    val reader = BinaryReader(ByteArrayByteSource(rawBytes), byteOrder)
    reader.seek(audioNode.byteOffset)
    val count = reader.readInt32().coerceAtLeast(0).coerceAtMost(reader.remaining.toInt())
    val payload = reader.readBytes(count)
    val compCode = root.findInt("m_CompressionFormat")
    val sig = detectAudioSignature(payload, compCode)

    return when (sig) {
      "WAV (RIFF)" -> Result.success("wav" to payload)
      "Ogg Vorbis" -> Result.success("ogg" to payload)
      "FSB5 Bank" -> Result.success("fsb" to payload)
      "MP3 (ID3)" -> Result.success("mp3" to payload)
      "PCM (Wrapped WAV)" -> {
        val ch = (root.findInt("m_Channels") ?: 2).coerceIn(1, 8)
        val freq = (root.findInt("m_Frequency") ?: 44100).coerceIn(8000, 192000)
        val bits = (root.findInt("m_BitsPerSample") ?: 16).coerceIn(8, 32)
        Result.success("wav" to wrapPcmAsWav(payload, ch, freq, bits))
      }
      else -> Result.failure(IllegalStateException("Unrecognized audio encoding ($sig); use Raw Export instead."))
    }
  }

  private fun wrapPcmAsWav(pcm: ByteArray, channels: Int, sampleRate: Int, bitsPerSample: Int): ByteArray {
    val byteRate = sampleRate * channels * (bitsPerSample / 8)
    val blockAlign = channels * (bitsPerSample / 8)
    val writer = BinaryWriter(ByteOrderMode.LITTLE_ENDIAN, pcm.size + 44)
    writer.writeBytes("RIFF".toByteArray(Charsets.US_ASCII))
    writer.writeInt32(36 + pcm.size)
    writer.writeBytes("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    writer.writeInt32(16)
    writer.writeInt16(1) // PCM
    writer.writeInt16(channels.toShort())
    writer.writeInt32(sampleRate)
    writer.writeInt32(byteRate)
    writer.writeInt16(blockAlign.toShort())
    writer.writeInt16(bitsPerSample.toShort())
    writer.writeBytes("data".toByteArray(Charsets.US_ASCII))
    writer.writeInt32(pcm.size)
    writer.writeBytes(pcm)
    return writer.toByteArray()
  }

  private fun detectAudioSignature(bytes: ByteArray, compressionFormat: Int?): String {
    if (bytes.size >= 4) {
      val s4 = String(bytes, 0, 4, Charsets.US_ASCII)
      if (s4 == "RIFF") return "WAV (RIFF)"
      if (s4 == "OggS") return "Ogg Vorbis"
      if (s4 == "FSB5") return "FSB5 Bank"
      if (s4.startsWith("ID3")) return "MP3 (ID3)"
    }
    if (bytes.isNotEmpty() && compressionFormat == 0) {
      return "PCM (Wrapped WAV)"
    }
    return if (bytes.isEmpty()) "External / Empty" else "Unknown Binary"
  }

  private fun compressionFormatName(code: Int?): String =
    when (code) {
      0 -> "PCM"
      1 -> "Vorbis"
      2 -> "ADPCM"
      3 -> "MP3"
      4 -> "VAG"
      5 -> "HEVAG"
      6 -> "XMA"
      7 -> "AAC"
      8 -> "GCADPCM"
      9 -> "ATRAC9"
      null -> "Unknown (TypeTree required)"
      else -> "Format_$code"
    }
}
