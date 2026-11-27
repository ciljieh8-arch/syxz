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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
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
import com.assetdesk.unity.BundleDirectoryEntry
import com.assetdesk.unity.BundleStorageBlock
import com.assetdesk.unity.HeaderParseResult
import com.assetdesk.unity.ParsedAssetBundle
import com.assetdesk.unity.UnityFileKind
import com.example.R
import com.example.model.OpenedUnityFile
import com.example.ui.components.EngineeringPanel
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.formatByteSize
import com.example.ui.theme.CoolSteelBlue
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.MutedMint
import com.example.ui.viewmodel.AssetDeskUiState

@Composable
fun FilesScreen(
  uiState: AssetDeskUiState,
  onSelectFile: (String) -> Unit,
  onOpenAndInspect: (String) -> Unit,
  onRemoveFile: (String) -> Unit,
  onClearAll: () -> Unit,
  onAddMoreFiles: () -> Unit,
  onOpenBundleEntry: (BundleDirectoryEntry) -> Unit,
  onExportBundleEntry: (BundleDirectoryEntry) -> Unit,
  onRebuildBundleAs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("files_screen"),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Session Files & Bundle Explorer",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${uiState.sessionFiles.size} file(s) loaded in memory-bounded workspace",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (uiState.sessionFiles.isNotEmpty()) {
            TextButton(
              onClick = onClearAll,
              modifier = Modifier.testTag("files_clear_all_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = stringResource(R.string.cd_clear_all),
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Clear")
            }
          }
          OutlinedButton(
            onClick = onAddMoreFiles,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("files_add_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Icon(
              imageVector = Icons.Outlined.Add,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add")
          }
        }
      }
    }

    if (uiState.sessionFiles.isEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Outlined.FolderOpen,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "No Unity Files in Session",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Select one or multiple .assets, .bundle, .unity3d, .asset, .resS, or .resource files to explore.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
          onClick = onAddMoreFiles,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("files_empty_open_button"),
        ) {
          Text("Select Unity Files")
        }
      }
    } else {
      val activeFile = uiState.activeFile
      val activeBundle = activeFile?.parsedAssetBundle

      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          SectionHeader(title = "Loaded Queue (Tap to Select & Inspect)")
        }
        items(
          items = uiState.sessionFiles,
          key = { it.id },
        ) { file ->
          SessionFileCard(
            file = file,
            isSelected = file.id == activeFile?.id,
            onSelect = { onSelectFile(file.id) },
            onInspect = { onOpenAndInspect(file.id) },
            onRemove = { onRemoveFile(file.id) },
          )
        }

        if (activeFile != null && activeBundle != null) {
          val modifiedEntryPaths = buildSet {
            addAll(activeFile.modifiedBundleEntries.keys)
            activeFile.stagedSerializedFiles.forEach { (path, sf) ->
              if (sf.objects.any { it.isModified }) add(path)
            }
          }
          item {
            Spacer(modifier = Modifier.height(6.dp))
            AssetBundleOverviewCard(
              bundle = activeBundle,
              modifiedCount = modifiedEntryPaths.size,
              canRebuildUnityFs = activeFile.kind == UnityFileKind.ASSET_BUNDLE_FS && !activeBundle.header.isReadOnly,
              onRebuildBundleAs = onRebuildBundleAs,
            )
          }

          if (activeBundle.blocks.isNotEmpty()) {
            item {
              SectionHeader(title = "Storage Blocks (${activeBundle.blocks.size})")
            }
            itemsIndexed(
              items = activeBundle.blocks,
              key = { idx, _ -> "block_$idx" },
            ) { idx, block ->
              BundleBlockRow(index = idx, block = block)
            }
          }

          item {
            SectionHeader(title = "Internal Bundle Files (${activeBundle.directoryEntries.size})")
          }
          items(
            items = activeBundle.directoryEntries,
            key = { it.path },
          ) { entry ->
            BundleDirectoryEntryRow(
              entry = entry,
              isActive = entry.path == activeFile.activeBundleEntryPath,
              isModified = entry.path in modifiedEntryPaths,
              onOpen = { onOpenBundleEntry(entry) },
              onExport = { onExportBundleEntry(entry) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SessionFileCard(
  file: OpenedUnityFile,
  isSelected: Boolean,
  onSelect: () -> Unit,
  onInspect: () -> Unit,
  onRemove: () -> Unit,
) {
  val borderColor =
    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = {
        onSelect()
        onInspect()
      })
      .testTag("session_file_card"),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = file.fileName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "${file.kind.displayName} · ${formatByteSize(file.fileSizeBytes)}",
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        StatusPill(status = file.readability)
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

      Spacer(modifier = Modifier.height(6.dp))
      val subtitle =
        when (val res = file.parseResult) {
          is HeaderParseResult.SerializedFileHeaderInfo -> {
            val count = file.parsedSerializedFile?.objects?.size ?: 0
            "Format v${res.formatVersion} · ${res.endianness.name} · $count asset(s)"
          }
          is HeaderParseResult.AssetBundleHeaderInfo -> {
            val entries = file.parsedAssetBundle?.directoryEntries?.size ?: 0
            "${res.signature} v${res.formatVersion} · ${res.compressionHint} · $entries internal file(s)"
          }
          is HeaderParseResult.CompanionResourceInfo ->
            "Streaming companion (${res.extension}) · Referenced by SerializedFile plugins"
          is HeaderParseResult.UnsupportedFormatInfo ->
            res.reason
          is HeaderParseResult.CorruptedFileInfo ->
            res.errorMessage
        }
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun AssetBundleOverviewCard(
  bundle: ParsedAssetBundle,
  modifiedCount: Int,
  canRebuildUnityFs: Boolean,
  onRebuildBundleAs: () -> Unit,
) {
  EngineeringPanel(borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)) {
    Column(
      modifier = Modifier
        .padding(14.dp)
        .testTag("bundle_explorer_panel"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Archive,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = "AssetBundle Explorer: ${bundle.fileName}",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }

      KeyValueRow("Signature / Ver", "${bundle.header.signature} v${bundle.header.formatVersion}")
      KeyValueRow("Engine / Player", "${bundle.header.unityEngineRevision} / ${bundle.header.unityPlayerVersion}")
      KeyValueRow("Blocks / Entries", "${bundle.blocks.size} blocks · ${bundle.directoryEntries.size} files")
      if (modifiedCount > 0) {
        KeyValueRow("Modified Entries", "$modifiedCount staged internal file(s)", valueColor = MutedMint)
      }

      if (canRebuildUnityFs) {
        Button(
          onClick = onRebuildBundleAs,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("rebuild_bundle_as_button"),
          shape = MaterialTheme.shapes.small,
        ) {
          Icon(
            imageVector = Icons.Outlined.Save,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            if (modifiedCount > 0) "Rebuild Modified UnityFS Bundle As..."
            else "Repack / Save UnityFS Bundle As...",
          )
        }
      } else {
        Text(
          text = "Legacy ${bundle.header.signature} bundle opened in read-only inspection mode.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun BundleBlockRow(
  index: Int,
  block: BundleStorageBlock,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = "Block #$index · ${block.compressionName}",
          style = MonoTextStyle,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "Compressed ${formatByteSize(block.compressedSize)} -> Raw ${formatByteSize(block.uncompressedSize)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = "0x${block.flags.toString(16)}",
        style = MonoTextStyle,
        color = CoolSteelBlue,
      )
    }
  }
}

@Composable
private fun BundleDirectoryEntryRow(
  entry: BundleDirectoryEntry,
  isActive: Boolean,
  isModified: Boolean,
  onOpen: () -> Unit,
  onExport: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("bundle_entry_row_${entry.path}"),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(
      width = 1.dp,
      color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    ),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = entry.path,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "Offset 0x${entry.offset.toString(16)} · ${formatByteSize(entry.size)} (${entry.size} B) · Flags 0x${entry.flags.toString(16)}",
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        val badgeText = when {
          isModified -> "MODIFIED"
          isActive -> "ACTIVE"
          else -> entry.status.uppercase()
        }
        val badgeColor = when {
          isModified -> MutedMint
          isActive -> MaterialTheme.colorScheme.primary
          else -> CoolSteelBlue
        }
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = badgeColor.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
        ) {
          Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onOpen,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("open_bundle_entry_${entry.path}"),
          shape = MaterialTheme.shapes.small,
        ) {
          Text("Open in Inspector")
        }
        OutlinedButton(
          onClick = onExport,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("export_bundle_entry_${entry.path}"),
          shape = MaterialTheme.shapes.small,
        ) {
          Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = "Extract ${entry.path}",
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Extract")
        }
      }
    }
  }
}
