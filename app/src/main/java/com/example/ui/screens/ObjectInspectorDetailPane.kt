package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetdesk.model.AssetFieldNode
import com.assetdesk.model.ObjectStatus
import com.assetdesk.model.PPtrReference
import com.assetdesk.model.TypeTreeNode
import com.assetdesk.model.TypeTreeSource
import com.assetdesk.plugins.PluginInspectionResult
import com.example.model.SelectedObjectDetailState
import com.example.ui.components.EngineeringPanel
import com.example.ui.components.KeyValueRow
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatByteSize
import com.example.ui.theme.CoolSteelBlue
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.MutedMint
import com.example.ui.theme.WarningAmber
import java.util.Locale

@Composable
fun ObjectInspectorDetailPane(
  detail: SelectedObjectDetailState,
  isReadOnly: Boolean,
  readOnlyReason: String?,
  onClose: () -> Unit,
  onNavigateToPPtr: (PPtrReference) -> Unit,
  onExportRaw: () -> Unit,
  onExportDump: () -> Unit,
  onExportPlugin: () -> Unit,
  onImportRaw: () -> Unit,
  onImportDumpFile: () -> Unit,
  onImportDumpText: (String) -> Unit,
  onImportTextReplacement: (String) -> Unit,
  onImportTexture: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onClose)

  var selectedSubTab by rememberSaveable(detail.objectInfo.pathId) { mutableIntStateOf(0) }
  var showDumpEditorDialog by rememberSaveable { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("object_detail_pane"),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(
          onClick = onClose,
          modifier = Modifier
            .size(48.dp)
            .testTag("object_detail_back_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back to Asset List",
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = detail.objectInfo.assetName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${detail.objectInfo.className} · FileID ${detail.objectInfo.fileId} · PathID ${detail.objectInfo.pathId} · ${detail.objectInfo.effectiveSize} B",
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        ObjectStatusBadge(status = detail.objectInfo.status)
        Spacer(modifier = Modifier.width(8.dp))
      }
    }

    TabRow(
      selectedTabIndex = selectedSubTab,
      containerColor = MaterialTheme.colorScheme.surface,
    ) {
      Tab(
        selected = selectedSubTab == 0,
        onClick = { selectedSubTab = 0 },
        text = { Text("View Data") },
        modifier = Modifier.testTag("object_tab_data"),
      )
      Tab(
        selected = selectedSubTab == 1,
        onClick = { selectedSubTab = 1 },
        text = { Text("TypeTree") },
        modifier = Modifier.testTag("object_tab_typetree"),
      )
      Tab(
        selected = selectedSubTab == 2,
        onClick = { selectedSubTab = 2 },
        text = { Text("Plugin") },
        modifier = Modifier.testTag("object_tab_plugin"),
      )
      Tab(
        selected = selectedSubTab == 3,
        onClick = { selectedSubTab = 3 },
        text = { Text("Raw / Dump") },
        modifier = Modifier.testTag("object_tab_raw_dump"),
      )
    }

    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      if (isReadOnly && readOnlyReason != null) {
        item {
          NoticeBanner(
            title = "Read-Only File",
            message = readOnlyReason,
            tint = CoolSteelBlue,
            icon = Icons.Outlined.Lock,
          )
        }
      }

      when (selectedSubTab) {
        0 -> item {
          ViewDataSubTab(
            detail = detail,
            onNavigateToPPtr = onNavigateToPPtr,
          )
        }
        1 -> item {
          TypeTreeSchemaSubTab(detail = detail)
        }
        2 -> item {
          PluginSubTab(
            detail = detail,
            isReadOnly = isReadOnly,
            onExportPlugin = onExportPlugin,
            onImportTextReplacement = onImportTextReplacement,
            onImportTexture = onImportTexture,
          )
        }
        3 -> item {
          RawDumpSubTab(
            detail = detail,
            isReadOnly = isReadOnly,
            onExportRaw = onExportRaw,
            onExportDump = onExportDump,
            onImportRaw = onImportRaw,
            onImportDumpFile = onImportDumpFile,
            onOpenDumpEditor = { showDumpEditorDialog = true },
          )
        }
      }
    }
  }

  if (showDumpEditorDialog) {
    DumpEditorDialog(
      initialDump = detail.dumpTextPreview.orEmpty(),
      onDismiss = { showDumpEditorDialog = false },
      onApply = { edited ->
        showDumpEditorDialog = false
        onImportDumpText(edited)
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewDataSubTab(
  detail: SelectedObjectDetailState,
  onNavigateToPPtr: (PPtrReference) -> Unit,
) {
  val decoded = detail.decodedData
  val expandedNodes = remember(detail.objectInfo.pathId) { mutableStateMapOf<String, Boolean>() }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    EngineeringPanel {
      Column(modifier = Modifier.padding(14.dp)) {
        SectionHeader(title = "Object Metadata & Validation")
        Spacer(modifier = Modifier.height(8.dp))
        KeyValueRow("Class ID / Type ID", "${detail.objectInfo.classId} (${detail.objectInfo.className}) / ${detail.objectInfo.typeId}")
        KeyValueRow("Offset / Byte Size", "0x${detail.objectInfo.byteStart.toString(16)} / ${detail.objectInfo.effectiveSize} B")
        KeyValueRow("TypeTree Source", detail.typeTreeSource.label, useMonoValue = false)
        KeyValueRow(
          "Decoded Size Check",
          "${decoded.bytesConsumed} / ${decoded.expectedBytes} B " +
            if (decoded.isSizeExact) "(Exact)" else "(Partial)",
          valueColor = if (decoded.isSizeExact) MutedMint else WarningAmber,
        )
        val containerPath = detail.objectInfo.containerPath
        if (containerPath != null) {
          KeyValueRow("Container Path", containerPath, useMonoValue = false)
        }
      }
    }

    val decodeWarning = decoded.decodeWarning
    if (decodeWarning != null) {
      NoticeBanner(
        title = "Decoder Notice",
        message = decodeWarning,
        tint = WarningAmber,
        icon = Icons.Outlined.WarningAmber,
      )
    }

    val nonNullPtrs = decoded.pptrReferences.filterNot { it.isNull }
    if (nonNullPtrs.isNotEmpty()) {
      EngineeringPanel {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SectionHeader(title = "PPtr References (${nonNullPtrs.size})")
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            nonNullPtrs.forEach { pptr ->
              AssistChip(
                onClick = { onNavigateToPPtr(pptr) },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                },
                label = {
                  Text(
                    text = "${pptr.targetTypeHint} [${pptr.fileId}:${pptr.pathId}]",
                    style = MonoTextStyle,
                  )
                },
                modifier = Modifier.testTag("pptr_chip_${pptr.fileId}_${pptr.pathId}"),
              )
            }
          }
        }
      }
    }

    EngineeringPanel {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SectionHeader(title = "Tree View")
        val root = decoded.rootNode
        if (root == null) {
          Text(
            text = "No TypeTree or built-in schema available for ClassID ${detail.objectInfo.classId}. Inspect raw bytes in Raw / Dump.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          FieldTreeNodeView(
            node = root,
            nodePath = "root",
            depth = 0,
            expandedMap = expandedNodes,
            onNavigateToPPtr = onNavigateToPPtr,
          )
        }
      }
    }
  }
}

