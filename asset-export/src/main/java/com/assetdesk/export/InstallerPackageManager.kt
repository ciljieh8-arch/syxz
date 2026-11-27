package com.assetdesk.export

import com.assetdesk.binary.ByteOrderMode
import com.assetdesk.model.ObjectStatus
import com.assetdesk.unity.ParsedSerializedFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PackageChangeEntry(
  val pathId: Long,
  val classId: Int,
  val className: String,
  val assetName: String,
  val byteSize: Int,
  val sha256: String,
  val blobBytes: ByteArray,
)

data class InstallerPackagePreview(
  val packageName: String,
  val baseFileName: String,
  val baseFormatVersion: Int,
  val baseEndianness: ByteOrderMode,
  val baseUnityVersion: String,
  val createdAtEpochMs: Long,
  val changes: List<PackageChangeEntry>,
)

data class PackageValidationResult(
  val isCompatible: Boolean,
  val errors: List<String>,
  val warnings: List<String>,
  val matchedObjectCount: Int,
)

/**
 * Android-native change package (.adpkg) creator and applier inspired by UABE Installer Packages.
 * Stored as a safe ZIP archive containing manifest.json + raw binary object payloads (no executables).
 */
object InstallerPackageManager {

  private const val PACKAGE_FORMAT = "assetdesk-change-package-v1"

