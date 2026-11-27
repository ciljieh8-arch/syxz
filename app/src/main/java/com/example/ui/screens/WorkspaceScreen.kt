package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetdesk.unity.HeaderParseResult
import com.example.R
import com.example.model.OpenedUnityFile
import com.example.model.RecentFileEntry
import com.example.ui.components.AssetDeskEmblem
import com.example.ui.components.EngineeringPanel
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.MonoTextStyle
import com.example.ui.viewmodel.AssetDeskUiState

@Composable
fun WorkspaceScreen(
  uiState: AssetDeskUiState,
  onOpenSingleFile: () -> Unit,
  onOpenBundleFile: () -> Unit,
  onOpenMultipleFiles: () -> Unit,
  onInspectActiveFile: () -> Unit,
  onImportInstallerPackage: () -> Unit,
  onReopenRecent: (RecentFileEntry) -> Unit,
  onRemoveRecent: (String) -> Unit,
  onClearRecents: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .testTag("workspace_screen"),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        WorkspaceHeaderBanner()
        PrimaryActionsPanel(
          hasActiveSerializedFile = uiState.activeFile?.parsedSerializedFile != null,
          onOpenSingleFile = onOpenSingleFile,
          onOpenBundleFile = onOpenBundleFile,
          onOpenMultipleFiles = onOpenMultipleFiles,
          onImportInstallerPackage = onImportInstallerPackage,
        )
      }
    }

    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SectionHeader(
          title = "Last Parsed File",
          trailing = if (uiState.activeFile != null) {
            {
              TextButton(
                onClick = onInspectActiveFile,
                modifier = Modifier.testTag("workspace_inspect_active_button"),
              ) {
                Text("Open Inspector")
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                  imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          } else {
            null
          },
        )
        ActiveFileSummaryPanel(
          activeFile = uiState.activeFile,
          onInspect = onInspectActiveFile,
        )
      }
    }

    item {
      SectionHeader(
        title = "Recent Files (${uiState.recentFiles.size})",
        trailing = if (uiState.recentFiles.isNotEmpty()) {
          {
            TextButton(
              onClick = onClearRecents,
              modifier = Modifier.testTag("clear_recent_files_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Clear")
            }
          }
        } else {
          null
        },
      )
    }

    if (uiState.recentFiles.isEmpty()) {
      item {
        EngineeringPanel {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.History,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = "No recent files opened",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Open a .assets, .bundle, .unity3d, .asset, .resS, or .resource file via Storage Access Framework.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    } else {
      items(
        items = uiState.recentFiles,
        key = { it.uriString },
      ) { recent ->
        RecentFileRow(
          entry = recent,
          onClick = { onReopenRecent(recent) },
          onRemove = { onRemoveRecent(recent.uriString) },
        )
      }
    }
  }
}

