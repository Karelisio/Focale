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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

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
    suspend fun setVaultSort(sort: VaultSort) = edit {
        it[Keys.VAULT_SORT] = sort.order.name
        it[Keys.VAULT_SORT_ASCENDING] = sort.ascending
    }
    suspend fun setVaultNewMediaAlerts(enabled: Boolean) = edit { it[Keys.VAULT_NEW_MEDIA_ALERTS] = enabled }
    suspend fun setVaultAlbumSort(sort: VaultAlbumSort) = edit {
        it[Keys.VAULT_ALBUM_SORT] = sort.order.name
        it[Keys.VAULT_ALBUM_SORT_ASCENDING] = sort.ascending
    }
    suspend fun setFolderHidden(bucketId: Long, hidden: Boolean) = edit { prefs ->
        val current = prefs[Keys.HIDDEN_FOLDERS].orEmpty()
        prefs[Keys.HIDDEN_FOLDERS] = if (hidden) current + bucketId.toString() else current - bucketId.toString()
    }

    /** Masque aussi les dossiers [bucketIds] (restauration d'une sauvegarde) ; renvoie le nombre de nouveaux. */
    suspend fun hideFolders(bucketIds: Collection<Long>): Int {
        var added = 0
        edit { prefs ->
            val current = prefs[Keys.HIDDEN_FOLDERS].orEmpty()
            val next = current + bucketIds.map(Long::toString)
            added = next.size - current.size
            prefs[Keys.HIDDEN_FOLDERS] = next
        }
        return added
    }

    // ── Sauvegarde des données ──────────────────────────────────────────────────────

    /**
     * Réglages repris par la sauvegarde des données ([BACKED_UP]) : ceux que l'utilisateur a
     * réglés, par nom de clé (les autres sont à leur valeur par défaut), et le nom de tous ceux de
     * la liste.
     */
    suspend fun backupValues(): Pair<Map<String, JsonPrimitive>, List<String>> {
        val prefs = dataStore.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.first()
        val values = BACKED_UP.mapNotNull { setting -> setting.exported(prefs)?.let { setting.key.name to it } }.toMap()
        return values to BACKED_UP.map { it.key.name }
    }

    /**
     * Remplace les réglages de la liste par ceux d'une sauvegarde : [values] les siens, à sa valeur
     * par défaut chacun de ceux que connaissait la version sauvegardée ([known]) et qui n'y est pas.
     * Un réglage qu'elle ne connaissait pas reste tel quel, une valeur illisible (version ultérieure)
     * aussi ; les clés hors de la liste sont ignorées.
     *
     * @return nombre de réglages changés.
     */
    suspend fun restoreBackup(values: Map<String, JsonPrimitive>, known: Collection<String>): Int {
        var changed = 0
        edit { prefs ->
            for (setting in BACKED_UP) {
                val name = setting.key.name
                if (name !in known) continue
                val value = values[name]
                if (value == null) {
                    if (prefs.contains(setting.key)) changed++
                    prefs.remove(setting.key)
                } else if (setting.imported(prefs, value)) {
                    changed++
                }
            }
        }
        return changed
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
            vaultSort = VaultSort(
                order = prefs[Keys.VAULT_SORT].toEnum(defaults.vaultSort.order),
                ascending = prefs[Keys.VAULT_SORT_ASCENDING] ?: defaults.vaultSort.ascending,
            ),
            vaultAlbumSort = VaultAlbumSort(
                order = prefs[Keys.VAULT_ALBUM_SORT].toEnum(defaults.vaultAlbumSort.order),
                ascending = prefs[Keys.VAULT_ALBUM_SORT_ASCENDING] ?: defaults.vaultAlbumSort.ascending,
            ),
            vaultNewMediaAlerts = prefs[Keys.VAULT_NEW_MEDIA_ALERTS] ?: defaults.vaultNewMediaAlerts,
        )
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    /**
     * Réglage repris par la sauvegarde des données : sa clé, et la lecture d'une valeur sauvegardée,
     * null si elle n'est pas valable (type inattendu, nom d'une version ultérieure…).
     */
    internal class BackedUp<T : Any>(
        val key: Preferences.Key<T>,
        private val read: (JsonPrimitive) -> T?,
        private val write: (T) -> JsonPrimitive,
    ) {
        fun exported(prefs: Preferences): JsonPrimitive? = prefs[key]?.let(write)

        /** Écrit la valeur sauvegardée [value] si elle est valable ; vrai si le réglage a changé. */
        fun imported(prefs: androidx.datastore.preferences.core.MutablePreferences, value: JsonPrimitive): Boolean {
            val parsed = read(value) ?: return false
            if (prefs[key] == parsed) return false
            prefs[key] = parsed
            return true
        }
    }

    internal companion object {
        /**
         * Liste blanche explicite des réglages sauvegardés : les choix de l'utilisateur. Une clé
         * ajoutée aux réglages n'est sauvegardée qu'une fois inscrite ici ; sinon, dans
         * [NOT_BACKED_UP] (vérifié par les tests). L'état propre à l'appareil ou passager n'est pas
         * dans ce DataStore (autorisations, application par défaut : le système ; mises à jour
         * téléchargées, nouveautés vues, synchronisation : `StateStore`). La langue de
         * l'application non plus : le système la garde (AppCompat avant Android 13, `AppLocales`),
         * et la sauvegarde ne la reprend pas.
         */
        internal val BACKED_UP: List<BackedUp<*>> = listOf(
            choice<ThemeMode>(Keys.THEME),
            flag(Keys.AMOLED),
            flag(Keys.DYNAMIC_COLOR),
            flag(Keys.COLOR_FROM_PHOTO),
            number(Keys.COLUMNS, AppSettings.MIN_COLUMNS..AppSettings.MAX_COLUMNS),
            choice<GroupMode>(Keys.GROUP_MODE),
            flag(Keys.SHOW_MEMORIES),
            flag(Keys.AUTOPLAY),
            flag(Keys.MUTE),
            flag(Keys.STRIP_METADATA),
            flag(Keys.ANALYSIS),
            flag(Keys.ANALYSIS_CHARGING),
            flag(Keys.ONLINE_MAP),
            number(Keys.SLIDESHOW_INTERVAL, 2..30),
            number(Keys.VAULT_AUTO_LOCK, 0..Int.MAX_VALUE),
            flag(Keys.AUTO_UPDATES),
            choice<PeopleSort>(Keys.PEOPLE_SORT),
            choice<VaultItemOrder>(Keys.VAULT_SORT),
            flag(Keys.VAULT_SORT_ASCENDING),
            choice<VaultAlbumOrder>(Keys.VAULT_ALBUM_SORT),
            flag(Keys.VAULT_ALBUM_SORT_ASCENDING),
            flag(Keys.VAULT_NEW_MEDIA_ALERTS),
        )

        /**
         * Clés écartées de la liste blanche, avec leur raison : les dossiers masqués désignent des
         * dossiers de cet appareil (`BUCKET_ID`), sauvegardés à part avec leur chemin et réunis à
         * ceux du téléphone qui restaure.
         */
        internal val NOT_BACKED_UP: Set<Preferences.Key<*>> = setOf(Keys.HIDDEN_FOLDERS)

        private fun flag(key: Preferences.Key<Boolean>) =
            BackedUp(key, { value -> value.booleanOrNull.takeUnless { value.isString } }, ::JsonPrimitive)

        private fun number(key: Preferences.Key<Int>, range: IntRange) =
            BackedUp(key, { value -> value.intOrNull.takeUnless { value.isString }?.coerceIn(range) }, ::JsonPrimitive)

        private inline fun <reified E : Enum<E>> choice(key: Preferences.Key<String>) = BackedUp(
            key,
            { value -> value.content.takeIf { value.isString && enumValues<E>().any { it.name == value.content } } },
            ::JsonPrimitive,
        )
    }

    internal object Keys {
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
        val VAULT_SORT = stringPreferencesKey("vault_sort")
        val VAULT_SORT_ASCENDING = booleanPreferencesKey("vault_sort_ascending")
        val VAULT_ALBUM_SORT = stringPreferencesKey("vault_album_sort")
        val VAULT_ALBUM_SORT_ASCENDING = booleanPreferencesKey("vault_album_sort_ascending")
        val VAULT_NEW_MEDIA_ALERTS = booleanPreferencesKey("vault_new_media_alerts")
    }
}
