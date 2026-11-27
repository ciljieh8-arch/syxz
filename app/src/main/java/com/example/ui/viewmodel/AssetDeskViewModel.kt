package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assetdesk.export.AssetDumpCodec
import com.assetdesk.export.InstallerPackageManager
import com.assetdesk.export.InstallerPackagePreview
import com.assetdesk.export.PackageValidationResult
import com.assetdesk.model.AssetSortField
import com.assetdesk.model.ObjectStatus
import com.assetdesk.model.PPtrReference
import com.assetdesk.model.UnityObjectInfo
import com.assetdesk.plugins.AudioClipPlugin
import com.assetdesk.plugins.MeshPlugin
import com.assetdesk.plugins.PluginInspectionResult
import com.assetdesk.plugins.TextAssetPlugin
import com.assetdesk.plugins.TexturePlugin
import com.assetdesk.unity.BundleDirectoryEntry
import com.assetdesk.unity.UnityFileKind
import com.example.data.SafFileRepository
import com.example.data.WorkspacePreferencesRepository
import com.example.model.AppSettingsState
import com.example.model.ExportModePreference
import com.example.model.OpenedUnityFile
import com.example.model.ParsingProgress
import com.example.model.PendingExportPayload
import com.example.model.RecentFileEntry
import com.example.model.SelectedObjectDetailState
import com.example.model.ThemePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AssetDeskUiState(
  val sessionFiles: List<OpenedUnityFile> = emptyList(),
  val selectedFileId: String? = null,
  val recentFiles: List<RecentFileEntry> = emptyList(),
  val settings: AppSettingsState = AppSettingsState(),
  val parsingProgress: ParsingProgress? = null,
  val errorMessage: String? = null,
  val statusMessage: String? = null,
  val assetSearchQuery: String = "",
  val assetTypeFilter: String = "All",
  val assetSortField: AssetSortField = AssetSortField.PATH_ID,
  val assetSortAscending: Boolean = true,
  val selectedObjectDetail: SelectedObjectDetailState? = null,
  val pendingExport: PendingExportPayload? = null,
  val pendingPackagePreview: InstallerPackagePreview? = null,
  val pendingPackageValidation: PackageValidationResult? = null,
) {
  val activeFile: OpenedUnityFile?
    get() = sessionFiles.firstOrNull { it.id == selectedFileId } ?: sessionFiles.firstOrNull()

  val filteredSortedObjects: List<UnityObjectInfo>
    get() {
      val sf = activeFile?.parsedSerializedFile ?: return emptyList()
      val query = assetSearchQuery.trim()
      val filtered = sf.objects.filter { obj ->
        val matchesType = assetTypeFilter == "All" || obj.className == assetTypeFilter
        val matchesQuery = query.isEmpty() ||
          obj.assetName.contains(query, ignoreCase = true) ||
          obj.className.contains(query, ignoreCase = true) ||
          obj.pathId.toString().contains(query) ||
          (obj.containerPath?.contains(query, ignoreCase = true) == true)
        matchesType && matchesQuery
      }
      val sorted = when (assetSortField) {
        AssetSortField.PATH_ID -> filtered.sortedBy { it.pathId }
        AssetSortField.NAME -> filtered.sortedBy { it.assetName.lowercase() }
        AssetSortField.TYPE -> filtered.sortedBy { it.className }
        AssetSortField.SIZE -> filtered.sortedBy { it.effectiveSize }
      }
      return if (assetSortAscending) sorted else sorted.reversed()
    }

  val availableTypeFilters: List<String>
    get() {
      val sf = activeFile?.parsedSerializedFile ?: return listOf("All")
      return listOf("All") + sf.objects.map { it.className }.distinct().sorted()
    }
}

