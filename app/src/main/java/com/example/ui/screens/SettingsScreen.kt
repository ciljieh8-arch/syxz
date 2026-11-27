package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.model.AppSettingsState
import com.example.model.ExportModePreference
import com.example.model.ThemePreference
import com.example.ui.components.EngineeringPanel
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionHeader

private val memoryLimitOptions = listOf(16, 32, 64, 128)

@Composable
fun SettingsScreen(
  settings: AppSettingsState,
  onThemeSelected: (ThemePreference) -> Unit,
  onMemoryLimitSelected: (Int) -> Unit,
  onExportModeSelected: (ExportModePreference) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showLicensesDialog by rememberSaveable { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .testTag("settings_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      SectionHeader(title = "Appearance")
      Spacer(modifier = Modifier.height(8.dp))
      EngineeringPanel {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
          ThemePreference.entries.forEachIndexed { index, option ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onThemeSelected(option) }
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("theme_option_${option.name.lowercase()}"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = settings.themePreference == option,
                onClick = null,
              )
              Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
              )
            }
            if (index < ThemePreference.entries.lastIndex) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
          }
        }
      }
    }

    item {
      SectionHeader(title = "Memory & Stream Guard")
      Spacer(modifier = Modifier.height(8.dp))
      EngineeringPanel {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
          Text(
            text = "Caps single-read allocations and non-seekable inspection buffers to prevent OutOfMemoryError on mobile RAM.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outline)
          memoryLimitOptions.forEachIndexed { index, limitMb ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onMemoryLimitSelected(limitMb) }
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("memory_limit_$limitMb"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = settings.memoryLimitMb == limitMb,
                onClick = null,
              )
              Text(
                text = "$limitMb MB buffer cap",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
              )
            }
            if (index < memoryLimitOptions.lastIndex) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
          }
        }
      }
    }

    item {
      SectionHeader(title = "Export Preferences")
      Spacer(modifier = Modifier.height(8.dp))
      EngineeringPanel {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
          ExportModePreference.entries.forEachIndexed { index, option ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onExportModeSelected(option) }
                .padding(horizontal = 14.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = settings.exportModePreference == option,
                onClick = null,
              )
              Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
              )
            }
            if (index < ExportModePreference.entries.lastIndex) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
          }
        }
      }
    }

    item {
      SectionHeader(title = "Build & Compliance")
      Spacer(modifier = Modifier.height(8.dp))
      EngineeringPanel {
        Column(modifier = Modifier.padding(14.dp)) {
          KeyValueRow("Application", "AssetDesk Mobile", useMonoValue = false)
          KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})")
          KeyValueRow("Engine Scope", "Full Local Parser, Plugins & Writer", useMonoValue = false)
          KeyValueRow("Data Processing", "100% Local On-Device", useMonoValue = false)
          Spacer(modifier = Modifier.height(10.dp))
          OutlinedButton(
            onClick = { showLicensesDialog = true },
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("open_source_licenses_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Open Source Licenses")
          }
        }
      }
    }
  }

  if (showLicensesDialog) {
    AlertDialog(
      onDismissRequest = { showLicensesDialog = false },
      title = { Text("Open Source Licenses") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "AssetDesk Mobile is built with open-source components:",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "• Kotlin Standard Library & Coroutines — Apache License 2.0\n" +
              "• AndroidX Core, Lifecycle, Activity, Navigation, DataStore — Apache License 2.0\n" +
              "• Jetpack Compose & Material 3 — Apache License 2.0\n" +
              "• XZ for Java (org.tukaani:xz) — 0BSD / Public Domain\n" +
              "• Space Grotesk, Inter, JetBrains Mono Fonts — SIL Open Font License 1.1\n" +
              "• JUnit 4 — Eclipse Public License 1.0\n" +
              "• Robolectric — MIT License",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = { showLicensesDialog = false }) {
          Text("Close")
        }
      },
    )
  }
}
