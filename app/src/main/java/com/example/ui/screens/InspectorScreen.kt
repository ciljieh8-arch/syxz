package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetdesk.model.AssetSortField
import com.assetdesk.model.PPtrReference
import com.assetdesk.model.UnityObjectInfo
import com.assetdesk.unity.HeaderParseResult
import com.assetdesk.unity.ParsedSerializedFile
import com.example.model.OpenedUnityFile
import com.example.ui.components.EngineeringPanel
import com.example.ui.components.HeaderTelemetryPanel
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.formatByteSize
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.MutedMint
import com.example.ui.viewmodel.AssetDeskUiState

@Composable
fun InspectorScreen(
  uiState: AssetDeskUiState,
  onSelectFile: (String) -> Unit,
  onOpenFile: () -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onTypeFilterSelected: (String) -> Unit,
  onSortSelected: (AssetSortField) -> Unit,
  onInspectObject: (UnityObjectInfo) -> Unit,
  onCloseObjectDetail: () -> Unit,
  onNavigateToPPtr: (PPtrReference) -> Unit,
  onExportRawObject: () -> Unit,
  onExportDumpObject: () -> Unit,
  onExportPluginOutput: () -> Unit,
  onImportRawObject: () -> Unit,
  onImportDumpFile: () -> Unit,
  onImportDumpText: (String) -> Unit,
  onImportTextReplacement: (String) -> Unit,
  onImportTextureReplacement: () -> Unit,
  onSaveSerializedFileAs: () -> Unit,
  onCreateInstallerPackage: () -> Unit,
  onImportInstallerPackage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeFile = uiState.activeFile
  val detail = uiState.selectedObjectDetail

  if (detail != null && activeFile != null) {
    val sf = activeFile.parsedSerializedFile
    ObjectInspectorDetailPane(
      detail = detail,
      isReadOnly = sf?.header?.isReadOnly ?: true,
      readOnlyReason = sf?.header?.readOnlyReason,
      onClose = onCloseObjectDetail,
      onNavigateToPPtr = onNavigateToPPtr,
      onExportRaw = onExportRawObject,
      onExportDump = onExportDumpObject,
      onExportPlugin = onExportPluginOutput,
      onImportRaw = onImportRawObject,
      onImportDumpFile = onImportDumpFile,
      onImportDumpText = onImportDumpText,
      onImportTextReplacement = onImportTextReplacement,
      onImportTexture = onImportTextureReplacement,
      modifier = modifier,
    )
    return
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("inspector_screen"),
  ) {
    if (activeFile == null) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Outlined.AccountTree,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Inspector Idle",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Open a Unity SerializedFile or AssetBundle to inspect its Asset Table, TypeTrees, and Objects.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
          onClick = onOpenFile,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("inspector_open_file_button"),
        ) {
          Text("Open File")
        }
      }
      return
    }

    if (uiState.sessionFiles.size > 1) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        uiState.sessionFiles.forEach { file ->
          FilterChip(
            selected = file.id == activeFile.id,
            onClick = { onSelectFile(file.id) },
            label = {
              Text(
                text = file.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
          )
        }
      }
    }

    Surface(
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = activeFile.activeBundleEntryPath ?: activeFile.fileName,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = buildString {
                append("${activeFile.kind.displayName} · ${formatByteSize(activeFile.fileSizeBytes)}")
                if (activeFile.hasUnsavedChanges) append(" · Modified")
              },
              style = MaterialTheme.typography.bodySmall,
              color = if (activeFile.hasUnsavedChanges) MutedMint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          StatusPill(status = activeFile.readability)
        }
      }
    }

    val sf = activeFile.parsedSerializedFile
    var selectedTab by rememberSaveable(activeFile.id, activeFile.activeBundleEntryPath) {
      mutableIntStateOf(0)
    }

    if (sf != null) {
      TabRow(
        selectedTabIndex = selectedTab.coerceIn(0, 3),
        containerColor = MaterialTheme.colorScheme.surface,
      ) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Assets (${sf.objects.size})") },
          modifier = Modifier.testTag("inspector_tab_assets"),
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Externals (${sf.externals.size})") },
          modifier = Modifier.testTag("inspector_tab_externals"),
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = { Text("Header") },
          modifier = Modifier.testTag("inspector_tab_header"),
        )
        Tab(
          selected = selectedTab == 3,
          onClick = { selectedTab = 3 },
          text = { Text("Layout") },
          modifier = Modifier.testTag("inspector_tab_layout"),
        )
      }

      when (selectedTab) {
        0 -> AssetListTab(
          uiState = uiState,
          sf = sf,
          hasUnsavedChanges = activeFile.hasUnsavedChanges,
          onSearchQueryChange = onSearchQueryChange,
          onTypeFilterSelected = onTypeFilterSelected,
          onSortSelected = onSortSelected,
          onInspectObject = onInspectObject,
          onSaveSerializedFileAs = onSaveSerializedFileAs,
          onCreateInstallerPackage = onCreateInstallerPackage,
          onImportInstallerPackage = onImportInstallerPackage,
        )
        1 -> ExternalsTab(sf = sf)
        2 -> LazyColumn(
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item { HeaderTelemetryPanel(parseResult = activeFile.parseResult) }
        }
        3 -> LazyColumn(
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item { BinaryLayoutPanel(activeFile = activeFile) }
        }
      }
    } else {
      TabRow(
        selectedTabIndex = selectedTab.coerceIn(0, 1),
        containerColor = MaterialTheme.colorScheme.surface,
      ) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Header Telemetry") },
          modifier = Modifier.testTag("inspector_tab_header"),
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Binary Layout") },
          modifier = Modifier.testTag("inspector_tab_layout"),
        )
      }
      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (selectedTab == 0) {
          item { HeaderTelemetryPanel(parseResult = activeFile.parseResult) }
        } else {
          item { BinaryLayoutPanel(activeFile = activeFile) }
        }
      }
    }
  }
}