class AssetDeskViewModel(
  application: Application,
) : AndroidViewModel(application) {

  private val safRepository = SafFileRepository(application.applicationContext)
  private val prefsRepository = WorkspacePreferencesRepository(application.applicationContext)

  private val _uiState = MutableStateFlow(AssetDeskUiState())
  val uiState: StateFlow<AssetDeskUiState> = _uiState.asStateFlow()

  private var activeParseJob: Job? = null

  init {
    viewModelScope.launch {
      prefsRepository.settingsFlow.collectLatest { settings ->
        _uiState.update { it.copy(settings = settings) }
      }
    }
    viewModelScope.launch {
      prefsRepository.recentFilesFlow.collectLatest { recents ->
        _uiState.update { it.copy(recentFiles = recents) }
      }
    }
  }

  fun openUris(uris: List<Uri>) {
    if (uris.isEmpty()) return
    activeParseJob?.cancel()
    val memoryLimit = _uiState.value.settings.memoryLimitMb

    activeParseJob = viewModelScope.launch {
      val newlyParsed = mutableListOf<OpenedUnityFile>()
      val failures = mutableListOf<String>()

      try {
        uris.forEachIndexed { index, uri ->
          val hintName = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${index + 1}"
          _uiState.update {
            it.copy(
              parsingProgress = ParsingProgress(hintName, index + 1, uris.size),
              errorMessage = null,
            )
          }

          val result = safRepository.openAndParseUri(uri, memoryLimit)
          result.onSuccess { opened ->
            newlyParsed.add(opened)
          }.onFailure { err ->
            failures.add("$hintName: ${err.message ?: "Failed to read file"}")
          }
        }

        _uiState.update { state ->
          val existingByUri = state.sessionFiles.filterNot { existing ->
            newlyParsed.any { it.uriString == existing.uriString }
          }
          val combined = newlyParsed + existingByUri
          val nextSelected = newlyParsed.firstOrNull()?.id ?: state.selectedFileId
          state.copy(
            sessionFiles = combined,
            selectedFileId = nextSelected,
            selectedObjectDetail = null,
            assetTypeFilter = "All",
            assetSearchQuery = "",
            parsingProgress = null,
            errorMessage = if (failures.isNotEmpty()) failures.joinToString("\n") else null,
          )
        }

        newlyParsed.forEach { opened ->
          viewModelScope.launch {
            runCatching {
              prefsRepository.recordRecentFile(
                RecentFileEntry(
                  uriString = opened.uriString,
                  fileName = opened.fileName,
                  fileSizeBytes = opened.fileSizeBytes,
                  formatLabel = opened.kind.displayName,
                  statusLabel = opened.readability.label,
                  lastOpenedEpochMs = opened.openedAtEpochMs,
                ),
              )
            }
          }
        }
      } catch (_: CancellationException) {
        _uiState.update {
          it.copy(parsingProgress = null, errorMessage = "File inspection cancelled by user.")
        }
      }
    }
  }

  fun cancelParsing() {
    activeParseJob?.cancel()
    activeParseJob = null
    _uiState.update { it.copy(parsingProgress = null) }
  }

  fun selectSessionFile(fileId: String) {
    _uiState.update {
      it.copy(
        selectedFileId = fileId,
        selectedObjectDetail = null,
        assetTypeFilter = "All",
        assetSearchQuery = "",
      )
    }
  }

  fun removeSessionFile(fileId: String) {
    _uiState.update { state ->
      val remaining = state.sessionFiles.filterNot { it.id == fileId }
      val nextSelected =
        if (state.selectedFileId == fileId) remaining.firstOrNull()?.id else state.selectedFileId
      state.copy(
        sessionFiles = remaining,
        selectedFileId = nextSelected,
        selectedObjectDetail = if (state.selectedFileId == fileId) null else state.selectedObjectDetail,
      )
    }
  }

  fun clearSessionFiles() {
    _uiState.update {
      it.copy(sessionFiles = emptyList(), selectedFileId = null, selectedObjectDetail = null)
    }
  }

  fun reopenRecentFile(entry: RecentFileEntry) {
    openUris(listOf(Uri.parse(entry.uriString)))
  }

  fun removeRecentFile(uriString: String) {
    viewModelScope.launch { prefsRepository.removeRecentFile(uriString) }
  }

  fun clearRecentFiles() {
    viewModelScope.launch { prefsRepository.clearRecentFiles() }
  }

  fun updateSearchQuery(query: String) {
    _uiState.update { it.copy(assetSearchQuery = query) }
  }

  fun updateTypeFilter(type: String) {
    _uiState.update { it.copy(assetTypeFilter = type) }
  }

  fun updateSort(field: AssetSortField) {
    _uiState.update { state ->
      if (state.assetSortField == field) {
        state.copy(assetSortAscending = !state.assetSortAscending)
      } else {
        state.copy(assetSortField = field, assetSortAscending = true)
      }
    }
  }

  fun openBundleEntry(entry: BundleDirectoryEntry) {
    val active = _uiState.value.activeFile ?: return
    val bundle = active.parsedAssetBundle ?: return
    val memoryLimit = _uiState.value.settings.memoryLimitMb

    viewModelScope.launch {
      _uiState.update {
        it.copy(parsingProgress = ParsingProgress(entry.path, 1, 1), errorMessage = null)
      }
      val res = safRepository.openBundleEntryAsSerializedFile(
        uri = Uri.parse(active.uriString),
        memoryLimitMb = memoryLimit,
        bundle = bundle,
        entry = entry,
        stagedSerializedFiles = active.stagedSerializedFiles,
      )
      res.onSuccess { sf ->
        updateActiveFile { file ->
          file.copy(
            parsedSerializedFile = sf,
            activeBundleEntryPath = entry.path,
            stagedSerializedFiles = file.stagedSerializedFiles + (entry.path to sf),
          )
        }
        _uiState.update {
          it.copy(
            parsingProgress = null,
            selectedObjectDetail = null,
            assetTypeFilter = "All",
            statusMessage = "Loaded internal SerializedFile '${entry.path}' (${sf.objects.size} assets).",
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            parsingProgress = null,
            errorMessage = "Could not open '${entry.path}' as SerializedFile: ${err.message}",
          )
        }
      }
    }
  }

  fun prepareExportBundleEntry(entry: BundleDirectoryEntry) {
    val active = _uiState.value.activeFile ?: return
    val bundle = active.parsedAssetBundle ?: return
    val memoryLimit = _uiState.value.settings.memoryLimitMb

    viewModelScope.launch {
      val res = safRepository.extractBundleEntryRawBytes(
        uri = Uri.parse(active.uriString),
        memoryLimitMb = memoryLimit,
        bundle = bundle,
        entry = entry,
        modifiedBundleEntries = active.modifiedBundleEntries,
      )
      res.onSuccess { bytes ->
        val safeName = entry.path.substringAfterLast('/')
        _uiState.update {
          it.copy(
            pendingExport = PendingExportPayload.BytesExport(
              suggestedFileName = safeName,
              mimeType = "application/octet-stream",
              bytes = bytes,
            ),
          )
        }
      }.onFailure { err ->
        _uiState.update { it.copy(errorMessage = "Bundle export failed: ${err.message}") }
      }
    }
  }

  fun inspectObject(obj: UnityObjectInfo) {
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    val memoryLimit = _uiState.value.settings.memoryLimitMb

    viewModelScope.launch {
      _uiState.update {
        it.copy(parsingProgress = ParsingProgress(obj.assetName, 1, 1), errorMessage = null)
      }
      val res = safRepository.loadObjectDetail(
        uri = Uri.parse(active.uriString),
        memoryLimitMb = memoryLimit,
        parsedSf = sf,
        obj = obj,
        bundle = active.parsedAssetBundle,
        bundleEntryPath = active.activeBundleEntryPath,
        modifiedBundleEntries = active.modifiedBundleEntries,
      )
      res.onSuccess { detail ->
        _uiState.update { it.copy(parsingProgress = null, selectedObjectDetail = detail) }
      }.onFailure { err ->
        _uiState.update {
          it.copy(parsingProgress = null, errorMessage = "Failed to inspect object: ${err.message}")
        }
      }
    }
  }

  fun closeObjectDetail() {
    _uiState.update { it.copy(selectedObjectDetail = null) }
  }

  fun navigateToPPtr(pptr: PPtrReference) {
    if (pptr.isNull) return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    if (pptr.fileId != 0) {
      val extName = sf.externals.getOrNull(pptr.fileId - 1)?.pathName ?: "External #${pptr.fileId}"
      _uiState.update {
        it.copy(
          statusMessage = "External dependency reference: FileID=${pptr.fileId} ($extName), PathID=${pptr.pathId}. Open '$extName' to inspect target.",
        )
      }
      return
    }
    val target = sf.objects.firstOrNull { it.pathId == pptr.pathId }
    if (target == null) {
      _uiState.update { it.copy(errorMessage = "Target PathID=${pptr.pathId} not found in current file.") }
      return
    }
    inspectObject(target)
  }

  fun prepareExportRawObject() {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val safeName = sanitizeFilename("${detail.objectInfo.assetName}_${detail.objectInfo.pathId}.dat")
    _uiState.update {
      it.copy(
        pendingExport = PendingExportPayload.BytesExport(
          suggestedFileName = safeName,
          mimeType = "application/octet-stream",
          bytes = detail.rawBytes,
        ),
      )
    }
  }

  fun prepareExportDumpObject() {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val dump = detail.dumpTextPreview
    if (dump == null) {
      _uiState.update { it.copy(errorMessage = "Dump export requires a TypeTree or fallback schema for this asset.") }
      return
    }
    val safeName = sanitizeFilename("${detail.objectInfo.assetName}_${detail.objectInfo.pathId}.txt")
    _uiState.update {
      it.copy(
        pendingExport = PendingExportPayload.BytesExport(
          suggestedFileName = safeName,
          mimeType = "text/plain",
          bytes = dump.toByteArray(Charsets.UTF_8),
        ),
      )
    }
  }

  fun prepareExportPluginOutput() {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return

    when (val plugin = detail.pluginResult) {
      is PluginInspectionResult.TexturePreview -> {
        val bmp = plugin.decodedBitmap
        if (bmp == null) {
          _uiState.update { it.copy(errorMessage = "Texture format ${plugin.formatName} cannot be exported to PNG directly.") }
          return
        }
        val pngBytes = TexturePlugin.exportPngBytes(bmp)
        _uiState.update {
          it.copy(
            pendingExport = PendingExportPayload.BytesExport(
              suggestedFileName = sanitizeFilename("${plugin.assetName}.png"),
              mimeType = "image/png",
              bytes = pngBytes,
            ),
          )
        }
      }
      is PluginInspectionResult.TextAssetPreview -> {
        val ext = if (plugin.isBinaryFallback) "bytes" else "txt"
        val mime = if (plugin.isBinaryFallback) "application/octet-stream" else "text/plain"
        _uiState.update {
          it.copy(
            pendingExport = PendingExportPayload.BytesExport(
              suggestedFileName = sanitizeFilename("${plugin.assetName}.$ext"),
              mimeType = mime,
              bytes = plugin.payloadBytes,
            ),
          )
        }
      }
      is PluginInspectionResult.AudioClipPreview -> {
        val res = AudioClipPlugin.exportRecognizedAudio(
          detail.rawBytes,
          sf.header.endianness,
          detail.decodedData,
        )
        res.onSuccess { (ext, bytes) ->
          _uiState.update {
            it.copy(
              pendingExport = PendingExportPayload.BytesExport(
                suggestedFileName = sanitizeFilename("${plugin.assetName}.$ext"),
                mimeType = "application/octet-stream",
                bytes = bytes,
              ),
            )
          }
        }.onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message) }
        }
      }
      is PluginInspectionResult.MeshPreview -> {
        val objText = plugin.objText
        if (objText == null) {
          _uiState.update { it.copy(errorMessage = "Mesh OBJ export unavailable for this geometry layout.") }
          return
        }
        _uiState.update {
          it.copy(
            pendingExport = PendingExportPayload.BytesExport(
              suggestedFileName = sanitizeFilename("${plugin.assetName}.obj"),
              mimeType = "text/plain",
              bytes = objText.toByteArray(Charsets.UTF_8),
            ),
          )
        }
      }
      else -> {
        _uiState.update { it.copy(errorMessage = "No plugin export action for this asset.") }
      }
    }
  }

  fun importRawIntoSelectedObject(uri: Uri) {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    if (sf.header.isReadOnly) {
      _uiState.update { it.copy(errorMessage = sf.header.readOnlyReason ?: "File is read-only.") }
      return
    }

    viewModelScope.launch {
      val res = safRepository.readBytesFromUri(uri, _uiState.value.settings.memoryLimitMb)
      res.onSuccess { newBytes ->
        applyModifiedObjectBytes(detail.objectInfo.pathId, newBytes, "Imported ${newBytes.size} raw bytes.")
      }.onFailure { err ->
        _uiState.update { it.copy(errorMessage = "Import Raw failed: ${err.message}") }
      }
    }
  }

  fun importDumpFromUriIntoSelectedObject(uri: Uri) {
    viewModelScope.launch {
      val res = safRepository.readBytesFromUri(uri, _uiState.value.settings.memoryLimitMb)
      res.onSuccess { bytes ->
        importDumpTextIntoSelectedObject(String(bytes, Charsets.UTF_8))
      }.onFailure { err ->
        _uiState.update { it.copy(errorMessage = "Failed to read dump file: ${err.message}") }
      }
    }
  }

  fun importDumpTextIntoSelectedObject(dumpText: String) {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    if (sf.header.isReadOnly) {
      _uiState.update { it.copy(errorMessage = sf.header.readOnlyReason ?: "File is read-only.") }
      return
    }
    val typeNode = detail.typeTreeNode
    if (typeNode == null) {
      _uiState.update { it.copy(errorMessage = "Cannot import dump: no TypeTree schema available.") }
      return
    }

    val serialized = AssetDumpCodec.importDump(dumpText, typeNode, sf.header.endianness)
    serialized.onSuccess { newBytes ->
      applyModifiedObjectBytes(detail.objectInfo.pathId, newBytes, "Dump validated and staged (${newBytes.size} B).")
    }.onFailure { err ->
      _uiState.update { it.copy(errorMessage = "Import Dump failed: ${err.message}") }
    }
  }

  fun importTextReplacement(newText: String) {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    if (sf.header.isReadOnly) {
      _uiState.update { it.copy(errorMessage = sf.header.readOnlyReason ?: "File is read-only.") }
      return
    }
    val newBytes = TextAssetPlugin.buildModifiedRawBytes(detail.rawBytes, newText, sf.header.endianness)
    applyModifiedObjectBytes(detail.objectInfo.pathId, newBytes, "TextAsset replacement staged (${newBytes.size} B).")
  }

  fun importTextureReplacementFromUri(uri: Uri) {
    val detail = _uiState.value.selectedObjectDetail ?: return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    if (sf.header.isReadOnly) {
      _uiState.update { it.copy(errorMessage = sf.header.readOnlyReason ?: "File is read-only.") }
      return
    }

    viewModelScope.launch {
      val bmpRes = safRepository.decodeBitmapFromUri(uri)
      bmpRes.onSuccess { bitmap ->
        val encoded = TexturePlugin.buildModifiedRawBytes(
          originalRawBytes = detail.rawBytes,
          byteOrder = sf.header.endianness,
          formatVersion = sf.header.formatVersion,
          decodedData = detail.decodedData,
          replacementBitmap = bitmap,
        )
        encoded.onSuccess { newBytes ->
          applyModifiedObjectBytes(detail.objectInfo.pathId, newBytes, "Texture2D pixel data validated & staged.")
        }.onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message) }
        }
      }.onFailure { err ->
        _uiState.update { it.copy(errorMessage = err.message) }
      }
    }
  }

  private fun applyModifiedObjectBytes(
    pathId: Long,
    newBytes: ByteArray,
    statusNote: String,
  ) {
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    val updatedObjects = sf.objects.map { obj ->
      if (obj.pathId == pathId) {
        obj.copy(modifiedRawBytes = newBytes, status = ObjectStatus.MODIFIED)
      } else obj
    }
    val updatedSf = sf.copy(objects = updatedObjects)
    val bundle = active.parsedAssetBundle
    val bundleEntryPath = active.activeBundleEntryPath
    updateActiveFile { file ->
      file.copy(
        parsedSerializedFile = updatedSf,
        stagedSerializedFiles = if (bundleEntryPath != null) {
          file.stagedSerializedFiles + (bundleEntryPath to updatedSf)
        } else {
          file.stagedSerializedFiles
        },
      )
    }

    // If inside an AssetBundle, also stage the re-serialized SerializedFile bytes into modifiedBundleEntries
    if (bundle != null && bundleEntryPath != null) {
      viewModelScope.launch {
        val sfBytesRes = safRepository.serializeActiveSerializedFileBytes(
          sourceUri = Uri.parse(active.uriString),
          memoryLimitMb = _uiState.value.settings.memoryLimitMb,
          parsedSf = updatedSf,
          bundle = bundle,
          bundleEntryPath = bundleEntryPath,
        )
        sfBytesRes.onSuccess { newEntryBytes ->
          updateActiveFile { file ->
            file.copy(
              modifiedBundleEntries = file.modifiedBundleEntries + (bundleEntryPath to newEntryBytes),
            )
          }
        }.onFailure { err ->
          _uiState.update {
            it.copy(errorMessage = "Failed to stage bundle entry '$bundleEntryPath': ${err.message}")
          }
        }
      }
    }

    val updatedObj = updatedObjects.firstOrNull { it.pathId == pathId }
    if (updatedObj != null) {
      inspectObject(updatedObj)
    }
    _uiState.update { it.copy(statusMessage = statusNote) }
  }

  fun prepareSaveSerializedFileAs() {
    val active = _uiState.value.activeFile ?: return
    val bundle = active.parsedAssetBundle
    if (bundle != null && bundle.header.kind == UnityFileKind.ASSET_BUNDLE_FS) {
      if (bundle.header.isReadOnly) {
        _uiState.update { it.copy(errorMessage = bundle.header.readOnlyReason ?: "Bundle is read-only.") }
        return
      }
      _uiState.update {
        it.copy(
          pendingExport = PendingExportPayload.RebuildBundleAs(
            suggestedFileName = "rebuilt_${bundle.fileName}",
            fileId = active.id,
          ),
        )
      }
      return
    }

    val sf = active.parsedSerializedFile ?: return
    if (sf.header.isReadOnly) {
      _uiState.update { it.copy(errorMessage = sf.header.readOnlyReason ?: "File is read-only.") }
      return
    }
    val baseName = sf.fileName.substringAfterLast('/')
    _uiState.update {
      it.copy(
        pendingExport = PendingExportPayload.SaveSerializedFileAs(
          suggestedFileName = "modified_$baseName",
          fileId = active.id,
        ),
      )
    }
  }

  fun prepareRebuildBundleAs() {
    val active = _uiState.value.activeFile ?: return
    val bundle = active.parsedAssetBundle ?: return
    _uiState.update {
      it.copy(
        pendingExport = PendingExportPayload.RebuildBundleAs(
          suggestedFileName = "rebuilt_${bundle.fileName}",
          fileId = active.id,
        ),
      )
    }
  }

  fun prepareCreateInstallerPackage() {
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return
    val pkgRes = InstallerPackageManager.createPackageBytes(
      packageName = "${sf.fileName} Patch",
      sourceFile = sf,
    )
    pkgRes.onSuccess { bytes ->
      _uiState.update {
        it.copy(
          pendingExport = PendingExportPayload.BytesExport(
            suggestedFileName = sanitizeFilename("${sf.fileName}.adpkg"),
            mimeType = "application/zip",
            bytes = bytes,
          ),
        )
      }
    }.onFailure { err ->
      _uiState.update { it.copy(errorMessage = err.message) }
    }
  }

  fun inspectInstallerPackageUri(uri: Uri) {
    val active = _uiState.value.activeFile
    val sf = active?.parsedSerializedFile
    if (sf == null) {
      _uiState.update { it.copy(errorMessage = "Open a target SerializedFile before importing a change package.") }
      return
    }

    viewModelScope.launch {
      val bytesRes = safRepository.readBytesFromUri(uri, _uiState.value.settings.memoryLimitMb)
      bytesRes.onSuccess { bytes ->
        val pkgRes = InstallerPackageManager.inspectPackage(bytes)
        pkgRes.onSuccess { preview ->
          val validation = InstallerPackageManager.validateAgainstTarget(preview, sf)
          _uiState.update {
            it.copy(
              pendingPackagePreview = preview,
              pendingPackageValidation = validation,
            )
          }
        }.onFailure { err ->
          _uiState.update { it.copy(errorMessage = "Invalid .adpkg package: ${err.message}") }
        }
      }.onFailure { err ->
        _uiState.update { it.copy(errorMessage = "Failed to read package: ${err.message}") }
      }
    }
  }

  fun confirmApplyInstallerPackage() {
    val preview = _uiState.value.pendingPackagePreview ?: return
    val active = _uiState.value.activeFile ?: return
    val sf = active.parsedSerializedFile ?: return

    val applied = InstallerPackageManager.applyPackage(preview, sf)
    applied.onSuccess { updatedSf ->
      val bundle = active.parsedAssetBundle
      val bundleEntryPath = active.activeBundleEntryPath
      updateActiveFile { file ->
        file.copy(
          parsedSerializedFile = updatedSf,
          stagedSerializedFiles = if (bundleEntryPath != null) {
            file.stagedSerializedFiles + (bundleEntryPath to updatedSf)
          } else {
            file.stagedSerializedFiles
          },
        )
      }
      if (bundle != null && bundleEntryPath != null) {
        viewModelScope.launch {
          safRepository.serializeActiveSerializedFileBytes(
            sourceUri = Uri.parse(active.uriString),
            memoryLimitMb = _uiState.value.settings.memoryLimitMb,
            parsedSf = updatedSf,
            bundle = bundle,
            bundleEntryPath = bundleEntryPath,
          ).onSuccess { newEntryBytes ->
            updateActiveFile { file ->
              file.copy(
                modifiedBundleEntries = file.modifiedBundleEntries + (bundleEntryPath to newEntryBytes),
              )
            }
          }
        }
      }
      _uiState.update {
        it.copy(
          pendingPackagePreview = null,
          pendingPackageValidation = null,
          selectedObjectDetail = null,
          statusMessage = "Applied ${preview.changes.size} asset patch(es) from '${preview.packageName}'. Use Save As to write output.",
        )
      }
    }.onFailure { err ->
      _uiState.update {
        it.copy(
          pendingPackagePreview = null,
          pendingPackageValidation = null,
          errorMessage = err.message,
        )
      }
    }
  }

  fun dismissInstallerPackagePreview() {
    _uiState.update { it.copy(pendingPackagePreview = null, pendingPackageValidation = null) }
  }

  fun completePendingExport(targetUri: Uri) {
    val pending = _uiState.value.pendingExport ?: return
    _uiState.update { it.copy(pendingExport = null) }

    viewModelScope.launch {
      when (pending) {
        is PendingExportPayload.BytesExport -> {
          val res = safRepository.writeBytesToUri(targetUri, pending.bytes)
          res.onSuccess {
            _uiState.update { it.copy(statusMessage = "Exported '${pending.suggestedFileName}' (${pending.bytes.size} B).") }
          }.onFailure { err ->
            _uiState.update { it.copy(errorMessage = "Export failed: ${err.message}") }
          }
        }
        is PendingExportPayload.SaveSerializedFileAs -> {
          val file = _uiState.value.sessionFiles.firstOrNull { it.id == pending.fileId } ?: return@launch
          val sf = file.parsedSerializedFile ?: return@launch
          _uiState.update { it.copy(parsingProgress = ParsingProgress(pending.suggestedFileName, 1, 1)) }
          val res = safRepository.saveModifiedSerializedFileToUri(
            sourceUri = Uri.parse(file.uriString),
            targetUri = targetUri,
            memoryLimitMb = _uiState.value.settings.memoryLimitMb,
            parsedSf = sf,
            bundle = file.parsedAssetBundle,
            bundleEntryPath = file.activeBundleEntryPath,
          )
          res.onSuccess {
            _uiState.update {
              it.copy(
                parsingProgress = null,
                statusMessage = "Saved & verified '${pending.suggestedFileName}'.",
              )
            }
          }.onFailure { err ->
            _uiState.update {
              it.copy(parsingProgress = null, errorMessage = "Save failed: ${err.message}")
            }
          }
        }
        is PendingExportPayload.RebuildBundleAs -> {
          val file = _uiState.value.sessionFiles.firstOrNull { it.id == pending.fileId } ?: return@launch
          val bundle = file.parsedAssetBundle ?: return@launch
          val staged = buildMap {
            putAll(file.stagedSerializedFiles)
            val entryPath = file.activeBundleEntryPath
            val activeSf = file.parsedSerializedFile
            if (entryPath != null && activeSf != null) {
              put(entryPath, activeSf)
            }
          }
          _uiState.update { it.copy(parsingProgress = ParsingProgress(pending.suggestedFileName, 1, 1)) }
          val res = safRepository.saveRebuiltBundleToUri(
            sourceUri = Uri.parse(file.uriString),
            targetUri = targetUri,
            memoryLimitMb = _uiState.value.settings.memoryLimitMb,
            bundle = bundle,
            replacedEntries = file.modifiedBundleEntries,
            stagedSerializedFiles = staged,
          )
          res.onSuccess { usedCodec ->
            _uiState.update {
              it.copy(
                parsingProgress = null,
                statusMessage = "Rebuilt & verified UnityFS bundle '${pending.suggestedFileName}' (${usedCodec.label}).",
              )
            }
          }.onFailure { err ->
            _uiState.update {
              it.copy(parsingProgress = null, errorMessage = "Bundle rebuild failed: ${err.message}")
            }
          }
        }
      }
    }
  }

  fun cancelPendingExport() {
    _uiState.update { it.copy(pendingExport = null) }
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun dismissStatus() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  fun setThemePreference(preference: ThemePreference) {
    viewModelScope.launch { prefsRepository.setThemePreference(preference) }
  }

  fun setMemoryLimitMb(limitMb: Int) {
    viewModelScope.launch { prefsRepository.setMemoryLimitMb(limitMb) }
  }

  fun setExportModePreference(preference: ExportModePreference) {
    viewModelScope.launch { prefsRepository.setExportModePreference(preference) }
  }

  private fun updateActiveFile(transform: (OpenedUnityFile) -> OpenedUnityFile) {
    val activeId = _uiState.value.activeFile?.id ?: return
    _uiState.update { state ->
      state.copy(
        sessionFiles = state.sessionFiles.map { file ->
          if (file.id == activeId) transform(file) else file
        },
      )
    }
  }

  private fun sanitizeFilename(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