@Composable
private fun WorkspaceHeaderBanner() {
  EngineeringPanel(containerColor = MaterialTheme.colorScheme.surface) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        AssetDeskEmblem(
          size = 42.dp,
          contentDescription = stringResource(R.string.cd_app_emblem),
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Local Unity Asset & Bundle Workbench",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(modifier = Modifier.height(12.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "ENGINE STATUS",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "PARSER · TYPETREE · PLUGINS · WRITER ACTIVE",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun PrimaryActionsPanel(
  hasActiveSerializedFile: Boolean,
  onOpenSingleFile: () -> Unit,
  onOpenBundleFile: () -> Unit,
  onOpenMultipleFiles: () -> Unit,
  onImportInstallerPackage: () -> Unit,
) {
  EngineeringPanel {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SectionHeader(title = "Storage Access Framework")
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = onOpenSingleFile,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("open_file_button"),
          shape = MaterialTheme.shapes.small,
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
        ) {
          Icon(
            imageVector = Icons.Outlined.FileOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.action_open_file))
        }

        OutlinedButton(
          onClick = onOpenBundleFile,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("open_bundle_button"),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)),
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.secondary,
          ),
        ) {
          Icon(
            imageVector = Icons.Outlined.Archive,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.action_open_bundle))
        }
      }

      OutlinedButton(
        onClick = onOpenMultipleFiles,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("open_multiple_button"),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Icon(
          imageVector = Icons.Outlined.FolderCopy,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Select Multiple Unity Files (.assets / .bundle / .unity3d / .resS)")
      }

      if (hasActiveSerializedFile) {
        OutlinedButton(
          onClick = onImportInstallerPackage,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("workspace_import_package_button"),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        ) {
          Icon(
            imageVector = Icons.Outlined.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Apply Installer Package (.adpkg) to Active File")
        }
      }

      Text(
        text = "Zero storage permissions required. All parsing, decompression, export, and saving happen 100% locally via Android SAF.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ActiveFileSummaryPanel(
  activeFile: OpenedUnityFile?,
  onInspect: () -> Unit,
) {
  if (activeFile == null) {
    EngineeringPanel {
      Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Description,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = "No active file loaded",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Use Open File or Open Bundle above to inspect SerializedFiles, AssetBundles, and TypeTrees.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    return
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onInspect)
      .testTag("active_file_summary_card"),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = activeFile.fileName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        StatusPill(status = activeFile.readability)
      }
      Spacer(modifier = Modifier.height(8.dp))
      KeyValueRow("Format", activeFile.kind.displayName, useMonoValue = false)
      KeyValueRow("Size", "${formatByteSize(activeFile.fileSizeBytes)} (${activeFile.fileSizeBytes} B)")
      val sf = activeFile.parsedSerializedFile
      if (sf != null) {
        KeyValueRow("Objects / Externals", "${sf.objects.size} assets · ${sf.externals.size} external refs")
        if (activeFile.activeBundleEntryPath != null) {
          KeyValueRow("Active Bundle Entry", activeFile.activeBundleEntryPath)
        }
      }
      val bundle = activeFile.parsedAssetBundle
      if (bundle != null) {
        KeyValueRow("Bundle Entries", "${bundle.directoryEntries.size} internal files · ${bundle.blocks.size} blocks")
      }
      if (activeFile.hasUnsavedChanges) {
        KeyValueRow("Session Status", "Staged edits ready (Save As)", valueColor = MaterialTheme.colorScheme.primary)
      }
      when (val result = activeFile.parseResult) {
        is HeaderParseResult.SerializedFileHeaderInfo -> {
          KeyValueRow("Version / Endian", "v${result.formatVersion} · ${result.endianness.name}")
          KeyValueRow("Metadata / DataOffset", "${result.metadataSize} B / 0x${result.dataOffset.toString(16)}")
        }
        is HeaderParseResult.AssetBundleHeaderInfo -> {
          KeyValueRow("Engine / Compression", "${result.unityEngineRevision} · ${result.compressionHint}")
        }
        is HeaderParseResult.CompanionResourceInfo -> {
          KeyValueRow("Extension", result.extension)
        }
        is HeaderParseResult.UnsupportedFormatInfo -> {
          KeyValueRow("Detected", result.detectedLabel, useMonoValue = false)
        }
        is HeaderParseResult.CorruptedFileInfo -> {
          KeyValueRow("Validation", result.errorMessage, useMonoValue = false, valueColor = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}

@Composable
private fun RecentFileRow(
  entry: RecentFileEntry,
  onClick: () -> Unit,
  onRemove: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .testTag("recent_file_item"),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = entry.fileName,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${entry.formatLabel} · ${formatByteSize(entry.fileSizeBytes)} · ${entry.statusLabel}",
          style = MonoTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "Opened ${formatTimestamp(entry.lastOpenedEpochMs)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(
        onClick = onRemove,
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Close,
          contentDescription = stringResource(R.string.cd_remove_item),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