@Composable
private fun FieldTreeNodeView(
  node: AssetFieldNode,
  nodePath: String,
  depth: Int,
  expandedMap: MutableMap<String, Boolean>,
  onNavigateToPPtr: (PPtrReference) -> Unit,
) {
  val hasChildren = node.children.isNotEmpty()
  val isExpanded = expandedMap[nodePath] ?: (depth < 2)

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = (depth * 12).dp, top = 3.dp, bottom = 3.dp)
        .then(
          if (hasChildren) {
            Modifier.clickable { expandedMap[nodePath] = !isExpanded }
          } else Modifier,
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (hasChildren) {
        Icon(
          imageVector = if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
      } else {
        Spacer(modifier = Modifier.width(16.dp))
      }
      Spacer(modifier = Modifier.width(4.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "${node.typeName} ${node.fieldName}",
          style = MonoTextStyle.copy(fontWeight = FontWeight.Medium),
          color = CoolSteelBlue,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (node.valueText.isNotEmpty()) {
          Text(
            text = node.valueText,
            style = MonoTextStyle,
            color = if (node.pptr != null && !node.pptr!!.isNull) MutedMint else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (node.pptr != null && !node.pptr!!.isNull) {
        TextButton(
          onClick = { onNavigateToPPtr(node.pptr!!) },
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Follow")
        }
      } else {
        Text(
          text = "+0x${node.byteOffset.toString(16)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (hasChildren && isExpanded) {
      val visibleChildren = if (node.children.size > 150) node.children.take(150) else node.children
      visibleChildren.forEachIndexed { idx, child ->
        FieldTreeNodeView(
          node = child,
          nodePath = "$nodePath/$idx",
          depth = depth + 1,
          expandedMap = expandedMap,
          onNavigateToPPtr = onNavigateToPPtr,
        )
      }
      if (node.children.size > 150) {
        Text(
          text = "... ${node.children.size - 150} additional child nodes truncated for UI responsiveness (export Dump for full list).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = ((depth + 1) * 12).dp, top = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun TypeTreeSchemaSubTab(detail: SelectedObjectDetailState) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    EngineeringPanel {
      Column(modifier = Modifier.padding(14.dp)) {
        SectionHeader(title = "TypeTree Schema Info")
        Spacer(modifier = Modifier.height(8.dp))
        KeyValueRow("Schema Source", detail.typeTreeSource.label, useMonoValue = false)
        KeyValueRow("ClassID / Name", "${detail.objectInfo.classId} (${detail.objectInfo.className})")
      }
    }

    EngineeringPanel {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        SectionHeader(title = "Field Hierarchy")
        val schema = detail.typeTreeNode
        if (schema == null) {
          Text(
            text = "No TypeTree embedded in file and no built-in schema registered for ClassID ${detail.objectInfo.classId}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          TypeTreeSchemaNodeRow(node = schema, depth = 0)
        }
      }
    }
  }
}

@Composable
private fun TypeTreeSchemaNodeRow(node: TypeTreeNode, depth: Int) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = (depth * 10).dp, top = 2.dp, bottom = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "${node.level} ${node.typeName} ${node.name}",
        style = MonoTextStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = buildString {
          append("sz=${node.byteSize}")
          if (node.isAligned) append(" ALIGN")
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (node.isAligned) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    node.children.forEach { child ->
      TypeTreeSchemaNodeRow(node = child, depth = depth + 1)
    }
  }
}

@Composable
private fun PluginSubTab(
  detail: SelectedObjectDetailState,
  isReadOnly: Boolean,
  onExportPlugin: () -> Unit,
  onImportTextReplacement: (String) -> Unit,
  onImportTexture: () -> Unit,
) {
  when (val result = detail.pluginResult) {
    null -> {
      NoticeBanner(
        title = "No Specialized Plugin Required",
        message = "ClassID ${detail.objectInfo.classId} (${detail.objectInfo.className}) is inspected via TypeTree View Data and Raw / Dump tabs.",
        tint = CoolSteelBlue,
        icon = Icons.Outlined.CheckCircle,
      )
    }
    is PluginInspectionResult.TexturePreview -> {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EngineeringPanel {
          Column(
            modifier = Modifier
              .padding(14.dp)
              .testTag("plugin_texture_preview"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            SectionHeader(title = "Texture2D Inspector")
            if (result.decodedBitmap != null) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 120.dp, max = 280.dp)
                  .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
                  .padding(8.dp),
                contentAlignment = Alignment.Center,
              ) {
                Image(
                  bitmap = result.decodedBitmap!!.asImageBitmap(),
                  contentDescription = "Decoded Texture2D ${result.assetName}",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxSize(),
                )
              }
            } else {
              NoticeBanner(
                title = "Preview Unavailable",
                message = result.note ?: "Texture bytes reside in an external .resS stream or unsupported GPU format.",
                tint = WarningAmber,
                icon = Icons.Outlined.WarningAmber,
              )
            }
            KeyValueRow("Dimensions", "${result.width} × ${result.height}")
            KeyValueRow("Format", "${result.formatName} (${result.formatCode})")
            KeyValueRow("Mip Count", "${result.mipCount}")
            KeyValueRow("Embedded Bytes", "${formatByteSize(result.imageDataSize.toLong())} (${result.imageDataSize} B)")
            if (result.streamPath != null) {
              KeyValueRow("Stream Data", result.streamPath!!, useMonoValue = false)
            }
            if (result.note != null && result.decodedBitmap != null) {
              Text(
                text = result.note!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = onExportPlugin,
                enabled = result.canExportPng,
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("export_texture_png_button"),
                shape = MaterialTheme.shapes.small,
              ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export PNG")
              }
              OutlinedButton(
                onClick = onImportTexture,
                enabled = result.canImportPng && !isReadOnly,
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("import_texture_png_button"),
                shape = MaterialTheme.shapes.small,
              ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import Image")
              }
            }
          }
        }
      }
    }
    is PluginInspectionResult.TextAssetPreview -> {
      var editableText by rememberSaveable(detail.objectInfo.pathId, detail.rawBytes.size) {
        mutableStateOf(result.textContent)
      }
      EngineeringPanel {
        Column(
          modifier = Modifier
            .padding(14.dp)
            .testTag("plugin_textasset_preview"),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          SectionHeader(title = "TextAsset (${result.encodingName})")
          KeyValueRow("Payload Size", "${result.payloadBytes.size} B")
          KeyValueRow("Mode", if (result.isBinaryFallback) "Binary Fallback" else "Text (${result.encodingName})", useMonoValue = false)
          OutlinedTextField(
            value = editableText,
            onValueChange = { if (result.canImportText && !isReadOnly) editableText = it },
            readOnly = !result.canImportText || isReadOnly,
            label = { Text("Script / Text Content") },
            textStyle = MonoTextStyle,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 140.dp, max = 320.dp)
              .testTag("textasset_editor_input"),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = onExportPlugin,
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("export_textasset_button"),
              shape = MaterialTheme.shapes.small,
            ) {
              Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(if (result.isBinaryFallback) "Export .bytes" else "Export .txt")
            }
            if (result.canImportText) {
              OutlinedButton(
                onClick = { onImportTextReplacement(editableText) },
                enabled = !isReadOnly,
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("apply_textasset_button"),
                shape = MaterialTheme.shapes.small,
              ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Apply Text")
              }
            }
          }
        }
      }
    }
    is PluginInspectionResult.AudioClipPreview -> {
      EngineeringPanel {
        Column(
          modifier = Modifier
            .padding(14.dp)
            .testTag("plugin_audioclip_preview"),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SectionHeader(title = "AudioClip Inspector")
          KeyValueRow("Container / Signature", result.detectedContainer, useMonoValue = false)
          KeyValueRow("Compression Format", result.compressionFormatName, useMonoValue = false)
          if (result.channels != null && result.frequencyHz != null) {
            KeyValueRow("Channels / Sample Rate", "${result.channels} ch · ${result.frequencyHz} Hz")
          }
          if (result.durationSec != null) {
            KeyValueRow("Duration", String.format(Locale.ROOT, "%.2f s", result.durationSec))
          }
          KeyValueRow("Embedded Audio Size", "${formatByteSize(result.embeddedDataSize.toLong())} (${result.embeddedDataSize} B)")
          if (result.externalResourcePath != null) {
            KeyValueRow("External Stream", "${result.externalResourcePath} (off=${result.externalOffset}, len=${result.externalSize})", useMonoValue = false)
          }
          Text(
            text = result.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = onExportPlugin,
            enabled = result.canExportAudio,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("export_audioclip_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Export Audio (.${result.exportExtension})")
          }
        }
      }
    }
    is PluginInspectionResult.MeshPreview -> {
      EngineeringPanel {
        Column(
          modifier = Modifier
            .padding(14.dp)
            .testTag("plugin_mesh_preview"),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SectionHeader(title = "Mesh Inspector")
          KeyValueRow("Vertices / Indices", "${result.vertexCount} / ${result.indexCount}")
          KeyValueRow("SubMeshes / Index Format", "${result.submeshCount} · ${result.indexFormatName}")
          KeyValueRow("Bounds", result.boundsSummary)
          KeyValueRow("Vertex Channels", result.channelsSummary, useMonoValue = false)
          Text(
            text = result.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = onExportPlugin,
            enabled = result.canExportObj,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("export_mesh_obj_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Export Wavefront .OBJ")
          }
        }
      }
    }
    is PluginInspectionResult.MetadataPluginPreview -> {
      EngineeringPanel {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SectionHeader(title = "${result.pluginName} Inspector")
          result.fields.forEach { (k, v) ->
            KeyValueRow(k, v)
          }
          Text(
            text = result.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    is PluginInspectionResult.PluginError -> {
      NoticeBanner(
        title = "${result.pluginName} Plugin Error",
        message = result.message,
        tint = ErrorCoral,
        icon = Icons.Outlined.WarningAmber,
      )
    }
  }
}

@Composable
private fun RawDumpSubTab(
  detail: SelectedObjectDetailState,
  isReadOnly: Boolean,
  onExportRaw: () -> Unit,
  onExportDump: () -> Unit,
  onImportRaw: () -> Unit,
  onImportDumpFile: () -> Unit,
  onOpenDumpEditor: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    EngineeringPanel {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        SectionHeader(title = "Export & Import Actions")
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = onExportRaw,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("export_raw_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Export Raw")
          }
          Button(
            onClick = onExportDump,
            enabled = detail.dumpTextPreview != null,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("export_dump_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Export Dump")
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = onImportRaw,
            enabled = !isReadOnly,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("import_raw_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Import Raw")
          }
          OutlinedButton(
            onClick = onImportDumpFile,
            enabled = !isReadOnly && detail.typeTreeNode != null,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("import_dump_file_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Import Dump")
          }
        }

        if (detail.dumpTextPreview != null) {
          OutlinedButton(
            onClick = onOpenDumpEditor,
            enabled = !isReadOnly,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("edit_dump_inline_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Edit & Re-Serialize Dump Inline")
          }
        }
      }
    }

    EngineeringPanel {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val previewLimit = minOf(detail.rawBytes.size, 512)
        SectionHeader(title = "Hex Preview ($previewLimit / ${detail.rawBytes.size} B)")
        Text(
          text = formatHexDump(detail.rawBytes, previewLimit),
          style = MonoTextStyle,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun DumpEditorDialog(
  initialDump: String,
  onDismiss: () -> Unit,
  onApply: (String) -> Unit,
) {
  var text by rememberSaveable { mutableStateOf(initialDump) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit Asset Dump") },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = MonoTextStyle,
        modifier = Modifier
          .fillMaxWidth()
          .height(300.dp)
          .testTag("inline_dump_editor_input"),
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onApply(text) },
        modifier = Modifier.testTag("apply_inline_dump_button"),
      ) {
        Text("Validate & Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
fun ObjectStatusBadge(status: ObjectStatus) {
  val tint = when (status) {
    ObjectStatus.VALID -> CoolSteelBlue
    ObjectStatus.MODIFIED -> MutedMint
    ObjectStatus.READ_ONLY -> WarningAmber
    ObjectStatus.OUT_OF_BOUNDS -> ErrorCoral
  }
  Surface(
    shape = RoundedCornerShape(4.dp),
    color = tint.copy(alpha = 0.14f),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
  ) {
    Text(
      text = status.label.uppercase(Locale.ROOT),
      style = MaterialTheme.typography.labelSmall,
      color = tint,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

private fun formatHexDump(bytes: ByteArray, limit: Int): String {
  if (bytes.isEmpty()) return "(empty)"
  val sb = StringBuilder()
  var off = 0
  while (off < limit) {
    sb.append(String.format(Locale.ROOT, "%04X: ", off))
    val rowEnd = minOf(off + 16, limit)
    for (i in off until off + 16) {
      if (i < rowEnd) {
        sb.append(String.format(Locale.ROOT, "%02X ", bytes[i].toInt() and 0xFF))
      } else {
        sb.append("   ")
      }
    }
    sb.append(' ')
    for (i in off until rowEnd) {
      val b = bytes[i].toInt() and 0xFF
      sb.append(if (b in 32..126) b.toChar() else '.')
    }
    if (rowEnd < limit) sb.append('\n')
    off += 16
  }
  return sb.toString()
}
