package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppSettingsState
import com.example.model.ExportModePreference
import com.example.model.RecentFileEntry
import com.example.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.assetDeskDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "assetdesk_workspace_prefs",
)

class WorkspacePreferencesRepository(
  private val context: Context,
) {

  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val MEMORY_LIMIT_MB = intPreferencesKey("memory_limit_mb")
    val EXPORT_MODE = stringPreferencesKey("export_mode")
    val RECENT_FILES_JSON = stringPreferencesKey("recent_files_json")
  }

  val settingsFlow: Flow<AppSettingsState> = context.assetDeskDataStore.data.map { prefs ->
    val theme = prefs[Keys.THEME_MODE]?.let { name ->
      ThemePreference.entries.firstOrNull { it.name == name }
    } ?: ThemePreference.DARK

    val memoryLimitMb = (prefs[Keys.MEMORY_LIMIT_MB] ?: 32).coerceIn(16, 128)

    val exportMode = prefs[Keys.EXPORT_MODE]?.let { name ->
      ExportModePreference.entries.firstOrNull { it.name == name }
    } ?: ExportModePreference.ASK_EACH_TIME

    AppSettingsState(
      themePreference = theme,
      memoryLimitMb = memoryLimitMb,
      exportModePreference = exportMode,
    )
  }

  val recentFilesFlow: Flow<List<RecentFileEntry>> = context.assetDeskDataStore.data.map { prefs ->
    decodeRecentFiles(prefs[Keys.RECENT_FILES_JSON])
  }

  suspend fun setThemePreference(preference: ThemePreference) {
    context.assetDeskDataStore.edit { prefs ->
      prefs[Keys.THEME_MODE] = preference.name
    }
  }

  suspend fun setMemoryLimitMb(limitMb: Int) {
    context.assetDeskDataStore.edit { prefs ->
      prefs[Keys.MEMORY_LIMIT_MB] = limitMb.coerceIn(16, 128)
    }
  }

  suspend fun setExportModePreference(preference: ExportModePreference) {
    context.assetDeskDataStore.edit { prefs ->
      prefs[Keys.EXPORT_MODE] = preference.name
    }
  }

  suspend fun recordRecentFile(entry: RecentFileEntry) {
    context.assetDeskDataStore.edit { prefs ->
      val current = decodeRecentFiles(prefs[Keys.RECENT_FILES_JSON]).toMutableList()
      current.removeAll { it.uriString == entry.uriString }
      current.add(0, entry)
      val trimmed = current.take(MAX_RECENT_FILES)
      prefs[Keys.RECENT_FILES_JSON] = encodeRecentFiles(trimmed)
    }
  }

  suspend fun removeRecentFile(uriString: String) {
    context.assetDeskDataStore.edit { prefs ->
      val updated = decodeRecentFiles(prefs[Keys.RECENT_FILES_JSON])
        .filterNot { it.uriString == uriString }
      prefs[Keys.RECENT_FILES_JSON] = encodeRecentFiles(updated)
    }
  }

  suspend fun clearRecentFiles() {
    context.assetDeskDataStore.edit { prefs ->
      prefs.remove(Keys.RECENT_FILES_JSON)
    }
  }

  private fun encodeRecentFiles(entries: List<RecentFileEntry>): String {
    val array = JSONArray()
    entries.forEach { item ->
      array.put(
        JSONObject().apply {
          put("uri", item.uriString)
          put("name", item.fileName)
          put("size", item.fileSizeBytes)
          put("format", item.formatLabel)
          put("status", item.statusLabel)
          put("openedAt", item.lastOpenedEpochMs)
        },
      )
    }
    return array.toString()
  }

  private fun decodeRecentFiles(rawJson: String?): List<RecentFileEntry> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
      val array = JSONArray(rawJson)
      buildList {
        for (i in 0 until array.length()) {
          val obj = array.optJSONObject(i) ?: continue
          val uri = obj.optString("uri")
          val name = obj.optString("name")
          if (uri.isBlank() || name.isBlank()) continue
          add(
            RecentFileEntry(
              uriString = uri,
              fileName = name,
              fileSizeBytes = obj.optLong("size", 0L),
              formatLabel = obj.optString("format", "Unknown"),
              statusLabel = obj.optString("status", "Unknown"),
              lastOpenedEpochMs = obj.optLong("openedAt", 0L),
            ),
          )
        }
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  companion object {
    private const val MAX_RECENT_FILES = 15
  }
}