@Composable
private fun AssetListTab(
  uiState: AssetDeskUiState,
  sf: ParsedSerializedFile,
  hasUnsavedChanges: Boolean,
  onSearchQueryChange: (String) -> Unit,
  onTypeFilterSelected: (String) -> Unit,
  onSortSelected: (AssetSortField) -> Unit,
  onInspectObject: (UnityObjectInfo) -> Unit,
  onSaveSerializedFileAs: () -> Unit,
  onCreateInstallerPackage: () -> Unit,
  onImportInstallerPackage: () -> Unit,
) {
  val objects = uiState.filteredSortedObjects

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier
      .fillMaxSize()
      .testTag("asset_list_tab"),
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = onSaveSerializedFileAs,
            enabled = !sf.header.isReadOnly,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("save_serialized_as_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (hasUnsavedChanges) "Save Modified As..." else "Save Copy As...")
          }
          OutlinedButton(
            onClick = onImportInstallerPackage,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("import_adpkg_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Import .adpkg")
          }
        }

        if (hasUnsavedChanges) {
          OutlinedButton(
            onClick = onCreateInstallerPackage,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("export_adpkg_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Export Installer Package (.adpkg) from Modified Assets")
          }
        }

        OutlinedTextField(
          value = uiState.assetSearchQuery,
          onValueChange = onSearchQueryChange,
          leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
          placeholder = { Text("Search by Name, Class, Path ID, or Container") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("asset_search_input"),
        )

        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(uiState.availableTypeFilters) { typeName ->
            FilterChip(
              selected = uiState.assetTypeFilter == typeName,
              onClick = { onTypeFilterSelected(typeName) },
              label = { Text(typeName) },
              modifier = Modifier.testTag("type_filter_$typeName"),
            )
          }
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Sort:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          AssetSortField.entries.forEach { field ->
            val active = uiState.assetSortField == field
            FilterChip(
              selected = active,
              onClick = { onSortSelected(field) },
              trailingIcon = if (active) {
                {
                  Icon(
                    imageVector = if (uiState.assetSortAscending) {
                      Icons.Outlined.ArrowUpward
                    } else {
                      Icons.Outlined.ArrowDownward
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                  )
                }
              } else null,
              label = { Text(field.label) },
              modifier = Modifier.testTag("sort_${field.name.lowercase()}"),
            )
          }
        }

        SectionHeader(title = "Showing ${objects.size} of ${sf.objects.size} Objects")
      }
    }

    if (objects.isEmpty()) {
      item {
        EngineeringPanel {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "No matching assets",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Try clearing your search query or selecting All types.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(
        items = objects,
        key = { "${it.fileId}_${it.pathId}" },
      ) { obj ->
        AssetObjectRow(
          obj = obj,
          onClick = { onInspectObject(obj) },
        )
      }
    }
  }
}

