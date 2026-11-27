package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assetdesk.unity.HeaderParseResult
import com.assetdesk.unity.ReadabilityStatus
import com.example.ui.theme.CoolSteelBlue
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.MutedMint
import com.example.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AssetDeskEmblem(
  modifier: Modifier = Modifier,
  size: Dp = 36.dp,
  contentDescription: String? = null,
) {
  val frameColor = CoolSteelBlue
  val coreColor = MutedMint
  val surfaceBg = MaterialTheme.colorScheme.surfaceContainerLowest

  Box(
    modifier = modifier
      .size(size)
      .then(
        if (contentDescription != null) {
          Modifier.semantics { this.contentDescription = contentDescription }
        } else {
          Modifier
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(size)) {
      val w = this.size.width
      val h = this.size.height
      val cx = w / 2f
      val cy = h / 2f

      drawRect(color = surfaceBg)

      val outerPath = Path().apply {
        moveTo(cx, h * 0.10f)
        lineTo(w * 0.70f, h * 0.10f)
        lineTo(w * 0.86f, h * 0.26f)
        lineTo(w * 0.86f, h * 0.72f)
        lineTo(cx, h * 0.92f)
        lineTo(w * 0.14f, h * 0.72f)
        lineTo(w * 0.14f, h * 0.30f)
        close()
      }
      drawPath(
        path = outerPath,
        color = frameColor,
        style = Stroke(width = w * 0.075f, cap = StrokeCap.Square, join = StrokeJoin.Miter),
      )

      val foldPath = Path().apply {
        moveTo(w * 0.68f, h * 0.10f)
        lineTo(w * 0.68f, h * 0.28f)
        lineTo(w * 0.86f, h * 0.28f)
      }
      drawPath(
        path = foldPath,
        color = frameColor,
        style = Stroke(width = w * 0.06f, cap = StrokeCap.Square, join = StrokeJoin.Miter),
      )

      val innerRadius = w * 0.21f
      val hexPath = Path().apply {
        for (i in 0 until 6) {
          val angle = Math.toRadians((60 * i - 90).toDouble())
          val px = cx + (innerRadius * cos(angle)).toFloat()
          val py = cy + (w * 0.03f) + (innerRadius * sin(angle)).toFloat()
          if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
      }
      drawPath(
        path = hexPath,
        color = coreColor,
        style = Stroke(width = w * 0.075f, cap = StrokeCap.Square, join = StrokeJoin.Miter),
      )
      drawCircle(
        color = coreColor,
        radius = w * 0.045f,
        center = Offset(cx, cy + (w * 0.03f)),
      )
    }
  }
}

@Composable
fun StatusPill(
  status: ReadabilityStatus,
  modifier: Modifier = Modifier,
) {
  val (tint, bgTint) =
    when (status) {
      ReadabilityStatus.SUPPORTED_READ_WRITE -> MutedMint to MutedMint.copy(alpha = 0.12f)
      ReadabilityStatus.READ_ONLY -> CoolSteelBlue to CoolSteelBlue.copy(alpha = 0.14f)
      ReadabilityStatus.COMPANION_RESOURCE -> CoolSteelBlue to CoolSteelBlue.copy(alpha = 0.14f)
      ReadabilityStatus.UNSUPPORTED -> WarningAmber to WarningAmber.copy(alpha = 0.14f)
      ReadabilityStatus.CORRUPTED -> ErrorCoral to ErrorCoral.copy(alpha = 0.14f)
    }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(4.dp),
    color = bgTint,
    border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
  ) {
    Text(
      text = status.label.uppercase(Locale.ROOT),
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
      color = tint,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

@Composable
fun EngineeringPanel(
  modifier: Modifier = Modifier,
  borderColor: Color = MaterialTheme.colorScheme.outline,
  containerColor: Color = MaterialTheme.colorScheme.surface,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
  ) {
    content()
  }
}

@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title.uppercase(Locale.ROOT),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (trailing != null) {
      trailing()
    }
  }
}

@Composable
fun KeyValueRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  useMonoValue: Boolean = true,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(0.44f),
    )
    Spacer(modifier = Modifier.width(12.dp))
    Text(
      text = value,
      style = if (useMonoValue) MonoTextStyle else MaterialTheme.typography.bodySmall,
      color = valueColor,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(0.56f),
    )
  }
}

