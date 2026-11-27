package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.assetdesk.binary.ByteArrayByteSource
import com.assetdesk.binary.FileChannelByteSource
import com.assetdesk.binary.SeekableByteSource
import com.assetdesk.export.AssetDumpCodec
import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.TypeTreeSource
import com.assetdesk.model.UnityObjectInfo
import com.assetdesk.plugins.AssetPluginRegistry
import com.assetdesk.unity.AssetBundleParser
import com.assetdesk.unity.BundleDirectoryEntry
import com.assetdesk.unity.HeaderParseResult
import com.assetdesk.unity.ParsedAssetBundle
import com.assetdesk.unity.ParsedSerializedFile
import com.assetdesk.unity.SerializedFileParser
import com.assetdesk.unity.TypeTreeValueParser
import com.assetdesk.unity.UnityFileKind
import com.assetdesk.unity.UnityHeaderParser
import com.assetdesk.writer.AssetBundleWriter
import com.assetdesk.writer.BundleCompressionMode
import com.assetdesk.writer.SerializedFileWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class SafFileRepository(
  private val context: Context,
) {

  suspend fun openAndParseUri(
    uri: Uri,
    memoryLimitMb: Int,
  ): Result<com.example.model.OpenedUnityFile> = withContext(Dispatchers.IO) {
    try {
      ensureActive()
      tryTakePersistablePermission(uri)
      val metadata = queryDocumentMetadata(uri)
      ensureActive()

      withSeekableSource(uri, metadata.displayName, memoryLimitMb) { source ->
        val headerResult = UnityHeaderParser.parse(source, metadata.displayName)
        ensureActive()

        var parsedSf: ParsedSerializedFile? = null
        var parsedBundle: ParsedAssetBundle? = null
        var activeBundleEntry: String? = null
        var parseWarningError: Throwable? = null

        when (headerResult.kind) {
          UnityFileKind.SERIALIZED_FILE -> {
            val sfRes = SerializedFileParser.parse(source, metadata.displayName)
            if (sfRes.isSuccess) {
              parsedSf = sfRes.getOrThrow()
            } else {
              parseWarningError = sfRes.exceptionOrNull()
            }
          }
          UnityFileKind.ASSET_BUNDLE_FS,
          UnityFileKind.ASSET_BUNDLE_RAW,
          UnityFileKind.ASSET_BUNDLE_WEB -> {
            val bRes = AssetBundleParser.parse(source, metadata.displayName)
            if (bRes.isSuccess) {
              parsedBundle = bRes.getOrThrow()
              // Automatically open the first serialized entry if present for immediate exploration
              val firstSfEntry = parsedBundle.directoryEntries.firstOrNull { entry ->
                !entry.path.endsWith(".resS", ignoreCase = true) &&
                  !entry.path.endsWith(".resource", ignoreCase = true) &&
                  entry.size >= 20L
              }
              if (firstSfEntry != null) {
                val extracted = AssetBundleParser.extractEntryBytes(source, parsedBundle, firstSfEntry)
                if (extracted.isSuccess) {
                  val innerSf = SerializedFileParser.parse(
                    ByteArrayByteSource(extracted.getOrThrow()),
                    firstSfEntry.path,
                  )
                  if (innerSf.isSuccess) {
                    parsedSf = innerSf.getOrThrow()
                    activeBundleEntry = firstSfEntry.path
                  }
                }
              }
            } else {
              parseWarningError = bRes.exceptionOrNull()
            }
          }
          else -> Unit
        }

        if (parseWarningError != null) {
          throw parseWarningError
        }

        com.example.model.OpenedUnityFile(
          id = "${uri}_${System.currentTimeMillis()}",
          uriString = uri.toString(),
          fileName = metadata.displayName,
          fileSizeBytes = source.length,
          mimeType = metadata.mimeType,
          parseResult = headerResult,
          parsedSerializedFile = parsedSf,
          parsedAssetBundle = parsedBundle,
          activeBundleEntryPath = activeBundleEntry,
          openedAtEpochMs = System.currentTimeMillis(),
        )
      }.let { Result.success(it) }
    } catch (oom: OutOfMemoryError) {
      Result.failure(
        IllegalStateException(
          "Insufficient memory while parsing file (limit: ${memoryLimitMb} MB).",
          oom,
        ),
      )
    } catch (t: Throwable) {
      if (t is kotlinx.coroutines.CancellationException) throw t
      Result.failure(t)
    }
  }

  suspend fun openBundleEntryAsSerializedFile(
    uri: Uri,
    memoryLimitMb: Int,
    bundle: ParsedAssetBundle,
    entry: BundleDirectoryEntry,
    modifiedBundleEntries: Map<String, ByteArray>,
  ): Result<ParsedSerializedFile> = withContext(Dispatchers.IO) {
    try {
      val entryBytes = modifiedBundleEntries[entry.path] ?: withSeekableSource(uri, bundle.fileName, memoryLimitMb) { source ->
        AssetBundleParser.extractEntryBytes(source, bundle, entry).getOrThrow()
      }
      SerializedFileParser.parse(ByteArrayByteSource(entryBytes), entry.path)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  suspend fun extractBundleEntryRawBytes(
    uri: Uri,
    memoryLimitMb: Int,
    bundle: ParsedAssetBundle,
    entry: BundleDirectoryEntry,
    modifiedBundleEntries: Map<String, ByteArray>,
  ): Result<ByteArray> = withContext(Dispatchers.IO) {
    try {
      val existing = modifiedBundleEntries[entry.path]
      if (existing != null) return@withContext Result.success(existing)
      val bytes = withSeekableSource(uri, bundle.fileName, memoryLimitMb) { source ->
        AssetBundleParser.extractEntryBytes(source, bundle, entry).getOrThrow()
      }
      Result.success(bytes)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  suspend fun loadObjectDetail(
    uri: Uri,
    memoryLimitMb: Int,
    parsedSf: ParsedSerializedFile,
    obj: UnityObjectInfo,
    bundle: ParsedAssetBundle?,
    bundleEntryPath: String?,
    modifiedBundleEntries: Map<String, ByteArray>,
  ): Result<com.example.model.SelectedObjectDetailState> = withContext(Dispatchers.IO) {
    try {
      val maxBytes = memoryLimitMb * 1024 * 1024
      val rawBytes = when {
        obj.modifiedRawBytes != null -> obj.modifiedRawBytes!!
        bundle != null && bundleEntryPath != null -> {
          val entryBytes = modifiedBundleEntries[bundleEntryPath]
            ?: withSeekableSource(uri, bundle.fileName, memoryLimitMb) { source ->
              val entry = bundle.directoryEntries.first { it.path == bundleEntryPath }
              AssetBundleParser.extractEntryBytes(source, bundle, entry).getOrThrow()
            }
          SerializedFileParser.readObjectRawBytes(ByteArrayByteSource(entryBytes), obj, maxBytes)
        }
        else -> {
          withSeekableSource(uri, parsedSf.fileName, memoryLimitMb) { source ->
            SerializedFileParser.readObjectRawBytes(source, obj, maxBytes)
          }
        }
      }

      val (typeNode, typeSource) = parsedSf.findTypeTreeForObject(obj)
      val decoded: DecodedAssetData = TypeTreeValueParser.decode(
        pathId = obj.pathId,
        className = obj.className,
        rawBytes = rawBytes,
        byteOrder = parsedSf.header.endianness,
        rootTypeNode = typeNode,
        typeTreeSource = typeSource,
      )

      val pluginResult = AssetPluginRegistry.findPluginFor(obj)?.inspect(
        obj = obj,
        rawBytes = rawBytes,
        byteOrder = parsedSf.header.endianness,
        formatVersion = parsedSf.header.formatVersion,
        decodedData = decoded,
      )

      val dumpText = if (typeNode != null && typeSource != TypeTreeSource.UNAVAILABLE) {
        AssetDumpCodec.exportDump(typeNode, rawBytes, parsedSf.header.endianness).getOrNull()
      } else null

      Result.success(
        com.example.model.SelectedObjectDetailState(
          objectInfo = obj,
          rawBytes = rawBytes,
          decodedData = decoded,
          typeTreeNode = typeNode,
          typeTreeSource = typeSource,
          pluginResult = pluginResult,
          dumpTextPreview = dumpText,
        ),
      )
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  suspend fun saveModifiedSerializedFileToUri(
    sourceUri: Uri,
    targetUri: Uri,
    memoryLimitMb: Int,
    parsedSf: ParsedSerializedFile,
    bundle: ParsedAssetBundle?,
    bundleEntryPath: String?,
    modifiedBundleEntries: Map<String, ByteArray>,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val tempOut = if (bundle != null && bundleEntryPath != null) {
        val baseEntryBytes = modifiedBundleEntries[bundleEntryPath]
          ?: withSeekableSource(sourceUri, bundle.fileName, memoryLimitMb) { src ->
            val entry = bundle.directoryEntries.first { it.path == bundleEntryPath }
            AssetBundleParser.extractEntryBytes(src, bundle, entry).getOrThrow()
          }
        SerializedFileWriter.writeAndVerifyToTempFile(
          originalSource = ByteArrayByteSource(baseEntryBytes),
          parsedFile = parsedSf,
          tempDirectory = context.cacheDir,
        ).getOrThrow()
      } else {
        withSeekableSource(sourceUri, parsedSf.fileName, memoryLimitMb) { src ->
          SerializedFileWriter.writeAndVerifyToTempFile(src, parsedSf, context.cacheDir).getOrThrow()
        }
      }

      try {
        copyFileToSafUri(tempOut, targetUri)
      } finally {
        tempOut.delete()
      }
      Result.success(Unit)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  suspend fun serializeActiveSerializedFileBytes(
    sourceUri: Uri,
    memoryLimitMb: Int,
    parsedSf: ParsedSerializedFile,
    bundle: ParsedAssetBundle?,
    bundleEntryPath: String?,
    modifiedBundleEntries: Map<String, ByteArray>,
  ): Result<ByteArray> = withContext(Dispatchers.IO) {
    try {
      val bytes = if (bundle != null && bundleEntryPath != null) {
        val baseEntryBytes = modifiedBundleEntries[bundleEntryPath]
          ?: withSeekableSource(sourceUri, bundle.fileName, memoryLimitMb) { src ->
            val entry = bundle.directoryEntries.first { it.path == bundleEntryPath }
            AssetBundleParser.extractEntryBytes(src, bundle, entry).getOrThrow()
          }
        SerializedFileWriter.serializeToByteArray(ByteArrayByteSource(baseEntryBytes), parsedSf)
      } else {
        withSeekableSource(sourceUri, parsedSf.fileName, memoryLimitMb) { src ->
          SerializedFileWriter.serializeToByteArray(src, parsedSf)
        }
      }
      Result.success(bytes)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  suspend fun saveRebuiltBundleToUri(
    sourceUri: Uri,
    targetUri: Uri,
    memoryLimitMb: Int,
    bundle: ParsedAssetBundle,
    replacedEntries: Map<String, ByteArray>,
    compressionMode: BundleCompressionMode = BundleCompressionMode.LZ4HC,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val tempBundle = withSeekableSource(sourceUri, bundle.fileName, memoryLimitMb) { src ->
        AssetBundleWriter.rebuildUnityFsToTempFile(
          originalSource = src,
          originalBundle = bundle,
          replacedEntries = replacedEntries,
          compressionMode = compressionMode,
          tempDirectory = context.cacheDir,
        ).getOrThrow()
      }
      try {
        copyFileToSafUri(tempBundle, targetUri)
      } finally {
        tempBundle.delete()
      }
      Result.success(Unit)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  suspend fun writeBytesToUri(targetUri: Uri, bytes: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        context.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
          out.write(bytes)
          out.flush()
        } ?: throw IllegalStateException("Unable to open output stream for destination URI.")
        Result.success(Unit)
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  suspend fun readBytesFromUri(uri: Uri, memoryLimitMb: Int): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      try {
        val maxBytes = memoryLimitMb.toLong() * 1024L * 1024L
        val out = java.io.ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
          val buf = ByteArray(32 * 1024)
          var total = 0L
          while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
              throw IllegalStateException("Selected file exceeds memory limit (${memoryLimitMb} MB).")
            }
            out.write(buf, 0, read)
          }
        } ?: throw IllegalStateException("Unable to open input stream for selected URI.")
        Result.success(out.toByteArray())
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  suspend fun decodeBitmapFromUri(uri: Uri): Result<Bitmap> =
    withContext(Dispatchers.IO) {
      try {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
          BitmapFactory.decodeStream(input)
        } ?: throw IllegalArgumentException("Selected file could not be decoded as an image.")
        Result.success(bitmap)
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  private fun copyFileToSafUri(srcFile: File, targetUri: Uri) {
    context.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
      FileInputStream(srcFile).use { input ->
        input.copyTo(out, bufferSize = 64 * 1024)
      }
      out.flush()
    } ?: throw IllegalStateException("Unable to open destination output stream.")
  }

  private fun tryTakePersistablePermission(uri: Uri) {
    try {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    } catch (_: SecurityException) {
    } catch (_: UnsupportedOperationException) {
    }
  }

  private fun queryDocumentMetadata(uri: Uri): DocumentMetadata {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
    var displayName: String? = null
    var sizeBytes = -1L

    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
          displayName = cursor.getString(nameIdx)
        }
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
          sizeBytes = cursor.getLong(sizeIdx)
        }
      }
    }

    val resolvedName = displayName
      ?: uri.lastPathSegment?.substringAfterLast('/')
      ?: "unnamed_unity_file"

    return DocumentMetadata(resolvedName, sizeBytes, mimeType)
  }

  private fun <T> withSeekableSource(
    uri: Uri,
    fileName: String,
    memoryLimitMb: Int,
    block: (SeekableByteSource) -> T,
  ): T {
    val resolver = context.contentResolver
    val pfd = resolver.openFileDescriptor(uri, "r")
    if (pfd != null) {
      pfd.use { descriptor ->
        val fis = FileInputStream(descriptor.fileDescriptor)
        val channel = fis.channel
        val seekableWorks = runCatching {
          val source = FileChannelByteSource(channel, fis)
          // Verify size() works on this FileChannel
          source.length
          source
        }
        if (seekableWorks.isSuccess) {
          return seekableWorks.getOrThrow().use { block(it) }
        } else {
          runCatching { fis.close() }
        }
      }
    }

    val maxCopyBytes = memoryLimitMb.toLong() * 1024L * 1024L
    val tempFile = File.createTempFile("assetdesk_inspect_", ".tmp", context.cacheDir)
    return try {
      resolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
          val buffer = ByteArray(64 * 1024)
          var copied = 0L
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            copied += read
            if (copied > maxCopyBytes) {
              throw IllegalStateException(
                "Non-seekable stream exceeds temporary inspection buffer limit (${memoryLimitMb} MB).",
              )
            }
            output.write(buffer, 0, read)
          }
        }
      } ?: throw IllegalStateException("Unable to open input stream for selected URI.")

      val raf = RandomAccessFile(tempFile, "r")
      FileChannelByteSource.fromRandomAccessFile(raf).use { source ->
        block(source)
      }
    } finally {
      tempFile.delete()
    }
  }

  private data class DocumentMetadata(
    val displayName: String,
    val reportedSizeBytes: Long,
    val mimeType: String?,
  )
}
