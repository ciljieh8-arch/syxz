package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.assetdesk.plugins.AssetPluginRegistry
import com.example.ui.components.EngineeringPanel
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SectionHeader
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.MutedMint

@Composable
fun PluginsScreen(
  modifier: Modifier = Modifier,
) {
  val plugins = AssetPluginRegistry.allPlugins

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .testTag("plugins_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      NoticeBanner(
        title = "Asset Plugin Registry (${plugins.size} Active)",
        message = "Plugins execute locally inside the Object Inspector's Plugin tab when inspecting matching ClassIDs (Texture2D, AudioClip, Mesh, TextAsset, MovieTexture, TerrainData, SubstanceArchive, UMAMesh).",
        tint = MutedMint,
        icon = Icons.Outlined.Extension,
      )
    }

    item {
      Spacer(modifier = Modifier.height(2.dp))
      SectionHeader(title = "Registered Asset Handlers (${plugins.size})")
    }

    items(plugins, key = { it.id }) { plugin ->
      EngineeringPanel(
        modifier = Modifier.testTag("plugin_card_${plugin.id}"),
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = plugin.displayName,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MutedMint.copy(alpha = 0.14f),
              border = BorderStroke(1.dp, MutedMint.copy(alpha = 0.45f)),
            ) {
              Text(
                text = "ACTIVE",
                style = MaterialTheme.typography.labelSmall,
                color = MutedMint,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = plugin.targetSummary,
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = plugin.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
