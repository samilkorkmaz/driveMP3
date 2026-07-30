package com.drivemp3.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.drivemp3.player.model.LibraryScope
import com.drivemp3.player.model.SortDirection
import com.drivemp3.player.model.SortField
import com.drivemp3.player.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "drivemp3_settings")

/** Persisted user choices: which folder the library covers, and how it is sorted. */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    private val preferences: Flow<Preferences> = dataStore.data
        // A corrupt preferences file should degrade to defaults, not crash the app.
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

    /** Null means the user has not chosen a scope yet, which routes them to the picker. */
    val libraryScope: Flow<LibraryScope?> = preferences
        .map { prefs ->
            when (val key = prefs[Keys.ScopeKey]) {
                null -> null
                LibraryScope.ALL_DRIVE_KEY -> LibraryScope.AllDrive
                else -> LibraryScope.Folder(
                    folderId = key,
                    folderName = prefs[Keys.ScopeFolderName].orEmpty(),
                )
            }
        }
        .distinctUntilChanged()

    val sortOrder: Flow<SortOrder> = preferences
        .map { prefs ->
            SortOrder(
                field = prefs[Keys.SortField].toEnum(SortField.entries, SortField.CreatedTime),
                direction = prefs[Keys.SortDirection]
                    .toEnum(SortDirection.entries, SortDirection.Descending),
            )
        }
        .distinctUntilChanged()

    suspend fun setLibraryScope(scope: LibraryScope) {
        dataStore.edit { prefs ->
            prefs[Keys.ScopeKey] = scope.storageKey
            when (scope) {
                is LibraryScope.Folder -> prefs[Keys.ScopeFolderName] = scope.folderName
                LibraryScope.AllDrive -> prefs.remove(Keys.ScopeFolderName)
            }
        }
    }

    suspend fun setSortOrder(sortOrder: SortOrder) {
        dataStore.edit { prefs ->
            prefs[Keys.SortField] = sortOrder.field.name
            prefs[Keys.SortDirection] = sortOrder.direction.name
        }
    }

    private object Keys {
        val ScopeKey = stringPreferencesKey("scope_key")
        val ScopeFolderName = stringPreferencesKey("scope_folder_name")
        val SortField = stringPreferencesKey("sort_field")
        val SortDirection = stringPreferencesKey("sort_direction")
    }
}

/** Enums are stored by name, so an unknown or renamed value falls back rather than throwing. */
private fun <T : Enum<T>> String?.toEnum(values: List<T>, default: T): T =
    values.firstOrNull { it.name == this } ?: default
