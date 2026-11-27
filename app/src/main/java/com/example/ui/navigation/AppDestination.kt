package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
  val route: String,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  WORKSPACE(
    route = "workspace",
    label = "Workspace",
    selectedIcon = Icons.Filled.Terminal,
    unselectedIcon = Icons.Outlined.Terminal,
    testTag = "nav_workspace",
  ),
  FILES(
    route = "files",
    label = "Files",
    selectedIcon = Icons.Filled.FolderOpen,
    unselectedIcon = Icons.Outlined.FolderOpen,
    testTag = "nav_files",
  ),
  INSPECTOR(
    route = "inspector",
    label = "Inspector",
    selectedIcon = Icons.Filled.AccountTree,
    unselectedIcon = Icons.Outlined.AccountTree,
    testTag = "nav_inspector",
  ),
  PLUGINS(
    route = "plugins",
    label = "Plugins",
    selectedIcon = Icons.Filled.Extension,
    unselectedIcon = Icons.Outlined.Extension,
    testTag = "nav_plugins",
  ),
  SETTINGS(
    route = "settings",
    label = "Settings",
    selectedIcon = Icons.Filled.Settings,
    unselectedIcon = Icons.Outlined.Settings,
    testTag = "nav_settings",
  ),
}