@Composable
fun HeaderTelemetryPanel(
  parseResult: HeaderParseResult,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (parseResult) {
      is HeaderParseResult.SerializedFileHeaderInfo -> {
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "SerializedFile Header")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Format Version", "v${parseResult.formatVersion}")
            KeyValueRow("Engine Target", parseResult.unityVersionHint, useMonoValue = false)
            KeyValueRow("Byte Order", parseResult.endianness.name.replace('_', ' '))
            KeyValueRow("Large File (64-bit)", if (parseResult.isLargeFileFormat) "Yes (v22+)" else "No (32-bit)")
            KeyValueRow("Metadata Size", "${formatByteSize(parseResult.metadataSize)} (${parseResult.metadataSize} B)")
            KeyValueRow("Data Offset", "0x${parseResult.dataOffset.toString(16).uppercase(Locale.ROOT)} (${parseResult.dataOffset} B)")
            KeyValueRow("Declared File Size", "${formatByteSize(parseResult.declaredFileSize)} (${parseResult.declaredFileSize} B)")
            KeyValueRow("Actual File Size", "${formatByteSize(parseResult.actualFileSize)} (${parseResult.actualFileSize} B)")
            KeyValueRow("Header Magic Hex", parseResult.firstBytesHex)
          }
        }

        if (parseResult.readOnlyReason != null) {
          NoticeBanner(
            title = "Read-Only Safeguard",
            message = parseResult.readOnlyReason ?: "",
            tint = CoolSteelBlue,
            icon = Icons.Outlined.Lock,
          )
        }
        parseResult.warnings.forEach { warning ->
          NoticeBanner(
            title = "Header Warning",
            message = warning,
            tint = WarningAmber,
            icon = Icons.Outlined.WarningAmber,
          )
        }
      }

      is HeaderParseResult.AssetBundleHeaderInfo -> {
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "AssetBundle Header")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Signature", parseResult.signature)
            KeyValueRow("Format Version", "v${parseResult.formatVersion}")
            KeyValueRow("Player Version", parseResult.unityPlayerVersion)
            KeyValueRow("Engine Revision", parseResult.unityEngineRevision)
            KeyValueRow("Compression Codec", parseResult.compressionHint)
            if (parseResult.compressedBlocksInfoSize != null && parseResult.uncompressedBlocksInfoSize != null) {
              KeyValueRow(
                "Blocks Info (Comp/Raw)",
                "${parseResult.compressedBlocksInfoSize} B / ${parseResult.uncompressedBlocksInfoSize} B",
              )
            }
            if (parseResult.blocksInfoLocationHint != null) {
              KeyValueRow("Blocks Info Location", parseResult.blocksInfoLocationHint!!, useMonoValue = false)
            }
            if (parseResult.flags != null) {
              KeyValueRow("Header Flags", "0x${parseResult.flags!!.toString(16).uppercase(Locale.ROOT)}")
            }
            if (parseResult.headerSize != null) {
              KeyValueRow("Stream Header Size", "${parseResult.headerSize} B")
            }
            if (parseResult.levelCount != null) {
              KeyValueRow("Level Count", "${parseResult.levelCount}")
            }
            KeyValueRow("Declared Size", "${formatByteSize(parseResult.declaredFileSize)} (${parseResult.declaredFileSize} B)")
            KeyValueRow("Actual File Size", "${formatByteSize(parseResult.actualFileSize)} (${parseResult.actualFileSize} B)")
            KeyValueRow("Header Magic Hex", parseResult.firstBytesHex)
          }
        }

        if (parseResult.readOnlyReason != null) {
          NoticeBanner(
            title = "Read-Only Mode",
            message = parseResult.readOnlyReason ?: "",
            tint = CoolSteelBlue,
            icon = Icons.Outlined.Lock,
          )
        }
        parseResult.warnings.forEach { warning ->
          NoticeBanner(
            title = "Bundle Warning",
            message = warning,
            tint = WarningAmber,
            icon = Icons.Outlined.WarningAmber,
          )
        }
      }

      is HeaderParseResult.CompanionResourceInfo -> {
        EngineeringPanel {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "Streaming Companion Resource")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("File Extension", parseResult.extension)
            KeyValueRow("Actual File Size", "${formatByteSize(parseResult.actualFileSize)} (${parseResult.actualFileSize} B)")
            KeyValueRow("First Bytes Hex", parseResult.firstBytesHex)
          }
        }
        NoticeBanner(
          title = "Companion Binary Data",
          message = parseResult.note,
          tint = CoolSteelBlue,
          icon = Icons.Outlined.Info,
        )
      }

      is HeaderParseResult.UnsupportedFormatInfo -> {
        EngineeringPanel(borderColor = WarningAmber.copy(alpha = 0.5f)) {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "Unsupported Format Detected")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Detected Type", parseResult.detectedLabel, useMonoValue = false)
            KeyValueRow("Actual File Size", "${formatByteSize(parseResult.actualFileSize)} (${parseResult.actualFileSize} B)")
            KeyValueRow("First Bytes Hex", parseResult.firstBytesHex.ifBlank { "(none)" })
          }
        }
        NoticeBanner(
          title = "Parser Rejection Reason",
          message = parseResult.reason,
          tint = WarningAmber,
          icon = Icons.Outlined.WarningAmber,
        )
      }

      is HeaderParseResult.CorruptedFileInfo -> {
        EngineeringPanel(borderColor = ErrorCoral.copy(alpha = 0.55f)) {
          Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title = "Validation Failure")
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow("Suspected Format", parseResult.suspectedKind.displayName, useMonoValue = false)
            KeyValueRow("Actual File Size", "${formatByteSize(parseResult.actualFileSize)} (${parseResult.actualFileSize} B)")
            KeyValueRow("First Bytes Hex", parseResult.firstBytesHex.ifBlank { "(empty)" })
          }
        }
        NoticeBanner(
          title = "Corrupted or Truncated Header",
          message = parseResult.errorMessage,
          tint = ErrorCoral,
          icon = Icons.Outlined.ErrorOutline,
        )
      }
    }
  }
}

@Composable
fun NoticeBanner(
  title: String,
  message: String,
  tint: Color,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = tint.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp),
      )
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          color = tint,
        )
        Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

fun formatByteSize(bytes: Long): String {
  if (bytes < 0) return "Unknown"
  if (bytes < 1024L) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
  val mb = kb / 1024.0
  if (mb < 1024.0) return String.format(Locale.ROOT, "%.2f MB", mb)
  val gb = mb / 1024.0
  return String.format(Locale.ROOT, "%.2f GB", gb)
}

fun formatTimestamp(epochMs: Long): String {
  if (epochMs <= 0L) return "Unknown"
  val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
  return formatter.format(Date(epochMs))
}