  fun createPackageBytes(
    packageName: String,
    sourceFile: ParsedSerializedFile,
  ): Result<ByteArray> {
    val modifiedObjects = sourceFile.objects.filter { it.isModified && it.modifiedRawBytes != null }
    if (modifiedObjects.isEmpty()) {
      return Result.failure(IllegalStateException("No modified assets in '${sourceFile.fileName}' to package."))
    }

    return try {
      val baos = ByteArrayOutputStream()
      ZipOutputStream(baos).use { zos ->
        val changeEntriesJson = StringBuilder()
        changeEntriesJson.append("[\n")
        modifiedObjects.forEachIndexed { index, obj ->
          val payload = obj.modifiedRawBytes!!
          val blobEntryName = "blobs/${obj.pathId}.bin"
          val sha = sha256Hex(payload)

          if (index > 0) changeEntriesJson.append(",\n")
          changeEntriesJson.append("    {\n")
          changeEntriesJson.append("      \"pathId\": ${obj.pathId},\n")
          changeEntriesJson.append("      \"classId\": ${obj.classId},\n")
          changeEntriesJson.append("      \"className\": \"${escapeJson(obj.className)}\",\n")
          changeEntriesJson.append("      \"assetName\": \"${escapeJson(obj.assetName)}\",\n")
          changeEntriesJson.append("      \"byteSize\": ${payload.size},\n")
          changeEntriesJson.append("      \"sha256\": \"${escapeJson(sha)}\",\n")
          changeEntriesJson.append("      \"blobEntry\": \"${escapeJson(blobEntryName)}\"\n")
          changeEntriesJson.append("    }")

          zos.putNextEntry(ZipEntry(blobEntryName))
          zos.write(payload)
          zos.closeEntry()
        }
        changeEntriesJson.append("\n  ]")

        val manifestJson = buildString {
          append("{\n")
          append("  \"format\": \"${escapeJson(PACKAGE_FORMAT)}\",\n")
          append("  \"packageName\": \"${escapeJson(packageName)}\",\n")
          append("  \"baseFileName\": \"${escapeJson(sourceFile.fileName)}\",\n")
          append("  \"baseFormatVersion\": ${sourceFile.header.formatVersion},\n")
          append("  \"baseEndianness\": \"${escapeJson(sourceFile.header.endianness.name)}\",\n")
          append("  \"baseUnityVersion\": \"${escapeJson(sourceFile.unityVersion)}\",\n")
          append("  \"createdAtEpochMs\": ${System.currentTimeMillis()},\n")
          append("  \"changes\": $changeEntriesJson\n")
          append("}\n")
        }

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write(manifestJson.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }
      Result.success(baos.toByteArray())
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  fun inspectPackage(packageBytes: ByteArray): Result<InstallerPackagePreview> {
    return try {
      var manifestJson: String? = null
      val blobs = mutableMapOf<String, ByteArray>()

      ZipInputStream(ByteArrayInputStream(packageBytes)).use { zis ->
        while (true) {
          val entry = zis.nextEntry ?: break
          if (!entry.isDirectory) {
            val content = zis.readBytes()
            if (entry.name == "manifest.json") {
              manifestJson = String(content, Charsets.UTF_8)
            } else {
              blobs[entry.name] = content
            }
          }
          zis.closeEntry()
        }
      }

      val rawManifest = manifestJson
        ?: return Result.failure(IllegalArgumentException("Invalid .adpkg file: missing manifest.json."))
      @Suppress("UNCHECKED_CAST")
      val root = MiniJsonParser(rawManifest).parseValue() as? Map<String, Any?>
        ?: return Result.failure(IllegalArgumentException("Invalid manifest.json root object."))

      val fmt = root["format"] as? String ?: ""
      if (fmt != PACKAGE_FORMAT) {
        return Result.failure(IllegalArgumentException("Unsupported package format: $fmt"))
      }

      val endianName = root["baseEndianness"] as? String ?: "LITTLE_ENDIAN"
      val endian = runCatching { ByteOrderMode.valueOf(endianName) }
        .getOrDefault(ByteOrderMode.LITTLE_ENDIAN)

      @Suppress("UNCHECKED_CAST")
      val rawChanges = root["changes"] as? List<Map<String, Any?>>
        ?: return Result.failure(IllegalArgumentException("manifest.json missing changes array."))

      val changes = buildList {
        for (item in rawChanges) {
          val blobName = item["blobEntry"] as? String
            ?: return Result.failure(IllegalArgumentException("Change entry missing blobEntry."))
          val blobBytes = blobs[blobName]
            ?: return Result.failure(IllegalArgumentException("Missing payload blob '$blobName' in package."))
          val expectedSize = (item["byteSize"] as? Number)?.toInt()
            ?: return Result.failure(IllegalArgumentException("Change entry missing byteSize."))
          val expectedSha = item["sha256"] as? String
            ?: return Result.failure(IllegalArgumentException("Change entry missing sha256."))

          if (blobBytes.size != expectedSize) {
            return Result.failure(IllegalArgumentException("Payload size mismatch for $blobName."))
          }
          if (sha256Hex(blobBytes) != expectedSha) {
            return Result.failure(IllegalArgumentException("SHA-256 checksum mismatch for $blobName."))
          }

          add(
            PackageChangeEntry(
              pathId = (item["pathId"] as Number).toLong(),
              classId = (item["classId"] as Number).toInt(),
              className = item["className"] as? String ?: "Object",
              assetName = item["assetName"] as? String ?: "<Unnamed>",
              byteSize = expectedSize,
              sha256 = expectedSha,
              blobBytes = blobBytes,
            ),
          )
        }
      }

      Result.success(
        InstallerPackagePreview(
          packageName = root["packageName"] as? String ?: "AssetDesk Change Package",
          baseFileName = root["baseFileName"] as? String ?: "Unknown",
          baseFormatVersion = (root["baseFormatVersion"] as? Number)?.toInt() ?: 0,
          baseEndianness = endian,
          baseUnityVersion = root["baseUnityVersion"] as? String ?: "",
          createdAtEpochMs = (root["createdAtEpochMs"] as? Number)?.toLong() ?: 0L,
          changes = changes,
        ),
      )
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  fun validateAgainstTarget(
    pkg: InstallerPackagePreview,
    target: ParsedSerializedFile,
  ): PackageValidationResult {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    if (target.header.isReadOnly) {
      errors.add("Target file '${target.fileName}' is marked read-only (${target.header.readOnlyReason ?: "unsafe endian/format"}). Cannot apply changes.")
    }
    if (pkg.baseFormatVersion != target.header.formatVersion) {
      errors.add("Format version mismatch: package targets SerializedFile v${pkg.baseFormatVersion}, loaded file is v${target.header.formatVersion}.")
    }
    if (pkg.baseEndianness != target.header.endianness) {
      errors.add("Byte order mismatch: package targets ${pkg.baseEndianness}, loaded file is ${target.header.endianness}.")
    }
    if (pkg.baseUnityVersion.isNotBlank() && target.unityVersion.isNotBlank() && pkg.baseUnityVersion != target.unityVersion) {
      warnings.add("Unity version differs: package='${pkg.baseUnityVersion}', target='${target.unityVersion}'.")
    }

    val byPathId = target.objects.associateBy { it.pathId }
    var matched = 0
    for (change in pkg.changes) {
      val existing = byPathId[change.pathId]
      if (existing == null) {
        errors.add("Target object PathID=${change.pathId} (${change.assetName}) not found in '${target.fileName}'.")
      } else if (existing.classId != change.classId) {
        errors.add("ClassID mismatch on PathID=${change.pathId}: package has ${change.classId}, target has ${existing.classId}.")
      } else {
        matched++
      }
    }

    return PackageValidationResult(
      isCompatible = errors.isEmpty() && matched > 0,
      errors = errors,
      warnings = warnings,
      matchedObjectCount = matched,
    )
  }

  fun applyPackage(
    pkg: InstallerPackagePreview,
    target: ParsedSerializedFile,
  ): Result<ParsedSerializedFile> {
    val validation = validateAgainstTarget(pkg, target)
    if (!validation.isCompatible) {
      return Result.failure(IllegalStateException(validation.errors.joinToString("\n")))
    }

    val changeMap = pkg.changes.associateBy { it.pathId }
    val updatedObjects = target.objects.map { obj ->
      val patch = changeMap[obj.pathId]
      if (patch != null) {
        obj.copy(
          modifiedRawBytes = patch.blobBytes,
          status = ObjectStatus.MODIFIED,
        )
      } else obj
    }
    return Result.success(target.copy(objects = updatedObjects))
  }

  private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
  }

  private fun escapeJson(value: String): String = buildString(value.length + 4) {
    for (ch in value) {
      when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> {
          if (ch.code < 0x20) {
            append(String.format(Locale.ROOT, "\\u%04x", ch.code))
          } else {
            append(ch)
          }
        }
      }
    }
  }

  private class MiniJsonParser(private val text: String) {
    private var idx = 0

    fun parseValue(): Any? {
      skipWhitespace()
      if (idx >= text.length) throw IllegalArgumentException("Unexpected end of JSON")
      return when (text[idx]) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> parseString()
        't', 'f' -> parseBoolean()
        'n' -> parseNull()
        '-', in '0'..'9' -> parseNumber()
        else -> throw IllegalArgumentException("Unexpected JSON character '${text[idx]}' at $idx")
      }
    }

    private fun parseObject(): Map<String, Any?> {
      expect('{')
      val map = LinkedHashMap<String, Any?>()
      skipWhitespace()
      if (peek() == '}') {
        idx++
        return map
      }
      while (true) {
        skipWhitespace()
        val key = parseString()
        skipWhitespace()
        expect(':')
        map[key] = parseValue()
        skipWhitespace()
        when (peek()) {
          ',' -> idx++
          '}' -> {
            idx++
            return map
          }
          else -> throw IllegalArgumentException("Expected ',' or '}' in JSON object at $idx")
        }
      }
    }

    private fun parseArray(): List<Any?> {
      expect('[')
      val list = ArrayList<Any?>()
      skipWhitespace()
      if (peek() == ']') {
        idx++
        return list
      }
      while (true) {
        list.add(parseValue())
        skipWhitespace()
        when (peek()) {
          ',' -> idx++
          ']' -> {
            idx++
            return list
          }
          else -> throw IllegalArgumentException("Expected ',' or ']' in JSON array at $idx")
        }
      }
    }

    private fun parseString(): String {
      expect('"')
      val sb = StringBuilder()
      while (idx < text.length) {
        val c = text[idx++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            if (idx >= text.length) throw IllegalArgumentException("Unterminated escape")
            val esc = text[idx++]
            when (esc) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                val hex = text.substring(idx, idx + 4)
                sb.append(hex.toInt(16).toChar())
                idx += 4
              }
              else -> sb.append(esc)
            }
          }
          else -> sb.append(c)
        }
      }
      throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun parseNumber(): Number {
      val start = idx
      if (text[idx] == '-') idx++
      while (idx < text.length && text[idx] in '0'..'9') idx++
      var isFloat = false
      if (idx < text.length && text[idx] == '.') {
        isFloat = true
        idx++
        while (idx < text.length && text[idx] in '0'..'9') idx++
      }
      if (idx < text.length && (text[idx] == 'e' || text[idx] == 'E')) {
        isFloat = true
        idx++
        if (idx < text.length && (text[idx] == '+' || text[idx] == '-')) idx++
        while (idx < text.length && text[idx] in '0'..'9') idx++
      }
      val numStr = text.substring(start, idx)
      return if (isFloat) numStr.toDouble() else numStr.toLong()
    }

    private fun parseBoolean(): Boolean {
      return if (text.startsWith("true", idx)) {
        idx += 4
        true
      } else if (text.startsWith("false", idx)) {
        idx += 5
        false
      } else {
        throw IllegalArgumentException("Invalid JSON boolean at $idx")
      }
    }

    private fun parseNull(): Any? {
      if (text.startsWith("null", idx)) {
        idx += 4
        return null
      }
      throw IllegalArgumentException("Invalid JSON null at $idx")
    }

    private fun skipWhitespace() {
      while (idx < text.length && text[idx].isWhitespace()) idx++
    }

    private fun peek(): Char = if (idx < text.length) text[idx] else '\u0000'

    private fun expect(ch: Char) {
      if (idx >= text.length || text[idx] != ch) {
        throw IllegalArgumentException("Expected '$ch' at $idx")
      }
      idx++
    }
  }
}
