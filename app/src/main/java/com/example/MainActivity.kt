package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.KeyValueRow
import com.example.ui.navigation.AppDestination
import com.example.ui.screens.FilesScreen
import com.example.ui.screens.InspectorScreen
import com.example.ui.screens.PluginsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.AssetDeskTheme
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.MutedMint
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.AssetDeskViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val viewModel: AssetDeskViewModel = viewModel()
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()

      AssetDeskTheme(themePreference = uiState.settings.themePreference) {
        AssetDeskAppShell(viewModel = viewModel)
      }
    }
  }
}

@Composable
fun AssetDeskAppShell(
  viewModel: AssetDeskViewModel,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route ?: AppDestination.WORKSPACE.route

  val singleFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      viewModel.openUris(listOf(uri))
    }
  }

  val multiFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris ->
    if (uris.isNotEmpty()) {
      viewModel.openUris(uris)
      navController.navigate(AppDestination.FILES.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
    }
  }

  val saveDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*"),
  ) { uri ->
    if (uri != null) {
      viewModel.completePendingExport(uri)
    } else {
      viewModel.cancelPendingExport()
    }
  }

  val importRawLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) viewModel.importRawIntoSelectedObject(uri)
  }

  val importDumpLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) viewModel.importDumpFromUriIntoSelectedObject(uri)
  }

  val importTextureLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) viewModel.importTextureReplacementFromUri(uri)
  }

  val importPackageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) viewModel.inspectInstallerPackageUri(uri)
  }

  val pendingExport = uiState.pendingExport
  LaunchedEffect(pendingExport) {
    if (pendingExport != null) {
      saveDocumentLauncher.launch(pendingExport.suggestedFileName)
    }
  }

  val navigateToTab: (AppDestination) -> Unit = { dest ->
    navController.navigate(dest.route) {
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      bottomBar = {
        if (!useNavigationRail) {
          NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
          ) {
            AppDestination.entries.forEach { dest ->
              val selected = currentRoute == dest.route
              NavigationBarItem(
                selected = selected,
                onClick = { navigateToTab(dest) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                  )
                },
                label = {
                  Text(
                    text = dest.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                      fontSize = 10.sp,
                      letterSpacing = (-0.2).sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (useNavigationRail) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(),
          ) {
            AppDestination.entries.forEach { dest ->
              val selected = currentRoute == dest.route
              NavigationRailItem(
                selected = selected,
                onClick = { navigateToTab(dest) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                  )
                },
                label = { Text(dest.label) },
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
          val progress = uiState.parsingProgress
          if (progress != null) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("parsing_progress_bar"),
            ) {
              Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "Processing (${progress.currentIndex}/${progress.totalFiles}): ${progress.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  TextButton(
                    onClick = { viewModel.cancelParsing() },
                    modifier = Modifier.testTag("cancel_parsing_button"),
                  ) {
                    Text("Cancel")
                  }
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
          }

          val statusMessage = uiState.statusMessage
          if (statusMessage != null) {
            Surface(
              color = MutedMint.copy(alpha = 0.14f),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("status_message_banner"),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = statusMessage,
                  style = MaterialTheme.typography.bodySmall,
                  color = MutedMint,
                  modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::dismissStatus) {
                  Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss status",
                    tint = MutedMint,
                  )
                }
              }
            }
          }

          NavHost(
            navController = navController,
            startDestination = AppDestination.WORKSPACE.route,
            modifier = Modifier.weight(1f),
          ) {
            composable(AppDestination.WORKSPACE.route) {
              WorkspaceScreen(
                uiState = uiState,
                onOpenSingleFile = { singleFilePicker.launch(arrayOf("*/*")) },
                onOpenBundleFile = { singleFilePicker.launch(arrayOf("*/*")) },
                onOpenMultipleFiles = { multiFilePicker.launch(arrayOf("*/*")) },
                onInspectActiveFile = { navigateToTab(AppDestination.INSPECTOR) },
                onImportInstallerPackage = { importPackageLauncher.launch(arrayOf("*/*")) },
                onReopenRecent = { entry ->
                  viewModel.reopenRecentFile(entry)
                  navigateToTab(AppDestination.INSPECTOR)
                },
                onRemoveRecent = viewModel::removeRecentFile,
                onClearRecents = viewModel::clearRecentFiles,
              )
            }
            composable(AppDestination.FILES.route) {
              FilesScreen(
                uiState = uiState,
                onSelectFile = viewModel::selectSessionFile,
                onOpenAndInspect = { fileId ->
                  viewModel.selectSessionFile(fileId)
                  navigateToTab(AppDestination.INSPECTOR)
                },
                onRemoveFile = viewModel::removeSessionFile,
                onClearAll = viewModel::clearSessionFiles,
                onAddMoreFiles = { multiFilePicker.launch(arrayOf("*/*")) },
                onOpenBundleEntry = { entry ->
                  viewModel.openBundleEntry(entry)
                  navigateToTab(AppDestination.INSPECTOR)
                },
                onExportBundleEntry = viewModel::prepareExportBundleEntry,
                onRebuildBundleAs = viewModel::prepareRebuildBundleAs,
              )
            }
            composable(AppDestination.INSPECTOR.route) {
              InspectorScreen(
                uiState = uiState,
                onSelectFile = viewModel::selectSessionFile,
                onOpenFile = { singleFilePicker.launch(arrayOf("*/*")) },
                onSearchQueryChange = viewModel::updateSearchQuery,
                onTypeFilterSelected = viewModel::updateTypeFilter,
                onSortSelected = viewModel::updateSort,
                onInspectObject = viewModel::inspectObject,
                onCloseObjectDetail = viewModel::closeObjectDetail,
                onNavigateToPPtr = viewModel::navigateToPPtr,
                onExportRawObject = viewModel::prepareExportRawObject,
                onExportDumpObject = viewModel::prepareExportDumpObject,
                onExportPluginOutput = viewModel::prepareExportPluginOutput,
                onImportRawObject = { importRawLauncher.launch(arrayOf("*/*")) },
                onImportDumpFile = { importDumpLauncher.launch(arrayOf("text/plain", "*/*")) },
                onImportDumpText = viewModel::importDumpTextIntoSelectedObject,
                onImportTextReplacement = viewModel::importTextReplacement,
                onImportTextureReplacement = { importTextureLauncher.launch(arrayOf("image/*", "*/*")) },
                onSaveSerializedFileAs = viewModel::prepareSaveSerializedFileAs,
                onCreateInstallerPackage = viewModel::prepareCreateInstallerPackage,
                onImportInstallerPackage = { importPackageLauncher.launch(arrayOf("*/*")) },
              )
            }
            composable(AppDestination.PLUGINS.route) {
              PluginsScreen()
            }
            composable(AppDestination.SETTINGS.route) {
              SettingsScreen(
                settings = uiState.settings,
                onThemeSelected = viewModel::setThemePreference,
                onMemoryLimitSelected = viewModel::setMemoryLimitMb,
                onExportModeSelected = viewModel::setExportModePreference,
              )
            }
          }
        }
      }
    }
  }

  val pkgPreview = uiState.pendingPackagePreview
  val pkgValidation = uiState.pendingPackageValidation
  if (pkgPreview != null && pkgValidation != null) {
    AlertDialog(
      onDismissRequest = viewModel::dismissInstallerPackagePreview,
      title = { Text("Installer Package: ${pkgPreview.packageName}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          KeyValueRow("Target File", pkgPreview.baseFileName, useMonoValue = false)
          KeyValueRow("Version / Endian", "v${pkgPreview.baseFormatVersion} · ${pkgPreview.baseEndianness}")
          KeyValueRow("Matched Assets", "${pkgValidation.matchedObjectCount} / ${pkgPreview.changes.size}")

          pkgValidation.errors.forEach { err ->
            Text(text = "Error: $err", style = MaterialTheme.typography.bodySmall, color = ErrorCoral)
          }
          pkgValidation.warnings.forEach { warn ->
            Text(text = "Warning: $warn", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
          }

          HorizontalDivider()
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(pkgPreview.changes) { change ->
              Text(
                text = "• PathID ${change.pathId} (${change.className}) ${change.assetName} [${change.byteSize} B · sha=${change.sha256.take(10)}]",
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = viewModel::confirmApplyInstallerPackage,
          enabled = pkgValidation.isCompatible,
          modifier = Modifier.testTag("confirm_apply_adpkg_button"),
        ) {
          Text("Apply Changes")
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::dismissInstallerPackagePreview) {
          Text("Cancel")
        }
      },
    )
  }

  val errorMessage = uiState.errorMessage
  if (errorMessage != null) {
    AlertDialog(
      onDismissRequest = viewModel::dismissError,
      title = { Text("AssetDesk Operation Error") },
      text = {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
        )
      },
      confirmButton = {
        TextButton(onClick = viewModel::dismissError) {
          Text("OK")
        }
      },
    )
  }
}