@Composable
private fun AssetObjectRow(
  obj: UnityObjectInfo,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .testTag("asset_row_${obj.pathId}"),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(
      width = 1.dp,
      color = if (obj.isModified) MutedMint.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline,
    ),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (obj.hasExplicitName) obj.assetName else "<Unnamed ${obj.className}>",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ObjectStatusBadge(status = obj.status)
      }
      Text(
        text = "${obj.className} (ClassID ${obj.classId}) · FileID ${obj.fileId} · PathID ${obj.pathId} · ${formatByteSize(obj.effectiveSize)} (${obj.effectiveSize} B)",
        style = MonoTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (!obj.containerPath.isNullOrBlank()) {
        Text(
          text = "Container: ${obj.containerPath}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ExternalsTab(sf: ParsedSerializedFile) {
  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      SectionHeader(title = "External File Dependencies (${sf.externals.size})")
    }
    if (sf.externals.isEmpty()) {
      item {
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            Text(
              text = "No external SerializedFile dependencies declared.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(sf.externals.size) { index ->
        val ext = sf.externals[index]
        EngineeringPanel {
          Column(modifier = Modifier.padding(12.dp)) {
            KeyValueRow("File ID", "${index + 1}")
            KeyValueRow("Path", ext.pathName, useMonoValue = false)
            if (ext.guid.isNotBlank()) {
              KeyValueRow("GUID", ext.guid)
            }
            KeyValueRow("Type", "${ext.type}")
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(4.dp))
      SectionHeader(title = "Script Type References (${sf.scriptTypes.size})")
    }
    items(sf.scriptTypes.size) { idx ->
      val st = sf.scriptTypes[idx]
      EngineeringPanel {
        Column(modifier = Modifier.padding(12.dp)) {
          KeyValueRow("Script #${idx}", "FileID ${st.localSerializedFileIndex} -> PathID ${st.localIdentifierInFile}")
        }
      }
    }
  }
}

@Composable
private fun BinaryLayoutPanel(activeFile: OpenedUnityFile) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    when (val res = activeFile.parseResult) {
      is HeaderParseResult.SerializedFileHeaderInfo -> {
        val headerBytes = if (res.isLargeFileFormat) 48L else 20L
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "SerializedFile Region Map")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Header Region", "0x0 .. 0x${headerBytes.toString(16)} ($headerBytes B)")
            KeyValueRow("Metadata Region", "0x${headerBytes.toString(16)} .. 0x${(headerBytes + res.metadataSize).toString(16)} (${res.metadataSize} B)")
            KeyValueRow("Asset Data Region", "0x${res.dataOffset.toString(16)} .. 0x${res.declaredFileSize.toString(16)} (${(res.declaredFileSize - res.dataOffset).coerceAtLeast(0L)} B)")
          }
        }
      }
      is HeaderParseResult.AssetBundleHeaderInfo -> {
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "Bundle Container Profile")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Container Kind", res.signature)
            KeyValueRow("Stream / Format Ver", "v${res.formatVersion}")
            KeyValueRow("Total Declared Size", "${res.declaredFileSize} B")
            KeyValueRow("Block Codec", res.compressionHint)
          }
        }
      }
      else -> {
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "File Bounds")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Total Byte Length", "${activeFile.fileSizeBytes} B")
            KeyValueRow("First 16 Bytes", activeFile.parseResult.firstBytesHex.ifBlank { "(empty)" })
          }
        }
      }
    }
  }
}
