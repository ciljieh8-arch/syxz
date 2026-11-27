package com.example.model

import com.assetdesk.model.DecodedAssetData
import com.assetdesk.model.TypeTreeNode
import com.assetdesk.model.TypeTreeSource
import com.assetdesk.model.UnityObjectInfo
import com.assetdesk.plugins.PluginInspectionResult
import com.assetdesk.unity.HeaderParseResult
import com.assetdesk.unity.ParsedAssetBundle
import com.assetdesk.unity.ParsedSerializedFile
import com.assetdesk.unity.ReadabilityStatus
import com.assetdesk.unity.UnityFileKind

enum class ThemePreference(val label: String) {
  DARK("Dark Graphite"),
  LIGHT("Light Technical"),
  SYSTEM("Follow System"),
}

enum class ExportModePreference(val label: String) {
  ASK_EACH_TIME("Ask Every Time"),
  PREFER_DUMP("Text Dump (.txt)"),
  PREFER_RAW("Raw Binary (.dat)"),
}

data class OpenedUnityFile(
  val id: String,
  val uriString: String,
  val fileName: String,
  val fileSizeBytes: Long,
  val mimeType: String?,
  val parseResult: HeaderParseResult,
  val parsedSerializedFile: ParsedSerializedFile? = null,
  val parsedAssetBundle: ParsedAssetBundle? = null,
  val activeBundleEntryPath: String? = null,
  val modifiedBundleEntries: Map<String, ByteArray> = emptyMap(),
  val openedAtEpochMs: Long,
) {
  val kind: UnityFileKind
    get() = parseResult.kind

  val readability: ReadabilityStatus
    get() = parseResult.readability

  val hasUnsavedChanges: Boolean
    get() = (parsedSerializedFile?.objects?.any { it.isModified } == true) ||
      modifiedBundleEntries.isNotEmpty()
}

data class SelectedObjectDetailState(
  val objectInfo: UnityObjectInfo,
  val rawBytes: ByteArray,
  val decodedData: DecodedAssetData,
  val typeTreeNode: TypeTreeNode?,
  val typeTreeSource: TypeTreeSource,
  val pluginResult: PluginInspectionResult?,
  val dumpTextPreview: String?,
)

data class RecentFileEntry(
  val uriString: String,
  val fileName: String,
  val fileSizeBytes: Long,
  val formatLabel: String,
  val statusLabel: String,
  val lastOpenedEpochMs: Long,
)

data class ParsingProgress(
  val fileName: String,
  val currentIndex: Int,
  val totalFiles: Int,
)

data class AppSettingsState(
  val themePreference: ThemePreference = ThemePreference.DARK,
  val memoryLimitMb: Int = 32,
  val exportModePreference: ExportModePreference = ExportModePreference.ASK_EACH_TIME,
)

sealed interface PendingExportPayload {
  val suggestedFileName: String
  val mimeType: String

  data class BytesExport(
    override val suggestedFileName: String,
    override val mimeType: String,
    val bytes: ByteArray,
  ) : PendingExportPayload

  data class SaveSerializedFileAs(
    override val suggestedFileName: String,
    override val mimeType: String = "application/octet-stream",
    val fileId: String,
  ) : PendingExportPayload

  data class RebuildBundleAs(
    override val suggestedFileName: String,
    override val mimeType: String = "application/octet-stream",
    val fileId: String,
  ) : PendingExportPayload
}
