package app.focale.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.focale.core.di.ApplicationScope
import app.focale.core.di.SettingsStore
import app.focale.core.model.GroupMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Lecture / écriture des réglages utilisateur. */
@Singleton
class SettingsRepository @Inject constructor(
    @SettingsStore private val dataStore: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map(::toSettings)

    /** Valeur courante, partagée par toute l'application. */
    val current: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setAmoled(enabled: Boolean) = edit { it[Keys.AMOLED] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setColorFromPhoto(enabled: Boolean) = edit { it[Keys.COLOR_FROM_PHOTO] = enabled }
    suspend fun setGridColumns(columns: Int) = edit {
        it[Keys.COLUMNS] = columns.coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS)
    }
    suspend fun setGroupMode(mode: GroupMode) = edit { it[Keys.GROUP_MODE] = mode.name }
    suspend fun setShowMemories(enabled: Boolean) = edit { it[Keys.SHOW_MEMORIES] = enabled }
    suspend fun setAutoplayVideos(enabled: Boolean) = edit { it[Keys.AUTOPLAY] = enabled }
    suspend fun setMuteVideosByDefault(enabled: Boolean) = edit { it[Keys.MUTE] = enabled }
    suspend fun setStripMetadataOnShare(enabled: Boolean) = edit { it[Keys.STRIP_METADATA] = enabled }
    suspend fun setAnalysisEnabled(enabled: Boolean) = edit { it[Keys.ANALYSIS] = enabled }
    suspend fun setAnalysisOnlyWhileCharging(enabled: Boolean) = edit { it[Keys.ANALYSIS_CHARGING] = enabled }
    suspend fun setOnlineMapTiles(enabled: Boolean) = edit { it[Keys.ONLINE_MAP] = enabled }
    suspend fun setSlideshowInterval(seconds: Int) = edit { it[Keys.SLIDESHOW_INTERVAL] = seconds.coerceIn(2, 30) }
    suspend fun setVaultAutoLockSeconds(seconds: Int) = edit { it[Keys.VAULT_AUTO_LOCK] = seconds.coerceAtLeast(0) }
    suspend fun setAutoCheckUpdates(enabled: Boolean) = edit { it[Keys.AUTO_UPDATES] = enabled }
    suspend fun setPeopleSort(sort: PeopleSort) = edit { it[Keys.PEOPLE_SORT] = sort.name }
    suspend fun setFolderHidden(bucketId: Long, hidden: Boolean) = edit { prefs ->
        val current = prefs[Keys.HIDDEN_FOLDERS].orEmpty()
        prefs[Keys.HIDDEN_FOLDERS] = if (hidden) current + bucketId.toString() else current - bucketId.toString()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun toSettings(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = prefs[Keys.THEME].toEnum(defaults.themeMode),
            amoled = prefs[Keys.AMOLED] ?: defaults.amoled,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            colorFromPhoto = prefs[Keys.COLOR_FROM_PHOTO] ?: defaults.colorFromPhoto,
            gridColumns = (prefs[Keys.COLUMNS] ?: defaults.gridColumns)
                .coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS),
            groupMode = prefs[Keys.GROUP_MODE].toEnum(defaults.groupMode),
            showMemories = prefs[Keys.SHOW_MEMORIES] ?: defaults.showMemories,
            autoplayVideos = prefs[Keys.AUTOPLAY] ?: defaults.autoplayVideos,
            muteVideosByDefault = prefs[Keys.MUTE] ?: defaults.muteVideosByDefault,
            stripMetadataOnShare = prefs[Keys.STRIP_METADATA] ?: defaults.stripMetadataOnShare,
            analysisEnabled = prefs[Keys.ANALYSIS] ?: defaults.analysisEnabled,
            analysisOnlyWhileCharging = prefs[Keys.ANALYSIS_CHARGING] ?: defaults.analysisOnlyWhileCharging,
            onlineMapTiles = prefs[Keys.ONLINE_MAP] ?: defaults.onlineMapTiles,
            slideshowIntervalSeconds = prefs[Keys.SLIDESHOW_INTERVAL] ?: defaults.slideshowIntervalSeconds,
            vaultAutoLockSeconds = prefs[Keys.VAULT_AUTO_LOCK] ?: defaults.vaultAutoLockSeconds,
            autoCheckUpdates = prefs[Keys.AUTO_UPDATES] ?: defaults.autoCheckUpdates,
            hiddenFolders = prefs[Keys.HIDDEN_FOLDERS].orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() },
            peopleSort = prefs[Keys.PEOPLE_SORT].toEnum(defaults.peopleSort),
        )
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val COLOR_FROM_PHOTO = booleanPreferencesKey("color_from_photo")
        val COLUMNS = intPreferencesKey("grid_columns")
        val GROUP_MODE = stringPreferencesKey("group_mode")
        val SHOW_MEMORIES = booleanPreferencesKey("show_memories")
        val AUTOPLAY = booleanPreferencesKey("autoplay_videos")
        val MUTE = booleanPreferencesKey("mute_videos")
        val STRIP_METADATA = booleanPreferencesKey("strip_metadata_on_share")
        val ANALYSIS = booleanPreferencesKey("analysis_enabled")
        val ANALYSIS_CHARGING = booleanPreferencesKey("analysis_only_charging")
        val ONLINE_MAP = booleanPreferencesKey("online_map_tiles")
        val SLIDESHOW_INTERVAL = intPreferencesKey("slideshow_interval")
        val VAULT_AUTO_LOCK = intPreferencesKey("vault_auto_lock")
        val AUTO_UPDATES = booleanPreferencesKey("auto_check_updates")
        val HIDDEN_FOLDERS = stringSetPreferencesKey("hidden_folders")
        val PEOPLE_SORT = stringPreferencesKey("people_sort")
    }
}
